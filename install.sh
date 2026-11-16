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
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/controid"
SERVICE_NAME="controid.service"
BINARIES=(controidd controid controid-tray)

OFFICIAL_APK_URL="https://controid.monogoncode.dev/downloads/controid.apk"
OFFICIAL_APK_INFO_URL="https://controid.monogoncode.dev/downloads/apk-info.json"
MIN_RUST_MAJOR=1
MIN_RUST_MINOR=85

TARGET_PC=0
TARGET_ANDROID=0
CARGO_OFFLINE=0
RUN_PAIR=0
SETUP_UINPUT=0
NO_SYSTEMD=0
XDG_AUTOSTART=0
NO_DEPS=0
AUTO_YES=0
BUILD_APK=0
CUSTOM_APK=""

log()  { printf '[install] %s\n' "$*"; }
warn() { printf '[warn]    %s\n' "$*" >&2; }
die()  { printf '[error]   %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'EOF'
Uso: ./install.sh [objetivo] [opciones]

Objetivos (si no se indica ninguno, se usa --pc por defecto):
  --pc              Instala solo el programa de PC Linux (sin dependencias Android/SDK)
  --android         Instala la app Android usando el APK precompilado (o --build-apk)
  --all             Instala el programa de PC y la app Android (--pc + --android)

Opciones generales:
  -y, --yes         Acepta automáticamente confirmaciones (p. ej. dependencias, uinput, PATH)
      --no-deps     No instala dependencias del sistema automáticamente
  -h, --help        Muestra esta ayuda

Opciones para PC (--pc / --all):
      --offline       Compila con Cargo en modo --offline (requiere dependencias ya instaladas)
      --pair          Ejecuta 'controid pair' tras instalar los binarios
      --setup-uinput  Instala regla udev en /etc/udev/rules.d/ (usa sudo/doas) para /dev/uinput
      --no-systemd    Copia binarios y unidad sin ejecutar comandos 'systemctl --user'
      --xdg-autostart Instala ~/.config/autostart/controidd.desktop para entornos sin systemd

Opciones para Android (--android / --all):
      --build-apk     Compila el APK desde android/ en lugar de descargar el APK precompilado
      --apk <ruta|url> Usa un APK local o URL concreta en lugar del APK oficial
EOF
}

while (($# > 0)); do
    case "$1" in
        --pc) TARGET_PC=1 ;;
        --android) TARGET_ANDROID=1 ;;
        --all)
            TARGET_PC=1
            TARGET_ANDROID=1
            ;;
        --offline) CARGO_OFFLINE=1 ;;
        --pair) RUN_PAIR=1 ;;
        --setup-uinput) SETUP_UINPUT=1 ;;
        --no-systemd) NO_SYSTEMD=1 ;;
        --xdg-autostart) XDG_AUTOSTART=1 ;;
        --no-deps) NO_DEPS=1 ;;
        -y|--yes) AUTO_YES=1 ;;
        --build-apk) BUILD_APK=1 ;;
        --apk)
            (($# >= 2)) || die "Falta la ruta o URL para --apk."
            CUSTOM_APK="$2"
            shift
            ;;
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

if [ "$TARGET_PC" -eq 0 ] && [ "$TARGET_ANDROID" -eq 0 ]; then
    TARGET_PC=1
fi

[[ "$(uname -s)" == "Linux" ]] || die "Este instalador solo es compatible con Linux."
[[ "${EUID}" -ne 0 ]] || die "No ejecutes ./install.sh como root; usa tu usuario normal (pedirá sudo/doas solo para paquetes o udev)."

ORIG_PATH="$PATH"
export PATH="$BIN_DIR:$HOME/.cargo/bin:$PATH"

run_as_root() {
    if [ "${EUID}" -eq 0 ]; then
        "$@"
    elif command -v sudo >/dev/null 2>&1; then
        sudo "$@"
    elif command -v doas >/dev/null 2>&1; then
        doas "$@"
    else
        die "Se necesita 'sudo' o 'doas' para instalar paquetes del sistema o configurar udev."
    fi
}

ask_yes_no() {
    local prompt="$1"
    local default_yes="${2:-0}"
    if [ "$AUTO_YES" -eq 1 ]; then
        return 0
    fi
    if [ ! -t 0 ]; then
        return 1
    fi
    local reply=""
    printf '[install] %s ' "$prompt"
    read -r reply || reply=""
    if [ -z "$reply" ]; then
        [ "$default_yes" -eq 1 ] && return 0 || return 1
    fi
    case "$reply" in
        s|S|si|SI|y|Y|yes|YES) return 0 ;;
        *) return 1 ;;
    esac
}

DISTRO_ID=""
DISTRO_LIKE=""
PKG_MGR=""

