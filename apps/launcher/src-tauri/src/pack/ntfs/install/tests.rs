use super::super::{download, tests::Fixture};
use super::*;
use crate::pack::tests::{self as publisher, runtime};
use std::{
    path::{Path, PathBuf},
    process::Command,
    sync::Arc,
};
include!("tests_repair.rs");
include!("tests_actor.rs");
fn path(dir: &Arc<Dir>) -> PathBuf {
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
    PathBuf::from(String::from_utf16(&buffer[..n]).unwrap())
}
fn document(sequence: u64, files: &[(&str, &[u8])]) -> manifest::Manifest {
    let mut doc = publisher::document(sequence);
    doc.files.clear();
    for (i, (path, bytes)) in files.iter().enumerate() {
        doc.files.push(manifest::File {
            path: (*path).into(),
            sha256: manifest::hash(bytes),
            length: bytes.len() as u64,
            side: Side::CLIENT,
            source_ref: manifest::Source {
                source_id: "fixture".into(),
                object_key: format!("objects/{sequence}-{i}.bin"),
            },
            managed: true,
        });
    }
    doc
}
async fn prepared(
    parent: &Arc<Dir>,
    new: &VerifiedRelease,
    files: &[(&str, &[u8])],
    name: &str,
) -> Prepared {
    let dir = parent.child(name, true).unwrap();
    let objects = new
        .manifest()
        .manifest()
        .files
        .iter()
        .zip(files)
        .map(|(file, (_, bytes))| (file.source_ref.object_key.clone(), bytes.to_vec()))
        .collect();
    download::fixture_prepared(&dir, new, objects)
        .await
        .unwrap()
}
async fn proof(
    parent: &Arc<Dir>,
    bootstrap: &[u8],
    transport: publisher::Memory,
    sequence: u64,
) -> VerifiedRelease {
    let dir = parent
        .child(&format!("proof-{}", uuid::Uuid::new_v4().simple()), true)
        .unwrap();
    trust::verify(
        bootstrap,
        "https://fixtures.animeoncraft.invalid/metadata/"
            .parse()
            .unwrap(),
        "https://fixtures.animeoncraft.invalid/targets/"
            .parse()
            .unwrap(),
        transport,
        &trust::TrustStore::fixture(path(&dir), "install-proof-v1"),
        &publisher::policy(sequence),
    )
    .await
    .unwrap()
}
async fn release(
    parent: &Arc<Dir>,
    p: &publisher::Fixture,
    doc: &manifest::Manifest,
) -> VerifiedRelease {
    proof(
        parent,
        &p.bootstrap,
        p.published_manifest(doc, doc.release_sequence, false).await,
        doc.release_sequence,
    )
    .await
}
fn read(root: &Arc<Dir>, name: &str) -> Vec<u8> {
    let mut f = managed_file(root, name).unwrap().unwrap();
    f.file.seek(SeekFrom::Start(0)).unwrap();
    let mut bytes = Vec::new();
    f.file.read_to_end(&mut bytes).unwrap();
    bytes
}
fn fail<T>(value: IResult<T>, code: &str) {
    match value {
        Err(e) => assert_eq!(e, code),
        Ok(_) => panic!("unexpected success"),
    }
}
#[test]
fn install_replace_reuse_remove_commit_reopen_and_userdata_preservation() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut store = Store::create(&files.root, &p.bootstrap).unwrap();
        let userdata = store.instance.dir.child("saves", true).unwrap();
        let mut world = userdata.file("world.dat", true).unwrap();
        world.write_new(b"user world bytes").unwrap();
        drop(world);
        let old_files: [(&str, &[u8]); 3] = [
            ("mods/a.jar", b"old a"),
            ("mods/keep.jar", b"unchanged"),
            ("libraries/remove.jar", b"remove bytes"),
        ];
        let old = release(&files.root, &p, &document(1, &old_files)).await;
        let mut downloaded = prepared(&files.root, &old, &old_files, "download-old").await;
        assert_eq!(
            store
                .begin(&old, None, &mut downloaded, &Cancel::new(), &mut |_| {})
                .unwrap(),
            Outcome::Committed {
                cancellation_deferred: false
            }
        );
        drop(downloaded);
        let new_files: [(&str, &[u8]); 3] = [
            ("mods/a.jar", b"new a"),
            ("mods/keep.jar", b"unchanged"),
            ("versions/v/new.jar", b"new file"),
        ];
        let new = release(&files.root, &p, &document(2, &new_files)).await;
        let mut downloaded = prepared(&files.root, &new, &new_files, "download-new").await;
        assert_eq!(
            store
                .begin(
                    &new,
                    Some(&old),
                    &mut downloaded,
                    &Cancel::new(),
                    &mut |_| {}
                )
                .unwrap(),
            Outcome::Committed {
                cancellation_deferred: false
            }
        );
        assert_eq!(read(&store.instance.dir, "mods/a.jar"), b"new a");
        assert_eq!(read(&store.instance.dir, "mods/keep.jar"), b"unchanged");
        assert!(managed_file(&store.instance.dir, "libraries/remove.jar")
            .unwrap()
            .is_none());
        let tx = store.load(2).unwrap();
        assert_eq!(get(&tx.backup, "00000.old", 128).unwrap(), b"old a");
        assert_eq!(get(&tx.backup, "00003.old", 128).unwrap(), b"remove bytes");
        assert_eq!(
            get(&userdata, "world.dat", 128).unwrap(),
            b"user world bytes"
        );
        drop(tx);
        drop(userdata);
        drop(downloaded);
        drop(store);
        let mut store = Store::reopen(&files.root, &p.bootstrap).unwrap();
        assert!(store.head().unwrap().pending.is_none());
        assert_eq!(store.head().unwrap().active.unwrap().number, 2);
        assert_eq!(
            store
                .recover(&new, Some(&old), &Cancel::new(), &mut |_| {})
                .unwrap(),
            Outcome::Committed {
                cancellation_deferred: false
            }
        );
        fail(
            store.begin(
                &old,
                Some(&new),
                &mut prepared(&files.root, &old, &old_files, "download-rollback").await,
                &Cancel::new(),
                &mut |_| {},
            ),
            "INSTALL_ROLLBACK",
        );
    });
}
#[test]
fn cancel_before_apply_and_deferred_cancel_after_backup() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut store = Store::create(&files.root, &p.bootstrap).unwrap();
        let payload: [(&str, &[u8]); 1] = [("mods/a.jar", b"old")];
        let old = release(&files.root, &p, &document(1, &payload)).await;
        let mut data = prepared(&files.root, &old, &payload, "download-one").await;
        let cancel = Cancel::new();
        let signal = cancel.clone();
        assert_eq!(
            store
                .begin(
                    &old,
                    None,
                    &mut data,
                    &cancel,
                    &mut |phase| if phase == "prepared" {
                        signal.cancel()
                    }
                )
                .unwrap(),
            Outcome::Cancelled
        );
        assert!(managed_file(&store.instance.dir, "mods/a.jar")
            .unwrap()
            .is_none());
        assert!(store.head().unwrap().active.is_none());
        assert_eq!(
            store
                .begin(&old, None, &mut data, &Cancel::new(), &mut |_| {})
                .unwrap(),
            Outcome::Committed {
                cancellation_deferred: false
            }
        );
        let payload: [(&str, &[u8]); 1] = [("mods/a.jar", b"new")];
        let new = release(&files.root, &p, &document(2, &payload)).await;
        let mut data = prepared(&files.root, &new, &payload, "download-two").await;
        let cancel = Cancel::new();
        let signal = cancel.clone();
        assert_eq!(
            store
                .begin(&new, Some(&old), &mut data, &cancel, &mut |phase| if phase
                    == "after-backup:00000"
                {
                    signal.cancel()
                })
                .unwrap(),
            Outcome::Committed {
                cancellation_deferred: true
            }
        );
        assert_eq!(read(&store.instance.dir, "mods/a.jar"), b"new");
        assert!(store.head().unwrap().pending.is_none());
    });
}
#[test]
fn foreign_modified_cache_and_cross_anchor_fail_before_managed_mutation() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut store = Store::create(&files.root, &p.bootstrap).unwrap();
        let payload: [(&str, &[u8]); 1] = [("mods/a.jar", b"old")];
        let old = release(&files.root, &p, &document(1, &payload)).await;
        let mut data = prepared(&files.root, &old, &payload, "download-one").await;
        let mods = store.instance.dir.child("mods", true).unwrap();
        let mut foreign = mods.file("foreign.jar", true).unwrap();
        foreign.write_new(b"foreign").unwrap();
        drop(foreign);
        fail(
            store.begin(&old, None, &mut data, &Cancel::new(), &mut |_| {}),
            "INSTALL_FOREIGN_OR_MODIFIED",
        );
        assert_eq!(read(&store.instance.dir, "mods/foreign.jar"), b"foreign");
        assert_eq!(store.head().unwrap().last, 0);
        let other = files.root.child("other", true).unwrap();
        let mut clean = Store::create(&other, &p.bootstrap).unwrap();
        let foreign_p = publisher::Fixture::new().await;
        let foreign = release(&files.root, &foreign_p, &document(1, &payload)).await;
        fail(
            clean.begin(&foreign, None, &mut data, &Cancel::new(), &mut |_| {}),
            "INSTALL_TRUST_MISMATCH",
        );
        clean
            .begin(&old, None, &mut data, &Cancel::new(), &mut |_| {})
            .unwrap();
        drop(data);
        std::fs::write(
            path(&clean.instance.dir).join("mods/a.jar"),
            b"user modification",
        )
        .unwrap();
        let payload: [(&str, &[u8]); 1] = [("mods/a.jar", b"new")];
        let new = release(&files.root, &p, &document(2, &payload)).await;
        let mut data = prepared(&files.root, &new, &payload, "download-two").await;
        fail(
            clean.begin(&new, Some(&old), &mut data, &Cancel::new(), &mut |_| {}),
            "INSTALL_FOREIGN_OR_MODIFIED",
        );
        assert_eq!(
            read(&clean.instance.dir, "mods/a.jar"),
            b"user modification"
        );
        assert_eq!(clean.head().unwrap().last, 1);
    });
}
#[test]
fn child_install_fixture() {
    let Some(root) = std::env::var_os("AOC_INSTALL_CHILD") else {
        return;
    };
    let root = PathBuf::from(root);
    let workspace = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../..")
        .canonicalize()
        .unwrap();
    let parent = workspace.join(".tools");
    assert_eq!(root.parent(), Some(parent.as_path()));
    assert!(root
        .file_name()
        .unwrap()
        .to_str()
        .unwrap()
        .starts_with("ntfs-fixture-"));
    assert_eq!(root.canonicalize().unwrap(), root);
    runtime().block_on(async {
        let root = Dir::fixture_existing(&root).unwrap();
        let bootstrap = get(&root, "bootstrap.json", manifest::MAX_MANIFEST).unwrap();
        let old_files = decode(
            &get(&root, "old-transport.json", 8 * manifest::MAX_MANIFEST).unwrap(),
            8 * manifest::MAX_MANIFEST,
        )
        .unwrap();
        let new_files = decode(
            &get(&root, "new-transport.json", 8 * manifest::MAX_MANIFEST).unwrap(),
            8 * manifest::MAX_MANIFEST,
        )
        .unwrap();
        let old = proof(
            &root,
            &bootstrap,
            publisher::Memory {
                files: Arc::new(old_files),
                crash: false,
            },
            std::env::var("AOC_INSTALL_OLD_SEQUENCE")
                .ok()
                .map(|s| s.parse().unwrap())
                .unwrap_or(1),
        )
        .await;
        let new = proof(
            &root,
            &bootstrap,
            publisher::Memory {
                files: Arc::new(new_files),
                crash: false,
            },
            std::env::var("AOC_INSTALL_NEW_SEQUENCE")
                .ok()
                .map(|s| s.parse().unwrap())
                .unwrap_or(2),
        )
        .await;
        let mut store = Store::reopen(&root, &bootstrap).unwrap();
        let crash = std::env::var("AOC_INSTALL_CRASH").unwrap();
        let mut hook = |phase: &str| {
            if phase == crash {
                std::process::exit(37)
            }
        };
        if std::env::var("AOC_INSTALL_RECOVER").as_deref() == Ok("yes") {
            store
                .recover(&new, Some(&old), &Cancel::new(), &mut hook)
                .unwrap();
        } else {
            let objects = decode(
                &get(&root, "objects.json", manifest::MAX_MANIFEST).unwrap(),
                manifest::MAX_MANIFEST,
            )
            .unwrap();
            let mut data = download::fixture_prepared(
                &root
                    .child(
                        &format!("child-download-{}", uuid::Uuid::new_v4().simple()),
                        true,
                    )
                    .unwrap(),
                &new,
                objects,
            )
            .await
            .unwrap();
            if std::env::var("AOC_INSTALL_PREPARE_RECOVERY").as_deref() == Ok("yes") {
                store
                    .resume_preparation(&new, Some(&old), &mut data, &Cancel::new(), &mut hook)
                    .unwrap();
            } else if std::env::var("AOC_INSTALL_REPAIR").as_deref() == Ok("yes") {
                store
                    .repair(&new, &mut data, &Cancel::new(), &mut hook)
                    .unwrap();
            } else {
                store
                    .begin(&new, Some(&old), &mut data, &Cancel::new(), &mut hook)
                    .unwrap();
            }
        }
    });
}
async fn crash_case(
    boundary: &str,
) -> (
    Fixture,
    publisher::Fixture,
    VerifiedRelease,
    VerifiedRelease,
) {
    let files = Fixture::new();
    let p = publisher::Fixture::new().await;
    let old_doc = document(1, &[("mods/a.jar", b"old")]);
    let new_doc = document(2, &[("mods/a.jar", b"new")]);
    let old_transport = p.published_manifest(&old_doc, 1, false).await;
    let new_transport = p.published_manifest(&new_doc, 2, false).await;
    let old = proof(&files.root, &p.bootstrap, old_transport.clone(), 1).await;
    let new = proof(&files.root, &p.bootstrap, new_transport.clone(), 2).await;
    let mut store = Store::create(&files.root, &p.bootstrap).unwrap();
    let mut data = prepared(&files.root, &old, &[("mods/a.jar", b"old")], "download-old").await;
    store
        .begin(&old, None, &mut data, &Cancel::new(), &mut |_| {})
        .unwrap();
    drop(data);
    drop(store);
    put(&files.root, "bootstrap.json", &p.bootstrap).unwrap();
    put(
        &files.root,
        "old-transport.json",
        &encoded(&*old_transport.files).unwrap(),
    )
    .unwrap();
    put(
        &files.root,
        "new-transport.json",
        &encoded(&*new_transport.files).unwrap(),
    )
    .unwrap();
    put(
        &files.root,
        "objects.json",
        &encoded(&BTreeMap::from([(
            new_doc.files[0].source_ref.object_key.clone(),
            b"new".to_vec(),
        )]))
        .unwrap(),
    )
    .unwrap();
    let output = Command::new(std::env::current_exe().unwrap())
        .args([
            "--exact",
            "pack::ntfs::install::tests::child_install_fixture",
            "--nocapture",
        ])
        .env("AOC_INSTALL_CHILD", path(&files.root))
        .env("AOC_INSTALL_CRASH", boundary)
        .output()
        .unwrap();
    assert_eq!(output.status.code(), Some(37), "{output:?}");
    (files, p, old, new)
}
#[test]
fn corrupted_missing_unknown_journal_and_stage_never_reset_or_mutate_managed_files() {
    runtime().block_on(async {
        for corruption in ["partial", "unknown", "missing", "stage"] {
            let (files, p, old, new) = crash_case("prepared").await;
            let mut store = Store::reopen(&files.root, &p.bootstrap).unwrap();
            let tx = store.load(2).unwrap();
            match corruption {
                "partial" => std::fs::write(path(&tx.journal).join("prepared.json"), b"{").unwrap(),
                "unknown" => put(&tx.journal, "foreign.json", b"{}").unwrap(),
                "missing" => std::fs::remove_file(path(&tx.journal).join("prepared.json")).unwrap(),
                "stage" => std::fs::write(path(&tx.staged).join("00000.new"), b"bad").unwrap(),
                _ => unreachable!(),
            }
            assert!(store
                .recover(&new, Some(&old), &Cancel::new(), &mut |_| {})
                .is_err());
            assert_eq!(read(&store.instance.dir, "mods/a.jar"), b"old");
            assert!(store
                .recover(&new, Some(&old), &Cancel::new(), &mut |_| {})
                .is_err());
            if corruption == "stage" {
                assert_eq!(get(&tx.staged, "00000.new", 128).unwrap(), b"bad");
            }
        }
    });
}
#[test]
fn resealed_journal_cannot_authorize_userdata_path_without_matching_tuf_plan() {
    runtime().block_on(async {
        let (files, p, old, new) = crash_case("prepared").await;
        let mut store = Store::reopen(&files.root, &p.bootstrap).unwrap();
        let mut tx = store.load(2).unwrap();
        let saves = store.instance.dir.child("saves", true).unwrap();
        let mut world = saves.file("world.dat", true).unwrap();
        world.write_new(b"preserve world").unwrap();
        drop(world);
        tx.plan.operations[0].path = "saves/world.dat".into();
        let bytes = encoded(&tx.plan).unwrap();
        let hash = manifest::hash(&bytes);
        let mut choice: serde_json::Value =
            decode(&get(&tx.journal, "stage-choice.json", CAP).unwrap(), CAP).unwrap();
        choice["plan"] = hash.clone().into();
        std::fs::write(
            path(&tx.journal).join("stage-choice.json"),
            encoded(&choice).unwrap(),
        )
        .unwrap();
        std::fs::write(path(&tx.journal).join("plan.json"), bytes).unwrap();
        std::fs::write(
            path(&tx.journal).join("prepared.json"),
            phase_bytes(&hash, "prepared").unwrap(),
        )
        .unwrap();
        fail(
            store.recover(&new, Some(&old), &Cancel::new(), &mut |_| {}),
            "INSTALL_PLAN_INVALID",
        );
        assert_eq!(get(&saves, "world.dat", 128).unwrap(), b"preserve world");
        assert_eq!(read(&store.instance.dir, "mods/a.jar"), b"old");
    });
}
#[test]
fn ambiguous_duplicate_stage_and_foreign_managed_file_block_recovery() {
    runtime().block_on(async {
        let (files, p, old, new) = crash_case("after-install:00000").await;
        let mut store = Store::reopen(&files.root, &p.bootstrap).unwrap();
        let tx = store.load(2).unwrap();
        let mut duplicate = tx.staged.file("00000.new", true).unwrap();
        duplicate.write_new(b"new").unwrap();
        drop(duplicate);
        fail(
            store.recover(&new, Some(&old), &Cancel::new(), &mut |_| {}),
            "INSTALL_AMBIGUOUS_STATE",
        );
        assert_eq!(read(&store.instance.dir, "mods/a.jar"), b"new");
        assert_eq!(get(&tx.backup, "00000.old", 128).unwrap(), b"old");
        let (files, p, old, new) = crash_case("prepared").await;
        let mut store = Store::reopen(&files.root, &p.bootstrap).unwrap();
        let mods = store.instance.dir.child("mods", false).unwrap();
        let mut foreign = mods.file("extra.jar", true).unwrap();
        foreign.write_new(b"foreign").unwrap();
        drop(foreign);
        fail(
            store.recover(&new, Some(&old), &Cancel::new(), &mut |_| {}),
            "INSTALL_FOREIGN_OR_MODIFIED",
        );
        assert_eq!(read(&store.instance.dir, "mods/extra.jar"), b"foreign");
        assert_eq!(read(&store.instance.dir, "mods/a.jar"), b"old");
    });
}
#[test]
fn partial_preparation_wrong_trust_cache_and_expiry_preserve_old_instance() {
    runtime().block_on(async {
        let (files, p, old, mut new) = crash_case("plan-written").await;
        let mut store = Store::reopen(&files.root, &p.bootstrap).unwrap();
        fail(
            store.recover(&new, Some(&old), &Cancel::new(), &mut |_| {}),
            "INSTALL_PREPARATION_REQUIRED",
        );
        fail(
            store.recover(&old, None, &Cancel::new(), &mut |_| {}),
            "INSTALL_TRUST_MISMATCH",
        );
        new.fixture_expire();
        fail(
            store.recover(&new, Some(&old), &Cancel::new(), &mut |_| {}),
            "RELEASE_EXPIRED",
        );
        assert_eq!(read(&store.instance.dir, "mods/a.jar"), b"old");
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut store = Store::create(&files.root, &p.bootstrap).unwrap();
        let old = release(&files.root, &p, &document(1, &[("mods/a.jar", b"old")])).await;
        let new = release(&files.root, &p, &document(2, &[("mods/a.jar", b"new")])).await;
        let mut data = prepared(
            &files.root,
            &old,
            &[("mods/a.jar", b"old")],
            "wrong-download",
        )
        .await;
        fail(
            store.begin(&new, None, &mut data, &Cancel::new(), &mut |_| {}),
            "INSTALL_CACHE_MISMATCH",
        );
        assert_eq!(store.head().unwrap().last, 0);
    });
}
#[test]
fn process_exit_after_journal_and_rename_boundaries_recovers_idempotently() {
    runtime().block_on(async {
        let p = publisher::Fixture::new().await;
        let old_payload: [(&str, &[u8]); 1] = [("mods/a.jar", b"old")];
        let new_payload: [(&str, &[u8]); 1] = [("mods/a.jar", b"new")];
        let old_doc = document(1, &old_payload);
        let new_doc = document(2, &new_payload);
        let old_transport = p.published_manifest(&old_doc, 1, false).await;
        let new_transport = p.published_manifest(&new_doc, 2, false).await;
        for boundary in [
            "prepared",
            "intent:00000",
            "after-backup:00000",
            "backed:00000",
            "after-install:00000",
            "done:00000",
            "index-written",
            "index-ready",
            "committed",
        ] {
            let files = Fixture::new();
            let old = proof(&files.root, &p.bootstrap, old_transport.clone(), 1).await;
            let new = proof(&files.root, &p.bootstrap, new_transport.clone(), 2).await;
            let mut store = Store::create(&files.root, &p.bootstrap).unwrap();
            let mut data = prepared(&files.root, &old, &old_payload, "download-old").await;
            store
                .begin(&old, None, &mut data, &Cancel::new(), &mut |_| {})
                .unwrap();
            drop(data);
            drop(store);
            put(&files.root, "bootstrap.json", &p.bootstrap).unwrap();
            put(
                &files.root,
                "old-transport.json",
                &encoded(&*old_transport.files).unwrap(),
            )
            .unwrap();
            put(
                &files.root,
                "new-transport.json",
                &encoded(&*new_transport.files).unwrap(),
            )
            .unwrap();
            let objects: BTreeMap<_, _> = BTreeMap::from([(
                new_doc.files[0].source_ref.object_key.clone(),
                b"new".to_vec(),
            )]);
            put(&files.root, "objects.json", &encoded(&objects).unwrap()).unwrap();
            let child = Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "pack::ntfs::install::tests::child_install_fixture",
                    "--nocapture",
                ])
                .env("AOC_INSTALL_CHILD", path(&files.root))
                .env("AOC_INSTALL_CRASH", boundary)
                .output()
                .unwrap();
            assert_eq!(child.status.code(), Some(37), "{boundary}: {child:?}");
            if boundary == "after-backup:00000" {
                let again = Command::new(std::env::current_exe().unwrap())
                    .args([
                        "--exact",
                        "pack::ntfs::install::tests::child_install_fixture",
                        "--nocapture",
                    ])
                    .env("AOC_INSTALL_CHILD", path(&files.root))
                    .env("AOC_INSTALL_RECOVER", "yes")
                    .env("AOC_INSTALL_CRASH", "after-install:00000")
                    .output()
                    .unwrap();
                assert_eq!(again.status.code(), Some(37), "recovery crash: {again:?}");
            }
            let mut store = Store::reopen(&files.root, &p.bootstrap).unwrap();
            assert_eq!(
                store
                    .recover(&new, Some(&old), &Cancel::new(), &mut |_| {})
                    .unwrap(),
                Outcome::Committed {
                    cancellation_deferred: false
                },
                "{boundary}"
            );
            assert_eq!(read(&store.instance.dir, "mods/a.jar"), b"new");
            assert!(store.head().unwrap().pending.is_none());
            assert_eq!(
                store
                    .recover(&new, Some(&old), &Cancel::new(), &mut |_| {})
                    .unwrap(),
                Outcome::Committed {
                    cancellation_deferred: false
                }
            );
        }
    });
}
#[test]
fn case_only_reuse_and_cross_release_ancestor_conflict_are_explicit() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut store = Store::create(&files.root, &p.bootstrap).unwrap();
        let old = release(&files.root, &p, &document(1, &[("mods/a.jar", b"same")])).await;
        let mut data = prepared(
            &files.root,
            &old,
            &[("mods/a.jar", b"same")],
            "download-old",
        )
        .await;
        store
            .begin(&old, None, &mut data, &Cancel::new(), &mut |_| {})
            .unwrap();
        let new = release(&files.root, &p, &document(2, &[("mods/A.jar", b"same")])).await;
        let mut data = prepared(
            &files.root,
            &new,
            &[("mods/A.jar", b"same")],
            "download-new",
        )
        .await;
        store
            .begin(&new, Some(&old), &mut data, &Cancel::new(), &mut |_| {})
            .unwrap();
        assert!(store
            .load(2)
            .unwrap()
            .backup
            .entries(1, Instant::now() + Duration::from_secs(1))
            .unwrap()
            .is_empty());
        assert_eq!(read(&store.instance.dir, "mods/a.jar"), b"same");
        let nested = release(
            &files.root,
            &p,
            &document(3, &[("mods/a.jar/child.jar", b"nested")]),
        )
        .await;
        let mut data = prepared(
            &files.root,
            &nested,
            &[("mods/a.jar/child.jar", b"nested")],
            "download-nested",
        )
        .await;
        fail(
            store.begin(&nested, Some(&new), &mut data, &Cancel::new(), &mut |_| {}),
            "INSTALL_PATH_CONFLICT",
        );
        assert_eq!(store.head().unwrap().last, 2);
    });
}
