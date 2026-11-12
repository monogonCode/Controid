use crate::clipboard::{ClipboardEvent, ClipboardPayload, WaylandClipboardDriver};
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
pub struct HyprlandBackend {
    uinput: Mutex<Option<UinputFallbackDevice>>,
}

impl HyprlandBackend {
    pub fn new() -> Self {
        Self::default()
    }
}

impl CompositorBackend for HyprlandBackend {
    fn name(&self) -> &'static str {
        "hyprland-wlroots"
    }

    fn watch_clipboard(&self) -> mpsc::Receiver<ClipboardEvent> {
        WaylandClipboardDriver::spawn_watcher()
    }

    fn set_clipboard<'a>(
        &'a self,
        payload: ClipboardPayload,
    ) -> Pin<Box<dyn Future<Output = Result<()>> + Send + 'a>> {
        Box::pin(async move {
            WaylandClipboardDriver::write_clipboard(payload).await?;
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
    fn hyprland_backend_is_send_sync_and_named() {
        fn assert_send_sync<T: Send + Sync>() {}
        assert_send_sync::<HyprlandBackend>();

        let backend = HyprlandBackend::new();
        let dyn_backend: &dyn CompositorBackend = &backend;
        assert_eq!(dyn_backend.name(), "hyprland-wlroots");
    }
}
