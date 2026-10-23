//! Friends launcher: a single Tauri updater source, ordinary NSIS installation, no pack journal.
use base64::{engine::general_purpose::STANDARD, Engine};
use serde::{Deserialize, Serialize};
use std::{
    sync::{Arc, Mutex},
    time::Duration,
};
use tauri_plugin_updater::{Update, UpdaterExt};
const LIMIT: u64 = 64 * 1024 * 1024;
type Result<T> = std::result::Result<T, &'static str>;

#[derive(Clone, Deserialize)]
#[serde(deny_unknown_fields)]
struct Source {
    endpoint: Option<String>,
    public_key: Option<String>,
}
impl Source {
    fn parse(text: &str) -> Result<Option<Self>> {
        let source: Self = serde_json::from_str(text).map_err(|_| "CONFIG_INVALID")?;
        match (&source.endpoint, &source.public_key) {
            (None, None) => Ok(None),
            (Some(endpoint), Some(key)) => {
                let url = reqwest::Url::parse(endpoint).map_err(|_| "CONFIG_INVALID")?;
                if !safe_url(&url) || !url.path().ends_with("/latest.json") {
                    return Err("CONFIG_INVALID");
                }
                let bytes = STANDARD.decode(key).map_err(|_| "CONFIG_INVALID")?;
                let text = std::str::from_utf8(&bytes).map_err(|_| "CONFIG_INVALID")?;
                minisign_verify::PublicKey::decode(text).map_err(|_| "CONFIG_INVALID")?;
                Ok(Some(source))
            }
            _ => Err("CONFIG_INVALID"),
        }
    }
}
fn safe_url(url: &reqwest::Url) -> bool {
    url.scheme() == "https"
        && url.host_str().is_some()
        && url.username().is_empty()
        && url.password().is_none()
        && url.fragment().is_none()
        && url.query().is_none()
}
#[derive(Clone, Copy, Serialize, PartialEq, Eq, Debug)]
#[serde(rename_all = "snake_case")]
pub(crate) enum Status {
    Unavailable,
    Idle,
    Checking,
    Current,
    Available,
    Downloading,
    Installing,
    Error,
}
#[derive(Clone, Serialize)]
pub(crate) struct Snapshot {
    current_version: String,
    source_configured: bool,
    installed: bool,
    status: Status,
    latest_version: Option<String>,
    downloaded: u64,
    total: Option<u64>,
    reason: Option<&'static str>,
}
#[derive(Deserialize)]
#[serde(tag = "action", rename_all = "snake_case", deny_unknown_fields)]
pub(crate) enum Input {
    Check {},
    Install { version: String },
    Cancel {},
}
struct State {
    view: Snapshot,
    update: Option<Update>,
    cancel: Option<tokio::sync::watch::Sender<bool>>,
}
struct Core {
    source: Option<Source>,
    state: Mutex<State>,
}
#[derive(Clone)]
pub(crate) struct Manager {
    core: Arc<Core>,
}
impl Manager {
    /// Keep checking even when the window is hidden. A known update stays mandatory.
    pub(crate) fn start_polling(&self, app: tauri::AppHandle) {
        let manager = self.clone();
        tauri::async_runtime::spawn(async move {
            let mut timer = tokio::time::interval(Duration::from_secs(5));
            timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                timer.tick().await;
                if let Ok(view) = manager.snapshot() {
                    if view.source_configured
                        && view.installed
                        && matches!(view.status, Status::Idle | Status::Current | Status::Error)
                        && view.latest_version.is_none()
                    {
                        let _ = manager.action(Input::Check {}, app.clone());
                    }
                }
            }
        });
    }
    /// Native launch and every new admission ticket require a fresh successful check.
    pub(crate) async fn require_current(&self, app: tauri::AppHandle) -> Result<()> {
        let view = self.snapshot()?;
        if !view.source_configured || !view.installed {
            return Ok(());
        }
        if view.latest_version.is_some() {
            return Err("LAUNCHER_UPDATE_REQUIRED");
        }
        match self.action(Input::Check {}, app) {
            Ok(_) | Err("UPDATE_BUSY") => {}
            Err(error) => return Err(error),
        }
        let deadline = tokio::time::Instant::now() + Duration::from_secs(20);
        loop {
            let view = self.snapshot()?;
            if view.latest_version.is_some() {
                return Err("LAUNCHER_UPDATE_REQUIRED");
            }
            match view.status {
                Status::Current => return Ok(()),
                Status::Checking if tokio::time::Instant::now() < deadline => {
                    tokio::time::sleep(Duration::from_millis(50)).await
                }
                _ => return Err("LAUNCHER_UPDATE_CHECK_FAILED"),
            }
        }
    }
    pub(crate) fn new(app: &tauri::AppHandle) -> Self {
        #[cfg(not(all(windows, feature = "simple-update-acceptance")))]
        let source = Source::parse(include_str!("../simple-update.json"));
        #[cfg(all(windows, feature = "simple-update-acceptance"))]
        let source = crate::installation_fixture::directory().and_then(|directory| {
            let text = std::fs::read_to_string(directory.join("simple-update.json"))
                .map_err(|_| "CONFIG_INVALID")?;
            Source::parse(&text)
        });
        let installed = installed(app);
        let configured = source.as_ref().is_ok_and(|s| s.is_some());
        let reason = if source.is_err() {
            Some("CONFIG_INVALID")
        } else if !configured {
            Some("NOT_CONFIGURED")
        } else if !installed {
            Some("NOT_INSTALLED")
        } else {
            None
        };
        Self {
            core: Arc::new(Core {
                source: source.ok().flatten(),
                state: Mutex::new(State {
                    view: Snapshot {
                        current_version: app.package_info().version.to_string(),
                        source_configured: configured,
                        installed,
                        status: if reason.is_some() {
                            Status::Unavailable
                        } else {
                            Status::Idle
                        },
                        latest_version: None,
                        downloaded: 0,
                        total: None,
                        reason,
                    },
                    update: None,
                    cancel: None,
                }),
            }),
        }
    }
    pub(crate) fn snapshot(&self) -> Result<Snapshot> {
        Ok(self
            .core
            .state
            .lock()
            .map_err(|_| "UPDATE_BUSY")?
            .view
            .clone())
    }
    pub(crate) fn busy(&self) -> Result<bool> {
        Ok(matches!(
            self.core
                .state
                .lock()
                .map_err(|_| "UPDATE_BUSY")?
                .view
                .status,
            Status::Downloading | Status::Installing
        ))
    }
    pub(crate) fn action(&self, input: Input, app: tauri::AppHandle) -> Result<Snapshot> {
        let mut state = self.core.state.lock().map_err(|_| "UPDATE_BUSY")?;
        if matches!(input, Input::Cancel {}) {
            if let Some(cancel) = &state.cancel {
                cancel.send_replace(true);
            }
            return Ok(state.view.clone());
        }
        if matches!(
            state.view.status,
            Status::Checking | Status::Downloading | Status::Installing
        ) {
            return Err("UPDATE_BUSY");
        }
        if !state.view.source_configured || !state.view.installed {
            return Ok(state.view.clone());
        }
        let (cancel, receiver) = tokio::sync::watch::channel(false);
        state.cancel = Some(cancel);
        let update = match input {
            Input::Check {} => {
                state.view.status = Status::Checking;
                None
            }
            Input::Install { version } => {
                if state
                    .update
                    .as_ref()
                    .is_none_or(|update| update.version != version)
                {
                    state.cancel = None;
                    return Err("UPDATE_CHANGED");
                }
                state.view.status = Status::Downloading;
                state.update.take()
            }
            Input::Cancel {} => unreachable!(),
        };
        state.view.downloaded = 0;
        state.view.total = None;
        state.view.reason = None;
        let snapshot = state.view.clone();
        let manager = self.clone();
        tauri::async_runtime::spawn(async move {
            manager.run(app, update, receiver).await;
        });
        Ok(snapshot)
    }
    async fn run(
        &self,
        app: tauri::AppHandle,
        update: Option<Update>,
        mut cancel: tokio::sync::watch::Receiver<bool>,
    ) {
        let outcome = if let Some(update) = update {
            let progress = self.clone();
            let (oversize, mut limit) = tokio::sync::watch::channel(false);
            let download = update.download(
                move |chunk, total| {
                    if let Ok(mut state) = progress.core.state.lock() {
                        state.view.downloaded = state.view.downloaded.saturating_add(chunk as u64);
                        state.view.total = total;
                        if state.view.downloaded > LIMIT || total.is_some_and(|n| n > LIMIT) {
                            oversize.send_replace(true);
                        }
                    }
                },
                || {},
            );
            let result = tokio::select! {
                biased;
                _ = cancel.changed() => Err("CANCELLED"),
                _ = limit.changed() => Err("TOO_LARGE"),
                result = tokio::time::timeout(Duration::from_secs(120), download) => result.map_err(|_| "NETWORK").and_then(|value| value.map_err(|_| "DOWNLOAD_FAILED")),
            };
            match result {
                Ok(bytes)
                    if bytes.len() as u64 <= LIMIT && !*cancel.borrow() && !*limit.borrow() =>
                {
                    if let Ok(mut state) = self.core.state.lock() {
                        state.view.status = Status::Installing;
                        state.cancel = None;
                    }
                    // The standard updater validates signature+signed version before returning bytes.
                    // NSIS waits for this exact parent, installs to its current directory, then restarts.
                    let result =
                        tauri::async_runtime::spawn_blocking(move || update.install(bytes)).await;
                    match result {
                        Ok(Ok(())) => return,
                        _ => Err("INSTALL_FAILED"),
                    }
                }
                Ok(_) => Err("TOO_LARGE"),
                Err(error) => {
                    if let Ok(mut state) = self.core.state.lock() {
                        state.update = Some(update);
                    }
                    Err(error)
                }
            }
        } else {
            tokio::select! {
                biased;
                _ = cancel.changed() => Err("CANCELLED"),
                result = self.check(&app) => result,
            }
        };
        if let Ok(mut state) = self.core.state.lock() {
            state.cancel = None;
            if let Err(error) = outcome {
                state.view.reason = Some(error);
                state.view.status = if error == "CANCELLED" {
                    if state.update.is_some() {
                        Status::Available
                    } else {
                        Status::Idle
                    }
                } else {
                    Status::Error
                };
            }
        }
    }
    async fn check(&self, app: &tauri::AppHandle) -> Result<()> {
        let source = self.core.source.as_ref().ok_or("NOT_CONFIGURED")?;
        let endpoint = reqwest::Url::parse(source.endpoint.as_ref().ok_or("CONFIG_INVALID")?)
            .map_err(|_| "CONFIG_INVALID")?;
        let directory = std::env::current_exe()
            .map_err(|_| "NOT_INSTALLED")?
            .parent()
            .ok_or("NOT_INSTALLED")?
            .to_path_buf();
        #[allow(unused_mut)]
        let mut builder = app
            .updater_builder()
            .pubkey(source.public_key.as_ref().ok_or("CONFIG_INVALID")?)
            .endpoints(vec![endpoint.clone()])
            .map_err(|_| "CONFIG_INVALID")?
            .target("windows-x86_64-nsis")
            .timeout(Duration::from_secs(15))
            .no_proxy()
            .installer_args([
                format!("/AOC_PARENT_PID={}", std::process::id()),
                "/NS".into(),
                format!("/D={}", directory.display()),
            ])
            .configure_client(|client| {
                client
                    .redirect(reqwest::redirect::Policy::none())
                    .connect_timeout(Duration::from_secs(5))
            });
        #[cfg(all(windows, feature = "simple-update-acceptance"))]
        {
            let directory = crate::installation_fixture::directory()?;
            let bytes = std::fs::read(directory.join("cert.pem")).map_err(|_| "CONFIG_INVALID")?;
            let cert = reqwest::Certificate::from_pem(&bytes).map_err(|_| "CONFIG_INVALID")?;
            builder = builder.configure_client(move |client| {
                client
                    .redirect(reqwest::redirect::Policy::none())
                    .add_root_certificate(cert.clone())
            });
        }
        let mut update = builder
            .build()
            .map_err(|_| "CONFIG_INVALID")?
            .check()
            .await
            .map_err(|_| "NETWORK")?;
        if let Some(update) = &mut update {
            let version = semver::Version::parse(&update.version).map_err(|_| "RELEASE_INVALID")?;
            if !version.pre.is_empty()
                || !version.build.is_empty()
                || !safe_url(&update.download_url)
                || update.download_url.origin() != endpoint.origin()
                || !update
                    .download_url
                    .path()
                    .ends_with(&format!("/AnimeOnCraft_{}_x64-setup.exe", update.version))
            {
                return Err("RELEASE_INVALID");
            }
            update.timeout = Some(Duration::from_secs(120));
        }
        let mut state = self.core.state.lock().map_err(|_| "UPDATE_BUSY")?;
        state.view.latest_version = update.as_ref().map(|u| u.version.clone());
        state.view.status = if update.is_some() {
            Status::Available
        } else {
            Status::Current
        };
        state.update = update;
        Ok(())
    }
}

