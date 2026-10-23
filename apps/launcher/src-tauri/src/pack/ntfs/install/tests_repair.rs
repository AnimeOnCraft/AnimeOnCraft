// Included inside install::tests; all paths/public publisher data remain parent-owned fixtures.
#[test]
fn preparation_process_exits_resume_in_same_intent_preserving_old_copies() {
    runtime().block_on(async {
        for phase in [
            "plan-written",
            "stage-created:00000:000",
            "stage-chunk:00000:000",
            "stage-verified:00000:000",
            "stage-choice",
        ] {
            let (files, p, old, new) = crash_case(phase).await;
            let mut store = Store::reopen(&files.root, &p.bootstrap).unwrap();
            let tx = store.load(2).unwrap();
            let old_stage = flat_file(&tx.staged, "00000.new").unwrap().map(|mut f| {
                let mut bytes = Vec::new();
                f.file.read_to_end(&mut bytes).unwrap();
                bytes
            });
            let mut data = prepared(
                &files.root,
                &new,
                &[("mods/a.jar", b"new")],
                "resume-download",
            )
            .await;
            store
                .resume_preparation(&new, Some(&old), &mut data, &Cancel::new(), &mut |_| {})
                .unwrap();
            assert_eq!(store.head().unwrap().last, 2);
            assert_eq!(read(&store.instance.dir, "mods/a.jar"), b"new");
            if let Some(bytes) = old_stage {
                if phase != "stage-choice" {
                    assert_eq!(get(&tx.staged, "00000.new", 128).unwrap(), bytes);
                }
            }
            store
                .recover(&new, Some(&old), &Cancel::new(), &mut |_| {})
                .unwrap();
        }
    });
}
#[test]
fn preparation_resume_can_itself_crash_and_partial_bytes_are_not_overwritten() {
    runtime().block_on(async {
        let (files, p, old, new) = crash_case("stage-created:00000:000").await;
        let store = Store::reopen(&files.root, &p.bootstrap).unwrap();
        let tx = store.load(2).unwrap();
        std::fs::write(path(&tx.staged).join("00000.new"), b"partial bytes").unwrap();
        drop(tx);
        drop(store);
        let child = Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "pack::ntfs::install::tests::child_install_fixture",
                "--nocapture",
            ])
            .env("AOC_INSTALL_CHILD", path(&files.root))
            .env("AOC_INSTALL_PREPARE_RECOVERY", "yes")
            .env("AOC_INSTALL_CRASH", "stage-created:00000:001")
            .output()
            .unwrap();
        assert_eq!(child.status.code(), Some(37), "{child:?}");
        let mut store = Store::reopen(&files.root, &p.bootstrap).unwrap();
        let mut data = prepared(
            &files.root,
            &new,
            &[("mods/a.jar", b"new")],
            "resume-download",
        )
        .await;
        store
            .resume_preparation(&new, Some(&old), &mut data, &Cancel::new(), &mut |_| {})
            .unwrap();
        let tx = store.load(2).unwrap();
        assert_eq!(get(&tx.staged, "00000.new", 128).unwrap(), b"partial bytes");
        assert_eq!(get(&tx.staged, "00000-001.new", 128).unwrap(), b"");
        assert_eq!(read(&store.instance.dir, "mods/a.jar"), b"new");
    });
}
#[test]
fn preparation_changed_inventory_choice_corruption_and_retry_limit_are_blocked() {
    runtime().block_on(async {
        for kind in ["changed", "choice", "limit", "cancel"] {
            let (files, p, old, new) = crash_case(if kind == "choice" {
                "stage-choice"
            } else {
                "plan-written"
            })
            .await;
            let mut store = Store::reopen(&files.root, &p.bootstrap).unwrap();
            let tx = store.load(2).unwrap();
            let cancel = Cancel::new();
            match kind {
                "changed" => {
                    std::fs::write(path(&store.instance.dir).join("mods/a.jar"), b"user change")
                        .unwrap()
                }
                "choice" => {
                    std::fs::write(path(&tx.journal).join("stage-choice.json"), b"{").unwrap()
                }
                "limit" => {
                    for a in 0..preparation::ATTEMPTS {
                        let mut f = tx.staged.file(&preparation::filename(0, a), true).unwrap();
                        f.write_new(b"partial").unwrap();
                    }
                }
                "cancel" => cancel.cancel(),
                _ => unreachable!(),
            }
            let mut data = prepared(
                &files.root,
                &new,
                &[("mods/a.jar", b"new")],
                "resume-download",
            )
            .await;
            let result =
                store.resume_preparation(&new, Some(&old), &mut data, &cancel, &mut |_| {});
            match kind {
                "changed" => fail(result, "INSTALL_PREPARATION_CHANGED"),
                "choice" => assert!(result.is_err()),
                "limit" => fail(result, "INSTALL_PREPARATION_LIMIT"),
                "cancel" => assert_eq!(result.unwrap(), Outcome::Cancelled),
                _ => unreachable!(),
            }
            assert_eq!(
                read(&store.instance.dir, "mods/a.jar"),
                if kind == "changed" {
                    b"user change".as_slice()
                } else {
                    b"old".as_slice()
                }
            );
        }
    });
}
#[test]
fn repair_restores_missing_and_modified_indexed_files_preserves_changes_and_userdata() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut store = Store::create(&files.root, &p.bootstrap).unwrap();
        let payload: [(&str, &[u8]); 3] = [
            ("mods/a.jar", b"correct a"),
            ("mods/b.jar", b"correct b"),
            ("libraries/keep.jar", b"keep"),
        ];
        let release = release(&files.root, &p, &document(1, &payload)).await;
        let mut data = prepared(&files.root, &release, &payload, "download").await;
        store
            .begin(&release, None, &mut data, &Cancel::new(), &mut |_| {})
            .unwrap();
        std::fs::write(
            path(&store.instance.dir).join("mods/a.jar"),
            b"user modified a",
        )
        .unwrap();
        std::fs::remove_file(path(&store.instance.dir).join("mods/b.jar")).unwrap();
        let saves = store.instance.dir.child("saves", true).unwrap();
        let mut world = saves.file("world.dat", true).unwrap();
        world.write_new(b"user world").unwrap();
        drop(world);
        fail(
            store.begin(
                &release,
                Some(&release),
                &mut data,
                &Cancel::new(),
                &mut |_| {},
            ),
            "INSTALL_FOREIGN_OR_MODIFIED",
        );
        store
            .repair(&release, &mut data, &Cancel::new(), &mut |_| {})
            .unwrap();
        assert_eq!(read(&store.instance.dir, "mods/a.jar"), b"correct a");
        assert_eq!(read(&store.instance.dir, "mods/b.jar"), b"correct b");
        assert_eq!(get(&saves, "world.dat", 128).unwrap(), b"user world");
        let tx = store.load(2).unwrap();
        assert_eq!(
            get(&tx.backup, "00001.old", 128).unwrap(),
            b"user modified a"
        );
        store
            .recover(&release, Some(&release), &Cancel::new(), &mut |_| {})
            .unwrap();
        store
            .repair(&release, &mut data, &Cancel::new(), &mut |_| {})
            .unwrap();
        assert!(names(&store.load(3).unwrap().backup, 1).unwrap().is_empty());
    });
}
#[test]
fn repair_foreign_no_index_wrong_release_and_cancel_preserve_everything() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let mut store = Store::create(&files.root, &p.bootstrap).unwrap();
        let release = release(&files.root, &p, &document(1, &[("mods/a.jar", b"good")])).await;
        let mut data = prepared(
            &files.root,
            &release,
            &[("mods/a.jar", b"good")],
            "download",
        )
        .await;
        fail(
            store.repair(&release, &mut data, &Cancel::new(), &mut |_| {}),
            "REPAIR_INDEX_REQUIRED",
        );
        store
            .begin(&release, None, &mut data, &Cancel::new(), &mut |_| {})
            .unwrap();
        std::fs::write(path(&store.instance.dir).join("mods/a.jar"), b"modified").unwrap();
        let cancel = Cancel::new();
        let signal = cancel.clone();
        assert_eq!(
            store
                .repair(
                    &release,
                    &mut data,
                    &cancel,
                    &mut |phase| if phase == "prepared" {
                        signal.cancel()
                    }
                )
                .unwrap(),
            Outcome::Cancelled
        );
        assert_eq!(read(&store.instance.dir, "mods/a.jar"), b"modified");
        let mods = store.instance.dir.child("mods", false).unwrap();
        let mut foreign = mods.file("extra.jar", true).unwrap();
        foreign.write_new(b"foreign").unwrap();
        drop(foreign);
        fail(
            store.repair(&release, &mut data, &Cancel::new(), &mut |_| {}),
            "INSTALL_FOREIGN_OR_MODIFIED",
        );
        assert_eq!(read(&store.instance.dir, "mods/extra.jar"), b"foreign");
        let newer =
            super::tests::release(&files.root, &p, &document(2, &[("mods/a.jar", b"new")])).await;
        assert!(repair::operations(&newer, Some(&release), &BTreeMap::new()).is_err());
    });
}
#[test]
fn repair_process_exit_preserves_modified_backup_then_recovers_idempotently() {
    runtime().block_on(async {
        for phase in ["after-backup:00000", "after-install:00000", "index-ready"] {
            let files = Fixture::new();
            let p = publisher::Fixture::new().await;
            let doc = document(1, &[("mods/a.jar", b"good")]);
            let transport = p.published_manifest(&doc, 1, false).await;
            let release = proof(&files.root, &p.bootstrap, transport.clone(), 1).await;
            let mut store = Store::create(&files.root, &p.bootstrap).unwrap();
            let mut data = prepared(
                &files.root,
                &release,
                &[("mods/a.jar", b"good")],
                "download",
            )
            .await;
            store
                .begin(&release, None, &mut data, &Cancel::new(), &mut |_| {})
                .unwrap();
            drop(data);
            std::fs::write(
                path(&store.instance.dir).join("mods/a.jar"),
                b"preserved modification",
            )
            .unwrap();
            drop(store);
            put(&files.root, "bootstrap.json", &p.bootstrap).unwrap();
            put(
                &files.root,
                "old-transport.json",
                &encoded(&*transport.files).unwrap(),
            )
            .unwrap();
            put(
                &files.root,
                "new-transport.json",
                &encoded(&*transport.files).unwrap(),
            )
            .unwrap();
            put(
                &files.root,
                "objects.json",
                &encoded(&BTreeMap::from([(
                    doc.files[0].source_ref.object_key.clone(),
                    b"good".to_vec(),
                )]))
                .unwrap(),
            )
            .unwrap();
            let child = Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "pack::ntfs::install::tests::child_install_fixture",
                    "--nocapture",
                ])
                .env("AOC_INSTALL_CHILD", path(&files.root))
                .env("AOC_INSTALL_OLD_SEQUENCE", "1")
                .env("AOC_INSTALL_NEW_SEQUENCE", "1")
                .env("AOC_INSTALL_REPAIR", "yes")
                .env("AOC_INSTALL_CRASH", phase)
                .output()
                .unwrap();
            assert_eq!(child.status.code(), Some(37), "{child:?}");
            let mut store = Store::reopen(&files.root, &p.bootstrap).unwrap();
            store
                .recover(&release, Some(&release), &Cancel::new(), &mut |_| {})
                .unwrap();
            assert_eq!(read(&store.instance.dir, "mods/a.jar"), b"good");
            assert_eq!(
                get(&store.load(2).unwrap().backup, "00000.old", 128).unwrap(),
                b"preserved modification"
            );
            store
                .recover(&release, Some(&release), &Cancel::new(), &mut |_| {})
                .unwrap();
        }
    });
}
