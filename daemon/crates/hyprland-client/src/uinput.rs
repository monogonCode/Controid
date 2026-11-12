use core_protocol::InputFallbackEvent;
use std::collections::HashSet;
use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::os::fd::AsRawFd;
use std::path::{Path, PathBuf};
use std::sync::mpsc as std_mpsc;
use std::thread;
use tokio::sync::watch;
use tracing::{debug, warn};
use udev::{Enumerator, EventType, MonitorBuilder};

const UINPUT_DEVICE_PATH: &str = "/dev/uinput";
const VIRTUAL_DEVICE_NAME: &str = "Controid Virtual Input";
const WATCHDOG_POLL_TIMEOUT_MS: libc::c_int = 500;
const MAX_ANCESTOR_DEPTH: usize = 6;

mod uapi {
    use nix::{ioctl_none, ioctl_write_int, ioctl_write_ptr};

    pub const EV_SYN: u16 = 0x00;
    pub const EV_KEY: u16 = 0x01;
    pub const EV_REL: u16 = 0x02;

    pub const SYN_REPORT: u16 = 0x00;

    pub const REL_X: u16 = 0x00;
    pub const REL_Y: u16 = 0x01;
    pub const REL_WHEEL: u16 = 0x08;

    pub const BTN_LEFT: u16 = 0x110;
    pub const BTN_RIGHT: u16 = 0x111;
    pub const BTN_MIDDLE: u16 = 0x112;
    pub const BTN_SIDE: u16 = 0x113;
    pub const BTN_EXTRA: u16 = 0x114;

    pub const BUS_BLUETOOTH: u16 = 0x05;
    pub const BUS_VIRTUAL: u16 = 0x06;

    const UINPUT_IOCTL_BASE: u8 = b'U';

    ioctl_none!(ui_dev_create, UINPUT_IOCTL_BASE, 1);
    ioctl_none!(ui_dev_destroy, UINPUT_IOCTL_BASE, 2);
    ioctl_write_ptr!(ui_dev_setup, UINPUT_IOCTL_BASE, 3, libc::uinput_setup);
    ioctl_write_int!(ui_set_evbit, UINPUT_IOCTL_BASE, 100);
    ioctl_write_int!(ui_set_keybit, UINPUT_IOCTL_BASE, 101);
    ioctl_write_int!(ui_set_relbit, UINPUT_IOCTL_BASE, 102);
}

#[derive(Debug)]
pub struct UinputFallbackDevice {
    file: File,
}

impl UinputFallbackDevice {
    pub fn create() -> io::Result<Self> {
        Self::create_at(Path::new(UINPUT_DEVICE_PATH))
    }

    pub(crate) fn create_at(path: &Path) -> io::Result<Self> {
        let file = OpenOptions::new()
            .write(true)
            .open(path)
            .map_err(|err| map_uinput_open_error(path, err))?;

        let fd = file.as_raw_fd();

        // SAFETY: `fd` is a valid open `/dev/uinput` descriptor; all ioctls configure a new
        // virtual device before `UI_DEV_CREATE` activates it.
        unsafe {
            uapi::ui_set_evbit(fd, libc::c_ulong::from(uapi::EV_SYN)).map_err(io::Error::from)?;
            uapi::ui_set_evbit(fd, libc::c_ulong::from(uapi::EV_KEY)).map_err(io::Error::from)?;
            uapi::ui_set_evbit(fd, libc::c_ulong::from(uapi::EV_REL)).map_err(io::Error::from)?;

            uapi::ui_set_relbit(fd, libc::c_ulong::from(uapi::REL_X)).map_err(io::Error::from)?;
            uapi::ui_set_relbit(fd, libc::c_ulong::from(uapi::REL_Y)).map_err(io::Error::from)?;
            uapi::ui_set_relbit(fd, libc::c_ulong::from(uapi::REL_WHEEL))
                .map_err(io::Error::from)?;

            for btn in uapi::BTN_LEFT..=uapi::BTN_EXTRA {
                uapi::ui_set_keybit(fd, libc::c_ulong::from(btn)).map_err(io::Error::from)?;
            }
            for key in 1u16..=248 {
                uapi::ui_set_keybit(fd, libc::c_ulong::from(key)).map_err(io::Error::from)?;
            }
            for key in 0x160u16..=0x1bf {
                uapi::ui_set_keybit(fd, libc::c_ulong::from(key)).map_err(io::Error::from)?;
            }
            for key in 0x1d0u16..=0x1e5 {
                uapi::ui_set_keybit(fd, libc::c_ulong::from(key)).map_err(io::Error::from)?;
            }

            let setup = build_uinput_setup(VIRTUAL_DEVICE_NAME);
            uapi::ui_dev_setup(fd, &setup).map_err(io::Error::from)?;
            uapi::ui_dev_create(fd).map_err(io::Error::from)?;
        }

        debug!(path = %path.display(), "created virtual uinput mouse+keyboard device");
        Ok(Self { file })
    }

