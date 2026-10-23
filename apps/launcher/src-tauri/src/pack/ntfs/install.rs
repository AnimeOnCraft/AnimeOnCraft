//! Receipt-bound immutable install journal. Verified TUF proofs authorize paths/bytes;
//! journal contents alone never do. Recovery rolls forward; backups are retained.
use super::{
    bridge::{decode, encoded, get, put},
    download::{free_bytes, Cancel, Prepared},
    owned::{OwnedRoot, Receipt},
    *,
};
use crate::pack::{
    manifest::{self, CheckedManifest, Fingerprint, Operation, Side},
    trust::{self, VerifiedRelease},
};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    time::{Duration, Instant},
};
mod preparation;
mod repair;
pub(super) mod startup;
use startup::Approval;
#[cfg(test)]
mod tests;
type IResult<T> = std::result::Result<T, &'static str>;
const CAP: usize = 4 * 1024 * 1024;
const TRANSACTIONS: usize = 32;
#[cfg(test)]
const ENVIRONMENT: &str = "ntfs-install-test-v2";
fn native<T>(r: Result<T>) -> IResult<T> {
    r.map_err(|_| "INSTALL_STORE_INVALID")
}
fn io<T>(r: io::Result<T>) -> IResult<T> {
    r.map_err(|_| "INSTALL_IO")
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Stamp {
    length: u64,
    sha256: String,
}
impl Stamp {
    fn of(file: &manifest::File) -> Self {
        Self {
            length: file.length,
            sha256: file.sha256.clone(),
        }
    }
    fn observed(f: &Fingerprint) -> Self {
        Self {
            length: f.length,
            sha256: f.sha256.clone(),
        }
    }
    fn fingerprint(&self, file: &mut HeldFile) -> IResult<()> {
        file.fingerprint(self.length, &self.sha256)
            .map_err(|_| "INSTALL_CONTENT_CHANGED")
    }
}
fn entries(manifest: &CheckedManifest) -> BTreeMap<String, Stamp> {
    manifest
        .manifest()
        .files
        .iter()
        .filter(|f| f.side != Side::SERVER)
        .map(|f| (f.path.to_ascii_lowercase(), Stamp::of(f)))
        .collect()
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
enum Action {
    Install,
    Reuse,
    Replace,
    Remove,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Op {
    action: Action,
    path: String,
    old: Option<Stamp>,
    new: Option<Stamp>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Binding {
    format: u8,
    environment: String,
    anchor: String,
    control: Receipt,
    instance: Receipt,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Plan {
    format: u8,
    mode: Mode,
    number: u64,
    previous: Option<u64>,
    binding: String,
    anchor: String,
    pack: String,
    channel: String,
    sequence: u64,
    old: Option<String>,
    new: String,
    before: BTreeMap<String, Stamp>,
    operations: Vec<Op>,
    space: u64,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Index {
    format: u8,
    number: u64,
    plan: String,
    manifest: String,
    entries: BTreeMap<String, Stamp>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Event {
    format: u8,
    plan: String,
    phase: String,
}
#[derive(Debug, PartialEq, Eq)]
pub(super) enum Outcome {
    Cancelled,
    Committed { cancellation_deferred: bool },
}
struct Head {
    active: Option<Index>,
    last: u64,
    pending: Option<u64>,
    floor: Option<(String, String, u64, String)>,
}
struct Tx {
    dir: Arc<Dir>,
    journal: Arc<Dir>,
    staged: Arc<Dir>,
    backup: Arc<Dir>,
    plan: Plan,
    hash: String,
    names: BTreeSet<String>,
    choice: Option<preparation::Choice>,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub(super) enum Mode {
    Update,
    Repair,
}
pub(super) struct Store {
    control: OwnedRoot,
    instance: OwnedRoot,
    transactions: Arc<Dir>,
    anchor: String,
    binding: String,
}
fn names(dir: &Arc<Dir>, maximum: usize) -> IResult<BTreeSet<String>> {
    Ok(
        native(dir.entries(maximum, Instant::now() + Duration::from_secs(60)))?
            .into_iter()
            .map(|e| e.name)
            .collect(),
    )
}
fn has(dir: &Arc<Dir>, name: &str) -> IResult<bool> {
    Ok(names(dir, manifest::MAX_FILES * 3 + 8)?.contains(name))
}
fn parent(root: &Arc<Dir>, path: &str, create: bool) -> IResult<Option<(Arc<Dir>, String)>> {
    if !manifest::managed(path) {
        return Err("INSTALL_PLAN_INVALID");
    }
    let mut parts = path.split('/').collect::<Vec<_>>();
    let leaf = parts.pop().ok_or("INSTALL_PLAN_INVALID")?.to_owned();
    let mut dir = root.clone();
    for component in parts {
        let exists = names(&dir, manifest::MAX_FILES)?
            .iter()
            .any(|n| n.eq_ignore_ascii_case(component));
        if !exists && !create {
            return Ok(None);
        }
        dir = native(dir.child(component, !exists))?;
    }
    Ok(Some((dir, leaf)))
}
fn managed_file(root: &Arc<Dir>, path: &str) -> IResult<Option<HeldFile>> {
    let Some((dir, name)) = parent(root, path, false)? else {
        return Ok(None);
    };
    if !names(&dir, manifest::MAX_FILES)?
        .iter()
        .any(|n| n.eq_ignore_ascii_case(&name))
    {
        return Ok(None);
    }
    Ok(Some(native(dir.file(&name, false))?))
}
fn flat_file(dir: &Arc<Dir>, name: &str) -> IResult<Option<HeldFile>> {
    if !has(dir, name)? {
        return Ok(None);
    }
    Ok(Some(native(dir.file(name, false))?))
}
fn phase_bytes(hash: &str, phase: &str) -> IResult<Vec<u8>> {
    encoded(&Event {
        format: 1,
        plan: hash.into(),
        phase: phase.into(),
    })
}
impl Tx {
    fn record(&mut self, phase: &str) -> IResult<()> {
        let name = format!("{phase}.json");
        let bytes = phase_bytes(&self.hash, phase)?;
        if has(&self.journal, &name)? {
            if get(&self.journal, &name, 16384)? != bytes {
                return Err("INSTALL_JOURNAL_INVALID");
            }
        } else {
            put(&self.journal, &name, &bytes)?;
        }
        self.names.insert(name);
        Ok(())
    }
    fn marked(&self, phase: &str) -> bool {
        self.names.contains(&format!("{phase}.json"))
    }
    fn validate_records(&self) -> IResult<()> {
        let mut allowed =
            BTreeSet::from(["plan.json".to_string(), "stage-choice.json".to_string()]);
        for phase in [
            "prepared",
            "cancel-request",
            "cancelled",
            "index-ready",
            "committed",
        ] {
            allowed.insert(format!("{phase}.json"));
        }
        let mut applying = false;
        for i in 0..self.plan.operations.len() {
            for phase in ["intent", "backed", "done"] {
                allowed.insert(format!("{phase}-{i:05}.json"));
            }
            let intent = self.marked(&format!("intent-{i:05}"));
            let backed = self.marked(&format!("backed-{i:05}"));
            let done = self.marked(&format!("done-{i:05}"));
            if (backed && !intent)
                || (done && !backed)
                || (intent && i > 0 && !self.marked(&format!("done-{:05}", i - 1)))
            {
                return Err("INSTALL_JOURNAL_INVALID");
            }
            applying |= intent;
        }
        let mut size = 0usize;
        for name in &self.names {
            if !allowed.contains(name) {
                return Err("INSTALL_JOURNAL_INVALID");
            }
            let bytes = get(&self.journal, name, CAP)?;
            size = size
                .checked_add(bytes.len())
                .ok_or("INSTALL_JOURNAL_LIMIT")?;
            if size > CAP {
                return Err("INSTALL_JOURNAL_LIMIT");
            }
            if name != "plan.json"
                && name != "stage-choice.json"
                && bytes
                    != phase_bytes(
                        &self.hash,
                        name.strip_suffix(".json")
                            .ok_or("INSTALL_JOURNAL_INVALID")?,
                    )?
            {
                return Err("INSTALL_JOURNAL_INVALID");
            }
        }
        if (applying || self.marked("index-ready")) && !self.marked("prepared") {
            return Err("INSTALL_JOURNAL_INVALID");
        }
        if self.marked("prepared") && self.choice.is_none() {
            return Err("INSTALL_STAGE_INVALID");
        }
        if let Some(choice) = &self.choice {
            choice.validate(self)?;
        }
        if self.marked("committed") && !self.marked("index-ready") {
            return Err("INSTALL_JOURNAL_INVALID");
        }
        if self.marked("index-ready")
            && (0..self.plan.operations.len()).any(|i| !self.marked(&format!("done-{i:05}")))
        {
            return Err("INSTALL_JOURNAL_INVALID");
        }
        if self.marked("cancelled")
            && (!self.marked("cancel-request")
                || applying
                || self.marked("index-ready")
                || self.marked("committed"))
        {
            return Err("INSTALL_JOURNAL_INVALID");
        }
        Ok(())
    }
}
impl Store {
    pub(super) fn release_references(&self) -> IResult<(Option<String>, Option<String>)> {
        let head = self.head()?;
        let pending = head
            .pending
            .map(|number| self.load(number).map(|tx| tx.plan.new))
            .transpose()?;
        Ok((head.active.map(|index| index.manifest), pending))
    }
    pub(super) fn receipts(&self) -> (Receipt, Receipt) {
        (self.control.receipt.clone(), self.instance.receipt.clone())
    }
    pub(super) fn check_approval(
        &self,
        approval: &Approval,
        new: &VerifiedRelease,
        old: Option<&VerifiedRelease>,
        cancel: &Cancel,
    ) -> IResult<()> {
        if self.preview(new, old, approval.mode, cancel)?.hash != approval.hash {
            return Err("INSTALL_PLAN_CHANGED");
        }
        Ok(())
    }
    pub(super) fn validate_layout(&self) -> IResult<()> {
        native(self.control.dir.exact_names(&[
            "aoc-instance.json",
            "binding.json",
            "transactions",
        ]))?;
        self.head()?;
        Ok(())
    }
    #[cfg(test)]
    pub(super) fn create(parent: &Arc<Dir>, bootstrap: &[u8]) -> IResult<Self> {
        Self::create_scoped(parent, bootstrap, "ntfs-owned-test-v1", ENVIRONMENT)
    }
    pub(super) fn create_scoped(
        parent: &Arc<Dir>,
        bootstrap: &[u8],
        ownership: &'static str,
        environment: &'static str,
    ) -> IResult<Self> {
        trust::root_policy(bootstrap)?;
        let control = native(OwnedRoot::create_scoped(
            parent,
            "install-control",
            ownership,
        ))?;
        let instance = native(OwnedRoot::create_scoped(parent, "game-instance", ownership))?;
        let anchor = manifest::hash(bootstrap);
        let bytes = encoded(&Binding {
            format: 2,
            environment: environment.into(),
            anchor: anchor.clone(),
            control: control.receipt.clone(),
            instance: instance.receipt.clone(),
        })?;
        put(&control.dir, "binding.json", &bytes)?;
        let transactions = native(control.dir.child("transactions", true))?;
        Ok(Self {
            control,
            instance,
            transactions,
            anchor,
            binding: manifest::hash(&bytes),
        })
    }
    #[cfg(test)]
    pub(super) fn reopen(parent: &Arc<Dir>, bootstrap: &[u8]) -> IResult<Self> {
        Self::reopen_scoped(parent, bootstrap, "ntfs-owned-test-v1", ENVIRONMENT)
    }
    pub(super) fn reopen_scoped(
        parent: &Arc<Dir>,
        bootstrap: &[u8],
        ownership: &'static str,
        environment: &'static str,
    ) -> IResult<Self> {
        trust::root_policy(bootstrap)?;
        let protection = Arc::new(native(Protection::current())?);
        let file = native(relative(
            parent,
            &mut native(portable_name("install-control"))?,
            true,
            false,
            None,
        ))?;
        let identity = native(inspect(&file, true))?;
        native(protection.check(&file, true))?;
        let dir = Arc::new(Dir {
            file,
            identity,
            _parent: Some(parent.clone()),
            protection: Some(protection),
        });
        let bytes = get(&dir, "binding.json", 16384)?;
        let binding: Binding = decode(&bytes, 16384)?;
        let anchor = manifest::hash(bootstrap);
        if binding.format != 2 || binding.environment != environment || binding.anchor != anchor {
            return Err("INSTALL_BINDING_INVALID");
        }
        let control = native(OwnedRoot::reopen_scoped(
            parent,
            "install-control",
            binding.control,
            ownership,
        ))?;
        let instance = native(OwnedRoot::reopen_scoped(
            parent,
            "game-instance",
            binding.instance,
            ownership,
        ))?;
        let transactions = native(control.dir.child("transactions", false))?;
        let store = Self {
            control,
            instance,
            transactions,
            anchor,
            binding: manifest::hash(&bytes),
        };
        store.head()?;
        Ok(store)
    }
    fn load(&self, n: u64) -> IResult<Tx> {
        let dir = native(self.transactions.child(&format!("tx-{n:05}"), false))?;
        let children = names(&dir, 4)?;
        if !children.contains("journal")
            || !children.contains("staged")
            || !children.contains("backup")
            || children
                .iter()
                .any(|n| !["journal", "staged", "backup", "index.json"].contains(&n.as_str()))
        {
            return Err("INSTALL_JOURNAL_INVALID");
        }
        let journal = native(dir.child("journal", false))?;
        let staged = native(dir.child("staged", false))?;
        let backup = native(dir.child("backup", false))?;
        let bytes = get(&journal, "plan.json", CAP)?;
        let plan: Plan = decode(&bytes, CAP)?;
        if plan.format != 2
            || plan.number != n
            || plan.binding != self.binding
            || plan.anchor != self.anchor
            || !manifest::digest(&plan.new)
            || plan.old.as_ref().is_some_and(|d| !manifest::digest(d))
            || plan.operations.len() > manifest::MAX_FILES
        {
            return Err("INSTALL_PLAN_INVALID");
        }
        let listing = names(&journal, manifest::MAX_FILES * 3 + 8)?;
        let choice = if listing.contains("stage-choice.json") {
            Some(decode(&get(&journal, "stage-choice.json", CAP)?, CAP)?)
        } else {
            None
        };
        let tx = Tx {
            dir,
            journal: journal.clone(),
            staged,
            backup,
            plan,
            hash: manifest::hash(&bytes),
            names: listing,
            choice,
        };
        tx.validate_records()?;
        let staged: BTreeSet<_> = tx
            .plan
            .operations
            .iter()
            .enumerate()
            .filter(|(_, op)| op.new.is_some() && op.action != Action::Reuse)
            .flat_map(|(i, _)| (0..preparation::ATTEMPTS).map(move |a| preparation::filename(i, a)))
            .collect();
        let backups: BTreeSet<_> = tx
            .plan
            .operations
            .iter()
            .enumerate()
            .filter(|(_, op)| op.old.is_some() && op.action != Action::Reuse)
            .map(|(i, _)| format!("{i:05}.old"))
            .collect();
        if !names(&tx.staged, manifest::MAX_FILES * preparation::ATTEMPTS)?.is_subset(&staged)
            || !names(&tx.backup, manifest::MAX_FILES)?.is_subset(&backups)
        {
            return Err("INSTALL_JOURNAL_INVALID");
        }
        Ok(tx)
    }
    fn head(&self) -> IResult<Head> {
        native(self.control.marker())?;
        native(self.instance.marker())?;
        let listing = names(&self.transactions, TRANSACTIONS)?;
        let mut head = Head {
            active: None,
            last: listing.len() as u64,
            pending: None,
            floor: None,
        };
        for n in 1..=head.last {
            if !listing.contains(&format!("tx-{n:05}")) {
                return Err("INSTALL_JOURNAL_INVALID");
            }
            let tx = self.load(n)?;
            if tx.plan.previous != head.active.as_ref().map(|i| i.number) || head.pending.is_some()
            {
                return Err("INSTALL_JOURNAL_INVALID");
            }
            if let Some((pack, channel, sequence, digest)) = &head.floor {
                if &tx.plan.pack != pack
                    || &tx.plan.channel != channel
                    || tx.plan.sequence < *sequence
                    || (tx.plan.sequence == *sequence && &tx.plan.new != digest)
                {
                    return Err("INSTALL_ROLLBACK");
                }
            }
            head.floor = Some((
                tx.plan.pack.clone(),
                tx.plan.channel.clone(),
                tx.plan.sequence,
                tx.plan.new.clone(),
            ));
            if tx.marked("committed") {
                let index: Index = decode(&get(&tx.dir, "index.json", CAP)?, CAP)?;
                if index.format != 1
                    || index.number != n
                    || index.plan != tx.hash
                    || index.manifest != tx.plan.new
                {
                    return Err("INSTALL_INDEX_INVALID");
                }
                validate_index(&index)?;
                head.active = Some(index);
            } else if !tx.marked("cancelled") {
                head.pending = Some(n);
            }
        }
        Ok(head)
    }
    fn context(&self, new: &VerifiedRelease, old: Option<&VerifiedRelease>) -> IResult<()> {
        new.current()?;
        if new.anchor() != self.anchor || old.is_some_and(|r| r.anchor() != self.anchor) {
            return Err("INSTALL_TRUST_MISMATCH");
        }
        if let Some(old) = old {
            let n = new.manifest().manifest();
            let o = old.manifest().manifest();
            if n.pack_id != o.pack_id
                || n.channel != o.channel
                || n.release_sequence < o.release_sequence
                || (n.release_sequence == o.release_sequence
                    && new.manifest().digest() != old.manifest().digest())
            {
                return Err("INSTALL_ROLLBACK");
            }
        }
        Ok(())
    }
    fn validate_previous(
        &self,
        index: Option<&Index>,
        old: Option<&VerifiedRelease>,
    ) -> IResult<()> {
        match (index, old) {
            (None, None) => Ok(()),
            (Some(index), Some(old))
                if index.manifest == old.manifest().digest()
                    && index.entries == entries(old.manifest()) =>
            {
                Ok(())
            }
            _ => Err("INSTALL_INDEX_INVALID"),
        }
    }
    fn begin(
        &mut self,
        new: &VerifiedRelease,
        old: Option<&VerifiedRelease>,
        prepared: &mut Prepared,
        cancel: &Cancel,
        hook: &mut impl FnMut(&str),
    ) -> IResult<Outcome> {
        self.begin_mode(new, old, prepared, cancel, hook, Mode::Update)
    }
    fn plan_mode(
        &self,
        new: &VerifiedRelease,
        old: Option<&VerifiedRelease>,
        mode: Mode,
        cancel: &Cancel,
    ) -> IResult<(Plan, inventory::Snapshot)> {
        self.context(new, old)?;
        cancel.check()?;
        let head = self.head()?;
        if head.pending.is_some() {
            return Err("INSTALL_RECOVERY_REQUIRED");
        }
        if head.last >= TRANSACTIONS as u64 {
            return Err("INSTALL_TRANSACTION_LIMIT");
        }
        self.validate_previous(head.active.as_ref(), old)?;
        if let Some((pack, channel, sequence, digest)) = &head.floor {
            let m = new.manifest().manifest();
            if &m.pack_id != pack
                || &m.channel != channel
                || m.release_sequence < *sequence
                || (m.release_sequence == *sequence && new.manifest().digest() != digest)
            {
                return Err("INSTALL_ROLLBACK");
            }
        }
        let inventory = native(self.instance.inventory(Default::default()))?;
        let before = inventory
            .observed
            .iter()
            .map(|(p, f)| (p.to_ascii_lowercase(), Stamp::observed(f)))
            .collect();
        let (operations, space) = planned(mode, new, old, &before)?;
        for op in &operations {
            // Probe file/parent kinds before changing any managed bytes.
            // Existing files are already held/hashed by inventory. Opening another
            // DELETE-capable handle would conflict with their no-delete sharing.
            if op.old.is_some() {
                continue;
            }
            let current = managed_file(&self.instance.dir, &op.path)?;
            match (&op.old, current) {
                (Some(expected), Some(mut file)) => expected.fingerprint(&mut file)?,
                (None, None) => {}
                _ => return Err("INSTALL_CONTENT_CHANGED"),
            }
        }
        if free_bytes(&self.instance.dir)? < space {
            return Err("INSTALL_NO_SPACE");
        }
        let m = new.manifest().manifest();
        let n = head.last + 1;
        let plan = Plan {
            format: 2,
            mode,
            number: n,
            previous: head.active.as_ref().map(|i| i.number),
            binding: self.binding.clone(),
            anchor: self.anchor.clone(),
            pack: m.pack_id.clone(),
            channel: m.channel.clone(),
            sequence: m.release_sequence,
            old: old.map(|r| r.manifest().digest().into()),
            new: new.manifest().digest().into(),
            before,
            operations,
            space,
        };
        let bytes = encoded(&plan)?;
        let hash = manifest::hash(&bytes);
        let index = Index {
            format: 1,
            number: n,
            plan: hash.clone(),
            manifest: plan.new.clone(),
            entries: entries(new.manifest()),
        };
        let estimate = bytes
            .len()
            .checked_add(encoded(&index)?.len())
            .and_then(|s| s.checked_add((plan.operations.len() * 3 + 5) * 256))
            .and_then(|s| s.checked_add(preparation::choice_budget(&plan, &hash).ok()?))
            .ok_or("INSTALL_JOURNAL_LIMIT")?;
        if estimate > CAP {
            return Err("INSTALL_JOURNAL_LIMIT");
        }
        Ok((plan, inventory))
    }
    fn begin_mode(
        &mut self,
        new: &VerifiedRelease,
        old: Option<&VerifiedRelease>,
        prepared: &mut Prepared,
        cancel: &Cancel,
        hook: &mut impl FnMut(&str),
        mode: Mode,
    ) -> IResult<Outcome> {
        self.begin_approval(new, old, prepared, cancel, hook, mode, None)
    }
    pub(super) fn begin_confirmed(
        &mut self,
        approval: Approval,
        new: &VerifiedRelease,
        old: Option<&VerifiedRelease>,
        prepared: &mut Prepared,
        cancel: &Cancel,
        hook: &mut impl FnMut(&str),
    ) -> IResult<Outcome> {
        self.begin_approval(
            new,
            old,
            prepared,
            cancel,
            hook,
            approval.mode,
            Some(approval.hash),
        )
    }
    fn begin_approval(
        &mut self,
        new: &VerifiedRelease,
        old: Option<&VerifiedRelease>,
        prepared: &mut Prepared,
        cancel: &Cancel,
        hook: &mut impl FnMut(&str),
        mode: Mode,
        approved: Option<String>,
    ) -> IResult<Outcome> {
        let (plan, mut inventory) = self.plan_mode(new, old, mode, cancel)?;
        if prepared.manifest != new.manifest().digest() {
            return Err("INSTALL_CACHE_MISMATCH");
        }
        let bytes = encoded(&plan)?;
        let hash = manifest::hash(&bytes);
        if approved.as_ref().is_some_and(|expected| *expected != hash) {
            return Err("INSTALL_PLAN_CHANGED");
        }
        cancel.check()?;
        let n = plan.number;
        let dir = native(self.transactions.child(&format!("tx-{n:05}"), true))?;
        let journal = native(dir.child("journal", true))?;
        let staged = native(dir.child("staged", true))?;
        let backup = native(dir.child("backup", true))?;
        put(&journal, "plan.json", &bytes)?;
        let mut tx = Tx {
            dir,
            journal,
            staged,
            backup,
            plan,
            hash,
            names: BTreeSet::from(["plan.json".into()]),
            choice: None,
        };
        hook("plan-written");
        if let Err(e) = tx.prepare(prepared, cancel, hook) {
            if e == "CANCELLED" {
                tx.record("cancel-request")?;
                tx.record("cancelled")?;
                return Ok(Outcome::Cancelled);
            }
            return Err(e);
        }
        native(inventory.validate())?;
        drop(inventory);
        new.current()?;
        tx.record("prepared")?;
        hook("prepared");
        self.apply(&mut tx, new, cancel, hook)
    }
    pub(super) fn recover(
        &mut self,
        new: &VerifiedRelease,
        old: Option<&VerifiedRelease>,
        cancel: &Cancel,
        hook: &mut impl FnMut(&str),
    ) -> IResult<Outcome> {
        self.context(new, old)?;
        let head = self.head()?;
        let n = head.pending.unwrap_or(head.last);
        if n == 0 {
            return Err("INSTALL_NOTHING_TO_RECOVER");
        }
        let mut tx = self.load(n)?;
        let m = new.manifest().manifest();
        if tx.plan.old != old.map(|r| r.manifest().digest().to_owned())
            || tx.plan.new != new.manifest().digest()
            || tx.plan.pack != m.pack_id
            || tx.plan.channel != m.channel
            || tx.plan.sequence != m.release_sequence
        {
            return Err("INSTALL_TRUST_MISMATCH");
        }
        let (ops, space) = planned(tx.plan.mode, new, old, &tx.plan.before)?;
        if tx.plan.operations != ops || tx.plan.space != space {
            return Err("INSTALL_PLAN_INVALID");
        }
        if tx.marked("cancelled") {
            return Ok(Outcome::Cancelled);
        }
        if !tx.marked("prepared") {
            return Err("INSTALL_PREPARATION_REQUIRED");
        }
        if !tx.marked("committed") {
            self.validate_previous(head.active.as_ref(), old)?;
        }
        self.preflight(&tx)?;
        self.apply(&mut tx, new, cancel, hook)
    }
    fn preflight(&self, tx: &Tx) -> IResult<()> {
        let mut inventory = native(self.instance.inventory(Default::default()))?;
        for (path, observed) in &inventory.observed {
            let path = path.to_ascii_lowercase();
            let actual = Stamp::observed(observed);
            let op = tx
                .plan
                .operations
                .iter()
                .find(|op| op.path == path)
                .ok_or("INSTALL_FOREIGN_OR_MODIFIED")?;
            if op.old.as_ref() != Some(&actual) && op.new.as_ref() != Some(&actual) {
                return Err("INSTALL_FOREIGN_OR_MODIFIED");
            }
        }
        native(inventory.validate())?;
        drop(inventory);
        for (i, op) in tx.plan.operations.iter().enumerate() {
            let current = managed_file(&self.instance.dir, &op.path)?;
            let staged = if op.new.is_some() && op.action != Action::Reuse {
                flat_file(&tx.staged, &tx.staged_name(i)?)?
            } else {
                None
            };
            let backup = flat_file(&tx.backup, &format!("{i:05}.old"))?;
            match (&op.action, current, staged, backup) {
                (Action::Reuse, Some(mut f), None, None) => op
                    .new
                    .as_ref()
                    .ok_or("INSTALL_PLAN_INVALID")?
                    .fingerprint(&mut f)?,
                (Action::Install, None, Some(mut s), None) => op
                    .new
                    .as_ref()
                    .ok_or("INSTALL_PLAN_INVALID")?
                    .fingerprint(&mut s)?,
                (Action::Install, Some(mut f), None, None)
                    if tx.marked(&format!("intent-{i:05}")) =>
                {
                    op.new
                        .as_ref()
                        .ok_or("INSTALL_PLAN_INVALID")?
                        .fingerprint(&mut f)?
                }
                (Action::Replace, Some(mut f), Some(mut s), None) => {
                    op.old
                        .as_ref()
                        .ok_or("INSTALL_PLAN_INVALID")?
                        .fingerprint(&mut f)?;
                    op.new
                        .as_ref()
                        .ok_or("INSTALL_PLAN_INVALID")?
                        .fingerprint(&mut s)?;
                }
                (Action::Replace, None, Some(mut s), Some(mut b))
                    if tx.marked(&format!("intent-{i:05}")) =>
                {
                    op.old
                        .as_ref()
                        .ok_or("INSTALL_PLAN_INVALID")?
                        .fingerprint(&mut b)?;
                    op.new
                        .as_ref()
                        .ok_or("INSTALL_PLAN_INVALID")?
                        .fingerprint(&mut s)?;
                }
                (Action::Replace, Some(mut f), None, Some(mut b))
                    if tx.marked(&format!("intent-{i:05}")) =>
                {
                    op.old
                        .as_ref()
                        .ok_or("INSTALL_PLAN_INVALID")?
                        .fingerprint(&mut b)?;
                    op.new
                        .as_ref()
                        .ok_or("INSTALL_PLAN_INVALID")?
                        .fingerprint(&mut f)?;
                }
                (Action::Remove, Some(mut f), None, None) => op
                    .old
                    .as_ref()
                    .ok_or("INSTALL_PLAN_INVALID")?
                    .fingerprint(&mut f)?,
                (Action::Remove, None, None, Some(mut b))
                    if tx.marked(&format!("intent-{i:05}")) =>
                {
                    op.old
                        .as_ref()
                        .ok_or("INSTALL_PLAN_INVALID")?
                        .fingerprint(&mut b)?
                }
                _ => return Err("INSTALL_AMBIGUOUS_STATE"),
            }
        }
        Ok(())
    }
    fn apply(
        &mut self,
        tx: &mut Tx,
        new: &VerifiedRelease,
        cancel: &Cancel,
        hook: &mut impl FnMut(&str),
    ) -> IResult<Outcome> {
        let applying = tx.marked("index-ready")
            || (0..tx.plan.operations.len()).any(|i| tx.marked(&format!("intent-{i:05}")));
        if cancel.check().is_err() && !tx.marked("committed") {
            tx.record("cancel-request")?;
        }
        if tx.marked("cancel-request") && !applying && !tx.marked("committed") {
            tx.record("cancelled")?;
            return Ok(Outcome::Cancelled);
        }
        if tx.marked("committed") {
            self.validate_final(new)?;
            return Ok(Outcome::Committed {
                cancellation_deferred: tx.marked("cancel-request"),
            });
        }
        for i in 0..tx.plan.operations.len() {
            new.current()?;
            if cancel.check().is_err() {
                tx.record("cancel-request")?;
                if !(0..tx.plan.operations.len()).any(|j| tx.marked(&format!("intent-{j:05}"))) {
                    tx.record("cancelled")?;
                    return Ok(Outcome::Cancelled);
                }
            }
            let op = tx.plan.operations[i].clone();
            tx.record(&format!("intent-{i:05}"))?;
            hook(&format!("intent:{i:05}"));
            let mut current = managed_file(&self.instance.dir, &op.path)?;
            if op.action == Action::Reuse {
                op.new
                    .as_ref()
                    .ok_or("INSTALL_PLAN_INVALID")?
                    .fingerprint(current.as_mut().ok_or("INSTALL_CONTENT_CHANGED")?)?;
            } else if let Some(old) = &op.old {
                let backup_name = format!("{i:05}.old");
                let backup = flat_file(&tx.backup, &backup_name)?;
                if let Some(mut backup) = backup {
                    old.fingerprint(&mut backup)?;
                    if let Some(file) = current.as_mut() {
                        op.new
                            .as_ref()
                            .ok_or("INSTALL_CONTENT_CHANGED")?
                            .fingerprint(file)?;
                    }
                } else {
                    let file = current.as_mut().ok_or("INSTALL_CONTENT_CHANGED")?;
                    old.fingerprint(file)?;
                    native(file.rename_empty(&tx.backup, &backup_name))?;
                    drop(current.take());
                    hook(&format!("after-backup:{i:05}"));
                }
            } else if let Some(file) = current.as_mut() {
                op.new
                    .as_ref()
                    .ok_or("INSTALL_PLAN_INVALID")?
                    .fingerprint(file)?;
            }
            tx.record(&format!("backed-{i:05}"))?;
            hook(&format!("backed:{i:05}"));
            if op.action != Action::Reuse {
                if let Some(expected) = &op.new {
                    let staged_name = tx.staged_name(i)?;
                    let staged = flat_file(&tx.staged, &staged_name)?;
                    match (current.take(), staged) {
                        (None, Some(mut staged)) => {
                            expected.fingerprint(&mut staged)?;
                            let (dir, name) = parent(&self.instance.dir, &op.path, true)?
                                .ok_or("INSTALL_PLAN_INVALID")?;
                            native(staged.rename_empty(&dir, &name))?;
                            drop(staged);
                            hook(&format!("after-install:{i:05}"));
                        }
                        (Some(mut current), None) => expected.fingerprint(&mut current)?,
                        _ => return Err("INSTALL_AMBIGUOUS_STATE"),
                    }
                } else if managed_file(&self.instance.dir, &op.path)?.is_some() {
                    return Err("INSTALL_CONTENT_CHANGED");
                }
            }
            tx.record(&format!("done-{i:05}"))?;
            hook(&format!("done:{i:05}"));
        }
        new.current()?;
        self.validate_final(new)?;
        let index = Index {
            format: 1,
            number: tx.plan.number,
            plan: tx.hash.clone(),
            manifest: tx.plan.new.clone(),
            entries: entries(new.manifest()),
        };
        let bytes = encoded(&index)?;
        if has(&tx.dir, "index.json")? {
            if get(&tx.dir, "index.json", CAP)? != bytes {
                return Err("INSTALL_INDEX_INVALID");
            }
        } else {
            put(&tx.dir, "index.json", &bytes)?;
        }
        hook("index-written");
        tx.record("index-ready")?;
        hook("index-ready");
        new.current()?;
        if cancel.check().is_err() {
            tx.record("cancel-request")?;
        }
        tx.record("committed")?;
        hook("committed");
        self.head()?;
        Ok(Outcome::Committed {
            cancellation_deferred: tx.marked("cancel-request"),
        })
    }
    fn validate_final(&self, new: &VerifiedRelease) -> IResult<()> {
        let mut snapshot = native(self.instance.inventory(Default::default()))?;
        let observed: BTreeMap<_, _> = snapshot
            .observed
            .iter()
            .map(|(p, f)| (p.to_ascii_lowercase(), Stamp::observed(f)))
            .collect();
        if observed != entries(new.manifest()) {
            return Err("INSTALL_FINAL_MISMATCH");
        }
        native(snapshot.validate())
    }
}
fn validate_index(index: &Index) -> IResult<()> {
    if index.entries.len() > manifest::MAX_FILES || !manifest::digest(&index.manifest) {
        return Err("INSTALL_INDEX_INVALID");
    }
    manifest::paths(index.entries.keys().map(String::as_str))?;
    let mut total = 0u64;
    for (path, stamp) in &index.entries {
        if !manifest::managed(path)
            || path != &path.to_ascii_lowercase()
            || !manifest::digest(&stamp.sha256)
        {
            return Err("INSTALL_INDEX_INVALID");
        }
        total = total
            .checked_add(stamp.length)
            .ok_or("INSTALL_INDEX_INVALID")?;
    }
    if total > manifest::MAX_BYTES {
        return Err("INSTALL_INDEX_INVALID");
    }
    Ok(())
}
fn operations(
    new: &VerifiedRelease,
    old: Option<&VerifiedRelease>,
    before: &BTreeMap<String, Stamp>,
) -> IResult<(Vec<Op>, u64)> {
    let old_entries = old.map(|r| entries(r.manifest())).unwrap_or_default();
    let new_entries = entries(new.manifest());
    let union: BTreeSet<_> = old_entries
        .keys()
        .chain(new_entries.keys())
        .map(String::as_str)
        .collect();
    manifest::paths(union.into_iter()).map_err(|_| "INSTALL_PATH_CONFLICT")?;
    if before.iter().any(|(p, s)| old_entries.get(p) != Some(s)) {
        return Err("INSTALL_FOREIGN_OR_MODIFIED");
    }
    let inventory = before
        .iter()
        .map(|(p, s)| {
            (
                p.clone(),
                Fingerprint {
                    length: s.length,
                    sha256: s.sha256.clone(),
                },
            )
        })
        .collect();
    let cache = new_entries.values().map(|s| s.sha256.clone()).collect();
    let pure = manifest::plan(
        new.manifest(),
        old.map(VerifiedRelease::manifest),
        &inventory,
        &cache,
    )?;
    if pure.operations.len() > manifest::MAX_FILES {
        return Err("INSTALL_JOURNAL_LIMIT");
    }
    let ops = pure
        .operations
        .into_iter()
        .map(|op| {
            let (action, path) = match op {
                Operation::Install(p) => (Action::Install, p),
                Operation::Reuse(p) => (Action::Reuse, p),
                Operation::Replace(p) => (Action::Replace, p),
                Operation::Remove(p) => (Action::Remove, p),
            };
            let path = path.to_ascii_lowercase();
            Op {
                action,
                path: path.clone(),
                old: before.get(&path).cloned(),
                new: new_entries.get(&path).cloned(),
            }
        })
        .collect();
    Ok((ops, pure.required_space))
}
fn planned(
    mode: Mode,
    new: &VerifiedRelease,
    old: Option<&VerifiedRelease>,
    before: &BTreeMap<String, Stamp>,
) -> IResult<(Vec<Op>, u64)> {
    match mode {
        Mode::Update => operations(new, old, before),
        Mode::Repair => repair::operations(new, old, before),
    }
}
fn stage(
    source: &mut HeldFile,
    dir: &Arc<Dir>,
    name: &str,
    stamp: &Stamp,
    cancel: &Cancel,
    hook: &mut impl FnMut(&str),
    phase: &str,
) -> IResult<()> {
    cancel.check()?;
    stamp.fingerprint(source)?;
    let mut target = native(dir.file(name, true))?;
    hook(&format!("stage-created:{phase}"));
    io(source.file.seek(SeekFrom::Start(0)))?;
    let mut buffer = [0u8; 64 * 1024];
    let mut total = 0u64;
    loop {
        cancel.check()?;
        native(source.unchanged())?;
        native(target.unchanged())?;
        let read = io(source.file.read(&mut buffer))?;
        if read == 0 {
            break;
        }
        total = total
            .checked_add(read as u64)
            .ok_or("INSTALL_CONTENT_CHANGED")?;
        if total > stamp.length {
            return Err("INSTALL_CONTENT_CHANGED");
        }
        io(target.file.write_all(&buffer[..read]))?;
        hook(&format!("stage-chunk:{phase}"));
    }
    if total != stamp.length {
        return Err("INSTALL_CONTENT_CHANGED");
    }
    io(target.file.sync_all())?;
    stamp.fingerprint(&mut target)?;
    stamp.fingerprint(source)?;
    hook(&format!("stage-verified:{phase}"));
    cancel.check()
}
