//! Worker owns Engine/local lifetime for the complete job. Normal source configuration is closed.
use super::*;
use crate::pack::actor::{Actor, Context, Input, Outcome as JobOutcome, Status};
use std::sync::Mutex;

struct Backend {
    engine: Engine,
    local: Option<Arc<crate::pack::local::LocalInstance>>,
    bootstrap: Vec<u8>,
    policy: ReleasePolicy,
    prior_policy: Option<ReleasePolicy>,
    source: download::Source,
    repository: Option<crate::pack::repository::Repository>,
    approval: Option<Approval>,
    hook: Box<dyn FnMut(&str) + Send>,
}
impl Backend {
    fn needs_metadata(error: &'static str) -> bool {
        matches!(
            error,
            "ENGINE_RELEASE_REQUIRED"
                | "BRIDGE_RECOVERY_REQUIRED"
                | "RELEASE_EXPIRED"
                | "RELEASE_MISMATCH"
        )
    }
    fn startup(&mut self) -> Startup {
        match self.reconcile() {
            Ok(context) => context.status,
            Err(error)
                if Self::needs_metadata(error)
                    && self.repository.is_some()
                    && matches!(self.engine.installer.has_pending(), Ok(false)) =>
            {
                Startup::Ready
            }
            Err(_) => Startup::Blocked,
        }
    }
    fn reconcile(&mut self) -> EResult<Reconciled> {
        if self
            .local
            .as_ref()
            .is_some_and(|local| local.snapshot().status != crate::pack::local::Status::Ready)
        {
            return Err("LOCAL_STORE_BLOCKED");
        }
        self.engine
            .reconcile(&self.bootstrap, &self.policy, self.prior_policy.as_ref())
    }
    async fn run(&mut self, input: Input, ctx: Context) -> JobOutcome {
        (self.hook)("before-work");
        let cancel = Cancel::linked(ctx.cancel_signal());
        let initial = self.reconcile();
        let fetch = self.repository.is_some()
            && !matches!(input, Input::Recover {})
            && (matches!(input, Input::Check {})
                || initial.as_ref().is_err_and(|e| Self::needs_metadata(*e)));
        if fetch {
            self.approval = None;
            if self
                .local
                .as_ref()
                .is_some_and(|local| local.snapshot().status != crate::pack::local::Status::Ready)
            {
                return JobOutcome::Blocked;
            }
            if initial.as_ref().is_err_and(|e| !Self::needs_metadata(*e)) {
                return JobOutcome::Blocked;
            }
            if let Err(error) = self
                .engine
                .refresh_repository(
                    &self.bootstrap,
                    self.repository.as_ref().unwrap(),
                    &self.policy,
                    &cancel,
                )
                .await
            {
                return if self.engine.validate(&self.bootstrap).is_err() {
                    JobOutcome::Blocked
                } else if matches!(
                    error,
                    "STORE_CONTEXT_CHANGED"
                        | "STORE_ROLLBACK"
                        | "ENGINE_TRUST_MISMATCH"
                        | "ENGINE_HISTORY_INVALID"
                ) {
                    JobOutcome::Blocked
                } else if error == "ENGINE_INSTALL_RECOVERY_REQUIRED" {
                    JobOutcome::RecoveryRequired
                } else {
                    JobOutcome::RepositoryUnavailable
                };
            }
        }
        if fetch && matches!(input, Input::Confirm { .. }) {
            return self.finish(Err("INSTALL_PLAN_CHANGED"), false);
        }
        let context = match self.reconcile() {
            Ok(context) => context,
            Err(_) => {
                self.approval = None;
                return JobOutcome::Blocked;
            }
        };
        let recovery = matches!(input, Input::Recover {});
        if matches!(context.status, Startup::Blocked) {
            self.approval = None;
            return JobOutcome::Blocked;
        }
        if matches!(context.status, Startup::RecoveryRequired) && !recovery {
            self.approval = None;
            return JobOutcome::RecoveryRequired;
        }
        if matches!(input, Input::Check {}) {
            self.approval = None;
            return if cancel.check().is_err() {
                JobOutcome::Cancelled
            } else {
                JobOutcome::Completed
            };
        }
        if matches!(input, Input::Install {} | Input::Repair {}) {
            self.approval = None;
            let mode = if matches!(input, Input::Repair {}) {
                Mode::Repair
            } else {
                Mode::Update
            };
            // Repair is for the authenticated installed current release only.
            if mode == Mode::Repair && !context.installed_current {
                return JobOutcome::Blocked;
            }
            let result = self.engine.preview(
                &self.bootstrap,
                &context.current,
                context.old(),
                mode,
                &cancel,
            );
            return match result {
                Ok(approval) => {
                    let summary = approval.summary.clone();
                    self.approval = Some(approval);
                    JobOutcome::Plan(summary)
                }
                Err(error) => self.finish(Err(error), false),
            };
        }
        let total = if recovery {
            match self.engine.installer.pending_operations() {
                Ok(total) => total,
                Err(_) => return JobOutcome::Blocked,
            }
        } else if let Some(approval) = &self.approval {
            let s = &approval.summary;
            s.install + s.replace + s.remove + s.reuse
        } else {
            return JobOutcome::Blocked;
        };
        if total > 10000 {
            return JobOutcome::Blocked;
        }
        let mut applied = 0;
        let mut applying = false;
        let mut progress_error = false;
        let hook = &mut self.hook;
        let mut phase = |phase: &str| {
            if total > 0 {
                let progress = if phase == "downloading" && !recovery {
                    Some((Status::Downloading, 0))
                } else if phase == "downloaded" && !recovery {
                    Some((Status::Preparing, 0))
                } else if !applying && (phase == "prepared" || phase.starts_with("intent:")) {
                    applying = true;
                    Some((Status::Applying, 0))
                } else if phase.starts_with("done:") {
                    applied += 1;
                    Some((Status::Applying, applied))
                } else {
                    None
                };
                if let Some((status, n)) = progress {
                    progress_error |= ctx.progress(status, n, total).is_err();
                }
            }
            hook(phase);
        };
        let result = match input {
            Input::Confirm { .. } => match self.approval.take() {
                Some(approval) => {
                    self.engine
                        .confirm_source(
                            &self.bootstrap,
                            approval,
                            &context.current,
                            context.old(),
                            &self.source,
                            &cancel,
                            &mut phase,
                        )
                        .await
                }
                None => Err("ENGINE_APPROVAL_REQUIRED"),
            },
            Input::Recover {} => {
                self.approval = None;
                self.engine
                    .resume_source(
                        &self.bootstrap,
                        &context.current,
                        context.old(),
                        &self.source,
                        &cancel,
                        &mut phase,
                    )
                    .await
            }
            _ => Err("ENGINE_ACTION_INVALID"),
        };
        let finished = self.finish(result, recovery);
        if progress_error {
            return JobOutcome::Blocked;
        }
        finished
    }
    fn finish(&mut self, result: EResult<Outcome>, recovery: bool) -> JobOutcome {
        // Neither an error nor a cancellation request proves the journal clean.
        let context = match self.reconcile() {
            Ok(context) => context,
            Err(_) => return JobOutcome::Blocked,
        };
        if matches!(context.status, Startup::RecoveryRequired) {
            return JobOutcome::RecoveryRequired;
        }
        if !matches!(context.status, Startup::Ready) {
            return JobOutcome::Blocked;
        }
        match result {
            Ok(Outcome::Committed { .. }) if context.installed_current => JobOutcome::Completed,
            Ok(Outcome::Cancelled) | Err("CANCELLED") => {
                if recovery {
                    JobOutcome::RecoveredCancellation
                } else {
                    JobOutcome::Cancelled
                }
            }
            Err("INSTALL_PLAN_CHANGED") => JobOutcome::PlanChanged,
            _ => JobOutcome::Blocked,
        }
    }
}
fn attach(mut backend: Backend) -> EResult<(Actor, std::sync::Weak<Mutex<Backend>>)> {
    let startup = backend.startup();
    let owner = Arc::new(Mutex::new(backend));
    let weak = Arc::downgrade(&owner);
    let actor = Actor::backend(true, startup, move |input, ctx| match owner.lock() {
        Ok(mut backend) => match tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
        {
            Ok(runtime) => runtime.block_on(backend.run(input, ctx)),
            Err(_) => JobOutcome::Blocked,
        },
        Err(_) => JobOutcome::Blocked,
    });
    Ok((actor, weak))
}
struct Configuration {
    bootstrap: Vec<u8>,
    policy: ReleasePolicy,
    prior_policy: Option<ReleasePolicy>,
    source: download::Source,
    repository: Option<crate::pack::repository::Repository>,
}
impl Configuration {
    fn checked(
        bootstrap: Vec<u8>,
        mut policy: ReleasePolicy,
        prior_policy: Option<ReleasePolicy>,
        metadata: &str,
        targets: &str,
        bases: std::collections::BTreeMap<String, Vec<String>>,
    ) -> EResult<Self> {
        trust::root_policy(&bootstrap)?;
        manifest::validate_policy(&policy)?;
        if let Some(prior) = &prior_policy {
            manifest::validate_policy(prior)?;
        }
        if policy.sequence == 0
            || prior_policy.as_ref().is_some_and(|prior| {
                prior.pack_id != policy.pack_id
                    || prior.channel != policy.channel
                    || prior.sequence > policy.sequence
            })
        {
            return Err("REPOSITORY_CONFIG_INVALID");
        }
        policy.launcher_version = env!("CARGO_PKG_VERSION")
            .parse()
            .map_err(|_| "REPOSITORY_CONFIG_INVALID")?;
        let source = download::Source::approved(bases, &policy.approved_sources)?;
        let repository = Some(crate::pack::repository::Repository::new(metadata, targets)?);
        Ok(Self {
            bootstrap,
            policy,
            prior_policy,
            source,
            repository,
        })
    }
}
pub(super) fn development(local: Arc<crate::pack::local::LocalInstance>) -> Actor {
    // No embedded trusted release/bootstrap/source registry is published yet.
    create_development(local, None)
}
#[cfg(feature = "pack-acceptance")]
pub(super) fn acceptance_actor(
    local: Arc<crate::pack::local::LocalInstance>,
    fixture: crate::pack::acceptance::Fixture,
) -> EResult<Actor> {
    use crate::pack::acceptance::{policy, ORIGIN};
    let metadata = format!("{ORIGIN}metadata/");
    let targets = format!("{ORIGIN}targets/");
    let mut configuration = Configuration::checked(
        fixture.bootstrap,
        policy(fixture.sequence),
        (fixture.sequence == 2).then(|| policy(1)),
        &metadata,
        &targets,
        std::collections::BTreeMap::from([("fixture".into(), vec![format!("{ORIGIN}artifacts/")])]),
    )?;
    configuration.repository = Some(crate::pack::repository::Repository::with_certificate(
        &metadata,
        &targets,
        &fixture.certificate,
    )?);
    configuration.source = configuration.source.with_certificate(fixture.certificate)?;
    Ok(create_development(local, Some(configuration)))
}
fn unavailable(local: Arc<crate::pack::local::LocalInstance>) -> Actor {
    let startup = if local.snapshot().status == crate::pack::local::Status::Ready {
        Startup::Ready
    } else {
        Startup::Blocked
    };
    Actor::backend(false, startup, move |_, _| {
        if local.snapshot().status == crate::pack::local::Status::Ready {
            JobOutcome::Unavailable
        } else {
            JobOutcome::Blocked
        }
    })
}
fn create_development(
    local: Arc<crate::pack::local::LocalInstance>,
    configuration: Option<Configuration>,
) -> Actor {
    let Some(configuration) = configuration.filter(|v| v.source.available()) else {
        return unavailable(local);
    };
    let engine = match local.engine(&configuration.bootstrap) {
        Ok(engine) => engine,
        Err(_) => {
            return Actor::backend(true, Startup::Blocked, move |_, _| {
                let _ = local.snapshot();
                JobOutcome::Blocked
            })
        }
    };
    attach(Backend {
        engine,
        local: Some(local),
        bootstrap: configuration.bootstrap,
        policy: configuration.policy,
        prior_policy: configuration.prior_policy,
        source: configuration.source,
        repository: configuration.repository,
        approval: None,
        hook: Box::new(|_| {}),
    })
    .expect("infallible backend owner")
    .0
}
#[cfg(test)]
mod tests;
