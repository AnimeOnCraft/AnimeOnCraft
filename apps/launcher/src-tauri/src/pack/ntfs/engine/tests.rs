use super::super::tests::Fixture;
use super::*;
use crate::pack::tests::{self as publisher, runtime};
use std::{
    path::{Path, PathBuf},
    process::Command,
    sync::{mpsc, Mutex},
};
mod history;
pub(super) fn path(files: &Fixture) -> PathBuf {
    files.path().join(NAMESPACE)
}
pub(super) fn game(files: &Fixture) -> PathBuf {
    path(files).join("install/game-instance")
}
pub(super) fn open(files: &Fixture, bootstrap: &[u8]) -> Engine {
    Engine::open(&files.root, bootstrap, &mut |_| {}).unwrap()
}
pub(super) fn tree(root: &Path) -> BTreeMap<String, Vec<u8>> {
    fn walk(base: &Path, at: &Path, result: &mut BTreeMap<String, Vec<u8>>) {
        for item in std::fs::read_dir(at).unwrap() {
            let item = item.unwrap();
            let path = item.path();
            if item.file_type().unwrap().is_dir() {
                walk(base, &path, result);
            } else {
                result.insert(
                    path.strip_prefix(base)
                        .unwrap()
                        .to_string_lossy()
                        .into_owned(),
                    std::fs::read(path).unwrap(),
                );
            }
        }
    }
    let mut result = BTreeMap::new();
    walk(root, root, &mut result);
    result
}
pub(super) fn doc(sequence: u64, bytes: &[u8]) -> manifest::Manifest {
    let mut doc = publisher::document(sequence);
    doc.files[0].length = bytes.len() as u64;
    doc.files[0].sha256 = manifest::hash(bytes);
    doc
}
pub(super) fn objects(bytes: &[u8]) -> BTreeMap<String, Vec<u8>> {
    BTreeMap::from([("objects/fixture.bin".into(), bytes.to_vec())])
}
pub(super) async fn refresh(
    engine: &mut Engine,
    p: &publisher::Fixture,
    sequence: u64,
    bytes: &[u8],
) -> VerifiedRelease {
    engine
        .refresh(
            &p.bootstrap,
            p.published_manifest(&doc(sequence, bytes), sequence, false)
                .await,
            &publisher::policy(sequence),
        )
        .await
        .unwrap()
}
pub(super) async fn install(
    engine: &mut Engine,
    p: &publisher::Fixture,
    new: &VerifiedRelease,
    old: Option<&VerifiedRelease>,
    mode: Mode,
    bytes: &[u8],
) {
    let cancel = Cancel::new();
    let approval = engine
        .preview(&p.bootstrap, new, old, mode, &cancel)
        .unwrap();
    assert!(matches!(
        engine
            .confirm(
                &p.bootstrap,
                approval,
                new,
                old,
                objects(bytes),
                &cancel,
                &mut |_| {}
            )
            .await
            .unwrap(),
        Outcome::Committed { .. }
    ));
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Input {
    bootstrap: Vec<u8>,
    transport: BTreeMap<String, Vec<u8>>,
    payload: Vec<u8>,
}
pub(super) fn child(files: &Fixture, phase: &str) -> std::process::Output {
    Command::new(std::env::current_exe().unwrap())
        .args([
            "--exact",
            "pack::ntfs::engine::tests::engine_child",
            "--nocapture",
        ])
        .env("AOC_ENGINE_CHILD", files.path())
        .env("AOC_ENGINE_PHASE", phase)
        .output()
        .unwrap()
}
pub(super) async fn input(files: &Fixture, p: &publisher::Fixture) {
    let payload = b"engine payload";
    let transport = p.published_manifest(&doc(1, payload), 1, false).await;
    std::fs::write(
        files.path().join("public-engine-input.json"),
        serde_json::to_vec(&Input {
            bootstrap: p.bootstrap.clone(),
            transport: (*transport.files).clone(),
            payload: payload.to_vec(),
        })
        .unwrap(),
    )
    .unwrap();
}
#[test]
fn engine_child() {
    let Some(parent) = std::env::var_os("AOC_ENGINE_CHILD") else {
        return;
    };
    let parent = PathBuf::from(parent).canonicalize().unwrap();
    assert_eq!(
        parent.parent().unwrap(),
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../..")
            .canonicalize()
            .unwrap()
            .join(".tools")
            .canonicalize()
            .unwrap()
    );
    assert!(parent
        .file_name()
        .unwrap()
        .to_str()
        .unwrap()
        .starts_with("ntfs-fixture-"));
    let bytes = std::fs::read(parent.join("public-engine-input.json")).unwrap();
    let input: Input = bridge::decode(&bytes, 1024 * 1024).unwrap();
    let phase = std::env::var("AOC_ENGINE_PHASE").unwrap();
    if phase == "local-probe" {
        let status = crate::pack::local::LocalInstance::open(&parent)
            .snapshot()
            .status;
        std::process::exit(match status {
            crate::pack::local::Status::Ready => 0,
            crate::pack::local::Status::Busy => 11,
            _ => 12,
        });
    }
    let root = Dir::existing(&parent).unwrap();
    let profile = if phase.starts_with("development-") {
        Profile::Development
    } else {
        Profile::Test
    };
    let crash = phase.strip_prefix("development-create:").unwrap_or(&phase);
    let opened = Engine::open_profile(&root, &input.bootstrap, profile, &mut |at| {
        if at == crash {
            std::process::exit(37);
        }
    });
    let mut engine = match opened {
        Ok(engine) => engine,
        Err("ENGINE_BUSY") => std::process::exit(11),
        Err(_) => std::process::exit(12),
    };
    if let Some(at) = phase.strip_prefix("install:") {
        runtime().block_on(async {
            let new = engine
                .refresh(
                    &input.bootstrap,
                    publisher::Memory {
                        files: Arc::new(input.transport),
                        crash: false,
                    },
                    &publisher::policy(1),
                )
                .await
                .unwrap();
            let cancel = Cancel::new();
            let approval = engine
                .preview(&input.bootstrap, &new, None, Mode::Update, &cancel)
                .unwrap();
            engine
                .confirm(
                    &input.bootstrap,
                    approval,
                    &new,
                    None,
                    objects(&input.payload),
                    &cancel,
                    &mut |phase| {
                        if phase == at
                            || (at == "stage-created" && phase.starts_with("stage-created:"))
                        {
                            std::process::exit(37);
                        }
                    },
                )
                .await
                .unwrap();
            panic!("install fault hook not reached");
        });
    }
}

#[test]
fn mapped_signed_download_install_reopen_update_and_repair_preserve_worlds() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut engine = open(&files, &p.bootstrap);
        let binding = std::fs::read(path(&files).join("binding.json")).unwrap();
        let ready = std::fs::read(path(&files).join("ready.json")).unwrap();
        std::fs::create_dir(game(&files).join("saves")).unwrap();
        std::fs::write(game(&files).join("saves/world.dat"), b"user world").unwrap();
        let old = refresh(&mut engine, &p, 1, b"old bytes").await;
        install(&mut engine, &p, &old, None, Mode::Update, b"old bytes").await;
        drop(engine);
        let before = tree(&path(&files));
        let mut engine = open(&files, &p.bootstrap);
        assert_eq!(tree(&path(&files)), before);
        assert!(matches!(
            engine.startup(&p.bootstrap, &old, Some(&old)).unwrap(),
            Startup::Ready
        ));
        let new = refresh(&mut engine, &p, 2, b"new bytes").await;
        install(
            &mut engine,
            &p,
            &new,
            Some(&old),
            Mode::Update,
            b"new bytes",
        )
        .await;
        assert_eq!(
            std::fs::read(game(&files).join("mods/fixture.jar")).unwrap(),
            b"new bytes"
        );
        drop(engine);
        std::fs::write(game(&files).join("mods/fixture.jar"), b"modified bytes").unwrap();
        let mut engine = open(&files, &p.bootstrap);
        install(
            &mut engine,
            &p,
            &new,
            Some(&new),
            Mode::Repair,
            b"new bytes",
        )
        .await;
        assert_eq!(
            std::fs::read(game(&files).join("mods/fixture.jar")).unwrap(),
            b"new bytes"
        );
        assert_eq!(
            std::fs::read(
                path(&files).join("install/install-control/transactions/tx-00003/backup/00000.old")
            )
            .unwrap(),
            b"modified bytes"
        );
        assert_eq!(
            std::fs::read(game(&files).join("saves/world.dat")).unwrap(),
            b"user world"
        );
        assert_eq!(
            std::fs::read(path(&files).join("binding.json")).unwrap(),
            binding
        );
        assert_eq!(
            std::fs::read(path(&files).join("ready.json")).unwrap(),
            ready
        );
    });
}

