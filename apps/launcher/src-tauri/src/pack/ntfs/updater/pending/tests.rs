use super::*;
use crate::pack::ntfs::tests::Fixture;
use std::{collections::BTreeMap, fs, process::Command};
fn local() -> String {
    manifest::hash(b"test-only-local-binding")
}
fn image(version: &str, byte: u8) -> Image {
    Image {
        version: version.into(),
        bytes: 1024,
        sha256: manifest::hash(&vec![byte; 1024]),
    }
}
fn intent() -> Intent {
    Intent {
        product: "AnimeOnCraft".into(),
        target: manifest::hash(b"test-only-installed-target"),
        old: image("0.7.18", 1),
        new: image("0.7.19", 2),
    }
}
fn observation(image: Image) -> Observation {
    let i = intent();
    Observation {
        product: i.product,
        target: i.target,
        image,
    }
}
fn snapshot(base: &Path) -> BTreeMap<PathBuf, Vec<u8>> {
    fn walk(base: &Path, path: &Path, out: &mut BTreeMap<PathBuf, Vec<u8>>) {
        for entry in fs::read_dir(path).unwrap() {
            let entry = entry.unwrap();
            if entry.file_type().unwrap().is_dir() {
                walk(base, &entry.path(), out);
            } else {
                out.insert(
                    entry.path().strip_prefix(base).unwrap().to_owned(),
                    fs::read(entry.path()).unwrap(),
                );
            }
        }
    }
    let mut out = BTreeMap::new();
    walk(base, base, &mut out);
    out
}
fn child(path: &Path, phase: &str) {
    let output = Command::new(std::env::current_exe().unwrap())
        .args([
            "--exact",
            "pack::ntfs::updater::pending::tests::journal_child",
            "--nocapture",
        ])
        .env("AOC_UPDATE_JOURNAL_CHILD", path)
        .env("AOC_UPDATE_JOURNAL_PHASE", phase)
        .output()
        .unwrap();
    assert_eq!(
        output.status.code(),
        Some(37),
        "{}",
        String::from_utf8_lossy(&output.stdout)
    );
}
#[test]
fn journal_child() {
    let Some(path) = std::env::var_os("AOC_UPDATE_JOURNAL_CHILD") else {
        return;
    };
    let path = PathBuf::from(path);
    let tools = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../..")
        .canonicalize()
        .unwrap()
        .join(".tools");
    assert_eq!(path.parent(), Some(tools.as_path()));
    assert!(path
        .file_name()
        .unwrap()
        .to_str()
        .unwrap()
        .starts_with("ntfs-fixture-"));
    assert_eq!(path.canonicalize().unwrap(), path);
    let parent = Dir::existing(&path).unwrap();
    let phase = std::env::var("AOC_UPDATE_JOURNAL_PHASE").unwrap();
    let mut crash = |at: &str| {
        if at == phase {
            std::process::exit(37);
        }
    };
    let mut journal = Journal::open_with(&parent, &local(), true, &mut crash)
        .unwrap()
        .unwrap();
    if phase.starts_with("result") {
        journal
            .reconcile_with(&observation(intent().new), &mut crash)
            .unwrap();
    } else {
        let mut stage = Stage::create(&parent, &[0x61; 1024]).unwrap();
        journal
            .begin_with(&mut stage, intent(), &mut crash)
            .unwrap();
        journal
            .spawned_with(std::process::id(), &mut crash)
            .unwrap();
    }
    panic!("Missing expected crash hook: {phase}");
}

#[test]
fn absent_read_is_write_free_and_leases_and_local_binding_cannot_be_bypassed() {
    let files = Fixture::new();
    assert!(Journal::open(&files.root, &local(), false)
        .unwrap()
        .is_none());
    assert!(snapshot(files.path()).is_empty());
    assert!(Journal::open(&files.root, "bad", true).is_err());
    assert!(snapshot(files.path()).is_empty());
    let journal = Journal::open(&files.root, &local(), true).unwrap().unwrap();
    assert_eq!(journal.status().unwrap(), Status::Empty);
    assert_eq!(
        Journal::open(&files.root, &local(), true).err(),
        Some("UPDATE_JOURNAL_BUSY")
    );
    let before = snapshot(files.path());
    drop(journal);
    assert_eq!(
        Journal::open(&files.root, &manifest::hash(b"other local"), false).err(),
        Some("UPDATE_JOURNAL_INVALID")
    );
    let journal = Journal::open(&files.root, &local(), false)
        .unwrap()
        .unwrap();
    assert_eq!(journal.status().unwrap(), Status::Empty);
    drop(journal);
    assert_eq!(snapshot(files.path()), before);
}

