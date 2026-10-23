//! One bounded immutable updater intent. No installer launch, orphan deletion or IPC.
//! Caller-supplied image observations must originate in native release/installed validation.
use super::*;
use crate::pack::{manifest, strict_json};
use serde::{Deserialize, Serialize};
use windows_sys::Win32::{Foundation::*, System::Threading::*};

const NAME: &str = "AnimeOnCraft.LauncherUpdateJournalDevelopment.v1";
const ENVIRONMENT: &str = "launcher-update-journal-development-v1";
const CAP: usize = 16384;
const BASE: [&str; 4] = [
    "aoc-instance.json",
    "binding.json",
    "lease.lock",
    "ready.json",
];
type JResult<T> = std::result::Result<T, &'static str>;
fn checked<T>(value: Result<T>) -> JResult<T> {
    value.map_err(|_| "UPDATE_JOURNAL_INVALID")
}
fn encode<T: Serialize>(value: &T) -> JResult<Vec<u8>> {
    let bytes = serde_json::to_vec(value).map_err(|_| "UPDATE_JOURNAL_INVALID")?;
    if bytes.len() > CAP {
        return Err("UPDATE_JOURNAL_INVALID");
    }
    Ok(bytes)
}
fn decode<T: serde::de::DeserializeOwned>(bytes: &[u8]) -> JResult<T> {
    let value = strict_json::parse(bytes, CAP).map_err(|_| "UPDATE_JOURNAL_INVALID")?;
    serde_json::from_value(value).map_err(|_| "UPDATE_JOURNAL_INVALID")
}
fn read(dir: &Arc<Dir>, name: &str) -> JResult<(HeldFile, Vec<u8>)> {
    let mut file = checked(dir.file(name, false))?;
    let mut bytes = Vec::new();
    (&mut file.file)
        .take(CAP as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "UPDATE_JOURNAL_INVALID")?;
    if bytes.len() > CAP {
        return Err("UPDATE_JOURNAL_INVALID");
    }
    checked(file.fingerprint(bytes.len() as u64, &manifest::hash(&bytes)))?;
    Ok((file, bytes))
}
fn put<T: Serialize>(dir: &Arc<Dir>, name: &str, value: &T) -> JResult<HeldFile> {
    let bytes = encode(value)?;
    let mut file = checked(dir.file(name, true))?;
    checked(file.write_new(&bytes))?; // write_new calls sync_all; never truncates/replaces.
    checked(file.fingerprint(bytes.len() as u64, &manifest::hash(&bytes)))?;
    Ok(file)
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Binding {
    format: u8,
    environment: String,
    parent: Identity,
    local: String,
    root: owned::Receipt,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Lease {
    format: u8,
    root: Identity,
    local: String,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Ready {
    format: u8,
    binding: Seal,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Seal {
    identity: Identity,
    length: u64,
    sha256: String,
}
impl Seal {
    fn held(file: &HeldFile) -> JResult<Self> {
        let (length, sha256) = file.verified.clone().ok_or("UPDATE_JOURNAL_INVALID")?;
        Ok(Self {
            identity: file.identity.clone(),
            length,
            sha256,
        })
    }
    fn check(&self, file: &mut HeldFile) -> JResult<()> {
        if self.identity != file.identity
            || self.length > CAP as u64
            || !manifest::digest(&self.sha256)
        {
            return Err("UPDATE_JOURNAL_INVALID");
        }
        checked(file.fingerprint(self.length, &self.sha256))
    }
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Image {
    pub(crate) version: String,
    pub(crate) bytes: u64,
    pub(crate) sha256: String,
}
impl Image {
    fn validate(&self) -> JResult<semver::Version> {
        let version =
            semver::Version::parse(&self.version).map_err(|_| "UPDATE_JOURNAL_INVALID")?;
        if self.version.len() > 48
            || !version.pre.is_empty()
            || !version.build.is_empty()
            || version.to_string() != self.version
            || !(512..=64 * 1024 * 1024).contains(&self.bytes)
            || !manifest::digest(&self.sha256)
        {
            return Err("UPDATE_JOURNAL_INVALID");
        }
        Ok(version)
    }
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Intent {
    pub(crate) product: String,
    pub(crate) target: String,
    pub(crate) old: Image,
    pub(crate) new: Image,
}
impl Intent {
    fn validate(&self) -> JResult<()> {
        if self.old.validate()? >= self.new.validate()?
            || self.old.sha256 == self.new.sha256
            || !manifest::digest(&self.target)
            || self.product.is_empty()
            || self.product.len() > 200
            || self
                .product
                .chars()
                .any(|c| c < ' ' || c == '\\' || c == '"')
        {
            return Err("UPDATE_JOURNAL_INVALID");
        }
        Ok(())
    }
}
// Native-only observation; deliberately not Deserialize and never accepted from IPC.
pub(crate) struct Observation {
    pub(crate) product: String,
    pub(crate) target: String,
    pub(crate) image: Image,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct StageSeal {
    name: String,
    root: owned::Receipt,
    parent: Identity,
    file: Identity,
    bytes: u64,
    sha256: String,
}
impl StageSeal {
    fn capture(stage: &mut Stage) -> JResult<Self> {
        stage.validate().map_err(|_| "UPDATE_JOURNAL_INVALID")?;
        let name = stage
            .path
            .parent()
            .and_then(|p| p.file_name())
            .and_then(|n| n.to_str())
            .ok_or("UPDATE_JOURNAL_INVALID")?
            .to_owned();
        let file = stage.file.as_ref().ok_or("UPDATE_JOURNAL_INVALID")?;
        let (bytes, sha256) = file.verified.clone().ok_or("UPDATE_JOURNAL_INVALID")?;
        Ok(Self {
            name,
            root: stage.root.receipt.clone(),
            parent: stage
                .root
                .dir
                ._parent
                .as_ref()
                .ok_or("UPDATE_JOURNAL_INVALID")?
                .identity
                .clone(),
            file: file.identity.clone(),
            bytes,
            sha256,
        })
    }
    fn fields(&self, parent: &Arc<Dir>) -> JResult<()> {
        let suffix = self
            .name
            .strip_prefix(PREFIX)
            .ok_or("UPDATE_JOURNAL_INVALID")?;
        if suffix.len() != 32
            || !suffix
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            || self.parent != parent.identity
            || !(512..=64 * 1024 * 1024).contains(&self.bytes)
            || !manifest::digest(&self.sha256)
        {
            return Err("UPDATE_JOURNAL_INVALID");
        }
        Ok(())
    }
    fn verify(&self, parent: &Arc<Dir>) -> JResult<()> {
        self.fields(parent)?;
        let root = checked(owned::OwnedRoot::reopen_scoped(
            parent,
            &self.name,
            self.root.clone(),
            "launcher-update-stage-v1",
        ))?;
        checked(root.dir.exact_names(&["aoc-instance.json", "setup.exe"]))?;
        let mut file = checked(root.dir.file("setup.exe", false))?;
        if file.identity != self.file {
            return Err("UPDATE_JOURNAL_INVALID");
        }
        checked(file.fingerprint(self.bytes, &self.sha256))
    }
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Pending {
    format: u8,
    local: String,
    intent: Intent,
    stage: StageSeal,
}
#[derive(Clone, Copy, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Witness {
    pid: u32,
    created: u64,
}
#[derive(PartialEq, Eq, Debug)]
enum Process {
    Alive,
    Absent,
    Unknown,
}
struct ProcessHandle(HANDLE);
impl Drop for ProcessHandle {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.0);
        }
    }
}
impl Witness {
    fn open(pid: u32) -> std::result::Result<ProcessHandle, Process> {
        if pid == 0 {
            return Err(Process::Unknown);
        }
        let handle =
            unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, 0, pid) };
        if handle.is_null() {
            return Err(if unsafe { GetLastError() } == ERROR_INVALID_PARAMETER {
                Process::Absent
            } else {
                Process::Unknown
            });
        }
        Ok(ProcessHandle(handle))
    }
    fn time(handle: &ProcessHandle) -> JResult<u64> {
        let mut created = FILETIME::default();
        let mut exit = FILETIME::default();
        let mut kernel = FILETIME::default();
        let mut user = FILETIME::default();
        if unsafe { GetProcessTimes(handle.0, &mut created, &mut exit, &mut kernel, &mut user) }
            == 0
        {
            return Err("UPDATE_JOURNAL_INVALID");
        }
        Ok(((created.dwHighDateTime as u64) << 32) | created.dwLowDateTime as u64)
    }
    fn capture(pid: u32) -> JResult<Self> {
        let handle = Self::open(pid).map_err(|_| "UPDATE_JOURNAL_INVALID")?;
        let created = Self::time(&handle)?;
        if created == 0 || unsafe { WaitForSingleObject(handle.0, 0) } != WAIT_TIMEOUT {
            return Err("UPDATE_JOURNAL_INVALID");
        }
        Ok(Self { pid, created })
    }
    fn probe(&self) -> Process {
        let handle = match Self::open(self.pid) {
            Ok(h) => h,
            Err(state) => return state,
        };
        match Self::time(&handle) {
            Ok(time) if time != self.created => Process::Absent, // PID reused: original process ended.
            Err(_) => Process::Unknown,
            _ => match unsafe { WaitForSingleObject(handle.0, 0) } {
                WAIT_OBJECT_0 => Process::Absent,
                WAIT_TIMEOUT => Process::Alive,
                _ => Process::Unknown,
            },
        }
    }
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Spawned {
    format: u8,
    pending: String,
    witness: Witness,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum Outcome {
    Applied,
    NotStarted,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Resolution {
    format: u8,
    pending: String,
    outcome: Outcome,
    observed: Image,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Status {
    Empty,
    Pending,
    WaitingForInstaller,
    RecoveryRequired,
    Applied,
    NotStarted,
}
pub(crate) struct Journal {
    root: owned::OwnedRoot,
    parent: Arc<Dir>,
    local: String,
    held: Vec<HeldFile>,
    local_holder: Option<Arc<crate::pack::local::LocalInstance>>,
}
struct State {
    pending: Option<(Pending, String)>,
    spawned: Option<Spawned>,
    result: Option<Resolution>,
}
impl Journal {
    pub(in crate::pack) fn hold_local(&mut self, local: Arc<crate::pack::local::LocalInstance>) {
        self.local_holder = Some(local);
    }
    pub(in crate::pack::ntfs) fn open(
        parent: &Arc<Dir>,
        local: &str,
        create: bool,
    ) -> JResult<Option<Self>> {
        Self::open_with(parent, local, create, &mut |_| {})
    }
    fn open_with(
        parent: &Arc<Dir>,
        local: &str,
        create: bool,
        hook: &mut impl FnMut(&str),
    ) -> JResult<Option<Self>> {
        if !manifest::digest(local) {
            return Err("UPDATE_JOURNAL_INVALID");
        }
        checked(parent.unchanged())?;
        let existing = relative(
            parent,
            &mut checked(portable_name(NAME))?,
            true,
            false,
            None,
        );
        match existing {
            Err(Error::Nt(code)) if code as u32 == 0xc0000034 => {
                if !create {
                    return Ok(None);
                }
                let root = checked(owned::OwnedRoot::create_scoped(parent, NAME, ENVIRONMENT))?;
                hook("root-created");
                let lease = put(
                    &root.dir,
                    "lease.lock",
                    &Lease {
                        format: 1,
                        root: root.dir.identity.clone(),
                        local: local.into(),
                    },
                )?;
                hook("lease-created");
                let binding = put(
                    &root.dir,
                    "binding.json",
                    &Binding {
                        format: 1,
                        environment: ENVIRONMENT.into(),
                        parent: parent.identity.clone(),
                        local: local.into(),
                        root: root.receipt.clone(),
                    },
                )?;
                hook("binding-created");
                let ready = put(
                    &root.dir,
                    "ready.json",
                    &Ready {
                        format: 1,
                        binding: Seal::held(&binding)?,
                    },
                )?;
                hook("root-ready");
                let journal = Self {
                    root,
                    parent: parent.clone(),
                    local: local.into(),
                    held: vec![lease, binding, ready],
                    local_holder: None,
                };
                journal.state()?;
                Ok(Some(journal))
            }
            Ok(file) => {
                let protection = Arc::new(checked(Protection::current())?);
                checked(protection.check(&file, true))?;
                let identity = checked(inspect(&file, true))?;
                if identity.volume != parent.identity.volume {
                    return Err("UPDATE_JOURNAL_INVALID");
                }
                let dir = Arc::new(Dir {
                    file,
                    identity,
                    _parent: Some(parent.clone()),
                    protection: Some(protection),
                });
                let lease = match dir.file("lease.lock", false) {
                    Err(Error::Nt(code)) if code as u32 == 0xc0000043 => {
                        return Err("UPDATE_JOURNAL_BUSY")
                    }
                    value => checked(value)?,
                };
                let mut lease = lease;
                let mut bytes = Vec::new();
                (&mut lease.file)
                    .take(CAP as u64 + 1)
                    .read_to_end(&mut bytes)
                    .map_err(|_| "UPDATE_JOURNAL_INVALID")?;
                let value: Lease = decode(&bytes)?;
                if value.format != 1 || value.root != dir.identity || value.local != local {
                    return Err("UPDATE_JOURNAL_INVALID");
                }
                checked(lease.fingerprint(bytes.len() as u64, &manifest::hash(&bytes)))?;
                let (mut binding, bytes) = read(&dir, "binding.json")?;
                let value: Binding = decode(&bytes)?;
                if value.format != 1
                    || value.environment != ENVIRONMENT
                    || value.local != local
                    || value.parent != parent.identity
                {
                    return Err("UPDATE_JOURNAL_INVALID");
                }
                let root = checked(owned::OwnedRoot::reopen_scoped(
                    parent,
                    NAME,
                    value.root,
                    ENVIRONMENT,
                ))?;
                if root.dir.identity != dir.identity {
                    return Err("UPDATE_JOURNAL_INVALID");
                }
                let (ready, bytes) = read(&dir, "ready.json")?;
                let value: Ready = decode(&bytes)?;
                if value.format != 1 {
                    return Err("UPDATE_JOURNAL_INVALID");
                }
                value.binding.check(&mut binding)?;
                let journal = Self {
                    root,
                    parent: parent.clone(),
                    local: local.into(),
                    held: vec![lease, binding, ready],
                    local_holder: None,
                };
                journal.state()?;
                Ok(Some(journal))
            }
            Err(_) => Err("UPDATE_JOURNAL_INVALID"),
        }
    }
    fn sealed<T: serde::de::DeserializeOwned>(
        &self,
        name: &str,
        proof: &str,
    ) -> JResult<(T, String)> {
        let (mut file, bytes) = read(&self.root.dir, name)?;
        let (_, seal) = read(&self.root.dir, proof)?;
        let seal: Seal = decode(&seal)?;
        seal.check(&mut file)?;
        Ok((decode(&bytes)?, seal.sha256))
    }
    fn write_sealed<T: Serialize>(
        &self,
        name: &str,
        proof: &str,
        value: &T,
        hook: &mut impl FnMut(&str),
    ) -> JResult<()> {
        let file = put(&self.root.dir, name, value)?;
        hook(name);
        let _seal = put(&self.root.dir, proof, &Seal::held(&file)?)?;
        hook(proof);
        Ok(())
    }
    fn state(&self) -> JResult<State> {
        if self
            .local_holder
            .as_ref()
            .is_some_and(|local| local.snapshot().status != crate::pack::local::Status::Ready)
        {
            return Err("UPDATE_LOCAL_UNAVAILABLE");
        }
        checked(self.parent.unchanged())?;
        checked(self.root.dir.unchanged())?;
        self.root.marker().map_err(|_| "UPDATE_JOURNAL_INVALID")?;
        for file in &self.held {
            checked(file.unchanged())?;
        }
        let names: std::collections::BTreeSet<_> = checked(
            self.root
                .dir
                .entries(11, Instant::now() + Duration::from_secs(5)),
        )?
        .into_iter()
        .map(|e| e.name)
        .collect();
        let mut expected: std::collections::BTreeSet<String> =
            BASE.into_iter().map(str::to_owned).collect();
        let has_pending = names.contains("pending.json");
        if has_pending {
            expected.extend(["pending.json".into(), "pending-ready.json".into()]);
        }
        let has_spawned = names.contains("spawned.json");
        if has_spawned {
            expected.extend(["spawned.json".into(), "spawn-ready.json".into()]);
        }
        let has_result = names.contains("result.json");
        if has_result {
            expected.extend(["result.json".into(), "result-ready.json".into()]);
        }
        if names != expected || (!has_pending && (has_spawned || has_result)) {
            return Err("UPDATE_JOURNAL_INVALID");
        }
        let pending = if has_pending {
            let (value, hash): (Pending, _) = self.sealed("pending.json", "pending-ready.json")?;
            if value.format != 1 || value.local != self.local {
                return Err("UPDATE_JOURNAL_INVALID");
            }
            value.intent.validate()?;
            value.stage.fields(&self.parent)?;
            Some((value, hash))
        } else {
            None
        };
        let spawned = if has_spawned {
            let (value, _): (Spawned, _) = self.sealed("spawned.json", "spawn-ready.json")?;
            if value.format != 1
                || Some(value.pending.as_str()) != pending.as_ref().map(|p| p.1.as_str())
                || value.witness.pid == 0
                || value.witness.created == 0
            {
                return Err("UPDATE_JOURNAL_INVALID");
            }
            Some(value)
        } else {
            None
        };
        let result = if has_result {
            let (value, _): (Resolution, _) = self.sealed("result.json", "result-ready.json")?;
            let p = pending.as_ref().ok_or("UPDATE_JOURNAL_INVALID")?;
            if value.format != 1
                || value.pending != p.1
                || match value.outcome {
                    Outcome::Applied => spawned.is_none() || value.observed != p.0.intent.new,
                    Outcome::NotStarted => spawned.is_some() || value.observed != p.0.intent.old,
                }
            {
                return Err("UPDATE_JOURNAL_INVALID");
            }
            Some(value)
        } else {
            None
        };
        Ok(State {
            pending,
            spawned,
            result,
        })
    }
    pub(crate) fn begin(&mut self, stage: &mut Stage, intent: Intent) -> JResult<()> {
        self.begin_with(stage, intent, &mut |_| {})
    }
    fn begin_with(
        &mut self,
        stage: &mut Stage,
        intent: Intent,
        hook: &mut impl FnMut(&str),
    ) -> JResult<()> {
        let state = self.state()?;
        if state.pending.is_some() {
            return Err("UPDATE_JOURNAL_LIMIT");
        }
        intent.validate()?;
        let stage = StageSeal::capture(stage)?;
        stage.fields(&self.parent)?;
        self.write_sealed(
            "pending.json",
            "pending-ready.json",
            &Pending {
                format: 1,
                local: self.local.clone(),
                intent,
                stage,
            },
            hook,
        )?;
        self.state()?;
        Ok(())
    }
    pub(crate) fn spawned(&mut self, pid: u32) -> JResult<()> {
        self.spawned_with(pid, &mut |_| {})
    }
    fn spawned_with(&mut self, pid: u32, hook: &mut impl FnMut(&str)) -> JResult<()> {
        let state = self.state()?;
        if state.spawned.is_some() || state.result.is_some() {
            return Err("UPDATE_JOURNAL_INVALID");
        }
        let pending = state.pending.ok_or("UPDATE_JOURNAL_INVALID")?.1;
        let witness = Witness::capture(pid)?;
        self.write_sealed(
            "spawned.json",
            "spawn-ready.json",
            &Spawned {
                format: 1,
                pending,
                witness,
            },
            hook,
        )?;
        self.state()?;
        Ok(())
    }
    // Only call after native CreateProcess returned an error; never infer from a missing PID.
    pub(crate) fn spawn_failed(&mut self) -> JResult<()> {
        let state = self.state()?;
        if state.spawned.is_some() || state.result.is_some() {
            return Err("UPDATE_JOURNAL_INVALID");
        }
        let (pending, hash) = state.pending.ok_or("UPDATE_JOURNAL_INVALID")?;
        self.write_sealed(
            "result.json",
            "result-ready.json",
            &Resolution {
                format: 1,
                pending: hash,
                outcome: Outcome::NotStarted,
                observed: pending.intent.old,
            },
            &mut |_| {},
        )?;
        self.state()?;
        Ok(())
    }
    pub(crate) fn status(&self) -> JResult<Status> {
        let state = self.state()?;
        Ok(match state.result.map(|r| r.outcome) {
            Some(Outcome::Applied) => Status::Applied,
            Some(Outcome::NotStarted) => Status::NotStarted,
            None if state.pending.is_some() => Status::Pending,
            _ => Status::Empty,
        })
    }
    pub(crate) fn reconcile(&mut self, observed: &Observation) -> JResult<Status> {
        self.reconcile_with(observed, &mut |_| {})
    }
    fn reconcile_with(
        &mut self,
        observed: &Observation,
        hook: &mut impl FnMut(&str),
    ) -> JResult<Status> {
        let state = self.state()?;
        let Some((pending, hash)) = state.pending else {
            return Ok(Status::Empty);
        };
        observed.image.validate()?;
        if observed.product != pending.intent.product || observed.target != pending.intent.target {
            return Ok(Status::RecoveryRequired);
        }
        if let Some(result) = state.result {
            return Ok(if observed.image != result.observed {
                Status::RecoveryRequired
            } else {
                match result.outcome {
                    Outcome::Applied => Status::Applied,
                    Outcome::NotStarted => Status::NotStarted,
                }
            });
        }
        let Some(spawned) = state.spawned else {
            return Ok(Status::RecoveryRequired);
        };
        match spawned.witness.probe() {
            Process::Alive => return Ok(Status::WaitingForInstaller),
            Process::Unknown => return Ok(Status::RecoveryRequired),
            Process::Absent => {}
        }
        if observed.image != pending.intent.new || pending.stage.verify(&self.parent).is_err() {
            return Ok(Status::RecoveryRequired);
        }
        self.write_sealed(
            "result.json",
            "result-ready.json",
            &Resolution {
                format: 1,
                pending: hash,
                outcome: Outcome::Applied,
                observed: observed.image.clone(),
            },
            hook,
        )?;
        self.state()?;
        Ok(Status::Applied)
    }
}

#[cfg(test)]
mod tests;
