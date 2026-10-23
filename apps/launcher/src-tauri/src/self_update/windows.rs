//! Read OS version resources from the retained private file; never execute to inspect it.
use super::{stable_version, Result, VerifiedInstaller};
use std::{
    ffi::c_void,
    os::windows::{ffi::OsStrExt, process::CommandExt},
    path::Path,
    ptr,
};
use windows_sys::Win32::Storage::FileSystem::*;
fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(Some(0)).collect()
}
pub(super) fn identity(path: &Path, expected: &str, product: &str) -> Result<()> {
    let path: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    let length = unsafe { GetFileVersionInfoSizeW(path.as_ptr(), ptr::null_mut()) };
    if !(std::mem::size_of::<VS_FIXEDFILEINFO>() as u32..=256 * 1024).contains(&length) {
        return Err("UPDATE_BINARY_IDENTITY");
    }
    let mut storage = vec![0u64; (length as usize).div_ceil(8)];
    if unsafe { GetFileVersionInfoW(path.as_ptr(), 0, length, storage.as_mut_ptr().cast()) } == 0 {
        return Err("UPDATE_BINARY_IDENTITY");
    }
    let query = |name: &str, scale: usize| -> Result<(*mut c_void, usize)> {
        let mut value = ptr::null_mut();
        let mut size = 0;
        if unsafe {
            VerQueryValueW(
                storage.as_ptr().cast(),
                wide(name).as_ptr(),
                &mut value,
                &mut size,
            )
        } == 0
        {
            return Err("UPDATE_BINARY_IDENTITY");
        }
        let bytes = (size as usize)
            .checked_mul(scale)
            .ok_or("UPDATE_BINARY_IDENTITY")?;
        let start = storage.as_ptr() as usize;
        if value.is_null()
            || (value as usize) < start
            || (value as usize)
                .checked_add(bytes)
                .is_none_or(|end| end > start + length as usize)
        {
            return Err("UPDATE_BINARY_IDENTITY");
        }
        Ok((value, size as usize))
    };
    let (info, size) = query("\\", 1)?;
    if size != std::mem::size_of::<VS_FIXEDFILEINFO>() {
        return Err("UPDATE_BINARY_IDENTITY");
    }
    let info = unsafe { ptr::read_unaligned(info.cast::<VS_FIXEDFILEINFO>()) };
    let version = stable_version(expected)?;
    if info.dwSignature != 0xfeef04bd
        || info.dwFileType != 1
        || info.dwProductVersionMS >> 16 != version.major as u32
        || info.dwProductVersionMS & 0xffff != version.minor as u32
        || info.dwProductVersionLS >> 16 != version.patch as u32
        || info.dwProductVersionLS & 0xffff != 0
        || [version.major, version.minor, version.patch]
            .iter()
            .any(|v| *v > 65535)
    {
        return Err("UPDATE_BINARY_IDENTITY");
    }
    let (translation, size) = query("\\VarFileInfo\\Translation", 1)?;
    if size == 0 || size > 256 || size % 4 != 0 {
        return Err("UPDATE_BINARY_IDENTITY");
    }
    for offset in (0..size).step_by(4) {
        let lang =
            unsafe { ptr::read_unaligned(translation.cast::<u8>().add(offset).cast::<u16>()) };
        let page =
            unsafe { ptr::read_unaligned(translation.cast::<u8>().add(offset + 2).cast::<u16>()) };
        let (text, count) = query(
            &format!("\\StringFileInfo\\{lang:04x}{page:04x}\\ProductName"),
            2,
        )?;
        if count == 0 || count > 512 || text as usize % 2 != 0 {
            return Err("UPDATE_BINARY_IDENTITY");
        }
        let text = unsafe { std::slice::from_raw_parts(text.cast::<u16>(), count) };
        if text.last() != Some(&0)
            || String::from_utf16(&text[..count - 1]).map_err(|_| "UPDATE_BINARY_IDENTITY")?
                != product
        {
            return Err("UPDATE_BINARY_IDENTITY");
        }
    }
    Ok(())
}
fn installed_registration(
    installed: &crate::pack::InstalledLauncher,
    product: &str,
    current: &str,
) -> Result<()> {
    use windows_sys::Win32::System::Registry::*;
    if product.is_empty() || product.len() > 200 || product.contains(['\\', '"']) {
        return Err("UPDATE_NOT_INSTALLED");
    }
    let key = wide(&format!(
        "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{product}"
    ));
    let read = |value: &str| -> Result<String> {
        let mut buffer = vec![0u16; 32768];
        let mut bytes = (buffer.len() * 2) as u32;
        if unsafe {
            RegGetValueW(
                HKEY_CURRENT_USER,
                key.as_ptr(),
                wide(value).as_ptr(),
                RRF_RT_REG_SZ | RRF_NOEXPAND,
                ptr::null_mut(),
                buffer.as_mut_ptr().cast(),
                &mut bytes,
            )
        } != 0
            || bytes < 2
            || bytes as usize > buffer.len() * 2
            || bytes % 2 != 0
        {
            return Err("UPDATE_NOT_INSTALLED");
        }
        let count = bytes as usize / 2;
        if buffer[count - 1] != 0 {
            return Err("UPDATE_NOT_INSTALLED");
        }
        String::from_utf16(&buffer[..count - 1]).map_err(|_| "UPDATE_NOT_INSTALLED")
    };
    if read("DisplayVersion")? != current {
        return Err("UPDATE_NOT_INSTALLED");
    }
    let location = read("InstallLocation")?;
    let location = location
        .strip_prefix('"')
        .and_then(|v| v.strip_suffix('"'))
        .ok_or("UPDATE_NOT_INSTALLED")?;
    if Path::new(location)
        .canonicalize()
        .map_err(|_| "UPDATE_NOT_INSTALLED")?
        != installed.target()?
    {
        return Err("UPDATE_NOT_INSTALLED");
    }
    Ok(())
}
pub(super) struct Handoff {
    stage: crate::pack::UpdateStage,
    authority: VerifiedInstaller,
    installed: crate::pack::InstalledLauncher,
}
impl Handoff {
    pub(super) fn prepare(
        authority: VerifiedInstaller,
        product: &str,
        current: &str,
    ) -> Result<Self> {
        authority.validate()?;
        let installed = crate::pack::InstalledLauncher::current()?;
        installed_registration(&installed, product, current)?;
        let mut stage = authority.local.update_stage(&authority.bytes)?;
        identity(stage.checked_path()?, &authority.version, product)?;
        authority.validate()?;
        Ok(Self {
            stage,
            authority,
            installed,
        })
    }
    pub(super) fn spawn(&mut self) -> Result<std::process::Child> {
        self.authority.validate()?;
        let path = self.stage.checked_path()?;
        // std Command calls CreateProcess directly with an absolute retained executable path.
        // No shell, elevation, user URL, command-line override or token forwarding.
        let mut command = std::process::Command::new(path);
        command
            .args(["/P", "/UPDATE", "/NS"])
            .arg(format!("/AOC_PARENT_PID={}", std::process::id()));
        #[cfg(not(feature = "self-update-acceptance"))]
        command.arg("/R");
        let target = self
            .installed
            .target()?
            .to_str()
            .ok_or("UPDATE_NOT_INSTALLED")?;
        let target = target.strip_prefix(r"\\?\").unwrap_or(target);
        if target.len() > 512 || target.chars().any(|c| c < ' ' || c == '"') {
            return Err("UPDATE_NOT_INSTALLED");
        }
        // NSIS /D is explicitly unquoted and last, even when the retained directory contains spaces.
        command.raw_arg(format!("/D={target}"));
        command
            .creation_flags(0x08000000)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null());
        let child = command.spawn().map_err(|_| "UPDATE_HANDOFF_FAILED")?;
        self.stage.handed_off();
        Ok(child)
    }
}
