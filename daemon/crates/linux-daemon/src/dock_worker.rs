use crate::sender::{OutgoingPriority, PrioritySender};
use anyhow::{Context, Result};
use bytes::{BufMut, BytesMut};
use core_protocol::{
    CHANNEL_DOCK, DOCK_ICON_REQ, DOCK_ICON_RESP, DOCK_LAUNCH_ACK, DOCK_LAUNCH_REQ,
    DOCK_LIST_REQ, DOCK_MEDIA_SPECTRUM, DOCK_PIN_SET, DOCK_RUNTIME_UPDATE, DOCK_SNAPSHOT,
    DOCK_SYSTEM_ACTION, FLAG_ABORT, FLAG_END, FLAG_START, FLAG_URGENT, Frame, MAX_PAYLOAD_SIZE,
};
use hyprland_client::dock::{
    DesktopAppEntry, DesktopEnv, DockLaunchAction, MediaStatus, RuntimeWindowState,
    WorkspaceInfo, detect_desktop_env, detect_session_type, executable_exists, execute_app_action,
    icon_fingerprint, now_unix_secs, perform_system_action, query_media_status,
    query_runtime_state, resolve_icon_png, scan_desktop_apps, spawn_desktop_event_watcher,
};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::Arc;
use std::sync::atomic::{AtomicU16, AtomicU64, Ordering};
use std::time::{Duration, Instant};
use tokio::io::{AsyncBufReadExt, BufReader, Lines};
use tokio::process::{Child, ChildStdout, Command as TokioCommand};
use tokio::sync::{mpsc, watch};
use tracing::{debug, info, warn};

