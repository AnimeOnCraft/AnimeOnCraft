use super::{manifest::MAX_MANIFEST, strict_json};
use async_trait::async_trait;
use futures::TryStreamExt;
use tough::{Transport, TransportError, TransportErrorKind, TransportStream};

/// Fixed base URLs come from a trusted repository configuration, never frontend arguments.
/// Metadata/manifest buffering is bounded; artifact downloads need the separate 7D downloader.
#[derive(Clone, Debug)]
pub struct Guarded<T> {
    inner: T,
    metadata: reqwest::Url,
    targets: reqwest::Url,
}
impl<T> Guarded<T> {
    pub fn new(inner: T, metadata: reqwest::Url, targets: reqwest::Url) -> Self {
        Self {
            inner,
            metadata,
            targets,
        }
    }
}
#[async_trait]
impl<T: Transport + Clone + Send + Sync + 'static> Transport for Guarded<T> {
    async fn fetch(&self, url: reqwest::Url) -> Result<TransportStream, TransportError> {
        let allowed = |base: &reqwest::Url| {
            url.scheme() == "https"
                && url.origin() == base.origin()
                && url.path().starts_with(base.path())
                && url.username().is_empty()
                && url.password().is_none()
                && url.query().is_none()
                && url.fragment().is_none()
        };
        let metadata = allowed(&self.metadata);
        if !metadata && !allowed(&self.targets) {
            return Err(TransportError::new(
                TransportErrorKind::UnsupportedUrlScheme,
                "unapproved repository origin",
            ));
        }
        let fetched = tokio::time::timeout(std::time::Duration::from_secs(15), async {
            let mut stream = self.inner.fetch(url.clone()).await?;
            let mut bytes = Vec::new();
            while let Some(chunk) = stream.try_next().await? {
                if bytes.len() + chunk.len() > MAX_MANIFEST {
                    return Err(TransportError::new(
                        TransportErrorKind::Other,
                        "repository size limit",
                    ));
                }
                bytes.extend_from_slice(&chunk);
            }
            Ok(bytes)
        })
        .await
        .map_err(|_| TransportError::new(TransportErrorKind::Other, "repository deadline"))
        .and_then(|result| result);
        let result = fetched.and_then(|bytes| {
            if metadata {
                strict_json::parse(&bytes, MAX_MANIFEST).map_err(|_| {
                    TransportError::new(TransportErrorKind::Other, "invalid repository JSON")
                })?;
            }
            Ok(bytes)
        });
        // Opening errors for N+1.root otherwise stop tough's chain search without rejecting it.
        // Only a genuine missing root may do that; other failures must abort stream consumption.
        let bytes = match result {
            Ok(bytes) => bytes,
            Err(error)
                if metadata
                    && url.path().ends_with(".root.json")
                    && error.kind() != TransportErrorKind::FileNotFound =>
            {
                return Ok(Box::pin(futures::stream::iter([Err(error)])))
            }
            Err(error) => return Err(error),
        };
        Ok(Box::pin(futures::stream::iter([Ok(bytes::Bytes::from(
            bytes,
        ))])))
    }
}
