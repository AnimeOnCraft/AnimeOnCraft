//! Retained handles/private ownership/listing for fixed local provisioning.
//! Development engine compiles normally; configured sources/online refresh remain gated.
use sha2::{Digest, Sha256};
use std::{
    ffi::OsStr,
    fs::{File, OpenOptions},
    io::{self, Read, Seek, SeekFrom, Write},
    mem::{offset_of, size_of},
    os::windows::{
        ffi::OsStrExt,
        fs::OpenOptionsExt,
        io::{AsRawHandle, FromRawHandle},
    },
    path::{Component, Path},
    ptr,
    sync::Arc,
};
use windows_sys::{
    Wdk::{
        Foundation::OBJECT_ATTRIBUTES,
        Storage::FileSystem::{
            FileRenameInformation, NtCreateFile, NtSetInformationFile, FILE_CREATE,
            FILE_DIRECTORY_FILE, FILE_NON_DIRECTORY_FILE, FILE_OPEN, FILE_OPEN_REPARSE_POINT,
            FILE_RENAME_INFORMATION, FILE_SYNCHRONOUS_IO_NONALERT,
        },
    },
    Win32::{
        Foundation::{HANDLE, OBJ_CASE_INSENSITIVE, OBJ_DONT_REPARSE, UNICODE_STRING},
        Storage::FileSystem::*,
        System::{WindowsProgramming::DRIVE_FIXED, IO::IO_STATUS_BLOCK},
    },
};
mod bridge;
mod download;
mod engine;
pub(super) use engine::Engine;
#[cfg(feature = "pack-acceptance")]
pub(super) fn acceptance_actor(
    local: Arc<crate::pack::local::LocalInstance>,
    fixture: crate::pack::acceptance::Fixture,
) -> std::result::Result<crate::pack::actor::Actor, &'static str> {
    engine::acceptance_actor(local, fixture)
}
pub(super) fn development_actor(
    local: Arc<crate::pack::local::LocalInstance>,
) -> crate::pack::actor::Actor {
    engine::development_actor(local)
}
mod install;
mod inventory;
mod listing;
mod owned;
mod protection;
pub(super) mod provision;
#[cfg(test)]
mod session;
pub(in crate::pack) mod updater;
use protection::Protection;
pub(in crate::pack) struct ScratchPermit {
    directory: std::path::PathBuf,
    guard: Arc<Dir>,
}
impl ScratchPermit {
    pub(in crate::pack) fn directory(&self) -> &Path {
        &self.directory
    }
    pub(in crate::pack) fn validate(&self) -> std::result::Result<(), &'static str> {
        (|| {
            self.guard.unchanged()?;
            self.guard
                .protection
                .as_ref()
                .ok_or(Error::Acl)?
                .check(&self.guard.file, true)
        })()
        .map_err(|_: Error| "TRUST_STATE_INVALID")
    }
}

