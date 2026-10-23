//! Explicit pack-acceptance build only. Fixed workspace child, no production FS/config IPC.
use super::manifest::{Java, Loader, ReleasePolicy};
use std::{collections::BTreeSet, path::PathBuf, sync::Arc};
pub(crate) const ORIGIN: &str = "https://localhost:39441/aoc/v1/";
pub(crate) struct Fixture {
    pub(crate) directory: PathBuf,
    pub(crate) bootstrap: Vec<u8>,
    pub(crate) certificate: Vec<u8>,
    pub(crate) sequence: u64,
}
fn read(directory: &std::path::Path, name: &str, limit: usize) -> Result<Vec<u8>, &'static str> {
    use std::io::Read;
    let path = directory.join(name);
    if path.canonicalize().map_err(|_| "PACK_FIXTURE_INVALID")? != path
        || std::fs::symlink_metadata(&path)
            .map_err(|_| "PACK_FIXTURE_INVALID")?
            .file_type()
            .is_symlink()
    {
        return Err("PACK_FIXTURE_INVALID");
    }
    let mut bytes = Vec::new();
    std::fs::File::open(path)
        .map_err(|_| "PACK_FIXTURE_INVALID")?
        .take(limit as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "PACK_FIXTURE_INVALID")?;
    if bytes.len() > limit {
        return Err("PACK_FIXTURE_INVALID");
    }
    Ok(bytes)
}
pub(crate) fn load() -> Result<Fixture, &'static str> {
    let workspace = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../..")
        .canonicalize()
        .map_err(|_| "PACK_FIXTURE_INVALID")?;
    let executable = std::env::current_exe()
        .and_then(|p| p.canonicalize())
        .map_err(|_| "PACK_FIXTURE_INVALID")?;
    if executable != workspace.join(".tools/pack-acceptance.exe") {
        return Err("PACK_FIXTURE_INVALID");
    }
    let input = PathBuf::from(
        std::env::var_os("AOC_PACK_ACCEPTANCE_DIRECTORY").ok_or("PACK_FIXTURE_INVALID")?,
    );
    let directory = input.canonicalize().map_err(|_| "PACK_FIXTURE_INVALID")?;
    let name = directory
        .file_name()
        .and_then(|n| n.to_str())
        .ok_or("PACK_FIXTURE_INVALID")?;
    let uuid = name
        .strip_prefix("pack-acceptance-")
        .and_then(|s| uuid::Uuid::parse_str(s).ok())
        .ok_or("PACK_FIXTURE_INVALID")?;
    if name != format!("pack-acceptance-{}", uuid.simple())
        || directory != workspace.join(".tools").join(name)
        || input.file_name() != directory.file_name()
        || input.parent().and_then(|p| p.canonicalize().ok()).as_ref()
            != Some(&workspace.join(".tools"))
        || directory
            .canonicalize()
            .map_err(|_| "PACK_FIXTURE_INVALID")?
            != directory
    {
        return Err("PACK_FIXTURE_INVALID");
    }
    #[derive(serde::Deserialize)]
    #[serde(deny_unknown_fields)]
    struct Choice {
        sequence: u64,
    }
    let choice: Choice = serde_json::from_value(super::strict_json::parse(
        &read(&directory, "choice.json", 128)?,
        128,
    )?)
    .map_err(|_| "PACK_FIXTURE_INVALID")?;
    if !matches!(choice.sequence, 1 | 2) {
        return Err("PACK_FIXTURE_INVALID");
    }
    Ok(Fixture {
        bootstrap: read(&directory, "bootstrap.json", 1024 * 1024)?,
        certificate: read(&directory, "certificate.pem", 16384)?,
        directory,
        sequence: choice.sequence,
    })
}
pub(crate) fn policy(sequence: u64) -> ReleasePolicy {
    ReleasePolicy {
        pack_id: "animeoncraft".into(),
        channel: "stable".into(),
        sequence,
        minecraft_version: "test-only".into(),
        loader: Loader {
            id: "fabric".into(),
            version: "fixture-v1".into(),
        },
        java: Java {
            major: 21,
            distribution_ref: "fixture-jre".into(),
        },
        launcher_version: env!("CARGO_PKG_VERSION").parse().unwrap(),
        protocol: 1,
        approved_sources: BTreeSet::from(["fixture".into()]),
    }
}
pub(crate) fn actor(
    local: Arc<super::local::LocalInstance>,
    fixture: Fixture,
) -> Result<super::actor::Actor, &'static str> {
    super::ntfs::acceptance_actor(local, fixture)
}
