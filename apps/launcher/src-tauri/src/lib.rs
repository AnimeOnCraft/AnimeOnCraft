mod account_client;
mod account_lock;
mod auth;
mod credential_store;
mod deployment;
mod game_launch;
mod game_preparation;
mod managed_pack;
mod game_profile;
mod game_status;
#[cfg(all(windows, feature = "installer-acceptance"))]
mod installation_fixture;
mod pack;
mod player_cape;
mod player_client;
mod player_skin;
mod public_api;
mod security;
mod self_update;
mod settings;
mod simple_update;
use public_api::{PublicClient, PublicInput, PublicReply};
use serde::Serialize;
use settings::{LauncherSettings, SettingsSnapshot, SettingsStore};
use std::sync::Mutex;
use tauri::Manager;
#[tauri::command]
fn simple_update_snapshot(
    updater: tauri::State<'_, simple_update::Manager>,
) -> Result<simple_update::Snapshot, &'static str> {
    updater.snapshot()
}
#[tauri::command]
fn simple_update_action(
    input: simple_update::Input,
    updater: tauri::State<'_, simple_update::Manager>,
    app: tauri::AppHandle,
) -> Result<simple_update::Snapshot, &'static str> {
    if matches!(&input, simple_update::Input::Install { .. })
        && matches!(
            app.state::<game_launch::Game>().snapshot()?.state,
            "STARTING" | "RUNNING" | "PREPARING"
        )
    {
        return Err("GAME_ALREADY_RUNNING");
    }
    updater.action(input, app)
}
#[tauri::command]
fn launcher_update_snapshot(
    updater: tauri::State<'_, self_update::manager::Manager>,
) -> Result<self_update::manager::Snapshot, &'static str> {
    updater.snapshot()
}
#[tauri::command]
fn launcher_update_action(
    input: self_update::manager::Input,
    updater: tauri::State<'_, self_update::manager::Manager>,
    app: tauri::AppHandle,
) -> Result<self_update::manager::Snapshot, &'static str> {
    updater.action(input, move || app.exit(0))
}

#[tauri::command]
fn installer_snapshot(
    actor: tauri::State<'_, pack::actor::Actor>,
) -> Result<pack::actor::Snapshot, &'static str> {
    actor.snapshot()
}
#[tauri::command]
fn installer_local_state(
    local: tauri::State<'_, std::sync::Arc<pack::local::LocalInstance>>,
) -> pack::local::Snapshot {
    local.snapshot()
}
#[tauri::command]
fn installer_action(
    input: pack::actor::Input,
    actor: tauri::State<'_, pack::actor::Actor>,
) -> Result<pack::actor::Snapshot, &'static str> {
    actor.action(input)
}

#[tauri::command]
fn pack_availability() -> pack::Availability {
    pack::availability()
}

#[tauri::command]
async fn security_sessions(
    auth: tauri::State<'_, auth::Auth>,
) -> Result<security::Sessions, &'static str> {
    security::sessions(&auth).await
}
#[tauri::command]
async fn security_action(
    input: security::Input,
    auth: tauri::State<'_, auth::Auth>,
    security: tauri::State<'_, security::Security>,
) -> Result<security::ResultView, &'static str> {
    security.action(&auth, input).await
}

