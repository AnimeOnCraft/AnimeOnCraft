use reqwest::{Client, Method};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use zeroize::{Zeroize, Zeroizing};

#[cfg(all(feature = "native-acceptance", feature = "pack-acceptance"))]
compile_error!("Account and pack acceptance profiles must be built separately.");
#[cfg(all(
    feature = "installer-acceptance",
    any(feature = "native-acceptance", feature = "pack-acceptance")
))]
compile_error!("Installer acceptance must be built separately from account/pack acceptance.");
#[cfg(not(any(
    feature = "native-acceptance",
    feature = "pack-acceptance",
    feature = "installer-acceptance"
)))]
pub const ENVIRONMENT: &str = match option_env!("AOC_NATIVE_ENVIRONMENT") {
    Some(value) => value,
    None => "development-loopback-v1",
};
#[cfg(feature = "native-acceptance")]
pub const ENVIRONMENT: &str = "acceptance-loopback-v1";
#[cfg(feature = "pack-acceptance")]
pub const ENVIRONMENT: &str = "pack-acceptance-loopback-v1";
#[cfg(feature = "installer-acceptance")]
pub const ENVIRONMENT: &str = "installer-acceptance-loopback-v1";
pub type Error = &'static str;
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ticket_version_header_preserves_the_previous_empty_body_and_maps_upgrade_required() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let origin = format!("http://{}", listener.local_addr().unwrap());
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let mut bytes = Vec::new();
            loop {
                let mut chunk = [0u8; 4096];
                let n = stream.read(&mut chunk).unwrap();
                assert!(n > 0);
                bytes.extend_from_slice(&chunk[..n]);
                if bytes.windows(4).any(|v| v == b"\r\n\r\n") && bytes.ends_with(b"{}") {
                    break;
                }
            }
            let request = String::from_utf8(bytes).unwrap().to_lowercase();
            assert!(request.starts_with("post /api/v1/game/tickets http/1.1\r\n"));
            assert!(request.contains(&format!(
                "x-aoc-launcher-version: {}\r\n",
                env!("CARGO_PKG_VERSION")
            )));
            assert!(!request.contains("launcher_version"));
            stream.write_all(b"HTTP/1.1 426 Upgrade Required\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}").unwrap();
        });
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
            .block_on(async {
                let reply = AccountClient::with_origin(origin)
                    .unwrap()
                    .request(
                        Method::POST,
                        "game/tickets",
                        Some(serde_json::json!({})),
                        None,
                        None,
                    )
                    .await
                    .unwrap();
                assert_eq!(reply.success(), Err("LAUNCHER_UPDATE_REQUIRED"));
            });
        server.join().unwrap();
    }
    #[test]
    fn disabled_account_api_is_reported_separately_from_missing_player_data() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let origin = format!("http://{}", listener.local_addr().unwrap());
        let server = std::thread::spawn(move || {
            for _ in 0..2 {
                let (mut stream, _) = listener.accept().unwrap();
                stream
                    .set_read_timeout(Some(Duration::from_secs(5)))
                    .unwrap();
                let mut request = [0u8; 4096];
                assert!(stream.read(&mut request).unwrap() > 0);
                stream.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}").unwrap();
            }
        });
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        runtime.block_on(async {
            let client = AccountClient::with_origin(origin).unwrap();
            assert!(matches!(
                client
                    .request(Method::GET, "auth/me", None, None, None)
                    .await,
                Err("ACCOUNT_SERVICE_UNAVAILABLE")
            ));
            let reply = client
                .request(Method::GET, "me/access", None, None, None)
                .await
                .unwrap();
            assert_eq!(reply.success(), Err("NOT_FOUND"));
        });
        server.join().unwrap();
    }
    #[test]
    #[ignore = "requires the isolated native-account test TLS server"]
    fn fixture_tls_verification_and_response_bounds() {
        #[derive(Deserialize)]
        struct Fixture {
            port: u16,
            certificate: String,
        }
        let file = std::env::var("AOC_NATIVE_TLS_FIXTURE").expect("own fixture path required");
        let fixture: Fixture = serde_json::from_slice(&std::fs::read(file).unwrap()).unwrap();
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        runtime.block_on(async {
            let url = format!("https://localhost:{}", fixture.port);
            let untrusted = AccountClient::with_origin(url.clone()).unwrap();
            assert!(untrusted
                .request(Method::GET, "auth/me", None, None, None)
                .await
                .is_err());
            let certificate =
                reqwest::Certificate::from_pem(fixture.certificate.as_bytes()).unwrap();
            let client = Client::builder()
                .no_proxy()
                .redirect(reqwest::redirect::Policy::none())
                .timeout(Duration::from_secs(5))
                .add_root_certificate(certificate)
                .build()
                .unwrap();
            let mut trusted = AccountClient {
                client,
                origin: url,
                rate_until: AtomicU64::new(0),
            };
            assert_eq!(
                trusted
                    .request(Method::GET, "auth/me", None, None, None)
                    .await
                    .unwrap()
                    .status,
                401
            );
            assert!(matches!(
                trusted
                    .request(Method::GET, "oversize", None, None, None)
                    .await,
                Err("INVALID_RESPONSE")
            ));
            assert!(matches!(
                trusted
                    .request(Method::GET, "redirect", None, None, None)
                    .await,
                Err("INVALID_RESPONSE")
            ));
            trusted.origin = format!("https://127.0.0.1:{}", fixture.port);
            assert!(trusted
                .request(Method::GET, "auth/me", None, None, None)
                .await
                .is_err());
        });
    }
    #[test]
    fn token_and_profile_contracts_reject_wrong_scope() {
        let mut pair = TokenPair {
            access_token: format!("aa_{}", "x".repeat(43)),
            refresh_token: format!("ar_{}", "x".repeat(43)),
            expires_in: 600,
            session_id: uuid::Uuid::new_v4().to_string(),
            absolute_expires_at: "2099-01-01T00:00:00Z".into(),
        };
        assert!(pair.validate().is_ok());
        pair.expires_in = 0;
        assert!(pair.validate().is_err());
        assert!(serde_json::from_str::<TokenPair>(r#"{"access_token":"x","access_token":"x","refresh_token":"x","expires_in":600,"session_id":"x","absolute_expires_at":"x"}"#).is_err());
        let profile = Profile {
            id: uuid::Uuid::new_v4().to_string(),
            login: "fixture".into(),
            password_change_required: false,
            email: "fixture@example.test".into(),
            email_verified: true,
            system_role: "PLAYER".into(),
            clan_management: vec![],
            session_id: pair.session_id.clone(),
            client_kind: "WEB_ADMIN".into(),
        };
        assert!(profile.validate(&pair.session_id).is_err());
    }
}
pub fn id(value: &str) -> bool {
    uuid::Uuid::parse_str(value).is_ok_and(|v| v.get_version_num() == 4 && v.to_string() == value)
}
pub fn opaque(value: &str, prefix: &str) -> bool {
    value.strip_prefix(prefix).is_some_and(|v| {
        v.len() == 43
            && v.bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
    })
}
pub fn future(value: &str) -> bool {
    chrono::DateTime::parse_from_rfc3339(value)
        .is_ok_and(|v| v.timestamp() > chrono::Utc::now().timestamp())
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TokenPair {
    pub access_token: String,
    pub refresh_token: String,
    pub expires_in: u64,
    pub session_id: String,
    pub absolute_expires_at: String,
}
impl Drop for TokenPair {
    fn drop(&mut self) {
        self.access_token.zeroize();
        self.refresh_token.zeroize();
    }
}
impl TokenPair {
    pub fn validate(&self) -> Result<(), Error> {
        if opaque(&self.access_token, "aa_")
            && opaque(&self.refresh_token, "ar_")
            && id(&self.session_id)
            && (31..=600).contains(&self.expires_in)
            && future(&self.absolute_expires_at)
        {
            Ok(())
        } else {
            Err("INVALID_RESPONSE")
        }
    }
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Manager {
    pub clan_id: String,
    pub can_remove_members: bool,
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Profile {
    pub id: String,
    pub login: String,
    pub password_change_required: bool,
    pub email: String,
    pub email_verified: bool,
    pub system_role: String,
    pub clan_management: Vec<Manager>,
    pub session_id: String,
    pub client_kind: String,
}
impl Profile {
    pub fn validate(&self, session: &str) -> Result<(), Error> {
        if id(&self.id)
            && self.session_id == session
            && self.client_kind == "LAUNCHER"
            && ["PLAYER", "OWNER"].contains(&self.system_role.as_str())
            && (3..=64).contains(&self.login.len())
            && self
                .login
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"_.-".contains(&b))
            && self.login.as_bytes()[0].is_ascii_alphanumeric()
            && self.email.len() <= 320
            && self.email.contains('@')
            && self.clan_management.len() <= 1000
            && self.clan_management.iter().all(|m| id(&m.clan_id))
        {
            Ok(())
        } else {
            Err("INVALID_RESPONSE")
        }
    }
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Challenge {
    pub mfa_required: bool,
    pub challenge: String,
}
impl Drop for Challenge {
    fn drop(&mut self) {
        self.challenge.zeroize();
    }
}
pub enum Login {
    Pair(TokenPair),
    Mfa(Challenge),
}
pub struct AccountClient {
    client: Client,
    origin: String,
    rate_until: AtomicU64,
}
pub struct Reply {
    pub status: u16,
    pub bytes: Zeroizing<Vec<u8>>,
}
struct SecretJson(serde_json::Value);
impl Drop for SecretJson {
    fn drop(&mut self) {
        fn erase(value: &mut serde_json::Value) {
            match value {
                serde_json::Value::String(value) => value.zeroize(),
                serde_json::Value::Array(items) => items.iter_mut().for_each(erase),
                serde_json::Value::Object(items) => items.values_mut().for_each(erase),
                _ => {}
            }
        }
        erase(&mut self.0);
    }
}
impl Reply {
    pub fn parse<T: DeserializeOwned>(&self) -> Result<T, Error> {
        serde_json::from_slice(&self.bytes).map_err(|_| "INVALID_RESPONSE")
    }
    pub fn success(&self) -> Result<(), Error> {
        match self.status {
            200..=299 => Ok(()),
            400 => Err("VALIDATION_ERROR"),
            401 => Err("AUTHENTICATION_REQUIRED"),
            403 => Err("FORBIDDEN"),
            404 => Err("NOT_FOUND"),
            409 => Err("RESOURCE_CONFLICT"),
            412 => Err("STALE_VERSION"),
            426 => Err("LAUNCHER_UPDATE_REQUIRED"),
            428 => Err("PRECONDITION_REQUIRED"),
            429 => Err("RATE_LIMITED"),
            _ => Err("SERVICE_UNAVAILABLE"),
        }
    }
}
impl AccountClient {
    pub fn new() -> Result<Self, Error> {
        Self::with_origin(if cfg!(feature = "native-acceptance") {
            "http://127.0.0.1:39431".into()
        } else {
            crate::deployment::public_origin()?
        })
    }
    fn with_origin(origin: String) -> Result<Self, Error> {
        let client = Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(2))
            .timeout(Duration::from_secs(15))
            .build()
            .map_err(|_| "SERVICE_UNAVAILABLE")?;
        Ok(Self {
            client,
            origin,
            rate_until: AtomicU64::new(0),
        })
    }
    pub fn retry_after(&self) -> u64 {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        self.rate_until.load(Ordering::Relaxed).saturating_sub(now)
    }
    pub async fn request(
        &self,
        method: Method,
        route: &str,
        body: Option<serde_json::Value>,
        access: Option<&str>,
        command: Option<(&str, Option<&str>)>,
    ) -> Result<Reply, Error> {
        if self.retry_after() > 0 {
            return Err("RATE_LIMITED");
        }
        // Routes are composed only in this module/auth/player allowlists, never supplied by WebView.
        let mut request = self
            .client
            .request(method, format!("{}/api/v1/{route}", self.origin))
            .header("x-aoc-client", "launcher")
            .header("accept", "application/json");
        if route == "game/tickets" {
            request = request.header("x-aoc-launcher-version", env!("CARGO_PKG_VERSION"));
        }
        if let Some(value) = access {
            if !opaque(value, "aa_") {
                return Err("AUTHENTICATION_REQUIRED");
            }
            request = request.bearer_auth(value);
        }
        if let Some((key, version)) = command {
            request = request.header("idempotency-key", key);
            if let Some(v) = version {
                request = request.header("if-match", format!("\"{v}\""));
            }
        }
        let body = body.map(SecretJson);
        if let Some(value) = &body {
            let bytes = serde_json::to_vec(&value.0).map_err(|_| "VALIDATION_ERROR")?;
            if bytes.len()
                > if route == "auth/me/skin" {
                    180000
                } else {
                    16384
                }
            {
                return Err("VALIDATION_ERROR");
            }
            request = request
                .header("content-type", "application/json")
                .body(bytes);
        }
        let mut response = request.send().await.map_err(|_| "NETWORK_UNCERTAIN")?;
        let status = response.status().as_u16();
        if status == 404 && route.starts_with("auth/") {
            return Err("ACCOUNT_SERVICE_UNAVAILABLE");
        }
        if status == 429 {
            let seconds = response
                .headers()
                .get("retry-after")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse::<u64>().ok())
                .unwrap_or(60)
                .clamp(1, 3600);
            let now = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs();
            self.rate_until.store(now + seconds, Ordering::Relaxed);
        }
        if (300..400).contains(&status) {
            return Err("INVALID_RESPONSE");
        }
        let mut bytes = Zeroizing::new(Vec::new());
        while let Some(chunk) = response.chunk().await.map_err(|_| "NETWORK_UNCERTAIN")? {
            if bytes.len() + chunk.len()
                > if route == "auth/me/capes/wardrobe" {
                    4 * 1024 * 1024
                } else {
                    524288
                }
            {
                return Err("INVALID_RESPONSE");
            }
            bytes.extend_from_slice(&chunk);
        }
        if status != 204
            && !response
                .headers()
                .get("content-type")
                .and_then(|v| v.to_str().ok())
                .is_some_and(|v| v.split(';').next() == Some("application/json"))
        {
            return Err("INVALID_RESPONSE");
        }
        Ok(Reply { status, bytes })
    }
    pub async fn login(&self, body: serde_json::Value) -> Result<Login, Error> {
        let reply = self
            .request(Method::POST, "auth/login", Some(body), None, None)
            .await?;
        reply.success()?;
        if let Ok(pair) = reply.parse::<TokenPair>() {
            pair.validate()?;
            return Ok(Login::Pair(pair));
        }
        let challenge = reply.parse::<Challenge>()?;
        if !challenge.mfa_required || !opaque(&challenge.challenge, "ac_") {
            return Err("INVALID_RESPONSE");
        }
        Ok(Login::Mfa(challenge))
    }
    pub async fn refresh(&self, token: &str) -> Result<TokenPair, Error> {
        let reply = self
            .request(
                Method::POST,
                "auth/refresh",
                Some(serde_json::json!({"token":token})),
                None,
                None,
            )
            .await?;
        reply.success()?;
        let pair = reply.parse::<TokenPair>()?;
        pair.validate()?;
        Ok(pair)
    }
    pub async fn me(&self, pair: &TokenPair) -> Result<Profile, Error> {
        let reply = self
            .request(Method::GET, "auth/me", None, Some(&pair.access_token), None)
            .await?;
        reply.success()?;
        let profile = reply.parse::<Profile>()?;
        profile.validate(&pair.session_id)?;
        Ok(profile)
    }
    pub async fn revoke(&self, token: &str) -> bool {
        self.request(
            Method::POST,
            "auth/logout",
            Some(serde_json::json!({"token":token})),
            None,
            None,
        )
        .await
        .is_ok_and(|r| r.status == 204)
    }
}
