use anyhow::{Context, Result, anyhow};
use bytes::{Bytes, BytesMut};
use core_protocol::{
    CHANNEL_REMOTE_INPUT, CODEC_H264, CODEC_H265, ControlMessage, EchoSuppressor, FLAG_URGENT,
    FlowController, Frame, INITIAL_WINDOW_CREDITS, IdentityKeyPair, InputFallbackEvent,
    ProtocolCodec, RemoteInputEvent,
};
use futures::StreamExt;
use hyprland_client::{
    ClipboardEvent, ClipboardPayload, CompositorBackend, HidWatchdog, LinuxDesktopBackend,
    MpvVideoSink,
};
use linux_daemon::ble;
use linux_daemon::clipboard_worker::{
    ClipboardWorker, MAX_SPOOL_BYTES, OutboundClipboardRequest, compute_payload_echo_hash,
    purge_stale_spool_files,
};
use linux_daemon::config::AppConfig;
use linux_daemon::dispatcher::{ChannelRouter, Dispatcher, perform_l7_handshake};
use linux_daemon::files::{self, FilesHandle, default_files_socket_path};
use linux_daemon::ipc::{IpcEvent, default_socket_path, start_ipc_server};
use linux_daemon::network;
use linux_daemon::notify;
use linux_daemon::power::{self, PowerEvent};
use linux_daemon::sender::{
    DEFAULT_HIGH_CHANNEL_CAPACITY, DEFAULT_LOW_CHANNEL_CAPACITY, PrioritySender,
    run_priority_emitter,
};
use linux_daemon::state::{CommandError, DaemonCommand, DaemonLinkState, DaemonTelemetry};
use rand::Rng;
use serde_json::json;
use std::pin::Pin;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::net::TcpStream;
use tokio::sync::{broadcast, mpsc, oneshot, watch};
use tokio::task::JoinHandle;
use tokio::time::{Instant, MissedTickBehavior, Sleep};
use tokio_util::codec::Framed;
use tracing::{debug, info, warn};

const TCP_CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const DIRECT_LAN_PROBE_TIMEOUT: Duration = Duration::from_millis(1800);
const BLE_TRIGGER_TIMEOUT: Duration = Duration::from_secs(45);
const WIFI_CONNECT_TIMEOUT_SECS: u64 = 25;
const HEARTBEAT_INTERVAL: Duration = Duration::from_secs(2);
const HEARTBEAT_DEAD_TIMEOUT: Duration = Duration::from_secs(20);
const STREAM_ACK_TIMEOUT: Duration = Duration::from_secs(5);

enum ConnectOutcome {
    Progress(DaemonLinkState),
    MacDiscovered { mac: String, paired: bool },
    Connected {
        framed: Framed<TcpStream, ProtocolCodec>,
        verified_peer_pubkey: Option<[u8; 32]>,
    },
    Failed {
        err: anyhow::Error,
        tethering_code: Option<String>,
    },
}

enum MediaWorkerCmd {
    Start(u8),
    Stop,
}

struct PendingStreamStart {
    codec: u8,
    reply: oneshot::Sender<Result<serde_json::Value, CommandError>>,
    deadline: Instant,
}

struct ActiveSession {
    sender: PrioritySender,
    outbound_clip_flow: FlowController,
    shutdown_tx: watch::Sender<bool>,
    ctrl_rx: Option<mpsc::Receiver<Frame>>,
    local_clip_tx: mpsc::Sender<ClipboardEvent>,
    manual_clip_tx: mpsc::Sender<OutboundClipboardRequest>,
    media_cmd_tx: mpsc::Sender<MediaWorkerCmd>,
    session_end_rx: mpsc::Receiver<anyhow::Result<()>>,
    last_ctrl_rx: Instant,
    pending_stream: Option<PendingStreamStart>,
    stream_active: bool,
    emitter_handle: JoinHandle<()>,
    dispatcher_handle: JoinHandle<()>,
    clipboard_handle: JoinHandle<()>,
    media_handle: JoinHandle<()>,
    input_handle: JoinHandle<()>,
}

impl ActiveSession {
    async fn teardown(mut self, send_stream_stop: bool) {
        if send_stream_stop && self.stream_active {
            let _ = self
                .sender
                .try_send_high(Frame::ctrl_urgent(ControlMessage::StreamStop.encode()));
        }
        if let Some(pending) = self.pending_stream.take() {
            let _ = pending
                .reply
                .send(Err(CommandError::new(5002, "session terminated")));
        }

        let _ = self.media_cmd_tx.try_send(MediaWorkerCmd::Stop);
        self.outbound_clip_flow.close();
        let _ = self.shutdown_tx.send(true);

        // Wait briefly for graceful worker teardown (closes mpv & removes incomplete spool files).
        let _ = tokio::time::timeout(Duration::from_secs(2), async {
            let _ = self.clipboard_handle.await;
            let _ = self.media_handle.await;
        })
        .await;

        self.emitter_handle.abort();
        self.dispatcher_handle.abort();
        self.input_handle.abort();
    }
}

fn merge_json_value(dst: &mut serde_json::Value, patch: &serde_json::Value) {
    match (dst, patch) {
        (serde_json::Value::Object(dst_map), serde_json::Value::Object(patch_map)) => {
            for (k, v) in patch_map {
                match dst_map.get_mut(k) {
                    Some(existing) if existing.is_object() && v.is_object() => {
                        merge_json_value(existing, v);
                    }
                    Some(existing) => {
                        *existing = v.clone();
                    }
                    None => {
                        dst_map.insert(k.clone(), v.clone());
                    }
                }
            }
        }
        (dst_slot, patch_val) => *dst_slot = patch_val.clone(),
    }
}

struct DaemonRuntime {
    config: AppConfig,
    identity_key: Arc<IdentityKeyPair>,
    system_dbus: zbus::Connection,
    session_dbus: Option<zbus::Connection>,
    compositor: Arc<dyn CompositorBackend>,
    files: FilesHandle,
    telemetry: DaemonTelemetry,
    telemetry_tx: watch::Sender<DaemonTelemetry>,
    events_tx: broadcast::Sender<IpcEvent>,
    bt_resume_tx: mpsc::Sender<()>,
    bt_monitor_handle: JoinHandle<()>,
    hid_rx: watch::Receiver<bool>,
    hid_watch_active: bool,
    connect_task: Option<JoinHandle<()>>,
    connect_rx: Option<mpsc::Receiver<ConnectOutcome>>,
    active_session: Option<ActiveSession>,
    retry_count: u32,
    retry_sleep: Option<Pin<Box<Sleep>>>,
    was_active_before_suspend: bool,
    last_error: Option<String>,
}

impl DaemonRuntime {
    fn set_state(&mut self, next: DaemonLinkState) {
        if self.telemetry.state == next {
            return;
        }
        info!(from = %self.telemetry.state, to = %next, "daemon link state transition");
        self.telemetry.state = next;
        if next != DaemonLinkState::Connected {
            self.telemetry.tethering = false;
        } else {
            self.last_error = None;
        }
        self.publish_telemetry();
        let _ = self.events_tx.send(IpcEvent {
            event: "link.state_changed".to_string(),
            data: json!({
                "state": next.as_str(),
                "ssid": self.config.device.hotspot_ssid,
            }),
        });
    }

