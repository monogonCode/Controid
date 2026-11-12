use crate::clipboard::{
    ClipboardEvent, ClipboardPayload, UniversalClipboardDriver, detect_clipboard_backend,
};
use crate::uinput::UinputFallbackDevice;
use anyhow::{Result, anyhow};
use core_protocol::InputFallbackEvent;
use std::future::Future;
use std::pin::Pin;
use std::sync::Mutex;
use tokio::sync::mpsc;

pub trait CompositorBackend: Send + Sync {
    fn name(&self) -> &'static str;
    fn watch_clipboard(&self) -> mpsc::Receiver<ClipboardEvent>;
    fn set_clipboard<'a>(
        &'a self,
        payload: ClipboardPayload,
    ) -> Pin<Box<dyn Future<Output = Result<()>> + Send + 'a>>;
    fn inject_fallback_input(&self, ev: InputFallbackEvent) -> Result<()>;
}

#[derive(Debug, Default)]
pub struct LinuxDesktopBackend {
    uinput: Mutex<Option<UinputFallbackDevice>>,
}

pub type HyprlandBackend = LinuxDesktopBackend;

impl LinuxDesktopBackend {
    pub fn new() -> Self {
        Self::default()
    }
}

impl CompositorBackend for LinuxDesktopBackend {
    fn name(&self) -> &'static str {
        detect_clipboard_backend().as_str()
    }

    fn watch_clipboard(&self) -> mpsc::Receiver<ClipboardEvent> {
        UniversalClipboardDriver::spawn_watcher()
    }

    fn set_clipboard<'a>(
        &'a self,
        payload: ClipboardPayload,
    ) -> Pin<Box<dyn Future<Output = Result<()>> + Send + 'a>> {
        Box::pin(async move {
            UniversalClipboardDriver::write_clipboard(payload).await?;
            Ok(())
        })
    }

    fn inject_fallback_input(&self, ev: InputFallbackEvent) -> Result<()> {
        let mut guard = self
            .uinput
            .lock()
            .map_err(|_| anyhow!("uinput device lock poisoned"))?;
        if guard.is_none() {
            *guard = Some(UinputFallbackDevice::create()?);
        }
        guard
            .as_mut()
            .expect("uinput device initialized")
            .emit(ev)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn desktop_backend_is_send_sync_and_named() {
        fn assert_send_sync<T: Send + Sync>() {}
        assert_send_sync::<LinuxDesktopBackend>();

        let backend = LinuxDesktopBackend::new();
        let dyn_backend: &dyn CompositorBackend = &backend;
        assert!(matches!(
            dyn_backend.name(),
            "wayland-ext-data-control"
                | "wayland-wlr-data-control"
                | "x11"
                | "unavailable"
        ));
    }
}