#[test]
fn concurrent_first_creation_has_one_writer_and_no_loser_can_reset_it() {
    let files = Fixture::new();
    let start = Arc::new(std::sync::Barrier::new(9));
    let finish = Arc::new(std::sync::Barrier::new(9));
    let workers: Vec<_> = (0..8)
        .map(|_| {
            let parent = files.root.clone();
            let start = start.clone();
            let finish = finish.clone();
            std::thread::spawn(move || {
                start.wait();
                let result = Journal::open(&parent, &local(), true);
                finish.wait(); // The winner retains its lease until every contender has finished.
                result
            })
        })
        .collect();
    start.wait();
    finish.wait();
    let results: Vec<_> = workers
        .into_iter()
        .map(|worker| worker.join().unwrap())
        .collect();
    assert_eq!(results.iter().filter(|result| result.is_ok()).count(), 1);
    let before = snapshot(files.path());
    drop(results);
    let journal = Journal::open(&files.root, &local(), false)
        .unwrap()
        .unwrap();
    assert_eq!(journal.status().unwrap(), Status::Empty);
    assert_eq!(snapshot(files.path()), before);
}

#[test]
fn intent_validation_and_no_spawn_gap_never_imply_success_or_trigger_writes() {
    let files = Fixture::new();
    let mut journal = Journal::open(&files.root, &local(), true).unwrap().unwrap();
    let mut stage = Stage::create(&files.root, &[0x61; 1024]).unwrap();
    let before = snapshot(files.path());
    for bad in [
        Intent {
            new: image("0.7.18", 2),
            ..intent()
        },
        Intent {
            target: "../world".into(),
            ..intent()
        },
        Intent {
            new: image("0.7.19-beta", 2),
            ..intent()
        },
    ] {
        assert!(journal.begin(&mut stage, bad).is_err());
        assert_eq!(snapshot(files.path()), before);
    }
    journal.begin(&mut stage, intent()).unwrap();
    assert_eq!(journal.status().unwrap(), Status::Pending);
    drop(stage); // ordinary pre-spawn Drop removes its own stage, not the durable journal.
    let before = snapshot(files.path());
    assert_eq!(
        journal.reconcile(&observation(intent().new)).unwrap(),
        Status::RecoveryRequired
    );
    assert_eq!(snapshot(files.path()), before);
    drop(journal);
    let mut journal = Journal::open(&files.root, &local(), false)
        .unwrap()
        .unwrap();
    assert_eq!(
        journal.reconcile(&observation(intent().old)).unwrap(),
        Status::RecoveryRequired
    );
    assert_eq!(snapshot(files.path()), before);
}

#[test]
fn live_installer_and_reused_pid_have_distinct_conservative_states() {
    let files = Fixture::new();
    let mut journal = Journal::open(&files.root, &local(), true).unwrap().unwrap();
    let mut stage = Stage::create(&files.root, &[0x61; 1024]).unwrap();
    journal.begin(&mut stage, intent()).unwrap();
    journal.spawned(std::process::id()).unwrap();
    let before = snapshot(files.path());
    assert_eq!(
        journal.reconcile(&observation(intent().new)).unwrap(),
        Status::WaitingForInstaller
    );
    assert!(journal.spawn_failed().is_err());
    assert!(journal.spawned(std::process::id()).is_err());
    assert_eq!(snapshot(files.path()), before);
    let mut witness = Witness::capture(std::process::id()).unwrap();
    assert_eq!(witness.probe(), Process::Alive);
    witness.created += 1;
    assert_eq!(witness.probe(), Process::Absent);
    assert_eq!(Witness { pid: 0, created: 1 }.probe(), Process::Unknown);
    stage.handed_off();
}

