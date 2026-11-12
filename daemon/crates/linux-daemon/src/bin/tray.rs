use anyhow::{Context, Result, bail};
use ksni::TrayMethods;
use linux_daemon::{config::AppConfig, ipc};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::fs;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;
use tokio::sync::mpsc;
use tracing::{debug, info, warn};

#[derive(Debug, Clone)]
enum TrayCommand {
    OpenGui,
    ResumeConnection,
    Disconnect,
    HotspotEnable,
    HotspotDisable,
    StreamStart,
    StreamStop,
    FilesMount,
    FilesUnmount,
    Quit,
}

#[derive(Debug, Serialize)]
struct TrayIpcRequest {
    id: u64,
    method: String,
    params: serde_json::Value,
}

#[derive(Debug, Deserialize)]
struct TrayIpcError {
    code: i32,
    message: String,
}

#[derive(Debug, Deserialize)]
struct TrayIpcResponse {
    id: u64,
    result: Option<serde_json::Value>,
    error: Option<TrayIpcError>,
}

#[derive(Debug, Deserialize)]
struct TrayIpcEvent {
    event: String,
    data: serde_json::Value,
}

struct ControidTray {
    daemon_online: bool,
    state: String,
    ssid: Option<String>,
    battery: Option<u8>,
    charging: bool,
    tethering: bool,
    hid_connected: bool,
    stream_active: bool,
    files_mounted: bool,
    last_error: Option<String>,
    cmd_tx: mpsc::UnboundedSender<TrayCommand>,
}

impl ControidTray {
    fn new(cmd_tx: mpsc::UnboundedSender<TrayCommand>) -> Self {
        Self {
            daemon_online: false,
            state: "idle".to_owned(),
            ssid: None,
            battery: None,
            charging: false,
            tethering: false,
            hid_connected: false,
            stream_active: false,
            files_mounted: false,
            last_error: None,
            cmd_tx,
        }
    }

    fn apply_files_status(&mut self, val: &serde_json::Value) {
        if let Some(mounted) = val.get("mounted").and_then(|v| v.as_bool()) {
            self.files_mounted = mounted;
        }
    }

    fn apply_status(&mut self, val: &serde_json::Value) {
        self.daemon_online = true;
        if let Some(s) = val.get("state").and_then(|v| v.as_str()) {
            self.state = s.to_owned();
        }
        if let Some(b) = val.get("battery").and_then(|v| v.as_u64()) {
            self.battery = u8::try_from(b).ok();
        }
        if let Some(c) = val.get("charging").and_then(|v| v.as_bool()) {
            self.charging = c;
        }
        if let Some(t) = val.get("tethering").and_then(|v| v.as_bool()) {
            self.tethering = t;
        }
        if let Some(h) = val.get("hid_connected").and_then(|v| v.as_bool()) {
            self.hid_connected = h;
        }
    }

    fn apply_event(&mut self, ev: &TrayIpcEvent) {
        self.daemon_online = true;
        match ev.event.as_str() {
            "link.state_changed" => {
                if let Some(s) = ev.data.get("state").and_then(|v| v.as_str()) {
                    self.state = s.to_owned();
                    if s == "connected" {
                        self.last_error = None;
                    }
                }
                if let Some(ssid) = ev.data.get("ssid").and_then(|v| v.as_str())
                    && !ssid.is_empty()
                {
                    self.ssid = Some(ssid.to_owned());
                }
            }
            "error.tethering" | "link.error" => {
                let code = ev
                    .data
                    .get("code")
                    .and_then(|v| v.as_str())
                    .unwrap_or("err");
                let msg = ev
                    .data
                    .get("message")
                    .and_then(|v| v.as_str())
                    .unwrap_or("Connection error");
                self.last_error = Some(format!("{code}: {msg}"));
            }
            "stream.state" => {
                if let Some(active) = ev.data.get("active").and_then(|v| v.as_bool()) {
                    self.stream_active = active;
                }
            }
            "files.state" => {
                self.apply_files_status(&ev.data);
            }
            "device.status" => {
                self.apply_status(&ev.data);
            }
            _ => {}
        }
    }

