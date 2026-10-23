use crate::account_client::{AccountClient, Error, Login, Profile, TokenPair, ENVIRONMENT};
use crate::credential_store::{CredentialStore, Record};
use reqwest::Method;
use serde::{Deserialize, Serialize};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use zeroize::{Zeroize, Zeroizing};

#[derive(Deserialize)]
#[serde(tag = "action", rename_all = "snake_case", deny_unknown_fields)]
pub enum AccountInput {
    Login {
        login: String,
        password: String,
        remember: bool,
    },
    Mfa {
        operation_id: String,
        proof: String,
    },
    Cancel,
    Register {
        login: String,
        email: String,
        password: String,
    },
    Logout,
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn refresh_preserves_generation_but_logout_cancels_old_results() {
        let auth = Auth {
            client: AccountClient::new().unwrap(),
            state: Mutex::new(State {
                generation: 3,
                status: "REFRESHING",
                session: None,
                profile: None,
                mfa: None,
                notice: None,
                restore: None,
            }),
            store: CredentialStore::fixture(),
            refresh: tokio::sync::Mutex::new(()),
        };
        assert!(auth.current(3));
        assert!(!auth.current(2));
        auth.lock().unwrap().clear("SIGNED_OUT");
        assert!(!auth.current(3));
    }
    #[test]
    fn snapshot_waits_for_an_existing_restore_instead_of_publishing_transient_login_state() {
        let auth = Auth {
            client: AccountClient::new().unwrap(),
            state: Mutex::new(State {
                generation: 0,
                status: "REFRESHING",
                session: None,
                profile: None,
                mfa: None,
                notice: None,
                restore: None,
            }),
            store: CredentialStore::fixture(),
            refresh: tokio::sync::Mutex::new(()),
        };
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
            .block_on(async {
                let held = auth.refresh.lock().await;
                let mut snapshot = Box::pin(auth.snapshot());
                assert!(
                    tokio::time::timeout(Duration::from_millis(20), snapshot.as_mut())
                        .await
                        .is_err()
                );
                auth.lock().unwrap().clear("SIGNED_OUT");
                drop(held);
                assert_eq!(snapshot.await.unwrap().state, "SIGNED_OUT");
            });
    }
}
impl Drop for AccountInput {
    fn drop(&mut self) {
        match self {
            Self::Login { password, .. } | Self::Register { password, .. } => password.zeroize(),
            Self::Mfa { proof, .. } => proof.zeroize(),
            _ => {}
        };
    }
}
#[derive(Clone, Serialize)]
pub struct Snapshot {
    pub retry_after_seconds: u64,
    pub state: &'static str,
    pub profile: Option<Profile>,
    pub remembered: bool,
    pub operation_id: Option<String>,
    pub notice: Option<&'static str>,
}
struct Mfa {
    operation: String,
    challenge: Zeroizing<String>,
    expires: Instant,
    remember: bool,
}
struct Session {
    pair: TokenPair,
    expires: Instant,
    remember: bool,
}
struct State {
    generation: u64,
    status: &'static str,
    session: Option<Session>,
    profile: Option<Profile>,
    mfa: Option<Mfa>,
    notice: Option<&'static str>,
    restore: Option<Record>,
}
impl State {
    fn snapshot(&self) -> Snapshot {
        Snapshot {
            retry_after_seconds: 0,
            state: self.status,
            profile: self.profile.clone(),
            remembered: self.session.as_ref().is_some_and(|v| v.remember),
            operation_id: self.mfa.as_ref().map(|v| v.operation.clone()),
            notice: self.notice,
        }
    }
    fn clear(&mut self, status: &'static str) {
        self.generation += 1;
        self.status = status;
        self.session = None;
        self.profile = None;
        self.mfa = None;
        self.restore = None;
        self.notice = None;
    }
}
pub struct Auth {
    pub client: AccountClient,
    state: Mutex<State>,
    store: CredentialStore,
    refresh: tokio::sync::Mutex<()>,
}
fn email(value: &str) -> bool {
    value.len() <= 320 && value.contains('@') && !value.chars().any(|c| c.is_control())
}
fn password(value: &str) -> bool {
    value.len() <= 1024 && !value.is_empty()
}
impl Auth {
    pub fn new() -> Result<Self, Error> {
        Self::with_store(CredentialStore::development())
    }
    fn with_store(store: CredentialStore) -> Result<Self, Error> {
        let (restore, status) = match store.load() {
            Ok(Some(record @ Record::Ready { .. })) => (Some(record), "RESTORING"),
            Ok(Some(Record::Pending { .. })) => (None, "REAUTH_REQUIRED"),
            Ok(_) => (None, "SIGNED_OUT"),
            Err("REAUTH_REQUIRED") => (None, "REAUTH_REQUIRED"),
            Err(_) => (None, "STORAGE_UNAVAILABLE"),
        };
        Ok(Self {
            client: AccountClient::new()?,
            state: Mutex::new(State {
                generation: 0,
                status,
                session: None,
                profile: None,
                mfa: None,
                notice: None,
                restore,
            }),
            store,
            refresh: tokio::sync::Mutex::new(()),
        })
    }
    fn lock(&self) -> Result<std::sync::MutexGuard<'_, State>, Error> {
        self.state.lock().map_err(|_| "STORAGE_UNAVAILABLE")
    }
    pub async fn snapshot(&self) -> Result<Snapshot, Error> {
        if matches!(self.lock()?.status, "RESTORING" | "REFRESHING") {
            let _ = self.ensure_session().await;
        }
        let mut snapshot = self.lock()?.snapshot();
        snapshot.retry_after_seconds = self.client.retry_after();
        Ok(snapshot)
    }
    fn fail(&self, generation: u64, error: Error) {
        if let Ok(mut state) = self.lock() {
            if state.generation == generation {
                state.clear(if error == "STORAGE_UNAVAILABLE" {
                    "STORAGE_UNAVAILABLE"
                } else {
                    "REAUTH_REQUIRED"
                });
                state.notice = Some(error);
            }
        }
    }
    async fn publish(
        &self,
        generation: u64,
        pair: TokenPair,
        remember: bool,
        expected: Option<&str>,
    ) -> Result<Snapshot, Error> {
        let result = async {
            pair.validate()?;
            if expected.is_some_and(|v| v != pair.session_id) {
                return Err("INVALID_RESPONSE");
            }
            let profile = self.client.me(&pair).await?;
            let mut state = self.lock()?;
            if state.generation != generation {
                return Err("CANCELLED");
            }
            if remember {
                self.store.save(&Record::Ready {
                    format: 1,
                    environment: ENVIRONMENT.into(),
                    session_id: pair.session_id.clone(),
                    absolute_expires_at: pair.absolute_expires_at.clone(),
                    refresh_token: Zeroizing::new(pair.refresh_token.clone()),
                })?;
            }
            let expires = Instant::now() + Duration::from_secs(pair.expires_in - 30);
            state.session = Some(Session {
                pair: TokenPair {
                    access_token: pair.access_token.clone(),
                    refresh_token: pair.refresh_token.clone(),
                    expires_in: pair.expires_in,
                    session_id: pair.session_id.clone(),
                    absolute_expires_at: pair.absolute_expires_at.clone(),
                },
                expires,
                remember,
            });
            state.profile = Some(profile);
            state.mfa = None;
            state.status = "SIGNED_IN";
            state.notice = None;
            Ok(state.snapshot())
        }
        .await;
        if let Err(error) = result {
            let _ = self.client.revoke(&pair.refresh_token).await;
            self.fail(generation, error);
        }
        result
    }
    pub async fn ensure_session(&self) -> Result<(), Error> {
        let _flight = self.refresh.lock().await;
        if self.client.retry_after() > 0 {
            return Err("RATE_LIMITED");
        }
        let (generation, token, expected, remember) = {
            let mut state = self.lock()?;
            if let Some(session) = &state.session {
                if session.expires > Instant::now() {
                    return Ok(());
                }
            }
            let (token, expected, remember) = if let Some(Record::Ready {
                refresh_token,
                session_id,
                ..
            }) = state.restore.take()
            {
                (refresh_token, session_id, true)
            } else if let Some(session) = &state.session {
                (
                    Zeroizing::new(session.pair.refresh_token.clone()),
                    session.pair.session_id.clone(),
                    session.remember,
                )
            } else {
                return Err("AUTHENTICATION_REQUIRED");
            };
            if remember {
                if let Err(error) = self.store.save(&Record::pending()) {
                    state.clear("STORAGE_UNAVAILABLE");
                    return Err(error);
                }
            }
            state.status = "REFRESHING";
            state.session = None;
            state.profile = None;
            (state.generation, token, expected, remember)
        };
        match self.client.refresh(&token).await {
            Ok(pair) => {
                self.publish(generation, pair, remember, Some(&expected))
                    .await?;
                Ok(())
            }
            Err(error) => {
                self.fail(generation, error);
                Err(error)
            }
        }
    }
    pub fn access(&self) -> Result<(u64, Zeroizing<String>), Error> {
        let state = self.lock()?;
        let session = state.session.as_ref().ok_or("AUTHENTICATION_REQUIRED")?;
        Ok((
            state.generation,
            Zeroizing::new(session.pair.access_token.clone()),
        ))
    }
    pub fn current(&self, generation: u64) -> bool {
        self.lock().is_ok_and(|s| {
            s.generation == generation && ["SIGNED_IN", "REFRESHING"].contains(&s.status)
        })
    }
    pub fn expired(&self, generation: u64, access: &str) {
        if let Ok(mut state) = self.lock() {
            if state.generation == generation {
                if let Some(session) = &mut state.session {
                    if session.pair.access_token == access {
                        session.expires = Instant::now();
                    }
                }
            }
        }
    }
    pub fn security_identity(&self, generation: u64) -> Result<(String, String), Error> {
        let state = self.lock()?;
        if state.generation != generation {
            return Err("CANCELLED");
        }
        let profile = state.profile.as_ref().ok_or("AUTHENTICATION_REQUIRED")?;
        Ok((profile.session_id.clone(), profile.email.clone()))
    }
    pub fn publish_security<T>(
        &self,
        generation: u64,
        task: impl FnOnce() -> Result<T, Error>,
    ) -> Result<T, Error> {
        let state = self.lock()?;
        if state.generation != generation || !["SIGNED_IN", "REFRESHING"].contains(&state.status) {
            return Err("CANCELLED");
        }
        task()
    }
    pub fn prepare_security_invalidation(&self, generation: u64) -> Result<(), Error> {
        let mut state = self.lock()?;
        if state.generation != generation || state.session.is_none() {
            return Err("CANCELLED");
        }
        if let Err(error) = self.store.save(&Record::logged_out()) {
            state.clear("STORAGE_UNAVAILABLE");
            return Err(error);
        }
        if let Some(session) = &mut state.session {
            session.remember = false;
        }
        Ok(())
    }
    pub fn finish_security_invalidation(
        &self,
        generation: u64,
        uncertain: bool,
        changed: bool,
    ) -> Result<Snapshot, Error> {
        let mut state = self.lock()?;
        if state.generation != generation {
            return Err("CANCELLED");
        }
        state.clear(if uncertain {
            "REAUTH_REQUIRED"
        } else {
            "SIGNED_OUT"
        });
        state.notice = Some(if uncertain {
            "SECURITY_OUTCOME_UNKNOWN"
        } else if changed {
            "SECURITY_LOGIN_REQUIRED"
        } else {
            "SECURITY_ACTION_REJECTED"
        });
        Ok(state.snapshot())
    }
    pub async fn action(&self, input: AccountInput) -> Result<Snapshot, Error> {
        if matches!(input, AccountInput::Logout | AccountInput::Cancel) {
            let (token, persisted) = {
                let mut state = self.lock()?;
                let token = state
                    .session
                    .as_ref()
                    .map(|s| Zeroizing::new(s.pair.refresh_token.clone()));
                state.clear("SIGNED_OUT");
                let result = self.store.save(&Record::logged_out());
                if result.is_err() {
                    state.status = "STORAGE_UNAVAILABLE";
                }
                (token, result.is_ok())
            };
            let revoked = if let Some(token) = token {
                self.client.revoke(&token).await
            } else {
                true
            };
            let mut state = self.lock()?;
            state.notice = Some(if !persisted {
                "STORAGE_UNAVAILABLE"
            } else if !revoked {
                "REMOTE_LOGOUT_UNCONFIRMED"
            } else {
                "SIGNED_OUT"
            });
            return Ok(state.snapshot());
        }
        let (generation, challenge, remember) = {
            let mut state = self.lock()?;
            if matches!(input, AccountInput::Mfa { .. }) {
                let mfa = state.mfa.as_ref().ok_or("AUTHENTICATION_REQUIRED")?;
                if let AccountInput::Mfa {
                    operation_id,
                    proof,
                } = &input
                {
                    if operation_id != &mfa.operation
                        || mfa.expires <= Instant::now()
                        || proof.len() > 128
                        || proof.len() < 6
                    {
                        return Err("VALIDATION_ERROR");
                    }
                }
                let values = (state.generation, Some(mfa.challenge.clone()), mfa.remember);
                state.status = "AUTHENTICATING";
                values
            } else {
                if !["SIGNED_OUT", "REAUTH_REQUIRED", "STORAGE_UNAVAILABLE"].contains(&state.status)
                {
                    return Err("BUSY");
                }
                let remember = matches!(input, AccountInput::Login { remember: true, .. });
                // Invalidate previous remembered state before any new login, including memory-only login.
                if self.store.save(&Record::logged_out()).is_err() {
                    state.clear("STORAGE_UNAVAILABLE");
                    return Err("STORAGE_UNAVAILABLE");
                }
                state.clear("AUTHENTICATING");
                (state.generation, None, remember)
            }
        };
        let result=async {
            match &input {
                AccountInput::Login{login:e,password:p,..}=>{
                    if e.len()<3||e.len()>320||e.chars().any(|c|c.is_control())||!password(p){return Err("VALIDATION_ERROR");}
                    match self.client.login(serde_json::json!({"login":e,"password":p})).await? {
                        Login::Pair(pair)=>self.publish(generation,pair,remember,None).await,
                        Login::Mfa(challenge)=>{let mut state=self.lock()?;if state.generation!=generation{return Err("CANCELLED");}state.status="MFA_REQUIRED";state.mfa=Some(Mfa{operation:uuid::Uuid::new_v4().to_string(),challenge:Zeroizing::new(challenge.challenge.clone()),expires:Instant::now()+Duration::from_secs(300),remember});Ok(state.snapshot())}
                    }
                }
                AccountInput::Mfa{proof,..}=>{
                    let reply=self.client.request(Method::POST,"auth/mfa/login",Some(serde_json::json!({"token":challenge.as_ref().ok_or("AUTHENTICATION_REQUIRED")?.as_str(),"proof":proof})),None,None).await?;reply.success()?;
                    self.publish(generation,reply.parse::<TokenPair>()?,remember,None).await
                }
                AccountInput::Register{login:l,email:e,password:p}=>{
                    if !email(e)||!password(p)||!(3..=64).contains(&l.len())||!l.bytes().all(|b|b.is_ascii_alphanumeric()||b"_.-".contains(&b))||!l.as_bytes()[0].is_ascii_alphanumeric(){return Err("VALIDATION_ERROR");}
                    let reply=self.client.request(Method::POST,"auth/register",Some(serde_json::json!({"login":l,"email":e,"password":p})),None,None).await?;reply.success()?;
                    self.complete(generation,"COMPLETED_LOGIN_REQUIRED")
                }
                _=>Err("VALIDATION_ERROR"),
            }
        }.await;
        if let Err(error) = result {
            let mut state = self.lock()?;
            if state.generation == generation && state.status == "AUTHENTICATING" {
                if ["NETWORK_UNCERTAIN", "INVALID_RESPONSE"].contains(&error) {
                    state.clear("REAUTH_REQUIRED");
                } else {
                    state.status = if state.mfa.is_some() {
                        "MFA_REQUIRED"
                    } else {
                        "SIGNED_OUT"
                    };
                }
                state.notice = Some(error);
            }
        }
        result
    }
    fn complete(&self, generation: u64, notice: &'static str) -> Result<Snapshot, Error> {
        let mut state = self.lock()?;
        if state.generation != generation {
            return Err("CANCELLED");
        }
        state.clear("SIGNED_OUT");
        state.notice = Some(notice);
        Ok(state.snapshot())
    }
}
