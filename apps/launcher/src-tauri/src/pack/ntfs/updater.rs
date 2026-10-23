//! Private retained staging for a signed launcher installer. Never adopts/overwrites old files.
use super::*;
use std::{
    path::PathBuf,
    time::{Duration, Instant},
};
const PREFIX: &str = "AnimeOnCraft.LauncherUpdate.";
pub(crate) struct Stage {
    root: owned::OwnedRoot,
    file: Option<HeldFile>,
    marker: Option<HeldFile>,
    path: PathBuf,
    cleanup_on_drop: bool,
}
pub(crate) struct Installed {
    _directory: Arc<Dir>,
    file: HeldFile,
    target: PathBuf,
}
impl Installed {
    pub(crate) fn current() -> std::result::Result<Self, &'static str> {
        (|| -> Result<Self> {
            let executable = std::env::current_exe()?;
            if executable.file_name() != Some(OsStr::new("animeoncraft-launcher.exe")) {
                return Err(Error::Path);
            }
            let target = executable.parent().ok_or(Error::Path)?;
            let directory = Dir::existing(target)?;
            let file = directory.file("animeoncraft-launcher.exe", false)?;
            let actual = handle_path(&file.file)?;
            let target = actual.parent().ok_or(Error::Path)?.to_owned();
            Ok(Self {
                _directory: directory,
                file,
                target,
            })
        })()
        .map_err(|_| "UPDATE_NOT_INSTALLED")
    }
    pub(crate) fn target(&self) -> std::result::Result<&Path, &'static str> {
        self.file.unchanged().map_err(|_| "UPDATE_NOT_INSTALLED")?;
        Ok(&self.target)
    }
}
fn handle_path(file: &File) -> Result<PathBuf> {
    let mut buffer = vec![0u16; 32768];
    let length = unsafe {
        GetFinalPathNameByHandleW(
            file.as_raw_handle(),
            buffer.as_mut_ptr(),
            buffer.len() as u32,
            0,
        )
    };
    if length == 0 || length as usize >= buffer.len() {
        return Err(Error::Path);
    }
    let path = String::from_utf16(&buffer[..length as usize]).map_err(|_| Error::Path)?;
    Ok(PathBuf::from(path))
}
impl Stage {
    fn inner(parent: &Arc<Dir>, bytes: &[u8]) -> Result<Self> {
        if bytes.is_empty() || bytes.len() > 64 * 1024 * 1024 {
            return Err(Error::Content);
        }
        parent.unchanged()?;
        // Unknown/old staging is never deleted or adopted. Fail at a small orphan budget.
        let entries = parent.entries(128, Instant::now() + Duration::from_secs(5))?;
        if entries
            .iter()
            .filter(|e| e.name.starts_with(PREFIX))
            .count()
            >= 2
        {
            return Err(Error::Inventory);
        }
        let (root, marker) = owned::OwnedRoot::create_update_stage(
            parent,
            &format!("{PREFIX}{}", uuid::Uuid::new_v4().simple()),
        )?;
        let mut created = root.dir.file("setup.exe", true)?;
        created.write_new(bytes)?;
        created.fingerprint(bytes.len() as u64, &super::super::manifest::hash(bytes))?;
        let created_identity = created.identity.clone();
        drop(created); // Reopen without write access before resource reads/execution.
        let mut file = root.dir.file("setup.exe", false)?;
        if file.identity != created_identity {
            return Err(Error::Identity);
        }
        file.fingerprint(bytes.len() as u64, &super::super::manifest::hash(bytes))?;
        // Transfer only this process's exclusive-create ownership after identity and hash checks.
        file.created = true;
        let path = handle_path(&file.file)?;
        let mut stage = Self {
            root,
            file: Some(file),
            marker: Some(marker),
            path,
            cleanup_on_drop: true,
        };
        stage.validate()?;
        Ok(stage)
    }
    pub(super) fn create(
        parent: &Arc<Dir>,
        bytes: &[u8],
    ) -> std::result::Result<Self, &'static str> {
        Self::inner(parent, bytes).map_err(|_| "UPDATE_STAGE_UNAVAILABLE")
    }
    fn validate(&mut self) -> Result<()> {
        self.root.dir.unchanged()?;
        let file = self.file.as_mut().ok_or(Error::Identity)?;
        file.unchanged()?;
        let (size, digest) = file.verified.clone().ok_or(Error::Content)?;
        file.fingerprint(size, &digest)?;
        self.root
            .validate_marker(self.marker.as_mut().ok_or(Error::Marker)?)?;
        if handle_path(&file.file)? != self.path {
            return Err(Error::Path);
        }
        let entries = self
            .root
            .dir
            .entries(3, Instant::now() + Duration::from_secs(5))?;
        if entries.len() != 2
            || !entries.iter().any(|e| e.name == "setup.exe")
            || !entries.iter().any(|e| e.name == "aoc-instance.json")
        {
            return Err(Error::Inventory);
        }
        Ok(())
    }
    pub(crate) fn checked_path(&mut self) -> std::result::Result<&Path, &'static str> {
        self.validate().map_err(|_| "UPDATE_STAGE_UNAVAILABLE")?;
        Ok(&self.path)
    }
    // Once CreateProcess succeeds the installer may still be using the file.
    // No cleanup is authorized by merely dropping the process-local handoff.
    pub(crate) fn handed_off(&mut self) {
        self.cleanup_on_drop = false;
    }
    fn cleanup(&mut self) -> Result<()> {
        self.cleanup_on_drop = false; // One attempt; failures preserve evidence, never retry broadly.
        self.validate()?;
        self.file.take().ok_or(Error::Identity)?.delete_owned()?;
        self.root.dir.exact_names(&["aoc-instance.json"])?;
        self.root
            .validate_marker(self.marker.as_mut().ok_or(Error::Marker)?)?;
        self.marker.take().ok_or(Error::Marker)?.delete_owned()?;
        self.root.dir.unchanged()?;
        self.root.dir.exact_names(&[])?;
        // Empty directory only, by the DELETE-capable handle acquired at exclusive creation.
        let info = FILE_DISPOSITION_INFO { DeleteFile: true };
        if unsafe {
            SetFileInformationByHandle(
                self.root.dir.file.as_raw_handle(),
                FileDispositionInfo,
                ptr::from_ref(&info).cast(),
                size_of::<FILE_DISPOSITION_INFO>() as u32,
            )
        } == 0
        {
            return Err(io::Error::last_os_error().into());
        }
        Ok(())
    }
}
impl Drop for Stage {
    fn drop(&mut self) {
        if self.cleanup_on_drop {
            let _ = self.cleanup();
        }
    }
}
pub(in crate::pack) mod pending;
#[cfg(test)]
mod tests;
