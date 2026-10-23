use super::*;
pub(super) fn operations(
    new: &VerifiedRelease,
    old: Option<&VerifiedRelease>,
    before: &BTreeMap<String, Stamp>,
) -> IResult<(Vec<Op>, u64)> {
    let old = old.ok_or("REPAIR_INDEX_REQUIRED")?;
    if new.manifest().digest() != old.manifest().digest() {
        return Err("REPAIR_RELEASE_MISMATCH");
    }
    let expected = entries(new.manifest());
    if before.keys().any(|p| !expected.contains_key(p)) {
        return Err("INSTALL_FOREIGN_OR_MODIFIED");
    }
    let mut observed = 0u64;
    for stamp in before.values() {
        if !manifest::digest(&stamp.sha256) {
            return Err("INSTALL_PLAN_INVALID");
        }
        observed = observed
            .checked_add(stamp.length)
            .ok_or("INSTALL_PLAN_INVALID")?;
    }
    if observed > manifest::MAX_BYTES {
        return Err("INSTALL_PLAN_INVALID");
    }
    let mut space = 64u64 * 1024 * 1024;
    let mut ops = Vec::new();
    for (path, new) in expected {
        let old = before.get(&path).cloned();
        let action = match &old {
            None => Action::Install,
            Some(stamp) if stamp == &new => Action::Reuse,
            Some(_) => Action::Replace,
        };
        if action != Action::Reuse {
            space = space
                .checked_add(new.length)
                .and_then(|s| s.checked_add(old.as_ref().map_or(0, |f| f.length)))
                .ok_or("INSTALL_NO_SPACE")?;
        }
        ops.push(Op {
            action,
            path,
            old,
            new: Some(new),
        });
    }
    Ok((ops, space))
}
impl Store {
    pub(super) fn repair(
        &mut self,
        release: &VerifiedRelease,
        prepared: &mut Prepared,
        cancel: &Cancel,
        hook: &mut impl FnMut(&str),
    ) -> IResult<Outcome> {
        if self.head()?.active.is_none() {
            return Err("REPAIR_INDEX_REQUIRED");
        }
        self.begin_mode(release, Some(release), prepared, cancel, hook, Mode::Repair)
    }
}