    fn publish_telemetry(&self) {
        let _ = self.telemetry_tx.send(self.telemetry.clone());
    }

    fn cancel_connect_and_backoff(&mut self) {
        if let Some(handle) = self.connect_task.take() {
            handle.abort();
        }
        self.connect_rx = None;
        self.retry_sleep = None;
    }

    fn start_connect_pipeline(&mut self, force_hotspot: bool) {
        if self.active_session.is_some() || self.connect_task.is_some() {
            return;
        }
        self.retry_sleep = None;
        let initial_state = if !force_hotspot
            && self.config.network.normalized_connection_mode() != "hotspot"
            && !self.config.network.direct_host.trim().is_empty()
        {
            DaemonLinkState::ConnectingWifi
        } else {
            DaemonLinkState::ConnectingBle
        };
        self.set_state(initial_state);

        let (tx, rx) = mpsc::channel(16);
        let config = self.config.clone();
        let system_dbus = self.system_dbus.clone();
        let identity_key = Arc::clone(&self.identity_key);

        let handle = tokio::spawn(async move {
            run_connect_pipeline(config, system_dbus, identity_key, force_hotspot, tx).await;
        });

        self.connect_task = Some(handle);
        self.connect_rx = Some(rx);
    }

    async fn enter_suspended(&mut self) {
        self.was_active_before_suspend = self.active_session.is_some()
            || self.connect_task.is_some()
            || self.retry_sleep.is_some();

        self.cancel_connect_and_backoff();
        self.retry_count = 0;
        if let Some(session) = self.active_session.take() {
            session.teardown(true).await;
        }
        self.files.set_connected(None).await;
        self.set_state(DaemonLinkState::Suspended);
    }

    async fn handle_resumed(&mut self) {
        if self.telemetry.state != DaemonLinkState::Suspended {
            return;
        }
        self.set_state(DaemonLinkState::Idle);
        let should_resume = self.was_active_before_suspend
            || (!self.config.device.hotspot_ssid.trim().is_empty()
                && network::is_ssid_active(&self.system_dbus, &self.config.device.hotspot_ssid)
                    .await
                    .unwrap_or(false));
        self.was_active_before_suspend = false;
        if should_resume {
            self.retry_count = 0;
            self.start_connect_pipeline(false);
        }
    }

    fn schedule_retry_or_exhaust(&mut self, err: &anyhow::Error) {
        self.last_error = Some(err.to_string());
        let max_retries = self.config.network.max_retries;
        self.retry_count = self.retry_count.saturating_add(1);

        if self.retry_count <= max_retries {
            let delay = compute_backoff_delay(self.retry_count);
            warn!(
                attempt = self.retry_count,
                max_retries,
                delay_ms = delay.as_millis() as u64,
                error = %err,
                "connection attempt failed; scheduling exponential backoff retry"
            );
            self.set_state(DaemonLinkState::Idle);
            self.retry_sleep = Some(Box::pin(tokio::time::sleep(delay)));
        } else {
            warn!(
                max_retries,
                error = %err,
                "connection retries exhausted; entering passive Idle"
            );
            self.retry_count = 0;
            self.retry_sleep = None;
            self.set_state(DaemonLinkState::Idle);
            if let Some(ref session_dbus) = self.session_dbus {
                notify::notify_fire_and_forget(
                    session_dbus.clone(),
                    "Controid Connection Failed",
                    &format!("Unable to connect after {max_retries} retries: {err}"),
                    2,
                );
            }
        }
    }

    fn update_discovered_mac(&mut self, mac: String, paired: bool) {
        if mac.is_empty() || self.config.device.mac_address.eq_ignore_ascii_case(&mac) {
            return;
        }
        if !paired && !self.config.device.mac_address.trim().is_empty() {
            info!(
                discovered_mac = %mac,
                configured_mac = %self.config.device.mac_address,
                "preserving configured Bluetooth MAC instead of unpaired RPA"
            );
            return;
        }
        info!(mac = %mac, paired, "updating persisted Controid Bluetooth MAC");
        self.config.device.mac_address = mac.clone();
        if let Err(err) = self.config.save() {
            warn!(error = %err, "failed to save updated MAC to config.toml");
        }

        self.bt_monitor_handle.abort();
        self.bt_monitor_handle = ble::spawn_bt_reconnect_monitor(
            self.system_dbus.clone(),
            mac.clone(),
            self.config.network.reconnect_cooldown_secs,
            self.bt_resume_tx.clone(),
        );
        self.hid_rx = HidWatchdog::spawn(Some(mac));
        self.hid_watch_active = true;
        self.telemetry.hid_connected = *self.hid_rx.borrow_and_update();
        self.publish_telemetry();
    }

    async fn install_active_session(
        &mut self,
        framed: Framed<TcpStream, ProtocolCodec>,
        verified_peer_pubkey: Option<[u8; 32]>,
    ) {
        if let Some(pubkey) = verified_peer_pubkey
            && self.config.device.peer_ed25519_pubkey_hex.is_none()
        {
            self.config.set_peer_pubkey(&pubkey);
            if let Err(err) = self.config.save() {
                warn!(error = %err, "failed to persist peer Ed25519 pubkey");
            }
        }

        self.retry_count = 0;
        self.retry_sleep = None;

        let session_id = uuid::Uuid::new_v4().simple().to_string();
        let (shutdown_tx, shutdown_rx) = watch::channel(false);
        let (sink, stream) = framed.split();
        let (sender, high_rx, low_rx) = PrioritySender::channel(
            DEFAULT_HIGH_CHANNEL_CAPACITY,
            DEFAULT_LOW_CHANNEL_CAPACITY,
        );
        let outbound_clip_flow = FlowController::new(INITIAL_WINDOW_CREDITS);

        let (ctrl_tx, ctrl_rx) = mpsc::channel(64);
        let (clip_meta_tx, clip_meta_rx) = mpsc::channel(32);
        let (clip_data_tx, clip_data_rx) = mpsc::channel(64);
        let (media_tx, media_rx) = mpsc::channel(128);
        let (input_tx, input_rx) = mpsc::channel(64);

        let (local_clip_tx, local_clip_rx) = mpsc::channel(16);
        let (manual_clip_tx, manual_clip_rx) = mpsc::channel(16);
        let (media_cmd_tx, media_cmd_rx) = mpsc::channel(8);
        let (session_end_tx, session_end_rx) = mpsc::channel(4);

        let router = ChannelRouter {
            ctrl_tx,
            clip_meta_tx,
            clip_data_tx,
            media_tx,
            input_tx,
            files_tx: self.files.files_tx.clone(),
        };

        let emitter_end_tx = session_end_tx.clone();
        let emitter_handle = tokio::spawn(async move {
            let res = run_priority_emitter(sink, high_rx, low_rx)
                .await
                .map_err(anyhow::Error::from);
            let _ = emitter_end_tx.try_send(res);
        });

        let dispatcher = Dispatcher::new(router, sender.clone())
            .with_outbound_clip_flow(outbound_clip_flow.clone());
        let disp_shutdown = shutdown_rx.clone();
        let disp_end_tx = session_end_tx;
        let dispatcher_handle = tokio::spawn(async move {
            let res = dispatcher
                .run(stream, disp_shutdown)
                .await
                .map_err(anyhow::Error::from);
            let _ = disp_end_tx.try_send(res);
        });

        let echo_suppressor = Arc::new(EchoSuppressor::default());
        let clipboard_worker = ClipboardWorker::new(
            session_id,
            sender.clone(),
            outbound_clip_flow.clone(),
            echo_suppressor,
            self.events_tx.clone(),
        )
        .with_compositor(Arc::clone(&self.compositor));
        let clip_shutdown = shutdown_rx.clone();
        let clipboard_handle = tokio::spawn(async move {
            clipboard_worker
                .run(
                    clip_meta_rx,
                    clip_data_rx,
                    local_clip_rx,
                    manual_clip_rx,
                    clip_shutdown,
                )
                .await;
        });

        let media_shutdown = shutdown_rx.clone();
        let media_events = self.events_tx.clone();
        let media_sender = sender.clone();
        let media_handle = tokio::spawn(async move {
            run_media_worker(media_rx, media_cmd_rx, media_sender, media_events, media_shutdown)
                .await;
        });

        let input_compositor = Arc::clone(&self.compositor);
        let input_hid_rx = self.hid_rx.clone();
        let input_shutdown = shutdown_rx;
        let input_handle = tokio::spawn(async move {
            run_input_worker(input_rx, input_compositor, input_hid_rx, input_shutdown).await;
        });

        self.active_session = Some(ActiveSession {
            sender,
            outbound_clip_flow,
            shutdown_tx,
            ctrl_rx: Some(ctrl_rx),
            local_clip_tx,
            manual_clip_tx,
            media_cmd_tx,
            session_end_rx,
            last_ctrl_rx: Instant::now(),
            pending_stream: None,
            stream_active: false,
            emitter_handle,
            dispatcher_handle,
            clipboard_handle,
            media_handle,
            input_handle,
        });

        self.telemetry.tethering = true;
        self.set_state(DaemonLinkState::Connected);
        self.files.set_connected(Some(self.active_session.as_ref().unwrap().sender.clone())).await;
        if self.config.files.auto_mount {
            let files = self.files.clone();
            let mount_point = Some(self.config.files.mount_point.clone());
            tokio::spawn(async move {
                if let Err(err) = files.mount(mount_point).await {
                    warn!(error = %err.message, "auto-mount failed on connect");
                }
            });
        }

        if !*self.hid_rx.borrow() && !self.config.device.mac_address.trim().is_empty() {
            let dbus = self.system_dbus.clone();
            let mac = self.config.device.mac_address.clone();
            tokio::spawn(async move {
                ble::ensure_hid_profile_connected(&dbus, &mac).await;
            });
        }
    }