#[test]
fn creation_exit_boundaries_preserve_partial_and_reopen_complete_layout() {
    runtime().block_on(async {
        let p = publisher::Fixture::new().await;
        for phase in [
            "namespace-created",
            "lease-created",
            "trust-created",
            "install-created",
            "downloads-created",
            "binding-created",
            "ready-created",
        ] {
            let files = Fixture::new();
            input(&files, &p).await;
            assert_eq!(child(&files, phase).status.code(), Some(37), "{phase}");
            let before = tree(&path(&files));
            let opened = Engine::open(&files.root, &p.bootstrap, &mut |_| {});
            assert_eq!(opened.is_ok(), phase == "ready-created", "{phase}");
            drop(opened);
            assert_eq!(tree(&path(&files)), before, "{phase}");
        }
    });
}

#[test]
fn process_exit_during_real_install_reopens_and_recovers_under_same_lease() {
    runtime().block_on(async {
        let p = publisher::Fixture::new().await;
        for phase in [
            "stage-created",
            "prepared",
            "after-install:00000",
            "index-ready",
        ] {
            let files = Fixture::new();
            input(&files, &p).await;
            drop(open(&files, &p.bootstrap));
            std::fs::create_dir(game(&files).join("saves")).unwrap();
            std::fs::write(game(&files).join("saves/world.dat"), b"crash world").unwrap();
            assert_eq!(
                child(&files, &format!("install:{phase}")).status.code(),
                Some(37),
                "{phase}"
            );
            let before = tree(&path(&files));
            let mut engine = open(&files, &p.bootstrap);
            assert_eq!(tree(&path(&files)), before);
            let new = engine
                .refresh(
                    &p.bootstrap,
                    p.published_manifest(&doc(1, b"engine payload"), 2, false)
                        .await,
                    &publisher::policy(1),
                )
                .await
                .unwrap();
            assert!(matches!(
                engine.startup(&p.bootstrap, &new, None).unwrap(),
                Startup::RecoveryRequired
            ));
            assert!(matches!(
                engine
                    .resume(
                        &p.bootstrap,
                        &new,
                        None,
                        objects(b"engine payload"),
                        &Cancel::new(),
                        &mut |_| {}
                    )
                    .await
                    .unwrap(),
                Outcome::Committed { .. }
            ));
            assert!(matches!(
                engine.startup(&p.bootstrap, &new, Some(&new)).unwrap(),
                Startup::Ready
            ));
            assert_eq!(
                std::fs::read(game(&files).join("mods/fixture.jar")).unwrap(),
                b"engine payload"
            );
            assert_eq!(
                std::fs::read(game(&files).join("saves/world.dat")).unwrap(),
                b"crash world"
            );
        }
    });
}

