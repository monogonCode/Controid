use crate::ipc::IpcEvent;
use crate::sender::{OutgoingPriority, PrioritySender};
use anyhow::{Context, Result, bail};
use bytes::BytesMut;
use core_protocol::{
    CHANNEL_CLIP_DATA, CHANNEL_CLIP_META, ECHO_SLICE_LEN, EchoSuppressor, FLAG_ABORT, FLAG_END,
    FLAG_START, FlowController, Frame, MAX_PAYLOAD_SIZE, StreamStartMeta, compute_binary_echo_hash,
    compute_clipboard_echo_hash,
};
use hyprland_client::{ClipboardEvent, ClipboardPayload, CompositorBackend, HyprlandBackend};
use serde_json::json;
use std::collections::HashMap;
use std::io::{self, ErrorKind};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU16, AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::fs::{self, File, OpenOptions};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::{broadcast, mpsc, watch};
use tokio::time::{Instant, MissedTickBehavior};
use tracing::{debug, warn};

pub const RAM_THRESHOLD_BYTES: usize = 32 * 1024 * 1024;
pub const MAX_SPOOL_BYTES: u64 = 512 * 1024 * 1024;
pub const CLIP_META_MAX_BYTES: usize = 32 * 1024;
pub const CLIPBOARD_STREAM_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_CLIP_HISTORY_FILES: usize = 10;
static CLIP_HISTORY_SEQ: AtomicU64 = AtomicU64::new(1);

pub fn default_spool_dir() -> PathBuf {
    dirs::runtime_dir()
        .unwrap_or_else(|| PathBuf::from("/tmp"))
        .join("controid")
        .join("spool")
}

pub async fn purge_stale_spool_files() -> io::Result<usize> {
    purge_spool_dir(&default_spool_dir()).await
}

pub async fn purge_spool_dir(dir: &Path) -> io::Result<usize> {
    let mut entries = match fs::read_dir(dir).await {
        Ok(rd) => rd,
        Err(err) if err.kind() == ErrorKind::NotFound => return Ok(0),
        Err(err) => return Err(err),
    };

    let mut removed = 0usize;
    while let Some(entry) = entries.next_entry().await? {
        let path = entry.path();
        let file_type = entry.file_type().await?;
        if file_type.is_file() {
            let is_tmp = path
                .extension()
                .and_then(|ext| ext.to_str())
                .is_some_and(|ext| ext == "tmp");
            if is_tmp && fs::remove_file(&path).await.is_ok() {
                removed += 1;
            }
        }
    }
    Ok(removed)
}

#[derive(Debug)]
enum InboundStorage {
    Memory(Vec<u8>),
    Spool {
        file: File,
        path: PathBuf,
        head: Vec<u8>,
        tail: Vec<u8>,
    },
}

struct InboundClipboardStream {
    channel_id: u8,
    mime: String,
    total_len: u64,
    received_len: u64,
    storage: InboundStorage,
    last_activity: Instant,
}

#[derive(Debug)]
pub struct OutboundClipboardRequest {
    pub payload: ClipboardPayload,
    pub echo_hash: [u8; 32],
}

pub struct ClipboardWorker {
    session_id: String,
    spool_dir: PathBuf,
    ram_threshold: usize,
    max_spool_bytes: u64,
    stream_timeout: Duration,
    sender: PrioritySender,
    outbound_clip_flow: FlowController,
    echo_suppressor: Arc<EchoSuppressor>,
    compositor: Arc<dyn CompositorBackend>,
    events_tx: broadcast::Sender<IpcEvent>,
    inbound_streams: HashMap<u16, InboundClipboardStream>,
    next_stream_id: Arc<AtomicU16>,
}

impl Drop for ClipboardWorker {
    fn drop(&mut self) {
        for (_, stream) in self.inbound_streams.drain() {
            if let InboundStorage::Spool { file, path, .. } = stream.storage {
                drop(file);
                let _ = std::fs::remove_file(path);
            }
        }
    }
}

impl ClipboardWorker {
    pub fn new(
        session_id: String,
        sender: PrioritySender,
        outbound_clip_flow: FlowController,
        echo_suppressor: Arc<EchoSuppressor>,
        events_tx: broadcast::Sender<IpcEvent>,
    ) -> Self {
        Self {
            session_id,
            spool_dir: default_spool_dir(),
            ram_threshold: RAM_THRESHOLD_BYTES,
            max_spool_bytes: MAX_SPOOL_BYTES,
            stream_timeout: CLIPBOARD_STREAM_TIMEOUT,
            sender,
            outbound_clip_flow,
            echo_suppressor,
            compositor: Arc::new(HyprlandBackend::new()),
            events_tx,
            inbound_streams: HashMap::new(),
            next_stream_id: Arc::new(AtomicU16::new(1)),
        }
    }

    pub fn with_spool_dir(mut self, spool_dir: PathBuf) -> Self {
        self.spool_dir = spool_dir;
        self
    }

    pub fn with_compositor(mut self, compositor: Arc<dyn CompositorBackend>) -> Self {
        self.compositor = compositor;
        self
    }

    pub fn with_thresholds(mut self, ram_threshold: usize, max_spool_bytes: u64) -> Self {
        self.ram_threshold = ram_threshold;
        self.max_spool_bytes = max_spool_bytes;
        self
    }

    pub fn with_stream_timeout(mut self, timeout: Duration) -> Self {
        self.stream_timeout = timeout;
        self
    }

    pub fn echo_suppressor(&self) -> &Arc<EchoSuppressor> {
        &self.echo_suppressor
    }

