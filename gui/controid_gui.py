#!/usr/bin/env python3
"""Controid Control Center — SekaiPop GTK4 / Libadwaita native Linux interface."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime
import html
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
from typing import Any

try:
    import tomllib
except ImportError as exc:  # pragma: no cover
    raise SystemExit(f"Python >= 3.11 required (missing tomllib): {exc}") from exc

import cairo
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


# ---------------------------------------------------------------------------
# SekaiPop Theme & Palette Engine (aligned 1:1 with Android SekaiTheme.kt)
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class SekaiUnitPalette:
    id: str
    name: str
    border: str
    accent_a: str
    accent_b: str
    deep: str
    divider: str
    hover_tint: str
    pink_accent: str
    mono: bool = False


@dataclass(frozen=True)
class SekaiResolvedTheme:
    palette: SekaiUnitPalette
    is_dark: bool
    surface: str
    surface_hover: str
    ring_white: str
    line: str
    line_shadow: str
    ink: str
    ink_sub: str
    chip_fill: str
    chip_ink: str
    backdrop_wash: str
    accent_a: str
    accent_b: str
    pink_accent: str
    attribute_colors: tuple[str, ...]


SEKAI_PALETTES: tuple[SekaiUnitPalette, ...] = (
    SekaiUnitPalette(
        id="mono",
        name="Monochrome",
        border="#444444",
        accent_a="#F4F4F4",
        accent_b="#BDBDBD",
        deep="#1A1A1A",
        divider="#ECECEC",
        hover_tint="#E9E9E9",
        pink_accent="#9E9E9E",
        mono=True,
    ),
    SekaiUnitPalette(
        id="warm_terracotta",
        name="Terracotta (SekaiPop)",
        border="#561E18",
        accent_a="#F2A396",
        accent_b="#DFC38C",
        deep="#1A1110",
        divider="#F5DDDA",
        hover_tint="#FFEFEA",
        pink_accent="#E8889E",
    ),
    SekaiUnitPalette(
        id="vs",
        name="Virtual Singer",
        border="#555473",
        accent_a="#04FEEE",
        accent_b="#00FFCB",
        deep="#232235",
        divider="#E4E4EC",
        hover_tint="#D9FFF6",
        pink_accent="#FF8FD6",
    ),
    SekaiUnitPalette(
        id="leoneed",
        name="Leo/need",
        border="#2D3775",
        accent_a="#8EA3FF",
        accent_b="#9FD8FF",
        deep="#181E3F",
        divider="#DEE1F2",
        hover_tint="#F1F9FF",
        pink_accent="#FF8FD6",
    ),
    SekaiUnitPalette(
        id="mmj",
        name="MORE MORE JUMP!",
        border="#39662D",
        accent_a="#A4EE55",
        accent_b="#7FE9A8",
        deep="#223C1B",
        divider="#E3F1DF",
        hover_tint="#ECFCF2",
        pink_accent="#FF8FD6",
    ),
    SekaiUnitPalette(
        id="vbs",
        name="Vivid BAD SQUAD",
        border="#561938",
        accent_a="#FF5C9A",
        accent_b="#FF96C0",
        deep="#43142C",
        divider="#F5DBE8",
        hover_tint="#FFEFF6",
        pink_accent="#FF8FD6",
    ),
    SekaiUnitPalette(
        id="wxs",
        name="Wonderlands×Showtime",
        border="#6A3C11",
        accent_a="#FFA51F",
        accent_b="#FFD54A",
        deep="#4B2A0C",
        divider="#F9E7D7",
        hover_tint="#FFF9E4",
        pink_accent="#FF8FD6",
    ),
    SekaiUnitPalette(
        id="nightcord",
        name="Nightcord at 25:00",
        border="#43285F",
        accent_a="#C08CF2",
        accent_b="#E2B5FF",
        deep="#2B1A3D",
        divider="#E8DFF1",
        hover_tint="#FBF4FF",
        pink_accent="#FF8FD6",
    ),
)


def _hex_to_rgb(hex_color: str) -> tuple[float, float, float]:
    h = hex_color.strip().lstrip("#")
    return tuple(int(h[i : i + 2], 16) / 255.0 for i in (0, 2, 4))  # type: ignore[return-value]


def _rgb_to_hex(rgb: tuple[float, float, float]) -> str:
    r = max(0, min(255, round(rgb[0] * 255)))
    g = max(0, min(255, round(rgb[1] * 255)))
    b = max(0, min(255, round(rgb[2] * 255)))
    return f"#{r:02X}{g:02X}{b:02X}"


def _lerp_hex(a: str, b: str, t: float) -> str:
    ra, ga, ba = _hex_to_rgb(a)
    rb, gb, bb = _hex_to_rgb(b)
    return _rgb_to_hex((ra + (rb - ra) * t, ga + (gb - ga) * t, ba + (bb - ba) * t))


def _hex_rgba(hex_color: str, alpha: float) -> str:
    r = int(hex_color[1:3], 16)
    g = int(hex_color[3:5], 16)
    b = int(hex_color[5:7], 16)
    return f"rgba({r}, {g}, {b}, {alpha:.3f})"


def resolve_sekai_theme(palette_id: str, is_dark: bool) -> SekaiResolvedTheme:
    palette = next((p for p in SEKAI_PALETTES if p.id == palette_id), SEKAI_PALETTES[0])
    attrs = (
        ("#E4E4E4", "#B0B0B0", "#F6F6F6", "#8C8C8C", "#CECECE")
        if palette.mono
        else ("#8EE3EF", "#FF9ECB", "#FFE066", "#B39DDB", "#A5E6A5")
    )
    if not is_dark:
        return SekaiResolvedTheme(
            palette=palette,
            is_dark=False,
            surface="#FFFDF9",
            surface_hover=palette.hover_tint,
            ring_white="#FFFFFF",
            line=palette.border,
            line_shadow=palette.border,
            ink=palette.border,
            ink_sub=_hex_rgba(palette.border, 0.70),
            chip_fill=_lerp_hex("#FFFFFF", palette.border, 0.11),
            chip_ink=palette.border,
            backdrop_wash=_lerp_hex("#FAF5F0", palette.accent_b, 0.10),
            accent_a=palette.accent_a,
            accent_b=palette.accent_b,
            pink_accent=palette.pink_accent,
            attribute_colors=attrs,
        )

    dark_surface = _lerp_hex(palette.deep, "#FFFFFF", 0.08)
    dark_hover = _lerp_hex(dark_surface, "#FFFFFF", 0.12)
    dark_shadow = _lerp_hex(palette.deep, "#000000", 0.45)
    dark_line = _lerp_hex(palette.border, "#FFFFFF", 0.22)
    dark_wash = _lerp_hex(palette.deep, palette.accent_b, 0.08)
    return SekaiResolvedTheme(
        palette=palette,
        is_dark=True,
        surface=dark_surface,
        surface_hover=dark_hover,
        ring_white=_lerp_hex("#FFFFFF", palette.border, 0.12),
        line=dark_line,
        line_shadow=dark_shadow,
        ink=palette.divider,
        ink_sub=_hex_rgba(palette.divider, 0.74),
        chip_fill=_lerp_hex(palette.deep, "#FFFFFF", 0.16),
        chip_ink="#FFFFFF",
        backdrop_wash=dark_wash,
        accent_a=palette.accent_a,
        accent_b=palette.accent_b,
        pink_accent=palette.pink_accent,
        attribute_colors=attrs,
    )


def build_sekai_css(t: SekaiResolvedTheme) -> str:
    accent_ink = "#1A1110"
    surface_soft = _hex_rgba(t.surface, 0.94)
    surface_glass = _hex_rgba(t.surface, 0.88)
    pill_selected = t.ring_white if t.is_dark else t.ink
    pill_selected_ink = "#1A1110" if t.is_dark else "#FFFFFF"
    card_shadow = _hex_rgba(t.line_shadow, 0.88)
    sub_tint = _hex_rgba(t.ink, 0.08 if not t.is_dark else 0.12)

    return f"""
window.sekai-window,
window.sekai-window > widget,
.sekai-root,
.sekai-root scrolledwindow,
.sekai-root viewport,
.sekai-root preferencespage,
.sekai-root preferencespage > scrolledwindow,
.sekai-root preferencespage > scrolledwindow > viewport {{
    background: transparent;
    color: {t.ink};
}}

.sekai-header {{
    background: transparent;
    border: none;
    box-shadow: none;
    padding: 4px 8px;
}}

window.sekai-window headerbar.sekai-headerbar,
headerbar.sekai-headerbar {{
    background-image: none;
    background-color: {surface_glass};
    border: 2.2px solid {t.line};
    border-radius: 18px;
    box-shadow: 0 3px 0 {card_shadow};
    margin: 8px 14px 6px 14px;
    padding: 4px 10px;
    min-height: 48px;
    color: {t.ink};
}}

.sekai-bottom-bar {{
    background-image: none;
    background-color: {surface_glass};
    border: 2.2px solid {t.line};
    border-radius: 18px;
    box-shadow: 0 3px 0 {card_shadow};
    margin: 4px 12px 10px 12px;
    padding: 4px;
}}

