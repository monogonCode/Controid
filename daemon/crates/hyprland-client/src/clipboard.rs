use core_protocol::{ECHO_SLICE_LEN, compute_clipboard_echo_hash};
use nix::fcntl::{FcntlArg, OFlag, fcntl};
use std::collections::HashMap;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::fd::AsRawFd;
use std::os::unix::fs::{FileExt, OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::sync::mpsc as std_mpsc;
use std::thread;
use std::time::{Duration, Instant};
use thiserror::Error;
use tokio::sync::mpsc;
use tracing::{debug, warn};
use wayland_client::globals::{GlobalListContents, registry_queue_init};
use wayland_client::protocol::wl_registry::{self, WlRegistry};
use wayland_client::protocol::wl_seat::{self, WlSeat};
use wayland_client::{Connection, Dispatch, Proxy, QueueHandle, event_created_child};
use wayland_protocols::ext::data_control::v1::client::{
    ext_data_control_device_v1::{self, EVT_DATA_OFFER_OPCODE as EXT_EVT_DATA_OFFER_OPCODE, ExtDataControlDeviceV1},
    ext_data_control_manager_v1::ExtDataControlManagerV1,
    ext_data_control_offer_v1::{self, ExtDataControlOfferV1},
    ext_data_control_source_v1::{self, ExtDataControlSourceV1},
};
use wayland_protocols_wlr::data_control::v1::client::{
    zwlr_data_control_device_v1::{self, EVT_DATA_OFFER_OPCODE as WLR_EVT_DATA_OFFER_OPCODE, ZwlrDataControlDeviceV1},
    zwlr_data_control_manager_v1::ZwlrDataControlManagerV1,
    zwlr_data_control_offer_v1::{self, ZwlrDataControlOfferV1},
    zwlr_data_control_source_v1::{self, ZwlrDataControlSourceV1},
};
use x11rb::connection::Connection as X11Connection;
use x11rb::protocol::Event as X11Event;
use x11rb::protocol::xfixes::{self, ConnectionExt as _, SelectionEventMask};
use x11rb::protocol::xproto::{
    self, Atom, AtomEnum, ChangeWindowAttributesAux, ConnectionExt as _, CreateWindowAux,
    EventMask, PropMode, SELECTION_NOTIFY_EVENT, SelectionNotifyEvent, Window, WindowClass,
};
use x11rb::rust_connection::RustConnection;
use x11rb::wrapper::ConnectionExt as _;

pub const RAM_THRESHOLD_BYTES: usize = 32 * 1024 * 1024;
pub const MAX_CLIPBOARD_BYTES: u64 = 512 * 1024 * 1024;
const STREAM_CHUNK_SIZE: usize = 64 * 1024;
const X11_INCR_THRESHOLD: u64 = 256 * 1024;
const X11_INCR_CHUNK_SIZE: usize = 128 * 1024;
const X11_SELECTION_TIMEOUT: Duration = Duration::from_secs(3);

const MIME_TEXT_UTF8: &str = "text/plain;charset=utf-8";
const MIME_PNG: &str = "image/png";
const MIME_JPEG: &str = "image/jpeg";

static SPOOL_SEQ: AtomicU64 = AtomicU64::new(1);
static ACTIVE_X11_OWNER_WIN: AtomicU32 = AtomicU32::new(0);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClipboardBackendKind {
    WaylandExtDataControl,
    WaylandWlrDataControl,
    X11,
    Unavailable,
}

impl ClipboardBackendKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::WaylandExtDataControl => "wayland-ext-data-control",
            Self::WaylandWlrDataControl => "wayland-wlr-data-control",
            Self::X11 => "x11",
            Self::Unavailable => "unavailable",
        }
    }
}