    pub async fn run(
        mut self,
        mut clip_meta_rx: mpsc::Receiver<Frame>,
        mut clip_data_rx: mpsc::Receiver<Frame>,
        mut local_clipboard_rx: mpsc::Receiver<ClipboardEvent>,
        mut manual_send_rx: mpsc::Receiver<OutboundClipboardRequest>,
        mut shutdown: watch::Receiver<bool>,
    ) {
        let mut sweep_ticker = tokio::time::interval(Duration::from_secs(1));
        sweep_ticker.set_missed_tick_behavior(MissedTickBehavior::Delay);
        sweep_ticker.tick().await;

        let mut meta_closed = false;
        let mut data_closed = false;
        let mut local_closed = false;
        let mut manual_closed = false;
        let mut active_outbound: Option<tokio::task::JoinHandle<()>> = None;

        loop {
            if meta_closed && data_closed && manual_closed {
                break;
            }

            tokio::select! {
                biased;

                changed = shutdown.changed() => {
                    if changed.is_err() || *shutdown.borrow() {
                        break;
                    }
                }

                maybe_meta = clip_meta_rx.recv(), if !meta_closed => match maybe_meta {
                    Some(frame) => {
                        self.handle_inbound_frame(frame).await;
                    }
                    None => {
                        meta_closed = true;
                    }
                },

                maybe_data = clip_data_rx.recv(), if !data_closed => match maybe_data {
                    Some(frame) => {
                        self.handle_inbound_frame(frame).await;
                    }
                    None => {
                        data_closed = true;
                    }
                },

                maybe_local = local_clipboard_rx.recv(), if !local_closed => match maybe_local {
                    Some(ev) => {
                        if !self.echo_suppressor.should_suppress(&ev.echo_hash) {
                            self.echo_suppressor.record(ev.echo_hash);
                            self.spawn_outbound_send(
                                ev.payload,
                                &mut active_outbound,
                                shutdown.clone(),
                            );
                        } else {
                            debug!("suppressed local clipboard echo");
                        }
                    }
                    None => {
                        local_closed = true;
                    }
                },

                maybe_manual = manual_send_rx.recv(), if !manual_closed => match maybe_manual {
                    Some(req) => {
                        self.echo_suppressor.record(req.echo_hash);
                        self.spawn_outbound_send(
                            req.payload,
                            &mut active_outbound,
                            shutdown.clone(),
                        );
                    }
                    None => {
                        manual_closed = true;
                    }
                },

                _ = sweep_ticker.tick() => {
                    self.sweep_stale_inbound(Instant::now()).await;
                }
            }
        }

        if let Some(handle) = active_outbound.take() {
            handle.abort();
        }
        self.purge_all_active().await;
    }

    fn spawn_outbound_send(
        &self,
        payload: ClipboardPayload,
        active_outbound: &mut Option<tokio::task::JoinHandle<()>>,
        shutdown: watch::Receiver<bool>,
    ) {
        if let Some(prev) = active_outbound.take()
            && !prev.is_finished()
        {
            prev.abort();
        }

        let stream_id = allocate_stream_id(&self.next_stream_id);
        let sender = self.sender.clone();
        let flow = self.outbound_clip_flow.clone();
        let max_bytes = self.max_spool_bytes;

        *active_outbound = Some(tokio::spawn(async move {
            if let Err(err) = send_clipboard_payload_with_cap(
                stream_id,
                payload,
                &sender,
                &flow,
                max_bytes,
                shutdown,
            )
            .await
            {
                warn!(stream_id, error = %err, "outbound clipboard transfer failed");
            }
        }));
    }

