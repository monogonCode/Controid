<p align="center">
  <img src="assets/icons/128x128/controid.png" alt="Controid Logo" width="112" />
</p>

<h1 align="center">Controid</h1>

<p align="center">
  <strong>Conecta tu móvil Android con tu PC Linux de forma nativa, privada y sin cables.</strong><br/>
  Portapapeles compartido (texto e imágenes), pantalla interactiva, trackpad/teclado Bluetooth y archivos del móvil montados como carpeta local.
</p>

---

## ¿Qué hace Controid?

Controid une tu teléfono Android y tu escritorio Linux en una sola experiencia fluida, funcionando 100% en tu red local o mediante el hotspot del móvil, sin servidores externos:

- **Portapapeles compartido al instante (Linux ⇄ Android):** copia texto o imágenes (incluidas capturas de pantalla) en un dispositivo y pégalos en el otro. Soporta desde texto corto hasta imágenes o archivos grandes (hasta 512 MiB en Linux / 64 MiB por flujo en Android).
- **Pantalla del móvil en tu PC con control total:** abre una ventana nativa (`controid-viewer` con vídeo H.264/H.265) para ver la pantalla de tu Android y controlarlo con el ratón (clics, arrastre y rueda) y teclado del ordenador.
- **Usa el móvil como Trackpad y Teclado para Linux:** mueve el cursor, haz clic, desplázate y escribe en el ordenador usando tu teléfono por Bluetooth HID (sin latencia ni cables), con respaldo automático por red local (`/dev/uinput`).
- **Archivos del móvil como un disco local (`~/Controid`):** explora, lee, edita, copia y borra archivos del almacenamiento interno del teléfono desde tu gestor de archivos o terminal mediante FUSE3.
- **Conexión automática sin complicaciones:** activa el hotspot Wi-Fi del móvil automáticamente por Bluetooth Low Energy (BLE) al necesitar conectarte, o conéctate por **LAN directa** si ambos están en el mismo Wi-Fi.
- **Autenticación segura:** cada sesión verifica la identidad del PC con firma criptográfica **Ed25519**.
- **Interfaz gráfica + CLI + Bandeja:** incluye centro de control gráfico en GTK4 (`controid-gui`), herramienta de terminal (`controid`), icono de bandeja (`controid-tray`) y salida JSON lista para Waybar.

---

## Requisitos previos

