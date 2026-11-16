<p align="center">
  <img src="assets/icons/128x128/controid.png" alt="Controid Logo" width="112" />
</p>

<h1 align="center">Controid</h1>

<p align="center">
  <strong>Tu Android y tu PC Linux unidos de forma nativa, privada y sin cables.</strong><br/>
  Dock remoto de apps, control multimedia, trackpad/teclado Bluetooth, portapapeles compartido, pantalla interactiva y archivos del móvil montados como carpeta local.
</p>

<p align="center">
  <a href="https://controid.monogoncode.dev">Web del proyecto</a> •
  <a href="#qué-puedes-hacer-con-controid">Funciones</a> •
  <a href="#requisitos-previos">Requisitos</a> •
  <a href="#guía-de-instalación">Instalación</a> •
  <a href="#primer-emparejamiento">Conexión</a> •
  <a href="#guía-de-uso">Cómo se usa</a> •
  <a href="#licencia">Licencia</a>
</p>
---

## ¿Qué puedes hacer con Controid?

Controid conecta tu teléfono Android y tu PC Linux directamente en tu red local (o activando el hotspot del móvil por Bluetooth LE), **100% en local y sin servidores externos**:

| Función | Qué hace en la práctica |
|---|---|
| **Dock del PC en el móvil (`Dock`)** | Ve todas las apps instaladas en tu Linux con sus iconos reales, filtra por categoría o *workspace*, abre/enfoca/cierra ventanas y cambia de espacio de trabajo desde el teléfono (vista **Dock** o **Tarjetas**). |
| **Control multimedia y sistema** | Reproduce, pausa, cambia de pista, ajusta el volumen, silencia el micrófono, bloquea la pantalla del PC o haz una captura, con **espectro de audio en vivo** opcional (`cava`). |
| **Trackpad y Teclado (`Pad`)** | Usa la pantalla del móvil como ratón y teclado inalámbricos por **Bluetooth HID** (baja latencia), con respaldo automático por red (`/dev/uinput`). |
| **Portapapeles compartido (`Clip`)** | Copia texto o imágenes (incluidas capturas de pantalla) en un dispositivo y pégalos al instante en el otro, con historial reciente y copia en 1 toque. |
| **Pantalla del móvil en el PC** | Abre la ventana nativa `controid-viewer` (vídeo H.264/H.265) para ver tu Android y manejarlo con el ratón y teclado del ordenador. |
| **Archivos en carpeta local** | Monta el almacenamiento interno del teléfono en `~/Controid` mediante **FUSE3** (`controid-mount`) para abrir, copiar o editar archivos como si fuera un disco local. |
| **Estética basada en [SekaiPop](https://github.com/Blapples/SekaiPop) sincronizable** | Elige entre **8 paletas de color** con modo claro y oscuro tanto en la app Android como en `controid-gui` (GTK4/Libadwaita). |
| **Seguridad local** | Cada sesión valida el PC mediante clave criptográfica **Ed25519**. |

---

## Requisitos previos

### 1. En Android
- **Android 9.0 o superior** (API 28+).
- **Bluetooth LE** (necesario para emparejamiento rápido, encendido automático del hotspot y modo Trackpad Bluetooth HID).
- **[Shizuku](https://shizuku.rikka.app/)** instalado y en ejecución (iniciado por *Depuración inalámbrica*, ADB o root). Controid usa Shizuku para:
  - leer el portapapeles en segundo plano y detectar capturas de pantalla,
  - activar el punto de acceso Wi-Fi automáticamente,
  - capturar la pantalla del sistema e inyectar toques/teclas remotas,
  - exponer el almacenamiento interno por FUSE.

### 2. En Linux
- **Sesión gráfica Wayland o X11:**
  - **Wayland:** Hyprland, Sway, Niri, KDE Plasma 6, GNOME, River, Wayfire o LabWC.
  - **X11 / Xorg:** soporte nativo (`XFixes` para portapapeles y ventanas EWMH para el Dock).
- **Servicios base:** `bluetoothd` (BlueZ) para BLE/HID, y `NetworkManager` o `iwd` para autoconexión al hotspot (en modo **LAN directa** no son obligatorios).
- **Dependencias de compilación y escritorio:**
  - `cargo`, `rustc`, `gcc`, `pkg-config`
  - Librerías dev: `libudev` (o `eudev`), `fuse3`, `sdl2`, `libavcodec`, `libswscale`, `libavutil`
  - Interfaz y herramientas: `python3` (>= 3.11) con PyGObject (`GTK 4` y `Libadwaita`), `fusermount3`
  - **Opcionales recomendados:** `wl-clipboard` / `xclip`, `playerctl` (controles MPRIS), `cava` (espectro de audio en tiempo real en el Dock), `rsvg-convert` o `magick` (renderizado de iconos SVG para apps del Dock), `mpv` (visor fallback).

<details>
<summary><strong>Instalar dependencias manualmente según tu distribución (opcional, <code>./install.sh --pc</code> lo hace automático)</strong></summary>

> Nota: `controidd` requiere **Rust >= 1.85** (Edition 2024). Si tu distribución incluye una versión más antigua, `./install.sh --pc` instalará `rustup` automáticamente.

#### Arch Linux / EndeavourOS / CachyOS
```bash
sudo pacman -S --needed \
  rustup base-devel pkgconf \
  systemd-libs fuse3 sdl2 ffmpeg \
  python-gobject python-cairo gtk4 libadwaita \
  wl-clipboard xclip bluez bluez-utils networkmanager \
  playerctl cava librsvg
```
*(Si acabas de instalar `rustup`, ejecuta `rustup default stable`.)*

#### Ubuntu / Debian / Pop!_OS
```bash
sudo apt update && sudo apt install -y \
  build-essential pkg-config curl ca-certificates \
  libudev-dev libfuse3-dev fuse3 libsdl2-dev \
  libavcodec-dev libswscale-dev libavutil-dev \
  python3-gi python3-gi-cairo gir1.2-gtk-4.0 gir1.2-adw-1 \
  wl-clipboard xclip bluez network-manager \
  playerctl librsvg2-bin
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
```

#### Fedora
```bash
sudo dnf install -y \
  gcc pkgconf-pkg-config curl \
  systemd-devel fuse3 fuse3-devel SDL2-devel \
  ffmpeg-free-devel \
  python3-gobject python3-cairo gtk4 libadwaita \
  wl-clipboard xclip bluez NetworkManager \
  playerctl librsvg2-tools
```
*(En Fedora, habilita RPM Fusion si necesitas soporte completo de FFmpeg H.264/H.265, y usa `rustup` si `rustc` es menor a 1.85.)*

#### openSUSE (Tumbleweed / Leap)
```bash
sudo zypper install -y \
  gcc pkg-config curl \
  libudev-devel fuse3 fuse3-devel libSDL2-devel \
  libavcodec-devel libswscale-devel libavutil-devel \
  python3-gobject python3-cairo typelib-1_0-Gtk-4_0 typelib-1_0-Adw-1 \
  wl-clipboard xclip bluez NetworkManager \
  playerctl rsvg-convert
```

#### Void Linux (runit)
```bash
sudo xbps-install -Sy \
  base-devel pkg-config curl \
  eudev-libudev-devel fuse3 fuse3-devel SDL2-devel ffmpeg6-devel \
  python3-gobject python3-cairo gtk4 libadwaita \
  wl-clipboard xclip bluez NetworkManager \
  playerctl librsvg-utils
```

#### Alpine Linux (OpenRC)
```bash
sudo apk add \
  build-base pkgconf cargo rust \
  eudev-dev fuse3 fuse3-dev sdl2-dev ffmpeg-dev \
  py3-gobject3 py3-cairo gtk4.0 libadwaita \
  wl-clipboard xclip bluez networkmanager \
  playerctl rsvg-convert
```

En distribuciones con `systemd`, activa Bluetooth y red:
```bash
sudo systemctl enable --now bluetooth NetworkManager
```

</details>

---

## Guía de instalación

La instalación del **PC Linux** y la de **Android** están separadas para que no necesites Android SDK, Java ni Gradle si solo quieres instalar el programa en el ordenador o usar el APK precompilado.

### Paso 1: Instalar en PC Linux (`--pc`)
Clona el repositorio y ejecuta `./install.sh --pc` (o simplemente `./install.sh`) **como usuario normal** (nunca con `sudo`; el instalador detecta tu distribución e instala automáticamente las dependencias del sistema y Rust):

```bash
git clone https://github.com/monogonCode/Controid.git
cd Controid
./install.sh --pc
```

Modos y opciones útiles de `./install.sh`:
- `--pc` *(por defecto)*: instala solo el programa de PC Linux (binarios, GUI, iconos y servicio), sin dependencias de Android.
- `--android`: descarga el APK precompilado oficial (sin compilar ni requerir Android SDK) y lo instala por ADB en el móvil conectado.
- `--all`: instala tanto el programa de PC como la app Android (`--pc` + `--android`).
- `--setup-uinput`: configura `/etc/udev/rules.d/99-controid-uinput.rules` y añade tu usuario al grupo `input` para usar el trackpad/teclado por red si no usas Bluetooth HID *(puede requerir cerrar e iniciar sesión una vez)*.
- `--xdg-autostart`: crea `~/.config/autostart/controidd.desktop` en sistemas o sesiones sin `systemd`.
- `--no-systemd`: instala los binarios sin ejecutar `systemctl --user`.
- `--no-deps`: omite la instalación automática de paquetes del sistema.
- `--pair`: ejecuta `controid pair` automáticamente al terminar.
- `-y, --yes`: acepta confirmaciones automáticamente.
- `--offline`: compila con `cargo --offline`.

Los ejecutables se instalan en `~/.local/bin/`:
`controidd` (demonio), `controid` (CLI), `controid-gui` (interfaz gráfica), `controid-viewer` (visor de pantalla), `controid-mount` (FUSE3) y `controid-tray` (bandeja).

> Si `~/.local/bin` no está en tu `PATH`, el instalador te ofrecerá añadirlo a `~/.bashrc` / `~/.zshrc`:
> ```bash
> export PATH="$HOME/.local/bin:$PATH"
> ```

### Paso 2: Instalar la app en Android (APK precompilado)
No necesitas compilar nada ni instalar Android SDK:

- **Opción recomendada (directa en el móvil):** descarga e instala el APK precompilado desde la web del proyecto:
  👉 **[https://controid.monogoncode.dev/downloads/controid.apk](https://controid.monogoncode.dev/downloads/controid.apk)**
- **Opción desde el PC por USB/ADB:** con *Depuración USB* activa en tu móvil, ejecuta:
  ```bash
  ./install.sh --android
  ```
  *(O `./install.sh --all` para instalar PC + Android en un solo paso.)*

<details>
<summary><strong>Solo desarrolladores: compilar el APK desde fuente</strong></summary>

Si modificas el código en `android/` y quieres compilar el APK con **JDK 17+** y **Android SDK**:

```bash
./install.sh --android --build-apk
# O manualmente:
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

</details>

### Paso 3: Activar permisos en el móvil
1. Abre **Shizuku** y comprueba que indique **"Shizuku está en ejecución"**.
2. Abre **Controid** en el móvil.
3. Concede **Dispositivos cercanos / Bluetooth**, **Notificaciones** y **Autorizar Shizuku siempre**.
4. Recomendado: desactiva la **optimización de batería** para **Controid** y **Shizuku** para evitar cierres en segundo plano.

---

## Primer emparejamiento

### Opción A: Automático (Bluetooth LE + Hotspot Wi-Fi)
Perfecto si quieres que el móvil encienda su punto de acceso Wi-Fi automáticamente al conectar:

1. **Guarda la red del hotspot una vez:** enciende el punto de acceso en tu móvil y conéctate a él una vez desde Linux para que `NetworkManager` o `iwd` guarden el nombre (SSID) y la contraseña. Luego puedes apagarlo.
2. **Empareja por Bluetooth:** empareja móvil y PC desde los ajustes Bluetooth del sistema (o `bluetoothctl`).
3. **Empareja Controid:** con la app abierta en Android, ejecuta en Linux:
   ```bash
   controid pair
   ```
4. **Inicia el demonio y la interfaz:**
   ```bash
   # Con systemd:
   systemctl --user enable --now controid.service
   # Sin systemd:
   controidd &

   controid-gui
   ```

### Opción B: LAN directa (mismo Wi-Fi / router)
Si PC y teléfono están en la misma red Wi-Fi y no necesitas activar el hotspot:

1. Abre `controid-gui` → pestaña **Ajustes**.
2. Cambia el modo de conexión a **LAN directa** (`direct`) e indica la IP local de tu móvil (puedes usar la autodetección si está conectado por ADB).
3. Pulsa **Guardar** y luego **Conectar**.

*(También puedes configurarlo en `~/.config/controid/config.toml` con `connection_mode = "direct"` y `direct_host = "192.168.x.x"`.)*

---

## Guía de uso

### En el móvil (App Android)
La app se organiza en 4 pestañas principales (y un selector de **Estética SekaiPop** para cambiar entre las 8 paletas y el modo claro/oscuro):

- **`Dock`**: lanza, enfoca o cierra aplicaciones de tu PC; filtra entre *Todo*, *Fijadas*, *Abiertas*, *Frecuentes* o categorías; cambia de *workspace*; y usa los controles multimedia, volumen, espectro de audio, bloqueo de pantalla y captura rápida. Puedes alternar entre vista clásica **Dock** y **Tarjetas**, además de pantalla completa.
- **`Panel`**: estado en tiempo real de conexión, batería, hotspot y accesos rápidos.
- **`Pad`**: incluye dos modos arriba:
  - **Pad**: trackpad multitáctil (1 dedo mueve/clic, 2 dedos o borde derecho hacen scroll), botones Izquierdo/Medio/Derecho y botón de teclado completo (Bluetooth HID o respaldo por red).
  - **Media**: mando a distancia multimedia con botones de dirección (`OK`, flechas, Atrás, Inicio, Menú), volumen, silencio de micrófono, bloqueo y `PLAY / PAUSA`.
- **`Clip`**: muestra el último elemento copiado (texto o imagen), permite enviarlo al PC, leer el portapapeles local y copiar al instante desde el historial reciente.

### En el PC Linux (`controid-gui` y `controid-viewer`)
- **`controid-gui`** (o busca **Controid** en el menú de aplicaciones):
  - **Panel**: estado de enlace, batería, encender/apagar hotspot, lanzar visor de pantalla (ajustando FPS, Mbps y códec H.264/H.265) y montar/abrir `~/Controid`.
  - **Clip**: historial y envío rápido de portapapeles entre Linux y Android.
  - **Ajustes**: modo de conexión (Auto BLE / LAN directa), preferencias del demonio y autostart.
  - **Logs**: diagnóstico rápido de BlueZ, red, FUSE3, portapapeles, `/dev/uinput`, ADB y registros en vivo.
- **Visor de pantalla (`controid-viewer`)**:
  - **Clic izquierdo / arrastre**: toque y deslizamiento en la pantalla Android.
  - **Rueda del ratón y teclado**: scroll y escritura directa en el teléfono.
  - **Atajos**: `Alt + H` (Inicio), `Alt + B` (Atrás), `Alt + S` (Recientes), `Alt + P` (Encender/suspender).

### Desde la terminal (`controid`)
```bash
controid pair                       # Descubrir móvil por BLE y guardar configuración
controid status                     # Ver estado de conexión, batería y sesión (--json disponible)
controid connect                    # Conectar ahora
controid disconnect                 # Desconectar (añade --hotspot-off para apagar hotspot)
controid hotspot on | off           # Encender o apagar el hotspot Android

controid screen start               # Duplicar pantalla (--max-fps / --bitrate-mbps)
controid screen stop                # Detener duplicación
controid screen key back            # Tecla remota: back | home | recents | power

controid files mount                # Montar móvil en ~/Controid
controid files unmount              # Desmontar ~/Controid
controid files status               # Consultar estado del montaje

controid clipboard send "Hola"      # Enviar texto al portapapeles del móvil
controid events --waybar            # Flujo NDJSON continuo para barra Waybar (por defecto)
```

<details>
<summary><strong>Integración opcional con Hyprland / Waybar</strong></summary>

En tu `hyprland.conf`:
```ini
exec-once = dbus-update-activation-environment --systemd WAYLAND_DISPLAY DISPLAY XDG_CURRENT_DESKTOP
exec-once = controid-tray
```
Puedes usar `controid events --waybar` en un módulo `custom/controid` de Waybar para mostrar batería y estado de conexión en la barra superior.

</details>

---

## Compatibilidad y notas importantes

- **Android multi-fabricante:** compatible con Pixel/AOSP, Samsung One UI, Xiaomi HyperOS/MIUI, OnePlus/OPPO ColorOS y Motorola/Realme. En marcas con gestión agresiva de batería (como HyperOS/MIUI o ColorOS), desactiva las restricciones de batería y habilita el inicio en segundo plano para **Controid** y **Shizuku**.
- **Portapapeles Linux universal:** `controidd` detecta automáticamente `ext_data_control_manager_v1` (KDE Plasma 6, GNOME moderno), `zwlr_data_control_manager_v1` (Hyprland, Sway, Niri, River, Wayfire, LabWC) o `X11 / XWayland` (`XFixes`).
- **Dock y control multimedia en Linux:** la gestión de ventanas y workspaces soporta de forma nativa **Hyprland**, **Sway**, **Niri** y entornos **X11**. En GNOME/KDE bajo Wayland funciona el lanzamiento de apps de escritorio, multimedia (`playerctl`/`wpctl`/`pactl`) y acciones del sistema con las utilidades instaladas.
- **Sistemas sin `systemd`:** compatible con Void (`runit`), Alpine (`OpenRC`), Artix o Devuan usando `./install.sh --xdg-autostart` (o `make install-autostart`).

---

## Estructura del proyecto

```text
Controid/
├── android/          # App Android (Kotlin, Jetpack Compose, Dock, Shizuku, BLE, HID, Stream, FUSE RPC)
├── daemon/           # Workspace Rust
│   └── crates/
│       ├── core-protocol/     # Protocolo binario v1, handshake Ed25519 y control de flujo
│       ├── hyprland-client/   # Dock/ventanas Linux, portapapeles Wayland/X11, uinput y vídeo
│       └── linux-daemon/      # controidd, dock_worker, CLI (controid) y bandeja (controid-tray)
├── gui/              # Centro de control gráfico en GTK4 / Libadwaita (controid_gui.py)
├── tools/            # Herramientas nativas C (controid-viewer y controid-mount)
├── systemd/          # Unidad systemd de usuario, lanzador .desktop y autostart XDG
├── proto/            # Especificaciones del protocolo v1, BLE GATT e IPC NDJSON
├── assets/           # Iconos oficiales de Controid
├── Makefile          # Atajos de compilación, test e instalación
└── install.sh        # Instalador automático para Linux
```

---

## Licencia

Distribuido bajo la licencia libre **[MIT](LICENSE)**. Puedes usar, modificar, redistribuir y aprender del código libremente manteniendo el aviso de copyright y licencia.

*Nota de diseño:* la interfaz **SekaiPop** de Controid (tanto en Jetpack Compose como en GTK4/Cairo) ha sido implementada desde cero para este proyecto, inspirándose únicamente en el estilo visual de SekaiPop sin reutilizar su código ni sus recursos.
