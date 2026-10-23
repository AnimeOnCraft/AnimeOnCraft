//! Test-only lease-bound release preflight, without backend IO or install authority.
use crate::pack::{
    local::LocalInstance,
    manifest,
    trust::{self, VerifiedRelease},
};
use std::sync::Arc;
type Result<T> = std::result::Result<T, &'static str>;

struct Session {
    local: Arc<LocalInstance>,
    anchor: String,
    binding: String,
}
impl Session {
    fn open(local: Arc<LocalInstance>, bootstrap: &[u8]) -> Result<Self> {
        trust::root_policy(bootstrap)?;
        let anchor = manifest::hash(bootstrap);
        let binding = local.backend_binding(&anchor)?;
        Ok(Self {
            local,
            anchor,
            binding,
        })
    }
    fn validate(&self) -> Result<()> {
        if self.local.backend_binding(&self.anchor)? != self.binding {
            return Err("LOCAL_BINDING_CHANGED");
        }
        Ok(())
    }
    fn bind(self, new: VerifiedRelease, old: Option<VerifiedRelease>) -> Result<BoundRelease> {
        let bound = BoundRelease {
            session: self,
            new,
            old,
        };
        bound.validate()?;
        Ok(bound)
    }
}

// No Clone/Serialize/DTO constructor. The single-use value owns both independently
// verified proofs and the local lifetime. No directory handle or writable path escapes.
struct BoundRelease {
    session: Session,
    new: VerifiedRelease,
    old: Option<VerifiedRelease>,
}
impl BoundRelease {
    fn validate(&self) -> Result<()> {
        self.session.validate()?;
        self.new.current()?;
        if self.new.anchor() != self.session.anchor
            || self
                .old
                .as_ref()
                .is_some_and(|old| old.anchor() != self.session.anchor)
        {
            return Err("LOCAL_TRUST_MISMATCH");
        }
        if let Some(old) = &self.old {
            let new = self.new.manifest().manifest();
            let previous = old.manifest().manifest();
            if new.pack_id != previous.pack_id
                || new.channel != previous.channel
                || new.release_sequence < previous.release_sequence
                || (new.release_sequence == previous.release_sequence
                    && self.new.manifest().digest() != old.manifest().digest())
            {
                return Err("LOCAL_RELEASE_MISMATCH");
            }
        }
        Ok(())
    }
    // Trusted synchronous work must finish before returning. Asynchronous integration
    // must move this owner into the full worker future, not retain detached proof bytes.
    fn run<R>(
        self,
        work: impl FnOnce(&VerifiedRelease, Option<&VerifiedRelease>) -> Result<R>,
    ) -> Result<R> {
        self.validate()?;
        let result = work(&self.new, self.old.as_ref());
        self.validate()?;
        result
    }
}

mod tests;