    fn status_summary(&self) -> String {
        if !self.daemon_online {
            return "Status: Daemon offline".to_owned();
        }
        let mut s = format!("Status: {}", self.state);
        if let Some(pct) = self.battery {
            s.push_str(&format!(
                " ({pct}%{})",
                if self.charging { "+" } else { "" }
            ));
        }
        s
    }
}

impl ksni::Tray for ControidTray {
    fn id(&self) -> String {
        "controid".into()
    }

    fn category(&self) -> ksni::Category {
        ksni::Category::Hardware
    }

    fn title(&self) -> String {
        "Controid".into()
    }

    fn icon_name(&self) -> String {
        "controid".into()
    }

    fn icon_pixmap(&self) -> Vec<ksni::Icon> {
        vec![ksni::Icon {
            width: 48,
            height: 48,
            data: include_bytes!("../../../../../assets/controid-tray-48.argb").to_vec(),
        }]
    }

    fn status(&self) -> ksni::Status {
        if self.last_error.is_some() {
            ksni::Status::NeedsAttention
        } else {
            ksni::Status::Active
        }
    }

    fn tool_tip(&self) -> ksni::ToolTip {
        let mut lines = vec![self.status_summary()];
        if let Some(ssid) = &self.ssid {
            lines.push(format!("Hotspot SSID: {ssid}"));
        }
        lines.push(format!(
            "Tethering: {} | HID: {} | Stream: {}",
            if self.tethering { "on" } else { "off" },
            if self.hid_connected { "on" } else { "off" },
            if self.stream_active { "on" } else { "off" }
        ));
        if let Some(err) = &self.last_error {
            lines.push(format!("Last error: {err}"));
        }

        ksni::ToolTip {
            icon_name: self.icon_name(),
            icon_pixmap: Vec::new(),
            title: "Controid".into(),
            description: lines.join("\n"),
        }
    }

    fn watcher_offline(&self, reason: ksni::OfflineReason) -> bool {
        match reason {
            ksni::OfflineReason::No => {
                warn!("StatusNotifierWatcher went offline; waiting for watcher return");
                true
            }
            ksni::OfflineReason::Error(err) => {
                warn!(error = %err, "StatusNotifierWatcher error; shutting down tray");
                false
            }
            _ => false,
        }
    }