### 1. En Android
- **Android 9.0 o superior** (API 28+).
- **Bluetooth Low Energy (BLE)** si quieres usar descubrimiento/encendido de hotspot y trackpad Bluetooth HID.
- **[Shizuku](https://shizuku.rikka.app/)** instalado y activo (iniciado por Depuración inalámbrica, ADB o root). Controid usa Shizuku para:
  - leer el portapapeles en segundo plano y detectar capturas de pantalla,
  - activar el hotspot Wi-Fi automáticamente,
  - capturar la pantalla del sistema e inyectar toques/teclas remotas,
  - exponer el almacenamiento interno por FUSE.

### 2. En Linux
- **Entorno gráfico Wayland o X11:**
  - **Wayland** con `ext_data_control_manager_v1` (KDE Plasma 6, GNOME/Mutter moderno) o `zwlr_data_control_manager_v1` (Hyprland, Sway, River, Wayfire, LabWC, Niri).
  - **X11 / Xorg** nativo (mediante `XFixes` sobre `CLIPBOARD`, texto e imágenes) y respaldo automático vía **XWayland** si un compositor Wayland no expone `data-control`.
- **Servicios del sistema:**
  - Gestor de sesiones `systemd --user`, **o** autostart estándar XDG (`~/.config/autostart/controidd.desktop`) / OpenRC / runit / s6 en distribuciones sin `systemd`.
  - `bluetoothd` (BlueZ) para BLE y perfil Bluetooth HID.
  - `NetworkManager` (principal) o `iwd` (`iwctl`) para autoconexión al hotspot Wi-Fi; si ninguno está activo, degrada limpiamente y permite modo **LAN directa**.
- **Dependencias de compilación y ejecución:**
  - Rust (`cargo` y `rustc`) + compilador C (`gcc`) + `pkg-config`
  - Librerías de desarrollo: `libudev` (o `eudev`), `fuse3`, `sdl2`, `libavcodec`, `libswscale`, `libavutil`
  - Herramientas de escritorio: `python3` (>= 3.11) con PyGObject (`GTK 4` y `Libadwaita`), `fusermount3`, y opcionalmente `wl-clipboard` (Wayland), `xclip`/`xsel` (X11) o `mpv` (visor secundario).

#### Instalación rápida de dependencias por distribución

<details>
<summary><strong>Arch Linux / EndeavourOS / CachyOS</strong></summary>

```bash
sudo pacman -S --needed \
  rustup gcc pkgconf \
  systemd-libs fuse3 sdl2 ffmpeg \
  python-gobject gtk4 libadwaita \
  wl-clipboard bluez bluez-utils networkmanager
```

Si acabas de instalar `rustup`:
```bash
rustup default stable
```

</details>

<details>
<summary><strong>Ubuntu / Debian / Pop!_OS</strong></summary>

```bash
sudo apt update && sudo apt install -y \
  build-essential pkg-config cargo rustc \
  libudev-dev libfuse3-dev fuse3 libsdl2-dev \
  libavcodec-dev libswscale-dev libavutil-dev \
  python3-gi gir1.2-gtk-4.0 gir1.2-adw-1 \
  wl-clipboard bluez network-manager
```

</details>

<details>
<summary><strong>Fedora</strong></summary>

```bash
sudo dnf install -y \
  gcc pkgconf-pkg-config cargo rust \
  systemd-devel fuse3 fuse3-devel SDL2-devel \
  ffmpeg-free-devel \
  python3-gobject gtk4 libadwaita \
  wl-clipboard bluez NetworkManager
```

*(Nota: en Fedora puede requerirse habilitar RPM Fusion si deseas paquetes completos de FFmpeg H.264/H.265.)*

</details>

<details>
<summary><strong>openSUSE (Tumbleweed / Leap)</strong></summary>

```bash
sudo zypper install -y \
  gcc pkg-config cargo rust \
  libudev-devel fuse3 fuse3-devel libSDL2-devel \
  ffmpeg-6-libavcodec-devel ffmpeg-6-libswscale-devel ffmpeg-6-libavutil-devel \
  python3-gobject typelib-1_0-Gtk-4_0 typelib-1_0-Adw-1 \
  wl-clipboard xclip bluez NetworkManager
```

</details>

<details>
<summary><strong>Void Linux (runit)</strong></summary>

```bash
sudo xbps-install -Sy \
  base-devel pkg-config cargo rust \
  eudev-libudev-devel fuse3 fuse3-devel SDL2-devel ffmpeg6-devel \
  python3-gobject gtk4 libadwaita \
  wl-clipboard xclip bluez NetworkManager
```

</details>

<details>
<summary><strong>Alpine Linux (OpenRC)</strong></summary>

```bash
sudo apk add \
  build-base pkgconf cargo rust \
  eudev-dev fuse3 fuse3-dev sdl2-dev ffmpeg-dev \
  py3-gobject3 gtk4.0 libadwaita \
  wl-clipboard xclip bluez networkmanager
```

</details>

Asegúrate de tener Bluetooth activo y tu gestor de red (`NetworkManager` o `iwd`):
```bash
# En distros con systemd:
sudo systemctl enable --now bluetooth NetworkManager
```

---

## Instalación

### Paso 1: Instalar en Linux
Clona o entra en la carpeta del proyecto y ejecuta el instalador (como **usuario normal**, nunca con `sudo`):

```bash
./install.sh
```

Opciones útiles del instalador:
- `--setup-uinput`: instala una regla `udev` (`/etc/udev/rules.d/99-controid-uinput.rules`) y añade tu usuario al grupo `input` para habilitar el respaldo de ratón/teclado por TCP cuando no uses Bluetooth HID *(puede requerir cerrar e iniciar sesión una vez)*.
- `--pair`: ejecuta automáticamente el emparejamiento BLE al finalizar.
- `--offline`: compila el proyecto Rust sin conexión a internet (`cargo --offline`).
- `--no-systemd`: instala binarios sin ejecutar comandos `systemctl --user`.
- `--xdg-autostart`: instala `~/.config/autostart/controidd.desktop` para arranque automático en distros o sesiones sin `systemd`.

Los binarios se instalan en `~/.local/bin/`:
- `controidd`: demonio principal en segundo plano (`controid.service`).
- `controid`: línea de comandos.
- `controid-gui`: interfaz gráfica (también accesible desde el menú de aplicaciones como **Controid**).
- `controid-viewer`: visor interactivo de pantalla Android.
- `controid-mount`: adaptador FUSE3 para montar los archivos del teléfono.
- `controid-tray`: icono en la bandeja del sistema.

> Si `~/.local/bin` no está en tu `PATH`, añádelo a `~/.bashrc` o `~/.zshrc`:
> ```bash
> export PATH="$HOME/.local/bin:$PATH"
> ```

### Paso 2: Instalar la app en Android
Necesitas **Android SDK** y **ADB** (con *Depuración USB* o *Depuración inalámbrica* activada en el móvil):

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Paso 3: Permisos iniciales en el móvil
1. Abre **Shizuku** en tu teléfono y asegúrate de que muestre **"Shizuku está en ejecución"**.
2. Abre **Controid** en el teléfono.
3. Concede los permisos solicitados: **Bluetooth / Dispositivos cercanos**, **Notificaciones** y **Autorizar Shizuku siempre**.
4. Recomendado: toca el aviso/ajuste para **desactivar la optimización de batería** en Controid y Shizuku para que el sistema no cierre la conexión en segundo plano.

---

## Primer emparejamiento y conexión

### Opción A: Modo Automático (Bluetooth LE + Hotspot Wi-Fi)
Ideal para usarlo fuera de casa o cuando el PC no está en la misma red que el móvil:

1. **Guarda la red del hotspot una primera vez:** enciende manualmente el punto de acceso Wi-Fi de tu Android y conéctate a él una vez desde tu PC para que **NetworkManager** guarde el nombre (SSID) y la contraseña. Luego puedes apagarlo.
2. **Empareja los dispositivos:**
   - Empareja el móvil y el PC por Bluetooth normal (desde Blueman, ajustes del sistema o `bluetoothctl`).
   - Con la app **Controid** abierta en el móvil, ejecuta en tu PC:
     ```bash
     controid pair
     ```
   - Esto detecta tu teléfono por BLE, guarda su MAC y el SSID de su hotspot en `~/.config/controid/config.toml` e intercambia las llaves de identidad **Ed25519**.
3. **Inicia el servicio y abre el panel:**
   ```bash
   # Con systemd:
   systemctl --user enable --now controid.service
   # Sin systemd (o con ~/.config/autostart/controidd.desktop):
   controidd &

   controid-gui
   ```

### Opción B: Modo LAN directa (PC y móvil en el mismo Wi-Fi)
Si ambos dispositivos comparten el mismo router Wi-Fi y no quieres activar el hotspot:

1. Abre `controid-gui`, ve a la pestaña de **Configuración / Conexión**.
2. Cambia el modo de conexión a **LAN directa** (`direct`) y escribe la IP local de tu teléfono (o usa el botón de autodetección si lo tienes por ADB).
3. Pulsa **Guardar** y **Conectar**.

*(También puedes editar `~/.config/controid/config.toml` manualmente con `connection_mode = "direct"` y `direct_host = "192.168.x.x"`.)*

---

## Cómo usar Controid

### Desde la interfaz gráfica (`controid-gui`)
Abre **Controid** desde tu lanzador de aplicaciones o ejecutando `controid-gui`. Desde ahí puedes:
- Ver estado de conexión, batería, hotspot y controlar el servicio (`systemd` o proceso `controidd` + autostart XDG si no hay `systemd`).
- Conectar / desconectar manualmente o encender/apagar el hotspot del móvil.
- Enviar texto o revisar el historial reciente del portapapeles (detectando automáticamente Wayland, X11 o el portapapeles nativo de GTK4).
- Iniciar/detener la duplicación de pantalla y elegir FPS, tasa de bits (Mbps) y códec (`H.264` o `H.265`).
- Montar/desmontar `~/Controid` y abrir la carpeta directamente.
- Ejecutar diagnósticos rápidos para verificar BlueZ, NetworkManager/`iwd`, FUSE3, sesión gráfica/portapapeles, `/dev/uinput`, ADB y logs (`journalctl` o `~/.local/state/controid/controidd.log`).

### Controles del visor de pantalla (`controid-viewer`)
Cuando inicias la duplicación de pantalla:
- **Clic izquierdo y arrastre:** toque y deslizamiento en la pantalla del teléfono.
- **Rueda del ratón:** desplazamiento vertical u horizontal.
- **Teclado del PC:** escribe directamente en campos de texto del móvil.
- **Atajos de navegación Android:**
  - `Alt + H`: Inicio (*Home*)
  - `Alt + B`: Atrás (*Back*)
  - `Alt + S`: Apps recientes (*Recents*)
  - `Alt + P`: Botón de encendido / suspender pantalla (*Power*)

### Desde la terminal (`controid`)

```bash
controid status                     # Estado actual de conexión, batería y sesión
controid connect                    # Conectar ahora (Auto o LAN directa)
controid disconnect                 # Desconectar (añade --hotspot-off para apagar el hotspot)
controid hotspot on | off           # Encender o apagar el hotspot del móvil

controid screen start               # Iniciar duplicación de pantalla (acepta --max-fps y --bitrate-mbps)
controid screen stop                # Detener duplicación de pantalla
controid screen key back            # Enviar tecla: back | home | recents | power

controid files mount                # Montar almacenamiento Android en ~/Controid
controid files unmount              # Desmontar ~/Controid
controid files status               # Ver si está montado

controid clipboard send "Hola"      # Enviar texto al portapapeles del teléfono
controid events --waybar            # Flujo NDJSON continuo para barra Waybar (por defecto)
```

### Integración con Hyprland / Waybar (opcional)
- Añade en tu `hyprland.conf`:
  ```ini
  exec-once = dbus-update-activation-environment --systemd WAYLAND_DISPLAY XDG_CURRENT_DESKTOP
  exec-once = controid-tray
  ```
- Puedes conectar `controid events --waybar` a un módulo `custom/controid` en Waybar para mostrar batería y estado de conexión en tu barra superior.

---

## Compatibilidad y limitaciones

### En Android
- **Multi-fabricante (Android 9+ / API 28+):** funciona en AOSP/Pixel, Samsung One UI, Xiaomi HyperOS/MIUI, OnePlus/OPPO ColorOS/OxygenOS, Motorola, Realme, etc. mediante APIs estándar y servicios Shizuku (`IClipboard` con soporte `AttributionSource` en Android 12+, `MediaStore`, `ITetheringConnector` con respaldo `ConnectivityManager`, `SurfaceControl`/`DisplayManagerGlobal`, `BluetoothHidDevice`).
- **Capturas de pantalla universales:** combina un `ContentObserver` directo sobre `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` con múltiples `FileObserver` (`Pictures/Screenshots`, `DCIM/Screenshots`, `Screenshots`, carpetas localizadas como `Capturas de pantalla`, `MIUI/screenshot` y subcarpetas detectadas dinámicamente), filtrando de forma insensible a mayúsculas por `BUCKET_DISPLAY_NAME`, `RELATIVE_PATH` y `_DATA`, y verificando `IS_PENDING = 0` y tamaño > 0.
- **Diferencias posibles según el fabricante:**
  - **Cierre agresivo en segundo plano:** Xiaomi (HyperOS/MIUI), OPPO/OnePlus/Realme (ColorOS) o Samsung pueden restringir servicios si no desactivas la optimización de batería (y en HyperOS/MIUI activar *Inicio automático* / *Depuración USB (Ajustes de seguridad)* para Shizuku).
  - **Restricciones de portapapeles o tethering:** algunos OEM o operadoras bloquean el aprovisionamiento programático del hotspot o el acceso en segundo plano al portapapeles; Controid intenta respaldos automáticos (5 GHz -> 2.4 GHz, `ITetheringConnector` -> `ConnectivityManager` legado) y reporta diagnósticos claros si el sistema los deniega.

### En Linux
- **Portapapeles universal (Wayland + X11):** `controidd` detecta automáticamente en tiempo de ejecución el mejor backend disponible y reintenta reconectarse si el servidor gráfico cambia o arranca más tarde:
  1. `ext_data_control_manager_v1` (KDE Plasma 6, GNOME/Mutter modernos).
  2. `zwlr_data_control_manager_v1` (Hyprland, Sway, River, Wayfire, LabWC, Niri).
  3. **X11 / XWayland** nativo mediante `x11rb` + `XFixes` sobre `CLIPBOARD` (texto e imágenes PNG/JPEG).
  - *Limitación restante:* una sesión Wayland pura sin `ext_data_control_v1` ni `zwlr_data_control_v1` **y** sin XWayland (`DISPLAY`) no permite leer/escribir el portapapeles desde procesos en segundo plano.
- **Autoconexión Wi-Fi y redes:** usa **NetworkManager** (D-Bus) como backend principal con degradación limpia a **`iwd` (`iwctl`)** y lectura de puerta de enlace desde `/proc/net/route`. Si ningún gestor Wi-Fi está activo, el demonio arranca sin errores y soporta el modo **LAN directa**. En `iwd`, la red del hotspot debe haber sido conectada/guardada previamente en `iwctl`.
- **Distribuciones sin `systemd`:** compatible con Alpine (OpenRC), Void (runit), Artix, Devuan o Gentoo mediante `./install.sh --xdg-autostart` (o `make install-autostart`) y control directo del proceso desde `controid-gui`.
- **Bandeja del sistema (`controid-tray`):** si el escritorio carece de `StatusNotifierWatcher` (por ejemplo GNOME sin AppIndicator o gestores minimalistas sin barra), la bandeja espera de forma pasiva sin bloquear ni fallar.
- **Entrada fallback por red:** requiere permiso de escritura sobre `/dev/uinput` (configurable automáticamente con `./install.sh --setup-uinput`).

---

## Estructura del proyecto

```text
Controid/
├── android/          # App Android (Kotlin, Jetpack Compose, Shizuku, BLE, HID, Stream, FUSE RPC)
├── daemon/           # Workspace Rust
│   └── crates/
│       ├── core-protocol/     # Framing binario, handshake Ed25519, control de flujo y eco
│       ├── hyprland-client/   # Portapapeles universal (Wayland ext/wlr + X11 XFixes) y uinput
│       └── linux-daemon/      # controidd, CLI (controid) y bandeja (controid-tray)
├── gui/              # Centro de Control gráfico en GTK4 / Libadwaita (controid_gui.py)
├── tools/            # Herramientas nativas C (controid-viewer y controid-mount)
├── systemd/          # Unidad systemd de usuario, lanzador .desktop y autostart XDG
├── proto/            # Especificaciones del protocolo v1, BLE GATT e IPC NDJSON
├── assets/           # Iconos oficiales de Controid
└── install.sh        # Script de compilación e instalación en Linux
```

---

## Licencia

Distribuido bajo licencia libre **[MIT](LICENSE)**. Puedes usar, modificar y compartir el proyecto libremente.
