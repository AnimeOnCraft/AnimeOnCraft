//! Durable pre-consumption evidence. Signed version fences are intentionally conservative;
//! unverified/rollback responses are retained, never promoted as trusted metadata.
use super::*;
use std::collections::BTreeSet;
const ROUNDS: usize = 4;
const EVENTS: usize = 160;
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Request {
    pack_id: String,
    channel: String,
    sequence: u64,
    minecraft_version: String,
    loader: manifest::Loader,
    java: manifest::Java,
    launcher_version: String,
    protocol: u16,
    approved_sources: BTreeSet<String>,
}
impl Request {
    pub(super) fn of(p: &manifest::ReleasePolicy) -> Self {
        Self {
            pack_id: p.pack_id.clone(),
            channel: p.channel.clone(),
            sequence: p.sequence,
            minecraft_version: p.minecraft_version.clone(),
            loader: p.loader.clone(),
            java: p.java.clone(),
            launcher_version: p.launcher_version.to_string(),
            protocol: p.protocol,
            approved_sources: p.approved_sources.clone(),
        }
    }
    pub(super) fn policy(&self) -> BResult<manifest::ReleasePolicy> {
        Ok(manifest::ReleasePolicy {
            pack_id: self.pack_id.clone(),
            channel: self.channel.clone(),
            sequence: self.sequence,
            minecraft_version: self.minecraft_version.clone(),
            loader: self.loader.clone(),
            java: self.java.clone(),
            launcher_version: self.launcher_version.parse().map_err(|_| "STORE_INVALID")?,
            protocol: self.protocol,
            approved_sources: self.approved_sources.clone(),
        })
    }
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Round {
    format: u8,
    number: usize,
    time: String,
    request: Request,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Event {
    format: u8,
    number: usize,
    round: usize,
    path: String,
    time: String,
    hash: String,
    body: TextBytes,
}
pub(super) struct Tracker {
    bootstrap: TextBytes,
    pub(super) chain: Vec<TextBytes>,
    root: Signed<Root>,
    roles: BTreeMap<String, TextBytes>,
    snapshot_floor: u64,
    targets_floor: u64,
    release_floor: u64,
    release_digest: Option<String>,
    time: chrono::DateTime<chrono::FixedOffset>,
    request: Request,
}
fn version(bytes: &[u8]) -> BResult<u64> {
    let value: serde_json::Value = decode(bytes, manifest::MAX_MANIFEST)?;
    value["signed"]["version"]
        .as_u64()
        .filter(|n| *n > 0)
        .ok_or("STORE_INVALID")
}
fn same_signed(a: &[u8], b: &[u8]) -> BResult<bool> {
    let a: serde_json::Value = decode(a, manifest::MAX_MANIFEST)?;
    let b: serde_json::Value = decode(b, manifest::MAX_MANIFEST)?;
    Ok(a["signed"] == b["signed"])
}
fn supported_path(path: &str) -> bool {
    if path.starts_with("/targets/") {
        return !path.contains('%') && !path.contains("..") && path.len() < 512;
    }
    let Some(name) = path.strip_prefix("/metadata/") else {
        return false;
    };
    if name == "timestamp.json" {
        return true;
    }
    for suffix in [".root.json", ".snapshot.json", ".targets.json"] {
        if let Some(n) = name.strip_suffix(suffix) {
            return n.parse::<u64>().is_ok_and(|v| v > 0 && n == v.to_string());
        }
    }
    false
}
impl Tracker {
    fn new(bootstrap: &[u8], old: Option<&Bundle>, request: Request) -> BResult<Self> {
        let (chain, root, roles, time, release_floor, release_digest) = if let Some(b) = old {
            validate_bundle(b, bootstrap)?;
            let cp: trust::Checkpoint = decode(&b.files["aoc-trust.json"], 16384)?;
            (
                b.chain.clone(),
                root_chain(bootstrap, &b.chain, &b.files["root.json"])?,
                b.raw.clone(),
                chrono::DateTime::parse_from_rfc3339(&cp.time_floor).map_err(|_| "CLOCK_ERROR")?,
                cp.sequence,
                cp.manifest_digest,
            )
        } else {
            (
                Vec::new(),
                signed(bootstrap)?,
                BTreeMap::new(),
                chrono::DateTime::parse_from_rfc3339("2020-01-01T00:00:00Z").unwrap(),
                0,
                None,
            )
        };
        let mut t = Self {
            bootstrap: TextBytes(bootstrap.to_vec()),
            chain,
            root,
            roles,
            snapshot_floor: 0,
            targets_floor: 0,
            release_floor,
            release_digest,
            time,
            request,
        };
        t.references()?;
        Ok(t)
    }
    fn clock(&mut self, time: &str) -> BResult<()> {
        let time = chrono::DateTime::parse_from_rfc3339(time).map_err(|_| "CLOCK_ERROR")?;
        if time > chrono::Utc::now() || time < self.time {
            return Err("CLOCK_ERROR");
        }
        self.time = time;
        Ok(())
    }
    fn references(&mut self) -> BResult<()> {
        if let Some(bytes) = self.roles.get("timestamp.json") {
            let v: Signed<Timestamp> = signed(bytes)?;
            self.snapshot_floor = self.snapshot_floor.max(
                v.signed
                    .meta
                    .get("snapshot.json")
                    .ok_or("TUF_REJECTED")?
                    .version
                    .get(),
            );
        }
        if let Some(bytes) = self.roles.get("snapshot.json") {
            let v: Signed<Snapshot> = signed(bytes)?;
            self.snapshot_floor = self.snapshot_floor.max(v.signed.version.get());
            self.targets_floor = self.targets_floor.max(
                v.signed
                    .meta
                    .get("targets.json")
                    .ok_or("TUF_REJECTED")?
                    .version
                    .get(),
            );
        }
        if let Some(bytes) = self.roles.get("targets.json") {
            self.targets_floor = self.targets_floor.max(version(bytes)?);
        }
        Ok(())
    }
    // A rejected live response cannot lower/update the fences. Replay ignores such a
    // response; the subsequent online verification is still required for release.
    fn accept(&mut self, path: &str, body: &[u8]) -> BResult<()> {
        if !supported_path(path) {
            return Err("SOURCE_NOT_APPROVED");
        }
        if path.ends_with(".root.json") {
            let Ok(next) = signed::<Root>(body) else {
                return Ok(());
            };
            let n = next.signed.version.get();
            if n <= self.root.signed.version.get() {
                let known = if n == signed::<Root>(&self.bootstrap)?.signed.version.get() {
                    Some(&self.bootstrap)
                } else {
                    self.chain.iter().find(|b| version(b).ok() == Some(n))
                };
                if !known.is_some_and(|b| same_signed(b, body).unwrap_or(false)) {
                    return Err("PARTIAL_EQUIVOCATION");
                }
                return Ok(());
            }
            if self.root.signed.verify_role(&next).is_err()
                || next.signed.verify_role(&next).is_err()
            {
                return Ok(());
            }
            if n != self
                .root
                .signed
                .version
                .get()
                .checked_add(1)
                .ok_or("ROOT_CHAIN_LIMIT")?
            {
                return Err("ROOT_CHAIN_INVALID");
            }
            trust::root_policy(body)?;
            if self.chain.len() >= 32 {
                return Err("ROOT_CHAIN_LIMIT");
            }
            self.chain.push(TextBytes(body.to_vec()));
            self.root = next;
            return Ok(());
        }
        if path.starts_with("/targets/") {
            return self.target(body);
        }
        let name = if path.ends_with("timestamp.json") {
            "timestamp.json"
        } else if path.ends_with("snapshot.json") {
            "snapshot.json"
        } else {
            "targets.json"
        };
        let valid = match name {
            "timestamp.json" => signed::<Timestamp>(body)
                .ok()
                .is_some_and(|v| self.root.signed.verify_role(&v).is_ok()),
            "snapshot.json" => signed::<Snapshot>(body)
                .ok()
                .is_some_and(|v| self.root.signed.verify_role(&v).is_ok()),
            _ => signed::<Targets>(body)
                .ok()
                .is_some_and(|v| self.root.signed.verify_role(&v).is_ok()),
        };
        if !valid {
            return Ok(());
        }
        let n = version(body)?;
        let floor = match name {
            "snapshot.json" => self.snapshot_floor,
            "targets.json" => self.targets_floor,
            _ => 0,
        };
        if n < floor {
            return Err("PARTIAL_ROLLBACK");
        }
        if let Some(old) = self.roles.get(name) {
            let old_version = version(old)?;
            if n < old_version {
                return Err("PARTIAL_ROLLBACK");
            }
            if n == old_version && !same_signed(old, body)? {
                return Err("PARTIAL_EQUIVOCATION");
            }
        }
        self.roles.insert(name.into(), TextBytes(body.to_vec()));
        self.references()
    }
    fn target(&mut self, body: &[u8]) -> BResult<()> {
        let Some(bytes) = self.roles.get("targets.json") else {
            return Ok(());
        };
        let targets: Signed<Targets> = signed(bytes)?;
        let p = self.request.policy()?;
        let name =
            tough::TargetName::new(format!("packs/{}/{}/manifest.json", p.channel, p.sequence))
                .map_err(|_| "INVALID_MANIFEST")?;
        let Some(entry) = targets.signed.targets.get(&name) else {
            return Ok(());
        };
        if entry.length != body.len() as u64
            || entry.hashes.sha256.as_ref() != &Sha256::digest(body)[..]
        {
            return Ok(());
        };
        let Ok(checked) = manifest::parse(body, &p) else {
            return Ok(());
        };
        if p.sequence < self.release_floor
            || (p.sequence == self.release_floor
                && self
                    .release_digest
                    .as_ref()
                    .is_some_and(|d| d != checked.digest()))
        {
            return Err("PARTIAL_ROLLBACK");
        };
        self.release_floor = p.sequence;
        self.release_digest = Some(checked.digest().into());
        Ok(())
    }
    fn check_policy(&self, p: &manifest::ReleasePolicy) -> BResult<()> {
        if p.pack_id != self.request.pack_id
            || p.channel != self.request.channel
            || p.sequence < self.release_floor
        {
            return Err("PARTIAL_ROLLBACK");
        };
        Ok(())
    }
    pub(super) fn stamp(&self, b: &mut Bundle) -> BResult<()> {
        let mut cp: trust::Checkpoint = decode(&b.files["aoc-trust.json"], 16384)?;
        let floor =
            chrono::DateTime::parse_from_rfc3339(&cp.time_floor).map_err(|_| "CLOCK_ERROR")?;
        if self.time > chrono::Utc::now() {
            return Err("CLOCK_ERROR");
        };
        if cp.sequence < self.release_floor
            || (cp.sequence == self.release_floor && self.release_digest != cp.manifest_digest)
        {
            return Err("PARTIAL_ROLLBACK");
        };
        for (name, old) in &self.roles {
            let new = &b.raw[name];
            let n = version(new)?;
            let o = version(old)?;
            if n < o || (n == o && !same_signed(new, old)?) {
                return Err("PARTIAL_ROLLBACK");
            };
        }
        if version(&b.raw["snapshot.json"])? < self.snapshot_floor
            || version(&b.raw["targets.json"])? < self.targets_floor
            || b.chain.len() < self.chain.len()
            || !b.chain.iter().zip(&self.chain).all(|(a, b)| a.0 == b.0)
        {
            return Err("PARTIAL_ROLLBACK");
        };
        let time = self.time.max(floor);
        cp.time_floor = time.to_rfc3339();
        b.files.insert(
            "latest_known_time.json".into(),
            TextBytes(encoded(&cp.time_floor)?),
        );
        b.files
            .insert("aoc-trust.json".into(), TextBytes(encoded(&cp)?));
        Ok(())
    }
}
pub(super) struct Attempt {
    pub(super) tracker: Arc<Mutex<Tracker>>,
    dir: Arc<Dir>,
    pub(super) round: usize,
    next: Mutex<usize>,
}
impl Attempt {
    pub(super) fn open(
        dir: Arc<Dir>,
        bootstrap: &[u8],
        old: Option<&Bundle>,
        request: Request,
        p: &manifest::ReleasePolicy,
    ) -> BResult<Arc<Self>> {
        let entries =
            native(dir.entries(EVENTS + ROUNDS, Instant::now() + Duration::from_secs(10)))?;
        let mut rounds = BTreeMap::new();
        let mut events = BTreeMap::new();
        for entry in entries {
            let (kind, number) = entry.name.split_once('-').ok_or("STORE_INVALID")?;
            let n = number
                .strip_suffix(".json")
                .ok_or("STORE_INVALID")?
                .parse::<usize>()
                .map_err(|_| "STORE_INVALID")?;
            match kind {
                "round" if n < ROUNDS && entry.name == format!("round-{n:03}.json") => {
                    rounds.insert(n, decode::<Round>(&get(&dir, &entry.name, 16384)?, 16384)?);
                }
                "event" if n < EVENTS && entry.name == format!("event-{n:03}.json") => {
                    events.insert(
                        n,
                        decode::<Event>(
                            &get(&dir, &entry.name, 4 * manifest::MAX_MANIFEST)?,
                            4 * manifest::MAX_MANIFEST,
                        )?,
                    );
                }
                _ => return Err("STORE_INVALID"),
            }
        }
        let mut tracker = Tracker::new(bootstrap, old, request)?;
        for n in 0..rounds.len() {
            let r = rounds.get(&n).ok_or("STORE_INVALID")?;
            if r.format != 1 || r.number != n {
                return Err("STORE_INVALID");
            };
        }
        let mut last_round = 0;
        let mut clock_round = 0;
        for n in 0..events.len() {
            let e = events.get(&n).ok_or("STORE_INVALID")?;
            if e.format != 1
                || e.number != n
                || e.round < last_round
                || !rounds.contains_key(&e.round)
                || !supported_path(&e.path)
                || e.hash != manifest::hash(&e.body)
            {
                return Err("STORE_INVALID");
            };
            while clock_round <= e.round {
                let round = &rounds[&clock_round];
                tracker.check_policy(&round.request.policy()?)?;
                tracker.request = round.request.clone();
                tracker.clock(&round.time)?;
                clock_round += 1;
            }
            tracker.clock(&e.time)?;
            match tracker.accept(&e.path, &e.body) {
                Ok(()) | Err("PARTIAL_ROLLBACK" | "PARTIAL_EQUIVOCATION") => {}
                Err(e) => return Err(e),
            };
            last_round = e.round;
        }
        // All begin times, including a crash before any response, are preserved.
        while clock_round < rounds.len() {
            let round = &rounds[&clock_round];
            tracker.check_policy(&round.request.policy()?)?;
            tracker.request = round.request.clone();
            tracker.clock(&round.time)?;
            clock_round += 1;
        }
        tracker.check_policy(p)?;
        tracker.request = Request::of(p);
        let round = rounds.len();
        if round >= ROUNDS {
            return Err("PARTIAL_RETRY_LIMIT");
        };
        let time = chrono::Utc::now().to_rfc3339();
        tracker.clock(&time)?;
        put(
            &dir,
            &format!("round-{round:03}.json"),
            &encoded(&Round {
                format: 1,
                number: round,
                time,
                request: Request::of(p),
            })?,
        )?;
        Ok(Arc::new(Self {
            tracker: Arc::new(Mutex::new(tracker)),
            dir,
            round,
            next: Mutex::new(events.len()),
        }))
    }
    fn capture(&self, path: &str, body: Vec<u8>) -> BResult<()> {
        let mut next = self.next.lock().map_err(|_| "STORE_INVALID")?;
        if *next >= EVENTS {
            return Err("PARTIAL_RETRY_LIMIT");
        };
        let time = chrono::Utc::now().to_rfc3339();
        let mut tracker = self.tracker.lock().map_err(|_| "STORE_INVALID")?;
        tracker.clock(&time)?;
        let event = Event {
            format: 1,
            number: *next,
            round: self.round,
            path: path.into(),
            time,
            hash: manifest::hash(&body),
            body: TextBytes(body),
        };
        put(
            &self.dir,
            &format!("event-{:03}.json", *next),
            &encoded(&event)?,
        )?;
        *next += 1;
        tracker.accept(path, &event.body)
    }
}
#[derive(Clone)]
pub(super) struct Fenced<T> {
    pub(super) inner: T,
    pub(super) attempt: Arc<Attempt>,
    pub(super) endpoints: crate::pack::repository::Endpoints,
}
impl<T: std::fmt::Debug> std::fmt::Debug for Fenced<T> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Fenced")
            .field("inner", &self.inner)
            .finish_non_exhaustive()
    }
}
#[async_trait::async_trait]
impl<T: Transport + Clone + Send + Sync + 'static> Transport for Fenced<T> {
    async fn fetch(
        &self,
        url: reqwest::Url,
    ) -> std::result::Result<TransportStream, TransportError> {
        let path = self.endpoints.canonical(&url).map_err(rejection)?;
        if !supported_path(&path) {
            return Err(rejection("SOURCE_NOT_APPROVED"));
        };
        // Already-verified intermediate roots are replayed to the standard TUF loader.
        let replay = {
            let tracker = self.attempt.tracker.lock().map_err(rejection)?;
            tracker
                .chain
                .iter()
                .find(|b| path == format!("/metadata/{}.root.json", version(b).unwrap_or(0)))
                .cloned()
        };
        if let Some(bytes) = replay {
            return Ok(Box::pin(futures::stream::iter([Ok(bytes::Bytes::from(
                bytes.0,
            ))])));
        };
        let mut stream = self.inner.fetch(url.clone()).await?;
        let mut bytes = Vec::new();
        while let Some(chunk) = stream.try_next().await? {
            if bytes.len() + chunk.len() > manifest::MAX_MANIFEST {
                return Err(rejection("SIZE_LIMIT"));
            };
            bytes.extend_from_slice(&chunk);
        }
        self.attempt
            .capture(&path, bytes.clone())
            .map_err(rejection)?;
        #[cfg(test)]
        if path.starts_with("/targets/")
            && std::env::var("AOC_BRIDGE_MODE").as_deref() == Ok("target")
        {
            // Test-only no-destructors fault boundary after durable evidence, before
            // the caller/library consumes the manifest. Never compiled in the app.
            std::process::exit(37);
        }
        Ok(Box::pin(futures::stream::iter([Ok(bytes::Bytes::from(
            bytes,
        ))])))
    }
}
fn rejection<E>(_: E) -> TransportError {
    TransportError::new(TransportErrorKind::Other, "partial recovery rejected")
}