    fn config_snapshot_json(&self) -> Result<serde_json::Value> {
        let mut val = serde_json::to_value(&self.config)?;
        if let Some(obj) = val.as_object_mut() {
            obj.insert(
                "config_path".to_string(),
                json!(AppConfig::config_path().display().to_string()),
            );
            obj.insert("last_error".to_string(), json!(self.last_error));
        }
        Ok(val)
    }

    async fn handle_command(&mut self, cmd: DaemonCommand) {
        match cmd {
            DaemonCommand::EnableHotspot { reply } => {
                if self.telemetry.state == DaemonLinkState::Connected {
                    let _ = reply.send(Ok(json!({ "status": "connected" })));
                    return;
                }
                if self.connect_task.is_some() {
                    let _ = reply.send(Ok(json!({ "status": "starting" })));
                    return;
                }
                self.retry_count = 0;
                self.last_error = None;
                self.start_connect_pipeline(true);
                let _ = reply.send(Ok(json!({ "status": "starting" })));
            }

            DaemonCommand::DisableHotspot { reply } => {
                self.cancel_connect_and_backoff();
                self.retry_count = 0;
                self.last_error = None;
                if let Some(session) = self.active_session.take() {
                    session.teardown(true).await;
                }
                self.files.set_connected(None).await;
                self.set_state(DaemonLinkState::Idle);

                if !self.config.device.mac_address.trim().is_empty() {
                    let dbus = self.system_dbus.clone();
                    let mac = self.config.device.mac_address.clone();
                    let uuid = self.config.device.service_uuid.clone();
                    let key = Arc::clone(&self.identity_key);
                    tokio::spawn(async move {
                        if let Err(err) = ble::stop_hotspot(&dbus, &mac, &uuid, &key).await {
                            debug!(error = %err, "best-effort stop_hotspot finished");
                        }
                    });
                }

                let _ = reply.send(Ok(json!({ "status": "idle" })));
            }

            DaemonCommand::ResumeConnection { reply } => {
                if self.telemetry.state == DaemonLinkState::Connected {
                    let _ = reply.send(Ok(json!({ "status": "connected" })));
                    return;
                }
                if self.connect_task.is_some() {
                    let _ = reply.send(Ok(json!({ "status": "starting" })));
                    return;
                }
                self.retry_count = 0;
                self.last_error = None;
                self.start_connect_pipeline(false);
                let _ = reply.send(Ok(json!({ "status": "resuming" })));
            }

            DaemonCommand::Disconnect { reply } => {
                self.cancel_connect_and_backoff();
                self.retry_count = 0;
                self.last_error = None;
                if let Some(session) = self.active_session.take() {
                    session.teardown(true).await;
                }
                self.files.set_connected(None).await;
                self.set_state(DaemonLinkState::Idle);
                let _ = reply.send(Ok(json!({ "status": "idle" })));
            }

            DaemonCommand::GetConfig { reply } => match self.config_snapshot_json() {
                Ok(val) => {
                    let _ = reply.send(Ok(val));
                }
                Err(err) => {
                    let _ = reply.send(Err(CommandError::internal(err.to_string())));
                }
            },

            DaemonCommand::SetConfig { config, reply } => {
                if !config.is_object() {
                    let _ = reply.send(Err(CommandError::invalid_params(
                        "config.set params must be a JSON object",
                    )));
                    return;
                }

                let mut base = match serde_json::to_value(&self.config) {
                    Ok(v) => v,
                    Err(err) => {
                        let _ = reply.send(Err(CommandError::internal(err.to_string())));
                        return;
                    }
                };
                merge_json_value(&mut base, &config);

                let mut updated: AppConfig = match serde_json::from_value(base) {
                    Ok(cfg) => cfg,
                    Err(err) => {
                        let _ = reply.send(Err(CommandError::invalid_params(format!(
                            "invalid config fields: {err}"
                        ))));
                        return;
                    }
                };

                updated.device.mac_address = updated.device.mac_address.trim().to_ascii_uppercase();
                updated.device.hotspot_ssid = updated.device.hotspot_ssid.trim().to_string();
                updated.device.service_uuid = updated.device.service_uuid.trim().to_string();
                if updated.device.service_uuid.is_empty() {
                    updated.device.service_uuid = ble::SERVICE_UUID.to_string();
                }
                if updated
                    .device
                    .peer_ed25519_pubkey_hex
                    .as_deref()
                    .is_some_and(|s| s.trim().is_empty())
                {
                    updated.device.peer_ed25519_pubkey_hex = None;
                }
                if let Err(err) = updated.peer_pubkey_bytes() {
                    let _ = reply.send(Err(CommandError::invalid_params(err.to_string())));
                    return;
                }
                if updated.network.tcp_port == 0 {
                    let _ = reply.send(Err(CommandError::invalid_params(
                        "network.tcp_port must be > 0",
                    )));
                    return;
                }
                updated.network.gateway_ip = updated.network.gateway_ip.trim().to_string();
                if updated.network.gateway_ip.is_empty() {
                    let _ = reply.send(Err(CommandError::invalid_params(
                        "network.gateway_ip cannot be empty",
                    )));
                    return;
                }
                updated.network.direct_host = updated.network.direct_host.trim().to_string();
                updated.network.connection_mode =
                    updated.network.normalized_connection_mode().to_string();
                if updated.streaming.max_fps == 0 || updated.streaming.bitrate_mbps == 0 {
                    let _ = reply.send(Err(CommandError::invalid_params(
                        "streaming.max_fps and bitrate_mbps must be > 0",
                    )));
                    return;
                }
                updated.files.mount_point = updated.files.mount_point.trim().to_string();
                if updated.files.mount_point.is_empty() {
                    updated.files.mount_point = "~/Controid".to_string();
                }

                if let Err(err) = updated.save() {
                    let _ = reply.send(Err(CommandError::internal(format!(
                        "failed to save config: {err}"
                    ))));
                    return;
                }

                let mac_changed =
                    !self.config.device.mac_address.eq_ignore_ascii_case(&updated.device.mac_address);
                let cooldown_changed = self.config.network.reconnect_cooldown_secs
                    != updated.network.reconnect_cooldown_secs;
                let mount_point_changed = self.config.files.mount_point != updated.files.mount_point;
                self.config = updated;

                if mount_point_changed {
                    self.files
                        .update_config(self.config.files.mount_point.clone())
                        .await;
                }

                if mac_changed || cooldown_changed {
                    self.bt_monitor_handle.abort();
                    self.bt_monitor_handle = ble::spawn_bt_reconnect_monitor(
                        self.system_dbus.clone(),
                        self.config.device.mac_address.clone(),
                        self.config.network.reconnect_cooldown_secs,
                        self.bt_resume_tx.clone(),
                    );
                }
                if mac_changed {
                    let next_mac = (!self.config.device.mac_address.trim().is_empty())
                        .then(|| self.config.device.mac_address.clone());
                    self.hid_rx = HidWatchdog::spawn(next_mac);
                    self.hid_watch_active = true;
                    self.telemetry.hid_connected = *self.hid_rx.borrow_and_update();
                    self.publish_telemetry();
                }

                match self.config_snapshot_json() {
                    Ok(snapshot) => {
                        let _ = self.events_tx.send(IpcEvent {
                            event: "config.changed".to_string(),
                            data: snapshot.clone(),
                        });
                        let _ = reply.send(Ok(snapshot));
                    }
                    Err(err) => {
                        let _ = reply.send(Err(CommandError::internal(err.to_string())));
                    }
                }
            }

            DaemonCommand::SendClipboard { mime, data, reply } => {
                let Some(session) = self.active_session.as_ref() else {
                    let _ = reply.send(Err(CommandError::not_connected()));
                    return;
                };
                if data.len() as u64 > MAX_SPOOL_BYTES {
                    let _ = reply.send(Err(CommandError::invalid_params(
                        "clipboard payload exceeds 512 MiB cap",
                    )));
                    return;
                }
                let echo_hash = compute_payload_echo_hash(&mime, &data);
                let req = OutboundClipboardRequest {
                    payload: ClipboardPayload::Memory { mime, data },
                    echo_hash,
                };
                match session.manual_clip_tx.try_send(req) {
                    Ok(()) => {
                        let _ = reply.send(Ok(()));
                    }
                    Err(_) => {
                        let _ = reply.send(Err(CommandError::new(
                            5003,
                            "clipboard worker busy or unavailable",
                        )));
                    }
                }
            }

            DaemonCommand::StartStream {
                max_fps,
                bitrate_mbps,
                codec,
                reply,
            } => {
                let Some(session) = self.active_session.as_mut() else {
                    let _ = reply.send(Err(CommandError::not_connected()));
                    return;
                };
                if session.pending_stream.is_some() {
                    let _ = reply.send(Err(CommandError::new(
                        5006,
                        "stream start already pending",
                    )));
                    return;
                }

                let fps = max_fps.unwrap_or(self.config.streaming.max_fps);
                let bitrate = bitrate_mbps.unwrap_or(self.config.streaming.bitrate_mbps);
                let codec_id =
                    codec.unwrap_or_else(|| codec_from_mime(&self.config.streaming.codec_mime));
                let req = ControlMessage::StreamReq {
                    max_fps: fps,
                    bitrate_mbps: bitrate,
                    codec: codec_id,
                };
                if session
                    .sender
                    .try_send_high(Frame::ctrl_urgent(req.encode()))
                    .is_err()
                {
                    let _ = reply.send(Err(CommandError::internal("failed to send STREAM_REQ")));
                    return;
                }

                session.pending_stream = Some(PendingStreamStart {
                    codec: codec_id,
                    reply,
                    deadline: Instant::now() + STREAM_ACK_TIMEOUT,
                });
            }

            DaemonCommand::StopStream { reply } => {
                let Some(session) = self.active_session.as_mut() else {
                    let _ = reply.send(Err(CommandError::not_connected()));
                    return;
                };
                let _ = session
                    .sender
                    .try_send_high(Frame::ctrl_urgent(ControlMessage::StreamStop.encode()));
                let _ = session.media_cmd_tx.try_send(MediaWorkerCmd::Stop);
                session.stream_active = false;
                let _ = self.events_tx.send(IpcEvent {
                    event: "stream.state".to_string(),
                    data: json!({ "active": false, "width": 0, "height": 0 }),
                });
                let _ = reply.send(Ok(()));
            }

            DaemonCommand::DiscoverDevices {
                timeout_secs,
                reply,
            } => {
                let dbus = self.system_dbus.clone();
                let uuid = self.config.device.service_uuid.clone();
                tokio::spawn(async move {
                    match ble::discover_controid_devices(&dbus, &uuid, timeout_secs).await {
                        Ok(devices) => {
                            let list: Vec<serde_json::Value> = devices
                                .into_iter()
                                .map(|dev| {
                                    json!({
                                        "mac": dev.mac,
                                        "mac_address": dev.mac,
                                        "name": dev.name,
                                        "alias": dev.name,
                                        "paired": dev.paired,
                                        "connected": dev.connected,
                                    })
                                })
                                .collect();
                            let _ = reply.send(Ok(json!({ "devices": list })));
                        }
                        Err(err) => {
                            let _ = reply.send(Err(CommandError::internal(err.to_string())));
                        }
                    }
                });
            }

            DaemonCommand::MountFiles { mount_point, reply } => {
                let files = self.files.clone();
                tokio::spawn(async move {
                    let res = files.mount(mount_point).await.and_then(|st| {
                        serde_json::to_value(st).map_err(|e| CommandError::internal(e.to_string()))
                    });
                    let _ = reply.send(res);
                });
            }

            DaemonCommand::UnmountFiles { reply } => {
                let files = self.files.clone();
                tokio::spawn(async move {
                    let res = files.unmount().await.and_then(|st| {
                        serde_json::to_value(st).map_err(|e| CommandError::internal(e.to_string()))
                    });
                    let _ = reply.send(res);
                });
            }

            DaemonCommand::FilesStatus { reply } => {
                let files = self.files.clone();
                tokio::spawn(async move {
                    let res = files.status().await.and_then(|st| {
                        serde_json::to_value(st).map_err(|e| CommandError::internal(e.to_string()))
                    });
                    let _ = reply.send(res);
                });
            }

            DaemonCommand::SendRemoteKey { keycode, reply } => {
                let Some(session) = self.active_session.as_ref() else {
                    let _ = reply.send(Err(CommandError::not_connected()));
                    return;
                };
                let down = match (RemoteInputEvent::Key {
                    action: 0,
                    keycode,
                    metastate: 0,
                    repeat: 0,
                })
                .encode()
                {
                    Ok(bytes) => bytes,
                    Err(err) => {
                        let _ = reply.send(Err(CommandError::internal(err.to_string())));
                        return;
                    }
                };
                let up = match (RemoteInputEvent::Key {
                    action: 1,
                    keycode,
                    metastate: 0,
                    repeat: 0,
                })
                .encode()
                {
                    Ok(bytes) => bytes,
                    Err(err) => {
                        let _ = reply.send(Err(CommandError::internal(err.to_string())));
                        return;
                    }
                };
                if session
                    .sender
                    .try_send_high(Frame::new(CHANNEL_REMOTE_INPUT, 0, FLAG_URGENT, 0, down))
                    .is_err()
                    || session
                        .sender
                        .try_send_high(Frame::new(CHANNEL_REMOTE_INPUT, 0, FLAG_URGENT, 0, up))
                        .is_err()
                {
                    let _ = reply.send(Err(CommandError::internal(
                        "failed to send remote key event",
                    )));
                    return;
                }
                let _ = reply.send(Ok(()));
            }
        }
    }