detect_distro() {
    if [ -r /etc/os-release ]; then
        # shellcheck disable=SC1091
        . /etc/os-release
        DISTRO_ID="${ID:-}"
        DISTRO_LIKE="${ID_LIKE:-}"
    fi

    if command -v pacman >/dev/null 2>&1; then
        PKG_MGR="pacman"
    elif command -v apt-get >/dev/null 2>&1; then
        PKG_MGR="apt"
    elif command -v dnf >/dev/null 2>&1; then
        PKG_MGR="dnf"
    elif command -v zypper >/dev/null 2>&1; then
        PKG_MGR="zypper"
    elif command -v xbps-install >/dev/null 2>&1; then
        PKG_MGR="xbps"
    elif command -v apk >/dev/null 2>&1; then
        PKG_MGR="apk"
    elif command -v eopkg >/dev/null 2>&1; then
        PKG_MGR="eopkg"
    else
        PKG_MGR="unknown"
    fi
}

rustc_version_ok() {
    command -v cargo >/dev/null 2>&1 || return 1
    command -v rustc >/dev/null 2>&1 || return 1
    local ver major minor
    ver="$(rustc --version 2>/dev/null | awk '{print $2}')"
    major="${ver%%.*}"
    minor="${ver#*.}"
    minor="${minor%%.*}"
    [[ "$major" =~ ^[0-9]+$ ]] && [[ "$minor" =~ ^[0-9]+$ ]] || return 1
    if (( major > MIN_RUST_MAJOR )) || (( major == MIN_RUST_MAJOR && minor >= MIN_RUST_MINOR )); then
        return 0
    fi
    return 1
}

python_gui_ok() {
    command -v python3 >/dev/null 2>&1 || return 1
    env -u DISPLAY -u WAYLAND_DISPLAY python3 - <<'PY' >/dev/null 2>&1
import sys
if sys.version_info < (3, 11):
    raise SystemExit(1)
import cairo
import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Gtk, Adw  # noqa: F401
PY
}

pc_core_deps_ok() {
    local cc_bin="${CC:-gcc}"
    command -v "$cc_bin" >/dev/null 2>&1 || return 1
    command -v pkg-config >/dev/null 2>&1 || return 1
    pkg-config --exists libudev fuse3 sdl2 libavcodec libswscale libavutil 2>/dev/null || return 1
    command -v fusermount3 >/dev/null 2>&1 || return 1
    python_gui_ok || return 1
    return 0
}

