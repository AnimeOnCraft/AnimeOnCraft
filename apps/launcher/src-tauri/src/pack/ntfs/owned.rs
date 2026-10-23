//! Receipt is a private caller-held anchor, not a signature or a durable production trust store.
use super::*;
use serde::{Deserialize, Serialize};
const MARKER: &str = "aoc-instance.json";
const ENVIRONMENT: &str = "ntfs-owned-test-v1";
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Marker {
    format: u8,
    environment: String,
    instance: uuid::Uuid,
    owner: String,
    root: Identity,
}
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Receipt {
    environment: String,
    instance: uuid::Uuid,
    root: Identity,
    marker_hash: String,
    marker_length: u64,
}
pub(super) struct OwnedRoot {
    pub(super) dir: Arc<Dir>,
    pub(super) receipt: Receipt,
}
impl OwnedRoot {
    #[cfg(test)]
    pub(super) fn create(parent: &Arc<Dir>, name: &str) -> Result<Self> {
        Self::create_scoped(parent, name, ENVIRONMENT)
    }
    pub(super) fn create_scoped(
        parent: &Arc<Dir>,
        name: &str,
        environment: &'static str,
    ) -> Result<Self> {
        Self::create_inner(parent, name, environment, false).map(|(root, _)| root)
    }
    pub(super) fn create_update_stage(parent: &Arc<Dir>, name: &str) -> Result<(Self, HeldFile)> {
        Self::create_inner(parent, name, "launcher-update-stage-v1", true)
    }
    fn create_inner(
        parent: &Arc<Dir>,
        name: &str,
        environment: &'static str,
        removable: bool,
    ) -> Result<(Self, HeldFile)> {
        parent.unchanged()?;
        let protection = Arc::new(Protection::current()?);
        let security = protection.descriptor(true)?;
        let file = relative_access(
            parent,
            &mut portable_name(name)?,
            true,
            true,
            Some(&security),
            removable,
        )?;
        let identity = inspect(&file, true)?;
        if identity.volume != parent.identity.volume {
            return Err(Error::Volume);
        }
        protection.check(&file, true)?;
        let dir = Arc::new(Dir {
            file,
            identity,
            _parent: Some(parent.clone()),
            protection: Some(protection.clone()),
        });
        let instance = uuid::Uuid::new_v4();
        let bytes = serde_json::to_vec(&Marker {
            format: 1,
            environment: environment.into(),
            instance,
            owner: protection.sid.clone(),
            root: dir.identity.clone(),
        })
        .map_err(|_| Error::Marker)?;
        let receipt = Receipt {
            environment: environment.into(),
            instance,
            root: dir.identity.clone(),
            marker_hash: super::super::manifest::hash(&bytes),
            marker_length: bytes.len() as u64,
        };
        let mut marker = dir.file(MARKER, true)?;
        marker.write_new(&bytes)?;
        marker.fingerprint(receipt.marker_length, &receipt.marker_hash)?;
        Ok((Self { dir, receipt }, marker))
    }
    #[cfg(test)]
    pub(super) fn reopen(parent: &Arc<Dir>, name: &str, receipt: Receipt) -> Result<Self> {
        Self::reopen_scoped(parent, name, receipt, ENVIRONMENT)
    }
    pub(super) fn reopen_scoped(
        parent: &Arc<Dir>,
        name: &str,
        receipt: Receipt,
        environment: &'static str,
    ) -> Result<Self> {
        parent.unchanged()?;
        if receipt.environment != environment
            || receipt.marker_length > 16384
            || !super::super::manifest::digest(&receipt.marker_hash)
        {
            return Err(Error::Marker);
        }
        let protection = Arc::new(Protection::current()?);
        let file = relative(parent, &mut portable_name(name)?, true, false, None)?;
        let identity = inspect(&file, true)?;
        if identity != receipt.root || identity.volume != parent.identity.volume {
            return Err(Error::Marker);
        }
        protection.check(&file, true)?;
        let root = Self {
            dir: Arc::new(Dir {
                file,
                identity,
                _parent: Some(parent.clone()),
                protection: Some(protection),
            }),
            receipt,
        };
        root.marker()?;
        Ok(root)
    }
    pub(super) fn marker(&self) -> Result<HeldFile> {
        self.dir.unchanged()?;
        let mut marker = self.dir.file(MARKER, false)?;
        self.validate_marker(&mut marker)?;
        Ok(marker)
    }
    pub(super) fn validate_marker(&self, marker: &mut HeldFile) -> Result<()> {
        self.dir.unchanged()?;
        marker.fingerprint(self.receipt.marker_length, &self.receipt.marker_hash)?;
        marker.file.seek(SeekFrom::Start(0))?;
        let mut bytes = Vec::new();
        (&mut marker.file).take(16385).read_to_end(&mut bytes)?;
        let value = super::super::strict_json::parse(&bytes, 16384).map_err(|_| Error::Marker)?;
        let parsed: Marker = serde_json::from_value(value).map_err(|_| Error::Marker)?;
        if parsed.format != 1
            || parsed.environment != self.receipt.environment
            || parsed.instance != self.receipt.instance
            || parsed.root != self.dir.identity
            || parsed.owner != self.dir.protection.as_ref().ok_or(Error::Acl)?.sid
        {
            return Err(Error::Marker);
        }
        Ok(())
    }
}
