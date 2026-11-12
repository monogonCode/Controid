use crate::config::{FilesConfig, expand_tilde};
use crate::ipc::{IpcEvent, bind_ipc_listener};
use crate::sender::PrioritySender;
use bytes::BytesMut;
use core_protocol::{
    CHANNEL_FILES, FLAG_ABORT, FLAG_END, FLAG_START, Frame, MAX_PAYLOAD_SIZE,
};
use nix::errno::Errno;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{UnixListener, UnixStream};
use tokio::process::{Child, Command};
use tokio::sync::{broadcast, mpsc, oneshot, watch};
use tokio::task::JoinHandle;
use tracing::{debug, info, warn};

use crate::state::CommandError;

pub const MAX_FILES_RPC_BYTES: usize = 1024 * 1024;
const FILES_RPC_TIMEOUT: Duration = Duration::from_secs(20);
const FILES_TX_CHANNEL_CAP: usize = 128;
const FILES_CMD_CHANNEL_CAP: usize = 64;
const FILES_RPC_CHANNEL_CAP: usize = 128;
const MAX_STREAM_ID: u16 = 0x7FFF;

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct FilesStatus {
    pub mounted: bool,
    pub mount_point: String,
    pub connected: bool,
    pub error: Option<String>,
}

#[derive(Debug)]
pub enum FilesCmd {
    SetConnected {
        sender: Option<PrioritySender>,
    },
    UpdateConfig {
        mount_point: String,
    },
    Mount {
        mount_point: Option<String>,
        reply: oneshot::Sender<Result<FilesStatus, CommandError>>,
    },
    Unmount {
        reply: oneshot::Sender<Result<FilesStatus, CommandError>>,
    },
    GetStatus {
        reply: oneshot::Sender<FilesStatus>,
    },
}

#[derive(Clone)]
pub struct FilesHandle {
    pub cmd_tx: mpsc::Sender<FilesCmd>,
    pub files_tx: mpsc::Sender<Frame>,
}

impl FilesHandle {
    pub async fn set_connected(&self, sender: Option<PrioritySender>) {
        let _ = self.cmd_tx.send(FilesCmd::SetConnected { sender }).await;
    }

    pub async fn update_config(&self, mount_point: String) {
        let _ = self
            .cmd_tx
            .send(FilesCmd::UpdateConfig { mount_point })
            .await;
    }

    pub async fn mount(&self, mount_point: Option<String>) -> Result<FilesStatus, CommandError> {
        let (reply_tx, reply_rx) = oneshot::channel();
        if self
            .cmd_tx
            .send(FilesCmd::Mount {
                mount_point,
                reply: reply_tx,
            })
            .await
            .is_err()
        {
            return Err(CommandError::internal("files manager unavailable"));
        }
        reply_rx
            .await
            .unwrap_or_else(|_| Err(CommandError::internal("files manager dropped reply")))
    }

    pub async fn unmount(&self) -> Result<FilesStatus, CommandError> {
        let (reply_tx, reply_rx) = oneshot::channel();
        if self
            .cmd_tx
            .send(FilesCmd::Unmount { reply: reply_tx })
            .await
            .is_err()
        {
            return Err(CommandError::internal("files manager unavailable"));
        }
        reply_rx
            .await
            .unwrap_or_else(|_| Err(CommandError::internal("files manager dropped reply")))
    }

    pub async fn status(&self) -> Result<FilesStatus, CommandError> {
        let (reply_tx, reply_rx) = oneshot::channel();
        if self
            .cmd_tx
            .send(FilesCmd::GetStatus { reply: reply_tx })
            .await
            .is_err()
        {
            return Err(CommandError::internal("files manager unavailable"));
        }
        reply_rx
            .await
            .map_err(|_| CommandError::internal("files manager dropped reply"))
    }
}

pub fn default_files_socket_path() -> PathBuf {
    dirs::runtime_dir()
        .unwrap_or_else(|| PathBuf::from("/tmp"))
        .join("controid")
        .join("files.sock")
}

struct InboundFileStream {
    expected_seq: u32,
    buf: Vec<u8>,
}

enum InternalRpcMsg {
    Alloc {
        rpc_reply: oneshot::Sender<Result<Vec<u8>, i32>>,
        alloc_reply: oneshot::Sender<Result<(PrioritySender, u16), i32>>,
    },
    Cancel {
        stream_id: u16,
    },
}