    pub async fn handle_inbound_frame(&mut self, frame: Frame) {
        let stream_id = frame.stream_id;
        let now = Instant::now();

        if (frame.flags & FLAG_ABORT) != 0 {
            self.remove_and_purge_stream(stream_id).await;
            return;
        }

        if (frame.flags & FLAG_START) != 0 {
            self.remove_and_purge_stream(stream_id).await;

            let meta = match StreamStartMeta::decode(&frame.payload) {
                Ok(m) => m,
                Err(err) => {
                    warn!(stream_id, error = %err, "invalid clipboard StreamStartMeta; aborting");
                    self.emit_abort(frame.channel_id, stream_id);
                    return;
                }
            };

            if meta.total_len > self.max_spool_bytes {
                warn!(
                    stream_id,
                    total_len = meta.total_len,
                    max = self.max_spool_bytes,
                    "clipboard payload exceeds 512 MiB cap; rejecting"
                );
                self.emit_abort(frame.channel_id, stream_id);
                return;
            }

            if (meta.initial_data.len() as u64) > meta.total_len {
                warn!(stream_id, "initial clipboard data exceeds declared total_len");
                self.emit_abort(frame.channel_id, stream_id);
                return;
            }

            let mut storage = if meta.total_len <= self.ram_threshold as u64 {
                let mut buf = Vec::with_capacity(meta.total_len as usize);
                buf.extend_from_slice(&meta.initial_data);
                InboundStorage::Memory(buf)
            } else {
                match self
                    .create_spool_storage(stream_id, &meta.initial_data)
                    .await
                {
                    Ok(s) => s,
                    Err(err) => {
                        warn!(stream_id, error = %err, "failed to create clipboard spool file");
                        self.emit_abort(frame.channel_id, stream_id);
                        return;
                    }
                }
            };

            let received_len = meta.initial_data.len() as u64;
            if (frame.flags & FLAG_END) != 0 {
                if received_len != meta.total_len {
                    warn!(
                        stream_id,
                        received_len,
                        total_len = meta.total_len,
                        "clipboard single-frame length mismatch; purging"
                    );
                    cleanup_storage(&mut storage).await;
                    self.emit_abort(frame.channel_id, stream_id);
                    return;
                }
                self.finalize_stream(stream_id, meta.mime, meta.total_len, storage)
                    .await;
                return;
            }

            self.inbound_streams.insert(
                stream_id,
                InboundClipboardStream {
                    channel_id: frame.channel_id,
                    mime: meta.mime,
                    total_len: meta.total_len,
                    received_len,
                    storage,
                    last_activity: now,
                },
            );
            return;
        }

        let Some(stream) = self.inbound_streams.get_mut(&stream_id) else {
            return;
        };

        let chunk = &frame.payload;
        let Some(new_len) = stream.received_len.checked_add(chunk.len() as u64) else {
            self.remove_and_purge_stream(stream_id).await;
            self.emit_abort(frame.channel_id, stream_id);
            return;
        };

        if new_len > stream.total_len || new_len > self.max_spool_bytes {
            warn!(
                stream_id,
                new_len,
                total_len = stream.total_len,
                "clipboard chunk exceeds declared/cap size; aborting and purging"
            );
            self.remove_and_purge_stream(stream_id).await;
            self.emit_abort(frame.channel_id, stream_id);
            return;
        }

        match &mut stream.storage {
            InboundStorage::Memory(buf) => {
                buf.extend_from_slice(chunk);
            }
            InboundStorage::Spool {
                file, head, tail, ..
            } => {
                if !chunk.is_empty() {
                    if let Err(err) = file.write_all(chunk).await {
                        warn!(stream_id, error = %err, "spool write failed; purging");
                        self.remove_and_purge_stream(stream_id).await;
                        self.emit_abort(frame.channel_id, stream_id);
                        return;
                    }
                    update_head_tail(head, tail, chunk);
                }
            }
        }

        stream.received_len = new_len;
        stream.last_activity = now;

        if (frame.flags & FLAG_END) != 0 {
            let finished = self.inbound_streams.remove(&stream_id).unwrap();
            let mut storage = finished.storage;
            if finished.received_len != finished.total_len {
                warn!(
                    stream_id,
                    received = finished.received_len,
                    expected = finished.total_len,
                    "clipboard EOF length mismatch; purging"
                );
                cleanup_storage(&mut storage).await;
                self.emit_abort(finished.channel_id, stream_id);
                return;
            }
            self.finalize_stream(stream_id, finished.mime, finished.total_len, storage)
                .await;
        }
    }

    pub async fn sweep_stale_inbound(&mut self, now: Instant) -> Vec<u16> {
        let expired: Vec<(u16, u8)> = self
            .inbound_streams
            .iter()
            .filter_map(|(&id, st)| {
                if now.duration_since(st.last_activity) >= self.stream_timeout {
                    Some((id, st.channel_id))
                } else {
                    None
                }
            })
            .collect();

        let mut purged = Vec::with_capacity(expired.len());
        for (stream_id, channel_id) in expired {
            warn!(stream_id, "purging timed-out inbound clipboard stream");
            self.remove_and_purge_stream(stream_id).await;
            self.emit_abort(channel_id, stream_id);
            purged.push(stream_id);
        }
        purged
    }

    pub async fn purge_all_active(&mut self) {
        let keys: Vec<u16> = self.inbound_streams.keys().copied().collect();
        for stream_id in keys {
            self.remove_and_purge_stream(stream_id).await;
        }
    }

    async fn remove_and_purge_stream(&mut self, stream_id: u16) {
        if let Some(mut stream) = self.inbound_streams.remove(&stream_id) {
            cleanup_storage(&mut stream.storage).await;
        }
    }

    async fn create_spool_storage(&self, stream_id: u16, initial_data: &[u8]) -> Result<InboundStorage> {
        fs::create_dir_all(&self.spool_dir)
            .await
            .with_context(|| format!("failed to create spool dir {}", self.spool_dir.display()))?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = fs::set_permissions(&self.spool_dir, std::fs::Permissions::from_mode(0o700)).await;
        }

        let path = self
            .spool_dir
            .join(format!("{}_{}.tmp", self.session_id, stream_id));

        let mut opts = OpenOptions::new();
        opts.write(true).create(true).truncate(true);
        #[cfg(unix)]
        {
            opts.mode(0o600);
        }
        let mut file = opts
            .open(&path)
            .await
            .with_context(|| format!("failed to open spool file {}", path.display()))?;

        let mut head = Vec::with_capacity(ECHO_SLICE_LEN);
        let mut tail = Vec::with_capacity(ECHO_SLICE_LEN);
        if !initial_data.is_empty() {
            if let Err(err) = file.write_all(initial_data).await {
                drop(file);
                let _ = fs::remove_file(&path).await;
                return Err(err.into());
            }
            update_head_tail(&mut head, &mut tail, initial_data);
        }