    fn menu(&self) -> Vec<ksni::MenuItem<Self>> {
        use ksni::menu::*;

        vec![
            StandardItem {
                label: self.status_summary(),
                enabled: false,
                ..Default::default()
            }
            .into(),
            MenuItem::Separator,
            StandardItem {
                label: "Open Control Center".into(),
                icon_name: "preferences-system".into(),
                activate: Box::new(|this: &mut Self| {
                    let _ = this.cmd_tx.send(TrayCommand::OpenGui);
                }),
                ..Default::default()
            }
            .into(),
            MenuItem::Separator,
            StandardItem {
                label: "Connect / Resume".into(),
                enabled: self.daemon_online && self.state != "connected",
                activate: Box::new(|this: &mut Self| {
                    let _ = this.cmd_tx.send(TrayCommand::ResumeConnection);
                }),
                ..Default::default()
            }
            .into(),
            StandardItem {
                label: "Disconnect".into(),
                enabled: self.daemon_online && self.state != "idle",
                activate: Box::new(|this: &mut Self| {
                    let _ = this.cmd_tx.send(TrayCommand::Disconnect);
                }),
                ..Default::default()
            }
            .into(),
            StandardItem {
                label: "Enable Hotspot".into(),
                enabled: self.daemon_online,
                activate: Box::new(|this: &mut Self| {
                    let _ = this.cmd_tx.send(TrayCommand::HotspotEnable);
                }),
                ..Default::default()
            }
            .into(),
            StandardItem {
                label: "Disable Hotspot".into(),
                enabled: self.daemon_online,
                activate: Box::new(|this: &mut Self| {
                    let _ = this.cmd_tx.send(TrayCommand::HotspotDisable);
                }),
                ..Default::default()
            }
            .into(),
            MenuItem::Separator,
            StandardItem {
                label: "Start Screen Stream".into(),
                enabled: self.daemon_online,
                activate: Box::new(|this: &mut Self| {
                    let _ = this.cmd_tx.send(TrayCommand::StreamStart);
                }),
                ..Default::default()
            }
            .into(),
            StandardItem {
                label: "Stop Screen Stream".into(),
                enabled: self.daemon_online,
                activate: Box::new(|this: &mut Self| {
                    let _ = this.cmd_tx.send(TrayCommand::StreamStop);
                }),
                ..Default::default()
            }
            .into(),
            StandardItem {
                label: if self.files_mounted {
                    "Unmount Phone Storage".into()
                } else {
                    "Mount Phone Storage".into()
                },
                enabled: self.daemon_online && (self.files_mounted || self.state == "connected"),
                activate: Box::new(|this: &mut Self| {
                    let cmd = if this.files_mounted {
                        TrayCommand::FilesUnmount
                    } else {
                        TrayCommand::FilesMount
                    };
                    let _ = this.cmd_tx.send(cmd);
                }),
                ..Default::default()
            }
            .into(),
            MenuItem::Separator,
            StandardItem {
                label: "Quit".into(),
                icon_name: "application-exit".into(),
                activate: Box::new(|this: &mut Self| {
                    let _ = this.cmd_tx.send(TrayCommand::Quit);
                }),
                ..Default::default()
            }
            .into(),
        ]
    }
}

fn load_config_or_default() -> AppConfig {
    let path = AppConfig::config_path();
    match fs::read_to_string(&path) {
        Ok(raw) => toml::from_str(&raw).unwrap_or_default(),
        Err(_) => AppConfig::default(),
    }
}

async fn ipc_call(
    method: &str,
    params: serde_json::Value,
    timeout: Duration,
) -> Result<Option<serde_json::Value>> {
    let sock_path = ipc::default_socket_path();
    let stream = UnixStream::connect(&sock_path)
        .await
        .with_context(|| format!("connect to {}", sock_path.display()))?;
    let (read_half, mut write_half) = stream.into_split();
    let mut reader = BufReader::new(read_half);

    let req = TrayIpcRequest {
        id: 1,
        method: method.to_owned(),
        params,
    };
    let mut payload = serde_json::to_string(&req)?;
    payload.push('\n');
    write_half.write_all(payload.as_bytes()).await?;
    write_half.flush().await?;

    let mut line = String::new();
    let n = tokio::time::timeout(timeout, reader.read_line(&mut line))
        .await
        .context("timed out reading daemon response")??;
    if n == 0 {
        bail!("daemon closed IPC connection");
    }
    if line.len() > ipc::MAX_IPC_LINE_BYTES {
        bail!("IPC line exceeded max length");
    }

    let resp: TrayIpcResponse = serde_json::from_str(line.trim_end())?;
    if resp.id != 1 {
        bail!("unexpected IPC response id {}", resp.id);
    }
    if let Some(err) = resp.error {
        bail!("IPC error ({}): {}", err.code, err.message);
    }
    Ok(resp.result)
}