    async fn handle_ctrl_frame(&mut self, frame: Frame) {
        let Ok(msg) = ControlMessage::decode(&frame.payload) else {
            return;
        };

        let Some(session) = self.active_session.as_mut() else {
            return;
        };
        session.last_ctrl_rx = Instant::now();

        match msg {
            ControlMessage::DeviceStatus {
                battery_pct,
                charging,
                tethering,
                hid_connected,
            } => {
                self.telemetry.battery = Some(battery_pct);
                self.telemetry.charging = charging;
                self.telemetry.tethering = tethering;
                self.telemetry.hid_connected = hid_connected || *self.hid_rx.borrow();
                self.publish_telemetry();
            }
            ControlMessage::StreamAck {
                status,
                width,
                height,
            } => {
                let pending = session.pending_stream.take();
                if status == 0 {
                    let codec = pending
                        .as_ref()
                        .map(|p| p.codec)
                        .unwrap_or_else(|| codec_from_mime(&self.config.streaming.codec_mime));
                    let _ = session.media_cmd_tx.try_send(MediaWorkerCmd::Start(codec));
                    session.stream_active = true;
                    let _ = self.events_tx.send(IpcEvent {
                        event: "stream.state".to_string(),
                        data: json!({ "active": true, "width": width, "height": height }),
                    });
                    if let Some(p) = pending {
                        let _ = p.reply.send(Ok(json!({
                            "active": true,
                            "width": width,
                            "height": height,
                        })));
                    }
                } else if let Some(p) = pending {
                    let _ = p.reply.send(Err(CommandError::new(
                        5005,
                        format!("peer rejected stream start (status=0x{status:02x})"),
                    )));
                }
            }
            ControlMessage::StreamStopped => {
                session.stream_active = false;
                let _ = session.media_cmd_tx.try_send(MediaWorkerCmd::Stop);
                let _ = self.events_tx.send(IpcEvent {
                    event: "stream.state".to_string(),
                    data: json!({ "active": false, "width": 0, "height": 0 }),
                });
            }
            ControlMessage::Heartbeat { .. } | ControlMessage::CreditGrant { .. } | ControlMessage::Ack { .. } => {}
            _ => {}
        }
    }
}

