//! Detect modpack-only updates even when the companion JAR has not changed.
use crate::account_client::Error;
use serde::Deserialize;
use std::{io::Read, path::Path};

#[derive(Deserialize)]
struct RequiredFile {
    name: String,
    bytes: u64,
}
#[derive(Deserialize)]
struct RequiredPack {
    profile_sha256: String,
    files: Vec<RequiredFile>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Receipt {
    format_version: u8,
    profile_sha256: String,
}
fn required() -> RequiredPack {
    serde_json::from_str(include_str!(concat!(
        env!("OUT_DIR"),
        "/required-client-pack.json"
    )))
    .expect("build-validated client pack")
}
pub(crate) fn verify(game_directory: &Path) -> Result<(), Error> {
    verify_pack(game_directory, &required())
}
fn verify_pack(game_directory: &Path, pack: &RequiredPack) -> Result<(), Error> {
    if pack.profile_sha256.is_empty() {
        return Ok(()); // Game-less CI builds have no bundled profile.
    }
    let missing = "RUNTIME_MOD_UPDATE_REQUIRED";
    let parent = game_directory.parent().ok_or(missing)?;
    let file = std::fs::File::open(parent.join("managed-pack.json")).map_err(|_| missing)?;
    let mut bytes = Vec::new();
    file.take(4097)
        .read_to_end(&mut bytes)
        .map_err(|_| missing)?;
    if bytes.len() > 4096 {
        return Err(missing);
    }
    let receipt: Receipt = serde_json::from_slice(&bytes).map_err(|_| missing)?;
    if receipt.format_version != 1 || receipt.profile_sha256 != pack.profile_sha256 {
        return Err(missing);
    }
    for file in &pack.files {
        let meta =
            std::fs::metadata(game_directory.join("mods").join(&file.name)).map_err(|_| missing)?;
        if !meta.is_file() || meta.len() != file.bytes {
            return Err(missing);
        }
    }
    Ok(())
}

#[cfg(test)]
pub(crate) fn prepare_fixture(game_directory: &Path) {
    let pack = required();
    std::fs::create_dir_all(game_directory.join("mods")).unwrap();
    for file in &pack.files {
        // Snapshot tests need file metadata, not hundreds of MB of copied JARs.
        std::fs::File::create(game_directory.join("mods").join(&file.name))
            .unwrap()
            .set_len(file.bytes)
            .unwrap();
    }
    std::fs::write(
        game_directory.parent().unwrap().join("managed-pack.json"),
        serde_json::json!({"format_version":1,"profile_sha256":pack.profile_sha256}).to_string(),
    )
    .unwrap();
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn modpack_update_with_unchanged_companion_cannot_skip_preparation() {
        let folder = tempfile::tempdir().unwrap();
        let game = folder.path().join("client");
        std::fs::create_dir_all(game.join("mods")).unwrap();
        std::fs::write(game.join("mods/companion.jar"), b"unchanged").unwrap();
        let pack = RequiredPack {
            profile_sha256: "new-profile".into(),
            files: vec![
                RequiredFile {
                    name: "companion.jar".into(),
                    bytes: 9,
                },
                RequiredFile {
                    name: "cavebiomes.jar".into(),
                    bytes: 3,
                },
            ],
        };
        let receipt = folder.path().join("managed-pack.json");
        assert_eq!(
            verify_pack(&game, &pack),
            Err("RUNTIME_MOD_UPDATE_REQUIRED")
        );
        std::fs::write(
            &receipt,
            r#"{"format_version":1,"profile_sha256":"old-profile"}"#,
        )
        .unwrap();
        assert_eq!(
            verify_pack(&game, &pack),
            Err("RUNTIME_MOD_UPDATE_REQUIRED")
        );
        std::fs::write(
            &receipt,
            r#"{"format_version":1,"profile_sha256":"new-profile"}"#,
        )
        .unwrap();
        assert_eq!(
            verify_pack(&game, &pack),
            Err("RUNTIME_MOD_UPDATE_REQUIRED")
        );
        std::fs::write(game.join("mods/cavebiomes.jar"), b"new").unwrap();
        assert_eq!(verify_pack(&game, &pack), Ok(()));
        std::fs::write(game.join("mods/cavebiomes.jar"), b"truncated").unwrap();
        assert_eq!(
            verify_pack(&game, &pack),
            Err("RUNTIME_MOD_UPDATE_REQUIRED")
        );
    }
    #[test]
    fn malformed_or_oversized_receipt_never_enables_play() {
        let folder = tempfile::tempdir().unwrap();
        let game = folder.path().join("client");
        let pack = RequiredPack {
            profile_sha256: "expected".into(),
            files: vec![],
        };
        for value in [b"{}".to_vec(), vec![b' '; 4097]] {
            std::fs::write(folder.path().join("managed-pack.json"), value).unwrap();
            assert_eq!(
                verify_pack(&game, &pack),
                Err("RUNTIME_MOD_UPDATE_REQUIRED")
            );
        }
    }
}
