use super::*;
#[test]
fn updater_stage_is_private_immutable_bounded_and_never_adopts_unknown_data() {
    let fixture = super::super::tests::Fixture::new();
    let mut first = Stage::create(&fixture.root, &[0x51; 1024]).unwrap();
    let path = first.checked_path().unwrap().to_owned();
    assert!(std::fs::OpenOptions::new().write(true).open(&path).is_err());
    assert_eq!(std::fs::read(&path).unwrap(), vec![0x51; 1024]);
    let foreign = path.parent().unwrap().join("foreign.txt");
    std::fs::write(&foreign, "preserved").unwrap();
    assert_eq!(first.checked_path().err(), Some("UPDATE_STAGE_UNAVAILABLE"));
    assert_eq!(std::fs::read_to_string(&foreign).unwrap(), "preserved");
    let _second = Stage::create(&fixture.root, &[0x52; 1024]).unwrap();
    assert_eq!(
        Stage::create(&fixture.root, &[0x53; 1024]).err(),
        Some("UPDATE_STAGE_UNAVAILABLE")
    );
    assert_eq!(std::fs::read(&path).unwrap(), vec![0x51; 1024]);
}

#[test]
fn refusal_drop_removes_only_its_created_stage_and_releases_the_orphan_budget() {
    let fixture = super::super::tests::Fixture::new();
    let foreign = fixture.root.file("foreign.txt", true).unwrap();
    for _ in 0..8 {
        let mut stage = Stage::create(&fixture.root, &[0x51; 1024]).unwrap();
        let path = stage.checked_path().unwrap().to_owned();
        let directory = path.parent().unwrap().to_owned();
        assert!(directory.exists());
        drop(stage);
        assert!(!path.exists());
        assert!(!directory.exists());
        assert!(foreign.unchanged().is_ok());
    }
}

#[test]
fn handed_off_stage_survives_drop_and_is_never_adopted_by_a_later_attempt() {
    let fixture = super::super::tests::Fixture::new();
    let mut paths = Vec::new();
    for _ in 0..2 {
        let mut stage = Stage::create(&fixture.root, &[0x52; 1024]).unwrap();
        paths.push(stage.checked_path().unwrap().to_owned());
        stage.handed_off();
        drop(stage);
    }
    assert_eq!(
        Stage::create(&fixture.root, &[0x53; 1024]).err(),
        Some("UPDATE_STAGE_UNAVAILABLE")
    );
    for path in paths {
        assert_eq!(std::fs::read(path).unwrap(), vec![0x52; 1024]);
    }
}

#[test]
fn cleanup_refuses_foreign_children_and_preserves_every_byte() {
    let fixture = super::super::tests::Fixture::new();
    let mut stage = Stage::create(&fixture.root, &[0x54; 1024]).unwrap();
    let path = stage.checked_path().unwrap().to_owned();
    let directory = path.parent().unwrap();
    let marker = directory.join("aoc-instance.json");
    let original_marker = std::fs::read(&marker).unwrap();
    assert!(std::fs::OpenOptions::new()
        .write(true)
        .open(&marker)
        .is_err());
    let foreign = directory.join("foreign");
    std::fs::create_dir(&foreign).unwrap();
    std::fs::write(foreign.join("world.dat"), b"preserved").unwrap();
    assert!(stage.cleanup().is_err());
    drop(stage);
    assert_eq!(std::fs::read(path).unwrap(), vec![0x54; 1024]);
    assert_eq!(std::fs::read(marker).unwrap(), original_marker);
    assert_eq!(
        std::fs::read(foreign.join("world.dat")).unwrap(),
        b"preserved"
    );
}

#[test]
fn unsuccessful_create_process_does_not_disarm_cleanup() {
    let fixture = super::super::tests::Fixture::new();
    let mut stage = Stage::create(&fixture.root, &[0x55; 1024]).unwrap();
    let path = stage.checked_path().unwrap().to_owned();
    let directory = path.parent().unwrap().to_owned();
    // Deliberately not a PE executable: Windows refuses before any child can run.
    assert!(std::process::Command::new(&path).spawn().is_err());
    drop(stage);
    assert!(!directory.exists());
}

#[test]
fn added_hard_link_invalidates_cleanup_without_deleting_either_name() {
    let fixture = super::super::tests::Fixture::new();
    let mut stage = Stage::create(&fixture.root, &[0x56; 1024]).unwrap();
    let path = stage.checked_path().unwrap().to_owned();
    let alias = path
        .parent()
        .unwrap()
        .parent()
        .unwrap()
        .join("foreign-alias.exe");
    std::fs::hard_link(&path, &alias).unwrap();
    assert!(stage.cleanup().is_err());
    drop(stage);
    assert_eq!(std::fs::read(path).unwrap(), vec![0x56; 1024]);
    assert_eq!(std::fs::read(alias).unwrap(), vec![0x56; 1024]);
}
