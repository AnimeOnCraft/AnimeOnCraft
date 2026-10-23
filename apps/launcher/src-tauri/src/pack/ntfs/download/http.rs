use super::*;
// Separate client: no account client injection, credentials, cookie store or automatic redirects.
pub(super) struct Http(reqwest::Client);
impl Http {
    pub(super) fn new() -> DResult<Self> {
        Self::build(false)
    }
    #[cfg(test)]
    pub(super) fn fixture(allow_http: bool) -> DResult<Self> {
        Self::build(allow_http)
    }
    fn build(allow_http: bool) -> DResult<Self> {
        Self::builder(allow_http)
            .build()
            .map(Self)
            .map_err(|_| "DOWNLOAD_CLIENT")
    }
    #[cfg(feature = "pack-acceptance")]
    pub(super) fn with_certificate(pem: &[u8]) -> DResult<Self> {
        let certificate = reqwest::Certificate::from_pem(pem).map_err(|_| "DOWNLOAD_CLIENT")?;
        Self::builder(false)
            .add_root_certificate(certificate)
            .build()
            .map(Self)
            .map_err(|_| "DOWNLOAD_CLIENT")
    }
    fn builder(allow_http: bool) -> reqwest::ClientBuilder {
        reqwest::Client::builder()
            .https_only(!allow_http)
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
            .timeout(Duration::from_secs(600))
            .http1_only()
            // A failed body can leave a pooled connection awaiting its driver cleanup.
            // Each bounded attempt uses a fresh connection, including zero-backoff retries.
            .pool_max_idle_per_host(0)
            .http1_max_headers(64)
    }
}
struct Stream(reqwest::Response);
#[async_trait::async_trait]
impl Body for Stream {
    async fn next(&mut self) -> DResult<Option<Bytes>> {
        self.0.chunk().await.map_err(|_| "DOWNLOAD_NETWORK")
    }
}
#[async_trait::async_trait]
impl Transport for Http {
    async fn open(&self, url: Url) -> DResult<Reply> {
        let reply = self
            .0
            .get(url)
            .header(header::ACCEPT_ENCODING, "identity")
            .send()
            .await
            .map_err(|_| "DOWNLOAD_NETWORK")?;
        Ok(Reply {
            status: reply.status().as_u16(),
            headers: reply.headers().clone(),
            body: Box::new(Stream(reply)),
        })
    }
}
