//! Minecraft Server List Ping, without player credentials or a Java process.
use serde::{Deserialize, Serialize};
use std::time::{Duration, Instant};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
};

const PROTOCOL: u32 = 767; // Mojang 1.21.1 client JAR, version.json.
const LIMIT: usize = 65536;

#[derive(Clone, Serialize)]
pub struct Snapshot {
    pub status: &'static str,
    pub players_online: Option<u32>,
    pub players_max: Option<u32>,
    pub latency_ms: Option<u32>,
    pub checked_at: String,
}
impl Snapshot {
    pub fn unavailable(status: &'static str) -> Self {
        Self {
            status,
            players_online: None,
            players_max: None,
            latency_ms: None,
            checked_at: chrono::Utc::now().to_rfc3339(),
        }
    }
}
#[derive(Deserialize)]
struct Wire {
    version: Version,
    players: Option<Players>,
}
#[derive(Deserialize)]
struct Version {
    protocol: u32,
}
#[derive(Deserialize)]
struct Players {
    online: u32,
    max: u32,
}

fn varint(mut value: u32) -> Vec<u8> {
    let mut bytes = Vec::new();
    loop {
        let mut byte = (value & 127) as u8;
        value >>= 7;
        if value != 0 {
            byte |= 128;
        }
        bytes.push(byte);
        if value == 0 {
            return bytes;
        }
    }
}
fn take_varint(bytes: &mut &[u8]) -> Result<u32, ()> {
    let mut result = 0;
    for index in 0..5 {
        let (&byte, remaining) = bytes.split_first().ok_or(())?;
        *bytes = remaining;
        if index == 4 && byte > 7 {
            return Err(());
        }
        result |= ((byte & 127) as u32) << (7 * index);
        if byte & 128 == 0 {
            if varint(result).len() != index + 1 {
                return Err(());
            }
            return Ok(result);
        }
    }
    Err(())
}
async fn read_length(stream: &mut TcpStream) -> Result<usize, ()> {
    let mut bytes = Vec::new();
    for _ in 0..5 {
        let byte = stream.read_u8().await.map_err(|_| ())?;
        bytes.push(byte);
        if byte & 128 == 0 {
            let value = take_varint(&mut bytes.as_slice())? as usize;
            return if (1..=LIMIT).contains(&value) {
                Ok(value)
            } else {
                Err(())
            };
        }
    }
    Err(())
}
fn parse(packet: &[u8], elapsed: u32) -> Result<Snapshot, ()> {
    let mut body = packet;
    if take_varint(&mut body)? != 0 {
        return Err(());
    }
    let size = take_varint(&mut body)? as usize;
    if size != body.len() || size > LIMIT {
        return Err(());
    }
    let wire: Wire = serde_json::from_slice(body).map_err(|_| ())?;
    if wire
        .players
        .as_ref()
        .is_some_and(|p| p.online > 1_000_000 || (p.max > 1_000_000 && p.max != i32::MAX as u32))
    {
        return Err(());
    }
    let mut value = Snapshot::unavailable(if wire.version.protocol == PROTOCOL {
        "AVAILABLE"
    } else {
        "INCOMPATIBLE"
    });
    if let Some(players) = wire.players {
        value.players_online = Some(players.online);
        // Zero in the native view denotes no configured practical slot limit.
        value.players_max = Some(if players.max == i32::MAX as u32 {
            0
        } else {
            players.max
        });
    }
    value.latency_ms = Some(elapsed);
    Ok(value)
}