#[test]
fn real_process_exit_then_exact_observation_seals_applied_without_deleting_stage() {
    let files = Fixture::new();
    child(files.path(), "spawn-ready.json");
    let before = snapshot(files.path());
    let mut journal = Journal::open(&files.root, &local(), false)
        .unwrap()
        .unwrap();
    assert_eq!(journal.status().unwrap(), Status::Pending);
    for bad in [
        observation(intent().old),
        Observation {
            target: manifest::hash(b"foreign target"),
            ..observation(intent().new)
        },
        Observation {
            product: "Other".into(),
            ..observation(intent().new)
        },
        observation(image("0.7.19", 3)),
    ] {
        assert_eq!(journal.reconcile(&bad).unwrap(), Status::RecoveryRequired);
        assert_eq!(snapshot(files.path()), before);
    }
    assert_eq!(
        journal.reconcile(&observation(intent().new)).unwrap(),
        Status::Applied
    );
    let after = snapshot(files.path());
    assert_eq!(after.len(), before.len() + 2);
    for (path, bytes) in &before {
        assert_eq!(after.get(path), Some(bytes));
    }
    let mut second = Stage::create(&files.root, &[0x62; 1024]).unwrap();
    assert_eq!(
        journal.begin(&mut second, intent()).err(),
        Some("UPDATE_JOURNAL_LIMIT")
    );
    drop(second);
    drop(journal);
    let mut journal = Journal::open(&files.root, &local(), false)
        .unwrap()
        .unwrap();
    assert_eq!(
        journal.reconcile(&observation(intent().new)).unwrap(),
        Status::Applied
    );
    assert_eq!(
        journal.reconcile(&observation(intent().old)).unwrap(),
        Status::RecoveryRequired
    );
    assert_eq!(snapshot(files.path()), after);
}

#[test]
fn explicit_native_spawn_error_can_be_persisted_but_cannot_be_inferred_after_crash() {
    let files = Fixture::new();
    let mut journal = Journal::open(&files.root, &local(), true).unwrap().unwrap();
    let mut stage = Stage::create(&files.root, &[0x61; 1024]).unwrap();
    journal.begin(&mut stage, intent()).unwrap();
    assert!(Command::new(stage.checked_path().unwrap()).spawn().is_err());
    journal.spawn_failed().unwrap();
    drop(stage);
    assert_eq!(
        journal.reconcile(&observation(intent().old)).unwrap(),
        Status::NotStarted
    );
    let before = snapshot(files.path());
    drop(journal);
    let mut journal = Journal::open(&files.root, &local(), false)
        .unwrap()
        .unwrap();
    assert_eq!(
        journal.reconcile(&observation(intent().old)).unwrap(),
        Status::NotStarted
    );
    assert_eq!(
        journal.reconcile(&observation(intent().new)).unwrap(),
        Status::RecoveryRequired
    );
    assert!(journal.spawn_failed().is_err());
    assert_eq!(snapshot(files.path()), before);
}

#[test]
fn crashes_during_root_creation_are_preserved_without_reset_or_adoption() {
    for phase in [
        "root-created",
        "lease-created",
        "binding-created",
        "root-ready",
    ] {
        let files = Fixture::new();
        child(files.path(), phase);
        let before = snapshot(files.path());
        for create in [false, true] {
            let result = Journal::open(&files.root, &local(), create);
            if phase == "root-ready" {
                assert_eq!(result.unwrap().unwrap().status().unwrap(), Status::Empty);
            } else {
                assert!(result.is_err(), "{phase}");
            }
            assert_eq!(snapshot(files.path()), before);
        }
    }
}

#[test]
fn crashes_before_and_after_pending_and_spawn_seals_do_not_repeat_installation() {
    for phase in [
        "pending.json",
        "pending-ready.json",
        "spawned.json",
        "spawn-ready.json",
    ] {
        let files = Fixture::new();
        child(files.path(), phase);
        let before = snapshot(files.path());
        let result = Journal::open(&files.root, &local(), false);
        if phase == "pending.json" || phase == "spawned.json" {
            assert!(result.is_err(), "{phase}");
        } else {
            let mut journal = result.unwrap().unwrap();
            assert_eq!(journal.status().unwrap(), Status::Pending);
            assert_eq!(
                journal.reconcile(&observation(intent().old)).unwrap(),
                Status::RecoveryRequired
            );
        }
        assert_eq!(snapshot(files.path()), before);
    }
}

#[test]
fn crashes_during_result_sealing_never_promote_a_partial_result() {
    for phase in ["result.json", "result-ready.json"] {
        let files = Fixture::new();
        child(files.path(), "spawn-ready.json");
        child(files.path(), phase);
        let before = snapshot(files.path());
        let result = Journal::open(&files.root, &local(), false);
        if phase == "result.json" {
            assert!(result.is_err());
        } else {
            let mut journal = result.unwrap().unwrap();
            assert_eq!(
                journal.reconcile(&observation(intent().new)).unwrap(),
                Status::Applied
            );
        }
        assert_eq!(snapshot(files.path()), before);
    }
}

