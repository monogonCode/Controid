#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="$ROOT_DIR/daemon/Cargo.toml"
BIN_DIR="$HOME/.local/bin"
APPLICATIONS_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
ICONS_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor"
CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/controid"
OLD_CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/droidconnect"
CONFIG_FILE="$CONFIG_DIR/config.toml"
SYSTEMD_USER_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
AUTOSTART_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/autostart"
SERVICE_NAME="controid.service"
BINARIES=(controidd controid controid-tray)

CARGO_OFFLINE=0
RUN_PAIR=0
SETUP_UINPUT=0
NO_SYSTEMD=0
XDG_AUTOSTART=0

log()  { printf '[install] %s\n' "$*"; }
warn() { printf '[warn]    %s\n' "$*" >&2; }
die()  { printf '[error]   %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'EOF'
Uso: ./install.sh [opciones]

Opciones:
  --offline       Compila con Cargo en modo --offline
  --pair          Ejecuta 'controid pair' tras instalar los binarios
  --setup-uinput  Instala regla udev en /etc/udev/rules.d/ (usa sudo) si /dev/uinput no es escribible
  --no-systemd    Copia binarios y unidad sin ejecutar comandos 'systemctl --user'
  --xdg-autostart Instala ~/.config/autostart/controidd.desktop para entornos sin systemd
  -h, --help      Muestra esta ayuda
EOF
}

is_paired() {
    [ -f "$CONFIG_FILE" ] &&
        grep -Eq '^[[:space:]]*mac_address[[:space:]]*=[[:space:]]*"[^"]+"' "$CONFIG_FILE" &&
        grep -Eq '^[[:space:]]*hotspot_ssid[[:space:]]*=[[:space:]]*"[^"]+"' "$CONFIG_FILE"
}

setup_uinput_rule() {
    command -v sudo >/dev/null 2>&1 || die "Falta 'sudo' para configurar la regla udev de /dev/uinput."
    log "Instalando regla udev para /dev/uinput (requiere sudo)..."
    sudo mkdir -p /etc/udev/rules.d
    printf 'KERNEL=="uinput", GROUP="input", MODE="0660"\n' |
        sudo tee /etc/udev/rules.d/99-controid-uinput.rules >/dev/null
    if getent group input >/dev/null 2>&1; then
        sudo usermod -aG input "$USER" || true
    fi
    if command -v udevadm >/dev/null 2>&1; then
        sudo udevadm control --reload-rules || true
        sudo udevadm trigger /dev/uinput || true
    else
        warn "'udevadm' no está disponible; recarga reglas udev/mdev o reinicia para aplicar 99-controid-uinput.rules."
    fi
    warn "Si pertenecías a un grupo nuevo ('input'), puede requerir reiniciar sesión."
}

install_xdg_autostart() {
    local autostart_tpl="$ROOT_DIR/systemd/controidd-autostart.desktop"
    mkdir -p "$AUTOSTART_DIR"
    if [ -f "$autostart_tpl" ]; then
        sed "s|^Exec=controidd|Exec=$BIN_DIR/controidd|" "$autostart_tpl" > "$AUTOSTART_DIR/controidd.desktop"
    else
        cat > "$AUTOSTART_DIR/controidd.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Controid Daemon
Comment=Servicio en segundo plano de Controid (Android - Linux)
Exec=$BIN_DIR/controidd
Icon=controid
Terminal=false
NoDisplay=true
Categories=Utility;Network;
X-GNOME-Autostart-enabled=true
EOF
    fi
    chmod 644 "$AUTOSTART_DIR/controidd.desktop"
    log "Autostart XDG instalado en $AUTOSTART_DIR/controidd.desktop."
}

while (($# > 0)); do
    case "$1" in
        --offline) CARGO_OFFLINE=1 ;;
        --pair) RUN_PAIR=1 ;;
        --setup-uinput) SETUP_UINPUT=1 ;;
        --no-systemd) NO_SYSTEMD=1 ;;
        --xdg-autostart) XDG_AUTOSTART=1 ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            die "Opción desconocida: $1"
            ;;
    esac
    shift
done

[[ "$(uname -s)" == "Linux" ]] || die "Solo compatible con Linux."
[[ "${EUID}" -ne 0 ]] || die "No ejecutes ./install.sh como root; instala en tu sesión de usuario."