    pub fn emit(&mut self, event: InputFallbackEvent) -> io::Result<()> {
        let (events, count) = encode_fallback_events(event)?;
        if count == 0 {
            return Ok(());
        }
        let bytes = events_as_bytes(&events[..count]);
        self.file.write_all(bytes)
    }
}

impl Drop for UinputFallbackDevice {
    fn drop(&mut self) {
        // SAFETY: `self.file` remains open until after `Drop::drop` finishes, and
        // `UI_DEV_DESTROY` tears down the active uinput kernel device.
        if let Err(err) = unsafe { uapi::ui_dev_destroy(self.file.as_raw_fd()) } {
            debug!(?err, "UI_DEV_DESTROY failed during uinput cleanup");
        }
    }
}

fn map_uinput_open_error(path: &Path, err: io::Error) -> io::Error {
    match err.kind() {
        io::ErrorKind::PermissionDenied => io::Error::new(
            io::ErrorKind::PermissionDenied,
            format!(
                "permission denied opening {} (ensure user belongs to 'input' group or udev rule grants access): {err}",
                path.display()
            ),
        ),
        io::ErrorKind::NotFound => io::Error::new(
            io::ErrorKind::NotFound,
            format!(
                "{} not found (ensure the 'uinput' kernel module is loaded): {err}",
                path.display()
            ),
        ),
        _ => err,
    }
}

pub fn build_uinput_setup(name: &str) -> libc::uinput_setup {
    // SAFETY: `libc::uinput_setup` is a plain C POD struct of integers and `[c_char; 80]`.
    let mut setup: libc::uinput_setup = unsafe { std::mem::zeroed() };
    setup.id = libc::input_id {
        bustype: uapi::BUS_VIRTUAL,
        vendor: 0x1209,
        product: 0xDC01,
        version: 1,
    };
    let max_len = name.len().min(libc::UINPUT_MAX_NAME_SIZE - 1);
    for (dst, &src) in setup.name.iter_mut().zip(name.as_bytes()[..max_len].iter()) {
        *dst = src as libc::c_char;
    }
    setup.ff_effects_max = 0;
    setup
}

#[inline]
pub fn make_input_event(type_: u16, code: u16, value: i32) -> libc::input_event {
    // SAFETY: `libc::input_event` is a C POD struct; zero timestamp lets kernel fill event time.
    let mut ev: libc::input_event = unsafe { std::mem::zeroed() };
    ev.type_ = type_;
    ev.code = code;
    ev.value = value;
    ev
}

pub fn encode_fallback_events(
    event: InputFallbackEvent,
) -> io::Result<([libc::input_event; 4], usize)> {
    let zero_ev = make_input_event(0, 0, 0);
    let mut buf = [zero_ev; 4];
    let mut count = 0usize;

    match event {
        InputFallbackEvent::MouseRel { dx, dy, wheel } => {
            if dx != 0 {
                buf[count] = make_input_event(uapi::EV_REL, uapi::REL_X, i32::from(dx));
                count += 1;
            }
            if dy != 0 {
                buf[count] = make_input_event(uapi::EV_REL, uapi::REL_Y, i32::from(dy));
                count += 1;
            }
            if wheel != 0 {
                buf[count] = make_input_event(uapi::EV_REL, uapi::REL_WHEEL, i32::from(wheel));
                count += 1;
            }
            if count > 0 {
                buf[count] = make_input_event(uapi::EV_SYN, uapi::SYN_REPORT, 0);
                count += 1;
            }
        }
        InputFallbackEvent::MouseBtn { button, pressed } => {
            let code = match button {
                1 => uapi::BTN_LEFT,
                2 => uapi::BTN_RIGHT,
                3 => uapi::BTN_MIDDLE,
                4 => uapi::BTN_SIDE,
                5 => uapi::BTN_EXTRA,
                other => {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidInput,
                        format!("unsupported mouse button code: {other}"),
                    ));
                }
            };
            buf[0] = make_input_event(uapi::EV_KEY, code, i32::from(pressed));
            buf[1] = make_input_event(uapi::EV_SYN, uapi::SYN_REPORT, 0);
            count = 2;
        }
        InputFallbackEvent::KeyEvent {
            linux_keycode,
            pressed,
        } => {
            if linux_keycode == 0 || linux_keycode > libc::KEY_MAX {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    format!("invalid linux keycode: {linux_keycode}"),
                ));
            }
            buf[0] = make_input_event(uapi::EV_KEY, linux_keycode, i32::from(pressed));
            buf[1] = make_input_event(uapi::EV_SYN, uapi::SYN_REPORT, 0);
            count = 2;
        }
    }

    Ok((buf, count))
}

