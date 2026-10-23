use super::super::{bridge::Bridge, tests::Fixture};
use super::*;
use crate::pack::{
    local::Status,
    tests::{self as publisher, runtime},
};
use std::{
    collections::BTreeMap,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
};

const NAMESPACE: &str = "AnimeOnCraft.InstallDevelopment.v1";
fn metadata(files: &Fixture) -> BTreeMap<&'static str, Vec<u8>> {
    [
        "aoc-instance.json",
        "namespace.json",
        "lease.lock",
        "ready.json",
        "control/aoc-instance.json",
        "instance/aoc-instance.json",
        "cache/aoc-instance.json",
    ]
    .into_iter()
    .map(|name| {
        (
            name,
            std::fs::read(files.path().join(NAMESPACE).join(name)).unwrap(),
        )
    })
    .collect()
}
async fn proof(
    files: &Fixture,
    p: &publisher::Fixture,
    document: manifest::Manifest,
) -> VerifiedRelease {
    let parent = files
        .root
        .child(&format!("proof-{}", uuid::Uuid::new_v4().simple()), true)
        .unwrap();
    let mut bridge = Bridge::create(&parent, &p.bootstrap).unwrap();
    let mut policy = publisher::policy(document.release_sequence);
    policy.pack_id = document.pack_id.clone();
    policy.channel = document.channel.clone();
    bridge
        .refresh(
            &p.bootstrap,
            p.published_manifest(&document, document.release_sequence, false)
                .await,
            &policy,
            false,
        )
        .await
        .unwrap()
}
fn session(files: &Fixture, bootstrap: &[u8]) -> Session {
    Session::open(Arc::new(LocalInstance::open(files.path())), bootstrap).unwrap()
}
fn error<T>(result: Result<T>, expected: &str) {
    match result {
        Err(code) => assert_eq!(code, expected),
        Ok(_) => panic!("unexpected preflight acceptance"),
    }
}

#[test]
fn worker_owns_lease_and_verified_proofs_until_actual_callback_finishes() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let local = Arc::new(LocalInstance::open(files.path()));
        let worker = Session::open(local.clone(), &p.bootstrap).unwrap();
        let old = proof(&files, &p, publisher::document(1)).await;
        let new = proof(&files, &p, publisher::document(2)).await;
        let expected = new.manifest().digest().to_owned();
        let permit = worker.bind(new, Some(old)).unwrap();
        let before = metadata(&files);
        let userdata = files.path().join(NAMESPACE).join("instance/saves");
        std::fs::create_dir(&userdata).unwrap();
        std::fs::write(userdata.join("world.dat"), b"user world").unwrap();
        let (entered, entry) = mpsc::channel();
        let (release, wait) = mpsc::channel();
        let job = std::thread::spawn(move || {
            permit.run(|new, old| {
                assert_eq!(new.manifest().digest(), expected);
                assert_eq!(old.unwrap().manifest().manifest().release_sequence, 1);
                entered.send(()).unwrap();
                wait.recv_timeout(std::time::Duration::from_secs(10))
                    .unwrap();
                Ok(new.manifest().manifest().release_sequence)
            })
        });
        entry
            .recv_timeout(std::time::Duration::from_secs(10))
            .unwrap();
        drop(local);
        let status = LocalInstance::open(files.path()).snapshot().status;
        // Always release/join our worker before an assertion can unwind fixture cleanup.
        release.send(()).unwrap();
        assert_eq!(job.join().unwrap(), Ok(2));
        assert_eq!(status, Status::Busy);
        assert_eq!(
            LocalInstance::open(files.path()).snapshot().status,
            Status::Ready
        );
        assert_eq!(metadata(&files), before);
        assert_eq!(
            std::fs::read(userdata.join("world.dat")).unwrap(),
            b"user world"
        );
    });
}

#[test]
fn binding_is_stable_after_reopen_but_differs_for_other_roots_and_bootstraps() {
    runtime().block_on(async {
        let files = Fixture::new();
        let other = Fixture::new();
        let p = publisher::Fixture::new().await;
        let second = publisher::Fixture::new().await;
        let worker = session(&files, &p.bootstrap);
        let binding = worker.binding.clone();
        assert_ne!(session(&other, &p.bootstrap).binding, binding);
        assert_ne!(
            Session::open(worker.local.clone(), &second.bootstrap)
                .unwrap()
                .binding,
            binding
        );
        drop(worker);
        assert_eq!(session(&files, &p.bootstrap).binding, binding);
    });
}

#[test]
fn busy_blocked_and_malformed_bootstrap_do_not_create_or_adopt_a_backend() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let local = Arc::new(LocalInstance::open(files.path()));
        let before = metadata(&files);
        error(
            Session::open(Arc::new(LocalInstance::open(files.path())), &p.bootstrap),
            "LOCAL_STORE_BUSY",
        );
        assert!(Session::open(local.clone(), b"{}").is_err());
        error(
            Session::open(Arc::new(LocalInstance::unavailable()), &p.bootstrap),
            "LOCAL_STORE_BLOCKED",
        );
        assert_eq!(metadata(&files), before);
        assert_eq!(local.snapshot().status, Status::Ready);
        assert_eq!(
            std::fs::read_dir(files.path().join(NAMESPACE).join("control"))
                .unwrap()
                .count(),
            1
        );
        assert_eq!(
            std::fs::read_dir(files.path().join(NAMESPACE).join("cache"))
                .unwrap()
                .count(),
            1
        );
    });
}

