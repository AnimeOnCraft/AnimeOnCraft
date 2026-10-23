use super::super::{bridge::Bridge, tests::Fixture};
use super::*;
use crate::pack::tests::{self as publisher, runtime};
use std::{
    collections::VecDeque,
    net::TcpListener,
    sync::{
        atomic::{AtomicBool, Ordering},
        Mutex,
    },
    thread,
};

#[derive(Clone)]
enum Step {
    Bytes(Vec<u8>),
    Error,
    Delay(Duration, Vec<u8>),
    Cancel(Cancel),
    Link(Arc<Dir>, std::path::PathBuf),
}
#[derive(Clone)]
struct Answer {
    status: u16,
    headers: header::HeaderMap,
    steps: Vec<Step>,
}
fn answer(bytes: &[u8]) -> Answer {
    Answer {
        status: 200,
        headers: header::HeaderMap::new(),
        steps: vec![Step::Bytes(bytes.into())],
    }
}
struct Memory {
    answers: Mutex<VecDeque<Answer>>,
    requests: Mutex<Vec<Url>>,
}
impl Memory {
    fn new(answers: Vec<Answer>) -> Self {
        Self {
            answers: Mutex::new(answers.into()),
            requests: Mutex::new(Vec::new()),
        }
    }
    fn calls(&self) -> usize {
        self.requests.lock().unwrap().len()
    }
}
struct Chunks(VecDeque<Step>);
#[async_trait::async_trait]
impl Body for Chunks {
    async fn next(&mut self) -> DResult<Option<Bytes>> {
        match self.0.pop_front() {
            None => Ok(None),
            Some(Step::Bytes(bytes)) => Ok(Some(bytes.into())),
            Some(Step::Error) => Err("DOWNLOAD_NETWORK"),
            Some(Step::Delay(delay, bytes)) => {
                tokio::time::sleep(delay).await;
                Ok(Some(bytes.into()))
            }
            Some(Step::Cancel(cancel)) => {
                cancel.cancel();
                futures::future::pending().await
            }
            Some(Step::Link(parts, destination)) => {
                let entries = parts
                    .entries(PARTS, Instant::now() + Duration::from_secs(1))
                    .unwrap();
                assert_eq!(entries.len(), 1);
                std::fs::hard_link(fixture_path(&parts).join(&entries[0].name), destination)
                    .unwrap();
                Ok(Some(Bytes::from_static(b"-only bytes")))
            }
        }
    }
}
#[async_trait::async_trait]
impl Transport for Memory {
    async fn open(&self, url: Url) -> DResult<Reply> {
        self.requests.lock().unwrap().push(url);
        let a = self
            .answers
            .lock()
            .unwrap()
            .pop_front()
            .ok_or("DOWNLOAD_NETWORK")?;
        Ok(Reply {
            status: a.status,
            headers: a.headers,
            body: Box::new(Chunks(a.steps.into())),
        })
    }
}
async fn release(files: &Fixture, document: &manifest::Manifest) -> VerifiedRelease {
    let p = publisher::Fixture::new().await;
    let mut bridge = Bridge::create(&files.root, &p.bootstrap).unwrap();
    bridge
        .refresh(
            &p.bootstrap,
            p.published_manifest(document, 1, false).await,
            &publisher::policy(1),
            false,
        )
        .await
        .unwrap()
}
fn empty_parts(store: &Store) {
    assert!(store
        .parts
        .entries(PARTS, Instant::now() + Duration::from_secs(2))
        .unwrap()
        .is_empty());
}
fn cache_count(store: &Store) -> usize {
    store
        .cache
        .entries(manifest::MAX_FILES, Instant::now() + Duration::from_secs(2))
        .unwrap()
        .len()
}
fn quick() -> Limits {
    Limits {
        idle: Duration::from_secs(2),
        total: Duration::from_secs(5),
        backoff: Duration::ZERO,
    }
}
fn fixture_path(dir: &Arc<Dir>) -> std::path::PathBuf {
    dir.unchanged().unwrap();
    let mut buffer = vec![0u16; 32768];
    let n = unsafe {
        GetFinalPathNameByHandleW(
            dir.file.as_raw_handle(),
            buffer.as_mut_ptr(),
            buffer.len() as u32,
            FILE_NAME_NORMALIZED | VOLUME_NAME_DOS,
        )
    } as usize;
    assert!(n > 0 && n < buffer.len());
    std::path::PathBuf::from(String::from_utf16(&buffer[..n]).unwrap())
}
fn error<T>(result: DResult<T>, expected: &str) {
    match result {
        Err(e) => assert_eq!(e, expected),
        Ok(_) => panic!("unexpected success"),
    }
}

