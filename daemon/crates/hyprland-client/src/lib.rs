pub mod clipboard;
pub mod compositor;
pub mod uinput;
pub mod video;

pub use clipboard::{
    ClipboardBackendKind, ClipboardError, ClipboardEvent, ClipboardPayload,
    UniversalClipboardDriver, WaylandClipboardDriver, detect_clipboard_backend,
};
pub use compositor::{CompositorBackend, HyprlandBackend, LinuxDesktopBackend};
pub use uinput::{HidWatchdog, UinputFallbackDevice};
pub use video::{MpvVideoSink, build_mpv_args, build_viewer_args};