#[derive(Debug, thiserror::Error)]
enum Error {
    #[error("unsafe path or redirected/non-fixed root")]
    Path,
    #[error("filesystem must be NTFS on one volume")]
    Volume,
    #[error("reparse point, hard link, wrong kind or changed file identity")]
    Identity,
    #[error("file size or digest differs from the expected content")]
    Content,
    #[error("object ownership or private protected DACL differs")]
    Acl,
    #[error("owned root marker or receipt differs")]
    Marker,
    #[error("inventory is unsafe, changed or exceeds a bound")]
    Inventory,
    #[error("operation was interrupted before verification or promotion")]
    Interrupted,
    #[error("NT operation failed: {0:#x}")]
    Nt(i32),
    #[error("filesystem operation failed")]
    Io(#[from] io::Error),
}
type Result<T> = std::result::Result<T, Error>;

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct Identity {
    volume: u64,
    id: [u8; 16],
}

struct Dir {
    file: File,
    identity: Identity,
    // Every ancestor stays open without FILE_SHARE_DELETE, including the drive root.
    _parent: Option<Arc<Dir>>,
    protection: Option<Arc<Protection>>,
}
struct HeldFile {
    file: File,
    identity: Identity,
    parents: Vec<Arc<Dir>>,
    created: bool,
    verified: Option<(u64, String)>,
}

fn single_component(name: &OsStr) -> Result<Vec<u16>> {
    let wide: Vec<u16> = name.encode_wide().collect();
    if wide.is_empty()
        || wide.len() > 255
        || wide == [46]
        || wide == [46, 46]
        || wide.iter().any(|c| *c < 32 || [47, 92, 58].contains(c))
        || matches!(wide.last(), Some(32 | 46))
    {
        return Err(Error::Path);
    }
    Ok(wide)
}
fn portable_name(name: &str) -> Result<Vec<u16>> {
    if name.contains('/') || !super::manifest::portable(name) {
        return Err(Error::Path);
    }
    single_component(OsStr::new(name))
}

// Only these three fixed information classes use this helper. Their official ABI structs
// are plain integer/bool records and GetFileInformationByHandleEx initializes the buffer.
fn inspect(file: &File, directory: bool) -> Result<Identity> {
    let mut tag = FILE_ATTRIBUTE_TAG_INFO::default();
    let mut standard = FILE_STANDARD_INFO::default();
    let mut id = FILE_ID_INFO::default();
    let handle = file.as_raw_handle();
    unsafe {
        for (class, buffer, len) in [
            (
                FileAttributeTagInfo,
                ptr::from_mut(&mut tag).cast(),
                size_of::<FILE_ATTRIBUTE_TAG_INFO>(),
            ),
            (
                FileStandardInfo,
                ptr::from_mut(&mut standard).cast(),
                size_of::<FILE_STANDARD_INFO>(),
            ),
            (
                FileIdInfo,
                ptr::from_mut(&mut id).cast(),
                size_of::<FILE_ID_INFO>(),
            ),
        ] {
            if GetFileInformationByHandleEx(handle, class, buffer, len as u32) == 0 {
                return Err(io::Error::last_os_error().into());
            }
        }
    }
    if tag.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT != 0
        || standard.DeletePending
        || standard.Directory != directory
        || (!directory && standard.NumberOfLinks != 1)
    {
        return Err(Error::Identity);
    }
    Ok(Identity {
        volume: id.VolumeSerialNumber,
        id: id.FileId.Identifier,
    })
}

fn relative(
    parent: &Dir,
    wide: &mut [u16],
    directory: bool,
    create: bool,
    security: Option<&protection::Descriptor>,
) -> Result<File> {
    relative_access(parent, wide, directory, create, security, false)
}

// DELETE on directories is restricted to exclusive creation of updater staging.
// Existing directories retain the ordinary no-delete access policy.
fn relative_access(
    parent: &Dir,
    wide: &mut [u16],
    directory: bool,
    create: bool,
    security: Option<&protection::Descriptor>,
    removable: bool,
) -> Result<File> {
    if removable && (!directory || !create || security.is_none()) {
        return Err(Error::Identity);
    }
    // Single component only; no intermediate textual path resolution occurs here.
    let name = UNICODE_STRING {
        Length: (wide.len() * 2) as u16,
        MaximumLength: (wide.len() * 2) as u16,
        Buffer: wide.as_mut_ptr(),
    };
    let attributes = OBJECT_ATTRIBUTES {
        Length: size_of::<OBJECT_ATTRIBUTES>() as u32,
        RootDirectory: parent.file.as_raw_handle(),
        ObjectName: &name,
        Attributes: OBJ_CASE_INSENSITIVE | OBJ_DONT_REPARSE,
        SecurityDescriptor: security.map_or(ptr::null(), |s| s.0.cast()),
        ..Default::default()
    };
    let mut io_status = IO_STATUS_BLOCK::default();
    let mut handle: HANDLE = ptr::null_mut();
    let access = FILE_READ_ATTRIBUTES
        | READ_CONTROL
        | SYNCHRONIZE
        | if directory {
            FILE_LIST_DIRECTORY | FILE_TRAVERSE | if removable { DELETE } else { 0 }
        } else {
            FILE_READ_DATA | DELETE | if create { FILE_WRITE_DATA } else { 0 }
        };
    // Synchronous API, all buffers/parent remain alive until it completes. A successful
    // owned handle is immediately transferred to File; failed calls do not own a handle.
    let status = unsafe {
        NtCreateFile(
            &mut handle,
            access,
            &attributes,
            &mut io_status,
            ptr::null(),
            FILE_ATTRIBUTE_NORMAL,
            if directory {
                FILE_SHARE_READ | FILE_SHARE_WRITE
            } else {
                FILE_SHARE_READ
            },
            if create { FILE_CREATE } else { FILE_OPEN },
            FILE_OPEN_REPARSE_POINT
                | FILE_SYNCHRONOUS_IO_NONALERT
                | if directory {
                    FILE_DIRECTORY_FILE
                } else {
                    FILE_NON_DIRECTORY_FILE
                },
            ptr::null(),
            0,
        )
    };
    if status < 0 {
        return Err(Error::Nt(status));
    }
    Ok(unsafe { File::from_raw_handle(handle) })
}

impl Dir {
    fn exact_names(self: &Arc<Self>, expected: &[&str]) -> Result<()> {
        let names: std::collections::BTreeSet<_> = self
            .entries(
                expected.len() + 1,
                std::time::Instant::now() + std::time::Duration::from_secs(10),
            )?
            .into_iter()
            .map(|entry| entry.name)
            .collect();
        if names != expected.iter().map(|name| (*name).to_owned()).collect() {
            return Err(Error::Inventory);
        }
        Ok(())
    }
    fn unchanged(&self) -> Result<()> {
        let mut current = Some(self);
        while let Some(dir) = current {
            if inspect(&dir.file, true)? != dir.identity {
                return Err(Error::Identity);
            }
            if let Some(protection) = &dir.protection {
                protection.check(&dir.file, true)?;
            }
            current = dir._parent.as_deref();
        }
        Ok(())
    }
    #[cfg(test)]
    fn fixture_existing(path: &Path) -> Result<Arc<Self>> {
        Self::existing(path)
    }
    fn existing(path: &Path) -> Result<Arc<Self>> {
        // Only local drive prefixes (including canonicalize's verbatim disk prefix).
        // No UNC, relative, device, SUBST or non-fixed drive aliases.
        let mut components = path.components();
        let drive = match components.next() {
            Some(Component::Prefix(prefix)) => match prefix.kind() {
                std::path::Prefix::Disk(letter) | std::path::Prefix::VerbatimDisk(letter) => letter,
                _ => return Err(Error::Path),
            },
            _ => return Err(Error::Path),
        };
        if components.next() != Some(Component::RootDir) {
            return Err(Error::Path);
        }
        let root = format!("{}:\\", drive as char);
        let wide: Vec<u16> = OsStr::new(&root).encode_wide().chain(Some(0)).collect();
        if unsafe { GetDriveTypeW(wide.as_ptr()) } != DRIVE_FIXED {
            return Err(Error::Path);
        }
        let file = OpenOptions::new()
            .read(true)
            .share_mode(FILE_SHARE_READ | FILE_SHARE_WRITE)
            .custom_flags(FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT)
            .open(&root)?;
        let mut fs_name = [0u16; 32];
        if unsafe {
            GetVolumeInformationByHandleW(
                file.as_raw_handle(),
                ptr::null_mut(),
                0,
                ptr::null_mut(),
                ptr::null_mut(),
                ptr::null_mut(),
                fs_name.as_mut_ptr(),
                32,
            )
        } == 0
        {
            return Err(io::Error::last_os_error().into());
        }
        let end = fs_name.iter().position(|c| *c == 0).ok_or(Error::Volume)?;
        if String::from_utf16_lossy(&fs_name[..end]) != "NTFS" {
            return Err(Error::Volume);
        }
        let mut final_name = [0u16; 512];
        let len = unsafe {
            GetFinalPathNameByHandleW(
                file.as_raw_handle(),
                final_name.as_mut_ptr(),
                final_name.len() as u32,
                FILE_NAME_NORMALIZED | VOLUME_NAME_DOS,
            )
        } as usize;
        if len == 0
            || len >= final_name.len()
            || !String::from_utf16_lossy(&final_name[..len])
                .eq_ignore_ascii_case(&format!("\\\\?\\{root}"))
        {
            return Err(Error::Path);
        }
        let identity = inspect(&file, true)?;
        let mut dir = Arc::new(Self {
            file,
            identity,
            _parent: None,
            protection: None,
        });
        for component in components {
            let Component::Normal(name) = component else {
                return Err(Error::Path);
            };
            dir = dir.child_os(name, false)?;
        }
        Ok(dir)
    }
    fn child_os(self: &Arc<Self>, name: &OsStr, create: bool) -> Result<Arc<Self>> {
        self.unchanged()?;
        let security = if create {
            self.protection
                .as_ref()
                .map(|p| p.descriptor(true))
                .transpose()?
        } else {
            None
        };
        let file = relative(
            self,
            &mut single_component(name)?,
            true,
            create,
            security.as_ref(),
        )?;
        let identity = inspect(&file, true)?;
        if identity.volume != self.identity.volume {
            return Err(Error::Volume);
        }
        if let Some(p) = &self.protection {
            p.check(&file, true)?;
        }
        Ok(Arc::new(Self {
            file,
            identity,
            _parent: Some(self.clone()),
            protection: self.protection.clone(),
        }))
    }
    fn child(self: &Arc<Self>, name: &str, create: bool) -> Result<Arc<Self>> {
        portable_name(name)?;
        self.child_os(OsStr::new(name), create)
    }
    fn file(self: &Arc<Self>, name: &str, create: bool) -> Result<HeldFile> {
        self.unchanged()?;
        let security = if create {
            self.protection
                .as_ref()
                .map(|p| p.descriptor(false))
                .transpose()?
        } else {
            None
        };
        let file = relative(
            self,
            &mut portable_name(name)?,
            false,
            create,
            security.as_ref(),
        )?;
        let identity = inspect(&file, false)?;
        if identity.volume != self.identity.volume {
            return Err(Error::Volume);
        }
        if let Some(p) = &self.protection {
            p.check(&file, false)?;
        }
        Ok(HeldFile {
            file,
            identity,
            parents: vec![self.clone()],
            created: create,
            verified: None,
        })
    }
}
impl HeldFile {
    fn unchanged(&self) -> Result<()> {
        if inspect(&self.file, false)? != self.identity {
            return Err(Error::Identity);
        }
        for parent in &self.parents {
            parent.unchanged()?;
            if let Some(p) = &parent.protection {
                p.check(&self.file, false)?;
            }
        }
        Ok(())
    }
    fn write_new(&mut self, bytes: &[u8]) -> Result<()> {
        // Existing managed files are never opened for write/truncate.
        if !self.created
            || bytes.len() as u64 > super::manifest::MAX_BYTES
            || self.file.metadata()?.len() != 0
        {
            return Err(Error::Content);
        }
        self.unchanged()?;
        self.file.write_all(bytes)?;
        self.file.sync_all()?;
        self.unchanged()
    }
    fn fingerprint(&mut self, expected_length: u64, expected_hash: &str) -> Result<()> {
        self.fingerprint_controlled(expected_length, expected_hash, || Ok(()))
    }
    fn fingerprint_controlled(
        &mut self,
        expected_length: u64,
        expected_hash: &str,
        mut check: impl FnMut() -> Result<()>,
    ) -> Result<()> {
        self.verified = None;
        check()?;
        self.unchanged()?;
        if expected_length > super::manifest::MAX_BYTES
            || self.file.metadata()?.len() != expected_length
        {
            return Err(Error::Content);
        }
        self.file.seek(SeekFrom::Start(0))?;
        let mut digest = Sha256::new();
        let mut buffer = [0u8; 64 * 1024];
        let mut total = 0u64;
        loop {
            check()?;
            let count = self.file.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            total = total.checked_add(count as u64).ok_or(Error::Content)?;
            if total > expected_length {
                return Err(Error::Content);
            }
            digest.update(&buffer[..count]);
        }
        self.unchanged()?;
        check()?;
        if total != expected_length || format!("{:x}", digest.finalize()) != expected_hash {
            return Err(Error::Content);
        }
        self.verified = Some((expected_length, expected_hash.to_owned()));
        Ok(())
    }
    fn rename_empty(&mut self, destination: &Arc<Dir>, name: &str) -> Result<()> {
        self.rename_empty_controlled(destination, name, || Ok(()))
    }
    fn rename_empty_controlled(
        &mut self,
        destination: &Arc<Dir>,
        name: &str,
        mut check: impl FnMut() -> Result<()>,
    ) -> Result<()> {
        let wide = portable_name(name)?;
        let (length, digest) = self.verified.clone().ok_or(Error::Content)?;
        self.fingerprint_controlled(length, &digest, &mut check)?;
        destination.unchanged()?;
        if destination.identity.volume != self.identity.volume {
            return Err(Error::Volume);
        }
        let len = size_of::<FILE_RENAME_INFORMATION>() + wide.len() * 2;
        // u64 allocation supplies the ABI's 8-byte alignment, offset_of uses the official
        // windows-sys layout. No replace/POSIX flags; existing destinations always fail.
        let mut buffer = vec![0u64; len.div_ceil(8)];
        let info = buffer.as_mut_ptr().cast::<FILE_RENAME_INFORMATION>();
        let mut io_status = IO_STATUS_BLOCK::default();
        check()?;
        let status = unsafe {
            (*info).RootDirectory = destination.file.as_raw_handle();
            (*info).FileNameLength = (wide.len() * 2) as u32;
            ptr::copy_nonoverlapping(
                wide.as_ptr(),
                buffer
                    .as_mut_ptr()
                    .cast::<u8>()
                    .add(offset_of!(FILE_RENAME_INFORMATION, FileName))
                    .cast::<u16>(),
                wide.len(),
            );
            NtSetInformationFile(
                self.file.as_raw_handle(),
                &mut io_status,
                buffer.as_ptr().cast(),
                len as u32,
                FileRenameInformation,
            )
        };
        if status < 0 {
            return Err(Error::Nt(status));
        }
        // Retain both source and destination ancestors until this file handle closes.
        self.parents.push(destination.clone());
        self.unchanged()?;
        check()
    }
    fn delete_owned(self) -> Result<()> {
        // This prototype only deletes files it exclusively created itself.
        if !self.created {
            return Err(Error::Identity);
        }
        self.unchanged()?;
        let info = FILE_DISPOSITION_INFO { DeleteFile: true };
        if unsafe {
            SetFileInformationByHandle(
                self.file.as_raw_handle(),
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::{fs, process::Command};
    pub(super) struct Fixture {
        dir: tempfile::TempDir,
        pub(super) root: Arc<Dir>,
    }
    impl Fixture {
        pub(super) fn new() -> Self {
            let workspace = Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../../..")
                .canonicalize()
                .unwrap();
            let tools = workspace.join(".tools");
            fs::create_dir_all(&tools).unwrap();
            assert_eq!(
                tools.canonicalize().unwrap(),
                tools,
                "fixture parent redirected"
            );
            let guard = Dir::fixture_existing(&tools).unwrap();
            let dir = tempfile::Builder::new()
                .prefix("ntfs-fixture-")
                .tempdir_in(&tools)
                .unwrap();
            assert_eq!(dir.path().parent(), Some(tools.as_path()));
            let root = guard
                .child_os(dir.path().file_name().unwrap(), false)
                .unwrap();
            Self { dir, root }
        }
        pub(super) fn path(&self) -> &Path {
            self.dir.path()
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            // Replace our guard to close the fixture chain before TempDir cleanup.
            self.root = self.root._parent.as_ref().unwrap().clone();
        }
    }
    fn hash(bytes: &[u8]) -> String {
        format!("{:x}", Sha256::digest(bytes))
    }

    #[test]
    fn actual_create_hash_backup_replace_delete() {
        let f = Fixture::new();
        let staged = f.root.child("staging", true).unwrap();
        let installed = f.root.child("mods", true).unwrap();
        let backup = f.root.child("backup", true).unwrap();
        let mut old = installed.file("example.jar", true).unwrap();
        old.write_new(b"old verified bytes").unwrap();
        let old_id = old.identity.clone();
        drop(old);
        let mut old = installed.file("example.jar", false).unwrap();
        old.fingerprint(18, &hash(b"old verified bytes")).unwrap();
        assert_eq!(old.identity, old_id);
        assert!(old.write_new(b"overwrite").is_err());
        let mut new = staged.file("new.jar", true).unwrap();
        new.write_new(b"new verified bytes").unwrap();
        new.fingerprint(18, &hash(b"new verified bytes")).unwrap();
        assert!(new.rename_empty(&installed, "example.jar").is_err());
        old.rename_empty(&backup, "example.jar").unwrap();
        new.rename_empty(&installed, "example.jar").unwrap();
        old.fingerprint(18, &hash(b"old verified bytes")).unwrap();
        new.fingerprint(18, &hash(b"new verified bytes")).unwrap();
        assert_eq!(
            fs::read(f.path().join("backup/example.jar")).unwrap(),
            b"old verified bytes"
        );
        new.delete_owned().unwrap();
        assert!(!f.path().join("mods/example.jar").exists());
        assert!(old.delete_owned().is_err());
    }
    #[test]
    fn escape_ads_devices_and_existing_collision_fail() {
        let f = Fixture::new();
        for name in [
            "../outside.jar",
            "a/b.jar",
            "a\\b.jar",
            "a.jar:ads",
            "CON.jar",
            "LPT1",
            "a.",
            ".",
            "",
            "C:\\x",
            "\\\\server\\x",
        ] {
            assert!(f.root.file(name, true).is_err(), "{name}");
        }
        fs::write(f.path().join("foreign.jar"), b"keep").unwrap();
        assert!(f.root.file("foreign.jar", true).is_err());
        assert_eq!(fs::read(f.path().join("foreign.jar")).unwrap(), b"keep");
        assert!(Dir::fixture_existing(Path::new("relative")).is_err());
        assert!(Dir::fixture_existing(Path::new("\\\\localhost\\F$\\AnimeOnCraft")).is_err());
    }
    #[test]
    fn parent_and_file_handles_prevent_swaps_and_writes() {
        let f = Fixture::new();
        let mods = f.root.child("mods", true).unwrap();
        let mut file = mods.file("a.jar", true).unwrap();
        file.write_new(b"sentinel").unwrap();
        // Hold only the file; it must keep the complete parent chain alive.
        drop(mods);
        for _ in 0..32 {
            assert!(fs::rename(f.path().join("mods"), f.path().join("moved")).is_err());
            assert!(fs::rename(f.path().join("mods/a.jar"), f.path().join("moved.jar")).is_err());
            assert!(OpenOptions::new()
                .write(true)
                .open(f.path().join("mods/a.jar"))
                .is_err());
        }
        // Windows permits a new hard link despite our share mode. Detect it, refuse
        // further operations and keep this spike gated; link-count checks are not a
        // guarantee against a malicious concurrent process running under the same SID.
        fs::hard_link(f.path().join("mods/a.jar"), f.path().join("alias.jar")).unwrap();
        assert!(matches!(
            file.fingerprint(8, &hash(b"sentinel")),
            Err(Error::Identity)
        ));
        assert!(file.rename_empty(&f.root, "moved.jar").is_err());
        assert_eq!(fs::read(f.path().join("alias.jar")).unwrap(), b"sentinel");
        fs::remove_file(f.path().join("alias.jar")).unwrap();
        file.fingerprint(8, &hash(b"sentinel")).unwrap();
        drop(file);
        fs::rename(f.path().join("mods"), f.path().join("moved")).unwrap();
    }
    #[test]
    fn hard_links_and_wrong_kind_are_rejected() {
        let f = Fixture::new();
        let outside = Fixture::new();
        fs::write(outside.path().join("sentinel.jar"), b"outside bytes").unwrap();
        fs::hard_link(
            outside.path().join("sentinel.jar"),
            f.path().join("linked.jar"),
        )
        .unwrap();
        assert!(matches!(
            f.root.file("linked.jar", false),
            Err(Error::Identity)
        ));
        assert!(f.root.file("linked.jar", true).is_err());
        let folder = f.root.child("folder", true).unwrap();
        assert!(f.root.file("folder", false).is_err());
        fs::write(f.path().join("plain"), b"plain").unwrap();
        assert!(f.root.child("plain", false).is_err());
        drop(folder);
        assert_eq!(
            fs::read(outside.path().join("sentinel.jar")).unwrap(),
            b"outside bytes"
        );
    }
    #[test]
    fn junction_final_and_ancestor_are_rejected_without_touching_target() {
        let f = Fixture::new();
        let outside = Fixture::new();
        fs::write(outside.path().join("sentinel.jar"), b"outside bytes").unwrap();
        let junction = f.path().join("junction");
        // Creation only, exact own TempDir paths. No delete/move shell commands.
        let output = Command::new("cmd.exe")
            .args(["/d", "/c", "mklink", "/J"])
            .arg(&junction)
            .arg(outside.path())
            .output()
            .unwrap();
        assert!(output.status.success(), "junction fixture creation failed");
        let result = || {
            assert!(f.root.child("junction", false).is_err());
            assert!(f.root.file("junction", false).is_err());
            assert!(Dir::fixture_existing(&junction).is_err());
            assert!(Dir::fixture_existing(&junction.join("child")).is_err());
            assert_eq!(
                fs::read(outside.path().join("sentinel.jar")).unwrap(),
                b"outside bytes"
            );
        };
        // Remove the junction itself before recursive TempDir cleanup, even on assertion failure.
        let tested = std::panic::catch_unwind(std::panic::AssertUnwindSafe(result));
        fs::remove_dir(&junction).unwrap();
        if let Err(error) = tested {
            std::panic::resume_unwind(error);
        }
    }
    #[test]
    fn content_failure_and_destination_conflict_preserve_files() {
        let f = Fixture::new();
        let backup = f.root.child("backup", true).unwrap();
        let mut file = f.root.file("new.jar", true).unwrap();
        file.write_new(b"new").unwrap();
        assert!(file.fingerprint(4, &hash(b"new")).is_err());
        assert!(file.fingerprint(3, &hash(b"bad")).is_err());
        assert!(file
            .fingerprint(super::super::manifest::MAX_BYTES + 1, &hash(b"new"))
            .is_err());
        fs::write(f.path().join("backup/occupied.jar"), b"foreign").unwrap();
        assert!(file.rename_empty(&backup, "occupied.jar").is_err());
        assert_eq!(
            fs::read(f.path().join("backup/occupied.jar")).unwrap(),
            b"foreign"
        );
        file.fingerprint(3, &hash(b"new")).unwrap();
    }

    #[test]
    fn unverified_locked_and_cross_volume_files_cannot_move() {
        let f = Fixture::new();
        let backup = f.root.child("backup", true).unwrap();
        let mut file = f.root.file("new.jar", true).unwrap();
        file.write_new(b"new").unwrap();
        assert!(matches!(
            file.rename_empty(&backup, "new.jar"),
            Err(Error::Content)
        ));
        file.fingerprint(3, &hash(b"new")).unwrap();
        let system_drive = std::env::var("SystemDrive").unwrap();
        let other = Dir::fixture_existing(Path::new(&format!("{system_drive}\\"))).unwrap();
        if other.identity.volume != f.root.identity.volume {
            assert!(matches!(
                file.rename_empty(&other, "aoc-no-write.jar"),
                Err(Error::Volume)
            ));
        } else {
            // Same-drive CI cannot exercise the real cross-volume case. Never attempt
            // a same-volume rename into the system drive root.
            eprintln!("cross-volume case unavailable: system/workspace share one volume");
        }
        file.fingerprint(3, &hash(b"bad")).unwrap_err();
        assert!(matches!(
            file.rename_empty(&backup, "new.jar"),
            Err(Error::Content)
        ));
        fs::write(f.path().join("locked.jar"), b"locked").unwrap();
        let locked = OpenOptions::new()
            .read(true)
            .share_mode(FILE_SHARE_READ)
            .open(f.path().join("locked.jar"))
            .unwrap();
        assert!(f.root.file("locked.jar", false).is_err());
        assert_eq!(fs::read(f.path().join("locked.jar")).unwrap(), b"locked");
        drop(locked);
        let mut reopened = f.root.file("locked.jar", false).unwrap();
        reopened.fingerprint(6, &hash(b"locked")).unwrap();
    }

    #[test]
    fn child_crash_releases_handles_and_keeps_flushed_bytes() {
        let f = Fixture::new();
        let output = Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "pack::ntfs::tests::fixture_crash_child",
                "--nocapture",
            ])
            .env("AOC_NTFS_FIXTURE_CHILD", f.path())
            .output()
            .unwrap();
        assert_eq!(output.status.code(), Some(37), "{output:?}");
        let mut file = f.root.file("crash.jar", false).unwrap();
        file.fingerprint(7, &hash(b"flushed")).unwrap();
        let backup = f.root.child("backup", true).unwrap();
        file.rename_empty(&backup, "crash.jar").unwrap();
    }

    #[test]
    fn fixture_crash_child() {
        let Some(path) = std::env::var_os("AOC_NTFS_FIXTURE_CHILD") else {
            return;
        };
        let path = Path::new(&path);
        let workspace = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../..")
            .canonicalize()
            .unwrap();
        assert_eq!(path.parent(), Some(workspace.join(".tools").as_path()));
        assert!(path
            .file_name()
            .unwrap()
            .to_string_lossy()
            .starts_with("ntfs-fixture-"));
        assert_eq!(path.canonicalize().unwrap(), path);
        let root = Dir::fixture_existing(path).unwrap();
        let mut file = root.file("crash.jar", true).unwrap();
        file.write_new(b"flushed").unwrap();
        file.fingerprint(7, &hash(b"flushed")).unwrap();
        // No Rust destructors: Windows must close the retained handles at process exit.
        std::process::exit(37);
    }

    fn set_fixture_acl(path: &Path, text: &str, protected: bool) {
        use windows_sys::Win32::Security::{Authorization::*, *};
        let sd = Protection::from_sddl(text).unwrap();
        let mut acl = ptr::null_mut();
        let mut present = 0;
        let mut defaulted = 0;
        assert_ne!(
            unsafe { GetSecurityDescriptorDacl(sd.0, &mut present, &mut acl, &mut defaulted) },
            0
        );
        assert_ne!(present, 0);
        let file = OpenOptions::new()
            .read(true)
            .access_mode(READ_CONTROL | WRITE_DAC)
            .share_mode(FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE)
            .custom_flags(FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT)
            .open(path)
            .unwrap();
        assert_eq!(
            unsafe {
                SetSecurityInfo(
                    file.as_raw_handle(),
                    SE_FILE_OBJECT,
                    DACL_SECURITY_INFORMATION
                        | if protected {
                            PROTECTED_DACL_SECURITY_INFORMATION
                        } else {
                            UNPROTECTED_DACL_SECURITY_INFORMATION
                        },
                    ptr::null_mut(),
                    ptr::null_mut(),
                    acl,
                    ptr::null_mut(),
                )
            },
            0
        );
    }

    #[test]
    fn owned_root_creation_reopen_and_marker_fail_closed() {
        use super::owned::{OwnedRoot, Receipt};
        let f = Fixture::new();
        let owned = OwnedRoot::create(&f.root, "instance").unwrap();
        let mods = owned.dir.child("mods", true).unwrap();
        let mut file = mods.file("fixture.jar", true).unwrap();
        file.write_new(b"bytes").unwrap();
        owned.marker().unwrap();
        assert!(OwnedRoot::create(&f.root, "instance").is_err());
        let receipt = owned.receipt.clone();
        drop(file);
        drop(mods);
        drop(owned);
        let root = OwnedRoot::reopen(&f.root, "instance", receipt.clone()).unwrap();
        root.marker().unwrap();
        let mut altered = serde_json::to_value(&receipt).unwrap();
        altered["environment"] = "wrong".into();
        let altered: Receipt = serde_json::from_value(altered).unwrap();
        assert!(OwnedRoot::reopen(&f.root, "instance", altered).is_err());
        drop(root);
        fs::write(f.path().join("instance/aoc-instance.json"), b"{}").unwrap();
        assert!(OwnedRoot::reopen(&f.root, "instance", receipt).is_err());
        assert_eq!(
            fs::read(f.path().join("instance/aoc-instance.json")).unwrap(),
            b"{}"
        );
        assert!(OwnedRoot::create(&f.root, "instance").is_err());
    }

    #[test]
    fn protected_dacl_tampering_is_rejected_without_repair() {
        use super::owned::OwnedRoot;
        let f = Fixture::new();
        let owned = OwnedRoot::create(&f.root, "instance").unwrap();
        let receipt = owned.receipt.clone();
        let sid = owned.dir.protection.as_ref().unwrap().sid.clone();
        let path = f.path().join("instance");
        let expected = format!("O:{sid}D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;{sid})");
        for (text, protected) in [
            ("D:NO_ACCESS_CONTROL".into(), true),
            (
                format!("D:P(A;OICI;FA;;;{sid})(A;OICI;FA;;;SY)(A;OICI;FA;;;WD)"),
                true,
            ),
            (format!("D:P(A;OICI;FA;;;{sid})"), true),
            (format!("D:(A;OICI;FA;;;SY)(A;OICI;FA;;;{sid})"), false),
        ] {
            set_fixture_acl(&path, &text, protected);
            assert!(matches!(owned.dir.unchanged(), Err(Error::Acl)));
            assert!(OwnedRoot::reopen(&f.root, "instance", receipt.clone()).is_err());
            // The test restores only its own descriptor; the adapter never repairs ACLs.
            set_fixture_acl(&path, &expected, true);
            owned.dir.unchanged().unwrap();
        }
        let mods = owned.dir.child("mods", true).unwrap();
        let mut file = mods.file("fixture.jar", true).unwrap();
        file.write_new(b"bytes").unwrap();
        drop(file);
        let file_path = path.join("mods/fixture.jar");
        set_fixture_acl(
            &file_path,
            &format!("D:P(A;;FA;;;SY)(A;;FA;;;{sid})(A;;FR;;;WD)"),
            true,
        );
        assert!(matches!(mods.file("fixture.jar", false), Err(Error::Acl)));
        assert_eq!(fs::read(file_path).unwrap(), b"bytes");
    }

    #[test]
    fn copied_marker_cannot_claim_a_different_root_identity() {
        use super::owned::OwnedRoot;
        let f = Fixture::new();
        let first = OwnedRoot::create(&f.root, "first").unwrap();
        let second = OwnedRoot::create(&f.root, "second").unwrap();
        let receipt = first.receipt.clone();
        let marker = fs::read(f.path().join("first/aoc-instance.json")).unwrap();
        drop(first);
        drop(second);
        fs::write(f.path().join("second/aoc-instance.json"), marker).unwrap();
        assert!(matches!(
            OwnedRoot::reopen(&f.root, "second", receipt),
            Err(Error::Marker)
        ));
    }

    fn checked_fixture(bytes: &[u8], sequence: u64) -> crate::pack::manifest::CheckedManifest {
        use crate::pack::manifest::*;
        let mut value: serde_json::Value = serde_json::from_slice(include_bytes!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../../packages/contracts/fixtures/pack-manifest-test-only.json"
        )))
        .unwrap();
        value["release_sequence"] = sequence.into();
        value["files"][0]["sha256"] = hash(bytes).into();
        value["files"][0]["length"] = (bytes.len() as u64).into();
        let serialized = serde_json::to_vec(&value).unwrap();
        let parsed: Manifest = serde_json::from_value(value).unwrap();
        let policy = ReleasePolicy {
            pack_id: parsed.pack_id.clone(),
            channel: parsed.channel.clone(),
            sequence,
            minecraft_version: parsed.minecraft_version.clone(),
            loader: parsed.loader.clone(),
            java: parsed.java.clone(),
            launcher_version: semver::Version::parse("0.7.5").unwrap(),
            protocol: parsed.integration_protocol_major,
            approved_sources: std::collections::BTreeSet::from(["fixture".into()]),
        };
        parse(&serialized, &policy).unwrap()
    }