#[test]
fn foreign_modified_linked_or_replaced_installer_is_preserved_and_prevents_acknowledgement() {
    for mode in ["foreign", "modified", "linked", "replaced"] {
        let files = Fixture::new();
        child(files.path(), "spawn-ready.json");
        let directory = fs::read_dir(files.path())
            .unwrap()
            .map(|e| e.unwrap().path())
            .find(|p| p.file_name().unwrap().to_str().unwrap().starts_with(PREFIX))
            .unwrap();
        let installer = directory.join("setup.exe");
        match mode {
            "foreign" => fs::write(directory.join("world.dat"), b"preserved foreign").unwrap(),
            "modified" => fs::write(&installer, vec![0x63; 1024]).unwrap(),
            "linked" => fs::hard_link(&installer, files.path().join("foreign-alias.exe")).unwrap(),
            "replaced" => {
                let bytes = fs::read(&installer).unwrap();
                fs::remove_file(&installer).unwrap();
                fs::write(&installer, bytes).unwrap();
            }
            _ => unreachable!(),
        }
        let before = snapshot(files.path());
        let mut journal = Journal::open(&files.root, &local(), false)
            .unwrap()
            .unwrap();
        assert_eq!(
            journal.reconcile(&observation(intent().new)).unwrap(),
            Status::RecoveryRequired,
            "{mode}"
        );
        assert_eq!(snapshot(files.path()), before);
    }
}

#[test]
fn corrupted_partial_duplicate_json_foreign_and_replaced_records_are_never_repaired_on_open() {
    for mode in [
        "corrupt",
        "oversize",
        "duplicate",
        "foreign",
        "replace",
        "hardlink",
    ] {
        let files = Fixture::new();
        child(files.path(), "spawn-ready.json");
        let pending = files.path().join(NAME).join("pending.json");
        match mode {
            "corrupt" => fs::write(&pending, b"{").unwrap(),
            "oversize" => fs::write(&pending, vec![b' '; CAP + 1]).unwrap(),
            "duplicate" => fs::write(&pending, b"{\"format\":1,\"format\":1}").unwrap(),
            "foreign" => {
                fs::write(files.path().join(NAME).join("foreign.txt"), b"preserved").unwrap()
            }
            "replace" => {
                let bytes = fs::read(&pending).unwrap();
                fs::remove_file(&pending).unwrap();
                fs::write(&pending, bytes).unwrap();
            }
            "hardlink" => {
                fs::hard_link(&pending, files.path().join("foreign-pending-alias")).unwrap()
            }
            _ => unreachable!(),
        }
        let before = snapshot(files.path());
        assert!(
            Journal::open(&files.root, &local(), false).is_err(),
            "{mode}"
        );
        assert!(
            Journal::open(&files.root, &local(), true).is_err(),
            "{mode}"
        );
        assert_eq!(snapshot(files.path()), before);
    }
}

#[test]
fn normal_local_api_retains_lifetime_and_preserves_the_original_empty_namespace() {
    use crate::pack::local::{LocalInstance, Status as LocalStatus};
    let files = Fixture::new();
    let local = Arc::new(LocalInstance::open(files.path()));
    assert_eq!(local.snapshot().status, LocalStatus::Ready);
    let original = snapshot(&files.path().join("AnimeOnCraft.InstallDevelopment.v1"));
    assert!(local.update_journal(false).unwrap().is_none());
    let journal = local.update_journal(true).unwrap().unwrap();
    drop(local);
    assert_eq!(
        LocalInstance::open(files.path()).snapshot().status,
        LocalStatus::Busy
    );
    assert_eq!(journal.status().unwrap(), Status::Empty);
    drop(journal);
    let local = Arc::new(LocalInstance::open(files.path()));
    assert_eq!(local.snapshot().status, LocalStatus::Ready);
    let journal = local.update_journal(false).unwrap().unwrap();
    assert_eq!(journal.status().unwrap(), Status::Empty);
    assert_eq!(
        snapshot(&files.path().join("AnimeOnCraft.InstallDevelopment.v1")),
        original
    );
    let busy = Arc::new(LocalInstance::open(files.path()));
    assert_eq!(busy.snapshot().status, LocalStatus::Busy);
    assert_eq!(
        busy.update_journal(true).err(),
        Some("UPDATE_LOCAL_UNAVAILABLE")
    );
}
