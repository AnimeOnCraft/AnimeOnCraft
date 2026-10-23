fn main() {
    let bootstrap = std::path::Path::new("../../../.tools/launcher-bootstrap/bootstrap.json");
    println!("cargo:rerun-if-changed={}", bootstrap.display());
    let expected = if bootstrap.is_file() {
        let value: serde_json::Value =
            serde_json::from_slice(&std::fs::read(bootstrap).expect("read bootstrap manifest"))
                .expect("valid bootstrap manifest");
        let hash = value["admission_sha256"]
            .as_str()
            .expect("bootstrap integration hash");
        assert!(
            hash.len() == 64 && hash.bytes().all(|b| b.is_ascii_hexdigit()),
            "invalid integration hash"
        );
        hash.to_owned()
    } else {
        String::new()
    };
    println!("cargo:rustc-env=AOC_REQUIRED_ADMISSION_SHA256={expected}");
    let profile_path =
        std::path::Path::new("../../../.tools/launcher-bootstrap/minecraft/modpack/profile.json");
    println!("cargo:rerun-if-changed={}", profile_path.display());
    let required_pack = if profile_path.is_file() {
        use sha2::Digest;
        let bytes = std::fs::read(profile_path).expect("read bundled modpack profile");
        let profile: serde_json::Value =
            serde_json::from_slice(&bytes).expect("valid modpack profile");
        let files: Vec<_> = profile["mods"]
            .as_array()
            .expect("modpack mods")
            .iter()
            .filter(|item| {
                item["install_sides"]
                    .as_array()
                    .is_some_and(|sides| sides.iter().any(|side| side == "client"))
            })
            .map(|item| {
                let name = item["file"]["name"].as_str().expect("mod filename");
                assert!(!name.contains(['/', '\\']) && name.ends_with(".jar"));
                let size = item["file"]["size"].as_u64().expect("mod size");
                assert!(size > 0);
                serde_json::json!({"name":name,"bytes":size})
            })
            .collect();
        assert!(!files.is_empty());
        serde_json::json!({"profile_sha256":format!("{:x}",sha2::Sha256::digest(&bytes)),"files":files})
    } else {
        serde_json::json!({"profile_sha256":"","files":[]})
    };
    std::fs::write(
        std::path::Path::new(&std::env::var_os("OUT_DIR").unwrap())
            .join("required-client-pack.json"),
        serde_json::to_vec(&required_pack).unwrap(),
    )
    .expect("write required client pack");
    println!("cargo:rerun-if-env-changed=AOC_API_ORIGIN");
    println!("cargo:rerun-if-env-changed=AOC_LOCAL_BUILD");
    if let Ok(origin) = std::env::var("AOC_API_ORIGIN") {
        let local_build = std::env::var("AOC_LOCAL_BUILD").ok().as_deref() == Some("1");
        assert!(
            (origin.starts_with("https://") || (local_build && origin == "http://127.0.0.1:3000"))
                && !origin.contains(['@', '\r', '\n'])
                && origin.len() < 2048,
            "Release API must be HTTPS; HTTP is allowed only for an explicit loopback local build"
        );
        use sha2::Digest;
        let digest = sha2::Sha256::digest(origin.trim_end_matches('/').as_bytes());
        println!(
            "cargo:rustc-env=AOC_NATIVE_ENVIRONMENT=friends-{:x}",
            digest
        );
    }
    println!("cargo:rerun-if-changed=windows-app-manifest.xml");
    tauri_build::try_build(
        tauri_build::Attributes::new()
            .windows_attributes(
                tauri_build::WindowsAttributes::new()
                    .app_manifest(include_str!("windows-app-manifest.xml")),
            )
            .app_manifest(tauri_build::AppManifest::new().commands(&[
                "native_health",
                "validate_launcher_settings",
                "load_launcher_settings",
                "save_launcher_settings",
                "read_public_resource",
                "account_snapshot",
                "account_action",
                "game_stop",
                "game_snapshot",
                "game_server_status",
                "game_launch",
                "game_prepare",
                "game_preparation_snapshot",
                "game_profile_read",
                "skin_read",
                "skin_save",
                "cape_read",
                "cape_save",
                "game_profile_create",
                "player_read",
                "player_prepare_action",
                "player_submit_action",
                "security_sessions",
                "security_action",
                "pack_availability",
                "installer_snapshot",
                "installer_action",
                "installer_local_state",
                "launcher_update_snapshot",
                "launcher_update_action",
                "simple_update_snapshot",
                "simple_update_action",
            ])),
    )
    .expect("failed to build launcher permissions")
}