pub struct FilesManager {
    socket_path: PathBuf,
    listener: Option<UnixListener>,
    events_tx: broadcast::Sender<IpcEvent>,
    sender: Option<PrioritySender>,
    configured_mount_point: String,
    active_mount_path: Option<PathBuf>,
    mounted: bool,
    last_error: Option<String>,
    child: Option<Child>,
    mount_binary_override: Option<PathBuf>,
    next_stream_id: u16,
    pending: HashMap<u16, oneshot::Sender<Result<Vec<u8>, i32>>>,
    inbound: HashMap<u16, InboundFileStream>,
}

impl FilesManager {
    pub fn new(
        socket_path: PathBuf,
        listener: UnixListener,
        initial_config: &FilesConfig,
        events_tx: broadcast::Sender<IpcEvent>,
    ) -> Self {
        Self {
            socket_path,
            listener: Some(listener),
            events_tx,
            sender: None,
            configured_mount_point: initial_config.mount_point.clone(),
            active_mount_path: None,
            mounted: false,
            last_error: None,
            child: None,
            mount_binary_override: None,
            next_stream_id: 1,
            pending: HashMap::new(),
            inbound: HashMap::new(),
        }
    }

    pub fn status(&self) -> FilesStatus {
        let display_mount = self
            .active_mount_path
            .as_ref()
            .map(|p| p.display().to_string())
            .unwrap_or_else(|| expand_tilde(&self.configured_mount_point).display().to_string());
        FilesStatus {
            mounted: self.mounted,
            mount_point: display_mount,
            connected: self.sender.is_some(),
            error: self.last_error.clone(),
        }
    }

    fn publish_state(&self) {
        if let Ok(val) = serde_json::to_value(self.status()) {
            let _ = self.events_tx.send(IpcEvent {
                event: "files.state".to_string(),
                data: val,
            });
        }
    }

    fn allocate_stream_id(&mut self) -> Option<u16> {
        for _ in 0..MAX_STREAM_ID {
            let candidate = self.next_stream_id;
            self.next_stream_id = if self.next_stream_id >= MAX_STREAM_ID {
                1
            } else {
                self.next_stream_id + 1
            };
            if !self.pending.contains_key(&candidate) && !self.inbound.contains_key(&candidate) {
                return Some(candidate);
            }
        }
        None
    }

    fn fail_all_pending(&mut self, errno: i32) {
        self.inbound.clear();
        for (_, tx) in self.pending.drain() {
            let _ = tx.send(Err(errno));
        }
    }

    fn fail_inbound_stream(&mut self, stream_id: u16, errno: i32) {
        self.inbound.remove(&stream_id);
        if let Some(tx) = self.pending.remove(&stream_id) {
            let _ = tx.send(Err(errno));
        }
    }

    fn handle_inbound_frame(&mut self, frame: Frame) {
        if frame.channel_id != CHANNEL_FILES || frame.stream_id == 0 {
            return;
        }
        let sid = frame.stream_id;

        if (frame.flags & FLAG_ABORT) != 0 {
            self.fail_inbound_stream(sid, -(Errno::EIO as i32));
            return;
        }

        if (frame.flags & FLAG_START) != 0 {
            if frame.sequence_number != 0 || self.inbound.contains_key(&sid) {
                self.fail_inbound_stream(sid, -(Errno::EIO as i32));
                return;
            }
            if frame.payload.len() > MAX_FILES_RPC_BYTES {
                self.send_abort(sid);
                self.fail_inbound_stream(sid, -(Errno::EMSGSIZE as i32));
                return;
            }
            if (frame.flags & FLAG_END) != 0 {
                if let Some(tx) = self.pending.remove(&sid) {
                    let _ = tx.send(Ok(frame.payload.to_vec()));
                }
            } else {
                self.inbound.insert(
                    sid,
                    InboundFileStream {
                        expected_seq: 1,
                        buf: frame.payload.to_vec(),
                    },
                );
            }
            return;
        }

        let Some(st) = self.inbound.get_mut(&sid) else {
            return;
        };
        if frame.sequence_number != st.expected_seq {
            self.fail_inbound_stream(sid, -(Errno::EIO as i32));
            return;
        }
        if st.buf.len().saturating_add(frame.payload.len()) > MAX_FILES_RPC_BYTES {
            self.send_abort(sid);
            self.fail_inbound_stream(sid, -(Errno::EMSGSIZE as i32));
            return;
        }
        st.buf.extend_from_slice(&frame.payload);
        st.expected_seq = st.expected_seq.saturating_add(1);

        if (frame.flags & FLAG_END) != 0 {
            let completed = self.inbound.remove(&sid).map(|s| s.buf).unwrap_or_default();
            if let Some(tx) = self.pending.remove(&sid) {
                let _ = tx.send(Ok(completed));
            }
        }
    }