viewswitcher button,
viewswitcherbar button {{
    border-radius: 12px;
    border: 1.5px solid transparent;
    padding: 4px 10px;
    color: {t.ink_sub};
    font-weight: 700;
    background: transparent;
}}

viewswitcher button:hover,
viewswitcherbar button:hover {{
    background: {t.surface_hover};
    color: {t.ink};
}}

viewswitcher button:checked,
viewswitcherbar button:checked {{
    background: {pill_selected};
    color: {pill_selected_ink};
    border: 1.8px solid {t.line};
    box-shadow: 0 2px 0 {card_shadow};
}}

.sekai-plate {{
    background: {surface_soft};
    border: 2.3px solid {t.line};
    border-radius: 18px;
    box-shadow: 0 4px 0 {card_shadow};
    padding: 12px 14px;
    color: {t.ink};
}}

.sekai-plate-tight {{
    background: {surface_soft};
    border: 2.2px solid {t.line};
    border-radius: 16px;
    box-shadow: 0 3px 0 {card_shadow};
    padding: 8px 10px;
    color: {t.ink};
}}

.sekai-hero {{
    background: {surface_soft};
    border: 2.4px solid {t.line};
    border-radius: 20px;
    box-shadow: 0 4px 0 {card_shadow};
    padding: 10px 14px;
}}
.sekai-diamond-ic {{
    color: #1A1110;
}}

.sekai-chip {{
    background: {t.chip_fill};
    color: {t.chip_ink};
    border: 1.8px solid {t.line};
    border-radius: 999px;
    padding: 4px 11px;
    font-size: 12px;
    font-weight: 800;
}}

.sekai-chip-accent {{
    background: {t.accent_a};
    color: {accent_ink};
    border: 1.8px solid {t.line};
    border-radius: 999px;
    padding: 4px 11px;
    font-size: 12px;
    font-weight: 800;
}}

.sekai-floating-pill {{
    background: {surface_glass};
    border: 2.2px solid {t.line};
    border-radius: 999px;
    box-shadow: 0 3px 0 {card_shadow};
    padding: 4px 6px;
}}

button.sekai-btn {{
    background-image: none;
    background-color: {t.surface};
    color: {t.ink};
    border: 2px solid {t.line};
    border-radius: 12px;
    box-shadow: 0 2px 0 {card_shadow};
    padding: 6px 12px;
    font-weight: 700;
    min-height: 32px;
}}

button.sekai-btn:hover {{
    background-color: {t.surface_hover};
}}

button.sekai-btn:active {{
    box-shadow: 0 0 0 {card_shadow};
}}

button.sekai-btn-pill {{
    background-image: none;
    background-color: {t.surface};
    color: {t.ink};
    border: 2px solid {t.line};
    border-radius: 999px;
    box-shadow: 0 2px 0 {card_shadow};
    padding: 5px 12px;
    font-weight: 800;
    min-height: 30px;
}}

button.sekai-btn-pill:hover {{
    background-color: {t.surface_hover};
}}

button.sekai-icon-btn {{
    background-image: none;
    background-color: {t.surface};
    color: {t.ink};
    border: 2px solid {t.line};
    border-radius: 12px;
    box-shadow: 0 2px 0 {card_shadow};
    padding: 4px;
    min-width: 34px;
    min-height: 34px;
}}

button.sekai-icon-btn:hover {{
    background-color: {t.surface_hover};
}}

button.sekai-active {{
    background-image: none;
    background-color: {pill_selected};
    color: {pill_selected_ink};
    border: 2px solid {t.line};
    box-shadow: 0 2px 0 {card_shadow};
}}

button.sekai-active:hover {{
    background-color: {pill_selected};
    opacity: 0.92;
}}

button.sekai-accent,
button.suggested-action {{
    background-image: none;
    background-color: {t.accent_a};
    color: {accent_ink};
    border: 2px solid {t.line};
    border-radius: 12px;
    box-shadow: 0 2px 0 {card_shadow};
    font-weight: 800;
    padding: 6px 14px;
}}

button.sekai-accent:hover,
button.suggested-action:hover {{
    background-color: {t.accent_b};
    color: {accent_ink};
}}

button.sekai-danger {{
    background-image: none;
    background-color: {t.pink_accent};
    color: {accent_ink};
    border: 2px solid {t.line};
    border-radius: 12px;
    box-shadow: 0 2px 0 {card_shadow};
    font-weight: 800;
}}

.sekai-stat-card {{
    background: {surface_soft};
    border: 2.2px solid {t.line};
    border-radius: 16px;
    box-shadow: 0 3px 0 {card_shadow};
    padding: 10px 12px;
}}

.sekai-stat-title {{
    color: {t.ink_sub};
    font-size: 11px;
    font-weight: 800;
}}

.sekai-stat-val {{
    color: {t.ink};
    font-size: 15px;
    font-weight: 900;
}}

.sekai-muted {{
    color: {t.ink_sub};
}}

preferencesgroup > box > label.title {{
    color: {t.ink};
    font-weight: 800;
    font-size: 15px;
}}

preferencesgroup > box > label.description {{
    color: {t.ink_sub};
}}

list.boxed-list,
preferencesgroup list.boxed-list {{
    background: {surface_soft};
    border: 2.3px solid {t.line};
    border-radius: 18px;
    box-shadow: 0 4px 0 {card_shadow};
}}

list.boxed-list > row {{
    background: transparent;
    color: {t.ink};
    border-bottom: 1px solid {sub_tint};
}}

list.boxed-list > row:last-child {{
    border-bottom: none;
}}

frame.sekai-frame,
.sekai-root frame {{
    background: {t.surface};
    border: 2.2px solid {t.line};
    border-radius: 16px;
    box-shadow: 0 3px 0 {card_shadow};
}}

