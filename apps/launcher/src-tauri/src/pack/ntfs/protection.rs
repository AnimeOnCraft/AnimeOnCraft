//! Per-user object security. The current OS user/SYSTEM are trusted principals.
//! This does not isolate another process using the same user token or an administrator.
use super::{Error, Result};
use std::{
    collections::BTreeSet,
    fs::File,
    mem::{offset_of, size_of},
    os::windows::io::AsRawHandle,
    ptr,
};
use windows_sys::Win32::{
    Foundation::{CloseHandle, LocalFree, HANDLE},
    Security::{Authorization::*, *},
    Storage::FileSystem::FILE_ALL_ACCESS,
    System::Threading::{GetCurrentProcess, OpenProcessToken},
};

pub(super) struct Descriptor(pub(super) PSECURITY_DESCRIPTOR);
impl Drop for Descriptor {
    fn drop(&mut self) {
        unsafe {
            LocalFree(self.0);
        }
    }
}
struct Token(HANDLE);
impl Drop for Token {
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.0);
        }
    }
}
pub(super) struct Protection {
    pub(super) sid: String,
}

fn wide(text: &str) -> Vec<u16> {
    text.encode_utf16().chain(Some(0)).collect()
}
fn sid_text(sid: PSID) -> Result<String> {
    let mut output = ptr::null_mut();
    // Sid is part of a successful token/security query, kept alive by the caller.
    if unsafe { IsValidSid(sid) } == 0 || unsafe { ConvertSidToStringSidW(sid, &mut output) } == 0 {
        return Err(Error::Acl);
    }
    let allocation = Descriptor(output.cast());
    let mut len = 0;
    unsafe {
        while len < 256 && *output.add(len) != 0 {
            len += 1;
        }
    }
    if len == 256 {
        return Err(Error::Acl);
    }
    let value = unsafe { String::from_utf16(std::slice::from_raw_parts(output, len)) }
        .map_err(|_| Error::Acl)?;
    drop(allocation);
    Ok(value)
}

