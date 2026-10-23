//! Local prepared Minecraft runtime. Account credentials never enter Java or IPC.
use crate::{
    account_client::{self, Error},
    auth::Auth,
    game_profile,
    settings::LauncherSettings,
};
use serde::{Deserialize, Serialize};
use std::{
    fs::{create_dir_all, OpenOptions},
    path::PathBuf,
    sync::{Arc, Mutex},
    time::Duration,
};
use tauri::Manager;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    process::Command,
};
use zeroize::Zeroizing;

fn java_command(path: &std::path::Path) -> Command {
    let mut command = Command::new(path);
    command.env_clear();
    for name in [
        "SystemRoot",
        "WINDIR",
        "PATH",
        "TEMP",
        "TMP",
        "USERPROFILE",
        "APPDATA",
        "LOCALAPPDATA",
    ] {
        if let Some(value) = std::env::var_os(name) {
            command.env(name, value);
        }
    }
    #[cfg(windows)]
    command.creation_flags(0x08000000);
    command
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Runtime {
    pub format_version: u8,
    pub server_id: String,
    pub server_port: u16,
    #[serde(default = "local_host")]
    pub server_host: String,
    pub java: PathBuf,
    pub game_directory: PathBuf,
    pub args: Vec<String>,
}
fn local_host() -> String {
    "127.0.0.1".into()
}
fn compatible_mod(path: &std::path::Path, expected: &str) -> Result<(), Error> {
    use sha2::Digest;
    let bytes = std::fs::read(path).map_err(|_| "RUNTIME_MOD_UPDATE_REQUIRED")?;
    if format!("{:x}", sha2::Sha256::digest(bytes)) != expected {
        return Err("RUNTIME_MOD_UPDATE_REQUIRED");
    }
    Ok(())
}
impl Runtime {
    pub(crate) fn validate(&self) -> Result<(), Error> {
        if self.format_version != 1
            || !account_client::id(&self.server_id)
            || self.server_port == 0
            || !crate::deployment::game_host(&self.server_host)
            || !self.java.is_absolute()
            || !self.java.is_file()
            || self
                .java
                .file_name()
                .is_none_or(|v| !v.eq_ignore_ascii_case("java.exe"))
            || !self.game_directory.is_absolute()
            || !self.game_directory.is_dir()
            || !self
                .game_directory
                .join("mods/animeoncraft-admission-0.3.0-friends-dev.jar")
                .is_file()
            || self.args.len() > 300
            || self.args.is_empty()
            || self
                .args
                .iter()
                .any(|a| a.len() > 65536 || a.contains('\0'))
            || !self.args.iter().any(|a| a == "%AOC_NAME%")
            || !self.args.iter().any(|a| a == "%AOC_UUID%")
            || !self
                .args
                .iter()
                .any(|a| a == &format!("{}:{}", self.server_host, self.server_port))
        {
            return Err("RUNTIME_INVALID");
        }
        Ok(())
    }
    fn arguments(
        &self,
        profile: &game_profile::Profile,
        settings: &LauncherSettings,
    ) -> Vec<String> {
        let mut args: Vec<String> = self
            .args
            .iter()
            .map(|s| {
                s.replace("%AOC_NAME%", &profile.game_name)
                    .replace("%AOC_UUID%", &profile.minecraft_uuid.replace('-', ""))
                    .replace("%AOC_RAM%", &settings.ram_mib.to_string())
                    .replace("%AOC_WIDTH%", &settings.width.to_string())
                    .replace("%AOC_HEIGHT%", &settings.height.to_string())
            })
            .collect();
        if settings.fullscreen {
            args.push("--fullscreen".into());
        }
        args
    }
}

#[derive(Clone, Serialize)]
pub struct Snapshot {
    pub state: &'static str,
    pub reason: &'static str,
    pub can_launch: bool,
}
pub struct Game {
    path: PathBuf,
    state: Arc<Mutex<Snapshot>>,
    stop: Mutex<Option<tokio::sync::oneshot::Sender<()>>>,
}
impl Game {
    pub(crate) fn runtime_path(&self) -> PathBuf {
        self.path.clone()
    }
    pub(crate) fn begin_preparation(&self) -> Result<(), Error> {
        let mut state = self.state.lock().map_err(|_| "GAME_UNAVAILABLE")?;
        if matches!(state.state, "STARTING" | "RUNNING" | "PREPARING") {
            return Err("GAME_ALREADY_RUNNING");
        }
        *state = Snapshot {
            state: "PREPARING",
            reason: "PREPARING",
            can_launch: false,
        };
        Ok(())
    }
    pub(crate) fn end_preparation(&self, error: Option<Error>) {
        if let Ok(mut state) = self.state.lock() {
            *state = Snapshot {
                state: if error.is_some() { "FAILED" } else { "STOPPED" },
                reason: error.unwrap_or("READY"),
                can_launch: false,
            };
        }
    }
    pub fn new(path: PathBuf) -> Self {
        Self {
            path,
            stop: Mutex::new(None),
            state: Arc::new(Mutex::new(Snapshot {
                state: "STOPPED",
                reason: "RUNTIME_NOT_CONFIGURED",
                can_launch: false,
            })),
        }
    }
    pub fn stop(&self) -> Result<(), Error> {
        if let Some(stop) = self.stop.lock().map_err(|_| "GAME_UNAVAILABLE")?.take() {
            let _ = stop.send(());
        }
        Ok(())
    }
    fn runtime(&self) -> Result<Runtime, Error> {
        use std::io::Read;
        let file = std::fs::File::open(&self.path).map_err(|_| "RUNTIME_NOT_CONFIGURED")?;
        let mut bytes = Vec::new();
        file.take(131073)
            .read_to_end(&mut bytes)
            .map_err(|_| "RUNTIME_INVALID")?;
        if bytes.len() > 131072 {
            return Err("RUNTIME_INVALID");
        }
        let value: Runtime = serde_json::from_slice(&bytes).map_err(|_| "RUNTIME_INVALID")?;
        if value
            .game_directory
            .parent()
            .is_some_and(|parent| parent.join("preparation.pending").exists())
        {
            return Err("RUNTIME_PREPARATION_REQUIRED");
        }
        value.validate()?;
        crate::managed_pack::verify(&value.game_directory)?;
        if let Some(expected) =
            option_env!("AOC_REQUIRED_ADMISSION_SHA256").filter(|hash| hash.len() == 64)
        {
            compatible_mod(
                &value
                    .game_directory
                    .join("mods/animeoncraft-admission-0.3.0-friends-dev.jar"),
                expected,
            )?;
        }
        Ok(value)
    }
    pub async fn server_status(&self) -> crate::game_status::Snapshot {
        match self.runtime() {
            Ok(runtime) => {
                crate::game_status::probe(&runtime.server_host, runtime.server_port).await
            }
            Err(_) => crate::game_status::Snapshot::unavailable("NOT_CONFIGURED"),
        }
    }
    pub fn snapshot(&self) -> Result<Snapshot, Error> {
        let mut view = self.state.lock().map_err(|_| "GAME_UNAVAILABLE")?.clone();
        if !matches!(view.state, "STARTING" | "RUNNING" | "PREPARING") {
            let runtime = self.runtime();
            view.can_launch = runtime.is_ok();
            if view.state == "STOPPED" {
                view.reason = runtime.err().unwrap_or("READY");
            }
        }
        Ok(view)
    }
    pub async fn launch(
        &self,
        app: tauri::AppHandle,
        settings: LauncherSettings,
    ) -> Result<Snapshot, Error> {
        {
            let mut state = self.state.lock().map_err(|_| "GAME_UNAVAILABLE")?;
            if matches!(state.state, "STARTING" | "RUNNING" | "PREPARING") {
                return Err("GAME_ALREADY_RUNNING");
            }
            *state = Snapshot {
                state: "STARTING",
                reason: "CHECKING",
                can_launch: false,
            };
        }
        let result = self.start(&app, settings).await;
        match result {
            Ok((mut child, listener, key, generation, runtime, profile)) => {
                let mut state = self.state.lock().map_err(|_| "GAME_UNAVAILABLE")?;
                *state = Snapshot {
                    state: "RUNNING",
                    reason: "RUNNING",
                    can_launch: false,
                };
                let shared = self.state.clone();
                let (sender, mut stop) = tokio::sync::oneshot::channel();
                *self.stop.lock().map_err(|_| "GAME_UNAVAILABLE")? = Some(sender);
                tauri::async_runtime::spawn(async move {
                    let bridge = serve(listener, key, app, generation, runtime.server_id, profile);
                    tokio::pin!(bridge);
                    let result = tokio::select! {result = child.wait() => result, _ = &mut stop => {let _ = child.kill().await; child.wait().await}, _ = &mut bridge => {let _ = child.kill().await; child.wait().await}};
                    if let Ok(mut state) = shared.lock() {
                        *state = Snapshot {
                            state: if result.as_ref().is_ok_and(|r| r.success()) {
                                "EXITED"
                            } else {
                                "FAILED"
                            },
                            reason: if result.as_ref().is_ok_and(|r| r.success()) {
                                "EXITED"
                            } else {
                                "GAME_PROCESS_FAILED"
                            },
                            can_launch: false,
                        };
                    }
                });
                Ok(state.clone())
            }
            Err(error) => {
                *self.state.lock().map_err(|_| "GAME_UNAVAILABLE")? = Snapshot {
                    state: "FAILED",
                    reason: error,
                    can_launch: false,
                };
                Err(error)
            }
        }
    }
    async fn start(
        &self,
        app: &tauri::AppHandle,
        settings: LauncherSettings,
    ) -> Result<
        (
            tokio::process::Child,
            TcpListener,
            Zeroizing<String>,
            u64,
            Runtime,
            game_profile::Profile,
        ),
        Error,
    > {
        let settings = crate::settings::validate(settings)?;
        let runtime = self.runtime()?;
        let auth = app.state::<Auth>();
        auth.ensure_session().await?;
        let (generation, _) = auth.access()?;
        let profile = game_profile::read(&auth)
            .await?
            .profile
            .ok_or("GAME_PROFILE_REQUIRED")?;
        let access =
            crate::player_client::read(&auth, crate::player_client::ReadInput::Access).await?;
        if access.get("effective_access").and_then(|v| v.as_str()) != Some("ALLOWED") {
            return Err("GAME_ACCESS_REQUIRED");
        }
        match crate::game_status::probe(&runtime.server_host, runtime.server_port)
            .await
            .status
        {
            "AVAILABLE" => (),
            "INCOMPATIBLE" => return Err("GAME_SERVER_INCOMPATIBLE"),
            "INVALID_RESPONSE" => return Err("GAME_SERVER_INVALID_RESPONSE"),
            _ => return Err("GAME_SERVER_UNAVAILABLE"),
        }
        let version = tokio::time::timeout(
            Duration::from_secs(5),
            java_command(&runtime.java)
                .arg("-version")
                .kill_on_drop(true)
                .output(),
        )
        .await
        .map_err(|_| "JAVA_UNAVAILABLE")?
        .map_err(|_| "JAVA_UNAVAILABLE")?;
        if !version.status.success()
            || !String::from_utf8_lossy(&version.stderr).contains("version \"21.")
        {
            return Err("JAVA_21_REQUIRED");
        }
        if !auth.current(generation) {
            return Err("CANCELLED");
        }
        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .map_err(|_| "BRIDGE_UNAVAILABLE")?;
        let key = Zeroizing::new(format!(
            "{}{}",
            uuid::Uuid::new_v4().simple(),
            uuid::Uuid::new_v4().simple()
        ));
        let mut command = java_command(&runtime.java);
        command
            .args(runtime.arguments(&profile, &settings))
            .current_dir(&runtime.game_directory)
            .kill_on_drop(true)
            .stdin(std::process::Stdio::null());
        let log_dir = runtime.game_directory.join("logs");
        create_dir_all(&log_dir).map_err(|_| "GAME_LOG_UNAVAILABLE")?;
        let log_path = log_dir.join("animeoncraft-launcher-game.log");
        let stdout_log = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&log_path)
            .map_err(|_| "GAME_LOG_UNAVAILABLE")?;
        let stderr_log = stdout_log.try_clone().map_err(|_| "GAME_LOG_UNAVAILABLE")?;
        command.stdout(std::process::Stdio::from(stdout_log));
        command.stderr(std::process::Stdio::from(stderr_log));
        command
            .env(
                "AOC_GAME_BRIDGE_PORT",
                listener
                    .local_addr()
                    .map_err(|_| "BRIDGE_UNAVAILABLE")?
                    .port()
                    .to_string(),
            )
            .env("AOC_GAME_BRIDGE_KEY", key.as_str())
            .env("AOC_GAME_TARGET_PORT", runtime.server_port.to_string())
            .env("AOC_GAME_TARGET_HOST", &runtime.server_host);
        #[cfg(windows)]
        command.creation_flags(0x08000000);
        let child = command.spawn().map_err(|_| "GAME_START_FAILED")?;
        Ok((child, listener, key, generation, runtime, profile))
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Challenge {
    server_id: String,
    connection_id: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Ticket {
    ticket: Zeroizing<String>,
    server_id: String,
    game_name: String,
    minecraft_uuid: String,
    expires_at: String,
}

async fn ticket(
    auth: &Auth,
    generation: u64,
    server_id: &str,
    profile: &game_profile::Profile,
) -> Result<Zeroizing<String>, Error> {
    for attempt in 0..2 {
        if !auth.current(generation) {
            return Err("CANCELLED");
        }
        auth.ensure_session().await?;
        let (current, access) = auth.access()?;
        if current != generation {
            return Err("CANCELLED");
        }
        let reply = auth
            .client
            .request(
                reqwest::Method::POST,
                "game/tickets",
                Some(serde_json::json!({})),
                Some(&access),
                None,
            )
            .await?;
        if !auth.current(generation) {
            return Err("CANCELLED");
        }
        if reply.status == 401 && attempt == 0 {
            auth.expired(generation, &access);
            continue;
        }
        reply.success()?;
        let result: Ticket = reply.parse()?;
        if result.server_id != server_id
            || result.game_name != profile.game_name
            || result.minecraft_uuid != profile.minecraft_uuid
            || !account_client::opaque(&result.ticket, "gt_")
            || !account_client::future(&result.expires_at)
        {
            return Err("INVALID_RESPONSE");
        }
        return auth.publish_security(generation, || Ok(result.ticket));
    }
    Err("UNAUTHORIZED")
}

// A tiny one-request HTTP endpoint for the companion mod; bounded and loopback only.
async fn challenge(stream: &mut TcpStream, key: &str, server: &str) -> Result<(), Error> {
    let mut bytes = Vec::new();
    let mut part = [0u8; 512];
    loop {
        let n = stream
            .read(&mut part)
            .await
            .map_err(|_| "BRIDGE_REQUEST_INVALID")?;
        if n == 0 || bytes.len() + n > 2048 {
            return Err("BRIDGE_REQUEST_INVALID");
        }
        bytes.extend_from_slice(&part[..n]);
        if let Some(end) = bytes.windows(4).position(|w| w == b"\r\n\r\n") {
            let header =
                std::str::from_utf8(&bytes[..end]).map_err(|_| "BRIDGE_REQUEST_INVALID")?;
            let mut lines = header.split("\r\n");
            if lines.next() != Some("POST /ticket HTTP/1.1") {
                return Err("BRIDGE_REQUEST_INVALID");
            }
            let mut length = None;
            let mut authorized = false;
            for line in lines {
                let (name, value) = line.split_once(':').ok_or("BRIDGE_REQUEST_INVALID")?;
                let value = value.trim();
                if name.eq_ignore_ascii_case("authorization") {
                    if authorized || value != format!("Bearer {key}") {
                        return Err("BRIDGE_REQUEST_INVALID");
                    }
                    authorized = true;
                }
                if name.eq_ignore_ascii_case("content-length") {
                    if length.is_some() {
                        return Err("BRIDGE_REQUEST_INVALID");
                    }
                    length = Some(
                        value
                            .parse::<usize>()
                            .map_err(|_| "BRIDGE_REQUEST_INVALID")?,
                    );
                }
                if ["origin", "cookie", "transfer-encoding"]
                    .iter()
                    .any(|v| name.eq_ignore_ascii_case(v))
                {
                    return Err("BRIDGE_REQUEST_INVALID");
                }
            }
            let length = length
                .filter(|n| *n > 0 && *n <= 512)
                .ok_or("BRIDGE_REQUEST_INVALID")?;
            if !authorized {
                return Err("BRIDGE_REQUEST_INVALID");
            }
            let total = end + 4 + length;
            if bytes.len() < total {
                continue;
            }
            if bytes.len() != total {
                return Err("BRIDGE_REQUEST_INVALID");
            }
            let value: Challenge =
                serde_json::from_slice(&bytes[end + 4..]).map_err(|_| "BRIDGE_REQUEST_INVALID")?;
            if value.server_id != server || !account_client::id(&value.connection_id) {
                return Err("BRIDGE_REQUEST_INVALID");
            }
            return Ok(());
        }
    }
}
async fn serve(
    listener: TcpListener,
    key: Zeroizing<String>,
    app: tauri::AppHandle,
    generation: u64,
    server: String,
    profile: game_profile::Profile,
) {
    loop {
        let Ok((mut stream, address)) = listener.accept().await else {
            return;
        };
        if !address.ip().is_loopback() {
            continue;
        }
        let result = tokio::time::timeout(Duration::from_secs(10), async {
            challenge(&mut stream, &key, &server).await?;
            app.state::<crate::simple_update::Manager>()
                .require_current(app.clone())
                .await?;
            ticket(&app.state::<Auth>(), generation, &server, &profile).await
        })
        .await;
        let update_required = matches!(&result, Ok(Err("LAUNCHER_UPDATE_REQUIRED")));
        let body = match result {
            Ok(Ok(ticket)) => {
                Zeroizing::new(serde_json::json!({"ticket":ticket.as_str()}).to_string())
            }
            _ => Zeroizing::new("{}".into()),
        };
        let status = if body.len() > 2 {
            "200 OK"
        } else if update_required {
            "426 Upgrade Required"
        } else {
            "403 Forbidden"
        };
        let header=format!("HTTP/1.1 {status}\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: {}\r\n\r\n",body.len());
        let _ = tokio::time::timeout(Duration::from_secs(2), async {
            stream.write_all(header.as_bytes()).await?;
            stream.write_all(body.as_bytes()).await
        })
        .await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn bridge_rejects_browser_credentials_wrong_server_and_oversized_body() {
        let server = uuid::Uuid::new_v4().to_string();
        let key = "a".repeat(64);
        let body=serde_json::json!({"server_id":server,"connection_id":uuid::Uuid::new_v4().to_string()}).to_string();
        let valid=format!("POST /ticket HTTP/1.1\r\nAuthorization: Bearer {key}\r\nContent-Length: {}\r\n\r\n{body}",body.len());
        for (request,accepted) in [
            (valid.clone(),true),
            (valid.replace("POST /ticket","GET /ticket"),false),
            (valid.replace(&key,&"b".repeat(64)),false),
            (valid.replace("Content-Length:","Origin: http://localhost\r\nContent-Length:"),false),
            (valid.replace("Content-Length:","Cookie: session=abc\r\nContent-Length:"),false),
            (valid.replace(&server,&uuid::Uuid::new_v4().to_string()),false),
            (format!("POST /ticket HTTP/1.1\r\nAuthorization: Bearer {key}\r\nContent-Length: 9999\r\n\r\n"),false),
            (valid.replace("Content-Length:","Transfer-Encoding: chunked\r\nContent-Length:"),false),
        ] {
            let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
            let address=listener.local_addr().unwrap();
            let sender=tokio::spawn(async move {
                let mut stream=TcpStream::connect(address).await.unwrap();
                // Exercise fragmented headers/body as well as normal requests.
                for bytes in request.as_bytes().chunks(17) {if stream.write_all(bytes).await.is_err(){break;}}
                let _=stream.shutdown().await;
            });
            let (mut stream,_)=listener.accept().await.unwrap();
            assert_eq!(challenge(&mut stream,&key,&server).await.is_ok(),accepted);
            sender.await.unwrap();
        }
    }
    #[test]
    fn absent_configuration_never_enables_play() {
        let directory = tempfile::tempdir().unwrap();
        let game = Game::new(directory.path().join("missing.json"));
        let state = game.snapshot().unwrap();
        assert!(!state.can_launch);
        assert_eq!(state.reason, "RUNTIME_NOT_CONFIGURED");
        std::fs::write(directory.path().join("missing.json"), b"{}").unwrap();
        assert!(!game.snapshot().unwrap().can_launch);
    }
    #[test]
    fn old_or_missing_companion_requires_preparation_after_a_launcher_update() {
        use sha2::Digest;
        let directory = tempfile::tempdir().unwrap();
        let file = directory.path().join("companion.jar");
        let expected = format!("{:x}", sha2::Sha256::digest(b"new companion"));
        assert_eq!(
            compatible_mod(&file, &expected),
            Err("RUNTIME_MOD_UPDATE_REQUIRED")
        );
        std::fs::write(&file, b"old companion").unwrap();
        assert_eq!(
            compatible_mod(&file, &expected),
            Err("RUNTIME_MOD_UPDATE_REQUIRED")
        );
        std::fs::write(&file, b"new companion").unwrap();
        assert_eq!(compatible_mod(&file, &expected), Ok(()));
    }
}
