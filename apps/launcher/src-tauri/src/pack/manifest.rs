use super::strict_json;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
pub const MAX_MANIFEST: usize = 2 * 1024 * 1024;
pub const MAX_BYTES: u64 = 20 * 1024 * 1024 * 1024;
pub const MAX_FILES: usize = 10_000;
pub type Error = &'static str;

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Platform {
    pub os: String,
    pub arch: String,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Loader {
    pub id: String,
    pub version: String,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Java {
    pub major: u16,
    pub distribution_ref: String,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Source {
    pub source_id: String,
    pub object_key: String,
}
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
pub enum Side {
    CLIENT,
    SERVER,
    BOTH,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct File {
    pub path: String,
    pub sha256: String,
    pub length: u64,
    pub side: Side,
    pub source_ref: Source,
    pub managed: bool,
}
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Manifest {
    pub schema_version: u8,
    pub pack_id: String,
    pub channel: String,
    pub release_sequence: u64,
    pub platform: Platform,
    pub minecraft_version: String,
    pub loader: Loader,
    pub java: Java,
    pub minimum_launcher_version: String,
    pub integration_protocol_major: u16,
    pub files: Vec<File>,
}
pub struct ReleasePolicy {
    pub pack_id: String,
    pub channel: String,
    pub sequence: u64,
    pub minecraft_version: String,
    pub loader: Loader,
    pub java: Java,
    pub launcher_version: semver::Version,
    pub protocol: u16,
    pub approved_sources: BTreeSet<String>,
}
pub(super) fn validate_policy(policy: &ReleasePolicy) -> Result<(), Error> {
    if !identifier(&policy.pack_id)
        || !identifier(&policy.channel)
        || policy.sequence == 0
        || !version_token(&policy.minecraft_version)
        || !["vanilla", "fabric", "forge", "neoforge"].contains(&policy.loader.id.as_str())
        || !version_token(&policy.loader.version)
        || ![17, 21].contains(&policy.java.major)
        || !identifier(&policy.java.distribution_ref)
        || policy.protocol == 0
        || !policy.launcher_version.pre.is_empty()
        || !policy.launcher_version.build.is_empty()
        || policy.approved_sources.is_empty()
        || policy.approved_sources.len() > 16
        || policy.approved_sources.iter().any(|id| !identifier(id))
    {
        return Err("REPOSITORY_CONFIG_INVALID");
    }
    Ok(())
}
pub struct CheckedManifest {
    manifest: Manifest,
    total: u64,
    digest: String,
}
impl CheckedManifest {
    pub fn manifest(&self) -> &Manifest {
        &self.manifest
    }
    pub fn total(&self) -> u64 {
        self.total
    }
    pub fn digest(&self) -> &str {
        &self.digest
    }
}
pub fn hash(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    format!("{:x}", Sha256::digest(bytes))
}
pub fn digest(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}
fn version_token(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
}
pub fn portable(path: &str) -> bool {
    if path.is_empty() || path.len() > 240 {
        return false;
    }
    path.split('/').all(|part| {
        if part.is_empty()
            || part.len() > 100
            || part.starts_with('.')
            || part.ends_with('.')
            || !part
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
        {
            return false;
        }
        let stem = part.split('.').next().unwrap_or("").to_ascii_uppercase();
        !["CON", "PRN", "AUX", "NUL"].contains(&stem.as_str())
            && !(stem.len() == 4
                && (&stem[..3] == "COM" || &stem[..3] == "LPT")
                && (b'1'..=b'9').contains(&stem.as_bytes()[3]))
    })
}
pub fn managed(path: &str) -> bool {
    if !portable(path) {
        return false;
    }
    let lower = path.to_ascii_lowercase();
    if [
        ".exe", ".bat", ".cmd", ".ps1", ".sh", ".com", ".msi", ".vbs", ".js",
    ]
    .iter()
    .any(|suffix| lower.ends_with(suffix))
    {
        return false;
    }
    if path.starts_with("mods/") {
        return path.ends_with(".jar");
    }
    if path.starts_with("libraries/") {
        return path.ends_with(".jar") || path.ends_with(".json");
    }
    if path.starts_with("versions/") {
        return path.ends_with(".jar") || path.ends_with(".json");
    }
    if path.starts_with("natives/") {
        return path.ends_with(".dll");
    }
    if let Some(object) = path.strip_prefix("assets/objects/") {
        let parts: Vec<_> = object.split('/').collect();
        return parts.len() == 2
            && parts[0].len() == 2
            && parts[1].len() == 40
            && parts[1].starts_with(parts[0])
            && parts.iter().all(|v| {
                v.bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            });
    }
    false
}
fn inventory_scope(path: &str) -> bool {
    let lower = path.to_ascii_lowercase();
    [
        "mods/",
        "libraries/",
        "assets/objects/",
        "versions/",
        "natives/",
    ]
    .iter()
    .any(|prefix| lower.starts_with(prefix))
}
pub fn paths<'a>(items: impl Iterator<Item = &'a str>) -> Result<(), Error> {
    let mut paths = BTreeSet::new();
    for path in items {
        if !portable(path) || !paths.insert(path.to_ascii_lowercase()) {
            return Err("INVALID_PATH");
        }
    }
    for path in &paths {
        let mut prefix = String::new();
        for part in path.split('/').take(path.split('/').count() - 1) {
            if !prefix.is_empty() {
                prefix.push('/');
            }
            prefix.push_str(part);
            if paths.contains(&prefix) {
                return Err("PATH_CONFLICT");
            }
        }
    }
    Ok(())
}
pub fn parse(bytes: &[u8], policy: &ReleasePolicy) -> Result<CheckedManifest, Error> {
    let value = strict_json::parse(bytes, MAX_MANIFEST).map_err(|_| "INVALID_MANIFEST")?;
    let manifest: Manifest = serde_json::from_value(value).map_err(|_| "INVALID_MANIFEST")?;
    if manifest.schema_version != 1
        || !identifier(&manifest.pack_id)
        || !identifier(&manifest.channel)
        || manifest.pack_id != policy.pack_id
        || manifest.channel != policy.channel
        || manifest.release_sequence == 0
        || manifest.release_sequence != policy.sequence
    {
        return Err("RELEASE_MISMATCH");
    }
    if manifest.platform.os != "windows" || manifest.platform.arch != "x86_64" {
        return Err("UNSUPPORTED_PLATFORM");
    }
    let minimum = semver::Version::parse(&manifest.minimum_launcher_version)
        .map_err(|_| "INVALID_MANIFEST")?;
    if minimum > policy.launcher_version || !minimum.pre.is_empty() || !minimum.build.is_empty() {
        return Err("LAUNCHER_UPDATE_REQUIRED");
    }
    if manifest.minecraft_version != policy.minecraft_version
        || manifest.loader.id != policy.loader.id
        || manifest.loader.version != policy.loader.version
        || manifest.java.major != policy.java.major
        || manifest.java.distribution_ref != policy.java.distribution_ref
        || manifest.integration_protocol_major != policy.protocol
    {
        return Err("COMPATIBILITY_MISMATCH");
    }
    if !["vanilla", "fabric", "forge", "neoforge"].contains(&manifest.loader.id.as_str())
        || !version_token(&manifest.minecraft_version)
        || !version_token(&manifest.loader.version)
        || !identifier(&manifest.java.distribution_ref)
        || ![17, 21].contains(&manifest.java.major)
        || manifest.integration_protocol_major == 0
    {
        return Err("UNSUPPORTED_RUNTIME");
    }
    if manifest.files.is_empty() || manifest.files.len() > MAX_FILES {
        return Err("INVALID_MANIFEST");
    }
    paths(manifest.files.iter().map(|f| f.path.as_str()))?;
    let mut total = 0u64;
    let mut object_lengths = BTreeMap::new();
    for file in &manifest.files {
        if !managed(&file.path) || !file.managed || !digest(&file.sha256) {
            return Err("INVALID_FILE");
        }
        if !identifier(&file.source_ref.source_id)
            || !policy.approved_sources.contains(&file.source_ref.source_id)
            || !portable(&file.source_ref.object_key)
        {
            return Err("SOURCE_NOT_APPROVED");
        }
        if file.length == 0 && file.sha256 != hash(&[]) {
            return Err("INVALID_FILE");
        }
        if object_lengths
            .insert(&file.sha256, file.length)
            .is_some_and(|old| old != file.length)
        {
            return Err("INVALID_FILE");
        }
        total = total.checked_add(file.length).ok_or("SIZE_LIMIT")?;
        if total > MAX_BYTES {
            return Err("SIZE_LIMIT");
        }
    }
    Ok(CheckedManifest {
        manifest,
        total,
        digest: hash(bytes),
    })
}
#[derive(Clone, Debug, PartialEq)]
pub struct Fingerprint {
    pub sha256: String,
    pub length: u64,
}
impl Fingerprint {
    fn matches(&self, file: &File) -> bool {
        self.sha256 == file.sha256 && self.length == file.length
    }
}
#[derive(Debug, PartialEq)]
pub enum Operation {
    Install(String),
    Reuse(String),
    Replace(String),
    Remove(String),
}
pub struct Plan {
    pub operations: Vec<Operation>,
    pub uncached_bytes: u64,
    pub backup_bytes: u64,
    pub required_space: u64,
}
/// Pure planning only. Inventory/index must come from the future handle-safe adapter and a trusted prior release.
pub fn plan(
    new: &CheckedManifest,
    old: Option<&CheckedManifest>,
    inventory: &BTreeMap<String, Fingerprint>,
    cache: &BTreeSet<String>,
) -> Result<Plan, Error> {
    paths(
        inventory
            .keys()
            .filter(|p| inventory_scope(p))
            .map(String::as_str),
    )?;
    if inventory.keys().any(|p| inventory_scope(p) && !managed(p)) {
        return Err("FOREIGN_MANAGED_FILE");
    }
    let previous: BTreeMap<_, _> = old
        .into_iter()
        .flat_map(|m| m.manifest.files.iter())
        .filter(|f| f.side != Side::SERVER)
        .map(|f| (f.path.to_ascii_lowercase(), f))
        .collect();
    let selected: BTreeMap<_, _> = new
        .manifest
        .files
        .iter()
        .filter(|f| f.side != Side::SERVER)
        .map(|f| (f.path.to_ascii_lowercase(), f))
        .collect();
    let observed: BTreeMap<_, _> = inventory
        .iter()
        .filter(|(p, _)| managed(p))
        .map(|(p, f)| (p.to_ascii_lowercase(), f))
        .collect();
    let mut operations = Vec::new();
    let mut uncached = 0u64;
    let mut backup = 0u64;
    let mut downloads = BTreeSet::new();
    for (path, file) in &selected {
        match observed.get(path) {
            None => operations.push(Operation::Install(file.path.clone())),
            Some(bytes) => {
                let old = previous.get(path).ok_or("FOREIGN_MANAGED_FILE")?;
                if !bytes.matches(old) {
                    return Err("MODIFIED_MANAGED_FILE");
                }
                if bytes.matches(file) {
                    operations.push(Operation::Reuse(file.path.clone()));
                    continue;
                }
                backup = backup.checked_add(bytes.length).ok_or("SIZE_LIMIT")?;
                operations.push(Operation::Replace(file.path.clone()));
            }
        }
        if !cache.contains(&file.sha256) && downloads.insert(file.sha256.clone()) {
            uncached = uncached.checked_add(file.length).ok_or("SIZE_LIMIT")?;
        }
    }
    for (path, bytes) in &observed {
        if selected.contains_key(path) {
            continue;
        }
        if let Some(old) = previous.get(path) {
            if !bytes.matches(old) {
                return Err("MODIFIED_MANAGED_FILE");
            }
            backup = backup.checked_add(bytes.length).ok_or("SIZE_LIMIT")?;
            operations.push(Operation::Remove(old.path.clone()));
        } else {
            return Err("FOREIGN_MANAGED_FILE");
        }
    }
    // Cache reuse is advisory: the future downloader must rehash every object before using it.
    let staged = selected
        .values()
        .filter(|f| {
            !operations
                .iter()
                .any(|o| matches!(o,Operation::Reuse(p) if p==&f.path))
        })
        .try_fold(0u64, |total, file| {
            total.checked_add(file.length).ok_or("SIZE_LIMIT")
        })?;
    let required = staged
        .checked_add(backup)
        .and_then(|n| n.checked_add(64 * 1024 * 1024))
        .ok_or("SIZE_LIMIT")?;
    Ok(Plan {
        operations,
        uncached_bytes: uncached,
        backup_bytes: backup,
        required_space: required,
    })
}