    fn send_abort(&self, stream_id: u16) {
        if let Some(sender) = &self.sender {
            let _ = sender.try_send_high(Frame::new(
                CHANNEL_FILES,
                stream_id,
                FLAG_ABORT,
                0,
                BytesMut::new(),
            ));
        }
    }

    async fn mount_inner(&mut self, mount_point_override: Option<String>) -> Result<FilesStatus, CommandError> {
        if self.sender.is_none() {
            return Err(CommandError::not_connected());
        }

        let raw_mp = mount_point_override
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .unwrap_or(self.configured_mount_point.as_str());
        let target_path = expand_tilde(raw_mp);

        if self.mounted && self.active_mount_path.as_ref() == Some(&target_path) {
            return Ok(self.status());
        }
        if self.mounted {
            let _ = self.unmount_inner().await;
        }

        // Recover if an earlier crash left a stale transport endpoint on target_path.
        if let Err(err) = std::fs::metadata(&target_path)
            && err.raw_os_error() == Some(Errno::ENOTCONN as i32)
        {
            let _ = run_fusermount(&target_path, true).await;
        }

        if let Err(err) = tokio::fs::create_dir_all(&target_path).await {
            let msg = format!("failed to create mount directory {}: {err}", target_path.display());
            self.last_error = Some(msg.clone());
            self.publish_state();
            return Err(CommandError::internal(msg));
        }

        let Some(mount_bin) = self
            .mount_binary_override
            .clone()
            .or_else(resolve_mount_binary)
        else {
            let msg = "controid-mount binary not found in PATH or ~/.local/bin".to_string();
            self.last_error = Some(msg.clone());
            self.publish_state();
            return Err(CommandError::internal(msg));
        };

        let mut cmd = Command::new(&mount_bin);
        cmd.arg("-f")
            .arg(&target_path)
            .env("CONTROID_FILES_SOCK", &self.socket_path)
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .kill_on_drop(true);

        let mut child = match cmd.spawn() {
            Ok(c) => c,
            Err(err) => {
                let msg = format!("failed to spawn {}: {err}", mount_bin.display());
                self.last_error = Some(msg.clone());
                self.publish_state();
                return Err(CommandError::internal(msg));
            }
        };

        // Brief check to catch immediate FUSE mount failures cleanly.
        tokio::time::sleep(Duration::from_millis(120)).await;
        match child.try_wait() {
            Ok(Some(status)) => {
                let mut err_detail = String::new();
                if let Some(mut stderr) = child.stderr.take() {
                    let mut buf = vec![0u8; 512];
                    if let Ok(Ok(n)) = tokio::time::timeout(
                        Duration::from_millis(200),
                        tokio::io::AsyncReadExt::read(&mut stderr, &mut buf),
                    )
                    .await
                    {
                        err_detail = String::from_utf8_lossy(&buf[..n]).trim().to_string();
                    }
                }
                let msg = if err_detail.is_empty() {
                    format!("controid-mount exited immediately ({status})")
                } else {
                    format!("controid-mount exited ({status}): {err_detail}")
                };
                self.last_error = Some(msg.clone());
                self.mounted = false;
                self.active_mount_path = None;
                self.publish_state();
                return Err(CommandError::internal(msg));
            }
            Ok(None) => {}
            Err(err) => {
                warn!(error = %err, "failed checking controid-mount child status");
            }
        }

        // Drop stderr pipe so child never blocks writing diagnostics.
        drop(child.stderr.take());
        info!(mount_point = %target_path.display(), pid = child.id(), "mounted Android storage via FUSE");
        self.child = Some(child);
        self.active_mount_path = Some(target_path);
        self.mounted = true;
        self.last_error = None;
        self.publish_state();
        Ok(self.status())
    }

