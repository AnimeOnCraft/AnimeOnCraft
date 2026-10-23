//! Explicit installed fixture only. No normal configuration/environment fallback.
use super::*;
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct PublicConfig {
    metadata_key: String,
    installer_key: String,
}
pub(super) fn configuration(current: &str) -> Result<(Option<Config>, Option<transport::Http>)> {
    let directory = crate::installation_fixture::directory()?;
    let bounded = |name: &str| -> Result<Vec<u8>> {
        use std::io::Read;
        let mut bytes = Vec::new();
        std::fs::File::open(directory.join(name))
            .map_err(|_| "UPDATE_FIXTURE_INVALID")?
            .take(8193)
            .read_to_end(&mut bytes)
            .map_err(|_| "UPDATE_FIXTURE_INVALID")?;
        if bytes.is_empty() || bytes.len() > 8192 {
            return Err("UPDATE_FIXTURE_INVALID");
        }
        Ok(bytes)
    };
    let values: PublicConfig =
        serde_json::from_value(strict_json::parse(&bounded("updater.json")?, 8192)?)
            .map_err(|_| "UPDATE_FIXTURE_INVALID")?;
    let config = Config::new(
        "https://localhost:39445/aoc/launcher/",
        &values.metadata_key,
        &values.installer_key,
        current,
    )?;
    let http = transport::Http::with_certificate(&bounded("certificate.pem")?)?;
    Ok((Some(config), Some(http)))
}
