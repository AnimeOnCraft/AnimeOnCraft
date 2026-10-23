use crate::account_client::{id, opaque, Error};
use crate::auth::{Auth, Snapshot};
use reqwest::Method;
use serde::{Deserialize, Serialize};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use zeroize::{Zeroize, Zeroizing};

#[derive(Deserialize)]
#[serde(tag = "action", rename_all = "snake_case", deny_unknown_fields)]
pub enum Input {
    Enroll {
        password: String,
    },
    Confirm {
        operation_id: String,
        password: String,
        proof: String,
    },
    CancelEnrollment,
    Password {
        password: String,
        new_password: String,
        proof: Option<String>,
    },
    Revoke {
        session_id: String,
    },
}
impl Drop for Input {
    fn drop(&mut self) {
        match self {
            Self::Enroll { password }
            | Self::Confirm { password, .. }
            | Self::Password { password, .. } => password.zeroize(),
            _ => {}
        }
        match self {
            Self::Confirm { proof, .. } => proof.zeroize(),
            Self::Password {
                new_password,
                proof,
                ..
            } => {
                new_password.zeroize();
                if let Some(proof) = proof {
                    proof.zeroize();
                }
            }
            _ => {}
        }
    }
}
#[derive(Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ResultView {
    Provisioning {
        operation_id: String,
        secret: Zeroizing<String>,
        expires_in: u64,
    },
    Recovery {
        codes: Vec<Zeroizing<String>>,
        account: Snapshot,
    },
    Completed {
        account: Snapshot,
    },
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Enrollment {
    otpauth_uri: Zeroizing<String>,
    expires_in: u64,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Recovery {
    recovery_codes: Vec<Zeroizing<String>>,
    reauthentication_required: bool,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SessionView {
    id: String,
    client_kind: String,
    created_at: String,
    absolute_expires_at: String,
    current: bool,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Sessions {
    items: Vec<SessionView>,
}
impl Sessions {
    fn validate(&self, current: &str) -> Result<(), Error> {
        if self.items.len() > 100 {
            return Err("INVALID_RESPONSE");
        }
        let mut ids = std::collections::HashSet::new();
        for item in &self.items {
            let created = chrono::DateTime::parse_from_rfc3339(&item.created_at)
                .map_err(|_| "INVALID_RESPONSE")?;
            let expires = chrono::DateTime::parse_from_rfc3339(&item.absolute_expires_at)
                .map_err(|_| "INVALID_RESPONSE")?;
            if !id(&item.id)
                || !ids.insert(&item.id)
                || !["LAUNCHER", "WEB_ADMIN"].contains(&item.client_kind.as_str())
                || expires <= created
                || item.current != (item.id == current)
            {
                return Err("INVALID_RESPONSE");
            }
        }
        Ok(())
    }
}
fn password(value: &str) -> bool {
    !value.is_empty() && value.len() <= 1024
}
fn proof(value: &str) -> bool {
    (value.len() == 6 && value.bytes().all(|b| b.is_ascii_digit())) || opaque(value, "rc_")
}
fn seed(enrollment: &Enrollment) -> Result<Zeroizing<String>, Error> {
    if enrollment.expires_in == 0
        || enrollment.expires_in > 300
        || enrollment.otpauth_uri.len() > 2048
    {
        return Err("INVALID_RESPONSE");
    }
    let uri = reqwest::Url::parse(&enrollment.otpauth_uri).map_err(|_| "INVALID_RESPONSE")?;
    if uri.scheme() != "otpauth"
        || uri.host_str() != Some("totp")
        || uri.path().len() < 2
        || !uri.username().is_empty()
        || uri.password().is_some()
        || uri.port().is_some()
        || uri.fragment().is_some()
    {
        return Err("INVALID_RESPONSE");
    }
    let mut values = std::collections::HashMap::new();
    for (key, value) in uri.query_pairs() {
        if !["secret", "issuer", "algorithm", "digits", "period"].contains(&key.as_ref())
            || values
                .insert(key.into_owned(), Zeroizing::new(value.into_owned()))
                .is_some()
        {
            return Err("INVALID_RESPONSE");
        }
    }
    for (key, expected) in [
        ("issuer", "AnimeOnCraft"),
        ("algorithm", "SHA1"),
        ("digits", "6"),
        ("period", "30"),
    ] {
        if !values.get(key).is_some_and(|v| v.as_str() == expected) {
            return Err("INVALID_RESPONSE");
        }
    }
    let secret = values.remove("secret").ok_or("INVALID_RESPONSE")?;
    if secret.len() != 32
        || !secret
            .bytes()
            .all(|b| b.is_ascii_uppercase() || (b'2'..=b'7').contains(&b))
    {
        return Err("INVALID_RESPONSE");
    }
    Ok(secret)
}
fn uncertain(error: Error) -> bool {
    [
        "NETWORK_UNCERTAIN",
        "INVALID_RESPONSE",
        "SECURITY_OUTCOME_UNKNOWN",
    ]
    .contains(&error)
}
async fn write(
    auth: &Auth,
    route: &str,
    body: serde_json::Value,
    access: &str,
) -> Result<crate::account_client::Reply, Error> {
    let reply = auth
        .client
        .request(Method::POST, route, Some(body), Some(access), None)
        .await?;
    if reply.status >= 500 {
        return Err("SECURITY_OUTCOME_UNKNOWN");
    }
    reply.success()?;
    Ok(reply)
}
pub async fn sessions(auth: &Auth) -> Result<Sessions, Error> {
    for attempt in 0..2 {
        auth.ensure_session().await?;
        let (generation, access) = auth.access()?;
        let (current, _) = auth.security_identity(generation)?;
        let reply = auth
            .client
            .request(Method::GET, "auth/sessions", None, Some(&access), None)
            .await?;
        if !auth.current(generation) {
            return Err("CANCELLED");
        }
        if reply.status == 401 && attempt == 0 {
            auth.expired(generation, &access);
            continue;
        }
        reply.success()?;
        let result: Sessions = reply.parse()?;
        result.validate(&current)?;
        return Ok(result);
    }
    Err("AUTHENTICATION_REQUIRED")
}
struct Pending {
    operation: String,
    generation: u64,
    expires: Instant,
}
pub struct Security {
    pending: Mutex<Option<Pending>>,
    flight: tokio::sync::Mutex<()>,
}
impl Security {
    pub fn new() -> Self {
        Self {
            pending: Mutex::new(None),
            flight: tokio::sync::Mutex::new(()),
        }
    }
    pub async fn action(&self, auth: &Auth, input: Input) -> Result<ResultView, Error> {
        let _flight = self.flight.try_lock().map_err(|_| "BUSY")?;
        if matches!(input, Input::CancelEnrollment) {
            *self.pending.lock().map_err(|_| "BUSY")? = None;
            return Ok(ResultView::Completed {
                account: auth.snapshot().await?,
            });
        }
        auth.ensure_session().await?;
        let (generation, access) = auth.access()?;
        let (current, _) = auth.security_identity(generation)?;
        match &input {
            Input::Enroll { password: p } => {
                if !password(p) {
                    return Err("VALIDATION_ERROR");
                }
                *self.pending.lock().map_err(|_| "BUSY")? = None;
                let result = write(
                    auth,
                    "auth/mfa/enroll",
                    serde_json::json!({"password":p}),
                    &access,
                )
                .await;
                if !auth.current(generation) {
                    return Err("CANCELLED");
                }
                let reply = result.map_err(|e| {
                    if uncertain(e) {
                        "SECURITY_OUTCOME_UNKNOWN"
                    } else {
                        e
                    }
                })?;
                let enrollment: Enrollment =
                    reply.parse().map_err(|_| "SECURITY_OUTCOME_UNKNOWN")?;
                let secret = seed(&enrollment).map_err(|_| "SECURITY_OUTCOME_UNKNOWN")?;
                let operation = uuid::Uuid::new_v4().to_string();
                auth.publish_security(generation, || {
                    *self.pending.lock().map_err(|_| "BUSY")? = Some(Pending {
                        operation: operation.clone(),
                        generation,
                        expires: Instant::now() + Duration::from_secs(enrollment.expires_in),
                    });
                    Ok(ResultView::Provisioning {
                        operation_id: operation,
                        secret,
                        expires_in: enrollment.expires_in,
                    })
                })
            }
            Input::Confirm {
                operation_id,
                password: p,
                proof: code,
            } => {
                if !password(p) || code.len() != 6 || !code.bytes().all(|b| b.is_ascii_digit()) {
                    return Err("VALIDATION_ERROR");
                }
                {
                    let mut pending = self.pending.lock().map_err(|_| "BUSY")?;
                    if !pending.as_ref().is_some_and(|v| {
                        v.operation == *operation_id
                            && v.generation == generation
                            && v.expires > Instant::now()
                    }) {
                        return Err("PRECONDITION_REQUIRED");
                    }
                    *pending = None;
                }
                auth.prepare_security_invalidation(generation)?;
                let result = write(
                    auth,
                    "auth/mfa/confirm",
                    serde_json::json!({"password":p,"proof":code}),
                    &access,
                )
                .await
                .and_then(|reply| {
                    let codes: Recovery = reply.parse()?;
                    let unique: std::collections::HashSet<_> =
                        codes.recovery_codes.iter().map(|v| v.as_str()).collect();
                    if !codes.reauthentication_required
                        || codes.recovery_codes.len() != 10
                        || unique.len() != 10
                        || !codes.recovery_codes.iter().all(|v| opaque(v, "rc_"))
                    {
                        return Err("INVALID_RESPONSE");
                    }
                    Ok(codes)
                });
                let account = auth.finish_security_invalidation(
                    generation,
                    result.as_ref().err().is_some_and(|e| uncertain(e)),
                    result.is_ok(),
                )?;
                let codes = result.map_err(|e| {
                    if uncertain(e) {
                        "SECURITY_OUTCOME_UNKNOWN"
                    } else {
                        e
                    }
                })?;
                Ok(ResultView::Recovery {
                    codes: codes.recovery_codes,
                    account,
                })
            }
            Input::Password {
                password: p,
                new_password: next,
                proof: code,
            } => {
                if !password(p)
                    || next.len() > 1024
                    || !(15..=128).contains(&next.chars().count())
                    || code.as_ref().is_some_and(|v| !proof(v))
                {
                    return Err("VALIDATION_ERROR");
                }
                if let Some(code) = code {
                    write(
                        auth,
                        "auth/step-up",
                        serde_json::json!({"password":p,"proof":code}),
                        &access,
                    )
                    .await
                    .map_err(|e| {
                        if uncertain(e) {
                            "SECURITY_OUTCOME_UNKNOWN"
                        } else {
                            e
                        }
                    })?;
                    if !auth.current(generation) {
                        return Err("CANCELLED");
                    }
                }
                auth.prepare_security_invalidation(generation)?;
                let result = write(
                    auth,
                    "auth/password",
                    serde_json::json!({"password":p,"new_password":next}),
                    &access,
                )
                .await;
                let account = auth.finish_security_invalidation(
                    generation,
                    result.as_ref().err().is_some_and(|e| uncertain(e)),
                    result.is_ok(),
                )?;
                result.map_err(|e| {
                    if uncertain(e) {
                        "SECURITY_OUTCOME_UNKNOWN"
                    } else {
                        e
                    }
                })?;
                Ok(ResultView::Completed { account })
            }
            Input::Revoke { session_id } => {
                if !id(session_id) {
                    return Err("VALIDATION_ERROR");
                }
                let own = session_id == &current;
                if own {
                    auth.prepare_security_invalidation(generation)?;
                }
                let result = write(
                    auth,
                    &format!("auth/sessions/{session_id}/revoke"),
                    serde_json::json!({}),
                    &access,
                )
                .await;
                if own {
                    let account = auth.finish_security_invalidation(
                        generation,
                        result.as_ref().err().is_some_and(|e| uncertain(e)),
                        result.is_ok(),
                    )?;
                    result.map_err(|e| {
                        if uncertain(e) {
                            "SECURITY_OUTCOME_UNKNOWN"
                        } else {
                            e
                        }
                    })?;
                    Ok(ResultView::Completed { account })
                } else {
                    if !auth.current(generation) {
                        return Err("CANCELLED");
                    }
                    result.map_err(|e| {
                        if uncertain(e) {
                            "SECURITY_OUTCOME_UNKNOWN"
                        } else {
                            e
                        }
                    })?;
                    Ok(ResultView::Completed {
                        account: auth.snapshot().await?,
                    })
                }
            }
            Input::CancelEnrollment => unreachable!(),
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn hostile_provisioning_and_session_scope_are_rejected() {
        let valid=format!("otpauth://totp/AnimeOnCraft:fixture?issuer=AnimeOnCraft&secret={}&algorithm=SHA1&digits=6&period=30","A".repeat(32));
        assert!(seed(&Enrollment {
            otpauth_uri: Zeroizing::new(valid.clone()),
            expires_in: 300
        })
        .is_ok());
        for uri in [
            valid.replace("otpauth:", "https:"),
            format!("{valid}&secret=bad"),
            valid.replace("issuer=AnimeOnCraft", "issuer=Other"),
            valid.replace("digits=6", "digits=8"),
        ] {
            assert!(seed(&Enrollment {
                otpauth_uri: Zeroizing::new(uri),
                expires_in: 300
            })
            .is_err());
        }
        assert!(serde_json::from_str::<Input>(
            r#"{"action":"revoke","session_id":"x","user_id":"x"}"#
        )
        .is_err());
        let session = id_fixture();
        let list = Sessions {
            items: vec![SessionView {
                id: session,
                client_kind: "LAUNCHER".into(),
                created_at: "2026-01-01T00:00:00Z".into(),
                absolute_expires_at: "2099-01-01T00:00:00Z".into(),
                current: true,
            }],
        };
        assert!(list.validate(&id_fixture()).is_err());
    }
    fn id_fixture() -> String {
        uuid::Uuid::new_v4().to_string()
    }
}