#[test]
fn foreign_anchor_pack_channel_sequence_and_resealed_equal_sequence_are_refused() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let foreign = publisher::Fixture::new().await;
        let local = Arc::new(LocalInstance::open(files.path()));
        let before = metadata(&files);
        for kind in [
            "new-anchor",
            "old-anchor",
            "pack",
            "channel",
            "sequence",
            "equal-digest",
        ] {
            let new = publisher::document(if kind == "sequence" { 1 } else { 2 });
            let mut old = publisher::document(2);
            match kind {
                "pack" => old.pack_id = "other-pack".into(),
                "channel" => old.channel = "beta".into(),
                "equal-digest" => old.files[0].sha256 = manifest::hash(b"different bytes"),
                _ => {}
            }
            let new = proof(
                &files,
                if kind == "new-anchor" { &foreign } else { &p },
                new,
            )
            .await;
            let old = proof(
                &files,
                if kind == "old-anchor" { &foreign } else { &p },
                old,
            )
            .await;
            let worker = Session::open(local.clone(), &p.bootstrap).unwrap();
            error(
                worker.bind(new, Some(old)),
                if kind.ends_with("anchor") {
                    "LOCAL_TRUST_MISMATCH"
                } else {
                    "LOCAL_RELEASE_MISMATCH"
                },
            );
            assert_eq!(metadata(&files), before);
        }
    });
}

#[test]
fn expiry_after_binding_refuses_callback_but_old_proof_does_not_set_new_expiry() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let local = Arc::new(LocalInstance::open(files.path()));
        let new = proof(&files, &p, publisher::document(2)).await;
        let mut old = proof(&files, &p, publisher::document(1)).await;
        old.fixture_expire();
        let permit = Session::open(local.clone(), &p.bootstrap)
            .unwrap()
            .bind(new, Some(old))
            .unwrap();
        assert_eq!(permit.run(|_, _| Ok(())), Ok(()));
        let new = proof(&files, &p, publisher::document(3)).await;
        let mut permit = Session::open(local, &p.bootstrap)
            .unwrap()
            .bind(new, None)
            .unwrap();
        permit.new.fixture_expire();
        let called = AtomicBool::new(false);
        error(
            permit.run(|_, _| {
                called.store(true, Ordering::Relaxed);
                Ok(())
            }),
            "RELEASE_EXPIRED",
        );
        assert!(!called.load(Ordering::Relaxed));
        assert_eq!(
            LocalInstance::open(files.path()).snapshot().status,
            Status::Ready
        );
    });
}

#[test]
fn changed_lease_after_binding_refuses_callback_and_preserves_metadata() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let local = Arc::new(LocalInstance::open(files.path()));
        let new = proof(&files, &p, publisher::document(1)).await;
        let permit = Session::open(local.clone(), &p.bootstrap)
            .unwrap()
            .bind(new, None)
            .unwrap();
        let before = metadata(&files);
        std::fs::hard_link(
            files.path().join(NAMESPACE).join("lease.lock"),
            files.path().join("before-work-link"),
        )
        .unwrap();
        let called = AtomicBool::new(false);
        error(
            permit.run(|_, _| {
                called.store(true, Ordering::Relaxed);
                Ok(())
            }),
            "LOCAL_STORE_BLOCKED",
        );
        assert!(!called.load(Ordering::Relaxed));
        assert_eq!(metadata(&files), before);
        assert_eq!(local.snapshot().status, Status::Blocked);
        assert_eq!(
            LocalInstance::open(files.path()).snapshot().status,
            Status::Busy
        );
    });
}

#[test]
fn callback_failure_is_revalidated_before_the_owner_is_released() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let local = Arc::new(LocalInstance::open(files.path()));
        let new = proof(&files, &p, publisher::document(1)).await;
        let permit = Session::open(local.clone(), &p.bootstrap)
            .unwrap()
            .bind(new, None)
            .unwrap();
        error(
            permit.run::<()>(|_, _| {
                std::fs::hard_link(
                    files.path().join(NAMESPACE).join("lease.lock"),
                    files.path().join("failed-work-link"),
                )
                .unwrap();
                Err("WORK_FAILED")
            }),
            "LOCAL_STORE_BLOCKED",
        );
        assert_eq!(local.snapshot().status, Status::Blocked);
        assert_eq!(
            LocalInstance::open(files.path()).snapshot().status,
            Status::Busy
        );
        drop(local);
        assert_eq!(
            LocalInstance::open(files.path()).snapshot().status,
            Status::Blocked
        );
    });
}

#[test]
fn post_callback_corruption_keeps_external_holder_blocked_with_lease_retained() {
    runtime().block_on(async {
        let files = Fixture::new();
        let p = publisher::Fixture::new().await;
        let local = Arc::new(LocalInstance::open(files.path()));
        let new = proof(&files, &p, publisher::document(1)).await;
        let permit = Session::open(local.clone(), &p.bootstrap)
            .unwrap()
            .bind(new, None)
            .unwrap();
        let before = metadata(&files);
        error(
            permit.run(|_, _| {
                std::fs::hard_link(
                    files.path().join(NAMESPACE).join("lease.lock"),
                    files.path().join("test-link"),
                )
                .unwrap();
                Ok(())
            }),
            "LOCAL_STORE_BLOCKED",
        );
        assert_eq!(local.snapshot().status, Status::Blocked);
        assert_eq!(
            LocalInstance::open(files.path()).snapshot().status,
            Status::Busy
        );
        assert_eq!(metadata(&files), before);
        drop(local);
        assert_eq!(
            LocalInstance::open(files.path()).snapshot().status,
            Status::Blocked
        );
        assert_eq!(
            std::fs::read(files.path().join("test-link")).unwrap(),
            before["lease.lock"]
        );
    });
}