        Ok(InboundStorage::Spool {
            file,
            path,
            head,
            tail,
        })
    }

    async fn finalize_stream(
        &self,
        stream_id: u16,
        mime: String,
        total_len: u64,
        storage: InboundStorage,
    ) {
        let mut text_preview: Option<String> = None;
        let (payload, echo_hash, spool_path) = match storage {
            InboundStorage::Memory(data) => {
                let is_text = mime.starts_with("text/");
                if is_text {
                    let mut end = data.len().min(4096);
                    while end > 0 && end < data.len() && (data[end] & 0xC0) == 0x80 {
                        end -= 1;
                    }
                    text_preview = Some(String::from_utf8_lossy(&data[..end]).into_owned());
                }
                let echo_hash = if is_text {
                    compute_clipboard_echo_hash(true, data.len() as u64, &data, &[])
                } else {
                    compute_binary_echo_hash(&data)
                };
                (ClipboardPayload::Memory { mime: mime.clone(), data }, echo_hash, None)
            }
            InboundStorage::Spool {
                mut file,
                path,
                head,
                tail,
            } => {
                if let Err(err) = file.flush().await {
                    warn!(stream_id, error = %err, "failed to flush spool file");
                    drop(file);
                    let _ = fs::remove_file(&path).await;
                    return;
                }
                drop(file);
                let echo_hash = compute_clipboard_echo_hash(false, total_len, &head, &tail);
                (
                    ClipboardPayload::SpoolFile {
                        mime: mime.clone(),
                        path: path.clone(),
                        total_len,
                    },
                    echo_hash,
                    Some(path),
                )
            }
        };

        if self.echo_suppressor.should_suppress(&echo_hash) {
            debug!(stream_id, "suppressed incoming remote clipboard echo");
            if let Some(path) = spool_path {
                let _ = fs::remove_file(path).await;
            }
            return;
        }

        // Record before calling Wayland backend so local watcher echo is suppressed immediately.
        self.echo_suppressor.record(echo_hash);

        let history_path = if mime.starts_with("image/") {
            self.persist_image_history(stream_id, &mime, &payload).await
        } else {
            None
        };

        if let Err(err) = self.compositor.set_clipboard(payload).await {
            warn!(stream_id, error = %err, "failed to apply remote clipboard payload");
            if let Some(path) = spool_path {
                let _ = fs::remove_file(path).await;
            }
            if let Some(hpath) = history_path {
                let _ = fs::remove_file(hpath).await;
            }
            return;
        }

        let file_path_str = history_path.map(|p| p.to_string_lossy().into_owned());
        let _ = self.events_tx.send(IpcEvent {
            event: "clipboard.received".to_string(),
            data: json!({
                "mime": mime,
                "bytes": total_len,
                "size_bytes": total_len,
                "text": text_preview,
                "file_path": file_path_str,
            }),
        });
    }

    async fn persist_image_history(
        &self,
        stream_id: u16,
        mime: &str,
        payload: &ClipboardPayload,
    ) -> Option<PathBuf> {
        let history_dir = self.spool_dir.join("history");
        if fs::create_dir_all(&history_dir).await.is_err() {
            return None;
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = fs::set_permissions(&history_dir, std::fs::Permissions::from_mode(0o700)).await;
        }

        prune_clip_history_dir(&history_dir, MAX_CLIP_HISTORY_FILES.saturating_sub(1)).await;

        let ext = image_extension_for_mime(mime);
        let now_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis())
            .unwrap_or(0);
        let seq = CLIP_HISTORY_SEQ.fetch_add(1, Ordering::Relaxed);
        let target = history_dir.join(format!("clip_{now_ms:013}_{seq:06}_{stream_id}.{ext}"));

        let write_ok = match payload {
            ClipboardPayload::Memory { data, .. } => {
                let mut opts = OpenOptions::new();
                opts.write(true).create(true).truncate(true);
                #[cfg(unix)]
                {
                    opts.mode(0o600);
                }
                match opts.open(&target).await {
                    Ok(mut file) => file.write_all(data).await.is_ok() && file.flush().await.is_ok(),
                    Err(_) => false,
                }
            }
            ClipboardPayload::SpoolFile { path, .. } => {
                let copied = fs::copy(path, &target).await.is_ok();
                #[cfg(unix)]
                if copied {
                    use std::os::unix::fs::PermissionsExt;
                    let _ = fs::set_permissions(&target, std::fs::Permissions::from_mode(0o600)).await;
                }
                copied
            }
        };

        if write_ok {
            Some(target)
        } else {
            let _ = fs::remove_file(&target).await;
            None
        }
    }

    fn emit_abort(&self, channel_id: u8, stream_id: u16) {
        let abort = Frame::new(channel_id, stream_id, FLAG_ABORT, 0, BytesMut::new());
        let _ = self.sender.try_send_high(abort);
    }
}

pub async fn send_clipboard_payload(
    stream_id: u16,
    payload: ClipboardPayload,
    sender: &PrioritySender,
    outbound_clip_flow: &FlowController,
    shutdown: watch::Receiver<bool>,
) -> Result<()> {
    send_clipboard_payload_with_cap(
        stream_id,
        payload,
        sender,
        outbound_clip_flow,
        MAX_SPOOL_BYTES,
        shutdown,
    )
    .await
}

