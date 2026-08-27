use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use sha2::{Digest, Sha256};
use std::fs;
use std::path::PathBuf;
use std::sync::OnceLock;

const MAGIC: &str = "PPL1";
const VERSION: &str = "1";
const FEATURES_ALL: u32 = 7;
const PUBLIC_KEY: [u8; 32] = [
    0x02, 0xbc, 0xaf, 0xaf, 0xe5, 0x78, 0x98, 0x60,
    0xba, 0x08, 0x0a, 0x13, 0xac, 0xf1, 0x56, 0x62,
    0x37, 0x30, 0x3d, 0x86, 0xf5, 0x2f, 0xa2, 0x91,
    0x18, 0x58, 0x68, 0x29, 0x87, 0xfe, 0xea, 0x6a,
];

static AUTHORIZED: OnceLock<bool> = OnceLock::new();
static GAME_DIRECTORY: OnceLock<PathBuf> = OnceLock::new();

pub fn simple_assist_authorized() -> bool {
    *AUTHORIZED.get_or_init(check_license)
}

/** Creates the offline activation claim as soon as the native payload loads. */
pub fn initialize_claim(game_directory: Option<String>) {
    if let Some(game_directory) = game_directory {
        if !game_directory.is_empty() {
            let _ = GAME_DIRECTORY.set(PathBuf::from(game_directory));
        }
    }
    let _ = simple_assist_authorized();
}

fn check_license() -> bool {
    let claim = device_claim();
    let directory = license_directory();
    if let Err(_) = fs::create_dir_all(&directory) { return false; }
    let claim_path = directory.join("claim.txt");
    if !claim_path.exists() {
        let _ = fs::write(&claim_path, format!("{}\n", claim));
    }
    let Ok(contents) = fs::read_to_string(directory.join("license.bin")) else {
        return false;
    };
    let mut lines = contents.lines();
    let (Some(magic), Some(version), Some(features), Some(licensed_claim), Some(signature)) =
        (lines.next(), lines.next(), lines.next(), lines.next(), lines.next()) else { return false; };
    if magic != MAGIC || version != VERSION || licensed_claim != claim || lines.next().is_some() {
        return false;
    }
    let Ok(features) = features.parse::<u32>() else { return false; };
    if features & FEATURES_ALL != FEATURES_ALL { return false; }
    let Ok(signature) = hex_bytes(signature) else { return false; };
    let Ok(signature) = Signature::from_slice(&signature) else { return false; };
    let Ok(public_key) = VerifyingKey::from_bytes(&PUBLIC_KEY) else { return false; };
    let payload = format!("{}\n{}\n{}\n{}\n", MAGIC, VERSION, features, claim);
    public_key.verify(payload.as_bytes(), &signature).is_ok()
}

fn device_claim() -> String {
    let source = machine_source();
    let mut digest = Sha256::new();
    digest.update(b"perfectpatch-license-v1\0");
    digest.update(source.as_bytes());
    hex(&digest.finalize())
}

#[cfg(target_os = "windows")]
fn machine_source() -> String {
    use winreg::enums::HKEY_LOCAL_MACHINE;
    use winreg::RegKey;
    let registry = RegKey::predef(HKEY_LOCAL_MACHINE);
    registry.open_subkey("SOFTWARE\\Microsoft\\Cryptography")
        .and_then(|key| key.get_value::<String, _>("MachineGuid"))
        .unwrap_or_default()
}

#[cfg(not(target_os = "windows"))]
fn machine_source() -> String {
    fs::read_to_string("/etc/machine-id")
        .or_else(|_| std::env::var("HOSTNAME").map_err(|_| std::io::Error::from(std::io::ErrorKind::NotFound)))
        .unwrap_or_default()
}

fn license_directory() -> PathBuf {
    if let Some(game_directory) = GAME_DIRECTORY.get() {
        return game_directory.join("config").join("debug");
    }
    if let Some(game_directory) = std::env::var_os("perfectpatch.game.dir") {
        if !game_directory.is_empty() {
            return PathBuf::from(game_directory).join("config").join("debug");
        }
    }
    #[cfg(target_os = "windows")]
    let root = std::env::var_os("APPDATA").map(PathBuf::from)
        .or_else(|| std::env::var_os("USERPROFILE").map(PathBuf::from))
        .unwrap_or_else(|| PathBuf::from("."));
    #[cfg(not(target_os = "windows"))]
    let root = std::env::var_os("HOME").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    root.join(".minecraft").join("config").join("debug")
}

fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut output = String::with_capacity(bytes.len() * 2);
    for &value in bytes { output.push(DIGITS[(value >> 4) as usize] as char); output.push(DIGITS[(value & 15) as usize] as char); }
    output
}

fn hex_bytes(input: &str) -> Result<Vec<u8>, ()> {
    if input.len() != 128 { return Err(()); }
    let mut bytes = Vec::with_capacity(64);
    let raw = input.as_bytes();
    for index in (0..raw.len()).step_by(2) {
        let high = nibble(raw[index]).ok_or(())?;
        let low = nibble(raw[index + 1]).ok_or(())?;
        bytes.push((high << 4) | low);
    }
    Ok(bytes)
}

fn nibble(value: u8) -> Option<u8> {
    match value { b'0'..=b'9' => Some(value - b'0'), b'a'..=b'f' => Some(value - b'a' + 10), b'A'..=b'F' => Some(value - b'A' + 10), _ => None }
}