pub fn events_as_bytes(events: &[libc::input_event]) -> &[u8] {
    // SAFETY: `libc::input_event` is `#[repr(C)]` POD with no uninitialized padding bytes when
    // initialized via `mem::zeroed()`.
    unsafe {
        std::slice::from_raw_parts(
            events.as_ptr().cast::<u8>(),
            std::mem::size_of_val(events),
        )
    }
}

pub struct HidWatchdog;

impl HidWatchdog {
    pub fn spawn(bt_mac: Option<String>) -> watch::Receiver<bool> {
        let (tx, rx) = watch::channel(false);
        let (ready_tx, ready_rx) = std_mpsc::sync_channel::<()>(1);
        let target_mac = bt_mac
            .as_deref()
            .map(normalize_mac)
            .filter(|m| !m.is_empty());

        let spawn_res = thread::Builder::new()
            .name("hid-watchdog".to_owned())
            .spawn(move || {
                if let Err(err) = run_hid_watchdog(target_mac, tx, ready_tx) {
                    warn!(?err, "hid watchdog unavailable; defaulting to fallback enabled");
                }
            });

        if let Err(err) = spawn_res {
            warn!(?err, "failed to spawn hid watchdog thread");
            return rx;
        }

        let _ = ready_rx.recv();
        rx
    }
}

fn run_hid_watchdog(
    target_mac: Option<String>,
    tx: watch::Sender<bool>,
    ready_tx: std_mpsc::SyncSender<()>,
) -> io::Result<()> {
    // Open monitor socket before initial scan so add/remove transitions during scan are not lost.
    let socket = match MonitorBuilder::new()
        .and_then(|b| b.match_subsystem("input"))
        .and_then(|b| b.listen())
    {
        Ok(sock) => sock,
        Err(err) => {
            let _ = ready_tx.send(());
            return Err(err);
        }
    };

    let mut active_devices: HashSet<PathBuf> = HashSet::new();
    match Enumerator::new() {
        Ok(mut enumerator) => {
            if enumerator.match_subsystem("input").is_ok()
                && let Ok(devices) = enumerator.scan_devices()
            {
                for dev in devices {
                    if matches_bt_hid(&dev, target_mac.as_deref()) {
                        active_devices.insert(dev.syspath().to_path_buf());
                    }
                }
            }
        }
        Err(err) => {
            warn!(?err, "initial udev input enumeration failed");
        }
    }

    let initial_present = !active_devices.is_empty();
    let _ = tx.send(initial_present);
    let _ = ready_tx.send(());
    debug!(initial_present, target_mac = ?target_mac, "hid watchdog initialized");

    let fd = socket.as_raw_fd();
    loop {
        if tx.is_closed() {
            break;
        }

        let mut pfd = libc::pollfd {
            fd,
            events: libc::POLLIN,
            revents: 0,
        };

        // SAFETY: `pfd` points to a single valid stack `pollfd` for the open udev netlink fd.
        let rc = unsafe { libc::poll(&mut pfd, 1, WATCHDOG_POLL_TIMEOUT_MS) };
        if rc < 0 {
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            return Err(err);
        }
        if rc == 0 {
            continue;
        }
        if (pfd.revents & (libc::POLLERR | libc::POLLHUP | libc::POLLNVAL)) != 0 {
            break;
        }
        if (pfd.revents & libc::POLLIN) == 0 {
            continue;
        }

        for event in socket.iter() {
            let syspath = event.syspath().to_path_buf();
            match event.event_type() {
                EventType::Add | EventType::Change | EventType::Bind => {
                    if matches_bt_hid(&event, target_mac.as_deref()) {
                        active_devices.insert(syspath);
                    } else {
                        active_devices.remove(&syspath);
                    }
                }
                EventType::Remove | EventType::Unbind => {
                    active_devices.remove(&syspath);
                }
                EventType::Unknown => {}
            }
        }

        let present = !active_devices.is_empty();
        tx.send_if_modified(|current| {
            if *current != present {
                *current = present;
                true
            } else {
                false
            }
        });
    }

    Ok(())
}

