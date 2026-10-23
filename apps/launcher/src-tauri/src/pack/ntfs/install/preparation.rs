use super::*;
pub(super) const ATTEMPTS: usize = 4;
pub(super) fn filename(i: usize, attempt: usize) -> String {
    if attempt == 0 {
        format!("{i:05}.new")
    } else {
        format!("{i:05}-{attempt:03}.new")
    }
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Choice {
    format: u8,
    plan: String,
    pub(super) files: BTreeMap<usize, String>,
}
impl Choice {
    pub(super) fn validate(&self, tx: &Tx) -> IResult<()> {
        let expected: BTreeSet<_> = tx
            .plan
            .operations
            .iter()
            .enumerate()
            .filter(|(_, op)| op.new.is_some() && op.action != Action::Reuse)
            .map(|(i, _)| i)
            .collect();
        if self.format != 1
            || self.plan != tx.hash
            || self.files.keys().copied().collect::<BTreeSet<_>>() != expected
            || self
                .files
                .iter()
                .any(|(i, n)| !(0..ATTEMPTS).any(|a| *n == filename(*i, a)))
        {
            return Err("INSTALL_STAGE_INVALID");
        }
        Ok(())
    }
}
pub(super) fn choice_budget(plan: &Plan, hash: &str) -> IResult<usize> {
    let files = plan
        .operations
        .iter()
        .enumerate()
        .filter(|(_, op)| op.new.is_some() && op.action != Action::Reuse)
        .map(|(i, _)| (i, filename(i, ATTEMPTS - 1)))
        .collect();
    Ok(encoded(&Choice {
        format: 1,
        plan: hash.into(),
        files,
    })?
    .len())
}
impl Tx {
    pub(super) fn staged_name(&self, i: usize) -> IResult<String> {
        self.choice
            .as_ref()
            .and_then(|c| c.files.get(&i))
            .cloned()
            .ok_or("INSTALL_STAGE_INVALID")
    }
    pub(super) fn prepare(
        &mut self,
        prepared: &mut Prepared,
        cancel: &Cancel,
        hook: &mut impl FnMut(&str),
    ) -> IResult<()> {
        if prepared.manifest != self.plan.new {
            return Err("INSTALL_CACHE_MISMATCH");
        }
        cancel.check()?;
        if let Some(choice) = &self.choice {
            choice.validate(self)?;
            for (i, name) in &choice.files {
                let stamp = self.plan.operations[*i]
                    .new
                    .as_ref()
                    .ok_or("INSTALL_PLAN_INVALID")?;
                stamp.fingerprint(
                    &mut flat_file(&self.staged, name)?.ok_or("INSTALL_STAGE_INVALID")?,
                )?;
            }
            return Ok(());
        }
        let mut selected = BTreeMap::new();
        for (i, op) in self.plan.operations.iter().enumerate() {
            if op.action == Action::Reuse || op.new.is_none() {
                continue;
            }
            cancel.check()?;
            let stamp = op.new.as_ref().ok_or("INSTALL_PLAN_INVALID")?;
            let source = prepared
                .files
                .get_mut(&stamp.sha256)
                .ok_or("INSTALL_CACHE_MISMATCH")?;
            stamp.fingerprint(source)?;
            let mut target = None;
            for a in 0..ATTEMPTS {
                let name = filename(i, a);
                if let Some(existing) = flat_file(&self.staged, &name)? {
                    native(existing.unchanged())?;
                    drop(existing);
                    continue;
                }
                target = Some((a, name));
                break;
            }
            let (attempt, name) = target.ok_or("INSTALL_PREPARATION_LIMIT")?;
            if free_bytes(&self.staged)?
                < stamp
                    .length
                    .checked_add(64 * 1024 * 1024)
                    .ok_or("INSTALL_NO_SPACE")?
            {
                return Err("INSTALL_NO_SPACE");
            }
            stage(
                source,
                &self.staged,
                &name,
                stamp,
                cancel,
                hook,
                &format!("{i:05}:{attempt:03}"),
            )?;
            selected.insert(i, name);
        }
        let choice = Choice {
            format: 1,
            plan: self.hash.clone(),
            files: selected,
        };
        choice.validate(self)?;
        put(&self.journal, "stage-choice.json", &encoded(&choice)?)?;
        self.names.insert("stage-choice.json".into());
        self.choice = Some(choice);
        hook("stage-choice");
        Ok(())
    }
}
impl Store {
    pub(in crate::pack::ntfs) fn resume_preparation(
        &mut self,
        new: &VerifiedRelease,
        old: Option<&VerifiedRelease>,
        prepared: &mut Prepared,
        cancel: &Cancel,
        hook: &mut impl FnMut(&str),
    ) -> IResult<Outcome> {
        self.context(new, old)?;
        let head = self.head()?;
        let n = head.pending.ok_or("INSTALL_NOTHING_TO_RECOVER")?;
        let mut tx = self.load(n)?;
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
        self.validate_previous(head.active.as_ref(), old)?;
        if tx.marked("prepared") {
            return self.recover(new, old, cancel, hook);
        }
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
        for op in &tx.plan.operations {
            if op.old.is_none() && managed_file(&self.instance.dir, &op.path)?.is_some() {
                return Err("INSTALL_PREPARATION_CHANGED");
            }
        }
        if cancel.check().is_err() || tx.marked("cancel-request") {
            tx.record("cancel-request")?;
            tx.record("cancelled")?;
            return Ok(Outcome::Cancelled);
        }
        if free_bytes(&self.instance.dir)? < tx.plan.space {
            return Err("INSTALL_NO_SPACE");
        }
        if let Err(e) = tx.prepare(prepared, cancel, hook) {
            if e == "CANCELLED" {
                tx.record("cancel-request")?;
                tx.record("cancelled")?;
                return Ok(Outcome::Cancelled);
            }
            return Err(e);
        }
        native(inventory.validate())?;
        drop(inventory);
        new.current()?;
        tx.record("prepared")?;
        hook("prepared");
        self.apply(&mut tx, new, cancel, hook)
    }
}
