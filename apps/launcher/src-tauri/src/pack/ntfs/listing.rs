//! Bounded enumeration through a retained native directory handle.
use super::*;
use std::{collections::BTreeSet, time::Instant};
use windows_sys::Win32::Foundation::ERROR_NO_MORE_FILES;
pub(super) const BUFFER: usize = 64 * 1024;
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(super) struct Entry {
    pub(super) name: String,
    pub(super) id: [u8; 16],
    pub(super) directory: bool,
    pub(super) attributes: u32,
    pub(super) length: u64,
}
pub(super) fn parse_page(buffer: &[u64]) -> Result<Vec<Entry>> {
    let capacity = buffer.len() * 8;
    let mut offset = 0usize;
    let mut output = Vec::new();
    loop {
        if offset
            .checked_add(size_of::<FILE_ID_EXTD_DIR_INFO>())
            .is_none_or(|end| end > capacity)
        {
            return Err(Error::Inventory);
        }
        // Buffers originate in GetFileInformationByHandleEx and are pre-zeroed. Bound
        // every ABI read/name/next offset; read_unaligned avoids relying on OS alignment.
        let info = unsafe {
            ptr::read_unaligned(
                buffer
                    .as_ptr()
                    .cast::<u8>()
                    .add(offset)
                    .cast::<FILE_ID_EXTD_DIR_INFO>(),
            )
        };
        let count = info.FileNameLength as usize;
        let name_start = offset + offset_of!(FILE_ID_EXTD_DIR_INFO, FileName);
        let name_end = name_start.checked_add(count).ok_or(Error::Inventory)?;
        if count == 0 || count > 510 || count % 2 != 0 || name_end > capacity {
            return Err(Error::Inventory);
        }
        let name = unsafe {
            std::slice::from_raw_parts(
                buffer.as_ptr().cast::<u8>().add(name_start).cast::<u16>(),
                count / 2,
            )
        };
        let name = String::from_utf16(name).map_err(|_| Error::Inventory)?;
        if name != "." && name != ".." {
            single_component(OsStr::new(&name)).map_err(|_| Error::Inventory)?;
            if info.EndOfFile < 0 {
                return Err(Error::Inventory);
            }
            output.push(Entry {
                name,
                id: info.FileId.Identifier,
                directory: info.FileAttributes & FILE_ATTRIBUTE_DIRECTORY != 0,
                attributes: info.FileAttributes,
                length: info.EndOfFile as u64,
            });
        }
        if info.NextEntryOffset == 0 {
            break;
        }
        let next = offset
            .checked_add(info.NextEntryOffset as usize)
            .ok_or(Error::Inventory)?;
        if info.NextEntryOffset % 8 != 0 || next < name_end || next <= offset || next >= capacity {
            return Err(Error::Inventory);
        }
        offset = next;
    }
    Ok(output)
}
impl Dir {
    pub(super) fn entries(&self, maximum: usize, deadline: Instant) -> Result<Vec<Entry>> {
        self.unchanged()?;
        let mut buffer = vec![0u64; BUFFER / 8];
        let mut first = true;
        let mut output = Vec::new();
        loop {
            if Instant::now() >= deadline {
                return Err(Error::Inventory);
            }
            buffer.fill(0);
            let class = if first {
                FileIdExtdDirectoryRestartInfo
            } else {
                FileIdExtdDirectoryInfo
            };
            first = false;
            if unsafe {
                GetFileInformationByHandleEx(
                    self.file.as_raw_handle(),
                    class,
                    buffer.as_mut_ptr().cast(),
                    BUFFER as u32,
                )
            } == 0
            {
                let error = io::Error::last_os_error();
                if error.raw_os_error() == Some(ERROR_NO_MORE_FILES as i32) {
                    break;
                }
                return Err(error.into());
            }
            let entries = parse_page(&buffer)?;
            if output
                .len()
                .checked_add(entries.len())
                .is_none_or(|len| len > maximum)
            {
                return Err(Error::Inventory);
            }
            output.extend(entries);
        }
        output.sort();
        let mut names = BTreeSet::new();
        for entry in &output {
            if !names.insert(entry.name.to_ascii_lowercase()) {
                return Err(Error::Inventory);
            }
        }
        self.unchanged()?;
        Ok(output)
    }
}
