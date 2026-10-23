use crate::pack::actor::{
    Actor as JobActor, Context as JobContext, Input as JobInput, Outcome as JobOutcome,
    Startup as JobStartup, Status as JobStatus,
};
use std::{
    sync::{mpsc, Mutex},
    time::{Duration, Instant},
};
struct Pause {
    at: &'static str,
    entered: mpsc::Sender<()>,
    go: mpsc::Receiver<()>,
}
struct Backend {
    store: Store,
    root: Arc<Dir>,
    new: VerifiedRelease,
    old: Option<VerifiedRelease>,
    objects: BTreeMap<String, Vec<u8>>,
    approval: Option<Approval>,
    pause: Option<Pause>,
}
fn settled(actor: &JobActor) -> crate::pack::actor::Snapshot {
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        let view = actor.snapshot().unwrap();
        if !view.busy {
            return view;
        }
        assert!(Instant::now() < deadline, "actor fixture timed out");
        std::thread::sleep(Duration::from_millis(5));
    }
}
fn paused(at: &'static str) -> (Pause, mpsc::Receiver<()>, mpsc::Sender<()>) {
    let (entered, receiver) = mpsc::channel();
    let (sender, go) = mpsc::channel();
    (Pause { at, entered, go }, receiver, sender)
}
impl Backend {
    fn run(&mut self, input: JobInput, ctx: JobContext) -> JobOutcome {
        let cancel = Cancel::linked(ctx.cancel_signal());
        if matches!(input, JobInput::Install {} | JobInput::Repair {}) {
            let mode = if matches!(input, JobInput::Repair {}) {
                Mode::Repair
            } else {
                Mode::Update
            };
            let old = if mode == Mode::Repair {
                Some(&self.new)
            } else {
                self.old.as_ref()
            };
            return match self.store.preview(&self.new, old, mode, &cancel) {
                Ok(approval) => {
                    let summary = approval.summary.clone();
                    self.approval = Some(approval);
                    JobOutcome::Plan(summary)
                }
                Err("CANCELLED") => JobOutcome::Cancelled,
                Err(_) => JobOutcome::RecoveryRequired,
            };
        }
        if let Some(pause) = &self.pause {
            if pause.at == "before-work" {
                pause.entered.send(()).unwrap();
                pause.go.recv_timeout(Duration::from_secs(10)).unwrap();
            }
        }
        runtime().block_on(async {
            let recovery = matches!(input, JobInput::Recover {});
            let total = self.new.manifest().manifest().files.len() as u32;
            let ready = if recovery {
                let n = self.store.head().unwrap().pending.unwrap();
                self.store.load(n).unwrap().marked("prepared")
            } else {
                false
            };
            let mut applying = false;
            let mut pause = self.pause.take();
            let mut hook = |phase: &str| {
                if !applying && (phase == "prepared" || phase.starts_with("intent:")) {
                    if total > 0 {
                        ctx.progress(JobStatus::Applying, 0, total).unwrap();
                    }
                    applying = true;
                }
                if let Some(value) = &pause {
                    if phase == value.at {
                        value.entered.send(()).unwrap();
                        value.go.recv_timeout(Duration::from_secs(10)).unwrap();
                        pause = None;
                    }
                }
            };
            let result = if recovery && ready {
                self.store
                    .recover(&self.new, self.old.as_ref(), &cancel, &mut hook)
            } else {
                if !recovery && total > 0 {
                    ctx.progress(JobStatus::Downloading, 0, total).unwrap();
                }
                let data = download::fixture_prepared_controlled(
                    &self
                        .root
                        .child(
                            &format!("actor-download-{}", uuid::Uuid::new_v4().simple()),
                            true,
                        )
                        .unwrap(),
                    &self.new,
                    self.objects.clone(),
                    &cancel,
                )
                .await;
                let mut data = match data {
                    Ok(value) => value,
                    Err("CANCELLED") if !recovery => return JobOutcome::Cancelled,
                    Err(_) => return JobOutcome::RecoveryRequired,
                };
                if !recovery && total > 0 {
                    ctx.progress(JobStatus::Preparing, 0, total).unwrap();
                }
                if recovery {
                    self.store.resume_preparation(
                        &self.new,
                        self.old.as_ref(),
                        &mut data,
                        &cancel,
                        &mut hook,
                    )
                } else {
                    let approval = self.approval.take().unwrap();
                    let old = if approval.mode == Mode::Repair {
                        Some(&self.new)
                    } else {
                        self.old.as_ref()
                    };
                    self.store
                        .begin_confirmed(approval, &self.new, old, &mut data, &cancel, &mut hook)
                }
            };
            match result {
                Ok(Outcome::Committed { .. }) => {
                    assert!(matches!(
                        self.store.startup(&self.new, Some(&self.new)).unwrap(),
                        JobStartup::Ready
                    ));
                    JobOutcome::Completed
                }
                Ok(Outcome::Cancelled) => {
                    assert!(matches!(
                        self.store.startup(&self.new, self.old.as_ref()).unwrap(),
                        JobStartup::Ready
                    ));
                    if recovery {
                        JobOutcome::RecoveredCancellation
                    } else {
                        JobOutcome::Cancelled
                    }
                }
                Err("CANCELLED") if !recovery => JobOutcome::Cancelled,
                Err("INSTALL_PLAN_CHANGED") => JobOutcome::PlanChanged,
                Err(_) => JobOutcome::RecoveryRequired,
            }
        })
    }
}
fn actor_for(backend: Backend) -> (JobActor, Arc<Mutex<Backend>>) {
    let startup = backend
        .store
        .startup(&backend.new, backend.old.as_ref())
        .unwrap_or(JobStartup::Blocked);
    let state = Arc::new(Mutex::new(backend));
    let worker = state.clone();
    (
        JobActor::fixture_startup(startup, move |input, ctx| {
            worker.lock().unwrap().run(input, ctx)
        }),
        state,
    )
}
fn objects(new: &VerifiedRelease, bytes: &[u8]) -> BTreeMap<String, Vec<u8>> {
    BTreeMap::from([(
        new.manifest().manifest().files[0]
            .source_ref
            .object_key
            .clone(),
        bytes.to_vec(),
    )])
}
#[test]
fn actor_worker_previews_then_confirms_real_signed_journal_preserving_userdata() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let new = release(
            &files.root,
            &p,
            &document(1, &[("mods/actor.jar", b"signed fixture bytes")]),
        )
        .await;
        let store = Store::create(&files.root, &p.bootstrap).unwrap();
        let userdata = store.instance.dir.child("saves", true).unwrap();
        put(&userdata, "world.dat", b"actor world").unwrap();
        let backend = Backend {
            store,
            root: files.root.clone(),
            objects: objects(&new, b"signed fixture bytes"),
            new,
            old: None,
            approval: None,
            pause: None,
        };
        let (actor, state) = actor_for(backend);
        actor.action(JobInput::Install {}).unwrap();
        let plan = settled(&actor).plan.unwrap();
        assert_eq!(plan.summary.install, 1);
        assert_eq!(plan.summary.staging_bytes, 20);
        {
            let state = state.lock().unwrap();
            assert_eq!(state.store.head().unwrap().last, 0);
            assert!(managed_file(&state.store.instance.dir, "mods/actor.jar")
                .unwrap()
                .is_none());
        }
        assert_eq!(
            actor.action(JobInput::Check {}),
            Err("PLAN_CONFIRMATION_REQUIRED")
        );
        actor
            .action(JobInput::Confirm {
                plan_id: plan.plan_id,
            })
            .unwrap();
        assert_eq!(settled(&actor).status, JobStatus::Completed);
        assert_eq!(
            actor.action(JobInput::Confirm {
                plan_id: plan.plan_id
            }),
            Err("STALE_PLAN")
        );
        assert_eq!(
            read(&state.lock().unwrap().store.instance.dir, "mods/actor.jar"),
            b"signed fixture bytes"
        );
        assert_eq!(get(&userdata, "world.dat", 128).unwrap(), b"actor world");
    });
}
#[test]
fn actor_startup_after_process_exit_recovers_early_apply_and_index_states() {
    runtime().block_on(async {
        for phase in [
            "stage-created:00000:000",
            "prepared",
            "after-backup:00000",
            "index-ready",
        ] {
            let (files, p, old, new) = crash_case(phase).await;
            assert!(matches!(
                startup::inspected_startup(&files.root, &p.bootstrap, &new, Some(&old)),
                JobStartup::RecoveryRequired
            ));
            let store = Store::reopen(&files.root, &p.bootstrap).unwrap();
            let userdata = store.instance.dir.child("saves", true).unwrap();
            put(&userdata, "world.dat", b"restart world").unwrap();
            let (actor, state) = actor_for(Backend {
                store,
                root: files.root.clone(),
                objects: objects(&new, b"new"),
                new,
                old: Some(old),
                approval: None,
                pause: None,
            });
            assert_eq!(
                actor.snapshot().unwrap().status,
                JobStatus::RecoveryRequired
            );
            assert_eq!(actor.action(JobInput::Install {}), Err("RECOVERY_REQUIRED"));
            actor.action(JobInput::Recover {}).unwrap();
            assert_eq!(settled(&actor).status, JobStatus::Completed);
            assert_eq!(
                read(&state.lock().unwrap().store.instance.dir, "mods/a.jar"),
                b"new"
            );
            assert_eq!(get(&userdata, "world.dat", 128).unwrap(), b"restart world");
        }
    });
}
#[test]
fn actor_recovery_cancel_proves_clean_old_index_or_finishes_new_commit() {
    runtime().block_on(async {
        for at in ["before-work", "after-backup:00000"] {
            let (files, p, old, new) = crash_case("prepared").await;
            let store = Store::reopen(&files.root, &p.bootstrap).unwrap();
            let (pause, entered, go) = paused(at);
            let (actor, state) = actor_for(Backend {
                store,
                root: files.root.clone(),
                objects: objects(&new, b"new"),
                new,
                old: Some(old),
                approval: None,
                pause: Some(pause),
            });
            let job = actor.action(JobInput::Recover {}).unwrap();
            entered.recv_timeout(Duration::from_secs(10)).unwrap();
            assert_eq!(
                actor.action(JobInput::Cancel {
                    session_id: crate::pack::actor::Id::new(),
                    job_id: job.job_id
                }),
                Err("STALE_JOB")
            );
            actor
                .action(JobInput::Cancel {
                    session_id: job.session_id,
                    job_id: job.job_id,
                })
                .unwrap();
            go.send(()).unwrap();
            let result = settled(&actor);
            assert!(result.cancel_requested);
            assert_eq!(
                result.status,
                if at == "before-work" {
                    JobStatus::Cancelled
                } else {
                    JobStatus::Completed
                }
            );
            let state = state.lock().unwrap();
            assert!(state.store.head().unwrap().pending.is_none());
            assert_eq!(
                read(&state.store.instance.dir, "mods/a.jar"),
                if at == "before-work" { b"old" } else { b"new" }
            );
        }
    });
}
#[test]
fn startup_corrupt_or_resealed_untrusted_journal_is_blocked_without_mutation() {
    runtime().block_on(async {
        for kind in ["partial", "reconstructed"] {
            let (files, p, old, new) = crash_case("prepared").await;
            let store = Store::reopen(&files.root, &p.bootstrap).unwrap();
            let mut tx = store.load(2).unwrap();
            let managed_path = path(&store.instance.dir).join("mods/a.jar");
            let userdata = store.instance.dir.child("saves", true).unwrap();
            put(&userdata, "world.dat", b"corruption preserved world").unwrap();
            let world_path = path(&userdata).join("world.dat");
            drop(userdata);
            if kind == "partial" {
                std::fs::write(path(&tx.journal).join("prepared.json"), b"{").unwrap();
            } else {
                tx.plan.operations[0].path = "saves/world.dat".into();
                let bytes = encoded(&tx.plan).unwrap();
                let hash = manifest::hash(&bytes);
                std::fs::write(path(&tx.journal).join("plan.json"), bytes).unwrap();
                for name in &tx.names {
                    if name != "plan.json" && name != "stage-choice.json" {
                        std::fs::write(
                            path(&tx.journal).join(name),
                            phase_bytes(&hash, name.strip_suffix(".json").unwrap()).unwrap(),
                        )
                        .unwrap();
                    }
                }
                let mut choice: serde_json::Value =
                    serde_json::from_slice(&encoded(&tx.choice.take().unwrap()).unwrap()).unwrap();
                choice["plan"] = hash.into();
                std::fs::write(
                    path(&tx.journal).join("stage-choice.json"),
                    encoded(&choice).unwrap(),
                )
                .unwrap();
            }
            drop(tx);
            drop(store);
            let state = startup::inspected_startup(&files.root, &p.bootstrap, &new, Some(&old));
            assert!(matches!(state, JobStartup::Blocked));
            let actor = JobActor::fixture_startup(state, |_, _| panic!("blocked worker invoked"));
            assert_eq!(actor.snapshot().unwrap().status, JobStatus::Blocked);
            for input in [
                JobInput::Check {},
                JobInput::Install {},
                JobInput::Repair {},
                JobInput::Recover {},
            ] {
                assert_eq!(actor.action(input), Err("STORE_INVALID"));
            }
            assert_eq!(std::fs::read(&managed_path).unwrap(), b"old");
            assert_eq!(
                std::fs::read(&world_path).unwrap(),
                b"corruption preserved world"
            );
        }
    });
}
#[test]
fn confirmed_repair_refuses_changed_inventory_before_any_journal_write() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let new = release(&files.root, &p, &document(1, &[("mods/a.jar", b"trusted")])).await;
        let mut store = Store::create(&files.root, &p.bootstrap).unwrap();
        let mut data = prepared(
            &files.root,
            &new,
            &[("mods/a.jar", b"trusted")],
            "seed-download",
        )
        .await;
        store
            .begin(&new, None, &mut data, &Cancel::new(), &mut |_| {})
            .unwrap();
        drop(data);
        let target = path(&store.instance.dir).join("mods/a.jar");
        std::fs::write(&target, b"first modification").unwrap();
        let approval = store
            .preview(&new, Some(&new), Mode::Repair, &Cancel::new())
            .unwrap();
        assert_eq!(approval.summary.replace, 1);
        std::fs::write(&target, b"second modification").unwrap();
        let mut data = prepared(
            &files.root,
            &new,
            &[("mods/a.jar", b"trusted")],
            "repair-download",
        )
        .await;
        fail(
            store.begin_confirmed(
                approval,
                &new,
                Some(&new),
                &mut data,
                &Cancel::new(),
                &mut |_| {},
            ),
            "INSTALL_PLAN_CHANGED",
        );
        assert_eq!(store.head().unwrap().last, 1);
        assert_eq!(
            read(&store.instance.dir, "mods/a.jar"),
            b"second modification"
        );
        let approval = store
            .preview(&new, Some(&new), Mode::Repair, &Cancel::new())
            .unwrap();
        store
            .begin_confirmed(
                approval,
                &new,
                Some(&new),
                &mut data,
                &Cancel::new(),
                &mut |_| {},
            )
            .unwrap();
        assert_eq!(read(&store.instance.dir, "mods/a.jar"), b"trusted");
        assert_eq!(
            get(&store.load(2).unwrap().backup, "00000.old", 128).unwrap(),
            b"second modification"
        );
    });
}
