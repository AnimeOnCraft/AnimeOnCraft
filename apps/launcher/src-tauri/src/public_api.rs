use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::time::Duration;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Resource {
    Ready,
    Compatibility,
    Clans,
    Clan,
    ClanProgression,
    ClanCompetitions,
    ClanWars,
    News,
    Article,
    WorldMap,
    WorldPoints,
    WorldEvents,
    WorldRelief,
}
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PublicInput {
    pub resource: Resource,
    pub key: Option<String>,
    pub cursor: Option<String>,
}
#[derive(Serialize)]
pub struct PublicReply {
    status: u16,
    body: Value,
}
pub struct PublicClient {
    client: reqwest::Client,
}
fn valid_uuid(value: &str) -> bool {
    value.len() == 36
        && value.bytes().enumerate().all(|(position, character)| {
            if [8, 13, 18, 23].contains(&position) {
                character == b'-'
            } else {
                character.is_ascii_hexdigit()
            }
        })
}
fn valid_slug(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 100
        && !value.starts_with('-')
        && !value.ends_with('-')
        && !value.contains("--")
        && value
            .bytes()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-')
}
fn path(input: &PublicInput) -> Result<String, &'static str> {
    let base = match input.resource {
        Resource::Ready => "health/ready",
        Resource::Compatibility => "launcher/compatibility",
        Resource::Clans
        | Resource::Clan
        | Resource::ClanProgression
        | Resource::ClanCompetitions
        | Resource::ClanWars => "clans",
        Resource::News | Resource::Article => "news",
        Resource::WorldMap => "world/map",
        Resource::WorldPoints => "world/points",
        Resource::WorldEvents => "world/events",
        Resource::WorldRelief => "world/relief",
    };
    let detail = matches!(
        input.resource,
        Resource::Clan
            | Resource::ClanProgression
            | Resource::ClanCompetitions
            | Resource::ClanWars
            | Resource::Article
    );
    let key = if detail {
        let key = input.key.as_deref().ok_or("INVALID_PUBLIC_REQUEST")?;
        if !(if matches!(
            input.resource,
            Resource::Clan
                | Resource::ClanProgression
                | Resource::ClanCompetitions
                | Resource::ClanWars
        ) {
            valid_uuid(key)
        } else {
            valid_slug(key)
        }) {
            return Err("INVALID_PUBLIC_REQUEST");
        }
        if matches!(input.resource, Resource::ClanProgression) {
            format!("/{key}/progression")
        } else if matches!(input.resource, Resource::ClanCompetitions) {
            format!("/{key}/competitions")
        } else if matches!(input.resource, Resource::ClanWars) {
            format!("/{key}/wars")
        } else {
            format!("/{key}")
        }
    } else {
        if input.key.is_some() {
            return Err("INVALID_PUBLIC_REQUEST");
        }
        String::new()
    };
    if let Some(cursor) = &input.cursor {
        if !matches!(input.resource, Resource::Clans | Resource::News)
            || cursor.is_empty()
            || cursor.len() > 512
        {
            return Err("INVALID_PUBLIC_REQUEST");
        }
    }
    Ok(format!(
        "{}/api/v1/{base}{key}",
        crate::deployment::public_origin()?
    ))
}
impl PublicClient {
    pub fn new() -> Result<Self, reqwest::Error> {
        Ok(Self {
            client: reqwest::Client::builder()
                .no_proxy()
                .redirect(reqwest::redirect::Policy::none())
                .timeout(Duration::from_secs(5))
                .connect_timeout(Duration::from_secs(2))
                .build()?,
        })
    }
    pub async fn read(&self, input: PublicInput) -> Result<PublicReply, &'static str> {
        // The relief underlay is one base64 image (≤ 3 MiB raw); every other response stays small.
        let relief = matches!(input.resource, Resource::WorldRelief);
        let limit: usize = if relief { 4_718_592 } else { 524_288 };
        let mut request = self.client.get(path(&input)?);
        if relief {
            request = request.timeout(Duration::from_secs(30));
        }
        if matches!(input.resource, Resource::Clans | Resource::News) {
            request = request.query(&[("limit", "20")]);
            if let Some(cursor) = &input.cursor {
                request = request.query(&[("cursor", cursor)]);
            }
        }
        let mut response = request
            .send()
            .await
            .map_err(|_| "PUBLIC_SERVICE_UNAVAILABLE")?;
        let status = response.status().as_u16();
        if status != 200 && !(matches!(input.resource, Resource::Ready) && status == 503) {
            return Ok(PublicReply {
                status,
                body: Value::Null,
            });
        }
        let content_type = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        if !content_type.starts_with("application/json")
            || response
                .content_length()
                .is_some_and(|size| size > limit as u64)
        {
            return Err("INVALID_PUBLIC_RESPONSE");
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|_| "PUBLIC_SERVICE_UNAVAILABLE")?
        {
            if bytes.len() + chunk.len() > limit {
                return Err("INVALID_PUBLIC_RESPONSE");
            }
            bytes.extend_from_slice(&chunk);
        }
        let body = serde_json::from_slice(&bytes).map_err(|_| "INVALID_PUBLIC_RESPONSE")?;
        Ok(PublicReply { status, body })
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_public_fixed_routes() {
        for (resource, suffix) in [
            (Resource::ClanProgression, "progression"),
            (Resource::ClanCompetitions, "competitions"),
            (Resource::ClanWars, "wars"),
        ] {
            let route = path(&PublicInput {
                resource,
                key: Some("11111111-1111-4111-8111-111111111111".into()),
                cursor: None,
            })
            .unwrap();
            assert!(route.ends_with(&format!(
                "/clans/11111111-1111-4111-8111-111111111111/{suffix}"
            )));
        }
        for key in [
            "../auth/me",
            "https://example.org",
            "foo?token=x",
            "foo/bar",
            "--",
            "bad_slug",
        ] {
            assert!(path(&PublicInput {
                resource: Resource::Article,
                key: Some(key.into()),
                cursor: None
            })
            .is_err());
        }
        assert_eq!(
            path(&PublicInput {
                resource: Resource::Article,
                key: Some("first-news".into()),
                cursor: None
            })
            .unwrap(),
            "http://127.0.0.1:3000/api/v1/news/first-news"
        );
        assert!(path(&PublicInput {
            resource: Resource::Clan,
            key: Some("not-a-uuid".into()),
            cursor: None
        })
        .is_err());
        assert!(path(&PublicInput {
            resource: Resource::Ready,
            key: None,
            cursor: Some("x".into())
        })
        .is_err());
        assert!(path(&PublicInput {
            resource: Resource::WorldMap,
            key: None,
            cursor: None
        })
        .unwrap()
        .ends_with("/api/v1/world/map"));
        assert!(path(&PublicInput {
            resource: Resource::WorldPoints,
            key: None,
            cursor: None
        })
        .unwrap()
        .ends_with("/api/v1/world/points"));
        assert!(path(&PublicInput {
            resource: Resource::WorldEvents,
            key: None,
            cursor: None
        })
        .unwrap()
        .ends_with("/api/v1/world/events"));
        assert!(path(&PublicInput {
            resource: Resource::WorldRelief,
            key: None,
            cursor: None,
        })
        .unwrap()
        .ends_with("/api/v1/world/relief"));
        for (resource, key, cursor) in [
            (
                Resource::WorldMap,
                Some("11111111-1111-4111-8111-111111111111".to_string()),
                None,
            ),
            (Resource::WorldPoints, None, Some("next".to_string())),
            (Resource::WorldEvents, None, Some("next".to_string())),
        ] {
            assert!(path(&PublicInput {
                resource,
                key,
                cursor
            })
            .is_err());
        }
        assert!(serde_json::from_str::<PublicInput>("{\"resource\":\"world_map\"}").is_ok());
        assert!(serde_json::from_str::<PublicInput>("{\"resource\":\"auth\"}").is_err());
        assert!(serde_json::from_str::<PublicInput>(
            "{\"resource\":\"news\",\"url\":\"https://example.org\"}"
        )
        .is_err());
    }
}
