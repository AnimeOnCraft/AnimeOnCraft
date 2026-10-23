//! Fixed development namespace, immutable receipts and OS share-mode lifetime lease.
use super::{
    owned::{OwnedRoot, Receipt},
    *,
};
use crate::pack::{
    local::{Status, ENVIRONMENT},
    manifest, strict_json,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeSet,
    time::{Duration, Instant},
};
const NAMESPACE: &str = "AnimeOnCraft.InstallDevelopment.v1";
const CAP: usize = 16384;
const NAMES: [&str; 7] = [
    "aoc-instance.json",
    "namespace.json",
    "lease.lock",
    "ready.json",
    "control",
    "instance",
    "cache",
];
type PResult<T> = std::result::Result<T, Status>;
fn checked<T>(result: Result<T>) -> PResult<T> {
    result.map_err(|_| Status::Blocked)
}
fn encoded<T: Serialize>(value: &T) -> PResult<Vec<u8>> {
    serde_json::to_vec(value).map_err(|_| Status::Blocked)
}
fn decode<T: serde::de::DeserializeOwned>(bytes: &[u8]) -> PResult<T> {
    serde_json::from_value(strict_json::parse(bytes, CAP).map_err(|_| Status::Blocked)?)
        .map_err(|_| Status::Blocked)
}
fn read(dir: &Arc<Dir>, name: &str) -> PResult<(HeldFile, Vec<u8>)> {
    let mut file = checked(dir.file(name, false))?;
    let mut bytes = Vec::new();
    (&mut file.file)
        .take(CAP as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| Status::Blocked)?;
    if bytes.len() > CAP {
        return Err(Status::Blocked);
    }
    checked(file.fingerprint(bytes.len() as u64, &manifest::hash(&bytes)))?;
    Ok((file, bytes))
}
fn put<T: Serialize>(dir: &Arc<Dir>, name: &str, value: &T) -> PResult<HeldFile> {
    let bytes = encoded(value)?;
    if bytes.len() > CAP {
        return Err(Status::Blocked);
    }
    let mut file = checked(dir.file(name, true))?;
    checked(file.write_new(&bytes))?;
    checked(file.fingerprint(bytes.len() as u64, &manifest::hash(&bytes)))?;
    Ok(file)
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Namespace {
    format: u8,
    environment: String,
    parent: Identity,
    root: Receipt,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct LeaseRecord {
    format: u8,
    environment: String,
    root: Identity,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Ready {
    format: u8,
    environment: String,
    namespace: String,
    control: Receipt,
    instance: Receipt,
    cache: Receipt,
}
pub(crate) struct Lifetime {
    namespace: OwnedRoot,
    control: OwnedRoot,
    instance: OwnedRoot,
    cache: OwnedRoot,
    held: Vec<HeldFile>,
}
impl Lifetime {
    pub(in crate::pack) fn update_journal(
        &mut self,
        create: bool,
    ) -> std::result::Result<Option<super::updater::pending::Journal>, &'static str> {
        self.validate().map_err(|_| "UPDATE_LOCAL_UNAVAILABLE")?;
        let parent = self
            .namespace
            .dir
            ._parent
            .as_ref()
            .ok_or("UPDATE_LOCAL_UNAVAILABLE")?;
        #[derive(Serialize)]
        struct Binding<'a> {
            format: u8,
            purpose: &'static str,
            parent: &'a Identity,
            namespace: &'a Receipt,
        }
        let bytes = encoded(&Binding {
            format: 1,
            purpose: "launcher-update-journal-v1",
            parent: &parent.identity,
            namespace: &self.namespace.receipt,
        })
        .map_err(|_| "UPDATE_LOCAL_UNAVAILABLE")?;
        let result =
            super::updater::pending::Journal::open(parent, &manifest::hash(&bytes), create);
        self.validate().map_err(|_| "UPDATE_LOCAL_UNAVAILABLE")?;
        result
    }
    pub(in crate::pack) fn update_stage(
        &mut self,
        bytes: &[u8],
    ) -> std::result::Result<super::updater::Stage, &'static str> {
        self.validate().map_err(|_| "UPDATE_LOCAL_UNAVAILABLE")?;
        let parent = self
            .namespace
            .dir
            ._parent
            .as_ref()
            .ok_or("UPDATE_LOCAL_UNAVAILABLE")?;
        let result = super::updater::Stage::create(parent, bytes);
        self.validate().map_err(|_| "UPDATE_LOCAL_UNAVAILABLE")?;
        result
    }
    pub(in crate::pack) fn engine(
        &mut self,
        bootstrap: &[u8],
    ) -> std::result::Result<super::Engine, &'static str> {
        self.validate().map_err(|_| "LOCAL_STORE_BLOCKED")?;
        let parent = self
            .namespace
            .dir
            ._parent
            .as_ref()
            .ok_or("LOCAL_STORE_BLOCKED")?;
        let result = super::Engine::open_development(parent, bootstrap);
        self.validate().map_err(|_| "LOCAL_STORE_BLOCKED")?;
        result
    }
    // Test-only preflight binding. It neither extends the empty layout nor exposes
    // native roots/receipts to IPC; backend schema and persistent trust are later gates.
    #[cfg(test)]
    pub(crate) fn backend_binding(&mut self, anchor: &str) -> PResult<String> {
        self.validate()?;
        #[derive(Serialize)]
        struct Binding<'a> {
            format: u8,
            purpose: &'static str,
            environment: &'static str,
            anchor: &'a str,
            parent: &'a Identity,
            namespace: &'a Receipt,
            control: &'a Receipt,
            instance: &'a Receipt,
            cache: &'a Receipt,
            lease: &'a Identity,
        }
        let bytes = encoded(&Binding {
            format: 1,
            purpose: "lease-backend-preflight-v1",
            environment: ENVIRONMENT,
            anchor,
            parent: &self
                .namespace
                .dir
                ._parent
                .as_ref()
                .ok_or(Status::Blocked)?
                .identity,
            namespace: &self.namespace.receipt,
            control: &self.control.receipt,
            instance: &self.instance.receipt,
            cache: &self.cache.receipt,
            lease: &self.held.first().ok_or(Status::Blocked)?.identity,
        })?;
        Ok(manifest::hash(&bytes))
    }
    pub(crate) fn open(local_data: &Path) -> PResult<Self> {
        let parent = checked(Dir::existing(local_data))?;
        Self::open_parent(&parent, &mut |_| {})
    }
    fn open_parent(parent: &Arc<Dir>, hook: &mut impl FnMut(&str)) -> PResult<Self> {
        checked(parent.unchanged())?;
        // Only an actual single-leaf NAME_NOT_FOUND permits create. No path fallback.
        let existing = relative(
            parent,
            &mut checked(portable_name(NAMESPACE))?,
            true,
            false,
            None,
        );
        match existing {
            Err(Error::Nt(code)) if code as u32 == 0xc0000034 => Self::create(parent, hook),
            Err(_) => Err(Status::Blocked),
            Ok(file) => {
                let protection = Arc::new(checked(Protection::current())?);
                let identity = checked(inspect(&file, true))?;
                if identity.volume != parent.identity.volume {
                    return Err(Status::Blocked);
                }
                checked(protection.check(&file, true))?;
                let dir = Arc::new(Dir {
                    file,
                    identity,
                    _parent: Some(parent.clone()),
                    protection: Some(protection),
                });
                Self::reopen(parent, dir)
            }
        }
    }
    fn create(parent: &Arc<Dir>, hook: &mut impl FnMut(&str)) -> PResult<Self> {
        let namespace = checked(OwnedRoot::create_scoped(parent, NAMESPACE, ENVIRONMENT))?;
        hook("namespace-created");
        let lease = put(
            &namespace.dir,
            "lease.lock",
            &LeaseRecord {
                format: 1,
                environment: ENVIRONMENT.into(),
                root: namespace.dir.identity.clone(),
            },
        )?;
        hook("lease-created");
        let receipt = put(
            &namespace.dir,
            "namespace.json",
            &Namespace {
                format: 1,
                environment: ENVIRONMENT.into(),
                parent: parent.identity.clone(),
                root: namespace.receipt.clone(),
            },
        )?;
        hook("namespace-sealed");
        let control = checked(OwnedRoot::create_scoped(
            &namespace.dir,
            "control",
            ENVIRONMENT,
        ))?;
        hook("control-created");
        let instance = checked(OwnedRoot::create_scoped(
            &namespace.dir,
            "instance",
            ENVIRONMENT,
        ))?;
        hook("instance-created");
        let cache = checked(OwnedRoot::create_scoped(
            &namespace.dir,
            "cache",
            ENVIRONMENT,
        ))?;
        hook("cache-created");
        let namespace_hash = receipt.verified.as_ref().ok_or(Status::Blocked)?.1.clone();
        let ready = put(
            &namespace.dir,
            "ready.json",
            &Ready {
                format: 1,
                environment: ENVIRONMENT.into(),
                namespace: namespace_hash,
                control: control.receipt.clone(),
                instance: instance.receipt.clone(),
                cache: cache.receipt.clone(),
            },
        )?;
        hook("ready-sealed");
        let mut lifetime = Self {
            namespace,
            control,
            instance,
            cache,
            held: vec![lease, receipt, ready],
        };
        lifetime.hold_markers()?;
        lifetime.validate()?;
        Ok(lifetime)
    }
    fn reopen(parent: &Arc<Dir>, dir: Arc<Dir>) -> PResult<Self> {
        // Existing file is never opened write. DELETE desired + share READ means
        // two cooperating lifetime holders cannot open it concurrently, across processes.
        let lease = match dir.file("lease.lock", false) {
            Err(Error::Nt(code)) if code as u32 == 0xc0000043 => return Err(Status::Busy),
            result => checked(result)?,
        };
        let mut lease = lease;
        let mut lease_bytes = Vec::new();
        (&mut lease.file)
            .take(CAP as u64 + 1)
            .read_to_end(&mut lease_bytes)
            .map_err(|_| Status::Blocked)?;
        let record: LeaseRecord = decode(&lease_bytes)?;
        if record.format != 1 || record.environment != ENVIRONMENT || record.root != dir.identity {
            return Err(Status::Blocked);
        }
        checked(lease.fingerprint(lease_bytes.len() as u64, &manifest::hash(&lease_bytes)))?;
        let (receipt, bytes) = read(&dir, "namespace.json")?;
        let value: Namespace = decode(&bytes)?;
        if value.format != 1 || value.environment != ENVIRONMENT || value.parent != parent.identity
        {
            return Err(Status::Blocked);
        }
        let namespace = checked(OwnedRoot::reopen_scoped(
            parent,
            NAMESPACE,
            value.root,
            ENVIRONMENT,
        ))?;
        if namespace.dir.identity != dir.identity {
            return Err(Status::Blocked);
        }
        let (ready, bytes) = read(&namespace.dir, "ready.json")?;
        let value: Ready = decode(&bytes)?;
        if value.format != 1
            || value.environment != ENVIRONMENT
            || value.namespace != receipt.verified.as_ref().ok_or(Status::Blocked)?.1
        {
            return Err(Status::Blocked);
        }
        let control = checked(OwnedRoot::reopen_scoped(
            &namespace.dir,
            "control",
            value.control,
            ENVIRONMENT,
        ))?;
        let instance = checked(OwnedRoot::reopen_scoped(
            &namespace.dir,
            "instance",
            value.instance,
            ENVIRONMENT,
        ))?;
        let cache = checked(OwnedRoot::reopen_scoped(
            &namespace.dir,
            "cache",
            value.cache,
            ENVIRONMENT,
        ))?;
        let mut lifetime = Self {
            namespace,
            control,
            instance,
            cache,
            held: vec![lease, receipt, ready],
        };
        lifetime.hold_markers()?;
        lifetime.validate()?;
        Ok(lifetime)
    }
    fn hold_markers(&mut self) -> PResult<()> {
        for root in [&self.namespace, &self.control, &self.instance, &self.cache] {
            self.held.push(checked(root.marker())?);
        }
        Ok(())
    }
    pub(crate) fn validate(&mut self) -> PResult<()> {
        for root in [&self.namespace, &self.control, &self.instance, &self.cache] {
            checked(root.dir.unchanged())?;
        }
        let listing: BTreeSet<_> = checked(
            self.namespace
                .dir
                .entries(8, Instant::now() + Duration::from_secs(5)),
        )?
        .into_iter()
        .map(|e| e.name)
        .collect();
        if listing != NAMES.into_iter().map(str::to_owned).collect() {
            return Err(Status::Blocked);
        }
        // Control/cache formats have no contents beyond their marker yet. Userdata
        // under the instance is deliberately not traversed, modified or adopted.
        for root in [&self.control, &self.cache] {
            let listing = checked(root.dir.entries(2, Instant::now() + Duration::from_secs(5)))?;
            if listing.len() != 1 || listing[0].name != "aoc-instance.json" {
                return Err(Status::Blocked);
            }
        }
        for file in &mut self.held {
            let (length, hash) = file.verified.clone().ok_or(Status::Blocked)?;
            checked(file.fingerprint(length, &hash))?;
        }
        Ok(())
    }
}
#[cfg(test)]
mod tests;
