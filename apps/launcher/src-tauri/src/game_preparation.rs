use crate::{
    account_client::Error,
    game_launch::{Game, Runtime},
};
use serde::{Deserialize, Serialize};
use std::io::Write;
use std::{
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::Duration,
};
use tauri::Manager;
use tokio::{
    io::{AsyncBufReadExt, BufReader},
    process::Command,
};

#[derive(Clone, Serialize)]
pub struct Snapshot {
    pub state: &'static str,
    pub stage: String,
    pub completed: u32,
    pub total: u32,
    pub downloaded: u32,
    pub repaired: u32,
    pub reason: &'static str,
}
impl Default for Snapshot {
    fn default() -> Self {
        Self {
            state: "IDLE",
            stage: "CHECKING".into(),
            completed: 0,
            total: 0,
            downloaded: 0,
            repaired: 0,
            reason: "READY",
        }
    }
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Progress {
    stage: String,
    completed: u32,
    total: u32,
    downloaded: u32,
    repaired: u32,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct PublicConfig {
    format_version: u8,
    api_origin: String,
    server_id: String,
    server_host: String,
    server_port: u16,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Bundle {
    format_version: u8,
    public_config: PublicConfig,
    admission_sha256: String,
}
pub struct Preparation {
    state: Arc<Mutex<Snapshot>>,
}
impl Preparation {
    pub fn new() -> Self {
        Self {
            state: Arc::new(Mutex::new(Snapshot::default())),
        }
    }
    pub fn snapshot(&self) -> Result<Snapshot, Error> {
        Ok(self
            .state
            .lock()
            .map_err(|_| "PREPARATION_UNAVAILABLE")?
            .clone())
    }
    pub fn start(&self, app: tauri::AppHandle, game: &Game) -> Result<Snapshot, Error> {
        let resource = windows_child_path(
            app.path()
                .resource_dir()
                .map_err(|_| "PREPARATION_NOT_BUNDLED")?,
        )
        .join("bootstrap");
        let bundle: Bundle = serde_json::from_slice(
            &std::fs::read(resource.join("bootstrap.json"))
                .map_err(|_| "PREPARATION_NOT_BUNDLED")?,
        )
        .map_err(|_| "PREPARATION_NOT_BUNDLED")?;
        let config = &bundle.public_config;
        if bundle.format_version != 1
            || config.format_version != 1
            || config.api_origin != crate::deployment::public_origin()?
            || !crate::account_client::id(&config.server_id)
            || !crate::deployment::game_host(&config.server_host)
            || config.server_port == 0
            || bundle.admission_sha256.len() != 64
            || !bundle
                .admission_sha256
                .bytes()
                .all(|b| b.is_ascii_hexdigit())
        {
            return Err("PREPARATION_NOT_BUNDLED");
        }
        let runtime_path = windows_child_path(game.runtime_path());
        if app.state::<crate::simple_update::Manager>().busy()? {
            return Err("PREPARATION_UPDATE_BUSY");
        }
        let directory = windows_child_path(select_directory(&runtime_path, &config.server_id)?);
        for file in [
            "runtime/node/node.exe",
            "runtime/java/bin/java.exe",
            "scripts/prepare-launcher-client.mjs",
        ] {
            if !resource.join(file).is_file() {
                return Err("PREPARATION_NOT_BUNDLED");
            }
        }
        game.begin_preparation()?;
        let view = Snapshot {
            state: "RUNNING",
            ..Snapshot::default()
        };
        *self.state.lock().map_err(|_| "PREPARATION_UNAVAILABLE")? = view.clone();
        let state = self.state.clone();
        tauri::async_runtime::spawn(async move {
            let result = run(&resource, &directory, &runtime_path, &bundle, &state).await;
            app.state::<Game>()
                .end_preparation(result.as_ref().err().copied());
            if let Ok(mut view) = state.lock() {
                view.state = if result.is_ok() { "READY" } else { "FAILED" };
                view.reason = result.err().unwrap_or("READY");
            }
        });
        Ok(view)
    }
}

fn windows_child_path(path: PathBuf) -> PathBuf {
    // current_exe/resource_dir use extended Win32 paths; Node rejects them as script entrypoints.
    #[cfg(windows)]
    if let Some(value) = path.to_str().and_then(|value| value.strip_prefix(r"\\?\")) {
        if value.as_bytes().get(1) == Some(&b':') {
            return PathBuf::from(value);
        }
    }
    path
}

fn select_directory(runtime_path: &Path, server_id: &str) -> Result<PathBuf, Error> {
    if let Ok(bytes) = std::fs::read(runtime_path) {
        if let Ok(runtime) = serde_json::from_slice::<Runtime>(&bytes) {
            if runtime.server_id != server_id {
                return Err("PREPARATION_RUNTIME_CONFLICT");
            }
            if runtime.game_directory.is_absolute()
                && runtime
                    .game_directory
                    .file_name()
                    .is_some_and(|p| p == "client")
            {
                return runtime
                    .game_directory
                    .parent()
                    .map(Path::to_path_buf)
                    .ok_or("RUNTIME_INVALID");
            }
        }
    }
    runtime_path
        .parent()
        .filter(|p| p.is_absolute())
        .map(|p| p.join("game"))
        .ok_or("RUNTIME_INVALID")
}
fn apply_progress(state: &Arc<Mutex<Snapshot>>, line: &str) {
    let Some(line) = line.strip_prefix("AOC_PREPARE ") else {
        return;
    };
    if line.len() > 1024 {
        return;
    }
    let Ok(progress) = serde_json::from_str::<Progress>(line) else {
        return;
    };
    if !["MODS", "MINECRAFT", "LOADER", "FILES", "VERIFYING", "DONE"]
        .contains(&progress.stage.as_str())
        || progress.completed > progress.total
    {
        return;
    }
    if let Ok(mut view) = state.lock() {
        view.stage = progress.stage;
        view.completed = progress.completed;
        view.total = progress.total;
        view.downloaded = progress.downloaded;
        view.repaired = progress.repaired;
    }
}
async fn run(
    resource: &Path,
    directory: &Path,
    runtime_path: &Path,
    bundle: &Bundle,
    state: &Arc<Mutex<Snapshot>>,
) -> Result<(), Error> {
    std::fs::create_dir_all(directory).map_err(|_| "PREPARATION_WRITE_FAILED")?;
    std::fs::write(
        directory.join("preparation.pending"),
        b"Managed game preparation in progress\n",
    )
    .map_err(|_| "PREPARATION_WRITE_FAILED")?;
    let log = std::fs::File::create(directory.join("preparation.log"))
        .map_err(|_| "PREPARATION_WRITE_FAILED")?;
    let mut stdout_log = log.try_clone().map_err(|_| "PREPARATION_WRITE_FAILED")?;
    let java = resource.join("runtime/java/bin/java.exe");
    let mut command = Command::new(resource.join("runtime/node/node.exe"));
    command
        .arg(resource.join("scripts/prepare-launcher-client.mjs"))
        .current_dir(directory)
        .env_clear();
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
    command
        .env("AOC_CLIENT_DATA_ROOT", directory)
        .env("AOC_CLIENT_JAVA", &java)
        .env("AOC_CLIENT_PROGRESS", "1");
    command
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(log)
        .kill_on_drop(true);
    #[cfg(windows)]
    command.creation_flags(0x08000000);
    let mut child = command.spawn().map_err(|_| "PREPARATION_START_FAILED")?;
    let stdout = child.stdout.take().ok_or("PREPARATION_START_FAILED")?;
    let mut lines = BufReader::new(stdout).lines();
    let operation = async {
        while let Some(line) = lines.next_line().await.map_err(|_| "PREPARATION_FAILED")? {
            let _ = writeln!(stdout_log, "{}", line);
            apply_progress(state, &line);
        }
        if !child
            .wait()
            .await
            .map_err(|_| "PREPARATION_FAILED")?
            .success()
        {
            return Err("PREPARATION_FAILED");
        }
        let bytes = std::fs::read(directory.join("prepared-game-runtime.json"))
            .map_err(|_| "PREPARATION_FAILED")?;
        let runtime: Runtime = serde_json::from_slice(&bytes).map_err(|_| "PREPARATION_FAILED")?;
        runtime.validate()?;
        crate::managed_pack::verify(&runtime.game_directory)?;
        if runtime.game_directory != directory.join("client")
            || runtime.java != java
            || runtime.server_id != bundle.public_config.server_id
            || runtime.server_host != bundle.public_config.server_host
            || runtime.server_port != bundle.public_config.server_port
        {
            return Err("PREPARATION_FAILED");
        }
        let parent = runtime_path.parent().ok_or("PREPARATION_WRITE_FAILED")?;
        std::fs::create_dir_all(parent).map_err(|_| "PREPARATION_WRITE_FAILED")?;
        if runtime_path.is_file() {
            std::fs::copy(
                runtime_path,
                parent.join(format!(
                    "game-runtime.before-preparation-{}.json",
                    uuid::Uuid::new_v4()
                )),
            )
            .map_err(|_| "PREPARATION_WRITE_FAILED")?;
        }
        let temporary = parent.join("game-runtime.prepare.tmp");
        std::fs::write(&temporary, bytes).map_err(|_| "PREPARATION_WRITE_FAILED")?;
        std::fs::rename(temporary, runtime_path).map_err(|_| "PREPARATION_WRITE_FAILED")?;
        std::fs::remove_file(directory.join("preparation.pending"))
            .map_err(|_| "PREPARATION_WRITE_FAILED")?;
        Ok(())
    };
    tokio::time::timeout(Duration::from_secs(1800), operation)
        .await
        .map_err(|_| "PREPARATION_TIMEOUT")?
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[cfg(windows)]
    fn child_process_paths_use_normal_drive_syntax() {
        assert_eq!(
            windows_child_path(PathBuf::from(r"\\?\F:\AnimeOnCraft\bootstrap")),
            PathBuf::from(r"F:\AnimeOnCraft\bootstrap")
        );
        assert_eq!(
            windows_child_path(PathBuf::from(r"C:\Users\player\game")),
            PathBuf::from(r"C:\Users\player\game")
        );
    }
    #[test]
    fn interrupted_preparation_blocks_launch_after_restart_until_repaired() {
        let folder = tempfile::tempdir().unwrap();
        let game_directory = folder.path().join("client");
        std::fs::create_dir_all(game_directory.join("mods")).unwrap();
        std::fs::write(
            game_directory.join("mods/animeoncraft-admission-0.3.0-friends-dev.jar"),
            if option_env!("AOC_REQUIRED_ADMISSION_SHA256").is_some_and(|hash| hash.len() == 64) {
                std::fs::read(std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                    .join("../../../.tools/launcher-bootstrap/minecraft/integration/neoforge/build/libs/animeoncraft-admission-0.3.0-friends-dev.jar"))
                    .expect("the configured bootstrap companion must exist")
            } else { b"fixture".to_vec() },
        )
        .unwrap();
        let java = folder.path().join("java.exe");
        std::fs::write(&java, b"fixture").unwrap();
        let path = folder.path().join("game-runtime.json");
        let runtime = Runtime {
            format_version: 1,
            server_id: "420ecd17-677d-4b50-bc5d-eb2c1b735373".into(),
            server_host: "127.0.0.1".into(),
            server_port: 25565,
            java,
            game_directory,
            args: vec![
                "%AOC_NAME%".into(),
                "%AOC_UUID%".into(),
                "127.0.0.1:25565".into(),
            ],
        };
        std::fs::write(&path, serde_json::to_vec(&runtime).unwrap()).unwrap();
        crate::managed_pack::prepare_fixture(&runtime.game_directory);
        assert!(Game::new(path.clone()).snapshot().unwrap().can_launch);
        // Updating third-party mods must block Play even with the same companion JAR.
        if let Some(mod_file) = std::fs::read_dir(runtime.game_directory.join("mods"))
            .unwrap()
            .filter_map(Result::ok)
            .find(|entry| entry.file_name() != "animeoncraft-admission-0.3.0-friends-dev.jar")
        {
            let receipt = folder.path().join("managed-pack.json");
            let saved_receipt = std::fs::read(&receipt).unwrap();
            std::fs::remove_file(&receipt).unwrap();
            let stale = Game::new(path.clone()).snapshot().unwrap();
            assert!(!stale.can_launch);
            assert_eq!(stale.reason, "RUNTIME_MOD_UPDATE_REQUIRED");
            std::fs::write(&receipt, saved_receipt).unwrap();
            let retired = mod_file.path().with_extension("missing");
            std::fs::rename(mod_file.path(), &retired).unwrap();
            assert_eq!(
                Game::new(path.clone()).snapshot().unwrap().reason,
                "RUNTIME_MOD_UPDATE_REQUIRED"
            );
            std::fs::rename(retired, mod_file.path()).unwrap();
            assert!(Game::new(path.clone()).snapshot().unwrap().can_launch);
        }
        std::fs::write(folder.path().join("preparation.pending"), b"interrupted").unwrap();
        let view = Game::new(path.clone()).snapshot().unwrap();
        assert!(!view.can_launch);
        assert_eq!(view.reason, "RUNTIME_PREPARATION_REQUIRED");
        std::fs::remove_file(folder.path().join("preparation.pending")).unwrap();
        assert!(Game::new(path).snapshot().unwrap().can_launch);
    }
    #[test]
    fn progress_does_not_accept_arbitrary_output() {
        let state = Arc::new(Mutex::new(Snapshot::default()));
        apply_progress(&state,"AOC_PREPARE {\"stage\":\"FILES\",\"completed\":12,\"total\":100,\"downloaded\":2,\"repaired\":1}");
        assert_eq!(state.lock().unwrap().completed, 12);
        for line in ["installer output","AOC_PREPARE {\"stage\":\"untrusted\",\"completed\":1,\"total\":1,\"downloaded\":0,\"repaired\":0}","AOC_PREPARE {\"stage\":\"FILES\",\"completed\":101,\"total\":100,\"downloaded\":0,\"repaired\":0}"]{apply_progress(&state,line);}
        assert_eq!(state.lock().unwrap().completed, 12);
    }
    #[test]
    fn preparation_locks_launch_and_rejects_concurrent_preparation() {
        let dir = tempfile::tempdir().unwrap();
        let game = Game::new(dir.path().join("game-runtime.json"));
        game.begin_preparation().unwrap();
        assert!(!game.snapshot().unwrap().can_launch);
        assert_eq!(
            game.begin_preparation().unwrap_err(),
            "GAME_ALREADY_RUNNING"
        );
        game.end_preparation(Some("PREPARATION_FAILED"));
        assert!(game.begin_preparation().is_ok());
    }
}
