use super::*;
#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(tag = "action", rename_all = "snake_case", deny_unknown_fields)]
pub(crate) enum Input {
    Check {},
    Cancel { session_id: Id, job_id: u32 },
    Confirm { plan_id: Id },
    Discard { plan_id: Id },
}
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub(crate) enum UpdateStatus {
    Unavailable,
    Idle,
    Preparing,
    AwaitingConfirmation,
    Cancelled,
    Expired,
    Failed,
    Current,
    HandingOff,
}
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
pub(crate) struct Snapshot {
    pub(crate) session_id: Id,
    pub(crate) job_id: u32,
    pub(crate) current_version: String,
    pub(crate) source_configured: bool,
    pub(crate) status: UpdateStatus,
    pub(crate) busy: bool,
    pub(crate) plan: Option<PlanView>,
    pub(crate) reason: Option<&'static str>,
}
struct ManagerState {
    view: Snapshot,
    cancel: Option<tokio::sync::watch::Sender<bool>>,
    #[cfg(windows)]
    handoff: Option<windows::Handoff>,
}
struct Core {
    service: Service,
    http: Option<transport::Http>,
    actor: Actor,
    local: Arc<LocalInstance>,
    product: String,
    installed: bool,
    state: Mutex<ManagerState>,
}
pub(crate) struct Manager {
    core: Arc<Core>,
}
impl Manager {
    pub(crate) fn new(
        actor: Actor,
        local: Arc<LocalInstance>,
        product: String,
        current: String,
        installed: bool,
    ) -> Result<Self> {
        #[cfg(all(windows, feature = "self-update-acceptance"))]
        let (config, http) = super::acceptance::configuration(&current)?;
        #[cfg(not(all(windows, feature = "self-update-acceptance")))]
        let (config, http) = match ordinary_config() {
            None => (None, None),
            Some(config) => (Some(config), Some(transport::Http::new()?)),
        };
        let configured = config.is_some();
        let manager = Self {
            core: Arc::new(Core {
                service: Service::new(config),
                http,
                actor,
                local,
                product,
                installed,
                state: Mutex::new(ManagerState {
                    view: Snapshot {
                        session_id: Id::new(),
                        job_id: 0,
                        current_version: current,
                        source_configured: configured,
                        status: if configured && installed {
                            UpdateStatus::Idle
                        } else {
                            UpdateStatus::Unavailable
                        },
                        busy: false,
                        plan: None,
                        reason: if !configured {
                            Some("UPDATE_NOT_CONFIGURED")
                        } else if !installed {
                            Some("UPDATE_NOT_INSTALLED")
                        } else {
                            None
                        },
                    },
                    cancel: None,
                    #[cfg(windows)]
                    handoff: None,
                }),
            }),
        };
        if configured {
            let weak = Arc::downgrade(&manager.core);
            std::thread::Builder::new()
                .name("updater-consent-expiry".into())
                .spawn(move || loop {
                    std::thread::sleep(Duration::from_secs(1));
                    let Some(core) = weak.upgrade() else {
                        break;
                    };
                    let Ok(mut state) = core.state.lock() else {
                        break;
                    };
                    if core.service.expire().unwrap_or(false) {
                        state.view.plan = None;
                        state.view.status = UpdateStatus::Expired;
                        state.view.reason = Some("UPDATE_EXPIRED");
                    }
                })
                .map_err(|_| "UPDATE_UNAVAILABLE")?;
        }
        Ok(manager)
    }
    pub(crate) fn snapshot(&self) -> Result<Snapshot> {
        let mut state = self.core.state.lock().map_err(|_| "UPDATE_UNAVAILABLE")?;
        if self.core.service.expire()? {
            state.view.plan = None;
            state.view.status = UpdateStatus::Expired;
            state.view.reason = Some("UPDATE_EXPIRED");
        }
        Ok(state.view.clone())
    }
    pub(crate) fn action(
        &self,
        input: Input,
        exit: impl FnOnce() + Send + 'static,
    ) -> Result<Snapshot> {
        let mut state = self.core.state.lock().map_err(|_| "UPDATE_UNAVAILABLE")?;
        if let Input::Cancel { session_id, job_id } = input {
            if !state.view.busy
                || state.view.status != UpdateStatus::Preparing
                || session_id != state.view.session_id
                || job_id != state.view.job_id
            {
                return Err("UPDATE_STALE_JOB");
            }
            state
                .cancel
                .as_ref()
                .ok_or("UPDATE_STALE_JOB")?
                .send_replace(true);
            return Ok(state.view.clone());
        }
        if state.view.busy {
            return Err("UPDATE_BUSY");
        }
        if self.core.service.config.is_none() {
            return Ok(state.view.clone());
        }
        if !self.core.installed {
            return Err("UPDATE_NOT_INSTALLED");
        }
        if self.core.service.expire()? {
            state.view.plan = None;
            state.view.status = UpdateStatus::Expired;
            state.view.reason = Some("UPDATE_EXPIRED");
        }
        match input {
            Input::Discard { plan_id } => {
                self.core.service.discard(plan_id)?;
                state.view.plan = None;
                state.view.status = UpdateStatus::Idle;
                state.view.reason = None;
                return Ok(state.view.clone());
            }
            Input::Confirm { plan_id }
                if state.view.plan.as_ref().map(|p| p.plan_id) != Some(plan_id) =>
            {
                return Err("UPDATE_STALE_PLAN")
            }
            Input::Check {} if state.view.plan.is_some() => {
                return Err("UPDATE_CONFIRMATION_REQUIRED")
            }
            _ => {}
        }
        state.view.job_id = state.view.job_id.checked_add(1).ok_or("UPDATE_JOB_LIMIT")?;
        let job = state.view.job_id;
        let (sender, cancel) = tokio::sync::watch::channel(false);
        state.cancel = Some(sender);
        state.view.busy = true;
        state.view.status = UpdateStatus::Preparing;
        state.view.reason = None;
        let authority = if let Input::Confirm { plan_id } = input {
            state.view.plan = None;
            match self.core.service.consume(plan_id) {
                Ok(value) => Some(value),
                Err(reason) => {
                    state.view.busy = false;
                    state.view.status = UpdateStatus::Failed;
                    state.view.reason = Some(safe_error(reason));
                    return Ok(state.view.clone());
                }
            }
        } else {
            None
        };
        let core = self.core.clone();
        let spawned = std::thread::Builder::new()
            .name("launcher-updater".into())
            .spawn(move || {
                let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(
                    || -> Result<Option<PlanView>> {
                        if let Some(authority) = authority {
                            #[cfg(windows)]
                            {
                                let handoff = windows::Handoff::prepare(
                                    authority,
                                    &core.product,
                                    &core
                                        .service
                                        .config
                                        .as_ref()
                                        .ok_or("UPDATE_NOT_CONFIGURED")?
                                        .current
                                        .to_string(),
                                )?;
                                let mut state =
                                    core.state.lock().map_err(|_| "UPDATE_UNAVAILABLE")?;
                                state.handoff = Some(handoff); // Keep exclusion and file handles until OS process exit.
                                if state
                                    .handoff
                                    .as_mut()
                                    .ok_or("UPDATE_HANDOFF_FAILED")?
                                    .spawn()
                                    .is_err()
                                {
                                    state.handoff = None;
                                    return Err("UPDATE_HANDOFF_FAILED");
                                }
                                state.view.status = UpdateStatus::HandingOff;
                                state.view.plan = None;
                                drop(state);
                                exit();
                                return Ok(None);
                            }
                            #[cfg(not(windows))]
                            {
                                let _ = (authority, exit);
                                return Err("UPDATE_NOT_INSTALLED");
                            }
                        }
                        let runtime = tokio::runtime::Builder::new_current_thread()
                            .enable_all()
                            .build()
                            .map_err(|_| "UPDATE_FAILED")?;
                        runtime
                            .block_on(
                                core.service.prepare_with(
                                    core.service
                                        .config
                                        .as_ref()
                                        .ok_or("UPDATE_NOT_CONFIGURED")?,
                                    core.http.as_ref().ok_or("UPDATE_UNAVAILABLE")?,
                                    &core.actor,
                                    core.local.clone(),
                                    cancel,
                                ),
                            )
                            .map(Some)
                    },
                ))
                .unwrap_or(Err("UPDATE_FAILED"));
                if let Ok(mut state) = core.state.lock() {
                    if state.view.job_id != job || state.view.status == UpdateStatus::HandingOff {
                        return;
                    }
                    state.view.busy = false;
                    state.cancel = None;
                    match result {
                        Ok(Some(plan)) => {
                            state.view.plan = Some(plan);
                            state.view.status = UpdateStatus::AwaitingConfirmation;
                        }
                        Ok(None) => {
                            state.view.status = UpdateStatus::Failed;
                            state.view.reason = Some("UPDATE_FAILED");
                        }
                        Err("UPDATE_CANCELLED") => {
                            state.view.status = UpdateStatus::Cancelled;
                        }
                        Err("UPDATE_NOT_NEWER") => {
                            state.view.status = UpdateStatus::Current;
                        }
                        Err("UPDATE_EXPIRED") => {
                            state.view.status = UpdateStatus::Expired;
                            state.view.reason = Some("UPDATE_EXPIRED");
                        }
                        Err(reason) => {
                            state.view.status = UpdateStatus::Failed;
                            state.view.reason = Some(safe_error(reason));
                        }
                    }
                }
            });
        if spawned.is_err() {
            state.view.busy = false;
            state.view.status = UpdateStatus::Failed;
            state.view.reason = Some("UPDATE_FAILED");
        }
        Ok(state.view.clone())
    }
}
fn safe_error(value: &'static str) -> &'static str {
    match value {
        "UPDATE_SIGNATURE_INVALID"
        | "UPDATE_MANIFEST_INVALID"
        | "UPDATE_VERSION_INVALID"
        | "UPDATE_EXPIRED"
        | "UPDATE_NOT_NEWER"
        | "UPDATE_INSTALLER_INVALID"
        | "UPDATE_ROLLBACK"
        | "UPDATE_RELEASE_CHANGED"
        | "UPDATE_NETWORK"
        | "UPDATE_HTTP_STATUS"
        | "UPDATE_BODY_INVALID"
        | "UPDATE_SOURCE_INVALID"
        | "UPDATE_GATE_CLOSED"
        | "UPDATE_LOCAL_UNAVAILABLE"
        | "UPDATE_STAGE_UNAVAILABLE"
        | "UPDATE_BINARY_IDENTITY"
        | "UPDATE_HANDOFF_FAILED"
        | "UPDATE_NOT_INSTALLED" => value,
        _ => "UPDATE_FAILED",
    }
}