    async fn unmount_inner(&mut self) -> Result<FilesStatus, CommandError> {
        let had_mount = self.mounted || self.child.is_some() || self.active_mount_path.is_some();
        let mount_path = self.active_mount_path.take();

        if let Some(mut child) = self.child.take() {
            let _ = child.start_kill();
            let _ = tokio::time::timeout(Duration::from_secs(2), child.wait()).await;
        }

        if let Some(path) = mount_path.as_ref() {
            let ok = run_fusermount(path, false).await;
            if !ok {
                let _ = run_fusermount(path, true).await;
            }
            info!(mount_point = %path.display(), "unmounted Android FUSE storage");
        }

        self.mounted = false;
        if had_mount {
            self.last_error = None;
            self.publish_state();
        }
        Ok(self.status())
    }

    pub async fn run(
        mut self,
        mut cmd_rx: mpsc::Receiver<FilesCmd>,
        mut files_rx: mpsc::Receiver<Frame>,
        mut shutdown: watch::Receiver<bool>,
    ) {
        let listener = self.listener.take().expect("FilesManager listener present");
        let (rpc_tx, mut rpc_rx) = mpsc::channel::<InternalRpcMsg>(FILES_RPC_CHANNEL_CAP);

        loop {
            let child_exit_fut = async {
                match self.child.as_mut() {
                    Some(child) => child.wait().await,
                    None => std::future::pending::<io::Result<std::process::ExitStatus>>().await,
                }
            };

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
                            let rpc_tx = rpc_tx.clone();
                            let shutdown = shutdown.clone();
                            tokio::spawn(async move {
                                if let Err(err) = handle_files_client(stream, rpc_tx, shutdown).await {
                                    debug!(error = %err, "files.sock client ended");
                                }
                            });
                        }
                        Err(err) => {
                            warn!(error = %err, "files.sock accept error");
                        }
                    }
                }

                maybe_cmd = cmd_rx.recv() => match maybe_cmd {
                    Some(FilesCmd::SetConnected { sender }) => {
                        let was_connected = self.sender.is_some();
                        let is_connected = sender.is_some();
                        self.sender = sender;
                        if !is_connected {
                            self.fail_all_pending(-(Errno::ENOTCONN as i32));
                            while files_rx.try_recv().is_ok() {}
                            let _ = self.unmount_inner().await;
                        }
                        if was_connected != is_connected {
                            self.publish_state();
                        }
                    }
                    Some(FilesCmd::UpdateConfig { mount_point }) => {
                        let trimmed = mount_point.trim();
                        if !trimmed.is_empty() && self.configured_mount_point != trimmed {
                            self.configured_mount_point = trimmed.to_string();
                            self.publish_state();
                        }
                    }
                    Some(FilesCmd::Mount { mount_point, reply }) => {
                        let res = self.mount_inner(mount_point).await;
                        let _ = reply.send(res);
                    }
                    Some(FilesCmd::Unmount { reply }) => {
                        let res = self.unmount_inner().await;
                        let _ = reply.send(res);
                    }
                    Some(FilesCmd::GetStatus { reply }) => {
                        let _ = reply.send(self.status());
                    }
                    None => break,
                },

                maybe_rpc = rpc_rx.recv() => match maybe_rpc {
                    Some(InternalRpcMsg::Alloc { rpc_reply, alloc_reply }) => {
                        let Some(sender) = self.sender.clone() else {
                            let _ = alloc_reply.send(Err(-(Errno::ENOTCONN as i32)));
                            continue;
                        };
                        let Some(sid) = self.allocate_stream_id() else {
                            let _ = alloc_reply.send(Err(-(Errno::EAGAIN as i32)));
                            continue;
                        };
                        self.pending.insert(sid, rpc_reply);
                        if alloc_reply.send(Ok((sender, sid))).is_err() {
                            self.pending.remove(&sid);
                        }
                    }
                    Some(InternalRpcMsg::Cancel { stream_id }) => {
                        self.pending.remove(&stream_id);
                        self.inbound.remove(&stream_id);
                    }
                    None => {}
                },

                maybe_frame = files_rx.recv() => match maybe_frame {
                    Some(frame) => self.handle_inbound_frame(frame),
                    None => break,
                },

                res = child_exit_fut => {
                    self.child = None;
                    let mount_path = self.active_mount_path.take();
                    self.mounted = false;
                    if let Some(path) = mount_path {
                        let ok = run_fusermount(&path, false).await;
                        if !ok {
                            let _ = run_fusermount(&path, true).await;
                        }
                    }
                    self.last_error = Some(match res {
                        Ok(st) => format!("controid-mount exited ({st})"),
                        Err(e) => format!("controid-mount error: {e}"),
                    });
                    warn!(error = ?self.last_error, "FUSE mount process exited");
                    self.publish_state();
                }
            }
        }

        self.fail_all_pending(-(Errno::ENOTCONN as i32));
        let _ = self.unmount_inner().await;
        let _ = std::fs::remove_file(&self.socket_path);
    }
}

