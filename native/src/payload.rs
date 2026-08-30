use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use std::sync::OnceLock;

const HEADER: &[u8; 4] = b"SKY1";
const NONCE_LENGTH: usize = 12;
const TAG_LENGTH: usize = 16;
static CONTENT_KEY: OnceLock<Option<[u8; 32]>> = OnceLock::new();

/** The key is injected only into release-native compilation by Gradle. */
fn content_key() -> Option<[u8; 32]> {
    *CONTENT_KEY.get_or_init(|| parse_hex(option_env!("PERFECTPATCH_PAYLOAD_KEY").unwrap_or("")))
}

/** License-gated authenticated decryption of the opaque Java resource. */
pub fn decrypt(encrypted: &[u8]) -> Option<Vec<u8>> {
    if !crate::license::simple_assist_authorized()
        || encrypted.len() < HEADER.len() + NONCE_LENGTH + TAG_LENGTH
        || &encrypted[..HEADER.len()] != HEADER {
        return None;
    }
    let key = content_key()?;
    let nonce_start = HEADER.len();
    let nonce_end = nonce_start + NONCE_LENGTH;
    let cipher = Aes256Gcm::new_from_slice(&key).ok()?;
    cipher.decrypt(Nonce::from_slice(&encrypted[nonce_start..nonce_end]),
                   &encrypted[nonce_end..]).ok()
}

fn parse_hex(value: &str) -> Option<[u8; 32]> {
    if value.len() != 64 { return None; }
    let mut key = [0u8; 32];
    for index in 0..32 {
        let high = nibble(value.as_bytes()[index * 2])?;
        let low = nibble(value.as_bytes()[index * 2 + 1])?;
        key[index] = (high << 4) | low;
    }
    Some(key)
}

fn nibble(value: u8) -> Option<u8> {
    match value {
        b'0'..=b'9' => Some(value - b'0'),
        b'a'..=b'f' => Some(value - b'a' + 10),
        b'A'..=b'F' => Some(value - b'A' + 10),
        _ => None,
    }
}