pub async fn probe(host: &str, port: u16) -> Snapshot {
    // The destination is taken only from validated local runtime; no IPC URL/host.
    let started = Instant::now();
    let result = tokio::time::timeout(Duration::from_secs(2), async {
        let mut stream = TcpStream::connect((host, port))
            .await
            .map_err(|_| "OFFLINE")?;
        let host = host.as_bytes();
        let mut handshake = vec![0];
        handshake.extend(varint(PROTOCOL));
        handshake.extend(varint(host.len() as u32));
        handshake.extend(host);
        handshake.extend(port.to_be_bytes());
        handshake.push(1);
        let mut request = varint(handshake.len() as u32);
        request.extend(handshake);
        request.extend([1, 0]);
        stream.write_all(&request).await.map_err(|_| "OFFLINE")?;
        let length = read_length(&mut stream)
            .await
            .map_err(|_| "INVALID_RESPONSE")?;
        let mut packet = vec![0; length];
        stream
            .read_exact(&mut packet)
            .await
            .map_err(|_| "INVALID_RESPONSE")?;
        parse(
            &packet,
            started.elapsed().as_millis().min(u32::MAX as u128) as u32,
        )
        .map_err(|_| "INVALID_RESPONSE")
    })
    .await;
    match result {
        Ok(Ok(snapshot)) => snapshot,
        Ok(Err(status)) => Snapshot::unavailable(status),
        Err(_) => Snapshot::unavailable("OFFLINE"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn packet(json: &str) -> Vec<u8> {
        let mut packet = vec![0];
        packet.extend(varint(json.len() as u32));
        packet.extend(json.as_bytes());
        packet
    }
    #[test]
    fn preserves_real_counts_without_exposing_names_motd_or_favicon() {
        let value=parse(&packet(r#"{"version":{"protocol":767,"name":"anything"},"players":{"online":2,"max":15,"sample":[{"name":"PrivatePlayer"}]},"description":{"text":"<script>"},"favicon":"arbitrary"}"#),12).unwrap();
        assert_eq!(value.status, "AVAILABLE");
        assert_eq!(value.players_online, Some(2));
        assert_eq!(value.players_max, Some(15));
        let json = serde_json::to_string(&value).unwrap();
        assert!(
            !json.contains("PrivatePlayer")
                && !json.contains("script")
                && !json.contains("favicon")
        );
        let hidden = parse(&packet(r#"{"version":{"protocol":767}}"#), 1).unwrap();
        assert_eq!(hidden.players_online, None);
        assert_eq!(
            parse(&packet(r#"{"version":{"protocol":1}}"#), 1)
                .unwrap()
                .status,
            "INCOMPATIBLE"
        );
    }
    #[test]
    fn java_unlimited_slots_are_normalized() {
        let value = parse(
            &packet(r#"{"version":{"protocol":767},"players":{"online":21,"max":2147483647}}"#),
            3,
        )
        .unwrap();
        assert_eq!(value.status, "AVAILABLE");
        assert_eq!(value.players_online, Some(21));
        assert_eq!(value.players_max, Some(0));
    }
    #[test]
    fn rejects_wrong_packets_overflows_and_invalid_player_counts() {
        for bytes in [
            vec![0x80, 0],
            vec![0xff, 0xff, 0xff, 0xff, 0x7f],
            vec![0x80; 6],
        ] {
            assert!(take_varint(&mut bytes.as_slice()).is_err());
        }
        for body in [
            r#"{"version":{"protocol":767},"players":{"online":-1,"max":15}}"#,
            r#"{"version":{"protocol":767},"players":{"online":0,"max":1000001}}"#,
            "{}",
        ] {
            assert!(parse(&packet(body), 1).is_err());
        }
        assert!(parse(&[1, 2, b'{', b'}'], 1).is_err());
        assert!(parse(&[0, 1, b'{', b'}'], 1).is_err());
    }
    #[tokio::test]
    async fn reads_fragmented_status_and_bounds_frame_allocation() {
        for oversized in [false, true] {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let port = listener.local_addr().unwrap().port();
            let server = tokio::spawn(async move {
                let (mut stream, _) = listener.accept().await.unwrap();
                let mut request = [0u8; 128];
                let _ = stream.read(&mut request).await.unwrap();
                let bytes = if oversized {
                    varint((LIMIT + 1) as u32)
                } else {
                    let body =
                        packet(r#"{"version":{"protocol":767},"players":{"online":0,"max":8}}"#);
                    let mut bytes = varint(body.len() as u32);
                    bytes.extend(body);
                    bytes
                };
                for byte in bytes {
                    if stream.write_all(&[byte]).await.is_err() {
                        break;
                    }
                }
            });
            let result = probe("127.0.0.1", port).await;
            assert_eq!(
                result.status,
                if oversized {
                    "INVALID_RESPONSE"
                } else {
                    "AVAILABLE"
                }
            );
            server.await.unwrap();
        }
    }
}