pub async fn spawn_files_manager(
    socket_path: PathBuf,
    initial_config: &FilesConfig,
    events_tx: broadcast::Sender<IpcEvent>,
    shutdown: watch::Receiver<bool>,
) -> io::Result<(FilesHandle, JoinHandle<()>)> {
    let listener = bind_ipc_listener(&socket_path)?;
    let (cmd_tx, cmd_rx) = mpsc::channel(FILES_CMD_CHANNEL_CAP);
    let (files_tx, files_rx) = mpsc::channel(FILES_TX_CHANNEL_CAP);
    let manager = FilesManager::new(socket_path, listener, initial_config, events_tx);
    let task = tokio::spawn(manager.run(cmd_rx, files_rx, shutdown));
    Ok((FilesHandle { cmd_tx, files_tx }, task))
}

pub async fn send_files_request_frames(
    sender: &PrioritySender,
    stream_id: u16,
    payload: &[u8],
) -> Result<(), i32> {
    if payload.is_empty() || payload.len() > MAX_FILES_RPC_BYTES {
        return Err(-(Errno::EINVAL as i32));
    }

    let total_chunks = payload.len().div_ceil(MAX_PAYLOAD_SIZE);
    for (seq, chunk) in payload.chunks(MAX_PAYLOAD_SIZE).enumerate() {
        let mut flags = 0u16;
        if seq == 0 {
            flags |= FLAG_START;
        }
        if seq + 1 == total_chunks {
            flags |= FLAG_END;
        }
        let frame = Frame::new(
            CHANNEL_FILES,
            stream_id,
            flags,
            seq as u32,
            BytesMut::from(chunk),
        );
        if sender.send_low(frame).await.is_err() {
            if seq > 0 {
                let _ = sender.try_send_high(Frame::new(
                    CHANNEL_FILES,
                    stream_id,
                    FLAG_ABORT,
                    0,
                    BytesMut::new(),
                ));
            }
            return Err(-(Errno::ENOTCONN as i32));
        }
    }

    Ok(())
}

async fn handle_files_client(
    mut stream: UnixStream,
    rpc_tx: mpsc::Sender<InternalRpcMsg>,
    mut shutdown: watch::Receiver<bool>,
) -> io::Result<()> {
    loop {
        let mut len_buf = [0u8; 4];
        tokio::select! {
            biased;
            changed = shutdown.changed() => {
                if changed.is_err() || *shutdown.borrow() {
                    return Ok(());
                }
                continue;
            }
            res = stream.read_exact(&mut len_buf) => match res {
                Ok(_) => {}
                Err(err) if err.kind() == io::ErrorKind::UnexpectedEof => return Ok(()),
                Err(err) => return Err(err),
            }
        }

        let req_len = u32::from_be_bytes(len_buf) as usize;
        if req_len == 0 {
            write_errno_response(&mut stream, -(Errno::EINVAL as i32)).await?;
            continue;
        }
        if req_len > MAX_FILES_RPC_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("files RPC payload {req_len} exceeds {MAX_FILES_RPC_BYTES} bytes"),
            ));
        }

        let mut req_payload = vec![0u8; req_len];
        stream.read_exact(&mut req_payload).await?;

        let (resp_tx, resp_rx) = oneshot::channel();
        let (alloc_tx, alloc_rx) = oneshot::channel();
        if rpc_tx
            .send(InternalRpcMsg::Alloc {
                rpc_reply: resp_tx,
                alloc_reply: alloc_tx,
            })
            .await
            .is_err()
        {
            write_errno_response(&mut stream, -(Errno::ENOTCONN as i32)).await?;
            return Ok(());
        }

        let (sender, stream_id) = match alloc_rx.await {
            Ok(Ok(pair)) => pair,
            Ok(Err(errno)) => {
                write_errno_response(&mut stream, errno).await?;
                continue;
            }
            Err(_) => {
                write_errno_response(&mut stream, -(Errno::ENOTCONN as i32)).await?;
                return Ok(());
            }
        };

        if let Err(errno) = send_files_request_frames(&sender, stream_id, &req_payload).await {
            let _ = rpc_tx.try_send(InternalRpcMsg::Cancel { stream_id });
            write_errno_response(&mut stream, errno).await?;
            continue;
        }

        let rpc_res = tokio::select! {
            biased;
            changed = shutdown.changed() => {
                let _ = changed;
                let _ = rpc_tx.try_send(InternalRpcMsg::Cancel { stream_id });
                return Ok(());
            }
            timed = tokio::time::timeout(FILES_RPC_TIMEOUT, resp_rx) => timed,
        };

        match rpc_res {
            Ok(Ok(Ok(resp_bytes))) => {
                write_framed_response(&mut stream, &resp_bytes).await?;
            }
            Ok(Ok(Err(errno))) => {
                write_errno_response(&mut stream, errno).await?;
            }
            Ok(Err(_)) => {
                write_errno_response(&mut stream, -(Errno::ENOTCONN as i32)).await?;
            }
            Err(_) => {
                let _ = rpc_tx.try_send(InternalRpcMsg::Cancel { stream_id });
                let _ = sender.try_send_high(Frame::new(
                    CHANNEL_FILES,
                    stream_id,
                    FLAG_ABORT,
                    0,
                    BytesMut::new(),
                ));
                write_errno_response(&mut stream, -(Errno::ETIMEDOUT as i32)).await?;
            }
        }
    }
}

