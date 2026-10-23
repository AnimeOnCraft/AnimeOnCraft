use crate::account_client::{id, opaque, Error, ENVIRONMENT};
use serde::{Deserialize, Serialize};
use zeroize::Zeroizing;

#[derive(Serialize, Deserialize)]
#[serde(
    tag = "state",
    rename_all = "SCREAMING_SNAKE_CASE",
    deny_unknown_fields
)]
pub enum Record {
    Ready {
        format: u8,
        environment: String,
        session_id: String,
        absolute_expires_at: String,
        refresh_token: Zeroizing<String>,
    },
    Pending {
        format: u8,
        environment: String,
        operation_id: String,
    },
    LoggedOut {
        format: u8,
        environment: String,
    },
}
impl Record {
    pub fn pending() -> Self {
        Self::Pending {
            format: 1,
            environment: ENVIRONMENT.into(),
            operation_id: uuid::Uuid::new_v4().to_string(),
        }
    }
    pub fn logged_out() -> Self {
        Self::LoggedOut {
            format: 1,
            environment: ENVIRONMENT.into(),
        }
    }
    pub fn validate(&self) -> Result<(), Error> {
        let (format, env) = match self {
            Self::Ready {
                format,
                environment,
                ..
            }
            | Self::Pending {
                format,
                environment,
                ..
            }
            | Self::LoggedOut {
                format,
                environment,
            } => (*format, environment),
        };
        if format != 1 || env != ENVIRONMENT {
            return Err("STORAGE_UNAVAILABLE");
        }
        match self {
            Self::Ready {
                session_id,
                absolute_expires_at,
                refresh_token,
                ..
            } => {
                if !id(session_id) || !opaque(refresh_token, "ar_") {
                    return Err("STORAGE_UNAVAILABLE");
                }
                let expires = chrono::DateTime::parse_from_rfc3339(absolute_expires_at)
                    .map_err(|_| "STORAGE_UNAVAILABLE")?;
                if expires.timestamp() <= chrono::Utc::now().timestamp() {
                    Err("REAUTH_REQUIRED")
                } else {
                    Ok(())
                }
            }
            Self::Pending { operation_id, .. } if !id(operation_id) => Err("STORAGE_UNAVAILABLE"),
            _ => Ok(()),
        }
    }
}
pub struct CredentialStore {
    target: String,
}
impl CredentialStore {
    pub fn development() -> Self {
        Self {
            target: format!("AnimeOnCraft/{ENVIRONMENT}/account-v1"),
        }
    }
    #[cfg(test)]
    pub fn fixture() -> Self {
        Self {
            target: format!("AnimeOnCraft/probe/{}", uuid::Uuid::new_v4().simple()),
        }
    }
    pub fn load(&self) -> Result<Option<Record>, Error> {
        let Some(bytes) = platform::read(&self.target)? else {
            return Ok(None);
        };
        let record: Record = serde_json::from_slice(&bytes).map_err(|_| "STORAGE_UNAVAILABLE")?;
        record.validate()?;
        Ok(Some(record))
    }
    pub fn save(&self, record: &Record) -> Result<(), Error> {
        record.validate()?;
        if let Some(existing) = platform::read(&self.target)? {
            let existing: Record =
                serde_json::from_slice(&existing).map_err(|_| "STORAGE_UNAVAILABLE")?;
            match existing.validate() {
                Ok(()) | Err("REAUTH_REQUIRED") => {}
                Err(error) => return Err(error),
            }
        }
        let bytes = Zeroizing::new(serde_json::to_vec(record).map_err(|_| "STORAGE_UNAVAILABLE")?);
        if bytes.len() > 2048 {
            return Err("STORAGE_UNAVAILABLE");
        }
        platform::write(&self.target, &bytes)?;
        let read = platform::read(&self.target)?.ok_or("STORAGE_UNAVAILABLE")?;
        if *read != *bytes {
            return Err("STORAGE_UNAVAILABLE");
        }
        Ok(())
    }
    #[cfg(test)]
    pub fn remove(&self) -> Result<(), Error> {
        platform::delete(&self.target)
    }
}
#[cfg(windows)]
mod platform {
    use super::*;
    use std::ptr::null_mut;
    use windows_sys::Win32::{
        Foundation::{GetLastError, ERROR_NOT_FOUND},
        Security::Credentials::*,
    };
    use zeroize::Zeroize;
    fn wide(value: &str) -> Vec<u16> {
        value.encode_utf16().chain(Some(0)).collect()
    }
    pub fn read(target: &str) -> Result<Option<Zeroizing<Vec<u8>>>, Error> {
        let target = wide(target);
        let mut pointer: *mut CREDENTIALW = null_mut();
        // Windows owns the returned allocation; it is always released by CredFree.
        unsafe {
            if CredReadW(target.as_ptr(), CRED_TYPE_GENERIC, 0, &mut pointer) == 0 {
                return if GetLastError() == ERROR_NOT_FOUND {
                    Ok(None)
                } else {
                    Err("STORAGE_UNAVAILABLE")
                };
            }
            let value = &*pointer;
            let valid = value.Type == CRED_TYPE_GENERIC
                && value.Persist == CRED_PERSIST_LOCAL_MACHINE
                && value.CredentialBlobSize <= 2048;
            let bytes = if valid && value.CredentialBlobSize > 0 && !value.CredentialBlob.is_null()
            {
                Some(Zeroizing::new(
                    std::slice::from_raw_parts(
                        value.CredentialBlob,
                        value.CredentialBlobSize as usize,
                    )
                    .to_vec(),
                ))
            } else {
                None
            };
            if !value.CredentialBlob.is_null() && value.CredentialBlobSize <= 2560 {
                std::slice::from_raw_parts_mut(
                    value.CredentialBlob,
                    value.CredentialBlobSize as usize,
                )
                .zeroize();
            }
            CredFree(pointer.cast());
            bytes.map(Some).ok_or("STORAGE_UNAVAILABLE")
        }
    }
    pub fn write(target: &str, bytes: &[u8]) -> Result<(), Error> {
        let mut target = wide(target);
        let mut user = wide("AnimeOnCraft");
        let value = CREDENTIALW {
            Flags: 0,
            Type: CRED_TYPE_GENERIC,
            TargetName: target.as_mut_ptr(),
            Comment: null_mut(),
            LastWritten: windows_sys::Win32::Foundation::FILETIME {
                dwLowDateTime: 0,
                dwHighDateTime: 0,
            },
            CredentialBlobSize: bytes.len() as u32,
            CredentialBlob: bytes.as_ptr() as *mut u8,
            Persist: CRED_PERSIST_LOCAL_MACHINE,
            AttributeCount: 0,
            Attributes: null_mut(),
            TargetAlias: null_mut(),
            UserName: user.as_mut_ptr(),
        };
        // Input buffers remain alive for this synchronous call. No caller-provided target/path.
        unsafe {
            if CredWriteW(&value, 0) == 0 {
                Err("STORAGE_UNAVAILABLE")
            } else {
                Ok(())
            }
        }
    }
    #[cfg(test)]
    pub fn delete(target: &str) -> Result<(), Error> {
        unsafe {
            if CredDeleteW(wide(target).as_ptr(), CRED_TYPE_GENERIC, 0) != 0
                || GetLastError() == ERROR_NOT_FOUND
            {
                Ok(())
            } else {
                Err("STORAGE_UNAVAILABLE")
            }
        }
    }
}
#[cfg(not(windows))]
mod platform {
    use super::*;
    pub fn read(_: &str) -> Result<Option<Zeroizing<Vec<u8>>>, Error> {
        Err("STORAGE_UNAVAILABLE")
    }
    pub fn write(_: &str, _: &[u8]) -> Result<(), Error> {
        Err("STORAGE_UNAVAILABLE")
    }
    #[cfg(test)]
    pub fn delete(_: &str) -> Result<(), Error> {
        Err("STORAGE_UNAVAILABLE")
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_unknown_duplicate_and_wrong_environment() {
        assert!(serde_json::from_str::<Record>(r#"{"state":"PENDING","format":1,"format":1,"environment":"development-loopback-v1","operation_id":"x"}"#).is_err());
        assert!(serde_json::from_str::<Record>(
            r#"{"state":"LOGGED_OUT","format":1,"environment":"x","token":"x"}"#
        )
        .is_err());
        assert!(Record::LoggedOut {
            format: 1,
            environment: "production".into()
        }
        .validate()
        .is_err());
    }
    #[test]
    #[cfg(windows)]
    fn windows_fixture_roundtrip_pending_and_cleanup() {
        let store = CredentialStore::fixture();
        assert!(store.load().unwrap().is_none());
        struct Cleanup<'a>(&'a CredentialStore);
        impl Drop for Cleanup<'_> {
            fn drop(&mut self) {
                let _ = self.0.remove();
            }
        }
        let _cleanup = Cleanup(&store);
        store
            .save(&Record::Ready {
                format: 1,
                environment: ENVIRONMENT.into(),
                session_id: uuid::Uuid::new_v4().to_string(),
                absolute_expires_at: "2099-01-01T00:00:00Z".into(),
                refresh_token: Zeroizing::new(format!("ar_{}", "a".repeat(43))),
            })
            .unwrap();
        assert!(matches!(store.load().unwrap(), Some(Record::Ready { .. })));
        store.save(&Record::pending()).unwrap();
        assert!(matches!(
            store.load().unwrap(),
            Some(Record::Pending { .. })
        ));
        store.save(&Record::logged_out()).unwrap();
        assert!(matches!(
            store.load().unwrap(),
            Some(Record::LoggedOut { .. })
        ));
        store.remove().unwrap();
        assert!(store.load().unwrap().is_none());
        let foreign = br#"{"state":"LOGGED_OUT","format":2,"environment":"future"}"#;
        platform::write(&store.target, foreign).unwrap();
        assert!(store.save(&Record::logged_out()).is_err());
        assert_eq!(
            &**platform::read(&store.target).unwrap().as_ref().unwrap(),
            foreign
        );
        let malformed = serde_json::to_vec(&Record::Ready {
            format: 1,
            environment: ENVIRONMENT.into(),
            session_id: "invalid".into(),
            absolute_expires_at: "2099-01-01T00:00:00Z".into(),
            refresh_token: Zeroizing::new("invalid".into()),
        })
        .unwrap();
        platform::write(&store.target, &malformed).unwrap();
        assert!(store.save(&Record::logged_out()).is_err());
        assert_eq!(
            &**platform::read(&store.target).unwrap().as_ref().unwrap(),
            &malformed
        );
        let expired = Record::Ready {
            format: 1,
            environment: ENVIRONMENT.into(),
            session_id: uuid::Uuid::new_v4().to_string(),
            absolute_expires_at: "2000-01-01T00:00:00Z".into(),
            refresh_token: Zeroizing::new(format!("ar_{}", "a".repeat(43))),
        };
        assert_eq!(expired.validate(), Err("REAUTH_REQUIRED"));
        platform::write(&store.target, &serde_json::to_vec(&expired).unwrap()).unwrap();
        store.save(&Record::logged_out()).unwrap();
    }
}
