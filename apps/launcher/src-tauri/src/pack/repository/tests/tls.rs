use super::*;
use futures::TryStreamExt;
#[test]
#[ignore = "requires the isolated pack acceptance HTTPS server"]
fn repository_tls_verification_and_bounds() {
    crate::pack::tests::runtime().block_on(async {
        let directory =
            std::path::PathBuf::from(std::env::var_os("AOC_PACK_ACCEPTANCE_DIRECTORY").unwrap());
        let certificate = std::fs::read(directory.join("certificate.pem")).unwrap();
        let metadata = "https://localhost:39441/aoc/v1/metadata/";
        let targets = "https://localhost:39441/aoc/v1/targets/";
        let ordinary = Repository::new(metadata, targets).unwrap();
        assert!(
            ordinary
                .fetch(format!("{metadata}client-valid.json").parse().unwrap())
                .await
                .is_err(),
            "Untrusted TLS certificate accepted"
        );
        let trusted = Repository::with_certificate(metadata, targets, &certificate).unwrap();
        let body = trusted
            .fetch(format!("{metadata}client-valid.json").parse().unwrap())
            .await
            .unwrap()
            .try_collect::<Vec<_>>()
            .await
            .unwrap();
        assert_eq!(body.concat(), br#"{"ok":true}"#);
        for suffix in [
            "client-oversize.json",
            "client-duplicate.json",
            "client-encoding.json",
            "client-redirect.json",
        ] {
            assert!(
                trusted
                    .fetch(format!("{metadata}{suffix}").parse().unwrap())
                    .await
                    .is_err(),
                "Accepted {suffix}"
            );
        }
        assert_eq!(
            trusted
                .fetch(format!("{metadata}2.root.json").parse().unwrap())
                .await
                .err()
                .unwrap()
                .kind(),
            TransportErrorKind::FileNotFound
        );
        assert!(
            trusted
                .fetch(format!("{metadata}99.root.json").parse().unwrap())
                .await
                .unwrap()
                .try_collect::<Vec<_>>()
                .await
                .is_err(),
            "HTTP500 silently stopped root search"
        );
        let wrong = Repository::with_certificate(
            "https://127.0.0.1:39441/aoc/v1/metadata/",
            "https://127.0.0.1:39441/aoc/v1/targets/",
            &certificate,
        )
        .unwrap();
        assert!(
            wrong
                .fetch(
                    "https://127.0.0.1:39441/aoc/v1/metadata/client-valid.json"
                        .parse()
                        .unwrap()
                )
                .await
                .is_err(),
            "Wrong TLS hostname accepted"
        );
    });
}
