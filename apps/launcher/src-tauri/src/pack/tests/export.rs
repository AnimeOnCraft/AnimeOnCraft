use super::*;
#[test]
#[ignore = "requires an explicit isolated pack-acceptance export directory"]
fn export_pack_acceptance_repository() {
    runtime().block_on(async {
        let parent = fixture_parent();
        let out = PathBuf::from(std::env::var_os("AOC_PACK_ACCEPTANCE_DIRECTORY").unwrap())
            .canonicalize()
            .unwrap();
        let name = out.file_name().unwrap().to_str().unwrap();
        let id = uuid::Uuid::parse_str(name.strip_prefix("pack-acceptance-").unwrap()).unwrap();
        assert_eq!(name, format!("pack-acceptance-{}", id.simple()));
        assert_eq!(out, parent.join(name));
        assert_eq!(out.canonicalize().unwrap(), out);
        let fixture = Fixture::new().await;
        std::fs::write(out.join("bootstrap.json"), &fixture.bootstrap).unwrap();
        for sequence in [1, 2] {
            let bytes = format!("signed native fixture release {sequence}").into_bytes();
            let mut doc = document(sequence);
            doc.files[0].length = bytes.len() as u64;
            doc.files[0].sha256 = hash(&bytes);
            let memory = fixture.published_manifest(&doc, sequence, false).await;
            let mut files = (*memory.files).clone();
            files.insert("/artifacts/objects/fixture.bin".into(), bytes);
            std::fs::write(
                out.join(format!("release{sequence}.json")),
                serde_json::to_vec(&files).unwrap(),
            )
            .unwrap();
        }
        // Ephemeral private signing keys stay in this process; only signed public data is exported.
    });
}