    #[test]
    fn bounded_inventory_retains_files_and_feeds_real_planner() {
        use super::{inventory::Limits, owned::OwnedRoot};
        use crate::pack::manifest::{plan, Operation};
        let f = Fixture::new();
        let owned = OwnedRoot::create(&f.root, "instance").unwrap();
        let mods = owned.dir.child("mods", true).unwrap();
        let mut file = mods.file("fixture.jar", true).unwrap();
        file.write_new(b"old").unwrap();
        drop(file);
        fs::create_dir(f.path().join("instance/saves")).unwrap();
        fs::write(f.path().join("instance/saves/world.dat"), b"user save").unwrap();
        let mut snapshot = owned.inventory(Limits::default()).unwrap();
        assert_eq!(snapshot.observed.len(), 1);
        assert_eq!(snapshot.observed["mods/fixture.jar"].sha256, hash(b"old"));
        let old = checked_fixture(b"old", 1);
        let new = checked_fixture(b"new", 2);
        assert_eq!(
            plan(&new, Some(&old), &snapshot.observed, &Default::default())
                .unwrap()
                .operations,
            vec![Operation::Replace("mods/fixture.jar".into())]
        );
        assert!(plan(&new, None, &snapshot.observed, &Default::default()).is_err());
        snapshot.validate().unwrap();
        assert!(fs::write(f.path().join("instance/mods/fixture.jar"), b"tamper").is_err());
        fs::write(
            f.path().join("instance/saves/world.dat"),
            b"changed user save",
        )
        .unwrap();
        snapshot.validate().unwrap();
        // An insertion is not hidden by the retained handles.
        let mut foreign = mods.file("foreign.jar", true).unwrap();
        foreign.write_new(b"foreign").unwrap();
        drop(foreign);
        assert!(snapshot.validate().is_err());
        drop(snapshot);
        let observed = owned.inventory(Limits::default()).unwrap();
        assert!(plan(&new, Some(&old), &observed.observed, &Default::default()).is_err());
        assert_eq!(
            fs::read(f.path().join("instance/saves/world.dat")).unwrap(),
            b"changed user save"
        );
    }

