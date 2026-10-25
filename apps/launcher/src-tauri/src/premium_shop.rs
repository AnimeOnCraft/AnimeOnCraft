use crate::{account_client::Error, auth::Auth};
use reqwest::Method;
use serde::{Deserialize, Serialize};
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Input {
    pub pack: String,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Wallet {
    pub earned: i64,
    pub paid: i64,
    pub total: i64,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Pack {
    pub code: String,
    pub crystals: i64,
    pub rubles: f64,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Order {
    pub id: String,
    pub pack: String,
    pub crystals: i64,
    pub amount: i64,
    pub currency: String,
    pub status: String,
    pub created_at: String,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Snapshot {
    pub wallet: Wallet,
    pub packs: Vec<Pack>,
    pub payments_enabled: bool,
    pub orders: Vec<Order>,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Created {
    pub order_id: String,
    pub confirmation_url: String,
}

async fn call(auth: &Auth, input: Option<&Input>) -> Result<(u64, crate::account_client::Reply), Error> {
    auth.ensure_session().await?;
    let mut retried = false;
    loop {
        let (generation, access) = auth.access()?;
        let reply = match input {
            Some(v) => {
                auth.client
                    .request(Method::POST, "premium/orders", Some(serde_json::json!({"pack":v.pack})), Some(&access), None)
                    .await?
            }
            None => auth.client.request(Method::GET, "premium", None, Some(&access), None).await?,
        };
        if !auth.current(generation) {
            return Err("CANCELLED");
        }
        if reply.status == 401 && !retried {
            retried = true;
            auth.expired(generation, &access);
            auth.ensure_session().await?;
            continue;
        }
        if reply.status == 404 {
            return Err("ACCOUNT_SERVICE_UNAVAILABLE");
        }
        if reply.status == 503 {
            return Err("PAYMENTS_DISABLED");
        }
        reply.success()?;
        return Ok((generation, reply));
    }
}

/// Balance, packs and the last orders of the signed-in account.
pub async fn read(auth: &Auth) -> Result<Snapshot, Error> {
    let (generation, reply) = call(auth, None).await?;
    let result = reply.parse::<Snapshot>()?;
    if result.wallet.earned < 0
        || result.wallet.paid < 0
        || result.wallet.total != result.wallet.earned + result.wallet.paid
        || result.packs.len() > 16
        || result.packs.iter().any(|p| !valid_code(&p.code) || p.crystals <= 0 || !(p.rubles > 0.0 && p.rubles < 100000.0))
        || result.orders.len() > 50
        || result.orders.iter().any(|o| {
            !valid_uuid(&o.id)
                || !valid_code(&o.pack)
                || !["RUB", "XTR"].contains(&o.currency.as_str())
                || !["PENDING", "SUCCEEDED", "CANCELED", "REFUNDED"].contains(&o.status.as_str())
                || chrono::DateTime::parse_from_rfc3339(&o.created_at).is_err()
        })
    {
        return Err("INVALID_RESPONSE");
    }
    auth.publish_security(generation, || Ok(result))
}

/// Creates a payment and opens the YooKassa page in the system browser. Crystals arrive only after the provider confirms the payment.
pub async fn order(auth: &Auth, input: Input) -> Result<Created, Error> {
    if !valid_code(&input.pack) {
        return Err("VALIDATION_ERROR");
    }
    let (generation, reply) = call(auth, Some(&input)).await?;
    let result = reply.parse::<Created>()?;
    if !valid_uuid(&result.order_id) || !payment_page(&result.confirmation_url) {
        return Err("INVALID_RESPONSE");
    }
    let result = auth.publish_security(generation, || Ok(result))?;
    open_browser(&result.confirmation_url)?;
    Ok(result)
}

fn valid_code(value: &str) -> bool {
    (2..=16).contains(&value.len()) && value.bytes().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit())
}
fn valid_uuid(value: &str) -> bool {
    uuid::Uuid::parse_str(value).is_ok_and(|v| v.hyphenated().to_string() == value)
}
/// Only HTTPS pages of YooMoney/YooKassa, with no characters a shell or URL handler could reinterpret.
fn payment_page(value: &str) -> bool {
    let hosts = ["https://yoomoney.ru/", "https://yookassa.ru/", "https://www.yoomoney.ru/"];
    value.len() <= 2048
        && hosts.iter().any(|h| value.starts_with(h))
        && value.bytes().all(|c| c.is_ascii_alphanumeric() || b"-._~:/?#=&%+".contains(&c))
}
fn open_browser(url: &str) -> Result<(), Error> {
    #[cfg(windows)]
    let status = std::process::Command::new("rundll32.exe").args(["url.dll,FileProtocolHandler", url]).spawn();
    #[cfg(not(windows))]
    let status = std::process::Command::new("xdg-open").arg(url).spawn();
    status.map(|_| ()).map_err(|_| "BROWSER_UNAVAILABLE")
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_yookassa_pages_open() {
        assert!(payment_page("https://yoomoney.ru/checkout/payments/v2/contract?orderId=2c7e-000f-5000-9000-1b6"));
        for url in ["http://yoomoney.ru/x", "https://yoomoney.ru.evil.com/x", "https://evil.com/?https://yoomoney.ru/", "https://yoomoney.ru/\"&calc", "file:///C:/x", "https://yoomoney.ru/a b"] {
            assert!(!payment_page(url), "{url}");
        }
    }
    #[test]
    fn codes_and_ids() {
        assert!(valid_code("P520"));
        assert!(!valid_code("p520"));
        assert!(!valid_code("../x"));
        assert!(valid_uuid("123e4567-e89b-42d3-a456-426614174000"));
        assert!(!valid_uuid("123E4567-E89B-42D3-A456-426614174000"));
    }
}
