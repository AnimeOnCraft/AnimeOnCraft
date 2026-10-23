use super::*;
fn directory() -> std::path::PathBuf {
    let path = std::path::PathBuf::from(
        std::env::var_os("AOC_SELF_UPDATE_ACCEPTANCE_DIRECTORY").expect("fixture directory"),
    );
    let tools = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../.tools")
        .canonicalize()
        .unwrap();
    let canonical = path.canonicalize().unwrap();
    // Windows canonicalize adds the verbatim-drive prefix; compare the same absolute spelling.
    let display = canonical.to_str().unwrap();
    let display = display.strip_prefix(r"\\?\").unwrap_or(display);
    assert_eq!(path, std::path::PathBuf::from(display));
    assert_eq!(canonical.parent().unwrap(), tools);
    let name = path
        .file_name()
        .unwrap()
        .to_str()
        .unwrap()
        .strip_prefix("self-update-acceptance-")
        .unwrap();
    assert_eq!(name.len(), 32);
    assert!(name
        .bytes()
        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)));
    canonical
}
#[test]
#[ignore = "exports ephemeral signed fixtures for the isolated self-update HTTPS acceptance"]
fn export_self_update_fixtures() {
    let directory = directory();
    let fixture = Fixture::new();
    let mut envelopes = serde_json::Map::new();
    for mode in [
        "good",
        "tamper",
        "expired",
        "platform",
        "bad_signature",
        "rollback",
    ] {
        let mut manifest = fixture.manifest(if mode == "rollback" {
            "0.7.19"
        } else {
            "0.7.18"
        });
        if mode == "expired" {
            manifest.expires_at = chrono::Utc::now().timestamp() - 1;
        }
        if mode == "platform" {
            manifest.target = "windows-aarch64-nsis".into();
        }
        if mode == "bad_signature" {
            manifest.installer_signature = sign(&fixture.metadata, &fixture.bytes);
        }
        let mut bytes = fixture.envelope(&manifest);
        if mode == "tamper" {
            let text = String::from_utf8(bytes)
                .unwrap()
                .replace("0.7.18", "0.7.99");
            bytes = text.into_bytes();
        }
        envelopes.insert(
            mode.into(),
            serde_json::Value::String(String::from_utf8(bytes).unwrap()),
        );
    }
    std::fs::write(directory.join("fixtures.json"), serde_json::to_vec(&serde_json::json!({
        "metadata_key": fixture.metadata.pk.to_base64(), "installer_key": fixture.installer.pk.to_base64(),
        "envelopes": envelopes, "artifact": fixture.bytes,
    })).unwrap()).unwrap();
    // Only public keys, signatures and synthetic bytes are exported. Secret keys die here.
}
#[cfg(windows)]
#[test]
#[ignore = "requires the isolated self-update HTTPS server"]
fn self_update_https_signature_cancellation_and_consent_acceptance() {
    let directory = directory();
    let certificate = std::fs::read(directory.join("certificate.pem")).unwrap();
    let fixtures: serde_json::Value =
        serde_json::from_slice(&std::fs::read(directory.join("fixtures.json")).unwrap()).unwrap();
    let config = |mode: &str| {
        Config::new(
            &format!("https://localhost:39443/aoc/launcher/{mode}/"),
            fixtures["metadata_key"].as_str().unwrap(),
            fixtures["installer_key"].as_str().unwrap(),
            "0.7.17",
        )
        .unwrap()
    };
    runtime().block_on(async {
        let http = transport::Http::with_certificate(&certificate).unwrap();
        let (_directory, local) = local();
        let actor = Actor::new();
        let (sender, cancel) = tokio::sync::watch::channel(false);
        let service = Service::new(Some(config("good")));
        assert_eq!(
            service
                .prepare(&actor, local.clone(), cancel.clone())
                .await
                .err(),
            Some("UPDATE_NETWORK"),
            "untrusted TLS accepted"
        );
        let wrong = Config::new(
            "https://127.0.0.1:39443/aoc/launcher/good/",
            fixtures["metadata_key"].as_str().unwrap(),
            fixtures["installer_key"].as_str().unwrap(),
            "0.7.17",
        )
        .unwrap();
        assert_eq!(
            service
                .prepare_with(&wrong, &http, &actor, local.clone(), cancel.clone())
                .await
                .err(),
            Some("UPDATE_NETWORK"),
            "wrong hostname accepted"
        );
        for mode in [
            "tamper",
            "expired",
            "platform",
            "bad_signature",
            "artifact_tamper",
            "oversize",
            "stream_oversize",
            "encoding",
            "duplicate",
            "redirect",
            "missing",
            "short",
        ] {
            let service = Service::new(Some(config(mode)));
            assert!(
                service
                    .prepare_with(
                        service.config.as_ref().unwrap(),
                        &http,
                        &actor,
                        local.clone(),
                        cancel.clone()
                    )
                    .await
                    .is_err(),
                "accepted {mode}"
            );
            assert!(actor.reserve_update().is_ok(), "gate retained after {mode}");
            assert!(service.state.lock().unwrap().prepared.is_none());
        }
        let service = Service::new(Some(config("good")));
        assert_eq!(
            service
                .prepare_with(
                    service.config.as_ref().unwrap(),
                    &http,
                    &actor,
                    Arc::new(LocalInstance::unavailable()),
                    cancel.clone()
                )
                .await
                .err(),
            Some("UPDATE_LOCAL_UNAVAILABLE")
        );
        for startup in [
            crate::pack::actor::Startup::RecoveryRequired,
            crate::pack::actor::Startup::Blocked,
        ] {
            let dirty =
                Actor::fixture_startup(startup, |_, _| crate::pack::actor::Outcome::Completed);
            assert_eq!(
                service
                    .prepare_with(
                        service.config.as_ref().unwrap(),
                        &http,
                        &dirty,
                        local.clone(),
                        cancel.clone()
                    )
                    .await
                    .err(),
                Some("UPDATE_GATE_CLOSED")
            );
        }
        let view = service
            .prepare_with(
                service.config.as_ref().unwrap(),
                &http,
                &actor,
                local.clone(),
                cancel.clone(),
            )
            .await
            .unwrap();
        assert_eq!(view.version, "0.7.18");
        assert_eq!(view.bytes, 1024);
        assert_eq!(
            service
                .prepare_with(
                    service.config.as_ref().unwrap(),
                    &http,
                    &actor,
                    local.clone(),
                    cancel.clone()
                )
                .await
                .err(),
            Some("UPDATE_CONFIRMATION_REQUIRED")
        );
        assert_eq!(
            actor.action(crate::pack::actor::Input::Check {}),
            Err("LAUNCHER_UPDATE_PENDING")
        );
        assert!(service.consume(Id::new()).is_err());
        service.discard(view.plan_id).unwrap();
        let view = service
            .prepare_with(
                service.config.as_ref().unwrap(),
                &http,
                &actor,
                local.clone(),
                cancel.clone(),
            )
            .await
            .unwrap();
        let verified = service.consume(view.plan_id).unwrap();
        assert_eq!(verified.bytes, vec![0x51; 1024]);
        assert_eq!(
            service.consume(view.plan_id).err(),
            Some("UPDATE_STALE_PLAN")
        );
        assert!(actor.reserve_update().is_err());
        drop(verified);
        assert!(actor.reserve_update().is_ok());
        let rollback = Service::new(Some(config("rollback")));
        let view = rollback
            .prepare_with(
                rollback.config.as_ref().unwrap(),
                &http,
                &actor,
                local.clone(),
                cancel.clone(),
            )
            .await
            .unwrap();
        rollback.discard(view.plan_id).unwrap();
        assert_eq!(
            rollback
                .prepare_with(
                    rollback.config.as_ref().unwrap(),
                    &http,
                    &actor,
                    local.clone(),
                    cancel.clone()
                )
                .await
                .err(),
            Some("UPDATE_ROLLBACK")
        );
        let slow = Service::new(Some(config("slow")));
        let future = slow.prepare_with(
            slow.config.as_ref().unwrap(),
            &http,
            &actor,
            local.clone(),
            cancel.clone(),
        );
        tokio::pin!(future);
        // Server marks the first body chunk delivered and leaves the actual socket suspended.
        let trigger = async {
            let deadline = Instant::now() + Duration::from_secs(10);
            loop {
                if directory.join("slow-started").is_file() {
                    break;
                }
                assert!(Instant::now() < deadline);
                tokio::time::sleep(Duration::from_millis(25)).await;
            }
            assert_eq!(
                slow.prepare_with(
                    slow.config.as_ref().unwrap(),
                    &http,
                    &actor,
                    local.clone(),
                    cancel.clone()
                )
                .await
                .err(),
                Some("UPDATE_BUSY")
            );
            assert_eq!(
                actor.action(crate::pack::actor::Input::Check {}),
                Err("LAUNCHER_UPDATE_PENDING")
            );
            sender.send_replace(true);
        };
        let (result, ()) = futures::future::join(future, trigger).await;
        assert_eq!(result.err(), Some("UPDATE_CANCELLED"));
        assert!(actor.reserve_update().is_ok());
        assert!(!slow.state.lock().unwrap().busy);
        sender.send_replace(false);
        let abort = Service::new(Some(config("abort")));
        let future = Box::pin(abort.prepare_with(
            abort.config.as_ref().unwrap(),
            &http,
            &actor,
            local.clone(),
            sender.subscribe(),
        ));
        let observer = Box::pin(async {
            let deadline = Instant::now() + Duration::from_secs(10);
            while !directory.join("abort-started").is_file() {
                assert!(Instant::now() < deadline);
                tokio::time::sleep(Duration::from_millis(25)).await;
            }
        });
        match futures::future::select(future, observer).await {
            futures::future::Either::Right(((), suspended)) => {
                assert!(actor.reserve_update().is_err());
                drop(suspended);
            }
            futures::future::Either::Left(_) => panic!("abort socket wasn't suspended"),
        }
        assert!(!abort.state.lock().unwrap().busy);
        assert!(actor.reserve_update().is_ok());
        let retry = abort
            .prepare_with(
                abort.config.as_ref().unwrap(),
                &http,
                &actor,
                local.clone(),
                sender.subscribe(),
            )
            .await
            .unwrap();
        abort.discard(retry.plan_id).unwrap();
        let ordinary = Service::ordinary();
        assert_eq!(
            ordinary.prepare(&actor, local.clone(), cancel).await.err(),
            Some("UPDATE_NOT_CONFIGURED")
        );
        std::fs::write(
            directory.join("rust-result.json"),
            br#"{"passed":true,"synthetic_artifact":true,"installer_executed":false}"#,
        )
        .unwrap();
    });
}
