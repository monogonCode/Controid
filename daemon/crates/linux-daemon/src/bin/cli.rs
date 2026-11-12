use anyhow::{Context, Result, anyhow, bail};
use clap::{Parser, Subcommand};
use linux_daemon::{ble, config::AppConfig, ipc};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::fs;
use std::io::{self, Write};
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;

#[derive(Parser)]
#[command(name = "controid", about = "Controid Linux-Android CLI")]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Discover nearby Controid BLE devices and save config.toml
    Pair {
        #[arg(long, default_value_t = 10)]
        timeout: u64,
    },
    /// Trigger connection (LAN or Android hotspot depending on config)
    Connect,
    /// Disconnect active session (pass --hotspot-off to also disable Android hotspot via BLE)
    Disconnect {
        #[arg(long)]
        hotspot_off: bool,
    },
    /// Query current link and device telemetry status
    Status {
        #[arg(long)]
        json: bool,
    },
    /// Stream NDJSON telemetry events (Waybar mode by default)
    Events {
        /// Output raw daemon NDJSON events instead of Waybar JSON objects
        #[arg(long, conflicts_with = "waybar")]
        raw: bool,
        /// Output Waybar custom module NDJSON objects (default)
        #[arg(long)]
        waybar: bool,
    },
    /// Control Android Wi-Fi hotspot (on | off)
    Hotspot {
        #[arg(value_parser = ["on", "off"])]
        action: String,
    },
    /// Control Android screen streaming and remote navigation keys
    Screen {
        #[command(subcommand)]
        command: ScreenCommands,
    },
    /// Mount, unmount, or inspect Android FUSE storage
    Files {
        #[command(subcommand)]
        command: FilesCommands,
    },
    /// Clipboard operations
    Clipboard {
        #[command(subcommand)]
        command: ClipboardCommands,
    },
}

#[derive(Subcommand)]
enum ScreenCommands {
    /// Start screen mirror stream
    Start {
        #[arg(long)]
        max_fps: Option<u16>,
        #[arg(long)]
        bitrate_mbps: Option<u8>,
    },
    /// Stop active screen mirror stream
    Stop,
    /// Send a remote Android key (back, home, recents, power)
    Key {
        #[arg(value_parser = ["back", "home", "recents", "power"])]
        key: String,
    },
}

#[derive(Subcommand)]
enum FilesCommands {
    /// Mount Android storage via FUSE
    Mount {
        #[arg(long)]
        mount_point: Option<String>,
    },
    /// Unmount Android FUSE storage
    Unmount,
    /// Query FUSE storage mount status
    Status,
}

#[derive(Subcommand)]
enum ClipboardCommands {
    /// Send text to the paired Android device clipboard
    Send {
        text: String,
        #[arg(long, default_value = "text/plain")]
        mime: String,
    },
}

#[derive(Debug, Serialize)]
struct ClientIpcRequest {
    id: u64,
    method: String,
    params: serde_json::Value,
}

#[derive(Debug, Deserialize)]
struct ClientIpcError {
    code: i32,
    message: String,
}

#[derive(Debug, Deserialize)]
struct ClientIpcResponse {
    id: u64,
    result: Option<serde_json::Value>,
    error: Option<ClientIpcError>,
}

#[derive(Debug, Deserialize)]
struct ClientIpcEvent {
    event: String,
    data: serde_json::Value,
}

#[derive(Debug, Clone, Default)]
struct WaybarState {
    link_state: String,
    ssid: Option<String>,
    battery: Option<u8>,
    charging: bool,
    tethering: bool,
    hid_connected: bool,
    stream_active: bool,
    stream_resolution: Option<(u64, u64)>,
    last_error: Option<String>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
struct WaybarOutput {
    text: String,
    alt: String,
    tooltip: String,
    class: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    percentage: Option<u8>,
    #[serde(skip_serializing_if = "Option::is_none")]
    event: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    data: Option<serde_json::Value>,
}

impl WaybarState {
    fn from_status(val: &serde_json::Value) -> Self {
        let mut st = Self::default();
        st.apply_status(val);
        st
    }