install_pkg_list_pacman() {
    local pkgs=("$@")
    ((${#pkgs[@]} > 0)) || return 0
    log "Instalando paquetes con pacman (${DISTRO_ID:-Arch})..."
    run_as_root pacman -S --needed --noconfirm "${pkgs[@]}"
}

install_pkg_list_apt() {
    local pkgs=("$@")
    ((${#pkgs[@]} > 0)) || return 0
    log "Actualizando índice APT e instalando paquetes (${DISTRO_ID:-Debian/Ubuntu})..."
    run_as_root apt-get update
    run_as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${pkgs[@]}"
}

install_pkg_list_dnf() {
    local pkgs=("$@")
    ((${#pkgs[@]} > 0)) || return 0
    log "Instalando paquetes con dnf (${DISTRO_ID:-Fedora})..."
    run_as_root dnf install -y "${pkgs[@]}"
}

install_pkg_list_zypper() {
    local pkgs=("$@")
    ((${#pkgs[@]} > 0)) || return 0
    log "Instalando paquetes con zypper (${DISTRO_ID:-openSUSE})..."
    run_as_root zypper --non-interactive install --no-recommends "${pkgs[@]}" ||
        run_as_root zypper --non-interactive install "${pkgs[@]}"
}

install_pkg_list_xbps() {
    local pkgs=("$@")
    ((${#pkgs[@]} > 0)) || return 0
    log "Instalando paquetes con xbps-install (Void Linux)..."
    run_as_root xbps-install -Sy "${pkgs[@]}"
}

install_pkg_list_apk() {
    local pkgs=("$@")
    ((${#pkgs[@]} > 0)) || return 0
    log "Instalando paquetes con apk (Alpine Linux)..."
    run_as_root apk add "${pkgs[@]}"
}

install_pkg_list_eopkg() {
    local pkgs=("$@")
    ((${#pkgs[@]} > 0)) || return 0
    log "Instalando paquetes con eopkg (Solus)..."
    run_as_root eopkg install -y "${pkgs[@]}"
}

install_pc_system_deps() {
    if [ -e /run/ostree-booted ] && [ "$PKG_MGR" = "unknown" ]; then
        warn "Sistema inmutable OSTree/Silverblue/Bazzite detectado; usa 'rpm-ostree' o un contenedor 'toolbox'/'distrobox' para compilar."
        return 0
    fi

    case "$PKG_MGR" in
        pacman)
            local pkgs=(
                base-devel pkgconf curl ca-certificates
                fuse3 ffmpeg
                python python-gobject python-cairo gtk4 libadwaita
                bluez bluez-utils networkmanager
                wl-clipboard xclip playerctl librsvg
            )
            if ! pkg-config --exists libudev 2>/dev/null; then
                if pacman -Qq eudev >/dev/null 2>&1 || pacman -Qq libeudev >/dev/null 2>&1; then
                    pacman -Si libeudev >/dev/null 2>&1 && pkgs+=(libeudev) || pkgs+=(eudev)
                elif pacman -Si systemd-libs >/dev/null 2>&1; then
                    pkgs+=(systemd-libs)
                elif pacman -Si libeudev >/dev/null 2>&1; then
                    pkgs+=(libeudev)
                fi
            fi
            if ! pkg-config --exists sdl2 2>/dev/null; then
                if pacman -Si sdl2-compat >/dev/null 2>&1; then
                    pkgs+=(sdl2-compat)
                else
                    pkgs+=(sdl2)
                fi
            fi
            if pacman -Si cava >/dev/null 2>&1; then
                pkgs+=(cava)
            fi
            install_pkg_list_pacman "${pkgs[@]}"
            ;;

        apt)
            run_as_root apt-get update
            local pkgs=(
                build-essential pkg-config curl ca-certificates
                libfuse3-dev fuse3 libsdl2-dev
                libavcodec-dev libswscale-dev libavutil-dev
                python3 python3-gi python3-gi-cairo gir1.2-gtk-4.0 gir1.2-adw-1
                bluez network-manager wl-clipboard xclip playerctl librsvg2-bin
            )
            if apt-cache show libudev-dev >/dev/null 2>&1; then
                pkgs+=(libudev-dev)
            elif apt-cache show libeudev-dev >/dev/null 2>&1; then
                pkgs+=(libeudev-dev)
            fi
            if apt-cache show cava >/dev/null 2>&1; then
                pkgs+=(cava)
            fi
            run_as_root env DEBIAN_FRONTEND=noninteractive apt-get install -y "${pkgs[@]}"
            ;;

        dnf)
            local pkgs=(
                gcc pkgconf-pkg-config curl ca-certificates
                systemd-devel fuse3 fuse3-devel
                python3 python3-gobject python3-cairo gtk4 libadwaita
                bluez NetworkManager wl-clipboard xclip playerctl librsvg2-tools
            )
            if ! pkg-config --exists sdl2 2>/dev/null; then
                if dnf -q list --available sdl2-compat-devel >/dev/null 2>&1; then
                    pkgs+=(sdl2-compat-devel)
                else
                    pkgs+=(SDL2-devel)
                fi
            fi
            if ! pkg-config --exists libavcodec libswscale libavutil 2>/dev/null; then
                if rpm -q ffmpeg-libs >/dev/null 2>&1 || rpm -q ffmpeg >/dev/null 2>&1; then
                    pkgs+=(ffmpeg-devel)
                elif dnf -q list --available ffmpeg-free-devel >/dev/null 2>&1; then
                    pkgs+=(ffmpeg-free-devel)
                else
                    pkgs+=(ffmpeg-devel)
                fi
            fi
            install_pkg_list_dnf "${pkgs[@]}"
            if dnf -q list --available cava >/dev/null 2>&1; then
                run_as_root dnf install -y cava || true
            fi
            ;;

        zypper)
            local pkgs=(
                gcc pkg-config curl ca-certificates
                libudev-devel fuse3 fuse3-devel
                python3 python3-gobject python3-cairo
                typelib-1_0-Gtk-4_0 typelib-1_0-Adw-1
                bluez NetworkManager wl-clipboard xclip playerctl
            )
            if ! pkg-config --exists sdl2 2>/dev/null; then
                pkgs+=(libSDL2-devel)
            fi
            if ! pkg-config --exists libavcodec libswscale libavutil 2>/dev/null; then
                local ff_ver=""
                for v in 8 7 6 5 4; do
                    if zypper --non-interactive info "ffmpeg-${v}-libavcodec-devel" >/dev/null 2>&1; then
                        ff_ver="$v"
                        break
                    fi
                done
                if [ -n "$ff_ver" ]; then
                    pkgs+=(
                        "ffmpeg-${ff_ver}-libavcodec-devel"
                        "ffmpeg-${ff_ver}-libswscale-devel"
                        "ffmpeg-${ff_ver}-libavutil-devel"
                    )
                else
                    pkgs+=(libavcodec-devel libswscale-devel libavutil-devel)
                fi
            fi
            install_pkg_list_zypper "${pkgs[@]}"
            run_as_root zypper --non-interactive install rsvg-convert >/dev/null 2>&1 || true
            ;;

        xbps)
            local pkgs=(
                base-devel pkg-config curl ca-certificates
                eudev-libudev-devel fuse3 fuse3-devel SDL2-devel
                python3 python3-gobject python3-cairo gtk4 libadwaita
                bluez NetworkManager wl-clipboard xclip playerctl librsvg-utils
            )
            if ! pkg-config --exists libavcodec libswscale libavutil 2>/dev/null; then
                if xbps-query -R ffmpeg6-devel >/dev/null 2>&1; then
                    pkgs+=(ffmpeg6-devel)
                else
                    pkgs+=(ffmpeg-devel)
                fi
            fi
            install_pkg_list_xbps "${pkgs[@]}"
            ;;

        apk)
            local pkgs=(
                build-base pkgconf curl ca-certificates
                eudev-dev fuse3 fuse3-dev sdl2-dev ffmpeg-dev
                python3 py3-gobject3 py3-cairo gtk4.0 libadwaita
                bluez networkmanager wl-clipboard xclip playerctl
            )
            if apk search -x rsvg-convert 2>/dev/null | grep -q '^rsvg-convert'; then
                pkgs+=(rsvg-convert)
            elif apk search -x librsvg 2>/dev/null | grep -q '^librsvg'; then
                pkgs+=(librsvg)
            fi
            install_pkg_list_apk "${pkgs[@]}"
            ;;

        eopkg)
            install_pkg_list_eopkg \
                gcc pkg-config curl ca-certificates \
                systemd-devel fuse3-devel sdl2-devel ffmpeg-devel \
                python3-gobject libgtk-4 libadwaita \
                bluez network-manager wl-clipboard xclip playerctl librsvg
            ;;

        *)
            warn "Distro/gestor de paquetes no reconocido automáticamente (${DISTRO_ID:-desconocido}). Se verificarán las dependencias manualmente."
            ;;
    esac
}

ensure_curl() {
    command -v curl >/dev/null 2>&1 && return 0
    command -v wget >/dev/null 2>&1 && return 0
    if [ "$NO_DEPS" -eq 1 ] || [ "$CARGO_OFFLINE" -eq 1 ]; then
        die "Se requiere 'curl' o 'wget'."
    fi
    case "$PKG_MGR" in
        pacman) install_pkg_list_pacman curl ca-certificates ;;
        apt) install_pkg_list_apt curl ca-certificates ;;
        dnf) install_pkg_list_dnf curl ca-certificates ;;
        zypper) install_pkg_list_zypper curl ca-certificates ;;
        xbps) install_pkg_list_xbps curl ca-certificates ;;
        apk) install_pkg_list_apk curl ca-certificates ;;
        eopkg) install_pkg_list_eopkg curl ca-certificates ;;
        *) die "Falta 'curl' o 'wget' para continuar." ;;
    esac
}

ensure_rust_toolchain() {
    if command -v rustup >/dev/null 2>&1; then
        if ! rustup show active-toolchain >/dev/null 2>&1; then
            log "Configurando toolchain Rust estable por defecto en rustup..."
            rustup default stable
        fi
    fi

    if rustc_version_ok; then
        return 0
    fi

    if [ "$NO_DEPS" -eq 1 ] || [ "$CARGO_OFFLINE" -eq 1 ]; then
        die "Se requiere Rust >= ${MIN_RUST_MAJOR}.${MIN_RUST_MINOR} (edition 2024). Instala rustup o actualiza cargo/rustc."
    fi

    if command -v rustup >/dev/null 2>&1; then
        log "Actualizando Rust estable mediante rustup (requiere >= ${MIN_RUST_MAJOR}.${MIN_RUST_MINOR})..."
        rustup toolchain install stable
        rustup default stable
    elif [ "$PKG_MGR" = "pacman" ] && pacman -Si rustup >/dev/null 2>&1; then
        log "Instalando rustup desde pacman..."
        install_pkg_list_pacman rustup
        rustup default stable
    elif [ "$PKG_MGR" = "apk" ]; then
        log "Intentando instalar cargo/rust desde apk..."
        install_pkg_list_apk cargo rust || true
        if ! rustc_version_ok; then
            ensure_curl
            log "Instalando Rust estable mediante rustup..."
            curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable --no-modify-path
        fi
    else
        ensure_curl
        log "Instalando Rust >= ${MIN_RUST_MAJOR}.${MIN_RUST_MINOR} mediante rustup (los paquetes de algunas distros traen versiones antiguas)..."
        if command -v curl >/dev/null 2>&1; then
            curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable --no-modify-path
        else
            wget -qO- https://sh.rustup.rs | sh -s -- -y --default-toolchain stable --no-modify-path
        fi
    fi

    export PATH="$HOME/.cargo/bin:$PATH"
    rustc_version_ok || die "No se pudo obtener Rust >= ${MIN_RUST_MAJOR}.${MIN_RUST_MINOR} (rustc actual: $(rustc --version 2>/dev/null || echo 'no encontrado'))."
}

is_paired() {
    [ -f "$CONFIG_FILE" ] &&
        grep -Eq '^[[:space:]]*mac_address[[:space:]]*=[[:space:]]*"[^"]+"' "$CONFIG_FILE" &&
        grep -Eq '^[[:space:]]*hotspot_ssid[[:space:]]*=[[:space:]]*"[^"]+"' "$CONFIG_FILE"
}

setup_uinput_rule() {
    log "Instalando regla udev para /dev/uinput (requiere privilegios)..."
    run_as_root mkdir -p /etc/udev/rules.d
    printf 'KERNEL=="uinput", GROUP="input", MODE="0660"\n' |
        run_as_root tee /etc/udev/rules.d/99-controid-uinput.rules >/dev/null
    if getent group input >/dev/null 2>&1; then
        if command -v usermod >/dev/null 2>&1; then
            run_as_root usermod -aG input "$USER" || true
        elif command -v addgroup >/dev/null 2>&1; then
            run_as_root addgroup "$USER" input || true
        fi
    fi
    if [ ! -e /dev/uinput ] && command -v modprobe >/dev/null 2>&1; then
        run_as_root modprobe uinput >/dev/null 2>&1 || true
    fi
    if command -v udevadm >/dev/null 2>&1; then
        run_as_root udevadm control --reload-rules || true
        run_as_root udevadm trigger /dev/uinput || true
    else
        warn "'udevadm' no está disponible; recarga reglas udev/mdev o reinicia para aplicar 99-controid-uinput.rules."
    fi
    warn "Si te acabas de añadir al grupo 'input', puede requerir cerrar e iniciar sesión una vez."
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
                local tmp_cfg="$CONFIG_DIR/.config.toml.migrate.$$"
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

ensure_local_bin_in_path() {
    case ":$ORIG_PATH:" in
        *":$BIN_DIR:"*) return 0 ;;
    esac

    local shell_name rc_file="" path_line='export PATH="$HOME/.local/bin:$PATH"'
    shell_name="$(basename -- "${SHELL:-bash}")"
    case "$shell_name" in
        zsh) rc_file="$HOME/.zshrc" ;;
        bash) rc_file="$HOME/.bashrc" ;;
        fish)
            rc_file="${XDG_CONFIG_HOME:-$HOME/.config}/fish/config.fish"
            path_line='fish_add_path "$HOME/.local/bin"'
            ;;
        *) rc_file="$HOME/.profile" ;;
    esac

    if [ -n "$rc_file" ] && [ -f "$rc_file" ] && grep -qE '(\.local/bin|\$HOME/\.local/bin)' "$rc_file" 2>/dev/null; then
        warn "$BIN_DIR ya está referenciado en $rc_file, pero no en tu PATH actual."
        return 0
    fi

    if ask_yes_no "¿Añadir $BIN_DIR al PATH en $rc_file? [S/n]" 1; then
        mkdir -p "$(dirname -- "$rc_file")"
        printf '\n# Controid CLI/GUI\n%s\n' "$path_line" >> "$rc_file"
        log "Añadido $BIN_DIR a $rc_file. Ejecuta 'source $rc_file' o abre una nueva terminal."
    else
        warn "$BIN_DIR no está en PATH; añádelo a $rc_file: $path_line"
    fi
}

install_pc() {
    log "=== Instalando Controid para PC (Linux) ==="

    if [ "$NO_DEPS" -eq 0 ] && [ "$CARGO_OFFLINE" -eq 0 ]; then
        if ! pc_core_deps_ok; then
            log "Faltan dependencias de compilación/escritorio; instalando automáticamente..."
            install_pc_system_deps
        else
            log "Dependencias C y GTK4/Libadwaita del sistema verificadas."
        fi
    fi

    ensure_rust_toolchain

    local cc_bin="${CC:-gcc}"
    command -v "$cc_bin" >/dev/null 2>&1 || die "Falta compilador C ('$cc_bin')."
    command -v pkg-config >/dev/null 2>&1 || die "Falta 'pkg-config'."
    pkg-config --exists libudev || die "Falta 'libudev' (Arch: systemd-libs | Debian/Ubuntu: libudev-dev | Fedora: systemd-devel | openSUSE: libudev-devel | Void/Alpine: eudev-dev)."
    pkg-config --exists fuse3 || die "Falta 'fuse3' (Arch: fuse3 | Debian/Ubuntu: libfuse3-dev | Fedora/openSUSE: fuse3-devel | Void: fuse3-devel | Alpine: fuse3-dev)."
    pkg-config --exists sdl2 libavcodec libswscale libavutil || die "Faltan librerías FFmpeg/SDL2 dev (Arch: sdl2 ffmpeg | Debian/Ubuntu: libsdl2-dev libavcodec-dev libswscale-dev libavutil-dev | Fedora: SDL2-devel ffmpeg-free-devel)."

    command -v fusermount3 >/dev/null 2>&1 || warn "Falta 'fusermount3' en PATH (necesario para montar almacenamiento FUSE en ~/Controid)."
    if ! python_gui_ok; then
        warn "Falta Python >= 3.11 o PyGObject GTK4/Libadwaita; 'controid-gui' no arrancará hasta instalarlos (el demonio y CLI sí funcionarán)."
    fi

    local has_systemd_sys=0
    if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1; then
        has_systemd_sys=1
        systemctl is-active --quiet bluetooth.service || warn "bluetooth.service no está activo (sudo systemctl enable --now bluetooth)."
        if ! systemctl is-active --quiet NetworkManager.service && ! systemctl is-active --quiet iwd.service; then
            warn "Ni NetworkManager ni iwd están activos; el hotspot automático requiere uno de ellos (el modo LAN directa funciona igual)."
        fi
    else
        if ! pgrep -x bluetoothd >/dev/null 2>&1; then
            warn "bluetoothd no parece estar en ejecución (inícialo si usarás BLE/HID)."
        fi
        if ! pgrep -x NetworkManager >/dev/null 2>&1 && ! pgrep -x iwd >/dev/null 2>&1; then
            warn "Ni NetworkManager ni iwd están en ejecución; el hotspot automático requiere NM o iwd (el modo LAN directa funciona igual)."
        fi
    fi

    if [ -e /dev/uinput ] && [ ! -w /dev/uinput ]; then
        if [ "$SETUP_UINPUT" -eq 1 ]; then
            setup_uinput_rule
        elif ask_yes_no "/dev/uinput no es escribible (opcional para trackpad de respaldo por red). ¿Configurar regla udev ahora? [s/N]" 0; then
            setup_uinput_rule
        else
            warn "/dev/uinput no es escribible (opcional para fallback TCP HID; el trackpad Bluetooth HID funciona igual). Reejecuta con --setup-uinput si lo necesitas."
        fi
    fi

    log "Compilando binarios Rust (release)..."
    local cargo_args=(build --release --manifest-path "$MANIFEST" --workspace)
    if [ "$CARGO_OFFLINE" -eq 1 ]; then
        cargo_args+=(--offline)
    fi
    cargo "${cargo_args[@]}"

    log "Compilando herramientas nativas C (controid-viewer, controid-mount)..."
    local tools_build_dir="$ROOT_DIR/daemon/target/tools"
    mkdir -p "$tools_build_dir"
    # shellcheck disable=SC2046
    "$cc_bin" -O2 -Wall -Wextra "$ROOT_DIR/tools/controid-viewer.c" \
        -o "$tools_build_dir/controid-viewer" \
        $(pkg-config --cflags --libs sdl2 libavcodec libswscale libavutil) -lpthread -lm
    # shellcheck disable=SC2046
    "$cc_bin" -O2 -Wall -Wextra -D_FILE_OFFSET_BITS=64 "$ROOT_DIR/tools/controid-mount.c" \
        -o "$tools_build_dir/controid-mount" \
        $(pkg-config --cflags --libs fuse3) -lpthread

    migrate_legacy_droidconnect

    log "Instalando ejecutables en $BIN_DIR..."
    mkdir -p "$BIN_DIR"
    local bin src tmp
    for bin in "${BINARIES[@]}"; do
        src="$ROOT_DIR/daemon/target/release/$bin"
        [ -x "$src" ] || die "Binario compilado no encontrado: $src"
        tmp="$BIN_DIR/.$bin.new.$$"
        install -m755 "$src" "$tmp"
        mv -f "$tmp" "$BIN_DIR/$bin"
    done
    for bin in controid-viewer controid-mount; do
        src="$tools_build_dir/$bin"
        [ -x "$src" ] || die "Binario C compilado no encontrado: $src"
        tmp="$BIN_DIR/.$bin.new.$$"
        install -m755 "$src" "$tmp"
        mv -f "$tmp" "$BIN_DIR/$bin"
    done

    if [ -f "$ROOT_DIR/gui/controid_gui.py" ]; then
        tmp="$BIN_DIR/.controid-gui.new.$$"
        install -m755 "$ROOT_DIR/gui/controid_gui.py" "$tmp"
        mv -f "$tmp" "$BIN_DIR/controid-gui"
    fi

    for size in 32x32 48x48 64x64 128x128 256x256 512x512; do
        local icon_src="$ROOT_DIR/assets/icons/$size/controid.png"
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

    ensure_local_bin_in_path

    if [ "$RUN_PAIR" -eq 1 ]; then
        if [ "$NO_SYSTEMD" -eq 0 ] && [ "$has_systemd_sys" -eq 1 ] &&
            systemctl --user show-environment >/dev/null 2>&1 &&
            systemctl --user is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
            systemctl --user stop "$SERVICE_NAME"
        fi
        log "Iniciando emparejamiento BLE (abre la app Android)..."
        "$BIN_DIR/controid" pair
    fi

    local has_systemd_user=0
    if [ "$has_systemd_sys" -eq 1 ] && systemctl --user show-environment >/dev/null 2>&1; then
        has_systemd_user=1
    fi

    if [ "$has_systemd_sys" -eq 1 ] || [ -d "$SYSTEMD_USER_DIR" ]; then
        log "Instalando unidad systemd en $SYSTEMD_USER_DIR/$SERVICE_NAME..."
        install -Dm644 "$ROOT_DIR/systemd/$SERVICE_NAME" "$SYSTEMD_USER_DIR/$SERVICE_NAME"
    fi

    if [ "$NO_SYSTEMD" -eq 0 ] && [ "$has_systemd_user" -eq 1 ]; then
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
        if [ "$XDG_AUTOSTART" -eq 0 ] && ask_yes_no "¿Instalar entrada XDG autostart ($AUTOSTART_DIR/controidd.desktop)? [s/N]" 0; then
            XDG_AUTOSTART=1
        elif [ "$XDG_AUTOSTART" -eq 0 ]; then
            log "Sugerencia sin systemd: reejecuta con '--xdg-autostart' o 'make install-autostart'."
        fi
    fi

    if [ "$XDG_AUTOSTART" -eq 1 ]; then
        install_xdg_autostart
    fi

    log "Instalación de PC completada."
}

download_file() {
    local url="$1"
    local dest="$2"
    ensure_curl
    if command -v curl >/dev/null 2>&1; then
        curl -fL --progress-bar "$url" -o "$dest"
    else
        wget -q --show-progress "$url" -O "$dest"
    fi
}

ensure_adb() {
    command -v adb >/dev/null 2>&1 && return 0
    if [ "$NO_DEPS" -eq 1 ]; then
        return 1
    fi
    log "Instalando ADB (herramientas de línea de comandos Android)..."
    case "$PKG_MGR" in
        pacman) install_pkg_list_pacman android-tools ;;
        apt) install_pkg_list_apt adb ;;
        dnf) install_pkg_list_dnf android-tools ;;
        zypper) install_pkg_list_zypper android-tools ;;
        xbps) install_pkg_list_xbps android-tools ;;
        apk) install_pkg_list_apk android-tools ;;
        eopkg) install_pkg_list_eopkg android-tools ;;
        *) return 1 ;;
    esac
    command -v adb >/dev/null 2>&1
}