command -v cargo >/dev/null 2>&1 || die "Falta 'cargo' (toolchain Rust: rustup o paquete rust/cargo de tu distro)."
CC_BIN="${CC:-gcc}"
command -v "$CC_BIN" >/dev/null 2>&1 || die "Falta compilador C ('$CC_BIN' — Arch: base-devel | Debian/Ubuntu: build-essential | Fedora/openSUSE: gcc | Void: base-devel | Alpine: build-base)."
command -v pkg-config >/dev/null 2>&1 || die "Falta 'pkg-config' (pkgconf / pkg-config)."
pkg-config --exists libudev || die "Falta 'libudev' (Arch: systemd-libs | Debian/Ubuntu: libudev-dev | Fedora: systemd-devel | openSUSE: libudev-devel | Void/Alpine: eudev-dev / eudev-devel)."
pkg-config --exists fuse3 || die "Falta 'fuse3' (Arch: fuse3 | Debian/Ubuntu: libfuse3-dev | Fedora/openSUSE: fuse3-devel | Void: fuse3-devel | Alpine: fuse3-dev)."
pkg-config --exists sdl2 libavcodec libswscale libavutil || die "Faltan librerías de desarrollo FFmpeg/SDL2 (Arch: sdl2 ffmpeg | Debian/Ubuntu: libsdl2-dev libavcodec-dev libswscale-dev libavutil-dev | Fedora: SDL2-devel ffmpeg-free-devel | openSUSE: libSDL2-devel ffmpeg-*-devel | Void: SDL2-devel ffmpeg6-devel | Alpine: sdl2-dev ffmpeg-dev)."

command -v fusermount3 >/dev/null 2>&1 || warn "Falta 'fusermount3' en PATH (necesario para montar/desmontar almacenamiento FUSE)."
command -v mpv >/dev/null 2>&1 || warn "Falta 'mpv' en PATH (solo necesario como visor fallback)."

HAS_SYSTEMD_SYS=0
if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
    HAS_SYSTEMD_SYS=1
    systemctl is-active --quiet bluetooth.service || warn "bluetooth.service no está activo (sudo systemctl enable --now bluetooth)."
    if ! systemctl is-active --quiet NetworkManager.service && ! systemctl is-active --quiet iwd.service; then
        warn "Ni NetworkManager ni iwd están activos; el hotspot automático requiere uno de ellos (el modo LAN directa funciona igual)."
    fi
else
    if ! pgrep -x bluetoothd >/dev/null 2>&1; then
        warn "bluetoothd no parece estar en ejecución (inícialo con OpenRC/runit/s6 si usarás BLE/HID)."
    fi
    if ! pgrep -x NetworkManager >/dev/null 2>&1 && ! pgrep -x iwd >/dev/null 2>&1; then
        warn "Ni NetworkManager ni iwd están en ejecución; el hotspot automático requiere NM o iwd (el modo LAN directa funciona igual)."
    fi
fi

if [ -e /dev/uinput ] && [ ! -w /dev/uinput ]; then
    if [ "$SETUP_UINPUT" -eq 1 ]; then
        setup_uinput_rule
    else
        warn "/dev/uinput no es escribible; opcional para fallback TCP HID: reejecuta con --setup-uinput."
    fi
fi

log "Compilando binarios release..."
cargo_args=(build --release --manifest-path "$MANIFEST" --workspace)
if [ "$CARGO_OFFLINE" -eq 1 ]; then
    cargo_args+=(--offline)
fi
cargo "${cargo_args[@]}"

