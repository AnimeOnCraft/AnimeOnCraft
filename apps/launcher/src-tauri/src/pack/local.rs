//! Read-only UI state for fixed development storage; never install/launch authority.
use serde::Serialize;
use std::{path::Path, sync::Mutex};
pub const ENVIRONMENT: &str = "development-local-v1";
#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Status {
    Ready,
    Busy,
    Blocked,
    Unsupported,
}
#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
pub struct Snapshot {
    pub status: Status,
    pub environment: &'static str,
}
pub struct LocalInstance {
    inner: Mutex<Inner>,
}
struct Inner {
    status: Status,
    #[cfg(windows)]
    lifetime: Option<super::ntfs::provision::Lifetime>,
}
impl LocalInstance {
    #[cfg(windows)]
    pub(crate) fn update_journal(
        self: &std::sync::Arc<Self>,
        create: bool,
    ) -> Result<Option<super::UpdateJournal>, &'static str> {
        let mut inner = self.inner.lock().map_err(|_| "UPDATE_LOCAL_UNAVAILABLE")?;
        if inner.status != Status::Ready {
            return Err("UPDATE_LOCAL_UNAVAILABLE");
        }
        let lifetime = inner.lifetime.as_mut().ok_or("UPDATE_LOCAL_UNAVAILABLE")?;
        let mut result = lifetime.update_journal(create);
        if lifetime.validate().is_err() {
            inner.status = Status::Blocked;
            return Err("UPDATE_LOCAL_UNAVAILABLE");
        }
        if let Ok(Some(journal)) = &mut result {
            journal.hold_local(self.clone());
        }
        result
    }
    #[cfg(windows)]
    pub(crate) fn update_stage(&self, bytes: &[u8]) -> Result<super::UpdateStage, &'static str> {
        let mut inner = self.inner.lock().map_err(|_| "UPDATE_LOCAL_UNAVAILABLE")?;
        if inner.status != Status::Ready {
            return Err("UPDATE_LOCAL_UNAVAILABLE");
        }
        inner
            .lifetime
            .as_mut()
            .ok_or("UPDATE_LOCAL_UNAVAILABLE")?
            .update_stage(bytes)
    }
    #[cfg(windows)]
    pub(super) fn engine(&self, bootstrap: &[u8]) -> Result<super::ntfs::Engine, &'static str> {
        let mut inner = self.inner.lock().map_err(|_| "LOCAL_STORE_BLOCKED")?;
        if inner.status != Status::Ready {
            return Err("LOCAL_STORE_BLOCKED");
        }
        let lifetime = inner.lifetime.as_mut().ok_or("LOCAL_STORE_BLOCKED")?;
        let result = lifetime.engine(bootstrap);
        if lifetime.validate().is_err() {
            inner.status = Status::Blocked;
            return Err("LOCAL_STORE_BLOCKED");
        }
        result
    }
    #[cfg(all(test, windows))]
    pub(crate) fn backend_binding(&self, anchor: &str) -> Result<String, &'static str> {
        let mut inner = self.inner.lock().map_err(|_| "LOCAL_STORE_BLOCKED")?;
        if inner.status == Status::Busy {
            return Err("LOCAL_STORE_BUSY");
        }
        if inner.status != Status::Ready {
            return Err("LOCAL_STORE_BLOCKED");
        }
        let result = inner
            .lifetime
            .as_mut()
            .ok_or("LOCAL_STORE_BLOCKED")?
            .backend_binding(anchor);
        match result {
            Ok(binding) => Ok(binding),
            Err(_) => {
                inner.status = Status::Blocked;
                // The invalidated holder keeps its lease until every owner closes.
                Err("LOCAL_STORE_BLOCKED")
            }
        }
    }
    pub(crate) fn open(local_data: &Path) -> Self {
        #[cfg(windows)]
        let inner = match super::ntfs::provision::Lifetime::open(local_data) {
            Ok(lifetime) => Inner {
                status: Status::Ready,
                lifetime: Some(lifetime),
            },
            Err(status) => Inner {
                status,
                lifetime: None,
            },
        };
        #[cfg(not(windows))]
        let inner = {
            let _ = local_data;
            Inner {
                status: Status::Unsupported,
            }
        };
        Self {
            inner: Mutex::new(inner),
        }
    }
    pub(crate) fn unavailable() -> Self {
        Self {
            inner: Mutex::new(Inner {
                status: Status::Blocked,
                #[cfg(windows)]
                lifetime: None,
            }),
        }
    }
    pub fn snapshot(&self) -> Snapshot {
        let Ok(mut inner) = self.inner.lock() else {
            return Snapshot {
                status: Status::Blocked,
                environment: ENVIRONMENT,
            };
        };
        #[cfg(windows)]
        if inner
            .lifetime
            .as_mut()
            .is_some_and(|l| l.validate().is_err())
        {
            inner.status = Status::Blocked;
        }
        // A failed validation retains the lease; reading status never reprovisions/retries.
        Snapshot {
            status: inner.status,
            environment: ENVIRONMENT,
        }
    }
}