#[derive(Debug, Error)]
pub enum ClipboardError {
    #[error("wayland clipboard error: {0}")]
    Wayland(String),
    #[error("x11 clipboard error: {0}")]
    X11(String),
    #[error("clipboard backend unavailable: {0}")]
    Unavailable(String),
    #[error("io error: {0}")]
    Io(#[from] io::Error),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ClipboardPayload {
    Memory {
        mime: String,
        data: Vec<u8>,
    },
    SpoolFile {
        mime: String,
        path: PathBuf,
        total_len: u64,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClipboardEvent {
    pub payload: ClipboardPayload,
    pub echo_hash: [u8; 32],
}

pub struct UniversalClipboardDriver;
pub type WaylandClipboardDriver = UniversalClipboardDriver;

fn env_nonempty(key: &str) -> bool {
    std::env::var_os(key).is_some_and(|v| !v.is_empty())
}

pub fn detect_clipboard_backend() -> ClipboardBackendKind {
    let has_wayland = env_nonempty("WAYLAND_DISPLAY");
    let has_x11 = env_nonempty("DISPLAY");

    if has_wayland {
        match probe_wayland_data_control() {
            Some(kind) => return kind,
            None if has_x11 => return ClipboardBackendKind::X11,
            None => return ClipboardBackendKind::Unavailable,
        }
    }

    if has_x11 {
        ClipboardBackendKind::X11
    } else {
        ClipboardBackendKind::Unavailable
    }
}

struct ProbeState;

impl Dispatch<WlRegistry, GlobalListContents> for ProbeState {
    fn event(
        _state: &mut Self,
        _proxy: &WlRegistry,
        _event: wl_registry::Event,
        _data: &GlobalListContents,
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
    }
}

fn probe_wayland_data_control() -> Option<ClipboardBackendKind> {
    let conn = Connection::connect_to_env().ok()?;
    let (globals, _queue) = registry_queue_init::<ProbeState>(&conn).ok()?;
    let ext_name = ExtDataControlManagerV1::interface().name;
    let wlr_name = ZwlrDataControlManagerV1::interface().name;

    globals.contents().with_list(|list| {
        if list.iter().any(|g| g.interface == ext_name) {
            Some(ClipboardBackendKind::WaylandExtDataControl)
        } else if list.iter().any(|g| g.interface == wlr_name) {
            Some(ClipboardBackendKind::WaylandWlrDataControl)
        } else {
            None
        }
    })
}

impl UniversalClipboardDriver {
    pub fn detect_backend() -> ClipboardBackendKind {
        detect_clipboard_backend()
    }

    pub fn spawn_watcher() -> mpsc::Receiver<ClipboardEvent> {
        let (tx, rx) = mpsc::channel(16);

        let spawn_res = thread::Builder::new()
            .name("clipboard-watcher".to_owned())
            .spawn(move || {
                let backend = detect_clipboard_backend();
                debug!(backend = backend.as_str(), "starting clipboard watcher");
                match backend {
                    ClipboardBackendKind::WaylandExtDataControl
                    | ClipboardBackendKind::WaylandWlrDataControl => {
                        if let Err(err) = run_wayland_watcher(tx) {
                            warn!(?err, "wayland clipboard watcher exited");
                        }
                    }
                    ClipboardBackendKind::X11 => {
                        if let Err(err) = run_x11_watcher(tx) {
                            warn!(?err, "x11 clipboard watcher exited");
                        }
                    }
                    ClipboardBackendKind::Unavailable => {
                        debug!("no compatible Wayland data-control or X11 clipboard backend available");
                    }
                }
            });

        if let Err(err) = spawn_res {
            warn!(?err, "failed to spawn clipboard watcher thread");
        }

        rx
    }

    pub async fn write_clipboard(payload: ClipboardPayload) -> Result<(), ClipboardError> {
        tokio::task::spawn_blocking(move || write_clipboard_blocking(payload))
            .await
            .map_err(|err| ClipboardError::Wayland(format!("clipboard worker failed: {err}")))?
    }
}

pub fn default_spool_dir() -> PathBuf {
    spool_dir_from_env(std::env::var_os("XDG_RUNTIME_DIR").as_deref())
}

fn spool_dir_from_env(runtime_dir: Option<&std::ffi::OsStr>) -> PathBuf {
    if let Some(runtime_dir) = runtime_dir.filter(|v| !v.is_empty()) {
        PathBuf::from(runtime_dir).join("controid").join("spool")
    } else {
        let uid = nix::unistd::Uid::current().as_raw();
        std::env::temp_dir()
            .join(format!("controid-{uid}"))
            .join("spool")
    }
}

pub fn is_text_mime(mime: &str) -> bool {
    mime.starts_with("text/") || matches!(mime, "UTF8_STRING" | "STRING" | "TEXT")
}

pub fn select_preferred_mime(offered: &[String]) -> Option<(&str, &'static str)> {
    for (candidate, canonical) in [
        (MIME_TEXT_UTF8, MIME_TEXT_UTF8),
        (MIME_PNG, MIME_PNG),
        (MIME_JPEG, MIME_JPEG),
        ("image/webp", "image/webp"),
        ("text/plain", MIME_TEXT_UTF8),
        ("UTF8_STRING", MIME_TEXT_UTF8),
        ("STRING", MIME_TEXT_UTF8),
    ] {
        if let Some(found) = offered.iter().find(|m| m.as_str() == candidate) {
            return Some((found.as_str(), canonical));
        }
    }
    None
}

pub fn compute_payload_echo_hash(mime: &str, data: &[u8]) -> [u8; 32] {
    if is_text_mime(mime) {
        compute_clipboard_echo_hash(true, data.len() as u64, data, &[])
    } else {
        let head = &data[..data.len().min(ECHO_SLICE_LEN)];
        let tail = &data[data.len().saturating_sub(ECHO_SLICE_LEN)..];
        compute_clipboard_echo_hash(false, data.len() as u64, head, tail)
    }
}

fn append_tail_slice(tail: &mut Vec<u8>, chunk: &[u8]) {
    if chunk.len() >= ECHO_SLICE_LEN {
        tail.clear();
        tail.extend_from_slice(&chunk[chunk.len() - ECHO_SLICE_LEN..]);
    } else {
        tail.extend_from_slice(chunk);
        if tail.len() > ECHO_SLICE_LEN {
            let excess = tail.len() - ECHO_SLICE_LEN;
            tail.drain(..excess);
        }
    }
}

fn create_spool_file(spool_dir: &Path) -> io::Result<(File, PathBuf)> {
    fs::create_dir_all(spool_dir)?;
    let _ = fs::set_permissions(spool_dir, fs::Permissions::from_mode(0o700));

    let pid = std::process::id();
    loop {
        let seq = SPOOL_SEQ.fetch_add(1, Ordering::Relaxed);
        let path = spool_dir.join(format!("clip-{pid}-{seq}.spool"));
        match OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&path)
        {
            Ok(file) => return Ok((file, path)),
            Err(err) if err.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(err) => return Err(err),
        }
    }
}

struct ClipboardAccumulator {
    mime: String,
    spool_dir: PathBuf,
    ram_threshold: usize,
    max_bytes: u64,
    ram_buf: Vec<u8>,
    head_buf: Vec<u8>,
    tail_buf: Vec<u8>,
    spool: Option<(File, PathBuf)>,
    total_len: u64,
}

impl ClipboardAccumulator {
    fn new(mime: String, spool_dir: PathBuf, ram_threshold: usize, max_bytes: u64) -> Self {
        Self {
            mime,
            spool_dir,
            ram_threshold,
            max_bytes,
            ram_buf: Vec::new(),
            head_buf: Vec::with_capacity(ECHO_SLICE_LEN),
            tail_buf: Vec::with_capacity(ECHO_SLICE_LEN * 2),
            spool: None,
            total_len: 0,
        }
    }

    fn cleanup_spool(&mut self) {
        if let Some((file, path)) = self.spool.take() {
            drop(file);
            let _ = fs::remove_file(path);
        }
    }

    fn push_chunk(&mut self, slice: &[u8]) -> Result<(), ClipboardError> {
        if slice.is_empty() {
            return Ok(());
        }
        let next_total = self.total_len.saturating_add(slice.len() as u64);
        if next_total > self.max_bytes {
            self.cleanup_spool();
            return Err(ClipboardError::Io(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("clipboard payload exceeds maximum limit of {} bytes", self.max_bytes),
            )));
        }
        self.total_len = next_total;

        if self.head_buf.len() < ECHO_SLICE_LEN {
            let need = ECHO_SLICE_LEN - self.head_buf.len();
            self.head_buf
                .extend_from_slice(&slice[..slice.len().min(need)]);
        }
        append_tail_slice(&mut self.tail_buf, slice);

        if let Some((file, _)) = self.spool.as_mut() {
            if let Err(err) = file.write_all(slice) {
                self.cleanup_spool();
                return Err(ClipboardError::Io(err));
            }
        } else if self.ram_buf.len() + slice.len() <= self.ram_threshold {
            self.ram_buf.extend_from_slice(slice);
        } else {
            let (mut file, path) = create_spool_file(&self.spool_dir)?;
            if let Err(err) = file
                .write_all(&self.ram_buf)
                .and_then(|()| file.write_all(slice))
            {
                drop(file);
                let _ = fs::remove_file(&path);
                return Err(ClipboardError::Io(err));
            }
            self.ram_buf = Vec::new();
            self.spool = Some((file, path));
        }

        Ok(())
    }

    fn finish(mut self) -> Result<ClipboardEvent, ClipboardError> {
        if let Some((mut file, path)) = self.spool.take() {
            file.flush()?;
            drop(file);
            let echo_hash = compute_clipboard_echo_hash(
                false,
                self.total_len,
                &self.head_buf,
                &self.tail_buf,
            );
            Ok(ClipboardEvent {
                payload: ClipboardPayload::SpoolFile {
                    mime: self.mime,
                    path,
                    total_len: self.total_len,
                },
                echo_hash,
            })
        } else {
            let echo_hash = compute_payload_echo_hash(&self.mime, &self.ram_buf);
            Ok(ClipboardEvent {
                payload: ClipboardPayload::Memory {
                    mime: self.mime,
                    data: self.ram_buf,
                },
                echo_hash,
            })
        }
    }
}

pub(crate) fn read_stream_to_event<R: Read>(
    mut reader: R,
    mime: String,
    spool_dir: &Path,
    ram_threshold: usize,
    max_bytes: u64,
    mut abort_check: impl FnMut() -> bool,
) -> Result<Option<ClipboardEvent>, ClipboardError> {
    let mut chunk = vec![0u8; STREAM_CHUNK_SIZE];
    let mut acc = ClipboardAccumulator::new(mime, spool_dir.to_path_buf(), ram_threshold, max_bytes);

    loop {
        if abort_check() {
            acc.cleanup_spool();
            return Ok(None);
        }

        let n = match reader.read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => n,
            Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
            Err(err) => {
                acc.cleanup_spool();
                return Err(ClipboardError::Io(err));
            }
        };

        acc.push_chunk(&chunk[..n])?;
    }

    acc.finish().map(Some)
}

#[derive(Clone)]
enum CopyPayloadSource {
    Bytes(Arc<Vec<u8>>),
    File(Arc<File>),
}

impl CopyPayloadSource {
    fn total_len(&self) -> io::Result<u64> {
        match self {
            Self::Bytes(b) => Ok(b.len() as u64),
            Self::File(f) => Ok(f.metadata()?.len()),
        }
    }

    fn read_slice(&self, offset: u64, buf: &mut [u8]) -> io::Result<usize> {
        match self {
            Self::Bytes(bytes) => {
                let Ok(off) = usize::try_from(offset) else {
                    return Ok(0);
                };
                if off >= bytes.len() {
                    return Ok(0);
                }
                let end = (off + buf.len()).min(bytes.len());
                let n = end - off;
                buf[..n].copy_from_slice(&bytes[off..end]);
                Ok(n)
            }
            Self::File(file) => loop {
                match file.read_at(buf, offset) {
                    Ok(n) => return Ok(n),
                    Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
                    Err(err) => return Err(err),
                }
            },
        }
    }
}

fn write_clipboard_blocking(payload: ClipboardPayload) -> Result<(), ClipboardError> {
    let (mime, source) = match payload {
        ClipboardPayload::Memory { mime, data } => (mime, CopyPayloadSource::Bytes(Arc::new(data))),
        ClipboardPayload::SpoolFile {
            mime,
            path,
            total_len: _,
        } => {
            let file = Arc::new(File::open(&path)?);
            (mime, CopyPayloadSource::File(file))
        }
    };

    match detect_clipboard_backend() {
        ClipboardBackendKind::WaylandExtDataControl | ClipboardBackendKind::WaylandWlrDataControl => {
            serve_data_control_clipboard(mime, source)
        }
        ClipboardBackendKind::X11 => serve_x11_clipboard(mime, source),
        ClipboardBackendKind::Unavailable => Err(ClipboardError::Unavailable(
            "no Wayland data-control or X11 display available".to_owned(),
        )),
    }
}

fn build_offered_mimes(base_mime: &str) -> Vec<String> {
    let mut mimes = vec![base_mime.to_owned()];
    if is_text_mime(base_mime) {
        for alias in [
            MIME_TEXT_UTF8,
            "text/plain",
            "UTF8_STRING",
            "STRING",
            "TEXT",
        ] {
            if !mimes.iter().any(|m| m == alias) {
                mimes.push(alias.to_owned());
            }
        }
    }
    mimes
}

pub(crate) fn stream_file_to_writer(file: &File, writer: &mut impl Write) -> io::Result<()> {
    let mut buf = vec![0u8; STREAM_CHUNK_SIZE];
    let mut offset: u64 = 0;

    loop {
        match file.read_at(&mut buf, offset) {
            Ok(0) => return writer.flush(),
            Ok(n) => {
                offset += n as u64;
                if let Err(err) = writer.write_all(&buf[..n]) {
                    if err.kind() == io::ErrorKind::BrokenPipe {
                        return Ok(());
                    }
                    return Err(err);
                }
            }
            Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
            Err(err) => return Err(err),
        }
    }
}