migrate_legacy_droidconnect() {
    if [ ! -f "$CONFIG_FILE" ] && [ -d "$OLD_CONFIG_DIR" ]; then
        if [ -f "$OLD_CONFIG_DIR/identity.pkcs8" ] || [ -f "$OLD_CONFIG_DIR/config.toml" ]; then
            mkdir -p "$CONFIG_DIR"
            chmod 700 "$CONFIG_DIR"
            if [ -f "$OLD_CONFIG_DIR/identity.pkcs8" ] && [ ! -f "$CONFIG_DIR/identity.pkcs8" ]; then
                install -m600 "$OLD_CONFIG_DIR/identity.pkcs8" "$CONFIG_DIR/identity.pkcs8"
                log "Identidad migrada a $CONFIG_DIR/identity.pkcs8."
            fi
            if [ -f "$OLD_CONFIG_DIR/config.toml" ]; then
                tmp_cfg="$CONFIG_DIR/.config.toml.migrate.$$"
                install -m600 /dev/null "$tmp_cfg"
                sed 's|~/DroidConnect|~/Controid|g' "$OLD_CONFIG_DIR/config.toml" > "$tmp_cfg"
                mv -f "$tmp_cfg" "$CONFIG_FILE"
                log "Configuración migrada a $CONFIG_FILE."
            fi
        fi
    fi

    if [ "$NO_SYSTEMD" -eq 0 ] && command -v systemctl >/dev/null 2>&1 &&
        systemctl --user show-environment >/dev/null 2>&1; then
        if systemctl --user is-active --quiet droidconnect.service 2>/dev/null; then
            systemctl --user stop droidconnect.service || true
        fi
        if systemctl --user is-enabled --quiet droidconnect.service 2>/dev/null; then
            systemctl --user disable droidconnect.service || true
        fi
    fi

    rm -f "$SYSTEMD_USER_DIR/droidconnect.service"
    rm -f \
        "$BIN_DIR/droidconnectd" \
        "$BIN_DIR/droidconnect" \
        "$BIN_DIR/droidconnect-tray" \
        "$BIN_DIR/droidconnect-viewer" \
        "$BIN_DIR/droidconnect-mount" \
        "$BIN_DIR/droidconnect-gui"
    rm -f "$APPLICATIONS_DIR/droidconnect.desktop"
}

log "Compilando herramientas nativas C (controid-viewer, controid-mount)..."
TOOLS_BUILD_DIR="$ROOT_DIR/daemon/target/tools"
mkdir -p "$TOOLS_BUILD_DIR"
# shellcheck disable=SC2046
"$CC_BIN" -O2 -Wall -Wextra "$ROOT_DIR/tools/controid-viewer.c" \
    -o "$TOOLS_BUILD_DIR/controid-viewer" \
    $(pkg-config --cflags --libs sdl2 libavcodec libswscale libavutil) -lpthread -lm
# shellcheck disable=SC2046
"$CC_BIN" -O2 -Wall -Wextra -D_FILE_OFFSET_BITS=64 "$ROOT_DIR/tools/controid-mount.c" \
    -o "$TOOLS_BUILD_DIR/controid-mount" \
    $(pkg-config --cflags --libs fuse3) -lpthread

migrate_legacy_droidconnect

log "Instalando binarios en $BIN_DIR..."
mkdir -p "$BIN_DIR"
for bin in "${BINARIES[@]}"; do
    src="$ROOT_DIR/daemon/target/release/$bin"
    [ -x "$src" ] || die "Binario compilado no encontrado: $src"
    tmp="$BIN_DIR/.$bin.new.$$"
    install -m755 "$src" "$tmp"
    mv -f "$tmp" "$BIN_DIR/$bin"
done
for tool_bin in controid-viewer controid-mount; do
    src="$TOOLS_BUILD_DIR/$tool_bin"
    [ -x "$src" ] || die "Binario C compilado no encontrado: $src"
    tmp="$BIN_DIR/.$tool_bin.new.$$"
    install -m755 "$src" "$tmp"
    mv -f "$tmp" "$BIN_DIR/$tool_bin"
done

if [ -f "$ROOT_DIR/gui/controid_gui.py" ]; then
    tmp="$BIN_DIR/.controid-gui.new.$$"
    install -m755 "$ROOT_DIR/gui/controid_gui.py" "$tmp"
    mv -f "$tmp" "$BIN_DIR/controid-gui"
fi

for size in 32x32 48x48 64x64 128x128 256x256 512x512; do
    icon_src="$ROOT_DIR/assets/icons/$size/controid.png"
    if [ -f "$icon_src" ]; then
        install -Dm644 "$icon_src" "$ICONS_DIR/$size/apps/controid.png"
    fi
done
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -f -t "$ICONS_DIR" >/dev/null 2>&1 || true
fi

