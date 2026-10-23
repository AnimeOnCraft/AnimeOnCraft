//! Immutable TUF generations, signed replay and guarded native online refresh.
//! READY commit recovery and guarded online retry of durable pre-READY evidence are supported.
use super::{
    owned::{OwnedRoot, Receipt},
    *,
};
use crate::pack::{manifest, strict_json, trust};
use futures::TryStreamExt;
use serde::{Deserialize, Serialize};
use std::{
    collections::BTreeMap,
    path::PathBuf,
    sync::Mutex,
    time::{Duration, Instant},
};
use tough::{
    schema::{Root, Signed, Snapshot, Targets, Timestamp},
    Transport, TransportError, TransportErrorKind, TransportStream,
};
type BResult<T> = std::result::Result<T, &'static str>;
const CAP: usize = 32 * 1024 * 1024;
const ATTEMPTS: u64 = 32;
mod history;
mod partial;
const FILES: [&str; 6] = [
    "root.json",
    "timestamp.json",
    "snapshot.json",
    "targets.json",
    "latest_known_time.json",
    "aoc-trust.json",
];
fn native<T>(r: Result<T>) -> BResult<T> {
    r.map_err(|_| "STORE_INVALID")
}
pub(super) fn encoded<T: Serialize>(value: &T) -> BResult<Vec<u8>> {
    serde_json::to_vec(value).map_err(|_| "STORE_INVALID")
}
pub(super) fn decode<T: serde::de::DeserializeOwned>(bytes: &[u8], max: usize) -> BResult<T> {
    serde_json::from_value(strict_json::parse(bytes, max).map_err(|_| "STORE_INVALID")?)
        .map_err(|_| "STORE_INVALID")
}
pub(super) fn get(dir: &Arc<Dir>, name: &str, cap: usize) -> BResult<Vec<u8>> {
    let mut file = native(dir.file(name, false))?;
    native(file.unchanged())?;
    let mut bytes = Vec::new();
    (&mut file.file)
        .take(cap as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "STORE_INVALID")?;
    native(file.unchanged())?;
    if bytes.len() > cap
        || bytes.len() as u64 != file.file.metadata().map_err(|_| "STORE_INVALID")?.len()
    {
        return Err("STORE_INVALID");
    }
    Ok(bytes)
}
pub(super) fn put(dir: &Arc<Dir>, name: &str, bytes: &[u8]) -> BResult<()> {
    if bytes.len() > CAP {
        return Err("STORE_LIMIT");
    }
    let mut file = native(dir.file(name, true))?;
    native(file.write_new(bytes))?;
    native(file.fingerprint(bytes.len() as u64, &manifest::hash(bytes)))
}
fn scratch_read(dir: &Arc<Dir>, name: &str) -> BResult<Vec<u8>> {
    native(dir.unchanged())?;
    let file = native(relative(
        dir,
        &mut native(portable_name(name))?,
        false,
        false,
        None,
    ))?;
    let id = native(inspect(&file, false))?;
    if id.volume != dir.identity.volume {
        return Err("STORE_INVALID");
    }
    let protection = dir.protection.as_ref().ok_or("STORE_INVALID")?;
    native(protection.check_scratch_file(&file))?;
    let mut bytes = Vec::new();
    (&file)
        .take(manifest::MAX_MANIFEST as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "STORE_INVALID")?;
    if bytes.len() > manifest::MAX_MANIFEST || native(inspect(&file, false))? != id {
        return Err("STORE_INVALID");
    }
    native(protection.check_scratch_file(&file))?;
    Ok(bytes)
}
fn path(dir: &Dir) -> BResult<PathBuf> {
    native(dir.unchanged())?;
    let mut buffer = [0u16; 4096];
    let len = unsafe {
        GetFinalPathNameByHandleW(
            dir.file.as_raw_handle(),
            buffer.as_mut_ptr(),
            buffer.len() as u32,
            FILE_NAME_NORMALIZED | VOLUME_NAME_DOS,
        )
    } as usize;
    if len == 0 || len >= buffer.len() {
        return Err("STORE_INVALID");
    }
    Ok(PathBuf::from(
        String::from_utf16(&buffer[..len]).map_err(|_| "STORE_INVALID")?,
    ))
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Binding {
    format: u8,
    anchor: String,
    control: Receipt,
    instance: Receipt,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Intent {
    format: u8,
    number: u64,
    previous: Option<u64>,
    anchor: String,
    request: partial::Request,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Seal {
    format: u8,
    number: u64,
    intent_hash: String,
    digest: String,
    length: u64,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Bundle {
    files: BTreeMap<String, TextBytes>,
    chain: Vec<TextBytes>,
    raw: BTreeMap<String, TextBytes>,
    target: TextBytes,
}
// Store JSON bodies as UTF8 strings, not huge arrays of JSON numbers. Preserve exact
// signed/downloaded bytes while bounding parser memory for persisted generations.
#[derive(Clone)]
struct TextBytes(Vec<u8>);
impl std::ops::Deref for TextBytes {
    type Target = [u8];
    fn deref(&self) -> &[u8] {
        &self.0
    }
}
impl AsRef<[u8]> for TextBytes {
    fn as_ref(&self) -> &[u8] {
        &self.0
    }
}
impl Serialize for TextBytes {
    fn serialize<S: serde::Serializer>(&self, s: S) -> std::result::Result<S::Ok, S::Error> {
        s.serialize_str(std::str::from_utf8(&self.0).map_err(serde::ser::Error::custom)?)
    }
}
impl<'de> Deserialize<'de> for TextBytes {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> std::result::Result<Self, D::Error> {
        let text = String::deserialize(d)?;
        if text.len() > manifest::MAX_MANIFEST {
            return Err(serde::de::Error::custom("body limit"));
        }
        Ok(Self(text.into_bytes()))
    }
}
#[derive(Clone, Debug)]
struct Recording<T> {
    inner: T,
    fetched: Arc<Mutex<BTreeMap<String, Vec<u8>>>>,
    endpoints: crate::pack::repository::Endpoints,
}
#[async_trait::async_trait]
impl<T: Transport + Clone> Transport for Recording<T> {
    async fn fetch(
        &self,
        url: reqwest::Url,
    ) -> std::result::Result<TransportStream, TransportError> {
        let mut stream = self.inner.fetch(url.clone()).await?;
        let mut bytes = Vec::new();
        while let Some(chunk) = stream.try_next().await? {
            if bytes.len() + chunk.len() > manifest::MAX_MANIFEST {
                return Err(TransportError::new(
                    TransportErrorKind::Other,
                    "bridge body limit",
                ));
            }
            bytes.extend_from_slice(&chunk);
        }
        let mut map = self.fetched.lock().map_err(|_| {
            TransportError::new(TransportErrorKind::Other, "bridge recording unavailable")
        })?;
        if map.len() >= 40 {
            return Err(TransportError::new(
                TransportErrorKind::Other,
                "bridge object limit",
            ));
        }
        map.insert(
            self.endpoints.canonical(&url).map_err(|_| {
                TransportError::new(TransportErrorKind::Other, "unapproved repository path")
            })?,
            bytes.clone(),
        );
        Ok(Box::pin(futures::stream::iter([Ok(bytes::Bytes::from(
            bytes,
        ))])))
    }
}
use crate::pack::trust::cached::{reference, root_chain, signed};
fn validate_bundle(bundle: &Bundle, bootstrap: &[u8]) -> BResult<()> {
    if bundle.files.len() != FILES.len()
        || FILES.iter().any(|name| !bundle.files.contains_key(*name))
        || bundle.raw.len() != 3
    {
        return Err("STORE_INVALID");
    }
    let root = root_chain(bootstrap, &bundle.chain, &bundle.files["root.json"])?;
    let timestamp: Signed<Timestamp> =
        signed(bundle.raw.get("timestamp.json").ok_or("STORE_INVALID")?)?;
    let snapshot: Signed<Snapshot> =
        signed(bundle.raw.get("snapshot.json").ok_or("STORE_INVALID")?)?;
    let targets: Signed<Targets> = signed(bundle.raw.get("targets.json").ok_or("STORE_INVALID")?)?;
    root.signed
        .verify_role(&timestamp)
        .map_err(|_| "TUF_REJECTED")?;
    root.signed
        .verify_role(&snapshot)
        .map_err(|_| "TUF_REJECTED")?;
    root.signed
        .verify_role(&targets)
        .map_err(|_| "TUF_REJECTED")?;
    for name in ["timestamp.json", "snapshot.json", "targets.json"] {
        let raw: serde_json::Value = decode(&bundle.raw[name], manifest::MAX_MANIFEST)?;
        let cached: serde_json::Value = decode(&bundle.files[name], manifest::MAX_MANIFEST)?;
        if raw != cached {
            return Err("STORE_INVALID");
        }
    }
    let ts: serde_json::Value = decode(&bundle.raw["timestamp.json"], 16384)?;
    let sn: serde_json::Value = decode(&bundle.raw["snapshot.json"], 262144)?;
    reference(
        &ts["signed"]["meta"],
        "snapshot.json",
        &bundle.raw["snapshot.json"],
    )?;
    reference(
        &sn["signed"]["meta"],
        "targets.json",
        &bundle.raw["targets.json"],
    )?;
    let cp: trust::Checkpoint = decode(&bundle.files["aoc-trust.json"], 16384)?;
    if cp.format != 1
        || cp.namespace != "ntfs-bridge-v1"
        || cp.phase != "READY"
        || cp.anchor != manifest::hash(bootstrap)
        || cp.metadata.len() != 4
    {
        return Err("STORE_INVALID");
    }
    for name in [
        "root.json",
        "timestamp.json",
        "snapshot.json",
        "targets.json",
    ] {
        if cp.metadata.get(name) != Some(&manifest::hash(&bundle.files[name])) {
            return Err("STORE_INVALID");
        }
    }
    let clock: String = decode(&bundle.files["latest_known_time.json"], 256)?;
    let time = chrono::DateTime::parse_from_rfc3339(&clock).map_err(|_| "CLOCK_ERROR")?;
    let floor = chrono::DateTime::parse_from_rfc3339(&cp.time_floor).map_err(|_| "CLOCK_ERROR")?;
    if time < floor || time > chrono::Utc::now() {
        return Err("CLOCK_ERROR");
    }
    trust::cached::check_observed_expiry(
        &root,
        &timestamp,
        &snapshot,
        &targets,
        time.with_timezone(&chrono::Utc),
    )?;
    let sequence = cp.sequence;
    if sequence == 0 {
        return Err("STORE_INVALID");
    }
    let manifest: serde_json::Value = decode(&bundle.target, manifest::MAX_MANIFEST)?;
    if manifest["release_sequence"].as_u64() != Some(sequence)
        || cp.manifest_digest.as_ref() != Some(&manifest::hash(&bundle.target))
    {
        return Err("STORE_INVALID");
    }
    let name = tough::TargetName::new(format!(
        "packs/{}/{sequence}/manifest.json",
        manifest["channel"].as_str().ok_or("STORE_INVALID")?
    ))
    .map_err(|_| "STORE_INVALID")?;
    let target = targets.signed.targets.get(&name).ok_or("TUF_REJECTED")?;
    if target.length != bundle.target.len() as u64
        || target.hashes.sha256.as_ref() != &Sha256::digest(&bundle.target)[..]
    {
        return Err("TUF_REJECTED");
    }
    Ok(())
}
fn dominates(new: &Bundle, old: &Bundle) -> BResult<()> {
    let new_target: serde_json::Value = decode(&new.target, manifest::MAX_MANIFEST)?;
    let old_target: serde_json::Value = decode(&old.target, manifest::MAX_MANIFEST)?;
    if new_target["pack_id"] != old_target["pack_id"]
        || new_target["channel"] != old_target["channel"]
    {
        return Err("STORE_CONTEXT_CHANGED");
    }
    let newer: trust::Checkpoint = decode(&new.files["aoc-trust.json"], 16384)?;
    let older: trust::Checkpoint = decode(&old.files["aoc-trust.json"], 16384)?;
    if newer.sequence < older.sequence
        || (newer.sequence == older.sequence && newer.manifest_digest != older.manifest_digest)
        || chrono::DateTime::parse_from_rfc3339(&newer.time_floor).map_err(|_| "CLOCK_ERROR")?
            < chrono::DateTime::parse_from_rfc3339(&older.time_floor).map_err(|_| "CLOCK_ERROR")?
    {
        return Err("STORE_ROLLBACK");
    }
    for name in [
        "root.json",
        "timestamp.json",
        "snapshot.json",
        "targets.json",
    ] {
        let n: serde_json::Value = decode(&new.files[name], manifest::MAX_MANIFEST)?;
        let o: serde_json::Value = decode(&old.files[name], manifest::MAX_MANIFEST)?;
        let next = n["signed"]["version"].as_u64().ok_or("STORE_INVALID")?;
        let previous = o["signed"]["version"].as_u64().ok_or("STORE_INVALID")?;
        if next < previous || (next == previous && n["signed"] != o["signed"]) {
            return Err("STORE_ROLLBACK");
        }
    }
    if new.chain.len() < old.chain.len()
        || !new.chain.iter().zip(&old.chain).all(|(a, b)| a.0 == b.0)
    {
        return Err("ROOT_CHAIN_INVALID");
    }
    Ok(())
}
pub(super) struct Bridge {
    control: OwnedRoot,
    instance: OwnedRoot,
    journal: Arc<Dir>,
    generations: Arc<Dir>,
    scratch: Arc<Dir>,
    evidence: Arc<Dir>,
    anchor: String,
}
impl Bridge {
    pub(super) fn receipts(&self) -> (Receipt, Receipt) {
        (self.control.receipt.clone(), self.instance.receipt.clone())
    }
    pub(super) fn validate_layout(&self, bootstrap: &[u8]) -> BResult<()> {
        native(
            self.control
                .dir
                .exact_names(&["aoc-instance.json", "binding.json", "journal"]),
        )?;
        native(self.instance.dir.exact_names(&[
            "aoc-instance.json",
            "generations",
            "scratch",
            "evidence",
        ]))?;
        self.inspect(bootstrap)?;
        Ok(())
    }
    pub(super) fn matches_latest(
        &self,
        bootstrap: &[u8],
        release: &trust::VerifiedRelease,
    ) -> BResult<()> {
        release.current()?;
        let (active, _, pending) = self.inspect(bootstrap)?;
        let (_, bundle) = active.ok_or("ENGINE_RELEASE_REQUIRED")?;
        let cp: trust::Checkpoint = decode(&bundle.files["aoc-trust.json"], 16384)?;
        if pending
            || release.anchor() != self.anchor
            || cp.manifest_digest.as_deref() != Some(release.manifest().digest())
            || cp.sequence != release.manifest().manifest().release_sequence
        {
            return Err("ENGINE_RELEASE_MISMATCH");
        }
        Ok(())
    }
    #[cfg(test)]
    pub(super) fn create(parent: &Arc<Dir>, bootstrap: &[u8]) -> BResult<Self> {
        Self::create_scoped(parent, bootstrap, "ntfs-owned-test-v1")
    }
    pub(super) fn create_scoped(
        parent: &Arc<Dir>,
        bootstrap: &[u8],
        environment: &'static str,
    ) -> BResult<Self> {
        trust::root_policy(bootstrap)?;
        let control = native(OwnedRoot::create_scoped(parent, "control", environment))?;
        let instance = native(OwnedRoot::create_scoped(parent, "instance", environment))?;
        let anchor = manifest::hash(bootstrap);
        put(
            &control.dir,
            "binding.json",
            &encoded(&Binding {
                format: 2,
                anchor: anchor.clone(),
                control: control.receipt.clone(),
                instance: instance.receipt.clone(),
            })?,
        )?;
        let journal = native(control.dir.child("journal", true))?;
        let generations = native(instance.dir.child("generations", true))?;
        let scratch = native(instance.dir.child("scratch", true))?;
        let evidence = native(instance.dir.child("evidence", true))?;
        Ok(Self {
            control,
            instance,
            journal,
            generations,
            scratch,
            evidence,
            anchor,
        })
    }
    #[cfg(test)]
    pub(super) fn reopen(parent: &Arc<Dir>, bootstrap: &[u8]) -> BResult<Self> {
        Self::reopen_scoped(parent, bootstrap, "ntfs-owned-test-v1")
    }
    pub(super) fn reopen_scoped(
        parent: &Arc<Dir>,
        bootstrap: &[u8],
        environment: &'static str,
    ) -> BResult<Self> {
        trust::root_policy(bootstrap)?;
        let protection = Arc::new(Protection::current().map_err(|_| "STORE_INVALID")?);
        let file = native(relative(
            parent,
            &mut native(portable_name("control"))?,
            true,
            false,
            None,
        ))?;
        let identity = native(inspect(&file, true))?;
        native(protection.check(&file, true))?;
        let dir = Arc::new(Dir {
            file,
            identity,
            _parent: Some(parent.clone()),
            protection: Some(protection),
        });
        let binding: Binding = decode(&get(&dir, "binding.json", 16384)?, 16384)?;
        let anchor = manifest::hash(bootstrap);
        if binding.format != 2 || binding.anchor != anchor {
            return Err("STORE_INVALID");
        }
        let control = native(OwnedRoot::reopen_scoped(
            parent,
            "control",
            binding.control,
            environment,
        ))?;
        let instance = native(OwnedRoot::reopen_scoped(
            parent,
            "instance",
            binding.instance,
            environment,
        ))?;
        let journal = native(control.dir.child("journal", false))?;
        let generations = native(instance.dir.child("generations", false))?;
        let scratch = native(instance.dir.child("scratch", false))?;
        let evidence = native(instance.dir.child("evidence", false))?;
        let bridge = Self {
            control,
            instance,
            journal,
            generations,
            scratch,
            evidence,
            anchor,
        };
        bridge.inspect(bootstrap)?;
        Ok(bridge)
    }
    fn payload(&self, n: u64, seal: &Seal, bootstrap: &[u8]) -> BResult<Bundle> {
        let dir = native(self.generations.child(&format!("generation-{n:05}"), false))?;
        let bytes = get(&dir, "bundle.json", CAP)?;
        if seal.number != n
            || seal.format != 1
            || seal.length != bytes.len() as u64
            || seal.digest != manifest::hash(&bytes)
        {
            return Err("STORE_INVALID");
        }
        let bundle: Bundle = decode(&bytes, CAP)?;
        validate_bundle(&bundle, bootstrap)?;
        Ok(bundle)
    }
    // Return the last complete generation and the one unresolved intent, if any.
    fn inspect(&self, bootstrap: &[u8]) -> BResult<(Option<(u64, Bundle)>, u64, bool)> {
        native(self.control.marker())?;
        native(self.instance.marker())?;
        let entries = native(self.journal.entries(
            ATTEMPTS as usize * 3,
            Instant::now() + Duration::from_secs(10),
        ))?;
        let mut maximum = 0;
        let mut names = std::collections::BTreeSet::new();
        for entry in entries {
            let parts = entry.name.split_once('-').ok_or("STORE_INVALID")?;
            if !["intent", "ready", "commit"].contains(&parts.0) {
                return Err("STORE_INVALID");
            }
            let n = parts
                .1
                .strip_suffix(".json")
                .ok_or("STORE_INVALID")?
                .parse::<u64>()
                .map_err(|_| "STORE_INVALID")?;
            if n == 0 || n > ATTEMPTS || entry.name != format!("{}-{n:05}.json", parts.0) {
                return Err("STORE_INVALID");
            }
            maximum = maximum.max(n);
            names.insert(entry.name);
        }
        let mut active = None;
        let mut pending = false;
        for n in 1..=maximum {
            let bytes = get(&self.journal, &format!("intent-{n:05}.json"), 16384)?;
            let intent: Intent = decode(&bytes, 16384)?;
            if intent.format != 2
                || intent.number != n
                || intent.anchor != self.anchor
                || intent.previous != active
            {
                return Err("STORE_INVALID");
            }
            if names.contains(&format!("commit-{n:05}.json")) {
                let ready = get(&self.journal, &format!("ready-{n:05}.json"), 16384)?;
                let commit = get(&self.journal, &format!("commit-{n:05}.json"), 16384)?;
                if ready != commit {
                    return Err("STORE_INVALID");
                }
                let seal: Seal = decode(&ready, 16384)?;
                if seal.format != 1
                    || seal.number != n
                    || seal.length == 0
                    || seal.length > CAP as u64
                    || !manifest::digest(&seal.digest)
                    || seal.intent_hash != manifest::hash(&bytes)
                {
                    return Err("STORE_INVALID");
                }
                active = Some(n);
            } else {
                if n != maximum {
                    return Err("STORE_INVALID");
                }
                pending = true;
            }
        }
        let active = match active {
            Some(n) => {
                let seal: Seal = decode(
                    &get(&self.journal, &format!("commit-{n:05}.json"), 16384)?,
                    16384,
                )?;
                Some((n, self.payload(n, &seal, bootstrap)?))
            }
            None => None,
        };
        Ok((active, maximum, pending))
    }
    pub(super) fn recover(&mut self, bootstrap: &[u8]) -> BResult<()> {
        let (active, n, pending) = self.inspect(bootstrap)?;
        if !pending {
            return Ok(());
        }
        let bytes = get(&self.journal, &format!("intent-{n:05}.json"), 16384)?;
        let ready_name = format!("ready-{n:05}.json");
        let entries = native(self.journal.entries(
            ATTEMPTS as usize * 3,
            Instant::now() + Duration::from_secs(10),
        ))?;
        if !entries.iter().any(|entry| entry.name == ready_name) {
            return Err("BRIDGE_RECOVERY_REQUIRED");
        }
        let ready = get(&self.journal, &ready_name, 16384)?;
        let seal: Seal = decode(&ready, 16384)?;
        if seal.intent_hash != manifest::hash(&bytes) {
            return Err("STORE_INVALID");
        }
        let candidate = self.payload(n, &seal, bootstrap)?;
        if let Some((_, old)) = &active {
            dominates(&candidate, old)?;
        }
        put(&self.journal, &format!("commit-{n:05}.json"), &ready)?;
        self.inspect(bootstrap)?;
        Ok(())
    }
    #[cfg(test)]
    pub(super) async fn refresh<T: Transport + Clone + Send + Sync + 'static>(
        &mut self,
        bootstrap: &[u8],
        transport: T,
        policy: &manifest::ReleasePolicy,
        crash_after_ready: bool,
    ) -> BResult<trust::VerifiedRelease> {
        let endpoints = crate::pack::repository::Endpoints::new(
            "https://fixtures.animeoncraft.invalid/metadata/",
            "https://fixtures.animeoncraft.invalid/targets/",
        )?;
        self.refresh_from(bootstrap, transport, policy, endpoints, crash_after_ready)
            .await
    }
    pub(super) async fn refresh_from<T: Transport + Clone + Send + Sync + 'static>(
        &mut self,
        bootstrap: &[u8],
        transport: T,
        policy: &manifest::ReleasePolicy,
        endpoints: crate::pack::repository::Endpoints,
        crash_after_ready: bool,
    ) -> BResult<trust::VerifiedRelease> {
        if manifest::hash(bootstrap) != self.anchor {
            return Err("STORE_INVALID");
        }
        match self.recover(bootstrap) {
            Ok(()) | Err("BRIDGE_RECOVERY_REQUIRED") => {}
            Err(e) => return Err(e),
        }
        let (old, last, pending) = self.inspect(bootstrap)?;
        if let Some((_, bundle)) = &old {
            let target: manifest::Manifest = decode(&bundle.target, manifest::MAX_MANIFEST)?;
            if target.pack_id != policy.pack_id || target.channel != policy.channel {
                return Err("STORE_CONTEXT_CHANGED");
            }
            if target.release_sequence > policy.sequence {
                return Err("STORE_ROLLBACK");
            }
        }
        if !pending && last >= ATTEMPTS {
            return Err("STORE_LIMIT");
        }
        let n = if pending { last } else { last + 1 };
        let intent = if pending {
            get(&self.journal, &format!("intent-{n:05}.json"), 16384)?
        } else {
            encoded(&Intent {
                format: 2,
                number: n,
                previous: old.as_ref().map(|(n, _)| *n),
                anchor: self.anchor.clone(),
                request: partial::Request::of(policy),
            })?
        };
        if !pending {
            put(&self.journal, &format!("intent-{n:05}.json"), &intent)?;
        }
        let request: Intent = decode(&intent, 16384)?;
        let evidence = native(self.evidence.child(&format!("attempt-{n:05}"), !pending))?;
        let attempt = partial::Attempt::open(
            evidence,
            bootstrap,
            old.as_ref().map(|(_, b)| b),
            request.request,
            policy,
        )?;
        let stage = native(
            self.scratch
                .child(&format!("stage-{n:05}-{:03}", attempt.round), true),
        )?;
        let permit = ScratchPermit {
            directory: path(&stage)?,
            guard: stage.clone(),
        };
        let mut chain = old.as_ref().map_or_else(Vec::new, |(_, b)| b.chain.clone());
        if let Some((_, bundle)) = &old {
            for (name, bytes) in &bundle.files {
                put(&stage, name, bytes)?;
            }
        }
        let recording = Recording {
            inner: partial::Fenced {
                inner: transport,
                attempt: attempt.clone(),
                endpoints: endpoints.clone(),
            },
            fetched: Arc::new(Mutex::new(BTreeMap::new())),
            endpoints: endpoints.clone(),
        };
        let store = trust::TrustStore::native_scratch(permit)?;
        let release = trust::verify(
            bootstrap,
            endpoints.metadata,
            endpoints.targets,
            recording.clone(),
            &store,
            policy,
        )
        .await?;
        let fetched = recording
            .fetched
            .lock()
            .map_err(|_| "STORE_INVALID")?
            .clone();
        let mut current = chain
            .last()
            .map_or_else(|| signed::<Root>(bootstrap), |r| signed::<Root>(r))?
            .signed
            .version
            .get();
        loop {
            let next = current.checked_add(1).ok_or("ROOT_CHAIN_LIMIT")?;
            let Some(bytes) = fetched.get(&format!("/metadata/{next}.root.json")) else {
                break;
            };
            chain.push(TextBytes(bytes.clone()));
            current = next;
        }
        let mut files = BTreeMap::new();
        for name in FILES {
            files.insert(name.into(), TextBytes(scratch_read(&stage, name)?));
        }
        let mut raw = BTreeMap::new();
        for name in ["timestamp.json", "snapshot.json", "targets.json"] {
            let found = fetched
                .iter()
                .find(|(path, _)| path.starts_with("/metadata/") && path.ends_with(name))
                .ok_or("STORE_INVALID")?;
            raw.insert(name.into(), TextBytes(found.1.clone()));
        }
        let target = fetched
            .iter()
            .find(|(name, _)| name.starts_with("/targets/"))
            .ok_or("STORE_INVALID")?
            .1
            .clone();
        if manifest::hash(&target) != release.manifest().digest() {
            return Err("STORE_INVALID");
        }
        let mut bundle = Bundle {
            files,
            chain,
            raw,
            target: TextBytes(target),
        };
        attempt
            .tracker
            .lock()
            .map_err(|_| "STORE_INVALID")?
            .stamp(&mut bundle)?;
        validate_bundle(&bundle, bootstrap)?;
        let bytes = encoded(&bundle)?;
        let generation = native(self.generations.child(&format!("generation-{n:05}"), true))?;
        put(&generation, "bundle.json", &bytes)?;
        let seal = encoded(&Seal {
            format: 1,
            number: n,
            intent_hash: manifest::hash(&intent),
            digest: manifest::hash(&bytes),
            length: bytes.len() as u64,
        })?;
        put(&self.journal, &format!("ready-{n:05}.json"), &seal)?;
        #[cfg(test)]
        if crash_after_ready {
            std::process::exit(37);
        }
        #[cfg(not(test))]
        let _ = crash_after_ready;
        self.recover(bootstrap)?;
        Ok(release)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pack::tests::{policy, runtime, Fixture as Publisher};
    use std::process::Command;
    #[test]
    fn signed_refresh_persistent_binding_and_rollback_rejection() {
        runtime().block_on(async {
            let files = super::super::tests::Fixture::new();
            let publisher = Publisher::new().await;
            let mut bridge = Bridge::create(&files.root, &publisher.bootstrap).unwrap();
            let first = publisher.published(1, 1, false).await;
            assert!(bridge
                .refresh(&publisher.bootstrap, first, &policy(1), false)
                .await
                .is_ok());
            drop(bridge);
            let mut bridge = Bridge::reopen(&files.root, &publisher.bootstrap).unwrap();
            let second = publisher.published(2, 2, false).await;
            assert!(bridge
                .refresh(&publisher.bootstrap, second.clone(), &policy(2), false)
                .await
                .is_ok());
            assert!(bridge
                .refresh(
                    &publisher.bootstrap,
                    publisher.published(1, 1, false).await,
                    &policy(1),
                    false
                )
                .await
                .is_err());
            // A known lower release is refused before recording a new intent.
            assert_eq!(bridge.recover(&publisher.bootstrap), Ok(()));
            let (_, last, pending) = bridge.inspect(&publisher.bootstrap).unwrap();
            assert_eq!(last, 2);
            assert!(!pending);
            assert_eq!(
                bridge.inspect(&publisher.bootstrap).unwrap().0.unwrap().0,
                2
            );
            assert!(bridge
                .refresh(&publisher.bootstrap, second, &policy(2), false)
                .await
                .is_ok());
        });
    }
    #[test]
    fn corrupted_binding_bundle_and_unknown_journal_are_preserved() {
        runtime().block_on(async {
            let files = super::super::tests::Fixture::new();
            let publisher = Publisher::new().await;
            let mut bridge = Bridge::create(&files.root, &publisher.bootstrap).unwrap();
            bridge
                .refresh(
                    &publisher.bootstrap,
                    publisher.published(1, 1, false).await,
                    &policy(1),
                    false,
                )
                .await
                .unwrap();
            let generation = native(bridge.generations.child("generation-00001", false)).unwrap();
            let payload_path = path(&generation).unwrap().join("bundle.json");
            drop(generation);
            drop(bridge);
            std::fs::write(&payload_path, b"{}").unwrap();
            assert!(Bridge::reopen(&files.root, &publisher.bootstrap).is_err());
            assert_eq!(std::fs::read(payload_path).unwrap(), b"{}");
        });
    }
    #[test]
    fn child_ready_crash_recovers_without_replaying_network() {
        runtime().block_on(async {
            let files = super::super::tests::Fixture::new();
            let location = path(&files.root).unwrap();
            let publisher = Publisher::new().await;
            let transport = publisher.published(1, 1, false).await;
            put(&files.root, "bootstrap.json", &publisher.bootstrap).unwrap();
            put(
                &files.root,
                "transport.json",
                &encoded(&*transport.files).unwrap(),
            )
            .unwrap();
            let output = Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "pack::ntfs::bridge::tests::fixture_bridge_child",
                    "--nocapture",
                ])
                .env("AOC_BRIDGE_CHILD", &location)
                .output()
                .unwrap();
            assert_eq!(output.status.code(), Some(37), "{output:?}");
            let bootstrap = get(&files.root, "bootstrap.json", 262144).unwrap();
            let mut bridge = Bridge::reopen(&files.root, &bootstrap).unwrap();
            let (_, _, pending) = bridge.inspect(&bootstrap).unwrap();
            assert!(pending);
            bridge.recover(&bootstrap).unwrap();
            assert!(!bridge.inspect(&bootstrap).unwrap().2);
            let before = get(&bridge.journal, "commit-00001.json", 16384).unwrap();
            bridge.recover(&bootstrap).unwrap();
            assert_eq!(
                before,
                get(&bridge.journal, "commit-00001.json", 16384).unwrap()
            );
        });
    }
    #[test]
    fn fixture_bridge_child() {
        let Some(location) = std::env::var_os("AOC_BRIDGE_CHILD") else {
            return;
        };
        let location = PathBuf::from(location);
        let workspace = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../..")
            .canonicalize()
            .unwrap();
        assert_eq!(location.parent(), Some(workspace.join(".tools").as_path()));
        assert!(location
            .file_name()
            .unwrap()
            .to_string_lossy()
            .starts_with("ntfs-fixture-"));
        assert_eq!(location.canonicalize().unwrap(), location);
        let parent = Dir::fixture_existing(&location).unwrap();
        runtime().block_on(async {
            let bootstrap = get(&parent, "bootstrap.json", 262144).unwrap();
            let files = decode(&get(&parent, "transport.json", CAP).unwrap(), CAP).unwrap();
            let memory = crate::pack::tests::Memory {
                files: Arc::new(files),
                crash: std::env::var("AOC_BRIDGE_MODE").as_deref() == Ok("pending"),
            };
            let transport = CrashBeforeSnapshot {
                inner: memory,
                crash: std::env::var("AOC_BRIDGE_MODE").as_deref() == Ok("snapshot"),
            };
            let existing = std::env::var("AOC_BRIDGE_EXISTING").as_deref() == Ok("yes");
            let mut bridge = if existing {
                Bridge::reopen(&parent, &bootstrap)
            } else {
                Bridge::create(&parent, &bootstrap)
            }
            .unwrap();
            let sequence = std::env::var("AOC_BRIDGE_SEQUENCE")
                .unwrap_or_else(|_| "1".into())
                .parse()
                .unwrap();
            bridge
                .refresh(&bootstrap, transport, &policy(sequence), true)
                .await
                .unwrap();
        });
    }
    #[derive(Clone, Debug)]
    struct CrashBeforeSnapshot {
        inner: crate::pack::tests::Memory,
        crash: bool,
    }
    #[async_trait::async_trait]
    impl Transport for CrashBeforeSnapshot {
        async fn fetch(
            &self,
            url: reqwest::Url,
        ) -> std::result::Result<TransportStream, TransportError> {
            if self.crash && url.path().ends_with(".snapshot.json") {
                std::process::exit(37);
            }
            self.inner.fetch(url).await
        }
    }
    #[test]
    fn partial_timestamp_crash_preserves_floor_rejects_old_then_retries() {
        runtime().block_on(async {
            let files = super::super::tests::Fixture::new();
            let publisher = Publisher::new().await;
            let old = publisher.published(1, 1, false).await;
            let newer = publisher.published(2, 2, false).await;
            let mut bridge = Bridge::create(&files.root, &publisher.bootstrap).unwrap();
            bridge
                .refresh(&publisher.bootstrap, old.clone(), &policy(1), false)
                .await
                .unwrap();
            drop(bridge);
            put(&files.root, "bootstrap.json", &publisher.bootstrap).unwrap();
            put(
                &files.root,
                "transport.json",
                &encoded(&*newer.files).unwrap(),
            )
            .unwrap();
            let output = Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "pack::ntfs::bridge::tests::fixture_bridge_child",
                    "--nocapture",
                ])
                .env("AOC_BRIDGE_CHILD", path(&files.root).unwrap())
                .env("AOC_BRIDGE_MODE", "snapshot")
                .env("AOC_BRIDGE_EXISTING", "yes")
                .env("AOC_BRIDGE_SEQUENCE", "2")
                .output()
                .unwrap();
            assert_eq!(output.status.code(), Some(37), "{output:?}");
            let mut bridge = Bridge::reopen(&files.root, &publisher.bootstrap).unwrap();
            assert!(bridge
                .refresh(&publisher.bootstrap, old, &policy(1), false)
                .await
                .is_err());
            bridge
                .refresh(&publisher.bootstrap, newer, &policy(2), false)
                .await
                .unwrap();
            let (active, n, pending) = bridge.inspect(&publisher.bootstrap).unwrap();
            assert!(!pending);
            assert_eq!(n, 2);
            assert_eq!(
                decode::<trust::Checkpoint>(&active.unwrap().1.files["aoc-trust.json"], 16384)
                    .unwrap()
                    .sequence,
                2
            );
        });
    }
    #[test]
    fn captured_target_release_floor_survives_process_exit() {
        runtime().block_on(async {
            let files = super::super::tests::Fixture::new();
            let publisher = Publisher::new().await;
            let newer = publisher.published(2, 2, false).await;
            put(&files.root, "bootstrap.json", &publisher.bootstrap).unwrap();
            put(
                &files.root,
                "transport.json",
                &encoded(&*newer.files).unwrap(),
            )
            .unwrap();
            let output = Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "pack::ntfs::bridge::tests::fixture_bridge_child",
                    "--nocapture",
                ])
                .env("AOC_BRIDGE_CHILD", path(&files.root).unwrap())
                .env("AOC_BRIDGE_MODE", "target")
                .env("AOC_BRIDGE_SEQUENCE", "2")
                .output()
                .unwrap();
            assert_eq!(output.status.code(), Some(37), "{output:?}");
            let mut bridge = Bridge::reopen(&files.root, &publisher.bootstrap).unwrap();
            assert!(matches!(
                bridge
                    .refresh(&publisher.bootstrap, newer.clone(), &policy(1), false)
                    .await,
                Err("PARTIAL_ROLLBACK")
            ));
            bridge
                .refresh(&publisher.bootstrap, newer, &policy(2), false)
                .await
                .unwrap();
            assert_eq!(bridge.inspect(&publisher.bootstrap).unwrap().1, 1);
        });
    }
    #[test]
    fn retry_policy_change_preserves_captured_release_floor() {
        runtime().block_on(async {
            let files = super::super::tests::Fixture::new();
            let publisher = Publisher::new().await;
            let mut missing = publisher.published(1, 1, false).await;
            let mut map = (*missing.files).clone();
            map.remove("/metadata/1.snapshot.json");
            missing.files = Arc::new(map);
            let mut bridge = Bridge::create(&files.root, &publisher.bootstrap).unwrap();
            assert!(bridge
                .refresh(&publisher.bootstrap, missing, &policy(1), false)
                .await
                .is_err());
            drop(bridge);
            let newer = publisher.published(2, 2, false).await;
            put(&files.root, "bootstrap.json", &publisher.bootstrap).unwrap();
            put(
                &files.root,
                "transport.json",
                &encoded(&*newer.files).unwrap(),
            )
            .unwrap();
            let output = Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "pack::ntfs::bridge::tests::fixture_bridge_child",
                    "--nocapture",
                ])
                .env("AOC_BRIDGE_CHILD", path(&files.root).unwrap())
                .env("AOC_BRIDGE_MODE", "target")
                .env("AOC_BRIDGE_EXISTING", "yes")
                .env("AOC_BRIDGE_SEQUENCE", "2")
                .output()
                .unwrap();
            assert_eq!(output.status.code(), Some(37), "{output:?}");
            let mut bridge = Bridge::reopen(&files.root, &publisher.bootstrap).unwrap();
            assert!(matches!(
                bridge
                    .refresh(&publisher.bootstrap, newer.clone(), &policy(1), false)
                    .await,
                Err("PARTIAL_ROLLBACK")
            ));
            bridge
                .refresh(&publisher.bootstrap, newer, &policy(2), false)
                .await
                .unwrap();
            assert_eq!(bridge.inspect(&publisher.bootstrap).unwrap().1, 1);
        });
    }
    #[test]
    fn unsigned_future_version_does_not_poison_recovery() {
        runtime().block_on(async {
            let files = super::super::tests::Fixture::new();
            let publisher = Publisher::new().await;
            let good = publisher.published(1, 1, false).await;
            let mut bad = good.clone();
            let mut map = (*bad.files).clone();
            let mut json: serde_json::Value =
                serde_json::from_slice(&map["/metadata/timestamp.json"]).unwrap();
            json["signed"]["version"] = 999.into();
            map.insert("/metadata/timestamp.json".into(), encoded(&json).unwrap());
            bad.files = Arc::new(map);
            let mut bridge = Bridge::create(&files.root, &publisher.bootstrap).unwrap();
            assert!(bridge
                .refresh(&publisher.bootstrap, bad, &policy(1), false)
                .await
                .is_err());
            bridge
                .refresh(&publisher.bootstrap, good, &policy(1), false)
                .await
                .unwrap();
            assert_eq!(bridge.inspect(&publisher.bootstrap).unwrap().1, 1);
        });
    }

    #[test]
    fn root_key_rotation_is_anchored_after_restart() {
        use crate::pack::tests::{keys, root};
        use aws_lc_rs::rand::SystemRandom;
        use tough::{
            editor::signed::SignedRole,
            schema::{KeyHolder, RoleType},
        };
        runtime().block_on(async {
            let files = super::super::tests::Fixture::new();
            let publisher = Publisher::new().await;
            let replacement = keys();
            let replacement_root = root(&replacement, 2).await;
            let mut next = publisher.root.clone();
            next.version = std::num::NonZeroU64::new(2).unwrap();
            next.keys
                .retain(|id, _| !publisher.root.roles[&RoleType::Root].keyids.contains(id));
            next.roles.insert(
                RoleType::Root,
                replacement_root.roles[&RoleType::Root].clone(),
            );
            for (id, key) in replacement_root.keys {
                if next.roles[&RoleType::Root].keyids.contains(&id) {
                    next.keys.insert(id, key);
                }
            }
            let new = SignedRole::new(
                next.clone(),
                &KeyHolder::Root(next.clone()),
                &replacement,
                &SystemRandom::new(),
            )
            .await
            .unwrap();
            let old = SignedRole::new(
                next,
                &KeyHolder::Root(publisher.root.clone()),
                &publisher.keys,
                &SystemRandom::new(),
            )
            .await
            .unwrap();
            let rotated = new
                .add_old_signatures(old.signed().signatures.clone())
                .unwrap();
            let mut transport = publisher.published(1, 1, false).await;
            let mut map = (*transport.files).clone();
            map.insert("/metadata/2.root.json".into(), rotated.buffer().clone());
            transport.files = Arc::new(map);
            let mut bridge = Bridge::create(&files.root, &publisher.bootstrap).unwrap();
            let mut interrupted = transport.clone();
            let mut map = (*interrupted.files).clone();
            map.remove("/metadata/1.snapshot.json");
            interrupted.files = Arc::new(map);
            assert!(bridge
                .refresh(&publisher.bootstrap, interrupted, &policy(1), false)
                .await
                .is_err());
            drop(bridge);
            let mut bridge = Bridge::reopen(&files.root, &publisher.bootstrap).unwrap();
            // The retry host no longer supplies root2; use only the authenticated,
            // durably observed old/new-signature root from the failed cycle.
            let mut map = (*transport.files).clone();
            map.remove("/metadata/2.root.json");
            transport.files = Arc::new(map);
            bridge
                .refresh(&publisher.bootstrap, transport, &policy(1), false)
                .await
                .unwrap();
            let bundle = bridge.inspect(&publisher.bootstrap).unwrap().0.unwrap().1;
            assert_eq!(bundle.chain.len(), 1);
            drop(bridge);
            let mut bridge = Bridge::reopen(&files.root, &publisher.bootstrap).unwrap();
            bridge
                .refresh(
                    &publisher.bootstrap,
                    publisher.published(2, 2, false).await,
                    &policy(2),
                    false,
                )
                .await
                .unwrap();
            let current = bridge.inspect(&publisher.bootstrap).unwrap().0.unwrap().1;
            assert_eq!(current.chain.len(), 1);
            let digest = manifest::hash(&bundle.target);
            let restored = bridge
                .restore(
                    &publisher.bootstrap,
                    &policy(2),
                    Some((&digest, &policy(1))),
                )
                .unwrap();
            assert_eq!(restored.current.manifest().manifest().release_sequence, 2);
            let prior = restored.prior.unwrap();
            assert_eq!(prior.prior().manifest().manifest().release_sequence, 1);
            assert_eq!(
                prior.prior().current(),
                Err("HISTORICAL_RELEASE_NOT_CURRENT")
            );
            // Valid self-signed unrelated root cannot replace the anchored cached root.
            let foreign = Publisher::new().await;
            let mut altered = current.clone();
            altered
                .files
                .insert("root.json".into(), TextBytes(foreign.bootstrap));
            altered.chain.clear();
            assert!(validate_bundle(&altered, &publisher.bootstrap).is_err());
            let mut truncated = current.clone();
            truncated.chain.clear();
            assert!(validate_bundle(&truncated, &publisher.bootstrap).is_err());
            assert!(dominates(&bundle, &current).is_err());
        });
    }
    #[test]
    fn missing_unknown_partial_records_and_binding_never_reset() {
        runtime().block_on(async {
            let files = super::super::tests::Fixture::new();
            let publisher = Publisher::new().await;
            let mut bridge = Bridge::create(&files.root, &publisher.bootstrap).unwrap();
            bridge
                .refresh(
                    &publisher.bootstrap,
                    publisher.published(1, 1, false).await,
                    &policy(1),
                    false,
                )
                .await
                .unwrap();
            put(&bridge.journal, "unknown.json", b"{}").unwrap();
            assert!(bridge.inspect(&publisher.bootstrap).is_err());
            let journal = path(&bridge.journal).unwrap();
            // Exact own fixture leaf, no recursive removal.
            std::fs::remove_file(journal.join("unknown.json")).unwrap();
            put(&bridge.journal, "intent-00002.json", b"{").unwrap();
            assert!(bridge.recover(&publisher.bootstrap).is_err());
            assert_eq!(
                get(&bridge.journal, "intent-00002.json", 16384).unwrap(),
                b"{"
            );
            let binding = path(&bridge.control.dir).unwrap().join("binding.json");
            drop(bridge);
            std::fs::write(&binding, b"{}").unwrap();
            assert!(Bridge::reopen(&files.root, &publisher.bootstrap).is_err());
            assert_eq!(std::fs::read(binding).unwrap(), b"{}");
        });
    }
    #[test]
    fn child_before_ready_crash_resumes_without_bootstrap_reset() {
        runtime().block_on(async {
            let files = super::super::tests::Fixture::new();
            let publisher = Publisher::new().await;
            let transport = publisher.published(1, 1, false).await;
            put(&files.root, "bootstrap.json", &publisher.bootstrap).unwrap();
            put(
                &files.root,
                "transport.json",
                &encoded(&*transport.files).unwrap(),
            )
            .unwrap();
            let output = Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "pack::ntfs::bridge::tests::fixture_bridge_child",
                    "--nocapture",
                ])
                .env("AOC_BRIDGE_CHILD", path(&files.root).unwrap())
                .env("AOC_BRIDGE_MODE", "pending")
                .output()
                .unwrap();
            assert_eq!(output.status.code(), Some(37), "{output:?}");
            let mut bridge = Bridge::reopen(&files.root, &publisher.bootstrap).unwrap();
            assert_eq!(
                bridge.recover(&publisher.bootstrap),
                Err("BRIDGE_RECOVERY_REQUIRED")
            );
            assert!(bridge
                .refresh(&publisher.bootstrap, transport, &policy(1), false)
                .await
                .is_ok());
            assert_eq!(bridge.inspect(&publisher.bootstrap).unwrap().1, 1);
            assert!(Bridge::create(&files.root, &publisher.bootstrap).is_err());
        });
    }
    #[test]
    fn corrupted_evidence_future_clock_and_retry_budget_fail_closed() {
        runtime().block_on(async {
            let publisher = Publisher::new().await;
            let good = publisher.published(1, 1, false).await;
            let mut unavailable = good.clone();
            let mut map = (*unavailable.files).clone();
            map.remove("/metadata/1.snapshot.json");
            unavailable.files = Arc::new(map);
            let files = super::super::tests::Fixture::new();
            let mut bridge = Bridge::create(&files.root, &publisher.bootstrap).unwrap();
            assert!(bridge
                .refresh(&publisher.bootstrap, unavailable.clone(), &policy(1), false)
                .await
                .is_err());
            let evidence =
                path(&native(bridge.evidence.child("attempt-00001", false)).unwrap()).unwrap();
            let original = std::fs::read(evidence.join("event-000.json")).unwrap();
            std::fs::write(evidence.join("event-000.json"), b"{}").unwrap();
            assert!(bridge
                .refresh(&publisher.bootstrap, good.clone(), &policy(1), false)
                .await
                .is_err());
            assert_eq!(
                std::fs::read(evidence.join("event-000.json")).unwrap(),
                b"{}"
            );
            // Restore only the exact synthetic fixture bytes, never adapter repair.
            std::fs::write(evidence.join("event-000.json"), original).unwrap();
            let original = std::fs::read(evidence.join("round-000.json")).unwrap();
            let mut json: serde_json::Value = serde_json::from_slice(&original).unwrap();
            json["time"] = (chrono::Utc::now() + chrono::Duration::days(1))
                .to_rfc3339()
                .into();
            std::fs::write(evidence.join("round-000.json"), encoded(&json).unwrap()).unwrap();
            assert!(matches!(
                bridge
                    .refresh(&publisher.bootstrap, good.clone(), &policy(1), false)
                    .await,
                Err("CLOCK_ERROR")
            ));
            std::fs::write(evidence.join("round-000.json"), original).unwrap();
            for _ in 1..4 {
                assert!(bridge
                    .refresh(&publisher.bootstrap, unavailable.clone(), &policy(1), false)
                    .await
                    .is_err());
            }
            assert!(matches!(
                bridge
                    .refresh(&publisher.bootstrap, good, &policy(1), false)
                    .await,
                Err("PARTIAL_RETRY_LIMIT")
            ));
            assert_eq!(bridge.inspect(&publisher.bootstrap).unwrap().1, 1);
        });
    }
}