async fn send_clipboard_payload_with_cap(
    stream_id: u16,
    payload: ClipboardPayload,
    sender: &PrioritySender,
    outbound_clip_flow: &FlowController,
    max_bytes: u64,
    mut shutdown: watch::Receiver<bool>,
) -> Result<()> {
    let (mime, total_len) = match &payload {
        ClipboardPayload::Memory { mime, data } => (mime.clone(), data.len() as u64),
        ClipboardPayload::SpoolFile { mime, total_len, .. } => (mime.clone(), *total_len),
    };

    if total_len > max_bytes {
        bail!("outbound clipboard payload {total_len} exceeds cap {max_bytes}");
    }

    let mime_len = mime.len();
    let header_overhead = 10 + mime_len;
    if header_overhead > MAX_PAYLOAD_SIZE {
        bail!("clipboard MIME type too long ({mime_len} bytes)");
    }

    let use_clip_meta = mime.starts_with("text/") && total_len <= CLIP_META_MAX_BYTES as u64;
    let (channel_id, priority) = if use_clip_meta {
        (CHANNEL_CLIP_META, OutgoingPriority::High)
    } else {
        (CHANNEL_CLIP_DATA, OutgoingPriority::Low)
    };

    let first_chunk_cap = MAX_PAYLOAD_SIZE - header_overhead;

    match payload {
        ClipboardPayload::Memory { data, .. } => {
            let first_len = data.len().min(first_chunk_cap);
            let is_single = first_len == data.len();
            let meta = StreamStartMeta {
                total_len,
                mime,
                initial_data: BytesMut::from(&data[..first_len]),
            };
            let frame0_payload = meta.encode().context("failed to encode StreamStartMeta")?;
            let flags0 = if is_single {
                FLAG_START | FLAG_END
            } else {
                FLAG_START
            };

            if channel_id == CHANNEL_CLIP_DATA
                && !acquire_credit_or_cancel(outbound_clip_flow, &mut shutdown).await
            {
                bail!("cancelled while waiting for CLIP_DATA flow credit");
            }
            sender
                .send(Frame::new(channel_id, stream_id, flags0, 0, frame0_payload), priority)
                .await
                .map_err(|_| anyhow::anyhow!("priority sender closed"))?;

            let mut offset = first_len;
            let mut seq = 1u32;
            while offset < data.len() {
                tokio::task::yield_now().await;

                let end = (offset + MAX_PAYLOAD_SIZE).min(data.len());
                let flags = if end == data.len() { FLAG_END } else { 0 };

                if channel_id == CHANNEL_CLIP_DATA
                    && !acquire_credit_or_cancel(outbound_clip_flow, &mut shutdown).await
                {
                    let _ = sender.try_send_high(Frame::new(
                        channel_id,
                        stream_id,
                        FLAG_ABORT,
                        0,
                        BytesMut::new(),
                    ));
                    bail!("cancelled mid-stream waiting for CLIP_DATA credit");
                }

                sender
                    .send(
                        Frame::new(channel_id, stream_id, flags, seq, &data[offset..end]),
                        priority,
                    )
                    .await
                    .map_err(|_| anyhow::anyhow!("priority sender closed"))?;

                offset = end;
                seq = seq.checked_add(1).context("clipboard sequence overflow")?;
            }
        }
        ClipboardPayload::SpoolFile { path, .. } => {
            let mut file = File::open(&path)
                .await
                .with_context(|| format!("failed to open outbound spool {}", path.display()))?;

            let mut first_buf = vec![0u8; (total_len as usize).min(first_chunk_cap)];
            if !first_buf.is_empty() {
                file.read_exact(&mut first_buf)
                    .await
                    .context("unexpected EOF reading outbound spool header chunk")?;
            }

            let mut sent = first_buf.len() as u64;
            let is_single = sent >= total_len;
            let meta = StreamStartMeta {
                total_len,
                mime,
                initial_data: BytesMut::from(&first_buf[..]),
            };
            let frame0_payload = meta.encode().context("failed to encode StreamStartMeta")?;
            let flags0 = if is_single {
                FLAG_START | FLAG_END
            } else {
                FLAG_START
            };

            if channel_id == CHANNEL_CLIP_DATA
                && !acquire_credit_or_cancel(outbound_clip_flow, &mut shutdown).await
            {
                bail!("cancelled while waiting for CLIP_DATA flow credit");
            }
            sender
                .send(Frame::new(channel_id, stream_id, flags0, 0, frame0_payload), priority)
                .await
                .map_err(|_| anyhow::anyhow!("priority sender closed"))?;

            let mut chunk_buf = vec![0u8; MAX_PAYLOAD_SIZE];
            let mut seq = 1u32;
            while sent < total_len {
                tokio::task::yield_now().await;

                let to_read = (total_len - sent).min(MAX_PAYLOAD_SIZE as u64) as usize;
                let slice = &mut chunk_buf[..to_read];
                if let Err(err) = file.read_exact(slice).await {
                    let _ = sender.try_send_high(Frame::new(
                        channel_id,
                        stream_id,
                        FLAG_ABORT,
                        0,
                        BytesMut::new(),
                    ));
                    return Err(err).context("unexpected EOF reading outbound spool file");
                }

                if channel_id == CHANNEL_CLIP_DATA
                    && !acquire_credit_or_cancel(outbound_clip_flow, &mut shutdown).await
                {
                    let _ = sender.try_send_high(Frame::new(
                        channel_id,
                        stream_id,
                        FLAG_ABORT,
                        0,
                        BytesMut::new(),
                    ));
                    bail!("cancelled mid-stream waiting for CLIP_DATA credit");
                }

                sent += to_read as u64;
                let flags = if sent >= total_len { FLAG_END } else { 0 };
                sender
                    .send(
                        Frame::new(channel_id, stream_id, flags, seq, &slice[..]),
                        priority,
                    )
                    .await
                    .map_err(|_| anyhow::anyhow!("priority sender closed"))?;

                seq = seq.checked_add(1).context("clipboard sequence overflow")?;
            }
        }
    }

    Ok(())
}