const MAX_INBOUND_DOCK_BYTES: usize = 256 * 1024;
const RUNTIME_POLL_INTERVAL: Duration = Duration::from_secs(2);
const SPECTRUM_BAR_COUNT: usize = 16;
const SPECTRUM_MIN_INTERVAL: Duration = Duration::from_millis(50);
const CAVA_RESTART_BACKOFF: Duration = Duration::from_secs(5);
static CAVA_CONFIG_SEQ: AtomicU64 = AtomicU64::new(1);

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct LaunchStat {
    pub count: u32,
    pub last_launched: u64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct DockPersistState {
    pub pinned_ids: Vec<String>,
    pub launch_stats: HashMap<String, LaunchStat>,
}

impl DockPersistState {
    pub fn state_path() -> PathBuf {
        dirs::config_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("controid")
            .join("dock_state.json")
    }

    pub fn load_or_default() -> Self {
        let path = Self::state_path();
        match fs::read_to_string(&path) {
            Ok(raw) => serde_json::from_str(&raw).unwrap_or_default(),
            Err(_) => Self::default(),
        }
    }

    pub fn save(&self) -> Result<()> {
        let path = Self::state_path();
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let encoded = serde_json::to_string_pretty(self)?;
        fs::write(&path, encoded).with_context(|| format!("failed to write {}", path.display()))?;
        Ok(())
    }

    pub fn ensure_initial_pins(&mut self, apps: &[DesktopAppEntry]) {
        if !self.pinned_ids.is_empty() {
            let valid: HashSet<&str> = apps.iter().map(|a| a.id.as_str()).collect();
            self.pinned_ids.retain(|id| valid.contains(id.as_str()));
            return;
        }

        let preferred_patterns = [
            "firefox",
            "chromium",
            "brave",
            "kitty",
            "alacritty",
            "foot",
            "wezterm",
            "ghostty",
            "konsole",
            "gnome-terminal",
            "xfce4-terminal",
            "nautilus",
            "thunar",
            "dolphin",
            "nemo",
            "code",
            "antigravity",
            "discord",
            "spotify",
            "vlc",
        ];

        for pat in preferred_patterns {
            if self.pinned_ids.len() >= 6 {
                break;
            }
            if let Some(found) = apps.iter().find(|a| {
                let id_l = a.id.to_ascii_lowercase();
                let bin_l = a
                    .exec_bin
                    .as_deref()
                    .map(|b| b.to_ascii_lowercase())
                    .unwrap_or_default();
                id_l.contains(pat) || bin_l == pat
            }) && !self.pinned_ids.contains(&found.id)
            {
                self.pinned_ids.push(found.id.clone());
            }
        }
        let _ = self.save();
    }
}

#[derive(Debug, Clone, Serialize)]
struct DockAppWire {
    id: String,
    name: String,
    generic_name: Option<String>,
    categories: Vec<String>,
    icon_hash: Option<String>,
    has_icon: bool,
    running_count: u32,
    focused: bool,
    terminal: bool,
    launch_count: u32,
    last_launched: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
struct DockRuntimePayload {
    desktop_env: String,
    session_type: String,
    supports_window_focus: bool,
    supports_workspaces: bool,
    active_workspace: Option<String>,
    workspaces: Vec<WorkspaceInfo>,
    running_counts: HashMap<String, u32>,
    focused_id: Option<String>,
    pinned_ids: Vec<String>,
    frequent_ids: Vec<String>,
    media: Option<MediaStatus>,
}

#[derive(Debug, Deserialize)]
struct ListReq {
    #[serde(default)]
    force_refresh: bool,
}

#[derive(Debug, Deserialize)]
struct IconReq {
    ids: Vec<String>,
    #[serde(default = "default_icon_size")]
    size: u32,
}

fn default_icon_size() -> u32 {
    96
}

#[derive(Debug, Deserialize)]
struct LaunchReq {
    desktop_id: String,
    #[serde(default)]
    action: Option<String>,
}

#[derive(Debug, Deserialize)]
struct PinSetReq {
    #[serde(default)]
    desktop_id: Option<String>,
    #[serde(default)]
    pinned: Option<bool>,
    #[serde(default)]
    pinned_ids: Option<Vec<String>>,
}

#[derive(Debug, Deserialize)]
struct SystemActionReq {
    action: String,
    #[serde(default)]
    value: Option<String>,
}

pub struct DockWorker {
    sender: PrioritySender,
    next_stream_id: Arc<AtomicU16>,
    de: DesktopEnv,
    session_type: &'static str,
    apps: Vec<DesktopAppEntry>,
    icon_hashes: HashMap<String, Option<String>>,
    persist: DockPersistState,
    runtime: RuntimeWindowState,
    media: Option<MediaStatus>,
    inbound_streams: HashMap<u16, BytesMut>,
}

impl DockWorker {
    pub fn new(sender: PrioritySender) -> Self {
        Self {
            sender,
            next_stream_id: Arc::new(AtomicU16::new(0x8001)),
            de: detect_desktop_env(),
            session_type: detect_session_type(),
            apps: Vec::new(),
            icon_hashes: HashMap::new(),
            persist: DockPersistState::load_or_default(),
            runtime: RuntimeWindowState::default(),
            media: None,
            inbound_streams: HashMap::new(),
        }
    }

    pub async fn run(
        mut self,
        mut dock_rx: mpsc::Receiver<Frame>,
        mut shutdown: watch::Receiver<bool>,
    ) {
        let (de_event_tx, mut de_event_rx) = mpsc::channel(16);
        let de_watcher = spawn_desktop_event_watcher(self.de.clone(), de_event_tx);

        // Initial catalog scan + snapshot push
        self.refresh_catalog().await;
        self.refresh_runtime().await;
        if let Err(err) = self.send_snapshot().await {
            debug!(error = %err, "initial dock snapshot send skipped");
        }

        let mut poll_ticker = tokio::time::interval(RUNTIME_POLL_INTERVAL);
        poll_ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        poll_ticker.tick().await;

        let mut last_runtime_wire = self.build_runtime_payload();
        let mut cava_session: Option<CavaSession> = None;
        let mut cava_retry_after: Option<Instant> = None;

        loop {
            self.sync_cava_state(&mut cava_session, &mut cava_retry_after)
                .await;

            tokio::select! {
                biased;

                changed = shutdown.changed() => {
                    if changed.is_err() || *shutdown.borrow() {
                        break;
                    }
                }

                maybe_frame = dock_rx.recv() => match maybe_frame {
                    Some(frame) => {
                        self.handle_inbound_frame(frame).await;
                    }
                    None => break,
                },

                Some(()) = de_event_rx.recv() => {
                    while de_event_rx.try_recv().is_ok() {}
                    self.refresh_runtime().await;
                    let next_wire = self.build_runtime_payload();
                    if next_wire != last_runtime_wire {
                        last_runtime_wire = next_wire.clone();
                        let _ = self.send_runtime_update(&next_wire).await;
                    }
                }

                _ = poll_ticker.tick() => {
                    self.refresh_runtime().await;
                    let next_wire = self.build_runtime_payload();
                    if next_wire != last_runtime_wire {
                        last_runtime_wire = next_wire.clone();
                        let _ = self.send_runtime_update(&next_wire).await;
                    }
                }

                line_res = async {
                    match cava_session.as_mut() {
                        Some(sess) => sess.lines.next_line().await,
                        None => std::future::pending().await,
                    }
                } => {
                    match line_res {
                        Ok(Some(line)) => {
                            self.handle_cava_line(&mut cava_session, &line);
                        }
                        Ok(None) | Err(_) => {
                            self.stop_cava(&mut cava_session).await;
                            cava_retry_after = Some(Instant::now() + CAVA_RESTART_BACKOFF);
                        }
                    }
                }
            }
        }

        self.stop_cava(&mut cava_session).await;
        de_watcher.abort();
    }

    async fn sync_cava_state(
        &self,
        cava_session: &mut Option<CavaSession>,
        cava_retry_after: &mut Option<Instant>,
    ) {
        let playing = self.media.as_ref().is_some_and(|m| m.playing);
        if playing {
            if cava_session.is_none()
                && cava_retry_after.is_none_or(|t| Instant::now() >= t)
                && executable_exists("cava")
            {
                match CavaSession::spawn() {
                    Ok(sess) => {
                        debug!(config = %sess.config_path.display(), "started cava spectrum stream");
                        *cava_session = Some(sess);
                        *cava_retry_after = None;
                    }
                    Err(err) => {
                        debug!(error = %err, "failed to start cava spectrum stream");
                        *cava_retry_after = Some(Instant::now() + CAVA_RESTART_BACKOFF);
                    }
                }
            }
        } else {
            self.stop_cava(cava_session).await;
            *cava_retry_after = None;
        }
    }

    fn handle_cava_line(&self, cava_session: &mut Option<CavaSession>, line: &str) {
        let Some(bars) = parse_cava_ascii_line(line) else {
            return;
        };
        let Some(sess) = cava_session.as_mut() else {
            return;
        };
        if sess.last_sent == Some(bars) {
            return;
        }
        let throttled = sess
            .last_sent_at
            .is_some_and(|t| t.elapsed() < SPECTRUM_MIN_INTERVAL);
        if throttled && bars != [0u8; SPECTRUM_BAR_COUNT] {
            return;
        }
        if self.try_send_spectrum_bars(&bars) {
            sess.last_sent = Some(bars);
            sess.last_sent_at = Some(Instant::now());
        }
    }

    async fn stop_cava(&self, cava_session: &mut Option<CavaSession>) {
        let Some(sess) = cava_session.take() else {
            return;
        };
        sess.stop().await;
        let _ = self.send_zero_spectrum().await;
    }

    fn try_send_spectrum_bars(&self, bars: &[u8; SPECTRUM_BAR_COUNT]) -> bool {
        let payload = build_spectrum_payload(bars);
        let sid = allocate_dock_stream_id(&self.next_stream_id);
        let frame = Frame::new(CHANNEL_DOCK, sid, FLAG_START | FLAG_END, 0, payload);
        self.sender.try_send_low(frame).is_ok()
    }

    async fn send_zero_spectrum(&self) -> Result<()> {
        let payload = build_spectrum_payload(&[0u8; SPECTRUM_BAR_COUNT]);
        let sid = allocate_dock_stream_id(&self.next_stream_id);
        send_dock_payload(&self.sender, sid, &payload, OutgoingPriority::Low).await
    }

    async fn refresh_catalog(&mut self) {
        let Ok((apps, hashes)) = tokio::task::spawn_blocking(|| {
            let apps = scan_desktop_apps();
            let mut hashes = HashMap::with_capacity(apps.len());
            for app in &apps {
                let fp = icon_fingerprint(app.icon_name.as_deref());
                hashes.insert(app.id.clone(), fp);
            }
            (apps, hashes)
        })
        .await
        else {
            return;
        };

        self.persist.ensure_initial_pins(&apps);
        self.apps = apps;
        self.icon_hashes = hashes;
        info!(
            count = self.apps.len(),
            de = self.de.display_name(),
            "dock desktop catalog loaded"
        );
    }

    async fn refresh_runtime(&mut self) {
        let apps = self.apps.clone();
        let de = self.de.clone();
        if let Ok((rt, media)) = tokio::task::spawn_blocking(move || {
            let rt = query_runtime_state(&apps, &de);
            let media = query_media_status();
            (rt, media)
        })
        .await
        {
            self.runtime = rt;
            self.media = media;
        }
    }

    async fn handle_inbound_frame(&mut self, frame: Frame) {
        let sid = frame.stream_id;
        if (frame.flags & FLAG_ABORT) != 0 {
            self.inbound_streams.remove(&sid);
            return;
        }

        let complete_payload = if (frame.flags & FLAG_START) != 0 && (frame.flags & FLAG_END) != 0 {
            self.inbound_streams.remove(&sid);
            Some(frame.payload)
        } else if (frame.flags & FLAG_START) != 0 {
            if frame.payload.len() > MAX_INBOUND_DOCK_BYTES {
                self.send_abort(sid);
                return;
            }
            self.inbound_streams.insert(sid, frame.payload);
            None
        } else if let Some(buf) = self.inbound_streams.get_mut(&sid) {
            if buf.len() + frame.payload.len() > MAX_INBOUND_DOCK_BYTES {
                self.inbound_streams.remove(&sid);
                self.send_abort(sid);
                return;
            }
            buf.extend_from_slice(&frame.payload);
            if (frame.flags & FLAG_END) != 0 {
                self.inbound_streams.remove(&sid)
            } else {
                None
            }
        } else {
            return;
        };

        if let Some(payload) = complete_payload {
            self.process_message(&payload).await;
        }
    }

    async fn process_message(&mut self, payload: &[u8]) {
        let Some((&opcode, body)) = payload.split_first() else {
            return;
        };

        match opcode {
            DOCK_LIST_REQ => {
                let force = serde_json::from_slice::<ListReq>(body)
                    .map(|r| r.force_refresh)
                    .unwrap_or(false);
                if force || self.apps.is_empty() {
                    self.refresh_catalog().await;
                }
                self.refresh_runtime().await;
                if let Err(err) = self.send_snapshot().await {
                    warn!(error = %err, "failed to send dock snapshot");
                }
            }

            DOCK_ICON_REQ => {
                let Ok(req) = serde_json::from_slice::<IconReq>(body) else {
                    return;
                };
                self.handle_icon_request(req).await;
            }

            DOCK_LAUNCH_REQ => {
                let Ok(req) = serde_json::from_slice::<LaunchReq>(body) else {
                    return;
                };
                self.handle_launch_request(req).await;
            }

            DOCK_PIN_SET => {
                let Ok(req) = serde_json::from_slice::<PinSetReq>(body) else {
                    return;
                };
                self.handle_pin_set(req).await;
            }

            DOCK_SYSTEM_ACTION => {
                let Ok(req) = serde_json::from_slice::<SystemActionReq>(body) else {
                    return;
                };
                self.handle_system_action(req).await;
            }

            other => {
                debug!(opcode = other, "ignoring unknown dock opcode");
            }
        }
    }

    async fn handle_icon_request(&self, req: IconReq) {
        let size = req.size.clamp(48, 160);
        let lookup: Vec<(String, String)> = req
            .ids
            .into_iter()
            .filter_map(|id| {
                let app = self.apps.iter().find(|a| a.id == id)?;
                let icon_name = app.icon_name.as_ref()?.clone();
                Some((id, icon_name))
            })
            .collect();

        if lookup.is_empty() {
            return;
        }

        let sender = self.sender.clone();
        let next_sid = Arc::clone(&self.next_stream_id);
        tokio::spawn(async move {
            for (id, icon_name) in lookup {
                let icon_res = tokio::task::spawn_blocking(move || resolve_icon_png(&icon_name, size))
                    .await
                    .ok()
                    .flatten();
                let Some((fp, png_bytes)) = icon_res else {
                    continue;
                };
                let id_bytes = id.as_bytes();
                let fp_bytes = fp.as_bytes();
                let mut msg = BytesMut::with_capacity(1 + 2 + id_bytes.len() + 2 + fp_bytes.len() + png_bytes.len());
                msg.put_u8(DOCK_ICON_RESP);
                msg.put_u16(id_bytes.len() as u16);
                msg.extend_from_slice(id_bytes);
                msg.put_u16(fp_bytes.len() as u16);
                msg.extend_from_slice(fp_bytes);
                msg.extend_from_slice(&png_bytes);

                let sid = allocate_dock_stream_id(&next_sid);
                if send_dock_payload(&sender, sid, &msg, OutgoingPriority::Low).await.is_err() {
                    break;
                }
            }
        });
    }

    async fn handle_launch_request(&mut self, req: LaunchReq) {
        let Some(app) = self.apps.iter().find(|a| a.id == req.desktop_id).cloned() else {
            let _ = self
                .send_launch_ack(&req.desktop_id, false, "error", Some("App not found"))
                .await;
            return;
        };

        let action = DockLaunchAction::from_str_opt(req.action.as_deref());
        let apps_snapshot = self.apps.clone();
        let de = self.de.clone();
        let app_clone = app.clone();

        let res = tokio::task::spawn_blocking(move || {
            execute_app_action(&app_clone, &apps_snapshot, action, &de)
        })
        .await;

        match res {
            Ok(Ok(outcome)) => {
                if outcome == "launched" || outcome == "focused" {
                    let stat = self.persist.launch_stats.entry(app.id.clone()).or_default();
                    stat.count = stat.count.saturating_add(1);
                    stat.last_launched = now_unix_secs();
                    let _ = self.persist.save();
                }
                let _ = self
                    .send_launch_ack(&app.id, true, outcome, None)
                    .await;
                tokio::time::sleep(Duration::from_millis(180)).await;
                self.refresh_runtime().await;
                let rt = self.build_runtime_payload();
                let _ = self.send_runtime_update(&rt).await;
            }
            Ok(Err(err)) => {
                warn!(app = %app.id, error = %err, "dock app action failed");
                let _ = self
                    .send_launch_ack(&app.id, false, "error", Some(&err.to_string()))
                    .await;
            }
            Err(err) => {
                let _ = self
                    .send_launch_ack(&app.id, false, "error", Some(&err.to_string()))
                    .await;
            }
        }
    }

    async fn handle_pin_set(&mut self, req: PinSetReq) {
        if let Some(list) = req.pinned_ids {
            let valid: HashSet<&str> = self.apps.iter().map(|a| a.id.as_str()).collect();
            let mut deduped = Vec::new();
            for id in list {
                if valid.contains(id.as_str()) && !deduped.contains(&id) {
                    deduped.push(id);
                }
            }
            self.persist.pinned_ids = deduped;
        } else if let (Some(id), Some(pinned)) = (req.desktop_id, req.pinned) {
            if pinned {
                if self.apps.iter().any(|a| a.id == id) && !self.persist.pinned_ids.contains(&id) {
                    self.persist.pinned_ids.push(id);
                }
            } else {
                self.persist.pinned_ids.retain(|x| x != &id);
            }
        }
        let _ = self.persist.save();
        let rt = self.build_runtime_payload();
        let _ = self.send_runtime_update(&rt).await;
    }

    async fn handle_system_action(&mut self, req: SystemActionReq) {
        let de = self.de.clone();
        let action = req.action.clone();
        let value = req.value.clone();
        let res = tokio::task::spawn_blocking(move || {
            perform_system_action(&action, value.as_deref(), &de)
        })
        .await;

        match res {
            Ok(Ok(outcome)) => {
                let _ = self.send_launch_ack("", true, &outcome, None).await;
                tokio::time::sleep(Duration::from_millis(120)).await;
                self.refresh_runtime().await;
                let rt = self.build_runtime_payload();
                let _ = self.send_runtime_update(&rt).await;
            }
            Ok(Err(err)) => {
                let _ = self
                    .send_launch_ack("", false, "error", Some(&err.to_string()))
                    .await;
            }
            Err(err) => {
                let _ = self
                    .send_launch_ack("", false, "error", Some(&err.to_string()))
                    .await;
            }
        }
    }

    fn frequent_ids(&self) -> Vec<String> {
        let mut ranked: Vec<(&String, &LaunchStat)> = self.persist.launch_stats.iter().collect();
        ranked.sort_by(|a, b| {
            b.1.count
                .cmp(&a.1.count)
                .then_with(|| b.1.last_launched.cmp(&a.1.last_launched))
        });
        ranked.into_iter().take(12).map(|(id, _)| id.clone()).collect()
    }

    fn build_runtime_payload(&self) -> DockRuntimePayload {
        DockRuntimePayload {
            desktop_env: self.de.display_name().to_string(),
            session_type: self.session_type.to_string(),
            supports_window_focus: self.runtime.supports_window_focus || self.de.supports_window_focus(),
            supports_workspaces: self.runtime.supports_workspaces || self.de.supports_workspaces(),
            active_workspace: self.runtime.active_workspace.clone(),
            workspaces: self.runtime.workspaces.clone(),
            running_counts: self.runtime.running_counts.clone(),
            focused_id: self.runtime.focused_id.clone(),
            pinned_ids: self.persist.pinned_ids.clone(),
            frequent_ids: self.frequent_ids(),
            media: self.media.clone(),
        }
    }

    async fn send_snapshot(&self) -> Result<()> {
        let apps_wire: Vec<DockAppWire> = self
            .apps
            .iter()
            .map(|app| {
                let stat = self.persist.launch_stats.get(&app.id);
                let icon_hash = self.icon_hashes.get(&app.id).cloned().flatten();
                let running_count = self.runtime.running_counts.get(&app.id).copied().unwrap_or(0);
                let focused = self.runtime.focused_id.as_deref() == Some(app.id.as_str());
                DockAppWire {
                    id: app.id.clone(),
                    name: app.name.clone(),
                    generic_name: app.generic_name.clone(),
                    categories: app.categories.clone(),
                    has_icon: icon_hash.is_some(),
                    icon_hash,
                    running_count,
                    focused,
                    terminal: app.terminal,
                    launch_count: stat.map(|s| s.count).unwrap_or(0),
                    last_launched: stat.map(|s| s.last_launched).unwrap_or(0),
                }
            })
            .collect();

        let payload_json = json!({
            "desktop_env": self.de.display_name(),
            "session_type": self.session_type,
            "supports_window_focus": self.runtime.supports_window_focus || self.de.supports_window_focus(),
            "supports_workspaces": self.runtime.supports_workspaces || self.de.supports_workspaces(),
            "active_workspace": self.runtime.active_workspace,
            "workspaces": self.runtime.workspaces,
            "pinned_ids": self.persist.pinned_ids,
            "frequent_ids": self.frequent_ids(),
            "media": self.media,
            "apps": apps_wire,
        });

        let json_bytes = serde_json::to_vec(&payload_json)?;
        let mut msg = BytesMut::with_capacity(1 + json_bytes.len());
        msg.put_u8(DOCK_SNAPSHOT);
        msg.extend_from_slice(&json_bytes);

        let sid = allocate_dock_stream_id(&self.next_stream_id);
        send_dock_payload(&self.sender, sid, &msg, OutgoingPriority::High).await
    }

    async fn send_runtime_update(&self, payload: &DockRuntimePayload) -> Result<()> {
        let json_bytes = serde_json::to_vec(payload)?;
        let mut msg = BytesMut::with_capacity(1 + json_bytes.len());
        msg.put_u8(DOCK_RUNTIME_UPDATE);
        msg.extend_from_slice(&json_bytes);

        let sid = allocate_dock_stream_id(&self.next_stream_id);
        send_dock_payload(&self.sender, sid, &msg, OutgoingPriority::High).await
    }

    async fn send_launch_ack(
        &self,
        desktop_id: &str,
        ok: bool,
        action: &str,
        error: Option<&str>,
    ) -> Result<()> {
        let json_bytes = serde_json::to_vec(&json!({
            "desktop_id": desktop_id,
            "ok": ok,
            "action": action,
            "error": error,
        }))?;
        let mut msg = BytesMut::with_capacity(1 + json_bytes.len());
        msg.put_u8(DOCK_LAUNCH_ACK);
        msg.extend_from_slice(&json_bytes);

        let sid = allocate_dock_stream_id(&self.next_stream_id);
        send_dock_payload(&self.sender, sid, &msg, OutgoingPriority::High).await
    }

    fn send_abort(&self, stream_id: u16) {
        let _ = self.sender.try_send_high(Frame::new(
            CHANNEL_DOCK,
            stream_id,
            FLAG_ABORT | FLAG_URGENT,
            0,
            BytesMut::new(),
        ));
    }
}

fn allocate_dock_stream_id(counter: &AtomicU16) -> u16 {
    loop {
        let cur = counter.load(Ordering::Relaxed);
        let normalized = if cur < 0x8001 || cur == 0xFFFF {
            0x8001
        } else {
            cur
        };
        let next = if normalized >= 0xFFFE {
            0x8001
        } else {
            normalized + 1
        };
        if counter
            .compare_exchange(cur, next, Ordering::Relaxed, Ordering::Relaxed)
            .is_ok()
        {
            return normalized;
        }
    }
}

async fn send_dock_payload(
    sender: &PrioritySender,
    stream_id: u16,
    data: &[u8],
    priority: OutgoingPriority,
) -> Result<()> {
    if data.is_empty() {
        sender
            .send(
                Frame::new(CHANNEL_DOCK, stream_id, FLAG_START | FLAG_END, 0, BytesMut::new()),
                priority,
            )
            .await
            .map_err(|_| anyhow::anyhow!("sender closed"))?;
        return Ok(());
    }

    let mut offset = 0usize;
    let mut seq = 0u32;
    while offset < data.len() {
        let end = (offset + MAX_PAYLOAD_SIZE).min(data.len());
        let mut flags = 0u16;
        if offset == 0 {
            flags |= FLAG_START;
        }
        if end >= data.len() {
            flags |= FLAG_END;
        }
        if priority == OutgoingPriority::High {
            flags |= FLAG_URGENT;
        }
        let frame = Frame::new(
            CHANNEL_DOCK,
            stream_id,
            flags,
            seq,
            BytesMut::from(&data[offset..end]),
        );
        sender
            .send(frame, priority)
            .await
            .map_err(|_| anyhow::anyhow!("sender closed"))?;
        offset = end;
        seq += 1;
    }
    Ok(())
}

struct CavaSession {
    child: Child,
    lines: Lines<BufReader<ChildStdout>>,
    config_path: PathBuf,
    last_sent: Option<[u8; SPECTRUM_BAR_COUNT]>,
    last_sent_at: Option<Instant>,
}

impl CavaSession {
    fn spawn() -> Result<Self> {
        let seq = CAVA_CONFIG_SEQ.fetch_add(1, Ordering::Relaxed);
        let config_path = std::env::temp_dir()
            .join(format!("controid-cava-{}-{seq}.conf", std::process::id()));
        fs::write(&config_path, build_cava_config())
            .with_context(|| format!("failed to write {}", config_path.display()))?;

        let mut cmd = TokioCommand::new("cava");
        cmd.arg("-p")
            .arg(&config_path)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true);

        let mut child = match cmd.spawn() {
            Ok(c) => c,
            Err(err) => {
                let _ = fs::remove_file(&config_path);
                return Err(err).context("failed to spawn cava");
            }
        };

        let Some(stdout) = child.stdout.take() else {
            let _ = child.start_kill();
            let _ = fs::remove_file(&config_path);
            anyhow::bail!("cava stdout pipe unavailable");
        };

        Ok(Self {
            child,
            lines: BufReader::new(stdout).lines(),
            config_path,
            last_sent: None,
            last_sent_at: None,
        })
    }

    async fn stop(mut self) {
        let _ = self.child.kill().await;
        let _ = self.child.wait().await;
        let _ = fs::remove_file(&self.config_path);
    }
}

impl Drop for CavaSession {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.config_path);
    }
}

