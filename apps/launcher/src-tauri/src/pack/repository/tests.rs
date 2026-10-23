use super::*;
use crate::pack::tests::{runtime, Memory};
use std::{collections::BTreeMap, sync::Arc};
mod tls;
#[test]
fn endpoints_reject_unsafe_or_ambiguous_configuration_before_network() {
    let valid = "https://repo.animeoncraft.invalid/aoc/metadata/";
    for value in [
        "http://repo.invalid/metadata/",
        "file:///metadata/",
        "https://user:secret@repo.invalid/metadata/",
        "https://repo.invalid/metadata/?key=secret",
        "https://repo.invalid/metadata/#x",
        "https://repo.invalid/a/../metadata/",
        "https://repo.invalid/%6d/",
        "https://repo.invalid/metadata",
        "https://repo.invalid/metadata//",
        "https://repo.invalid/",
        "https://REPO.invalid/metadata/",
    ] {
        assert!(base(value).is_err(), "{value}");
    }
    assert!(Endpoints::new(valid, "https://other.invalid/targets/").is_err());
    assert!(Endpoints::new(valid, valid).is_err());
    let endpoints =
        Endpoints::new(valid, "https://repo.animeoncraft.invalid/aoc/targets/").unwrap();
    assert_eq!(
        endpoints
            .canonical(&format!("{valid}timestamp.json").parse().unwrap())
            .unwrap(),
        "/metadata/timestamp.json"
    );
    assert!(endpoints
        .canonical(
            &"https://repo.animeoncraft.invalid/aoc/metadata-evil/1.root.json"
                .parse()
                .unwrap()
        )
        .is_err());
    assert!(endpoints
        .canonical(
            &"https://repo.animeoncraft.invalid/aoc/metadata/%2fsecret"
                .parse()
                .unwrap()
        )
        .is_err());
}
#[test]
fn concrete_https_transport_refuses_other_paths_and_http_before_socket() {
    runtime().block_on(async {
        let repository = Repository::new(
            "https://127.0.0.1:39431/metadata/",
            "https://127.0.0.1:39431/targets/",
        )
        .unwrap();
        for value in [
            "http://127.0.0.1:39431/metadata/timestamp.json",
            "https://127.0.0.1:39431/private/secrets.json",
            "https://127.0.0.1:39431/metadata/timestamp.json?token=x",
        ] {
            let error = repository
                .fetch(value.parse().unwrap())
                .await
                .err()
                .unwrap();
            assert!(error.to_string().contains("SOURCE_NOT_APPROVED"));
        }
        let repository = Repository::fixture(
            "https://fixtures.animeoncraft.invalid/metadata/",
            "https://fixtures.animeoncraft.invalid/targets/",
            Memory {
                files: Arc::new(BTreeMap::new()),
                crash: false,
            },
        );
        assert_eq!(
            repository
                .fetch(
                    "https://fixtures.animeoncraft.invalid/metadata/2.root.json"
                        .parse()
                        .unwrap()
                )
                .await
                .err()
                .unwrap()
                .kind(),
            TransportErrorKind::FileNotFound
        );
    });
}