async fn run_connect_pipeline(
    config: AppConfig,
    system_dbus: zbus::Connection,
    identity_key: Arc<IdentityKeyPair>,
    force_hotspot: bool,
    tx: mpsc::Sender<ConnectOutcome>,
) {
    let mode = if force_hotspot {
        "hotspot"
    } else {
        config.network.normalized_connection_mode()
    };
    let direct_host = config.network.direct_host.trim().to_string();
    let mut connected_stream: Option<TcpStream> = None;

    if mode == "lan" || (mode == "auto" && !direct_host.is_empty()) {
        if direct_host.is_empty() {
            let _ = tx
                .send(ConnectOutcome::Failed {
                    err: anyhow!("connection_mode is 'lan' but network.direct_host is empty"),
                    tethering_code: None,
                })
                .await;
            return;
        }

        let _ = tx
            .send(ConnectOutcome::Progress(DaemonLinkState::ConnectingWifi))
            .await;

        let direct_addr = format!("{direct_host}:{}", config.network.tcp_port);
        match tokio::time::timeout(DIRECT_LAN_PROBE_TIMEOUT, TcpStream::connect(&direct_addr)).await
        {
            Ok(Ok(stream)) => {
                info!(addr = %direct_addr, "connected over direct LAN");
                connected_stream = Some(stream);
            }
            Ok(Err(err)) => {
                if mode == "lan" {
                    let _ = tx
                        .send(ConnectOutcome::Failed {
                            err: anyhow!("Direct LAN TCP connect to {direct_addr} failed: {err}"),
                            tethering_code: None,
                        })
                        .await;
                    return;
                }
                debug!(addr = %direct_addr, error = %err, "direct LAN unreachable in auto mode; falling back to BLE hotspot");
            }
            Err(_) => {
                if mode == "lan" {
                    let _ = tx
                        .send(ConnectOutcome::Failed {
                            err: anyhow!("Direct LAN TCP connect to {direct_addr} timed out"),
                            tethering_code: None,
                        })
                        .await;
                    return;
                }
                debug!(addr = %direct_addr, "direct LAN probe timed out in auto mode; falling back to BLE hotspot");
            }
        }
    }

    let tcp_stream = match connected_stream {
        Some(stream) => stream,
        None => {
            let _ = tx
                .send(ConnectOutcome::Progress(DaemonLinkState::ConnectingBle))
                .await;

            let ssid_already_active = if config.device.hotspot_ssid.trim().is_empty() {
                false
            } else {
                network::is_ssid_active(&system_dbus, &config.device.hotspot_ssid)
                    .await
                    .unwrap_or(false)
            };

            if !ssid_already_active {
                match tokio::time::timeout(
                    BLE_TRIGGER_TIMEOUT,
                    ble::trigger_hotspot_and_wait(
                        &system_dbus,
                        &config.device.mac_address,
                        &config.device.service_uuid,
                        &identity_key,
                    ),
                )
                .await
                {
                    Ok(Ok((active_mac, active_paired))) => {
                        let _ = tx
                            .send(ConnectOutcome::MacDiscovered {
                                mac: active_mac,
                                paired: active_paired,
                            })
                            .await;
                    }
                    Ok(Err(err)) => {
                        let tethering_code = extract_tethering_error_code(&err);
                        let _ = tx
                            .send(ConnectOutcome::Failed {
                                err,
                                tethering_code,
                            })
                            .await;
                        return;
                    }
                    Err(_) => {
                        let _ = tx
                            .send(ConnectOutcome::Failed {
                                err: anyhow!("BLE hotspot trigger timed out after 45s"),
                                tethering_code: None,
                            })
                            .await;
                        return;
                    }
                }
            }

            let _ = tx
                .send(ConnectOutcome::Progress(DaemonLinkState::ConnectingWifi))
                .await;

            if !ssid_already_active
                && let Err(err) = network::connect_hotspot_ssid(
                    &system_dbus,
                    &config.device.hotspot_ssid,
                    WIFI_CONNECT_TIMEOUT_SECS,
                )
                .await
            {
                let _ = tx
                    .send(ConnectOutcome::Failed {
                        err,
                        tethering_code: None,
                    })
                    .await;
                return;
            }

            let gateway_host =
                network::get_active_wifi_gateway(&system_dbus, &config.device.hotspot_ssid)
                    .await
                    .ok()
                    .flatten()
                    .unwrap_or_else(|| config.network.gateway_ip.clone());
            let addr = format!("{gateway_host}:{}", config.network.tcp_port);

            match tokio::time::timeout(TCP_CONNECT_TIMEOUT, TcpStream::connect(&addr)).await {
                Ok(Ok(stream)) => stream,
                Ok(Err(err)) => {
                    let _ = tx
                        .send(ConnectOutcome::Failed {
                            err: anyhow!("TCP connect to {addr} failed: {err}"),
                            tethering_code: None,
                        })
                        .await;
                    return;
                }
                Err(_) => {
                    let _ = tx
                        .send(ConnectOutcome::Failed {
                            err: anyhow!("TCP connect to {addr} timed out"),
                            tethering_code: None,
                        })
                        .await;
                    return;
                }
            }
        }
    };

    let _ = tx
        .send(ConnectOutcome::Progress(DaemonLinkState::Authenticating))
        .await;

    let _ = tcp_stream.set_nodelay(true);
    let mut framed = Framed::new(tcp_stream, ProtocolCodec::new());

    let expected_peer = match config.peer_pubkey_bytes() {
        Ok(k) => k,
        Err(err) => {
            let _ = tx
                .send(ConnectOutcome::Failed {
                    err,
                    tethering_code: None,
                })
                .await;
            return;
        }
    };

    match perform_l7_handshake(&mut framed, &identity_key, expected_peer).await {
        Ok(verified_peer_pubkey) => {
            let _ = tx
                .send(ConnectOutcome::Connected {
                    framed,
                    verified_peer_pubkey,
                })
                .await;
        }
        Err(err) => {
            let _ = tx
                .send(ConnectOutcome::Failed {
                    err: err.into(),
                    tethering_code: None,
                })
                .await;
        }
    }
}

