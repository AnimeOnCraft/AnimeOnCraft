//! Verify immutable external menu media; cache only checks already proven against the compiled digest.
use crate::account_client::Error;
use sha2::{Digest, Sha256};
use std::{
    io::Read,
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
    time::SystemTime,
};
type Stamp = (PathBuf, String, u64, SystemTime);
static VERIFIED: OnceLock<Mutex<Option<Stamp>>> = OnceLock::new();
pub(crate) fn verify(file: &Path, expected: &str, size: u64) -> Result<(), Error> {
    let fail = "RUNTIME_MOD_UPDATE_REQUIRED";
    let meta = std::fs::metadata(file).map_err(|_| fail)?;
    if meta.len() != size || !meta.is_file() {
        return Err(fail);
    }
    let stamp = (
        file.to_path_buf(),
        expected.to_owned(),
        size,
        meta.modified().map_err(|_| fail)?,
    );
    let mut cache = VERIFIED
        .get_or_init(|| Mutex::new(None))
        .lock()
        .map_err(|_| fail)?;
    if cache.as_ref() == Some(&stamp) {
        return Ok(());
    }
    let mut input = std::fs::File::open(file).map_err(|_| fail)?;
    let mut digest = Sha256::new();
    let mut chunk = [0u8; 262144];
    loop {
        let count = input.read(&mut chunk).map_err(|_| fail)?;
        if count == 0 {
            break;
        }
        digest.update(&chunk[..count]);
    }
    if format!("{:x}", digest.finalize()) != expected {
        return Err(fail);
    }
    *cache = Some(stamp);
    Ok(())
}
pub(crate) fn verify_required(game: &Path) -> Result<(), Error> {
    if let Some(hash) = option_env!("AOC_TITLE_ASSET_SHA256").filter(|h| h.len() == 64) {
        let size = env!("AOC_TITLE_ASSET_BYTES")
            .parse::<u64>()
            .map_err(|_| "RUNTIME_MOD_UPDATE_REQUIRED")?;
        verify(
            &game.join("aoc-assets/title").join(format!("{hash}.zip")),
            hash,
            size,
        )?;
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn missing_corrupt_and_changed_assets_block_launch() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("media.zip");
        let good = b"verified media";
        let hash = format!("{:x}", Sha256::digest(good));
        assert!(verify(&file, &hash, good.len() as u64).is_err());
        std::fs::write(&file, good).unwrap();
        assert!(verify(&file, &hash, good.len() as u64).is_ok());
        assert!(verify(&file, &hash, good.len() as u64).is_ok());
        std::fs::write(&file, b"corrupt").unwrap();
        assert!(verify(&file, &hash, good.len() as u64).is_err());
        std::fs::write(&file, good).unwrap();
        assert!(verify(&file, &"0".repeat(64), good.len() as u64).is_err());
    }
}