#[tauri::command]
async fn account_snapshot(
    auth: tauri::State<'_, auth::Auth>,
) -> Result<auth::Snapshot, &'static str> {
    auth.snapshot().await
}
#[tauri::command]
fn game_stop(game: tauri::State<'_, game_launch::Game>) -> Result<(), &'static str> {
    game.stop()
}
#[tauri::command]
fn game_preparation_snapshot(
    preparation: tauri::State<'_, game_preparation::Preparation>,
) -> Result<game_preparation::Snapshot, &'static str> {
    preparation.snapshot()
}
#[tauri::command]
fn game_prepare(
    app: tauri::AppHandle,
    preparation: tauri::State<'_, game_preparation::Preparation>,
    game: tauri::State<'_, game_launch::Game>,
) -> Result<game_preparation::Snapshot, &'static str> {
    preparation.start(app, &game)
}
#[tauri::command]
fn game_snapshot(
    game: tauri::State<'_, game_launch::Game>,
) -> Result<game_launch::Snapshot, &'static str> {
    game.snapshot()
}
#[tauri::command]
async fn game_server_status(
    game: tauri::State<'_, game_launch::Game>,
) -> Result<game_status::Snapshot, &'static str> {
    Ok(game.server_status().await)
}
#[tauri::command]
async fn game_launch(
    app: tauri::AppHandle,
    game: tauri::State<'_, game_launch::Game>,
    store: tauri::State<'_, Mutex<SettingsStore>>,
    updater: tauri::State<'_, simple_update::Manager>,
) -> Result<game_launch::Snapshot, &'static str> {
    let settings = store
        .lock()
        .map_err(|_| "SETTINGS_UNAVAILABLE")?
        .load()?
        .settings;
    updater.require_current(app.clone()).await?;
    game.launch(app, settings).await
}
#[tauri::command]
async fn game_profile_read(
    auth: tauri::State<'_, auth::Auth>,
) -> Result<game_profile::Snapshot, &'static str> {
    game_profile::read(&auth).await
}
#[tauri::command]
async fn skin_read(
    auth: tauri::State<'_, auth::Auth>,
) -> Result<player_skin::Snapshot, &'static str> {
    player_skin::request(&auth, None).await
}
#[tauri::command]
async fn cape_read(
    auth: tauri::State<'_, auth::Auth>,
) -> Result<player_cape::Snapshot, &'static str> {
    player_cape::request(&auth, None).await
}
#[tauri::command]
async fn cape_save(
    input: player_cape::Input,
    auth: tauri::State<'_, auth::Auth>,
) -> Result<player_cape::Snapshot, &'static str> {
    player_cape::request(&auth, Some(input)).await
}
#[tauri::command]
async fn skin_save(
    input: player_skin::Input,
    auth: tauri::State<'_, auth::Auth>,
) -> Result<player_skin::Snapshot, &'static str> {
    player_skin::request(&auth, Some(input)).await
}
#[tauri::command]
async fn game_profile_create(
    input: game_profile::Input,
    auth: tauri::State<'_, auth::Auth>,
) -> Result<game_profile::Snapshot, &'static str> {
    game_profile::create(&auth, input).await
}
#[tauri::command]
async fn account_action(
    input: auth::AccountInput,
    auth: tauri::State<'_, auth::Auth>,
) -> Result<auth::Snapshot, &'static str> {
    auth.action(input).await
}
#[tauri::command]
async fn player_read(
    input: player_client::ReadInput,
    auth: tauri::State<'_, auth::Auth>,
) -> Result<serde_json::Value, &'static str> {
    player_client::read(&auth, input).await
}
#[tauri::command]
fn player_prepare_action(
    input: player_client::Action,
    auth: tauri::State<'_, auth::Auth>,
    actions: tauri::State<'_, player_client::PlayerActions>,
) -> Result<player_client::ActionResult, &'static str> {
    actions.prepare(&auth, input)
}
#[tauri::command]
async fn player_submit_action(
    operation_id: String,
    retry: bool,
    auth: tauri::State<'_, auth::Auth>,
    actions: tauri::State<'_, player_client::PlayerActions>,
) -> Result<player_client::ActionResult, &'static str> {
    actions.submit(&auth, operation_id, retry).await
}

