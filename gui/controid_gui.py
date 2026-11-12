#!/usr/bin/env python3
"""Controid Control Center — GTK4 / Libadwaita native Linux interface."""

from __future__ import annotations

import argparse
import html
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Any

try:
    import tomllib
except ImportError as exc:  # pragma: no cover
    raise SystemExit(f"Python >= 3.11 required (missing tomllib): {exc}") from exc

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("Gdk", "4.0")
from gi.repository import Adw, Gdk, Gio, GLib, Gtk, Pango

APP_ID = "org.controid.ControlCenter"
SERVICE_NAME = "controid.service"
DEFAULT_SERVICE_UUID = "0000a001-0000-1000-8000-00805f9b34fb"
MAX_IPC_LINE_BYTES = 64 * 1024

STATE_LABELS = {
    "idle": "En espera (desconectado)",
    "connecting_ble": "Conectando por Bluetooth LE…",
    "connecting_wifi": "Conectando por Wi-Fi / LAN…",
    "authenticating": "Autenticando sesión (Ed25519)…",
    "connected": "Conectado",
    "suspended": "Suspendido",
    "offline": "Demonio detenido",
}


def xdg_config_home() -> Path:
    return Path(os.environ.get("XDG_CONFIG_HOME") or (Path.home() / ".config"))


def xdg_state_home() -> Path:
    return Path(os.environ.get("XDG_STATE_HOME") or (Path.home() / ".local" / "state"))


def xdg_runtime_dir() -> Path:
    return Path(os.environ.get("XDG_RUNTIME_DIR") or "/tmp")


def config_path() -> Path:
    return xdg_config_home() / "controid" / "config.toml"


def socket_path() -> Path:
    return xdg_runtime_dir() / "controid" / "daemon.sock"


def daemon_log_path() -> Path:
    return xdg_state_home() / "controid" / "controidd.log"


def autostart_desktop_path() -> Path:
    return xdg_config_home() / "autostart" / "controidd.desktop"


def resolve_controid_bin(tool: str) -> str | None:
    found = shutil.which(tool)
    if found:
        return found
    candidate = Path.home() / ".local" / "bin" / tool
    if candidate.is_file() and os.access(candidate, os.X_OK):
        return str(candidate)
    return None


def detect_session_kind() -> str:
    if os.environ.get("WAYLAND_DISPLAY"):
        return "wayland"
    if os.environ.get("DISPLAY"):
        return "x11"
    return "headless"


def has_systemd_user() -> bool:
    if not Path("/run/systemd/system").is_dir() or not shutil.which("systemctl"):
        return False
    rc, _, _ = run_cmd(["systemctl", "--user", "show-environment"], timeout=1.5)
    return rc == 0


def default_config() -> dict[str, Any]:
    return {
        "device": {
            "mac_address": "",
            "service_uuid": DEFAULT_SERVICE_UUID,
            "hotspot_ssid": "",
            "peer_ed25519_pubkey_hex": None,
        },
        "network": {
            "reconnect_cooldown_secs": 30,
            "max_retries": 4,
            "tcp_port": 55777,
            "gateway_ip": "192.168.43.1",
            "connection_mode": "auto",
            "direct_host": "",
        },
        "streaming": {
            "max_fps": 60,
            "bitrate_mbps": 8,
            "codec_mime": "video/avc",
        },
        "files": {
            "mount_point": "~/Controid",
            "auto_mount": True,
        },
    }


def deep_merge_config(base: dict[str, Any], patch: dict[str, Any]) -> dict[str, Any]:
    out = json.loads(json.dumps(base))
    for section in ("device", "network", "streaming", "files"):
        src = patch.get(section)
        if isinstance(src, dict):
            out[section].update({k: v for k, v in src.items() if k in out[section]})
    return out


def load_config_file() -> dict[str, Any]:
    cfg = default_config()
    path = config_path()
    if path.exists():
        with path.open("rb") as fh:
            parsed = tomllib.load(fh)
        cfg = deep_merge_config(cfg, parsed)
    return cfg


def _toml_quote(value: str) -> str:
    escaped = (
        value.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    )
    return f'"{escaped}"'


def save_config_file(cfg: dict[str, Any]) -> Path:
    path = config_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    os.chmod(path.parent, 0o700)

    dev = cfg["device"]
    net = cfg["network"]
    st = cfg["streaming"]
    files_cfg = cfg["files"]

    lines = [
        "[device]",
        f"mac_address = {_toml_quote(str(dev.get('mac_address', '')).strip().upper())}",
        f"service_uuid = {_toml_quote(str(dev.get('service_uuid', DEFAULT_SERVICE_UUID)).strip() or DEFAULT_SERVICE_UUID)}",
        f"hotspot_ssid = {_toml_quote(str(dev.get('hotspot_ssid', '')).strip())}",
    ]
    pubkey = dev.get("peer_ed25519_pubkey_hex")
    if isinstance(pubkey, str) and pubkey.strip():
        lines.append(f"peer_ed25519_pubkey_hex = {_toml_quote(pubkey.strip())}")

    lines.extend(
        [
            "",
            "[network]",
            f"reconnect_cooldown_secs = {int(net.get('reconnect_cooldown_secs', 30))}",
            f"max_retries = {int(net.get('max_retries', 4))}",
            f"tcp_port = {int(net.get('tcp_port', 55777))}",
            f"gateway_ip = {_toml_quote(str(net.get('gateway_ip', '192.168.43.1')).strip() or '192.168.43.1')}",
            f"connection_mode = {_toml_quote(str(net.get('connection_mode', 'auto')).strip().lower() or 'auto')}",
            f"direct_host = {_toml_quote(str(net.get('direct_host', '')).strip())}",
            "",
            "[streaming]",
            f"max_fps = {int(st.get('max_fps', 60))}",
            f"bitrate_mbps = {int(st.get('bitrate_mbps', 8))}",
            f"codec_mime = {_toml_quote(str(st.get('codec_mime', 'video/avc')).strip() or 'video/avc')}",
            "",
            "[files]",
            f"mount_point = {_toml_quote(str(files_cfg.get('mount_point', '~/Controid')).strip() or '~/Controid')}",
            f"auto_mount = {'true' if bool(files_cfg.get('auto_mount', True)) else 'false'}",
            "",
        ]
    )

    tmp = path.with_name(f".config.toml.tmp.{os.getpid()}")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
        fh.flush()
        os.fsync(fh.fileno())
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)
    os.chmod(path, 0o600)
    return path


class IpcError(RuntimeError):
    def __init__(self, code: int, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


def ipc_call(method: str, params: dict[str, Any] | None = None, timeout: float = 10.0) -> Any:
    sock_file = socket_path()
    if not sock_file.exists():
        raise FileNotFoundError(f"Socket IPC no encontrado: {sock_file}")

    req = {"id": 1, "method": method, "params": params or {}}
    payload = (json.dumps(req) + "\n").encode("utf-8")

    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout)
        sock.connect(str(sock_file))
        sock.sendall(payload)

        chunks: list[bytes] = []
        total = 0
        while True:
            data = sock.recv(4096)
            if not data:
                break
            chunks.append(data)
            total += len(data)
            if b"\n" in data:
                break
            if total > MAX_IPC_LINE_BYTES:
                raise RuntimeError("Respuesta IPC excede el límite de 64 KiB")

    raw = b"".join(chunks).split(b"\n", 1)[0].decode("utf-8", errors="replace").strip()
    if not raw:
        raise RuntimeError("El demonio cerró la conexión IPC sin responder")
    resp = json.loads(raw)
    if resp.get("error"):
        err = resp["error"]
        raise IpcError(int(err.get("code", -1)), str(err.get("message", "Error IPC")))
    return resp.get("result")