    #[test]
    fn inventory_bounds_and_hostile_entries_are_rejected() {
        use super::{inventory::Limits, owned::OwnedRoot};
        let f = Fixture::new();
        let owned = OwnedRoot::create(&f.root, "instance").unwrap();
        let mods = owned.dir.child("mods", true).unwrap();
        for name in ["one.jar", "two.jar"] {
            let mut file = mods.file(name, true).unwrap();
            file.write_new(b"bytes").unwrap();
        }
        for limits in [
            Limits {
                entries: 1,
                ..Default::default()
            },
            Limits {
                bytes: 4,
                ..Default::default()
            },
            Limits {
                depth: 0,
                ..Default::default()
            },
            Limits {
                duration: std::time::Duration::ZERO,
                ..Default::default()
            },
        ] {
            assert!(owned.inventory(limits).is_err());
        }
        let mut script = mods.file("bad.ps1", true).unwrap();
        script.write_new(b"blocked").unwrap();
        drop(script);
        assert!(owned.inventory(Limits::default()).is_err());
        fs::remove_file(f.path().join("instance/mods/bad.ps1")).unwrap();
        fs::hard_link(
            f.path().join("instance/mods/one.jar"),
            f.path().join("outside-alias.jar"),
        )
        .unwrap();
        assert!(owned.inventory(Limits::default()).is_err());
        assert_eq!(
            fs::read(f.path().join("outside-alias.jar")).unwrap(),
            b"bytes"
        );
    }

