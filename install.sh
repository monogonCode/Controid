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
SERVICE_NAME="controid.service"
BINARIES=(controidd controid controid-tray)

CARGO_OFFLINE=0
RUN_PAIR=0
SETUP_UINPUT=0
NO_SYSTEMD=0

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
    printf 'KERNEL=="uinput", GROUP="input", MODE="0660"\n' |
        sudo tee /etc/udev/rules.d/99-controid-uinput.rules >/dev/null
    sudo usermod -aG input "$USER"
    sudo udevadm control --reload-rules
    sudo udevadm trigger /dev/uinput
    warn "Si pertenecías a un grupo nuevo ('input'), puede requerir reiniciar sesión."
}

while (($# > 0)); do
    case "$1" in
        --offline) CARGO_OFFLINE=1 ;;
        --pair) RUN_PAIR=1 ;;
        --setup-uinput) SETUP_UINPUT=1 ;;
        --no-systemd) NO_SYSTEMD=1 ;;
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

command -v cargo >/dev/null 2>&1 || die "Falta 'cargo' (toolchain Rust)."
CC_BIN="${CC:-gcc}"
command -v "$CC_BIN" >/dev/null 2>&1 || die "Falta compilador C ('$CC_BIN')."
command -v pkg-config >/dev/null 2>&1 || die "Falta 'pkg-config'."
pkg-config --exists libudev || die "Falta 'libudev' (Arch: systemd-libs | Debian/Ubuntu: libudev-dev | Fedora: systemd-devel)."
pkg-config --exists fuse3 || die "Falta 'fuse3' (Arch: fuse3 | Debian/Ubuntu: libfuse3-dev | Fedora: fuse3-devel)."
pkg-config --exists sdl2 libavcodec libswscale libavutil || die "Faltan librerías de desarrollo FFmpeg/SDL2 (sdl2, libavcodec, libswscale, libavutil)."

command -v fusermount3 >/dev/null 2>&1 || warn "Falta 'fusermount3' en PATH (necesario para montar/desmontar almacenamiento FUSE)."
command -v mpv >/dev/null 2>&1 || warn "Falta 'mpv' en PATH (solo necesario como visor fallback)."

if command -v systemctl >/dev/null 2>&1; then
    systemctl is-active --quiet bluetooth.service || warn "bluetooth.service no está activo (sudo systemctl enable --now bluetooth)."
    systemctl is-active --quiet NetworkManager.service || warn "NetworkManager.service no está activo (sudo systemctl enable --now NetworkManager)."
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
    if [ "$NO_SYSTEMD" -eq 0 ] && command -v systemctl >/dev/null 2>&1 &&
        systemctl --user is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
        systemctl --user stop "$SERVICE_NAME"
    fi
    log "Iniciando emparejamiento BLE (abre la app Android)..."
    "$BIN_DIR/controid" pair
fi

log "Instalando unidad systemd en $SYSTEMD_USER_DIR/$SERVICE_NAME..."
install -Dm644 "$ROOT_DIR/systemd/$SERVICE_NAME" "$SYSTEMD_USER_DIR/$SERVICE_NAME"

if [ "$NO_SYSTEMD" -eq 0 ]; then
    if ! command -v systemctl >/dev/null 2>&1 || ! systemctl --user show-environment >/dev/null 2>&1; then
        warn "Sesión systemd --user no disponible; habilita manualmente después:"
        warn "  systemctl --user daemon-reload && systemctl --user enable --now $SERVICE_NAME"
    else
        if [ -n "${WAYLAND_DISPLAY:-}" ] &&
            ! systemctl --user show-environment 2>/dev/null | grep -q '^WAYLAND_DISPLAY='; then
            warn "WAYLAND_DISPLAY no está exportado en systemd --user; en Hyprland añade:"
            warn "  exec-once = dbus-update-activation-environment --systemd WAYLAND_DISPLAY XDG_CURRENT_DESKTOP"
        fi

        systemctl --user daemon-reload
        systemctl --user enable "$SERVICE_NAME"

        if is_paired; then
            systemctl --user restart "$SERVICE_NAME"
            log "Servicio $SERVICE_NAME habilitado y ejecutándose."
        else
            log "Servicio $SERVICE_NAME habilitado (en espera de emparejamiento)."
        fi
    fi
fi

if ! is_paired; then
    cat <<EOF

Siguientes pasos:
  1. Guarda el perfil Wi-Fi del hotspot Android en NetworkManager (conectándote una vez).
  2. Con la app Android abierta, empareja por BLE:
       controid pair
   3. Inicia el demonio o abre la interfaz gráfica:
        systemctl --user start $SERVICE_NAME
        controid-gui
   4. (Opcional Hyprland) Bandeja del sistema:
        exec-once = controid-tray
EOF
fi