#[derive(Serialize)]
struct NativeHealth {
    status: &'static str,
    version: String,
    play_available: bool,
    account_environment: &'static str,
}
#[tauri::command]
fn native_health(app: tauri::AppHandle) -> NativeHealth {
    NativeHealth {
        status: "LAUNCHER_ACCOUNT_DEV",
        version: app.package_info().version.to_string(),
        play_available: app
            .state::<game_launch::Game>()
            .snapshot()
            .is_ok_and(|v| v.can_launch),
        account_environment: account_client::ENVIRONMENT,
    }
}
#[tauri::command]
fn validate_launcher_settings(input: LauncherSettings) -> Result<LauncherSettings, &'static str> {
    settings::validate(input)
}
#[tauri::command]
fn load_launcher_settings(
    store: tauri::State<'_, Mutex<SettingsStore>>,
) -> Result<SettingsSnapshot, &'static str> {
    store.lock().map_err(|_| "SETTINGS_UNAVAILABLE")?.load()
}
#[tauri::command]
fn save_launcher_settings(
    input: LauncherSettings,
    store: tauri::State<'_, Mutex<SettingsStore>>,
) -> Result<SettingsSnapshot, &'static str> {
    store
        .lock()
        .map_err(|_| "SETTINGS_UNAVAILABLE")?
        .save(input)
}
#[tauri::command]
async fn read_public_resource(
    input: PublicInput,
    client: tauri::State<'_, PublicClient>,
) -> Result<PublicReply, &'static str> {
    client.read(input).await
}
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let Ok(account_lock) = account_lock::AccountLock::acquire() else {
        return;
    };
    tauri::Builder::default()
        .setup(move |app| {
            app.manage(account_lock);
            app.manage(auth::Auth::new().map_err(std::io::Error::other)?);
            app.manage(player_client::PlayerActions::new());
            #[cfg(not(any(
                feature = "native-acceptance",
                feature = "pack-acceptance",
                feature = "installer-acceptance"
            )))]
            let game_path = app
                .path()
                .home_dir()?
                .join(".animeoncraft/launcher/game-runtime.json");
            #[cfg(any(feature = "pack-acceptance", feature = "installer-acceptance"))]
            let game_path = std::path::PathBuf::new();
            #[cfg(feature = "native-acceptance")]
            let game_path = std::env::var_os("AOC_GAME_RUNTIME_FIXTURE")
                .map(std::path::PathBuf::from)
                .unwrap_or_else(|| {
                    app.path()
                        .app_config_dir()
                        .unwrap()
                        .join("unconfigured-fixture/game-runtime.json")
                });
            app.manage(game_launch::Game::new(game_path));
            app.manage(game_preparation::Preparation::new());
            app.manage(security::Security::new());
            #[cfg(all(windows, feature = "pack-acceptance"))]
            let fixture = pack::acceptance::load().map_err(std::io::Error::other)?;
            #[cfg(all(windows, feature = "installer-acceptance"))]
            let installation_directory =
                installation_fixture::directory().map_err(std::io::Error::other)?;
            #[cfg(not(all(
                windows,
                any(feature = "pack-acceptance", feature = "installer-acceptance")
            )))]
            app.manage(Mutex::new(SettingsStore::new(app.path().app_config_dir()?)));
            #[cfg(all(windows, feature = "pack-acceptance"))]
            app.manage(Mutex::new(SettingsStore::new(
                fixture.directory.join("settings"),
            )));
            #[cfg(all(windows, feature = "installer-acceptance"))]
            app.manage(Mutex::new(SettingsStore::new(
                installation_directory.join("state/settings"),
            )));
            app.manage(PublicClient::new()?);
            #[cfg(not(all(
                windows,
                any(feature = "pack-acceptance", feature = "installer-acceptance")
            )))]
            // Friends MVP does not provision an experimental installer namespace on startup.
            // Real game installation will be connected after the Minecraft/modpack choice.
            let local = pack::local::LocalInstance::unavailable();
            #[cfg(all(windows, feature = "pack-acceptance"))]
            let local = pack::local::LocalInstance::open(&fixture.directory.join("local"));
            #[cfg(all(
                windows,
                feature = "installer-acceptance",
                not(feature = "simple-update-acceptance")
            ))]
            let local =
                pack::local::LocalInstance::open(&installation_directory.join("state/local"));
            #[cfg(all(windows, feature = "simple-update-acceptance"))]
            let local = pack::local::LocalInstance::unavailable();
            let local = std::sync::Arc::new(local);
            #[cfg(not(all(windows, feature = "pack-acceptance")))]
            app.manage(pack::development_actor(local.clone()));
            #[cfg(all(windows, feature = "pack-acceptance"))]
            app.manage(
                pack::acceptance::actor(local.clone(), fixture).map_err(std::io::Error::other)?,
            );
            #[cfg(feature = "self-update-acceptance")]
            let actor = app.state::<pack::actor::Actor>().inner().clone();
            #[cfg(feature = "self-update-acceptance")]
            app.manage(
                self_update::manager::Manager::new(
                    actor,
                    local.clone(),
                    app.package_info().name.clone(),
                    app.package_info().version.to_string(),
                    matches!(
                        tauri::utils::platform::bundle_type(),
                        Some(tauri::utils::config::BundleType::Nsis)
                    ),
                )
                .map_err(std::io::Error::other)?,
            );
            app.handle()
                .plugin(tauri_plugin_updater::Builder::new().build())?;
            let updater = simple_update::Manager::new(app.handle());
            updater.start_polling(app.handle().clone());
            app.manage(updater);
            app.manage(local);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            native_health,
            validate_launcher_settings,
            load_launcher_settings,
            save_launcher_settings,
            read_public_resource,
            account_snapshot,
            account_action,
            game_stop,
            game_snapshot,
            game_server_status,
            game_launch,
            game_prepare,
            game_preparation_snapshot,
            game_profile_read,
            skin_read,
            skin_save,
            cape_read,
            cape_save,
            game_profile_create,
            player_read,
            player_prepare_action,
            player_submit_action,
            security_sessions,
            security_action,
            pack_availability,
            installer_snapshot,
            installer_action,
            installer_local_state,
            launcher_update_snapshot,
            launcher_update_action,
            simple_update_snapshot,
            simple_update_action
        ])
        .run(tauri::generate_context!())
        .expect("failed to run AnimeOnCraft launcher");
}