async fn run_media_worker(
    mut media_rx: mpsc::Receiver<Frame>,
    mut cmd_rx: mpsc::Receiver<MediaWorkerCmd>,
    sender: PrioritySender,
    events_tx: broadcast::Sender<IpcEvent>,
    mut shutdown: watch::Receiver<bool>,
) {
    let mut sink: Option<MpvVideoSink> = None;
    let mut viewer_input_rx: Option<mpsc::Receiver<Bytes>> = None;

    loop {
        let viewer_input_fut = async {
            match viewer_input_rx.as_mut() {
                Some(rx) => rx.recv().await,
                None => std::future::pending::<Option<Bytes>>().await,
            }
        };

        tokio::select! {
            biased;

            changed = shutdown.changed() => {
                if changed.is_err() || *shutdown.borrow() {
                    break;
                }
            }

            maybe_cmd = cmd_rx.recv() => match maybe_cmd {
                Some(MediaWorkerCmd::Start(codec)) => {
                    viewer_input_rx = None;
                    if let Some(prev) = sink.take() {
                        let _ = prev.stop().await;
                    }
                    match MpvVideoSink::start_interactive(codec).await {
                        Ok((s, rx)) => {
                            sink = Some(s);
                            viewer_input_rx = Some(rx);
                        }
                        Err(err) => warn!(error = %err, "failed to start video viewer sink"),
                    }
                }
                Some(MediaWorkerCmd::Stop) => {
                    viewer_input_rx = None;
                    if let Some(prev) = sink.take() {
                        let _ = prev.stop().await;
                    }
                }
                None => break,
            },

            maybe_input = viewer_input_fut => match maybe_input {
                Some(payload) => {
                    let frame = Frame::new(
                        CHANNEL_REMOTE_INPUT,
                        0,
                        FLAG_URGENT,
                        0,
                        BytesMut::from(payload.as_ref()),
                    );
                    if sender.try_send_high(frame).is_err() {
                        debug!("dropping remote input frame: queue full or closed");
                    }
                }
                None => {
                    viewer_input_rx = None;
                }
            },

            maybe_frame = media_rx.recv() => match maybe_frame {
                Some(frame) => {
                    if let Some(active_sink) = sink.as_mut()
                        && let Err(err) = active_sink.write_nal(frame.payload.freeze()).await
                    {
                        warn!(error = %err, "video sink write failed; stopping stream");
                        viewer_input_rx = None;
                        if let Some(prev) = sink.take() {
                            let _ = prev.stop().await;
                        }
                        let _ = sender.try_send_high(Frame::ctrl_urgent(ControlMessage::StreamStop.encode()));
                        let _ = events_tx.send(IpcEvent {
                            event: "stream.state".to_string(),
                            data: json!({ "active": false, "width": 0, "height": 0 }),
                        });
                    }
                }
                None => break,
            }
        }
    }

    if let Some(active_sink) = sink.take() {
        let _ = active_sink.stop().await;
    }
}

async fn run_input_worker(
    mut input_rx: mpsc::Receiver<Frame>,
    compositor: Arc<dyn CompositorBackend>,
    hid_rx: watch::Receiver<bool>,
    mut shutdown: watch::Receiver<bool>,
) {
    loop {
        tokio::select! {
            biased;

            changed = shutdown.changed() => {
                if changed.is_err() || *shutdown.borrow() {
                    break;
                }
            }

            maybe_frame = input_rx.recv() => match maybe_frame {
                Some(frame) => {
                    if *hid_rx.borrow() {
                        continue;
                    }
                    match InputFallbackEvent::decode(&frame.payload) {
                        Ok(ev) => {
                            if let Err(err) = compositor.inject_fallback_input(ev) {
                                debug!(error = %err, "uinput fallback injection failed");
                            }
                        }
                        Err(err) => {
                            debug!(error = %err, "invalid InputFallbackEvent payload");
                        }
                    }
                }
                None => break,
            }
        }
    }
}

pub fn compute_backoff_delay(retry_attempt: u32) -> Duration {
    let shift = retry_attempt.clamp(1, 6);
    let base_secs = 1u64 << shift; // 2s, 4s, 8s, 16s for attempts 1..=4
    let base_ms = base_secs * 1000;
    let jitter_max = (base_ms / 5).max(1);
    let jitter_ms = rand::rng().random_range(0..=jitter_max);
    Duration::from_millis(base_ms + jitter_ms)
}

pub fn codec_from_mime(mime: &str) -> u8 {
    let lower = mime.to_ascii_lowercase();
    if lower.contains("hevc") || lower.contains("h265") || lower.contains("265") {
        CODEC_H265
    } else {
        CODEC_H264
    }
}

