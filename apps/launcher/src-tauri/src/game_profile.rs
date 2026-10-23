use crate::account_client::Error;
use crate::auth::Auth;
use reqwest::Method;
use serde::{Deserialize, Serialize};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Input {
    pub game_name: String,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Profile {
    pub game_name: String,
    pub minecraft_uuid: String,
    pub created_at: String,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Snapshot {
    pub profile: Option<Profile>,
}

pub fn valid_name(value: &str) -> bool {
    (3..=16).contains(&value.len())
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_')
}

fn validate(value: &Snapshot) -> Result<(), Error> {
    if let Some(profile) = &value.profile {
        if !valid_name(&profile.game_name)
            || !uuid::Uuid::parse_str(&profile.minecraft_uuid)
                .is_ok_and(|v| v.get_version_num() == 3 && v.to_string() == profile.minecraft_uuid)
            || chrono::DateTime::parse_from_rfc3339(&profile.created_at).is_err()
        {
            return Err("INVALID_RESPONSE");
        }
    }
    Ok(())
}

pub async fn read(auth: &Auth) -> Result<Snapshot, Error> {
    request(auth, None).await
}

pub async fn create(auth: &Auth, input: Input) -> Result<Snapshot, Error> {
    if !valid_name(&input.game_name) {
        return Err("GAME_NAME_INVALID");
    }
    request(auth, Some(input.game_name)).await
}

async fn request(auth: &Auth, name: Option<String>) -> Result<Snapshot, Error> {
    auth.ensure_session().await?;
    let mut retried = false;
    loop {
        let (generation, access) = auth.access()?;
        let body = name
            .as_ref()
            .map(|name| serde_json::json!({"game_name": name}));
        let reply = auth
            .client
            .request(
                if name.is_some() {
                    Method::POST
                } else {
                    Method::GET
                },
                "auth/me/game-profile",
                body,
                Some(&access),
                None,
            )
            .await?;
        if !auth.current(generation) {
            return Err("CANCELLED");
        }
        if reply.status == 401 && !retried {
            retried = true;
            auth.expired(generation, &access);
            auth.ensure_session().await?;
            continue;
        }
        if reply.status == 409 {
            return Err("GAME_NAME_CONFLICT");
        }
        reply.success()?;
        let result = reply.parse::<Snapshot>()?;
        validate(&result)?;
        if let Some(expected) = &name {
            if result
                .profile
                .as_ref()
                .is_none_or(|v| v.game_name != *expected)
            {
                return Err("INVALID_RESPONSE");
            }
        }
        return auth.publish_security(generation, || Ok(result));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_names_that_would_change_identity_or_escape_input() {
        for value in [
            "ab",
            "abcdefghijklmnopq",
            " Игрок",
            "Player\n",
            "Player.Name",
            "Player-Name",
            "Ｐlayer",
        ] {
            assert!(!valid_name(value));
        }
        assert!(valid_name("Friend_123"));
        assert!(
            serde_json::from_str::<Input>(r#"{"game_name":"Friend","user_id":"other"}"#).is_err()
        );
    }

    #[test]
    fn accepts_only_canonical_offline_uuid_and_known_fields() {
        let valid = r#"{"profile":{"game_name":"Notch","minecraft_uuid":"b50ad385-829d-3141-a216-7e7d7539ba7f","created_at":"2026-10-15T00:00:00.000Z"}}"#;
        let parsed: Snapshot = serde_json::from_str(valid).unwrap();
        assert!(validate(&parsed).is_ok());
        for bad in [
            valid.replace("-3141-", "-4141-"),
            valid.replace("Notch", "Notch."),
            valid.replace("2026-10-15T00:00:00.000Z", "yesterday"),
        ] {
            assert!(validate(&serde_json::from_str::<Snapshot>(&bad).unwrap()).is_err());
        }
        assert!(
            serde_json::from_str::<Snapshot>(r#"{"profile":null,"access_token":"secret"}"#)
                .is_err()
        );
        assert!(validate(&Snapshot { profile: None }).is_ok());
    }
}
