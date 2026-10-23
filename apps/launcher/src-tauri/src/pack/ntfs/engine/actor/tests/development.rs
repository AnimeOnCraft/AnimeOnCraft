use super::*;
use crate::pack::local::{LocalInstance, Status as LocalStatus};
fn local(files: &Fixture) -> Arc<LocalInstance> {
    let local = Arc::new(LocalInstance::open(files.path()));
    assert_eq!(local.snapshot().status, LocalStatus::Ready);
    local
}
fn development_path(files: &Fixture) -> std::path::PathBuf {
    files.path().join(Profile::Development.namespace())
}
fn configuration(p: &publisher::Fixture, sequence: u64, bytes: &[u8]) -> Configuration {
    Configuration {
        bootstrap: p.bootstrap.clone(),
        policy: publisher::policy(sequence),
        prior_policy: (sequence > 1).then(|| publisher::policy(sequence - 1)),
        source: download::Source::Fixture(objects(bytes)),
        repository: None,
    }
}
#[test]
fn ordinary_unconfigured_factory_keeps_local_lease_and_never_creates_engine() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        fixture::input(&files, &p).await;
        let local = local(&files);
        let old = files.path().join("AnimeOnCraft.InstallDevelopment.v1");
        std::fs::create_dir(old.join("instance/saves")).unwrap();
        std::fs::write(old.join("instance/saves/world.dat"), b"legacy world").unwrap();
        let before = tree(files.path());
        let actor = crate::pack::development_actor(local.clone());
        assert_eq!(actor.snapshot().unwrap().status, Status::Unavailable);
        for input in [
            crate::pack::actor::Input::Install {},
            crate::pack::actor::Input::Repair {},
            crate::pack::actor::Input::Recover {},
        ] {
            assert_eq!(actor.action(input), Err("RELEASE_NOT_CONFIGURED"));
        }
        actor.action(crate::pack::actor::Input::Check {}).unwrap();
        assert_eq!(settled(&actor).status, Status::Unavailable);
        assert_eq!(tree(files.path()), before);
        assert!(!development_path(&files).exists());
        drop(local);
        assert_eq!(
            fixture::child(&files, "local-probe").status.code(),
            Some(11)
        );
        drop(actor);
        assert_eq!(fixture::child(&files, "local-probe").status.code(), Some(0));
        assert_eq!(tree(files.path()), before);
    });
}
#[test]
fn missing_catalog_and_existing_foreign_engine_are_not_adopted_by_unconfigured_factory() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let local = local(&files);
        std::fs::create_dir(development_path(&files)).unwrap();
        std::fs::write(
            development_path(&files).join("user-data"),
            b"preserve foreign bytes",
        )
        .unwrap();
        let before = tree(files.path());
        let mut config = configuration(&p, 1, b"payload");
        config.source = download::Source::Configured;
        let actor = create_development(local.clone(), Some(config));
        assert_eq!(actor.snapshot().unwrap().status, Status::Unavailable);
        actor.action(crate::pack::actor::Input::Check {}).unwrap();
        assert_eq!(settled(&actor).status, Status::Unavailable);
        assert_eq!(tree(files.path()), before);
        drop(actor);
        // Only the test signed source permits attempting the separate layout.
        let actor = create_development(local.clone(), Some(configuration(&p, 1, b"payload")));
        assert_eq!(actor.snapshot().unwrap().status, Status::Blocked);
        assert_eq!(
            actor.action(crate::pack::actor::Input::Check {}),
            Err("STORE_INVALID")
        );
        assert_eq!(tree(files.path()), before);
        assert_eq!(local.snapshot().status, LocalStatus::Ready);
    });
}
#[test]
fn ordinary_factory_reopens_development_schema_installs_updates_and_preserves_old_profile() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        fixture::input(&files, &p).await;
        let local = local(&files);
        let old = files.path().join("AnimeOnCraft.InstallDevelopment.v1");
        std::fs::create_dir(old.join("instance/saves")).unwrap();
        std::fs::write(old.join("instance/saves/world.dat"), b"old profile world").unwrap();
        let old_before = tree(&old);
        let mut engine = local.engine(&p.bootstrap).unwrap();
        drop(refresh(&mut engine, &p, 1, b"first").await);
        let binding = std::fs::read(development_path(&files).join("binding.json")).unwrap();
        let record: serde_json::Value = serde_json::from_slice(&binding).unwrap();
        assert_eq!(record["environment"], Profile::Development.environment());
        assert_eq!(
            record["game_instance"]["environment"],
            Profile::Development.environment()
        );
        let game = development_path(&files).join("install/game-instance");
        std::fs::create_dir(game.join("saves")).unwrap();
        std::fs::write(game.join("saves/world.dat"), b"engine world").unwrap();
        drop(engine);
        let before = tree(&development_path(&files));
        let actor = create_development(local.clone(), Some(configuration(&p, 1, b"first")));
        assert_eq!(actor.snapshot().unwrap().status, Status::Idle);
        assert_eq!(tree(&development_path(&files)), before);
        let plan = preview(&actor, crate::pack::actor::Input::Install {});
        assert_eq!(confirm(&actor, plan).status, Status::Completed);
        assert_eq!(
            fixture::child(&files, "development-probe").status.code(),
            Some(11)
        );
        drop(actor);
        let mut engine = local.engine(&p.bootstrap).unwrap();
        drop(refresh(&mut engine, &p, 2, b"second").await);
        drop(engine);
        let actor = create_development(local.clone(), Some(configuration(&p, 2, b"second")));
        assert_eq!(actor.snapshot().unwrap().status, Status::Idle);
        let plan = preview(&actor, crate::pack::actor::Input::Install {});
        assert_eq!(plan.summary.replace, 1);
        assert_eq!(confirm(&actor, plan).status, Status::Completed);
        std::fs::write(game.join("mods/fixture.jar"), b"user edit").unwrap();
        let plan = preview(&actor, crate::pack::actor::Input::Repair {});
        assert_eq!(confirm(&actor, plan).status, Status::Completed);
        assert_eq!(
            std::fs::read(game.join("mods/fixture.jar")).unwrap(),
            b"second"
        );
        assert_eq!(
            std::fs::read(game.join("saves/world.dat")).unwrap(),
            b"engine world"
        );
        assert_eq!(tree(&old), old_before);
        assert_eq!(
            std::fs::read(development_path(&files).join("binding.json")).unwrap(),
            binding
        );
        assert!(!files.path().join(NAMESPACE).exists());
    });
}
#[test]
fn development_worker_retains_both_local_and_engine_leases_after_ui_drop() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        fixture::input(&files, &p).await;
        let local = local(&files);
        let mut engine = local.engine(&p.bootstrap).unwrap();
        drop(refresh(&mut engine, &p, 1, b"payload").await);
        let mut backend = backend(engine, &p, 1, b"payload");
        backend.local = Some(local.clone());
        let (entered, go) = pause(&mut backend, "downloading");
        let (actor, weak) = attach(backend).unwrap();
        let plan = preview(&actor, crate::pack::actor::Input::Install {});
        actor
            .action(crate::pack::actor::Input::Confirm {
                plan_id: plan.plan_id,
            })
            .unwrap();
        entered.recv_timeout(Duration::from_secs(20)).unwrap();
        drop(actor);
        drop(local);
        assert_eq!(
            fixture::child(&files, "local-probe").status.code(),
            Some(11)
        );
        assert_eq!(
            fixture::child(&files, "development-probe").status.code(),
            Some(11)
        );
        go.send(()).unwrap();
        let deadline = Instant::now() + Duration::from_secs(30);
        while weak.upgrade().is_some() {
            assert!(Instant::now() < deadline);
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(fixture::child(&files, "local-probe").status.code(), Some(0));
        assert_eq!(
            fixture::child(&files, "development-probe").status.code(),
            Some(0)
        );
    });
}
#[test]
fn local_corruption_prevents_confirm_and_keeps_engine_and_local_owners() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        fixture::input(&files, &p).await;
        let local = local(&files);
        let mut engine = local.engine(&p.bootstrap).unwrap();
        drop(refresh(&mut engine, &p, 1, b"payload").await);
        drop(engine);
        let actor = create_development(local.clone(), Some(configuration(&p, 1, b"payload")));
        let plan = preview(&actor, crate::pack::actor::Input::Install {});
        std::fs::hard_link(
            files
                .path()
                .join("AnimeOnCraft.InstallDevelopment.v1/lease.lock"),
            files.path().join("own-lease-alias"),
        )
        .unwrap();
        let before = tree(files.path());
        assert_eq!(confirm(&actor, plan).status, Status::Blocked);
        assert_eq!(local.snapshot().status, LocalStatus::Blocked);
        assert_eq!(tree(files.path()), before);
        assert_eq!(
            fixture::child(&files, "local-probe").status.code(),
            Some(11)
        );
        assert_eq!(
            fixture::child(&files, "development-probe").status.code(),
            Some(11)
        );
    });
}
#[test]
fn development_creation_process_exit_preserves_partial_or_reopens_exact_ready() {
    runtime().block_on(async {
        for phase in [
            "namespace-created",
            "lease-created",
            "trust-created",
            "install-created",
            "downloads-created",
            "binding-created",
            "ready-created",
        ] {
            let files = Fixture::new();
            let p = publisher::Fixture::new().await;
            fixture::input(&files, &p).await;
            let local = local(&files);
            let old_before = tree(&files.path().join("AnimeOnCraft.InstallDevelopment.v1"));
            assert_eq!(
                fixture::child(&files, &format!("development-create:{phase}"))
                    .status
                    .code(),
                Some(37)
            );
            let before = tree(&development_path(&files));
            assert_eq!(
                fixture::child(&files, "development-probe").status.code(),
                Some(if phase == "ready-created" { 0 } else { 12 }),
                "{phase}"
            );
            assert_eq!(tree(&development_path(&files)), before);
            assert_eq!(
                tree(&files.path().join("AnimeOnCraft.InstallDevelopment.v1")),
                old_before
            );
            assert_eq!(local.snapshot().status, LocalStatus::Ready);
        }
    });
}
#[test]
fn development_binding_rejects_foreign_environment_and_unconfigured_download_never_writes() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let local = local(&files);
        let mut engine = local.engine(&p.bootstrap).unwrap();
        let new = refresh(&mut engine, &p, 1, b"payload").await;
        let before = tree(&development_path(&files));
        assert!(matches!(
            download::Source::Configured
                .prepare(&mut engine.downloads, &new, &Cancel::new())
                .await,
            Err("RELEASE_NOT_CONFIGURED")
        ));
        assert_eq!(tree(&development_path(&files)), before);
        drop(engine);
        let file = development_path(&files).join("binding.json");
        let mut binding: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&file).unwrap()).unwrap();
        binding["environment"] = ENVIRONMENT.into();
        let bytes = serde_json::to_vec(&binding).unwrap();
        std::fs::write(&file, &bytes).unwrap();
        let file = development_path(&files).join("ready.json");
        let mut ready: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&file).unwrap()).unwrap();
        ready["binding"] = manifest::hash(&bytes).into();
        std::fs::write(file, serde_json::to_vec(&ready).unwrap()).unwrap();
        let before = tree(files.path());
        assert!(matches!(
            local.engine(&p.bootstrap),
            Err("ENGINE_BINDING_INVALID")
        ));
        assert_eq!(tree(files.path()), before);
        assert_eq!(local.snapshot().status, LocalStatus::Ready);
    });
}
