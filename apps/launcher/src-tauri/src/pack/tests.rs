use super::{manifest::*, strict_json, trust};
use async_trait::async_trait;
use aws_lc_rs::{rand::SystemRandom, signature::Ed25519KeyPair};
use serde_json::Value;
use std::{
    collections::{BTreeMap, BTreeSet, HashMap},
    fmt,
    num::NonZeroU64,
    path::PathBuf,
    sync::Arc,
};
use tough::{
    editor::{signed::SignedRole, RepositoryEditor},
    key_source::KeySource,
    schema::{KeyHolder, RoleKeys, RoleType, Root, Signed},
    sign::Sign,
    TargetName, Transport, TransportError, TransportErrorKind, TransportStream,
};
use zeroize::Zeroizing;
mod export;

pub(super) fn runtime() -> tokio::runtime::Runtime {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
}
pub(super) fn policy(sequence: u64) -> ReleasePolicy {
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
        launcher_version: semver::Version::new(0, 7, 3),
        protocol: 1,
        approved_sources: BTreeSet::from(["fixture".into()]),
    }
}
pub(super) fn document(sequence: u64) -> Manifest {
    Manifest {
        schema_version: 1,
        pack_id: "animeoncraft".into(),
        channel: "stable".into(),
        release_sequence: sequence,
        platform: Platform {
            os: "windows".into(),
            arch: "x86_64".into(),
        },
        minecraft_version: "test-only".into(),
        loader: Loader {
            id: "fabric".into(),
            version: "fixture-v1".into(),
        },
        java: Java {
            major: 21,
            distribution_ref: "fixture-jre".into(),
        },
        minimum_launcher_version: "0.7.3".into(),
        integration_protocol_major: 1,
        files: vec![File {
            path: "mods/fixture.jar".into(),
            sha256: hash(b"test-only bytes"),
            length: 15,
            side: Side::CLIENT,
            source_ref: Source {
                source_id: "fixture".into(),
                object_key: "objects/fixture.bin".into(),
            },
            managed: true,
        }],
    }
}
#[test]
fn manifest_rejects_ambiguous_json_and_hostile_windows_paths() {
    let valid = document(1);
    let bytes = serde_json::to_vec(&valid).unwrap();
    assert!(parse(&bytes, &policy(1)).is_ok());
    for path in [
        "../mods/x.jar",
        "mods/../x.jar",
        "mods//x.jar",
        "mods/CON.jar",
        "mods/lpt9.jar",
        "mods/NuL.ext.jar",
        "mods/a.jar.",
        "mods/.hidden.jar",
        "mods/a.jar:stream",
        "mods/%2e.jar",
        "C:/mods/x.jar",
        "//server/file",
        "mods\\x.jar",
        "world/level.dat",
        "saves/world/level.dat",
        "config/server.properties",
        "mods/run.exe",
        "runtime/java.exe",
        "assets/objects/aa/x",
    ] {
        let mut bad = valid.clone();
        bad.files[0].path = path.into();
        assert!(
            parse(&serde_json::to_vec(&bad).unwrap(), &policy(1)).is_err(),
            "Accepted {path}"
        );
    }
    assert!(strict_json::parse(br#"{"a":{"x":1,"x":2}}"#, 128).is_err());
    assert!(strict_json::parse(br#"{"a":1} {"b":2}"#, 128).is_err());
    let mut unknown = serde_json::to_value(&valid).unwrap();
    unknown["files"][0]["source_ref"]["url"] = Value::String("https://evil.invalid".into());
    assert!(parse(&serde_json::to_vec(&unknown).unwrap(), &policy(1)).is_err());
    assert!(paths(["mods/A.jar", "mods/a.jar"].into_iter()).is_err());
    assert!(paths(["mods/a.jar", "mods/a.jar/b.jar"].into_iter()).is_err());
    let mut overflow = valid.clone();
    overflow.files[0].length = u64::MAX;
    assert!(parse(&serde_json::to_vec(&overflow).unwrap(), &policy(1)).is_err());
    let mut zero = valid.clone();
    zero.files[0].length = 0;
    assert!(parse(&serde_json::to_vec(&zero).unwrap(), &policy(1)).is_err());
    zero.files[0].sha256 = hash(&[]);
    assert!(parse(&serde_json::to_vec(&zero).unwrap(), &policy(1)).is_ok());
    let mut server = valid.clone();
    let mut file = server.files[0].clone();
    file.path = "world/secret.dat".into();
    file.side = Side::SERVER;
    server.files.push(file);
    assert!(parse(&serde_json::to_vec(&server).unwrap(), &policy(1)).is_err());
    let mut newer = valid.clone();
    newer.minimum_launcher_version = "9.0.0".into();
    assert!(matches!(
        parse(&serde_json::to_vec(&newer).unwrap(), &policy(1)),
        Err("LAUNCHER_UPDATE_REQUIRED")
    ));
    assert!(matches!(parse(&bytes, &policy(2)), Err("RELEASE_MISMATCH")));
}
#[test]
fn planner_preserves_user_data_blocks_foreign_and_requires_verified_old_bytes() {
    let old = document(1);
    let old_checked = parse(&serde_json::to_vec(&old).unwrap(), &policy(1)).unwrap();
    let mut new = document(2);
    new.files[0].sha256 = hash(b"new");
    new.files[0].length = 3;
    let new_checked = parse(&serde_json::to_vec(&new).unwrap(), &policy(2)).unwrap();
    let mut inventory = BTreeMap::from([
        (
            "mods/fixture.jar".into(),
            Fingerprint {
                sha256: old.files[0].sha256.clone(),
                length: 15,
            },
        ),
        (
            "saves/MyWorld/level.dat".into(),
            Fingerprint {
                sha256: hash(b"world"),
                length: 5,
            },
        ),
    ]);
    let result = plan(
        &new_checked,
        Some(&old_checked),
        &inventory,
        &BTreeSet::new(),
    )
    .unwrap();
    assert_eq!(
        result.operations,
        vec![Operation::Replace("mods/fixture.jar".into())]
    );
    assert_eq!(result.backup_bytes, 15);
    assert_eq!(result.uncached_bytes, 3);
    assert!(result.required_space >= 64 * 1024 * 1024 + 18);
    assert_eq!(inventory["saves/MyWorld/level.dat"].length, 5);
    inventory.get_mut("mods/fixture.jar").unwrap().sha256 = hash(b"modified");
    assert!(matches!(
        plan(
            &new_checked,
            Some(&old_checked),
            &inventory,
            &BTreeSet::new()
        ),
        Err("MODIFIED_MANAGED_FILE")
    ));
    inventory.remove("mods/fixture.jar");
    inventory.insert(
        "mods/unknown.jar".into(),
        Fingerprint {
            sha256: hash(b"foreign"),
            length: 7,
        },
    );
    assert!(matches!(
        plan(
            &new_checked,
            Some(&old_checked),
            &inventory,
            &BTreeSet::new()
        ),
        Err("FOREIGN_MANAGED_FILE")
    ));
    inventory.remove("mods/unknown.jar");
    inventory.insert(
        "mods/run.exe".into(),
        Fingerprint {
            sha256: hash(b"foreign"),
            length: 7,
        },
    );
    assert!(plan(&new_checked, None, &inventory, &BTreeSet::new()).is_err());
}

struct EphemeralKey(Zeroizing<Vec<u8>>);
impl fmt::Debug for EphemeralKey {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("EphemeralKey([REDACTED])")
    }
}
#[async_trait]
impl KeySource for EphemeralKey {
    async fn as_sign(&self) -> Result<Box<dyn Sign>, Box<dyn std::error::Error + Send + Sync>> {
        Ok(Box::new(Ed25519KeyPair::from_pkcs8(&self.0).unwrap()))
    }
    async fn write(
        &self,
        _: &str,
        _: &str,
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        Err("read-only test key".into())
    }
}
pub(super) fn keys() -> Vec<Box<dyn KeySource>> {
    (0..6)
        .map(|_| {
            Box::new(EphemeralKey(Zeroizing::new(
                Ed25519KeyPair::generate_pkcs8(&SystemRandom::new())
                    .unwrap()
                    .as_ref()
                    .to_vec(),
            ))) as Box<dyn KeySource>
        })
        .collect()
}
fn future(days: i64) -> jiff::Timestamp {
    (chrono::Utc::now() + chrono::Duration::days(days))
        .to_rfc3339()
        .parse()
        .unwrap()
}
pub(super) async fn root(signers: &[Box<dyn KeySource>], version: u64) -> Root {
    let mut keys = HashMap::new();
    let mut ids = Vec::new();
    for source in signers {
        let key = source.as_sign().await.unwrap().tuf_key();
        let id = key.key_id().unwrap();
        ids.push(id.clone());
        keys.insert(id.clone(), key);
    }
    let mut roles = HashMap::new();
    roles.insert(
        RoleType::Root,
        RoleKeys {
            keyids: ids[..3].to_vec(),
            threshold: NonZeroU64::new(2).unwrap(),
            _extra: HashMap::new(),
        },
    );
    for (i, role) in [RoleType::Targets, RoleType::Snapshot, RoleType::Timestamp]
        .into_iter()
        .enumerate()
    {
        roles.insert(
            role,
            RoleKeys {
                keyids: vec![ids[i + 3].clone()],
                threshold: NonZeroU64::new(1).unwrap(),
                _extra: HashMap::new(),
            },
        );
    }
    Root {
        spec_version: "1.0.0".into(),
        consistent_snapshot: true,
        version: NonZeroU64::new(version).unwrap(),
        expires: future(365),
        keys,
        roles,
        _extra: HashMap::new(),
    }
}
#[derive(Clone, Debug)]
pub(super) struct Memory {
    pub(super) files: Arc<BTreeMap<String, Vec<u8>>>,
    pub(super) crash: bool,
}
#[async_trait]
impl Transport for Memory {
    async fn fetch(&self, url: reqwest::Url) -> Result<TransportStream, TransportError> {
        if self.crash {
            std::process::exit(37);
        }
        if url.scheme() != "https"
            || url.host_str() != Some("fixtures.animeoncraft.invalid")
            || !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
        {
            return Err(TransportError::new(
                TransportErrorKind::UnsupportedUrlScheme,
                "fixture origin",
            ));
        }
        let bytes = self.files.get(url.path()).ok_or_else(|| {
            TransportError::new(TransportErrorKind::FileNotFound, "missing fixture object")
        })?;
        if bytes.len() > MAX_MANIFEST {
            return Err(TransportError::new(
                TransportErrorKind::Other,
                "fixture size limit",
            ));
        }
        if url.path().starts_with("/metadata/") {
            strict_json::parse(bytes, MAX_MANIFEST).map_err(|_| {
                TransportError::new(TransportErrorKind::Other, "invalid fixture metadata")
            })?;
        }
        Ok(Box::pin(futures::stream::iter(vec![Ok(
            bytes::Bytes::from(bytes.clone()),
        )])))
    }
}
pub(super) struct Fixture {
    directory: tempfile::TempDir,
    pub(super) keys: Vec<Box<dyn KeySource>>,
    pub(super) root: Root,
    pub(super) bootstrap: Vec<u8>,
}
fn fixture_parent() -> PathBuf {
    let workspace = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../..")
        .canonicalize()
        .unwrap();
    let parent = workspace.join(".tools");
    std::fs::create_dir_all(&parent).unwrap();
    assert_eq!(
        parent.canonicalize().unwrap(),
        parent,
        "fixture parent must not be redirected outside the workspace"
    );
    parent
}
impl Fixture {
    pub(super) async fn new() -> Self {
        let parent = fixture_parent();
        let directory = tempfile::Builder::new()
            .prefix("pack-policy-")
            .tempdir_in(&parent)
            .unwrap();
        assert!(directory.path().starts_with(parent));
        let keys = keys();
        let root = root(&keys, 1).await;
        let bootstrap = SignedRole::new(
            root.clone(),
            &KeyHolder::Root(root.clone()),
            &keys,
            &SystemRandom::new(),
        )
        .await
        .unwrap()
        .buffer()
        .clone();
        std::fs::write(directory.path().join("bootstrap.json"), &bootstrap).unwrap();
        std::fs::create_dir(directory.path().join("state")).unwrap();
        Self {
            directory,
            keys,
            root,
            bootstrap,
        }
    }
    fn store(&self) -> trust::TrustStore {
        trust::TrustStore::fixture(self.directory.path().join("state"), "fixture-v1")
    }
    pub(super) async fn published(&self, sequence: u64, version: u64, expired: bool) -> Memory {
        self.published_manifest(&document(sequence), version, expired)
            .await
    }
    pub(super) async fn published_manifest(
        &self,
        document: &Manifest,
        version: u64,
        expired: bool,
    ) -> Memory {
        let sequence = document.release_sequence;
        let out = self.directory.path().join(format!(
            "published-{sequence}-{version}-{}",
            uuid::Uuid::new_v4().simple()
        ));
        std::fs::create_dir(&out).unwrap();
        let target = serde_json::to_vec(document).unwrap();
        let path = out.join("manifest.json");
        std::fs::write(&path, &target).unwrap();
        let mut editor = RepositoryEditor::new(self.directory.path().join("bootstrap.json"))
            .await
            .unwrap();
        editor
            .targets_version(NonZeroU64::new(version).unwrap())
            .unwrap()
            .targets_expires(future(90))
            .unwrap()
            .snapshot_version(NonZeroU64::new(version).unwrap())
            .snapshot_expires(future(7))
            .timestamp_version(NonZeroU64::new(version).unwrap())
            .timestamp_expires(future(if expired { -1 } else { 1 }));
        let name = format!("packs/{}/{sequence}/manifest.json", document.channel);
        editor
            .add_target(
                TargetName::new(&name).unwrap(),
                tough::schema::Target::from_path(&path).await.unwrap(),
            )
            .unwrap();
        editor
            .sign(&self.keys)
            .await
            .unwrap()
            .write(out.join("metadata"))
            .await
            .unwrap();
        let mut files = BTreeMap::new();
        for file in std::fs::read_dir(out.join("metadata")).unwrap() {
            let file = file.unwrap();
            files.insert(
                format!("/metadata/{}", file.file_name().to_str().unwrap()),
                std::fs::read(file.path()).unwrap(),
            );
        }

        files.insert(format!("/targets/{}.{}", hash(&target), name), target);
        Memory {
            files: Arc::new(files),
            crash: false,
        }
    }
}
async fn verify(
    fixture: &Fixture,
    memory: Memory,
    sequence: u64,
) -> Result<trust::VerifiedRelease, &'static str> {
    trust::verify(
        &fixture.bootstrap,
        "https://fixtures.animeoncraft.invalid/metadata/"
            .parse()
            .unwrap(),
        "https://fixtures.animeoncraft.invalid/targets/"
            .parse()
            .unwrap(),
        memory,
        &fixture.store(),
        &policy(sequence),
    )
    .await
}
#[test]
fn tuf_signed_manifest_hash_expiry_threshold_and_rollback() {
    runtime().block_on(async {
        let fixture = Fixture::new().await;
        let first = fixture.published(1, 1, false).await;
        assert_eq!(
            verify(&fixture, first.clone(), 1)
                .await
                .unwrap()
                .manifest()
                .manifest()
                .release_sequence,
            1
        );
        let mut tampered = first.clone();
        let mut files = (*tampered.files).clone();
        let key = files
            .keys()
            .find(|k| k.starts_with("/targets/"))
            .unwrap()
            .clone();
        files.get_mut(&key).unwrap()[0] = b'!';
        tampered.files = Arc::new(files);
        assert!(verify(&fixture, tampered, 1).await.is_err());
        assert!(
            verify(&fixture, first, 1).await.is_ok(),
            "target failure must retain usable trusted metadata"
        );
        let second = fixture.published(2, 2, false).await;
        assert!(verify(&fixture, second, 2).await.is_ok());
        let old = fixture.published(1, 1, false).await;
        assert!(matches!(
            verify(&fixture, old, 1).await,
            Err("TUF_REJECTED")
        ));
        assert!(
            matches!(
                verify(&fixture, fixture.published(2, 2, false).await, 2).await,
                Err("TRUST_STATE_INVALID")
            ),
            "failed metadata update must not silently reset trust"
        );
        let expired = Fixture::new().await;
        assert!(verify(&expired, expired.published(1, 1, true).await, 1)
            .await
            .is_err());
        let threshold = Fixture::new().await;
        let mut bad: Signed<Root> = serde_json::from_slice(&threshold.bootstrap).unwrap();
        bad.signatures.truncate(1);
        let bytes = serde_json::to_vec(&bad).unwrap();
        assert!(trust::verify(
            &bytes,
            "https://fixtures.animeoncraft.invalid/metadata/"
                .parse()
                .unwrap(),
            "https://fixtures.animeoncraft.invalid/targets/"
                .parse()
                .unwrap(),
            threshold.published(1, 1, false).await,
            &threshold.store(),
            &policy(1)
        )
        .await
        .is_err());
    })
}
#[test]
fn corruption_missing_state_clock_and_release_sequence_fail_closed() {
    runtime().block_on(async {
        let fixture = Fixture::new().await;
        let latest = fixture.published(2, 1, false).await;
        verify(&fixture, latest, 2).await.unwrap();
        let rollback = fixture.published(1, 2, false).await;
        assert!(
            matches!(verify(&fixture, rollback, 1).await, Err("RELEASE_ROLLBACK")),
            "new metadata cannot authorize old release sequence"
        );
        std::fs::write(
            fixture.store().directory().join("latest_known_time.json"),
            b"{broken}",
        )
        .unwrap();
        assert!(matches!(
            verify(&fixture, fixture.published(2, 2, false).await, 2).await,
            Err("TRUST_STATE_INVALID")
        ));
        let missing = Fixture::new().await;
        let valid = missing.published(1, 1, false).await;
        verify(&missing, valid.clone(), 1).await.unwrap();
        std::fs::remove_file(missing.store().directory().join("targets.json")).unwrap();
        assert!(matches!(
            verify(&missing, valid, 1).await,
            Err("TRUST_STATE_INVALID")
        ));
        let clock = Fixture::new().await;
        let valid = clock.published(1, 1, false).await;
        verify(&clock, valid.clone(), 1).await.unwrap();
        std::fs::write(
            clock.store().directory().join("latest_known_time.json"),
            serde_json::to_vec(&(chrono::Utc::now() + chrono::Duration::minutes(5)).to_rfc3339())
                .unwrap(),
        )
        .unwrap();
        assert!(matches!(verify(&clock, valid, 1).await, Err("CLOCK_ERROR")));
    })
}

#[test]
fn rotated_root_is_persisted_and_thresholds_cannot_be_bypassed() {
    runtime().block_on(async {
        let fixture = Fixture::new().await;
        let replacement = keys();
        let next = root(&replacement, 2).await;
        let new = SignedRole::new(
            next.clone(),
            &KeyHolder::Root(next.clone()),
            &replacement,
            &SystemRandom::new(),
        )
        .await
        .unwrap();
        let old = SignedRole::new(
            next.clone(),
            &KeyHolder::Root(fixture.root.clone()),
            &fixture.keys,
            &SystemRandom::new(),
        )
        .await
        .unwrap();
        let combined = new
            .clone()
            .add_old_signatures(old.signed().signatures.clone())
            .unwrap();
        let mut memory = fixture.published(1, 1, false).await;
        let mut files = (*memory.files).clone();
        // Only root keys change; the existing metadata roles retain their original keys.
        let mut next = fixture.root.clone();
        next.version = NonZeroU64::new(2).unwrap();
        next.keys
            .retain(|id, _| !fixture.root.roles[&RoleType::Root].keyids.contains(id));
        next.roles.insert(
            RoleType::Root,
            root(&replacement, 2).await.roles[&RoleType::Root].clone(),
        );
        for (id, key) in root(&replacement, 2).await.keys {
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
            next.clone(),
            &KeyHolder::Root(fixture.root.clone()),
            &fixture.keys,
            &SystemRandom::new(),
        )
        .await
        .unwrap();
        let signed = new
            .clone()
            .add_old_signatures(old.signed().signatures.clone())
            .unwrap();
        files.insert("/metadata/2.root.json".into(), signed.buffer().clone());
        memory.files = Arc::new(files);
        verify(&fixture, memory.clone(), 1).await.unwrap();
        let saved: Signed<Root> = serde_json::from_slice(
            &std::fs::read(fixture.store().directory().join("root.json")).unwrap(),
        )
        .unwrap();
        assert_eq!(saved.signed.version.get(), 2);
        verify(&fixture, memory, 1).await.unwrap();
        let rejected = Fixture::new().await;
        let mut bad = rejected.published(1, 1, false).await;
        let mut files = (*bad.files).clone();
        files.insert("/metadata/2.root.json".into(), combined.buffer().clone());
        bad.files = Arc::new(files);
        assert!(
            verify(&rejected, bad, 1).await.is_err(),
            "unrelated old signatures must not establish a rotation chain"
        );
        let duplicate = Fixture::new().await;
        let mut raw: Signed<Root> = serde_json::from_slice(&duplicate.bootstrap).unwrap();
        raw.signatures = vec![raw.signatures[0].clone(), raw.signatures[0].clone()];
        let bytes = serde_json::to_vec(&raw).unwrap();
        assert!(trust::verify(
            &bytes,
            "https://fixtures.animeoncraft.invalid/metadata/"
                .parse()
                .unwrap(),
            "https://fixtures.animeoncraft.invalid/targets/"
                .parse()
                .unwrap(),
            duplicate.published(1, 1, false).await,
            &duplicate.store(),
            &policy(1)
        )
        .await
        .is_err());
    })
}

#[test]
fn restart_and_process_crash_keep_trust_state_fail_closed() {
    runtime().block_on(async {
        let fixture = Fixture::new().await;
        let memory = fixture.published(1, 1, false).await;
        verify(&fixture, memory.clone(), 1).await.unwrap();
        std::fs::write(
            fixture.directory.path().join("transport.json"),
            serde_json::to_vec(&*memory.files).unwrap(),
        )
        .unwrap();
        let executable = std::env::current_exe().unwrap();
        for (mode, expected) in [("read", 0), ("crash", 37)] {
            let status = std::process::Command::new(&executable)
                .args(["--exact", "pack::tests::fixture_child_reader"])
                .env("AOC_PACK_FIXTURE_DIRECTORY", fixture.directory.path())
                .env("AOC_PACK_FIXTURE_MODE", mode)
                .status()
                .unwrap();
            assert_eq!(status.code(), Some(expected));
        }
        assert!(
            matches!(
                verify(&fixture, memory, 1).await,
                Err("TRUST_STATE_INVALID")
            ),
            "interrupted update must not reset to bootstrap"
        );
    })
}
#[test]
fn fixture_child_reader() {
    let Ok(directory) = std::env::var("AOC_PACK_FIXTURE_DIRECTORY") else {
        return;
    };
    let parent = fixture_parent();
    let directory = PathBuf::from(directory).canonicalize().unwrap();
    assert!(directory.starts_with(&parent));
    assert!(directory
        .file_name()
        .unwrap()
        .to_str()
        .unwrap()
        .starts_with("pack-policy-"));
    let bootstrap = std::fs::read(directory.join("bootstrap.json")).unwrap();
    let files: BTreeMap<String, Vec<u8>> =
        serde_json::from_slice(&std::fs::read(directory.join("transport.json")).unwrap()).unwrap();
    let store = trust::TrustStore::fixture(directory.join("state"), "fixture-v1");
    let memory = Memory {
        files: Arc::new(files),
        crash: std::env::var("AOC_PACK_FIXTURE_MODE").unwrap() == "crash",
    };
    runtime()
        .block_on(trust::verify(
            &bootstrap,
            "https://fixtures.animeoncraft.invalid/metadata/"
                .parse()
                .unwrap(),
            "https://fixtures.animeoncraft.invalid/targets/"
                .parse()
                .unwrap(),
            memory,
            &store,
            &policy(1),
        ))
        .unwrap();
}

#[test]
fn broken_root_update_does_not_fall_back_to_old_root() {
    runtime().block_on(async {
        let fixture = Fixture::new().await;
        let mut memory = fixture.published(1, 1, false).await;
        let mut files = (*memory.files).clone();
        files.insert("/metadata/2.root.json".into(), b"{broken".to_vec());
        memory.files = Arc::new(files);
        assert!(matches!(
            verify(&fixture, memory, 1).await,
            Err("TUF_REJECTED")
        ));
    })
}

#[test]
fn transport_denies_arbitrary_origins_and_duplicate_metadata() {
    runtime().block_on(async {
        let memory = Memory {
            files: Arc::new(BTreeMap::from([(
                "/metadata/timestamp.json".into(),
                br#"{"signed":{},"signed":{}}"#.to_vec(),
            )])),
            crash: false,
        };
        let guarded = super::transport::Guarded::new(
            memory,
            "https://fixtures.animeoncraft.invalid/metadata/"
                .parse()
                .unwrap(),
            "https://fixtures.animeoncraft.invalid/targets/"
                .parse()
                .unwrap(),
        );
        for url in [
            "file:///C:/secret",
            "http://fixtures.animeoncraft.invalid/metadata/root.json",
            "https://evil.invalid/metadata/root.json",
            "https://fixtures.animeoncraft.invalid/elsewhere/root.json",
            "https://user@fixtures.animeoncraft.invalid/metadata/root.json",
            "https://fixtures.animeoncraft.invalid/metadata/root.json?token=x",
            "https://fixtures.animeoncraft.invalid/metadata/timestamp.json",
        ] {
            assert!(guarded.fetch(url.parse().unwrap()).await.is_err());
        }
    })
}