async fn acquire_credit_or_cancel(
    flow: &FlowController,
    shutdown: &mut watch::Receiver<bool>,
) -> bool {
    if *shutdown.borrow() {
        return false;
    }
    tokio::select! {
        biased;
        changed = shutdown.changed() => {
            let _ = changed;
            false
        }
        acquired = flow.acquire_credit() => acquired,
    }
}

fn allocate_stream_id(counter: &AtomicU16) -> u16 {
    loop {
        let current = counter.fetch_add(1, Ordering::Relaxed);
        if current != 0 {
            return current;
        }
    }
}

fn update_head_tail(head: &mut Vec<u8>, tail: &mut Vec<u8>, chunk: &[u8]) {
    if head.len() < ECHO_SLICE_LEN {
        let take = (ECHO_SLICE_LEN - head.len()).min(chunk.len());
        head.extend_from_slice(&chunk[..take]);
    }
    if chunk.len() >= ECHO_SLICE_LEN {
        tail.clear();
        tail.extend_from_slice(&chunk[chunk.len() - ECHO_SLICE_LEN..]);
    } else if !chunk.is_empty() {
        let combined = tail.len() + chunk.len();
        if combined > ECHO_SLICE_LEN {
            tail.drain(..combined - ECHO_SLICE_LEN);
        }
        tail.extend_from_slice(chunk);
    }
}

async fn cleanup_storage(storage: &mut InboundStorage) {
    if let InboundStorage::Spool { file, path, .. } = storage {
        let _ = file.flush().await;
        let p = path.clone();
        // Close handle by replacing storage with empty Memory before removing file
        *storage = InboundStorage::Memory(Vec::new());
        let _ = fs::remove_file(p).await;
    }
}

pub fn compute_payload_echo_hash(mime: &str, data: &[u8]) -> [u8; 32] {
    if mime.starts_with("text/") && data.len() <= RAM_THRESHOLD_BYTES {
        compute_clipboard_echo_hash(true, data.len() as u64, data, &[])
    } else {
        compute_binary_echo_hash(data)
    }
}

fn image_extension_for_mime(mime: &str) -> &'static str {
    let clean = mime.split(';').next().unwrap_or("").trim();
    match clean {
        "image/png" => "png",
        "image/jpeg" | "image/jpg" => "jpg",
        "image/webp" => "webp",
        "image/gif" => "gif",
        "image/bmp" | "image/x-ms-bmp" => "bmp",
        "image/heic" | "image/heif" => "heic",
        _ => "bin",
    }
}

