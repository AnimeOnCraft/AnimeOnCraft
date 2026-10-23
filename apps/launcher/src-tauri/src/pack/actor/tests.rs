use super::*;
use std::{
    sync::mpsc,
    time::{Duration, Instant},
};
pub(crate) fn finish(actor: &Actor) -> Snapshot {
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        let s = actor.snapshot().unwrap();
        if !s.busy {
            return s;
        }
        assert!(Instant::now() < deadline, "worker timed out");
        std::thread::sleep(Duration::from_millis(5));
    }
}
fn summary(mode: PlanMode) -> PlanSummary {
    PlanSummary {
        mode,
        install: 1,
        replace: 0,
        remove: 0,
        reuse: 0,
        staging_bytes: 10,
        required_space: 64 * 1024 * 1024 + 10,
    }
}
fn fixture(worker: impl Fn(Input, Context) -> Outcome + Send + Sync + 'static) -> Actor {
    Actor::fixture(false, move |input, ctx| match input {
        Input::Install {} => Outcome::Plan(summary(PlanMode::Install)),
        Input::Repair {} => Outcome::Plan(summary(PlanMode::Repair)),
        _ => worker(input, ctx),
    })
}
fn start(actor: &Actor) -> Snapshot {
    actor.action(Input::Install {}).unwrap();
    let plan_id = finish(actor).plan.unwrap().plan_id;
    actor.action(Input::Confirm { plan_id }).unwrap()
}
#[test]
fn production_has_no_install_authority() {
    let a = Actor::new();
    assert_eq!(a.snapshot().unwrap().status, Status::Unavailable);
    for input in [
        Input::Install {},
        Input::Repair {},
        Input::Recover {},
        Input::Confirm { plan_id: Id::new() },
        Input::Discard { plan_id: Id::new() },
    ] {
        assert_eq!(a.action(input), Err("RELEASE_NOT_CONFIGURED"));
    }
    a.action(Input::Check {}).unwrap();
    assert_eq!(finish(&a).reason, Some(Reason::ReleaseNotConfigured));
}
#[test]
fn launcher_update_reservation_excludes_workers_plans_dirty_state_and_other_updates() {
    let a = Actor::new();
    let holder = a.reserve_update().unwrap();
    assert!(holder.validate().is_ok());
    assert_eq!(a.reserve_update().err(), Some("UPDATE_GATE_CLOSED"));
    assert_eq!(a.action(Input::Check {}), Err("LAUNCHER_UPDATE_PENDING"));
    drop(holder);
    assert!(a.reserve_update().is_ok());
    for startup in [Startup::RecoveryRequired, Startup::Blocked] {
        let a = Actor::fixture_startup(startup, |_, _| Outcome::Completed);
        assert_eq!(a.reserve_update().err(), Some("UPDATE_GATE_CLOSED"));
    }
    let a = fixture(|_, _| Outcome::Completed);
    a.action(Input::Install {}).unwrap();
    let plan = finish(&a).plan.unwrap();
    assert_eq!(a.reserve_update().err(), Some("UPDATE_GATE_CLOSED"));
    a.action(Input::Discard {
        plan_id: plan.plan_id,
    })
    .unwrap();
    assert!(a.reserve_update().is_ok());
    let (entered_tx, entered_rx) = mpsc::channel();
    let (release_tx, release_rx) = mpsc::channel();
    let release_rx = std::sync::Mutex::new(release_rx);
    let a = Actor::fixture(false, move |_, _| {
        entered_tx.send(()).unwrap();
        release_rx.lock().unwrap().recv().unwrap();
        Outcome::Completed
    });
    a.action(Input::Check {}).unwrap();
    entered_rx.recv_timeout(Duration::from_secs(5)).unwrap();
    assert_eq!(a.reserve_update().err(), Some("UPDATE_GATE_CLOSED"));
    release_tx.send(()).unwrap();
    finish(&a);
    assert!(a.reserve_update().is_ok());
}
#[test]
fn racing_pack_worker_and_update_reservation_cannot_both_enter() {
    for _ in 0..32 {
        let barrier = Arc::new(std::sync::Barrier::new(3));
        let release = Arc::new(std::sync::Barrier::new(2));
        let worker_release = release.clone();
        let a = Arc::new(Actor::fixture(false, move |_, _| {
            worker_release.wait();
            Outcome::Completed
        }));
        let update_actor = a.clone();
        let update_barrier = barrier.clone();
        let updater = std::thread::spawn(move || {
            update_barrier.wait();
            update_actor.reserve_update()
        });
        let pack_actor = a.clone();
        let pack_barrier = barrier.clone();
        let pack = std::thread::spawn(move || {
            pack_barrier.wait();
            pack_actor.action(Input::Check {})
        });
        barrier.wait();
        let holder = updater.join().unwrap();
        let started = pack.join().unwrap();
        assert_ne!(holder.is_ok(), started.is_ok());
        if started.is_ok() {
            release.wait();
            finish(&a);
        } else {
            drop(holder);
        }
        assert!(a.reserve_update().is_ok());
    }
}
#[test]
fn wire_input_rejects_arbitrary_capabilities() {
    for json in [
        r#"{"action":"install","path":"F:/world"}"#,
        r#"{"action":"check","url":"https://evil"}"#,
        r#"{"action":"cancel","job_id":1,"force":true}"#,
        r#"{"action":"cancel","job_id":-1}"#,
        r#"{"action":"launch"}"#,
        r#"{"action":"confirm","plan_id":"../../saves"}"#,
        r#"{"action":"discard","plan_id":"00000000000000000000000000000000","path":"F:/world"}"#,
        r#"{"action":"cancel","session_id":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","job_id":1}"#,
    ] {
        assert!(serde_json::from_str::<Input>(json).is_err(), "{json}");
    }
    assert_eq!(
        serde_json::from_str::<Input>(r#"{"action":"check"}"#).unwrap(),
        Input::Check {}
    );
}
#[test]
fn confirmation_is_single_use_discardable_and_does_not_survive_restart() {
    let a = fixture(|_, _| Outcome::Completed);
    assert_eq!(
        a.action(Input::Confirm { plan_id: Id::new() }),
        Err("STALE_PLAN")
    );
    a.action(Input::Install {}).unwrap();
    let first = finish(&a).plan.unwrap();
    assert_eq!(
        a.action(Input::Repair {}),
        Err("PLAN_CONFIRMATION_REQUIRED")
    );
    assert_eq!(
        a.action(Input::Discard { plan_id: Id::new() }),
        Err("STALE_PLAN")
    );
    a.action(Input::Discard {
        plan_id: first.plan_id,
    })
    .unwrap();
    a.action(Input::Install {}).unwrap();
    let second = finish(&a).plan.unwrap();
    assert_ne!(first.plan_id, second.plan_id);
    assert_eq!(
        a.action(Input::Confirm {
            plan_id: first.plan_id
        }),
        Err("STALE_PLAN")
    );
    let restarted = fixture(|_, _| Outcome::Completed);
    assert_ne!(
        a.snapshot().unwrap().session_id,
        restarted.snapshot().unwrap().session_id
    );
    assert_eq!(
        restarted.action(Input::Confirm {
            plan_id: second.plan_id
        }),
        Err("STALE_PLAN")
    );
    a.action(Input::Confirm {
        plan_id: second.plan_id,
    })
    .unwrap();
    finish(&a);
    assert_eq!(
        a.action(Input::Confirm {
            plan_id: second.plan_id
        }),
        Err("STALE_PLAN")
    );
}
#[test]
fn cancelled_preview_never_issues_confirmation_and_signal_keeps_request() {
    let (entered, received) = mpsc::channel();
    let (go, wait) = mpsc::channel();
    let wait = Mutex::new(wait);
    let a = Actor::fixture(false, move |_, ctx| {
        let signal = ctx.cancel_signal();
        entered.send(()).unwrap();
        wait.lock().unwrap().recv().unwrap();
        assert!(*signal.borrow());
        Outcome::Plan(summary(PlanMode::Install))
    });
    let job = a.action(Input::Install {}).unwrap();
    received.recv_timeout(Duration::from_secs(2)).unwrap();
    assert_eq!(
        a.action(Input::Cancel {
            session_id: Id::new(),
            job_id: job.job_id
        }),
        Err("STALE_JOB")
    );
    a.action(Input::Cancel {
        session_id: job.session_id,
        job_id: job.job_id,
    })
    .unwrap();
    go.send(()).unwrap();
    let result = finish(&a);
    assert_eq!(result.status, Status::Cancelled);
    assert!(result.plan.is_none());
}
#[test]
fn invalid_summary_or_wrong_worker_stage_cannot_authorize_apply() {
    for kind in ["wrong-mode", "overflow", "bytes", "completed"] {
        let a = Actor::fixture(false, move |_, ctx| {
            assert_eq!(
                ctx.progress(Status::Downloading, 0, 1),
                Err("INVALID_PROGRESS")
            );
            if kind == "completed" {
                return Outcome::Completed;
            }
            let mut plan = summary(PlanMode::Install);
            match kind {
                "wrong-mode" => plan.mode = PlanMode::Repair,
                "overflow" => plan.reuse = u32::MAX,
                "bytes" => plan.staging_bytes = u64::MAX,
                _ => unreachable!(),
            }
            Outcome::Plan(plan)
        });
        a.action(Input::Install {}).unwrap();
        let state = finish(&a);
        assert_eq!(state.status, Status::RecoveryRequired);
        assert!(state.plan.is_none());
    }
}
#[test]
fn recovery_is_not_cleared_by_a_new_preview_or_stale_plan_result() {
    for kind in ["plan", "changed", "cancelled", "unavailable"] {
        let a = Actor::fixture(true, move |_, _| match kind {
            "plan" => Outcome::Plan(summary(PlanMode::Install)),
            "changed" => Outcome::PlanChanged,
            "cancelled" => Outcome::Cancelled,
            _ => Outcome::Unavailable,
        });
        a.action(Input::Recover {}).unwrap();
        let state = finish(&a);
        assert_eq!(state.status, Status::RecoveryRequired);
        assert!(state.plan.is_none());
        assert_eq!(a.action(Input::Install {}), Err("RECOVERY_REQUIRED"));
    }
}
#[test]
fn serializes_and_rejects_stale_cancel() {
    let (entered_tx, entered_rx) = mpsc::channel();
    let (release_tx, release_rx) = mpsc::channel();
    let release_rx = Mutex::new(release_rx);
    let a = fixture(move |_, ctx| {
        entered_tx.send(()).unwrap();
        release_rx.lock().unwrap().recv().unwrap();
        assert!(ctx.cancelled());
        Outcome::Cancelled
    });
    let first = start(&a);
    entered_rx.recv_timeout(Duration::from_secs(2)).unwrap();
    for action in [
        Input::Check {},
        Input::Install {},
        Input::Repair {},
        Input::Recover {},
    ] {
        assert_eq!(a.action(action), Err("INSTALLER_BUSY"));
    }
    assert_eq!(
        a.action(Input::Cancel {
            session_id: first.session_id,
            job_id: first.job_id + 1
        }),
        Err("STALE_JOB")
    );
    let cancelled = a
        .action(Input::Cancel {
            session_id: first.session_id,
            job_id: first.job_id,
        })
        .unwrap();
    assert!(cancelled.busy && cancelled.cancel_requested);
    release_tx.send(()).unwrap();
    assert_eq!(finish(&a).status, Status::Cancelled);
    assert_eq!(
        a.action(Input::Cancel {
            session_id: first.session_id,
            job_id: first.job_id
        }),
        Err("STALE_JOB")
    );
}
#[test]
fn deferred_cancellation_can_commit() {
    let (tx, rx) = mpsc::channel();
    let (go, wait) = mpsc::channel();
    let wait = Mutex::new(wait);
    let a = fixture(move |_, ctx| {
        ctx.progress(Status::Applying, 0, 1).unwrap();
        tx.send(()).unwrap();
        wait.lock().unwrap().recv().unwrap();
        assert!(ctx.cancelled());
        ctx.progress(Status::Applying, 1, 1).unwrap();
        Outcome::Completed
    });
    let s = start(&a);
    rx.recv_timeout(Duration::from_secs(2)).unwrap();
    a.action(Input::Cancel {
        session_id: s.session_id,
        job_id: s.job_id,
    })
    .unwrap();
    go.send(()).unwrap();
    let s = finish(&a);
    assert_eq!(s.status, Status::Completed);
    assert!(s.cancel_requested);
}
#[test]
fn panic_and_incomplete_recovery_keep_gate() {
    let a = Actor::fixture(false, |input, _| match input {
        Input::Recover {} => Outcome::Cancelled,
        _ => panic!("fixture-only private detail"),
    });
    a.action(Input::Install {}).unwrap();
    assert_eq!(finish(&a).status, Status::RecoveryRequired);
    for input in [Input::Check {}, Input::Install {}, Input::Repair {}] {
        assert_eq!(a.action(input), Err("RECOVERY_REQUIRED"));
    }
    a.action(Input::Recover {}).unwrap();
    assert_eq!(finish(&a).status, Status::RecoveryRequired);
    let a = Actor::fixture(true, |_, _| Outcome::Completed);
    a.action(Input::Recover {}).unwrap();
    assert_eq!(finish(&a).status, Status::Completed);
    a.action(Input::Check {}).unwrap();
    assert_eq!(finish(&a).status, Status::Completed);
}
#[test]
fn progress_is_bounded_monotonic_and_job_bound() {
    let (tx, rx) = mpsc::channel();
    let a = fixture(move |_, ctx| {
        assert_eq!(
            ctx.progress(Status::Completed, 1, 1),
            Err("INVALID_PROGRESS")
        );
        assert_eq!(
            ctx.progress(Status::Downloading, 1, 0),
            Err("INVALID_PROGRESS")
        );
        assert_eq!(
            ctx.progress(Status::Downloading, 1, 10001),
            Err("INVALID_PROGRESS")
        );
        ctx.progress(Status::Downloading, 1, 2).unwrap();
        assert_eq!(
            ctx.progress(Status::Downloading, 0, 2),
            Err("INVALID_PROGRESS")
        );
        assert_eq!(
            ctx.progress(Status::Downloading, 1, 3),
            Err("INVALID_PROGRESS")
        );
        ctx.progress(Status::Preparing, 0, 2).unwrap();
        assert_eq!(
            ctx.progress(Status::Downloading, 2, 2),
            Err("INVALID_PROGRESS")
        );
        tx.send(ctx).unwrap();
        Outcome::Completed
    });
    start(&a);
    let ctx = rx.recv_timeout(Duration::from_secs(2)).unwrap();
    finish(&a);
    assert_eq!(ctx.progress(Status::Applying, 1, 1), Err("STALE_JOB"));
    assert!(ctx.cancelled());
}
#[test]
fn simultaneous_start_has_one_worker_and_old_cancel_cannot_touch_next_job() {
    use std::sync::{
        atomic::{AtomicUsize, Ordering},
        Barrier,
    };
    let (go, wait) = mpsc::channel();
    let wait = Mutex::new(wait);
    let count = Arc::new(AtomicUsize::new(0));
    let worker_count = count.clone();
    let a = Arc::new(fixture(move |_, _| {
        worker_count.fetch_add(1, Ordering::SeqCst);
        wait.lock().unwrap().recv().unwrap();
        Outcome::Completed
    }));
    a.action(Input::Install {}).unwrap();
    let initial = finish(&a);
    let plan_id = initial.plan.unwrap().plan_id;
    let barrier = Arc::new(Barrier::new(8));
    let threads: Vec<_> = (0..8)
        .map(|_| {
            let a = a.clone();
            let barrier = barrier.clone();
            std::thread::spawn(move || {
                barrier.wait();
                a.action(Input::Confirm { plan_id })
            })
        })
        .collect();
    let results: Vec<_> = threads.into_iter().map(|t| t.join().unwrap()).collect();
    assert_eq!(results.iter().filter(|r| r.is_ok()).count(), 1);
    assert_eq!(
        results
            .iter()
            .filter(|r| **r == Err("INSTALLER_BUSY"))
            .count(),
        7
    );
    let first = a.snapshot().unwrap().job_id;
    go.send(()).unwrap();
    finish(&a);
    let second = start(&a);
    assert_eq!(second.job_id, first + 2);
    assert_eq!(
        a.action(Input::Cancel {
            session_id: second.session_id,
            job_id: first
        }),
        Err("STALE_JOB")
    );
    assert!(!a.snapshot().unwrap().cancel_requested);
    go.send(()).unwrap();
    finish(&a);
    assert_eq!(count.load(Ordering::SeqCst), 2);
}
