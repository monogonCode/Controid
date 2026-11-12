use anyhow::{Context, Result, bail};
use core_protocol::IdentityKeyPair;
use serde::{Deserialize, Serialize};
use std::fs::{self, OpenOptions};
use std::io::{ErrorKind, Write};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct DeviceConfig {
    pub mac_address: String,
    pub service_uuid: String,
    pub hotspot_ssid: String,
    pub peer_ed25519_pubkey_hex: Option<String>,
}

impl Default for DeviceConfig {
    fn default() -> Self {
        Self {
            mac_address: String::new(),
            service_uuid: "0000a001-0000-1000-8000-00805f9b34fb".to_string(),
            hotspot_ssid: String::new(),
            peer_ed25519_pubkey_hex: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct NetworkConfig {
    pub reconnect_cooldown_secs: u64,
    pub max_retries: u32,
    pub tcp_port: u16,
    pub gateway_ip: String,
    pub connection_mode: String,
    pub direct_host: String,
}

impl Default for NetworkConfig {
    fn default() -> Self {
        Self {
            reconnect_cooldown_secs: 30,
            max_retries: 4,
            tcp_port: 55777,
            gateway_ip: "192.168.43.1".to_string(),
            connection_mode: "auto".to_string(),
            direct_host: String::new(),
        }
    }
}

impl NetworkConfig {
    pub fn normalized_connection_mode(&self) -> &'static str {
        match self.connection_mode.trim().to_ascii_lowercase().as_str() {
            "lan" | "direct" => "lan",
            "hotspot" | "ble" => "hotspot",
            _ => "auto",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct StreamingConfig {
    pub max_fps: u16,
    pub bitrate_mbps: u8,
    pub codec_mime: String,
}

impl Default for StreamingConfig {
    fn default() -> Self {
        Self {
            max_fps: 60,
            bitrate_mbps: 8,
            codec_mime: "video/avc".to_string(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct FilesConfig {
    pub mount_point: String,
    pub auto_mount: bool,
}

impl Default for FilesConfig {
    fn default() -> Self {
        Self {
            mount_point: "~/Controid".to_string(),
            auto_mount: true,
        }
    }
}

impl FilesConfig {
    pub fn resolved_mount_point(&self) -> PathBuf {
        expand_tilde(&self.mount_point)
    }
}

pub fn expand_tilde(raw: &str) -> PathBuf {
    let trimmed = raw.trim();
    let home = dirs::home_dir().unwrap_or_else(|| PathBuf::from("/tmp"));
    if trimmed.is_empty() {
        return home.join("Controid");
    }
    if trimmed == "~" {
        return home;
    }
    if let Some(rest) = trimmed.strip_prefix("~/") {
        return home.join(rest);
    }
    PathBuf::from(trimmed)
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct AppConfig {
    pub device: DeviceConfig,
    pub network: NetworkConfig,
    pub streaming: StreamingConfig,
    pub files: FilesConfig,
}

impl AppConfig {
    pub fn config_dir() -> PathBuf {
        dirs::config_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("controid")
    }

    pub fn config_path() -> PathBuf {
        Self::config_dir().join("config.toml")
    }

    pub fn identity_key_path() -> PathBuf {
        Self::config_dir().join("identity.pkcs8")
    }

    pub fn load_or_default() -> Result<Self> {
        Self::load_or_default_at(&Self::config_path())
    }

    pub fn load_or_default_at(path: &Path) -> Result<Self> {
        match fs::read_to_string(path) {
            Ok(raw) => {
                let cfg: Self = toml::from_str(&raw)
                    .with_context(|| format!("failed to parse config file {}", path.display()))?;
                Ok(cfg)
            }
            Err(err) if err.kind() == ErrorKind::NotFound => Ok(Self::default()),
            Err(err) => Err(err).with_context(|| format!("failed to read {}", path.display())),
        }
    }

    pub fn save(&self) -> Result<()> {
        self.save_to(&Self::config_path())
    }

    pub fn save_to(&self, path: &Path) -> Result<()> {
        ensure_private_parent_dir(path)?;
        let encoded = toml::to_string_pretty(self).context("failed to serialize config")?;

        let parent = path.parent().unwrap_or_else(|| Path::new("."));
        let file_name = path
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or("config.toml");
        let tmp_path = parent.join(format!(".{file_name}.tmp.{}", std::process::id()));

        let mut opts = OpenOptions::new();
        opts.write(true).create(true).truncate(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            opts.mode(0o600);
        }

        {
            let mut file = opts
                .open(&tmp_path)
                .with_context(|| format!("failed to create temp config {}", tmp_path.display()))?;
            file.write_all(encoded.as_bytes())?;
            file.sync_all()?;
        }

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&tmp_path, fs::Permissions::from_mode(0o600))?;
        }

        fs::rename(&tmp_path, path)
            .with_context(|| format!("failed to persist config to {}", path.display()))?;

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
        }

        Ok(())
    }

    pub fn load_or_generate_identity_key() -> Result<IdentityKeyPair> {
        Self::load_or_generate_identity_key_at(&Self::identity_key_path())
    }

    pub fn load_or_generate_identity_key_at(path: &Path) -> Result<IdentityKeyPair> {
        ensure_private_parent_dir(path)?;
        let pair = IdentityKeyPair::load_or_generate(path)
            .with_context(|| format!("failed to load or generate identity key at {}", path.display()))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(path, fs::Permissions::from_mode(0o600))?;
        }
        Ok(pair)
    }

    pub fn peer_pubkey_bytes(&self) -> Result<Option<[u8; 32]>> {
        let Some(hex_str) = self.device.peer_ed25519_pubkey_hex.as_deref() else {
            return Ok(None);
        };
        let trimmed = hex_str.trim();
        if trimmed.is_empty() {
            return Ok(None);
        }
        let decoded = hex::decode(trimmed).context("invalid hex in peer_ed25519_pubkey_hex")?;
        if decoded.len() != 32 {
            bail!(
                "peer_ed25519_pubkey_hex must decode to 32 bytes, got {}",
                decoded.len()
            );
        }
        let mut out = [0u8; 32];
        out.copy_from_slice(&decoded);
        Ok(Some(out))
    }

    pub fn set_peer_pubkey(&mut self, pubkey: &[u8; 32]) {
        self.device.peer_ed25519_pubkey_hex = Some(hex::encode(pubkey));
    }
}

fn ensure_private_parent_dir(path: &Path) -> Result<()> {
    if let Some(parent) = path.parent()
        && !parent.as_os_str().is_empty()
    {
        fs::create_dir_all(parent)
            .with_context(|| format!("failed to create directory {}", parent.display()))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(parent, fs::Permissions::from_mode(0o700))?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_dir(label: &str) -> PathBuf {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!("controid-config-{label}-{unique}"))
    }

    #[test]
    fn load_or_default_returns_defaults_when_missing() {
        let dir = temp_dir("missing");
        let cfg = AppConfig::load_or_default_at(&dir.join("config.toml")).unwrap();
        assert_eq!(cfg, AppConfig::default());
        assert_eq!(cfg.network.tcp_port, 55777);
        assert_eq!(cfg.network.gateway_ip, "192.168.43.1");
        assert_eq!(cfg.network.max_retries, 4);
    }

    #[test]
    fn save_and_reload_enforces_0600_permissions() {
        let dir = temp_dir("save");
        let cfg_path = dir.join("controid").join("config.toml");

        let mut cfg = AppConfig::default();
        cfg.device.mac_address = "AA:BB:CC:DD:EE:FF".to_string();
        cfg.device.hotspot_ssid = "PixelHotspot".to_string();
        cfg.set_peer_pubkey(&[0x42; 32]);
        cfg.save_to(&cfg_path).unwrap();

        let reloaded = AppConfig::load_or_default_at(&cfg_path).unwrap();
        assert_eq!(reloaded, cfg);
        assert_eq!(reloaded.peer_pubkey_bytes().unwrap(), Some([0x42; 32]));

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let file_mode = fs::metadata(&cfg_path).unwrap().permissions().mode() & 0o777;
            let dir_mode = fs::metadata(cfg_path.parent().unwrap())
                .unwrap()
                .permissions()
                .mode()
                & 0o777;
            assert_eq!(file_mode, 0o600);
            assert_eq!(dir_mode, 0o700);
        }

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn partial_toml_fills_defaults() {
        let dir = temp_dir("partial");
        fs::create_dir_all(&dir).unwrap();
        let cfg_path = dir.join("config.toml");
        fs::write(&cfg_path, "[device]\nhotspot_ssid = \"MyPhone\"\n").unwrap();

        let cfg = AppConfig::load_or_default_at(&cfg_path).unwrap();
        assert_eq!(cfg.device.hotspot_ssid, "MyPhone");
        assert_eq!(cfg.network.tcp_port, 55777);
        assert_eq!(cfg.network.connection_mode, "auto");
        assert_eq!(cfg.network.direct_host, "");
        assert_eq!(cfg.streaming.max_fps, 60);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn normalized_connection_mode_handles_aliases() {
        let mut net = NetworkConfig::default();
        assert_eq!(net.normalized_connection_mode(), "auto");
        net.connection_mode = "LAN".to_string();
        assert_eq!(net.normalized_connection_mode(), "lan");
        net.connection_mode = "hotspot".to_string();
        assert_eq!(net.normalized_connection_mode(), "hotspot");
        net.connection_mode = "unknown".to_string();
        assert_eq!(net.normalized_connection_mode(), "auto");
    }

    #[test]
    fn identity_key_persists_with_0600() {
        let dir = temp_dir("identity");
        let key_path = dir.join("identity.pkcs8");

        let first = AppConfig::load_or_generate_identity_key_at(&key_path).unwrap();
        let second = AppConfig::load_or_generate_identity_key_at(&key_path).unwrap();
        assert_eq!(first.public_key_bytes(), second.public_key_bytes());

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(&key_path).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode, 0o600);
        }

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn invalid_peer_pubkey_hex_rejected() {
        let mut cfg = AppConfig::default();
        cfg.device.peer_ed25519_pubkey_hex = Some("abcd".to_string());
        assert!(cfg.peer_pubkey_bytes().is_err());

        cfg.device.peer_ed25519_pubkey_hex = Some("zzzz".to_string());
        assert!(cfg.peer_pubkey_bytes().is_err());
    }

    #[test]
    fn files_config_defaults_and_tilde_expansion() {
        let cfg = FilesConfig::default();
        assert_eq!(cfg.mount_point, "~/Controid");
        assert!(cfg.auto_mount);
        let expected_home = dirs::home_dir().unwrap_or_else(|| PathBuf::from("/tmp"));
        assert_eq!(cfg.resolved_mount_point(), expected_home.join("Controid"));
        assert_eq!(expand_tilde("/mnt/phone"), PathBuf::from("/mnt/phone"));
    }
}
