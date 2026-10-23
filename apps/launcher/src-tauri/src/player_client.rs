use crate::account_client::{id, Error};
use crate::auth::Auth;
use reqwest::Method;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

#[derive(Deserialize)]
#[serde(tag = "resource", rename_all = "snake_case", deny_unknown_fields)]
pub enum ReadInput {
    Access,
    Roster {
        clan_id: String,
        cursor: Option<String>,
    },
    OwnerClans,
    OwnerApplications {
        clan_id: String,
        cursor: Option<String>,
    },
    Clan,
    Applications {
        cursor: Option<String>,
    },
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Membership {
    pub id: String,
    pub user_id: String,
    pub clan_id: String,
    pub joined_at: String,
    pub version: String,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Clan {
    pub id: String,
    pub name: String,
    pub short_code: String,
    pub description: String,
    pub discord_url: Option<String>,
    pub logo_url: Option<String>,
    pub enabled: bool,
    pub archived_at: Option<String>,
    pub version: String,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Application {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub nickname: Option<String>,
    pub id: String,
    pub user_id: String,
    pub clan_id: String,
    pub message: String,
    pub status: String,
    pub version: String,
    pub created_at: String,
    pub decided_at: Option<String>,
    pub reason: Option<String>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Applications {
    pub items: Vec<Application>,
    pub next_cursor: Option<String>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct OwnClan {
    pub clan: Option<Clan>,
    pub membership: Option<Membership>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Access {
    pub effective_access: String,
    pub reason: String,
    pub access_epoch: String,
    pub membership: Option<Membership>,
    pub sync_status: String,
    pub checked_at: String,
    pub game_admission_available: bool,
}
fn version(value: &str) -> bool {
    value
        .parse::<u64>()
        .is_ok_and(|v| v <= i64::MAX as u64 && v.to_string() == value)
}
fn membership(value: &Membership) -> bool {
    id(&value.id) && id(&value.user_id) && id(&value.clan_id) && version(&value.version)
}
pub async fn read(auth: &Auth, input: ReadInput) -> Result<Value, Error> {
    let route = match &input {
        ReadInput::OwnerClans => "launcher/owner/clans".into(),
        ReadInput::OwnerApplications { clan_id, cursor } => {
            if !id(clan_id) {
                return Err("VALIDATION_ERROR");
            }
            let mut url =
                reqwest::Url::parse("http://127.0.0.1/").map_err(|_| "VALIDATION_ERROR")?;
            url.query_pairs_mut().append_pair("limit", "20");
            if let Some(cursor) = cursor {
                if cursor.is_empty()
                    || cursor.len() > 2048
                    || !cursor
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b"_-.".contains(&b))
                {
                    return Err("VALIDATION_ERROR");
                }
                url.query_pairs_mut().append_pair("cursor", cursor);
            }
            format!(
                "launcher/owner/clans/{clan_id}/applications?{}",
                url.query().unwrap_or("")
            )
        }
        ReadInput::Roster { clan_id, cursor } => {
            if !id(clan_id)
                || cursor.as_ref().is_some_and(|value| {
                    value.is_empty()
                        || value.len() > 512
                        || !value
                            .bytes()
                            .all(|b| b.is_ascii_alphanumeric() || b"_-.".contains(&b))
                })
            {
                return Err("VALIDATION_ERROR");
            }
            let mut query =
                reqwest::Url::parse("http://127.0.0.1/").map_err(|_| "VALIDATION_ERROR")?;
            query.query_pairs_mut().append_pair("limit", "20");
            if let Some(cursor) = cursor {
                query.query_pairs_mut().append_pair("cursor", cursor);
            }
            format!("clans/{clan_id}/members?{}", query.query().unwrap_or(""))
        }
        ReadInput::Access => "me/access".into(),
        ReadInput::Clan => "me/clan".into(),
        ReadInput::Applications { cursor } => {
            let mut url = reqwest::Url::parse("http://127.0.0.1/me/applications")
                .map_err(|_| "VALIDATION_ERROR")?;
            url.query_pairs_mut().append_pair("limit", "20");
            if let Some(cursor) = cursor {
                if cursor.is_empty()
                    || cursor.len() > 2048
                    || !cursor
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-' || b == b'.')
                {
                    return Err("VALIDATION_ERROR");
                }
                url.query_pairs_mut().append_pair("cursor", cursor);
            }
            format!("me/applications?{}", url.query().unwrap_or(""))
        }
    };
    auth.ensure_session().await?;
    let mut retried = false;
    let reply = loop {
        let (generation, access) = auth.access()?;
        let reply = auth
            .client
            .request(Method::GET, &route, None, Some(&access), None)
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
        break reply;
    };
    let output = match input {
        ReadInput::OwnerClans => {
            #[derive(Serialize, Deserialize)]
            #[serde(deny_unknown_fields)]
            struct Clans {
                items: Vec<Clan>,
            }
            let value = reply.parse::<Clans>()?;
            if value.items.len() > 100
                || value
                    .items
                    .iter()
                    .any(|c| !id(&c.id) || !version(&c.version))
            {
                return Err("INVALID_RESPONSE");
            }
            serde_json::to_value(value)
        }
        ReadInput::Roster { .. } => {
            #[derive(Serialize, Deserialize)]
            #[serde(deny_unknown_fields)]
            struct Member {
                nickname: Option<String>,
                joined_at: String,
                role: String,
                online: Option<bool>,
                online_updated_at: Option<String>,
            }
            #[derive(Serialize, Deserialize)]
            #[serde(deny_unknown_fields)]
            struct Roster {
                items: Vec<Member>,
                next_cursor: Option<String>,
            }
            let value = reply.parse::<Roster>()?;
            if value.items.len() > 20
                || value
                    .next_cursor
                    .as_ref()
                    .is_some_and(|cursor| cursor.len() > 512)
                || value.items.iter().any(|item| {
                    item.nickname.as_ref().is_some_and(|name| {
                        name.len() > 16
                            || !name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
                    }) || !["MEMBER", "MANAGER"].contains(&item.role.as_str())
                        || chrono::DateTime::parse_from_rfc3339(&item.joined_at).is_err()
                        || item
                            .online_updated_at
                            .as_ref()
                            .is_some_and(|date| chrono::DateTime::parse_from_rfc3339(date).is_err())
                        || item.online.is_some() != item.online_updated_at.is_some()
                })
            {
                return Err("INVALID_RESPONSE");
            }
            serde_json::to_value(value)
        }
        ReadInput::Access => {
            let value = reply.parse::<Access>()?;
            if !["ALLOWED", "DENIED"].contains(&value.effective_access.as_str())
                || ![
                    "ALLOWED",
                    "BANNED",
                    "GAME_PROFILE_REQUIRED",
                    "GRANT_REQUIRED",
                ]
                .contains(&value.reason.as_str())
                || !version(&value.access_epoch)
                || value.game_admission_available
                || value.membership.as_ref().is_some_and(|v| !membership(v))
            {
                return Err("INVALID_RESPONSE");
            }
            serde_json::to_value(value)
        }
        ReadInput::Clan => {
            let value = reply.parse::<OwnClan>()?;
            if value.membership.as_ref().is_some_and(|v| !membership(v))
                || value
                    .clan
                    .as_ref()
                    .is_some_and(|v| !id(&v.id) || !version(&v.version) || v.name.len() > 1000)
            {
                return Err("INVALID_RESPONSE");
            }
            serde_json::to_value(value)
        }
        ReadInput::Applications { .. } | ReadInput::OwnerApplications { .. } => {
            let value = reply.parse::<Applications>()?;
            if value.items.len() > 20
                || value.items.iter().any(|v| {
                    !id(&v.id)
                        || !id(&v.user_id)
                        || !id(&v.clan_id)
                        || !version(&v.version)
                        || v.message.len() > 16000
                        || !["PENDING", "ACCEPTED", "REJECTED", "WITHDRAWN", "CANCELLED"]
                            .contains(&v.status.as_str())
                })
            {
                return Err("INVALID_RESPONSE");
            }
            serde_json::to_value(value)
        }
    };
    output.map_err(|_| "INVALID_RESPONSE")
}
#[derive(Clone, Deserialize)]
#[serde(tag = "action", rename_all = "snake_case", deny_unknown_fields)]
pub enum Action {
    OwnerJoin {
        clan_id: String,
        version: String,
    },
    OwnerApprove {
        application_id: String,
        version: String,
    },
    OwnerReject {
        application_id: String,
        version: String,
    },
    Apply {
        clan_id: String,
        message: String,
    },
    Withdraw {
        application_id: String,
        version: String,
        reason: String,
    },
    Leave {
        version: String,
        reason: String,
    },
}
impl Action {
    fn validate(&self) -> Result<(), Error> {
        let valid = match self {
            Self::OwnerJoin {
                clan_id,
                version: v,
            } => id(clan_id) && version(v),
            Self::OwnerApprove {
                application_id,
                version: v,
            }
            | Self::OwnerReject {
                application_id,
                version: v,
            } => id(application_id) && version(v),
            Self::Apply { clan_id, message } => id(clan_id) && message.chars().count() <= 4000,
            Self::Withdraw {
                application_id,
                version: v,
                reason,
            } => {
                id(application_id)
                    && version(v)
                    && (1..=1000).contains(&reason.trim().chars().count())
            }
            Self::Leave { version: v, reason } => {
                version(v) && (1..=1000).contains(&reason.trim().chars().count())
            }
        };
        if valid {
            Ok(())
        } else {
            Err("VALIDATION_ERROR")
        }
    }
    fn request(&self) -> (String, Value, Option<&str>) {
        match self {
            Self::OwnerJoin { clan_id, version } => (
                format!("launcher/owner/clans/{clan_id}/join"),
                serde_json::json!({"reason":"Owner joined from launcher"}),
                Some(version),
            ),
            Self::OwnerApprove {
                application_id,
                version,
            } => (
                format!("launcher/owner/applications/{application_id}/approve"),
                serde_json::json!({"reason":"Approved by owner from launcher"}),
                Some(version),
            ),
            Self::OwnerReject {
                application_id,
                version,
            } => (
                format!("launcher/owner/applications/{application_id}/reject"),
                serde_json::json!({"reason":"Rejected by owner from launcher"}),
                Some(version),
            ),
            Self::Apply { clan_id, message } => (
                format!("clans/{clan_id}/applications"),
                serde_json::json!({"message":message}),
                None,
            ),
            Self::Withdraw {
                application_id,
                version,
                reason,
            } => (
                format!("me/applications/{application_id}/withdraw"),
                serde_json::json!({"reason":reason}),
                Some(version),
            ),
            Self::Leave { version, reason } => (
                "me/clan/leave".into(),
                serde_json::json!({"reason":reason}),
                Some(version),
            ),
        }
    }
}
struct Pending {
    action: Action,
    generation: u64,
    expires: Instant,
    status: &'static str,
}
pub struct PlayerActions(Mutex<HashMap<String, Pending>>);
#[derive(Serialize)]
pub struct ActionResult {
    pub operation_id: String,
    pub status: &'static str,
    pub error: Option<Error>,
}
impl PlayerActions {
    pub fn new() -> Self {
        Self(Mutex::new(HashMap::new()))
    }
    pub fn prepare(&self, auth: &Auth, action: Action) -> Result<ActionResult, Error> {
        action.validate()?;
        let (generation, _) = auth.access()?;
        let mut actions = self.0.lock().map_err(|_| "BUSY")?;
        actions.retain(|_, v| v.expires > Instant::now() && v.generation == generation);
        if actions.len() >= 32 {
            return Err("BUSY");
        }
        let operation_id = uuid::Uuid::new_v4().to_string();
        actions.insert(
            operation_id.clone(),
            Pending {
                action,
                generation,
                expires: Instant::now() + Duration::from_secs(86400),
                status: "PREPARED",
            },
        );
        Ok(ActionResult {
            operation_id,
            status: "PREPARED",
            error: None,
        })
    }
    pub async fn submit(
        &self,
        auth: &Auth,
        operation_id: String,
        retry: bool,
    ) -> Result<ActionResult, Error> {
        let (action, generation) = {
            let mut actions = self.0.lock().map_err(|_| "BUSY")?;
            let pending = actions.get_mut(&operation_id).ok_or("NOT_FOUND")?;
            if pending.expires <= Instant::now() || !auth.current(pending.generation) {
                return Err("AUTHENTICATION_REQUIRED");
            }
            if pending.status != if retry { "UNKNOWN_OUTCOME" } else { "PREPARED" } {
                return Err("BUSY");
            }
            pending.status = "SUBMITTING";
            (pending.action.clone(), pending.generation)
        };
        // Refresh BEFORE submission is allowed; a write is never automatically replayed after401.
        let result = async {
            auth.ensure_session().await?;
            if !auth.current(generation) {
                return Err("CANCELLED");
            }
            let (_, access) = auth.access()?;
            let (route, body, version) = action.request();
            let reply = auth
                .client
                .request(
                    Method::POST,
                    &route,
                    Some(body),
                    Some(&access),
                    Some((&operation_id, version)),
                )
                .await?;
            if reply.status >= 500 {
                return Err("NETWORK_UNCERTAIN");
            }
            reply.success()?;
            Ok(())
        }
        .await;
        let (status, error) = match result {
            Ok(()) => ("COMPLETED", None),
            Err(error @ ("NETWORK_UNCERTAIN" | "INVALID_RESPONSE")) => {
                ("UNKNOWN_OUTCOME", Some(error))
            }
            Err(error) => ("REJECTED", Some(error)),
        };
        let mut actions = self.0.lock().map_err(|_| "BUSY")?;
        if let Some(pending) = actions.get_mut(&operation_id) {
            pending.status = status;
        }
        if !auth.current(generation) {
            return Err("CANCELLED");
        }
        Ok(ActionResult {
            operation_id,
            status,
            error,
        })
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn optional_about_and_owner_actions() {
        let clan_id = "12345678-1234-4234-8234-123456789012".to_owned();
        for message in ["", "Hi", "Привет"] {
            assert!(Action::Apply {
                clan_id: clan_id.clone(),
                message: message.into()
            }
            .validate()
            .is_ok());
        }
        assert!(Action::Apply {
            clan_id: clan_id.clone(),
            message: "x".repeat(4001)
        }
        .validate()
        .is_err());
        let join = Action::OwnerJoin {
            clan_id: clan_id.clone(),
            version: "0".into(),
        };
        assert!(join.validate().is_ok());
        assert_eq!(
            join.request().0,
            format!("launcher/owner/clans/{clan_id}/join")
        );
        assert!(serde_json::from_str::<Action>(r#"{"action":"owner_join","clan_id":"12345678-1234-4234-8234-123456789012","version":"0","user_id":"someone"}"#).is_err());
    }
    #[test]
    fn action_scope_and_versions() {
        assert!(Action::Leave {
            version: "-1".into(),
            reason: "x".into()
        }
        .validate()
        .is_err());
        assert!(serde_json::from_str::<Action>(
            r#"{"action":"apply","clan_id":"x","message":"x","user_id":"x"}"#
        )
        .is_err());
        assert!(serde_json::from_str::<ReadInput>(r#"{"resource":"management"}"#).is_err());
    }
}