#[test]
fn worker_owner_prevents_second_process_until_final_drop() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        input(&files, &p).await;
        let owner = Arc::new(Mutex::new(open(&files, &p.bootstrap)));
        let worker = owner.clone();
        let (entered, entry) = mpsc::channel();
        let (release, wait) = mpsc::channel();
        let job = std::thread::spawn(move || {
            let _engine = worker.lock().unwrap();
            entered.send(()).unwrap();
            wait.recv_timeout(std::time::Duration::from_secs(10))
                .unwrap();
        });
        entry
            .recv_timeout(std::time::Duration::from_secs(10))
            .unwrap();
        drop(owner);
        let second = child(&files, "probe");
        release.send(()).unwrap();
        job.join().unwrap();
        assert_eq!(second.status.code(), Some(11));
        assert!(child(&files, "probe").status.success());
    });
}

#[test]
fn wrong_bootstrap_or_resealed_foreign_role_never_adopts_or_writes() {
    runtime().block_on(async {
        let files = Fixture::new();
        let other = Fixture::new();
        let p = publisher::Fixture::new().await;
        let foreign = publisher::Fixture::new().await;
        drop(open(&files, &p.bootstrap));
        drop(open(&other, &p.bootstrap));
        let before = tree(&path(&files));
        assert!(Engine::open(&files.root, &foreign.bootstrap, &mut |_| {}).is_err());
        assert_eq!(tree(&path(&files)), before);
        let mut binding: Binding = bridge::decode(
            &std::fs::read(path(&files).join("binding.json")).unwrap(),
            CAP,
        )
        .unwrap();
        let other: Binding = bridge::decode(
            &std::fs::read(path(&other).join("binding.json")).unwrap(),
            CAP,
        )
        .unwrap();
        binding.downloads = other.downloads;
        let bytes = bridge::encoded(&binding).unwrap();
        std::fs::write(path(&files).join("binding.json"), &bytes).unwrap();
        std::fs::write(
            path(&files).join("ready.json"),
            bridge::encoded(&Ready {
                format: 1,
                environment: ENVIRONMENT.into(),
                binding: manifest::hash(&bytes),
            })
            .unwrap(),
        )
        .unwrap();
        let corrupted = tree(&path(&files));
        assert!(Engine::open(&files.root, &p.bootstrap, &mut |_| {}).is_err());
        assert_eq!(tree(&path(&files)), corrupted);
    });
}

