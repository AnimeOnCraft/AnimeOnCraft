//! One in-process worker; ordinary source configuration remains unavailable.
//! Inputs never convey paths, sources, trust proofs or launch permission.
use serde::{de, Deserialize, Deserializer, Serialize, Serializer};
use std::sync::{Arc, Mutex};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Id([u8; 16]);
impl Id {
    pub(crate) fn new() -> Self {
        Self(uuid::Uuid::new_v4().into_bytes())
    }
}
impl Serialize for Id {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(&uuid::Uuid::from_bytes(self.0).simple().to_string())
    }
}
impl<'de> Deserialize<'de> for Id {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct Visitor;
        impl de::Visitor<'_> for Visitor {
            type Value = Id;
            fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
                f.write_str("32 lowercase hexadecimal characters")
            }
            fn visit_str<E: de::Error>(self, value: &str) -> Result<Id, E> {
                if value.len() != 32
                    || !value
                        .bytes()
                        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                {
                    return Err(E::custom("INVALID_ID"));
                }
                uuid::Uuid::parse_str(value)
                    .map(|v| Id(v.into_bytes()))
                    .map_err(|_| E::custom("INVALID_ID"))
            }
        }
        deserializer.deserialize_str(Visitor)
    }
}
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum PlanMode {
    Install,
    Repair,
}
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
pub struct PlanSummary {
    pub(crate) mode: PlanMode,
    pub(crate) install: u32,
    pub(crate) replace: u32,
    pub(crate) remove: u32,
    pub(crate) reuse: u32,
    pub(crate) staging_bytes: u64,
    pub(crate) required_space: u64,
}
impl PlanSummary {
    fn valid(&self) -> bool {
        [self.install, self.replace, self.remove, self.reuse]
            .into_iter()
            .try_fold(0u32, u32::checked_add)
            .is_some_and(|n| n <= 10000)
            && self.staging_bytes <= super::manifest::MAX_BYTES
            && self.required_space >= self.staging_bytes + 64 * 1024 * 1024
            && self.required_space <= super::manifest::MAX_BYTES * 2 + 64 * 1024 * 1024
    }
}
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
pub struct PlanView {
    pub plan_id: Id,
    pub summary: PlanSummary,
}
#[derive(Clone, Copy)]
pub(crate) enum Startup {
    Ready,
    RecoveryRequired,
    Blocked,
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(tag = "action", rename_all = "snake_case", deny_unknown_fields)]
pub enum Input {
    Check {},
    Install {},
    Repair {},
    Recover {},
    Confirm { plan_id: Id },
    Discard { plan_id: Id },
    Cancel { session_id: Id, job_id: u32 },
}
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Status {
    RepositoryUnavailable,
    Idle,
    Unavailable,
    Checking,
    Downloading,
    Preparing,
    Applying,
    Recovering,
    Completed,
    Cancelled,
    RecoveryRequired,
    AwaitingConfirmation,
    Blocked,
    PlanChanged,
}
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum Reason {
    RepositoryUnavailable,
    ReleaseNotConfigured,
    RecoveryRequired,
    StoreInvalid,
    PlanChanged,
}
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
pub struct Progress {
    completed: u32,
    total: u32,
}
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
pub struct Snapshot {
    pub session_id: Id,
    pub job_id: u32,
    pub status: Status,
    pub busy: bool,
    pub cancel_requested: bool,
    pub progress: Option<Progress>,
    pub reason: Option<Reason>,
    pub plan: Option<PlanView>,
}
struct State {
    view: Snapshot,
    action: Input,
    recovery_required: bool,
    blocked: bool,
    signal: tokio::sync::watch::Sender<bool>,
    update_reservation: Option<Id>,
}
pub struct Context {
    state: Arc<Mutex<State>>,
    job_id: u32,
    signal: tokio::sync::watch::Receiver<bool>,
}
impl Context {
    pub(crate) fn cancel_signal(&self) -> tokio::sync::watch::Receiver<bool> {
        self.signal.clone()
    }
    pub fn cancelled(&self) -> bool {
        self.state.lock().map_or(true, |s| {
            s.view.job_id != self.job_id || !s.view.busy || s.view.cancel_requested
        })
    }
    // Trusted worker reports object counts only. No elapsed-time estimates/fake percentages.
    pub fn progress(&self, status: Status, completed: u32, total: u32) -> Result<(), &'static str> {
        let mut s = self.state.lock().map_err(|_| "INSTALLER_UNAVAILABLE")?;
        if s.view.job_id != self.job_id || !s.view.busy {
            return Err("STALE_JOB");
        }
        let rank = |v| match v {
            Status::Checking | Status::Recovering => 0,
            Status::Downloading => 1,
            Status::Preparing => 2,
            Status::Applying => 3,
            _ => 4,
        };
        let allowed = match s.action {
            Input::Check {} | Input::Install {} | Input::Repair {} => status == Status::Checking,
            Input::Recover {} => matches!(status, Status::Recovering | Status::Applying),
            Input::Confirm { .. } => matches!(
                status,
                Status::Checking | Status::Downloading | Status::Preparing | Status::Applying
            ),
            Input::Cancel { .. } | Input::Discard { .. } => false,
        };
        if !allowed
            || rank(status) < rank(s.view.status)
            || total == 0
            || total > 10000
            || completed > total
        {
            return Err("INVALID_PROGRESS");
        }
        if status == s.view.status
            && s.view
                .progress
                .is_some_and(|p| p.total != total || p.completed > completed)
        {
            return Err("INVALID_PROGRESS");
        }
        s.view.status = status;
        s.view.progress = Some(Progress { completed, total });
        Ok(())
    }
}
pub enum Outcome {
    RepositoryUnavailable,
    Blocked,
    Unavailable,
    Completed,
    Cancelled,
    RecoveryRequired,
    Plan(PlanSummary),
    PlanChanged,
    RecoveredCancellation,
}
type Worker = dyn Fn(Input, Context) -> Outcome + Send + Sync;
#[derive(Clone)]
pub struct Actor {
    state: Arc<Mutex<State>>,
    worker: Arc<Worker>,
    configured: bool,
}
// Native-only exclusion authority. Retained through updater preparation/consent/handoff.
// No IPC value can construct a holder or clear another operation's reservation.
pub(crate) struct UpdateReservation {
    state: Arc<Mutex<State>>,
    id: Id,
}
impl UpdateReservation {
    pub(crate) fn validate(&self) -> Result<(), &'static str> {
        let state = self.state.lock().map_err(|_| "INSTALLER_UNAVAILABLE")?;
        if state.update_reservation != Some(self.id)
            || state.blocked
            || state.recovery_required
            || state.view.busy
            || state.view.plan.is_some()
        {
            return Err("UPDATE_GATE_CLOSED");
        }
        Ok(())
    }
}
impl Drop for UpdateReservation {
    fn drop(&mut self) {
        if let Ok(mut state) = self.state.lock() {
            if state.update_reservation == Some(self.id) {
                state.update_reservation = None;
            }
        }
    }
}
impl Actor {
    pub fn new() -> Self {
        Self::create(false, Startup::Ready, Arc::new(|_, _| Outcome::Unavailable))
    }
    fn create(configured: bool, startup: Startup, worker: Arc<Worker>) -> Self {
        let recovery_required = matches!(startup, Startup::RecoveryRequired);
        let blocked = matches!(startup, Startup::Blocked);
        Self {
            state: Arc::new(Mutex::new(State {
                view: Snapshot {
                    session_id: Id::new(),
                    job_id: 0,
                    status: if blocked {
                        Status::Blocked
                    } else if recovery_required {
                        Status::RecoveryRequired
                    } else if configured {
                        Status::Idle
                    } else {
                        Status::Unavailable
                    },
                    busy: false,
                    cancel_requested: false,
                    progress: None,
                    reason: if blocked {
                        Some(Reason::StoreInvalid)
                    } else if recovery_required {
                        Some(Reason::RecoveryRequired)
                    } else if configured {
                        None
                    } else {
                        Some(Reason::ReleaseNotConfigured)
                    },
                    plan: None,
                },
                action: Input::Check {},
                recovery_required,
                blocked,
                signal: tokio::sync::watch::channel(false).0,
                update_reservation: None,
            })),
            worker,
            configured,
        }
    }
    #[cfg(test)]
    pub(crate) fn fixture(
        recovery_required: bool,
        worker: impl Fn(Input, Context) -> Outcome + Send + Sync + 'static,
    ) -> Self {
        Self::fixture_startup(
            if recovery_required {
                Startup::RecoveryRequired
            } else {
                Startup::Ready
            },
            worker,
        )
    }
    #[cfg(test)]
    pub(crate) fn fixture_startup(
        startup: Startup,
        worker: impl Fn(Input, Context) -> Outcome + Send + Sync + 'static,
    ) -> Self {
        Self::backend(true, startup, worker)
    }
    pub(in crate::pack) fn backend(
        configured: bool,
        startup: Startup,
        worker: impl Fn(Input, Context) -> Outcome + Send + Sync + 'static,
    ) -> Self {
        Self::create(configured, startup, Arc::new(worker))
    }
    pub fn snapshot(&self) -> Result<Snapshot, &'static str> {
        Ok(self
            .state
            .lock()
            .map_err(|_| "INSTALLER_UNAVAILABLE")?
            .view
            .clone())
    }
    pub(crate) fn reserve_update(&self) -> Result<UpdateReservation, &'static str> {
        let mut state = self.state.lock().map_err(|_| "INSTALLER_UNAVAILABLE")?;
        if state.update_reservation.is_some()
            || state.blocked
            || state.recovery_required
            || state.view.busy
            || state.view.plan.is_some()
        {
            return Err("UPDATE_GATE_CLOSED");
        }
        let id = Id::new();
        state.update_reservation = Some(id);
        Ok(UpdateReservation {
            state: self.state.clone(),
            id,
        })
    }
    pub fn action(&self, input: Input) -> Result<Snapshot, &'static str> {
        let mut s = self.state.lock().map_err(|_| "INSTALLER_UNAVAILABLE")?;
        if s.update_reservation.is_some() {
            return Err("LAUNCHER_UPDATE_PENDING");
        }
        if let Input::Cancel { session_id, job_id } = input {
            if !s.view.busy || session_id != s.view.session_id || job_id != s.view.job_id {
                return Err("STALE_JOB");
            }
            s.view.cancel_requested = true;
            s.signal.send_replace(true);
            return Ok(s.view.clone());
        }
        if s.view.busy {
            return Err("INSTALLER_BUSY");
        }
        if !self.configured && input != (Input::Check {}) {
            return Err("RELEASE_NOT_CONFIGURED");
        }
        if s.blocked {
            return Err("STORE_INVALID");
        }
        match input {
            Input::Confirm { plan_id } | Input::Discard { plan_id } => {
                if s.view.plan.as_ref().map(|p| p.plan_id) != Some(plan_id) {
                    return Err("STALE_PLAN");
                }
                if matches!(input, Input::Confirm { .. }) && s.view.job_id == u32::MAX {
                    return Err("JOB_LIMIT");
                }
                s.view.plan = None;
                if matches!(input, Input::Discard { .. }) {
                    s.view.status = Status::Idle;
                    s.view.cancel_requested = false;
                    return Ok(s.view.clone());
                }
            }
            _ if s.view.plan.is_some() => return Err("PLAN_CONFIRMATION_REQUIRED"),
            _ => {}
        }
        if s.recovery_required && input != (Input::Recover {}) {
            return Err("RECOVERY_REQUIRED");
        }
        if input == (Input::Recover {}) && !s.recovery_required {
            return Err("RECOVERY_NOT_REQUIRED");
        }
        s.view.job_id = s.view.job_id.checked_add(1).ok_or("JOB_LIMIT")?;
        s.action = input;
        s.view.status = if input == (Input::Recover {}) {
            Status::Recovering
        } else {
            Status::Checking
        };
        s.view.busy = true;
        s.view.cancel_requested = false;
        s.view.progress = None;
        s.view.reason = None;
        s.signal = tokio::sync::watch::channel(false).0;
        let signal = s.signal.subscribe();
        let started = s.view.clone();
        let job_id = started.job_id;
        let state = self.state.clone();
        let worker = self.worker.clone();
        // Reserve while locked, spawn, then unlock: concurrent invocations cannot overlap.
        let spawned = std::thread::Builder::new()
            .name("pack-worker".into())
            .spawn(move || {
                let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    worker(
                        input,
                        Context {
                            state: state.clone(),
                            job_id,
                            signal,
                        },
                    )
                }))
                .unwrap_or(Outcome::RecoveryRequired);
                if let Ok(mut s) = state.lock() {
                    if s.view.job_id != job_id || !s.view.busy {
                        return;
                    }
                    // Cancel is a request. Only the worker can prove clean cancellation or commit.
                    if let Outcome::Plan(summary) = outcome {
                        let mode = match input {
                            Input::Install {} => Some(PlanMode::Install),
                            Input::Repair {} => Some(PlanMode::Repair),
                            _ => None,
                        };
                        if mode == Some(summary.mode) && summary.valid() && !s.view.cancel_requested
                        {
                            s.view.plan = Some(PlanView {
                                plan_id: Id::new(),
                                summary,
                            });
                            s.view.status = Status::AwaitingConfirmation;
                            s.view.reason = None;
                            s.view.busy = false;
                            s.view.progress = None;
                            return;
                        }
                        // Invalid worker protocol cannot issue confirmation authority.
                        s.view.plan = None;
                        s.recovery_required = s.recovery_required || !s.view.cancel_requested;
                        s.view.status = if !s.recovery_required {
                            Status::Cancelled
                        } else {
                            Status::RecoveryRequired
                        };
                        s.view.reason = if s.recovery_required {
                            Some(Reason::RecoveryRequired)
                        } else {
                            None
                        };
                        s.view.busy = false;
                        s.view.progress = None;
                        return;
                    }
                    let outcome = if matches!(input, Input::Install {} | Input::Repair {})
                        && matches!(outcome, Outcome::Completed)
                    {
                        Outcome::RecoveryRequired
                    } else {
                        outcome
                    };
                    if matches!(outcome, Outcome::Blocked) {
                        s.blocked = true;
                        s.view.status = Status::Blocked;
                        s.view.reason = Some(Reason::StoreInvalid);
                        s.view.plan = None;
                        s.view.busy = false;
                        s.view.progress = None;
                        return;
                    }
                    let outcome = if s.recovery_required
                        && !matches!(outcome, Outcome::Completed | Outcome::RecoveredCancellation)
                    {
                        Outcome::RecoveryRequired
                    } else {
                        outcome
                    };
                    s.recovery_required = matches!(outcome, Outcome::RecoveryRequired);
                    s.view.status = match outcome {
                        Outcome::RepositoryUnavailable => Status::RepositoryUnavailable,
                        Outcome::Blocked => unreachable!(),
                        Outcome::Unavailable => Status::Unavailable,
                        Outcome::Completed => Status::Completed,
                        Outcome::Cancelled => Status::Cancelled,
                        Outcome::RecoveredCancellation => Status::Cancelled,
                        Outcome::PlanChanged => Status::PlanChanged,
                        Outcome::RecoveryRequired => Status::RecoveryRequired,
                        Outcome::Plan(_) => unreachable!(),
                    };
                    s.view.reason = match outcome {
                        Outcome::RepositoryUnavailable => Some(Reason::RepositoryUnavailable),
                        Outcome::Unavailable => Some(Reason::ReleaseNotConfigured),
                        Outcome::RecoveryRequired => Some(Reason::RecoveryRequired),
                        Outcome::PlanChanged => Some(Reason::PlanChanged),
                        _ => None,
                    };
                    s.view.busy = false;
                    s.view.progress = None;
                }
            });
        if spawned.is_err() {
            s.recovery_required = true;
            s.view.busy = false;
            s.view.status = Status::RecoveryRequired;
            s.view.reason = Some(Reason::RecoveryRequired);
            return Err("INSTALLER_UNAVAILABLE");
        }
        Ok(started)
    }
}
#[cfg(test)]
mod tests;