def run_cmd(args: list[str], timeout: float = 5.0) -> tuple[int, str, str]:
    try:
        proc = subprocess.run(
            args,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
        return proc.returncode, proc.stdout.strip(), proc.stderr.strip()
    except FileNotFoundError:
        return 127, "", f"Comando no encontrado: {args[0]}"
    except subprocess.TimeoutExpired:
        return 124, "", f"Tiempo agotado ejecutando: {' '.join(args)}"


def get_paired_bt_devices() -> list[dict[str, Any]]:
    rc, out, _ = run_cmd(["bluetoothctl", "devices", "Paired"], timeout=3.0)
    if rc != 0 or not out:
        rc, out, _ = run_cmd(["bluetoothctl", "devices", "Bonded"], timeout=3.0)
    devices: list[dict[str, Any]] = []
    if rc == 0 and out:
        for line in out.splitlines():
            m = re.match(r"^Device\s+([0-9A-Fa-f:]{17})\s*(.*)$", line.strip())
            if m:
                mac = m.group(1).upper()
                name = m.group(2).strip() or None
                devices.append(
                    {
                        "mac_address": mac,
                        "name": name,
                        "paired": True,
                        "connected": False,
                    }
                )
    return devices


def _strip_ansi(text: str) -> str:
    return re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", text)


def get_saved_wifi_ssids() -> list[str]:
    ssids: list[str] = []
    if shutil.which("nmcli"):
        rc, out, _ = run_cmd(
            ["nmcli", "-t", "-f", "NAME,TYPE", "connection", "show"],
            timeout=3.0,
        )
        if rc == 0 and out:
            for line in out.splitlines():
                parts = line.rsplit(":", 1)
                if len(parts) == 2 and parts[1] == "802-11-wireless" and parts[0]:
                    ssids.append(parts[0])
    if not ssids and shutil.which("iwctl"):
        rc, out, _ = run_cmd(["iwctl", "known-networks", "list"], timeout=3.0)
        if rc == 0 and out:
            for raw in _strip_ansi(out).splitlines():
                line = raw.strip()
                if (
                    not line
                    or line.startswith("Known")
                    or line.startswith("-")
                    or line.startswith("Name")
                ):
                    continue
                # Split on 2+ spaces to preserve SSIDs containing single spaces
                cols = re.split(r"\s{2,}", line)
                if cols and cols[0]:
                    ssids.append(cols[0].strip())
    return sorted(set(ssids), key=str.casefold)


def detect_adb_phone_lan_ip() -> tuple[str | None, str]:
    if not shutil.which("adb"):
        return None, "adb no está instalado en PATH"
    rc, out, err = run_cmd(["adb", "devices", "-l"], timeout=4.0)
    if rc != 0:
        return None, err or out or "Error ejecutando adb devices"
    online_serials: list[str] = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            online_serials.append(parts[0])
    if not online_serials:
        return None, "No hay ningún teléfono conectado por ADB"

    serial = online_serials[0]
    rc, ip_out, ip_err = run_cmd(
        ["adb", "-s", serial, "shell", "ip", "-4", "-o", "addr", "show", "wlan0"],
        timeout=4.0,
    )
    if rc != 0:
        return None, ip_err or ip_out or "No se pudo consultar wlan0 en el teléfono"
    m = re.search(r"inet\s+(\d+\.\d+\.\d+\.\d+)/", ip_out)
    if m:
        return m.group(1), f"Detectada IP LAN {m.group(1)} ({serial})"
    return None, f"La interfaz wlan0 del teléfono ({serial}) no tiene IPv4 asignada"


def collect_diagnostics() -> tuple[list[tuple[str, str, bool]], str | None]:
    checks: list[tuple[str, str, bool]] = []
    sock_exists = socket_path().exists()
    systemd_user = has_systemd_user()
    if systemd_user:
        rc_srv, srv_out, _ = run_cmd(["systemctl", "--user", "is-active", SERVICE_NAME], timeout=3.0)
        rc_ena, ena_out, _ = run_cmd(["systemctl", "--user", "is-enabled", SERVICE_NAME], timeout=3.0)
        checks.append(
            (
                "Servicio systemd usuario (controid.service)",
                f"estado={srv_out or 'inactivo'} | arranque={ena_out or 'deshabilitado'}",
                rc_srv == 0,
            )
        )
    else:
        rc_pg, _, _ = run_cmd(["pgrep", "-x", "controidd"], timeout=2.0)
        autostart_on = autostart_desktop_path().is_file()
        daemon_up = rc_pg == 0 or sock_exists
        checks.append(
            (
                "Demonio local controidd (sin systemd)",
                f"proceso={'activo' if daemon_up else 'detenido'} | autostart XDG={'habilitado' if autostart_on else 'deshabilitado'}",
                daemon_up,
            )
        )
    checks.append(
        (
            "Socket IPC local",
            f"{socket_path()} ({'disponible' if sock_exists else 'no disponible'})",
            sock_exists,
        )
    )

    paired_bt = get_paired_bt_devices()
    if Path("/run/systemd/system").is_dir() and shutil.which("systemctl"):
        rc_bt, bt_out, _ = run_cmd(["systemctl", "is-active", "bluetooth.service"], timeout=3.0)
        bt_ok = rc_bt == 0
        bt_state = bt_out or "inactivo"
    else:
        rc_bt, _, _ = run_cmd(["pgrep", "-x", "bluetoothd"], timeout=2.0)
        bt_ok = rc_bt == 0
        bt_state = "activo (bluetoothd)" if bt_ok else "inactivo"
    checks.append(
        (
            "Bluetooth (BlueZ)",
            f"servicio={bt_state} | dispositivos emparejados={len(paired_bt)}",
            bt_ok,
        )
    )

    ssids = get_saved_wifi_ssids()
    nm_running = False
    if Path("/run/systemd/system").is_dir() and shutil.which("systemctl"):
        rc_nm, _, _ = run_cmd(["systemctl", "is-active", "NetworkManager.service"], timeout=3.0)
        nm_running = rc_nm == 0
    if not nm_running and shutil.which("nmcli"):
        rc_nm2, _, _ = run_cmd(["nmcli", "-t", "general", "status"], timeout=2.5)
        nm_running = rc_nm2 == 0
    if nm_running:
        checks.append(
            (
                "Red Wi-Fi (NetworkManager)",
                f"activo | perfiles Wi-Fi={len(ssids)}",
                True,
            )
        )
    else:
        rc_iwd, _, _ = run_cmd(["pgrep", "-x", "iwd"], timeout=2.0)
        if rc_iwd == 0:
            checks.append(
                (
                    "Red Wi-Fi (iwd / iwctl)",
                    f"iwd activo | redes conocidas={len(ssids)}",
                    True,
                )
            )
        else:
            checks.append(
                (
                    "Gestor Wi-Fi (modo directo LAN disponible)",
                    "Sin NetworkManager ni iwd activos; el modo Wi-Fi hotspot automático requiere NM/iwd (LAN directa operativa)",
                    True,
                )
            )

    uinput = Path("/dev/uinput")
    uinput_rw = uinput.exists() and os.access(uinput, os.W_OK)
    checks.append(
        (
            "/dev/uinput (entrada fallback Linux)",
            "escribible" if uinput_rw else ("sin permiso de escritura (ejecuta ./install.sh --setup-uinput)" if uinput.exists() else "no presente"),
            uinput_rw,
        )
    )

    session = detect_session_kind()
    clip_tools: list[str] = []
    if shutil.which("wl-paste"):
        clip_tools.append("wl-paste")
    if shutil.which("xclip"):
        clip_tools.append("xclip")
    if shutil.which("xsel"):
        clip_tools.append("xsel")
    checks.append(
        (
            f"Sesión gráfica ({session}) y portapapeles",
            f"herramientas CLI: {', '.join(clip_tools) if clip_tools else 'solo GTK4/nativo del demonio'}",
            session != "headless",
        )
    )

    for tool, label in (
        ("controid-viewer", "Visor interactivo (controid-viewer)"),
        ("controid-mount", "Servidor FUSE local (controid-mount)"),
        ("fusermount3", "Montaje FUSE3 (fusermount3)"),
        ("mpv", "Visor fallback (mpv)"),
        ("controid", "CLI controid"),
    ):
        found = resolve_controid_bin(tool)
        checks.append((label, found or "No encontrado en PATH", found is not None))

    detected_ip: str | None = None
    if shutil.which("adb"):
        rc_adb, adb_out, _ = run_cmd(["adb", "devices", "-l"], timeout=4.0)
        devs = [l.strip() for l in adb_out.splitlines()[1:] if "\tdevice" in l or " device " in l]
        if devs:
            serial = devs[0].split()[0]
            rc_pid, pid_out, _ = run_cmd(["adb", "-s", serial, "shell", "pidof", "com.controid"], timeout=3.0)
            app_running = rc_pid == 0 and bool(pid_out.strip())
            detected_ip, ip_msg = detect_adb_phone_lan_ip()
            checks.append(
                (
                    f"Android ADB ({serial})",
                    f"app={'activa (pid ' + pid_out.strip() + ')' if app_running else 'detenida'} | {ip_msg}",
                    app_running,
                )
            )
        else:
            checks.append(("Android ADB", "Sin dispositivo ADB activo", False))
    else:
        checks.append(("Android ADB", "adb no instalado (opcional)", False))

    return checks, detected_ip


class ControlRow(Adw.PreferencesRow):
    """Full-width title/subtitle above wrapped Gtk.FlowBox controls to avoid narrow-pane crushing."""

    def __init__(self, title: str, subtitle: str, widgets: list[Gtk.Widget]) -> None:
        super().__init__(activatable=False, focusable=False)
        outer = Gtk.Box(
            orientation=Gtk.Orientation.VERTICAL,
            spacing=8,
            margin_top=12,
            margin_bottom=12,
            margin_start=12,
            margin_end=12,
        )
        self._title_label = Gtk.Label(
            label=title,
            xalign=0.0,
            wrap=True,
            wrap_mode=Pango.WrapMode.WORD_CHAR,
            hexpand=True,
        )
        self._title_label.add_css_class("heading")
        outer.append(self._title_label)

        self._subtitle_label = Gtk.Label(
            label=subtitle,
            xalign=0.0,
            wrap=True,
            wrap_mode=Pango.WrapMode.WORD_CHAR,
            hexpand=True,
        )
        self._subtitle_label.add_css_class("caption")
        self._subtitle_label.add_css_class("dim-label")
        self._subtitle_label.set_visible(bool(subtitle))
        outer.append(self._subtitle_label)

        flow = Gtk.FlowBox(
            selection_mode=Gtk.SelectionMode.NONE,
            row_spacing=6,
            column_spacing=8,
            homogeneous=False,
            min_children_per_line=1,
            max_children_per_line=6,
            hexpand=True,
        )
        for widget in widgets:
            flow.append(Gtk.FlowBoxChild(child=widget, focusable=False))
        outer.append(flow)
        self.set_child(outer)

    def set_title(self, title: str) -> None:
        self._title_label.set_text(title)

    def set_subtitle(self, subtitle: str) -> None:
        self._subtitle_label.set_text(subtitle)
        self._subtitle_label.set_visible(bool(subtitle))


class ControidWindow(Adw.ApplicationWindow):
    def __init__(self, app: Adw.Application) -> None:
        super().__init__(application=app, title="Controid — Centro de Control")
        self.set_icon_name("controid")
        self.set_size_request(360, 480)
        self.set_default_size(900, 700)

        self.daemon_online = False
        self.telemetry: dict[str, Any] = {
            "state": "idle",
            "battery": None,
            "charging": False,
            "tethering": False,
            "hid_connected": False,
        }
        self.files_status: dict[str, Any] = {
            "mounted": False,
            "mount_point": str(Path.home() / "Controid"),
            "connected": False,
            "error": None,
        }
        self.last_error: str | None = None
        self.stream_active = False
        self.stream_resolution: str | None = None
        self.config_data = default_config()
        self.discovered_devices: list[dict[str, Any]] = []
        self.event_log_lines: list[str] = []
        self._stop_flag = threading.Event()
        self._suppress_settings_dirty = False

        self.toast_overlay = Adw.ToastOverlay()
        toolbar_view = Adw.ToolbarView()
        self.toast_overlay.set_child(toolbar_view)
        self.set_content(self.toast_overlay)

        header = Adw.HeaderBar()
        self.view_stack = Adw.ViewStack()
        self.view_switcher = Adw.ViewSwitcher(
            stack=self.view_stack,
            policy=Adw.ViewSwitcherPolicy.WIDE,
        )
        self.window_title = Adw.WindowTitle(title="Controid", subtitle="")
        header.set_title_widget(self.view_switcher)

        self.header_badge = Gtk.Label(label="Iniciando…")
        self.header_badge.add_css_class("caption")
        header.pack_end(self.header_badge)
        toolbar_view.add_top_bar(header)

        self.switcher_bar = Adw.ViewSwitcherBar(stack=self.view_stack, reveal=False)
        toolbar_view.add_bottom_bar(self.switcher_bar)

        bp = Adw.Breakpoint.new(Adw.BreakpointCondition.parse("max-width: 700sp"))
        bp.add_setter(self.switcher_bar, "reveal", True)
        bp.add_setter(header, "title-widget", self.window_title)
        self.add_breakpoint(bp)

        self.error_banner = Adw.Banner(title="")
        self.error_banner.set_button_label("Limpiar")
        self.error_banner.connect("button-clicked", lambda _b: self._clear_error_banner())

        main_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        main_box.append(self.error_banner)
        self.view_stack.set_vexpand(True)
        main_box.append(self.view_stack)
        toolbar_view.set_content(main_box)

        self._build_panel_tab()
        self._build_clipboard_tab()
        self._build_settings_tab()
        self._build_diagnostics_tab()

        self.connect("close-request", self._on_close_request)

        self._load_config_async()
        self._refresh_status_once()
        self._refresh_systemd_status()
        self._refresh_diagnostics()
        self._refresh_journal()
        self._load_paired_devices_initial()

        self._event_thread = threading.Thread(target=self._ipc_event_loop, daemon=True)
        self._event_thread.start()

        GLib.timeout_add_seconds(5, self._periodic_poll)

    # ------------------------------------------------------------------ UI Tabs

    def _make_control_row(self, title: str, subtitle: str, widgets: list[Gtk.Widget]) -> ControlRow:
        return ControlRow(title, subtitle, widgets)

    def _build_panel_tab(self) -> None:
        page = Adw.PreferencesPage()
        page.set_title("Panel")
        page.set_icon_name("network-wireless-symbolic")

        # Status & telemetry group
        status_group = Adw.PreferencesGroup(
            title="Estado del enlace y dispositivo",
            description="Telemetría en tiempo real del demonio controidd",
        )
        self.row_link_state = Adw.ActionRow(title="Estado de conexión")
        self.row_link_state.set_subtitle("Comprobando…")
        status_group.add(self.row_link_state)

        self.row_battery = Adw.ActionRow(title="Batería del teléfono")
        self.row_battery.set_subtitle("No disponible")
        status_group.add(self.row_battery)

        self.row_hid = Adw.ActionRow(title="Control HID (Teclado / Ratón BT)")
        self.row_hid.set_subtitle("Desconectado")
        status_group.add(self.row_hid)

        self.row_stream = Adw.ActionRow(title="Transmisión de pantalla")
        self.row_stream.set_subtitle("Inactiva")
        status_group.add(self.row_stream)

        self.row_endpoint = Adw.ActionRow(title="Destino configurado")
        self.row_endpoint.set_subtitle("Sin configurar")
        status_group.add(self.row_endpoint)
        page.add(status_group)

        # Quick controls group
        actions_group = Adw.PreferencesGroup(
            title="Control de conexión y pantalla",
            description="Usa Wi-Fi LAN directa si está configurada o activa el Hotspot del móvil vía BLE",
        )
        self.btn_connect = Gtk.Button(label="Conectar")
        self.btn_connect.add_css_class("suggested-action")
        self.btn_connect.connect("clicked", lambda _b: self._send_ipc_action("connection.resume", timeout=30.0))
        self.btn_disconnect = Gtk.Button(label="Desconectar")
        self.btn_disconnect.connect("clicked", lambda _b: self._send_ipc_action("connection.disconnect", timeout=15.0))
        actions_group.add(
            self._make_control_row(
                "Enlace Controid",
                "Iniciar o detener la sesión TCP autenticada con el móvil",
                [self.btn_connect, self.btn_disconnect],
            )
        )

        self.btn_hs_on = Gtk.Button(label="Activar Hotspot")
        self.btn_hs_on.connect("clicked", lambda _b: self._send_ipc_action("hotspot.enable", timeout=30.0))
        self.btn_hs_off = Gtk.Button(label="Apagar Hotspot")
        self.btn_hs_off.connect("clicked", lambda _b: self._send_ipc_action("hotspot.disable", timeout=15.0))
        actions_group.add(
            self._make_control_row(
                "Hotspot Wi-Fi móvil (vía Bluetooth LE)",
                "Fuerza activar o apagar el punto de acceso Wi-Fi en Android (requiere Shizuku)",
                [self.btn_hs_on, self.btn_hs_off],
            )
        )

        self.btn_stream_start = Gtk.Button(label="Iniciar pantalla")
        self.btn_stream_start.add_css_class("suggested-action")
        self.btn_stream_start.connect("clicked", lambda _b: self._start_stream())
        self.btn_stream_stop = Gtk.Button(label="Detener pantalla")
        self.btn_stream_stop.connect("clicked", lambda _b: self._send_ipc_action("stream.stop", timeout=10.0))
        actions_group.add(
            self._make_control_row(
                "Duplicar y controlar pantalla (controid-viewer)",
                "Inicia captura de hardware H.264/H.265 con entrada interactiva (ratón, teclado y scroll)",
                [self.btn_stream_start, self.btn_stream_stop],
            )
        )

        self.nav_buttons: list[Gtk.Button] = []
        for label, key_name in (
            ("Atrás", "back"),
            ("Inicio", "home"),
            ("Recientes", "recents"),
            ("Encender", "power"),
        ):
            nav_btn = Gtk.Button(label=label)
            nav_btn.connect(
                "clicked",
                lambda _b, k=key_name: self._send_ipc_action("input.key", {"key": k}, timeout=5.0),
            )
            self.nav_buttons.append(nav_btn)
        actions_group.add(
            self._make_control_row(
                "Botones de navegación remota",
                "Envía teclas de sistema directamente al dispositivo Android conectado",
                self.nav_buttons,
            )
        )
        page.add(actions_group)

        # FUSE files storage group
        files_group = Adw.PreferencesGroup(
            title="Almacenamiento del teléfono (Disco FUSE)",
            description="Monta la memoria interna del móvil como una carpeta local de Linux",
        )
        self.btn_files_mount = Gtk.Button(label="Montar carpeta")
        self.btn_files_mount.add_css_class("suggested-action")
        self.btn_files_mount.connect("clicked", lambda _b: self._send_ipc_action("files.mount", timeout=15.0))
        self.btn_files_unmount = Gtk.Button(label="Desmontar")
        self.btn_files_unmount.connect("clicked", lambda _b: self._send_ipc_action("files.unmount", timeout=15.0))
        self.btn_files_open = Gtk.Button(label="Abrir en gestor")
        self.btn_files_open.connect("clicked", lambda _b: self._open_mount_folder())
        self.row_files = self._make_control_row(
            "Carpeta remota Android",
            f"Desmontado ({Path.home() / 'Controid'})",
            [self.btn_files_mount, self.btn_files_unmount, self.btn_files_open],
        )
        files_group.add(self.row_files)
        page.add(files_group)

        # Background daemon / systemd service group
        sys_group = Adw.PreferencesGroup(title="Servicio en segundo plano")
        sys_buttons: list[Gtk.Widget] = []
        for label, action in (
            ("Iniciar", "start"),
            ("Reiniciar", "restart"),
            ("Detener", "stop"),
            ("Habilitar autoarranque", "enable"),
        ):
            btn = Gtk.Button(label=label)
            btn.connect("clicked", lambda _b, a=action: self._run_systemd_action(a))
            sys_buttons.append(btn)
        self.row_systemd = self._make_control_row(
            "controid.service" if has_systemd_user() else "controidd",
            "Consultando estado…",
            sys_buttons,
        )
        sys_group.add(self.row_systemd)

        tray_btn = Gtk.Button(label="Iniciar bandeja")
        tray_btn.connect("clicked", lambda _b: self._launch_tray())
        self.row_tray = self._make_control_row(
            "Icono en bandeja del sistema (controid-tray)",
            "Permite acceso rápido desde Waybar, KDE, XFCE u otras bandejas SNI",
            [tray_btn],
        )
        sys_group.add(self.row_tray)
        page.add(sys_group)

        stack_page = self.view_stack.add_titled(page, "panel", "Panel")
        stack_page.set_icon_name("network-wireless-symbolic")

    def _build_clipboard_tab(self) -> None:
        page = Adw.PreferencesPage()
        page.set_title("Portapapeles")

        send_group = Adw.PreferencesGroup(
            title="Enviar al portapapeles de Android",
            description="También puedes copiar texto normalmente en Wayland cuando la sesión esté conectada",
        )
        text_frame = Gtk.Frame()
        scroll = Gtk.ScrolledWindow(min_content_height=130, max_content_height=220)
        self.clip_text_view = Gtk.TextView(wrap_mode=Gtk.WrapMode.WORD_CHAR)
        self.clip_text_view.set_top_margin(8)
        self.clip_text_view.set_bottom_margin(8)
        self.clip_text_view.set_left_margin(10)
        self.clip_text_view.set_right_margin(10)
        scroll.set_child(self.clip_text_view)
        text_frame.set_child(scroll)
        send_group.add(text_frame)

        self.clip_mime_dropdown = Gtk.DropDown.new_from_strings(["text/plain", "text/html", "text/uri-list"])
        btn_paste = Gtk.Button(label="Pegar del PC")
        btn_paste.connect("clicked", lambda _b: self._paste_local_clipboard())
        btn_send = Gtk.Button(label="Enviar al móvil")
        btn_send.add_css_class("suggested-action")
        btn_send.connect("clicked", lambda _b: self._send_clipboard_text())
        ctrl_row = self._make_control_row(
            "Formato y envío",
            "Selecciona tipo MIME y envía el contenido al teléfono",
            [self.clip_mime_dropdown, btn_paste, btn_send],
        )
        send_group.add(ctrl_row)
        page.add(send_group)

        hist_group = Adw.PreferencesGroup(
            title="Historial de transferencias (sesión actual)",
            description="Elementos recibidos desde Android o enviados desde Linux",
        )
        clear_btn = Gtk.Button(label="Limpiar historial")
        clear_btn.connect("clicked", lambda _b: self._clear_clipboard_history())
        hist_group.set_header_suffix(clear_btn)

        self.clip_history_list = Gtk.ListBox()
        self.clip_history_list.add_css_class("boxed-list")
        self.clip_history_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self.clip_empty_row = Adw.ActionRow(
            title="Sin transferencias registradas aún",
            subtitle="Los elementos aparecerán aquí al recibir o enviar contenido",
        )
        self.clip_history_list.append(self.clip_empty_row)
        hist_group.add(self.clip_history_list)
        page.add(hist_group)

        stack_page = self.view_stack.add_titled(page, "clipboard", "Portapapeles")
        stack_page.set_icon_name("edit-paste-symbolic")

    def _build_settings_tab(self) -> None:
        page = Adw.PreferencesPage()
        page.set_title("Ajustes")

        # Device & BLE discovery group
        dev_group = Adw.PreferencesGroup(
            title="Dispositivo Android y Bluetooth LE",
            description="Selecciona un móvil emparejado en Bluetooth o escanea anunciantes Controid",
        )
        scan_btn = Gtk.Button(label="Escanear BLE (8s)")
        scan_btn.connect("clicked", lambda _b: self._scan_ble_devices())
        dev_group.set_header_suffix(scan_btn)

        self.ble_devices_list = Gtk.ListBox()
        self.ble_devices_list.add_css_class("boxed-list")
        self.ble_devices_list.set_selection_mode(Gtk.SelectionMode.NONE)
        dev_group.add(self.ble_devices_list)

        self.entry_mac = Adw.EntryRow(title="Dirección MAC Bluetooth (enlazada)")
        self.entry_uuid = Adw.EntryRow(title="UUID de servicio GATT BLE")

        self.entry_ssid = Adw.EntryRow(title="SSID del Hotspot Wi-Fi Android")
        ssid_pick_btn = Gtk.Button(
            icon_name="view-list-symbolic",
            valign=Gtk.Align.CENTER,
            tooltip_text="Elegir red Wi-Fi guardada",
        )
        ssid_pick_btn.add_css_class("flat")
        ssid_pick_btn.connect("clicked", self._on_pick_saved_ssid)
        self.entry_ssid.add_suffix(ssid_pick_btn)

        clear_key_btn = Gtk.Button(label="Restablecer clave")
        clear_key_btn.connect("clicked", lambda _b: self._reset_peer_pubkey())
        self.row_pubkey = self._make_control_row(
            "Clave pública Ed25519 del teléfono (TOFU)",
            "Sin clave guardada (se fijará en la primera conexión)",
            [clear_key_btn],
        )

        for w in (self.entry_mac, self.entry_uuid, self.entry_ssid, self.row_pubkey):
            dev_group.add(w)
        page.add(dev_group)

        # Network & Mode group
        net_group = Adw.PreferencesGroup(
            title="Red y Modo de Conexión",
            description="En modo Automático usa Wi-Fi LAN directa si está accesible y reserva BLE+Hotspot para cuando estés fuera de casa",
        )
        mode_model = Gtk.StringList.new(
            [
                "Automático (LAN directa -> BLE + Hotspot)",
                "Solo Wi-Fi LAN directa",
                "Solo BLE + Hotspot Android",
            ]
        )
        self.combo_mode = Adw.ComboRow(title="Modo de conexión", model=mode_model)

        self.entry_direct_host = Adw.EntryRow(title="IP Wi-Fi LAN directa del teléfono (direct_host)")
        adb_ip_btn = Gtk.Button(
            icon_name="network-wired-symbolic",
            valign=Gtk.Align.CENTER,
            tooltip_text="Detectar IP LAN por ADB",
        )
        adb_ip_btn.add_css_class("flat")
        adb_ip_btn.connect("clicked", lambda _b: self._detect_lan_ip_via_adb())
        self.entry_direct_host.add_suffix(adb_ip_btn)

        self.entry_gateway_ip = Adw.EntryRow(title="IP Gateway Hotspot por defecto")
        self.spin_tcp_port = Adw.SpinRow.new_with_range(1, 65535, 1)
        self.spin_tcp_port.set_title("Puerto TCP de datos")
        self.spin_cooldown = Adw.SpinRow.new_with_range(1, 600, 5)
        self.spin_cooldown.set_title("Cooldown reconexión Bluetooth (segundos)")
        self.spin_retries = Adw.SpinRow.new_with_range(0, 50, 1)
        self.spin_retries.set_title("Máximo de reintentos automáticos")

        for w in (
            self.combo_mode,
            self.entry_direct_host,
            self.entry_gateway_ip,
            self.spin_tcp_port,
            self.spin_cooldown,
            self.spin_retries,
        ):
            net_group.add(w)
        page.add(net_group)

        # Video streaming settings group
        stream_group = Adw.PreferencesGroup(title="Transmisión de pantalla")
        self.spin_fps = Adw.SpinRow.new_with_range(15, 120, 5)
        self.spin_fps.set_title("FPS máximos")
        self.spin_bitrate = Adw.SpinRow.new_with_range(1, 50, 1)
        self.spin_bitrate.set_title("Bitrate (Mbps)")
        codec_model = Gtk.StringList.new(["H.264 (video/avc)", "H.265 / HEVC (video/hevc)"])
        self.combo_codec = Adw.ComboRow(title="Códec de vídeo", model=codec_model)

        for w in (self.spin_fps, self.spin_bitrate, self.combo_codec):
            stream_group.add(w)
        page.add(stream_group)

        # FUSE files settings group
        files_cfg_group = Adw.PreferencesGroup(
            title="Archivos FUSE",
            description="Acceso directo al almacenamiento del dispositivo en Linux",
        )
        self.entry_mount_point = Adw.EntryRow(title="Carpeta de montaje local (mount_point)")
        self.switch_auto_mount = Adw.SwitchRow(
            title="Montar almacenamiento automáticamente al conectar",
            subtitle="Se desmonta de forma segura al desconectar o suspender el equipo",
        )
        files_cfg_group.add(self.entry_mount_point)
        files_cfg_group.add(self.switch_auto_mount)
        page.add(files_cfg_group)

        # Save bar
        save_group = Adw.PreferencesGroup()
        reload_btn = Gtk.Button(label="Recargar")
        reload_btn.connect("clicked", lambda _b: self._load_config_async())
        save_btn = Gtk.Button(label="Guardar y aplicar")
        save_btn.add_css_class("suggested-action")
        save_btn.connect("clicked", lambda _b: self._save_config_async())
        save_row = self._make_control_row(
            "Aplicar configuración",
            f"Archivo: {config_path()}",
            [reload_btn, save_btn],
        )
        save_group.add(save_row)
        page.add(save_group)

        stack_page = self.view_stack.add_titled(page, "settings", "Dispositivos y Ajustes")
        stack_page.set_icon_name("preferences-system-symbolic")

    def _build_diagnostics_tab(self) -> None:
        page = Adw.PreferencesPage()
        page.set_title("Diagnóstico")

        health_group = Adw.PreferencesGroup(
            title="Salud del sistema y dependencias",
            description="Comprobación rápida de BlueZ, red (NetworkManager/iwd), sesión, FUSE3, visores y ADB",
        )
        btn_open_apk = Gtk.Button(label="Abrir app en móvil (ADB)")
        btn_open_apk.connect("clicked", lambda _b: self._launch_android_app_adb())
        btn_recheck = Gtk.Button(label="Volver a comprobar")
        btn_recheck.connect("clicked", lambda _b: self._refresh_diagnostics())
        health_group.add(
            self._make_control_row(
                "Acciones de diagnóstico",
                "Verifica herramientas instaladas o inicia la aplicación en el móvil",
                [btn_open_apk, btn_recheck],
            )
        )

        self.diag_list = Gtk.ListBox()
        self.diag_list.add_css_class("boxed-list")
        self.diag_list.set_selection_mode(Gtk.SelectionMode.NONE)
        health_group.add(self.diag_list)
        page.add(health_group)

        logs_group = Adw.PreferencesGroup(
            title="Registro de eventos y servicio",
            description="Eventos IPC en vivo y últimas líneas de controidd / journalctl",
        )
        lbox = Gtk.Box(spacing=6)
        btn_refresh_logs = Gtk.Button(label="Actualizar logs")
        btn_refresh_logs.connect("clicked", lambda _b: self._refresh_journal())
        lbox.append(btn_refresh_logs)
        logs_group.set_header_suffix(lbox)

        log_frame = Gtk.Frame()
        log_scroll = Gtk.ScrolledWindow(min_content_height=240, max_content_height=340)
        self.logs_view = Gtk.TextView(editable=False, monospace=True, wrap_mode=Gtk.WrapMode.WORD_CHAR)
        self.logs_view.set_top_margin(8)
        self.logs_view.set_bottom_margin(8)
        self.logs_view.set_left_margin(10)
        self.logs_view.set_right_margin(10)
        log_scroll.set_child(self.logs_view)
        log_frame.set_child(log_scroll)
        logs_group.add(log_frame)
        page.add(logs_group)

        stack_page = self.view_stack.add_titled(page, "diagnostics", "Diagnóstico y Logs")
        stack_page.set_icon_name("utilities-terminal-symbolic")

    # ------------------------------------------------------------------ Helpers

    def _toast(self, message: str) -> None:
        GLib.idle_add(lambda m=message: self.toast_overlay.add_toast(Adw.Toast.new(m)) or False)

    def _clear_error_banner(self) -> None:
        self.last_error = None
        self.error_banner.set_revealed(False)

    def _show_error(self, message: str) -> None:
        self.last_error = message
        self.error_banner.set_title(message)
        self.error_banner.set_revealed(True)

    def _on_close_request(self, _win: Gtk.Window) -> bool:
        self._stop_flag.set()
        return False

    def _periodic_poll(self) -> bool:
        if self._stop_flag.is_set():
            return False
        self._refresh_status_once()
        self._refresh_systemd_status()
        return True

    def _render_status(self) -> None:
        state_key = "offline" if not self.daemon_online else str(self.telemetry.get("state", "idle"))
        label = STATE_LABELS.get(state_key, state_key)
        self.header_badge.set_text(label)
        self.row_link_state.set_subtitle(label)

        bat = self.telemetry.get("battery")
        chg = bool(self.telemetry.get("charging", False))
        if isinstance(bat, int):
            self.row_battery.set_subtitle(f"{bat}% {'(Cargando)' if chg else '(En batería)'}")
        else:
            self.row_battery.set_subtitle("No disponible hasta conectar sesión")

        hid = bool(self.telemetry.get("hid_connected", False))
        teth = bool(self.telemetry.get("tethering", False))
        self.row_hid.set_subtitle(
            "Conectado vía Bluetooth HID nativo" if hid else ("Fallback TCP activo" if state_key == "connected" else "En espera")
        )
        self.row_stream.set_subtitle(
            f"Activa ({self.stream_resolution})" if self.stream_active and self.stream_resolution else (
                "Activa" if self.stream_active else ("Inactiva — Listo para transmitir" if state_key == "connected" else "Inactiva")
            )
        )

        dev = self.config_data.get("device", {})
        net = self.config_data.get("network", {})
        mode = str(net.get("connection_mode", "auto"))
        direct = str(net.get("direct_host", "")).strip() or "sin IP directa"
        mac = str(dev.get("mac_address", "")).strip() or "sin MAC"
        ssid = str(dev.get("hotspot_ssid", "")).strip() or "sin SSID"
        port = net.get("tcp_port", 55777)
        self.row_endpoint.set_subtitle(
            f"Modo: {mode} | LAN: {direct}:{port} | MAC BT: {mac} | Hotspot: {ssid} | Enlace red: {'OK' if teth else 'inactivo'}"
        )

        can_cmd = self.daemon_online
        is_connected = state_key == "connected"
        self.btn_connect.set_sensitive(can_cmd and not is_connected)
        self.btn_disconnect.set_sensitive(can_cmd and state_key != "idle")
        self.btn_hs_on.set_sensitive(can_cmd)
        self.btn_hs_off.set_sensitive(can_cmd)
        self.btn_stream_start.set_sensitive(can_cmd and is_connected and not self.stream_active)
        self.btn_stream_stop.set_sensitive(can_cmd and self.stream_active)
        for btn in self.nav_buttons:
            btn.set_sensitive(can_cmd and is_connected)

        files_mounted = bool(self.files_status.get("mounted", False))
        raw_mp = str(
            self.files_status.get("mount_point")
            or self.config_data.get("files", {}).get("mount_point")
            or "~/Controid"
        )
        mount_point = os.path.expanduser(raw_mp)
        files_err = self.files_status.get("error")
        if files_mounted:
            self.row_files.set_subtitle(f"Montado en {mount_point}")
        elif isinstance(files_err, str) and files_err:
            self.row_files.set_subtitle(f"Desmontado ({mount_point}) — Error: {files_err}")
        else:
            self.row_files.set_subtitle(f"Desmontado ({mount_point})")
        self.btn_files_mount.set_sensitive(can_cmd and is_connected and not files_mounted)
        self.btn_files_unmount.set_sensitive(can_cmd and files_mounted)
        self.btn_files_open.set_sensitive(files_mounted)

    def _open_mount_folder(self) -> None:
        raw_mp = str(
            self.files_status.get("mount_point")
            or self.config_data.get("files", {}).get("mount_point")
            or "~/Controid"
        )
        mount_point = Path(os.path.expanduser(raw_mp))
        if not mount_point.exists():
            self._toast(f"La carpeta no existe: {mount_point}")
            return
        try:
            subprocess.Popen(
                ["xdg-open", str(mount_point)],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
        except Exception as exc:
            self._toast(f"No se pudo abrir el gestor de archivos: {exc}")

    # ------------------------------------------------------------------ IPC & Async Tasks

    def _refresh_status_once(self) -> None:
        def worker() -> None:
            try:
                res = ipc_call("device.status", timeout=2.5)
                files_res: dict[str, Any] | None = None
                try:
                    fr = ipc_call("files.status", timeout=2.5)
                    if isinstance(fr, dict):
                        files_res = fr
                except Exception:
                    files_res = None
                if isinstance(res, dict):
                    GLib.idle_add(lambda r=res, f=files_res: self._on_status_loaded(r, f) or False)
            except Exception:
                GLib.idle_add(lambda: self._on_daemon_offline() or False)

        threading.Thread(target=worker, daemon=True).start()

    def _on_status_loaded(self, res: dict[str, Any], files_res: dict[str, Any] | None = None) -> None:
        self.daemon_online = True
        self.telemetry.update(res)
        if isinstance(files_res, dict):
            self.files_status.update(files_res)
        if res.get("state") == "connected":
            self._clear_error_banner()
        self._render_status()

    def _on_daemon_offline(self) -> None:
        self.daemon_online = False
        self.stream_active = False
        self.files_status["mounted"] = False
        self.files_status["connected"] = False
        self._render_status()

    def _send_ipc_action(self, method: str, params: dict[str, Any] | None = None, timeout: float = 15.0) -> None:
        def worker() -> None:
            try:
                res = ipc_call(method, params or {}, timeout=timeout)
                self._toast(f"Comando '{method}' enviado")
                GLib.idle_add(lambda: self._after_action(method, res) or False)
            except Exception as exc:
                msg = f"{method}: {exc}"
                GLib.idle_add(lambda m=msg: self._show_error(m) or False)
                self._toast(f"Error: {exc}")

        threading.Thread(target=worker, daemon=True).start()

    def _after_action(self, method: str, res: Any) -> None:
        self._append_event_log(f"CMD {method} -> {json.dumps(res) if res is not None else 'ok'}")
        if method in ("connection.disconnect", "hotspot.disable"):
            self._clear_error_banner()
        if method in ("files.mount", "files.unmount") and isinstance(res, dict):
            self.files_status.update(res)
            self._render_status()
        self._refresh_status_once()

    def _start_stream(self) -> None:
        fps = int(self.spin_fps.get_value())
        bitrate = int(self.spin_bitrate.get_value())
        codec = "h265" if self.combo_codec.get_selected() == 1 else "h264"
        self._send_ipc_action(
            "stream.start",
            {"max_fps": fps, "bitrate_mbps": bitrate, "codec": codec},
            timeout=15.0,
        )

    def _ipc_event_loop(self) -> None:
        while not self._stop_flag.is_set():
            sock_file = socket_path()
            if not sock_file.exists():
                time.sleep(2.0)
                continue
            try:
                with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
                    sock.settimeout(5.0)
                    sock.connect(str(sock_file))
                    sub = json.dumps({"id": 1, "method": "events.subscribe", "params": {}}) + "\n"
                    sock.sendall(sub.encode("utf-8"))
                    sock.settimeout(None)
                    buf = b""
                    ack_seen = False
                    while not self._stop_flag.is_set():
                        chunk = sock.recv(4096)
                        if not chunk:
                            break
                        buf += chunk
                        while b"\n" in buf:
                            raw_line, buf = buf.split(b"\n", 1)
                            text = raw_line.decode("utf-8", errors="replace").strip()
                            if not text:
                                continue
                            obj = json.loads(text)
                            if not ack_seen:
                                ack_seen = True
                                GLib.idle_add(lambda: self._refresh_status_once() or False)
                                continue
                            if "event" in obj:
                                GLib.idle_add(lambda ev=obj: self._handle_ipc_event(ev) or False)
            except Exception:
                time.sleep(2.0)

    def _handle_ipc_event(self, ev: dict[str, Any]) -> None:
        name = str(ev.get("event", ""))
        data = ev.get("data") if isinstance(ev.get("data"), dict) else {}
        self.daemon_online = True
        if name != "device.status":
            self._append_event_log(f"EVT {name}: {json.dumps(data, ensure_ascii=False)}")

        if name == "link.state_changed":
            st = str(data.get("state", "idle"))
            self.telemetry["state"] = st
            if st == "connected":
                self._clear_error_banner()
        elif name == "device.status":
            self.telemetry.update(data)
        elif name in ("error.tethering", "link.error"):
            msg = str(data.get("message") or "Error de conexión")
            code = data.get("code")
            self._show_error(f"[{code}] {msg}" if code else msg)
        elif name == "stream.state":
            self.stream_active = bool(data.get("active", False))
            w = int(data.get("width", 0) or 0)
            h = int(data.get("height", 0) or 0)
            self.stream_resolution = f"{w}x{h}" if w > 0 and h > 0 else None
        elif name == "files.state":
            self.files_status.update(data)
        elif name == "clipboard.received":
            mime = str(data.get("mime", "text/plain"))
            size_bytes = int(data.get("size_bytes") or data.get("bytes") or 0)
            text = data.get("text")
            file_path = data.get("file_path") if isinstance(data.get("file_path"), str) else None
            self._add_clipboard_history(
                "Recibido de Android",
                mime,
                text if isinstance(text, str) else None,
                size_bytes=size_bytes,
                file_path=file_path,
            )
        elif name == "config.changed":
            self._apply_loaded_config(data)

        self._render_status()

    # ------------------------------------------------------------------ Clipboard

    def _paste_local_clipboard(self) -> None:
        text_mimes = (
            "text/plain;charset=utf-8",
            "text/plain",
            "UTF8_STRING",
            "STRING",
            "TEXT",
        )

        def read_via_gdk_fallback() -> None:
            disp = Gdk.Display.get_default()
            if not disp:
                self._toast("No hay texto disponible en el portapapeles")
                return
            clip = disp.get_clipboard()
            formats = clip.get_formats()
            mime_types = formats.get_mime_types() if formats else []
            if any(m.startswith("image/") for m in mime_types) and not any(
                m in text_mimes or m.startswith("text/") for m in mime_types
            ):
                self._toast("El portapapeles contiene una imagen (se sincroniza automáticamente al copiar)")
                return

            def on_read(src: Gdk.Clipboard, res: Gio.AsyncResult) -> None:
                try:
                    text = src.read_text_finish(res)
                except Exception:
                    text = None
                if text:
                    self.clip_text_view.get_buffer().set_text(text)
                else:
                    self._toast("No hay texto disponible en el portapapeles")

            clip.read_text_async(None, on_read)

        def worker() -> None:
            session = detect_session_kind()
            if session == "wayland" and shutil.which("wl-paste"):
                rc_types, types_out, _ = run_cmd(["wl-paste", "--list-types"], timeout=2.0)
                offered = [line.strip() for line in types_out.splitlines() if line.strip()] if rc_types == 0 else []
                chosen_text_mime = next((m for m in text_mimes if m in offered), None)
                if chosen_text_mime:
                    rc, out, _ = run_cmd(["wl-paste", "--type", chosen_text_mime, "--no-newline"], timeout=2.0)
                    if rc == 0 and out:
                        GLib.idle_add(lambda o=out: self.clip_text_view.get_buffer().set_text(o) or False)
                        return
                if any(m.startswith("image/") for m in offered):
                    GLib.idle_add(
                        lambda: self._toast("El portapapeles contiene una imagen (se sincroniza automáticamente al copiar)")
                        or False
                    )
                    return
                if not offered:
                    rc, out, _ = run_cmd(["wl-paste", "--no-newline"], timeout=2.0)
                    if rc == 0 and out:
                        GLib.idle_add(lambda o=out: self.clip_text_view.get_buffer().set_text(o) or False)
                        return

            if os.environ.get("DISPLAY"):
                if shutil.which("xclip"):
                    rc_t, t_out, _ = run_cmd(
                        ["xclip", "-o", "-selection", "clipboard", "-t", "TARGETS"],
                        timeout=2.0,
                    )
                    offered_x = [l.strip() for l in t_out.splitlines() if l.strip()] if rc_t == 0 else []
                    chosen_x = next((m for m in text_mimes if m in offered_x), None)
                    if chosen_x:
                        rc_x, x_out, _ = run_cmd(
                            ["xclip", "-o", "-selection", "clipboard", "-t", chosen_x],
                            timeout=2.0,
                        )
                        if rc_x == 0 and x_out:
                            GLib.idle_add(lambda o=x_out: self.clip_text_view.get_buffer().set_text(o) or False)
                            return
                    if any(m.startswith("image/") for m in offered_x):
                        GLib.idle_add(
                            lambda: self._toast("El portapapeles contiene una imagen (se sincroniza automáticamente al copiar)")
                            or False
                        )
                        return
                    rc_x, x_out, _ = run_cmd(["xclip", "-o", "-selection", "clipboard"], timeout=2.0)
                    if rc_x == 0 and x_out:
                        GLib.idle_add(lambda o=x_out: self.clip_text_view.get_buffer().set_text(o) or False)
                        return
                elif shutil.which("xsel"):
                    rc_s, s_out, _ = run_cmd(["xsel", "-ob"], timeout=2.0)
                    if rc_s == 0 and s_out:
                        GLib.idle_add(lambda o=s_out: self.clip_text_view.get_buffer().set_text(o) or False)
                        return

            GLib.idle_add(lambda: read_via_gdk_fallback() or False)

        threading.Thread(target=worker, daemon=True).start()

    def _send_clipboard_text(self) -> None:
        buf = self.clip_text_view.get_buffer()
        start, end = buf.get_bounds()
        text = buf.get_text(start, end, False)
        if not text:
            self._toast("Escribe o pega algún texto primero")
            return
        mime_item = self.clip_mime_dropdown.get_selected_item()
        mime = mime_item.get_string() if mime_item else "text/plain"

        def worker() -> None:
            try:
                ipc_call("clipboard.send", {"mime": mime, "data": text}, timeout=10.0)
                GLib.idle_add(
                    lambda: (
                        self._add_clipboard_history("Enviado a Android", mime, text),
                        self._toast("Portapapeles enviado al teléfono"),
                    )[1]
                    or False
                )
            except Exception as exc:
                self._toast(f"No se pudo enviar: {exc}")

        threading.Thread(target=worker, daemon=True).start()

    @staticmethod
    def _format_bytes(size: int) -> str:
        if size < 1024:
            return f"{size} B"
        if size < 1024 * 1024:
            return f"{size / 1024:.1f} KiB"
        return f"{size / (1024 * 1024):.2f} MiB"

    def _add_clipboard_history(
        self,
        direction: str,
        mime: str,
        text: str | None = None,
        *,
        size_bytes: int = 0,
        file_path: str | None = None,
    ) -> None:
        if self.clip_empty_row.get_parent() is self.clip_history_list:
            self.clip_history_list.remove(self.clip_empty_row)
        stamp = datetime.now().strftime("%H:%M:%S")

        if isinstance(text, str):
            snippet = text.replace("\n", " ")[:140]
            row = Adw.ActionRow(
                title=html.escape(f"[{stamp}] {direction} ({mime})"),
                subtitle=html.escape(snippet or "(vacío)"),
            )
            copy_btn = Gtk.Button(label="Copiar")
            copy_btn.set_valign(Gtk.Align.CENTER)
            copy_btn.connect("clicked", lambda _b, t=text: self._copy_to_pc_clipboard(t))
            row.add_suffix(copy_btn)
            self.clip_history_list.prepend(row)
            return

        image_tex: Gdk.Texture | None = None
        if file_path and Path(file_path).is_file():
            try:
                image_tex = Gdk.Texture.new_from_filename(file_path)
            except Exception:
                image_tex = None

        size_label = self._format_bytes(size_bytes) if size_bytes > 0 else "tamaño desconocido"
        if image_tex is not None:
            subtitle = f"Imagen {image_tex.get_width()}×{image_tex.get_height()} • {size_label}"
        elif mime.startswith("image/"):
            subtitle = f"Imagen ({mime}) • {size_label}"
        else:
            subtitle = f"Contenido binario ({mime}) • {size_label}"

        row = Adw.ActionRow(
            title=html.escape(f"[{stamp}] {direction} ({mime})"),
            subtitle=html.escape(subtitle),
        )
        if image_tex is not None:
            thumb = Gtk.Image.new_from_paintable(image_tex)
            thumb.set_pixel_size(36)
            row.add_prefix(thumb)

        if file_path and Path(file_path).is_file():
            copy_btn = Gtk.Button(label="Copiar imagen" if mime.startswith("image/") else "Copiar")
            copy_btn.set_valign(Gtk.Align.CENTER)
            copy_btn.connect(
                "clicked",
                lambda _b, p=file_path, m=mime: self._copy_file_to_pc_clipboard(p, m),
            )
            row.add_suffix(copy_btn)

        self.clip_history_list.prepend(row)

    def _copy_to_pc_clipboard(self, text: str) -> None:
        disp = Gdk.Display.get_default()
        if disp:
            disp.get_clipboard().set(text)
            self._toast("Copiado al portapapeles de Linux")

    def _copy_file_to_pc_clipboard(self, file_path: str, mime: str) -> None:
        path = Path(file_path)
        if not path.is_file():
            self._toast("La imagen en caché ya no está disponible")
            return

        if detect_session_kind() == "wayland" and shutil.which("wl-copy"):
            def worker() -> None:
                try:
                    with path.open("rb") as fh:
                        proc = subprocess.run(
                            ["wl-copy", "--type", mime],
                            stdin=fh,
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL,
                            timeout=3.0,
                            check=False,
                        )
                    if proc.returncode != 0:
                        raise RuntimeError(f"wl-copy código {proc.returncode}")
                    GLib.idle_add(lambda: self._toast("Imagen copiada al portapapeles de Linux") or False)
                except Exception as exc:
                    GLib.idle_add(lambda e=str(exc): self._toast(f"No se pudo copiar imagen: {e}") or False)

            threading.Thread(target=worker, daemon=True).start()
            return

        disp = Gdk.Display.get_default()
        if disp:
            try:
                tex = Gdk.Texture.new_from_filename(file_path)
                disp.get_clipboard().set(tex)
                self._toast("Imagen copiada al portapapeles de Linux")
            except Exception as exc:
                self._toast(f"No se pudo copiar imagen: {exc}")

    def _clear_clipboard_history(self) -> None:
        child = self.clip_history_list.get_first_child()
        while child is not None:
            nxt = child.get_next_sibling()
            self.clip_history_list.remove(child)
            child = nxt
        self.clip_history_list.append(self.clip_empty_row)

    # ------------------------------------------------------------------ Config & Discovery

    def _load_config_async(self) -> None:
        def worker() -> None:
            data: dict[str, Any] | None = None
            try:
                res = ipc_call("config.get", timeout=3.0)
                if isinstance(res, dict):
                    data = res
            except Exception:
                data = None
            if data is None:
                data = load_config_file()
            GLib.idle_add(lambda d=data: self._apply_loaded_config(d) or False)

        threading.Thread(target=worker, daemon=True).start()

    def _apply_loaded_config(self, raw: dict[str, Any]) -> None:
        if isinstance(raw.get("last_error"), str) and raw["last_error"]:
            self._show_error(raw["last_error"])

        cfg = deep_merge_config(default_config(), raw)
        self.config_data = cfg
        dev = cfg["device"]
        net = cfg["network"]
        st = cfg["streaming"]
        files_cfg = cfg["files"]

        self._suppress_settings_dirty = True
        try:
            self.entry_mac.set_text(str(dev.get("mac_address", "")))
            self.entry_uuid.set_text(str(dev.get("service_uuid", DEFAULT_SERVICE_UUID)))
            self.entry_ssid.set_text(str(dev.get("hotspot_ssid", "")))

            pubkey = dev.get("peer_ed25519_pubkey_hex")
            if isinstance(pubkey, str) and pubkey.strip():
                self.row_pubkey.set_subtitle(f"Fijada: {pubkey[:16]}…{pubkey[-8:]}")
            else:
                self.row_pubkey.set_subtitle("Sin clave fijada aún (TOFU automático al conectar)")

            mode = str(net.get("connection_mode", "auto")).lower()
            self.combo_mode.set_selected({"auto": 0, "lan": 1, "hotspot": 2}.get(mode, 0))
            self.entry_direct_host.set_text(str(net.get("direct_host", "")))
            self.entry_gateway_ip.set_text(str(net.get("gateway_ip", "192.168.43.1")))
            self.spin_tcp_port.set_value(float(net.get("tcp_port", 55777)))
            self.spin_cooldown.set_value(float(net.get("reconnect_cooldown_secs", 30)))
            self.spin_retries.set_value(float(net.get("max_retries", 4)))

            self.spin_fps.set_value(float(st.get("max_fps", 60)))
            self.spin_bitrate.set_value(float(st.get("bitrate_mbps", 8)))
            codec_mime = str(st.get("codec_mime", "video/avc")).lower()
            self.combo_codec.set_selected(1 if ("hevc" in codec_mime or "265" in codec_mime) else 0)

            self.entry_mount_point.set_text(str(files_cfg.get("mount_point", "~/Controid")))
            self.switch_auto_mount.set_active(bool(files_cfg.get("auto_mount", True)))
        finally:
            self._suppress_settings_dirty = False

        self._render_status()

    def _gather_config_from_ui(self) -> dict[str, Any]:
        mode_idx = self.combo_mode.get_selected()
        mode_val = {0: "auto", 1: "lan", 2: "hotspot"}.get(mode_idx, "auto")
        codec_val = "video/hevc" if self.combo_codec.get_selected() == 1 else "video/avc"
        return {
            "device": {
                "mac_address": self.entry_mac.get_text().strip().upper(),
                "service_uuid": self.entry_uuid.get_text().strip() or DEFAULT_SERVICE_UUID,
                "hotspot_ssid": self.entry_ssid.get_text().strip(),
                "peer_ed25519_pubkey_hex": self.config_data.get("device", {}).get("peer_ed25519_pubkey_hex"),
            },
            "network": {
                "connection_mode": mode_val,
                "direct_host": self.entry_direct_host.get_text().strip(),
                "gateway_ip": self.entry_gateway_ip.get_text().strip() or "192.168.43.1",
                "tcp_port": int(self.spin_tcp_port.get_value()),
                "reconnect_cooldown_secs": int(self.spin_cooldown.get_value()),
                "max_retries": int(self.spin_retries.get_value()),
            },
            "streaming": {
                "max_fps": int(self.spin_fps.get_value()),
                "bitrate_mbps": int(self.spin_bitrate.get_value()),
                "codec_mime": codec_val,
            },
            "files": {
                "mount_point": self.entry_mount_point.get_text().strip() or "~/Controid",
                "auto_mount": bool(self.switch_auto_mount.get_active()),
            },
        }

    def _save_config_async(self) -> None:
        cfg = self._gather_config_from_ui()

        def worker() -> None:
            try:
                if socket_path().exists():
                    updated = ipc_call("config.set", cfg, timeout=6.0)
                    if isinstance(updated, dict):
                        GLib.idle_add(lambda u=updated: self._apply_loaded_config(u) or False)
                    self._toast("Ajustes guardados y aplicados en el demonio")
                else:
                    save_config_file(cfg)
                    GLib.idle_add(lambda c=cfg: self._apply_loaded_config(c) or False)
                    self._toast("Ajustes guardados en config.toml")
            except Exception as exc:
                self._toast(f"Error guardando ajustes: {exc}")

        threading.Thread(target=worker, daemon=True).start()

    def _reset_peer_pubkey(self) -> None:
        self.config_data["device"]["peer_ed25519_pubkey_hex"] = None
        self._save_config_async()

    def _load_paired_devices_initial(self) -> None:
        def worker() -> None:
            paired = get_paired_bt_devices()
            GLib.idle_add(lambda d=paired: self._render_ble_devices(d, initial=True) or False)

        threading.Thread(target=worker, daemon=True).start()

    def _scan_ble_devices(self) -> None:
        self._toast("Escaneando BLE durante 8 segundos…")

        def worker() -> None:
            merged: dict[str, dict[str, Any]] = {}
            for d in get_paired_bt_devices():
                merged[d["mac_address"]] = d
            try:
                res = ipc_call("pair.discover", {"timeout_secs": 8}, timeout=15.0)
                if isinstance(res, dict) and isinstance(res.get("devices"), list):
                    for item in res["devices"]:
                        mac = str(item.get("mac_address") or item.get("mac") or "").upper()
                        if not mac:
                            continue
                        prev = merged.get(mac, {})
                        merged[mac] = {
                            "mac_address": mac,
                            "name": item.get("name") or item.get("alias") or prev.get("name"),
                            "paired": bool(item.get("paired", prev.get("paired", False))),
                            "connected": bool(item.get("connected", False)),
                        }
            except Exception as exc:
                self._toast(f"Escaneo IPC falló ({exc}); mostrando emparejados Bluetooth")

            devices = sorted(
                merged.values(),
                key=lambda x: (not x.get("paired", False), not x.get("connected", False), x.get("mac_address", "")),
            )
            GLib.idle_add(lambda d=devices: self._render_ble_devices(d, initial=False) or False)

        threading.Thread(target=worker, daemon=True).start()

    def _render_ble_devices(self, devices: list[dict[str, Any]], initial: bool = False) -> None:
        child = self.ble_devices_list.get_first_child()
        while child is not None:
            nxt = child.get_next_sibling()
            self.ble_devices_list.remove(child)
            child = nxt

        if not devices:
            row = Adw.ActionRow(
                title="Ningún dispositivo encontrado",
                subtitle="Empareja el teléfono por Bluetooth o pulsa 'Escanear BLE'",
            )
            self.ble_devices_list.append(row)
            return

        for dev in devices:
            mac = str(dev.get("mac_address", ""))
            name = dev.get("name") or "(Sin nombre)"
            badges: list[str] = []
            if dev.get("paired"):
                badges.append("Emparejado")
            if dev.get("connected"):
                badges.append("Conectado")
            badge_str = f" — {', '.join(badges)}" if badges else ""
            row = Adw.ActionRow(
                title=html.escape(f"{name}{badge_str}"),
                subtitle=html.escape(mac),
            )
            pick = Gtk.Button(label="Seleccionar")
            pick.set_valign(Gtk.Align.CENTER)
            pick.connect("clicked", lambda _b, m=mac: self._select_device_mac(m))
            row.add_suffix(pick)
            self.ble_devices_list.append(row)

        if not initial:
            self._toast(f"Encontrados {len(devices)} dispositivo(s)")

    def _select_device_mac(self, mac: str) -> None:
        self.entry_mac.set_text(mac)
        self._toast(f"MAC {mac} seleccionada — pulsa 'Guardar y aplicar'")

    def _on_pick_saved_ssid(self, anchor_btn: Gtk.Button) -> None:
        ssids = get_saved_wifi_ssids()
        if not ssids:
            self._toast("No se encontraron perfiles Wi-Fi guardados en NetworkManager")
            return
        popover = Gtk.Popover()
        popover.set_parent(anchor_btn)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        box.set_margin_top(6)
        box.set_margin_bottom(6)
        box.set_margin_start(6)
        box.set_margin_end(6)
        for ssid in ssids[:12]:
            btn = Gtk.Button(label=ssid)
            btn.add_css_class("flat")
            btn.connect(
                "clicked",
                lambda _b, s=ssid, pop=popover: (self.entry_ssid.set_text(s), pop.popdown()),
            )
            box.append(btn)
        popover.set_child(box)
        popover.popup()

    def _detect_lan_ip_via_adb(self) -> None:
        def worker() -> None:
            ip, msg = detect_adb_phone_lan_ip()
            if ip:
                GLib.idle_add(lambda v=ip: self.entry_direct_host.set_text(v) or False)
            self._toast(msg)

        threading.Thread(target=worker, daemon=True).start()

    # ------------------------------------------------------------------ Systemd & Diagnostics

    def _refresh_systemd_status(self) -> None:
        def worker() -> None:
            if has_systemd_user():
                rc_act, act, _ = run_cmd(["systemctl", "--user", "is-active", SERVICE_NAME], timeout=2.5)
                rc_ena, ena, _ = run_cmd(["systemctl", "--user", "is-enabled", SERVICE_NAME], timeout=2.5)
                text = f"Estado: {act or ('activo' if rc_act == 0 else 'inactivo')} | Arranque: {ena or ('habilitado' if rc_ena == 0 else 'deshabilitado')}"
                title = SERVICE_NAME
            else:
                rc_pg, _, _ = run_cmd(["pgrep", "-x", "controidd"], timeout=2.0)
                active = rc_pg == 0 or socket_path().exists()
                autostart = autostart_desktop_path().is_file()
                text = f"Proceso: {'activo' if active else 'inactivo'} | Autostart XDG: {'habilitado' if autostart else 'deshabilitado'}"
                title = "controidd (sin systemd)"

            def update_ui() -> bool:
                self.row_systemd.set_title(title)
                self.row_systemd.set_subtitle(text)
                return False

            GLib.idle_add(update_ui)

        threading.Thread(target=worker, daemon=True).start()

    def _spawn_daemon_detached(self) -> tuple[bool, str]:
        exe = resolve_controid_bin("controidd")
        if not exe:
            return False, "No se encontró el binario controidd en PATH ni en ~/.local/bin"
        log_file = daemon_log_path()
        log_file.parent.mkdir(parents=True, exist_ok=True)
        try:
            with log_file.open("ab") as fh:
                subprocess.Popen(
                    [exe],
                    stdin=subprocess.DEVNULL,
                    stdout=fh,
                    stderr=fh,
                    start_new_session=True,
                )
            return True, f"controidd iniciado (log en {log_file})"
        except Exception as exc:
            return False, f"No se pudo iniciar controidd: {exc}"

    def _run_systemd_action(self, action: str) -> None:
        def worker() -> None:
            if has_systemd_user():
                args = (
                    ["systemctl", "--user", "enable", "--now", SERVICE_NAME]
                    if action == "enable"
                    else ["systemctl", "--user", action, SERVICE_NAME]
                )
                rc, out, err = run_cmd(args, timeout=8.0)
                if rc == 0:
                    self._toast(f"systemd: {action} ejecutado")
                else:
                    self._toast(f"Error systemd ({action}): {err or out}")
            else:
                if action == "start":
                    ok, msg = self._spawn_daemon_detached()
                    self._toast(msg if ok else msg)
                elif action == "stop":
                    rc, _, err = run_cmd(["pkill", "-TERM", "-x", "controidd"], timeout=4.0)
                    self._toast("controidd detenido" if rc in (0, 1) else f"Error al detener: {err}")
                elif action == "restart":
                    run_cmd(["pkill", "-TERM", "-x", "controidd"], timeout=4.0)
                    time.sleep(0.6)
                    _, msg = self._spawn_daemon_detached()
                    self._toast(msg)
                elif action == "enable":
                    desktop = autostart_desktop_path()
                    if desktop.is_file():
                        desktop.unlink()
                        self._toast("Autostart XDG deshabilitado")
                    else:
                        exe = resolve_controid_bin("controidd") or "controidd"
                        desktop.parent.mkdir(parents=True, exist_ok=True)
                        desktop.write_text(
                            "[Desktop Entry]\n"
                            "Type=Application\n"
                            "Name=Controid Daemon\n"
                            "Comment=Android & Linux continuity daemon\n"
                            f"Exec={exe}\n"
                            "Terminal=false\n"
                            "X-GNOME-Autostart-enabled=true\n",
                            encoding="utf-8",
                        )
                        self._toast(f"Autostart XDG habilitado en {desktop}")

            self._refresh_systemd_status()
            time.sleep(0.6)
            self._refresh_status_once()

        threading.Thread(target=worker, daemon=True).start()

    def _launch_tray(self) -> None:
        rc, _, _ = run_cmd(["pgrep", "-x", "controid-tray"], timeout=2.0)
        if rc == 0:
            self._toast("La bandeja controid-tray ya está activa")
            return
        exe = shutil.which("controid-tray") or str(Path.home() / ".local" / "bin" / "controid-tray")
        try:
            subprocess.Popen(
                [exe],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
            )
            self._toast("Bandeja iniciada")
        except Exception as exc:
            self._toast(f"No se pudo iniciar controid-tray: {exc}")

    def _refresh_diagnostics(self) -> None:
        def worker() -> None:
            checks, detected_ip = collect_diagnostics()
            GLib.idle_add(lambda c=checks, ip=detected_ip: self._render_diagnostics(c, ip) or False)

        threading.Thread(target=worker, daemon=True).start()

    def _render_diagnostics(self, checks: list[tuple[str, str, bool]], detected_ip: str | None) -> None:
        child = self.diag_list.get_first_child()
        while child is not None:
            nxt = child.get_next_sibling()
            self.diag_list.remove(child)
            child = nxt

        for title, detail, ok in checks:
            row = Adw.ActionRow(
                title=html.escape(title),
                subtitle=html.escape(detail),
            )
            badge = Gtk.Image.new_from_icon_name("emblem-ok-symbolic" if ok else "dialog-warning-symbolic")
            badge.set_valign(Gtk.Align.CENTER)
            row.add_prefix(badge)
            self.diag_list.append(row)

        if detected_ip and not self.entry_direct_host.get_text().strip():
            self.entry_direct_host.set_text(detected_ip)

    def _launch_android_app_adb(self) -> None:
        def worker() -> None:
            rc, out, err = run_cmd(
                ["adb", "shell", "am", "start", "-n", "com.controid/.MainActivity"],
                timeout=5.0,
            )
            self._toast("App abierta en el móvil" if rc == 0 else f"Error ADB: {err or out}")

        threading.Thread(target=worker, daemon=True).start()

    def _append_event_log(self, msg: str) -> None:
        stamp = datetime.now().strftime("%H:%M:%S")
        self.event_log_lines.append(f"[{stamp}] {msg}")
        self.event_log_lines = self.event_log_lines[-80:]
        self._update_logs_buffer()

    def _refresh_journal(self) -> None:
        def worker() -> None:
            if has_systemd_user() and shutil.which("journalctl"):
                rc, out, err = run_cmd(
                    ["journalctl", "--user", "-u", SERVICE_NAME, "-n", "60", "--no-pager", "-o", "short-iso"],
                    timeout=4.0,
                )
                text = out if rc == 0 else (err or "journalctl no disponible")
            else:
                log_file = daemon_log_path()
                if log_file.is_file():
                    lines = log_file.read_text(encoding="utf-8", errors="replace").splitlines()
                    text = "\n".join(lines[-60:]) if lines else f"{log_file} está vacío"
                else:
                    text = (
                        "Entorno sin systemd usuario.\n"
                        f"Cuando inicies controidd desde el panel, su log se guardará en {log_file}."
                    )
            GLib.idle_add(lambda t=text: self._set_journal_text(t) or False)

        threading.Thread(target=worker, daemon=True).start()

    def _set_journal_text(self, journal_text: str) -> None:
        self._last_journal = journal_text
        self._update_logs_buffer()

    def _update_logs_buffer(self) -> None:
        ev_block = "\n".join(self.event_log_lines) if self.event_log_lines else "(Sin eventos IPC recientes)"
        j_block = getattr(self, "_last_journal", "")
        combined = f"=== Eventos IPC en vivo ===\n{ev_block}\n\n=== Servicio / Demonio (últimas 60 líneas) ===\n{j_block}"
        self.logs_view.get_buffer().set_text(combined)


class ControidApp(Adw.Application):
    def __init__(self) -> None:
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.FLAGS_NONE)

    def do_startup(self) -> None:
        Adw.Application.do_startup(self)
        Gtk.Window.set_default_icon_name("controid")

    def do_activate(self) -> None:
        win = self.get_active_window()
        if win is None:
            win = ControidWindow(self)
        win.present()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Controid Control Center GUI")
    parser.add_argument(
        "--check",
        action="store_true",
        help="Verifica dependencias GTK4/Libadwaita y lectura de configuración sin abrir ventana",
    )
    args, unknown = parser.parse_known_args(argv)
    if args.check:
        cfg = load_config_file()
        print(
            json.dumps(
                {
                    "gtk_version": f"{Gtk.get_major_version()}.{Gtk.get_minor_version()}",
                    "adw_version": f"{Adw.get_major_version()}.{Adw.get_minor_version()}",
                    "config_path": str(config_path()),
                    "socket_exists": socket_path().exists(),
                    "connection_mode": cfg["network"]["connection_mode"],
                }
            )
        )
        return 0

    app = ControidApp()
    return app.run([sys.argv[0], *unknown])


if __name__ == "__main__":
    raise SystemExit(main())