#[test]
fn signed_release_streams_deduplicates_filters_server_and_verifies_empty() {
    runtime().block_on(async {
        let files = Fixture::new();
        let mut document = publisher::document(1);
        let mut duplicate = document.files[0].clone();
        duplicate.path = "libraries/duplicate.jar".into();
        document.files.push(duplicate);
        let mut empty = document.files[0].clone();
        empty.path = "libraries/empty.jar".into();
        empty.length = 0;
        empty.sha256 = manifest::hash(&[]);
        empty.source_ref.object_key = "objects/empty.bin".into();
        document.files.push(empty);
        let mut server = document.files[0].clone();
        server.path = "mods/server.jar".into();
        server.side = manifest::Side::SERVER;
        server.sha256 = manifest::hash(b"server");
        server.length = 6;
        document.files.push(server);
        let verified = release(&files, &document).await;
        let payload = b"test-only bytes";
        // Objects are sorted by digest; answers still have to match the requested object.
        struct ByKey;
        #[async_trait::async_trait]
        impl Transport for ByKey {
            async fn open(&self, url: Url) -> DResult<Reply> {
                let a = if url.path().ends_with("empty.bin") {
                    answer(&[])
                } else {
                    Answer {
                        steps: vec![
                            Step::Bytes(b"test".to_vec()),
                            Step::Bytes(b"-only ".to_vec()),
                            Step::Bytes(b"bytes".to_vec()),
                        ],
                        ..answer(&[])
                    }
                };
                Ok(Reply {
                    status: a.status,
                    headers: a.headers,
                    body: Box::new(Chunks(a.steps.into())),
                })
            }
        }
        let mut store = Store::create(&files.root).unwrap();
        let mut prepared = store
            .prepare(
                &verified,
                &ByKey,
                &Catalog::fixture(),
                &Cancel::new(),
                quick(),
            )
            .await
            .unwrap();
        assert_eq!(prepared.files.len(), 2);
        assert_eq!(prepared.stats.attempts, 2);
        assert_eq!(prepared.stats.received, 15);
        assert_eq!(prepared.stats.verified, 15);
        assert_eq!(prepared.manifest, verified.manifest().digest());
        prepared
            .files
            .get_mut(&manifest::hash(payload))
            .unwrap()
            .fingerprint(15, &manifest::hash(payload))
            .unwrap();
        prepared
            .files
            .get_mut(&manifest::hash(&[]))
            .unwrap()
            .fingerprint(0, &manifest::hash(&[]))
            .unwrap();
        empty_parts(&store);
        assert_eq!(cache_count(&store), 2);
        assert!(store.owner.dir.child("mods", false).is_err());
        drop(prepared);
        let memory = Memory::new(vec![]);
        let prepared = store
            .prepare(
                &verified,
                &memory,
                &Catalog::fixture(),
                &Cancel::new(),
                quick(),
            )
            .await
            .unwrap();
        assert_eq!(memory.calls(), 0);
        assert_eq!(prepared.stats.cached, 2);
        assert_eq!(prepared.stats.attempts, 0);
    });
}
#[test]
fn cache_reopen_rehash_and_corruption_preserve_existing_bytes() {
    runtime().block_on(async {
        let files = Fixture::new();
        let verified = release(&files, &publisher::document(1)).await;
        let mut store = Store::create(&files.root).unwrap();
        let receipt = store.owner.receipt.clone();
        drop(
            store
                .prepare(
                    &verified,
                    &Memory::new(vec![answer(b"test-only bytes")]),
                    &Catalog::fixture(),
                    &Cancel::new(),
                    quick(),
                )
                .await
                .unwrap(),
        );
        let name = format!("{}.blob", manifest::hash(b"test-only bytes"));
        let path = fixture_path(&store.cache);
        drop(store);
        let mut store = Store::reopen(&files.root, receipt.clone()).unwrap();
        let transport = Memory::new(vec![]);
        drop(
            store
                .prepare(
                    &verified,
                    &transport,
                    &Catalog::fixture(),
                    &Cancel::new(),
                    quick(),
                )
                .await
                .unwrap(),
        );
        assert_eq!(transport.calls(), 0);
        drop(store);
        std::fs::write(path.join(&name), b"modified bytes!").unwrap();
        let mut store = Store::reopen(&files.root, receipt).unwrap();
        error(
            store
                .prepare(
                    &verified,
                    &transport,
                    &Catalog::fixture(),
                    &Cancel::new(),
                    quick(),
                )
                .await,
            "DOWNLOAD_CACHE_INVALID",
        );
        assert_eq!(std::fs::read(path.join(name)).unwrap(), b"modified bytes!");
        assert_eq!(transport.calls(), 0);
    });
}
#[test]
fn wrong_hash_short_overlong_and_hostile_headers_never_promote() {
    runtime().block_on(async {
        let files = Fixture::new();
        let verified = release(&files, &publisher::document(1)).await;
        let mut cases = vec![
            (answer(b"short"), "DOWNLOAD_LENGTH"),
            (answer(b"test-only bytes!"), "DOWNLOAD_LENGTH"),
            (answer(b"wrong-only data"), "DOWNLOAD_HASH"),
        ];
        for (name, value) in [
            (header::CONTENT_ENCODING, "gzip"),
            (header::CONTENT_ENCODING, "identity, gzip"),
            (header::CONTENT_RANGE, "bytes 0-14/15"),
            (header::CONTENT_LENGTH, "14"),
        ] {
            let mut a = answer(b"test-only bytes");
            a.headers.insert(name.clone(), value.parse().unwrap());
            cases.push((
                a,
                if name == header::CONTENT_LENGTH {
                    "DOWNLOAD_LENGTH"
                } else {
                    "DOWNLOAD_HEADERS"
                },
            ));
        }
        let mut duplicate = answer(b"test-only bytes");
        duplicate
            .headers
            .append(header::CONTENT_LENGTH, "15".parse().unwrap());
        duplicate
            .headers
            .append(header::CONTENT_LENGTH, "15".parse().unwrap());
        cases.push((duplicate, "DOWNLOAD_HEADERS"));
        let mut partial = answer(b"test-only bytes");
        partial.status = 206;
        cases.push((partial, "DOWNLOAD_HTTP"));
        let mut huge = answer(&[]);
        huge.steps = vec![Step::Bytes(vec![0; CHUNK_LIMIT + 1])];
        cases.push((huge, "DOWNLOAD_CHUNK_LIMIT"));
        for (n, (a, expected)) in cases.into_iter().enumerate() {
            let parent = files.root.child(&format!("case-{n}"), true).unwrap();
            let mut store = Store::create(&parent).unwrap();
            let memory = Memory::new(vec![a]);
            error(
                store
                    .prepare(
                        &verified,
                        &memory,
                        &Catalog::fixture(),
                        &Cancel::new(),
                        quick(),
                    )
                    .await,
                expected,
            );
            assert_eq!(memory.calls(), 1);
            empty_parts(&store);
            assert_eq!(cache_count(&store), 0);
        }
    });
}
#[test]
fn redirect_policy_checks_every_hop_and_has_finite_budget() {
    runtime().block_on(async {
        let files = Fixture::new();
        let verified = release(&files, &publisher::document(1)).await;
        let mut redirect = answer(&[]);
        redirect.status = 302;
        redirect.headers.insert(
            header::LOCATION,
            "https://mirror.animeoncraft.invalid/artifacts/objects/fixture.bin"
                .parse()
                .unwrap(),
        );
        let mut store = Store::create(&files.root).unwrap();
        let memory = Memory::new(vec![redirect.clone(), answer(b"test-only bytes")]);
        drop(
            store
                .prepare(
                    &verified,
                    &memory,
                    &Catalog::fixture(),
                    &Cancel::new(),
                    quick(),
                )
                .await
                .unwrap(),
        );
        assert_eq!(memory.calls(), 2);
        let values = [
            "http://artifacts.animeoncraft.invalid/artifacts/a",
            "https://evil.invalid/artifacts/a",
            "https://user:pass@mirror.animeoncraft.invalid/artifacts/a",
            "/artifacts/%2e%2e/a",
            "/artifacts/../a",
            "/artifacts/a?q=x",
            "/artifacts/a#x",
            "/outside/a",
        ];
        for (n, location) in values.into_iter().enumerate() {
            let parent = files.root.child(&format!("deny-{n}"), true).unwrap();
            let mut store = Store::create(&parent).unwrap();
            let mut r = redirect.clone();
            r.headers
                .insert(header::LOCATION, location.parse().unwrap());
            let memory = Memory::new(vec![r]);
            error(
                store
                    .prepare(
                        &verified,
                        &memory,
                        &Catalog::fixture(),
                        &Cancel::new(),
                        quick(),
                    )
                    .await,
                "SOURCE_NOT_APPROVED",
            );
            assert_eq!(memory.calls(), 1);
            empty_parts(&store);
            assert_eq!(cache_count(&store), 0);
        }
        let parent = files.root.child("loop", true).unwrap();
        let mut store = Store::create(&parent).unwrap();
        let memory = Memory::new(vec![redirect; 4]);
        error(
            store
                .prepare(
                    &verified,
                    &memory,
                    &Catalog::fixture(),
                    &Cancel::new(),
                    quick(),
                )
                .await,
            "DOWNLOAD_REDIRECT_LIMIT",
        );
        assert_eq!(memory.calls(), 4);
        empty_parts(&store);
    });
}
#[test]
fn source_preflight_rejects_unconfigured_catalog_before_mutation() {
    runtime().block_on(async {
        let files = Fixture::new();
        let verified = release(&files, &publisher::document(1)).await;
        let mut store = Store::create(&files.root).unwrap();
        let memory = Memory::new(vec![]);
        let catalog = Catalog {
            bases: BTreeMap::new(),
            http_fixture: false,
        };
        error(
            store
                .prepare(&verified, &memory, &catalog, &Cancel::new(), quick())
                .await,
            "SOURCE_NOT_APPROVED",
        );
        assert_eq!(memory.calls(), 0);
        empty_parts(&store);
        for key in [
            "../a",
            "objects/a%20b",
            "objects/a?token=x",
            "https://evil.invalid/a",
            "objects\\a",
            "CON.txt",
        ] {
            assert!(Catalog::fixture()
                .resolve(&manifest::Source {
                    source_id: "fixture".into(),
                    object_key: key.into()
                })
                .is_err());
        }
    });
}
#[test]
fn cancellation_before_fetch_and_during_stream_deletes_only_own_part() {
    runtime().block_on(async {
        let files = Fixture::new();
        let verified = release(&files, &publisher::document(1)).await;
        let mut store = Store::create(&files.root).unwrap();
        let cancel = Cancel::new();
        cancel.cancel();
        let memory = Memory::new(vec![]);
        error(
            store
                .prepare(&verified, &memory, &Catalog::fixture(), &cancel, quick())
                .await,
            "CANCELLED",
        );
        assert_eq!(memory.calls(), 0);
        let cancel = Cancel::new();
        let mut a = answer(&[]);
        a.steps = vec![Step::Bytes(b"test".to_vec()), Step::Cancel(cancel.clone())];
        let memory = Memory::new(vec![a]);
        let mut untouched = store
            .parts
            .file("00000000000000000000000000000000.part", true)
            .unwrap();
        untouched
            .write_new(b"previous interrupted attempt")
            .unwrap();
        drop(untouched);
        error(
            store
                .prepare(&verified, &memory, &Catalog::fixture(), &cancel, quick())
                .await,
            "CANCELLED",
        );
        assert_eq!(cache_count(&store), 0);
        let entries = store
            .parts
            .entries(PARTS, Instant::now() + Duration::from_secs(1))
            .unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].name, "00000000000000000000000000000000.part");
        let mut previous = store.parts.file(&entries[0].name, false).unwrap();
        previous
            .fingerprint(28, &manifest::hash(b"previous interrupted attempt"))
            .unwrap();
        assert!(previous.delete_owned().is_err());
    });
}
#[test]
fn idle_total_deadline_and_network_retry_restart_from_zero() {
    runtime().block_on(async {
        let files = Fixture::new();
        let verified = release(&files, &publisher::document(1)).await;
        let mut broken = answer(&[]);
        broken.steps = vec![Step::Bytes(b"test".to_vec()), Step::Error];
        let mut store = Store::create(&files.root).unwrap();
        let memory = Memory::new(vec![broken, answer(b"test-only bytes")]);
        let prepared = store
            .prepare(
                &verified,
                &memory,
                &Catalog::fixture(),
                &Cancel::new(),
                quick(),
            )
            .await
            .unwrap();
        assert_eq!(prepared.stats.received, 19);
        assert_eq!(prepared.stats.attempts, 2);
        empty_parts(&store);
        drop(prepared);
        let mut slow = answer(&[]);
        slow.steps = vec![Step::Delay(
            Duration::from_millis(100),
            b"test-only bytes".to_vec(),
        )];
        for (name, limits, expected, calls) in [
            (
                "idle",
                Limits {
                    idle: Duration::from_millis(10),
                    total: Duration::from_secs(2),
                    backoff: Duration::ZERO,
                },
                "DOWNLOAD_IDLE",
                2,
            ),
            (
                "deadline",
                Limits {
                    idle: Duration::from_secs(2),
                    total: Duration::from_millis(10),
                    backoff: Duration::ZERO,
                },
                "DOWNLOAD_DEADLINE",
                1,
            ),
        ] {
            let parent = files.root.child(name, true).unwrap();
            let mut store = Store::create(&parent).unwrap();
            let memory = Memory::new(vec![slow.clone(), slow.clone()]);
            error(
                store
                    .prepare(
                        &verified,
                        &memory,
                        &Catalog::fixture(),
                        &Cancel::new(),
                        limits,
                    )
                    .await,
                expected,
            );
            assert_eq!(memory.calls(), calls);
            empty_parts(&store);
            assert_eq!(cache_count(&store), 0);
        }
    });
}