if [ -f "$ROOT_DIR/systemd/controid.desktop" ]; then
    mkdir -p "$APPLICATIONS_DIR"
    sed "s|^Exec=controid-gui|Exec=$BIN_DIR/controid-gui|" \
        "$ROOT_DIR/systemd/controid.desktop" > "$APPLICATIONS_DIR/controid.desktop"
    chmod 644 "$APPLICATIONS_DIR/controid.desktop"
    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$APPLICATIONS_DIR" >/dev/null 2>&1 || true
    fi
fi

if [[ ":$PATH:" != *":$BIN_DIR:"* ]]; then
    warn "$BIN_DIR no está en PATH; añádelo en tu shell para usar 'controid'."
fi

if [ "$RUN_PAIR" -eq 1 ]; then
    if [ "$NO_SYSTEMD" -eq 0 ] && [ "$HAS_SYSTEMD_SYS" -eq 1 ] &&
        systemctl --user show-environment >/dev/null 2>&1 &&
        systemctl --user is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
        systemctl --user stop "$SERVICE_NAME"
    fi
    log "Iniciando emparejamiento BLE (abre la app Android)..."
    "$BIN_DIR/controid" pair
fi

HAS_SYSTEMD_USER=0
if [ "$HAS_SYSTEMD_SYS" -eq 1 ] && systemctl --user show-environment >/dev/null 2>&1; then
    HAS_SYSTEMD_USER=1
fi

if [ "$HAS_SYSTEMD_SYS" -eq 1 ] || [ -d "$SYSTEMD_USER_DIR" ]; then
    log "Instalando unidad systemd en $SYSTEMD_USER_DIR/$SERVICE_NAME..."
    install -Dm644 "$ROOT_DIR/systemd/$SERVICE_NAME" "$SYSTEMD_USER_DIR/$SERVICE_NAME"
fi

if [ "$NO_SYSTEMD" -eq 0 ] && [ "$HAS_SYSTEMD_USER" -eq 1 ]; then
    if [ -n "${WAYLAND_DISPLAY:-}" ] &&
        ! systemctl --user show-environment 2>/dev/null | grep -q '^WAYLAND_DISPLAY='; then
        warn "WAYLAND_DISPLAY no está exportado en systemd --user; en Hyprland/Sway añade:"
        warn "  exec-once = dbus-update-activation-environment --systemd WAYLAND_DISPLAY DISPLAY XDG_CURRENT_DESKTOP"
    fi

    systemctl --user daemon-reload
    systemctl --user enable "$SERVICE_NAME"

    if is_paired; then
        systemctl --user restart "$SERVICE_NAME"
        log "Servicio $SERVICE_NAME habilitado y ejecutándose."
    else
        log "Servicio $SERVICE_NAME habilitado (en espera de emparejamiento)."
    fi
elif [ "$NO_SYSTEMD" -eq 0 ]; then
    warn "Sesión systemd --user no disponible (normal en Alpine/Void/Artix OpenRC/Devuan o sesiones sin systemd)."
    if [ "$XDG_AUTOSTART" -eq 0 ] && [ -t 0 ]; then
        printf '[install] ¿Instalar entrada XDG autostart (%s/controidd.desktop)? [s/N] ' "$AUTOSTART_DIR"
        read -r reply || reply=""
        case "$reply" in
            s|S|si|SI|y|Y|yes|YES) XDG_AUTOSTART=1 ;;
        esac
    elif [ "$XDG_AUTOSTART" -eq 0 ]; then
        log "Sugerencia sin systemd: reejecuta con '--xdg-autostart' o 'make install-autostart' para arranque automático XDG."
    fi
fi

if [ "$XDG_AUTOSTART" -eq 1 ]; then
    install_xdg_autostart
fi

if ! is_paired; then
    if [ "$HAS_SYSTEMD_USER" -eq 1 ]; then
        start_hint="systemctl --user start $SERVICE_NAME"
    else
        start_hint="$BIN_DIR/controidd &"
    fi
    cat <<EOF

Siguientes pasos:
  1. Guarda el perfil Wi-Fi del hotspot Android en NetworkManager o iwd (conectándote una vez), o usa modo LAN directa en la GUI.
  2. Con la app Android abierta, empareja por BLE:
       controid pair
  3. Inicia el demonio o abre la interfaz gráfica:
       $start_hint
       controid-gui
  4. (Opcional) Bandeja del sistema (Waybar, KDE, XFCE, etc.):
       controid-tray
EOF
fi
