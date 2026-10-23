//! Read-only committed generation replay; no network/vendor scratch or clock reset.
use super::*;
use crate::pack::trust::cached::{self, HistoricalRelease};
pub(in crate::pack::ntfs) struct Restored {
    pub(in crate::pack::ntfs) current: trust::VerifiedRelease,
    pub(in crate::pack::ntfs) prior: Option<HistoricalRelease>,
}
fn observed(bundle: &Bundle) -> BResult<chrono::DateTime<chrono::Utc>> {
    let clock: String = decode(&bundle.files["latest_known_time.json"], 256)?;
    chrono::DateTime::parse_from_rfc3339(&clock)
        .map(|time| time.with_timezone(&chrono::Utc))
        .map_err(|_| "CLOCK_ERROR")
}
impl Bridge {
    pub(in crate::pack::ntfs) fn validate_history(&self, bootstrap: &[u8]) -> BResult<()> {
        let (active, _, _) = self.inspect(bootstrap)?;
        let mut previous: Option<Bundle> = None;
        for number in 1..=active.map_or(0, |(n, _)| n) {
            let seal: Seal = decode(
                &get(&self.journal, &format!("commit-{number:05}.json"), 16384)?,
                16384,
            )?;
            let bundle = self.payload(number, &seal, bootstrap)?;
            if let Some(old) = &previous {
                dominates(&bundle, old)?;
            }
            let intent: Intent = decode(
                &get(&self.journal, &format!("intent-{number:05}.json"), 16384)?,
                16384,
            )?;
            manifest::parse(&bundle.target, &intent.request.policy()?)?;
            previous = Some(bundle);
        }
        Ok(())
    }
    pub(in crate::pack::ntfs) fn restore(
        &self,
        bootstrap: &[u8],
        policy: &manifest::ReleasePolicy,
        prior: Option<(&str, &manifest::ReleasePolicy)>,
    ) -> BResult<Restored> {
        if manifest::hash(bootstrap) != self.anchor {
            return Err("STORE_INVALID");
        }
        let (active, _, pending) = self.inspect(bootstrap)?;
        if pending {
            return Err("BRIDGE_RECOVERY_REQUIRED");
        }
        let (last, latest) = active.ok_or("ENGINE_RELEASE_REQUIRED")?;
        let latest_digest = manifest::hash(&latest.target);
        drop(latest);
        let mut previous: Option<Bundle> = None;
        let mut historical = None;
        for number in 1..=last {
            let seal: Seal = decode(
                &get(&self.journal, &format!("commit-{number:05}.json"), 16384)?,
                16384,
            )?;
            let bundle = self.payload(number, &seal, bootstrap)?;
            if let Some(old) = &previous {
                dominates(&bundle, old)?;
            }
            let intent: Intent = decode(
                &get(&self.journal, &format!("intent-{number:05}.json"), 16384)?,
                16384,
            )?;
            manifest::parse(&bundle.target, &intent.request.policy()?)?;
            if let Some((digest, policy)) = prior {
                if digest != latest_digest && manifest::hash(&bundle.target) == digest {
                    historical = Some(cached::historical(
                        bootstrap,
                        &bundle.chain,
                        &bundle.files["root.json"],
                        &bundle.raw,
                        &bundle.target,
                        policy,
                        observed(&bundle)?,
                    )?);
                }
            }
            previous = Some(bundle);
        }
        if prior.is_some_and(|(digest, _)| digest != latest_digest) && historical.is_none() {
            return Err("HISTORY_RELEASE_NOT_FOUND");
        }
        let latest = previous.ok_or("ENGINE_RELEASE_REQUIRED")?;
        let document: manifest::Manifest = decode(&latest.target, manifest::MAX_MANIFEST)?;
        if document.pack_id != policy.pack_id || document.channel != policy.channel {
            return Err("STORE_CONTEXT_CHANGED");
        }
        if document.release_sequence != policy.sequence {
            return Err(if document.release_sequence < policy.sequence {
                "RELEASE_MISMATCH"
            } else {
                "STORE_ROLLBACK"
            });
        }
        let current = cached::current(
            bootstrap,
            &latest.chain,
            &latest.files["root.json"],
            &latest.raw,
            &latest.target,
            policy,
            observed(&latest)?,
        )?;
        Ok(Restored {
            current,
            prior: historical,
        })
    }
}
