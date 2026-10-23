use super::super::tests::Fixture;
use super::*;
use crate::pack::local::LocalInstance;
use std::{collections::BTreeMap, process::Command, sync::Barrier};
fn root_path(files: &Fixture) -> std::path::PathBuf {
    files.path().join(NAMESPACE)
}
fn bytes(path: &Path) -> BTreeMap<String, Vec<u8>> {
    fn walk(base: &Path, dir: &Path, output: &mut BTreeMap<String, Vec<u8>>) {
        for entry in std::fs::read_dir(dir).unwrap() {
            let entry = entry.unwrap();
            let path = entry.path();
            if entry.file_type().unwrap().is_dir() {
                walk(base, &path, output);
            } else {
                output.insert(
                    path.strip_prefix(base)
                        .unwrap()
                        .to_string_lossy()
                        .to_string(),
                    std::fs::read(path).unwrap(),
                );
            }
        }
    }
    let mut output = BTreeMap::new();
    walk(path, path, &mut output);
    output
}
fn child(path: &Path, phase: &str) -> std::process::Output {
    Command::new(std::env::current_exe().unwrap())
        .args([
            "--exact",
            "pack::ntfs::provision::tests::provision_child",
            "--nocapture",
        ])
        .env("AOC_PROVISION_CHILD", path)
        .env("AOC_PROVISION_PHASE", phase)
        .output()
        .unwrap()
}
#[test]
fn provision_child() {
    let Some(root) = std::env::var_os("AOC_PROVISION_CHILD") else {
        return;
    };
    let root = std::path::PathBuf::from(root).canonicalize().unwrap();
    assert_eq!(
        root.parent().unwrap(),
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../..")
            .canonicalize()
            .unwrap()
            .join(".tools")
            .canonicalize()
            .unwrap()
    );
    assert!(root
        .file_name()
        .unwrap()
        .to_str()
        .unwrap()
        .starts_with("ntfs-fixture-"));
    let phase = std::env::var("AOC_PROVISION_PHASE").unwrap();
    let parent = Dir::existing(&root).unwrap();
    match Lifetime::open_parent(&parent, &mut |at| {
        if phase == at {
            std::process::exit(37);
        }
    }) {
        Ok(mut lifetime) => {
            lifetime.validate().unwrap();
        }
        Err(Status::Busy) => std::process::exit(11),
        Err(Status::Blocked) => std::process::exit(12),
        Err(_) => panic!("unexpected fixture status"),
    }
}
#[test]
fn fixed_namespace_reopens_without_writes_and_preserves_instance_userdata() {
    let files = Fixture::new();
    let local = LocalInstance::open(files.path());
    assert_eq!(local.snapshot().status, Status::Ready);
    let world = root_path(&files).join("instance/saves");
    std::fs::create_dir(&world).unwrap();
    std::fs::write(world.join("world.dat"), b"user world").unwrap();
    let before = bytes(&root_path(&files));
    assert_eq!(
        LocalInstance::open(files.path()).snapshot().status,
        Status::Busy
    );
    assert_eq!(child(files.path(), "probe").status.code(), Some(11));
    drop(local);
    let local = LocalInstance::open(files.path());
    assert_eq!(local.snapshot().status, Status::Ready);
    assert_eq!(bytes(&root_path(&files)), before);
    drop(local);
    assert!(child(files.path(), "probe").status.success());
    assert_eq!(bytes(&root_path(&files)), before);
}
#[test]
fn simultaneous_first_create_has_one_lifetime_holder() {
    let files = Fixture::new();
    let barrier = Arc::new(Barrier::new(6));
    let workers: Vec<_> = (0..6)
        .map(|_| {
            let path = files.path().to_owned();
            let barrier = barrier.clone();
            std::thread::spawn(move || {
                barrier.wait();
                LocalInstance::open(&path)
            })
        })
        .collect();
    let holders: Vec<_> = workers.into_iter().map(|t| t.join().unwrap()).collect();
    assert_eq!(
        holders
            .iter()
            .filter(|l| l.snapshot().status == Status::Ready)
            .count(),
        1
    );
    assert!(holders.iter().all(|l| matches!(
        l.snapshot().status,
        Status::Ready | Status::Busy | Status::Blocked
    )));
    drop(holders);
    assert_eq!(
        LocalInstance::open(files.path()).snapshot().status,
        Status::Ready
    );
}
#[test]
fn child_exit_releases_lease_but_incomplete_provisioning_is_preserved_blocked() {
    for phase in [
        "namespace-created",
        "lease-created",
        "namespace-sealed",
        "control-created",
        "instance-created",
        "cache-created",
        "ready-sealed",
    ] {
        let files = Fixture::new();
        let result = child(files.path(), phase);
        assert_eq!(result.status.code(), Some(37), "{phase}: {result:?}");
        let before = bytes(&root_path(&files));
        let local = LocalInstance::open(files.path());
        assert_eq!(
            local.snapshot().status,
            if phase == "ready-sealed" {
                Status::Ready
            } else {
                Status::Blocked
            },
            "{phase}"
        );
        assert_eq!(bytes(&root_path(&files)), before);
    }
}
#[test]
fn worker_arc_retains_lease_after_ui_owner_drops() {
    let files = Fixture::new();
    let local = Arc::new(LocalInstance::open(files.path()));
    assert_eq!(local.snapshot().status, Status::Ready);
    let (entered, received) = std::sync::mpsc::channel();
    let (go, wait) = std::sync::mpsc::channel();
    let worker_owner = local.clone();
    let worker = std::thread::spawn(move || {
        assert_eq!(worker_owner.snapshot().status, Status::Ready);
        entered.send(()).unwrap();
        wait.recv_timeout(Duration::from_secs(10)).unwrap();
        drop(worker_owner);
    });
    received.recv_timeout(Duration::from_secs(2)).unwrap();
    drop(local);
    let result = child(files.path(), "probe");
    go.send(()).unwrap();
    worker.join().unwrap();
    assert_eq!(result.status.code(), Some(11), "{result:?}");
    let local = LocalInstance::open(files.path());
    assert_eq!(local.snapshot().status, Status::Ready);
}
#[test]
fn unknown_namespace_or_changed_records_are_never_adopted_reset_or_written() {
    for kind in [
        "unknown", "receipt", "binding", "lease", "foreign", "copied",
    ] {
        let files = Fixture::new();
        if kind == "unknown" {
            std::fs::create_dir(root_path(&files)).unwrap();
            std::fs::write(root_path(&files).join("sentinel"), b"foreign").unwrap();
        } else {
            drop(LocalInstance::open(files.path()));
            match kind {
                "receipt" => {
                    std::fs::write(root_path(&files).join("namespace.json"), b"{").unwrap()
                }
                "binding" => std::fs::write(root_path(&files).join("ready.json"), b"{}").unwrap(),
                "lease" => std::fs::write(root_path(&files).join("lease.lock"), b"bad").unwrap(),
                "foreign" => {
                    std::fs::write(root_path(&files).join("foreign.bin"), b"preserve").unwrap()
                }
                "copied" => {
                    let other = Fixture::new();
                    drop(LocalInstance::open(other.path()));
                    std::fs::copy(
                        root_path(&other).join("namespace.json"),
                        root_path(&files).join("namespace.json"),
                    )
                    .unwrap();
                }
                _ => unreachable!(),
            }
        }
        let before = bytes(&root_path(&files));
        let local = LocalInstance::open(files.path());
        assert_eq!(local.snapshot().status, Status::Blocked, "{kind}");
        assert_eq!(bytes(&root_path(&files)), before);
    }
}
#[test]
fn added_hard_link_invalidates_holder_without_releasing_lease() {
    let files = Fixture::new();
    let local = LocalInstance::open(files.path());
    assert_eq!(local.snapshot().status, Status::Ready);
    std::fs::hard_link(
        root_path(&files).join("lease.lock"),
        files.path().join("foreign-link"),
    )
    .unwrap();
    assert_eq!(local.snapshot().status, Status::Blocked);
    assert_eq!(child(files.path(), "probe").status.code(), Some(11));
    drop(local);
    assert_eq!(
        LocalInstance::open(files.path()).snapshot().status,
        Status::Blocked
    );
    assert_eq!(
        std::fs::read(files.path().join("foreign-link")).unwrap(),
        std::fs::read(root_path(&files).join("lease.lock")).unwrap()
    );
}
#[test]
fn redirected_child_is_refused_without_touching_external_fixture() {
    let files = Fixture::new();
    let outside = Fixture::new();
    drop(LocalInstance::open(files.path()));
    let cache = root_path(&files).join("cache");
    assert!(cache.starts_with(files.path()));
    std::fs::remove_file(cache.join("aoc-instance.json")).unwrap();
    std::fs::remove_dir(&cache).unwrap();
    std::fs::write(outside.path().join("sentinel"), b"external fixture").unwrap();
    let result = Command::new("cmd.exe")
        .args(["/d", "/c", "mklink", "/J"])
        .arg(&cache)
        .arg(outside.path())
        .output()
        .unwrap();
    assert!(result.status.success());
    let checked = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let local = LocalInstance::open(files.path());
        assert_eq!(local.snapshot().status, Status::Blocked);
        assert_eq!(
            std::fs::read(outside.path().join("sentinel")).unwrap(),
            b"external fixture"
        );
    }));
    std::fs::remove_dir(&cache).unwrap();
    if let Err(error) = checked {
        std::panic::resume_unwind(error);
    }
}