    #[test]
    fn enumeration_to_open_replacement_is_detected() {
        use super::{inventory::Limits, owned::OwnedRoot};
        let f = Fixture::new();
        let owned = OwnedRoot::create(&f.root, "instance").unwrap();
        let mods = owned.dir.child("mods", true).unwrap();
        let mut file = mods.file("fixture.jar", true).unwrap();
        file.write_new(b"old").unwrap();
        drop(file);
        let mut changed = false;
        assert!(owned
            .inventory_with(Limits::default(), &mut |path| {
                if path == "mods/fixture.jar" && !changed {
                    changed = true;
                    fs::rename(
                        f.path().join("instance/mods/fixture.jar"),
                        f.path().join("old.jar"),
                    )
                    .unwrap();
                    let mut replacement = mods.file("fixture.jar", true).unwrap();
                    replacement.write_new(b"new").unwrap();
                }
            })
            .is_err());
        assert!(changed);
        assert_eq!(fs::read(f.path().join("old.jar")).unwrap(), b"old");
        assert_eq!(
            fs::read(f.path().join("instance/mods/fixture.jar")).unwrap(),
            b"new"
        );
    }

    #[test]
    fn inventory_multiple_native_pages_and_empty_files() {
        use super::{inventory::Limits, owned::OwnedRoot};
        let f = Fixture::new();
        let owned = OwnedRoot::create(&f.root, "instance").unwrap();
        let mods = owned.dir.child("mods", true).unwrap();
        // 750 names cannot fit in a single64KiB FILE_ID_EXTD_DIR_INFO page.
        for index in 0..750 {
            let file = mods.file(&format!("empty-{index:04}.jar"), true).unwrap();
            file.file.sync_all().unwrap();
        }
        let mut snapshot = owned.inventory(Limits::default()).unwrap();
        assert_eq!(snapshot.observed.len(), 750);
        for item in snapshot.observed.values() {
            assert_eq!(item.length, 0);
            assert_eq!(item.sha256, hash(b""));
        }
        snapshot.validate().unwrap();
    }

    #[test]
    fn inventory_rejects_junction_without_reading_external_target() {
        use super::{inventory::Limits, owned::OwnedRoot};
        let f = Fixture::new();
        let outside = Fixture::new();
        let owned = OwnedRoot::create(&f.root, "instance").unwrap();
        fs::write(outside.path().join("sentinel.jar"), b"external bytes").unwrap();
        let junction = f.path().join("instance/mods");
        let output = Command::new("cmd.exe")
            .args(["/d", "/c", "mklink", "/J"])
            .arg(&junction)
            .arg(outside.path())
            .output()
            .unwrap();
        assert!(output.status.success());
        let tested = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            assert!(owned.inventory(Limits::default()).is_err());
            assert_eq!(
                fs::read(outside.path().join("sentinel.jar")).unwrap(),
                b"external bytes"
            );
        }));
        fs::remove_dir(&junction).unwrap();
        if let Err(error) = tested {
            std::panic::resume_unwind(error);
        }
    }
}