pub fn build_cava_config() -> &'static str {
    "[general]\nframerate = 18\nbars = 16\n[output]\nmethod = raw\nraw_target = /dev/stdout\ndata_format = ascii\nascii_max_range = 100\nchannels = mono\n"
}

pub fn parse_cava_ascii_line(line: &str) -> Option<[u8; SPECTRUM_BAR_COUNT]> {
    // Strip any OSC title escape prefix (e.g. "\x1b]0;cava\x07") if present on startup.
    let cleaned = line.rsplit('\x07').next().unwrap_or(line).trim();
    if cleaned.is_empty() {
        return None;
    }

    let mut bars = [0u8; SPECTRUM_BAR_COUNT];
    let mut count = 0usize;
    let tokens: Box<dyn Iterator<Item = &str>> = if cleaned.contains(';') {
        Box::new(cleaned.split(';'))
    } else if cleaned.contains(',') {
        Box::new(cleaned.split(','))
    } else {
        Box::new(cleaned.split_whitespace())
    };

    for raw in tokens {
        let tok = raw.trim();
        if tok.is_empty() {
            continue;
        }
        let val: u16 = tok.parse().ok()?;
        if count >= SPECTRUM_BAR_COUNT {
            return None;
        }
        bars[count] = val.min(100) as u8;
        count += 1;
    }

    (count == SPECTRUM_BAR_COUNT).then_some(bars)
}

