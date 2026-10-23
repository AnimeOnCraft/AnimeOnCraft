//! TUF verification foundation. A production TrustStore constructor is intentionally absent until
//! the Windows retained-handle filesystem adapter can protect tough's datastore IO.
use super::{
    manifest::{self, CheckedManifest, ReleasePolicy},
    strict_json,
};
use futures::TryStreamExt;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashSet};
use std::path::{Path, PathBuf};
use tough::{
    schema::{RoleType, Root, Signed},
    ExpirationEnforcement, Limits, RepositoryLoader, Transport,
};
pub type Error = &'static str;
const CHECKPOINT: &str = "aoc-trust.json";
const META: [&str; 4] = [
    "root.json",
    "timestamp.json",
    "snapshot.json",
    "targets.json",
];
#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Checkpoint {
    pub(super) format: u8,
    pub(super) namespace: String,
    pub(super) anchor: String,
    pub(super) phase: String,
    pub(super) metadata: BTreeMap<String, String>,
    pub(super) time_floor: String,
    pub(super) sequence: u64,
    pub(super) manifest_digest: Option<String>,
}
pub struct TrustStore {
    directory: PathBuf,
    namespace: String,
    #[cfg(windows)]
    permit: Option<super::ntfs::ScratchPermit>,
}
impl TrustStore {
    #[cfg(test)]
    pub fn fixture(directory: PathBuf, namespace: &str) -> Self {
        Self {
            directory,
            namespace: namespace.into(),
            #[cfg(windows)]
            permit: None,
        }
    }
    #[cfg(windows)]
    pub(super) fn native_scratch(permit: super::ntfs::ScratchPermit) -> Result<Self, Error> {
        permit.validate().map_err(|_| "TRUST_STATE_INVALID")?;
        Ok(Self {
            directory: permit.directory().to_owned(),
            namespace: "ntfs-bridge-v1".into(),
            permit: Some(permit),
        })
    }
    fn read(&self, anchor: &str) -> Result<Option<Checkpoint>, Error> {
        #[cfg(windows)]
        if let Some(permit) = &self.permit {
            permit.validate().map_err(|_| "TRUST_STATE_INVALID")?;
        }
        let bytes = match std::fs::read(self.directory.join(CHECKPOINT)) {
            Ok(bytes) => bytes,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                if std::fs::read_dir(&self.directory)
                    .map_err(|_| "TRUST_STATE_INVALID")?
                    .next()
                    .is_some()
                {
                    return Err("TRUST_STATE_INVALID");
                }
                return Ok(None);
            }
            Err(_) => return Err("TRUST_STATE_INVALID"),
        };
        let value = strict_json::parse(&bytes, 16384).map_err(|_| "TRUST_STATE_INVALID")?;
        let record: Checkpoint =
            serde_json::from_value(value).map_err(|_| "TRUST_STATE_INVALID")?;
        if record.format != 1
            || record.namespace != self.namespace
            || record.anchor != anchor
            || record.phase != "READY"
            || record.metadata.len() != 4
            || record.metadata.keys().any(|k| !META.contains(&k.as_str()))
            || record.metadata.values().any(|v| !manifest::digest(v))
            || record
                .manifest_digest
                .as_ref()
                .is_some_and(|v| !manifest::digest(v))
            || (record.sequence == 0) != record.manifest_digest.is_none()
        {
            return Err("TRUST_STATE_INVALID");
        }
        let floor = chrono::DateTime::parse_from_rfc3339(&record.time_floor)
            .map_err(|_| "TRUST_STATE_INVALID")?;
        let clock = self.clock()?;
        if clock < floor {
            return Err("CLOCK_ERROR");
        }
        for (file, expected) in &record.metadata {
            let bytes =
                std::fs::read(self.directory.join(file)).map_err(|_| "TRUST_STATE_INVALID")?;
            if manifest::hash(&bytes) != *expected {
                return Err("TRUST_STATE_INVALID");
            }
            strict_json::parse(&bytes, manifest::MAX_MANIFEST)
                .map_err(|_| "TRUST_STATE_INVALID")?;
        }
        Ok(Some(record))
    }
    fn clock(&self) -> Result<chrono::DateTime<chrono::FixedOffset>, Error> {
        let bytes = std::fs::read(self.directory.join("latest_known_time.json"))
            .map_err(|_| "TRUST_STATE_INVALID")?;
        let value = strict_json::parse(&bytes, 256).map_err(|_| "TRUST_STATE_INVALID")?;
        let time =
            chrono::DateTime::parse_from_rfc3339(value.as_str().ok_or("TRUST_STATE_INVALID")?)
                .map_err(|_| "TRUST_STATE_INVALID")?;
        if time > chrono::Utc::now() {
            return Err("CLOCK_ERROR");
        }
        Ok(time)
    }
    fn write(&self, checkpoint: &Checkpoint) -> Result<(), Error> {
        use std::io::Write;
        let path = self.directory.join("aoc-trust.new");
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)
            .map_err(|_| "TRUST_STATE_INVALID")?;
        file.write_all(&serde_json::to_vec(checkpoint).map_err(|_| "TRUST_STATE_INVALID")?)
            .and_then(|_| file.sync_all())
            .map_err(|_| "TRUST_STATE_INVALID")?;
        drop(file);
        std::fs::rename(path, self.directory.join(CHECKPOINT)).map_err(|_| "TRUST_STATE_INVALID")
    }
    pub fn directory(&self) -> &Path {
        &self.directory
    }
}
pub(super) fn root_policy(bytes: &[u8]) -> Result<(), Error> {
    let value = strict_json::parse(bytes, 262144).map_err(|_| "TUF_REJECTED")?;
    let root: Signed<Root> = serde_json::from_value(value).map_err(|_| "TUF_REJECTED")?;
    root.signed.verify_role(&root).map_err(|_| "TUF_REJECTED")?;
    if !root.signed.consistent_snapshot || root.signed.roles.len() != 4 {
        return Err("TUF_POLICY_REJECTED");
    }
    let mut keys = HashSet::new();
    for role in [
        RoleType::Root,
        RoleType::Targets,
        RoleType::Snapshot,
        RoleType::Timestamp,
    ] {
        let config = root.signed.roles.get(&role).ok_or("TUF_POLICY_REJECTED")?;
        if role == RoleType::Root {
            if config.threshold.get() != 2 || config.keyids.len() != 3 {
                return Err("TUF_POLICY_REJECTED");
            }
        } else if config.threshold.get() != 1 || config.keyids.len() != 1 {
            return Err("TUF_POLICY_REJECTED");
        }
        for key in &config.keyids {
            if !keys.insert(key.clone()) {
                return Err("TUF_POLICY_REJECTED");
            }
        }
    }
    Ok(())
}
pub struct VerifiedRelease {
    manifest: CheckedManifest,
    anchor: String,
    expires: chrono::DateTime<chrono::Utc>,
    not_before: chrono::DateTime<chrono::Utc>,
    #[cfg(windows)]
    historical: bool,
}
impl VerifiedRelease {
    pub fn manifest(&self) -> &CheckedManifest {
        &self.manifest
    }
    pub(super) fn anchor(&self) -> &str {
        &self.anchor
    }
    pub(super) fn current(&self) -> Result<(), Error> {
        #[cfg(windows)]
        if self.historical {
            return Err("HISTORICAL_RELEASE_NOT_CURRENT");
        }
        let now = chrono::Utc::now();
        if now < self.not_before {
            return Err("CLOCK_ERROR");
        }
        if now >= self.expires {
            Err("RELEASE_EXPIRED")
        } else {
            Ok(())
        }
    }
    #[cfg(test)]
    pub(super) fn fixture_expire(&mut self) {
        self.expires = chrono::Utc::now() - chrono::Duration::seconds(1);
    }
}
/// This adapter requires an explicitly selected transport. Production has neither a root nor a
/// safe datastore permit; the default transport (including file://) is never used.
pub async fn verify<T: Transport + Clone + Send + Sync + 'static>(
    bootstrap: &[u8],
    metadata_url: reqwest::Url,
    targets_url: reqwest::Url,
    transport: T,
    store: &TrustStore,
    policy: &ReleasePolicy,
) -> Result<VerifiedRelease, Error> {
    for url in [&metadata_url, &targets_url] {
        if url.scheme() != "https"
            || !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
            || !url.path().ends_with('/')
        {
            return Err("SOURCE_NOT_APPROVED");
        }
    }
    if chrono::Utc::now().timestamp() < 1_577_836_800 {
        return Err("CLOCK_ERROR");
    }
    root_policy(bootstrap)?;
    let anchor = manifest::hash(bootstrap);
    let previous = store.read(&anchor)?;
    let root = if previous.is_some() {
        std::fs::read(store.directory.join("root.json")).map_err(|_| "TRUST_STATE_INVALID")?
    } else {
        bootstrap.to_vec()
    };
    root_policy(&root)?;
    let mut checkpoint = previous.unwrap_or(Checkpoint {
        format: 1,
        namespace: store.namespace.clone(),
        anchor: anchor.clone(),
        phase: "READY".into(),
        metadata: BTreeMap::new(),
        time_floor: "2020-01-01T00:00:00Z".into(),
        sequence: 0,
        manifest_digest: None,
    });
    checkpoint.phase = "UPDATING".into();
    store.write(&checkpoint)?;
    let guarded =
        super::transport::Guarded::new(transport, metadata_url.clone(), targets_url.clone());
    let repo = RepositoryLoader::new(&root, metadata_url, targets_url)
        .transport(guarded)
        .datastore(&store.directory)
        .expiration_enforcement(ExpirationEnforcement::Safe)
        .limits(Limits {
            max_root_size: 262144,
            max_targets_size: manifest::MAX_MANIFEST as u64,
            max_timestamp_size: 16384,
            max_snapshot_size: 262144,
            max_root_updates: 32,
        })
        .load()
        .await
        .map_err(|_| "TUF_REJECTED")?;
    root_policy(&serde_json::to_vec(repo.root()).map_err(|_| "TUF_REJECTED")?)?;
    if repo
        .targets()
        .signed
        .delegations
        .as_ref()
        .is_some_and(|d| !d.roles.is_empty() || !d.keys.is_empty())
    {
        return Err("TUF_POLICY_REJECTED");
    }
    checkpoint.metadata.clear();
    for file in META {
        let bytes = std::fs::read(store.directory.join(file)).map_err(|_| "TRUST_STATE_INVALID")?;
        strict_json::parse(&bytes, manifest::MAX_MANIFEST).map_err(|_| "TRUST_STATE_INVALID")?;
        checkpoint
            .metadata
            .insert(file.into(), manifest::hash(&bytes));
    }
    checkpoint.phase = "READY".into();
    checkpoint.time_floor = store.clock()?.to_rfc3339();
    store.write(&checkpoint)?;
    if policy.sequence < checkpoint.sequence {
        return Err("RELEASE_ROLLBACK");
    }
    let name = tough::TargetName::new(format!(
        "packs/{}/{}/manifest.json",
        policy.channel, policy.sequence
    ))
    .map_err(|_| "INVALID_MANIFEST")?;
    let metadata = repo
        .targets()
        .signed
        .targets
        .get(&name)
        .ok_or("RELEASE_NOT_FOUND")?;
    if metadata.length > manifest::MAX_MANIFEST as u64 {
        return Err("SIZE_LIMIT");
    }
    let mut stream = repo
        .read_target(&name)
        .await
        .map_err(|_| "TUF_REJECTED")?
        .ok_or("RELEASE_NOT_FOUND")?;
    let mut bytes = Vec::new();
    while let Some(chunk) = stream.try_next().await.map_err(|_| "TUF_REJECTED")? {
        if bytes.len() + chunk.len() > manifest::MAX_MANIFEST {
            return Err("SIZE_LIMIT");
        }
        bytes.extend_from_slice(&chunk);
    }
    let checked = manifest::parse(&bytes, policy)?;
    if policy.sequence == checkpoint.sequence
        && checkpoint
            .manifest_digest
            .as_ref()
            .is_some_and(|old| old != checked.digest())
    {
        return Err("RELEASE_REPLACED");
    }
    checkpoint.sequence = policy.sequence;
    checkpoint.manifest_digest = Some(checked.digest().into());
    checkpoint.time_floor = store.clock()?.to_rfc3339();
    store.write(&checkpoint)?;
    let expires = [
        repo.root().signed.expires,
        repo.timestamp().signed.expires,
        repo.snapshot().signed.expires,
        repo.targets().signed.expires,
    ]
    .into_iter()
    .min()
    .ok_or("TUF_REJECTED")?
    .to_string();
    let expires = chrono::DateTime::parse_from_rfc3339(&expires)
        .map_err(|_| "TUF_REJECTED")?
        .with_timezone(&chrono::Utc);
    let verified = VerifiedRelease {
        manifest: checked,
        anchor,
        expires,
        not_before: chrono::Utc::now(),
        #[cfg(windows)]
        historical: false,
    };
    verified.current()?;
    Ok(verified)
}

#[cfg(windows)]
pub(super) mod cached;
