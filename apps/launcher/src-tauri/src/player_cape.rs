use crate::{account_client::Error, auth::Auth};
use reqwest::Method;
use serde::{Deserialize, Serialize};
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Input {
    pub cape_id: Option<String>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Cape {
    pub id: String,
    pub name: String,
    pub awarded_at: String,
    pub description: String,
    pub preview_png_base64: String,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Beta {
    pub starts_at: Option<String>,
    pub ends_at: Option<String>,
    pub active: bool,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Snapshot {
    pub capes: Vec<Cape>,
    pub equipped: Option<String>,
    pub beta: Beta,
}
pub async fn request(auth: &Auth, input: Option<Input>) -> Result<Snapshot, Error> {
    if input
        .as_ref()
        .and_then(|v| v.cape_id.as_deref())
        .is_some_and(|v| !valid_id(v))
    {
        return Err("CAPE_INVALID");
    }
    auth.ensure_session().await?;
    let mut retried = false;
    loop {
        let (generation, access) = auth.access()?;
        let body = input
            .as_ref()
            .map(|v| serde_json::json!({"cape_id":v.cape_id}));
        let reply = auth
            .client
            .request(
                if input.is_some() {
                    Method::POST
                } else {
                    Method::GET
                },
                "auth/me/capes/wardrobe",
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
        reply.success()?;
        let result = reply.parse::<Snapshot>()?;
        if result.capes.len() > 32
            || result.capes.iter().any(|c| {
                !valid_id(&c.id)
                    || c.name.is_empty()
                    || c.name.chars().count() > 80
                    || c.description.chars().count() > 240
                    || !valid_preview(&c.preview_png_base64)
                    || chrono::DateTime::parse_from_rfc3339(&c.awarded_at).is_err()
            })
            || result
                .equipped
                .as_ref()
                .is_some_and(|id| !result.capes.iter().any(|c| &c.id == id))
            || result
                .beta
                .starts_at
                .as_ref()
                .is_some_and(|v| chrono::DateTime::parse_from_rfc3339(v).is_err())
            || result
                .beta
                .ends_at
                .as_ref()
                .is_some_and(|v| chrono::DateTime::parse_from_rfc3339(v).is_err())
        {
            return Err("INVALID_RESPONSE");
        }
        return auth.publish_security(generation, || Ok(result));
    }
}

fn valid_id(value: &str) -> bool {
    value.len() >= 1
        && value.len() <= 32
        && value.as_bytes()[0].is_ascii_lowercase()
        && value
            .bytes()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-')
}
fn valid_preview(value: &str) -> bool {
    use base64::{engine::general_purpose::STANDARD, Engine};
    value.len() <= 133336
        && STANDARD.decode(value).is_ok_and(|v| {
            v.len() >= 33
                && v.len() <= 100000
                && v[..8] == [137, 80, 78, 71, 13, 10, 26, 10]
                && v[12..16] == *b"IHDR"
                && v[16..20] == 160u32.to_be_bytes()
                && v[20..24] == 256u32.to_be_bytes()
        })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn personal_ids_allow_catalog_names_and_reject_paths() {
        assert!(valid_id("beta"));
        assert!(valid_id("ayaa-cobblestone"));
        for id in ["", "../beta", "https://evil", "Beta", "a/b"] {
            assert!(!valid_id(id));
        }
    }
    #[test]
    fn preview_rejects_bad_data() {
        assert!(!valid_preview("not an image"));
    }
}
