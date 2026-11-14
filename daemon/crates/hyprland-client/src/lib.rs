pub mod clipboard;
pub mod compositor;
pub mod dock;
pub mod uinput;
pub mod video;

pub use clipboard::{
    ClipboardBackendKind, ClipboardError, ClipboardEvent, ClipboardPayload,
    UniversalClipboardDriver, WaylandClipboardDriver, detect_clipboard_backend,
};
pub use compositor::{CompositorBackend, HyprlandBackend, LinuxDesktopBackend};
pub use dock::{
    DesktopAppEntry, DesktopEnv, DockLaunchAction, MediaStatus, RuntimeWindowState,
    WorkspaceInfo, detect_desktop_env, detect_session_type, execute_app_action,
    icon_fingerprint, launch_desktop_entry, perform_system_action, query_media_status,
    query_runtime_state, resolve_icon_png, scan_desktop_apps, spawn_desktop_event_watcher,
};
pub use uinput::{HidWatchdog, UinputFallbackDevice};
pub use video::{MpvVideoSink, build_mpv_args, build_viewer_args};
