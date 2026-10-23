use crate::account_client::{Error, ENVIRONMENT};
pub struct AccountLock {
    shutdown: Option<std::sync::mpsc::Sender<()>>,
    thread: Option<std::thread::JoinHandle<()>>,
}
impl Drop for AccountLock {
    fn drop(&mut self) {
        self.shutdown.take();
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}
impl AccountLock {
    #[cfg(windows)]
    pub fn acquire() -> Result<Self, Error> {
        Self::acquire_named(ENVIRONMENT)
    }
    #[cfg(windows)]
    fn acquire_named(namespace: &str) -> Result<Self, Error> {
        let (shutdown, wait) = std::sync::mpsc::channel();
        let (ready, result) = std::sync::mpsc::sync_channel(1);
        let namespace = namespace.to_string();
        // The dedicated OS thread owns and releases the Windows mutex, never an async task.
        let thread = std::thread::spawn(move || {
            platform::hold(wait, ready, namespace);
        });
        match result.recv() {
            Ok(true) => Ok(Self {
                shutdown: Some(shutdown),
                thread: Some(thread),
            }),
            _ => {
                drop(shutdown);
                let _ = thread.join();
                Err("ACCOUNT_ALREADY_OPEN_OR_UNAVAILABLE")
            }
        }
    }
    #[cfg(not(windows))]
    pub fn acquire() -> Result<Self, Error> {
        Err("WINDOWS_REQUIRED")
    }
}
#[cfg(windows)]
mod platform {
    use std::ptr::null_mut;
    use windows_sys::Win32::{
        Foundation::*,
        Security::{Authorization::*, *},
        System::Threading::*,
    };
    fn wide(value: &str) -> Vec<u16> {
        value.encode_utf16().chain(Some(0)).collect()
    }
    pub fn hold(
        wait: std::sync::mpsc::Receiver<()>,
        ready: std::sync::mpsc::SyncSender<bool>,
        namespace: String,
    ) {
        unsafe {
            let mut token = null_mut();
            if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) == 0 {
                let _ = ready.send(false);
                return;
            }
            let mut size = 0;
            GetTokenInformation(token, TokenUser, null_mut(), 0, &mut size);
            if size == 0 || size > 65536 {
                CloseHandle(token);
                let _ = ready.send(false);
                return;
            }
            let mut buffer = vec![0u64; (size as usize).div_ceil(8)];
            let ok = GetTokenInformation(
                token,
                TokenUser,
                buffer.as_mut_ptr().cast(),
                size,
                &mut size,
            );
            CloseHandle(token);
            if ok == 0 {
                let _ = ready.send(false);
                return;
            }
            let user = &*(buffer.as_ptr() as *const TOKEN_USER);
            let mut sid = null_mut();
            if ConvertSidToStringSidW(user.User.Sid, &mut sid) == 0 {
                let _ = ready.send(false);
                return;
            }
            let mut length = 0;
            while *sid.add(length) != 0 && length < 256 {
                length += 1;
            }
            let text = String::from_utf16_lossy(std::slice::from_raw_parts(sid, length));
            LocalFree(sid.cast());
            let mut descriptor = null_mut();
            if ConvertStringSecurityDescriptorToSecurityDescriptorW(
                wide(&format!("D:P(A;;GA;;;{text})(A;;GA;;;SY)")).as_ptr(),
                1,
                &mut descriptor,
                null_mut(),
            ) == 0
            {
                let _ = ready.send(false);
                return;
            }
            let attributes = SECURITY_ATTRIBUTES {
                nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
                lpSecurityDescriptor: descriptor,
                bInheritHandle: 0,
            };
            let mutex = CreateMutexW(
                &attributes,
                0,
                wide(&format!("Global\\AnimeOnCraft.Account.{text}.{namespace}")).as_ptr(),
            );
            LocalFree(descriptor);
            if mutex.is_null() {
                let _ = ready.send(false);
                return;
            }
            let status = WaitForSingleObject(mutex, 0);
            if status != WAIT_OBJECT_0 && status != WAIT_ABANDONED {
                CloseHandle(mutex);
                let _ = ready.send(false);
                return;
            }
            let _ = ready.send(true);
            let _ = wait.recv();
            ReleaseMutex(mutex);
            CloseHandle(mutex);
        }
    }
}
#[cfg(all(test, windows))]
mod tests {
    use super::*;
    #[test]
    fn singleton_rejects_second_owner_and_releases() {
        let namespace = format!("probe.{}", uuid::Uuid::new_v4().simple());
        let first = AccountLock::acquire_named(&namespace).unwrap();
        assert!(AccountLock::acquire_named(&namespace).is_err());
        drop(first);
        assert!(AccountLock::acquire_named(&namespace).is_ok());
    }
}