#[cfg(windows)]
fn installed(app: &tauri::AppHandle) -> bool {
    use windows_sys::Win32::System::Registry::{RegGetValueW, HKEY_CURRENT_USER, RRF_RT_REG_SZ};
    let name = app
        .config()
        .product_name
        .as_deref()
        .unwrap_or("AnimeOnCraft");
    let wide = |text: &str| text.encode_utf16().chain(Some(0)).collect::<Vec<_>>();
    let key = wide(&format!(
        "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{name}"
    ));
    let value = wide("InstallLocation");
    let mut buffer = [0u16; 2048];
    let mut bytes = std::mem::size_of_val(&buffer) as u32;
    if unsafe {
        RegGetValueW(
            HKEY_CURRENT_USER,
            key.as_ptr(),
            value.as_ptr(),
            RRF_RT_REG_SZ,
            std::ptr::null_mut(),
            buffer.as_mut_ptr().cast(),
            &mut bytes,
        )
    } != 0
    {
        return false;
    }
    let length = buffer.iter().position(|c| *c == 0).unwrap_or(buffer.len());
    let path = String::from_utf16_lossy(&buffer[..length]);
    let installed = std::path::Path::new(path.trim_matches('"')).canonicalize();
    let current = std::env::current_exe().and_then(|p| p.parent().unwrap().canonicalize());
    matches!((installed, current), (Ok(left), Ok(right)) if left == right)
}
#[cfg(not(windows))]
fn installed(_: &tauri::AppHandle) -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn no_placeholder_source_or_transport_bypass() {
        assert!(Source::parse(include_str!("../simple-update.json")).is_ok());
        assert!(Source::parse(r#"{"endpoint":null,"public_key":null}"#)
            .unwrap()
            .is_none());
        for input in [
            r#"{"endpoint":"https://example.test/latest.json","public_key":null}"#,
            r#"{"endpoint":null,"public_key":"fake"}"#,
            r#"{"endpoint":null,"public_key":null,"force":true}"#,
        ] {
            assert!(Source::parse(input).is_err());
        }
        for url in [
            "http://example.test/latest.json",
            "https://user:password@example.test/latest.json",
            "https://example.test/latest.json?token=x",
            "https://example.test/latest.json#x",
        ] {
            assert!(!safe_url(&reqwest::Url::parse(url).unwrap()));
        }
        assert!(safe_url(
            &reqwest::Url::parse("https://example.test/latest.json").unwrap()
        ));
    }
    #[test]
    fn renderer_cannot_set_source_file_or_installer_arguments() {
        for text in [
            r#"{"action":"check","url":"https://evil.test/latest.json"}"#,
            r#"{"action":"install","version":"0.7.22","path":"F:/foreign.exe"}"#,
            r#"{"action":"cancel","force":true}"#,
        ] {
            assert!(serde_json::from_str::<Input>(text).is_err());
        }
    }
}
