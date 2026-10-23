use crate::account_client::Error;
pub fn validate_origin(value: &str) -> Result<String, Error> {
    let url = reqwest::Url::parse(value).map_err(|_| "API_NOT_CONFIGURED")?;
    let local = url.scheme() == "http" && url.host_str() == Some("127.0.0.1");
    if (!local && url.scheme() != "https")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || url.path() != "/"
    {
        return Err("API_NOT_CONFIGURED");
    }
    Ok(url.as_str().trim_end_matches('/').to_string())
}
pub fn public_origin() -> Result<String, Error> {
    if cfg!(any(
        feature = "native-acceptance",
        feature = "pack-acceptance",
        feature = "installer-acceptance"
    )) {
        return Ok("http://127.0.0.1:3000".into());
    }
    validate_origin(option_env!("AOC_API_ORIGIN").unwrap_or("http://127.0.0.1:3000"))
}
pub fn game_host(value: &str) -> bool {
    if value.parse::<std::net::Ipv4Addr>().is_ok() {
        return true;
    }
    if value.is_empty()
        || value.len() > 253
        || value.bytes().all(|b| b.is_ascii_digit() || b == b'.')
    {
        return false;
    }
    value.split('.').all(|p| {
        !p.is_empty()
            && p.len() <= 63
            && !p.starts_with('-')
            && !p.ends_with('-')
            && p.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'-')
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn permits_https_and_keeps_plain_http_local() {
        assert_eq!(
            validate_origin("https://api.example.test").unwrap(),
            "https://api.example.test"
        );
        assert!(validate_origin("http://127.0.0.1:3000").is_ok());
        for value in [
            "http://api.example.test",
            "https://u:p@api.example.test",
            "https://api.example.test/path",
            "https://api.example.test?q=x",
            "https://api.example.test/#x",
        ] {
            assert!(validate_origin(value).is_err());
        }
    }
    #[test]
    fn host_is_a_host_without_paths_credentials_or_ports() {
        for value in ["127.0.0.1", "203.0.113.10", "play.example.test"] {
            assert!(game_host(value));
        }
        for value in [
            "",
            "https://play.example.test",
            "user@host",
            "host:25565",
            "bad..host",
            "999.1.2.3",
            "-bad.test",
        ] {
            assert!(!game_host(value));
        }
    }
}
