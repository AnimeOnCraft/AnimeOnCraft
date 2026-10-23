use super::*;
use crate::pack::tests::{self as publisher, runtime};
async fn bodies(p: &publisher::Fixture) -> (BTreeMap<String, Vec<u8>>, Vec<u8>) {
    bodies_expiry(p, false).await
}
async fn bodies_expiry(
    p: &publisher::Fixture,
    expired: bool,
) -> (BTreeMap<String, Vec<u8>>, Vec<u8>) {
    let data = p.published(1, 1, expired).await;
    let raw = ["timestamp.json", "snapshot.json", "targets.json"]
        .into_iter()
        .map(|name| {
            let body = data
                .files
                .iter()
                .find(|(path, _)| path.starts_with("/metadata/") && path.ends_with(name))
                .unwrap()
                .1
                .clone();
            (name.to_owned(), body)
        })
        .collect();
    let target = data
        .files
        .iter()
        .find(|(path, _)| path.starts_with("/targets/"))
        .unwrap()
        .1
        .clone();
    (raw, target)
}
#[test]
fn actually_expired_signed_roles_allow_only_historical_integrity_comparison() {
    runtime().block_on(async {
        let p = publisher::Fixture::new().await;
        let (raw, target) = bodies_expiry(&p, true).await;
        let chain: Vec<Vec<u8>> = Vec::new();
        let expiry: serde_json::Value = serde_json::from_slice(&raw["timestamp.json"]).unwrap();
        let observed =
            chrono::DateTime::parse_from_rfc3339(expiry["signed"]["expires"].as_str().unwrap())
                .unwrap()
                .with_timezone(&chrono::Utc)
                - chrono::Duration::hours(1);
        assert!(matches!(
            current(
                &p.bootstrap,
                &chain,
                &p.bootstrap,
                &raw,
                &target,
                &publisher::policy(1),
                observed
            ),
            Err("RELEASE_EXPIRED")
        ));
        let old = historical(
            &p.bootstrap,
            &chain,
            &p.bootstrap,
            &raw,
            &target,
            &publisher::policy(1),
            observed,
        )
        .unwrap();
        assert_eq!(old.prior().current(), Err("HISTORICAL_RELEASE_NOT_CURRENT"));
    });
}
#[test]
fn historical_expiry_never_grants_current_authority_and_observation_is_checked() {
    runtime().block_on(async {
        let p = publisher::Fixture::new().await;
        let (raw, target) = bodies(&p).await;
        let chain: Vec<Vec<u8>> = Vec::new();
        let policy = publisher::policy(1);
        let valid = evidence(&p.bootstrap, &chain, &p.bootstrap, &raw, &target, &policy).unwrap();
        let observed = chrono::Utc::now();
        let later = valid.expires + chrono::Duration::days(1);
        let historical = historical_at(
            &p.bootstrap,
            &chain,
            &p.bootstrap,
            &raw,
            &target,
            &policy,
            observed,
            later,
        )
        .unwrap();
        assert_eq!(
            historical.prior().current(),
            Err("HISTORICAL_RELEASE_NOT_CURRENT")
        );
        assert_eq!(
            historical.prior().manifest().digest(),
            manifest::hash(&target)
        );
        assert!(matches!(
            historical_at(
                &p.bootstrap,
                &chain,
                &p.bootstrap,
                &raw,
                &target,
                &policy,
                valid.expires,
                later
            ),
            Err("HISTORY_OBSERVATION_EXPIRED")
        ));
        assert!(matches!(
            historical_at(
                &p.bootstrap,
                &chain,
                &p.bootstrap,
                &raw,
                &target,
                &policy,
                observed,
                observed - chrono::Duration::seconds(1)
            ),
            Err("CLOCK_ERROR")
        ));
        let mut proof = current(
            &p.bootstrap,
            &chain,
            &p.bootstrap,
            &raw,
            &target,
            &policy,
            observed,
        )
        .unwrap();
        proof.fixture_expire();
        assert_eq!(proof.current(), Err("RELEASE_EXPIRED"));
    });
}
#[test]
fn cached_factory_rejects_signature_hash_bootstrap_and_source_changes() {
    runtime().block_on(async {
        let p = publisher::Fixture::new().await;
        let foreign = publisher::Fixture::new().await;
        let (raw, target) = bodies(&p).await;
        let chain: Vec<Vec<u8>> = Vec::new();
        let observed = chrono::Utc::now();
        assert!(current(
            &foreign.bootstrap,
            &chain,
            &p.bootstrap,
            &raw,
            &target,
            &publisher::policy(1),
            observed
        )
        .is_err());
        let mut tampered = raw.clone();
        let mut json: serde_json::Value = serde_json::from_slice(&raw["timestamp.json"]).unwrap();
        json["signed"]["version"] = 999.into();
        tampered.insert("timestamp.json".into(), serde_json::to_vec(&json).unwrap());
        assert!(current(
            &p.bootstrap,
            &chain,
            &p.bootstrap,
            &tampered,
            &target,
            &publisher::policy(1),
            observed
        )
        .is_err());
        let mut target_changed = target.clone();
        target_changed.push(b' ');
        assert!(current(
            &p.bootstrap,
            &chain,
            &p.bootstrap,
            &raw,
            &target_changed,
            &publisher::policy(1),
            observed
        )
        .is_err());
        let mut removed = publisher::policy(1);
        removed.approved_sources.clear();
        assert!(current(
            &p.bootstrap,
            &chain,
            &p.bootstrap,
            &raw,
            &target,
            &removed,
            observed
        )
        .is_err());
    });
}
