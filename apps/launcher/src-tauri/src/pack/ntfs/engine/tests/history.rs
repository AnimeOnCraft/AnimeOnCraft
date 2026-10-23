use super::*;
#[test]
fn restart_restores_installed_and_current_proofs_without_network_or_writes() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut engine = open(&files, &p.bootstrap);
        std::fs::create_dir(game(&files).join("saves")).unwrap();
        std::fs::write(game(&files).join("saves/world.dat"), b"history world").unwrap();
        let old = refresh(&mut engine, &p, 1, b"old").await;
        install(&mut engine, &p, &old, None, Mode::Update, b"old").await;
        drop(old);
        drop(refresh(&mut engine, &p, 2, b"new").await);
        drop(engine);
        let before = tree(&path(&files));
        let mut engine = open(&files, &p.bootstrap);
        let context = engine
            .reconcile(
                &p.bootstrap,
                &publisher::policy(2),
                Some(&publisher::policy(1)),
            )
            .unwrap();
        assert!(matches!(context.status, Startup::Ready));
        assert!(!context.installed_current);
        assert_eq!(
            context
                .old()
                .unwrap()
                .manifest()
                .manifest()
                .release_sequence,
            1
        );
        assert_eq!(
            context.old().unwrap().current(),
            Err("HISTORICAL_RELEASE_NOT_CURRENT")
        );
        assert_eq!(tree(&path(&files)), before);
        install(
            &mut engine,
            &p,
            &context.current,
            context.old(),
            Mode::Update,
            b"new",
        )
        .await;
        drop(context);
        drop(engine);
        let before = tree(&path(&files));
        let mut engine = open(&files, &p.bootstrap);
        let context = engine
            .reconcile(&p.bootstrap, &publisher::policy(2), None)
            .unwrap();
        assert!(context.installed_current);
        assert!(context.old().unwrap().current().is_ok());
        assert!(matches!(context.status, Startup::Ready));
        assert_eq!(tree(&path(&files)), before);
        assert_eq!(
            std::fs::read(game(&files).join("saves/world.dat")).unwrap(),
            b"history world"
        );
    });
}

#[test]
fn restored_pending_plan_recovers_without_retained_in_memory_proof() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        input(&files, &p).await;
        drop(open(&files, &p.bootstrap));
        assert_eq!(child(&files, "install:prepared").status.code(), Some(37));
        let before = tree(&path(&files));
        let mut engine = open(&files, &p.bootstrap);
        let context = engine
            .reconcile(&p.bootstrap, &publisher::policy(1), None)
            .unwrap();
        assert!(matches!(context.status, Startup::RecoveryRequired));
        assert_eq!(tree(&path(&files)), before);
        assert!(matches!(
            engine
                .resume(
                    &p.bootstrap,
                    &context.current,
                    context.old(),
                    objects(b"engine payload"),
                    &Cancel::new(),
                    &mut |_| {}
                )
                .await
                .unwrap(),
            Outcome::Committed { .. }
        ));
        drop(context);
        drop(engine);
        let mut engine = open(&files, &p.bootstrap);
        let context = engine
            .reconcile(&p.bootstrap, &publisher::policy(1), None)
            .unwrap();
        assert!(matches!(context.status, Startup::Ready));
        assert_eq!(
            std::fs::read(game(&files).join("mods/fixture.jar")).unwrap(),
            b"engine payload"
        );
    });
}

