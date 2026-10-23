//! Fixed development/test engine schemas. One retained lease owns TUF/download/install stores.
use super::{
    bridge::{self, Bridge},
    download::{self, Cancel},
    install::{self, startup::Approval, Mode, Outcome},
    owned::{OwnedRoot, Receipt},
    *,
};
use crate::pack::{
    actor::Startup,
    manifest::{self, ReleasePolicy},
    trust::{self, VerifiedRelease},
};
use serde::{Deserialize, Serialize};
#[cfg(test)]
use std::collections::BTreeMap;
#[cfg(test)]
const NAMESPACE: &str = "AnimeOnCraft.EngineTest.v1";
#[cfg(test)]
const ENVIRONMENT: &str = "engine-integration-test-v1";
#[derive(Clone, Copy)]
enum Profile {
    Development,
    #[cfg(test)]
    Test,
}
impl Profile {
    fn namespace(self) -> &'static str {
        match self {
            Self::Development => "AnimeOnCraft.InstallEngineDevelopment.v1",
            #[cfg(test)]
            Self::Test => NAMESPACE,
        }
    }
    fn environment(self) -> &'static str {
        match self {
            Self::Development => "development-install-engine-v1",
            #[cfg(test)]
            Self::Test => ENVIRONMENT,
        }
    }
    fn ownership(self) -> &'static str {
        match self {
            #[cfg(test)]
            Self::Test => "ntfs-owned-test-v1",
            _ => self.environment(),
        }
    }
    fn install_environment(self) -> &'static str {
        match self {
            #[cfg(test)]
            Self::Test => "ntfs-install-test-v2",
            _ => self.environment(),
        }
    }
}
#[cfg(feature = "pack-acceptance")]
pub(super) fn acceptance_actor(
    local: Arc<crate::pack::local::LocalInstance>,
    fixture: crate::pack::acceptance::Fixture,
) -> EResult<crate::pack::actor::Actor> {
    actor::acceptance_actor(local, fixture)
}
const CAP: usize = 16384;
type EResult<T> = std::result::Result<T, &'static str>;
fn native<T>(value: Result<T>) -> EResult<T> {
    value.map_err(|_| "ENGINE_LAYOUT_INVALID")
}
fn read(dir: &Arc<Dir>, name: &str) -> EResult<(HeldFile, Vec<u8>)> {
    let mut file = native(dir.file(name, false))?;
    let mut bytes = Vec::new();
    (&mut file.file)
        .take(CAP as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "ENGINE_LAYOUT_INVALID")?;
    if bytes.len() > CAP {
        return Err("ENGINE_LAYOUT_INVALID");
    }
    native(file.fingerprint(bytes.len() as u64, &manifest::hash(&bytes)))?;
    Ok((file, bytes))
}
fn put<T: Serialize>(dir: &Arc<Dir>, name: &str, value: &T) -> EResult<HeldFile> {
    let bytes = bridge::encoded(value)?;
    if bytes.len() > CAP {
        return Err("ENGINE_LAYOUT_INVALID");
    }
    let mut file = native(dir.file(name, true))?;
    native(file.write_new(&bytes))?;
    native(file.fingerprint(bytes.len() as u64, &manifest::hash(&bytes)))?;
    Ok(file)
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Lease {
    format: u8,
    environment: String,
    root: Identity,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Binding {
    format: u8,
    environment: String,
    anchor: String,
    parent: Identity,
    namespace: Receipt,
    lease: Identity,
    trust_parent: Receipt,
    install_parent: Receipt,
    cache_parent: Receipt,
    trust_control: Receipt,
    trust_state: Receipt,
    install_control: Receipt,
    game_instance: Receipt,
    downloads: Receipt,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Ready {
    format: u8,
    environment: String,
    binding: String,
}
pub(in crate::pack) struct Engine {
    root: OwnedRoot,
    trust_parent: OwnedRoot,
    install_parent: OwnedRoot,
    cache_parent: OwnedRoot,
    bridge: Bridge,
    installer: install::Store,
    downloads: download::Store,
    anchor: String,
    held: Vec<HeldFile>,
    blocked: bool,
    profile: Profile,
}
struct Reconciled {
    current: VerifiedRelease,
    prior: Option<trust::cached::HistoricalRelease>,
    installed_current: bool,
    status: Startup,
}
impl Reconciled {
    fn old(&self) -> Option<&VerifiedRelease> {
        if self.installed_current {
            Some(&self.current)
        } else {
            self.prior.as_ref().map(|prior| prior.prior())
        }
    }
}
impl Engine {
    fn reconcile(
        &mut self,
        bootstrap: &[u8],
        policy: &ReleasePolicy,
        prior_policy: Option<&ReleasePolicy>,
    ) -> EResult<Reconciled> {
        self.validate(bootstrap)?;
        let result = (|| {
            let (active, pending) = self.installer.release_references()?;
            let restored = self.bridge.restore(bootstrap, policy, None)?;
            let installed_current = active.as_deref() == Some(restored.current.manifest().digest());
            let restored = if let Some(digest) = active.as_deref().filter(|_| !installed_current) {
                self.bridge.restore(
                    bootstrap,
                    policy,
                    Some((digest, prior_policy.ok_or("HISTORY_POLICY_REQUIRED")?)),
                )?
            } else {
                restored
            };
            if pending
                .as_deref()
                .is_some_and(|digest| digest != restored.current.manifest().digest())
            {
                return Err("ENGINE_PENDING_RELEASE_MISMATCH");
            }
            let mut context = Reconciled {
                current: restored.current,
                prior: restored.prior,
                installed_current,
                status: Startup::Blocked,
            };
            context.status = self.installer.startup(&context.current, context.old())?;
            Ok(context)
        })();
        self.validate(bootstrap)?;
        result
    }
    #[cfg(test)]
    fn open(parent: &Arc<Dir>, bootstrap: &[u8], hook: &mut impl FnMut(&str)) -> EResult<Self> {
        Self::open_profile(parent, bootstrap, Profile::Test, hook)
    }
    pub(super) fn open_development(parent: &Arc<Dir>, bootstrap: &[u8]) -> EResult<Self> {
        Self::open_profile(parent, bootstrap, Profile::Development, &mut |_| {})
    }
    fn open_profile(
        parent: &Arc<Dir>,
        bootstrap: &[u8],
        profile: Profile,
        hook: &mut impl FnMut(&str),
    ) -> EResult<Self> {
        trust::root_policy(bootstrap)?;
        native(parent.unchanged())?;
        match relative(
            parent,
            &mut native(portable_name(profile.namespace()))?,
            true,
            false,
            None,
        ) {
            Err(Error::Nt(code)) if code as u32 == 0xc0000034 => {
                Self::create(parent, bootstrap, profile, hook)
            }
            Err(_) => Err("ENGINE_LAYOUT_INVALID"),
            Ok(file) => {
                let identity = native(inspect(&file, true))?;
                if identity.volume != parent.identity.volume {
                    return Err("ENGINE_LAYOUT_INVALID");
                }
                let protection = Arc::new(native(Protection::current())?);
                native(protection.check(&file, true))?;
                let dir = Arc::new(Dir {
                    file,
                    identity,
                    _parent: Some(parent.clone()),
                    protection: Some(protection),
                });
                Self::reopen(parent, dir, bootstrap, profile)
            }
        }
    }
    fn create(
        parent: &Arc<Dir>,
        bootstrap: &[u8],
        profile: Profile,
        hook: &mut impl FnMut(&str),
    ) -> EResult<Self> {
        let root = native(OwnedRoot::create_scoped(
            parent,
            profile.namespace(),
            profile.environment(),
        ))?;
        hook("namespace-created");
        let lease = put(
            &root.dir,
            "lease.lock",
            &Lease {
                format: 1,
                environment: profile.environment().into(),
                root: root.dir.identity.clone(),
            },
        )?;
        hook("lease-created");
        let trust_parent = native(OwnedRoot::create_scoped(
            &root.dir,
            "trust",
            profile.environment(),
        ))?;
        let bridge = Bridge::create_scoped(&trust_parent.dir, bootstrap, profile.ownership())?;
        hook("trust-created");
        let install_parent = native(OwnedRoot::create_scoped(
            &root.dir,
            "install",
            profile.environment(),
        ))?;
        let installer = install::Store::create_scoped(
            &install_parent.dir,
            bootstrap,
            profile.ownership(),
            profile.install_environment(),
        )?;
        hook("install-created");
        let cache_parent = native(OwnedRoot::create_scoped(
            &root.dir,
            "cache",
            profile.environment(),
        ))?;
        let downloads = download::Store::create_scoped(&cache_parent.dir, profile.ownership())?;
        hook("downloads-created");
        let anchor = manifest::hash(bootstrap);
        let (trust_control, trust_state) = bridge.receipts();
        let (install_control, game_instance) = installer.receipts();
        let binding = put(
            &root.dir,
            "binding.json",
            &Binding {
                format: 1,
                environment: profile.environment().into(),
                anchor: anchor.clone(),
                parent: parent.identity.clone(),
                namespace: root.receipt.clone(),
                lease: lease.identity.clone(),
                trust_parent: trust_parent.receipt.clone(),
                install_parent: install_parent.receipt.clone(),
                cache_parent: cache_parent.receipt.clone(),
                trust_control,
                trust_state,
                install_control,
                game_instance,
                downloads: downloads.receipt(),
            },
        )?;
        hook("binding-created");
        let ready = put(
            &root.dir,
            "ready.json",
            &Ready {
                format: 1,
                environment: profile.environment().into(),
                binding: binding
                    .verified
                    .as_ref()
                    .ok_or("ENGINE_LAYOUT_INVALID")?
                    .1
                    .clone(),
            },
        )?;
        hook("ready-created");
        let mut engine = Self {
            root,
            trust_parent,
            install_parent,
            cache_parent,
            bridge,
            installer,
            downloads,
            anchor,
            held: vec![lease, binding, ready],
            blocked: false,
            profile,
        };
        engine.hold_markers()?;
        engine.validate(bootstrap)?;
        Ok(engine)
    }
    fn reopen(
        parent: &Arc<Dir>,
        dir: Arc<Dir>,
        bootstrap: &[u8],
        profile: Profile,
    ) -> EResult<Self> {
        let mut lease = match dir.file("lease.lock", false) {
            Err(Error::Nt(code)) if code as u32 == 0xc0000043 => return Err("ENGINE_BUSY"),
            result => native(result)?,
        };
        let mut bytes = Vec::new();
        (&mut lease.file)
            .take(CAP as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| "ENGINE_LAYOUT_INVALID")?;
        let record: Lease = bridge::decode(&bytes, CAP)?;
        if record.format != 1
            || record.environment != profile.environment()
            || record.root != dir.identity
        {
            return Err("ENGINE_LAYOUT_INVALID");
        }
        native(lease.fingerprint(bytes.len() as u64, &manifest::hash(&bytes)))?;
        let (binding, bytes) = read(&dir, "binding.json")?;
        let record: Binding = bridge::decode(&bytes, CAP)?;
        let (ready, bytes) = read(&dir, "ready.json")?;
        let seal: Ready = bridge::decode(&bytes, CAP)?;
        let anchor = manifest::hash(bootstrap);
        if record.format != 1
            || record.environment != profile.environment()
            || record.parent != parent.identity
            || record.anchor != anchor
            || record.lease != lease.identity
            || seal.format != 1
            || seal.environment != profile.environment()
            || seal.binding != binding.verified.as_ref().ok_or("ENGINE_LAYOUT_INVALID")?.1
        {
            return Err("ENGINE_BINDING_INVALID");
        }
        let root = native(OwnedRoot::reopen_scoped(
            parent,
            profile.namespace(),
            record.namespace,
            profile.environment(),
        ))?;
        if root.dir.identity != dir.identity {
            return Err("ENGINE_BINDING_INVALID");
        }
        let trust_parent = native(OwnedRoot::reopen_scoped(
            &root.dir,
            "trust",
            record.trust_parent,
            profile.environment(),
        ))?;
        let install_parent = native(OwnedRoot::reopen_scoped(
            &root.dir,
            "install",
            record.install_parent,
            profile.environment(),
        ))?;
        let cache_parent = native(OwnedRoot::reopen_scoped(
            &root.dir,
            "cache",
            record.cache_parent,
            profile.environment(),
        ))?;
        let bridge = Bridge::reopen_scoped(&trust_parent.dir, bootstrap, profile.ownership())?;
        let installer = install::Store::reopen_scoped(
            &install_parent.dir,
            bootstrap,
            profile.ownership(),
            profile.install_environment(),
        )?;
        let downloads = download::Store::reopen_scoped(
            &cache_parent.dir,
            record.downloads,
            profile.ownership(),
        )?;
        if bridge.receipts() != (record.trust_control, record.trust_state)
            || installer.receipts() != (record.install_control, record.game_instance)
        {
            return Err("ENGINE_BINDING_INVALID");
        }
        let mut engine = Self {
            root,
            trust_parent,
            install_parent,
            cache_parent,
            bridge,
            installer,
            downloads,
            anchor,
            held: vec![lease, binding, ready],
            blocked: false,
            profile,
        };
        engine.hold_markers()?;
        engine.validate(bootstrap)?;
        Ok(engine)
    }
    fn hold_markers(&mut self) -> EResult<()> {
        for root in [
            &self.root,
            &self.trust_parent,
            &self.install_parent,
            &self.cache_parent,
        ] {
            self.held.push(native(root.marker())?);
        }
        Ok(())
    }
    fn validate(&mut self, bootstrap: &[u8]) -> EResult<()> {
        if self.blocked {
            return Err("ENGINE_BLOCKED");
        }
        // A different caller bootstrap is an input refusal, not evidence of corruption.
        if manifest::hash(bootstrap) != self.anchor {
            return Err("ENGINE_TRUST_MISMATCH");
        }
        let result = (|| {
            native(self.root.dir.exact_names(&[
                "aoc-instance.json",
                "lease.lock",
                "binding.json",
                "ready.json",
                "trust",
                "install",
                "cache",
            ]))?;
            native(self.trust_parent.dir.exact_names(&[
                "aoc-instance.json",
                "control",
                "instance",
            ]))?;
            native(self.install_parent.dir.exact_names(&[
                "aoc-instance.json",
                "install-control",
                "game-instance",
            ]))?;
            native(
                self.cache_parent
                    .dir
                    .exact_names(&["aoc-instance.json", "downloads"]),
            )?;
            for file in &mut self.held {
                let (length, hash) = file.verified.clone().ok_or("ENGINE_LAYOUT_INVALID")?;
                native(file.fingerprint(length, &hash))?;
            }
            self.bridge.validate_layout(bootstrap)?;
            self.installer.validate_layout()?;
            self.downloads.validate_layout()?;
            Ok(())
        })();
        if result.is_err() {
            self.blocked = true;
        }
        result
    }
    fn release(&mut self, bootstrap: &[u8], new: &VerifiedRelease) -> EResult<()> {
        self.validate(bootstrap)?;
        self.bridge.matches_latest(bootstrap, new)
    }
    #[cfg(test)]
    async fn refresh<T: tough::Transport + Clone + Send + Sync + 'static>(
        &mut self,
        bootstrap: &[u8],
        transport: T,
        policy: &ReleasePolicy,
    ) -> EResult<VerifiedRelease> {
        self.validate(bootstrap)?;
        let result = self
            .bridge
            .refresh(bootstrap, transport, policy, false)
            .await;
        self.validate(bootstrap)?;
        result
    }
    async fn refresh_repository(
        &mut self,
        bootstrap: &[u8],
        repository: &crate::pack::repository::Repository,
        policy: &ReleasePolicy,
        cancel: &Cancel,
    ) -> EResult<VerifiedRelease> {
        self.validate(bootstrap)?;
        self.bridge
            .validate_history(bootstrap)
            .map_err(|_| "ENGINE_HISTORY_INVALID")?;
        if self.installer.has_pending()? {
            return Err("ENGINE_INSTALL_RECOVERY_REQUIRED");
        }
        cancel.check()?;
        let result = {
            let work = Box::pin(self.bridge.refresh_from(
                bootstrap,
                repository.clone(),
                policy,
                repository.endpoints.clone(),
                false,
            ));
            let stopped = Box::pin(async {
                let mut signal = cancel.receiver();
                loop {
                    if *signal.borrow_and_update() || signal.changed().await.is_err() {
                        break;
                    }
                }
            });
            match futures::future::select(work, stopped).await {
                futures::future::Either::Left((result, _)) => result,
                futures::future::Either::Right(_) => Err("CANCELLED"),
            }
        };
        self.validate(bootstrap)?;
        result
    }
    fn preview(
        &mut self,
        bootstrap: &[u8],
        new: &VerifiedRelease,
        old: Option<&VerifiedRelease>,
        mode: Mode,
        cancel: &Cancel,
    ) -> EResult<Approval> {
        self.release(bootstrap, new)?;
        let result = self.installer.preview(new, old, mode, cancel);
        self.validate(bootstrap)?;
        result
    }
    fn startup(
        &mut self,
        bootstrap: &[u8],
        new: &VerifiedRelease,
        old: Option<&VerifiedRelease>,
    ) -> EResult<Startup> {
        self.release(bootstrap, new)?;
        let result = self.installer.startup(new, old);
        self.validate(bootstrap)?;
        result
    }
    #[cfg(test)]
    async fn confirm(
        &mut self,
        bootstrap: &[u8],
        approval: Approval,
        new: &VerifiedRelease,
        old: Option<&VerifiedRelease>,
        objects: BTreeMap<String, Vec<u8>>,
        cancel: &Cancel,
        hook: &mut impl FnMut(&str),
    ) -> EResult<Outcome> {
        self.confirm_source(
            bootstrap,
            approval,
            new,
            old,
            &download::Source::Fixture(objects),
            cancel,
            hook,
        )
        .await
    }
    async fn confirm_source(
        &mut self,
        bootstrap: &[u8],
        approval: Approval,
        new: &VerifiedRelease,
        old: Option<&VerifiedRelease>,
        source: &download::Source,
        cancel: &Cancel,
        hook: &mut impl FnMut(&str),
    ) -> EResult<Outcome> {
        self.release(bootstrap, new)?;
        let result = async {
            self.installer.check_approval(&approval, new, old, cancel)?;
            hook("downloading");
            let mut data = source.prepare(&mut self.downloads, new, cancel).await?;
            hook("downloaded");
            self.installer
                .begin_confirmed(approval, new, old, &mut data, cancel, hook)
        }
        .await;
        self.validate(bootstrap)?;
        result
    }
    #[cfg(test)]
    async fn resume(
        &mut self,
        bootstrap: &[u8],
        new: &VerifiedRelease,
        old: Option<&VerifiedRelease>,
        objects: BTreeMap<String, Vec<u8>>,
        cancel: &Cancel,
        hook: &mut impl FnMut(&str),
    ) -> EResult<Outcome> {
        self.resume_source(
            bootstrap,
            new,
            old,
            &download::Source::Fixture(objects),
            cancel,
            hook,
        )
        .await
    }
    async fn resume_source(
        &mut self,
        bootstrap: &[u8],
        new: &VerifiedRelease,
        old: Option<&VerifiedRelease>,
        source: &download::Source,
        cancel: &Cancel,
        hook: &mut impl FnMut(&str),
    ) -> EResult<Outcome> {
        self.release(bootstrap, new)?;
        let result = async {
            if self.installer.pending_prepared()? {
                return self.installer.recover(new, old, cancel, hook);
            }
            hook("downloading");
            let mut data = source.prepare(&mut self.downloads, new, cancel).await?;
            hook("downloaded");
            self.installer
                .resume_preparation(new, old, &mut data, cancel, hook)
        }
        .await;
        self.validate(bootstrap)?;
        result
    }
}
mod actor;
pub(super) fn development_actor(
    local: Arc<crate::pack::local::LocalInstance>,
) -> crate::pack::actor::Actor {
    actor::development(local)
}
#[cfg(test)]
mod tests;
