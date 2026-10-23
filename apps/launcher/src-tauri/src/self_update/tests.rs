use super::*;
use std::io::Cursor;
#[cfg(windows)]
mod handoff;
mod tls;
struct Fixture {
    metadata: minisign::KeyPair,
    installer: minisign::KeyPair,
    bytes: Vec<u8>,
}
impl Fixture {
    fn new() -> Self {
        Self {
            metadata: minisign::KeyPair::generate_unencrypted_keypair().unwrap(),
            installer: minisign::KeyPair::generate_unencrypted_keypair().unwrap(),
            bytes: vec![0x51; 1024],
        }
    }
    fn config(&self, base: &str) -> Config {
        Config::new(
            base,
            &self.metadata.pk.to_base64(),
            &self.installer.pk.to_base64(),
            "0.7.17",
        )
        .unwrap()
    }
    fn manifest(&self, version: &str) -> Manifest {
        let now = chrono::Utc::now().timestamp();
        Manifest {
            schema: 1,
            product: PRODUCT.into(),
            channel: "stable".into(),
            target: TARGET.into(),
            version: version.into(),
            issued_at: now - 60,
            expires_at: now + 3600,
            file: format!("AnimeOnCraft_{version}_x64-setup.exe"),
            size: self.bytes.len() as u64,
            sha256: format!("{:x}", Sha256::digest(&self.bytes)),
            installer_signature: sign(&self.installer, &self.bytes),
        }
    }
    fn envelope(&self, manifest: &Manifest) -> Vec<u8> {
        self.payload(&serde_json::to_string(manifest).unwrap())
    }
    fn payload(&self, payload: &str) -> Vec<u8> {
        serde_json::to_vec(&Envelope {
            payload: payload.into(),
            signature: sign(&self.metadata, payload.as_bytes()),
        })
        .unwrap()
    }
}
fn sign(key: &minisign::KeyPair, bytes: &[u8]) -> String {
    minisign::sign(
        Some(&key.pk),
        &key.sk,
        Cursor::new(bytes),
        Some("AnimeOnCraft isolated fixture"),
        None,
    )
    .unwrap()
    .into_string()
}
fn runtime() -> tokio::runtime::Runtime {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
}
#[cfg(windows)]
fn local() -> (tempfile::TempDir, Arc<LocalInstance>) {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../.tools")
        .canonicalize()
        .unwrap();
    let directory = tempfile::Builder::new()
        .prefix("self-update-test-")
        .tempdir_in(root)
        .unwrap();
    let local = Arc::new(LocalInstance::open(directory.path()));
    assert_eq!(local.snapshot().status, Status::Ready);
    (directory, local)
}
#[test]
fn separate_keys_and_canonical_native_source_required() {
    let fixture = Fixture::new();
    for base in [
        "http://repo.invalid/aoc/",
        "https://repo.invalid/",
        "https://REPO.invalid/aoc/",
        "https://repo.invalid/a/../aoc/",
        "https://repo.invalid/aoc/?secret=1",
        "https://u:p@repo.invalid/aoc/",
        "https://repo.invalid/aoc/#x",
        "https://repo.invalid/aoc//",
        "https://repo.invalid/%61oc/",
        "file:///aoc/",
    ] {
        assert!(
            Config::new(
                base,
                &fixture.metadata.pk.to_base64(),
                &fixture.installer.pk.to_base64(),
                "0.7.17"
            )
            .is_err(),
            "{base}"
        );
    }
    let base = "https://repo.invalid/aoc/launcher/";
    let key = fixture.metadata.pk.to_base64();
    assert!(Config::new(base, &key, &key, "0.7.17").is_err());
    let mut alias = base64::engine::general_purpose::STANDARD
        .decode(&key)
        .unwrap();
    alias[2] ^= 1; // Changing the ID must not turn the same Ed25519 key into a separate authority.
    let alias = base64::engine::general_purpose::STANDARD.encode(alias);
    assert!(Config::new(base, &key, &alias, "0.7.17").is_err());
    assert!(ordinary_config().is_none());
}
#[test]
fn both_exact_metadata_bytes_and_installer_bytes_are_authenticated() {
    let fixture = Fixture::new();
    let config = fixture.config("https://repo.invalid/aoc/launcher/");
    let manifest = fixture.manifest("0.7.18");
    let bytes = fixture.envelope(&manifest);
    let (verified, _) = verify_manifest(&config, &bytes, chrono::Utc::now().timestamp()).unwrap();
    assert_eq!(
        verify_installer(&config, &verified, fixture.bytes.clone()).unwrap(),
        fixture.bytes
    );
    let mut envelope: Envelope = serde_json::from_slice(&bytes).unwrap();
    envelope.payload = envelope.payload.replace("0.7.18", "0.7.99");
    assert_eq!(
        verify_manifest(
            &config,
            &serde_json::to_vec(&envelope).unwrap(),
            chrono::Utc::now().timestamp()
        )
        .err(),
        Some("UPDATE_SIGNATURE_INVALID")
    );
    let wrong = Fixture::new().config("https://repo.invalid/aoc/launcher/");
    assert_eq!(
        verify_manifest(&wrong, &bytes, chrono::Utc::now().timestamp()).err(),
        Some("UPDATE_SIGNATURE_INVALID")
    );
    let mut corrupted = fixture.bytes.clone();
    corrupted[3] ^= 1;
    assert_eq!(
        verify_installer(&config, &manifest, corrupted).err(),
        Some("UPDATE_INSTALLER_INVALID")
    );
    let mut wrong_signature = manifest.clone();
    wrong_signature.installer_signature = sign(&fixture.metadata, &fixture.bytes);
    assert_eq!(
        verify_installer(&config, &wrong_signature, fixture.bytes.clone()).err(),
        Some("UPDATE_SIGNATURE_INVALID")
    );
    let mut wrong_comment = manifest.clone();
    wrong_comment.installer_signature = wrong_comment
        .installer_signature
        .replace("isolated fixture", "altered comment");
    assert_eq!(
        verify_installer(&config, &wrong_comment, fixture.bytes.clone()).err(),
        Some("UPDATE_SIGNATURE_INVALID")
    );
}
#[test]
fn signed_semantics_refuse_platform_product_paths_versions_and_resource_abuse() {
    let fixture = Fixture::new();
    let config = fixture.config("https://repo.invalid/aoc/launcher/");
    let valid = fixture.manifest("0.7.18");
    let now = chrono::Utc::now().timestamp();
    for (field, value) in [
        ("schema", serde_json::json!(2)),
        ("product", serde_json::json!("other.launcher")),
        ("channel", serde_json::json!("beta")),
        ("target", serde_json::json!("windows-aarch64-nsis")),
        ("version", serde_json::json!("0.7.18-beta.1")),
        ("version", serde_json::json!("0.7.18+build")),
        ("version", serde_json::json!("v0.7.18")),
        ("version", serde_json::json!("0.7.17")),
        ("version", serde_json::json!("0.7.16")),
        ("file", serde_json::json!("../../world.exe")),
        (
            "file",
            serde_json::json!("AnimeOnCraft_0.7.19_x64-setup.exe"),
        ),
        ("size", serde_json::json!(0)),
        ("size", serde_json::json!(MAX_INSTALLER + 1)),
        ("sha256", serde_json::json!("A".repeat(64))),
        ("extra", serde_json::json!(true)),
        ("issued_at", serde_json::json!(now + 1)),
        ("expires_at", serde_json::json!(now)),
        ("expires_at", serde_json::json!(now + 90000)),
        ("installer_signature", serde_json::json!("x".repeat(1025))),
    ] {
        let mut payload = serde_json::to_value(&valid).unwrap();
        payload[field] = value;
        assert!(
            verify_manifest(&config, &fixture.payload(&payload.to_string()), now).is_err(),
            "{field}"
        );
    }
    let duplicated = serde_json::to_string(&valid)
        .unwrap()
        .replacen("{", "{\"schema\":1,", 1);
    assert!(verify_manifest(&config, &fixture.payload(&duplicated), now).is_err());
    let envelope = fixture.envelope(&valid);
    let duplicated_envelope =
        String::from_utf8(envelope)
            .unwrap()
            .replacen("{", "{\"signature\":\"x\",", 1);
    assert!(verify_manifest(&config, duplicated_envelope.as_bytes(), now).is_err());
    assert!(verify_manifest(&config, &vec![0; MAX_ENVELOPE + 1], now).is_err());
    assert_eq!(
        config.artifact(&valid).unwrap().as_str(),
        "https://repo.invalid/aoc/launcher/0.7.18/AnimeOnCraft_0.7.18_x64-setup.exe"
    );
}
#[test]
fn unconfigured_and_cancelled_preparation_never_reserve_or_start_io() {
    runtime().block_on(async {
        let service = Service::ordinary();
        let actor = Actor::new();
        let local = Arc::new(LocalInstance::unavailable());
        let (_sender, cancel) = tokio::sync::watch::channel(false);
        assert_eq!(
            service.prepare(&actor, local, cancel).await.err(),
            Some("UPDATE_NOT_CONFIGURED")
        );
        assert!(actor.reserve_update().is_ok());
        let http = transport::Http::new().unwrap();
        let (sender, mut cancel) = tokio::sync::watch::channel(true);
        assert_eq!(
            http.fetch(
                "https://127.0.0.1:1/aoc/latest.json".parse().unwrap(),
                100,
                None,
                &mut cancel
            )
            .await
            .err(),
            Some("UPDATE_CANCELLED")
        );
        sender.send_replace(false);
        drop(sender);
        assert_eq!(
            http.fetch(
                "https://127.0.0.1:1/aoc/latest.json".parse().unwrap(),
                100,
                None,
                &mut cancel
            )
            .await
            .err(),
            Some("UPDATE_CANCELLED")
        );
    });
}
#[test]
fn signed_same_version_replacement_and_lower_release_are_refused_within_process() {
    let fixture = Fixture::new();
    let config = fixture.config("https://repo.invalid/aoc/");
    let service = Service::ordinary();
    let now = chrono::Utc::now().timestamp();
    let manifest = fixture.manifest("0.7.19");
    let (manifest, version) = verify_manifest(&config, &fixture.envelope(&manifest), now).unwrap();
    service.remember(&manifest, version.clone()).unwrap();
    let mut renewed = manifest.clone();
    renewed.expires_at += 10;
    service.remember(&renewed, version.clone()).unwrap();
    let mut replacement = manifest.clone();
    replacement.sha256 = "f".repeat(64);
    let (replacement, replacement_version) =
        verify_manifest(&config, &fixture.envelope(&replacement), now).unwrap();
    assert_eq!(
        service.remember(&replacement, replacement_version),
        Err("UPDATE_RELEASE_CHANGED")
    );
    let mut different_size = manifest.clone();
    different_size.size += 1;
    assert_eq!(
        service.remember(&different_size, version),
        Err("UPDATE_RELEASE_CHANGED")
    );
    let lower = fixture.manifest("0.7.18");
    let (lower, lower_version) = verify_manifest(&config, &fixture.envelope(&lower), now).unwrap();
    assert_eq!(
        service.remember(&lower, lower_version),
        Err("UPDATE_ROLLBACK")
    );
    // A process floor is not a durable OS rollback anchor; restart limitations remain explicit.
    Service::ordinary()
        .remember(&lower, stable_version(&lower.version).unwrap())
        .unwrap();
}
#[cfg(windows)]
#[test]
fn one_use_consent_retains_pack_and_local_leases_until_handoff_is_dropped() {
    let fixture = Fixture::new();
    let service = Service::new(Some(fixture.config("https://repo.invalid/aoc/")));
    let actor = Actor::new();
    let (directory, local) = local();
    let weak = Arc::downgrade(&local);
    let manifest = fixture.manifest("0.7.18");
    let view = PlanView {
        plan_id: Id::new(),
        version: manifest.version.clone(),
        bytes: manifest.size,
    };
    service.state.lock().unwrap().prepared = Some(Prepared {
        view: view.clone(),
        manifest,
        created: Instant::now(),
        installer: fixture.bytes.clone(),
        reservation: actor.reserve_update().unwrap(),
        local: local.clone(),
    });
    drop(local);
    assert_eq!(service.consume(Id::new()).err(), Some("UPDATE_STALE_PLAN"));
    assert_eq!(
        actor.action(crate::pack::actor::Input::Check {}),
        Err("LAUNCHER_UPDATE_PENDING")
    );
    let mut verified = service.consume(view.plan_id).unwrap();
    assert_eq!(verified.version, "0.7.18");
    assert_eq!(verified.bytes, fixture.bytes);
    assert!(verified.reservation.validate().is_ok());
    assert!(weak.upgrade().is_some());
    assert_eq!(
        service.consume(view.plan_id).err(),
        Some("UPDATE_STALE_PLAN")
    );
    assert!(actor.reserve_update().is_err());
    assert!(verified.validate().is_ok());
    verified.created = Instant::now() - PLAN_TTL;
    assert_eq!(verified.validate(), Err("UPDATE_EXPIRED"));
    drop(verified);
    assert!(weak.upgrade().is_none());
    assert!(actor.reserve_update().is_ok());
    drop(directory);
}
#[cfg(windows)]
#[test]
fn expired_or_invalidated_consent_is_consumed_and_gate_released() {
    let fixture = Fixture::new();
    let service = Service::ordinary();
    let actor = Actor::new();
    let (_directory, local) = local();
    for mode in ["wall", "monotonic", "blocked", "discard"] {
        let mut manifest = fixture.manifest("0.7.18");
        if mode == "wall" {
            manifest.expires_at = chrono::Utc::now().timestamp();
        }
        let view = PlanView {
            plan_id: Id::new(),
            version: manifest.version.clone(),
            bytes: manifest.size,
        };
        service.state.lock().unwrap().prepared = Some(Prepared {
            view: view.clone(),
            manifest,
            created: if mode == "monotonic" {
                Instant::now() - PLAN_TTL
            } else {
                Instant::now()
            },
            installer: fixture.bytes.clone(),
            reservation: actor.reserve_update().unwrap(),
            local: if mode == "blocked" {
                Arc::new(LocalInstance::unavailable())
            } else {
                local.clone()
            },
        });
        if mode == "discard" {
            assert_eq!(service.discard(Id::new()), Err("UPDATE_STALE_PLAN"));
            service.discard(view.plan_id).unwrap();
        } else {
            assert!(service.consume(view.plan_id).is_err(), "{mode}");
        }
        assert_eq!(
            service.consume(view.plan_id).err(),
            Some("UPDATE_STALE_PLAN")
        );
        assert!(actor.reserve_update().is_ok());
    }
}