pub fn build_spectrum_payload(bars: &[u8; SPECTRUM_BAR_COUNT]) -> BytesMut {
    let mut buf = BytesMut::with_capacity(1 + SPECTRUM_BAR_COUNT);
    buf.put_u8(DOCK_MEDIA_SPECTRUM);
    for &b in bars {
        buf.put_u8(b.min(100));
    }
    buf
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_cava_ascii_line_handles_delimiters_clamping_and_osc() {
        let line = "0;5;12;33;100;120;45;0;1;2;3;4;5;6;7;8;";
        let parsed = parse_cava_ascii_line(line).unwrap();
        assert_eq!(
            parsed,
            [0, 5, 12, 33, 100, 100, 45, 0, 1, 2, 3, 4, 5, 6, 7, 8]
        );

        let with_osc = "\x1b]0;cava\x071;2;3;4;5;6;7;8;9;10;11;12;13;14;15;16;";
        assert_eq!(
            parse_cava_ascii_line(with_osc).unwrap(),
            [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16]
        );

        assert!(parse_cava_ascii_line("\x1b]0;cava\x07").is_none());
        assert!(parse_cava_ascii_line("1;2;3").is_none());
        assert!(parse_cava_ascii_line("1;2;foo;4;5;6;7;8;9;10;11;12;13;14;15;16;").is_none());
    }

    #[test]
    fn spectrum_payload_is_17_bytes_with_opcode_0x85() {
        let bars = [0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 150, 12, 8, 4, 0];
        let payload = build_spectrum_payload(&bars);
        assert_eq!(payload.len(), 17);
        assert_eq!(payload[0], DOCK_MEDIA_SPECTRUM);
        assert_eq!(DOCK_MEDIA_SPECTRUM, 0x85);
        assert_eq!(payload[12], 100);
    }

    #[test]
    fn cava_config_targets_16_bars_raw_ascii_mono() {
        let cfg = build_cava_config();
        assert!(cfg.contains("framerate = 18"));
        assert!(cfg.contains("bars = 16"));
        assert!(cfg.contains("method = raw"));
        assert!(cfg.contains("raw_target = /dev/stdout"));
        assert!(cfg.contains("data_format = ascii"));
        assert!(cfg.contains("ascii_max_range = 100"));
        assert!(cfg.contains("channels = mono"));
    }
}