#[test]
fn empty_chunks_do_not_reset_idle_progress_deadline() {
    runtime().block_on(async {
        let files = Fixture::new();
        let verified = release(&files, &publisher::document(1)).await;
        let mut store = Store::create(&files.root).unwrap();
        let mut empty = answer(&[]);
        empty.steps = vec![Step::Delay(Duration::from_millis(3), vec![]); 20];
        let memory = Memory::new(vec![empty.clone(), empty]);
        error(
            store
                .prepare(
                    &verified,
                    &memory,
                    &Catalog::fixture(),
                    &Cancel::new(),
                    Limits {
                        idle: Duration::from_millis(15),
                        total: Duration::from_secs(2),
                        backoff: Duration::ZERO,
                    },
                )
                .await,
            "DOWNLOAD_IDLE",
        );
        assert_eq!(memory.calls(), 2);
        empty_parts(&store);
        assert_eq!(cache_count(&store), 0);
    });
}
#[test]
fn hard_link_during_stream_refuses_cleanup_and_preserves_fixture_link() {
    runtime().block_on(async {
        let files = Fixture::new();
        let verified = release(&files, &publisher::document(1)).await;
        let mut store = Store::create(&files.root).unwrap();
        let destination = fixture_path(&files.root).join("outside.bin");
        let mut a = answer(&[]);
        a.steps = vec![
            Step::Bytes(b"test".to_vec()),
            Step::Link(store.parts.clone(), destination.clone()),
        ];
        error(
            store
                .prepare(
                    &verified,
                    &Memory::new(vec![a]),
                    &Catalog::fixture(),
                    &Cancel::new(),
                    quick(),
                )
                .await,
            "DOWNLOAD_CLEANUP_REQUIRED",
        );
        assert_eq!(std::fs::read(&destination).unwrap(), b"test");
        assert_eq!(
            store
                .parts
                .entries(PARTS, Instant::now() + Duration::from_secs(1))
                .unwrap()
                .len(),
            1
        );
        assert_eq!(cache_count(&store), 0);
    });
}
#[test]
fn interrupted_readback_and_expired_promote_leave_no_cache_object() {
    let files = Fixture::new();
    let store = Store::create(&files.root).unwrap();
    let bytes = vec![7u8; 3 * BUFFER];
    let hash = manifest::hash(&bytes);
    let mut part = store
        .parts
        .file("00000000000000000000000000000001.part", true)
        .unwrap();
    part.write_new(&bytes).unwrap();
    let mut checks = 0;
    assert!(matches!(
        part.fingerprint_controlled(bytes.len() as u64, &hash, || {
            checks += 1;
            if checks == 3 {
                Err(Error::Interrupted)
            } else {
                Ok(())
            }
        }),
        Err(Error::Interrupted)
    ));
    assert!(part.verified.is_none());
    assert!(part
        .rename_empty(&store.cache, &format!("{hash}.blob"))
        .is_err());
    part.fingerprint(bytes.len() as u64, &hash).unwrap();
    error(
        controlled(
            &Cancel::new(),
            Instant::now() - Duration::from_secs(1),
            "DOWNLOAD_STORE_INVALID",
            |check| part.rename_empty_controlled(&store.cache, &format!("{hash}.blob"), check),
        ),
        "DOWNLOAD_DEADLINE",
    );
    assert!(part.verified.is_none());
    assert_eq!(cache_count(&store), 0);
    let cancel = Cancel::new();
    cancel.cancel();
    error(
        controlled(
            &cancel,
            Instant::now() + Duration::from_secs(1),
            "DOWNLOAD_STORE_INVALID",
            |check| part.fingerprint_controlled(bytes.len() as u64, &hash, check),
        ),
        "CANCELLED",
    );
    part.delete_owned().unwrap();
    empty_parts(&store);
}
#[test]
fn orphan_budget_and_unknown_entries_block_without_deleting_existing_files() {
    runtime().block_on(async {
        let files = Fixture::new();
        let verified = release(&files, &publisher::document(1)).await;
        for (name, count) in [("budget", PARTS), ("unknown", 1)] {
            let parent = files.root.child(name, true).unwrap();
            let mut store = Store::create(&parent).unwrap();
            for n in 0..count {
                let name = if count == 1 {
                    "foreign.bin".to_string()
                } else {
                    format!("{n:032x}.part")
                };
                let mut held = store.parts.file(&name, true).unwrap();
                held.write_new(b"preserve").unwrap();
            }
            let memory = Memory::new(vec![]);
            error(
                store
                    .prepare(
                        &verified,
                        &memory,
                        &Catalog::fixture(),
                        &Cancel::new(),
                        quick(),
                    )
                    .await,
                "DOWNLOAD_PARTS_BLOCKED",
            );
            assert_eq!(memory.calls(), 0);
            assert_eq!(
                store
                    .parts
                    .entries(PARTS, Instant::now() + Duration::from_secs(1))
                    .unwrap()
                    .len(),
                count
            );
            assert_eq!(cache_count(&store), 0);
        }
    });
}