#[test]
fn missing_or_restricted_prior_policy_and_mismatched_index_preserve_state() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut engine = open(&files, &p.bootstrap);
        let old = refresh(&mut engine, &p, 1, b"old").await;
        install(&mut engine, &p, &old, None, Mode::Update, b"old").await;
        drop(old);
        drop(refresh(&mut engine, &p, 2, b"new").await);
        drop(engine);
        let before = tree(&path(&files));
        let mut engine = open(&files, &p.bootstrap);
        assert!(matches!(
            engine.reconcile(&p.bootstrap, &publisher::policy(2), None),
            Err("HISTORY_POLICY_REQUIRED")
        ));
        let mut removed = publisher::policy(1);
        removed.approved_sources.clear();
        assert!(engine
            .reconcile(&p.bootstrap, &publisher::policy(2), Some(&removed))
            .is_err());
        assert_eq!(tree(&path(&files)), before);
        drop(engine);
        let index = path(&files).join("install/install-control/transactions/tx-00001/index.json");
        let mut value: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&index).unwrap()).unwrap();
        value["entries"]["mods/fixture.jar"]["sha256"] = manifest::hash(b"foreign index").into();
        std::fs::write(index, serde_json::to_vec(&value).unwrap()).unwrap();
        let before = tree(&path(&files));
        let mut engine = open(&files, &p.bootstrap);
        assert!(matches!(
            engine.reconcile(
                &p.bootstrap,
                &publisher::policy(2),
                Some(&publisher::policy(1))
            ),
            Err("INSTALL_INDEX_INVALID")
        ));
        assert_eq!(tree(&path(&files)), before);
    });
}

#[test]
fn resealed_old_generation_signature_corruption_is_not_hidden_by_latest() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut engine = open(&files, &p.bootstrap);
        drop(refresh(&mut engine, &p, 1, b"old").await);
        drop(refresh(&mut engine, &p, 2, b"new").await);
        drop(engine);
        let generation =
            path(&files).join("trust/instance/generations/generation-00001/bundle.json");
        let mut bundle: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&generation).unwrap()).unwrap();
        let mut role: serde_json::Value =
            serde_json::from_str(bundle["raw"]["timestamp.json"].as_str().unwrap()).unwrap();
        role["signed"]["version"] = 999.into();
        let changed = serde_json::to_string(&role).unwrap();
        bundle["raw"]["timestamp.json"] = changed.clone().into();
        bundle["files"]["timestamp.json"] = changed.clone().into();
        let mut checkpoint: serde_json::Value =
            serde_json::from_str(bundle["files"]["aoc-trust.json"].as_str().unwrap()).unwrap();
        checkpoint["metadata"]["timestamp.json"] = manifest::hash(changed.as_bytes()).into();
        bundle["files"]["aoc-trust.json"] = serde_json::to_string(&checkpoint).unwrap().into();
        let bytes = serde_json::to_vec(&bundle).unwrap();
        std::fs::write(generation, &bytes).unwrap();
        for phase in ["ready", "commit"] {
            let file = path(&files).join(format!("trust/control/journal/{phase}-00001.json"));
            let mut seal: serde_json::Value =
                serde_json::from_slice(&std::fs::read(&file).unwrap()).unwrap();
            seal["digest"] = manifest::hash(&bytes).into();
            seal["length"] = (bytes.len() as u64).into();
            std::fs::write(file, serde_json::to_vec(&seal).unwrap()).unwrap();
        }
        let before = tree(&path(&files));
        let mut engine = open(&files, &p.bootstrap); // Latest generation itself remains intact.
        assert!(engine
            .reconcile(&p.bootstrap, &publisher::policy(2), None)
            .is_err());
        assert_eq!(tree(&path(&files)), before);
    });
}

#[test]
fn pending_install_and_trust_contexts_do_not_silently_select_a_new_release() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        input(&files, &p).await;
        drop(open(&files, &p.bootstrap));
        assert_eq!(child(&files, "install:prepared").status.code(), Some(37));
        let mut engine = open(&files, &p.bootstrap);
        drop(refresh(&mut engine, &p, 2, b"other").await);
        let before = tree(&path(&files));
        assert!(matches!(
            engine.reconcile(&p.bootstrap, &publisher::policy(2), None),
            Err("ENGINE_PENDING_RELEASE_MISMATCH")
        ));
        assert_eq!(tree(&path(&files)), before);
        let unavailable = publisher::Memory {
            files: Arc::new(BTreeMap::new()),
            crash: false,
        };
        assert!(engine
            .refresh(&p.bootstrap, unavailable, &publisher::policy(3))
            .await
            .is_err());
        let before = tree(&path(&files));
        assert!(matches!(
            engine.reconcile(&p.bootstrap, &publisher::policy(2), None),
            Err("BRIDGE_RECOVERY_REQUIRED")
        ));
        assert_eq!(tree(&path(&files)), before);
    });
}

