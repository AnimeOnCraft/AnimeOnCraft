//! 7E2a: separate launcher trust and bounded preparation. No installer execution or IPC.
//! Ordinary configuration is absent. Test signing keys never enter the application.
#![allow(dead_code)]
#[cfg(all(windows, feature = "self-update-acceptance"))]
mod acceptance;
pub(crate) mod manager;
mod transport;
#[cfg(windows)]
mod windows;
use crate::pack::{
    actor::{Actor, Id, UpdateReservation},
    local::{LocalInstance, Status},
    strict_json,
};
use base64::Engine;
use minisign_verify::{PublicKey, Signature};
use reqwest::Url;
use semver::Version;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

const MAX_ENVELOPE: usize = 32 * 1024;
const MAX_PAYLOAD: usize = 16 * 1024;
const MAX_SIGNATURE: usize = 1024;
const MAX_INSTALLER: u64 = 64 * 1024 * 1024;
const PLAN_TTL: Duration = Duration::from_secs(300);
const PRODUCT: &str = "ru.animeoncraft.launcher";
const TARGET: &str = "windows-x86_64-nsis";
type Result<T> = std::result::Result<T, &'static str>;

struct Config {
    base: Url,
    metadata_key: PublicKey,
    installer_key: PublicKey,
    current: Version,
}
impl Config {
    // Native release configuration only; never supplied by UI, environment or remote metadata.
    fn new(base: &str, metadata_key: &str, installer_key: &str, current: &str) -> Result<Self> {
        if base.len() > 512
            || !base.is_ascii()
            || base.bytes().any(|b| b <= 32 || b"%\\?#".contains(&b))
        {
            return Err("UPDATE_CONFIG_INVALID");
        }
        let url = Url::parse(base).map_err(|_| "UPDATE_CONFIG_INVALID")?;
        let segments = url.path().trim_matches('/');
        let decode_key = |key: &str| -> Result<Vec<u8>> {
            if key.len() != 56 {
                return Err("UPDATE_CONFIG_INVALID");
            }
            let bytes = base64::engine::general_purpose::STANDARD
                .decode(key)
                .map_err(|_| "UPDATE_CONFIG_INVALID")?;
            if bytes.len() != 42 || base64::engine::general_purpose::STANDARD.encode(&bytes) != key
            {
                return Err("UPDATE_CONFIG_INVALID");
            }
            Ok(bytes)
        };
        let metadata_bytes = decode_key(metadata_key)?;
        let installer_bytes = decode_key(installer_key)?;
        if url.scheme() != "https"
            || url.host_str().is_none()
            || !url.username().is_empty()
            || url.password().is_some()
            || !url.path().ends_with('/')
            || url.path().contains("//")
            || url.as_str() != base
            || segments.is_empty()
            || segments.split('/').any(|s| {
                s.is_empty()
                    || s == "."
                    || s == ".."
                    || !s
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b"-_.".contains(&b))
            })
            || metadata_bytes[10..] == installer_bytes[10..]
        {
            return Err("UPDATE_CONFIG_INVALID");
        }
        Ok(Self {
            base: url,
            metadata_key: PublicKey::from_base64(metadata_key)
                .map_err(|_| "UPDATE_CONFIG_INVALID")?,
            installer_key: PublicKey::from_base64(installer_key)
                .map_err(|_| "UPDATE_CONFIG_INVALID")?,
            current: stable_version(current)?,
        })
    }
    fn artifact(&self, manifest: &Manifest) -> Result<Url> {
        self.base
            .join(&format!("{}/{}", manifest.version, manifest.file))
            .map_err(|_| "UPDATE_MANIFEST_INVALID")
    }
}
fn ordinary_config() -> Option<Config> {
    None
}
fn stable_version(value: &str) -> Result<Version> {
    if value.len() > 48 {
        return Err("UPDATE_VERSION_INVALID");
    }
    let version = Version::parse(value).map_err(|_| "UPDATE_VERSION_INVALID")?;
    if !version.pre.is_empty() || !version.build.is_empty() || version.to_string() != value {
        return Err("UPDATE_VERSION_INVALID");
    }
    Ok(version)
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Envelope {
    payload: String,
    signature: String,
}
#[derive(Deserialize, Serialize, Clone)]
#[serde(deny_unknown_fields)]
struct Manifest {
    schema: u32,
    product: String,
    channel: String,
    target: String,
    version: String,
    issued_at: i64,
    expires_at: i64,
    file: String,
    size: u64,
    sha256: String,
    installer_signature: String,
}
fn signature(key: &PublicKey, body: &[u8], value: &str) -> Result<()> {
    if value.is_empty() || value.len() > MAX_SIGNATURE {
        return Err("UPDATE_SIGNATURE_INVALID");
    }
    let signature = Signature::decode(value).map_err(|_| "UPDATE_SIGNATURE_INVALID")?;
    // false disallows legacy non-prehashed signatures; trusted comment verification is included.
    key.verify(body, &signature, false)
        .map_err(|_| "UPDATE_SIGNATURE_INVALID")
}
fn verify_manifest(config: &Config, bytes: &[u8], now: i64) -> Result<(Manifest, Version)> {
    let envelope: Envelope = serde_json::from_value(strict_json::parse(bytes, MAX_ENVELOPE)?)
        .map_err(|_| "UPDATE_MANIFEST_INVALID")?;
    if envelope.payload.is_empty() || envelope.payload.len() > MAX_PAYLOAD {
        return Err("UPDATE_MANIFEST_INVALID");
    }
    signature(
        &config.metadata_key,
        envelope.payload.as_bytes(),
        &envelope.signature,
    )?;
    let manifest: Manifest = serde_json::from_value(strict_json::parse(
        envelope.payload.as_bytes(),
        MAX_PAYLOAD,
    )?)
    .map_err(|_| "UPDATE_MANIFEST_INVALID")?;
    let version = stable_version(&manifest.version)?;
    if manifest.schema != 1
        || manifest.product != PRODUCT
        || manifest.channel != "stable"
        || manifest.target != TARGET
        || manifest.file != format!("AnimeOnCraft_{}_x64-setup.exe", manifest.version)
        || !(512..=MAX_INSTALLER).contains(&manifest.size)
        || manifest.sha256.len() != 64
        || !manifest
            .sha256
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        || manifest.installer_signature.is_empty()
        || manifest.installer_signature.len() > MAX_SIGNATURE
    {
        return Err("UPDATE_MANIFEST_INVALID");
    }
    if manifest.issued_at > now
        || manifest.issued_at < 0
        || manifest.expires_at <= now
        || manifest.expires_at <= manifest.issued_at
        || manifest.expires_at - manifest.issued_at > 86400
    {
        return Err("UPDATE_EXPIRED");
    }
    if version < config.current {
        return Err("UPDATE_ROLLBACK");
    }
    if version == config.current {
        return Err("UPDATE_NOT_NEWER");
    }
    Ok((manifest, version))
}
fn verify_installer(config: &Config, manifest: &Manifest, bytes: Vec<u8>) -> Result<Vec<u8>> {
    if bytes.len() as u64 != manifest.size
        || format!("{:x}", Sha256::digest(&bytes)) != manifest.sha256
    {
        return Err("UPDATE_INSTALLER_INVALID");
    }
    signature(&config.installer_key, &bytes, &manifest.installer_signature)?;
    Ok(bytes)
}
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
pub(crate) struct PlanView {
    pub(crate) plan_id: Id,
    pub(crate) version: String,
    pub(crate) bytes: u64,
}
struct Prepared {
    view: PlanView,
    manifest: Manifest,
    created: Instant,
    installer: Vec<u8>,
    reservation: UpdateReservation,
    local: Arc<LocalInstance>,
}
// Single-use in-memory authority, deliberately not Clone or Deserialize. No path/URL exposed.
struct VerifiedInstaller {
    version: String,
    bytes: Vec<u8>,
    reservation: UpdateReservation,
    local: Arc<LocalInstance>,
    created: Instant,
    issued_at: i64,
    expires_at: i64,
}
impl VerifiedInstaller {
    // Future installer handoff must revalidate this retained authority immediately before execution.
    fn validate(&self) -> Result<()> {
        let now = chrono::Utc::now().timestamp();
        if self.created.elapsed() >= PLAN_TTL || now < self.issued_at || now >= self.expires_at {
            return Err("UPDATE_EXPIRED");
        }
        clean(&self.reservation, &self.local)
    }
}
struct AcceptedRelease {
    version: Version,
    sha256: String,
    size: u64,
}
struct State {
    busy: bool,
    highest: Option<AcceptedRelease>,
    prepared: Option<Prepared>,
}
struct Service {
    config: Option<Config>,
    state: Mutex<State>,
}
struct Busy<'a>(&'a Service);
impl Drop for Busy<'_> {
    fn drop(&mut self) {
        if let Ok(mut state) = self.0.state.lock() {
            state.busy = false;
        }
    }
}
impl Service {
    fn expire(&self) -> Result<bool> {
        let mut state = self.state.lock().map_err(|_| "UPDATE_UNAVAILABLE")?;
        let expired = state.prepared.as_ref().is_some_and(|p| {
            p.created.elapsed() >= PLAN_TTL
                || chrono::Utc::now().timestamp() < p.manifest.issued_at
                || chrono::Utc::now().timestamp() >= p.manifest.expires_at
        });
        if expired {
            state.prepared = None;
        }
        Ok(expired)
    }
    fn ordinary() -> Self {
        Self::new(ordinary_config())
    }
    fn new(config: Option<Config>) -> Self {
        Self {
            config,
            state: Mutex::new(State {
                busy: false,
                highest: None,
                prepared: None,
            }),
        }
    }
    fn begin(&self) -> Result<Busy<'_>> {
        let mut state = self.state.lock().map_err(|_| "UPDATE_UNAVAILABLE")?;
        if state.busy {
            return Err("UPDATE_BUSY");
        }
        if state.prepared.is_some() {
            return Err("UPDATE_CONFIRMATION_REQUIRED");
        }
        state.busy = true;
        Ok(Busy(self))
    }
    fn remember(&self, manifest: &Manifest, version: Version) -> Result<()> {
        let mut state = self.state.lock().map_err(|_| "UPDATE_UNAVAILABLE")?;
        if let Some(prior) = &state.highest {
            if version < prior.version {
                return Err("UPDATE_ROLLBACK");
            }
            if version == prior.version
                && (manifest.sha256 != prior.sha256 || manifest.size != prior.size)
            {
                return Err("UPDATE_RELEASE_CHANGED");
            }
        }
        state.highest = Some(AcceptedRelease {
            version,
            sha256: manifest.sha256.clone(),
            size: manifest.size,
        });
        Ok(())
    }
    async fn prepare(
        &self,
        actor: &Actor,
        local: Arc<LocalInstance>,
        cancel: tokio::sync::watch::Receiver<bool>,
    ) -> Result<PlanView> {
        let config = self.config.as_ref().ok_or("UPDATE_NOT_CONFIGURED")?;
        let http = transport::Http::new()?;
        self.prepare_with(config, &http, actor, local, cancel).await
    }
    async fn prepare_with(
        &self,
        config: &Config,
        http: &transport::Http,
        actor: &Actor,
        local: Arc<LocalInstance>,
        mut cancel: tokio::sync::watch::Receiver<bool>,
    ) -> Result<PlanView> {
        let _busy = self.begin()?;
        let reservation = actor.reserve_update()?;
        clean(&reservation, &local)?;
        let envelope = http
            .fetch(
                config
                    .base
                    .join("latest.json")
                    .map_err(|_| "UPDATE_CONFIG_INVALID")?,
                MAX_ENVELOPE as u64,
                None,
                &mut cancel,
            )
            .await?;
        let (manifest, version) =
            verify_manifest(config, &envelope, chrono::Utc::now().timestamp())?;
        self.remember(&manifest, version)?;
        clean(&reservation, &local)?;
        let bytes = http
            .fetch(
                config.artifact(&manifest)?,
                manifest.size,
                Some(manifest.size),
                &mut cancel,
            )
            .await?;
        transport::cancelled(&cancel)?;
        let installer = verify_installer(config, &manifest, bytes)?;
        transport::cancelled(&cancel)?;
        clean(&reservation, &local)?;
        let now = chrono::Utc::now().timestamp();
        if now < manifest.issued_at || now >= manifest.expires_at {
            return Err("UPDATE_EXPIRED");
        }
        let view = PlanView {
            plan_id: Id::new(),
            version: manifest.version.clone(),
            bytes: manifest.size,
        };
        let prepared = Prepared {
            view: view.clone(),
            manifest,
            created: Instant::now(),
            installer,
            reservation,
            local,
        };
        self.state
            .lock()
            .map_err(|_| "UPDATE_UNAVAILABLE")?
            .prepared = Some(prepared);
        Ok(view)
    }
    fn discard(&self, id: Id) -> Result<()> {
        let mut state = self.state.lock().map_err(|_| "UPDATE_UNAVAILABLE")?;
        if state.prepared.as_ref().map(|p| p.view.plan_id) != Some(id) {
            return Err("UPDATE_STALE_PLAN");
        }
        state.prepared = None;
        Ok(())
    }
    fn consume(&self, id: Id) -> Result<VerifiedInstaller> {
        let mut state = self.state.lock().map_err(|_| "UPDATE_UNAVAILABLE")?;
        if state.prepared.as_ref().map(|p| p.view.plan_id) != Some(id) {
            return Err("UPDATE_STALE_PLAN");
        }
        // Consume even on expiry or dirty-state refusal: no old consent can be retried.
        let plan = state.prepared.take().ok_or("UPDATE_STALE_PLAN")?;
        let verified = VerifiedInstaller {
            version: plan.view.version,
            bytes: plan.installer,
            reservation: plan.reservation,
            local: plan.local,
            created: plan.created,
            issued_at: plan.manifest.issued_at,
            expires_at: plan.manifest.expires_at,
        };
        verified.validate()?;
        Ok(verified)
    }
}
fn clean(reservation: &UpdateReservation, local: &LocalInstance) -> Result<()> {
    reservation.validate()?;
    if local.snapshot().status != Status::Ready {
        return Err("UPDATE_LOCAL_UNAVAILABLE");
    }
    Ok(())
}
#[cfg(test)]
mod tests;
