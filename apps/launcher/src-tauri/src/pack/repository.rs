//! Trusted native endpoints and credential-free HTTPS metadata transport. No IPC constructor.
use super::{manifest, transport::Guarded};
use reqwest::{header, Url};
use std::time::Duration;
use tough::{Transport, TransportError, TransportErrorKind, TransportStream};
pub(super) fn base(value: &str) -> Result<Url, &'static str> {
    if value.len() > 512
        || !value.is_ascii()
        || value.bytes().any(|b| b <= 32 || b"%\\?#".contains(&b))
    {
        return Err("REPOSITORY_CONFIG_INVALID");
    }
    let url = Url::parse(value).map_err(|_| "REPOSITORY_CONFIG_INVALID")?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || !url.path().ends_with('/')
        || url.path().contains("//")
        || url.as_str() != value
        || !manifest::portable(url.path().trim_matches('/'))
    {
        return Err("REPOSITORY_CONFIG_INVALID");
    }
    Ok(url)
}
#[derive(Clone, Debug)]
pub(super) struct Endpoints {
    pub(super) metadata: Url,
    pub(super) targets: Url,
}
impl Endpoints {
    pub(super) fn new(metadata: &str, targets: &str) -> Result<Self, &'static str> {
        let metadata = base(metadata)?;
        let targets = base(targets)?;
        if metadata.origin() != targets.origin()
            || metadata.path().starts_with(targets.path())
            || targets.path().starts_with(metadata.path())
        {
            return Err("REPOSITORY_CONFIG_INVALID");
        }
        Ok(Self { metadata, targets })
    }
    pub(super) fn canonical(&self, url: &Url) -> Result<String, &'static str> {
        if url.scheme() != "https"
            || !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
        {
            return Err("SOURCE_NOT_APPROVED");
        }
        for (base, label) in [(&self.metadata, "metadata"), (&self.targets, "targets")] {
            if url.origin() == base.origin() {
                if let Some(path) = url
                    .path()
                    .strip_prefix(base.path())
                    .filter(|p| manifest::portable(p))
                {
                    return Ok(format!("/{label}/{path}"));
                }
            }
        }
        Err("SOURCE_NOT_APPROVED")
    }
}
#[derive(Clone, Debug)]
pub(super) struct Repository {
    pub(super) endpoints: Endpoints,
    pub(super) transport: Box<dyn Transport>,
}
impl Repository {
    #[cfg(any(test, feature = "pack-acceptance"))]
    pub(super) fn with_certificate(
        metadata: &str,
        targets: &str,
        pem: &[u8],
    ) -> Result<Self, &'static str> {
        let endpoints = Endpoints::new(metadata, targets)?;
        let client = Http::with_certificate(endpoints.clone(), pem)?;
        Ok(Self {
            endpoints: endpoints.clone(),
            transport: Box::new(Guarded::new(client, endpoints.metadata, endpoints.targets)),
        })
    }
    pub(super) fn new(metadata: &str, targets: &str) -> Result<Self, &'static str> {
        let endpoints = Endpoints::new(metadata, targets)?;
        let client = Http::new(endpoints.clone())?;
        Ok(Self {
            endpoints: endpoints.clone(),
            transport: Box::new(Guarded::new(client, endpoints.metadata, endpoints.targets)),
        })
    }
    #[cfg(test)]
    pub(super) fn fixture(
        metadata: &str,
        targets: &str,
        transport: impl Transport + Clone + 'static,
    ) -> Self {
        let endpoints = Endpoints::new(metadata, targets).unwrap();
        Self {
            endpoints: endpoints.clone(),
            transport: Box::new(Guarded::new(
                transport,
                endpoints.metadata,
                endpoints.targets,
            )),
        }
    }
}
#[async_trait::async_trait]
impl Transport for Repository {
    async fn fetch(&self, url: Url) -> Result<TransportStream, TransportError> {
        self.endpoints.canonical(&url).map_err(error)?;
        self.transport.fetch(url).await
    }
}
#[derive(Clone, Debug)]
struct Http {
    client: reqwest::Client,
    endpoints: Endpoints,
}
impl Http {
    fn new(endpoints: Endpoints) -> Result<Self, &'static str> {
        let client = Self::builder().build().map_err(|_| "REPOSITORY_CLIENT")?;
        Ok(Self { client, endpoints })
    }
    #[cfg(any(test, feature = "pack-acceptance"))]
    fn with_certificate(endpoints: Endpoints, pem: &[u8]) -> Result<Self, &'static str> {
        let certificate = reqwest::Certificate::from_pem(pem).map_err(|_| "REPOSITORY_CLIENT")?;
        let client = Self::builder()
            .add_root_certificate(certificate)
            .build()
            .map_err(|_| "REPOSITORY_CLIENT")?;
        Ok(Self { client, endpoints })
    }
    fn builder() -> reqwest::ClientBuilder {
        reqwest::Client::builder()
            .https_only(true)
            .no_proxy()
            .no_gzip()
            .no_brotli()
            .no_deflate()
            .no_zstd()
            .redirect(reqwest::redirect::Policy::none())
            .referer(false)
            .retry(reqwest::retry::never())
            .connect_timeout(Duration::from_secs(5))
            .read_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(15))
            .http1_only()
            .pool_max_idle_per_host(0)
            .http1_max_headers(64)
    }
}
fn error(message: &'static str) -> TransportError {
    TransportError::new(TransportErrorKind::Other, message)
}
#[async_trait::async_trait]
impl Transport for Http {
    async fn fetch(&self, url: Url) -> Result<TransportStream, TransportError> {
        self.endpoints.canonical(&url).map_err(error)?;
        let reply = self
            .client
            .get(url)
            .header(header::ACCEPT_ENCODING, "identity")
            .send()
            .await
            .map_err(|_| error("metadata network"))?;
        if reply.status().as_u16() == 404 {
            return Err(TransportError::new(
                TransportErrorKind::FileNotFound,
                "metadata missing",
            ));
        }
        if reply.status().as_u16() != 200 {
            return Err(error("metadata HTTP status"));
        }
        for name in [header::CONTENT_ENCODING, header::CONTENT_LENGTH] {
            if reply.headers().get_all(name).iter().count() > 1 {
                return Err(error("metadata headers"));
            }
        }
        if reply
            .headers()
            .get(header::CONTENT_ENCODING)
            .is_some_and(|v| v.as_bytes() != b"identity")
            || reply
                .content_length()
                .is_some_and(|n| n > manifest::MAX_MANIFEST as u64)
        {
            return Err(error("metadata body limit"));
        }
        Ok(Box::pin(futures::stream::try_unfold(
            reply,
            |mut reply| async {
                match reply.chunk().await.map_err(|_| error("metadata body"))? {
                    Some(chunk) => Ok(Some((chunk, reply))),
                    None => Ok(None),
                }
            },
        )))
    }
}
#[cfg(test)]
mod tests;