.sekai-root textview,
.sekai-root textview text {{
    background: {t.surface};
    color: {t.ink};
}}
"""


# ---------------------------------------------------------------------------
# Paths, Config, IPC & Host Integration Helpers
# ---------------------------------------------------------------------------


def xdg_config_home() -> Path:
    return Path(os.environ.get("XDG_CONFIG_HOME") or (Path.home() / ".config"))


def xdg_state_home() -> Path:
    return Path(os.environ.get("XDG_STATE_HOME") or (Path.home() / ".local" / "state"))


def xdg_runtime_dir() -> Path:
    return Path(os.environ.get("XDG_RUNTIME_DIR") or "/tmp")


def config_path() -> Path:
    return xdg_config_home() / "controid" / "config.toml"


def gui_prefs_path() -> Path:
    return xdg_config_home() / "controid" / "gui_prefs.json"


def socket_path() -> Path:
    return xdg_runtime_dir() / "controid" / "daemon.sock"


def daemon_log_path() -> Path:
    return xdg_state_home() / "controid" / "controidd.log"


def autostart_desktop_path() -> Path:
    return xdg_config_home() / "autostart" / "controidd.desktop"


def load_gui_prefs() -> dict[str, Any]:
    defaults = {"palette_id": "mono", "dark_mode": True}
    path = gui_prefs_path()
    if path.is_file():
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                defaults.update({k: v for k, v in data.items() if k in defaults})
        except Exception:
            pass
    return defaults


def save_gui_prefs(prefs: dict[str, Any]) -> None:
    path = gui_prefs_path()
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(prefs, indent=2), encoding="utf-8")
    except Exception:
        pass


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
                devices.append({"mac_address": mac, "name": name, "paired": True, "connected": False})
    return devices


def _strip_ansi(text: str) -> str:
    return re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", text)


def get_saved_wifi_ssids() -> list[str]:
    ssids: list[str] = []
    if shutil.which("nmcli"):
        rc, out, _ = run_cmd(["nmcli", "-t", "-f", "NAME,TYPE", "connection", "show"], timeout=3.0)
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
                if not line or line.startswith(("Known", "-", "Name")):
                    continue
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
    rc, ip_out, ip_err = run_cmd(["adb", "-s", serial, "shell", "ip", "-4", "-o", "addr", "show", "wlan0"], timeout=4.0)
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
    checks.append(("Socket IPC local", f"{socket_path()} ({'disponible' if sock_exists else 'no disponible'})", sock_exists))

    paired_bt = get_paired_bt_devices()
    if Path("/run/systemd/system").is_dir() and shutil.which("systemctl"):
        rc_bt, bt_out, _ = run_cmd(["systemctl", "is-active", "bluetooth.service"], timeout=3.0)
        bt_ok, bt_state = rc_bt == 0, (bt_out or "inactivo")
    else:
        rc_bt, _, _ = run_cmd(["pgrep", "-x", "bluetoothd"], timeout=2.0)
        bt_ok = rc_bt == 0
        bt_state = "activo (bluetoothd)" if bt_ok else "inactivo"
    checks.append(("Bluetooth (BlueZ)", f"servicio={bt_state} | dispositivos emparejados={len(paired_bt)}", bt_ok))

    ssids = get_saved_wifi_ssids()
    nm_running = False
    if Path("/run/systemd/system").is_dir() and shutil.which("systemctl"):
        rc_nm, _, _ = run_cmd(["systemctl", "is-active", "NetworkManager.service"], timeout=3.0)
        nm_running = rc_nm == 0
    if not nm_running and shutil.which("nmcli"):
        rc_nm2, _, _ = run_cmd(["nmcli", "-t", "general", "status"], timeout=2.5)
        nm_running = rc_nm2 == 0
    if nm_running:
        checks.append(("Red Wi-Fi (NetworkManager)", f"activo | perfiles Wi-Fi={len(ssids)}", True))
    else:
        rc_iwd, _, _ = run_cmd(["pgrep", "-x", "iwd"], timeout=2.0)
        if rc_iwd == 0:
            checks.append(("Red Wi-Fi (iwd / iwctl)", f"iwd activo | redes conocidas={len(ssids)}", True))
        else:
            checks.append(("Gestor Wi-Fi (LAN directa disponible)", "Hotspot automático requiere NM/iwd; LAN directa operativa", True))

    uinput = Path("/dev/uinput")
    uinput_rw = uinput.exists() and os.access(uinput, os.W_OK)
    checks.append(
        (
            "/dev/uinput (entrada fallback Linux)",
            "escribible" if uinput_rw else ("sin permiso (ejecuta ./install.sh --setup-uinput)" if uinput.exists() else "no presente"),
            uinput_rw,
        )
    )

    session = detect_session_kind()
    clip_tools = [t for t in ("wl-paste", "xclip", "xsel") if shutil.which(t)]
    checks.append(
        (
            f"Sesión gráfica ({session}) y portapapeles",
            f"herramientas CLI: {', '.join(clip_tools) if clip_tools else 'GTK4/nativo del demonio'}",
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
            checks.append((f"Android ADB ({serial})", f"app={'activa (pid ' + pid_out.strip() + ')' if app_running else 'detenida'} | {ip_msg}", app_running))
        else:
            checks.append(("Android ADB", "Sin dispositivo ADB activo", False))
    else:
        checks.append(("Android ADB", "adb no instalado (opcional)", False))

    return checks, detected_ip


# ---------------------------------------------------------------------------
# Cairo Custom Widgets: SekaiBackdrop & Diamond
# ---------------------------------------------------------------------------


class SekaiBackdropCanvas(Gtk.DrawingArea):
    def __init__(self, theme: SekaiResolvedTheme) -> None:
        super().__init__(hexpand=True, vexpand=True)
        self.theme = theme
        self.set_draw_func(self._draw)

    def set_theme(self, theme: SekaiResolvedTheme) -> None:
        self.theme = theme
        self.queue_draw()

    @staticmethod
    def _draw_sparkle(cr: cairo.Context, cx: float, cy: float, r: float, rgba: tuple[float, float, float, float]) -> None:
        cr.save()
        cr.set_source_rgba(*rgba)
        cr.move_to(cx, cy - r)
        cr.line_to(cx + r * 0.28, cy - r * 0.28)
        cr.line_to(cx + r, cy)
        cr.line_to(cx + r * 0.28, cy + r * 0.28)
        cr.line_to(cx, cy + r)
        cr.line_to(cx - r * 0.28, cy + r * 0.28)
        cr.line_to(cx - r, cy)
        cr.line_to(cx - r * 0.28, cy - r * 0.28)
        cr.close_path()
        cr.fill()
        cr.restore()

    def _draw(self, _area: Gtk.DrawingArea, cr: cairo.Context, w: int, h: int) -> None:
        r, g, b = _hex_to_rgb(self.theme.backdrop_wash)
        cr.set_source_rgb(r, g, b)
        cr.paint()

        # Subtle diagonal ribbons inspired by SekaiBackdrop
        cr.save()
        ar, ag, ab = _hex_to_rgb(self.theme.accent_b)
        alpha_band = 0.06 if self.theme.is_dark else 0.11
        cr.set_source_rgba(ar, ag, ab, alpha_band)
        for idx, offset in enumerate((-0.15, 0.22, 0.62)):
            cr.new_path()
            x0 = w * offset
            band = 64.0 + idx * 14.0
            cr.move_to(x0, 0)
            cr.line_to(x0 + band, 0)
            cr.line_to(x0 + band - h * 0.26, h)
            cr.line_to(x0 - h * 0.26, h)
            cr.close_path()
            cr.fill()
        cr.restore()

        # Crisp micro dot grid
        lr, lg, lb = _hex_to_rgb(self.theme.ink)
        dot_alpha = 0.065 if self.theme.is_dark else 0.075
        cr.set_source_rgba(lr, lg, lb, dot_alpha)
        step = 24
        for y in range(12, h, step):
            for x in range(12, w, step):
                cr.arc(x, y, 1.35, 0, math.tau)
                cr.fill()

        # Corner Sekai sparkles
        sr, sg, sb = _hex_to_rgb(self.theme.accent_a)
        sp_alpha = 0.16 if self.theme.is_dark else 0.24
        self._draw_sparkle(cr, 48, 86, 18, (sr, sg, sb, sp_alpha))
        self._draw_sparkle(cr, w - 58, 128, 22, (ar, ag, ab, sp_alpha))
        self._draw_sparkle(cr, w - 74, h - 72, 16, (sr, sg, sb, sp_alpha))
        self._draw_sparkle(cr, 78, h - 94, 13, (ar, ag, ab, sp_alpha))


class SekaiDiamondIcon(Gtk.DrawingArea):
    def __init__(self, icon_name: str, theme: SekaiResolvedTheme, size: int = 38) -> None:
        super().__init__()
        self.theme = theme
        self.set_size_request(size, size)
        self.icon_name = icon_name
        self.set_draw_func(self._draw)

    def set_theme(self, theme: SekaiResolvedTheme) -> None:
        self.theme = theme
        self.queue_draw()

    def _draw(self, _area: Gtk.DrawingArea, cr: cairo.Context, w: int, h: int) -> None:
        cx, cy = w / 2.0, h / 2.0
        r = min(w, h) * 0.43
        cr.save()
        cr.move_to(cx, cy - r)
        cr.line_to(cx + r, cy)
        cr.line_to(cx, cy + r)
        cr.line_to(cx - r, cy)
        cr.close_path()
        cr.set_source_rgb(*_hex_to_rgb(self.theme.accent_a))
        cr.fill_preserve()
        cr.set_line_width(2.0)
        cr.set_source_rgb(*_hex_to_rgb(self.theme.line))
        cr.stroke()
        cr.restore()


# ---------------------------------------------------------------------------
# Shared SekaiPop Widgets
# ---------------------------------------------------------------------------


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
        self._title_label = Gtk.Label(label=title, xalign=0.0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, hexpand=True)
        self._title_label.add_css_class("heading")
        outer.append(self._title_label)

        self._subtitle_label = Gtk.Label(label=subtitle, xalign=0.0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR, hexpand=True)
        self._subtitle_label.add_css_class("caption")
        self._subtitle_label.add_css_class("sekai-muted")
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
            if isinstance(widget, Gtk.Button) and not widget.has_css_class("suggested-action"):
                widget.add_css_class("sekai-btn")
            flow.append(Gtk.FlowBoxChild(child=widget, focusable=False))
        outer.append(flow)
        self.set_child(outer)

    def set_title(self, title: str) -> None:
        self._title_label.set_text(title)

    def set_subtitle(self, subtitle: str) -> None:
        self._subtitle_label.set_text(subtitle)
        self._subtitle_label.set_visible(bool(subtitle))


# ---------------------------------------------------------------------------
# Main Controid Control Center Window
# ---------------------------------------------------------------------------


class ControidWindow(Adw.ApplicationWindow):
    def __init__(self, app: Adw.Application, initial_tab: Optional[str] = None) -> None:
        super().__init__(application=app, title="Controid — Centro de Control")
        self.set_icon_name("controid")
        self.set_size_request(360, 520)
        self.set_default_size(980, 730)
        self.add_css_class("sekai-window")

        self.gui_prefs = load_gui_prefs()
        self.theme = resolve_sekai_theme(self.gui_prefs["palette_id"], bool(self.gui_prefs["dark_mode"]))
        self.css_provider = Gtk.CssProvider()
        Gtk.StyleContext.add_provider_for_display(
            Gdk.Display.get_default(),
            self.css_provider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION,
        )

        self.daemon_online = False
        self.telemetry: dict[str, Any] = {
            "state": "idle",
            "battery": None,
            "charging": False,
            "tethering": False,
            "hid_connected": False,
        }
        self.files_status: dict[str, Any] = {"mounted": False, "mount_point": str(Path.home() / "Controid"), "connected": False, "error": None}
        self.last_error: str | None = None
        self.stream_active = False
        self.stream_resolution: str | None = None
        self.config_data = default_config()
        self.discovered_devices: list[dict[str, Any]] = []
        self.event_log_lines: list[str] = []
        self._stop_flag = threading.Event()
        self._suppress_settings_dirty = False
        self._immersive_mode = False
        self._is_narrow = False

        self._theme_drawings: list[Any] = []

        # Root Overlay: SekaiBackdropCanvas underneath ToolbarView
        self.root_overlay = Gtk.Overlay()
        self.root_overlay.add_css_class("sekai-root")
        self.backdrop = SekaiBackdropCanvas(self.theme)
        self.root_overlay.set_child(self.backdrop)

        self.toast_overlay = Adw.ToastOverlay()
        self.toolbar_view = Adw.ToolbarView()
        self.toolbar_view.set_top_bar_style(Adw.ToolbarStyle.FLAT)
        self.toolbar_view.set_bottom_bar_style(Adw.ToolbarStyle.FLAT)
        self.toast_overlay.set_child(self.toolbar_view)
        self.root_overlay.add_overlay(self.toast_overlay)
        self.set_content(self.root_overlay)

        self.header = Adw.HeaderBar()
        self.header.add_css_class("sekai-headerbar")
        self.view_stack = Adw.ViewStack()
        self.view_switcher = Adw.ViewSwitcher(stack=self.view_stack, policy=Adw.ViewSwitcherPolicy.WIDE)
        self.window_title = Adw.WindowTitle(title="Controid", subtitle="SekaiPop")
        self.header.set_title_widget(self.view_switcher)

        self.header_badge = Gtk.Label(label="◆ Iniciando")
        self.header_badge.add_css_class("sekai-chip")
        self.header.pack_end(self.header_badge)

        self.btn_fullscreen = Gtk.Button(icon_name="view-fullscreen-symbolic", tooltip_text="Pantalla completa integrada")
        self.btn_fullscreen.add_css_class("sekai-icon-btn")
        self.btn_fullscreen.connect("clicked", lambda _b: self._toggle_immersive())
        self.header.pack_end(self.btn_fullscreen)

        self.btn_theme = Gtk.MenuButton(icon_name="applications-graphics-symbolic", tooltip_text="Estilo y paleta SekaiPop")
        self.btn_theme.add_css_class("sekai-icon-btn")
        self.btn_theme.set_popover(self._build_theme_popover())
        self.header.pack_end(self.btn_theme)

        self.toolbar_view.add_top_bar(self.header)

        self.switcher_bar = Adw.ViewSwitcherBar(stack=self.view_stack, reveal=False)
        self.switcher_bar.add_css_class("sekai-bottom-bar")
        self.toolbar_view.add_bottom_bar(self.switcher_bar)

        bp = Adw.Breakpoint.new(Adw.BreakpointCondition.parse("max-width: 780sp"))
        bp.connect("apply", lambda _b: self._on_breakpoint_change(True))
        bp.connect("unapply", lambda _b: self._on_breakpoint_change(False))
        bp.add_setter(self.switcher_bar, "reveal", True)
        bp.add_setter(self.header, "title-widget", self.window_title)
        self.add_breakpoint(bp)

        self.error_banner = Adw.Banner(title="")
        self.error_banner.set_button_label("Limpiar")
        self.error_banner.connect("button-clicked", lambda _b: self._clear_error_banner())

        main_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        main_box.append(self.error_banner)
        self.view_stack.set_vexpand(True)
        main_box.append(self.view_stack)
        self.toolbar_view.set_content(main_box)

        self._build_panel_tab()
        self._build_clipboard_tab()
        self._build_settings_tab()
        self._build_diagnostics_tab()

        self._apply_sekai_theme()
        self.view_stack.connect("notify::visible-child-name", lambda _s, _p: self._sync_chrome_visibility())
        self.connect("close-request", self._on_close_request)
        if initial_tab:
            self.view_stack.set_visible_child_name(initial_tab)
        GLib.idle_add(lambda: (self.set_focus(None), False)[1])

        key_ctrl = Gtk.EventControllerKey()
        key_ctrl.connect("key-pressed", self._on_key_pressed)
        self.add_controller(key_ctrl)

        self._load_config_async()
        self._refresh_status_once()
        self._refresh_systemd_status()
        self._refresh_diagnostics()
        self._refresh_journal()
        self._load_paired_devices_initial()

        self._event_thread = threading.Thread(target=self._ipc_event_loop, daemon=True)
        self._event_thread.start()

        GLib.timeout_add_seconds(5, self._periodic_poll)

    # ------------------------------------------------------------------ Theme & Chrome

    def _on_breakpoint_change(self, narrow: bool) -> None:
        self._is_narrow = narrow
        if hasattr(self, "stat_flow"):
            self.stat_flow.set_max_children_per_line(3 if narrow else 5)

    def _on_key_pressed(self, _ctrl: Gtk.EventControllerKey, keyval: int, _keycode: int, _state: Gdk.ModifierType) -> bool:
        if keyval == Gdk.KEY_F11:
            self._toggle_immersive()
            return True
        if keyval == Gdk.KEY_Escape and self._immersive_mode:
            self._toggle_immersive()
            return True
        return False

    def _toggle_immersive(self) -> None:
        self._immersive_mode = not self._immersive_mode
        self._sync_chrome_visibility()

    def _sync_chrome_visibility(self) -> None:
        imm = self._immersive_mode
        self.toolbar_view.set_reveal_top_bars(not imm)
        self.toolbar_view.set_reveal_bottom_bars(not imm)
        if hasattr(self, "panel_floating_pill"):
            self.panel_floating_pill.set_visible(imm)
        self.btn_fullscreen.set_icon_name("view-restore-symbolic" if imm else "view-fullscreen-symbolic")

    def _build_theme_popover(self) -> Gtk.Popover:
        pop = Gtk.Popover()
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_top=10, margin_bottom=10, margin_start=10, margin_end=10)

        hdr = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        lbl = Gtk.Label(label="Modo oscuro", xalign=0.0, hexpand=True)
        lbl.add_css_class("heading")
        self.switch_dark_theme = Gtk.Switch(active=bool(self.gui_prefs["dark_mode"]), valign=Gtk.Align.CENTER)
        self.switch_dark_theme.connect("notify::active", self._on_dark_toggled)
        hdr.append(lbl)
        hdr.append(self.switch_dark_theme)
        box.append(hdr)

        sep = Gtk.Separator()
        box.append(sep)

        sub = Gtk.Label(label="Paleta SekaiPop", xalign=0.0)
        sub.add_css_class("caption")
        sub.add_css_class("sekai-muted")
        box.append(sub)

        self._palette_buttons: dict[str, Gtk.Button] = {}
        for pal in SEKAI_PALETTES:
            row_btn = Gtk.Button()
            row_btn.add_css_class("sekai-btn")
            row_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
            swatch = Gtk.DrawingArea()
            swatch.set_size_request(18, 18)

            def draw_sw(_a: Gtk.DrawingArea, cr: cairo.Context, w: int, h: int, p: SekaiUnitPalette = pal) -> None:
                cr.arc(w / 2.0, h / 2.0, min(w, h) * 0.42, 0, math.tau)
                cr.set_source_rgb(*_hex_to_rgb(p.accent_a))
                cr.fill_preserve()
                cr.set_line_width(2.0)
                cr.set_source_rgb(*_hex_to_rgb(p.border))
                cr.stroke()

            swatch.set_draw_func(draw_sw)
            row_box.append(swatch)
            row_box.append(Gtk.Label(label=pal.name, xalign=0.0, hexpand=True))
            row_btn.set_child(row_box)
            row_btn.connect("clicked", lambda _b, pid=pal.id, p_ref=pop: (self._select_palette(pid), p_ref.popdown()))
            self._palette_buttons[pal.id] = row_btn
            box.append(row_btn)

        pop.set_child(box)
        return pop

    def _on_dark_toggled(self, sw: Gtk.Switch, _pspec: Any) -> None:
        self.gui_prefs["dark_mode"] = bool(sw.get_active())
        save_gui_prefs(self.gui_prefs)
        self._apply_sekai_theme()

    def _select_palette(self, palette_id: str) -> None:
        self.gui_prefs["palette_id"] = palette_id
        save_gui_prefs(self.gui_prefs)
        self._apply_sekai_theme()

    def _apply_sekai_theme(self) -> None:
        self.theme = resolve_sekai_theme(str(self.gui_prefs.get("palette_id", "mono")), bool(self.gui_prefs.get("dark_mode", True)))
        mgr = Adw.StyleManager.get_default()
        mgr.set_color_scheme(Adw.ColorScheme.FORCE_DARK if self.theme.is_dark else Adw.ColorScheme.FORCE_LIGHT)
        self.css_provider.load_from_data(build_sekai_css(self.theme).encode("utf-8"))
        self.backdrop.set_theme(self.theme)
        for widget in self._theme_drawings:
            widget.set_theme(self.theme)
        if hasattr(self, "_palette_buttons"):
            for pid, btn in self._palette_buttons.items():
                if pid == self.theme.palette.id:
                    btn.add_css_class("sekai-active")
                else:
                    btn.remove_css_class("sekai-active")

    def _make_hero(self, icon_name: str, title: str, subtitle: str, right_widget: Gtk.Widget | None = None) -> tuple[Gtk.Box, Gtk.Label]:
        hero = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12, margin_bottom=10)
        hero.add_css_class("sekai-hero")

        badge_overlay = Gtk.Overlay(valign=Gtk.Align.CENTER)
        diamond = SekaiDiamondIcon(icon_name, self.theme, size=40)
        self._theme_drawings.append(diamond)
        badge_overlay.set_child(diamond)
        ic = Gtk.Image.new_from_icon_name(icon_name)
        ic.set_pixel_size(16)
        ic.set_halign(Gtk.Align.CENTER)
        ic.set_valign(Gtk.Align.CENTER)
        ic.add_css_class("sekai-diamond-ic")
        badge_overlay.add_overlay(ic)
        hero.append(badge_overlay)

        tbox = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True, valign=Gtk.Align.CENTER)
        tl = Gtk.Label(label=title, xalign=0.0)
        tl.add_css_class("title-3")
        sl = Gtk.Label(label=subtitle, xalign=0.0, wrap=True, wrap_mode=Pango.WrapMode.WORD_CHAR)
        sl.add_css_class("caption")
        sl.add_css_class("sekai-muted")
        tbox.append(tl)
        tbox.append(sl)
        hero.append(tbox)

        if right_widget is not None:
            right_widget.set_valign(Gtk.Align.CENTER)
            hero.append(right_widget)
        return hero, sl

    def _make_control_row(self, title: str, subtitle: str, widgets: list[Gtk.Widget]) -> ControlRow:
        return ControlRow(title, subtitle, widgets)

    # ------------------------------------------------------------------ Tabs

    def _build_panel_tab(self) -> None:
        overlay = Gtk.Overlay()
        page = Adw.PreferencesPage()
        page.set_title("Panel")

        hero_chip = Gtk.Label(label="En espera")
        hero_chip.add_css_class("sekai-chip-accent")
        hero, self.panel_hero_sub = self._make_hero(
            "network-wireless-symbolic",
            "Panel Controid",
            "Enlace Linux ↔ Android, pantalla, HID y almacenamiento FUSE",
            hero_chip,
        )
        self.panel_hero_chip = hero_chip

        top_group = Adw.PreferencesGroup()
        top_group.add(hero)

        # Quick stat tiles row matching Android Dashboard
        self.stat_flow = Gtk.FlowBox(
            selection_mode=Gtk.SelectionMode.NONE,
            row_spacing=8,
            column_spacing=8,
            min_children_per_line=2,
            max_children_per_line=5,
            homogeneous=True,
            margin_bottom=10,
        )
        stat_flow = self.stat_flow
        self.stat_vals: dict[str, Gtk.Label] = {}
        for key, label in (
            ("link", "ENLACE"),
            ("battery", "BATERÍA"),
            ("hid", "ENTRADA HID"),
            ("stream", "PANTALLA"),
            ("files", "DISCO FUSE"),
        ):
            card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=3)
            card.add_css_class("sekai-stat-card")
            t_lbl = Gtk.Label(label=label, xalign=0.0)
            t_lbl.add_css_class("sekai-stat-title")
            v_lbl = Gtk.Label(label="—", xalign=0.0, ellipsize=Pango.EllipsizeMode.END)
            v_lbl.add_css_class("sekai-stat-val")
            card.append(t_lbl)
            card.append(v_lbl)
            self.stat_vals[key] = v_lbl
            stat_flow.append(Gtk.FlowBoxChild(child=card, focusable=False))
        top_group.add(stat_flow)
        page.add(top_group)

        # Detailed status & telemetry group
        status_group = Adw.PreferencesGroup(title="Estado detallado", description="Telemetría en tiempo real de controidd")
        self.row_link_state = Adw.ActionRow(title="Estado de conexión", subtitle="Comprobando…")
        self.row_battery = Adw.ActionRow(title="Batería del teléfono", subtitle="No disponible")
        self.row_hid = Adw.ActionRow(title="Control HID (Teclado / Ratón BT)", subtitle="Desconectado")
        self.row_stream = Adw.ActionRow(title="Transmisión de pantalla", subtitle="Inactiva")
        self.row_endpoint = Adw.ActionRow(title="Destino configurado", subtitle="Sin configurar")
        for r in (self.row_link_state, self.row_battery, self.row_hid, self.row_stream, self.row_endpoint):
            status_group.add(r)
        page.add(status_group)

        # Quick controls group
        actions_group = Adw.PreferencesGroup(
            title="Control de conexión y pantalla",
            description="Usa Wi-Fi LAN directa o activa el Hotspot móvil vía Bluetooth LE",
        )
        self.btn_connect = Gtk.Button(label="Conectar")
        self.btn_connect.add_css_class("suggested-action")
        self.btn_connect.connect("clicked", lambda _b: self._send_ipc_action("connection.resume", timeout=30.0))
        self.btn_disconnect = Gtk.Button(label="Desconectar")
        self.btn_disconnect.connect("clicked", lambda _b: self._send_ipc_action("connection.disconnect", timeout=15.0))
        actions_group.add(self._make_control_row("Enlace Controid", "Sesión TCP autenticada con Ed25519", [self.btn_connect, self.btn_disconnect]))

        self.btn_hs_on = Gtk.Button(label="Activar Hotspot")
        self.btn_hs_on.connect("clicked", lambda _b: self._send_ipc_action("hotspot.enable", timeout=30.0))
        self.btn_hs_off = Gtk.Button(label="Apagar Hotspot")
        self.btn_hs_off.connect("clicked", lambda _b: self._send_ipc_action("hotspot.disable", timeout=15.0))
        actions_group.add(self._make_control_row("Hotspot Wi-Fi móvil (BLE)", "Enciende o apaga el punto de acceso Android (Shizuku)", [self.btn_hs_on, self.btn_hs_off]))

        self.btn_stream_start = Gtk.Button(label="Iniciar pantalla")
        self.btn_stream_start.add_css_class("suggested-action")
        self.btn_stream_start.connect("clicked", lambda _b: self._start_stream())
        self.btn_stream_stop = Gtk.Button(label="Detener pantalla")
        self.btn_stream_stop.connect("clicked", lambda _b: self._send_ipc_action("stream.stop", timeout=10.0))
        actions_group.add(self._make_control_row("Duplicar pantalla (controid-viewer)", "Captura hardware H.264/H.265 interactiva", [self.btn_stream_start, self.btn_stream_stop]))

        self.nav_buttons = []
        for label, key_name in (("Atrás", "back"), ("Inicio", "home"), ("Recientes", "recents"), ("Encender", "power")):
            nav_btn = Gtk.Button(label=label)
            nav_btn.connect("clicked", lambda _b, k=key_name: self._send_ipc_action("input.key", {"key": k}, timeout=5.0))
            self.nav_buttons.append(nav_btn)
        actions_group.add(self._make_control_row("Navegación rápida Android", "Envía eventos del sistema al móvil", self.nav_buttons))
        page.add(actions_group)

        # FUSE files
        files_group = Adw.PreferencesGroup(title="Almacenamiento del teléfono (FUSE)")
        self.btn_files_mount = Gtk.Button(label="Montar carpeta")
        self.btn_files_mount.add_css_class("suggested-action")
        self.btn_files_mount.connect("clicked", lambda _b: self._send_ipc_action("files.mount", timeout=15.0))
        self.btn_files_unmount = Gtk.Button(label="Desmontar")
        self.btn_files_unmount.connect("clicked", lambda _b: self._send_ipc_action("files.unmount", timeout=15.0))
        self.btn_files_open = Gtk.Button(label="Abrir carpeta")
        self.btn_files_open.connect("clicked", lambda _b: self._open_mount_folder())
        self.row_files = self._make_control_row("Carpeta remota Android", f"Desmontado ({Path.home() / 'Controid'})", [self.btn_files_mount, self.btn_files_unmount, self.btn_files_open])
        files_group.add(self.row_files)
        page.add(files_group)

        # Systemd + tray
        sys_group = Adw.PreferencesGroup(title="Servicio y Bandeja")
        sys_buttons: list[Gtk.Widget] = []
        for label, action in (("Iniciar", "start"), ("Reiniciar", "restart"), ("Detener", "stop"), ("Autoarranque", "enable")):
            btn = Gtk.Button(label=label)
            btn.connect("clicked", lambda _b, a=action: self._run_systemd_action(a))
            sys_buttons.append(btn)
        self.row_systemd = self._make_control_row("controid.service" if has_systemd_user() else "controidd", "Consultando estado…", sys_buttons)
        sys_group.add(self.row_systemd)

        tray_btn = Gtk.Button(label="Iniciar bandeja")
        tray_btn.connect("clicked", lambda _b: self._launch_tray())
        self.row_tray = self._make_control_row("Bandeja SNI (controid-tray)", "Acceso desde Waybar, KDE, XFCE", [tray_btn])
        sys_group.add(self.row_tray)
        page.add(sys_group)

        self.panel_floating_pill = self._make_floating_exit_pill()
        overlay.set_child(page)
        overlay.add_overlay(self.panel_floating_pill)

        sp = self.view_stack.add_titled(overlay, "panel", "Panel")
        sp.set_icon_name("network-wireless-symbolic")

    def _make_floating_exit_pill(self) -> Gtk.Box:
        pill = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6, halign=Gtk.Align.END, valign=Gtk.Align.START, margin_top=10, margin_end=14)
        pill.add_css_class("sekai-floating-pill")
        btn_exit = Gtk.Button(icon_name="view-restore-symbolic", tooltip_text="Salir de pantalla completa")
        btn_exit.add_css_class("sekai-icon-btn")
        btn_exit.connect("clicked", lambda _b: self._toggle_immersive())
        pill.append(btn_exit)
        pill.set_visible(False)
        return pill

    def _build_clipboard_tab(self) -> None:
        page = Adw.PreferencesPage()
        page.set_title("Clip")

        hero, _ = self._make_hero(
            "edit-paste-symbolic",
            "Portapapeles Universal",
            "Sincronización bidireccional cifrada (texto, HTML, URIs e imágenes)",
        )
        top_g = Adw.PreferencesGroup()
        top_g.add(hero)
        page.add(top_g)

        send_group = Adw.PreferencesGroup(title="Enviar a Android")
        text_frame = Gtk.Frame()
        text_frame.add_css_class("sekai-frame")
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
        send_group.add(self._make_control_row("Formato y envío", "Elige MIME y envía al teléfono", [self.clip_mime_dropdown, btn_paste, btn_send]))
        page.add(send_group)

        hist_group = Adw.PreferencesGroup(title="Historial de transferencias")
        clear_btn = Gtk.Button(label="Limpiar historial")
        clear_btn.add_css_class("sekai-btn")
        clear_btn.connect("clicked", lambda _b: self._clear_clipboard_history())
        hist_group.set_header_suffix(clear_btn)

        self.clip_history_list = Gtk.ListBox()
        self.clip_history_list.add_css_class("boxed-list")
        self.clip_history_list.set_selection_mode(Gtk.SelectionMode.NONE)
        self.clip_empty_row = Adw.ActionRow(title="Sin transferencias registradas aún", subtitle="Aparecerán aquí al enviar o recibir contenido")
        self.clip_history_list.append(self.clip_empty_row)
        hist_group.add(self.clip_history_list)
        page.add(hist_group)

        sp = self.view_stack.add_titled(page, "clipboard", "Clip")
        sp.set_icon_name("edit-paste-symbolic")

    def _build_settings_tab(self) -> None:
        page = Adw.PreferencesPage()
        page.set_title("Ajustes")

        hero, _ = self._make_hero(
            "preferences-system-symbolic",
            "Dispositivos y Ajustes",
            "Emparejamiento Bluetooth LE, Wi-Fi LAN directa, vídeo y FUSE",
        )
        top_g = Adw.PreferencesGroup()
        top_g.add(hero)
        page.add(top_g)

        dev_group = Adw.PreferencesGroup(title="Dispositivo Android y Bluetooth LE")
        scan_btn = Gtk.Button(label="Escanear BLE (8s)")
        scan_btn.add_css_class("sekai-btn")
        scan_btn.connect("clicked", lambda _b: self._scan_ble_devices())
        dev_group.set_header_suffix(scan_btn)

        self.ble_devices_list = Gtk.ListBox()
        self.ble_devices_list.add_css_class("boxed-list")
        self.ble_devices_list.set_selection_mode(Gtk.SelectionMode.NONE)
        dev_group.add(self.ble_devices_list)

        self.entry_mac = Adw.EntryRow(title="Dirección MAC Bluetooth")
        self.entry_uuid = Adw.EntryRow(title="UUID de servicio GATT BLE")
        self.entry_ssid = Adw.EntryRow(title="SSID del Hotspot Wi-Fi Android")
        ssid_pick_btn = Gtk.Button(icon_name="view-list-symbolic", valign=Gtk.Align.CENTER, tooltip_text="Elegir red guardada")
        ssid_pick_btn.add_css_class("flat")
        ssid_pick_btn.connect("clicked", self._on_pick_saved_ssid)
        self.entry_ssid.add_suffix(ssid_pick_btn)

        clear_key_btn = Gtk.Button(label="Restablecer clave")
        clear_key_btn.connect("clicked", lambda _b: self._reset_peer_pubkey())
        self.row_pubkey = self._make_control_row("Clave pública Ed25519 (TOFU)", "Sin clave fijada", [clear_key_btn])

        for w in (self.entry_mac, self.entry_uuid, self.entry_ssid, self.row_pubkey):
            dev_group.add(w)
        page.add(dev_group)

        net_group = Adw.PreferencesGroup(title="Red y Modo de Conexión")
        mode_model = Gtk.StringList.new(["Automático (LAN directa -> BLE + Hotspot)", "Solo Wi-Fi LAN directa", "Solo BLE + Hotspot Android"])
        self.combo_mode = Adw.ComboRow(title="Modo de conexión", model=mode_model)

        self.entry_direct_host = Adw.EntryRow(title="IP Wi-Fi LAN directa (direct_host)")
        adb_ip_btn = Gtk.Button(icon_name="network-wired-symbolic", valign=Gtk.Align.CENTER, tooltip_text="Detectar IP por ADB")
        adb_ip_btn.add_css_class("flat")
        adb_ip_btn.connect("clicked", lambda _b: self._detect_lan_ip_via_adb())
        self.entry_direct_host.add_suffix(adb_ip_btn)

        self.entry_gateway_ip = Adw.EntryRow(title="IP Gateway Hotspot")
        self.spin_tcp_port = Adw.SpinRow.new_with_range(1, 65535, 1)
        self.spin_tcp_port.set_title("Puerto TCP de datos")
        self.spin_cooldown = Adw.SpinRow.new_with_range(1, 600, 5)
        self.spin_cooldown.set_title("Cooldown reconexión Bluetooth (s)")
        self.spin_retries = Adw.SpinRow.new_with_range(0, 50, 1)
        self.spin_retries.set_title("Máximo reintentos")

        for w in (self.combo_mode, self.entry_direct_host, self.entry_gateway_ip, self.spin_tcp_port, self.spin_cooldown, self.spin_retries):
            net_group.add(w)
        page.add(net_group)

        stream_group = Adw.PreferencesGroup(title="Transmisión de pantalla")
        self.spin_fps = Adw.SpinRow.new_with_range(15, 120, 5)
        self.spin_fps.set_title("FPS máximos")
        self.spin_bitrate = Adw.SpinRow.new_with_range(1, 50, 1)
        self.spin_bitrate.set_title("Bitrate (Mbps)")
        self.combo_codec = Adw.ComboRow(title="Códec de vídeo", model=Gtk.StringList.new(["H.264 (video/avc)", "H.265 / HEVC (video/hevc)"]))
        for w in (self.spin_fps, self.spin_bitrate, self.combo_codec):
            stream_group.add(w)
        page.add(stream_group)

        files_cfg_group = Adw.PreferencesGroup(title="Archivos FUSE")
        self.entry_mount_point = Adw.EntryRow(title="Carpeta de montaje local")
        self.switch_auto_mount = Adw.SwitchRow(title="Montar automáticamente al conectar", subtitle="Desmonta al suspender o cerrar")
        files_cfg_group.add(self.entry_mount_point)
        files_cfg_group.add(self.switch_auto_mount)
        page.add(files_cfg_group)

        save_group = Adw.PreferencesGroup()
        reload_btn = Gtk.Button(label="Recargar")
        reload_btn.connect("clicked", lambda _b: self._load_config_async())
        save_btn = Gtk.Button(label="Guardar y aplicar")
        save_btn.add_css_class("suggested-action")
        save_btn.connect("clicked", lambda _b: self._save_config_async())
        save_group.add(self._make_control_row("Aplicar configuración", f"Archivo: {config_path()}", [reload_btn, save_btn]))
        page.add(save_group)

        sp = self.view_stack.add_titled(page, "settings", "Ajustes")
        sp.set_icon_name("preferences-system-symbolic")

    def _build_diagnostics_tab(self) -> None:
        page = Adw.PreferencesPage()
        page.set_title("Logs")

        hero, _ = self._make_hero(
            "utilities-terminal-symbolic",
            "Diagnóstico y Registro",
            "Dependencias del sistema, ADB y registro en vivo del demonio",
        )
        top_g = Adw.PreferencesGroup()
        top_g.add(hero)
        page.add(top_g)

        health_group = Adw.PreferencesGroup(title="Salud del sistema y dependencias")
        btn_open_apk = Gtk.Button(label="Abrir app en móvil (ADB)")
        btn_open_apk.connect("clicked", lambda _b: self._launch_android_app_adb())
        btn_recheck = Gtk.Button(label="Volver a comprobar")
        btn_recheck.connect("clicked", lambda _b: self._refresh_diagnostics())
        health_group.add(self._make_control_row("Acciones de diagnóstico", "Verifica herramientas o abre Controid en Android", [btn_open_apk, btn_recheck]))

        self.diag_list = Gtk.ListBox()
        self.diag_list.add_css_class("boxed-list")
        self.diag_list.set_selection_mode(Gtk.SelectionMode.NONE)
        health_group.add(self.diag_list)
        page.add(health_group)

        logs_group = Adw.PreferencesGroup(title="Registro de eventos y servicio")
        btn_refresh_logs = Gtk.Button(label="Actualizar logs")
        btn_refresh_logs.add_css_class("sekai-btn")
        btn_refresh_logs.connect("clicked", lambda _b: self._refresh_journal())
        logs_group.set_header_suffix(btn_refresh_logs)

        log_frame = Gtk.Frame()
        log_frame.add_css_class("sekai-frame")
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

        sp = self.view_stack.add_titled(page, "diagnostics", "Logs")
        sp.set_icon_name("utilities-terminal-symbolic")

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
        self.header_badge.set_text(f"◆ {'Conectado' if state_key == 'connected' else label.split('(')[0].strip()}")
        self.row_link_state.set_subtitle(label)

        bat = self.telemetry.get("battery")
        chg = bool(self.telemetry.get("charging", False))
        bat_txt = f"{bat}% {'⚡' if chg else ''}".strip() if isinstance(bat, int) else "—"
        self.row_battery.set_subtitle(f"{bat}% {'(Cargando)' if chg else '(En batería)'}" if isinstance(bat, int) else "No disponible")

        hid = bool(self.telemetry.get("hid_connected", False))
        teth = bool(self.telemetry.get("tethering", False))
        hid_txt = "BT HID" if hid else ("TCP Live" if state_key == "connected" else "En espera")
        self.row_hid.set_subtitle("Conectado vía Bluetooth HID nativo" if hid else ("Fallback TCP activo" if state_key == "connected" else "En espera"))

        stream_txt = f"Activa {self.stream_resolution or ''}".strip() if self.stream_active else ("Lista" if state_key == "connected" else "Inactiva")
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
            f"Modo: {mode} | LAN: {direct}:{port} | MAC BT: {mac} | SSID: {ssid} | Hotspot: {'activo' if teth else 'inactivo'}"
        )
        if hasattr(self, "panel_hero_sub"):
            self.panel_hero_sub.set_text(f"{label} • LAN {direct}:{port}")
        if hasattr(self, "panel_hero_chip"):
            self.panel_hero_chip.set_text(f"{mode.upper()} • {port}" if self.daemon_online else "Demonio offline")

        can_cmd = self.daemon_online
        is_connected = state_key == "connected"
        hotspot_connected = is_connected and teth
        self.btn_connect.set_sensitive(can_cmd and not is_connected)
        self.btn_disconnect.set_sensitive(can_cmd and state_key != "idle")
        self.btn_hs_on.set_label("Cambiar a Hotspot" if (is_connected and not teth) else "Activar Hotspot")
        self.btn_hs_on.set_sensitive(can_cmd and not hotspot_connected)
        self.btn_hs_off.set_sensitive(can_cmd)
        self.btn_stream_start.set_sensitive(can_cmd and is_connected and not self.stream_active)
        self.btn_stream_stop.set_sensitive(can_cmd and self.stream_active)
        for btn in self.nav_buttons:
            btn.set_sensitive(can_cmd and is_connected)

        files_mounted = bool(self.files_status.get("mounted", False))
        raw_mp = str(self.files_status.get("mount_point") or self.config_data.get("files", {}).get("mount_point") or "~/Controid")
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

        if hasattr(self, "stat_vals"):
            self.stat_vals["link"].set_text("Conectado" if is_connected else label.split("(")[0].strip())
            self.stat_vals["battery"].set_text(bat_txt)
            self.stat_vals["hid"].set_text(hid_txt)
            self.stat_vals["stream"].set_text(stream_txt)
            self.stat_vals["files"].set_text("Montado" if files_mounted else "Desmontado")

    def _open_mount_folder(self) -> None:
        raw_mp = str(self.files_status.get("mount_point") or self.config_data.get("files", {}).get("mount_point") or "~/Controid")
        mount_point = Path(os.path.expanduser(raw_mp))
        if not mount_point.exists():
            self._toast(f"La carpeta no existe: {mount_point}")
            return
        try:
            subprocess.Popen(["xdg-open", str(mount_point)], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        except Exception as exc:
            self._toast(f"No se pudo abrir el gestor: {exc}")

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

    @staticmethod
    def _format_action_toast(method: str, res: Any) -> str:
        status = res.get("status") if isinstance(res, dict) else None
        if method == "hotspot.enable":
            if status == "starting":
                return "Activando Hotspot por BLE…"
            if status == "connected":
                return "Hotspot ya activo y conectado"
            if status:
                return f"Hotspot: {status}"
        elif method == "hotspot.disable":
            return "Apagando Hotspot…"
        elif method == "connection.resume":
            if status == "connected":
                return "Ya conectado"
            if status in ("starting", "resuming"):
                return "Iniciando conexión…"
        elif method == "connection.disconnect":
            return "Desconectando…"
        return f"Comando '{method}' enviado"

    def _send_ipc_action(self, method: str, params: dict[str, Any] | None = None, timeout: float = 15.0) -> None:
        def worker() -> None:
            try:
                res = ipc_call(method, params or {}, timeout=timeout)
                self._toast(self._format_action_toast(method, res))
                GLib.idle_add(lambda r=res: self._after_action(method, r) or False)
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
        self._send_ipc_action("stream.start", {"max_fps": fps, "bitrate_mbps": bitrate, "codec": codec}, timeout=15.0)

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
            self._add_clipboard_history("Recibido de Android", mime, text if isinstance(text, str) else None, size_bytes=size_bytes, file_path=file_path)
        elif name == "config.changed":
            self._apply_loaded_config(data)

        self._render_status()

    # ------------------------------------------------------------------ Clipboard

    def _paste_local_clipboard(self) -> None:
        text_mimes = ("text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING", "TEXT")

        def read_via_gdk_fallback() -> None:
            disp = Gdk.Display.get_default()
            if not disp:
                self._toast("No hay texto en el portapapeles")
                return
            clip = disp.get_clipboard()
            formats = clip.get_formats()
            mime_types = formats.get_mime_types() if formats else []
            if any(m.startswith("image/") for m in mime_types) and not any(m in text_mimes or m.startswith("text/") for m in mime_types):
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
                offered = [l.strip() for l in types_out.splitlines() if l.strip()] if rc_types == 0 else []
                chosen_text_mime = next((m for m in text_mimes if m in offered), None)
                if chosen_text_mime:
                    rc, out, _ = run_cmd(["wl-paste", "--type", chosen_text_mime, "--no-newline"], timeout=2.0)
                    if rc == 0 and out:
                        GLib.idle_add(lambda o=out: self.clip_text_view.get_buffer().set_text(o) or False)
                        return
                if any(m.startswith("image/") for m in offered):
                    GLib.idle_add(lambda: self._toast("El portapapeles contiene una imagen") or False)
                    return
                if not offered:
                    rc, out, _ = run_cmd(["wl-paste", "--no-newline"], timeout=2.0)
                    if rc == 0 and out:
                        GLib.idle_add(lambda o=out: self.clip_text_view.get_buffer().set_text(o) or False)
                        return

            if os.environ.get("DISPLAY"):
                if shutil.which("xclip"):
                    rc_t, t_out, _ = run_cmd(["xclip", "-o", "-selection", "clipboard", "-t", "TARGETS"], timeout=2.0)
                    offered_x = [l.strip() for l in t_out.splitlines() if l.strip()] if rc_t == 0 else []
                    chosen_x = next((m for m in text_mimes if m in offered_x), None)
                    if chosen_x:
                        rc_x, x_out, _ = run_cmd(["xclip", "-o", "-selection", "clipboard", "-t", chosen_x], timeout=2.0)
                        if rc_x == 0 and x_out:
                            GLib.idle_add(lambda o=x_out: self.clip_text_view.get_buffer().set_text(o) or False)
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
                GLib.idle_add(lambda: (self._add_clipboard_history("Enviado a Android", mime, text), self._toast("Portapapeles enviado al teléfono"))[1] or False)
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

    def _add_clipboard_history(self, direction: str, mime: str, text: str | None = None, *, size_bytes: int = 0, file_path: str | None = None) -> None:
        if self.clip_empty_row.get_parent() is self.clip_history_list:
            self.clip_history_list.remove(self.clip_empty_row)
        stamp = datetime.now().strftime("%H:%M:%S")

        if isinstance(text, str):
            snippet = text.replace("\n", " ")[:140]
            row = Adw.ActionRow(title=html.escape(f"[{stamp}] {direction} ({mime})"), subtitle=html.escape(snippet or "(vacío)"))
            copy_btn = Gtk.Button(label="Copiar", valign=Gtk.Align.CENTER)
            copy_btn.add_css_class("sekai-btn")
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
        subtitle = (
            f"Imagen {image_tex.get_width()}×{image_tex.get_height()} • {size_label}"
            if image_tex is not None
            else (f"Imagen ({mime}) • {size_label}" if mime.startswith("image/") else f"Binario ({mime}) • {size_label}")
        )
        row = Adw.ActionRow(title=html.escape(f"[{stamp}] {direction} ({mime})"), subtitle=html.escape(subtitle))
        if image_tex is not None:
            thumb = Gtk.Image.new_from_paintable(image_tex)
            thumb.set_pixel_size(36)
            row.add_prefix(thumb)
        if file_path and Path(file_path).is_file():
            copy_btn = Gtk.Button(label="Copiar imagen" if mime.startswith("image/") else "Copiar", valign=Gtk.Align.CENTER)
            copy_btn.add_css_class("sekai-btn")
            copy_btn.connect("clicked", lambda _b, p=file_path, m=mime: self._copy_file_to_pc_clipboard(p, m))
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
            self._toast("La imagen ya no está disponible")
            return
        if detect_session_kind() == "wayland" and shutil.which("wl-copy"):
            def worker() -> None:
                try:
                    with path.open("rb") as fh:
                        proc = subprocess.run(["wl-copy", "--type", mime], stdin=fh, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=3.0, check=False)
                    if proc.returncode != 0:
                        raise RuntimeError(f"wl-copy código {proc.returncode}")
                    GLib.idle_add(lambda: self._toast("Imagen copiada al portapapeles") or False)
                except Exception as exc:
                    GLib.idle_add(lambda e=str(exc): self._toast(f"No se pudo copiar imagen: {e}") or False)

            threading.Thread(target=worker, daemon=True).start()
            return
        disp = Gdk.Display.get_default()
        if disp:
            try:
                disp.get_clipboard().set(Gdk.Texture.new_from_filename(file_path))
                self._toast("Imagen copiada al portapapeles")
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
        dev, net, st, files_cfg = cfg["device"], cfg["network"], cfg["streaming"], cfg["files"]

        self._suppress_settings_dirty = True
        try:
            self.entry_mac.set_text(str(dev.get("mac_address", "")))
            self.entry_uuid.set_text(str(dev.get("service_uuid", DEFAULT_SERVICE_UUID)))
            self.entry_ssid.set_text(str(dev.get("hotspot_ssid", "")))

            pubkey = dev.get("peer_ed25519_pubkey_hex")
            self.row_pubkey.set_subtitle(
                f"Fijada: {pubkey[:16]}…{pubkey[-8:]}" if isinstance(pubkey, str) and pubkey.strip() else "Sin clave fijada aún (TOFU automático)"
            )

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
        mode_val = {0: "auto", 1: "lan", 2: "hotspot"}.get(self.combo_mode.get_selected(), "auto")
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
            "streaming": {"max_fps": int(self.spin_fps.get_value()), "bitrate_mbps": int(self.spin_bitrate.get_value()), "codec_mime": codec_val},
            "files": {"mount_point": self.entry_mount_point.get_text().strip() or "~/Controid", "auto_mount": bool(self.switch_auto_mount.get_active())},
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
            merged: dict[str, dict[str, Any]] = {d["mac_address"]: d for d in get_paired_bt_devices()}
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
                self._toast(f"Escaneo IPC falló ({exc}); mostrando emparejados")

            devices = sorted(merged.values(), key=lambda x: (not x.get("paired", False), not x.get("connected", False), x.get("mac_address", "")))
            GLib.idle_add(lambda d=devices: self._render_ble_devices(d, initial=False) or False)

        threading.Thread(target=worker, daemon=True).start()

    def _render_ble_devices(self, devices: list[dict[str, Any]], initial: bool = False) -> None:
        child = self.ble_devices_list.get_first_child()
        while child is not None:
            nxt = child.get_next_sibling()
            self.ble_devices_list.remove(child)
            child = nxt

        if not devices:
            self.ble_devices_list.append(Adw.ActionRow(title="Ningún dispositivo encontrado", subtitle="Empareja el teléfono por Bluetooth o escanea"))
            return

        for dev in devices:
            mac = str(dev.get("mac_address", ""))
            name = dev.get("name") or "(Sin nombre)"
            badges = [b for b, f in (("Emparejado", dev.get("paired")), ("Conectado", dev.get("connected"))) if f]
            row = Adw.ActionRow(title=html.escape(f"{name}{' — ' + ', '.join(badges) if badges else ''}"), subtitle=html.escape(mac))
            pick = Gtk.Button(label="Seleccionar", valign=Gtk.Align.CENTER)
            pick.add_css_class("sekai-btn")
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
            self._toast("No se encontraron perfiles Wi-Fi guardados")
            return
        popover = Gtk.Popover()
        popover.set_parent(anchor_btn)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4, margin_top=6, margin_bottom=6, margin_start=6, margin_end=6)
        for ssid in ssids[:12]:
            btn = Gtk.Button(label=ssid)
            btn.add_css_class("flat")
            btn.connect("clicked", lambda _b, s=ssid, pop=popover: (self.entry_ssid.set_text(s), pop.popdown()))
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
                text = f"Proceso: {'activo' if active else 'inactivo'} | Autostart XDG: {'habilitado' if autostart_desktop_path().is_file() else 'deshabilitado'}"
                title = "controidd (sin systemd)"

            GLib.idle_add(lambda t=title, s=text: (self.row_systemd.set_title(t), self.row_systemd.set_subtitle(s))[1] or False)

        threading.Thread(target=worker, daemon=True).start()

    def _spawn_daemon_detached(self) -> tuple[bool, str]:
        exe = resolve_controid_bin("controidd")
        if not exe:
            return False, "No se encontró el binario controidd"
        log_file = daemon_log_path()
        log_file.parent.mkdir(parents=True, exist_ok=True)
        try:
            with log_file.open("ab") as fh:
                subprocess.Popen([exe], stdin=subprocess.DEVNULL, stdout=fh, stderr=fh, start_new_session=True)
            return True, f"controidd iniciado (log en {log_file})"
        except Exception as exc:
            return False, f"No se pudo iniciar controidd: {exc}"

    def _run_systemd_action(self, action: str) -> None:
        def worker() -> None:
            if has_systemd_user():
                args = ["systemctl", "--user", "enable", "--now", SERVICE_NAME] if action == "enable" else ["systemctl", "--user", action, SERVICE_NAME]
                rc, out, err = run_cmd(args, timeout=8.0)
                self._toast(f"systemd: {action} ejecutado" if rc == 0 else f"Error systemd ({action}): {err or out}")
            else:
                if action == "start":
                    _, msg = self._spawn_daemon_detached()
                    self._toast(msg)
                elif action == "stop":
                    rc, _, err = run_cmd(["pkill", "-TERM", "-x", "controidd"], timeout=4.0)
                    self._toast("controidd detenido" if rc in (0, 1) else f"Error: {err}")
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
                        desktop.write_text(f"[Desktop Entry]\nType=Application\nName=Controid Daemon\nExec={exe}\nTerminal=false\nX-GNOME-Autostart-enabled=true\n", encoding="utf-8")
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
            subprocess.Popen([exe], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
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
            row = Adw.ActionRow(title=html.escape(title), subtitle=html.escape(detail))
            badge = Gtk.Image.new_from_icon_name("emblem-ok-symbolic" if ok else "dialog-warning-symbolic")
            badge.set_valign(Gtk.Align.CENTER)
            row.add_prefix(badge)
            self.diag_list.append(row)
        if detected_ip and not self.entry_direct_host.get_text().strip():
            self.entry_direct_host.set_text(detected_ip)

    def _launch_android_app_adb(self) -> None:
        def worker() -> None:
            rc, out, err = run_cmd(["adb", "shell", "am", "start", "-n", "com.controid/.MainActivity"], timeout=5.0)
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
                rc, out, err = run_cmd(["journalctl", "--user", "-u", SERVICE_NAME, "-n", "60", "--no-pager", "-o", "short-iso"], timeout=4.0)
                text = out if rc == 0 else (err or "journalctl no disponible")
            else:
                log_file = daemon_log_path()
                text = ("\n".join(log_file.read_text(encoding="utf-8", errors="replace").splitlines()[-60:]) or f"{log_file} está vacío") if log_file.is_file() else f"Log en {log_file}"
            GLib.idle_add(lambda t=text: self._set_journal_text(t) or False)

        threading.Thread(target=worker, daemon=True).start()

    def _set_journal_text(self, journal_text: str) -> None:
        self._last_journal = journal_text
        self._update_logs_buffer()

    def _update_logs_buffer(self) -> None:
        ev_block = "\n".join(self.event_log_lines) if self.event_log_lines else "(Sin eventos IPC recientes)"
        j_block = getattr(self, "_last_journal", "")
        self.logs_view.get_buffer().set_text(f"=== Eventos IPC en vivo ===\n{ev_block}\n\n=== Servicio / Demonio (últimas 60 líneas) ===\n{j_block}")


class ControidApp(Adw.Application):
    def __init__(self, initial_tab: Optional[str] = None) -> None:
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.FLAGS_NONE)
        self.initial_tab = initial_tab

    def do_startup(self) -> None:
        Adw.Application.do_startup(self)
        Gtk.Window.set_default_icon_name("controid")

    def do_activate(self) -> None:
        win = self.get_active_window()
        if win is None:
            win = ControidWindow(self, initial_tab=self.initial_tab)
        elif self.initial_tab:
            win.view_stack.set_visible_child_name(self.initial_tab)
        win.present()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Controid Control Center GUI")
    parser.add_argument("--check", action="store_true", help="Verifica GTK4/Libadwaita y lectura de configuración sin abrir ventana")
    parser.add_argument(
        "--tab",
        choices=("panel", "clipboard", "settings", "diagnostics"),
        default=None,
        help="Pestaña inicial al abrir la ventana",
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
                    "palettes": len(SEKAI_PALETTES),
                }
            )
        )
        return 0

    app = ControidApp(initial_tab=args.tab)
    return app.run([sys.argv[0], *unknown])


if __name__ == "__main__":
    raise SystemExit(main())
