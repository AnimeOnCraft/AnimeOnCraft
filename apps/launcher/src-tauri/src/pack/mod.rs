//! 7D foundation. No production repository/root is configured; filesystem apply remains gated.
#![allow(dead_code)]
pub mod actor;
pub mod manifest;
pub mod strict_json;
pub mod transport;
pub mod trust;
// Fixed local and engine profiles share retained-handle primitives in Windows.
// Normal source configuration is closed; no filesystem or source input is exposed to IPC.
#[cfg(all(windows, feature = "pack-acceptance"))]
pub(crate) mod acceptance;
pub mod local;
mod repository;
pub(crate) fn development_actor(local: std::sync::Arc<local::LocalInstance>) -> actor::Actor {
    #[cfg(windows)]
    {
        ntfs::development_actor(local)
    }
    #[cfg(not(windows))]
    {
        actor::Actor::backend(false, actor::Startup::Blocked, move |_, _| {
            let _ = local.snapshot();
            actor::Outcome::Blocked
        })
    }
}
#[cfg(windows)]
mod ntfs;
#[cfg(windows)]
pub(crate) use ntfs::updater::pending::Journal as UpdateJournal;
#[cfg(windows)]
pub(crate) use ntfs::updater::Installed as InstalledLauncher;
#[cfg(windows)]
pub(crate) use ntfs::updater::Stage as UpdateStage;
#[derive(serde::Serialize)]
pub struct Availability {
    pub available: bool,
    pub reason: &'static str,
}
pub fn availability() -> Availability {
    Availability {
        available: false,
        reason: "RELEASE_NOT_CONFIGURED",
    }
}
#[cfg(test)]
mod tests;