build_android_apk_from_source() {
    local android_dir="$ROOT_DIR/android"
    local local_props="$android_dir/local.properties"
    local sdk_dir=""

    command -v java >/dev/null 2>&1 || die "Para compilar el APK desde fuente (--build-apk) necesitas JDK 17+ instalado."

    if [ -f "$local_props" ]; then
        sdk_dir="$(sed -nE 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*(.*)$/\1/p' "$local_props" | tail -n1 | tr -d '\r')"
        sdk_dir="${sdk_dir//\\:/:}"
    fi
    if [ -z "$sdk_dir" ] || [ ! -d "$sdk_dir" ]; then
        for candidate in \
            "${ANDROID_HOME:-}" \
            "${ANDROID_SDK_ROOT:-}" \
            "$ROOT_DIR/../android-sdk" \
            "$HOME/Android/Sdk" \
            "/opt/android-sdk" \
            "/usr/lib/android-sdk" \
            "/usr/local/android-sdk"; do
            if [[ -n "$candidate" && ( -d "$candidate/platforms" || -d "$candidate/platform-tools" ) ]]; then
                sdk_dir="$(cd -- "$candidate" && pwd)"
                break
            fi
        done
    fi

    [ -n "$sdk_dir" ] || die "No se encontró Android SDK (ANDROID_HOME / ~/Android/Sdk). Si solo quieres instalar la app, ejecuta './install.sh --android' sin '--build-apk' para usar el APK precompilado."

    export ANDROID_HOME="$sdk_dir"
    export ANDROID_SDK_ROOT="$sdk_dir"
    if [ ! -f "$local_props" ] || ! grep -qE '^[[:space:]]*sdk\.dir[[:space:]]*=' "$local_props"; then
        printf 'sdk.dir=%s\n' "$sdk_dir" >> "$local_props"
    fi

    log "Compilando APK Android desde fuente (./gradlew assembleDebug)..."
    (cd "$android_dir" && ./gradlew assembleDebug)
    APK_PATH="$android_dir/app/build/outputs/apk/debug/app-debug.apk"
    [ -s "$APK_PATH" ] || die "La compilación terminó pero no se encontró $APK_PATH"
}