impl Protection {
    pub(super) fn current() -> Result<Self> {
        let mut token = ptr::null_mut();
        if unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) } == 0 {
            return Err(Error::Acl);
        }
        let token = Token(token);
        let mut size = 0;
        unsafe {
            GetTokenInformation(token.0, TokenUser, ptr::null_mut(), 0, &mut size);
        }
        if size < size_of::<TOKEN_USER>() as u32 || size > 65536 {
            return Err(Error::Acl);
        }
        let mut buffer = vec![0u64; (size as usize).div_ceil(8)];
        if unsafe {
            GetTokenInformation(
                token.0,
                TokenUser,
                buffer.as_mut_ptr().cast(),
                size,
                &mut size,
            )
        } == 0
        {
            return Err(Error::Acl);
        }
        let user = unsafe { &*buffer.as_ptr().cast::<TOKEN_USER>() };
        Ok(Self {
            sid: sid_text(user.User.Sid)?,
        })
    }
    pub(super) fn from_sddl(text: &str) -> Result<Descriptor> {
        let mut output = ptr::null_mut();
        if unsafe {
            ConvertStringSecurityDescriptorToSecurityDescriptorW(
                wide(text).as_ptr(),
                1,
                &mut output,
                ptr::null_mut(),
            )
        } == 0
        {
            return Err(Error::Acl);
        }
        Ok(Descriptor(output))
    }
    pub(super) fn descriptor(&self, directory: bool) -> Result<Descriptor> {
        let flags = if directory { "OICI" } else { "" };
        Self::from_sddl(&format!(
            "O:{}D:P(A;{flags};FA;;;SY)(A;{flags};FA;;;{})",
            self.sid, self.sid
        ))
    }
    pub(super) fn check(&self, file: &File, directory: bool) -> Result<()> {
        self.check_mode(file, directory, false)
    }
    pub(super) fn check_scratch_file(&self, file: &File) -> Result<()> {
        self.check_mode(file, false, true)
    }
    fn check_mode(&self, file: &File, directory: bool, inherited_file: bool) -> Result<()> {
        let mut owner = ptr::null_mut();
        let mut acl = ptr::null_mut();
        let mut descriptor = ptr::null_mut();
        let status = unsafe {
            GetSecurityInfo(
                file.as_raw_handle(),
                SE_FILE_OBJECT,
                OWNER_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION,
                &mut owner,
                ptr::null_mut(),
                &mut acl,
                ptr::null_mut(),
                &mut descriptor,
            )
        };
        if status != 0 {
            return Err(Error::Acl);
        }
        let descriptor = Descriptor(descriptor);
        let mut control = 0;
        let mut revision = 0;
        if descriptor.0.is_null()
            || acl.is_null()
            || unsafe { IsValidSecurityDescriptor(descriptor.0) } == 0
            || unsafe { IsValidAcl(acl) } == 0
            || unsafe { GetSecurityDescriptorControl(descriptor.0, &mut control, &mut revision) }
                == 0
            || (control & SE_DACL_PROTECTED == 0 && !inherited_file)
            || sid_text(owner)? != self.sid
        {
            return Err(Error::Acl);
        }
        let mut info = ACL_SIZE_INFORMATION::default();
        if unsafe {
            GetAclInformation(
                acl,
                ptr::from_mut(&mut info).cast(),
                size_of::<ACL_SIZE_INFORMATION>() as u32,
                AclSizeInformation,
            )
        } == 0
            || info.AceCount != 2
        {
            return Err(Error::Acl);
        }
        let mut principals = BTreeSet::new();
        for index in 0..info.AceCount {
            let mut pointer = ptr::null_mut();
            if unsafe { GetAce(acl, index, &mut pointer) } == 0 || pointer.is_null() {
                return Err(Error::Acl);
            }
            let header = unsafe { &*pointer.cast::<ACE_HEADER>() };
            // ACCESS_ALLOWED_ACE_TYPE=0; only ordinary explicit allow ACEs, not callbacks/object/inherited ACEs.
            let flags = if directory {
                3
            } else if control & SE_DACL_PROTECTED != 0 {
                0
            } else {
                16
            };
            if header.AceType != 0
                || header.AceFlags != flags
                || (header.AceSize as usize) < offset_of!(ACCESS_ALLOWED_ACE, SidStart) + 8
            {
                return Err(Error::Acl);
            }
            let ace = unsafe { &*pointer.cast::<ACCESS_ALLOWED_ACE>() };
            if ace.Mask != FILE_ALL_ACCESS {
                return Err(Error::Acl);
            }
            let sid: PSID = unsafe {
                pointer
                    .cast::<u8>()
                    .add(offset_of!(ACCESS_ALLOWED_ACE, SidStart))
                    .cast()
            };
            // Bound the variable SID inside its ACE before a SID API can inspect it.
            let sid_capacity = header.AceSize as usize - offset_of!(ACCESS_ALLOWED_ACE, SidStart);
            let subauthorities = unsafe { *sid.cast::<u8>().add(1) } as usize;
            if subauthorities > 15 || 8 + subauthorities * 4 > sid_capacity {
                return Err(Error::Acl);
            }
            let name = sid_text(sid)?;
            if !principals.insert(name) {
                return Err(Error::Acl);
            }
        }
        if principals != BTreeSet::from([self.sid.clone(), "S-1-5-18".into()]) {
            return Err(Error::Acl);
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use windows_sys::Win32::Storage::FileSystem::{
        FILE_GENERIC_EXECUTE, FILE_GENERIC_READ, FILE_GENERIC_WRITE, FILE_WRITE_DATA,
    };
    #[test]
    fn windows_access_check_allows_user_but_denies_restricted_principal() {
        let protection = Protection::current().unwrap();
        let fixture = super::super::tests::Fixture::new();
        let owned = super::super::owned::OwnedRoot::create(&fixture.root, "instance").unwrap();
        let mut descriptor = ptr::null_mut();
        // AccessCheck requires owner AND group, so query the complete descriptor of
        // the actual created NTFS object, not a standalone owner/DACL template.
        assert_eq!(
            unsafe {
                GetSecurityInfo(
                    owned.dir.file.as_raw_handle(),
                    SE_FILE_OBJECT,
                    OWNER_SECURITY_INFORMATION
                        | GROUP_SECURITY_INFORMATION
                        | DACL_SECURITY_INFORMATION,
                    ptr::null_mut(),
                    ptr::null_mut(),
                    ptr::null_mut(),
                    ptr::null_mut(),
                    &mut descriptor,
                )
            },
            0
        );
        let descriptor = Descriptor(descriptor);
        let mut token = ptr::null_mut();
        assert_ne!(
            unsafe {
                OpenProcessToken(
                    GetCurrentProcess(),
                    TOKEN_QUERY | TOKEN_DUPLICATE,
                    &mut token,
                )
            },
            0
        );
        let original = Token(token);
        let mut sid = ptr::null_mut();
        assert_ne!(
            unsafe { ConvertStringSidToSidW(wide(&protection.sid).as_ptr(), &mut sid) },
            0
        );
        let sid_allocation = Descriptor(sid);
        let disable = SID_AND_ATTRIBUTES {
            Sid: sid,
            Attributes: 0,
        };
        let mut restricted = ptr::null_mut();
        assert_ne!(
            unsafe {
                CreateRestrictedToken(
                    original.0,
                    DISABLE_MAX_PRIVILEGE,
                    1,
                    &disable,
                    0,
                    ptr::null(),
                    0,
                    ptr::null(),
                    &mut restricted,
                )
            },
            0
        );
        let restricted = Token(restricted);
        let mapping = GENERIC_MAPPING {
            GenericRead: FILE_GENERIC_READ,
            GenericWrite: FILE_GENERIC_WRITE,
            GenericExecute: FILE_GENERIC_EXECUTE,
            GenericAll: FILE_ALL_ACCESS,
        };
        for (source, expected) in [(original.0, true), (restricted.0, false)] {
            let mut impersonation = ptr::null_mut();
            assert_ne!(
                unsafe { DuplicateToken(source, SecurityImpersonation, &mut impersonation) },
                0
            );
            let impersonation = Token(impersonation);
            let mut privileges = [0u64; 128];
            let mut size = size_of_val(&privileges) as u32;
            let mut granted = 0;
            let mut allowed = 0;
            assert_ne!(
                unsafe {
                    AccessCheck(
                        descriptor.0,
                        impersonation.0,
                        FILE_WRITE_DATA,
                        &mapping,
                        privileges.as_mut_ptr().cast(),
                        &mut size,
                        &mut granted,
                        &mut allowed,
                    )
                },
                0
            );
            assert_eq!(allowed != 0, expected);
        }
        drop(sid_allocation);
    }
}
