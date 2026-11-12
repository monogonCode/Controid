use serde::{Deserialize, Serialize};
use tokio::sync::oneshot;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DaemonLinkState {
    Idle,
    Suspended,
    ConnectingBle,
    ConnectingWifi,
    Authenticating,
    Connected,
}

impl DaemonLinkState {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Idle => "idle",
            Self::Suspended => "suspended",
            Self::ConnectingBle => "connecting_ble",
            Self::ConnectingWifi => "connecting_wifi",
            Self::Authenticating => "authenticating",
            Self::Connected => "connected",
        }
    }
}

impl std::fmt::Display for DaemonLinkState {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DaemonTelemetry {
    pub state: DaemonLinkState,
    pub battery: Option<u8>,
    pub charging: bool,
    pub tethering: bool,
    pub hid_connected: bool,
}

impl Default for DaemonTelemetry {
    fn default() -> Self {
        Self {
            state: DaemonLinkState::Idle,
            battery: None,
            charging: false,
            tethering: false,
            hid_connected: false,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CommandError {
    pub code: i32,
    pub message: String,
}

impl CommandError {
    pub fn new(code: i32, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    pub fn not_connected() -> Self {
        Self::new(5001, "Device not connected")
    }

    pub fn invalid_params(message: impl Into<String>) -> Self {
        Self::new(-32602, message)
    }

    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(5000, message)
    }
}

#[derive(Debug)]
pub enum DaemonCommand {
    EnableHotspot {
        reply: oneshot::Sender<Result<serde_json::Value, CommandError>>,
    },
    DisableHotspot {
        reply: oneshot::Sender<Result<serde_json::Value, CommandError>>,
    },
    SendClipboard {
        mime: String,
        data: Vec<u8>,
        reply: oneshot::Sender<Result<(), CommandError>>,
    },
    StartStream {
        max_fps: Option<u16>,
        bitrate_mbps: Option<u8>,
        codec: Option<u8>,
        reply: oneshot::Sender<Result<serde_json::Value, CommandError>>,
    },
    StopStream {
        reply: oneshot::Sender<Result<(), CommandError>>,
    },
    ResumeConnection {
        reply: oneshot::Sender<Result<serde_json::Value, CommandError>>,
    },
    Disconnect {
        reply: oneshot::Sender<Result<serde_json::Value, CommandError>>,
    },
    GetConfig {
        reply: oneshot::Sender<Result<serde_json::Value, CommandError>>,
    },
    SetConfig {
        config: serde_json::Value,
        reply: oneshot::Sender<Result<serde_json::Value, CommandError>>,
    },
    DiscoverDevices {
        timeout_secs: u64,
        reply: oneshot::Sender<Result<serde_json::Value, CommandError>>,
    },
    MountFiles {
        mount_point: Option<String>,
        reply: oneshot::Sender<Result<serde_json::Value, CommandError>>,
    },
    UnmountFiles {
        reply: oneshot::Sender<Result<serde_json::Value, CommandError>>,
    },
    FilesStatus {
        reply: oneshot::Sender<Result<serde_json::Value, CommandError>>,
    },
    SendRemoteKey {
        keycode: u16,
        reply: oneshot::Sender<Result<(), CommandError>>,
    },
}