resolve_android_apk() {
    mkdir -p "$CACHE_DIR"
    APK_PATH=""

    if [ "$BUILD_APK" -eq 1 ]; then
        build_android_apk_from_source
        return 0
    fi

    if [ -n "$CUSTOM_APK" ]; then
        if [[ "$CUSTOM_APK" =~ ^https?:// ]]; then
            APK_PATH="$CACHE_DIR/controid-custom.apk"
            log "Descargando APK desde $CUSTOM_APK..."
            download_file "$CUSTOM_APK" "$APK_PATH"
        else
            [ -s "$CUSTOM_APK" ] || die "APK no encontrado o vacío: $CUSTOM_APK"
            APK_PATH="$CUSTOM_APK"
        fi
        return 0
    fi

    local local_web_apk="$ROOT_DIR/web/downloads/controid.apk"
    if [ -s "$local_web_apk" ]; then
        log "Usando APK precompilado local: $local_web_apk"
        APK_PATH="$local_web_apk"
        return 0
    fi

    local cached_apk="$CACHE_DIR/controid.apk"
    local info_tmp="$CACHE_DIR/apk-info.json"
    local expected_sha=""
    local apk_ver=""

    ensure_curl
    log "Consultando última versión del APK precompilado..."
    if download_file "$OFFICIAL_APK_INFO_URL" "$info_tmp" >/dev/null 2>&1; then
        expected_sha="$(sed -nE 's/.*"sha256"[[:space:]]*:[[:space:]]*"([0-9a-fA-F]{64})".*/\1/p' "$info_tmp" | head -n1)"
        apk_ver="$(sed -nE 's/.*"versionName"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' "$info_tmp" | head -n1)"
    fi

    if [ -n "$expected_sha" ] && [ -s "$cached_apk" ] && command -v sha256sum >/dev/null 2>&1; then
        local current_sha
        current_sha="$(sha256sum "$cached_apk" | awk '{print $1}')"
        if [ "$current_sha" = "$expected_sha" ]; then
            log "APK precompilado ya en caché (${apk_ver:+v$apk_ver, }SHA-256 verificado)."
            APK_PATH="$cached_apk"
            return 0
        fi
    fi

    log "Descargando APK precompilado oficial${apk_ver:+ (v$apk_ver)}..."
    local tmp_apk="$CACHE_DIR/.controid.apk.download.$$"
    download_file "$OFFICIAL_APK_URL" "$tmp_apk" || die "No se pudo descargar el APK desde $OFFICIAL_APK_URL. Puedes descargarlo manualmente desde el navegador en tu móvil."
    [ -s "$tmp_apk" ] || die "El APK descargado está vacío."

    if [ -n "$expected_sha" ] && command -v sha256sum >/dev/null 2>&1; then
        local got_sha
        got_sha="$(sha256sum "$tmp_apk" | awk '{print $1}')"
        if [ "$got_sha" != "$expected_sha" ]; then
            rm -f "$tmp_apk"
            die "Verificación SHA-256 fallida para el APK descargado (esperado $expected_sha, obtenido $got_sha)."
        fi
        log "SHA-256 del APK verificado (${got_sha:0:12})."
    fi

    mv -f "$tmp_apk" "$cached_apk"
    APK_PATH="$cached_apk"
}

save_apk_for_manual_transfer() {
    local dest_dir="$HOME/Downloads"
    [ -d "$dest_dir" ] || dest_dir="$ROOT_DIR"
    local dest="$dest_dir/controid.apk"
    if [ "$APK_PATH" != "$dest" ]; then
        cp -f "$APK_PATH" "$dest"
    fi
    log "APK guardado en: $dest"
    log "También puedes descargarlo directamente desde el móvil en:"
    log "  $OFFICIAL_APK_URL"
}

install_android() {
    log "=== Instalando Controid en Android ==="
    resolve_android_apk

    if ! ensure_adb; then
        warn "No se pudo instalar 'adb' automáticamente."
        save_apk_for_manual_transfer
        return 0
    fi

    adb start-server >/dev/null 2>&1 || true

    local serial=""
    while true; do
        local devices_out authorized_list unauthorized_count
        devices_out="$(adb devices 2>/dev/null | awk 'NR>1 && NF>=2 {print $1 "\t" $2}')"
        authorized_list="$(printf '%s\n' "$devices_out" | awk '$2=="device" {print $1}')"
        unauthorized_count="$(printf '%s\n' "$devices_out" | awk '$2=="unauthorized" {c++} END{print c+0}')"

        if [ -n "$authorized_list" ]; then
            serial="$(printf '%s\n' "$authorized_list" | head -n1)"
            break
        fi

        if [ "$unauthorized_count" -gt 0 ]; then
            warn "Móvil detectado por ADB pero no autorizado: acepta el diálogo '¿Permitir depuración USB?' en la pantalla del teléfono."
        else
            warn "No se detectó ningún móvil Android con Depuración USB/Inalámbrica activa."
        fi

        if [ "$AUTO_YES" -eq 1 ] || [ ! -t 0 ]; then
            save_apk_for_manual_transfer
            return 0
        fi

        if ask_yes_no "¿Reintentar detección ADB tras conectar/autorizar el móvil? (Enter o 'n' guarda el APK) [s/N]" 0; then
            continue
        else
            save_apk_for_manual_transfer
            return 0
        fi
    done

    log "Instalando APK en dispositivo Android ($serial)..."
    local adb_out=""
    set +e
    adb_out="$(adb -s "$serial" install -r "$APK_PATH" 2>&1)"
    local adb_rc=$?
    set -e

    if [ "$adb_rc" -ne 0 ]; then
        if printf '%s' "$adb_out" | grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'; then
            warn "La firma del APK instalado en el móvil difiere de este APK."
            if ask_yes_no "¿Desinstalar la versión previa (com.controid) e instalar esta? [S/n]" 1; then
                adb -s "$serial" uninstall com.controid
                adb -s "$serial" install "$APK_PATH"
            else
                die "Instalación cancelada por firma incompatible."
            fi
        else
            printf '%s\n' "$adb_out" >&2
            save_apk_for_manual_transfer
            die "Fallo al instalar el APK por ADB."
        fi
    fi

    log "App Android instalada correctamente en $serial."
    cat <<'EOF'

En el móvil Android:
  1. Abre Shizuku y verifica que indique "Shizuku está en ejecución".
  2. Abre Controid y concede Bluetooth/Dispositivos cercanos, Notificaciones y Shizuku.
  3. Desactiva la optimización de batería para Controid y Shizuku.
EOF
}

detect_distro

if [ "$TARGET_PC" -eq 1 ]; then
    install_pc
fi

if [ "$TARGET_ANDROID" -eq 1 ]; then
    install_android
fi

if [ "$TARGET_PC" -eq 1 ] && ! is_paired; then
    start_hint="$BIN_DIR/controidd &"
    if [ -d /run/systemd/system ] && command -v systemctl >/dev/null 2>&1 &&
        systemctl --user show-environment >/dev/null 2>&1; then
        start_hint="systemctl --user start $SERVICE_NAME"
    fi
    cat <<EOF

Siguientes pasos:
  1. Si aún no tienes la app en el móvil, descarga el APK precompilado directamente desde tu Android:
       $OFFICIAL_APK_URL
     (o conecta el móvil por USB/ADB y ejecuta: ./install.sh --android)
  2. Guarda el perfil Wi-Fi del hotspot Android en NetworkManager o iwd (conectándote una vez), o usa modo LAN directa en la GUI.
  3. Con la app Android abierta, empareja por BLE:
       controid pair
  4. Inicia el demonio o abre la interfaz gráfica:
       $start_hint
       controid-gui
EOF
fi