pub(crate) fn normalize_mac(raw: &str) -> String {
    raw.chars()
        .filter(|c| c.is_ascii_hexdigit())
        .map(|c| c.to_ascii_lowercase())
        .collect()
}

pub(crate) fn parse_bustype_hex(raw: &str) -> Option<u16> {
    let trimmed = raw.trim().trim_start_matches("0x").trim_start_matches("0X");
    u16::from_str_radix(trimmed, 16).ok()
}

fn is_input_event_device(dev: &udev::Device) -> bool {
    if dev.sysname().to_string_lossy().starts_with("event") {
        return true;
    }
    dev.devnode()
        .and_then(|p| p.file_name())
        .is_some_and(|name| name.to_string_lossy().starts_with("event"))
}

fn is_bluetooth_device(dev: &udev::Device) -> bool {
    let mut cur = Some(dev.clone());
    for _ in 0..MAX_ANCESTOR_DEPTH {
        let Some(node) = cur else {
            break;
        };

        if node
            .property_value("ID_BUS")
            .and_then(|v| v.to_str())
            .is_some_and(|v| v.eq_ignore_ascii_case("bluetooth"))
        {
            return true;
        }
        if node
            .attribute_value("id/bustype")
            .and_then(|v| v.to_str())
            .and_then(parse_bustype_hex)
            == Some(uapi::BUS_BLUETOOTH)
        {
            return true;
        }
        if node
            .property_value("MODALIAS")
            .and_then(|v| v.to_str())
            .is_some_and(|m| m.starts_with("input:b0005"))
        {
            return true;
        }
        if node
            .property_value("HID_ID")
            .and_then(|v| v.to_str())
            .is_some_and(|h| h.starts_with("0005:"))
        {
            return true;
        }
        if node
            .property_value("PRODUCT")
            .and_then(|v| v.to_str())
            .is_some_and(|p| p.starts_with("5/"))
        {
            return true;
        }

        cur = node.parent();
    }
    false
}

fn matches_bt_mac(dev: &udev::Device, target_mac_norm: &str) -> bool {
    let mut cur = Some(dev.clone());
    for _ in 0..MAX_ANCESTOR_DEPTH {
        let Some(node) = cur else {
            break;
        };

        for attr in ["uniq", "address"] {
            if let Some(val) = node.attribute_value(attr).and_then(|v| v.to_str())
                && normalize_mac(val) == target_mac_norm
            {
                return true;
            }
        }
        for prop in ["UNIQ", "HID_UNIQ"] {
            if let Some(val) = node.property_value(prop).and_then(|v| v.to_str())
                && normalize_mac(val) == target_mac_norm
            {
                return true;
            }
        }

        cur = node.parent();
    }
    false
}