#[test]
fn historical_proof_cannot_download_even_empty_or_cached_payloads() {
    runtime().block_on(async {
        let p = publisher::Fixture::new().await;
        for server_only in [false, true] {
            let files = Fixture::new();
            let mut engine = open(&files, &p.bootstrap);
            let mut document = doc(1, b"old");
            if server_only {
                document.files[0].side = manifest::Side::SERVER;
            }
            let old = engine
                .refresh(
                    &p.bootstrap,
                    p.published_manifest(&document, 1, false).await,
                    &publisher::policy(1),
                )
                .await
                .unwrap();
            install(&mut engine, &p, &old, None, Mode::Update, b"old").await;
            drop(old);
            drop(refresh(&mut engine, &p, 2, b"new").await);
            let context = engine
                .reconcile(
                    &p.bootstrap,
                    &publisher::policy(2),
                    Some(&publisher::policy(1)),
                )
                .unwrap();
            let before = tree(&path(&files));
            assert!(matches!(
                download::fixture_prepared_store(
                    &mut engine.downloads,
                    context.old().unwrap(),
                    BTreeMap::new(),
                    &Cancel::new()
                )
                .await,
                Err("HISTORICAL_RELEASE_NOT_CURRENT")
            ));
            assert_eq!(tree(&path(&files)), before);
        }
    });
}

#[test]
fn valid_signatures_do_not_hide_same_version_replacement_or_context_changes() {
    runtime().block_on(async {
        let p = publisher::Fixture::new().await;
        for kind in ["same-version", "pack", "channel"] {
            let files = Fixture::new();
            let other = Fixture::new();
            let mut engine = open(&files, &p.bootstrap);
            drop(refresh(&mut engine, &p, 1, b"old").await);
            drop(refresh(&mut engine, &p, 2, b"new").await);
            drop(engine);
            let mut document = doc(2, b"new");
            let mut policy = publisher::policy(2);
            if kind == "pack" {
                document.pack_id = "other-pack".into();
                policy.pack_id = document.pack_id.clone();
            }
            if kind == "channel" {
                document.channel = "beta".into();
                policy.channel = document.channel.clone();
            }
            let mut source = open(&other, &p.bootstrap);
            drop(
                source
                    .refresh(
                        &p.bootstrap,
                        p.published_manifest(&document, 1, false).await,
                        &policy,
                    )
                    .await
                    .unwrap(),
            );
            drop(source);
            let bytes = std::fs::read(
                path(&other).join("trust/instance/generations/generation-00001/bundle.json"),
            )
            .unwrap();
            std::fs::write(
                path(&files).join("trust/instance/generations/generation-00002/bundle.json"),
                &bytes,
            )
            .unwrap();
            for phase in ["ready", "commit"] {
                let file = path(&files).join(format!("trust/control/journal/{phase}-00002.json"));
                let mut seal: serde_json::Value =
                    serde_json::from_slice(&std::fs::read(&file).unwrap()).unwrap();
                seal["digest"] = manifest::hash(&bytes).into();
                seal["length"] = (bytes.len() as u64).into();
                std::fs::write(file, serde_json::to_vec(&seal).unwrap()).unwrap();
            }
            let before = tree(&path(&files));
            let mut engine = open(&files, &p.bootstrap);
            assert!(
                matches!(
                    engine.reconcile(&p.bootstrap, &policy, None),
                    Err("STORE_ROLLBACK") | Err("STORE_CONTEXT_CHANGED")
                ),
                "{kind}"
            );
            assert_eq!(tree(&path(&files)), before);
        }
    });
}
