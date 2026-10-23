use crate::{account_client::Error, auth::Auth};
use base64::{engine::general_purpose::STANDARD, Engine};
use reqwest::Method;
use serde::{Deserialize, Serialize};
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Input {
    pub png_base64: Option<String>,
    pub model: String,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Skin {
    pub png_base64: String,
    pub model: String,
    pub updated_at: String,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Snapshot {
    pub skin: Option<Skin>,
}
fn png(value: &str) -> bool {
    if value.len() > 175000 {
        return false;
    }
    STANDARD.decode(value).is_ok_and(|v| {
        v.len() >= 33
            && v.len() <= 131072
            && v[..8] == [137, 80, 78, 71, 13, 10, 26, 10]
            && v[12..16] == *b"IHDR"
            && v[16..20] == [0, 0, 0, 64]
            && v[20..24] == [0, 0, 0, 64]
    })
}
pub async fn request(auth: &Auth, input: Option<Input>) -> Result<Snapshot, Error> {
    if let Some(v) = &input {
        if !["classic", "slim"].contains(&v.model.as_str())
            || v.png_base64.as_ref().is_some_and(|s| !png(s))
        {
            return Err("SKIN_INVALID");
        }
    }
    auth.ensure_session().await?;
    let mut retried = false;
    loop {
        let (generation, access) = auth.access()?;
        let body = input
            .as_ref()
            .map(|v| serde_json::json!({"png_base64":v.png_base64,"model":v.model}));
        let reply = auth
            .client
            .request(
                if input.is_some() {
                    Method::POST
                } else {
                    Method::GET
                },
                "auth/me/skin",
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
        if reply.status == 400 {
            return Err("SKIN_INVALID");
        }
        reply.success()?;
        let result = reply.parse::<Snapshot>()?;
        if let Some(v) = &result.skin {
            if !png(&v.png_base64)
                || !["classic", "slim"].contains(&v.model.as_str())
                || chrono::DateTime::parse_from_rfc3339(&v.updated_at).is_err()
            {
                return Err("INVALID_RESPONSE");
            }
        }
        return auth.publish_security(generation, || Ok(result));
    }
}