fn stream_bytes_to_writer(bytes: &[u8], writer: &mut impl Write) -> io::Result<()> {
    for chunk in bytes.chunks(STREAM_CHUNK_SIZE) {
        if let Err(err) = writer.write_all(chunk) {
            if err.kind() == io::ErrorKind::BrokenPipe {
                return Ok(());
            }
            return Err(err);
        }
    }
    if let Err(err) = writer.flush()
        && err.kind() != io::ErrorKind::BrokenPipe
    {
        return Err(err);
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Wayland Data Control (ext_data_control_v1 + zwlr_data_control_v1)
// ---------------------------------------------------------------------------

#[derive(Clone)]
enum DataControlManager {
    Ext(ExtDataControlManagerV1),
    Wlr(ZwlrDataControlManagerV1),
}

#[derive(Clone)]
enum DataControlDevice {
    Ext(ExtDataControlDeviceV1),
    Wlr(ZwlrDataControlDeviceV1),
}

#[derive(Clone)]
enum DataControlSource {
    Ext(ExtDataControlSourceV1),
    Wlr(ZwlrDataControlSourceV1),
}

#[derive(Clone, PartialEq, Eq, Hash)]
enum DataControlOffer {
    Ext(ExtDataControlOfferV1),
    Wlr(ZwlrDataControlOfferV1),
}

impl DataControlManager {
    fn bind<D>(
        globals: &wayland_client::globals::GlobalList,
        qh: &QueueHandle<D>,
    ) -> Result<Self, ClipboardError>
    where
        D: Dispatch<ExtDataControlManagerV1, ()>
            + Dispatch<ZwlrDataControlManagerV1, ()>
            + 'static,
    {
        if let Ok(ext) = globals.bind::<ExtDataControlManagerV1, _, _>(qh, 1..=1, ()) {
            return Ok(Self::Ext(ext));
        }
        if let Ok(wlr) = globals.bind::<ZwlrDataControlManagerV1, _, _>(qh, 1..=2, ()) {
            return Ok(Self::Wlr(wlr));
        }
        Err(ClipboardError::Wayland(
            "neither ext_data_control_manager_v1 nor zwlr_data_control_manager_v1 exposed"
                .to_owned(),
        ))
    }

    fn get_data_device<D, U>(&self, seat: &WlSeat, qh: &QueueHandle<D>, udata: U) -> DataControlDevice
    where
        D: Dispatch<ExtDataControlDeviceV1, U> + Dispatch<ZwlrDataControlDeviceV1, U> + 'static,
        U: Send + Sync + 'static,
    {
        match self {
            Self::Ext(m) => DataControlDevice::Ext(m.get_data_device(seat, qh, udata)),
            Self::Wlr(m) => DataControlDevice::Wlr(m.get_data_device(seat, qh, udata)),
        }
    }

    fn create_data_source<D>(&self, qh: &QueueHandle<D>) -> DataControlSource
    where
        D: Dispatch<ExtDataControlSourceV1, ()> + Dispatch<ZwlrDataControlSourceV1, ()> + 'static,
    {
        match self {
            Self::Ext(m) => DataControlSource::Ext(m.create_data_source(qh, ())),
            Self::Wlr(m) => DataControlSource::Wlr(m.create_data_source(qh, ())),
        }
    }
}

impl DataControlDevice {
    fn destroy(&self) {
        match self {
            Self::Ext(d) => d.destroy(),
            Self::Wlr(d) => d.destroy(),
        }
    }

    fn set_selection(&self, source: Option<&DataControlSource>) {
        match (self, source) {
            (Self::Ext(d), Some(DataControlSource::Ext(s))) => d.set_selection(Some(s)),
            (Self::Ext(d), None) => d.set_selection(None),
            (Self::Wlr(d), Some(DataControlSource::Wlr(s))) => d.set_selection(Some(s)),
            (Self::Wlr(d), None) => d.set_selection(None),
            _ => {}
        }
    }
}

impl DataControlSource {
    fn offer(&self, mime_type: String) {
        match self {
            Self::Ext(s) => s.offer(mime_type),
            Self::Wlr(s) => s.offer(mime_type),
        }
    }

    fn is_alive(&self) -> bool {
        match self {
            Self::Ext(s) => s.is_alive(),
            Self::Wlr(s) => s.is_alive(),
        }
    }
}

impl DataControlOffer {
    fn destroy(&self) {
        match self {
            Self::Ext(o) => o.destroy(),
            Self::Wlr(o) => o.destroy(),
        }
    }
}

fn serve_data_control_clipboard(
    mime: String,
    data: CopyPayloadSource,
) -> Result<(), ClipboardError> {
    let (tx, rx) = std_mpsc::sync_channel::<Result<(), ClipboardError>>(1);

    thread::Builder::new()
        .name("wayland-data-control-copy".to_owned())
        .spawn(move || {
            let offered_mimes = build_offered_mimes(&mime);
            let setup = prepare_data_control_copy_state(offered_mimes, data);
            match setup {
                Ok((mut queue, mut state)) => {
                    let _ = tx.send(Ok(()));
                    while state.sources.iter().any(DataControlSource::is_alive) {
                        if let Err(err) = queue.blocking_dispatch(&mut state) {
                            debug!(?err, "wayland clipboard server stopped");
                            break;
                        }
                    }
                }
                Err(err) => {
                    let _ = tx.send(Err(err));
                }
            }
        })
        .map_err(ClipboardError::Io)?;

    rx.recv()
        .map_err(|_| ClipboardError::Wayland("clipboard server terminated".to_owned()))?
}

struct DataControlCopyState {
    devices: Vec<DataControlDevice>,
    sources: Vec<DataControlSource>,
    offered_mimes: Vec<String>,
    data: CopyPayloadSource,
}

fn prepare_data_control_copy_state(
    offered_mimes: Vec<String>,
    data: CopyPayloadSource,
) -> Result<(wayland_client::EventQueue<DataControlCopyState>, DataControlCopyState), ClipboardError>
{
    let conn =
        Connection::connect_to_env().map_err(|err| ClipboardError::Wayland(err.to_string()))?;
    let (globals, mut queue) = registry_queue_init::<DataControlCopyState>(&conn)
        .map_err(|err| ClipboardError::Wayland(err.to_string()))?;
    let qh = queue.handle();

    let manager = DataControlManager::bind(&globals, &qh)?;

    let registry = globals.registry();
    let seats: Vec<WlSeat> = globals.contents().with_list(|list| {
        list.iter()
            .filter(|g| g.interface == WlSeat::interface().name)
            .map(|g| registry.bind(g.name, g.version.min(2), &qh, ()))
            .collect()
    });

    if seats.is_empty() {
        return Err(ClipboardError::Wayland(
            "no wayland seats available".to_owned(),
        ));
    }

    let devices: Vec<DataControlDevice> = seats
        .iter()
        .map(|seat| manager.get_data_device(seat, &qh, ()))
        .collect();

    let mut state = DataControlCopyState {
        devices,
        sources: Vec::new(),
        offered_mimes,
        data,
    };

    queue
        .roundtrip(&mut state)
        .map_err(|err| ClipboardError::Wayland(err.to_string()))?;

    for device in &state.devices {
        let source = manager.create_data_source(&qh);
        for mime in &state.offered_mimes {
            source.offer(mime.clone());
        }
        device.set_selection(Some(&source));
        state.sources.push(source);
    }

    queue
        .roundtrip(&mut state)
        .map_err(|err| ClipboardError::Wayland(err.to_string()))?;

    Ok((queue, state))
}

impl DataControlCopyState {
    fn handle_source_send(&self, mime_type: &str, fd: std::os::fd::OwnedFd) {
        if !self.offered_mimes.iter().any(|m| m == mime_type) {
            return;
        }
        if let Err(err) = fcntl(&fd, FcntlArg::F_SETFL(OFlag::empty())) {
            warn!(?err, "failed to clear O_NONBLOCK on clipboard target pipe");
            return;
        }

        let data = self.data.clone();
        if let Err(err) = thread::Builder::new()
            .name("wayland-clipboard-writer".to_owned())
            .spawn(move || {
                let mut out = File::from(fd);
                let res = match &data {
                    CopyPayloadSource::Bytes(bytes) => stream_bytes_to_writer(bytes, &mut out),
                    CopyPayloadSource::File(file) => stream_file_to_writer(file, &mut out),
                };
                if let Err(err) = res {
                    debug!(?err, "clipboard pipe write aborted");
                }
            })
        {
            warn!(?err, "failed to spawn clipboard pipe writer thread");
        }
    }
}

impl Dispatch<WlRegistry, GlobalListContents> for DataControlCopyState {
    fn event(
        _state: &mut Self,
        _proxy: &WlRegistry,
        _event: wl_registry::Event,
        _data: &GlobalListContents,
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
    }
}

impl Dispatch<WlSeat, ()> for DataControlCopyState {
    fn event(
        _state: &mut Self,
        _proxy: &WlSeat,
        _event: wl_seat::Event,
        _data: &(),
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
    }
}

impl Dispatch<ZwlrDataControlManagerV1, ()> for DataControlCopyState {
    fn event(
        _state: &mut Self,
        _proxy: &ZwlrDataControlManagerV1,
        _event: <ZwlrDataControlManagerV1 as Proxy>::Event,
        _data: &(),
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
    }
}

impl Dispatch<ExtDataControlManagerV1, ()> for DataControlCopyState {
    fn event(
        _state: &mut Self,
        _proxy: &ExtDataControlManagerV1,
        _event: <ExtDataControlManagerV1 as Proxy>::Event,
        _data: &(),
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
    }
}

impl Dispatch<ZwlrDataControlDeviceV1, ()> for DataControlCopyState {
    fn event(
        _state: &mut Self,
        _proxy: &ZwlrDataControlDeviceV1,
        event: zwlr_data_control_device_v1::Event,
        _data: &(),
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
        if let zwlr_data_control_device_v1::Event::DataOffer { id } = event {
            id.destroy();
        }
    }

    event_created_child!(DataControlCopyState, ZwlrDataControlDeviceV1, [
        WLR_EVT_DATA_OFFER_OPCODE => (ZwlrDataControlOfferV1, ()),
    ]);
}

impl Dispatch<ExtDataControlDeviceV1, ()> for DataControlCopyState {
    fn event(
        _state: &mut Self,
        _proxy: &ExtDataControlDeviceV1,
        event: ext_data_control_device_v1::Event,
        _data: &(),
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
        if let ext_data_control_device_v1::Event::DataOffer { id } = event {
            id.destroy();
        }
    }

    event_created_child!(DataControlCopyState, ExtDataControlDeviceV1, [
        EXT_EVT_DATA_OFFER_OPCODE => (ExtDataControlOfferV1, ()),
    ]);
}

impl Dispatch<ZwlrDataControlOfferV1, ()> for DataControlCopyState {
    fn event(
        _state: &mut Self,
        _proxy: &ZwlrDataControlOfferV1,
        _event: zwlr_data_control_offer_v1::Event,
        _data: &(),
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
    }
}

impl Dispatch<ExtDataControlOfferV1, ()> for DataControlCopyState {
    fn event(
        _state: &mut Self,
        _proxy: &ExtDataControlOfferV1,
        _event: ext_data_control_offer_v1::Event,
        _data: &(),
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
    }
}

impl Dispatch<ZwlrDataControlSourceV1, ()> for DataControlCopyState {
    fn event(
        state: &mut Self,
        source: &ZwlrDataControlSourceV1,
        event: zwlr_data_control_source_v1::Event,
        _data: &(),
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
        match event {
            zwlr_data_control_source_v1::Event::Send { mime_type, fd } => {
                state.handle_source_send(&mime_type, fd);
            }
            zwlr_data_control_source_v1::Event::Cancelled => {
                source.destroy();
            }
            _ => {}
        }
    }
}

impl Dispatch<ExtDataControlSourceV1, ()> for DataControlCopyState {
    fn event(
        state: &mut Self,
        source: &ExtDataControlSourceV1,
        event: ext_data_control_source_v1::Event,
        _data: &(),
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
        match event {
            ext_data_control_source_v1::Event::Send { mime_type, fd } => {
                state.handle_source_send(&mime_type, fd);
            }
            ext_data_control_source_v1::Event::Cancelled => {
                source.destroy();
            }
            _ => {}
        }
    }
}

struct SeatEntry {
    registry_name: u32,
    name: Option<String>,
    device: Option<DataControlDevice>,
    current_offer: Option<DataControlOffer>,
}

struct WatcherState {
    manager: DataControlManager,
    seats: HashMap<WlSeat, SeatEntry>,
    pending_offers: HashMap<DataControlOffer, Vec<String>>,
    tx: mpsc::Sender<ClipboardEvent>,
    generation: Arc<AtomicU64>,
    spool_dir: PathBuf,
}

impl WatcherState {
    fn on_data_offer(&mut self, offer: DataControlOffer) {
        self.pending_offers.insert(offer, Vec::new());
    }

    fn on_selection(&mut self, seat: &WlSeat, id: Option<DataControlOffer>) {
        let Some(seat_entry) = self.seats.get_mut(seat) else {
            if let Some(offer) = id {
                self.pending_offers.remove(&offer);
                offer.destroy();
            }
            return;
        };

        if let Some(old) = seat_entry.current_offer.take() {
            old.destroy();
        }

        let Some(offer) = id else {
            return;
        };

        let offered = self.pending_offers.remove(&offer).unwrap_or_default();
        seat_entry.current_offer = Some(offer);

        let Some((req_mime, canonical_mime)) = select_preferred_mime(&offered) else {
            return;
        };

        let req_mime = req_mime.to_owned();
        let canonical_mime = canonical_mime.to_owned();
        let seat_name = seat_entry.name.clone();
        let expected_gen = self.generation.fetch_add(1, Ordering::SeqCst) + 1;
        let generation = Arc::clone(&self.generation);
        let tx = self.tx.clone();
        let spool_dir = self.spool_dir.clone();

        if let Err(err) = thread::Builder::new()
            .name("wayland-clipboard-reader".to_owned())
            .spawn(move || {
                let paste_seat = match seat_name.as_deref() {
                    Some(name) => wl_clipboard_rs::paste::Seat::Specific(name),
                    None => wl_clipboard_rs::paste::Seat::Unspecified,
                };

                let (pipe, _) = match wl_clipboard_rs::paste::get_contents(
                    wl_clipboard_rs::paste::ClipboardType::Regular,
                    paste_seat,
                    wl_clipboard_rs::paste::MimeType::Specific(&req_mime),
                ) {
                    Ok(res) => res,
                    Err(
                        wl_clipboard_rs::paste::Error::ClipboardEmpty
                        | wl_clipboard_rs::paste::Error::NoMimeType
                        | wl_clipboard_rs::paste::Error::NoSeats,
                    ) => return,
                    Err(err) => {
                        debug!(?err, mime = %req_mime, "failed to paste clipboard offer");
                        return;
                    }
                };

                match read_stream_to_event(
                    pipe,
                    canonical_mime,
                    &spool_dir,
                    RAM_THRESHOLD_BYTES,
                    MAX_CLIPBOARD_BYTES,
                    || generation.load(Ordering::SeqCst) != expected_gen || tx.is_closed(),
                ) {
                    Ok(Some(event)) => {
                        if generation.load(Ordering::SeqCst) != expected_gen {
                            if let ClipboardPayload::SpoolFile { path, .. } = event.payload {
                                let _ = fs::remove_file(path);
                            }
                            return;
                        }
                        let _ = tx.blocking_send(event);
                    }
                    Ok(None) => {}
                    Err(err) => {
                        warn!(?err, "failed reading clipboard pipe stream");
                    }
                }
            })
        {
            warn!(?err, "failed to spawn clipboard reader thread");
        }
    }

    fn on_primary_selection(&mut self, offer: Option<DataControlOffer>) {
        if let Some(offer) = offer {
            self.pending_offers.remove(&offer);
            offer.destroy();
        }
    }

    fn on_finished(&mut self, seat: &WlSeat) {
        if let Some(entry) = self.seats.get_mut(seat) {
            if let Some(offer) = entry.current_offer.take() {
                offer.destroy();
            }
            if let Some(device) = entry.device.take() {
                device.destroy();
            }
        }
    }
}

fn run_wayland_watcher(tx: mpsc::Sender<ClipboardEvent>) -> Result<(), ClipboardError> {
    let conn =
        Connection::connect_to_env().map_err(|err| ClipboardError::Wayland(err.to_string()))?;
    let (globals, mut queue) = registry_queue_init::<WatcherState>(&conn)
        .map_err(|err| ClipboardError::Wayland(err.to_string()))?;
    let qh = queue.handle();

    let manager = match DataControlManager::bind(&globals, &qh) {
        Ok(m) => m,
        Err(err) => {
            warn!(?err, "Wayland data-control protocol unavailable");
            return Err(err);
        }
    };

    let registry = globals.registry();
    let seats: HashMap<WlSeat, SeatEntry> = globals.contents().with_list(|list| {
        list.iter()
            .filter(|g| g.interface == WlSeat::interface().name)
            .map(|g| {
                let seat: WlSeat = registry.bind(g.name, g.version.min(2), &qh, ());
                let device = manager.get_data_device(&seat, &qh, seat.clone());
                (
                    seat,
                    SeatEntry {
                        registry_name: g.name,
                        name: None,
                        device: Some(device),
                        current_offer: None,
                    },
                )
            })
            .collect()
    });

    let mut state = WatcherState {
        manager,
        seats,
        pending_offers: HashMap::new(),
        tx,
        generation: Arc::new(AtomicU64::new(0)),
        spool_dir: default_spool_dir(),
    };

    loop {
        if state.tx.is_closed() {
            break;
        }

        if let Err(err) = queue.blocking_dispatch(&mut state) {
            warn!(?err, "wayland clipboard watcher disconnected");
            break;
        }
    }

    Ok(())
}

impl Dispatch<WlRegistry, GlobalListContents> for WatcherState {
    fn event(
        state: &mut Self,
        registry: &WlRegistry,
        event: wl_registry::Event,
        _data: &GlobalListContents,
        _conn: &Connection,
        qh: &QueueHandle<Self>,
    ) {
        match event {
            wl_registry::Event::Global {
                name,
                interface,
                version,
            } if interface == WlSeat::interface().name => {
                let seat: WlSeat = registry.bind(name, version.min(2), qh, ());
                let device = state.manager.get_data_device(&seat, qh, seat.clone());
                state.seats.insert(
                    seat,
                    SeatEntry {
                        registry_name: name,
                        name: None,
                        device: Some(device),
                        current_offer: None,
                    },
                );
            }
            wl_registry::Event::GlobalRemove { name } => {
                if let Some(seat_key) = state
                    .seats
                    .iter()
                    .find_map(|(seat, entry)| (entry.registry_name == name).then(|| seat.clone()))
                    && let Some(mut entry) = state.seats.remove(&seat_key)
                {
                    if let Some(offer) = entry.current_offer.take() {
                        offer.destroy();
                    }
                    if let Some(device) = entry.device.take() {
                        device.destroy();
                    }
                }
            }
            _ => {}
        }
    }
}

impl Dispatch<WlSeat, ()> for WatcherState {
    fn event(
        state: &mut Self,
        seat: &WlSeat,
        event: wl_seat::Event,
        _data: &(),
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
        if let wl_seat::Event::Name { name } = event
            && let Some(entry) = state.seats.get_mut(seat)
        {
            entry.name = Some(name);
        }
    }
}

impl Dispatch<ZwlrDataControlManagerV1, ()> for WatcherState {
    fn event(
        _state: &mut Self,
        _proxy: &ZwlrDataControlManagerV1,
        _event: <ZwlrDataControlManagerV1 as Proxy>::Event,
        _data: &(),
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
    }
}

impl Dispatch<ExtDataControlManagerV1, ()> for WatcherState {
    fn event(
        _state: &mut Self,
        _proxy: &ExtDataControlManagerV1,
        _event: <ExtDataControlManagerV1 as Proxy>::Event,
        _data: &(),
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
    }
}

impl Dispatch<ZwlrDataControlDeviceV1, WlSeat> for WatcherState {
    fn event(
        state: &mut Self,
        _proxy: &ZwlrDataControlDeviceV1,
        event: zwlr_data_control_device_v1::Event,
        seat: &WlSeat,
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
        match event {
            zwlr_data_control_device_v1::Event::DataOffer { id } => {
                state.on_data_offer(DataControlOffer::Wlr(id));
            }
            zwlr_data_control_device_v1::Event::Selection { id } => {
                state.on_selection(seat, id.map(DataControlOffer::Wlr));
            }
            zwlr_data_control_device_v1::Event::PrimarySelection { id } => {
                state.on_primary_selection(id.map(DataControlOffer::Wlr));
            }
            zwlr_data_control_device_v1::Event::Finished => {
                state.on_finished(seat);
            }
            _ => {}
        }
    }

    event_created_child!(WatcherState, ZwlrDataControlDeviceV1, [
        WLR_EVT_DATA_OFFER_OPCODE => (ZwlrDataControlOfferV1, ()),
    ]);
}

impl Dispatch<ExtDataControlDeviceV1, WlSeat> for WatcherState {
    fn event(
        state: &mut Self,
        _proxy: &ExtDataControlDeviceV1,
        event: ext_data_control_device_v1::Event,
        seat: &WlSeat,
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
        match event {
            ext_data_control_device_v1::Event::DataOffer { id } => {
                state.on_data_offer(DataControlOffer::Ext(id));
            }
            ext_data_control_device_v1::Event::Selection { id } => {
                state.on_selection(seat, id.map(DataControlOffer::Ext));
            }
            ext_data_control_device_v1::Event::PrimarySelection { id } => {
                state.on_primary_selection(id.map(DataControlOffer::Ext));
            }
            ext_data_control_device_v1::Event::Finished => {
                state.on_finished(seat);
            }
            _ => {}
        }
    }

    event_created_child!(WatcherState, ExtDataControlDeviceV1, [
        EXT_EVT_DATA_OFFER_OPCODE => (ExtDataControlOfferV1, ()),
    ]);
}

impl Dispatch<ZwlrDataControlOfferV1, ()> for WatcherState {
    fn event(
        state: &mut Self,
        offer: &ZwlrDataControlOfferV1,
        event: zwlr_data_control_offer_v1::Event,
        _data: &(),
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
        if let zwlr_data_control_offer_v1::Event::Offer { mime_type } = event
            && let Some(mimes) = state
                .pending_offers
                .get_mut(&DataControlOffer::Wlr(offer.clone()))
        {
            mimes.push(mime_type);
        }
    }
}

impl Dispatch<ExtDataControlOfferV1, ()> for WatcherState {
    fn event(
        state: &mut Self,
        offer: &ExtDataControlOfferV1,
        event: ext_data_control_offer_v1::Event,
        _data: &(),
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
        if let ext_data_control_offer_v1::Event::Offer { mime_type } = event
            && let Some(mimes) = state
                .pending_offers
                .get_mut(&DataControlOffer::Ext(offer.clone()))
        {
            mimes.push(mime_type);
        }
    }
}

// ---------------------------------------------------------------------------
// X11 Clipboard Backend (x11rb + XFixes SelectionNotify + INCR support)
// ---------------------------------------------------------------------------

x11rb::atom_manager! {
    X11Atoms: X11AtomsCookie {
        CLIPBOARD,
        TARGETS,
        TIMESTAMP,
        INCR,
        UTF8_STRING,
        STRING,
        TEXT,
        CONTROID_CLIP_PROP,
        MIME_TEXT_UTF8: b"text/plain;charset=utf-8",
        MIME_TEXT_PLAIN: b"text/plain",
        MIME_IMAGE_PNG: b"image/png",
        MIME_IMAGE_JPEG: b"image/jpeg",
        MIME_IMAGE_WEBP: b"image/webp",
    }
}

fn poll_x11_fd(raw_fd: std::os::unix::io::RawFd, timeout_ms: i32) -> io::Result<bool> {
    let mut pfd = libc::pollfd {
        fd: raw_fd,
        events: libc::POLLIN,
        revents: 0,
    };
    loop {
        let rc = unsafe { libc::poll(&mut pfd, 1, timeout_ms) };
        if rc < 0 {
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(err);
        }
        return Ok(rc > 0);
    }
}

fn wait_x11_event_timeout(
    conn: &RustConnection,
    timeout: Duration,
) -> Result<Option<X11Event>, ClipboardError> {
    if let Some(ev) = conn
        .poll_for_event()
        .map_err(|e| ClipboardError::X11(e.to_string()))?
    {
        return Ok(Some(ev));
    }

    let deadline = Instant::now() + timeout;
    let fd = conn.stream().as_raw_fd();
    loop {
        let now = Instant::now();
        if now >= deadline {
            return Ok(None);
        }
        let rem_ms = (deadline - now).as_millis().min(500) as i32;
        if poll_x11_fd(fd, rem_ms)?
            && let Some(ev) = conn
                .poll_for_event()
                .map_err(|e| ClipboardError::X11(e.to_string()))?
        {
            return Ok(Some(ev));
        }
    }
}

fn create_x11_hidden_window(
    conn: &RustConnection,
    root: Window,
    event_mask: EventMask,
) -> Result<Window, ClipboardError> {
    let win = conn
        .generate_id()
        .map_err(|e| ClipboardError::X11(e.to_string()))?;
    conn.create_window(
        0,
        win,
        root,
        0,
        0,
        1,
        1,
        0,
        WindowClass::INPUT_ONLY,
        x11rb::COPY_FROM_PARENT,
        &CreateWindowAux::new().event_mask(event_mask),
    )
    .map_err(|e| ClipboardError::X11(e.to_string()))?
    .check()
    .map_err(|e| ClipboardError::X11(e.to_string()))?;
    Ok(win)
}

fn run_x11_watcher(tx: mpsc::Sender<ClipboardEvent>) -> Result<(), ClipboardError> {
    let (conn, screen_num) =
        RustConnection::connect(None).map_err(|e| ClipboardError::X11(e.to_string()))?;
    let atoms = X11Atoms::new(&conn)
        .map_err(|e| ClipboardError::X11(e.to_string()))?
        .reply()
        .map_err(|e| ClipboardError::X11(e.to_string()))?;

    let fixes = xfixes::query_version(&conn, 5, 0)
        .map_err(|e| ClipboardError::X11(e.to_string()))?
        .reply()
        .map_err(|e| ClipboardError::X11(e.to_string()))?;
    if fixes.major_version < 1 {
        return Err(ClipboardError::X11(
            "XFixes extension < 1.0 cannot monitor clipboard".to_owned(),
        ));
    }

    let root = conn.setup().roots[screen_num].root;
    let win = create_x11_hidden_window(&conn, root, EventMask::NO_EVENT)?;

    conn.xfixes_select_selection_input(
        win,
        atoms.CLIPBOARD,
        SelectionEventMask::SET_SELECTION_OWNER,
    )
    .map_err(|e| ClipboardError::X11(e.to_string()))?
    .check()
    .map_err(|e| ClipboardError::X11(e.to_string()))?;
    conn.flush()
        .map_err(|e| ClipboardError::X11(e.to_string()))?;

    let generation = Arc::new(AtomicU64::new(0));
    let spool_dir = default_spool_dir();
    let fd = conn.stream().as_raw_fd();

    loop {
        if tx.is_closed() {
            break;
        }

        while let Some(ev) = conn
            .poll_for_event()
            .map_err(|e| ClipboardError::X11(e.to_string()))?
        {
            if let X11Event::XfixesSelectionNotify(sel_ev) = ev
                && sel_ev.selection == atoms.CLIPBOARD
                && sel_ev.owner != x11rb::NONE
                && sel_ev.owner != ACTIVE_X11_OWNER_WIN.load(Ordering::SeqCst)
            {
                let expected_gen = generation.fetch_add(1, Ordering::SeqCst) + 1;
                let generation_cl = Arc::clone(&generation);
                let tx_cl = tx.clone();
                let spool_cl = spool_dir.clone();

                if let Err(err) = thread::Builder::new()
                    .name("x11-clipboard-reader".to_owned())
                    .spawn(move || {
                        match read_x11_clipboard_once(&spool_cl, || {
                            generation_cl.load(Ordering::SeqCst) != expected_gen
                                || tx_cl.is_closed()
                        }) {
                            Ok(Some(event)) => {
                                if generation_cl.load(Ordering::SeqCst) != expected_gen {
                                    if let ClipboardPayload::SpoolFile { path, .. } = event.payload
                                    {
                                        let _ = fs::remove_file(path);
                                    }
                                    return;
                                }
                                let _ = tx_cl.blocking_send(event);
                            }
                            Ok(None) => {}
                            Err(err) => {
                                debug!(?err, "x11 clipboard read failed");
                            }
                        }
                    })
                {
                    warn!(?err, "failed to spawn x11 clipboard reader thread");
                }
            }
        }

        if tx.is_closed() {
            break;
        }
        let _ = poll_x11_fd(fd, 500)?;
    }

    let _ = conn.destroy_window(win);
    let _ = conn.flush();
    Ok(())
}

fn wait_x11_selection_notify(
    conn: &RustConnection,
    win: Window,
    selection: Atom,
    target: Atom,
    timeout: Duration,
) -> Result<Option<SelectionNotifyEvent>, ClipboardError> {
    let deadline = Instant::now() + timeout;
    loop {
        let now = Instant::now();
        if now >= deadline {
            return Ok(None);
        }
        let Some(ev) = wait_x11_event_timeout(conn, deadline - now)? else {
            return Ok(None);
        };
        if let X11Event::SelectionNotify(sn) = ev
            && sn.requestor == win
            && sn.selection == selection
            && sn.target == target
        {
            return Ok(Some(sn));
        }
    }
}

fn read_x11_clipboard_once(
    spool_dir: &Path,
    mut abort_check: impl FnMut() -> bool,
) -> Result<Option<ClipboardEvent>, ClipboardError> {
    let (conn, screen_num) =
        RustConnection::connect(None).map_err(|e| ClipboardError::X11(e.to_string()))?;
    let atoms = X11Atoms::new(&conn)
        .map_err(|e| ClipboardError::X11(e.to_string()))?
        .reply()
        .map_err(|e| ClipboardError::X11(e.to_string()))?;

    let root = conn.setup().roots[screen_num].root;
    let win = create_x11_hidden_window(&conn, root, EventMask::PROPERTY_CHANGE)?;

    let res = read_x11_clipboard_with_window(&conn, &atoms, win, spool_dir, &mut abort_check);
    let _ = conn.destroy_window(win);
    let _ = conn.flush();
    res
}

fn read_x11_clipboard_with_window(
    conn: &RustConnection,
    atoms: &X11Atoms,
    win: Window,
    spool_dir: &Path,
    abort_check: &mut impl FnMut() -> bool,
) -> Result<Option<ClipboardEvent>, ClipboardError> {
    if abort_check() {
        return Ok(None);
    }

    let _ = conn.delete_property(win, atoms.CONTROID_CLIP_PROP);
    conn.convert_selection(
        win,
        atoms.CLIPBOARD,
        atoms.TARGETS,
        atoms.CONTROID_CLIP_PROP,
        x11rb::CURRENT_TIME,
    )
    .map_err(|e| ClipboardError::X11(e.to_string()))?;
    conn.flush()
        .map_err(|e| ClipboardError::X11(e.to_string()))?;

    let Some(sn) =
        wait_x11_selection_notify(conn, win, atoms.CLIPBOARD, atoms.TARGETS, X11_SELECTION_TIMEOUT)?
    else {
        return Ok(None);
    };
    if sn.property == x11rb::NONE {
        return Ok(None);
    }

    let targets_prop = conn
        .get_property(
            true,
            win,
            atoms.CONTROID_CLIP_PROP,
            AtomEnum::ATOM,
            0,
            u32::MAX,
        )
        .map_err(|e| ClipboardError::X11(e.to_string()))?
        .reply()
        .map_err(|e| ClipboardError::X11(e.to_string()))?;

    let mut offered_atoms: Vec<(Atom, String)> = Vec::new();
    if let Some(iter) = targets_prop.value32() {
        for atom in iter {
            if abort_check() {
                return Ok(None);
            }
            if let Ok(cookie) = conn.get_atom_name(atom)
                && let Ok(reply) = cookie.reply()
            {
                let name = String::from_utf8_lossy(&reply.name).into_owned();
                offered_atoms.push((atom, name));
            }
        }
    }

    let offered_names: Vec<String> = offered_atoms.iter().map(|(_, n)| n.clone()).collect();
    let Some((req_mime, canonical_mime)) = select_preferred_mime(&offered_names) else {
        return Ok(None);
    };
    let req_atom = offered_atoms
        .iter()
        .find_map(|(atom, name)| (name == req_mime).then_some(*atom))
        .unwrap_or(atoms.UTF8_STRING);
    let canonical_mime = canonical_mime.to_owned();

    if abort_check() {
        return Ok(None);
    }

    let _ = conn.delete_property(win, atoms.CONTROID_CLIP_PROP);
    conn.convert_selection(
        win,
        atoms.CLIPBOARD,
        req_atom,
        atoms.CONTROID_CLIP_PROP,
        x11rb::CURRENT_TIME,
    )
    .map_err(|e| ClipboardError::X11(e.to_string()))?;
    conn.flush()
        .map_err(|e| ClipboardError::X11(e.to_string()))?;

    let Some(sn) =
        wait_x11_selection_notify(conn, win, atoms.CLIPBOARD, req_atom, X11_SELECTION_TIMEOUT)?
    else {
        return Ok(None);
    };
    if sn.property == x11rb::NONE {
        return Ok(None);
    }

    let prop = conn
        .get_property(
            false,
            win,
            atoms.CONTROID_CLIP_PROP,
            AtomEnum::ANY,
            0,
            u32::MAX,
        )
        .map_err(|e| ClipboardError::X11(e.to_string()))?
        .reply()
        .map_err(|e| ClipboardError::X11(e.to_string()))?;

    if prop.type_ != atoms.INCR {
        let _ = conn.delete_property(win, atoms.CONTROID_CLIP_PROP);
        let _ = conn.flush();
        return read_stream_to_event(
            io::Cursor::new(prop.value),
            canonical_mime,
            spool_dir,
            RAM_THRESHOLD_BYTES,
            MAX_CLIPBOARD_BYTES,
            abort_check,
        );
    }

    // Incremental (INCR) transfer for large clipboard payloads (e.g., high-res screenshots).
    conn.delete_property(win, atoms.CONTROID_CLIP_PROP)
        .map_err(|e| ClipboardError::X11(e.to_string()))?;
    conn.flush()
        .map_err(|e| ClipboardError::X11(e.to_string()))?;

    let mut acc = ClipboardAccumulator::new(
        canonical_mime,
        spool_dir.to_path_buf(),
        RAM_THRESHOLD_BYTES,
        MAX_CLIPBOARD_BYTES,
    );

    loop {
        if abort_check() {
            acc.cleanup_spool();
            return Ok(None);
        }

        let deadline = Instant::now() + X11_SELECTION_TIMEOUT;
        let mut got_new_value = false;
        while Instant::now() < deadline {
            if abort_check() {
                acc.cleanup_spool();
                return Ok(None);
            }
            let rem = deadline.saturating_duration_since(Instant::now());
            let Some(ev) = wait_x11_event_timeout(conn, rem)? else {
                break;
            };
            if let X11Event::PropertyNotify(pn) = ev
                && pn.window == win
                && pn.atom == atoms.CONTROID_CLIP_PROP
                && pn.state == xproto::Property::NEW_VALUE
            {
                got_new_value = true;
                break;
            }
        }
        if !got_new_value {
            acc.cleanup_spool();
            return Ok(None);
        }

        let chunk = conn
            .get_property(
                true,
                win,
                atoms.CONTROID_CLIP_PROP,
                AtomEnum::ANY,
                0,
                u32::MAX,
            )
            .map_err(|e| ClipboardError::X11(e.to_string()))?
            .reply()
            .map_err(|e| ClipboardError::X11(e.to_string()))?;
        let _ = conn.flush();

        if chunk.value.is_empty() {
            break;
        }
        acc.push_chunk(&chunk.value)?;
    }

    acc.finish().map(Some)
}

struct PendingX11Incr {
    data: CopyPayloadSource,
    target_atom: Atom,
    offset: u64,
}

fn acquire_x11_timestamp(
    conn: &RustConnection,
    win: Window,
    prop_atom: Atom,
) -> Result<u32, ClipboardError> {
    conn.change_property8(PropMode::REPLACE, win, prop_atom, AtomEnum::STRING, &[])
        .map_err(|e| ClipboardError::X11(e.to_string()))?;
    conn.flush()
        .map_err(|e| ClipboardError::X11(e.to_string()))?;

    let deadline = Instant::now() + Duration::from_secs(1);
    while Instant::now() < deadline {
        let rem = deadline.saturating_duration_since(Instant::now());
        if let Some(X11Event::PropertyNotify(pn)) = wait_x11_event_timeout(conn, rem)?
            && pn.window == win
            && pn.atom == prop_atom
        {
            return Ok(pn.time);
        }
    }
    Ok(x11rb::CURRENT_TIME)
}

fn send_x11_selection_notify(
    conn: &RustConnection,
    req: &xproto::SelectionRequestEvent,
    property: Atom,
) {
    let ev = SelectionNotifyEvent {
        response_type: SELECTION_NOTIFY_EVENT,
        sequence: 0,
        time: req.time,
        requestor: req.requestor,
        selection: req.selection,
        target: req.target,
        property,
    };
    let _ = conn.send_event(false, req.requestor, EventMask::NO_EVENT, ev);
    let _ = conn.flush();
}

fn serve_x11_clipboard(mime: String, data: CopyPayloadSource) -> Result<(), ClipboardError> {
    let (tx, rx) = std_mpsc::sync_channel::<Result<(), ClipboardError>>(1);

    thread::Builder::new()
        .name("x11-clipboard-copy".to_owned())
        .spawn(move || {
            let setup = prepare_x11_copy_state(&mime);
            let (conn, atoms, win, owner_ts, offered_atoms) = match setup {
                Ok(v) => {
                    let _ = tx.send(Ok(()));
                    v
                }
                Err(err) => {
                    let _ = tx.send(Err(err));
                    return;
                }
            };

            let mut incr_transfers: HashMap<(Window, Atom), PendingX11Incr> = HashMap::new();
            let mut incr_buf = vec![0u8; X11_INCR_CHUNK_SIZE];

            loop {
                let ev = match conn.wait_for_event() {
                    Ok(ev) => ev,
                    Err(err) => {
                        debug!(?err, "x11 clipboard owner connection closed");
                        break;
                    }
                };

                match ev {
                    X11Event::SelectionClear(sc) if sc.selection == atoms.CLIPBOARD => {
                        break;
                    }
                    X11Event::SelectionRequest(req) if req.selection == atoms.CLIPBOARD => {
                        let prop = if req.property == x11rb::NONE {
                            req.target
                        } else {
                            req.property
                        };

                        if req.target == atoms.TARGETS {
                            let mut targets = vec![atoms.TARGETS, atoms.TIMESTAMP];
                            for atom in &offered_atoms {
                                if !targets.contains(atom) {
                                    targets.push(*atom);
                                }
                            }
                            if conn
                                .change_property32(
                                    PropMode::REPLACE,
                                    req.requestor,
                                    prop,
                                    AtomEnum::ATOM,
                                    &targets,
                                )
                                .is_ok()
                            {
                                send_x11_selection_notify(&conn, &req, prop);
                            } else {
                                send_x11_selection_notify(&conn, &req, x11rb::NONE);
                            }
                        } else if req.target == atoms.TIMESTAMP {
                            if conn
                                .change_property32(
                                    PropMode::REPLACE,
                                    req.requestor,
                                    prop,
                                    AtomEnum::INTEGER,
                                    &[owner_ts],
                                )
                                .is_ok()
                            {
                                send_x11_selection_notify(&conn, &req, prop);
                            } else {
                                send_x11_selection_notify(&conn, &req, x11rb::NONE);
                            }
                        } else if offered_atoms.contains(&req.target) {
                            let total_len = data.total_len().unwrap_or(0);
                            if total_len <= X11_INCR_THRESHOLD {
                                let mut buf = vec![0u8; total_len as usize];
                                let mut filled = 0usize;
                                while filled < buf.len() {
                                    match data.read_slice(filled as u64, &mut buf[filled..]) {
                                        Ok(0) | Err(_) => break,
                                        Ok(n) => filled += n,
                                    }
                                }
                                if conn
                                    .change_property8(
                                        PropMode::REPLACE,
                                        req.requestor,
                                        prop,
                                        req.target,
                                        &buf[..filled],
                                    )
                                    .is_ok()
                                {
                                    send_x11_selection_notify(&conn, &req, prop);
                                } else {
                                    send_x11_selection_notify(&conn, &req, x11rb::NONE);
                                }
                            } else {
                                let _ = conn.change_window_attributes(
                                    req.requestor,
                                    &ChangeWindowAttributesAux::new()
                                        .event_mask(EventMask::PROPERTY_CHANGE),
                                );
                                let size_hint = total_len.min(u32::MAX as u64) as u32;
                                if conn
                                    .change_property32(
                                        PropMode::REPLACE,
                                        req.requestor,
                                        prop,
                                        atoms.INCR,
                                        &[size_hint],
                                    )
                                    .is_ok()
                                {
                                    incr_transfers.insert(
                                        (req.requestor, prop),
                                        PendingX11Incr {
                                            data: data.clone(),
                                            target_atom: req.target,
                                            offset: 0,
                                        },
                                    );
                                    send_x11_selection_notify(&conn, &req, prop);
                                } else {
                                    send_x11_selection_notify(&conn, &req, x11rb::NONE);
                                }
                            }
                        } else {
                            send_x11_selection_notify(&conn, &req, x11rb::NONE);
                        }
                    }
                    X11Event::PropertyNotify(pn) if pn.state == xproto::Property::DELETE => {
                        if let Some(transfer) = incr_transfers.get_mut(&(pn.window, pn.atom)) {
                            let n = transfer
                                .data
                                .read_slice(transfer.offset, &mut incr_buf)
                                .unwrap_or(0);
                            if n == 0 {
                                let target_atom = transfer.target_atom;
                                incr_transfers.remove(&(pn.window, pn.atom));
                                let _ = conn.change_property8(
                                    PropMode::REPLACE,
                                    pn.window,
                                    pn.atom,
                                    target_atom,
                                    &[],
                                );
                                let _ = conn.flush();
                            } else {
                                transfer.offset += n as u64;
                                if conn
                                    .change_property8(
                                        PropMode::REPLACE,
                                        pn.window,
                                        pn.atom,
                                        transfer.target_atom,
                                        &incr_buf[..n],
                                    )
                                    .is_err()
                                {
                                    incr_transfers.remove(&(pn.window, pn.atom));
                                } else {
                                    let _ = conn.flush();
                                }
                            }
                        }
                    }
                    _ => {}
                }
            }

            let _ = ACTIVE_X11_OWNER_WIN.compare_exchange(
                win,
                0,
                Ordering::SeqCst,
                Ordering::SeqCst,
            );
            let _ = conn.destroy_window(win);
            let _ = conn.flush();
        })
        .map_err(ClipboardError::Io)?;

    rx.recv()
        .map_err(|_| ClipboardError::X11("x11 clipboard server terminated".to_owned()))?
}

fn prepare_x11_copy_state(
    mime: &str,
) -> Result<(RustConnection, X11Atoms, Window, u32, Vec<Atom>), ClipboardError> {
    let (conn, screen_num) =
        RustConnection::connect(None).map_err(|e| ClipboardError::X11(e.to_string()))?;
    let atoms = X11Atoms::new(&conn)
        .map_err(|e| ClipboardError::X11(e.to_string()))?
        .reply()
        .map_err(|e| ClipboardError::X11(e.to_string()))?;

    let root = conn.setup().roots[screen_num].root;
    let win = create_x11_hidden_window(&conn, root, EventMask::PROPERTY_CHANGE)?;
    let owner_ts = acquire_x11_timestamp(&conn, win, atoms.CONTROID_CLIP_PROP)?;

    conn.set_selection_owner(win, atoms.CLIPBOARD, owner_ts)
        .map_err(|e| ClipboardError::X11(e.to_string()))?;
    conn.flush()
        .map_err(|e| ClipboardError::X11(e.to_string()))?;

    let owner_reply = conn
        .get_selection_owner(atoms.CLIPBOARD)
        .map_err(|e| ClipboardError::X11(e.to_string()))?
        .reply()
        .map_err(|e| ClipboardError::X11(e.to_string()))?;
    if owner_reply.owner != win {
        let _ = conn.destroy_window(win);
        return Err(ClipboardError::X11(
            "failed to acquire X11 CLIPBOARD ownership".to_owned(),
        ));
    }
    ACTIVE_X11_OWNER_WIN.store(win, Ordering::SeqCst);

    let offered_mimes = build_offered_mimes(mime);
    let mut offered_atoms = Vec::with_capacity(offered_mimes.len());
    for m in &offered_mimes {
        let atom = conn
            .intern_atom(false, m.as_bytes())
            .map_err(|e| ClipboardError::X11(e.to_string()))?
            .reply()
            .map_err(|e| ClipboardError::X11(e.to_string()))?
            .atom;
        offered_atoms.push(atom);
    }

    Ok((conn, atoms, win, owner_ts, offered_atoms))
}

#[cfg(test)]
mod tests {
    use super::*;
    use core_protocol::{compute_binary_echo_hash, compute_text_echo_hash};
    use std::io::Cursor;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn unique_test_dir(tag: &str) -> PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!(
            "controid-clip-test-{}-{}-{nanos}",
            std::process::id(),
            tag
        ))
    }

    #[test]
    fn preferred_mime_selection_prioritizes_supported_types() {
        let offered = vec![
            "text/html".to_owned(),
            "image/jpeg".to_owned(),
            "image/png".to_owned(),
        ];
        assert_eq!(
            select_preferred_mime(&offered),
            Some((MIME_PNG, MIME_PNG))
        );

        let text_offered = vec!["image/png".to_owned(), MIME_TEXT_UTF8.to_owned()];
        assert_eq!(
            select_preferred_mime(&text_offered),
            Some((MIME_TEXT_UTF8, MIME_TEXT_UTF8))
        );

        let fallback_text = vec!["UTF8_STRING".to_owned()];
        assert_eq!(
            select_preferred_mime(&fallback_text),
            Some(("UTF8_STRING", MIME_TEXT_UTF8))
        );

        let legacy_x11_text = vec!["STRING".to_owned()];
        assert_eq!(
            select_preferred_mime(&legacy_x11_text),
            Some(("STRING", MIME_TEXT_UTF8))
        );

        let unsupported = vec!["application/octet-stream".to_owned()];
        assert_eq!(select_preferred_mime(&unsupported), None);
    }

    #[test]
    fn payload_echo_hash_matches_protocol_text_and_binary() {
        let text = "Hyprland Wayland 🚀";
        assert_eq!(
            compute_payload_echo_hash(MIME_TEXT_UTF8, text.as_bytes()),
            compute_text_echo_hash(text)
        );

        let mut img = vec![0u8; 4096];
        for (idx, b) in img.iter_mut().enumerate() {
            *b = (idx % 233) as u8;
        }
        assert_eq!(
            compute_payload_echo_hash(MIME_PNG, &img),
            compute_binary_echo_hash(&img)
        );
    }

    #[test]
    fn stream_under_threshold_stays_in_memory() {
        let dir = unique_test_dir("mem");
        let data = b"hello small clipboard".to_vec();
        let event = read_stream_to_event(
            Cursor::new(data.clone()),
            MIME_TEXT_UTF8.to_owned(),
            &dir,
            1024,
            MAX_CLIPBOARD_BYTES,
            || false,
        )
        .unwrap()
        .unwrap();

        assert_eq!(
            event.payload,
            ClipboardPayload::Memory {
                mime: MIME_TEXT_UTF8.to_owned(),
                data,
            }
        );
        assert_eq!(event.echo_hash, compute_text_echo_hash("hello small clipboard"));
        assert!(!dir.exists());
    }

    #[test]
    fn stream_over_threshold_spools_to_disk_and_matches_binary_echo_hash() {
        let dir = unique_test_dir("spool");
        // Threshold smaller than 1024 and payload larger than 4096 exercises cross-boundary head/tail.
        let mut payload = vec![0u8; 5000];
        for (i, b) in payload.iter_mut().enumerate() {
            *b = ((i * 7) % 251) as u8;
        }
        let expected_hash = compute_binary_echo_hash(&payload);

        let event = read_stream_to_event(
            Cursor::new(payload.clone()),
            MIME_PNG.to_owned(),
            &dir,
            256,
            MAX_CLIPBOARD_BYTES,
            || false,
        )
        .unwrap()
        .unwrap();

        let (path, total_len) = match event.payload {
            ClipboardPayload::SpoolFile {
                mime,
                path,
                total_len,
            } => {
                assert_eq!(mime, MIME_PNG);
                (path, total_len)
            }
            other => panic!("expected SpoolFile, got {other:?}"),
        };

        assert_eq!(total_len, payload.len() as u64);
        assert_eq!(event.echo_hash, expected_hash);
        assert_eq!(fs::read(&path).unwrap(), payload);

        // Verify FileExt::read_at streamer reproduces identical bytes without loading into RAM.
        let file = File::open(&path).unwrap();
        let mut streamed_out = Vec::new();
        stream_file_to_writer(&file, &mut streamed_out).unwrap();
        assert_eq!(streamed_out, payload);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn spool_dir_uses_xdg_runtime_dir_when_present() {
        let dir = spool_dir_from_env(Some(std::ffi::OsStr::new("/run/user/1000")));
        assert_eq!(dir, PathBuf::from("/run/user/1000/controid/spool"));

        let fallback = spool_dir_from_env(Some(std::ffi::OsStr::new("")));
        assert!(fallback.ends_with("spool"));
    }

    #[test]
    fn stream_enforces_max_cap_and_cleans_spool_file() {
        let dir = unique_test_dir("cap");
        let payload = vec![0x5Au8; 256 * 1024];

        let err = read_stream_to_event(
            Cursor::new(payload),
            MIME_JPEG.to_owned(),
            &dir,
            32 * 1024,
            128 * 1024,
            || false,
        )
        .unwrap_err();

        assert!(matches!(err, ClipboardError::Io(ref e) if e.kind() == io::ErrorKind::InvalidData));
        assert!(dir.exists());
        let entries = fs::read_dir(&dir).unwrap().count();
        assert_eq!(entries, 0);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn stream_abort_removes_partial_spool_file() {
        let dir = unique_test_dir("abort");
        let payload = vec![0x11u8; 256 * 1024];
        let mut calls = 0usize;

        let res = read_stream_to_event(
            Cursor::new(payload),
            MIME_PNG.to_owned(),
            &dir,
            1024,
            MAX_CLIPBOARD_BYTES,
            || {
                calls += 1;
                calls > 2
            },
        )
        .unwrap();

        assert!(res.is_none());
        assert!(dir.exists());
        let entries = fs::read_dir(&dir).unwrap().count();
        assert_eq!(entries, 0);

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn offered_mimes_include_text_aliases_and_stream_bytes() {
        let mimes = build_offered_mimes("text/plain");
        for expected in [
            "text/plain",
            MIME_TEXT_UTF8,
            "UTF8_STRING",
            "STRING",
            "TEXT",
        ] {
            assert!(mimes.iter().any(|m| m == expected), "missing {expected}");
        }
        assert_eq!(build_offered_mimes(MIME_PNG), vec![MIME_PNG]);

        let mut out = Vec::new();
        stream_bytes_to_writer(b"hello wayland", &mut out).unwrap();
        assert_eq!(out, b"hello wayland");
    }

    #[test]
    fn backend_kind_strings_are_stable() {
        assert_eq!(
            ClipboardBackendKind::WaylandExtDataControl.as_str(),
            "wayland-ext-data-control"
        );
        assert_eq!(
            ClipboardBackendKind::WaylandWlrDataControl.as_str(),
            "wayland-wlr-data-control"
        );
        assert_eq!(ClipboardBackendKind::X11.as_str(), "x11");
        assert_eq!(ClipboardBackendKind::Unavailable.as_str(), "unavailable");
    }
}