fn extract_tethering_error_code(err: &anyhow::Error) -> Option<String> {
    let msg = err.to_string();
    for code in ["0x80", "0x81", "0x82", "0x83", "0x84"] {
        if msg.contains(code) {
            return Some(code.to_string());
        }
    }
    None
}

fn current_timestamp_ms() -> u32 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u32
}

#[tokio::main]
async fn main() -> Result<()> {
    let env_filter = tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"));
    let _ = tracing_subscriber::fmt().with_env_filter(env_filter).try_init();

    match purge_stale_spool_files().await {
        Ok(n) if n > 0 => info!(removed = n, "purged stale clipboard spool files on startup"),
        Ok(_) => {}
        Err(err) => warn!(error = %err, "failed to purge stale spool files on startup"),
    }

    let config = AppConfig::load_or_default().context("failed to load AppConfig")?;
    let identity_key = Arc::new(
        AppConfig::load_or_generate_identity_key().context("failed to load identity keypair")?,
    );

    let system_dbus = zbus::Connection::system()
        .await
        .context("failed to connect to system D-Bus")?;
    let session_dbus = match zbus::Connection::session().await {
        Ok(conn) => Some(conn),
        Err(err) => {
            warn!(error = %err, "session D-Bus unavailable; desktop notifications disabled");
            None
        }
    };

    let initial_mac = (!config.device.mac_address.trim().is_empty())
        .then(|| config.device.mac_address.clone());
    let mut hid_rx = HidWatchdog::spawn(initial_mac);

    let initial_telemetry = DaemonTelemetry {
        hid_connected: *hid_rx.borrow_and_update(),
        ..DaemonTelemetry::default()
    };
    let (telemetry_tx, telemetry_rx) = watch::channel(initial_telemetry.clone());
    let (events_tx, _) = broadcast::channel(128);
    let (cmd_tx, mut cmd_rx) = mpsc::channel(64);
    let (shutdown_tx, shutdown_rx) = watch::channel(false);

    let ipc_handle = start_ipc_server(
        default_socket_path(),
        cmd_tx,
        telemetry_rx,
        events_tx.clone(),
        shutdown_rx.clone(),
    )
    .await
    .context("failed to start local Unix domain socket IPC server")?;

    let (power_tx, power_rx) = mpsc::channel(16);
    let power_handle = power::spawn_sleep_monitor(system_dbus.clone(), power_tx);
    let mut power_rx_opt = Some(power_rx);

    let (bt_resume_tx, bt_resume_rx) = mpsc::channel(8);
    let bt_monitor_handle = ble::spawn_bt_reconnect_monitor(
        system_dbus.clone(),
        config.device.mac_address.clone(),
        config.network.reconnect_cooldown_secs,
        bt_resume_tx.clone(),
    );
    let mut bt_resume_rx_opt = Some(bt_resume_rx);

    let compositor: Arc<dyn CompositorBackend> = Arc::new(LinuxDesktopBackend::new());
    info!(backend = compositor.name(), "initialized desktop backend");
    let mut local_clipboard_rx_opt = Some(compositor.watch_clipboard());
    let mut clipboard_retry_sleep: Option<Pin<Box<Sleep>>> = None;
    let mut clipboard_retry_attempts: u32 = 0;

    let (files, files_task) = files::spawn_files_manager(
        default_files_socket_path(),
        &config.files,
        events_tx.clone(),
        shutdown_rx.clone(),
    )
    .await
    .context("failed to start FUSE files manager")?;

    let mut runtime = DaemonRuntime {
        config,
        identity_key,
        system_dbus,
        session_dbus,
        compositor,
        files,
        telemetry: initial_telemetry,
        telemetry_tx,
        events_tx,
        bt_resume_tx,
        bt_monitor_handle,
        hid_rx,
        hid_watch_active: true,
        connect_task: None,
        connect_rx: None,
        active_session: None,
        retry_count: 0,
        retry_sleep: None,
        was_active_before_suspend: false,
        last_error: None,
    };

    let mut heartbeat_ticker = tokio::time::interval(HEARTBEAT_INTERVAL);
    heartbeat_ticker.set_missed_tick_behavior(MissedTickBehavior::Delay);
    heartbeat_ticker.tick().await;

    let mut sigterm =
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;

    info!("controidd orchestrator started");

    loop {
        let retry_fut = async {
            match runtime.retry_sleep.as_mut() {
                Some(sleep) => sleep.as_mut().await,
                None => std::future::pending::<()>().await,
            }
        };

        let connect_progress_fut = async {
            match runtime.connect_rx.as_mut() {
                Some(rx) => rx.recv().await,
                None => std::future::pending::<Option<ConnectOutcome>>().await,
            }
        };

        let (ctrl_rx_opt, session_end_rx_opt) = match runtime.active_session.as_mut() {
            Some(session) => (session.ctrl_rx.as_mut(), Some(&mut session.session_end_rx)),
            None => (None, None),
        };

        let ctrl_frame_fut = async {
            match ctrl_rx_opt {
                Some(rx) => rx.recv().await,
                None => std::future::pending::<Option<Frame>>().await,
            }
        };

        let session_end_fut = async {
            match session_end_rx_opt {
                Some(rx) => rx.recv().await,
                None => std::future::pending::<Option<anyhow::Result<()>>>().await,
            }
        };

        let power_fut = async {
            match power_rx_opt.as_mut() {
                Some(rx) => rx.recv().await,
                None => std::future::pending::<Option<PowerEvent>>().await,
            }
        };

        let bt_resume_fut = async {
            match bt_resume_rx_opt.as_mut() {
                Some(rx) => rx.recv().await,
                None => std::future::pending::<Option<()>>().await,
            }
        };

        let local_clip_fut = async {
            match local_clipboard_rx_opt.as_mut() {
                Some(rx) => rx.recv().await,
                None => std::future::pending::<Option<ClipboardEvent>>().await,
            }
        };

        let clipboard_retry_fut = async {
            match clipboard_retry_sleep.as_mut() {
                Some(sleep) => sleep.as_mut().await,
                None => std::future::pending::<()>().await,
            }
        };

        let hid_rx_opt = runtime.hid_watch_active.then_some(&mut runtime.hid_rx);
        let hid_changed_fut = async {
            match hid_rx_opt {
                Some(rx) => rx.changed().await,
                None => std::future::pending::<Result<(), watch::error::RecvError>>().await,
            }
        };

        tokio::select! {
            biased;

            _ = tokio::signal::ctrl_c() => {
                info!("SIGINT received; shutting down");
                break;
            }
            _ = sigterm.recv() => {
                info!("SIGTERM received; shutting down");
                break;
            }

            maybe_power = power_fut => {
                match maybe_power {
                    Some(PowerEvent::Suspending) => {
                        info!("system suspending; tearing down active connection");
                        runtime.enter_suspended().await;
                    }
                    Some(PowerEvent::Resumed) => {
                        info!("system resumed; re-evaluating link state");
                        runtime.handle_resumed().await;
                    }
                    None => {
                        warn!("power sleep monitor channel closed");
                        power_rx_opt = None;
                    }
                }
            }

            maybe_cmd = cmd_rx.recv() => {
                if let Some(cmd) = maybe_cmd {
                    runtime.handle_command(cmd).await;
                } else {
                    break;
                }
            }

            maybe_outcome = connect_progress_fut => {
                match maybe_outcome {
                    Some(ConnectOutcome::Progress(st)) => {
                        runtime.set_state(st);
                    }
                    Some(ConnectOutcome::MacDiscovered { mac, paired }) => {
                        runtime.update_discovered_mac(mac, paired);
                    }
                    Some(ConnectOutcome::Connected { framed, verified_peer_pubkey }) => {
                        runtime.connect_task = None;
                        runtime.connect_rx = None;
                        runtime.install_active_session(framed, verified_peer_pubkey).await;
                    }
                    Some(ConnectOutcome::Failed { err, tethering_code }) => {
                        runtime.connect_task = None;
                        runtime.connect_rx = None;
                        let err_msg = err.to_string();
                        if let Some(ref code) = tethering_code {
                            let _ = runtime.events_tx.send(IpcEvent {
                                event: "error.tethering".to_string(),
                                data: json!({
                                    "code": code,
                                    "message": err_msg,
                                }),
                            });
                        }
                        let _ = runtime.events_tx.send(IpcEvent {
                            event: "link.error".to_string(),
                            data: json!({
                                "code": tethering_code,
                                "message": err_msg,
                            }),
                        });
                        runtime.schedule_retry_or_exhaust(&err);
                    }
                    None => {
                        runtime.connect_task = None;
                        runtime.connect_rx = None;
                    }
                }
            }

            _ = retry_fut => {
                runtime.retry_sleep = None;
                if runtime.telemetry.state == DaemonLinkState::Idle {
                    runtime.start_connect_pipeline(false);
                }
            }

            maybe_bt = bt_resume_fut => {
                if maybe_bt.is_none() {
                    bt_resume_rx_opt = None;
                } else if runtime.telemetry.state == DaemonLinkState::Idle
                    && runtime.connect_task.is_none()
                    && runtime.active_session.is_none()
                {
                    info!("BT reconnect monitor triggered resume");
                    runtime.retry_count = 0;
                    runtime.start_connect_pipeline(false);
                }
            }

            maybe_end = session_end_fut => {
                let err_opt = match maybe_end {
                    Some(Err(e)) => Some(e),
                    _ => None,
                };
                if let Some(session) = runtime.active_session.take() {
                    session.teardown(false).await;
                }
                runtime.files.set_connected(None).await;
                let reason = err_opt.unwrap_or_else(|| anyhow!("peer disconnected"));
                runtime.schedule_retry_or_exhaust(&reason);
            }

            maybe_ctrl = ctrl_frame_fut => {
                match maybe_ctrl {
                    Some(frame) => {
                        runtime.handle_ctrl_frame(frame).await;
                    }
                    None => {
                        if let Some(session) = runtime.active_session.as_mut() {
                            session.ctrl_rx = None;
                        }
                    }
                }
            }

            maybe_local_clip = local_clip_fut => {
                match maybe_local_clip {
                    Some(ev) => {
                        if clipboard_retry_attempts > 0 {
                            info!(backend = runtime.compositor.name(), "desktop clipboard watcher reconnected");
                            clipboard_retry_attempts = 0;
                        }
                        if let Some(session) = runtime.active_session.as_ref() {
                            let _ = session.local_clip_tx.try_send(ev);
                        }
                    }
                    None => {
                        local_clipboard_rx_opt = None;
                        clipboard_retry_attempts = clipboard_retry_attempts.saturating_add(1);
                        let delay_secs = match clipboard_retry_attempts {
                            1 => 3,
                            2 => 5,
                            3 => 10,
                            4 => 15,
                            _ => 30,
                        };
                        if clipboard_retry_attempts == 1 {
                            warn!(
                                backend = runtime.compositor.name(),
                                delay_secs,
                                "desktop clipboard watcher exited; scheduling reconnect"
                            );
                        } else {
                            debug!(
                                backend = runtime.compositor.name(),
                                attempt = clipboard_retry_attempts,
                                delay_secs,
                                "desktop clipboard watcher unavailable; retrying"
                            );
                        }
                        clipboard_retry_sleep =
                            Some(Box::pin(tokio::time::sleep(Duration::from_secs(delay_secs))));
                    }
                }
            }

            _ = clipboard_retry_fut => {
                clipboard_retry_sleep = None;
                local_clipboard_rx_opt = Some(runtime.compositor.watch_clipboard());
            }

            res = hid_changed_fut => {
                match res {
                    Ok(()) => {
                        runtime.telemetry.hid_connected = *runtime.hid_rx.borrow_and_update();
                        runtime.publish_telemetry();
                    }
                    Err(_) => {
                        runtime.hid_watch_active = false;
                    }
                }
            }

            _ = heartbeat_ticker.tick() => {
                let now = Instant::now();
                let mut dead_reason: Option<anyhow::Error> = None;

                if let Some(session) = runtime.active_session.as_mut() {
                    if let Some(pending) = session.pending_stream.as_ref()
                        && now >= pending.deadline
                        && let Some(expired) = session.pending_stream.take()
                    {
                        let _ = expired.reply.send(Err(CommandError::new(
                            5004,
                            "timed out waiting for STREAM_ACK",
                        )));
                    }

                    if now.duration_since(session.last_ctrl_rx) >= HEARTBEAT_DEAD_TIMEOUT {
                        dead_reason = Some(anyhow!("heartbeat watchdog timeout"));
                    } else {
                        let ping = ControlMessage::Heartbeat {
                            is_pong: false,
                            timestamp_ms: current_timestamp_ms(),
                        };
                        let _ = session
                            .sender
                            .try_send_high(Frame::ctrl_urgent(ping.encode()));
                    }
                }

                if let Some(reason) = dead_reason {
                    warn!(error = %reason, "active session stalled; reconnecting");
                    if let Some(session) = runtime.active_session.take() {
                        session.teardown(true).await;
                    }
                    runtime.files.set_connected(None).await;
                    runtime.schedule_retry_or_exhaust(&reason);
                }
            }
        }
    }

    runtime.cancel_connect_and_backoff();
    if let Some(session) = runtime.active_session.take() {
        session.teardown(true).await;
    }
    runtime.files.set_connected(None).await;
    let _ = shutdown_tx.send(true);
    power_handle.abort();
    runtime.bt_monitor_handle.abort();
    let _ = tokio::time::timeout(Duration::from_secs(2), ipc_handle).await;
    let _ = tokio::time::timeout(Duration::from_secs(3), files_task).await;
    let _ = purge_stale_spool_files().await;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_follows_exponential_schedule_with_bounded_jitter() {
        for (attempt, base_ms) in [(1u32, 2000u64), (2, 4000), (3, 8000), (4, 16000)] {
            let d = compute_backoff_delay(attempt);
            let ms = d.as_millis() as u64;
            assert!(ms >= base_ms, "attempt {attempt}: {ms} < {base_ms}");
            assert!(
                ms <= base_ms + base_ms / 5,
                "attempt {attempt}: {ms} exceeded jitter bound"
            );
        }
    }

    #[test]
    fn codec_mime_selection() {
        assert_eq!(codec_from_mime("video/avc"), CODEC_H264);
        assert_eq!(codec_from_mime("video/hevc"), CODEC_H265);
        assert_eq!(codec_from_mime("video/h265"), CODEC_H265);
    }
}
