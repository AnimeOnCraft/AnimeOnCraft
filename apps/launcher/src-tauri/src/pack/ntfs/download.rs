//! Bounded artifact pipeline. Normal catalog is unconfigured; no path/URL IPC.
use super::{owned::OwnedRoot, *};
use crate::pack::{manifest, trust::VerifiedRelease};
use bytes::Bytes;
use futures::future::{select, Either};
use reqwest::{header, Url};
use std::{
    collections::BTreeMap,
    future::Future,
    time::{Duration, Instant},
};
mod http;
#[cfg(test)]
mod tests;
type DResult<T> = std::result::Result<T, &'static str>;
const BUFFER: usize = 64 * 1024;
const CHUNK_LIMIT: usize = 256 * 1024;
const RESERVE: u64 = 64 * 1024 * 1024;
const PARTS: usize = 32;
const RETRIES: usize = 2;

#[derive(Clone)]
pub(super) struct Cancel {
    sender: Option<tokio::sync::watch::Sender<bool>>,
    receiver: tokio::sync::watch::Receiver<bool>,
}
impl Cancel {
    pub(super) fn receiver(&self) -> tokio::sync::watch::Receiver<bool> {
        self.receiver.clone()
    }
    pub(super) fn new() -> Self {
        let (sender, receiver) = tokio::sync::watch::channel(false);
        Self {
            sender: Some(sender),
            receiver,
        }
    }
    pub(super) fn linked(receiver: tokio::sync::watch::Receiver<bool>) -> Self {
        Self {
            sender: None,
            receiver,
        }
    }
    pub(super) fn cancel(&self) {
        if let Some(sender) = &self.sender {
            sender.send_replace(true);
        }
    }
    pub(super) fn check(&self) -> DResult<()> {
        if *self.receiver.borrow() {
            Err("CANCELLED")
        } else {
            Ok(())
        }
    }
    async fn wait(&self) {
        let mut changes = self.receiver.clone();
        loop {
            if *changes.borrow_and_update() {
                return;
            }
            if changes.changed().await.is_err() {
                return;
            }
        }
    }
}
#[derive(Clone, Copy)]
struct Limits {
    idle: Duration,
    total: Duration,
    backoff: Duration,
}
impl Default for Limits {
    fn default() -> Self {
        Self {
            idle: Duration::from_secs(15),
            total: Duration::from_secs(600),
            backoff: Duration::from_millis(200),
        }
    }
}
async fn bounded<T>(
    cancel: &Cancel,
    end: Instant,
    idle: Duration,
    operation: impl Future<Output = DResult<T>>,
) -> DResult<T> {
    cancel.check()?;
    if Instant::now() >= end {
        return Err("DOWNLOAD_DEADLINE");
    }
    if idle.is_zero() {
        return Err("DOWNLOAD_IDLE");
    }
    let stop = end.min(Instant::now() + idle);
    let work = tokio::time::timeout_at(tokio::time::Instant::from_std(stop), operation);
    futures::pin_mut!(work);
    let cancelled = cancel.wait();
    futures::pin_mut!(cancelled);
    match select(cancelled, work).await {
        Either::Left(_) => Err("CANCELLED"),
        Either::Right((Ok(result), _)) => {
            cancel.check()?;
            if Instant::now() >= stop {
                return Err(if Instant::now() >= end {
                    "DOWNLOAD_DEADLINE"
                } else {
                    "DOWNLOAD_IDLE"
                });
            }
            result
        }
        Either::Right((Err(_), _)) => Err(if Instant::now() >= end {
            "DOWNLOAD_DEADLINE"
        } else {
            "DOWNLOAD_IDLE"
        }),
    }
}
#[async_trait::async_trait]
trait Body: Send {
    async fn next(&mut self) -> DResult<Option<Bytes>>;
}
struct Reply {
    status: u16,
    headers: header::HeaderMap,
    body: Box<dyn Body>,
}
#[async_trait::async_trait]
trait Transport: Send + Sync {
    async fn open(&self, url: Url) -> DResult<Reply>;
}