async fn write_errno_response(stream: &mut UnixStream, errno: i32) -> io::Result<()> {
    write_framed_response(stream, &errno.to_be_bytes()).await
}

async fn write_framed_response(stream: &mut UnixStream, body: &[u8]) -> io::Result<()> {
    let len = u32::try_from(body.len())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "response too large"))?;
    stream.write_all(&len.to_be_bytes()).await?;
    stream.write_all(body).await?;
    stream.flush().await
}

fn resolve_mount_binary() -> Option<PathBuf> {
    if let Ok(exe) = std::env::current_exe()
        && let Some(dir) = exe.parent()
    {
        let sibling = dir.join("controid-mount");
        if sibling.is_file() {
            return Some(sibling);
        }
    }
    if let Some(path_var) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&path_var) {
            let candidate = dir.join("controid-mount");
            if candidate.is_file() {
                return Some(candidate);
            }
        }
    }
    if let Some(home) = dirs::home_dir() {
        let local_bin = home.join(".local").join("bin").join("controid-mount");
        if local_bin.is_file() {
            return Some(local_bin);
        }
    }
    None
}

async fn run_fusermount(mount_point: &Path, lazy: bool) -> bool {
    let mut cmd = Command::new("fusermount3");
    cmd.arg("-u");
    if lazy {
        cmd.arg("-z");
    }
    cmd.arg(mount_point)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());

    match tokio::time::timeout(Duration::from_secs(3), cmd.status()).await {
        Ok(Ok(status)) => status.success(),
        _ => false,
    }
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
        std::env::temp_dir().join(format!("controid-files-{label}-{unique}"))
    }

    #[tokio::test]
    async fn files_socket_chunks_multiframe_request_and_reassembles_response() {
        let dir = temp_dir("rpc");
        let sock_path = dir.join("files.sock");
        let (events_tx, _) = broadcast::channel(16);
        let (shutdown_tx, shutdown_rx) = watch::channel(false);

        let (handle, task) = spawn_files_manager(
            sock_path.clone(),
            &FilesConfig::default(),
            events_tx,
            shutdown_rx,
        )
        .await
        .unwrap();

        // While disconnected, RPC fails fast with -ENOTCONN
        {
            let mut client = UnixStream::connect(&sock_path).await.unwrap();
            let req = vec![core_protocol::FS_OP_STATFS];
            client
                .write_all(&(req.len() as u32).to_be_bytes())
                .await
                .unwrap();
            client.write_all(&req).await.unwrap();
            let mut len_buf = [0u8; 4];
            client.read_exact(&mut len_buf).await.unwrap();
            let resp_len = u32::from_be_bytes(len_buf) as usize;
            assert_eq!(resp_len, 4);
            let mut errno_buf = [0u8; 4];
            client.read_exact(&mut errno_buf).await.unwrap();
            assert_eq!(i32::from_be_bytes(errno_buf), -(Errno::ENOTCONN as i32));
        }

        // Connect mock PrioritySender and test multi-chunk (>16 KiB) request + multi-chunk response
        let (sender, _high_rx, mut low_rx) = PrioritySender::channel(32, 32);
        handle.set_connected(Some(sender)).await;

        let big_body = vec![0x5Au8; MAX_PAYLOAD_SIZE + 500];
        let mut big_req = Vec::with_capacity(1 + big_body.len());
        big_req.push(core_protocol::FS_OP_WRITE);
        big_req.extend_from_slice(&big_body);

        let mut client = UnixStream::connect(&sock_path).await.unwrap();
        client
            .write_all(&(big_req.len() as u32).to_be_bytes())
            .await
            .unwrap();
        client.write_all(&big_req).await.unwrap();

        let f0 = low_rx.recv().await.unwrap();
        let f1 = low_rx.recv().await.unwrap();
        assert_eq!(f0.channel_id, CHANNEL_FILES);
        assert_eq!(f0.flags, FLAG_START);
        assert_eq!(f0.sequence_number, 0);
        assert_eq!(f0.payload.len(), MAX_PAYLOAD_SIZE);
        assert_eq!(f1.channel_id, CHANNEL_FILES);
        assert_eq!(f1.stream_id, f0.stream_id);
        assert_eq!(f1.flags, FLAG_END);
        assert_eq!(f1.sequence_number, 1);
        assert_eq!(f1.payload.len(), 501);

        let mut reassembled_req = f0.payload.to_vec();
        reassembled_req.extend_from_slice(&f1.payload);
        assert_eq!(reassembled_req, big_req);

        // Send multi-frame response back on the same stream_id
        let mut resp_payload = Vec::new();
        resp_payload.extend_from_slice(&0i32.to_be_bytes());
        resp_payload.extend_from_slice(&vec![0x77u8; MAX_PAYLOAD_SIZE + 123]);
        let half = MAX_PAYLOAD_SIZE;
        handle
            .files_tx
            .send(Frame::new(
                CHANNEL_FILES,
                f0.stream_id,
                FLAG_START,
                0,
                BytesMut::from(&resp_payload[..half]),
            ))
            .await
            .unwrap();
        handle
            .files_tx
            .send(Frame::new(
                CHANNEL_FILES,
                f0.stream_id,
                FLAG_END,
                1,
                BytesMut::from(&resp_payload[half..]),
            ))
            .await
            .unwrap();

        let mut resp_len_buf = [0u8; 4];
        client.read_exact(&mut resp_len_buf).await.unwrap();
        let resp_len = u32::from_be_bytes(resp_len_buf) as usize;
        assert_eq!(resp_len, resp_payload.len());
        let mut got_resp = vec![0u8; resp_len];
        client.read_exact(&mut got_resp).await.unwrap();
        assert_eq!(got_resp, resp_payload);

        let _ = shutdown_tx.send(true);
        task.await.unwrap();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn pending_rpc_fails_with_eio_on_flag_abort() {
        let dir = temp_dir("abort");
        let sock_path = dir.join("files.sock");
        let (events_tx, _) = broadcast::channel(16);
        let (shutdown_tx, shutdown_rx) = watch::channel(false);

        let (handle, task) = spawn_files_manager(
            sock_path.clone(),
            &FilesConfig::default(),
            events_tx,
            shutdown_rx,
        )
        .await
        .unwrap();

        let (sender, _high_rx, mut low_rx) = PrioritySender::channel(16, 16);
        handle.set_connected(Some(sender)).await;

        let mut client = UnixStream::connect(&sock_path).await.unwrap();
        let req = vec![core_protocol::FS_OP_GETATTR, b'/'];
        client
            .write_all(&(req.len() as u32).to_be_bytes())
            .await
            .unwrap();
        client.write_all(&req).await.unwrap();

        let f0 = low_rx.recv().await.unwrap();
        handle
            .files_tx
            .send(Frame::new(
                CHANNEL_FILES,
                f0.stream_id,
                FLAG_ABORT,
                0,
                BytesMut::new(),
            ))
            .await
            .unwrap();

        let mut resp_len_buf = [0u8; 4];
        client.read_exact(&mut resp_len_buf).await.unwrap();
        assert_eq!(u32::from_be_bytes(resp_len_buf), 4);
        let mut errno_buf = [0u8; 4];
        client.read_exact(&mut errno_buf).await.unwrap();
        assert_eq!(i32::from_be_bytes(errno_buf), -(Errno::EIO as i32));

        let _ = shutdown_tx.send(true);
        task.await.unwrap();
        let _ = std::fs::remove_dir_all(&dir);
    }
}
