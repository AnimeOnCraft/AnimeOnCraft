use super::super::tests::{self as fixture, *};
use super::*;
use crate::pack::{
    ntfs::tests::Fixture,
    tests::{self as publisher, runtime},
};
use std::{
    sync::mpsc,
    time::{Duration, Instant},
};
mod development;
mod online;

fn backend(engine: Engine, p: &publisher::Fixture, sequence: u64, bytes: &[u8]) -> Backend {
    Backend {
        engine,
        local: None,
        bootstrap: p.bootstrap.clone(),
        policy: publisher::policy(sequence),
        prior_policy: (sequence > 1).then(|| publisher::policy(sequence - 1)),
        source: download::Source::Fixture(objects(bytes)),
        repository: None,
        approval: None,
        hook: Box::new(|_| {}),
    }
}
fn settled(actor: &Actor) -> crate::pack::actor::Snapshot {
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        let view = actor.snapshot().unwrap();
        if !view.busy {
            return view;
        }
        assert!(Instant::now() < deadline, "engine actor timed out");
        std::thread::sleep(Duration::from_millis(5));
    }
}
fn preview(actor: &Actor, input: crate::pack::actor::Input) -> crate::pack::actor::PlanView {
    actor.action(input).unwrap();
    let view = settled(actor);
    assert_eq!(view.status, Status::AwaitingConfirmation);
    view.plan.unwrap()
}
fn confirm(actor: &Actor, plan: crate::pack::actor::PlanView) -> crate::pack::actor::Snapshot {
    actor
        .action(crate::pack::actor::Input::Confirm {
            plan_id: plan.plan_id,
        })
        .unwrap();
    settled(actor)
}
fn pause(backend: &mut Backend, at: &'static str) -> (mpsc::Receiver<()>, mpsc::Sender<()>) {
    let (entered, receiver) = mpsc::channel();
    let (sender, go) = mpsc::channel();
    let mut once = false;
    backend.hook = Box::new(move |phase| {
        if phase == at && !once {
            once = true;
            entered.send(()).unwrap();
            go.recv_timeout(Duration::from_secs(20)).unwrap();
        }
    });
    (receiver, sender)
}
#[test]
fn persistent_actor_installs_reopens_updates_repairs_and_preserves_world() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        fixture::input(&files, &p).await;
        let mut engine = open(&files, &p.bootstrap);
        drop(refresh(&mut engine, &p, 1, b"first").await);
        std::fs::create_dir(game(&files).join("saves")).unwrap();
        std::fs::write(game(&files).join("saves/world.dat"), b"actor world").unwrap();
        let before = tree(&path(&files));
        let (actor, weak) = attach(backend(engine, &p, 1, b"first")).unwrap();
        let plan = preview(&actor, crate::pack::actor::Input::Install {});
        assert_eq!(plan.summary.install, 1);
        assert_eq!(tree(&path(&files)), before);
        assert_eq!(
            actor.action(crate::pack::actor::Input::Check {}),
            Err("PLAN_CONFIRMATION_REQUIRED")
        );
        let id = plan.plan_id;
        assert_eq!(confirm(&actor, plan).status, Status::Completed);
        assert_eq!(
            actor.action(crate::pack::actor::Input::Confirm { plan_id: id }),
            Err("STALE_PLAN")
        );
        assert_eq!(fixture::child(&files, "probe").status.code(), Some(11));
        drop(actor);
        assert!(weak.upgrade().is_none());
        let mut engine = open(&files, &p.bootstrap);
        drop(refresh(&mut engine, &p, 2, b"second").await);
        let (actor, weak) = attach(backend(engine, &p, 2, b"second")).unwrap();
        let plan = preview(&actor, crate::pack::actor::Input::Install {});
        assert_eq!(plan.summary.replace, 1);
        assert_eq!(confirm(&actor, plan).status, Status::Completed);
        // This must reconcile again: the formerly historical index is now current.
        let plan = preview(&actor, crate::pack::actor::Input::Repair {});
        assert_eq!(plan.summary.reuse, 1);
        actor
            .action(crate::pack::actor::Input::Discard {
                plan_id: plan.plan_id,
            })
            .unwrap();
        std::fs::write(game(&files).join("mods/fixture.jar"), b"edited").unwrap();
        let plan = preview(&actor, crate::pack::actor::Input::Repair {});
        assert_eq!(plan.summary.replace, 1);
        assert_eq!(confirm(&actor, plan).status, Status::Completed);
        assert_eq!(
            std::fs::read(game(&files).join("mods/fixture.jar")).unwrap(),
            b"second"
        );
        assert_eq!(
            std::fs::read(game(&files).join("saves/world.dat")).unwrap(),
            b"actor world"
        );
        drop(actor);
        assert!(weak.upgrade().is_none());
        assert_eq!(fixture::child(&files, "probe").status.code(), Some(0));
    });
}
#[test]
fn worker_owns_lease_after_ui_drop_until_async_job_finishes() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        fixture::input(&files, &p).await;
        let mut engine = open(&files, &p.bootstrap);
        drop(refresh(&mut engine, &p, 1, b"payload").await);
        let mut backend = backend(engine, &p, 1, b"payload");
        let (entered, go) = pause(&mut backend, "downloading");
        let (actor, weak) = attach(backend).unwrap();
        let plan = preview(&actor, crate::pack::actor::Input::Install {});
        actor
            .action(crate::pack::actor::Input::Confirm {
                plan_id: plan.plan_id,
            })
            .unwrap();
        entered.recv_timeout(Duration::from_secs(20)).unwrap();
        assert_eq!(actor.snapshot().unwrap().status, Status::Downloading);
        assert_eq!(
            actor.action(crate::pack::actor::Input::Check {}),
            Err("INSTALLER_BUSY")
        );
        drop(actor);
        assert!(weak.upgrade().is_some());
        assert_eq!(fixture::child(&files, "probe").status.code(), Some(11));
        go.send(()).unwrap();
        let deadline = Instant::now() + Duration::from_secs(30);
        while weak.upgrade().is_some() {
            assert!(Instant::now() < deadline);
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(fixture::child(&files, "probe").status.code(), Some(0));
        assert_eq!(
            std::fs::read(game(&files).join("mods/fixture.jar")).unwrap(),
            b"payload"
        );
    });
}
#[test]
fn cancellation_distinguishes_before_download_partial_stage_and_late_commit() {
    runtime().block_on(async {
        for (at, expected) in [
            ("downloading", Status::Cancelled),
            ("stage-created:00000:000", Status::Cancelled),
            ("prepared", Status::Cancelled),
            ("intent:00000", Status::Completed),
        ] {
            let files = Fixture::new();
            let p = publisher::Fixture::new().await;
            let mut engine = open(&files, &p.bootstrap);
            drop(refresh(&mut engine, &p, 1, b"payload").await);
            let mut backend = backend(engine, &p, 1, b"payload");
            let (entered, go) = pause(&mut backend, at);
            let (actor, _) = attach(backend).unwrap();
            let plan = preview(&actor, crate::pack::actor::Input::Install {});
            let job = actor
                .action(crate::pack::actor::Input::Confirm {
                    plan_id: plan.plan_id,
                })
                .unwrap();
            entered.recv_timeout(Duration::from_secs(20)).unwrap();
            assert_eq!(
                actor.action(crate::pack::actor::Input::Cancel {
                    session_id: crate::pack::actor::Id::new(),
                    job_id: job.job_id,
                }),
                Err("STALE_JOB")
            );
            actor
                .action(crate::pack::actor::Input::Cancel {
                    session_id: job.session_id,
                    job_id: job.job_id,
                })
                .unwrap();
            go.send(()).unwrap();
            assert_eq!(settled(&actor).status, expected, "{at}");
            if at == "downloading" {
                assert!(!game(&files).join("mods/fixture.jar").exists());
                assert!(!path(&files)
                    .join("install/install-control/transactions/tx-00001")
                    .exists());
            } else if expected == Status::Cancelled {
                assert!(!game(&files).join("mods/fixture.jar").exists());
                assert!(path(&files)
                    .join("install/install-control/transactions/tx-00001/journal/cancelled.json")
                    .exists());
            }
        }
    });
}
#[test]
fn recovery_cancel_clears_dirty_gate_only_after_persisted_clean_state() {
    runtime().block_on(async {
        for (crash, at, expected) in [
            ("stage-created", "downloading", Status::RecoveryRequired),
            ("stage-created", "downloaded", Status::Cancelled),
            ("prepared", "before-work", Status::Cancelled),
        ] {
            let files = Fixture::new();
            let p = publisher::Fixture::new().await;
            fixture::input(&files, &p).await;
            drop(open(&files, &p.bootstrap));
            assert_eq!(
                fixture::child(&files, &format!("install:{crash}"))
                    .status
                    .code(),
                Some(37)
            );
            let mut backend = backend(open(&files, &p.bootstrap), &p, 1, b"engine payload");
            let (entered, go) = pause(&mut backend, at);
            let (actor, _) = attach(backend).unwrap();
            let job = actor.action(crate::pack::actor::Input::Recover {}).unwrap();
            entered.recv_timeout(Duration::from_secs(20)).unwrap();
            actor
                .action(crate::pack::actor::Input::Cancel {
                    session_id: job.session_id,
                    job_id: job.job_id,
                })
                .unwrap();
            go.send(()).unwrap();
            assert_eq!(settled(&actor).status, expected, "{crash}/{at}");
            assert!(!game(&files).join("mods/fixture.jar").exists());
            if expected == Status::RecoveryRequired {
                assert_eq!(
                    actor.action(crate::pack::actor::Input::Install {}),
                    Err("RECOVERY_REQUIRED")
                );
                actor.action(crate::pack::actor::Input::Recover {}).unwrap();
                assert_eq!(settled(&actor).status, Status::Completed);
            } else {
                assert_eq!(
                    actor.action(crate::pack::actor::Input::Recover {}),
                    Err("RECOVERY_NOT_REQUIRED")
                );
                let plan = preview(&actor, crate::pack::actor::Input::Install {});
                assert_eq!(confirm(&actor, plan).status, Status::Completed);
            }
        }
    });
}
#[test]
fn poisoned_worker_keeps_lease_and_new_actor_recovers_persisted_transaction() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        fixture::input(&files, &p).await;
        let mut engine = open(&files, &p.bootstrap);
        drop(refresh(&mut engine, &p, 1, b"payload").await);
        let mut backend = backend(engine, &p, 1, b"payload");
        backend.hook = Box::new(|phase| {
            if phase == "prepared" {
                panic!("isolated engine worker fault");
            }
        });
        let (actor, weak) = attach(backend).unwrap();
        let plan = preview(&actor, crate::pack::actor::Input::Install {});
        assert_eq!(confirm(&actor, plan).status, Status::RecoveryRequired);
        assert_eq!(fixture::child(&files, "probe").status.code(), Some(11));
        actor.action(crate::pack::actor::Input::Recover {}).unwrap();
        assert_eq!(settled(&actor).status, Status::Blocked);
        let before = tree(&path(&files));
        drop(actor);
        assert!(weak.upgrade().is_none());
        let engine = open(&files, &p.bootstrap);
        let (actor, _) = attach(self::backend(engine, &p, 1, b"wrong unused bytes")).unwrap();
        assert_eq!(actor.snapshot().unwrap().status, Status::RecoveryRequired);
        assert_eq!(tree(&path(&files)), before);
        actor.action(crate::pack::actor::Input::Recover {}).unwrap();
        assert_eq!(settled(&actor).status, Status::Completed);
    });
}
#[test]
fn preview_authority_does_not_survive_restart_or_changed_signed_history() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut engine = open(&files, &p.bootstrap);
        drop(refresh(&mut engine, &p, 1, b"payload").await);
        let (actor, _) = attach(backend(engine, &p, 1, b"payload")).unwrap();
        let plan = preview(&actor, crate::pack::actor::Input::Install {});
        let before = tree(&path(&files));
        drop(actor);
        let (actor, _) = attach(backend(open(&files, &p.bootstrap), &p, 1, b"payload")).unwrap();
        assert_eq!(
            actor.action(crate::pack::actor::Input::Confirm {
                plan_id: plan.plan_id
            }),
            Err("STALE_PLAN")
        );
        assert_eq!(tree(&path(&files)), before);
        let plan = preview(&actor, crate::pack::actor::Input::Install {});
        let bundle = path(&files).join("trust/instance/generations/generation-00001/bundle.json");
        std::fs::write(bundle, b"{}").unwrap();
        let before = tree(&path(&files));
        assert_eq!(confirm(&actor, plan).status, Status::Blocked);
        assert_eq!(tree(&path(&files)), before);
        assert!(!path(&files)
            .join("install/install-control/transactions/tx-00001")
            .exists());
    });
}
#[test]
fn progress_counts_actual_removal_operations_with_zero_download_objects() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut engine = open(&files, &p.bootstrap);
        let mut old_doc = fixture::doc(1, b"payload");
        let mut extra = old_doc.files[0].clone();
        extra.path = "mods/extra.jar".into();
        old_doc.files.push(extra);
        let old = engine
            .refresh(
                &p.bootstrap,
                p.published_manifest(&old_doc, 1, false).await,
                &publisher::policy(1),
            )
            .await
            .unwrap();
        install(&mut engine, &p, &old, None, Mode::Update, b"payload").await;
        drop(old);
        let mut new_doc = fixture::doc(2, b"unused server payload");
        new_doc.files[0].side = manifest::Side::SERVER;
        drop(
            engine
                .refresh(
                    &p.bootstrap,
                    p.published_manifest(&new_doc, 2, false).await,
                    &publisher::policy(2),
                )
                .await
                .unwrap(),
        );
        let mut backend = backend(engine, &p, 2, b"");
        let (entered, go) = pause(&mut backend, "done:00001");
        let (actor, _) = attach(backend).unwrap();
        let plan = preview(&actor, crate::pack::actor::Input::Install {});
        assert_eq!(plan.summary.remove, 2);
        assert_eq!(plan.summary.staging_bytes, 0);
        actor
            .action(crate::pack::actor::Input::Confirm {
                plan_id: plan.plan_id,
            })
            .unwrap();
        entered.recv_timeout(Duration::from_secs(20)).unwrap();
        let view = serde_json::to_value(actor.snapshot().unwrap()).unwrap();
        assert_eq!(view["status"], "applying");
        assert_eq!(view["progress"]["completed"], 2);
        assert_eq!(view["progress"]["total"], 2);
        go.send(()).unwrap();
        assert_eq!(settled(&actor).status, Status::Completed);
        assert!(!game(&files).join("mods/fixture.jar").exists());
        assert!(!game(&files).join("mods/extra.jar").exists());
    });
}
#[test]
fn actor_recovers_real_process_exit_without_network_or_old_live_proofs() {
    runtime().block_on(async {
        for phase in [
            "stage-created",
            "prepared",
            "after-install:00000",
            "index-ready",
        ] {
            let files = Fixture::new();
            let p = publisher::Fixture::new().await;
            fixture::input(&files, &p).await;
            drop(open(&files, &p.bootstrap));
            assert_eq!(
                fixture::child(&files, &format!("install:{phase}"))
                    .status
                    .code(),
                Some(37)
            );
            let before = tree(&path(&files));
            let engine = open(&files, &p.bootstrap);
            let bytes = if phase == "stage-created" {
                b"engine payload".as_slice()
            } else {
                b"".as_slice()
            };
            let (actor, _) = attach(backend(engine, &p, 1, bytes)).unwrap();
            assert_eq!(actor.snapshot().unwrap().status, Status::RecoveryRequired);
            assert_eq!(tree(&path(&files)), before);
            assert_eq!(
                actor.action(crate::pack::actor::Input::Install {}),
                Err("RECOVERY_REQUIRED")
            );
            actor.action(crate::pack::actor::Input::Recover {}).unwrap();
            assert_eq!(settled(&actor).status, Status::Completed, "{phase}");
            assert_eq!(
                std::fs::read(game(&files).join("mods/fixture.jar")).unwrap(),
                b"engine payload"
            );
        }
    });
}
#[test]
fn changed_preview_is_refused_before_cache_or_journal_and_repaired_by_fresh_plan() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut engine = open(&files, &p.bootstrap);
        let new = refresh(&mut engine, &p, 1, b"payload").await;
        install(&mut engine, &p, &new, None, Mode::Update, b"payload").await;
        drop(new);
        std::fs::write(game(&files).join("mods/fixture.jar"), b"edit one").unwrap();
        let (actor, _) = attach(backend(engine, &p, 1, b"payload")).unwrap();
        let plan = preview(&actor, crate::pack::actor::Input::Repair {});
        std::fs::write(game(&files).join("mods/fixture.jar"), b"edit two").unwrap();
        let before = tree(&path(&files));
        assert_eq!(confirm(&actor, plan).status, Status::PlanChanged);
        assert_eq!(tree(&path(&files)), before);
        let plan = preview(&actor, crate::pack::actor::Input::Repair {});
        assert_eq!(confirm(&actor, plan).status, Status::Completed);
        assert_eq!(
            std::fs::read(
                path(&files).join("install/install-control/transactions/tx-00002/backup/00000.old")
            )
            .unwrap(),
            b"edit two"
        );
    });
}
#[test]
fn invalid_startup_and_error_postvalidation_block_and_keep_lease() {
    runtime().block_on(async {
        for at_start in [true, false] {
            let files = Fixture::new();
            let p = publisher::Fixture::new().await;
            fixture::input(&files, &p).await;
            let mut engine = open(&files, &p.bootstrap);
            drop(refresh(&mut engine, &p, 1, b"payload").await);
            if at_start {
                std::fs::write(path(&files).join("unexpected"), b"preserve").unwrap();
            }
            let mut backend = backend(engine, &p, 1, b"wrong hash bytes");
            let corrupt = path(&files).join("cache/unexpected");
            if !at_start {
                backend.hook = Box::new(move |at| {
                    if at == "downloading" {
                        std::fs::write(&corrupt, b"preserve").unwrap();
                    }
                });
            }
            let (actor, _) = attach(backend).unwrap();
            if !at_start {
                let plan = preview(&actor, crate::pack::actor::Input::Install {});
                assert_eq!(confirm(&actor, plan).status, Status::Blocked);
            }
            assert_eq!(actor.snapshot().unwrap().status, Status::Blocked);
            assert_eq!(
                actor.action(crate::pack::actor::Input::Check {}),
                Err("STORE_INVALID")
            );
            assert_eq!(fixture::child(&files, "probe").status.code(), Some(11));
            assert!(!game(&files).join("mods/fixture.jar").exists());
            drop(actor);
            assert_eq!(fixture::child(&files, "probe").status.code(), Some(12));
        }
    });
}
