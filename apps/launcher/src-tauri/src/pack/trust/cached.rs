//! Test-only native archive verifier. Historical proof never grants current release authority.
use super::*;
use sha2::{Digest, Sha256};
use tough::schema::{Snapshot, Targets, Timestamp};
type CResult<T> = Result<T, Error>;
fn decode<T: serde::de::DeserializeOwned>(bytes: &[u8], cap: usize) -> CResult<T> {
    serde_json::from_value(strict_json::parse(bytes, cap).map_err(|_| "STORE_INVALID")?)
        .map_err(|_| "STORE_INVALID")
}
pub(in crate::pack) fn signed<T: serde::de::DeserializeOwned>(bytes: &[u8]) -> CResult<Signed<T>> {
    decode(bytes, manifest::MAX_MANIFEST)
}
pub(in crate::pack) fn root_chain<B: AsRef<[u8]>>(
    bootstrap: &[u8],
    chain: &[B],
    final_root: &[u8],
) -> CResult<Signed<Root>> {
    root_policy(bootstrap)?;
    let mut current: Signed<Root> = signed(bootstrap)?;
    if chain.len() > 32 {
        return Err("ROOT_CHAIN_LIMIT");
    }
    for bytes in chain {
        root_policy(bytes.as_ref())?;
        let next: Signed<Root> = signed(bytes.as_ref())?;
        if next.signed.version.get()
            != current
                .signed
                .version
                .get()
                .checked_add(1)
                .ok_or("TUF_REJECTED")?
        {
            return Err("ROOT_CHAIN_INVALID");
        }
        current
            .signed
            .verify_role(&next)
            .map_err(|_| "ROOT_CHAIN_INVALID")?;
        next.signed
            .verify_role(&next)
            .map_err(|_| "ROOT_CHAIN_INVALID")?;
        current = next;
    }
    let actual: serde_json::Value = decode(final_root, 262144)?;
    if serde_json::to_value(&current).map_err(|_| "TUF_REJECTED")? != actual {
        return Err("ROOT_CHAIN_INVALID");
    }
    Ok(current)
}
pub(in crate::pack) fn reference(meta: &serde_json::Value, name: &str, raw: &[u8]) -> CResult<()> {
    let record = meta.get(name).ok_or("TUF_REJECTED")?;
    let content: serde_json::Value = decode(raw, manifest::MAX_MANIFEST)?;
    if record.get("version").and_then(|v| v.as_u64()) != content["signed"]["version"].as_u64()
        || record.get("length").and_then(|v| v.as_u64()) != Some(raw.len() as u64)
        || record["hashes"]["sha256"].as_str() != Some(manifest::hash(raw).as_str())
    {
        return Err("TUF_REJECTED");
    }
    Ok(())
}
struct Evidence {
    manifest: CheckedManifest,
    expires: chrono::DateTime<chrono::Utc>,
}
pub(in crate::pack) fn check_observed_expiry(
    root: &Signed<Root>,
    timestamp: &Signed<Timestamp>,
    snapshot: &Signed<Snapshot>,
    targets: &Signed<Targets>,
    observed: chrono::DateTime<chrono::Utc>,
) -> CResult<()> {
    let expires = [
        root.signed.expires.to_string(),
        timestamp.signed.expires.to_string(),
        snapshot.signed.expires.to_string(),
        targets.signed.expires.to_string(),
    ]
    .into_iter()
    .map(|date| {
        chrono::DateTime::parse_from_rfc3339(&date)
            .map(|date| date.with_timezone(&chrono::Utc))
            .map_err(|_| "TUF_REJECTED")
    })
    .collect::<CResult<Vec<_>>>()?
    .into_iter()
    .min()
    .ok_or("TUF_REJECTED")?;
    if observed.timestamp() < 1_577_836_800 || observed > chrono::Utc::now() {
        return Err("CLOCK_ERROR");
    }
    if observed >= expires {
        return Err("HISTORY_OBSERVATION_EXPIRED");
    }
    Ok(())
}
fn evidence<C: AsRef<[u8]>, B: AsRef<[u8]>>(
    bootstrap: &[u8],
    chain: &[C],
    final_root: &[u8],
    raw: &BTreeMap<String, B>,
    target: &[u8],
    policy: &ReleasePolicy,
) -> CResult<Evidence> {
    if raw.len() != 3 {
        return Err("STORE_INVALID");
    }
    let root = root_chain(bootstrap, chain, final_root)?;
    let body = |name: &str| raw.get(name).map(AsRef::as_ref).ok_or("STORE_INVALID");
    let timestamp: Signed<Timestamp> = decode(body("timestamp.json")?, 16384)?;
    let snapshot: Signed<Snapshot> = decode(body("snapshot.json")?, 262144)?;
    let targets: Signed<Targets> = signed(body("targets.json")?)?;
    root.signed
        .verify_role(&timestamp)
        .map_err(|_| "TUF_REJECTED")?;
    root.signed
        .verify_role(&snapshot)
        .map_err(|_| "TUF_REJECTED")?;
    root.signed
        .verify_role(&targets)
        .map_err(|_| "TUF_REJECTED")?;
    if targets
        .signed
        .delegations
        .as_ref()
        .is_some_and(|d| !d.roles.is_empty() || !d.keys.is_empty())
    {
        return Err("TUF_POLICY_REJECTED");
    }
    let ts: serde_json::Value = decode(body("timestamp.json")?, 16384)?;
    let sn: serde_json::Value = decode(body("snapshot.json")?, 262144)?;
    reference(
        &ts["signed"]["meta"],
        "snapshot.json",
        body("snapshot.json")?,
    )?;
    reference(&sn["signed"]["meta"], "targets.json", body("targets.json")?)?;
    let name = tough::TargetName::new(format!(
        "packs/{}/{}/manifest.json",
        policy.channel, policy.sequence
    ))
    .map_err(|_| "INVALID_MANIFEST")?;
    let expected = targets
        .signed
        .targets
        .get(&name)
        .ok_or("RELEASE_NOT_FOUND")?;
    if expected.length != target.len() as u64
        || expected.hashes.sha256.as_ref() != &Sha256::digest(target)[..]
    {
        return Err("TUF_REJECTED");
    }
    let manifest = manifest::parse(target, policy)?;
    let expires = [
        root.signed.expires.to_string(),
        timestamp.signed.expires.to_string(),
        snapshot.signed.expires.to_string(),
        targets.signed.expires.to_string(),
    ]
    .into_iter()
    .map(|date| {
        chrono::DateTime::parse_from_rfc3339(&date)
            .map(|date| date.with_timezone(&chrono::Utc))
            .map_err(|_| "TUF_REJECTED")
    })
    .collect::<CResult<Vec<_>>>()?
    .into_iter()
    .min()
    .ok_or("TUF_REJECTED")?;
    Ok(Evidence { manifest, expires })
}
fn at(
    evidence: &Evidence,
    observed: chrono::DateTime<chrono::Utc>,
    now: chrono::DateTime<chrono::Utc>,
) -> CResult<()> {
    if observed.timestamp() < 1_577_836_800 || observed > now {
        return Err("CLOCK_ERROR");
    }
    if observed >= evidence.expires {
        return Err("HISTORY_OBSERVATION_EXPIRED");
    }
    Ok(())
}
pub(in crate::pack) fn current<C: AsRef<[u8]>, B: AsRef<[u8]>>(
    bootstrap: &[u8],
    chain: &[C],
    final_root: &[u8],
    raw: &BTreeMap<String, B>,
    target: &[u8],
    policy: &ReleasePolicy,
    observed: chrono::DateTime<chrono::Utc>,
) -> CResult<VerifiedRelease> {
    let evidence = evidence(bootstrap, chain, final_root, raw, target, policy)?;
    at(&evidence, observed, chrono::Utc::now())?;
    let proof = VerifiedRelease {
        manifest: evidence.manifest,
        anchor: manifest::hash(bootstrap),
        expires: evidence.expires,
        not_before: observed,
        historical: false,
    };
    proof.current()?;
    Ok(proof)
}
pub(in crate::pack) struct HistoricalRelease {
    prior: VerifiedRelease,
}
impl HistoricalRelease {
    pub(in crate::pack) fn prior(&self) -> &VerifiedRelease {
        &self.prior
    }
}
pub(in crate::pack) fn historical<C: AsRef<[u8]>, B: AsRef<[u8]>>(
    bootstrap: &[u8],
    chain: &[C],
    final_root: &[u8],
    raw: &BTreeMap<String, B>,
    target: &[u8],
    policy: &ReleasePolicy,
    observed: chrono::DateTime<chrono::Utc>,
) -> CResult<HistoricalRelease> {
    historical_at(
        bootstrap,
        chain,
        final_root,
        raw,
        target,
        policy,
        observed,
        chrono::Utc::now(),
    )
}
fn historical_at<C: AsRef<[u8]>, B: AsRef<[u8]>>(
    bootstrap: &[u8],
    chain: &[C],
    final_root: &[u8],
    raw: &BTreeMap<String, B>,
    target: &[u8],
    policy: &ReleasePolicy,
    observed: chrono::DateTime<chrono::Utc>,
    now: chrono::DateTime<chrono::Utc>,
) -> CResult<HistoricalRelease> {
    let evidence = evidence(bootstrap, chain, final_root, raw, target, policy)?;
    at(&evidence, observed, now)?;
    Ok(HistoricalRelease {
        prior: VerifiedRelease {
            manifest: evidence.manifest,
            anchor: manifest::hash(bootstrap),
            expires: evidence.expires,
            not_before: observed,
            historical: true,
        },
    })
}
#[cfg(test)]
mod tests;
