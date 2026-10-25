//! OWNER password reset for a player from the launcher "Админка" (POST launcher/owner/password-reset).
//! The OWNER re-enters their own password and an MFA code on every reset; the backend re-checks both.
//! The temporary password is returned once to the WebView and never stored here.
use crate::account_client::{id, opaque, Error};
use crate::auth::Auth;
use reqwest::Method;
use serde::{Deserialize, Serialize};
use zeroize::{Zeroize, Zeroizing};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Input {
    login: String,
    password: String,
    proof: String,
}
impl Drop for Input {
    fn drop(&mut self) {
        self.password.zeroize();
        self.proof.zeroize();
    }
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Reset {
    user_id: String,
    login: String,
    game_name: Option<String>,
    delivery_email: String,
    temporary_password: Option<Zeroizing<String>>,
}
#[derive(Deserialize)]
struct Failure {
    error: FailureBody,
}
#[derive(Deserialize)]
struct FailureBody {
    code: String,
    #[serde(default)]
    message: String,
}

fn not_found(failure: Option<Failure>) -> Error {
    if failure.is_some_and(|f| f.error.code == "PLAYER_NOT_FOUND" || f.error.message == "Player not found") {
        "PLAYER_NOT_FOUND"
    } else {
        "OWNER_RESET_UNAVAILABLE"
    }
}

fn login(value: &str) -> bool {
    (3..=64).contains(&value.len())
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_.-".contains(&b))
}
fn proof(value: &str) -> bool {
    (value.len() == 6 && value.bytes().all(|b| b.is_ascii_digit())) || opaque(value, "rc_")
}
impl Input {
    fn validate(&self) -> Result<(), Error> {
        if login(self.login.trim())
            && !self.password.is_empty()
            && self.password.len() <= 1024
            && proof(self.proof.trim())
        {
            Ok(())
        } else {
            Err("VALIDATION_ERROR")
        }
    }
}
impl Reset {
    fn validate(&self) -> Result<(), Error> {
        let name_ok = self.game_name.as_ref().is_none_or(|name| {
            (1..=16).contains(&name.len())
                && name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
        });
        let password_ok = self.temporary_password.as_ref().is_some_and(|value| {
            (16..=64).contains(&value.len())
                && value
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b))
        });
        if id(&self.user_id)
            && login(&self.login)
            && name_ok
            && (3..=320).contains(&self.delivery_email.len())
            && self.delivery_email.contains('@')
            && !self.delivery_email.chars().any(char::is_control)
            && password_ok
        {
            Ok(())
        } else {
            Err("INVALID_RESPONSE")
        }
    }
}
fn forbidden(code: &str) -> Error {
    match code {
        "OWNER_PROOF_REJECTED" => "OWNER_PROOF_REJECTED",
        "OWNER_MFA_REQUIRED" => "OWNER_MFA_REQUIRED",
        "PASSWORD_CHANGE_REQUIRED" => "PASSWORD_CHANGE_REQUIRED",
        _ => "FORBIDDEN",
    }
}

pub async fn reset(auth: &Auth, input: Input) -> Result<Reset, Error> {
    input.validate()?;
    auth.ensure_session().await?;
    let (generation, access) = auth.access()?;
    let key = uuid::Uuid::new_v4().to_string();
    let body = serde_json::json!({
        "login": input.login.trim(),
        "password": input.password,
        "proof": input.proof.trim(),
        "reason": "Password reset by owner from launcher",
    });
    // A write: never replayed automatically. A lost reply is reported as unknown; a new reset is harmless.
    let reply = auth
        .client
        .request(
            Method::POST,
            "launcher/owner/password-reset",
            Some(body),
            Some(&access),
            Some((&key, None)),
        )
        .await
        .map_err(|error| {
            if error == "NETWORK_UNCERTAIN" {
                "SECURITY_OUTCOME_UNKNOWN"
            } else {
                error
            }
        })?;
    if !auth.current(generation) {
        return Err("CANCELLED");
    }
    match reply.status {
        200..=299 => {}
        403 => {
            return Err(reply
                .parse::<Failure>()
                .map(|f| forbidden(&f.error.code))
                .unwrap_or("FORBIDDEN"))
        }
        404 => return Err(not_found(reply.parse::<Failure>().ok())),
        409 => {
            if reply.parse::<Failure>().is_ok_and(|f| f.error.code == "AMBIGUOUS_PLAYER") {
                return Err("AMBIGUOUS_PLAYER");
            }
            return Err("RESOURCE_CONFLICT");
        }
        500.. => return Err("SECURITY_OUTCOME_UNKNOWN"),
        _ => reply.success()?,
    }
    let value = reply
        .parse::<Reset>()
        .map_err(|_| "SECURITY_OUTCOME_UNKNOWN")?;
    value.validate().map_err(|_| "SECURITY_OUTCOME_UNKNOWN")?;
    Ok(value)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn missing_route_is_not_reported_as_missing_player() {
        let player = serde_json::from_str(r#"{"error":{"code":"NOT_FOUND","message":"Player not found"}}"#).ok();
        assert_eq!(not_found(player), "PLAYER_NOT_FOUND");
        let route = serde_json::from_str(r#"{"error":{"code":"NOT_FOUND","message":"Cannot POST /api/v1/launcher/owner/password-reset"}}"#).ok();
        assert_eq!(not_found(route), "OWNER_RESET_UNAVAILABLE");
        assert_eq!(not_found(None), "OWNER_RESET_UNAVAILABLE");
    }
    fn input(login: &str, password: &str, proof: &str) -> Input {
        Input {
            login: login.into(),
            password: password.into(),
            proof: proof.into(),
        }
    }
    #[test]
    fn input_rules() {
        assert!(input("Player_1", "secret", "123456").validate().is_ok());
        assert!(input(" nick ", "secret", " 123456 ").validate().is_ok());
        assert!(input("ab", "secret", "123456").validate().is_err());
        assert!(input("bad login", "secret", "123456").validate().is_err());
        assert!(input("player", "", "123456").validate().is_err());
        assert!(input("player", "secret", "12345").validate().is_err());
        assert!(input("player", "secret", "abcdef").validate().is_err());
        assert!(serde_json::from_str::<Input>(
            r#"{"login":"p1x","password":"x","proof":"123456","user_id":"other"}"#
        )
        .is_err());
    }
    #[test]
    fn reply_rules() {
        let good = r#"{"user_id":"12345678-1234-4234-8234-123456789012","login":"player","game_name":"Nick_1","delivery_email":"p@example.com","temporary_password":"AbCdEfGhIjKlMnOpQrStUvWx"}"#;
        assert!(serde_json::from_str::<Reset>(good).unwrap().validate().is_ok());
        let replay = good.replace(r#""AbCdEfGhIjKlMnOpQrStUvWx""#, "null");
        assert!(serde_json::from_str::<Reset>(&replay)
            .unwrap()
            .validate()
            .is_err());
        let bad_mail = good.replace("p@example.com", "nobody");
        assert!(serde_json::from_str::<Reset>(&bad_mail)
            .unwrap()
            .validate()
            .is_err());
        assert!(serde_json::from_str::<Reset>(&good.replace("}", r#","extra":1}"#)).is_err());
        assert_eq!(forbidden("OWNER_PROOF_REJECTED"), "OWNER_PROOF_REJECTED");
        assert_eq!(forbidden("SOMETHING"), "FORBIDDEN");
    }
}
