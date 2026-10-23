use super::Result;
use futures::future::{select, Either};
use reqwest::{header, Url};
use std::time::Duration;
pub(super) struct Http {
    client: reqwest::Client,
}
pub(super) fn cancelled(cancel: &tokio::sync::watch::Receiver<bool>) -> Result<()> {
    if *cancel.borrow() || cancel.has_changed().is_err() {
        return Err("UPDATE_CANCELLED");
    }
    Ok(())
}
impl Http {
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
            .timeout(Duration::from_secs(60))
            .http1_only()
            .pool_max_idle_per_host(0)
            .http1_max_headers(64)
    }
    pub(super) fn new() -> Result<Self> {
        Ok(Self {
            client: Self::builder().build().map_err(|_| "UPDATE_NETWORK")?,
        })
    }
    #[cfg(any(test, feature = "self-update-acceptance"))]
    pub(super) fn with_certificate(pem: &[u8]) -> Result<Self> {
        let certificate = reqwest::Certificate::from_pem(pem).map_err(|_| "UPDATE_NETWORK")?;
        Ok(Self {
            client: Self::builder()
                .add_root_certificate(certificate)
                .build()
                .map_err(|_| "UPDATE_NETWORK")?,
        })
    }
    pub(super) async fn fetch(
        &self,
        url: Url,
        limit: u64,
        exact: Option<u64>,
        cancel: &mut tokio::sync::watch::Receiver<bool>,
    ) -> Result<Vec<u8>> {
        cancelled(cancel)?;
        if url.scheme() != "https"
            || !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
        {
            return Err("UPDATE_SOURCE_INVALID");
        }
        let request = self
            .client
            .get(url)
            .header(header::ACCEPT_ENCODING, "identity")
            .send();
        let mut reply = match select(Box::pin(cancel.changed()), Box::pin(request)).await {
            Either::Left(_) => return Err("UPDATE_CANCELLED"),
            Either::Right((reply, _)) => reply.map_err(|_| "UPDATE_NETWORK")?,
        };
        if reply.status().as_u16() != 200 {
            return Err("UPDATE_HTTP_STATUS");
        }
        for name in [header::CONTENT_ENCODING, header::CONTENT_LENGTH] {
            if reply.headers().get_all(name).iter().count() > 1 {
                return Err("UPDATE_BODY_INVALID");
            }
        }
        if reply
            .headers()
            .get(header::CONTENT_ENCODING)
            .is_some_and(|v| v.as_bytes() != b"identity")
            || reply
                .content_length()
                .is_some_and(|n| n > limit || exact.is_some_and(|size| n != size))
        {
            return Err("UPDATE_BODY_INVALID");
        }
        let mut bytes = Vec::new();
        loop {
            let chunk = match select(Box::pin(cancel.changed()), Box::pin(reply.chunk())).await {
                Either::Left(_) => return Err("UPDATE_CANCELLED"),
                Either::Right((chunk, _)) => chunk.map_err(|_| "UPDATE_NETWORK")?,
            };
            cancelled(cancel)?;
            let Some(chunk) = chunk else {
                break;
            };
            if (bytes.len() as u64)
                .checked_add(chunk.len() as u64)
                .is_none_or(|n| n > limit)
            {
                return Err("UPDATE_BODY_INVALID");
            }
            bytes
                .try_reserve_exact(chunk.len())
                .map_err(|_| "UPDATE_BODY_INVALID")?;
            bytes.extend_from_slice(&chunk);
        }
        if exact.is_some_and(|n| n != bytes.len() as u64) {
            return Err("UPDATE_BODY_INVALID");
        }
        Ok(bytes)
    }
}
