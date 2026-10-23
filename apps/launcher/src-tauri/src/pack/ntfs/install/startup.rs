//! Read-only startup inspection and single-use, in-memory confirmation capability.
use super::*;
use crate::pack::actor::{PlanMode, PlanSummary, Startup};

pub(in crate::pack::ntfs) struct Approval {
    pub(super) mode: Mode,
    pub(super) hash: String,
    pub(in crate::pack::ntfs) summary: PlanSummary,
}
impl Store {
    pub(in crate::pack::ntfs) fn has_pending(&self) -> IResult<bool> {
        Ok(self.head()?.pending.is_some())
    }
    pub(in crate::pack::ntfs) fn pending_operations(&self) -> IResult<u32> {
        let n = self.head()?.pending.ok_or("INSTALL_NOTHING_TO_RECOVER")?;
        Ok(self.load(n)?.plan.operations.len() as u32)
    }
    pub(in crate::pack::ntfs) fn pending_prepared(&self) -> IResult<bool> {
        self.head()?
            .pending
            .map_or(Ok(false), |n| Ok(self.load(n)?.marked("prepared")))
    }
    pub(in crate::pack::ntfs) fn preview(
        &self,
        new: &VerifiedRelease,
        old: Option<&VerifiedRelease>,
        mode: Mode,
        cancel: &Cancel,
    ) -> IResult<Approval> {
        if mode == Mode::Repair && self.head()?.active.is_none() {
            return Err("REPAIR_INDEX_REQUIRED");
        }
        let (plan, mut inventory) = self.plan_mode(new, old, mode, cancel)?;
        let mut summary = PlanSummary {
            mode: if mode == Mode::Repair {
                PlanMode::Repair
            } else {
                PlanMode::Install
            },
            install: 0,
            replace: 0,
            remove: 0,
            reuse: 0,
            staging_bytes: 0,
            required_space: plan.space,
        };
        for op in &plan.operations {
            match op.action {
                Action::Install => summary.install += 1,
                Action::Replace => summary.replace += 1,
                Action::Remove => summary.remove += 1,
                Action::Reuse => summary.reuse += 1,
            }
            if op.action != Action::Reuse {
                summary.staging_bytes = summary
                    .staging_bytes
                    .checked_add(op.new.as_ref().map_or(0, |s| s.length))
                    .ok_or("INSTALL_PLAN_INVALID")?;
            }
        }
        native(inventory.validate())?;
        cancel.check()?;
        Ok(Approval {
            mode,
            hash: manifest::hash(&encoded(&plan)?),
            summary,
        })
    }
    pub(in crate::pack::ntfs) fn startup(
        &self,
        new: &VerifiedRelease,
        old: Option<&VerifiedRelease>,
    ) -> IResult<Startup> {
        self.context(new, old)?;
        let head = self.head()?;
        self.validate_previous(head.active.as_ref(), old)?;
        if let Some((pack, channel, sequence, digest)) = &head.floor {
            let m = new.manifest().manifest();
            if m.pack_id != *pack
                || m.channel != *channel
                || m.release_sequence < *sequence
                || (m.release_sequence == *sequence && new.manifest().digest() != digest)
            {
                return Err("INSTALL_ROLLBACK");
            }
        }
        if let Some(number) = head.pending {
            let tx = self.load(number)?;
            let m = new.manifest().manifest();
            if tx.plan.old != old.map(|r| r.manifest().digest().to_owned())
                || tx.plan.new != new.manifest().digest()
                || tx.plan.pack != m.pack_id
                || tx.plan.channel != m.channel
                || tx.plan.sequence != m.release_sequence
            {
                return Err("INSTALL_TRUST_MISMATCH");
            }
            let (ops, space) = planned(tx.plan.mode, new, old, &tx.plan.before)?;
            if tx.plan.operations != ops || tx.plan.space != space {
                return Err("INSTALL_PLAN_INVALID");
            }
            if tx.marked("prepared") {
                self.preflight(&tx)?;
            } else {
                let mut inventory = native(self.instance.inventory(Default::default()))?;
                let before: BTreeMap<_, _> = inventory
                    .observed
                    .iter()
                    .map(|(p, f)| (p.to_ascii_lowercase(), Stamp::observed(f)))
                    .collect();
                if before != tx.plan.before
                    || !names(&tx.backup, manifest::MAX_FILES)?.is_empty()
                    || has(&tx.dir, "index.json")?
                {
                    return Err("INSTALL_PREPARATION_CHANGED");
                }
                native(inventory.validate())?;
                drop(inventory);
                for op in &ops {
                    if op.old.is_none() && managed_file(&self.instance.dir, &op.path)?.is_some() {
                        return Err("INSTALL_PREPARATION_CHANGED");
                    }
                }
                if let Some(choice) = &tx.choice {
                    for (i, name) in &choice.files {
                        tx.plan.operations[*i]
                            .new
                            .as_ref()
                            .ok_or("INSTALL_PLAN_INVALID")?
                            .fingerprint(
                                &mut flat_file(&tx.staged, name)?.ok_or("INSTALL_STAGE_INVALID")?,
                            )?;
                    }
                }
            }
            return Ok(Startup::RecoveryRequired);
        }
        // Modified/missing *known* files may be repaired later; this does not grant PLAY.
        let mut inventory = native(self.instance.inventory(Default::default()))?;
        let indexed = head.active.map(|i| i.entries).unwrap_or_default();
        if inventory
            .observed
            .keys()
            .any(|p| !indexed.contains_key(&p.to_ascii_lowercase()))
        {
            return Err("INSTALL_FOREIGN_OR_MODIFIED");
        }
        native(inventory.validate())?;
        Ok(Startup::Ready)
    }
}
#[cfg(test)]
pub(super) fn inspected_startup(
    parent: &Arc<Dir>,
    bootstrap: &[u8],
    new: &VerifiedRelease,
    old: Option<&VerifiedRelease>,
) -> Startup {
    Store::reopen(parent, bootstrap)
        .and_then(|s| s.startup(new, old))
        .unwrap_or(Startup::Blocked)
}