// Own loopback socket, no production endpoints, OS trust/proxy or account/session mutations.
struct Server {
    url: Url,
    requests: Arc<Mutex<Vec<String>>>,
    stop: Arc<AtomicBool>,
    worker: Option<thread::JoinHandle<()>>,
}
impl Server {
    fn new(responses: Vec<Vec<u8>>) -> Self {
        let socket = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = socket.local_addr().unwrap().port();
        socket.set_nonblocking(true).unwrap();
        let requests = Arc::new(Mutex::new(Vec::new()));
        let stop = Arc::new(AtomicBool::new(false));
        let r = requests.clone();
        let s = stop.clone();
        let worker = thread::spawn(move || {
            let mut responses = VecDeque::from(responses);
            while !s.load(Ordering::SeqCst) {
                match socket.accept() {
                    Ok((mut stream, _)) => {
                        // Windows may inherit the listening socket's nonblocking mode.
                        // Never emit the fixture response before the complete request.
                        stream.set_nonblocking(false).unwrap();
                        stream
                            .set_read_timeout(Some(Duration::from_secs(2)))
                            .unwrap();
                        stream
                            .set_write_timeout(Some(Duration::from_secs(2)))
                            .unwrap();
                        let mut bytes = Vec::new();
                        let mut byte = [0u8; 1];
                        while bytes.len() < 8192 && stream.read_exact(&mut byte).is_ok() {
                            bytes.push(byte[0]);
                            if bytes.ends_with(b"\r\n\r\n") {
                                break;
                            }
                        }
                        if !bytes.ends_with(b"\r\n\r\n") {
                            continue;
                        }
                        r.lock().unwrap().push(String::from_utf8(bytes).unwrap());
                        if let Some(response) = responses.pop_front() {
                            let _ = stream.write_all(&response);
                        }
                    }
                    Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(2))
                    }
                    Err(_) => break,
                }
            }
        });
        Self {
            url: format!("http://127.0.0.1:{port}/artifacts/")
                .parse()
                .unwrap(),
            requests,
            stop,
            worker: Some(worker),
        }
    }
    fn catalog(&self) -> Catalog {
        let mirror = self.url.join("/mirror/").unwrap();
        Catalog {
            bases: BTreeMap::from([("fixture".into(), vec![self.url.clone(), mirror])]),
            http_fixture: true,
        }
    }
}
impl Drop for Server {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        self.worker.take().unwrap().join().unwrap();
    }
}
#[test]
fn actual_http_redirect_sends_identity_without_account_cookie_range_or_referer() {
    runtime().block_on(async {
        let files=Fixture::new(); let verified=release(&files,&publisher::document(1)).await;
        let server=Server::new(vec![b"HTTP/1.1 302 Found\r\nLocation: /mirror/objects/fixture.bin\r\nSet-Cookie: fixture=secret\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_vec(),b"HTTP/1.1 200 OK\r\nContent-Length: 15\r\nConnection: close\r\n\r\ntest-only bytes".to_vec()]);
        let mut store=Store::create(&files.root).unwrap(); let client=http::Http::fixture(true).unwrap();
        drop(store.prepare(&verified,&client,&server.catalog(),&Cancel::new(),quick()).await.unwrap());
        let requests=server.requests.lock().unwrap(); assert_eq!(requests.len(),2);
        for request in &*requests { let lower=request.to_ascii_lowercase(); assert!(lower.lines().any(|line| line.split_once(':').is_some_and(|(name,value)| name == "accept-encoding" && value.trim() == "identity"))); for name in ["authorization:","cookie:","range:","referer:","proxy-authorization:"] { assert!(!lower.contains(name)); } }
        assert!(requests[1].starts_with("GET /mirror/objects/fixture.bin HTTP/1.1")); empty_parts(&store);
    });
}
#[test]
fn loopback_fixture_waits_for_complete_delayed_request() {
    let server = Server::new(vec![
        b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".to_vec(),
    ]);
    let mut stream =
        std::net::TcpStream::connect(("127.0.0.1", server.url.port().unwrap())).unwrap();
    stream
        .set_read_timeout(Some(Duration::from_secs(2)))
        .unwrap();
    stream
        .write_all(b"GET /artifacts/test HTTP/1.1\r\nHost: localhost\r\n")
        .unwrap();
    thread::sleep(Duration::from_millis(100));
    assert!(server.requests.lock().unwrap().is_empty());
    stream.write_all(b"\r\n").unwrap();
    let mut bytes = Vec::new();
    stream.read_to_end(&mut bytes).unwrap();
    assert!(bytes.ends_with(b"\r\n\r\nok"));
    assert_eq!(server.requests.lock().unwrap().len(), 1);
}
#[test]
fn actual_http_disconnect_retries_fresh_and_chunked_overrun_is_rejected() {
    runtime().block_on(async {
        let files=Fixture::new(); let verified=release(&files,&publisher::document(1)).await;
        let server=Server::new(vec![b"HTTP/1.1 200 OK\r\nContent-Length: 15\r\nConnection: close\r\n\r\ntest".to_vec(),b"HTTP/1.1 200 OK\r\nContent-Length: 15\r\nConnection: close\r\n\r\ntest-only bytes".to_vec()]);
        let client=http::Http::fixture(true).unwrap(); let mut store=Store::create(&files.root).unwrap();
        let prepared=store.prepare(&verified,&client,&server.catalog(),&Cancel::new(),quick()).await.unwrap(); assert_eq!(prepared.stats.attempts,2); assert_eq!(server.requests.lock().unwrap().len(),2); empty_parts(&store); drop(prepared);
        let parent=files.root.child("overflow",true).unwrap(); let mut store=Store::create(&parent).unwrap();
        let server=Server::new(vec![b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n10\r\ntest-only bytes!\r\n0\r\n\r\n".to_vec()]);
        error(store.prepare(&verified,&client,&server.catalog(),&Cancel::new(),quick()).await,"DOWNLOAD_LENGTH"); empty_parts(&store); assert_eq!(cache_count(&store),0);
    });
}
#[test]
fn actual_https_only_client_refuses_http_before_socket_request() {
    runtime().block_on(async {
        let files = Fixture::new();
        let verified = release(&files, &publisher::document(1)).await;
        let server = Server::new(vec![]);
        let mut store = Store::create(&files.root).unwrap();
        error(
            store
                .prepare(
                    &verified,
                    &http::Http::fixture(false).unwrap(),
                    &server.catalog(),
                    &Cancel::new(),
                    quick(),
                )
                .await,
            "DOWNLOAD_NETWORK",
        );
        assert!(server.requests.lock().unwrap().is_empty());
        empty_parts(&store);
    });
}
