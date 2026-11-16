#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
WEB_DIR="$ROOT_DIR/web"
CONTAINERFILE="$WEB_DIR/Containerfile"
SYNC_SCRIPT="$WEB_DIR/sync-apk.sh"
APK_FILE="$WEB_DIR/downloads/controid.apk"
APK_INFO_FILE="$WEB_DIR/downloads/apk-info.json"

CONTAINER_NAME="${CONTROID_WEB_CONTAINER:-controid-web}"
IMAGE_NAME="${CONTROID_WEB_IMAGE:-localhost/controid-web:latest}"
BIND_ADDR="${CONTROID_WEB_BIND:-0.0.0.0}"
PREFERRED_PORT="${CONTROID_WEB_PORT:-}"
PORT_RANGE_START="${CONTROID_WEB_PORT_START:-8080}"
PORT_RANGE_END="${CONTROID_WEB_PORT_END:-8999}"
RESTART_POLICY="${CONTROID_WEB_RESTART:-unless-stopped}"

FORCE_APK_BUILD=0
SKIP_APK=0
STRICT_PORT=0
USE_CACHE=0
ACTION="deploy"

log()  { printf '[deploy] %s\n' "$*"; }
warn() { printf '[warn]   %s\n' "$*" >&2; }
die()  { printf '[error]  %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'EOF'
Uso: ./deploy.sh [opciones]

Automatiza la compilación/subida del APK actualizado a web/downloads/, la
construcción de la imagen Podman de Controid Web y el despliegue del contenedor
con detección dinámica de puertos libres para evitar conflictos.

Opciones:
  -p, --port <puerto>      Puerto preferido en el host (si está ocupado, busca el siguiente libre)
      --strict-port        Falla si --port está ocupado en lugar de buscar otro libre
      --port-start <n>     Inicio del rango de búsqueda dinámica (por defecto: 8080)
      --port-end <n>       Fin del rango de búsqueda dinámica (por defecto: 8999)
      --bind <ip>          Dirección de escucha en el host (por defecto: 0.0.0.0)
      --name <nombre>      Nombre del contenedor Podman (por defecto: controid-web)
      --image <imagen>     Tag de la imagen Podman (por defecto: localhost/controid-web:latest)
      --restart <política> Política de reinicio de Podman (por defecto: unless-stopped)
      --build-apk          Fuerza recompilar el APK Android (./web/sync-apk.sh --build)
      --skip-apk           Omite sincronizar/compilar el APK antes de construir la imagen
      --cache              Permite caché de capas en 'podman build' (por defecto usa --no-cache)
      --status             Muestra el estado actual del contenedor, puerto y APK publicado
      --stop               Detiene y elimina el contenedor desplegado
  -h, --help               Muestra esta ayuda
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -p|--port)
            [[ $# -ge 2 ]] || die "Falta el número de puerto para $1"
            PREFERRED_PORT="$2"
            shift 2
            ;;
        --strict-port)
            STRICT_PORT=1
            shift
            ;;
        --port-start)
            [[ $# -ge 2 ]] || die "Falta el número de puerto para $1"
            PORT_RANGE_START="$2"
            shift 2
            ;;
        --port-end)
            [[ $# -ge 2 ]] || die "Falta el número de puerto para $1"
            PORT_RANGE_END="$2"
            shift 2
            ;;
        --bind)
            [[ $# -ge 2 ]] || die "Falta la IP para $1"
            BIND_ADDR="$2"
            shift 2
            ;;
        --name)
            [[ $# -ge 2 ]] || die "Falta el nombre de contenedor para $1"
            CONTAINER_NAME="$2"
            shift 2
            ;;
        --image)
            [[ $# -ge 2 ]] || die "Falta el nombre de imagen para $1"
            IMAGE_NAME="$2"
            shift 2
            ;;
        --restart)
            [[ $# -ge 2 ]] || die "Falta la política de reinicio para $1"
            RESTART_POLICY="$2"
            shift 2
            ;;
        --build-apk)
            FORCE_APK_BUILD=1
            shift
            ;;
        --skip-apk)
            SKIP_APK=1
            shift
            ;;
        --cache)
            USE_CACHE=1
            shift
            ;;
        --status)
            ACTION="status"
            shift
            ;;
        --stop|--down)
            ACTION="stop"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "Opción desconocida: $1 (usa --help)"
            ;;
    esac
done

validate_port() {
    local p="$1"
    [[ "$p" =~ ^[0-9]+$ ]] && (( p >= 1 && p <= 65535 ))
}

command -v podman >/dev/null 2>&1 || die "Se requiere 'podman' instalado en el sistema."
[[ -d "$WEB_DIR" ]] || die "No existe el directorio web en $WEB_DIR"
[[ -f "$CONTAINERFILE" ]] || die "No existe $CONTAINERFILE"

if [[ -n "$PREFERRED_PORT" ]]; then
    validate_port "$PREFERRED_PORT" || die "Puerto inválido: $PREFERRED_PORT"
fi
validate_port "$PORT_RANGE_START" || die "Puerto inicial inválido: $PORT_RANGE_START"
validate_port "$PORT_RANGE_END" || die "Puerto final inválido: $PORT_RANGE_END"
(( PORT_RANGE_START <= PORT_RANGE_END )) || die "--port-start ($PORT_RANGE_START) no puede ser mayor que --port-end ($PORT_RANGE_END)"

unprivileged_port_floor() {
    if [[ "$(id -u)" -eq 0 ]]; then
        echo 1
        return
    fi
    if [[ -r /proc/sys/net/ipv4/ip_unprivileged_port_start ]]; then
        cat /proc/sys/net/ipv4/ip_unprivileged_port_start
    else
        echo 1024
    fi
}

container_exists() {
    podman container exists "$1" >/dev/null 2>&1
}

container_running() {
    local status
    status="$(podman inspect -f '{{.State.Running}}' "$1" 2>/dev/null || echo false)"
    [[ "$status" == "true" ]]
}

get_container_host_port() {
    local name="$1"
    container_exists "$name" || return 0
    podman inspect -f '{{with index .HostConfig.PortBindings "80/tcp"}}{{(index . 0).HostPort}}{{end}}' "$name" 2>/dev/null || true
}

other_podman_container_using_port() {
    local port="$1"
    local exclude_name="$2"
    podman ps -a --format '{{.Names}}\t{{.Ports}}' 2>/dev/null | awk -v port="$port" -v excl="$exclude_name" '
        $1 != excl && $0 ~ (":" port "->") { print $1; exit }
    '
}

ss_port_in_use() {
    local port="$1"
    command -v ss >/dev/null 2>&1 || return 1
    ss -H -ltn 2>/dev/null | awk -v port="$port" '
        {
            addr = $4
            sub(/.*:/, "", addr)
            if (addr == port) { found = 1; exit }
        }
        END { exit(found ? 0 : 1) }
    '
}

python_can_bind_port() {
    local port="$1"
    local bind_addr="$2"
    command -v python3 >/dev/null 2>&1 || return 0
    python3 - "$port" "$bind_addr" <<'PY'
import socket, sys

port = int(sys.argv[1])
bind_addr = sys.argv[2]

def try_bind(family, addr):
    s = socket.socket(family, socket.SOCK_STREAM)
    try:
        if family == socket.AF_INET6 and hasattr(socket, "IPV6_V6ONLY"):
            try:
                s.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
            except OSError:
                pass
        s.bind((addr, port))
        return True
    except OSError:
        return False
    finally:
        s.close()

if bind_addr in ("0.0.0.0", ""):
    ok4 = try_bind(socket.AF_INET, "0.0.0.0")
    ok6 = True
    if socket.has_ipv6:
        ok6 = try_bind(socket.AF_INET6, "::")
    sys.exit(0 if (ok4 and ok6) else 1)
elif ":" in bind_addr:
    sys.exit(0 if try_bind(socket.AF_INET6, bind_addr) else 1)
else:
    sys.exit(0 if try_bind(socket.AF_INET, bind_addr) else 1)
PY
}

port_conflict_reason() {
    local port="$1"
    local exclude_name="$2"
    local floor
    floor="$(unprivileged_port_floor)"

    if (( port < floor )); then
        echo "requiere privilegios (< $floor en Podman rootless)"
        return 0
    fi

    local other_ctr
    other_ctr="$(other_podman_container_using_port "$port" "$exclude_name")"
    if [[ -n "$other_ctr" ]]; then
        echo "asignado al contenedor Podman '$other_ctr'"
        return 0
    fi

    if ss_port_in_use "$port"; then
        echo "en uso por otro proceso del sistema (ss)"
        return 0
    fi

    if ! python_can_bind_port "$port" "$BIND_ADDR"; then
        echo "no disponible al intentar bind en $BIND_ADDR:$port"
        return 0
    fi

    return 1
}

select_available_port() {
    local exclude_name="$1"
    local previous_port="$2"
    local prev_running="$3"
    local candidates=()
    local p reason

    if [[ -n "$PREFERRED_PORT" ]]; then
        if [[ "$STRICT_PORT" -eq 1 ]]; then
            if [[ "$PREFERRED_PORT" == "$previous_port" && "$prev_running" == "true" ]]; then
                local other_ctr
                other_ctr="$(other_podman_container_using_port "$PREFERRED_PORT" "$exclude_name")"
                if [[ -n "$other_ctr" ]]; then
                    die "El puerto $PREFERRED_PORT está ocupado (asignado al contenedor Podman '$other_ctr')."
                fi
                echo "$PREFERRED_PORT"
                return 0
            fi
            if reason="$(port_conflict_reason "$PREFERRED_PORT" "$exclude_name")"; then
                die "El puerto $PREFERRED_PORT está ocupado ($reason)."
            fi
            echo "$PREFERRED_PORT"
            return 0
        fi
        local start_scan="$PREFERRED_PORT"
        for (( p = start_scan; p <= PORT_RANGE_END; p++ )); do
            candidates+=("$p")
        done
        if (( PREFERRED_PORT > PORT_RANGE_START )); then
            for (( p = PORT_RANGE_START; p < PREFERRED_PORT; p++ )); do
                candidates+=("$p")
            done
        fi
    else
        if [[ -n "$previous_port" ]] && validate_port "$previous_port"; then
            candidates+=("$previous_port")
        fi
        for (( p = PORT_RANGE_START; p <= PORT_RANGE_END; p++ )); do
            [[ "$p" == "$previous_port" ]] && continue
            candidates+=("$p")
        done
    fi

    for p in "${candidates[@]}"; do
        if [[ "$p" == "$previous_port" && "$prev_running" == "true" ]]; then
            local other_ctr
            other_ctr="$(other_podman_container_using_port "$p" "$exclude_name")"
            if [[ -z "$other_ctr" ]]; then
                log "Reutilizando puerto previo de '$exclude_name': $p" >&2
                echo "$p"
                return 0
            fi
        fi

        if reason="$(port_conflict_reason "$p" "$exclude_name")"; then
            warn "Puerto $p ocupado ($reason); probando siguiente..."
            continue
        fi
        echo "$p"
        return 0
    done

    die "No se encontró ningún puerto libre en el rango revisado."
}

print_apk_summary() {
    if [[ -f "$APK_INFO_FILE" ]] && command -v python3 >/dev/null 2>&1; then
        python3 - "$APK_INFO_FILE" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    d = json.load(f)
mb = d.get("sizeBytes", 0) / (1024 * 1024)
print(f"v{d.get('versionName', '?')} (code {d.get('versionCode', '?')}) · {mb:.1f} MB · sha256:{str(d.get('sha256', ''))[:12]}")
PY
    elif [[ -f "$APK_FILE" ]]; then
        sha256sum "$APK_FILE" | awk '{print "sha256:" substr($1, 1, 12)}'
    else
        echo "no disponible"
    fi
}

show_status() {
    if ! container_exists "$CONTAINER_NAME"; then
        log "El contenedor '$CONTAINER_NAME' no existe."
        return 0
    fi
    local running port
    running="$(podman inspect -f '{{.State.Status}}' "$CONTAINER_NAME" 2>/dev/null || echo unknown)"
    port="$(get_container_host_port "$CONTAINER_NAME")"
    log "Contenedor: $CONTAINER_NAME (estado: $running)"
    if [[ -n "$port" ]]; then
        log "Puerto host: $BIND_ADDR:$port -> 80/tcp"
        log "Web:         http://localhost:$port/"
        log "APK directo: http://localhost:$port/downloads/controid.apk"
    fi
    log "APK local:   $(print_apk_summary)"
}

if [[ "$ACTION" == "status" ]]; then
    show_status
    exit 0
fi

if [[ "$ACTION" == "stop" ]]; then
    if container_exists "$CONTAINER_NAME"; then
        log "Deteniendo y eliminando contenedor '$CONTAINER_NAME'..."
        podman rm -f "$CONTAINER_NAME" >/dev/null
        log "Contenedor '$CONTAINER_NAME' eliminado."
    else
        log "El contenedor '$CONTAINER_NAME' no estaba desplegado."
    fi
    exit 0
fi

# 1. Sincronizar/compilar APK actualizado en web/downloads/
if [[ "$SKIP_APK" -eq 0 ]]; then
    [[ -x "$SYNC_SCRIPT" ]] || die "No se encuentra ejecutable $SYNC_SCRIPT"
    if [[ "$FORCE_APK_BUILD" -eq 1 ]]; then
        log "Compilando y sincronizando APK Android (--build)..."
        "$SYNC_SCRIPT" --build
    else
        log "Verificando y sincronizando APK Android en web/downloads/..."
        "$SYNC_SCRIPT"
    fi
else
    log "Omitiendo sincronización de APK (--skip-apk)."
fi

[[ -s "$APK_FILE" ]] || die "No existe un APK válido en $APK_FILE"
[[ -s "$APK_INFO_FILE" ]] || die "No existe metadata en $APK_INFO_FILE"
log "APK listo para desplegar: $(print_apk_summary)"

# 2. Detectar puerto previo y disponibilidad antes de construir
PREVIOUS_PORT="$(get_container_host_port "$CONTAINER_NAME")"
PREV_RUNNING="false"
if container_running "$CONTAINER_NAME"; then
    PREV_RUNNING="true"
fi

TARGET_PORT="$(select_available_port "$CONTAINER_NAME" "$PREVIOUS_PORT" "$PREV_RUNNING")"
log "Puerto seleccionado inicialmente: $TARGET_PORT"

# 3. Construir imagen Podman (con fuse-overlayfs en rootless si está disponible)
BUILD_ARGS=(-t "$IMAGE_NAME" -f "$CONTAINERFILE")
if [[ "$USE_CACHE" -eq 0 ]]; then
    BUILD_ARGS+=(--no-cache)
fi
FUSE_BIN="$(command -v fuse-overlayfs || true)"
if [[ -n "$FUSE_BIN" ]] && [[ "$(id -u)" -ne 0 ]]; then
    BUILD_ARGS+=(--storage-opt "overlay.mount_program=$FUSE_BIN")
fi

log "Construyendo imagen Podman '$IMAGE_NAME' desde '$WEB_DIR'..."
podman build "${BUILD_ARGS[@]}" "$WEB_DIR"

# 4. Reemplazar contenedor previo minimizando caída
if container_exists "$CONTAINER_NAME"; then
    log "Reemplazando contenedor previo '$CONTAINER_NAME'..."
    podman rm -f "$CONTAINER_NAME" >/dev/null
fi

# Re-verificar el puerto justo antes de arrancar (por si otro servicio lo tomó durante el build)
if reason="$(port_conflict_reason "$TARGET_PORT" "$CONTAINER_NAME")"; then
    if [[ "$STRICT_PORT" -eq 1 ]]; then
        die "El puerto $TARGET_PORT pasó a estar ocupado durante el build ($reason)."
    fi
    warn "El puerto $TARGET_PORT pasó a estar ocupado ($reason); reasignando..."
    TARGET_PORT="$(select_available_port "$CONTAINER_NAME" "" "false")"
fi

# Arranque con reintento automático ante condición de carrera en puertos (salvo --strict-port)
ATTEMPTS=0
MAX_ATTEMPTS=10
while true; do
    ATTEMPTS=$((ATTEMPTS + 1))
    log "Iniciando contenedor '$CONTAINER_NAME' en ${BIND_ADDR}:${TARGET_PORT} -> 80/tcp..."
    set +e
    RUN_OUTPUT="$(podman run -d \
        --name "$CONTAINER_NAME" \
        --restart "$RESTART_POLICY" \
        -p "${BIND_ADDR}:${TARGET_PORT}:80" \
        "$IMAGE_NAME" 2>&1)"
    RUN_RC=$?
    set -e

    if [[ $RUN_RC -eq 0 ]]; then
        CONTAINER_ID="$RUN_OUTPUT"
        break
    fi

    podman rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
    if [[ "$STRICT_PORT" -eq 1 ]] || (( ATTEMPTS >= MAX_ATTEMPTS )); then
        die "Error al arrancar '$CONTAINER_NAME' en puerto $TARGET_PORT: $RUN_OUTPUT"
    fi

    warn "Fallo al reservar puerto $TARGET_PORT ($RUN_OUTPUT); buscando siguiente puerto libre..."
    PREFERRED_PORT="$((TARGET_PORT + 1))"
    TARGET_PORT="$(select_available_port "$CONTAINER_NAME" "" "false")"
done

# 5. Verificación HTTP en vivo del sitio y del APK servido
if command -v curl >/dev/null 2>&1; then
    READY=0
    for _ in $(seq 1 20); do
        if curl -fsS "http://127.0.0.1:${TARGET_PORT}/health" >/dev/null 2>&1; then
            READY=1
            break
        fi
        sleep 0.25
    done
    [[ "$READY" -eq 1 ]] || die "Nginx en el contenedor no respondió en http://127.0.0.1:${TARGET_PORT}/health"

    LOCAL_SIZE="$(stat -c%s "$APK_FILE")"
    REMOTE_HEADERS="$(curl -fsSI "http://127.0.0.1:${TARGET_PORT}/downloads/controid.apk")"
    REMOTE_SIZE="$(printf '%s\n' "$REMOTE_HEADERS" | awk 'tolower($1)=="content-length:" {gsub(/\r/,"",$2); print $2}')"
    REMOTE_TYPE="$(printf '%s\n' "$REMOTE_HEADERS" | awk 'tolower($1)=="content-type:" {gsub(/\r/,"",$2); print $2}')"

    [[ "$REMOTE_SIZE" == "$LOCAL_SIZE" ]] || die "El APK servido ($REMOTE_SIZE bytes) no coincide con $APK_FILE ($LOCAL_SIZE bytes)"
    log "Verificación HTTP OK (/health 200, APK $REMOTE_SIZE bytes, $REMOTE_TYPE)"
fi

# 6. Resumen final con URLs accesibles
LOCAL_SHA="$(sha256sum "$APK_FILE" | awk '{print $1}')"
echo ""
echo "================================================================="
echo " Controid Web desplegado correctamente en Podman"
echo "-----------------------------------------------------------------"
echo " Contenedor : $CONTAINER_NAME (${CONTAINER_ID:0:12})"
echo " Imagen     : $IMAGE_NAME"
echo " Puerto     : ${BIND_ADDR}:${TARGET_PORT} -> 80/tcp"
echo " Web local  : http://localhost:${TARGET_PORT}/"
echo " APK directo: http://localhost:${TARGET_PORT}/downloads/controid.apk"
echo " APK info   : $(print_apk_summary)"
echo " SHA-256    : $LOCAL_SHA"

if [[ "$BIND_ADDR" == "0.0.0.0" ]]; then
    LAN_IPS=""
    if command -v ip >/dev/null 2>&1; then
        LAN_IPS="$(ip -4 -o addr show scope global 2>/dev/null | awk '{sub(/\/.*/, "", $4); print $4}' || true)"
    elif command -v hostname >/dev/null 2>&1; then
        LAN_IPS="$(hostname -I 2>/dev/null || true)"
    fi
    for ip in $LAN_IPS; do
        [[ "$ip" == *:* ]] && continue
        echo " Red/LAN    : http://${ip}:${TARGET_PORT}/"
    done
fi
echo "================================================================="
