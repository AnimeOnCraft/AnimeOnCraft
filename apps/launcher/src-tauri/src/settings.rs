use serde::{Deserialize, Serialize};
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct LauncherSettings {
    pub ram_mib: u32,
    pub width: u16,
    pub height: u16,
    pub fullscreen: bool,
}
impl Default for LauncherSettings {
    fn default() -> Self {
        Self {
            ram_mib: 4096,
            width: 1920,
            height: 1080,
            fullscreen: false,
        }
    }
}
#[derive(Debug, Serialize)]
pub struct SettingsSnapshot {
    pub settings: LauncherSettings,
    pub saved: bool,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct StoredSettings {
    format_version: u8,
    settings: LauncherSettings,
}

pub fn validate(input: LauncherSettings) -> Result<LauncherSettings, &'static str> {
    if !(2048..=32768).contains(&input.ram_mib) {
        return Err("INVALID_RAM");
    }
    if !(800..=7680).contains(&input.width) || !(600..=4320).contains(&input.height) {
        return Err("INVALID_RESOLUTION");
    }
    Ok(input)
}
fn safe_existing(path: &Path) -> Result<(), &'static str> {
    match fs::symlink_metadata(path) {
        Ok(meta) => {
            #[cfg(windows)]
            {
                use std::os::windows::fs::MetadataExt;
                if meta.file_attributes() & 0x400 != 0 {
                    return Err("SETTINGS_PATH_UNSAFE");
                }
            }
            if meta.file_type().is_symlink() {
                return Err("SETTINGS_PATH_UNSAFE");
            }
            Ok(())
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(_) => Err("SETTINGS_UNAVAILABLE"),
    }
}
pub struct SettingsStore {
    directory: PathBuf,
}
impl SettingsStore {
    pub fn new(directory: PathBuf) -> Self {
        Self { directory }
    }
    fn prepare(&self) -> Result<PathBuf, &'static str> {
        safe_existing(&self.directory)?;
        fs::create_dir_all(&self.directory).map_err(|_| "SETTINGS_UNAVAILABLE")?;
        let path = self.directory.join("launcher-settings.json");
        safe_existing(&path)?;
        Ok(path)
    }
    pub fn load(&self) -> Result<SettingsSnapshot, &'static str> {
        let path = self.prepare()?;
        let file = match File::open(path) {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(SettingsSnapshot {
                    settings: LauncherSettings::default(),
                    saved: false,
                })
            }
            Err(_) => return Err("SETTINGS_UNAVAILABLE"),
        };
        let mut bytes = Vec::new();
        file.take(4097)
            .read_to_end(&mut bytes)
            .map_err(|_| "SETTINGS_UNAVAILABLE")?;
        if bytes.len() > 4096 {
            return Err("SETTINGS_CORRUPT");
        }
        let stored: StoredSettings =
            serde_json::from_slice(&bytes).map_err(|_| "SETTINGS_CORRUPT")?;
        if stored.format_version != 1 {
            return Err("SETTINGS_VERSION_UNSUPPORTED");
        }
        Ok(SettingsSnapshot {
            settings: validate(stored.settings).map_err(|_| "SETTINGS_CORRUPT")?,
            saved: true,
        })
    }
    pub fn save(&self, input: LauncherSettings) -> Result<SettingsSnapshot, &'static str> {
        let settings = validate(input)?;
        // Never replace an unreadable/unsupported settings file with guessed defaults.
        self.load()?;
        let destination = self.prepare()?;
        static SEQUENCE: AtomicU64 = AtomicU64::new(0);
        let pending = self.directory.join(format!(
            "launcher-settings.{}.{}.pending",
            std::process::id(),
            SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        let bytes = serde_json::to_vec(&StoredSettings {
            format_version: 1,
            settings: settings.clone(),
        })
        .map_err(|_| "SETTINGS_UNAVAILABLE")?;
        let result = (|| {
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&pending)
                .map_err(|_| "SETTINGS_UNAVAILABLE")?;
            file.write_all(&bytes)
                .and_then(|_| file.sync_all())
                .map_err(|_| "SETTINGS_UNAVAILABLE")?;
            drop(file);
            safe_existing(&destination)?;
            fs::rename(&pending, &destination).map_err(|_| "SETTINGS_UNAVAILABLE")?;
            Ok(SettingsSnapshot {
                settings,
                saved: true,
            })
        })();
        if result.is_err() {
            let _ = fs::remove_file(&pending);
        }
        result
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn cleanup(store: &SettingsStore) {
        let target = fs::canonicalize(&store.directory).unwrap();
        let parent = fs::canonicalize(std::env::temp_dir()).unwrap();
        assert_eq!(target.parent(), Some(parent.as_path()));
        assert!(target
            .file_name()
            .unwrap()
            .to_string_lossy()
            .starts_with(&format!("aoc-settings-test-{}-", std::process::id())));
        fs::remove_dir_all(target).unwrap();
    }
    fn store() -> SettingsStore {
        static ID: AtomicU64 = AtomicU64::new(0);
        SettingsStore::new(std::env::temp_dir().join(format!(
            "aoc-settings-test-{}-{}",
            std::process::id(),
            ID.fetch_add(1, Ordering::Relaxed)
        )))
    }
    #[test]
    fn persist_and_replace_only_settings() {
        let store = store();
        assert!(!store.load().unwrap().saved);
        fs::write(store.directory.join("keep.txt"), "user data").unwrap();
        let mut settings = LauncherSettings::default();
        settings.fullscreen = true;
        assert_eq!(store.save(settings.clone()).unwrap().settings, settings);
        settings.ram_mib = 8192;
        store.save(settings.clone()).unwrap();
        assert_eq!(store.load().unwrap().settings, settings);
        assert_eq!(
            fs::read_to_string(store.directory.join("keep.txt")).unwrap(),
            "user data"
        );
        cleanup(&store);
    }
    #[test]
    fn corrupt_and_unknown_version_never_overwritten() {
        let store = store();
        store.load().unwrap();
        let path = store.directory.join("launcher-settings.json");
        for raw in ["broken", "{\"format_version\":2,\"settings\":{\"ram_mib\":4096,\"width\":1920,\"height\":1080,\"fullscreen\":false}}", "{\"format_version\":1,\"settings\":{\"ram_mib\":4096,\"width\":1920,\"height\":1080,\"fullscreen\":false,\"javaagent\":\"evil\"}}"] {
            fs::write(&path, raw).unwrap(); assert!(store.save(LauncherSettings::default()).is_err()); assert_eq!(fs::read_to_string(&path).unwrap(), raw);
        }
        cleanup(&store);
    }
    #[test]
    fn settings_bounds_and_size() {
        let mut input = LauncherSettings::default();
        input.ram_mib = u32::MAX;
        assert!(validate(input).is_err());
        let mut input = LauncherSettings::default();
        input.width = 1;
        assert!(validate(input).is_err());
        let store = store();
        store.load().unwrap();
        fs::write(
            store.directory.join("launcher-settings.json"),
            vec![b'x'; 4097],
        )
        .unwrap();
        assert!(store.load().is_err());
        cleanup(&store);
    }
}