// Catalog construction is trusted native configuration, never manifest/UI/environment/API data.
pub(super) struct Catalog {
    bases: BTreeMap<String, Vec<Url>>,
    #[cfg(test)]
    http_fixture: bool,
}
impl Catalog {
    fn configured() -> Option<Self> {
        // Never infer source URLs from a manifest or frontend input.
        None
    }
    #[cfg(test)]
    fn fixture() -> Self {
        Self {
            bases: BTreeMap::from([(
                "fixture".into(),
                vec![
                    "https://artifacts.animeoncraft.invalid/artifacts/"
                        .parse()
                        .unwrap(),
                    "https://mirror.animeoncraft.invalid/artifacts/"
                        .parse()
                        .unwrap(),
                ],
            )]),
            http_fixture: false,
        }
    }
    fn allows(&self, source: &str, url: &Url) -> bool {
        (url.scheme() == "https" || self.allows_fixture_http(url))
            && url.username().is_empty()
            && url.password().is_none()
            && url.query().is_none()
            && url.fragment().is_none()
            && self.bases.get(source).is_some_and(|bases| {
                bases.iter().any(|base| {
                    url.origin() == base.origin()
                        && url
                            .path()
                            .strip_prefix(base.path())
                            .is_some_and(manifest::portable)
                })
            })
    }
    fn allows_fixture_http(&self, url: &Url) -> bool {
        #[cfg(test)]
        {
            self.http_fixture && url.scheme() == "http" && url.host_str() == Some("127.0.0.1")
        }
        #[cfg(not(test))]
        {
            let _ = url;
            false
        }
    }
    fn resolve(&self, source: &manifest::Source) -> DResult<Url> {
        if !manifest::portable(&source.object_key) {
            return Err("SOURCE_NOT_APPROVED");
        }
        let base = self
            .bases
            .get(&source.source_id)
            .and_then(|v| v.first())
            .ok_or("SOURCE_NOT_APPROVED")?;
        let url = base
            .join(&source.object_key)
            .map_err(|_| "SOURCE_NOT_APPROVED")?;
        if !self.allows(&source.source_id, &url) {
            return Err("SOURCE_NOT_APPROVED");
        }
        Ok(url)
    }
    fn redirect(&self, source: &str, current: &Url, location: &str) -> DResult<Url> {
        if location.len() > 512
            || location.is_empty()
            || location
                .bytes()
                .any(|b| b <= 32 || b >= 127 || b"%\\?#".contains(&b))
            || location.split('/').any(|p| p == "." || p == "..")
        {
            return Err("SOURCE_NOT_APPROVED");
        }
        let url = current.join(location).map_err(|_| "SOURCE_NOT_APPROVED")?;
        if !self.allows(source, &url) {
            return Err("SOURCE_NOT_APPROVED");
        }
        Ok(url)
    }
}
fn single<'a>(
    headers: &'a header::HeaderMap,
    name: header::HeaderName,
) -> DResult<Option<&'a str>> {
    let all = headers.get_all(name);
    let mut values = all.iter();
    let first = values.next();
    if values.next().is_some() {
        return Err("DOWNLOAD_HEADERS");
    }
    first
        .map(|v| v.to_str().map_err(|_| "DOWNLOAD_HEADERS"))
        .transpose()
}
async fn response<T: Transport>(
    transport: &T,
    catalog: &Catalog,
    source: &manifest::Source,
    length: u64,
    cancel: &Cancel,
    end: Instant,
    idle: Duration,
) -> DResult<Reply> {
    let mut url = catalog.resolve(source)?;
    for hops in 0..=3 {
        let reply = bounded(cancel, end, idle, transport.open(url.clone())).await?;
        if [301, 302, 303, 307, 308].contains(&reply.status) {
            if hops == 3 {
                return Err("DOWNLOAD_REDIRECT_LIMIT");
            }
            let location = single(&reply.headers, header::LOCATION)?.ok_or("DOWNLOAD_HEADERS")?;
            url = catalog.redirect(&source.source_id, &url, location)?;
            continue;
        }
        if [502, 503, 504].contains(&reply.status) {
            return Err("DOWNLOAD_RETRYABLE_HTTP");
        }
        if reply.status != 200 {
            return Err("DOWNLOAD_HTTP");
        }
        if single(&reply.headers, header::CONTENT_ENCODING)?
            .is_some_and(|v| !v.eq_ignore_ascii_case("identity"))
            || single(&reply.headers, header::CONTENT_RANGE)?.is_some()
        {
            return Err("DOWNLOAD_HEADERS");
        }
        if let Some(value) = single(&reply.headers, header::CONTENT_LENGTH)? {
            if value.is_empty()
                || !value.bytes().all(|b| b.is_ascii_digit())
                || value.parse::<u64>().ok() != Some(length)
            {
                return Err("DOWNLOAD_LENGTH");
            }
        }
        return Ok(reply);
    }
    Err("DOWNLOAD_REDIRECT_LIMIT")
}
fn fs<T>(r: Result<T>) -> DResult<T> {
    r.map_err(|_| "DOWNLOAD_STORE_INVALID")
}
fn io<T>(r: io::Result<T>) -> DResult<T> {
    r.map_err(|_| "DOWNLOAD_IO")
}
fn controlled<T>(
    cancel: &Cancel,
    end: Instant,
    error: &'static str,
    operation: impl FnOnce(&mut dyn FnMut() -> Result<()>) -> Result<T>,
) -> DResult<T> {
    let mut interrupted = None;
    let result = operation(&mut || {
        let reason = cancel
            .check()
            .err()
            .or_else(|| (Instant::now() >= end).then_some("DOWNLOAD_DEADLINE"));
        if let Some(reason) = reason {
            interrupted = Some(reason);
            Err(Error::Interrupted)
        } else {
            Ok(())
        }
    });
    result.map_err(|_| interrupted.unwrap_or(error))
}
pub(super) fn free_bytes(dir: &Arc<Dir>) -> DResult<u64> {
    fs(dir.unchanged())?;
    let mut path = vec![0u16; 32768];
    let n = unsafe {
        GetFinalPathNameByHandleW(
            dir.file.as_raw_handle(),
            path.as_mut_ptr(),
            path.len() as u32,
            FILE_NAME_NORMALIZED | VOLUME_NAME_DOS,
        )
    } as usize;
    if n == 0 || n >= path.len() {
        return Err("DOWNLOAD_STORE_INVALID");
    }
    path[n] = 0;
    let mut available = 0;
    if unsafe {
        GetDiskFreeSpaceExW(
            path.as_ptr(),
            &mut available,
            ptr::null_mut(),
            ptr::null_mut(),
        )
    } == 0
    {
        return Err("DOWNLOAD_IO");
    }
    fs(dir.unchanged())?;
    Ok(available)
}
struct Object {
    length: u64,
    hash: String,
    source: manifest::Source,
}
pub(super) struct Prepared {
    pub(super) files: BTreeMap<String, HeldFile>,
    pub(super) manifest: String,
    stats: Stats,
}
#[derive(Default, Debug)]
struct Stats {
    received: u64,
    verified: u64,
    cached: usize,
    attempts: usize,
}
pub(super) struct Store {
    owner: OwnedRoot,
    parts: Arc<Dir>,
    cache: Arc<Dir>,
}
// Test harness shared with the installer: still consumes a signed VerifiedRelease and
// exercises this downloader rather than manufacturing a Prepared capability.
#[cfg(test)]
pub(super) async fn fixture_prepared(
    parent: &Arc<Dir>,
    release: &VerifiedRelease,
    objects: BTreeMap<String, Vec<u8>>,
) -> DResult<Prepared> {
    fixture_prepared_controlled(parent, release, objects, &Cancel::new()).await
}
#[cfg(test)]
pub(super) async fn fixture_prepared_controlled(
    parent: &Arc<Dir>,
    release: &VerifiedRelease,
    objects: BTreeMap<String, Vec<u8>>,
    cancel: &Cancel,
) -> DResult<Prepared> {
    fixture_prepared_store(&mut Store::create(parent)?, release, objects, cancel).await
}
#[cfg(test)]
pub(super) async fn fixture_prepared_store(
    store: &mut Store,
    release: &VerifiedRelease,
    objects: BTreeMap<String, Vec<u8>>,
    cancel: &Cancel,
) -> DResult<Prepared> {
    struct Memory(BTreeMap<String, Vec<u8>>);
    struct Stream(std::collections::VecDeque<Bytes>);
    #[async_trait::async_trait]
    impl Body for Stream {
        async fn next(&mut self) -> DResult<Option<Bytes>> {
            Ok(self.0.pop_front())
        }
    }
    #[async_trait::async_trait]
    impl Transport for Memory {
        async fn open(&self, url: Url) -> DResult<Reply> {
            let key = url
                .path()
                .strip_prefix("/artifacts/")
                .ok_or("SOURCE_NOT_APPROVED")?;
            let bytes = self.0.get(key).ok_or("DOWNLOAD_NETWORK")?;
            let chunks = bytes.chunks(BUFFER).map(Bytes::copy_from_slice).collect();
            Ok(Reply {
                status: 200,
                headers: header::HeaderMap::new(),
                body: Box::new(Stream(chunks)),
            })
        }
    }
    store
        .prepare(
            release,
            &Memory(objects),
            &Catalog::fixture(),
            cancel,
            Limits::default(),
        )
        .await
}
pub(super) enum Source {
    Configured,
    Approved(Catalog),
    #[cfg(feature = "pack-acceptance")]
    Acceptance {
        catalog: Catalog,
        certificate: Vec<u8>,
    },
    #[cfg(test)]
    Fixture(BTreeMap<String, Vec<u8>>),
}
impl Source {
    #[cfg(feature = "pack-acceptance")]
    pub(super) fn with_certificate(self, certificate: Vec<u8>) -> DResult<Self> {
        match self {
            Self::Approved(catalog) => Ok(Self::Acceptance {
                catalog,
                certificate,
            }),
            _ => Err("REPOSITORY_CONFIG_INVALID"),
        }
    }
    pub(super) fn approved(
        bases: BTreeMap<String, Vec<String>>,
        expected: &std::collections::BTreeSet<String>,
    ) -> DResult<Self> {
        if bases.is_empty()
            || bases.len() > 16
            || bases
                .keys()
                .cloned()
                .collect::<std::collections::BTreeSet<_>>()
                != *expected
        {
            return Err("REPOSITORY_CONFIG_INVALID");
        }
        let bases = bases
            .into_iter()
            .map(|(id, urls)| {
                if id.is_empty()
                    || id.len() > 64
                    || !id
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b"-_".contains(&b))
                    || urls.is_empty()
                    || urls.len() > 4
                {
                    return Err("REPOSITORY_CONFIG_INVALID");
                }
                let urls = urls
                    .into_iter()
                    .map(|url| crate::pack::repository::base(&url))
                    .collect::<DResult<Vec<_>>>()?;
                if urls
                    .iter()
                    .map(|u| u.as_str())
                    .collect::<std::collections::BTreeSet<_>>()
                    .len()
                    != urls.len()
                {
                    return Err("REPOSITORY_CONFIG_INVALID");
                }
                Ok((id, urls))
            })
            .collect::<DResult<BTreeMap<_, _>>>()?;
        Ok(Self::Approved(Catalog {
            bases,
            #[cfg(test)]
            http_fixture: false,
        }))
    }
    pub(super) fn available(&self) -> bool {
        match self {
            Self::Configured => Catalog::configured().is_some(),
            Self::Approved(_) => true,
            #[cfg(feature = "pack-acceptance")]
            Self::Acceptance { .. } => true,
            #[cfg(test)]
            Self::Fixture(_) => true,
        }
    }
    pub(super) async fn prepare(
        &self,
        store: &mut Store,
        release: &VerifiedRelease,
        cancel: &Cancel,
    ) -> DResult<Prepared> {
        match self {
            Self::Configured => {
                let catalog = Catalog::configured().ok_or("RELEASE_NOT_CONFIGURED")?;
                store
                    .prepare(
                        release,
                        &http::Http::new()?,
                        &catalog,
                        cancel,
                        Limits::default(),
                    )
                    .await
            }
            Self::Approved(catalog) => {
                store
                    .prepare(
                        release,
                        &http::Http::new()?,
                        catalog,
                        cancel,
                        Limits::default(),
                    )
                    .await
            }
            #[cfg(feature = "pack-acceptance")]
            Self::Acceptance {
                catalog,
                certificate,
            } => {
                store
                    .prepare(
                        release,
                        &http::Http::with_certificate(certificate)?,
                        catalog,
                        cancel,
                        Limits::default(),
                    )
                    .await
            }
            #[cfg(test)]
            Self::Fixture(objects) => {
                fixture_prepared_store(store, release, objects.clone(), cancel).await
            }
        }
    }
}
impl Store {
    pub(super) fn receipt(&self) -> super::owned::Receipt {
        self.owner.receipt.clone()
    }
    pub(super) fn validate_layout(&self) -> DResult<()> {
        fs(self.owner.marker())?;
        fs(self
            .owner
            .dir
            .exact_names(&["aoc-instance.json", "parts", "objects"]))?;
        for entry in fs(self.cache.entries(
            manifest::MAX_FILES,
            Instant::now() + Duration::from_secs(10),
        ))? {
            if !entry
                .name
                .strip_suffix(".blob")
                .is_some_and(manifest::digest)
            {
                return Err("DOWNLOAD_STORE_INVALID");
            }
            fs(self.cache.file(&entry.name, false))?;
        }
        for entry in fs(self
            .parts
            .entries(PARTS, Instant::now() + Duration::from_secs(10)))?
        {
            if !entry.name.strip_suffix(".part").is_some_and(|name| {
                name.len() == 32
                    && name
                        .bytes()
                        .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
            }) {
                return Err("DOWNLOAD_PARTS_BLOCKED");
            }
            fs(self.parts.file(&entry.name, false))?;
        }
        Ok(())
    }
    #[cfg(test)]
    pub(super) fn create(parent: &Arc<Dir>) -> DResult<Self> {
        Self::create_scoped(parent, "ntfs-owned-test-v1")
    }
    pub(super) fn create_scoped(parent: &Arc<Dir>, environment: &'static str) -> DResult<Self> {
        let owner = fs(OwnedRoot::create_scoped(parent, "downloads", environment))?;
        let parts = fs(owner.dir.child("parts", true))?;
        let cache = fs(owner.dir.child("objects", true))?;
        Ok(Self {
            owner,
            parts,
            cache,
        })
    }
    #[cfg(test)]
    pub(super) fn reopen(parent: &Arc<Dir>, receipt: super::owned::Receipt) -> DResult<Self> {
        Self::reopen_scoped(parent, receipt, "ntfs-owned-test-v1")
    }
    pub(super) fn reopen_scoped(
        parent: &Arc<Dir>,
        receipt: super::owned::Receipt,
        environment: &'static str,
    ) -> DResult<Self> {
        let owner = fs(OwnedRoot::reopen_scoped(
            parent,
            "downloads",
            receipt,
            environment,
        ))?;
        let parts = fs(owner.dir.child("parts", false))?;
        let cache = fs(owner.dir.child("objects", false))?;
        Ok(Self {
            owner,
            parts,
            cache,
        })
    }
    // &mut self gives one worker per handle-owning store. Cross-process install actor is later.
    async fn prepare<T: Transport>(
        &mut self,
        release: &VerifiedRelease,
        transport: &T,
        catalog: &Catalog,
        cancel: &Cancel,
        limits: Limits,
    ) -> DResult<Prepared> {
        cancel.check()?;
        release.current()?;
        fs(self.owner.marker())?;
        let mut objects = BTreeMap::new();
        for file in &release.manifest().manifest().files {
            if file.side == manifest::Side::SERVER {
                continue;
            }
            // Resolve every source, including duplicates/cache hits, before any mutation.
            catalog.resolve(&file.source_ref)?;
            objects
                .entry(file.sha256.clone())
                .or_insert_with(|| Object {
                    length: file.length,
                    hash: file.sha256.clone(),
                    source: file.source_ref.clone(),
                });
        }
        let names: std::collections::BTreeSet<_> = fs(self.cache.entries(
            manifest::MAX_FILES,
            Instant::now() + Duration::from_secs(10),
        ))?
        .into_iter()
        .map(|e| e.name)
        .collect();
        if names
            .iter()
            .any(|n| !n.strip_suffix(".blob").is_some_and(manifest::digest))
        {
            return Err("DOWNLOAD_STORE_INVALID");
        }
        if names.len()
            + objects
                .keys()
                .filter(|hash| !names.contains(&format!("{hash}.blob")))
                .count()
            > manifest::MAX_FILES
        {
            return Err("DOWNLOAD_STORE_LIMIT");
        }
        let parts = fs(self
            .parts
            .entries(PARTS, Instant::now() + Duration::from_secs(10)))?;
        if parts.len() >= PARTS
            || parts.iter().any(|e| {
                !e.name.strip_suffix(".part").is_some_and(|n| {
                    n.len() == 32
                        && n.bytes()
                            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                })
            })
        {
            return Err("DOWNLOAD_PARTS_BLOCKED");
        }
        let mut files = BTreeMap::new();
        let mut stats = Stats::default();
        let mut required = RESERVE;
        for (hash, object) in &objects {
            let name = format!("{hash}.blob");
            if names.contains(&name) {
                let mut held = fs(self.cache.file(&name, false))?;
                controlled(
                    cancel,
                    Instant::now() + limits.total,
                    "DOWNLOAD_CACHE_INVALID",
                    |check| held.fingerprint_controlled(object.length, hash, check),
                )?;
                stats.cached += 1;
                stats.verified = stats
                    .verified
                    .checked_add(object.length)
                    .ok_or("DOWNLOAD_SIZE_LIMIT")?;
                files.insert(hash.clone(), held);
            } else {
                required = required
                    .checked_add(object.length)
                    .ok_or("DOWNLOAD_SIZE_LIMIT")?;
            }
        }
        if free_bytes(&self.cache)? < required {
            return Err("DOWNLOAD_NO_SPACE");
        }
        for (hash, object) in &objects {
            cancel.check()?;
            if files.contains_key(hash) {
                continue;
            }
            let end = Instant::now() + limits.total;
            let mut saved = None;
            for attempt in 0..RETRIES {
                cancel.check()?;
                if Instant::now() >= end {
                    return Err("DOWNLOAD_DEADLINE");
                }
                if free_bytes(&self.parts)?
                    < object
                        .length
                        .checked_add(RESERVE)
                        .ok_or("DOWNLOAD_SIZE_LIMIT")?
                {
                    return Err("DOWNLOAD_NO_SPACE");
                }
                let name = format!("{}.part", uuid::Uuid::new_v4().simple());
                let mut part = fs(self.parts.file(&name, true))?;
                stats.attempts += 1;
                let result = self
                    .receive(
                        &mut part, object, transport, catalog, cancel, end, limits, &mut stats,
                    )
                    .await;
                if let Err(error) = result {
                    if part.delete_owned().is_err() {
                        return Err("DOWNLOAD_CLEANUP_REQUIRED");
                    }
                    if attempt + 1 < RETRIES
                        && [
                            "DOWNLOAD_NETWORK",
                            "DOWNLOAD_IDLE",
                            "DOWNLOAD_RETRYABLE_HTTP",
                        ]
                        .contains(&error)
                    {
                        // An intentional retry pause is bounded by total time, not by
                        // the network idle deadline. A zero pause does not arm a timer.
                        if !limits.backoff.is_zero() {
                            bounded(
                                cancel,
                                end,
                                end.saturating_duration_since(Instant::now()),
                                async {
                                    tokio::time::sleep(limits.backoff).await;
                                    Ok(())
                                },
                            )
                            .await?;
                        }
                        continue;
                    }
                    return Err(error);
                }
                if let Err(error) = controlled(cancel, end, "DOWNLOAD_STORE_INVALID", |check| {
                    part.rename_empty_controlled(&self.cache, &format!("{hash}.blob"), check)
                }) {
                    if part.delete_owned().is_err() {
                        return Err("DOWNLOAD_CLEANUP_REQUIRED");
                    }
                    return Err(error);
                }
                saved = Some(part);
                break;
            }
            files.insert(hash.clone(), saved.ok_or("DOWNLOAD_NETWORK")?);
            stats.verified = stats
                .verified
                .checked_add(object.length)
                .ok_or("DOWNLOAD_SIZE_LIMIT")?;
        }
        cancel.check()?;
        release.current()?;
        Ok(Prepared {
            files,
            manifest: release.manifest().digest().into(),
            stats,
        })
    }
    async fn receive<T: Transport>(
        &self,
        part: &mut HeldFile,
        object: &Object,
        transport: &T,
        catalog: &Catalog,
        cancel: &Cancel,
        end: Instant,
        limits: Limits,
        stats: &mut Stats,
    ) -> DResult<()> {
        let mut reply = response(
            transport,
            catalog,
            &object.source,
            object.length,
            cancel,
            end,
            limits.idle,
        )
        .await?;
        let mut length = 0u64;
        let mut digest = Sha256::new();
        let mut progress = Instant::now();
        loop {
            let idle = (progress + limits.idle).saturating_duration_since(Instant::now());
            let Some(bytes) = bounded(cancel, end, idle, reply.body.next()).await? else {
                break;
            };
            if bytes.is_empty() {
                continue;
            }
            if bytes.len() > CHUNK_LIMIT {
                return Err("DOWNLOAD_CHUNK_LIMIT");
            }
            stats.received = stats
                .received
                .checked_add(bytes.len() as u64)
                .ok_or("DOWNLOAD_SIZE_LIMIT")?;
            length = length
                .checked_add(bytes.len() as u64)
                .ok_or("DOWNLOAD_SIZE_LIMIT")?;
            if length > object.length {
                return Err("DOWNLOAD_LENGTH");
            }
            for chunk in bytes.chunks(BUFFER) {
                cancel.check()?;
                if Instant::now() >= end {
                    return Err("DOWNLOAD_DEADLINE");
                }
                fs(part.unchanged())?;
                io(part.file.write_all(chunk))?;
                digest.update(chunk);
            }
            progress = Instant::now();
        }
        if length != object.length {
            return Err("DOWNLOAD_LENGTH");
        }
        if format!("{:x}", digest.finalize()) != object.hash {
            return Err("DOWNLOAD_HASH");
        }
        cancel.check()?;
        io(part.file.sync_all())?;
        controlled(cancel, end, "DOWNLOAD_STORE_INVALID", |check| {
            part.fingerprint_controlled(object.length, &object.hash, check)
        })?;
        cancel.check()?;
        if Instant::now() >= end {
            return Err("DOWNLOAD_DEADLINE");
        }
        Ok(())
    }
}