    fn apply_status(&mut self, val: &serde_json::Value) {
        if let Some(s) = val.get("state").and_then(|v| v.as_str()) {
            self.link_state = s.to_owned();
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

    fn apply_event(&mut self, ev: &ClientIpcEvent) {
        match ev.event.as_str() {
            "link.state_changed" => {
                if let Some(s) = ev.data.get("state").and_then(|v| v.as_str()) {
                    self.link_state = s.to_owned();
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
            "error.tethering" => {
                let code = ev
                    .data
                    .get("code")
                    .and_then(|v| v.as_str())
                    .unwrap_or("err");
                let msg = ev
                    .data
                    .get("message")
                    .and_then(|v| v.as_str())
                    .unwrap_or("Tethering error");
                self.last_error = Some(format!("{code}: {msg}"));
            }
            "stream.state" => {
                if let Some(active) = ev.data.get("active").and_then(|v| v.as_bool()) {
                    self.stream_active = active;
                }
                if let (Some(w), Some(h)) = (
                    ev.data.get("width").and_then(|v| v.as_u64()),
                    ev.data.get("height").and_then(|v| v.as_u64()),
                ) {
                    self.stream_resolution = Some((w, h));
                }
            }
            "device.status" => {
                self.apply_status(&ev.data);
            }
            _ => {}
        }
    }

    fn to_waybar(&self, ev: Option<&ClientIpcEvent>) -> WaybarOutput {
        let state = if self.link_state.is_empty() {
            "idle"
        } else {
            self.link_state.as_str()
        };

        let class = if self.last_error.is_some() && state != "connected" {
            "error".to_owned()
        } else {
            state.to_owned()
        };

        let mut text = match state {
            "connected" => match self.battery {
                Some(pct) if self.charging => format!("Android {pct}%+"),
                Some(pct) => format!("Android {pct}%"),
                None => "Android Connected".to_owned(),
            },
            "connecting_ble" => "Android (BLE)".to_owned(),
            "connecting_wifi" => "Android (Wi-Fi)".to_owned(),
            "authenticating" => "Android (Auth)".to_owned(),
            "suspended" => "Android (Suspended)".to_owned(),
            _ if self.last_error.is_some() => "Android (Error)".to_owned(),
            _ => "Android (Idle)".to_owned(),
        };
        if self.stream_active {
            text.push_str(" [Stream]");
        }

        let mut tip_lines = vec![format!("State: {state}")];
        if let Some(ssid) = &self.ssid {
            tip_lines.push(format!("SSID: {ssid}"));
        }
        if let Some(pct) = self.battery {
            tip_lines.push(format!(
                "Battery: {pct}%{}",
                if self.charging { " (charging)" } else { "" }
            ));
        }
        tip_lines.push(format!(
            "Tethering: {} | HID: {}",
            if self.tethering { "on" } else { "off" },
            if self.hid_connected { "on" } else { "off" }
        ));
        if self.stream_active {
            if let Some((w, h)) = self.stream_resolution {
                tip_lines.push(format!("Screen stream: active ({w}x{h})"));
            } else {
                tip_lines.push("Screen stream: active".to_owned());
            }
        }
        if let Some(err) = &self.last_error {
            tip_lines.push(format!("Error: {err}"));
        }

        WaybarOutput {
            text,
            alt: state.to_owned(),
            tooltip: tip_lines.join("\n"),
            class,
            percentage: self.battery,
            event: ev.map(|e| e.event.clone()),
            data: ev.map(|e| e.data.clone()),
        }
    }
}

async fn read_bounded_line(
    reader: &mut BufReader<tokio::net::unix::OwnedReadHalf>,
    timeout: Duration,
) -> Result<String> {
    let mut line = String::new();
    let n = tokio::time::timeout(timeout, reader.read_line(&mut line))
        .await
        .context("timed out waiting for daemon IPC response")?
        .context("failed reading daemon IPC response")?;
    if n == 0 {
        bail!("daemon closed IPC connection unexpectedly");
    }
    if line.len() > ipc::MAX_IPC_LINE_BYTES {
        bail!("IPC line exceeded maximum {} bytes", ipc::MAX_IPC_LINE_BYTES);
    }
    Ok(line)
}

async fn ipc_call(
    method: &str,
    params: serde_json::Value,
    timeout: Duration,
) -> Result<Option<serde_json::Value>> {
    let sock_path = ipc::default_socket_path();
    let stream = UnixStream::connect(&sock_path).await.with_context(|| {
        format!(
            "failed to connect to controidd socket at {}",
            sock_path.display()
        )
    })?;
    let (read_half, mut write_half) = stream.into_split();
    let mut reader = BufReader::new(read_half);

    let req = ClientIpcRequest {
        id: 1,
        method: method.to_owned(),
        params,
    };
    let mut payload = serde_json::to_string(&req)?;
    payload.push('\n');
    write_half
        .write_all(payload.as_bytes())
        .await
        .context("failed sending IPC request")?;
    write_half.flush().await?;

    let line = read_bounded_line(&mut reader, timeout).await?;
    let resp: ClientIpcResponse =
        serde_json::from_str(line.trim_end()).context("invalid JSON response from daemon")?;
    if resp.id != 1 {
        bail!("unexpected IPC response id {}", resp.id);
    }
    if let Some(err) = resp.error {
        bail!("daemon error ({}): {}", err.code, err.message);
    }
    Ok(resp.result)
}

fn load_config_or_default() -> AppConfig {
    let path = AppConfig::config_path();
    match fs::read_to_string(&path) {
        Ok(raw) => toml::from_str(&raw).unwrap_or_default(),
        Err(_) => AppConfig::default(),
    }
}

fn save_config(cfg: &AppConfig) -> Result<()> {
    let path = AppConfig::config_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let body = toml::to_string_pretty(cfg)?;
    fs::write(&path, body)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(&path, fs::Permissions::from_mode(0o600));
    }
    Ok(())
}

fn parse_discovered_devices(val: &serde_json::Value) -> Vec<(String, Option<String>, bool)> {
    let list = if let Some(arr) = val.get("devices").and_then(|v| v.as_array()) {
        arr
    } else if let Some(arr) = val.as_array() {
        arr
    } else {
        return Vec::new();
    };

    let mut out = Vec::new();
    for item in list {
        if let Some(obj) = item.as_object() {
            let mac = obj
                .get("mac_address")
                .or_else(|| obj.get("mac"))
                .or_else(|| obj.get("address"))
                .and_then(|v| v.as_str())
                .map(ToOwned::to_owned);
            let alias = obj
                .get("alias")
                .or_else(|| obj.get("name"))
                .and_then(|v| v.as_str())
                .filter(|s| !s.is_empty())
                .map(ToOwned::to_owned);
            let paired = obj.get("paired").and_then(|v| v.as_bool()).unwrap_or(false);
            if let Some(m) = mac {
                out.push((m, alias, paired));
            }
        } else if let Some(pair) = item.as_array()
            && let Some(mac) = pair.first().and_then(|v| v.as_str())
        {
            let alias = pair
                .get(1)
                .and_then(|v| v.as_str())
                .filter(|s| !s.is_empty())
                .map(ToOwned::to_owned);
            let paired = pair.get(2).and_then(|v| v.as_bool()).unwrap_or(false);
            out.push((mac.to_owned(), alias, paired));
        }
    }
    out
}

fn prompt_line(prompt: &str) -> Result<String> {
    print!("{prompt}");
    io::stdout().flush()?;
    let mut input = String::new();
    io::stdin().read_line(&mut input)?;
    Ok(input.trim().to_owned())
}

async fn run_pair(timeout_secs: u64) -> Result<()> {
    let mut cfg = load_config_or_default();
    println!(
        "Scanning for Controid BLE devices ({}s)...",
        timeout_secs
    );

    let devices = match ipc_call(
        "pair.discover",
        json!({ "timeout_secs": timeout_secs }),
        Duration::from_secs(timeout_secs + 5),
    )
    .await
    {
        Ok(Some(val)) => parse_discovered_devices(&val),
        Ok(None) => Vec::new(),
        Err(_) => {
            let sys_conn = zbus::Connection::system()
                .await
                .context("failed to connect to system D-Bus for BLE discovery")?;
            ble::discover_controid_devices(&sys_conn, &cfg.device.service_uuid, timeout_secs)
                .await?
                .into_iter()
                .map(|d| (d.mac, d.name, d.paired))
                .collect()
        }
    };

    if devices.is_empty() {
        bail!(
            "no Controid BLE devices found advertising {}",
            cfg.device.service_uuid
        );
    }

    println!("Discovered devices:");
    for (idx, (mac, alias, paired)) in devices.iter().enumerate() {
        let name = alias.as_deref().unwrap_or("(unnamed)");
        let badge = if *paired { " [paired]" } else { "" };
        println!("  {}. {}  {}{}", idx + 1, mac, name, badge);
    }

    let selected_idx = loop {
        let prompt = if devices.len() == 1 {
            "Select device [1]: ".to_owned()
        } else {
            format!("Select device [1-{}]: ", devices.len())
        };
        let raw = prompt_line(&prompt)?;
        if raw.is_empty() && devices.len() == 1 {
            break 0;
        }
        if let Ok(n) = raw.parse::<usize>()
            && (1..=devices.len()).contains(&n)
        {
            break n - 1;
        }
        println!("Invalid selection.");
    };

    let (selected_mac, selected_alias, _paired) = &devices[selected_idx];
    let existing_ssid = cfg.device.hotspot_ssid.clone();
    let ssid = loop {
        let prompt = if existing_ssid.is_empty() {
            "Enter Android hotspot SSID: ".to_owned()
        } else {
            format!("Enter Android hotspot SSID [{existing_ssid}]: ")
        };
        let raw = prompt_line(&prompt)?;
        if raw.is_empty() && !existing_ssid.is_empty() {
            break existing_ssid;
        }
        if !raw.is_empty() {
            break raw;
        }
        println!("Hotspot SSID cannot be empty.");
    };

    cfg.device.mac_address = selected_mac.clone();
    cfg.device.hotspot_ssid = ssid.clone();
    save_config(&cfg)?;

    println!(
        "Paired {} ({}) with SSID '{}' -> {}",
        selected_mac,
        selected_alias.as_deref().unwrap_or("unnamed"),
        ssid,
        AppConfig::config_path().display()
    );
    Ok(())
}

async fn run_status(as_json: bool) -> Result<()> {
    let result = ipc_call("device.status", json!({}), Duration::from_secs(5))
        .await?
        .ok_or_else(|| anyhow!("empty result from device.status"))?;

    if as_json {
        println!("{}", serde_json::to_string(&result)?);
        return Ok(());
    }

    let state = result
        .get("state")
        .and_then(|v| v.as_str())
        .unwrap_or("unknown");
    let battery = result
        .get("battery")
        .and_then(|v| v.as_u64())
        .map(|b| format!("{b}%"))
        .unwrap_or_else(|| "n/a".to_owned());
    let charging = result
        .get("charging")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let tethering = result
        .get("tethering")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let hid = result
        .get("hid_connected")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    println!("State:         {state}");
    println!(
        "Battery:       {}{}",
        battery,
        if charging { " (charging)" } else { "" }
    );
    println!("Tethering:     {tethering}");
    println!("HID connected: {hid}");
    Ok(())
}

fn emit_line_stdout(line: &str) -> Result<bool> {
    let mut out = io::stdout().lock();
    if let Err(err) = writeln!(out, "{line}").and_then(|_| out.flush()) {
        if err.kind() == io::ErrorKind::BrokenPipe {
            return Ok(false);
        }
        return Err(err.into());
    }
    Ok(true)
}

async fn run_events(raw_mode: bool) -> Result<()> {
    let mut wb_state = if !raw_mode {
        match ipc_call("device.status", json!({}), Duration::from_secs(3)).await {
            Ok(Some(val)) => WaybarState::from_status(&val),
            _ => WaybarState::default(),
        }
    } else {
        WaybarState::default()
    };

    let sock_path = ipc::default_socket_path();
    let stream = UnixStream::connect(&sock_path).await.with_context(|| {
        format!(
            "failed to connect to controidd socket at {}",
            sock_path.display()
        )
    })?;
    let (read_half, mut write_half) = stream.into_split();
    let mut reader = BufReader::new(read_half);

    let req = ClientIpcRequest {
        id: 1,
        method: "events.subscribe".to_owned(),
        params: json!({}),
    };
    let mut payload = serde_json::to_string(&req)?;
    payload.push('\n');
    write_half.write_all(payload.as_bytes()).await?;
    write_half.flush().await?;

    let ack_line = read_bounded_line(&mut reader, Duration::from_secs(5)).await?;
    let ack: ClientIpcResponse = serde_json::from_str(ack_line.trim_end())
        .context("invalid events.subscribe response JSON")?;
    if let Some(err) = ack.error {
        bail!("events.subscribe error ({}): {}", err.code, err.message);
    }

    if !raw_mode {
        let line = serde_json::to_string(&wb_state.to_waybar(None))?;
        if !emit_line_stdout(&line)? {
            return Ok(());
        }
    }

    loop {
        let mut line = String::new();
        let n = reader.read_line(&mut line).await?;
        if n == 0 {
            break;
        }
        if line.len() > ipc::MAX_IPC_LINE_BYTES {
            continue;
        }
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        if raw_mode {
            if !emit_line_stdout(trimmed)? {
                break;
            }
        } else if let Ok(ev) = serde_json::from_str::<ClientIpcEvent>(trimmed) {
            wb_state.apply_event(&ev);
            let out_json = serde_json::to_string(&wb_state.to_waybar(Some(&ev)))?;
            if !emit_line_stdout(&out_json)? {
                break;
            }
        }
    }

    Ok(())
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();

    match cli.command {
        Commands::Pair { timeout } => run_pair(timeout).await,
        Commands::Connect => {
            let res = ipc_call("connection.resume", json!({}), Duration::from_secs(30)).await?;
            if let Some(v) = res {
                println!("{}", serde_json::to_string(&v)?);
            } else {
                println!("ok");
            }
            Ok(())
        }
        Commands::Disconnect { hotspot_off } => {
            let method = if hotspot_off {
                "hotspot.disable"
            } else {
                "connection.disconnect"
            };
            let res = ipc_call(method, json!({}), Duration::from_secs(15)).await?;
            if let Some(v) = res {
                println!("{}", serde_json::to_string(&v)?);
            } else {
                println!("ok");
            }
            Ok(())
        }
        Commands::Status { json } => run_status(json).await,
        Commands::Events { raw, .. } => run_events(raw).await,
        Commands::Hotspot { action } => {
            let method = match action.as_str() {
                "on" => "hotspot.enable",
                "off" => "hotspot.disable",
                other => bail!("invalid hotspot action '{other}' (expected 'on' or 'off')"),
            };
            let res = ipc_call(method, json!({}), Duration::from_secs(30)).await?;
            if let Some(v) = res {
                println!("{}", serde_json::to_string(&v)?);
            } else {
                println!("ok");
            }
            Ok(())
        }
        Commands::Screen { command } => {
            let res = match command {
                ScreenCommands::Start {
                    max_fps,
                    bitrate_mbps,
                } => {
                    let cfg = load_config_or_default();
                    ipc_call(
                        "stream.start",
                        json!({
                            "max_fps": max_fps.unwrap_or(cfg.streaming.max_fps),
                            "bitrate_mbps": bitrate_mbps.unwrap_or(cfg.streaming.bitrate_mbps),
                        }),
                        Duration::from_secs(15),
                    )
                    .await?
                }
                ScreenCommands::Stop => ipc_call("stream.stop", json!({}), Duration::from_secs(10)).await?,
                ScreenCommands::Key { key } => {
                    ipc_call("input.key", json!({ "key": key }), Duration::from_secs(10)).await?
                }
            };
            if let Some(v) = res {
                println!("{}", serde_json::to_string(&v)?);
            } else {
                println!("ok");
            }
            Ok(())
        }
        Commands::Files { command } => {
            let res = match command {
                FilesCommands::Mount { mount_point } => {
                    let params = match mount_point {
                        Some(mp) => json!({ "mount_point": mp }),
                        None => json!({}),
                    };
                    ipc_call("files.mount", params, Duration::from_secs(15)).await?
                }
                FilesCommands::Unmount => {
                    ipc_call("files.unmount", json!({}), Duration::from_secs(15)).await?
                }
                FilesCommands::Status => {
                    ipc_call("files.status", json!({}), Duration::from_secs(5)).await?
                }
            };
            if let Some(v) = res {
                println!("{}", serde_json::to_string(&v)?);
            } else {
                println!("ok");
            }
            Ok(())
        }
        Commands::Clipboard { command } => match command {
            ClipboardCommands::Send { text, mime } => {
                let res = ipc_call(
                    "clipboard.send",
                    json!({ "mime": mime, "data": text }),
                    Duration::from_secs(10),
                )
                .await?;
                if let Some(v) = res {
                    println!("{}", serde_json::to_string(&v)?);
                } else {
                    println!("ok");
                }
                Ok(())
            }
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn waybar_state_formats_events_and_errors() {
        let mut st = WaybarState::from_status(&json!({
            "state": "connected",
            "battery": 85,
            "charging": false,
            "tethering": true,
            "hid_connected": true
        }));
        let out = st.to_waybar(None);
        assert_eq!(out.text, "Android 85%");
        assert_eq!(out.class, "connected");
        assert_eq!(out.percentage, Some(85));

        st.apply_event(&ClientIpcEvent {
            event: "stream.state".to_owned(),
            data: json!({"active": true, "width": 1080, "height": 2400}),
        });
        assert!(st.to_waybar(None).text.contains("[Stream]"));

        st.apply_event(&ClientIpcEvent {
            event: "link.state_changed".to_owned(),
            data: json!({"state": "idle", "ssid": "AndroidAP"}),
        });
        st.apply_event(&ClientIpcEvent {
            event: "error.tethering".to_owned(),
            data: json!({"code": "0x82", "message": "Carrier Provisioning Failed"}),
        });
        let err_out = st.to_waybar(None);
        assert_eq!(err_out.class, "error");
        assert!(err_out.tooltip.contains("0x82"));
    }

    #[test]
    fn parse_discovered_devices_supports_objects_and_tuples() {
        let obj_payload = json!({
            "devices": [
                {"mac": "AA:BB:CC:DD:EE:FF", "alias": "Pixel 8", "paired": true},
                {"address": "11:22:33:44:55:66", "name": ""}
            ]
        });
        let parsed = parse_discovered_devices(&obj_payload);
        assert_eq!(
            parsed,
            vec![
                ("AA:BB:CC:DD:EE:FF".to_owned(), Some("Pixel 8".to_owned()), true),
                ("11:22:33:44:55:66".to_owned(), None, false)
            ]
        );
    }
}
