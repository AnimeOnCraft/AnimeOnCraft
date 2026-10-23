use super::*;
use crate::pack::repository::Repository;
use tough::{Transport, TransportError, TransportStream};
#[derive(Clone, Debug)]
struct Prefix {
    inner: publisher::Memory,
}
#[async_trait::async_trait]
impl Transport for Prefix {
    async fn fetch(
        &self,
        mut url: reqwest::Url,
    ) -> std::result::Result<TransportStream, TransportError> {
        let path = url.path().strip_prefix("/aoc/v1").unwrap().to_owned();
        url.set_path(&path);
        url.set_host(Some("fixtures.animeoncraft.invalid")).unwrap();
        self.inner.fetch(url).await
    }
}
#[derive(Clone, Debug)]
struct Slow {
    inner: publisher::Memory,
    entered: mpsc::Sender<()>,
}
#[async_trait::async_trait]
impl Transport for Slow {
    async fn fetch(
        &self,
        url: reqwest::Url,
    ) -> std::result::Result<TransportStream, TransportError> {
        if url.path().ends_with(".snapshot.json") {
            self.entered.send(()).unwrap();
            futures::future::pending::<()>().await;
        }
        self.inner.fetch(url).await
    }
}
fn repository(transport: publisher::Memory) -> Repository {
    Repository::fixture(
        "https://fixtures.animeoncraft.invalid/metadata/",
        "https://fixtures.animeoncraft.invalid/targets/",
        transport,
    )
}
#[test]
fn repository_prefix_survives_persisted_evidence_and_readonly_reopen() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let engine = open(&files, &p.bootstrap);
        let mut backend = backend(engine, &p, 1, b"payload");
        backend.repository = Some(Repository::fixture(
            "https://repo.animeoncraft.invalid/aoc/v1/metadata/",
            "https://repo.animeoncraft.invalid/aoc/v1/targets/",
            Prefix {
                inner: p
                    .published_manifest(&fixture::doc(1, b"payload"), 1, false)
                    .await,
            },
        ));
        let (actor, _) = attach(backend).unwrap();
        actor.action(crate::pack::actor::Input::Check {}).unwrap();
        assert_eq!(settled(&actor).status, Status::Completed);
        drop(actor);
        let before = tree(&path(&files));
        let mut engine = open(&files, &p.bootstrap);
        assert!(matches!(
            engine
                .reconcile(&p.bootstrap, &publisher::policy(1), None)
                .unwrap()
                .status,
            Startup::Ready
        ));
        assert_eq!(tree(&path(&files)), before);
    });
}
#[test]
fn cancelling_suspended_metadata_keeps_lease_and_retries_after_restart() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        fixture::input(&files, &p).await;
        let good = p
            .published_manifest(&fixture::doc(1, b"payload"), 1, false)
            .await;
        let (entered, paused) = mpsc::channel();
        let mut backend = backend(open(&files, &p.bootstrap), &p, 1, b"payload");
        backend.repository = Some(Repository::fixture(
            "https://fixtures.animeoncraft.invalid/metadata/",
            "https://fixtures.animeoncraft.invalid/targets/",
            Slow {
                inner: good.clone(),
                entered,
            },
        ));
        let (actor, _) = attach(backend).unwrap();
        let job = actor.action(crate::pack::actor::Input::Check {}).unwrap();
        paused.recv_timeout(Duration::from_secs(20)).unwrap();
        assert_eq!(fixture::child(&files, "probe").status.code(), Some(11));
        actor
            .action(crate::pack::actor::Input::Cancel {
                session_id: job.session_id,
                job_id: job.job_id,
            })
            .unwrap();
        assert_eq!(settled(&actor).status, Status::RepositoryUnavailable);
        assert!(!game(&files).join("mods/fixture.jar").exists());
        drop(actor);
        let before = tree(&path(&files));
        let mut backend = self::backend(open(&files, &p.bootstrap), &p, 1, b"payload");
        backend.repository = Some(repository(good));
        let (actor, _) = attach(backend).unwrap();
        assert_eq!(actor.snapshot().unwrap().status, Status::Idle);
        assert_eq!(tree(&path(&files)), before);
        actor.action(crate::pack::actor::Input::Check {}).unwrap();
        assert_eq!(settled(&actor).status, Status::Completed);
    });
}
#[test]
fn corrupt_signed_metadata_never_produces_plan_and_newer_valid_roles_retry() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut wrong = p
            .published_manifest(&fixture::doc(1, b"payload"), 1, false)
            .await;
        let mut map = (*wrong.files).clone();
        let mut timestamp: serde_json::Value =
            serde_json::from_slice(&map["/metadata/timestamp.json"]).unwrap();
        timestamp["signed"]["version"] = 99.into();
        map.insert(
            "/metadata/timestamp.json".into(),
            serde_json::to_vec(&timestamp).unwrap(),
        );
        wrong.files = Arc::new(map);
        let mut backend = backend(open(&files, &p.bootstrap), &p, 1, b"payload");
        backend.repository = Some(repository(wrong));
        let (actor, weak) = attach(backend).unwrap();
        actor.action(crate::pack::actor::Input::Install {}).unwrap();
        let view = settled(&actor);
        assert_eq!(view.status, Status::RepositoryUnavailable);
        assert!(view.plan.is_none());
        assert!(!path(&files)
            .join("install/install-control/transactions/tx-00001")
            .exists());
        weak.upgrade().unwrap().lock().unwrap().repository = Some(repository(
            p.published_manifest(&fixture::doc(1, b"payload"), 2, false)
                .await,
        ));
        let plan = preview(&actor, crate::pack::actor::Input::Install {});
        assert_eq!(confirm(&actor, plan).status, Status::Completed);
    });
}
#[test]
fn metadata_refresh_cannot_supersede_pending_install_or_repair_corrupt_layout() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        fixture::input(&files, &p).await;
        drop(open(&files, &p.bootstrap));
        assert_eq!(
            fixture::child(&files, "install:prepared").status.code(),
            Some(37)
        );
        let mut engine = open(&files, &p.bootstrap);
        let before = tree(&path(&files));
        let repo = repository(
            p.published_manifest(&fixture::doc(2, b"different"), 2, false)
                .await,
        );
        assert!(matches!(
            engine
                .refresh_repository(&p.bootstrap, &repo, &publisher::policy(2), &Cancel::new())
                .await,
            Err("ENGINE_INSTALL_RECOVERY_REQUIRED")
        ));
        assert_eq!(tree(&path(&files)), before);
        drop(engine);
        let mut backend = backend(open(&files, &p.bootstrap), &p, 1, b"engine payload");
        backend.repository = Some(repo);
        let (actor, _) = attach(backend).unwrap();
        assert_eq!(actor.snapshot().unwrap().status, Status::RecoveryRequired);
        assert_eq!(
            actor.action(crate::pack::actor::Input::Check {}),
            Err("RECOVERY_REQUIRED")
        );
        actor.action(crate::pack::actor::Input::Recover {}).unwrap();
        assert_eq!(settled(&actor).status, Status::Completed);
        drop(actor);
        std::fs::write(path(&files).join("unknown"), b"preserve").unwrap();
        let before = tree(&path(&files));
        assert!(matches!(
            Engine::open(&files.root, &p.bootstrap, &mut |_| {}),
            Err("ENGINE_LAYOUT_INVALID")
        ));
        assert_eq!(tree(&path(&files)), before);
    });
}
#[test]
fn checked_configuration_validates_policy_root_sources_and_endpoints_before_layout() {
    runtime().block_on(async {
        let p = publisher::Fixture::new().await;
        let bases = BTreeMap::from([(
            "fixture".into(),
            vec!["https://artifacts.animeoncraft.invalid/artifacts/".into()],
        )]);
        let config = Configuration::checked(
            p.bootstrap.clone(),
            publisher::policy(1),
            None,
            "https://repo.animeoncraft.invalid/metadata/",
            "https://repo.animeoncraft.invalid/targets/",
            bases.clone(),
        )
        .unwrap();
        assert_eq!(
            config.policy.launcher_version.to_string(),
            env!("CARGO_PKG_VERSION")
        );
        assert!(config.source.available());
        for case in 0..4 {
            let mut policy = publisher::policy(1);
            match case {
                0 => policy.sequence = 0,
                1 => policy.java.major = 99,
                2 => policy
                    .approved_sources
                    .insert("missing".into())
                    .then_some(())
                    .unwrap(),
                _ => policy.pack_id = "../foreign".into(),
            }
            assert!(Configuration::checked(
                p.bootstrap.clone(),
                policy,
                None,
                "https://repo.animeoncraft.invalid/metadata/",
                "https://repo.animeoncraft.invalid/targets/",
                bases.clone()
            )
            .is_err());
        }
        assert!(Configuration::checked(
            b"{}".to_vec(),
            publisher::policy(1),
            None,
            "https://repo.animeoncraft.invalid/metadata/",
            "https://repo.animeoncraft.invalid/targets/",
            bases
        )
        .is_err());
    });
}
#[test]
fn first_online_check_populates_verified_history_then_confirms_without_seeded_proof() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let engine = open(&files, &p.bootstrap);
        let mut backend = backend(engine, &p, 1, b"payload");
        backend.repository = Some(repository(
            p.published_manifest(&fixture::doc(1, b"payload"), 1, false)
                .await,
        ));
        let (actor, _) = attach(backend).unwrap();
        assert_eq!(actor.snapshot().unwrap().status, Status::Idle);
        actor.action(crate::pack::actor::Input::Check {}).unwrap();
        assert_eq!(settled(&actor).status, Status::Completed);
        assert!(!game(&files).join("mods/fixture.jar").exists());
        let plan = preview(&actor, crate::pack::actor::Input::Install {});
        assert_eq!(confirm(&actor, plan).status, Status::Completed);
        assert_eq!(
            std::fs::read(game(&files).join("mods/fixture.jar")).unwrap(),
            b"payload"
        );
    });
}
#[test]
fn next_explicit_release_fetches_before_preview_and_does_not_change_context() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut first = backend(open(&files, &p.bootstrap), &p, 1, b"first");
        first.repository = Some(repository(
            p.published_manifest(&fixture::doc(1, b"first"), 1, false)
                .await,
        ));
        let (actor, _) = attach(first).unwrap();
        let plan = preview(&actor, crate::pack::actor::Input::Install {});
        assert_eq!(confirm(&actor, plan).status, Status::Completed);
        drop(actor);
        let mut second = backend(open(&files, &p.bootstrap), &p, 2, b"second");
        second.repository = Some(repository(
            p.published_manifest(&fixture::doc(2, b"second"), 2, false)
                .await,
        ));
        let (actor, _) = attach(second).unwrap();
        assert_eq!(actor.snapshot().unwrap().status, Status::Idle);
        let plan = preview(&actor, crate::pack::actor::Input::Install {});
        assert_eq!(plan.summary.replace, 1);
        assert_eq!(
            std::fs::read(game(&files).join("mods/fixture.jar")).unwrap(),
            b"first"
        );
        assert_eq!(confirm(&actor, plan).status, Status::Completed);
        drop(actor);
        let mut engine = open(&files, &p.bootstrap);
        let before = tree(&path(&files));
        let mut changed = publisher::policy(3);
        changed.channel = "beta".into();
        let source = repository(
            p.published_manifest(&fixture::doc(3, b"third"), 3, false)
                .await,
        );
        assert!(matches!(
            engine
                .refresh_repository(&p.bootstrap, &source, &changed, &Cancel::new())
                .await,
            Err("STORE_CONTEXT_CHANGED")
        ));
        assert_eq!(tree(&path(&files)), before);
    });
}
#[test]
fn network_failure_has_no_plan_and_retries_persisted_fences_without_reset() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let engine = open(&files, &p.bootstrap);
        let mut backend = backend(engine, &p, 1, b"payload");
        backend.repository = Some(repository(publisher::Memory {
            files: Arc::new(BTreeMap::new()),
            crash: false,
        }));
        let (actor, weak) = attach(backend).unwrap();
        actor.action(crate::pack::actor::Input::Check {}).unwrap();
        let view = settled(&actor);
        assert_eq!(view.status, Status::RepositoryUnavailable);
        assert!(view.plan.is_none());
        assert!(!game(&files).join("mods/fixture.jar").exists());
        let intent =
            std::fs::read(path(&files).join("trust/control/journal/intent-00001.json")).unwrap();
        weak.upgrade().unwrap().lock().unwrap().repository = Some(repository(
            p.published_manifest(&fixture::doc(1, b"payload"), 1, false)
                .await,
        ));
        actor.action(crate::pack::actor::Input::Check {}).unwrap();
        assert_eq!(settled(&actor).status, Status::Completed);
        assert_eq!(
            std::fs::read(path(&files).join("trust/control/journal/intent-00001.json")).unwrap(),
            intent
        );
    });
}