async fn run_telemetry_loop(handle: ksni::Handle<ControidTray>) {
    let sock_path = ipc::default_socket_path();

    loop {
        if handle.is_closed() {
            break;
        }

        if let Ok(Some(status_val)) =
            ipc_call("device.status", json!({}), Duration::from_secs(3)).await
        {
            handle
                .update(|tray| tray.apply_status(&status_val))
                .await;
        }
        if let Ok(Some(files_val)) =
            ipc_call("files.status", json!({}), Duration::from_secs(3)).await
        {
            handle
                .update(|tray| tray.apply_files_status(&files_val))
                .await;
        }

        let stream = match UnixStream::connect(&sock_path).await {
            Ok(s) => s,
            Err(err) => {
                debug!(error = %err, "controidd IPC unavailable; retrying");
                handle
                    .update(|tray| {
                        tray.daemon_online = false;
                        tray.files_mounted = false;
                        tray.state = "idle".to_owned();
                    })
                    .await;
                tokio::time::sleep(Duration::from_secs(3)).await;
                continue;
            }
        };

        let (read_half, mut write_half) = stream.into_split();
        let mut reader = BufReader::new(read_half);

        let sub = TrayIpcRequest {
            id: 1,
            method: "events.subscribe".to_owned(),
            params: json!({}),
        };
        let Ok(mut payload) = serde_json::to_string(&sub) else {
            break;
        };
        payload.push('\n');
        if write_half.write_all(payload.as_bytes()).await.is_err()
            || write_half.flush().await.is_err()
        {
            tokio::time::sleep(Duration::from_secs(3)).await;
            continue;
        }

        let mut ack_line = String::new();
        match tokio::time::timeout(Duration::from_secs(5), reader.read_line(&mut ack_line)).await {
            Ok(Ok(n)) if n > 0 && ack_line.len() <= ipc::MAX_IPC_LINE_BYTES => {
                if let Ok(ack) = serde_json::from_str::<TrayIpcResponse>(ack_line.trim_end())
                    && ack.error.is_none()
                {
                    handle.update(|tray| tray.daemon_online = true).await;
                } else {
                    tokio::time::sleep(Duration::from_secs(3)).await;
                    continue;
                }
            }
            _ => {
                tokio::time::sleep(Duration::from_secs(3)).await;
                continue;
            }
        }

        loop {
            if handle.is_closed() {
                return;
            }
            let mut line = String::new();
            match reader.read_line(&mut line).await {
                Ok(0) | Err(_) => break,
                Ok(_) => {
                    if line.len() > ipc::MAX_IPC_LINE_BYTES {
                        continue;
                    }
                    let trimmed = line.trim();
                    if trimmed.is_empty() {
                        continue;
                    }
                    if let Ok(ev) = serde_json::from_str::<TrayIpcEvent>(trimmed) {
                        handle.update(|tray| tray.apply_event(&ev)).await;
                    }
                }
            }
        }

        handle
            .update(|tray| {
                tray.daemon_online = false;
            })
            .await;
        tokio::time::sleep(Duration::from_secs(2)).await;
    }
}

fn spawn_gui_process() -> Result<()> {
    let mut candidates = Vec::new();
    if let Ok(exe) = std::env::current_exe()
        && let Some(dir) = exe.parent()
    {
        candidates.push(dir.join("controid-gui"));
    }
    candidates.push(std::path::PathBuf::from("controid-gui"));

    let mut last_err = None;
    for bin in candidates {
        match std::process::Command::new(&bin)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
        {
            Ok(_child) => return Ok(()),
            Err(err) => last_err = Some(err),
        }
    }

    Err(anyhow::anyhow!(
        "failed to launch controid-gui: {}",
        last_err
            .map(|e| e.to_string())
            .unwrap_or_else(|| "not found".to_owned())
    ))
}

