//! Bounded retained-handle inventory. An observation is not authorization to replace/delete.
use super::listing::Entry;
#[cfg(test)]
use super::listing::{parse_page, BUFFER};
use super::owned::OwnedRoot;
use super::*;
use crate::pack::manifest::{Fingerprint, MAX_BYTES, MAX_FILES};
use std::{
    collections::BTreeMap,
    time::{Duration, Instant},
};
const SCOPES: [&str; 5] = ["mods", "libraries", "versions", "natives", "assets"];
impl HeldFile {
    fn observe(&mut self, budget: u64, deadline: Instant) -> Result<Fingerprint> {
        self.verified = None; // Observed bytes are not a trusted manifest fingerprint.
        self.unchanged()?;
        let length = self.file.metadata()?.len();
        if length > budget {
            return Err(Error::Inventory);
        }
        self.file.seek(SeekFrom::Start(0))?;
        let mut buffer = [0u8; 64 * 1024];
        let mut digest = Sha256::new();
        let mut total = 0u64;
        loop {
            if Instant::now() >= deadline {
                return Err(Error::Inventory);
            }
            let read = self.file.read(&mut buffer)?;
            if read == 0 {
                break;
            }
            total = total.checked_add(read as u64).ok_or(Error::Inventory)?;
            if total > length || total > budget {
                return Err(Error::Inventory);
            }
            digest.update(&buffer[..read]);
        }
        self.unchanged()?;
        if total != length {
            return Err(Error::Inventory);
        }
        Ok(Fingerprint {
            length,
            sha256: format!("{:x}", digest.finalize()),
        })
    }
}
#[derive(Clone, Copy)]
pub(super) struct Limits {
    pub(super) entries: usize,
    pub(super) bytes: u64,
    pub(super) depth: usize,
    pub(super) duration: Duration,
}
impl Default for Limits {
    fn default() -> Self {
        Self {
            entries: MAX_FILES,
            bytes: MAX_BYTES,
            depth: 8,
            duration: Duration::from_secs(60),
        }
    }
}
pub(super) struct Snapshot {
    pub(super) observed: BTreeMap<String, Fingerprint>,
    files: Vec<(String, HeldFile)>,
    directories: Vec<(Arc<Dir>, Vec<Entry>, bool)>,
    _marker: HeldFile,
    limits: Limits,
}
fn scoped(entries: Vec<Entry>) -> Vec<Entry> {
    entries
        .into_iter()
        .filter(|e| SCOPES.contains(&e.name.to_ascii_lowercase().as_str()))
        .collect()
}
impl OwnedRoot {
    pub(super) fn inventory(&self, limits: Limits) -> Result<Snapshot> {
        self.inventory_with(limits, &mut |_| {})
    }
    pub(super) fn inventory_with(
        &self,
        limits: Limits,
        hook: &mut impl FnMut(&str),
    ) -> Result<Snapshot> {
        if limits.entries == 0
            || limits.entries > MAX_FILES
            || limits.bytes > MAX_BYTES
            || limits.depth > 8
            || limits.duration > Duration::from_secs(60)
        {
            return Err(Error::Inventory);
        }
        let deadline = Instant::now()
            .checked_add(limits.duration)
            .ok_or(Error::Inventory)?;
        let marker = self.marker()?;
        let initial = scoped(self.dir.entries(limits.entries, deadline)?);
        let mut snapshot = Snapshot {
            observed: BTreeMap::new(),
            files: Vec::new(),
            directories: Vec::new(),
            _marker: marker,
            limits,
        };
        let mut count = 0usize;
        let mut total = 0u64;
        for entry in &initial {
            if !entry.directory || entry.attributes & FILE_ATTRIBUTE_REPARSE_POINT != 0 {
                return Err(Error::Inventory);
            }
            hook(&entry.name);
            let dir = self.dir.child(&entry.name, false)?;
            if dir.identity.id != entry.id {
                return Err(Error::Inventory);
            }
            snapshot.walk(dir, &entry.name, 1, &mut count, &mut total, deadline, hook)?;
        }
        if initial != scoped(self.dir.entries(limits.entries, deadline)?) {
            return Err(Error::Inventory);
        }
        snapshot.directories.push((self.dir.clone(), initial, true));
        crate::pack::manifest::paths(snapshot.observed.keys().map(String::as_str))
            .map_err(|_| Error::Inventory)?;
        snapshot.validate_at(deadline)?;
        Ok(snapshot)
    }
}
impl Snapshot {
    fn walk(
        &mut self,
        dir: Arc<Dir>,
        prefix: &str,
        depth: usize,
        count: &mut usize,
        total: &mut u64,
        deadline: Instant,
        hook: &mut impl FnMut(&str),
    ) -> Result<()> {
        if depth > self.limits.depth {
            return Err(Error::Inventory);
        }
        let initial = dir.entries(self.limits.entries, deadline)?;
        for entry in &initial {
            *count = count.checked_add(1).ok_or(Error::Inventory)?;
            if *count > self.limits.entries || entry.attributes & FILE_ATTRIBUTE_REPARSE_POINT != 0
            {
                return Err(Error::Inventory);
            }
            let path = format!("{prefix}/{}", entry.name);
            if !crate::pack::manifest::portable(&path) {
                return Err(Error::Inventory);
            }
            hook(&path);
            if entry.directory {
                let child = dir.child(&entry.name, false)?;
                if child.identity.id != entry.id {
                    return Err(Error::Inventory);
                }
                self.walk(child, &path, depth + 1, count, total, deadline, hook)?;
            } else {
                if !crate::pack::manifest::managed(&path) {
                    return Err(Error::Inventory);
                }
                let mut file = dir.file(&entry.name, false)?;
                if file.identity.id != entry.id || file.file.metadata()?.len() != entry.length {
                    return Err(Error::Inventory);
                }
                let observed = file.observe(
                    self.limits
                        .bytes
                        .checked_sub(*total)
                        .ok_or(Error::Inventory)?,
                    deadline,
                )?;
                *total = total.checked_add(observed.length).ok_or(Error::Inventory)?;
                if self.observed.insert(path.clone(), observed).is_some() {
                    return Err(Error::Inventory);
                }
                self.files.push((path, file));
            }
        }
        if initial != dir.entries(self.limits.entries, deadline)? {
            return Err(Error::Inventory);
        }
        self.directories.push((dir, initial, false));
        Ok(())
    }
    pub(super) fn validate(&mut self) -> Result<()> {
        let deadline = Instant::now()
            .checked_add(self.limits.duration)
            .ok_or(Error::Inventory)?;
        self.validate_at(deadline)
    }
    fn validate_at(&mut self, deadline: Instant) -> Result<()> {
        let (length, digest) = self._marker.verified.clone().ok_or(Error::Marker)?;
        self._marker.fingerprint(length, &digest)?;
        for (dir, expected, root) in &self.directories {
            let current = dir.entries(self.limits.entries, deadline)?;
            if *expected != if *root { scoped(current) } else { current } {
                return Err(Error::Inventory);
            }
        }
        for (path, file) in &mut self.files {
            if &file.observe(self.limits.bytes, deadline)?
                != self.observed.get(path).ok_or(Error::Inventory)?
            {
                return Err(Error::Inventory);
            }
        }
        // Re-scan after hashing too. This detects observed changes, not an OS-atomic
        // directory snapshot against a hostile process running under the same SID.
        for (dir, expected, root) in &self.directories {
            let current = dir.entries(self.limits.entries, deadline)?;
            if *expected != if *root { scoped(current) } else { current } {
                return Err(Error::Inventory);
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn malformed_directory_pages_are_bounded_before_abi_reads() {
        assert!(parse_page(&[]).is_err());
        assert!(parse_page(&[0]).is_err());
        let mut buffer = vec![0u64; BUFFER / 8];
        let info = buffer.as_mut_ptr().cast::<FILE_ID_EXTD_DIR_INFO>();
        unsafe {
            (*info).FileNameLength = 2;
            (*info).FileName[0] = b'a' as u16;
        }
        assert_eq!(parse_page(&buffer).unwrap()[0].name, "a");
        for length in [0, 1, 511, u32::MAX] {
            unsafe {
                (*info).FileNameLength = length;
            }
            assert!(parse_page(&buffer).is_err());
        }
        unsafe {
            (*info).FileNameLength = 2;
        }
        for offset in [1, 8, (BUFFER as u32), u32::MAX] {
            unsafe {
                (*info).NextEntryOffset = offset;
            }
            assert!(parse_page(&buffer).is_err());
        }
        unsafe {
            (*info).NextEntryOffset = 0;
            (*info).FileName[0] = 0xD800;
        }
        assert!(parse_page(&buffer).is_err());
    }
}
