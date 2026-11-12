use core_protocol::{ECHO_SLICE_LEN, compute_clipboard_echo_hash};
use nix::fcntl::{FcntlArg, OFlag, fcntl};
use std::collections::HashMap;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::os::unix::fs::{FileExt, OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc as std_mpsc;
use std::thread;
use thiserror::Error;
use tokio::sync::mpsc;
use tracing::{debug, warn};
use wayland_client::globals::{GlobalListContents, registry_queue_init};
use wayland_client::protocol::wl_registry::{self, WlRegistry};
use wayland_client::protocol::wl_seat::{self, WlSeat};
use wayland_client::{Connection, Dispatch, Proxy, QueueHandle, event_created_child};
use wayland_protocols_wlr::data_control::v1::client::{
    zwlr_data_control_device_v1::{self, EVT_DATA_OFFER_OPCODE, ZwlrDataControlDeviceV1},
    zwlr_data_control_manager_v1::ZwlrDataControlManagerV1,
    zwlr_data_control_offer_v1::{self, ZwlrDataControlOfferV1},
    zwlr_data_control_source_v1::{self, ZwlrDataControlSourceV1},
};

pub const RAM_THRESHOLD_BYTES: usize = 32 * 1024 * 1024;
pub const MAX_CLIPBOARD_BYTES: u64 = 512 * 1024 * 1024;
const STREAM_CHUNK_SIZE: usize = 64 * 1024;

const MIME_TEXT_UTF8: &str = "text/plain;charset=utf-8";
const MIME_PNG: &str = "image/png";
const MIME_JPEG: &str = "image/jpeg";

static SPOOL_SEQ: AtomicU64 = AtomicU64::new(1);

#[derive(Debug, Error)]
pub enum ClipboardError {
    #[error("wayland clipboard error: {0}")]
    Wayland(String),
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

pub struct WaylandClipboardDriver;

impl WaylandClipboardDriver {
    pub fn spawn_watcher() -> mpsc::Receiver<ClipboardEvent> {
        let (tx, rx) = mpsc::channel(16);

        let spawn_res = thread::Builder::new()
            .name("wayland-clipboard-watcher".to_owned())
            .spawn(move || {
                if let Err(err) = run_watcher(tx) {
                    warn!(?err, "wayland clipboard watcher unavailable");
                }
            });

        if let Err(err) = spawn_res {
            warn!(?err, "failed to spawn wayland clipboard watcher thread");
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

pub(crate) fn read_stream_to_event<R: Read>(
    mut reader: R,
    mime: String,
    spool_dir: &Path,
    ram_threshold: usize,
    max_bytes: u64,
    mut abort_check: impl FnMut() -> bool,
) -> Result<Option<ClipboardEvent>, ClipboardError> {
    let mut chunk = vec![0u8; STREAM_CHUNK_SIZE];
    let mut ram_buf: Vec<u8> = Vec::new();
    let mut head_buf: Vec<u8> = Vec::with_capacity(ECHO_SLICE_LEN);
    let mut tail_buf: Vec<u8> = Vec::with_capacity(ECHO_SLICE_LEN * 2);
    let mut spool: Option<(File, PathBuf)> = None;
    let mut total_len: u64 = 0;

    loop {
        if abort_check() {
            if let Some((file, path)) = spool.take() {
                drop(file);
                let _ = fs::remove_file(path);
            }
            return Ok(None);
        }

        let n = match reader.read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => n,
            Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
            Err(err) => {
                if let Some((file, path)) = spool.take() {
                    drop(file);
                    let _ = fs::remove_file(path);
                }
                return Err(ClipboardError::Io(err));
            }
        };

        let slice = &chunk[..n];
        let next_total = total_len.saturating_add(n as u64);
        if next_total > max_bytes {
            if let Some((file, path)) = spool.take() {
                drop(file);
                let _ = fs::remove_file(path);
            }
            return Err(ClipboardError::Io(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("clipboard payload exceeds maximum limit of {max_bytes} bytes"),
            )));
        }
        total_len = next_total;

        if head_buf.len() < ECHO_SLICE_LEN {
            let need = ECHO_SLICE_LEN - head_buf.len();
            head_buf.extend_from_slice(&slice[..slice.len().min(need)]);
        }
        append_tail_slice(&mut tail_buf, slice);

        if let Some((file, _)) = spool.as_mut() {
            if let Err(err) = file.write_all(slice) {
                if let Some((f, path)) = spool.take() {
                    drop(f);
                    let _ = fs::remove_file(path);
                }
                return Err(ClipboardError::Io(err));
            }
        } else if ram_buf.len() + slice.len() <= ram_threshold {
            ram_buf.extend_from_slice(slice);
        } else {
            let (mut file, path) = create_spool_file(spool_dir)?;
            if let Err(err) = file.write_all(&ram_buf).and_then(|()| file.write_all(slice)) {
                drop(file);
                let _ = fs::remove_file(&path);
                return Err(ClipboardError::Io(err));
            }
            // Release RAM buffer immediately once payload spills to spool file.
            ram_buf = Vec::new();
            spool = Some((file, path));
        }
    }

    if let Some((mut file, path)) = spool {
        file.flush()?;
        drop(file);
        let echo_hash = compute_clipboard_echo_hash(false, total_len, &head_buf, &tail_buf);
        Ok(Some(ClipboardEvent {
            payload: ClipboardPayload::SpoolFile {
                mime,
                path,
                total_len,
            },
            echo_hash,
        }))
    } else {
        let echo_hash = compute_payload_echo_hash(&mime, &ram_buf);
        Ok(Some(ClipboardEvent {
            payload: ClipboardPayload::Memory {
                mime,
                data: ram_buf,
            },
            echo_hash,
        }))
    }
}

#[derive(Clone)]
enum CopyPayloadSource {
    Bytes(Arc<Vec<u8>>),
    File(Arc<File>),
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
    serve_data_control_clipboard(mime, source)
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
                    while state.sources.iter().any(Proxy::is_alive) {
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
    devices: Vec<ZwlrDataControlDeviceV1>,
    sources: Vec<ZwlrDataControlSourceV1>,
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

    let manager: ZwlrDataControlManagerV1 = globals
        .bind(&qh, 1..=2, ())
        .map_err(|err| ClipboardError::Wayland(format!("wlr-data-control unavailable: {err}")))?;

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

    let devices: Vec<ZwlrDataControlDeviceV1> = seats
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
        let source = manager.create_data_source(&qh, ());
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

impl Dispatch<ZwlrDataControlDeviceV1, ()> for DataControlCopyState {
    fn event(
        _state: &mut Self,
        _proxy: &ZwlrDataControlDeviceV1,
        event: zwlr_data_control_device_v1::Event,
        _data: &(),
        _conn: &Connection,
        _qh: &QueueHandle<Self>,
    ) {
        match event {
            zwlr_data_control_device_v1::Event::DataOffer { id } => id.destroy(),
            zwlr_data_control_device_v1::Event::Finished => {}
            _ => {}
        }
    }

    event_created_child!(DataControlCopyState, ZwlrDataControlDeviceV1, [
        EVT_DATA_OFFER_OPCODE => (ZwlrDataControlOfferV1, ()),
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
                if !state.offered_mimes.iter().any(|m| m == &mime_type) {
                    return;
                }

                // Compositor passes a non-blocking pipe fd; clear O_NONBLOCK so blocking writes
                // stream cleanly on the worker thread without spinning.
                if let Err(err) = fcntl(&fd, FcntlArg::F_SETFL(OFlag::empty())) {
                    warn!(?err, "failed to clear O_NONBLOCK on clipboard target pipe");
                    return;
                }

                let data = state.data.clone();
                if let Err(err) = thread::Builder::new()
                    .name("wayland-clipboard-writer".to_owned())
                    .spawn(move || {
                        let mut out = File::from(fd);
                        let res = match &data {
                            CopyPayloadSource::Bytes(bytes) => {
                                stream_bytes_to_writer(bytes, &mut out)
                            }
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
            zwlr_data_control_source_v1::Event::Cancelled => {
                source.destroy();
            }
            _ => {}
        }
    }
}

struct SeatEntry {
    registry_name: u32,
    name: Option<String>,
    device: Option<ZwlrDataControlDeviceV1>,
    current_offer: Option<ZwlrDataControlOfferV1>,
}

struct WatcherState {
    manager: ZwlrDataControlManagerV1,
    seats: HashMap<WlSeat, SeatEntry>,
    pending_offers: HashMap<ZwlrDataControlOfferV1, Vec<String>>,
    tx: mpsc::Sender<ClipboardEvent>,
    generation: Arc<AtomicU64>,
    spool_dir: PathBuf,
}

fn run_watcher(tx: mpsc::Sender<ClipboardEvent>) -> Result<(), ClipboardError> {
    let conn =
        Connection::connect_to_env().map_err(|err| ClipboardError::Wayland(err.to_string()))?;
    let (globals, mut queue) = registry_queue_init::<WatcherState>(&conn)
        .map_err(|err| ClipboardError::Wayland(err.to_string()))?;
    let qh = queue.handle();

    let manager: ZwlrDataControlManagerV1 = match globals.bind(&qh, 1..=2, ()) {
        Ok(m) => m,
        Err(err) => {
            warn!(
                ?err,
                "compositor does not expose zwlr_data_control_manager_v1; clipboard watcher disabled"
            );
            return Ok(());
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
                state.pending_offers.insert(id, Vec::new());
            }
            zwlr_data_control_device_v1::Event::Selection { id } => {
                let Some(seat_entry) = state.seats.get_mut(seat) else {
                    if let Some(offer) = id {
                        state.pending_offers.remove(&offer);
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

                let offered = state.pending_offers.remove(&offer).unwrap_or_default();
                seat_entry.current_offer = Some(offer);

                let Some((req_mime, canonical_mime)) = select_preferred_mime(&offered) else {
                    return;
                };

                let req_mime = req_mime.to_owned();
                let canonical_mime = canonical_mime.to_owned();
                let seat_name = seat_entry.name.clone();
                let expected_gen = state.generation.fetch_add(1, Ordering::SeqCst) + 1;
                let generation = Arc::clone(&state.generation);
                let tx = state.tx.clone();
                let spool_dir = state.spool_dir.clone();

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
                                    if let ClipboardPayload::SpoolFile { path, .. } = event.payload
                                    {
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
            zwlr_data_control_device_v1::Event::PrimarySelection { id: Some(offer) } => {
                state.pending_offers.remove(&offer);
                offer.destroy();
            }
            zwlr_data_control_device_v1::Event::Finished => {
                if let Some(entry) = state.seats.get_mut(seat) {
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

    event_created_child!(WatcherState, ZwlrDataControlDeviceV1, [
        EVT_DATA_OFFER_OPCODE => (ZwlrDataControlOfferV1, ()),
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
            && let Some(mimes) = state.pending_offers.get_mut(offer)
        {
            mimes.push(mime_type);
        }
    }
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
}
