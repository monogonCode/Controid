use crate::state::{CommandError, DaemonCommand, DaemonTelemetry};
use core_protocol::{CODEC_H264, CODEC_H265};
use futures::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::io;
use std::path::{Path, PathBuf};
use std::time::Duration;
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::{broadcast, mpsc, oneshot, watch};
use tokio::task::JoinHandle;
use tokio_util::codec::{Framed, LinesCodec, LinesCodecError};
use tracing::{debug, warn};

pub const MAX_IPC_LINE_BYTES: usize = 64 * 1024;
pub const IPC_COMMAND_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IpcRequest {
    pub id: u64,
    pub method: String,
    #[serde(default)]
    pub params: serde_json::Value,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct IpcError {
    pub code: i32,
    pub message: String,
}

impl From<CommandError> for IpcError {
    fn from(value: CommandError) -> Self {
        Self {
            code: value.code,
            message: value.message,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IpcResponse {
    pub id: u64,
    pub result: Option<serde_json::Value>,
    pub error: Option<IpcError>,
}

impl IpcResponse {
    pub fn ok(id: u64, result: serde_json::Value) -> Self {
        Self {
            id,
            result: Some(result),
            error: None,
        }
    }

    pub fn err(id: u64, code: i32, message: impl Into<String>) -> Self {
        Self {
            id,
            result: None,
            error: Some(IpcError {
                code,
                message: message.into(),
            }),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct IpcEvent {
    pub event: String,
    pub data: serde_json::Value,
}

pub fn default_socket_path() -> PathBuf {
    let base = dirs::runtime_dir().unwrap_or_else(|| PathBuf::from("/tmp"));
    base.join("controid").join("daemon.sock")
}

pub fn bind_ipc_listener(socket_path: &Path) -> io::Result<UnixListener> {
    if let Some(parent) = socket_path.parent()
        && !parent.as_os_str().is_empty()
    {
        std::fs::create_dir_all(parent)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(parent, std::fs::Permissions::from_mode(0o700))?;
        }
    }

    if let Ok(_meta) = std::fs::symlink_metadata(socket_path) {
        if std::os::unix::net::UnixStream::connect(socket_path).is_ok() {
            return Err(io::Error::new(
                io::ErrorKind::AddrInUse,
                format!("IPC socket already active at {}", socket_path.display()),
            ));
        }
        std::fs::remove_file(socket_path)?;
    }

    let listener = UnixListener::bind(socket_path)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(socket_path, std::fs::Permissions::from_mode(0o600))?;
    }
    Ok(listener)
}

pub async fn start_ipc_server(
    socket_path: PathBuf,
    cmd_tx: mpsc::Sender<DaemonCommand>,
    telemetry_rx: watch::Receiver<DaemonTelemetry>,
    events_tx: broadcast::Sender<IpcEvent>,
    mut shutdown: watch::Receiver<bool>,
) -> io::Result<JoinHandle<()>> {
    let listener = bind_ipc_listener(&socket_path)?;

    let handle = tokio::spawn(async move {
        loop {
            tokio::select! {
                biased;

                changed = shutdown.changed() => {
                    if changed.is_err() || *shutdown.borrow() {
                        break;
                    }
                }

                accepted = listener.accept() => {
                    match accepted {
                        Ok((stream, _addr)) => {
                            let cmd_tx = cmd_tx.clone();
                            let telemetry_rx = telemetry_rx.clone();
                            let events_tx = events_tx.clone();
                            let shutdown = shutdown.clone();
                            tokio::spawn(async move {
                                if let Err(err) = handle_client(
                                    stream,
                                    cmd_tx,
                                    telemetry_rx,
                                    events_tx,
                                    shutdown,
                                )
                                .await
                                {
                                    debug!(error = %err, "IPC client session ended");
                                }
                            });
                        }
                        Err(err) => {
                            warn!(error = %err, "IPC accept error");
                        }
                    }
                }
            }
        }

        let _ = std::fs::remove_file(&socket_path);
    });

    Ok(handle)
}

async fn handle_client(
    stream: UnixStream,
    cmd_tx: mpsc::Sender<DaemonCommand>,
    telemetry_rx: watch::Receiver<DaemonTelemetry>,
    events_tx: broadcast::Sender<IpcEvent>,
    mut shutdown: watch::Receiver<bool>,
) -> io::Result<()> {
    let mut framed = Framed::new(stream, LinesCodec::new_with_max_length(MAX_IPC_LINE_BYTES));

    loop {
        let line = tokio::select! {
            biased;

            changed = shutdown.changed() => {
                if changed.is_err() || *shutdown.borrow() {
                    return Ok(());
                }
                continue;
            }

            next_line = framed.next() => match next_line {
                Some(Ok(line)) => line,
                Some(Err(LinesCodecError::MaxLineLengthExceeded)) => {
                    let resp = IpcResponse::err(
                        0,
                        -32600,
                        format!("IPC line exceeds max length of {MAX_IPC_LINE_BYTES} bytes"),
                    );
                    if let Ok(encoded) = serde_json::to_string(&resp) {
                        let _ = framed.send(encoded).await;
                    }
                    return Ok(());
                }
                Some(Err(LinesCodecError::Io(err))) => return Err(err),
                None => return Ok(()),
            }
        };

        if line.trim().is_empty() {
            continue;
        }

        let req: IpcRequest = match serde_json::from_str(&line) {
            Ok(req) => req,
            Err(err) => {
                let resp = IpcResponse::err(0, -32700, format!("invalid JSON request: {err}"));
                send_response(&mut framed, &resp).await?;
                continue;
            }
        };

        if req.method == "events.subscribe" {
            let ack = IpcResponse::ok(req.id, json!({ "subscribed": true }));
            send_response(&mut framed, &ack).await?;
            return run_event_subscriber(framed, events_tx, shutdown).await;
        }

        let resp = dispatch_request(req, &cmd_tx, &telemetry_rx, &mut shutdown).await;
        send_response(&mut framed, &resp).await?;
    }
}

async fn run_event_subscriber(
    mut framed: Framed<UnixStream, LinesCodec>,
    events_tx: broadcast::Sender<IpcEvent>,
    mut shutdown: watch::Receiver<bool>,
) -> io::Result<()> {
    let mut rx = events_tx.subscribe();

    loop {
        tokio::select! {
            biased;

            changed = shutdown.changed() => {
                if changed.is_err() || *shutdown.borrow() {
                    return Ok(());
                }
            }

            ev = rx.recv() => match ev {
                Ok(event) => {
                    let line = serde_json::to_string(&event)
                        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
                    framed.send(line).await.map_err(lines_err_to_io)?;
                }
                Err(broadcast::error::RecvError::Lagged(skipped)) => {
                    warn!(skipped, "telemetry subscriber lagged; skipping older events to bound memory");
                }
                Err(broadcast::error::RecvError::Closed) => return Ok(()),
            },

            incoming = framed.next() => match incoming {
                None | Some(Err(_)) => return Ok(()),
                Some(Ok(_)) => {}
            }
        }
    }
}

async fn dispatch_request(
    req: IpcRequest,
    cmd_tx: &mpsc::Sender<DaemonCommand>,
    telemetry_rx: &watch::Receiver<DaemonTelemetry>,
    shutdown: &mut watch::Receiver<bool>,
) -> IpcResponse {
    let id = req.id;

    match req.method.as_str() {
        "device.status" => {
            let snapshot = telemetry_rx.borrow().clone();
            match serde_json::to_value(snapshot) {
                Ok(val) => IpcResponse::ok(id, val),
                Err(err) => IpcResponse::err(id, 5000, format!("serialization error: {err}")),
            }
        }

        "hotspot.enable" => {
            send_value_command(
                id,
                cmd_tx,
                |reply| DaemonCommand::EnableHotspot { reply },
                IPC_COMMAND_TIMEOUT,
                shutdown,
            )
            .await
        }

        "hotspot.disable" => {
            send_value_command(
                id,
                cmd_tx,
                |reply| DaemonCommand::DisableHotspot { reply },
                IPC_COMMAND_TIMEOUT,
                shutdown,
            )
            .await
        }

        "clipboard.send" => {
            let mime = match req.params.get("mime") {
                None => "text/plain".to_string(),
                Some(v) => match v.as_str() {
                    Some(s) if !s.is_empty() => s.to_string(),
                    _ => {
                        return IpcResponse::err(id, -32602, "params.mime must be a non-empty string");
                    }
                },
            };
            let data = match req.params.get("data").and_then(|v| v.as_str()) {
                Some(s) => s.as_bytes().to_vec(),
                None => {
                    return IpcResponse::err(id, -32602, "params.data string is required");
                }
            };

            send_unit_command(
                id,
                cmd_tx,
                |reply| DaemonCommand::SendClipboard { mime, data, reply },
                IPC_COMMAND_TIMEOUT,
                shutdown,
            )
            .await
        }

        "stream.start" => {
            let max_fps = match req.params.get("max_fps") {
                None => None,
                Some(v) => match v.as_u64().and_then(|n| u16::try_from(n).ok()) {
                    Some(fps) if fps > 0 => Some(fps),
                    _ => return IpcResponse::err(id, -32602, "params.max_fps must be u16 > 0"),
                },
            };
            let bitrate_mbps = match req.params.get("bitrate_mbps") {
                None => None,
                Some(v) => match v.as_u64().and_then(|n| u8::try_from(n).ok()) {
                    Some(b) if b > 0 => Some(b),
                    _ => return IpcResponse::err(id, -32602, "params.bitrate_mbps must be u8 > 0"),
                },
            };
            let codec = match req.params.get("codec") {
                None => None,
                Some(v) => {
                    if let Some(s) = v.as_str() {
                        match s.to_ascii_lowercase().as_str() {
                            "h264" | "avc" | "video/avc" => Some(CODEC_H264),
                            "h265" | "hevc" | "video/hevc" => Some(CODEC_H265),
                            _ => return IpcResponse::err(id, -32602, "unsupported params.codec"),
                        }
                    } else if let Some(n) = v.as_u64().and_then(|n| u8::try_from(n).ok()) {
                        if n == CODEC_H264 || n == CODEC_H265 {
                            Some(n)
                        } else {
                            return IpcResponse::err(id, -32602, "unsupported numeric params.codec");
                        }
                    } else {
                        return IpcResponse::err(id, -32602, "invalid params.codec");
                    }
                }
            };

            send_value_command(
                id,
                cmd_tx,
                |reply| DaemonCommand::StartStream {
                    max_fps,
                    bitrate_mbps,
                    codec,
                    reply,
                },
                IPC_COMMAND_TIMEOUT,
                shutdown,
            )
            .await
        }

        "stream.stop" => {
            send_unit_command(
                id,
                cmd_tx,
                |reply| DaemonCommand::StopStream { reply },
                IPC_COMMAND_TIMEOUT,
                shutdown,
            )
            .await
        }

        "connection.resume" => {
            send_value_command(
                id,
                cmd_tx,
                |reply| DaemonCommand::ResumeConnection { reply },
                IPC_COMMAND_TIMEOUT,
                shutdown,
            )
            .await
        }

        "connection.disconnect" => {
            send_value_command(
                id,
                cmd_tx,
                |reply| DaemonCommand::Disconnect { reply },
                IPC_COMMAND_TIMEOUT,
                shutdown,
            )
            .await
        }

        "config.get" => {
            send_value_command(
                id,
                cmd_tx,
                |reply| DaemonCommand::GetConfig { reply },
                IPC_COMMAND_TIMEOUT,
                shutdown,
            )
            .await
        }

        "config.set" => {
            let config = req.params.clone();
            send_value_command(
                id,
                cmd_tx,
                move |reply| DaemonCommand::SetConfig { config, reply },
                IPC_COMMAND_TIMEOUT,
                shutdown,
            )
            .await
        }

        "pair.discover" => {
            let timeout_secs = match req.params.get("timeout_secs") {
                None => 10,
                Some(v) => match v.as_u64() {
                    Some(s) if (1..=60).contains(&s) => s,
                    _ => {
                        return IpcResponse::err(
                            id,
                            -32602,
                            "params.timeout_secs must be between 1 and 60",
                        );
                    }
                },
            };

            send_value_command(
                id,
                cmd_tx,
                |reply| DaemonCommand::DiscoverDevices {
                    timeout_secs,
                    reply,
                },
                Duration::from_secs(timeout_secs + 5),
                shutdown,
            )
            .await
        }

        "files.mount" => {
            let mount_point = match req.params.get("mount_point") {
                None | Some(serde_json::Value::Null) => None,
                Some(v) => match v.as_str() {
                    Some(s) if !s.trim().is_empty() => Some(s.trim().to_string()),
                    _ => {
                        return IpcResponse::err(
                            id,
                            -32602,
                            "params.mount_point must be a non-empty string",
                        );
                    }
                },
            };

            send_value_command(
                id,
                cmd_tx,
                |reply| DaemonCommand::MountFiles { mount_point, reply },
                IPC_COMMAND_TIMEOUT,
                shutdown,
            )
            .await
        }

        "files.unmount" => {
            send_value_command(
                id,
                cmd_tx,
                |reply| DaemonCommand::UnmountFiles { reply },
                IPC_COMMAND_TIMEOUT,
                shutdown,
            )
            .await
        }

        "files.status" => {
            send_value_command(
                id,
                cmd_tx,
                |reply| DaemonCommand::FilesStatus { reply },
                IPC_COMMAND_TIMEOUT,
                shutdown,
            )
            .await
        }

        "input.key" => {
            let keycode = if let Some(kc_val) = req.params.get("keycode") {
                match kc_val.as_u64().and_then(|n| u16::try_from(n).ok()) {
                    Some(kc) if kc > 0 => kc,
                    _ => {
                        return IpcResponse::err(id, -32602, "params.keycode must be u16 > 0");
                    }
                }
            } else if let Some(name_val) = req.params.get("key") {
                match name_val.as_str().map(|s| s.trim().to_ascii_lowercase()).as_deref() {
                    Some("back") => 4,
                    Some("home") => 3,
                    Some("recents") | Some("app_switch") | Some("overview") => 187,
                    Some("power") => 26,
                    _ => {
                        return IpcResponse::err(
                            id,
                            -32602,
                            "params.key must be one of: back, home, recents, power",
                        );
                    }
                }
            } else {
                return IpcResponse::err(id, -32602, "params.keycode or params.key is required");
            };

            send_unit_command(
                id,
                cmd_tx,
                |reply| DaemonCommand::SendRemoteKey { keycode, reply },
                IPC_COMMAND_TIMEOUT,
                shutdown,
            )
            .await
        }

        other => IpcResponse::err(id, -32601, format!("unknown method: {other}")),
    }
}

async fn send_value_command<F>(
    id: u64,
    cmd_tx: &mpsc::Sender<DaemonCommand>,
    make_cmd: F,
    timeout: Duration,
    shutdown: &mut watch::Receiver<bool>,
) -> IpcResponse
where
    F: FnOnce(oneshot::Sender<Result<serde_json::Value, CommandError>>) -> DaemonCommand,
{
    let (reply_tx, reply_rx) = oneshot::channel();
    if cmd_tx.try_send(make_cmd(reply_tx)).is_err() {
        return IpcResponse::err(id, 5003, "daemon command queue busy or unavailable");
    }

    tokio::select! {
        biased;
        changed = shutdown.changed() => {
            let _ = changed;
            IpcResponse::err(id, 5002, "daemon shutting down")
        }
        res = tokio::time::timeout(timeout, reply_rx) => match res {
            Ok(Ok(Ok(val))) => IpcResponse::ok(id, val),
            Ok(Ok(Err(err))) => IpcResponse {
                id,
                result: None,
                error: Some(err.into()),
            },
            Ok(Err(_)) => IpcResponse::err(id, 5000, "daemon dropped command reply"),
            Err(_) => IpcResponse::err(id, 5004, "daemon command timed out"),
        }
    }
}

async fn send_unit_command<F>(
    id: u64,
    cmd_tx: &mpsc::Sender<DaemonCommand>,
    make_cmd: F,
    timeout: Duration,
    shutdown: &mut watch::Receiver<bool>,
) -> IpcResponse
where
    F: FnOnce(oneshot::Sender<Result<(), CommandError>>) -> DaemonCommand,
{
    let (reply_tx, reply_rx) = oneshot::channel();
    if cmd_tx.try_send(make_cmd(reply_tx)).is_err() {
        return IpcResponse::err(id, 5003, "daemon command queue busy or unavailable");
    }

    tokio::select! {
        biased;
        changed = shutdown.changed() => {
            let _ = changed;
            IpcResponse::err(id, 5002, "daemon shutting down")
        }
        res = tokio::time::timeout(timeout, reply_rx) => match res {
            Ok(Ok(Ok(()))) => IpcResponse::ok(id, json!({ "ok": true })),
            Ok(Ok(Err(err))) => IpcResponse {
                id,
                result: None,
                error: Some(err.into()),
            },
            Ok(Err(_)) => IpcResponse::err(id, 5000, "daemon dropped command reply"),
            Err(_) => IpcResponse::err(id, 5004, "daemon command timed out"),
        }
    }
}

async fn send_response(
    framed: &mut Framed<UnixStream, LinesCodec>,
    resp: &IpcResponse,
) -> io::Result<()> {
    let encoded =
        serde_json::to_string(resp).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    framed.send(encoded).await.map_err(lines_err_to_io)
}

fn lines_err_to_io(err: LinesCodecError) -> io::Error {
    match err {
        LinesCodecError::MaxLineLengthExceeded => {
            io::Error::new(io::ErrorKind::InvalidData, "IPC line exceeded 64 KB")
        }
        LinesCodecError::Io(e) => e,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::state::DaemonLinkState;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_sock_dir(label: &str) -> PathBuf {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!("controid-ipc-{label}-{unique}"))
    }

    #[tokio::test]
    async fn socket_permissions_methods_and_concurrent_clients() {
        let dir = temp_sock_dir("methods");
        let sock_path = dir.join("controid").join("daemon.sock");

        let (cmd_tx, mut cmd_rx) = mpsc::channel(32);
        let initial = DaemonTelemetry {
            state: DaemonLinkState::Connected,
            battery: Some(85),
            charging: false,
            tethering: true,
            hid_connected: true,
        };
        let (_tel_tx, tel_rx) = watch::channel(initial);
        let (events_tx, _) = broadcast::channel(8);
        let (shutdown_tx, shutdown_rx) = watch::channel(false);

        let server_handle =
            start_ipc_server(sock_path.clone(), cmd_tx, tel_rx, events_tx, shutdown_rx)
                .await
                .unwrap();

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let sock_mode = std::fs::metadata(&sock_path).unwrap().permissions().mode() & 0o777;
            let dir_mode = std::fs::metadata(sock_path.parent().unwrap())
                .unwrap()
                .permissions()
                .mode()
                & 0o777;
            assert_eq!(sock_mode, 0o600);
            assert_eq!(dir_mode, 0o700);
        }

        // Mock command handler
        let cmd_task = tokio::spawn(async move {
            while let Some(cmd) = cmd_rx.recv().await {
                match cmd {
                    DaemonCommand::EnableHotspot { reply } => {
                        let _ = reply.send(Ok(json!({"status": "starting"})));
                    }
                    DaemonCommand::DisableHotspot { reply } => {
                        let _ = reply.send(Ok(json!({"status": "idle"})));
                    }
                    DaemonCommand::SendClipboard { reply, .. } => {
                        let _ = reply.send(Err(CommandError::not_connected()));
                    }
                    DaemonCommand::StartStream {
                        max_fps,
                        bitrate_mbps,
                        reply,
                        ..
                    } => {
                        let _ = reply.send(Ok(json!({
                            "active": true,
                            "max_fps": max_fps.unwrap_or(60),
                            "bitrate_mbps": bitrate_mbps.unwrap_or(8),
                        })));
                    }
                    DaemonCommand::StopStream { reply } => {
                        let _ = reply.send(Ok(()));
                    }
                    DaemonCommand::ResumeConnection { reply } => {
                        let _ = reply.send(Ok(json!({"status": "resuming"})));
                    }
                    DaemonCommand::Disconnect { reply } => {
                        let _ = reply.send(Ok(json!({"status": "idle"})));
                    }
                    DaemonCommand::GetConfig { reply } => {
                        let _ = reply.send(Ok(json!({"config": {"network": {"connection_mode": "auto"}}})));
                    }
                    DaemonCommand::SetConfig { config, reply } => {
                        let _ = reply.send(Ok(json!({"ok": true, "config": config})));
                    }
                    DaemonCommand::DiscoverDevices { timeout_secs, reply } => {
                        let _ = reply.send(Ok(json!({"timeout_secs": timeout_secs, "devices": []})));
                    }
                    DaemonCommand::MountFiles { mount_point, reply } => {
                        let _ = reply.send(Ok(json!({
                            "mounted": true,
                            "mount_point": mount_point.unwrap_or_else(|| "/tmp/Controid".to_string()),
                            "connected": true,
                            "error": null
                        })));
                    }
                    DaemonCommand::UnmountFiles { reply } => {
                        let _ = reply.send(Ok(json!({
                            "mounted": false,
                            "mount_point": "/tmp/Controid",
                            "connected": true,
                            "error": null
                        })));
                    }
                    DaemonCommand::FilesStatus { reply } => {
                        let _ = reply.send(Ok(json!({
                            "mounted": false,
                            "mount_point": "/tmp/Controid",
                            "connected": true,
                            "error": null
                        })));
                    }
                    DaemonCommand::SendRemoteKey { keycode, reply } => {
                        assert!(keycode > 0);
                        let _ = reply.send(Ok(()));
                    }
                }
            }
        });

        let c1 = UnixStream::connect(&sock_path).await.unwrap();
        let c2 = UnixStream::connect(&sock_path).await.unwrap();
        let mut f1 = Framed::new(c1, LinesCodec::new_with_max_length(MAX_IPC_LINE_BYTES));
        let mut f2 = Framed::new(c2, LinesCodec::new_with_max_length(MAX_IPC_LINE_BYTES));

        f1.send(r#"{"id":3,"method":"device.status","params":{}}"#.to_string())
            .await
            .unwrap();
        f2.send(r#"{"id":1,"method":"hotspot.enable","params":{}}"#.to_string())
            .await
            .unwrap();

        let r1: IpcResponse =
            serde_json::from_str(&f1.next().await.unwrap().unwrap()).unwrap();
        assert_eq!(r1.id, 3);
        assert_eq!(r1.result.as_ref().unwrap()["battery"], 85);

        let r2: IpcResponse =
            serde_json::from_str(&f2.next().await.unwrap().unwrap()).unwrap();
        assert_eq!(r2.id, 1);
        assert_eq!(r2.result.as_ref().unwrap()["status"], "starting");

        f1.send(
            r#"{"id":4,"method":"clipboard.send","params":{"mime":"text/plain","data":"Hello"}}"#
                .to_string(),
        )
        .await
        .unwrap();
        let r4: IpcResponse =
            serde_json::from_str(&f1.next().await.unwrap().unwrap()).unwrap();
        assert_eq!(r4.error.as_ref().unwrap().code, 5001);

        for req in [
            r#"{"id":5,"method":"stream.start","params":{"max_fps":60,"bitrate_mbps":8}}"#,
            r#"{"id":6,"method":"stream.stop","params":{}}"#,
            r#"{"id":7,"method":"connection.resume","params":{}}"#,
            r#"{"id":8,"method":"pair.discover","params":{"timeout_secs":5}}"#,
            r#"{"id":9,"method":"hotspot.disable","params":{}}"#,
            r#"{"id":10,"method":"config.get","params":{}}"#,
            r#"{"id":11,"method":"config.set","params":{"network":{"connection_mode":"lan"}}}"#,
            r#"{"id":12,"method":"connection.disconnect","params":{}}"#,
            r#"{"id":13,"method":"files.mount","params":{}}"#,
            r#"{"id":14,"method":"files.status","params":{}}"#,
            r#"{"id":15,"method":"files.unmount","params":{}}"#,
            r#"{"id":16,"method":"input.key","params":{"key":"back"}}"#,
            r#"{"id":17,"method":"input.key","params":{"keycode":26}}"#,
        ] {
            f1.send(req.to_string()).await.unwrap();
            let resp: IpcResponse =
                serde_json::from_str(&f1.next().await.unwrap().unwrap()).unwrap();
            assert!(resp.error.is_none(), "unexpected error on {req}: {resp:?}");
        }

        let _ = shutdown_tx.send(true);
        server_handle.await.unwrap();
        cmd_task.abort();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn subscriber_handles_lagged_broadcast_without_oom_or_crash() {
        let dir = temp_sock_dir("lag");
        let sock_path = dir.join("daemon.sock");

        let (cmd_tx, _cmd_rx) = mpsc::channel(8);
        let (_tel_tx, tel_rx) = watch::channel(DaemonTelemetry::default());
        // Small ring capacity (4) to trigger RecvError::Lagged deterministically
        let (events_tx, _) = broadcast::channel(4);
        let (shutdown_tx, shutdown_rx) = watch::channel(false);

        let server_handle = start_ipc_server(
            sock_path.clone(),
            cmd_tx,
            tel_rx,
            events_tx.clone(),
            shutdown_rx,
        )
        .await
        .unwrap();

        let client = UnixStream::connect(&sock_path).await.unwrap();
        let mut framed = Framed::new(client, LinesCodec::new_with_max_length(MAX_IPC_LINE_BYTES));
        framed
            .send(r#"{"id":9,"method":"events.subscribe","params":{}}"#.to_string())
            .await
            .unwrap();
        let ack: IpcResponse =
            serde_json::from_str(&framed.next().await.unwrap().unwrap()).unwrap();
        assert_eq!(ack.id, 9);

        // Overflow broadcast buffer before subscriber reads
        for i in 0..20 {
            let _ = events_tx.send(IpcEvent {
                event: "link.state_changed".to_string(),
                data: json!({"seq": i}),
            });
        }

        // Subscriber should still receive latest surviving events cleanly after Lagged
        let ev: IpcEvent =
            serde_json::from_str(&framed.next().await.unwrap().unwrap()).unwrap();
        assert_eq!(ev.event, "link.state_changed");
        assert!(ev.data["seq"].as_u64().unwrap() >= 16);

        let _ = shutdown_tx.send(true);
        server_handle.await.unwrap();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn oversized_ipc_line_rejected_cleanly() {
        let dir = temp_sock_dir("oversized");
        let sock_path = dir.join("daemon.sock");

        let (cmd_tx, _cmd_rx) = mpsc::channel(8);
        let (_tel_tx, tel_rx) = watch::channel(DaemonTelemetry::default());
        let (events_tx, _) = broadcast::channel(8);
        let (shutdown_tx, shutdown_rx) = watch::channel(false);

        let server_handle =
            start_ipc_server(sock_path.clone(), cmd_tx, tel_rx, events_tx, shutdown_rx)
                .await
                .unwrap();

        let client = UnixStream::connect(&sock_path).await.unwrap();
        let mut framed = Framed::new(client, LinesCodec::new_with_max_length(MAX_IPC_LINE_BYTES * 2));
        let giant = "x".repeat(MAX_IPC_LINE_BYTES + 128);
        framed.send(giant).await.unwrap();

        let resp: IpcResponse =
            serde_json::from_str(&framed.next().await.unwrap().unwrap()).unwrap();
        assert_eq!(resp.error.unwrap().code, -32600);

        let _ = shutdown_tx.send(true);
        server_handle.await.unwrap();
        let _ = std::fs::remove_dir_all(&dir);
    }
}