fn matches_bt_hid(dev: &udev::Device, target_mac_norm: Option<&str>) -> bool {
    if !is_input_event_device(dev) || !is_bluetooth_device(dev) {
        return false;
    }
    target_mac_norm.is_none_or(|mac| matches_bt_mac(dev, mac))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::mem::{align_of, size_of};

    #[test]
    fn uinput_struct_sizes_match_kernel_uapi() {
        assert_eq!(size_of::<libc::input_id>(), 8);
        assert_eq!(size_of::<libc::uinput_setup>(), 8 + libc::UINPUT_MAX_NAME_SIZE + 4);
        assert_eq!(size_of::<libc::uinput_setup>(), 92);

        #[cfg(target_pointer_width = "64")]
        assert_eq!(size_of::<libc::input_event>(), 24);
        #[cfg(target_pointer_width = "32")]
        assert_eq!(size_of::<libc::input_event>(), 16);

        assert!(align_of::<libc::input_event>() >= 4);
    }

    #[test]
    fn uinput_setup_truncates_and_null_terminates() {
        let setup = build_uinput_setup("Controid Virtual Input");
        assert_eq!(setup.id.bustype, uapi::BUS_VIRTUAL);
        assert_eq!(setup.id.vendor, 0x1209);
        assert_eq!(setup.id.product, 0xDC01);
        assert_eq!(setup.id.version, 1);
        assert_eq!(setup.ff_effects_max, 0);
        assert_eq!(setup.name[libc::UINPUT_MAX_NAME_SIZE - 1], 0);

        let long_name = "A".repeat(200);
        let setup_long = build_uinput_setup(&long_name);
        assert_eq!(setup_long.name[libc::UINPUT_MAX_NAME_SIZE - 1], 0);
    }

    #[test]
    fn serialize_mouse_rel_events_with_syn_report() {
        let (events, count) = encode_fallback_events(InputFallbackEvent::MouseRel {
            dx: -12,
            dy: 34,
            wheel: -1,
        })
        .unwrap();
        assert_eq!(count, 4);
        assert_eq!((events[0].type_, events[0].code, events[0].value), (uapi::EV_REL, uapi::REL_X, -12));
        assert_eq!((events[1].type_, events[1].code, events[1].value), (uapi::EV_REL, uapi::REL_Y, 34));
        assert_eq!((events[2].type_, events[2].code, events[2].value), (uapi::EV_REL, uapi::REL_WHEEL, -1));
        assert_eq!((events[3].type_, events[3].code, events[3].value), (uapi::EV_SYN, uapi::SYN_REPORT, 0));

        let bytes = events_as_bytes(&events[..count]);
        assert_eq!(bytes.len(), 4 * size_of::<libc::input_event>());

        let (_, zero_count) = encode_fallback_events(InputFallbackEvent::MouseRel {
            dx: 0,
            dy: 0,
            wheel: 0,
        })
        .unwrap();
        assert_eq!(zero_count, 0);
    }

    #[test]
    fn serialize_mouse_btn_and_key_events_with_validation() {
        let (btn_events, btn_count) = encode_fallback_events(InputFallbackEvent::MouseBtn {
            button: 2,
            pressed: true,
        })
        .unwrap();
        assert_eq!(btn_count, 2);
        assert_eq!(
            (btn_events[0].type_, btn_events[0].code, btn_events[0].value),
            (uapi::EV_KEY, uapi::BTN_RIGHT, 1)
        );
        assert_eq!(
            (btn_events[1].type_, btn_events[1].code, btn_events[1].value),
            (uapi::EV_SYN, uapi::SYN_REPORT, 0)
        );

        let err = encode_fallback_events(InputFallbackEvent::MouseBtn {
            button: 99,
            pressed: true,
        })
        .unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::InvalidInput);

        let (key_events, key_count) = encode_fallback_events(InputFallbackEvent::KeyEvent {
            linux_keycode: 28,
            pressed: false,
        })
        .unwrap();
        assert_eq!(key_count, 2);
        assert_eq!(
            (key_events[0].type_, key_events[0].code, key_events[0].value),
            (uapi::EV_KEY, 28, 0)
        );
        assert_eq!(
            (key_events[1].type_, key_events[1].code, key_events[1].value),
            (uapi::EV_SYN, uapi::SYN_REPORT, 0)
        );

        assert_eq!(
            encode_fallback_events(InputFallbackEvent::KeyEvent {
                linux_keycode: 0,
                pressed: true,
            })
            .unwrap_err()
            .kind(),
            io::ErrorKind::InvalidInput
        );
    }

    #[test]
    fn uinput_missing_device_reports_clear_enoent() {
        let err = UinputFallbackDevice::create_at(Path::new("/dev/nonexistent-uinput-test"))
            .unwrap_err();
        assert_eq!(err.kind(), io::ErrorKind::NotFound);
        assert!(err.to_string().contains("uinput"));
    }

    #[test]
    fn mac_and_bustype_helpers_normalize_inputs() {
        assert_eq!(normalize_mac("AA:BB:cc:DD-11:22"), "aabbccdd1122");
        assert_eq!(parse_bustype_hex("0005\n"), Some(uapi::BUS_BLUETOOTH));
        assert_eq!(parse_bustype_hex("0x0005"), Some(uapi::BUS_BLUETOOTH));
        assert_eq!(parse_bustype_hex("0003"), Some(0x0003));
        assert_eq!(parse_bustype_hex("xyz"), None);
    }
}