async fn handle_command(cmd: TrayCommand, handle: ksni::Handle<ControidTray>) {
    if matches!(cmd, TrayCommand::OpenGui) {
        if let Err(err) = spawn_gui_process() {
            warn!(error = %err, "failed to launch Controid GUI");
            let err_str = err.to_string();
            handle
                .update(|tray| {
                    tray.last_error = Some(err_str);
                })
                .await;
        }
        return;
    }

    let (method, params, timeout) = match cmd {
        TrayCommand::OpenGui | TrayCommand::Quit => return,
        TrayCommand::ResumeConnection => ("connection.resume", json!({}), Duration::from_secs(30)),
        TrayCommand::Disconnect => ("connection.disconnect", json!({}), Duration::from_secs(15)),
        TrayCommand::HotspotEnable => ("hotspot.enable", json!({}), Duration::from_secs(30)),
        TrayCommand::HotspotDisable => ("hotspot.disable", json!({}), Duration::from_secs(15)),
        TrayCommand::StreamStart => {
            let cfg = load_config_or_default();
            (
                "stream.start",
                json!({
                    "max_fps": cfg.streaming.max_fps,
                    "bitrate_mbps": cfg.streaming.bitrate_mbps
                }),
                Duration::from_secs(15),
            )
        }
        TrayCommand::StreamStop => ("stream.stop", json!({}), Duration::from_secs(10)),
        TrayCommand::FilesMount => ("files.mount", json!({}), Duration::from_secs(15)),
        TrayCommand::FilesUnmount => ("files.unmount", json!({}), Duration::from_secs(15)),
    };

    match ipc_call(method, params, timeout).await {
        Err(err) => {
            warn!(method, error = %err, "tray IPC command failed");
            let err_str = err.to_string();
            handle
                .update(|tray| {
                    tray.last_error = Some(err_str);
                })
                .await;
        }
        Ok(res) => {
            handle
                .update(|tray| {
                    tray.last_error = None;
                    if let Some(val) = &res
                        && val.get("mounted").is_some()
                    {
                        tray.apply_files_status(val);
                    }
                })
                .await;
        }
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .init();

    let (cmd_tx, mut cmd_rx) = mpsc::unbounded_channel();
    let tray = ControidTray::new(cmd_tx);

    let handle = match tray.spawn().await {
        Ok(h) => h,
        Err(err) => {
            warn!(error = %err, "StatusNotifierWatcher unavailable; exiting tray cleanly");
            return Ok(());
        }
    };
    info!("Controid StatusNotifierItem tray started");

    let telemetry_task = tokio::spawn(run_telemetry_loop(handle.clone()));

    loop {
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {
                break;
            }
            maybe_cmd = cmd_rx.recv() => {
                match maybe_cmd {
                    Some(TrayCommand::Quit) | None => break,
                    Some(cmd) => {
                        let h = handle.clone();
                        tokio::spawn(handle_command(cmd, h));
                    }
                }
            }
        }
    }

    telemetry_task.abort();
    handle.shutdown().await;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use ksni::Tray;

    #[test]
    fn tray_updates_status_icon_and_tooltip() {
        let (tx, _rx) = mpsc::unbounded_channel();
        let mut tray = ControidTray::new(tx);
        assert_eq!(tray.id(), "controid");
        assert_eq!(tray.title(), "Controid");
        assert_eq!(tray.icon_name(), "controid");
        let pixmap = tray.icon_pixmap();
        assert_eq!(pixmap.len(), 1);
        assert_eq!(pixmap[0].width, 48);
        assert_eq!(pixmap[0].height, 48);
        assert_eq!(pixmap[0].data.len(), 48 * 48 * 4);
        assert_eq!(tray.status(), ksni::Status::Active);

        tray.apply_status(&json!({
            "state": "connected",
            "battery": 78,
            "charging": true,
            "tethering": true,
            "hid_connected": true
        }));
        assert!(tray.daemon_online);
        assert_eq!(tray.icon_name(), "controid");
        assert_eq!(tray.tool_tip().title, "Controid");
        assert!(tray.tool_tip().description.contains("78%+"));

        tray.apply_event(&TrayIpcEvent {
            event: "error.tethering".to_owned(),
            data: json!({"code": "0x82", "message": "Carrier Provisioning Failed"}),
        });
        assert_eq!(tray.status(), ksni::Status::NeedsAttention);
        assert!(tray.tool_tip().description.contains("0x82"));
    }
}