async fn prune_clip_history_dir(dir: &Path, keep: usize) {
    let Ok(mut rd) = fs::read_dir(dir).await else {
        return;
    };
    let mut entries: Vec<(String, PathBuf)> = Vec::new();
    while let Ok(Some(entry)) = rd.next_entry().await {
        if let Ok(ft) = entry.file_type().await
            && ft.is_file()
        {
            let name = entry.file_name().to_string_lossy().into_owned();
            entries.push((name, entry.path()));
        }
    }
    if entries.len() <= keep {
        return;
    }
    entries.sort_by(|a, b| a.0.cmp(&b.0));
    let remove_count = entries.len().saturating_sub(keep);
    for (_, path) in entries.into_iter().take(remove_count) {
        let _ = fs::remove_file(path).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use core_protocol::{InputFallbackEvent, compute_text_echo_hash};
    use std::future::Future;
    use std::pin::Pin;
    use std::sync::Mutex;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[derive(Default)]
    struct MockCompositor {
        received: Mutex<Vec<ClipboardPayload>>,
    }

    impl CompositorBackend for MockCompositor {
        fn name(&self) -> &'static str {
            "mock"
        }

        fn watch_clipboard(&self) -> mpsc::Receiver<ClipboardEvent> {
            let (_tx, rx) = mpsc::channel(1);
            rx
        }

        fn set_clipboard<'a>(
            &'a self,
            payload: ClipboardPayload,
        ) -> Pin<Box<dyn Future<Output = Result<()>> + Send + 'a>> {
            self.received.lock().unwrap().push(payload);
            Box::pin(async { Ok(()) })
        }

        fn inject_fallback_input(&self, _ev: InputFallbackEvent) -> Result<()> {
            Ok(())
        }
    }

    fn temp_spool_dir(label: &str) -> PathBuf {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!("controid-spool-{label}-{unique}"))
    }

    #[tokio::test]
    async fn inbound_memory_and_echo_suppression() {
        let spool = temp_spool_dir("mem");
        let (sender, _high_rx, _low_rx) = PrioritySender::channel(16, 16);
        let flow = FlowController::default();
        let echo = Arc::new(EchoSuppressor::default());
        let compositor = Arc::new(MockCompositor::default());
        let (events_tx, mut events_rx) = broadcast::channel(16);

        let mut worker = ClipboardWorker::new(
            "sess1".to_string(),
            sender,
            flow,
            Arc::clone(&echo),
            events_tx,
        )
        .with_spool_dir(spool.clone())
        .with_compositor(compositor.clone());

        let meta = StreamStartMeta {
            total_len: 11,
            mime: "text/plain".to_string(),
            initial_data: BytesMut::from(&b"hello "[..]),
        }
        .encode()
        .unwrap();

        worker
            .handle_inbound_frame(Frame::new(CHANNEL_CLIP_META, 1, FLAG_START, 0, meta))
            .await;
        worker
            .handle_inbound_frame(Frame::new(
                CHANNEL_CLIP_META,
                1,
                FLAG_END,
                1,
                BytesMut::from(&b"world"[..]),
            ))
            .await;

        {
            let applied = compositor.received.lock().unwrap();
            assert_eq!(applied.len(), 1);
            match &applied[0] {
                ClipboardPayload::Memory { mime, data } => {
                    assert_eq!(mime, "text/plain");
                    assert_eq!(data, b"hello world");
                }
                _ => panic!("expected Memory payload"),
            }
        }

        let ev = events_rx.recv().await.unwrap();
        assert_eq!(ev.event, "clipboard.received");
        assert_eq!(ev.data["mime"], "text/plain");
        assert_eq!(ev.data["bytes"], 11);
        assert_eq!(ev.data["size_bytes"], 11);
        assert_eq!(ev.data["text"], "hello world");

        // Echo hash must now suppress bouncing "hello world" back
        let h = compute_text_echo_hash("hello world");
        assert!(echo.should_suppress(&h));

        let _ = std::fs::remove_dir_all(&spool);
    }

    #[tokio::test]
    async fn inbound_spool_threshold_streams_to_disk_and_enforces_cap() {
        let spool = temp_spool_dir("spool");
        let (sender, mut high_rx, _low_rx) = PrioritySender::channel(16, 16);
        let flow = FlowController::default();
        let echo = Arc::new(EchoSuppressor::default());
        let compositor = Arc::new(MockCompositor::default());
        let (events_tx, _events_rx) = broadcast::channel(16);

        // Use a small threshold (100 bytes) and cap (500 bytes) to test spooling cleanly.
        let mut worker = ClipboardWorker::new(
            "s42".to_string(),
            sender,
            flow,
            Arc::clone(&echo),
            events_tx,
        )
        .with_spool_dir(spool.clone())
        .with_compositor(compositor.clone())
        .with_thresholds(100, 500);

        // 1. Over cap (> 500) -> immediately rejected with FLAG_ABORT and no spool file created
        let over_meta = StreamStartMeta {
            total_len: 501,
            mime: "application/octet-stream".to_string(),
            initial_data: BytesMut::new(),
        }
        .encode()
        .unwrap();
        worker
            .handle_inbound_frame(Frame::new(CHANNEL_CLIP_DATA, 9, FLAG_START, 0, over_meta))
            .await;
        let abort = high_rx.try_recv().unwrap();
        assert_eq!((abort.stream_id, abort.flags), (9, FLAG_ABORT));
        assert!(!spool.join("s42_9.tmp").exists());

        // 2. Above RAM threshold (150 bytes) -> spools to <spool>/s42_10.tmp
        let payload = vec![0x7Au8; 150];
        let start_meta = StreamStartMeta {
            total_len: 150,
            mime: "image/png".to_string(),
            initial_data: BytesMut::from(&payload[..50]),
        }
        .encode()
        .unwrap();
        worker
            .handle_inbound_frame(Frame::new(CHANNEL_CLIP_DATA, 10, FLAG_START, 0, start_meta))
            .await;
        assert!(spool.join("s42_10.tmp").exists());

        worker
            .handle_inbound_frame(Frame::new(
                CHANNEL_CLIP_DATA,
                10,
                FLAG_END,
                1,
                BytesMut::from(&payload[50..]),
            ))
            .await;

        let applied = compositor.received.lock().unwrap();
        assert_eq!(applied.len(), 1);
        match &applied[0] {
            ClipboardPayload::SpoolFile {
                mime,
                path,
                total_len,
            } => {
                assert_eq!(mime, "image/png");
                assert_eq!(*total_len, 150);
                assert_eq!(std::fs::read(path).unwrap(), payload);
            }
            _ => panic!("expected SpoolFile payload"),
        }

        let _ = std::fs::remove_dir_all(&spool);
    }

    #[tokio::test]
    async fn spool_files_purged_on_abort_timeout_and_startup() {
        let spool = temp_spool_dir("purge");
        std::fs::create_dir_all(&spool).unwrap();
        std::fs::write(spool.join("stale_old_1.tmp"), b"stale").unwrap();
        std::fs::write(spool.join("keep.txt"), b"not-tmp").unwrap();

        let purged = purge_spool_dir(&spool).await.unwrap();
        assert_eq!(purged, 1);
        assert!(!spool.join("stale_old_1.tmp").exists());
        assert!(spool.join("keep.txt").exists());

        let (sender, mut high_rx, _low_rx) = PrioritySender::channel(16, 16);
        let flow = FlowController::default();
        let echo = Arc::new(EchoSuppressor::default());
        let (events_tx, _events_rx) = broadcast::channel(16);

        let mut worker = ClipboardWorker::new("sess".to_string(), sender, flow, echo, events_tx)
            .with_spool_dir(spool.clone())
            .with_thresholds(10, 1024)
            .with_stream_timeout(Duration::from_millis(50));

        // Stream 1 aborted explicitly via FLAG_ABORT
        let meta1 = StreamStartMeta {
            total_len: 100,
            mime: "application/octet-stream".to_string(),
            initial_data: BytesMut::from(&b"part1"[..]),
        }
        .encode()
        .unwrap();
        worker
            .handle_inbound_frame(Frame::new(CHANNEL_CLIP_DATA, 1, FLAG_START, 0, meta1))
            .await;
        assert!(spool.join("sess_1.tmp").exists());

        worker
            .handle_inbound_frame(Frame::new(
                CHANNEL_CLIP_DATA,
                1,
                FLAG_ABORT,
                0,
                BytesMut::new(),
            ))
            .await;
        assert!(!spool.join("sess_1.tmp").exists());

        // Stream 2 times out and gets swept
        let meta2 = StreamStartMeta {
            total_len: 100,
            mime: "application/octet-stream".to_string(),
            initial_data: BytesMut::from(&b"part2"[..]),
        }
        .encode()
        .unwrap();
        worker
            .handle_inbound_frame(Frame::new(CHANNEL_CLIP_DATA, 2, FLAG_START, 0, meta2))
            .await;
        assert!(spool.join("sess_2.tmp").exists());

        let swept = worker
            .sweep_stale_inbound(Instant::now() + Duration::from_millis(60))
            .await;
        assert_eq!(swept, vec![2]);
        assert!(!spool.join("sess_2.tmp").exists());
        let abort_frame = high_rx.try_recv().unwrap();
        assert_eq!((abort_frame.stream_id, abort_frame.flags), (2, FLAG_ABORT));

        let _ = std::fs::remove_dir_all(&spool);
    }

    #[tokio::test]
    async fn outbound_chunker_splits_16kb_and_respects_flow_credits() {
        let (sender, _high_rx, mut low_rx) = PrioritySender::channel(16, 16);
        let flow = FlowController::new(2);
        let (_shutdown_tx, shutdown_rx) = watch::channel(false);

        // 40 KB binary payload -> requires 3 chunks on CHANNEL_CLIP_DATA, but flow only has 2 credits initially!
        let payload = ClipboardPayload::Memory {
            mime: "application/octet-stream".to_string(),
            data: vec![0x33u8; 40 * 1024],
        };

        let send_task = tokio::spawn(async move {
            send_clipboard_payload(7, payload, &sender, &flow, shutdown_rx).await
        });

        let f0 = low_rx.recv().await.unwrap();
        let f1 = low_rx.recv().await.unwrap();
        assert_eq!(f0.channel_id, CHANNEL_CLIP_DATA);
        assert_eq!(f0.flags, FLAG_START);
        assert_eq!(f0.sequence_number, 0);
        assert!(f0.payload.len() <= MAX_PAYLOAD_SIZE);
        assert_eq!(f1.sequence_number, 1);
        assert_eq!(f1.flags, 0);
        assert_eq!(f1.payload.len(), MAX_PAYLOAD_SIZE);

        // Third chunk cannot be sent until credit is granted
        tokio::time::sleep(Duration::from_millis(20)).await;
        assert!(low_rx.try_recv().is_err());

        // Grant 1 credit via a fresh handle or wait: we moved `flow` into send_task, so test with clone!
        // Abort send_task to re-run with cloned flow handle cleanly.
        send_task.abort();

        let (sender, _high_rx, mut low_rx) = PrioritySender::channel(16, 16);
        let flow = FlowController::new(2);
        let flow_grant = flow.clone();
        let (_shutdown_tx, shutdown_rx) = watch::channel(false);
        let payload = ClipboardPayload::Memory {
            mime: "application/octet-stream".to_string(),
            data: vec![0x33u8; 40 * 1024],
        };

        let send_task = tokio::spawn(async move {
            send_clipboard_payload(8, payload, &sender, &flow, shutdown_rx).await
        });

        let c0 = low_rx.recv().await.unwrap();
        let c1 = low_rx.recv().await.unwrap();
        tokio::task::yield_now().await;
        assert!(low_rx.try_recv().is_err());

        flow_grant.grant_credits(1, 32 * 1024);
        let c2 = low_rx.recv().await.unwrap();
        assert_eq!(c2.sequence_number, 2);
        assert_eq!(c2.flags, FLAG_END);
        send_task.await.unwrap().unwrap();

        // Reassemble and verify exact payload bytes
        let meta = StreamStartMeta::decode(&c0.payload).unwrap();
        let mut reassembled = meta.initial_data.to_vec();
        reassembled.extend_from_slice(&c1.payload);
        reassembled.extend_from_slice(&c2.payload);
        assert_eq!(reassembled.len(), 40 * 1024);
        assert!(reassembled.iter().all(|&b| b == 0x33));
    }

    #[tokio::test]
    async fn inbound_image_persists_history_file_and_emits_path() {
        let spool = temp_spool_dir("imghist");
        let (sender, _high_rx, _low_rx) = PrioritySender::channel(16, 16);
        let flow = FlowController::default();
        let echo = Arc::new(EchoSuppressor::default());
        let compositor = Arc::new(MockCompositor::default());
        let (events_tx, mut events_rx) = broadcast::channel(16);

        let mut worker = ClipboardWorker::new(
            "img1".to_string(),
            sender,
            flow,
            echo,
            events_tx,
        )
        .with_spool_dir(spool.clone())
        .with_compositor(compositor.clone());

        let png = b"\x89PNG\r\n\x1a\nfake-png-body".to_vec();
        let meta = StreamStartMeta {
            total_len: png.len() as u64,
            mime: "image/png".to_string(),
            initial_data: BytesMut::from(&png[..]),
        }
        .encode()
        .unwrap();

        worker
            .handle_inbound_frame(Frame::new(
                CHANNEL_CLIP_DATA,
                5,
                FLAG_START | FLAG_END,
                0,
                meta,
            ))
            .await;

        let ev = events_rx.recv().await.unwrap();
        assert_eq!(ev.event, "clipboard.received");
        assert_eq!(ev.data["mime"], "image/png");
        assert!(ev.data["text"].is_null());
        let file_path = ev.data["file_path"].as_str().expect("expected file_path");
        assert_eq!(std::fs::read(file_path).unwrap(), png);

        let _ = std::fs::remove_dir_all(&spool);
    }
}
