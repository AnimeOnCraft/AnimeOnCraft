use super::*;
#[test]
fn manager_input_has_no_source_path_key_or_shell_capability() {
    for json in [
        r#"{"action":"check","url":"https://evil"}"#,
        r#"{"action":"confirm","plan_id":"old"}"#,
        r#"{"action":"install","path":"F:/world"}"#,
        r#"{"action":"check","key":"x"}"#,
    ] {
        assert!(serde_json::from_str::<manager::Input>(json).is_err());
    }
    let (_directory, local) = local();
    let actor = Actor::new();
    let manager = manager::Manager::new(
        actor.clone(),
        local,
        "AnimeOnCraft".into(),
        "0.7.18".into(),
        false,
    )
    .unwrap();
    let snapshot = manager.snapshot().unwrap();
    assert!(!snapshot.source_configured);
    assert_eq!(snapshot.reason, Some("UPDATE_NOT_CONFIGURED"));
    assert_eq!(
        manager.action(manager::Input::Check {}, || panic!("unconfigured exit")),
        Ok(snapshot)
    );
    assert!(actor.reserve_update().is_ok());
}
#[test]
fn consent_expiry_releases_reservation_without_new_user_input() {
    let fixture = Fixture::new();
    let service = Service::ordinary();
    let actor = Actor::new();
    let (_directory, local) = local();
    let manifest = fixture.manifest("0.7.19");
    let view = PlanView {
        plan_id: Id::new(),
        version: manifest.version.clone(),
        bytes: manifest.size,
    };
    service.state.lock().unwrap().prepared = Some(Prepared {
        view,
        manifest,
        created: Instant::now() - PLAN_TTL,
        installer: fixture.bytes,
        reservation: actor.reserve_update().unwrap(),
        local,
    });
    assert!(actor.reserve_update().is_err());
    assert!(service.expire().unwrap());
    assert!(actor.reserve_update().is_ok());
    assert!(!service.expire().unwrap());
}
#[test]
fn non_executable_bytes_never_acquire_windows_installer_identity() {
    let (_directory, local) = local();
    let mut stage = local.update_stage(&vec![0x51; 1024]).unwrap();
    assert_eq!(
        windows::identity(stage.checked_path().unwrap(), "0.7.19", "AnimeOnCraft"),
        Err("UPDATE_BINARY_IDENTITY")
    );
}
#[test]
#[ignore = "exports ephemeral signatures over the actual isolated new-version NSIS installer"]
fn export_installed_update_fixture() {
    use std::io::Read;
    let directory =
        std::path::PathBuf::from(std::env::var_os("AOC_INSTALLED_UPDATE_DIRECTORY").unwrap())
            .canonicalize()
            .unwrap();
    let tools = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../.tools")
        .canonicalize()
        .unwrap();
    assert_eq!(directory.parent().unwrap(), tools);
    let name = directory.file_name().unwrap().to_str().unwrap();
    let id = name.strip_prefix("installer-acceptance-").unwrap();
    assert_eq!(id.len(), 32);
    assert!(id
        .bytes()
        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)));
    let path = directory.join("signed-installer.exe");
    let product = format!("AnimeOnCraft Updater Test {id}");
    windows::identity(&path, "0.7.19", &product).unwrap();
    assert_eq!(
        windows::identity(&path, "0.7.99", &product),
        Err("UPDATE_BINARY_IDENTITY")
    );
    assert_eq!(
        windows::identity(&path, "0.7.19", "Other"),
        Err("UPDATE_BINARY_IDENTITY")
    );
    let mut fixture = Fixture::new();
    fixture.bytes.clear();
    std::fs::File::open(path)
        .unwrap()
        .take(MAX_INSTALLER + 1)
        .read_to_end(&mut fixture.bytes)
        .unwrap();
    assert!((512..=MAX_INSTALLER).contains(&(fixture.bytes.len() as u64)));
    std::fs::write(directory.join("updater.json"), serde_json::to_vec(&serde_json::json!({"metadata_key":fixture.metadata.pk.to_base64(),"installer_key":fixture.installer.pk.to_base64()})).unwrap()).unwrap();
    std::fs::write(
        directory.join("latest.json"),
        fixture.envelope(&fixture.manifest("0.7.19")),
    )
    .unwrap();
    // A signer mistake is authenticated yet must fail embedded installer VERSION binding at Confirm.
    std::fs::write(
        directory.join("wrong-version.json"),
        fixture.envelope(&fixture.manifest("0.7.99")),
    )
    .unwrap();
}