#[test]
fn foreign_approval_is_refused_before_download_or_journal_mutation() {
    runtime().block_on(async {
        let files = Fixture::new();
        let other = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut a = open(&files, &p.bootstrap);
        let mut b = open(&other, &p.bootstrap);
        let new_a = refresh(&mut a, &p, 1, b"payload").await;
        let new_b = refresh(&mut b, &p, 1, b"payload").await;
        let approval = a
            .preview(&p.bootstrap, &new_a, None, Mode::Update, &Cancel::new())
            .unwrap();
        let before = tree(&path(&other));
        assert!(matches!(
            b.confirm(
                &p.bootstrap,
                approval,
                &new_b,
                None,
                objects(b"payload"),
                &Cancel::new(),
                &mut |_| {}
            )
            .await,
            Err("INSTALL_PLAN_CHANGED")
        ));
        assert_eq!(tree(&path(&other)), before);
        let external = refresh(&mut a, &p, 2, b"new").await;
        assert!(matches!(
            b.preview(&p.bootstrap, &external, None, Mode::Update, &Cancel::new()),
            Err("ENGINE_RELEASE_MISMATCH")
        ));
        assert_eq!(tree(&path(&other)), before);
    });
}

#[test]
fn unknown_layout_and_lease_invalidation_preserve_data_and_hold_owner() {
    runtime().block_on(async {
        let p = publisher::Fixture::new().await;
        for fault in ["unknown", "lease-link"] {
            let files = Fixture::new();
            let mut engine = open(&files, &p.bootstrap);
            if fault == "lease-link" {
                std::fs::hard_link(
                    path(&files).join("lease.lock"),
                    files.path().join("test-lease-link"),
                )
                .unwrap();
            } else {
                std::fs::write(
                    path(&files).join("cache/downloads/foreign.bin"),
                    b"foreign bytes",
                )
                .unwrap();
            }
            let before = tree(&path(&files));
            assert!(engine.validate(&p.bootstrap).is_err());
            assert!(matches!(
                Engine::open(&files.root, &p.bootstrap, &mut |_| {}),
                Err("ENGINE_BUSY")
            ));
            assert!(matches!(
                engine.validate(&p.bootstrap),
                Err("ENGINE_BLOCKED")
            ));
            assert_eq!(tree(&path(&files)), before);
            drop(engine);
            assert!(Engine::open(&files.root, &p.bootstrap, &mut |_| {}).is_err());
            assert_eq!(tree(&path(&files)), before);
        }
    });
}

#[test]
fn redirected_download_objects_are_refused_without_touching_external_data() {
    runtime().block_on(async {
        let files = Fixture::new();
        let outside = Fixture::new();
        let p = publisher::Fixture::new().await;
        drop(open(&files, &p.bootstrap));
        let junction = path(&files).join("cache/downloads/objects");
        assert!(junction.starts_with(files.path()));
        std::fs::remove_dir(&junction).unwrap();
        std::fs::write(outside.path().join("sentinel.blob"), b"external sentinel").unwrap();
        let output = Command::new("cmd.exe")
            .args(["/d", "/c", "mklink", "/J"])
            .arg(&junction)
            .arg(outside.path())
            .output()
            .unwrap();
        assert!(output.status.success());
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            assert!(Engine::open(&files.root, &p.bootstrap, &mut |_| {}).is_err());
            assert_eq!(
                std::fs::read(outside.path().join("sentinel.blob")).unwrap(),
                b"external sentinel"
            );
        }));
        std::fs::remove_dir(&junction).unwrap();
        if let Err(error) = result {
            std::panic::resume_unwind(error);
        }
    });
}
