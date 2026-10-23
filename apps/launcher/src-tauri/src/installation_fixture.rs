//! Installer acceptance only: installed test exe and own workspace parent, never normal roots.
use std::path::PathBuf;
pub(crate) fn directory() -> Result<PathBuf, &'static str> {
    let workspace = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../..")
        .canonicalize()
        .map_err(|_| "INSTALLER_FIXTURE_INVALID")?;
    let input = PathBuf::from(
        std::env::var_os("AOC_INSTALLER_ACCEPTANCE_DIRECTORY")
            .ok_or("INSTALLER_FIXTURE_INVALID")?,
    );
    let directory = input
        .canonicalize()
        .map_err(|_| "INSTALLER_FIXTURE_INVALID")?;
    let name = directory
        .file_name()
        .and_then(|n| n.to_str())
        .ok_or("INSTALLER_FIXTURE_INVALID")?;
    let uuid = name
        .strip_prefix("installer-acceptance-")
        .and_then(|v| uuid::Uuid::parse_str(v).ok())
        .ok_or("INSTALLER_FIXTURE_INVALID")?;
    if name != format!("installer-acceptance-{}", uuid.simple())
        || directory != workspace.join(".tools").join(name)
        || input.file_name() != directory.file_name()
        || input.parent().and_then(|p| p.canonicalize().ok()).as_ref()
            != Some(&workspace.join(".tools"))
    {
        return Err("INSTALLER_FIXTURE_INVALID");
    }
    let executable = std::env::current_exe()
        .and_then(|p| p.canonicalize())
        .map_err(|_| "INSTALLER_FIXTURE_INVALID")?;
    if executable != directory.join("install/animeoncraft-launcher.exe") {
        return Err("INSTALLER_FIXTURE_INVALID");
    }
    Ok(directory)
}
