use anyhow::{Context, Result, anyhow, bail};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::env;
use std::fs;
use std::io::Read;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::net::UnixStream;
use tokio::sync::mpsc;
use tracing::debug;
use x11rb::connection::Connection as X11Connection;
use x11rb::protocol::xproto::{
    AtomEnum, ClientMessageEvent, ConnectionExt as _, EventMask, Window,
};
use x11rb::rust_connection::RustConnection;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum DesktopEnv {
    Hyprland,
    Sway,
    Niri,
    Kde,
    Gnome,
    Xfce,
    Cinnamon,
    Mate,
    Lxqt,
    River,
    Wayfire,
    Labwc,
    Other(String),
}

impl DesktopEnv {
    pub fn display_name(&self) -> &str {
        match self {
            Self::Hyprland => "Hyprland",
            Self::Sway => "Sway",
            Self::Niri => "Niri",
            Self::Kde => "KDE Plasma",
            Self::Gnome => "GNOME",
            Self::Xfce => "XFCE",
            Self::Cinnamon => "Cinnamon",
            Self::Mate => "MATE",
            Self::Lxqt => "LXQt",
            Self::River => "River",
            Self::Wayfire => "Wayfire",
            Self::Labwc => "LabWC",
            Self::Other(s) => s.as_str(),
        }
    }

    pub fn supports_window_focus(&self) -> bool {
        matches!(self, Self::Hyprland | Self::Sway | Self::Niri) || env_nonempty("DISPLAY")
    }

    pub fn supports_workspaces(&self) -> bool {
        matches!(self, Self::Hyprland | Self::Sway | Self::Niri) || env_nonempty("DISPLAY")
    }
}

pub fn detect_desktop_env() -> DesktopEnv {
    if env_nonempty("HYPRLAND_INSTANCE_SIGNATURE") {
        return DesktopEnv::Hyprland;
    }
    if env_nonempty("SWAYSOCK") {
        return DesktopEnv::Sway;
    }
    if env_nonempty("NIRI_SOCKET") {
        return DesktopEnv::Niri;
    }

    let combined = [
        env::var("XDG_CURRENT_DESKTOP").unwrap_or_default(),
        env::var("XDG_SESSION_DESKTOP").unwrap_or_default(),
        env::var("DESKTOP_SESSION").unwrap_or_default(),
    ]
    .join(":")
    .to_ascii_lowercase();

    for (needle, env_kind) in [
        ("hyprland", DesktopEnv::Hyprland),
        ("sway", DesktopEnv::Sway),
        ("niri", DesktopEnv::Niri),
        ("kde", DesktopEnv::Kde),
        ("plasma", DesktopEnv::Kde),
        ("gnome", DesktopEnv::Gnome),
        ("ubuntu", DesktopEnv::Gnome),
        ("pop", DesktopEnv::Gnome),
        ("xfce", DesktopEnv::Xfce),
        ("cinnamon", DesktopEnv::Cinnamon),
        ("mate", DesktopEnv::Mate),
        ("lxqt", DesktopEnv::Lxqt),
        ("river", DesktopEnv::River),
        ("wayfire", DesktopEnv::Wayfire),
        ("labwc", DesktopEnv::Labwc),
    ] {
        if combined.contains(needle) {
            return env_kind;
        }
    }

    let raw = env::var("XDG_CURRENT_DESKTOP")
        .or_else(|_| env::var("XDG_SESSION_DESKTOP"))
        .unwrap_or_else(|_| "Linux".to_string());
    let first = raw.split(':').next().unwrap_or("Linux").trim();
    if first.is_empty() {
        DesktopEnv::Other("Linux".to_string())
    } else {
        DesktopEnv::Other(first.to_string())
    }
}

pub fn detect_session_type() -> &'static str {
    if env_nonempty("WAYLAND_DISPLAY") {
        "wayland"
    } else if env_nonempty("DISPLAY") {
        "x11"
    } else {
        "unknown"
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DesktopAppEntry {
    pub id: String,
    pub desktop_path: PathBuf,
    pub name: String,
    pub generic_name: Option<String>,
    pub comment: Option<String>,
    pub exec: String,
    pub exec_bin: Option<String>,
    pub icon_name: Option<String>,
    pub categories: Vec<String>,
    pub keywords: Vec<String>,
    pub startup_wm_class: Option<String>,
    pub terminal: bool,
    pub dbus_activatable: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WorkspaceInfo {
    pub id: String,
    pub name: String,
    pub active: bool,
    pub windows: u32,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct RuntimeWindowState {
    pub running_counts: HashMap<String, u32>,
    pub focused_id: Option<String>,
    pub workspaces: Vec<WorkspaceInfo>,
    pub active_workspace: Option<String>,
    pub supports_window_focus: bool,
    pub supports_workspaces: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct MediaStatus {
    pub title: String,
    pub artist: String,
    pub playing: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub player: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub volume: Option<u8>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub muted: Option<bool>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DockLaunchAction {
    Activate,
    NewWindow,
    Focus,
    Close,
}

impl DockLaunchAction {
    pub fn from_str_opt(s: Option<&str>) -> Self {
        match s.map(|v| v.trim().to_ascii_lowercase()).as_deref() {
            Some("new_window") | Some("new") | Some("launch") => Self::NewWindow,
            Some("focus") => Self::Focus,
            Some("close") => Self::Close,
            _ => Self::Activate,
        }
    }
}

fn env_nonempty(key: &str) -> bool {
    env::var_os(key).is_some_and(|v| !v.is_empty())
}

fn current_desktop_tokens() -> Vec<String> {
    env::var("XDG_CURRENT_DESKTOP")
        .unwrap_or_default()
        .split(':')
        .map(|s| s.trim().to_ascii_lowercase())
        .filter(|s| !s.is_empty())
        .collect()
}

fn preferred_locales() -> Vec<String> {
    let raw = env::var("LC_ALL")
        .ok()
        .filter(|s| !s.is_empty())
        .or_else(|| env::var("LC_MESSAGES").ok().filter(|s| !s.is_empty()))
        .or_else(|| env::var("LANG").ok().filter(|s| !s.is_empty()))
        .unwrap_or_default();
    let clean = raw.split('.').next().unwrap_or("").trim();
    let clean = clean.split('@').next().unwrap_or("").trim();
    let mut locales = Vec::new();
    if !clean.is_empty() && clean != "C" && clean != "POSIX" {
        locales.push(clean.to_string());
        if let Some((lang, _)) = clean.split_once('_')
            && !lang.is_empty()
        {
            locales.push(lang.to_string());
        }
    }
    locales
}

fn xdg_application_dirs() -> Vec<PathBuf> {
    let mut dirs_out = Vec::new();
    let mut seen = HashSet::new();

    let mut push_dir = |p: PathBuf| {
        if seen.insert(p.clone()) {
            dirs_out.push(p);
        }
    };

    if let Some(data_home) = env::var_os("XDG_DATA_HOME")
        .filter(|v| !v.is_empty())
        .map(PathBuf::from)
        .or_else(dirs::data_local_dir)
    {
        push_dir(data_home.join("applications"));
        push_dir(data_home.join("flatpak/exports/share/applications"));
    }

    if let Some(home) = dirs::home_dir() {
        push_dir(home.join(".local/share/applications"));
        push_dir(home.join(".local/share/flatpak/exports/share/applications"));
        push_dir(home.join(".nix-profile/share/applications"));
    }

    let data_dirs = env::var("XDG_DATA_DIRS")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| "/usr/local/share:/usr/share".to_string());
    for entry in data_dirs.split(':').filter(|s| !s.trim().is_empty()) {
        push_dir(PathBuf::from(entry.trim()).join("applications"));
    }

    for extra in [
        "/usr/local/share/applications",
        "/usr/share/applications",
        "/var/lib/flatpak/exports/share/applications",
        "/var/lib/snapd/desktop/applications",
        "/run/current-system/sw/share/applications",
    ] {
        push_dir(PathBuf::from(extra));
    }

    dirs_out
}

fn collect_desktop_files(root: &Path, current: &Path, out: &mut Vec<(String, PathBuf)>) {
    let Ok(entries) = fs::read_dir(current) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            collect_desktop_files(root, &path, out);
        } else if path.extension().and_then(|e| e.to_str()) == Some("desktop")
            && let Ok(rel) = path.strip_prefix(root)
        {
            let id = rel.to_string_lossy().replace('/', "-");
            out.push((id, path));
        }
    }
}

pub fn executable_exists(cmd: &str) -> bool {
    let trimmed = cmd.trim();
    if trimmed.is_empty() {
        return false;
    }
    let path = Path::new(trimmed);
    if path.is_absolute() || trimmed.contains('/') {
        return fs::metadata(path)
            .map(|m| m.is_file() && (m.permissions().mode() & 0o111) != 0)
            .unwrap_or(false);
    }
    env::var_os("PATH")
        .into_iter()
        .flat_map(|paths| env::split_paths(&paths).collect::<Vec<_>>())
        .any(|dir| {
            let candidate = dir.join(trimmed);
            fs::metadata(candidate)
                .map(|m| m.is_file() && (m.permissions().mode() & 0o111) != 0)
                .unwrap_or(false)
        })
}

pub fn parse_desktop_entry(
    id: &str,
    path: &Path,
    raw: &str,
    desktop_tokens: &[String],
    locales: &[String],
) -> Option<DesktopAppEntry> {
    let mut in_desktop_entry = false;
    let mut fields: HashMap<String, String> = HashMap::new();

    for line in raw.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        if trimmed.starts_with('[') && trimmed.ends_with(']') {
            if in_desktop_entry {
                break;
            }
            in_desktop_entry = trimmed == "[Desktop Entry]";
            continue;
        }
        if !in_desktop_entry {
            continue;
        }
        if let Some((k, v)) = trimmed.split_once('=') {
            fields.insert(k.trim().to_string(), v.trim().to_string());
        }
    }

    if fields.get("Type").map(|s| s.as_str()) != Some("Application") {
        return None;
    }
    if fields.get("Hidden").is_some_and(|v| v.eq_ignore_ascii_case("true")) {
        return None;
    }
    if fields
        .get("NoDisplay")
        .is_some_and(|v| v.eq_ignore_ascii_case("true"))
    {
        return None;
    }

    if let Some(only) = fields.get("OnlyShowIn") {
        let allowed: Vec<String> = only
            .split(';')
            .map(|s| s.trim().to_ascii_lowercase())
            .filter(|s| !s.is_empty())
            .collect();
        if !desktop_tokens.is_empty()
            && !desktop_tokens.iter().any(|t| allowed.contains(t))
        {
            return None;
        }
    }

    if let Some(not_in) = fields.get("NotShowIn") {
        let blocked: Vec<String> = not_in
            .split(';')
            .map(|s| s.trim().to_ascii_lowercase())
            .filter(|s| !s.is_empty())
            .collect();
        if desktop_tokens.iter().any(|t| blocked.contains(t)) {
            return None;
        }
    }

    if let Some(try_exec) = fields.get("TryExec")
        && !executable_exists(try_exec)
    {
        return None;
    }

    let get_localized = |base: &str| -> Option<String> {
        for loc in locales {
            let key = format!("{base}[{loc}]");
            if let Some(val) = fields.get(&key).filter(|s| !s.is_empty()) {
                return Some(val.clone());
            }
        }
        fields.get(base).filter(|s| !s.is_empty()).cloned()
    };

    let name = get_localized("Name")?;
    let dbus_activatable = fields
        .get("DBusActivatable")
        .is_some_and(|v| v.eq_ignore_ascii_case("true"));
    let exec = fields.get("Exec").cloned().unwrap_or_default();
    if exec.is_empty() && !dbus_activatable {
        return None;
    }

    let terminal = fields
        .get("Terminal")
        .is_some_and(|v| v.eq_ignore_ascii_case("true"));

    let exec_bin = parse_exec_tokens(&exec).and_then(|tokens| {
        tokens.into_iter().find(|t| {
            t != "env" && !t.contains('=') && !t.starts_with('-')
        })
    }).and_then(|bin| {
        Path::new(&bin)
            .file_name()
            .and_then(|n| n.to_str())
            .map(|s| s.to_string())
    });

    let categories = fields
        .get("Categories")
        .map(|s| {
            s.split(';')
                .map(|c| c.trim().to_string())
                .filter(|c| !c.is_empty())
                .collect()
        })
        .unwrap_or_default();

    let keywords = get_localized("Keywords")
        .map(|s| {
            s.split(';')
                .map(|k| k.trim().to_string())
                .filter(|k| !k.is_empty())
                .collect()
        })
        .unwrap_or_default();

    Some(DesktopAppEntry {
        id: id.to_string(),
        desktop_path: path.to_path_buf(),
        name,
        generic_name: get_localized("GenericName"),
        comment: get_localized("Comment"),
        exec,
        exec_bin,
        icon_name: fields.get("Icon").filter(|s| !s.is_empty()).cloned(),
        categories,
        keywords,
        startup_wm_class: fields
            .get("StartupWMClass")
            .filter(|s| !s.is_empty())
            .cloned(),
        terminal,
        dbus_activatable,
    })
}

pub fn scan_desktop_apps() -> Vec<DesktopAppEntry> {
    let desktop_tokens = current_desktop_tokens();
    let locales = preferred_locales();
    let mut seen_ids = HashSet::new();
    let mut entries = Vec::new();

    for dir in xdg_application_dirs() {
        let mut found = Vec::new();
        collect_desktop_files(&dir, &dir, &mut found);
        found.sort_by(|a, b| a.0.cmp(&b.0));

        for (id, path) in found {
            if !seen_ids.insert(id.clone()) {
                continue;
            }
            let Ok(raw) = fs::read_to_string(&path) else {
                continue;
            };
            if let Some(entry) = parse_desktop_entry(&id, &path, &raw, &desktop_tokens, &locales) {
                entries.push(entry);
            }
        }
    }

    entries.sort_by(|a, b| {
        a.name
            .to_lowercase()
            .cmp(&b.name.to_lowercase())
            .then_with(|| a.id.cmp(&b.id))
    });
    entries
}

pub fn parse_exec_tokens(exec: &str) -> Option<Vec<String>> {
    let mut args = Vec::new();
    let mut cur = String::new();
    let mut in_quotes = false;
    let mut quote_char = '\0';
    let mut escaped = false;
    let mut has_token = false;

    for ch in exec.chars() {
        if escaped {
            cur.push(ch);
            escaped = false;
            has_token = true;
            continue;
        }
        if in_quotes {
            if ch == '\\' && quote_char == '"' {
                escaped = true;
            } else if ch == quote_char {
                in_quotes = false;
            } else {
                cur.push(ch);
            }
            has_token = true;
            continue;
        }
        match ch {
            '\\' => {
                escaped = true;
                has_token = true;
            }
            '"' | '\'' => {
                in_quotes = true;
                quote_char = ch;
                has_token = true;
            }
            c if c.is_whitespace() => {
                if has_token {
                    args.push(std::mem::take(&mut cur));
                    has_token = false;
                }
            }
            c => {
                cur.push(c);
                has_token = true;
            }
        }
    }
    if has_token {
        args.push(cur);
    }

    let cleaned: Vec<String> = args
        .into_iter()
        .filter_map(|arg| {
            if matches!(
                arg.as_str(),
                "%f" | "%F" | "%u" | "%U" | "%d" | "%D" | "%n" | "%N" | "%i" | "%c" | "%k" | "%v" | "%m"
            ) {
                None
            } else {
                let unescaped = arg.replace("%%", "%");
                if unescaped.is_empty() {
                    None
                } else {
                    Some(unescaped)
                }
            }
        })
        .collect();

    if cleaned.is_empty() {
        None
    } else {
        Some(cleaned)
    }
}

fn detect_terminal_emulator() -> Option<(String, &'static str)> {
    if let Ok(term) = env::var("TERMINAL")
        && executable_exists(&term)
    {
        let flag = if term.contains("gnome-terminal") {
            "--"
        } else {
            "-e"
        };
        return Some((term, flag));
    }
    for (bin, flag) in [
        ("kitty", "-e"),
        ("alacritty", "-e"),
        ("foot", "-e"),
        ("wezterm", "start"),
        ("ghostty", "-e"),
        ("konsole", "-e"),
        ("xfce4-terminal", "-e"),
        ("gnome-terminal", "--"),
        ("xterm", "-e"),
    ] {
        if executable_exists(bin) {
            return Some((bin.to_string(), flag));
        }
    }
    None
}

fn build_launch_argv(app: &DesktopAppEntry) -> Option<Vec<String>> {
    let mut argv = parse_exec_tokens(&app.exec)?;
    if app.terminal
        && let Some((term, flag)) = detect_terminal_emulator()
    {
        let mut wrapped = vec![term, flag.to_string()];
        wrapped.append(&mut argv);
        return Some(wrapped);
    }
    Some(argv)
}

fn shell_escape(s: &str) -> String {
    if !s.is_empty()
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"-_./:=@+".contains(&b))
    {
        s.to_string()
    } else {
        format!("'{}'", s.replace('\'', "'\\''"))
    }
}

fn spawn_detached(argv: &[String]) -> Result<()> {
    let (program, args) = argv.split_first().ok_or_else(|| anyhow!("empty argv"))?;
    let mut cmd = Command::new(program);
    cmd.args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    unsafe {
        cmd.pre_exec(|| {
            libc::setsid();
            Ok(())
        });
    }
    cmd.spawn()
        .with_context(|| format!("failed to spawn {program}"))?;
    Ok(())
}

pub fn launch_desktop_entry(app: &DesktopAppEntry, de: &DesktopEnv) -> Result<()> {
    if matches!(de, DesktopEnv::Hyprland)
        && executable_exists("hyprctl")
        && !app.exec.is_empty()
        && let Some(argv) = build_launch_argv(app)
    {
        let cmd_line = argv
            .iter()
            .map(|a| shell_escape(a))
            .collect::<Vec<_>>()
            .join(" ");
        let status = Command::new("hyprctl")
            .args(["dispatch", "exec", "--", &cmd_line])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
        if status.is_ok_and(|s| s.success()) {
            return Ok(());
        }
    }

    if matches!(de, DesktopEnv::Sway)
        && executable_exists("swaymsg")
        && let Some(argv) = build_launch_argv(app)
    {
        let cmd_line = argv
            .iter()
            .map(|a| shell_escape(a))
            .collect::<Vec<_>>()
            .join(" ");
        let status = Command::new("swaymsg")
            .args(["exec", "--", &cmd_line])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
        if status.is_ok_and(|s| s.success()) {
            return Ok(());
        }
    }

    if matches!(de, DesktopEnv::Niri)
        && executable_exists("niri")
        && let Some(argv) = build_launch_argv(app)
    {
        let mut cmd = Command::new("niri");
        cmd.args(["msg", "action", "spawn", "--"]);
        cmd.args(&argv);
        let status = cmd
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
        if status.is_ok_and(|s| s.success()) {
            return Ok(());
        }
    }

    if (app.dbus_activatable || app.exec.is_empty()) && executable_exists("gio") {
        let status = Command::new("gio")
            .arg("launch")
            .arg(&app.desktop_path)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
        if status.is_ok_and(|s| s.success()) {
            return Ok(());
        }
    }

    if let Some(argv) = build_launch_argv(app) {
        return spawn_detached(&argv);
    }

    if executable_exists("gio") {
        let status = Command::new("gio")
            .arg("launch")
            .arg(&app.desktop_path)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()?;
        if status.success() {
            return Ok(());
        }
    }

    bail!("unable to launch {}", app.id)
}

#[derive(Debug, Clone)]
struct RunningWindow {
    window_id: String,
    class_tokens: Vec<String>,
    title_tokens: Vec<String>,
    pid: Option<u32>,
    focused: bool,
    focus_history_id: Option<i64>,
}

fn normalize_token(s: &str) -> String {
    let lower = s.trim().to_ascii_lowercase();
    lower.strip_suffix(".desktop").unwrap_or(&lower).to_string()
}

fn strip_wrapper_suffix(s: &str) -> &str {
    s.strip_suffix("-bin")
        .or_else(|| s.strip_suffix(".sh"))
        .or_else(|| s.strip_suffix(".py"))
        .or_else(|| s.strip_suffix(".bin"))
        .unwrap_or(s)
}

fn proc_names_for_pid(pid: u32) -> Vec<String> {
    let mut names = Vec::new();
    if let Ok(cmdline) = fs::read(format!("/proc/{pid}/cmdline"))
        && let Some(first) = cmdline.split(|&b| b == 0).next()
        && !first.is_empty()
    {
        let argv0 = String::from_utf8_lossy(first);
        if let Some(base) = Path::new(argv0.as_ref()).file_name().and_then(|n| n.to_str()) {
            let norm = normalize_token(base);
            if !norm.is_empty() {
                names.push(norm);
            }
        }
    }
    if let Ok(comm) = fs::read_to_string(format!("/proc/{pid}/comm")) {
        let norm = normalize_token(&comm);
        if !norm.is_empty() && !names.contains(&norm) {
            names.push(norm);
        }
    }
    names
}

fn match_window_to_app(win: &RunningWindow, apps: &[DesktopAppEntry]) -> Option<String> {
    // Tier 1: Exact class / initialClass / app_id match against StartupWMClass or desktop ID stems.
    for token in &win.class_tokens {
        let norm = normalize_token(token);
        if norm.is_empty() {
            continue;
        }
        for app in apps {
            let id_stem = normalize_token(&app.id);
            let id_last = id_stem.rsplit('.').next().unwrap_or(&id_stem);
            if app
                .startup_wm_class
                .as_deref()
                .is_some_and(|w| normalize_token(w) == norm)
                || id_stem == norm
                || (!id_last.is_empty() && id_last == norm)
            {
                return Some(app.id.clone());
            }
        }
    }

    // Tier 2: Class / initialClass / app_id match against Exec binary basename.
    for token in &win.class_tokens {
        let norm = normalize_token(token);
        if norm.is_empty() {
            continue;
        }
        let norm_stripped = strip_wrapper_suffix(&norm);
        for app in apps {
            if let Some(bin) = app.exec_bin.as_deref() {
                let bin_norm = normalize_token(bin);
                if bin_norm == norm || strip_wrapper_suffix(&bin_norm) == norm_stripped {
                    return Some(app.id.clone());
                }
            }
        }
    }

    // Tier 3: Process argv[0] basename and /proc/<pid>/comm match.
    if let Some(pid) = win.pid {
        for proc_name in proc_names_for_pid(pid) {
            let proc_stripped = strip_wrapper_suffix(&proc_name);
            for app in apps {
                let id_stem = normalize_token(&app.id);
                let id_last = id_stem.rsplit('.').next().unwrap_or(&id_stem);
                let bin_match = app.exec_bin.as_deref().is_some_and(|b| {
                    let bn = normalize_token(b);
                    bn == proc_name || strip_wrapper_suffix(&bn) == proc_stripped
                });
                let wm_match = app
                    .startup_wm_class
                    .as_deref()
                    .is_some_and(|w| normalize_token(w) == proc_name);
                if bin_match || wm_match || id_stem == proc_name || id_last == proc_name {
                    return Some(app.id.clone());
                }
            }
        }
    }

    // Tier 4: Class token matches desktop entry Name (e.g. Flatpak "Spotify" -> com.spotify.Client).
    for token in &win.class_tokens {
        let norm = normalize_token(token);
        if norm.len() < 2 {
            continue;
        }
        for app in apps {
            if normalize_token(&app.name) == norm {
                return Some(app.id.clone());
            }
        }
    }

    // Tier 5: Title / initialTitle fallback only when class and pid failed to identify the app.
    for token in &win.title_tokens {
        let norm = normalize_token(token);
        if norm.len() < 2 {
            continue;
        }
        for app in apps {
            let id_stem = normalize_token(&app.id);
            if app
                .startup_wm_class
                .as_deref()
                .is_some_and(|w| normalize_token(w) == norm)
                || id_stem == norm
                || app
                    .exec_bin
                    .as_deref()
                    .is_some_and(|b| normalize_token(b) == norm)
                || normalize_token(&app.name) == norm
            {
                return Some(app.id.clone());
            }
        }
    }

    None
}

fn parse_hyprland_clients(
    clients: &[serde_json::Value],
    active_addr: Option<&str>,
) -> Vec<RunningWindow> {
    let mut out = Vec::new();
    for c in clients {
        if c.get("mapped").and_then(|m| m.as_bool()) == Some(false) {
            continue;
        }
        let Some(addr) = c.get("address").and_then(|a| a.as_str()).map(String::from) else {
            continue;
        };
        let mut class_tokens = Vec::new();
        for key in ["class", "initialClass"] {
            if let Some(val) = c.get(key).and_then(|v| v.as_str())
                && !val.trim().is_empty()
            {
                class_tokens.push(val.to_string());
            }
        }
        let mut title_tokens = Vec::new();
        for key in ["initialTitle", "title"] {
            if let Some(val) = c.get(key).and_then(|v| v.as_str())
                && !val.trim().is_empty()
            {
                title_tokens.push(val.to_string());
            }
        }
        let pid = c.get("pid").and_then(|p| p.as_u64()).map(|p| p as u32);
        let focus_history_id = c.get("focusHistoryID").and_then(|v| v.as_i64());
        let focused = active_addr == Some(addr.as_str());
        out.push(RunningWindow {
            window_id: addr,
            class_tokens,
            title_tokens,
            pid,
            focused,
            focus_history_id,
        });
    }
    out
}

fn query_hyprland_runtime(apps: &[DesktopAppEntry]) -> Option<RuntimeWindowState> {
    let clients_out = Command::new("hyprctl").args(["-j", "clients"]).output().ok()?;
    if !clients_out.status.success() {
        return None;
    }
    let clients: Vec<serde_json::Value> = serde_json::from_slice(&clients_out.stdout).ok()?;

    let active_addr = Command::new("hyprctl")
        .args(["-j", "activewindow"])
        .output()
        .ok()
        .and_then(|o| serde_json::from_slice::<serde_json::Value>(&o.stdout).ok())
        .and_then(|v| v.get("address").and_then(|a| a.as_str()).map(String::from));

    let active_ws_id = Command::new("hyprctl")
        .args(["-j", "activeworkspace"])
        .output()
        .ok()
        .and_then(|o| serde_json::from_slice::<serde_json::Value>(&o.stdout).ok())
        .and_then(|v| v.get("id").and_then(|i| i.as_i64()));

    let mut workspaces = Vec::new();
    if let Ok(ws_out) = Command::new("hyprctl").args(["-j", "workspaces"]).output()
        && ws_out.status.success()
        && let Ok(ws_list) = serde_json::from_slice::<Vec<serde_json::Value>>(&ws_out.stdout)
    {
        let mut parsed: Vec<(i64, WorkspaceInfo)> = ws_list
            .into_iter()
            .filter_map(|w| {
                let id = w.get("id")?.as_i64()?;
                if id <= 0 {
                    return None;
                }
                let name = w
                    .get("name")
                    .and_then(|n| n.as_str())
                    .map(String::from)
                    .unwrap_or_else(|| id.to_string());
                let windows = w.get("windows").and_then(|n| n.as_u64()).unwrap_or(0) as u32;
                Some((
                    id,
                    WorkspaceInfo {
                        id: id.to_string(),
                        name,
                        active: active_ws_id == Some(id),
                        windows,
                    },
                ))
            })
            .collect();
        parsed.sort_by_key(|(id, _)| *id);
        workspaces = parsed.into_iter().map(|(_, info)| info).collect();
    }

    let mut state = RuntimeWindowState {
        supports_window_focus: true,
        supports_workspaces: true,
        active_workspace: active_ws_id.map(|id| id.to_string()),
        workspaces,
        ..Default::default()
    };

    for win in parse_hyprland_clients(&clients, active_addr.as_deref()) {
        if let Some(app_id) = match_window_to_app(&win, apps) {
            *state.running_counts.entry(app_id.clone()).or_insert(0) += 1;
            if win.focused {
                state.focused_id = Some(app_id);
            }
        }
    }

    Some(state)
}

fn collect_sway_nodes(node: &serde_json::Value, out: &mut Vec<RunningWindow>) {
    let name_title = node
        .get("name")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(String::from);

    if let Some(app_id) = node.get("app_id").and_then(|v| v.as_str())
        && !app_id.is_empty()
    {
        let con_id = node
            .get("id")
            .and_then(|v| v.as_i64())
            .map(|id| id.to_string())
            .unwrap_or_default();
        let focused = node.get("focused").and_then(|v| v.as_bool()).unwrap_or(false);
        let pid = node.get("pid").and_then(|v| v.as_u64()).map(|p| p as u32);
        let mut tokens = vec![app_id.to_string()];
        if let Some(cls) = node
            .get("window_properties")
            .and_then(|p| p.get("class"))
            .and_then(|v| v.as_str())
        {
            tokens.push(cls.to_string());
        }
        out.push(RunningWindow {
            window_id: con_id,
            class_tokens: tokens,
            title_tokens: name_title.into_iter().collect(),
            pid,
            focused,
            focus_history_id: None,
        });
    } else if let Some(cls) = node
        .get("window_properties")
        .and_then(|p| p.get("class"))
        .and_then(|v| v.as_str())
    {
        let con_id = node
            .get("id")
            .and_then(|v| v.as_i64())
            .map(|id| id.to_string())
            .unwrap_or_default();
        let focused = node.get("focused").and_then(|v| v.as_bool()).unwrap_or(false);
        let pid = node.get("pid").and_then(|v| v.as_u64()).map(|p| p as u32);
        out.push(RunningWindow {
            window_id: con_id,
            class_tokens: vec![cls.to_string()],
            title_tokens: name_title.into_iter().collect(),
            pid,
            focused,
            focus_history_id: None,
        });
    }

    for key in ["nodes", "floating_nodes"] {
        if let Some(arr) = node.get(key).and_then(|v| v.as_array()) {
            for child in arr {
                collect_sway_nodes(child, out);
            }
        }
    }
}

fn query_sway_runtime(apps: &[DesktopAppEntry]) -> Option<RuntimeWindowState> {
    let tree_out = Command::new("swaymsg").args(["-t", "get_tree"]).output().ok()?;
    if !tree_out.status.success() {
        return None;
    }
    let root: serde_json::Value = serde_json::from_slice(&tree_out.stdout).ok()?;
    let mut windows = Vec::new();
    collect_sway_nodes(&root, &mut windows);

    let mut workspaces = Vec::new();
    let mut active_ws = None;
    if let Ok(ws_out) = Command::new("swaymsg").args(["-t", "get_workspaces"]).output()
        && ws_out.status.success()
        && let Ok(ws_arr) = serde_json::from_slice::<Vec<serde_json::Value>>(&ws_out.stdout)
    {
        for w in ws_arr {
            let Some(name) = w.get("name").and_then(|v| v.as_str()).map(String::from) else {
                continue;
            };
            let focused = w.get("focused").and_then(|v| v.as_bool()).unwrap_or(false);
            if focused {
                active_ws = Some(name.clone());
            }
            workspaces.push(WorkspaceInfo {
                id: name.clone(),
                name,
                active: focused,
                windows: 0,
            });
        }
    }

    let mut state = RuntimeWindowState {
        supports_window_focus: true,
        supports_workspaces: !workspaces.is_empty(),
        workspaces,
        active_workspace: active_ws,
        ..Default::default()
    };

    for win in windows {
        if let Some(app_id) = match_window_to_app(&win, apps) {
            *state.running_counts.entry(app_id.clone()).or_insert(0) += 1;
            if win.focused {
                state.focused_id = Some(app_id);
            }
        }
    }
    Some(state)
}

fn parse_niri_windows(wins: &[serde_json::Value]) -> Vec<RunningWindow> {
    wins.iter()
        .map(|w| {
            let id = w.get("id").and_then(|v| v.as_u64()).unwrap_or(0).to_string();
            let focused = w.get("is_focused").and_then(|v| v.as_bool()).unwrap_or(false);
            let pid = w.get("pid").and_then(|v| v.as_u64()).map(|p| p as u32);
            let mut class_tokens = Vec::new();
            if let Some(app_id) = w.get("app_id").and_then(|v| v.as_str())
                && !app_id.trim().is_empty()
            {
                class_tokens.push(app_id.to_string());
            }
            let mut title_tokens = Vec::new();
            if let Some(title) = w.get("title").and_then(|v| v.as_str())
                && !title.trim().is_empty()
            {
                title_tokens.push(title.to_string());
            }
            RunningWindow {
                window_id: id,
                class_tokens,
                title_tokens,
                pid,
                focused,
                focus_history_id: None,
            }
        })
        .collect()
}

fn query_niri_runtime(apps: &[DesktopAppEntry]) -> Option<RuntimeWindowState> {
    let win_out = Command::new("niri").args(["msg", "-j", "windows"]).output().ok()?;
    if !win_out.status.success() {
        return None;
    }
    let wins: Vec<serde_json::Value> = serde_json::from_slice(&win_out.stdout).ok()?;

    let mut workspaces = Vec::new();
    let mut active_ws = None;
    if let Ok(ws_out) = Command::new("niri").args(["msg", "-j", "workspaces"]).output()
        && ws_out.status.success()
        && let Ok(ws_arr) = serde_json::from_slice::<Vec<serde_json::Value>>(&ws_out.stdout)
    {
        for w in ws_arr {
            let Some(idx) = w.get("idx").and_then(|v| v.as_u64()) else {
                continue;
            };
            let id = idx.to_string();
            let name = w
                .get("name")
                .and_then(|v| v.as_str())
                .map(String::from)
                .unwrap_or_else(|| id.clone());
            let active = w.get("is_active").and_then(|v| v.as_bool()).unwrap_or(false);
            if active {
                active_ws = Some(id.clone());
            }
            workspaces.push(WorkspaceInfo {
                id,
                name,
                active,
                windows: 0,
            });
        }
    }

    let mut state = RuntimeWindowState {
        supports_window_focus: true,
        supports_workspaces: !workspaces.is_empty(),
        workspaces,
        active_workspace: active_ws,
        ..Default::default()
    };

    for rw in parse_niri_windows(&wins) {
        if let Some(app_id) = match_window_to_app(&rw, apps) {
            *state.running_counts.entry(app_id.clone()).or_insert(0) += 1;
            if rw.focused {
                state.focused_id = Some(app_id);
            }
        }
    }
    Some(state)
}

fn query_x11_runtime(apps: &[DesktopAppEntry]) -> Option<RuntimeWindowState> {
    let (conn, screen_num) = RustConnection::connect(None).ok()?;
    let root = conn.setup().roots.get(screen_num)?.root;

    let intern = |name: &[u8]| -> Option<u32> {
        conn.intern_atom(false, name).ok()?.reply().ok().map(|r| r.atom)
    };

    let net_client_list = intern(b"_NET_CLIENT_LIST")?;
    let net_active_window = intern(b"_NET_ACTIVE_WINDOW")?;
    let net_wm_pid = intern(b"_NET_WM_PID");
    let net_current_desktop = intern(b"_NET_CURRENT_DESKTOP");
    let net_number_of_desktops = intern(b"_NET_NUMBER_OF_DESKTOPS");
    let net_desktop_names = intern(b"_NET_DESKTOP_NAMES");

    let active_win: u32 = conn
        .get_property(false, root, net_active_window, AtomEnum::WINDOW, 0, 1)
        .ok()?
        .reply()
        .ok()
        .and_then(|r| r.value32()?.next())
        .unwrap_or(0);

    let clients_reply = conn
        .get_property(false, root, net_client_list, AtomEnum::WINDOW, 0, 4096)
        .ok()?
        .reply()
        .ok()?;
    let client_wins: Vec<Window> = clients_reply
        .value32()
        .map(|it| it.collect())
        .unwrap_or_default();

    let cur_desktop = net_current_desktop
        .and_then(|atom| {
            conn.get_property(false, root, atom, AtomEnum::CARDINAL, 0, 1)
                .ok()?
                .reply()
                .ok()?
                .value32()?
                .next()
        })
        .unwrap_or(0);

    let num_desktops = net_number_of_desktops
        .and_then(|atom| {
            conn.get_property(false, root, atom, AtomEnum::CARDINAL, 0, 1)
                .ok()?
                .reply()
                .ok()?
                .value32()?
                .next()
        })
        .unwrap_or(0)
        .min(16);

    let desktop_names: Vec<String> = net_desktop_names
        .and_then(|atom| {
            let reply = conn
                .get_property(false, root, atom, AtomEnum::ANY, 0, 4096)
                .ok()?
                .reply()
                .ok()?;
            Some(
                reply
                    .value
                    .split(|&b| b == 0)
                    .filter(|s| !s.is_empty())
                    .map(|s| String::from_utf8_lossy(s).to_string())
                    .collect(),
            )
        })
        .unwrap_or_default();

    let mut workspaces = Vec::new();
    for idx in 0..num_desktops {
        let name = desktop_names
            .get(idx as usize)
            .cloned()
            .unwrap_or_else(|| (idx + 1).to_string());
        workspaces.push(WorkspaceInfo {
            id: idx.to_string(),
            name,
            active: idx == cur_desktop,
            windows: 0,
        });
    }

    let mut state = RuntimeWindowState {
        supports_window_focus: true,
        supports_workspaces: !workspaces.is_empty(),
        active_workspace: (!workspaces.is_empty()).then(|| cur_desktop.to_string()),
        workspaces,
        ..Default::default()
    };

    for win in client_wins {
        let wm_class_bytes = conn
            .get_property(false, win, AtomEnum::WM_CLASS, AtomEnum::STRING, 0, 1024)
            .ok()
            .and_then(|c| c.reply().ok())
            .map(|r| r.value)
            .unwrap_or_default();
        let tokens: Vec<String> = wm_class_bytes
            .split(|&b| b == 0)
            .filter(|s| !s.is_empty())
            .map(|s| String::from_utf8_lossy(s).to_string())
            .collect();

        let pid = net_wm_pid.and_then(|atom| {
            conn.get_property(false, win, atom, AtomEnum::CARDINAL, 0, 1)
                .ok()?
                .reply()
                .ok()?
                .value32()?
                .next()
        });

        let rw = RunningWindow {
            window_id: win.to_string(),
            class_tokens: tokens,
            title_tokens: Vec::new(),
            pid,
            focused: win == active_win,
            focus_history_id: None,
        };
        if let Some(app_id) = match_window_to_app(&rw, apps) {
            *state.running_counts.entry(app_id.clone()).or_insert(0) += 1;
            if rw.focused {
                state.focused_id = Some(app_id);
            }
        }
    }

    Some(state)
}

fn query_proc_runtime(apps: &[DesktopAppEntry]) -> RuntimeWindowState {
    let mut state = RuntimeWindowState::default();
    let mut bin_map: HashMap<String, String> = HashMap::new();
    for app in apps {
        if let Some(ref bin) = app.exec_bin {
            let norm = normalize_token(bin);
            if norm.len() >= 2 && !matches!(norm.as_str(), "sh" | "bash" | "python" | "python3" | "env") {
                bin_map.entry(norm).or_insert_with(|| app.id.clone());
            }
        }
    }

    let Ok(proc_dir) = fs::read_dir("/proc") else {
        return state;
    };
    let my_uid = unsafe { libc::getuid() };
    for entry in proc_dir.flatten() {
        let name = entry.file_name();
        let Some(pid_str) = name.to_str() else {
            continue;
        };
        if !pid_str.bytes().all(|b| b.is_ascii_digit()) {
            continue;
        }
        let Ok(meta) = entry.metadata() else {
            continue;
        };
        #[cfg(unix)]
        {
            use std::os::unix::fs::MetadataExt;
            if meta.uid() != my_uid {
                continue;
            }
        }
        if let Ok(comm) = fs::read_to_string(entry.path().join("comm")) {
            let norm = normalize_token(&comm);
            if let Some(app_id) = bin_map.get(&norm) {
                *state.running_counts.entry(app_id.clone()).or_insert(0) += 1;
            }
        }
    }

    state
}

pub fn query_runtime_state(apps: &[DesktopAppEntry], de: &DesktopEnv) -> RuntimeWindowState {
    match de {
        DesktopEnv::Hyprland => {
            if let Some(st) = query_hyprland_runtime(apps) {
                return st;
            }
        }
        DesktopEnv::Sway => {
            if let Some(st) = query_sway_runtime(apps) {
                return st;
            }
        }
        DesktopEnv::Niri => {
            if let Some(st) = query_niri_runtime(apps) {
                return st;
            }
        }
        _ => {}
    }

    if env_nonempty("DISPLAY")
        && let Some(st) = query_x11_runtime(apps)
    {
        return st;
    }

    query_proc_runtime(apps)
}

fn select_matching_window_id(matches: &[RunningWindow], prefer_active: bool) -> Option<String> {
    if matches.is_empty() {
        return None;
    }
    if matches.len() == 1 {
        return Some(matches[0].window_id.clone());
    }
    if prefer_active {
        if let Some(active) = matches.iter().find(|w| w.focused) {
            return Some(active.window_id.clone());
        }
    } else if let Some(pos) = matches.iter().position(|w| w.focused) {
        return Some(matches[(pos + 1) % matches.len()].window_id.clone());
    }

    if let Some(most_recent) = matches
        .iter()
        .filter_map(|w| w.focus_history_id.map(|id| (id, w)))
        .min_by_key(|(id, _)| *id)
        .map(|(_, w)| w)
    {
        return Some(most_recent.window_id.clone());
    }

    Some(matches[0].window_id.clone())
}

fn find_matching_hyprland_address(
    app: &DesktopAppEntry,
    apps: &[DesktopAppEntry],
    prefer_active: bool,
) -> Option<String> {
    let out = Command::new("hyprctl").args(["-j", "clients"]).output().ok()?;
    if !out.status.success() {
        return None;
    }
    let clients: Vec<serde_json::Value> = serde_json::from_slice(&out.stdout).ok()?;
    let active_addr = Command::new("hyprctl")
        .args(["-j", "activewindow"])
        .output()
        .ok()
        .and_then(|o| serde_json::from_slice::<serde_json::Value>(&o.stdout).ok())
        .and_then(|v| v.get("address").and_then(|a| a.as_str()).map(String::from));

    let matches: Vec<RunningWindow> = parse_hyprland_clients(&clients, active_addr.as_deref())
        .into_iter()
        .filter(|rw| match_window_to_app(rw, apps).as_deref() == Some(app.id.as_str()))
        .collect();

    select_matching_window_id(&matches, prefer_active)
}

fn find_matching_sway_con_id(
    app: &DesktopAppEntry,
    apps: &[DesktopAppEntry],
    prefer_active: bool,
) -> Option<String> {
    let tree_out = Command::new("swaymsg").args(["-t", "get_tree"]).output().ok()?;
    if !tree_out.status.success() {
        return None;
    }
    let root: serde_json::Value = serde_json::from_slice(&tree_out.stdout).ok()?;
    let mut windows = Vec::new();
    collect_sway_nodes(&root, &mut windows);
    let matches: Vec<RunningWindow> = windows
        .into_iter()
        .filter(|rw| match_window_to_app(rw, apps).as_deref() == Some(app.id.as_str()))
        .collect();
    select_matching_window_id(&matches, prefer_active)
}

fn find_matching_niri_window_id(
    app: &DesktopAppEntry,
    apps: &[DesktopAppEntry],
    prefer_active: bool,
) -> Option<String> {
    let out = Command::new("niri").args(["msg", "-j", "windows"]).output().ok()?;
    if !out.status.success() {
        return None;
    }
    let wins: Vec<serde_json::Value> = serde_json::from_slice(&out.stdout).ok()?;
    let matches: Vec<RunningWindow> = parse_niri_windows(&wins)
        .into_iter()
        .filter(|rw| match_window_to_app(rw, apps).as_deref() == Some(app.id.as_str()))
        .collect();
    select_matching_window_id(&matches, prefer_active)
}

fn lua_quote(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('\'');
    for ch in s.chars() {
        match ch {
            '\\' => out.push_str("\\\\"),
            '\'' => out.push_str("\\'"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            _ => out.push(ch),
        }
    }
    out.push('\'');
    out
}

fn clean_hypr_address(addr: &str) -> &str {
    let trimmed = addr.trim();
    trimmed.strip_prefix("address:").unwrap_or(trimmed)
}

pub fn format_hypr_lua_workspace_focus(target: &str) -> String {
    let trimmed = target.trim();
    if let Ok(num) = trimmed.parse::<i64>() {
        format!("hl.dispatch(hl.dsp.focus({{ workspace = {num} }}))")
    } else {
        format!(
            "hl.dispatch(hl.dsp.focus({{ workspace = {} }}))",
            lua_quote(trimmed)
        )
    }
}

pub fn format_hypr_lua_window_focus(addr: &str) -> String {
    let clean = clean_hypr_address(addr);
    format!(
        "hl.dispatch(hl.dsp.focus({{ window = {} }}))",
        lua_quote(&format!("address:{clean}"))
    )
}

pub fn format_hypr_lua_window_close(addr: &str) -> String {
    let clean = clean_hypr_address(addr);
    format!(
        "hl.dispatch(hl.dsp.window.close({{ window = {} }}))",
        lua_quote(&format!("address:{clean}"))
    )
}

fn command_output_summary(out: &std::process::Output) -> String {
    let stdout = String::from_utf8_lossy(&out.stdout).trim().to_string();
    let stderr = String::from_utf8_lossy(&out.stderr).trim().to_string();
    match (stdout.is_empty(), stderr.is_empty()) {
        (false, false) => format!("{stdout}; {stderr}"),
        (false, true) => stdout,
        (true, false) => stderr,
        (true, true) => format!("exit code {:?}", out.status.code()),
    }
}

fn hyprctl_output_ok(out: &std::process::Output) -> bool {
    if !out.status.success() {
        return false;
    }
    let stdout_l = String::from_utf8_lossy(&out.stdout)
        .trim_start()
        .to_ascii_lowercase();
    !stdout_l.starts_with("error")
}

fn run_hyprctl_with_lua_fallback(classic_args: &[&str], lua_expr: &str) -> Result<()> {
    let classic_res = Command::new("hyprctl").args(classic_args).output();
    let classic_err = match classic_res {
        Ok(ref out) if hyprctl_output_ok(out) => return Ok(()),
        Ok(ref out) => command_output_summary(out),
        Err(err) => err.to_string(),
    };

    let eval_out = Command::new("hyprctl")
        .args(["eval", lua_expr])
        .output()
        .with_context(|| format!("failed to run hyprctl eval (after classic dispatch failed: {classic_err})"))?;

    if hyprctl_output_ok(&eval_out) {
        return Ok(());
    }

    let eval_err = command_output_summary(&eval_out);
    bail!("hyprctl dispatch failed ({classic_err}); eval fallback failed ({eval_err})")
}

pub fn hyprctl_focus_workspace(target: &str) -> Result<()> {
    let trimmed = target.trim();
    if trimmed.is_empty() {
        bail!("empty workspace target");
    }
    let lua_expr = format_hypr_lua_workspace_focus(trimmed);
    run_hyprctl_with_lua_fallback(&["dispatch", "workspace", trimmed], &lua_expr)
}

pub fn hyprctl_focus_window(addr: &str) -> Result<()> {
    let clean = clean_hypr_address(addr);
    let addr_arg = format!("address:{clean}");
    let lua_expr = format_hypr_lua_window_focus(clean);
    run_hyprctl_with_lua_fallback(&["dispatch", "focuswindow", &addr_arg], &lua_expr)
}

pub fn hyprctl_close_window(addr: &str) -> Result<()> {
    let clean = clean_hypr_address(addr);
    let addr_arg = format!("address:{clean}");
    let lua_expr = format_hypr_lua_window_close(clean);
    run_hyprctl_with_lua_fallback(&["dispatch", "closewindow", &addr_arg], &lua_expr)
}

fn run_command_checked(bin: &str, args: &[&str]) -> Result<()> {
    let out = Command::new(bin)
        .args(args)
        .output()
        .with_context(|| format!("failed to execute {bin}"))?;
    if out.status.success() {
        return Ok(());
    }
    bail!("{bin} failed: {}", command_output_summary(&out))
}

fn focus_x11_window(app: &DesktopAppEntry, apps: &[DesktopAppEntry], close: bool) -> Result<bool> {
    let Ok((conn, screen_num)) = RustConnection::connect(None) else {
        return Ok(false);
    };
    let Some(screen) = conn.setup().roots.get(screen_num) else {
        return Ok(false);
    };
    let root = screen.root;
    let Ok(net_client_list) = conn.intern_atom(false, b"_NET_CLIENT_LIST") else {
        return Ok(false);
    };
    let Ok(net_client_list) = net_client_list.reply().map(|r| r.atom) else {
        return Ok(false);
    };
    let target_atom_name = if close {
        &b"_NET_CLOSE_WINDOW"[..]
    } else {
        &b"_NET_ACTIVE_WINDOW"[..]
    };
    let Ok(target_atom) = conn.intern_atom(false, target_atom_name) else {
        return Ok(false);
    };
    let Ok(target_atom) = target_atom.reply().map(|r| r.atom) else {
        return Ok(false);
    };

    let Ok(clients_cookie) = conn.get_property(false, root, net_client_list, AtomEnum::WINDOW, 0, 4096) else {
        return Ok(false);
    };
    let Ok(clients_reply) = clients_cookie.reply() else {
        return Ok(false);
    };
    let client_wins: Vec<Window> = clients_reply
        .value32()
        .map(|it| it.collect())
        .unwrap_or_default();

    for win in client_wins {
        let wm_class_bytes = conn
            .get_property(false, win, AtomEnum::WM_CLASS, AtomEnum::STRING, 0, 1024)
            .ok()
            .and_then(|c| c.reply().ok())
            .map(|r| r.value)
            .unwrap_or_default();
        let tokens: Vec<String> = wm_class_bytes
            .split(|&b| b == 0)
            .filter(|s| !s.is_empty())
            .map(|s| String::from_utf8_lossy(s).to_string())
            .collect();
        let rw = RunningWindow {
            window_id: win.to_string(),
            class_tokens: tokens,
            title_tokens: Vec::new(),
            pid: None,
            focused: false,
            focus_history_id: None,
        };
        if match_window_to_app(&rw, apps).as_deref() == Some(app.id.as_str()) {
            let ev = ClientMessageEvent::new(
                32,
                win,
                target_atom,
                [2, 0, 0, 0, 0],
            );
            conn.send_event(
                false,
                root,
                EventMask::SUBSTRUCTURE_REDIRECT | EventMask::SUBSTRUCTURE_NOTIFY,
                ev,
            )
            .context("failed to send X11 window action event")?;
            conn.flush().context("failed to flush X11 connection")?;
            return Ok(true);
        }
    }

    Ok(false)
}

pub fn execute_app_action(
    app: &DesktopAppEntry,
    apps: &[DesktopAppEntry],
    action: DockLaunchAction,
    de: &DesktopEnv,
) -> Result<&'static str> {
    match action {
        DockLaunchAction::NewWindow => {
            launch_desktop_entry(app, de)?;
            Ok("launched")
        }
        DockLaunchAction::Close => {
            if matches!(de, DesktopEnv::Hyprland)
                && let Some(addr) = find_matching_hyprland_address(app, apps, true)
            {
                hyprctl_close_window(&addr)?;
                return Ok("closed");
            }
            if matches!(de, DesktopEnv::Sway)
                && let Some(con_id) = find_matching_sway_con_id(app, apps, true)
            {
                run_command_checked("swaymsg", &[&format!("[con_id={con_id}]"), "kill"])?;
                return Ok("closed");
            }
            if matches!(de, DesktopEnv::Niri)
                && let Some(win_id) = find_matching_niri_window_id(app, apps, true)
            {
                run_command_checked("niri", &["msg", "action", "close-window", "--id", &win_id])?;
                return Ok("closed");
            }
            if !matches!(de, DesktopEnv::Hyprland | DesktopEnv::Sway | DesktopEnv::Niri)
                && env_nonempty("DISPLAY")
                && focus_x11_window(app, apps, true)?
            {
                return Ok("closed");
            }
            bail!("window close not supported for {} on {}", app.name, de.display_name())
        }
        DockLaunchAction::Focus | DockLaunchAction::Activate => {
            if matches!(de, DesktopEnv::Hyprland)
                && let Some(addr) = find_matching_hyprland_address(app, apps, false)
            {
                hyprctl_focus_window(&addr)?;
                return Ok("focused");
            }
            if matches!(de, DesktopEnv::Sway)
                && let Some(con_id) = find_matching_sway_con_id(app, apps, false)
            {
                run_command_checked("swaymsg", &[&format!("[con_id={con_id}]"), "focus"])?;
                return Ok("focused");
            }
            if matches!(de, DesktopEnv::Niri)
                && let Some(win_id) = find_matching_niri_window_id(app, apps, false)
            {
                run_command_checked("niri", &["msg", "action", "focus-window", "--id", &win_id])?;
                return Ok("focused");
            }
            if !matches!(de, DesktopEnv::Hyprland | DesktopEnv::Sway | DesktopEnv::Niri)
                && env_nonempty("DISPLAY")
                && focus_x11_window(app, apps, false)?
            {
                return Ok("focused");
            }

            if action == DockLaunchAction::Focus {
                bail!("no running window found for {}", app.name);
            }

            launch_desktop_entry(app, de)?;
            Ok("launched")
        }
    }
}

pub fn perform_system_action(action: &str, value: Option<&str>, de: &DesktopEnv) -> Result<String> {
    match action {
        "workspace_switch" => {
            let target = value
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .ok_or_else(|| anyhow!("missing workspace value"))?;
            match de {
                DesktopEnv::Hyprland => {
                    hyprctl_focus_workspace(target)?;
                }
                DesktopEnv::Sway => {
                    run_command_checked("swaymsg", &["workspace", target])?;
                }
                DesktopEnv::Niri => {
                    run_command_checked("niri", &["msg", "action", "focus-workspace", target])?;
                }
                _ => {
                    if env_nonempty("DISPLAY")
                        && let Ok(idx) = target.parse::<u32>()
                        && let Ok((conn, screen_num)) = RustConnection::connect(None)
                        && let Some(screen) = conn.setup().roots.get(screen_num)
                        && let Ok(cookie) = conn.intern_atom(false, b"_NET_CURRENT_DESKTOP")
                        && let Ok(reply) = cookie.reply()
                    {
                        let ev = ClientMessageEvent::new(
                            32,
                            screen.root,
                            reply.atom,
                            [idx, 0, 0, 0, 0],
                        );
                        conn.send_event(
                            false,
                            screen.root,
                            EventMask::SUBSTRUCTURE_REDIRECT | EventMask::SUBSTRUCTURE_NOTIFY,
                            ev,
                        )
                        .context("failed to send X11 workspace switch event")?;
                        conn.flush().context("failed to flush X11 connection")?;
                    } else {
                        bail!("workspace switch unsupported on {}", de.display_name());
                    }
                }
            }
            Ok(format!("workspace:{target}"))
        }
        "media_play_pause" => {
            run_best_effort(&[("playerctl", &["play-pause"])])?;
            Ok("media_play_pause".to_string())
        }
        "media_next" => {
            run_best_effort(&[("playerctl", &["next"])])?;
            Ok("media_next".to_string())
        }
        "media_prev" => {
            run_best_effort(&[("playerctl", &["previous"])])?;
            Ok("media_prev".to_string())
        }
        "media_seek_fwd" => {
            run_best_effort(&[("playerctl", &["position", "10+"])])?;
            Ok("media_seek_fwd".to_string())
        }
        "media_seek_back" => {
            run_best_effort(&[("playerctl", &["position", "10-"])])?;
            Ok("media_seek_back".to_string())
        }
        "volume_up" => {
            run_best_effort(&[
                ("wpctl", &["set-volume", "@DEFAULT_AUDIO_SINK@", "5%+"]),
                ("pactl", &["set-sink-volume", "@DEFAULT_SINK@", "+5%"]),
            ])?;
            Ok("volume_up".to_string())
        }
        "volume_down" => {
            run_best_effort(&[
                ("wpctl", &["set-volume", "@DEFAULT_AUDIO_SINK@", "5%-"]),
                ("pactl", &["set-sink-volume", "@DEFAULT_SINK@", "-5%"]),
            ])?;
            Ok("volume_down".to_string())
        }
        "volume_mute" => {
            run_best_effort(&[
                ("wpctl", &["set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"]),
                ("pactl", &["set-sink-mute", "@DEFAULT_SINK@", "toggle"]),
            ])?;
            Ok("volume_mute".to_string())
        }
        "mic_mute" => {
            run_best_effort(&[
                ("wpctl", &["set-mute", "@DEFAULT_AUDIO_SOURCE@", "toggle"]),
                ("pactl", &["set-source-mute", "@DEFAULT_SOURCE@", "toggle"]),
            ])?;
            Ok("mic_mute".to_string())
        }
        "lock" => {
            run_best_effort(&[
                ("loginctl", &["lock-session"]),
                ("hyprlock", &[]),
                ("swaylock", &[]),
            ])?;
            Ok("lock".to_string())
        }
        "screenshot" => {
            take_screenshot()?;
            Ok("screenshot".to_string())
        }
        other => bail!("unsupported dock system action: {other}"),
    }
}

fn take_screenshot() -> Result<()> {
    let dir = dirs::picture_dir()
        .or_else(dirs::home_dir)
        .unwrap_or_else(env::temp_dir);
    let _ = fs::create_dir_all(&dir);
    let file_path = dir.join(format!("screenshot-{}.png", now_unix_secs()));
    let path_str = file_path.to_string_lossy().to_string();
    let dir_str = dir.to_string_lossy().to_string();
    let candidates: [(&str, &[&str]); 8] = [
        ("grim", &[path_str.as_str()]),
        ("hyprshot", &["-m", "output", "-o", dir_str.as_str()]),
        ("grimshot", &["save", "output", path_str.as_str()]),
        ("spectacle", &["-b", "-o", path_str.as_str()]),
        ("gnome-screenshot", &["-f", path_str.as_str()]),
        ("xfce4-screenshooter", &["-f", "-s", path_str.as_str()]),
        ("flameshot", &["full", "-p", path_str.as_str()]),
        ("scrot", &[path_str.as_str()]),
    ];
    run_best_effort(&candidates)
}

fn run_best_effort(candidates: &[(&str, &[&str])]) -> Result<()> {
    for (bin, args) in candidates {
        if executable_exists(bin) {
            let status = Command::new(bin)
                .args(*args)
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status();
            if status.is_ok_and(|s| s.success()) {
                return Ok(());
            }
        }
    }
    bail!("no available backend tool for system action")
}

pub fn parse_wpctl_volume(output: &str) -> (Option<u8>, Option<bool>) {
    let muted = output.contains("[MUTED]");
    let vol = output
        .split_whitespace()
        .skip_while(|t| !t.eq_ignore_ascii_case("Volume:"))
        .nth(1)
        .and_then(|num| num.parse::<f64>().ok())
        .filter(|v| v.is_finite() && *v >= 0.0)
        .map(|v| (v * 100.0).round().clamp(0.0, 100.0) as u8);
    let muted_opt = (vol.is_some() || muted).then_some(muted);
    (vol, muted_opt)
}

pub fn parse_pactl_volume(output: &str) -> Option<u8> {
    for token in output.split_whitespace() {
        if let Some(pct_str) = token.strip_suffix('%')
            && let Ok(pct) = pct_str.parse::<u16>()
        {
            return Some(pct.min(100) as u8);
        }
    }
    None
}

pub fn parse_pactl_mute(output: &str) -> Option<bool> {
    for token in output.split_whitespace() {
        if token.eq_ignore_ascii_case("yes") {
            return Some(true);
        }
        if token.eq_ignore_ascii_case("no") {
            return Some(false);
        }
    }
    None
}

fn query_audio_volume() -> (Option<u8>, Option<bool>) {
    if executable_exists("wpctl")
        && let Ok(out) = Command::new("wpctl")
            .args(["get-volume", "@DEFAULT_AUDIO_SINK@"])
            .output()
        && out.status.success()
    {
        let parsed = parse_wpctl_volume(&String::from_utf8_lossy(&out.stdout));
        if parsed.0.is_some() || parsed.1.is_some() {
            return parsed;
        }
    }

    if executable_exists("pactl") {
        let vol = Command::new("pactl")
            .args(["get-sink-volume", "@DEFAULT_SINK@"])
            .output()
            .ok()
            .filter(|o| o.status.success())
            .and_then(|o| parse_pactl_volume(&String::from_utf8_lossy(&o.stdout)));
        let muted = Command::new("pactl")
            .args(["get-sink-mute", "@DEFAULT_SINK@"])
            .output()
            .ok()
            .filter(|o| o.status.success())
            .and_then(|o| parse_pactl_mute(&String::from_utf8_lossy(&o.stdout)));
        return (vol, muted);
    }

    (None, None)
}

pub fn query_media_status() -> Option<MediaStatus> {
    let mut title = String::new();
    let mut artist = String::new();
    let mut player = None;
    let mut playing = false;

    if executable_exists("playerctl")
        && let Ok(status_out) = Command::new("playerctl").arg("status").output()
        && status_out.status.success()
    {
        let status_str = String::from_utf8_lossy(&status_out.stdout)
            .trim()
            .to_ascii_lowercase();
        playing = status_str == "playing";
        if let Ok(meta_out) = Command::new("playerctl")
            .args(["metadata", "--format", "{{title}}\n{{artist}}\n{{playerName}}"])
            .output()
            && meta_out.status.success()
        {
            let meta = String::from_utf8_lossy(&meta_out.stdout);
            let mut lines = meta.lines();
            title = lines.next().unwrap_or("").trim().to_string();
            artist = lines.next().unwrap_or("").trim().to_string();
            player = lines
                .next()
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(String::from);
        }
    }

    let (volume, muted) = query_audio_volume();
    if title.is_empty() && artist.is_empty() && volume.is_none() && muted.is_none() {
        return None;
    }

    Some(MediaStatus {
        title,
        artist,
        playing,
        player,
        volume,
        muted,
    })
}

fn detect_icon_themes() -> Vec<String> {
    let mut themes = Vec::new();
    let mut push_theme = |t: &str| {
        let trimmed = t.trim().trim_matches('"').trim_matches('\'');
        if !trimmed.is_empty() && !themes.iter().any(|existing| existing == trimmed) {
            themes.push(trimmed.to_string());
        }
    };

    if let Some(cfg) = dirs::config_dir() {
        for rel in ["gtk-4.0/settings.ini", "gtk-3.0/settings.ini", "kdeglobals", "qt6ct/qt6ct.conf", "qt5ct/qt5ct.conf"] {
            if let Ok(raw) = fs::read_to_string(cfg.join(rel)) {
                for line in raw.lines() {
                    let trimmed = line.trim();
                    if let Some((k, v)) = trimmed.split_once('=') {
                        let key = k.trim();
                        if matches!(
                            key,
                            "gtk-icon-theme-name" | "Theme" | "icon_theme"
                        ) {
                            push_theme(v);
                        }
                    }
                }
            }
        }
    }

    if executable_exists("gsettings")
        && let Ok(out) = Command::new("gsettings")
            .args(["get", "org.gnome.desktop.interface", "icon-theme"])
            .output()
        && out.status.success()
    {
        push_theme(&String::from_utf8_lossy(&out.stdout));
    }

    for fallback in ["Papirus", "Papirus-Dark", "Adwaita", "breeze", "breeze-dark", "hicolor"] {
        push_theme(fallback);
    }
    themes
}

fn icon_search_roots() -> Vec<PathBuf> {
    let mut roots = Vec::new();
    let mut seen = HashSet::new();
    let mut push = |p: PathBuf| {
        if seen.insert(p.clone()) && p.exists() {
            roots.push(p);
        }
    };

    if let Some(home) = dirs::home_dir() {
        push(home.join(".local/share/icons"));
        push(home.join(".icons"));
    }
    let data_dirs = env::var("XDG_DATA_DIRS")
        .ok()
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| "/usr/local/share:/usr/share".to_string());
    for d in data_dirs.split(':').filter(|s| !s.trim().is_empty()) {
        push(PathBuf::from(d.trim()).join("icons"));
    }
    push(PathBuf::from("/usr/share/icons"));
    push(PathBuf::from("/usr/share/pixmaps"));
    roots
}

pub fn find_icon_file(icon_name: &str) -> Option<PathBuf> {
    let trimmed = icon_name.trim();
    if trimmed.is_empty() {
        return None;
    }

    let direct = Path::new(trimmed);
    if direct.is_absolute() && direct.is_file() {
        return Some(direct.to_path_buf());
    }

    let themes = detect_icon_themes();
    let roots = icon_search_roots();
    let exts = ["png", "svg", "svgz"];
    let size_dirs = [
        "96x96",
        "128x128",
        "64x64",
        "scalable",
        "256x256",
        "48x48",
        "512x512",
        "32x32",
    ];
    let contexts = ["apps", "categories", "places", "devices", "mimetypes", "status"];

    let stem = trimmed
        .strip_suffix(".png")
        .or_else(|| trimmed.strip_suffix(".svg"))
        .or_else(|| trimmed.strip_suffix(".xpm"))
        .unwrap_or(trimmed);

    for root in &roots {
        if root.ends_with("pixmaps") {
            for ext in exts {
                let p = root.join(format!("{stem}.{ext}"));
                if p.is_file() {
                    return Some(p);
                }
            }
            continue;
        }

        for theme in &themes {
            let theme_dir = root.join(theme);
            if !theme_dir.is_dir() {
                continue;
            }
            for size in size_dirs {
                for ctx in contexts {
                    for ext in exts {
                        let candidate = theme_dir.join(size).join(ctx).join(format!("{stem}.{ext}"));
                        if candidate.is_file() {
                            return Some(candidate);
                        }
                        let candidate_alt = theme_dir.join(ctx).join(size).join(format!("{stem}.{ext}"));
                        if candidate_alt.is_file() {
                            return Some(candidate_alt);
                        }
                    }
                }
            }
        }
    }

    // Last resort: scan hicolor or pixmaps direct match
    for root in &roots {
        for ext in exts {
            let candidate = root.join(format!("{stem}.{ext}"));
            if candidate.is_file() {
                return Some(candidate);
            }
        }
    }

    None
}

fn icon_cache_dir() -> PathBuf {
    dirs::cache_dir()
        .unwrap_or_else(env::temp_dir)
        .join("controid")
        .join("dock-icons")
}

pub fn icon_fingerprint(icon_name: Option<&str>) -> Option<String> {
    let name = icon_name?.trim();
    if name.is_empty() {
        return None;
    }
    let path = find_icon_file(name)?;
    let mtime = fs::metadata(&path)
        .ok()
        .and_then(|m| m.modified().ok())
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let mut hasher = Sha256::new();
    hasher.update(path.to_string_lossy().as_bytes());
    hasher.update(b":");
    hasher.update(mtime.to_le_bytes());
    let hex_digest = hex::encode(hasher.finalize());
    Some(hex_digest[..12].to_string())
}

pub fn resolve_icon_png(icon_name: &str, size: u32) -> Option<(String, Vec<u8>)> {
    let path = find_icon_file(icon_name)?;
    let size = size.clamp(48, 160);
    let mtime = fs::metadata(&path)
        .ok()
        .and_then(|m| m.modified().ok())
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_secs())
        .unwrap_or(0);

    let mut hasher = Sha256::new();
    hasher.update(path.to_string_lossy().as_bytes());
    hasher.update(b":");
    hasher.update(mtime.to_le_bytes());
    let fp = hex::encode(hasher.finalize())[..12].to_string();

    let cache_dir = icon_cache_dir();
    let cache_file = cache_dir.join(format!("{fp}_{size}.png"));
    if let Ok(cached) = fs::read(&cache_file)
        && cached.starts_with(b"\x89PNG\r\n\x1a\n")
    {
        return Some((fp, cached));
    }

    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .map(|s| s.to_ascii_lowercase())
        .unwrap_or_default();

    let png_bytes: Option<Vec<u8>> = if ext == "png" {
        let raw = fs::read(&path).ok()?;
        if raw.len() <= 64 * 1024 {
            Some(raw)
        } else {
            rasterize_to_png(&path, size).or(Some(raw))
        }
    } else {
        rasterize_to_png(&path, size)
    };

    let bytes = png_bytes?;
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        if fs::create_dir_all(&cache_dir).is_ok() {
            let _ = fs::write(&cache_file, &bytes);
        }
        return Some((fp, bytes));
    }
    None
}

fn rasterize_to_png(path: &Path, size: u32) -> Option<Vec<u8>> {
    let size_str = size.to_string();
    if executable_exists("rsvg-convert")
        && path
            .extension()
            .and_then(|e| e.to_str())
            .is_some_and(|e| e.eq_ignore_ascii_case("svg") || e.eq_ignore_ascii_case("svgz"))
        && let Ok(out) = Command::new("rsvg-convert")
            .args(["-w", &size_str, "-h", &size_str, "-f", "png"])
            .arg(path)
            .output()
        && out.status.success()
        && out.stdout.starts_with(b"\x89PNG\r\n\x1a\n")
    {
        return Some(out.stdout);
    }

    if executable_exists("magick")
        && let Ok(out) = Command::new("magick")
            .args(["-background", "none"])
            .arg(path)
            .args(["-resize", &format!("{size}x{size}"), "png:-"])
            .output()
        && out.status.success()
        && out.stdout.starts_with(b"\x89PNG\r\n\x1a\n")
    {
        return Some(out.stdout);
    }

    if executable_exists("python3") {
        let py = "import sys; import gi; gi.require_version('GdkPixbuf','2.0'); from gi.repository import GdkPixbuf; pb = GdkPixbuf.Pixbuf.new_from_file_at_scale(sys.argv[1], int(sys.argv[2]), int(sys.argv[2]), True); ok, buf = pb.save_to_bufferv('png', [], []); sys.stdout.buffer.write(buf) if ok else sys.exit(1)";
        if let Ok(out) = Command::new("python3")
            .args(["-c", py])
            .arg(path)
            .arg(&size_str)
            .output()
            && out.status.success()
            && out.stdout.starts_with(b"\x89PNG\r\n\x1a\n")
        {
            return Some(out.stdout);
        }
    }

    None
}

pub fn spawn_desktop_event_watcher(
    de: DesktopEnv,
    tx: mpsc::Sender<()>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        if !matches!(de, DesktopEnv::Hyprland) {
            return;
        }
        let Some(sig) = env::var_os("HYPRLAND_INSTANCE_SIGNATURE").filter(|s| !s.is_empty()) else {
            return;
        };
        let runtime_dir = env::var_os("XDG_RUNTIME_DIR")
            .filter(|s| !s.is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("/tmp"));
        let sock_path = runtime_dir
            .join("hypr")
            .join(sig)
            .join(".socket2.sock");

        loop {
            match UnixStream::connect(&sock_path).await {
                Ok(stream) => {
                    let mut reader = BufReader::new(stream);
                    let mut line = String::new();
                    while let Ok(n) = reader.read_line(&mut line).await {
                        if n == 0 {
                            break;
                        }
                        if line.starts_with("activewindow>>")
                            || line.starts_with("openwindow>>")
                            || line.starts_with("closewindow>>")
                            || line.starts_with("workspace>>")
                            || line.starts_with("focusedmon>>")
                        {
                            let _ = tx.try_send(());
                        }
                        line.clear();
                    }
                }
                Err(err) => {
                    debug!(error = %err, "hyprland socket2 unavailable for dock watcher");
                    tokio::time::sleep(std::time::Duration::from_secs(5)).await;
                }
            }
        }
    })
}

pub fn now_unix_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

pub fn is_valid_png(bytes: &[u8]) -> bool {
    let mut r = std::io::Cursor::new(bytes);
    let mut sig = [0u8; 8];
    r.read_exact(&mut sig).is_ok() && sig == *b"\x89PNG\r\n\x1a\n"
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_exec_strips_field_codes_and_preserves_quoted_args() {
        let tokens = parse_exec_tokens("env FOO=1 \"/opt/My App/bin\" --flag %U --name \"a b\" %%")
            .unwrap();
        assert_eq!(
            tokens,
            vec!["env", "FOO=1", "/opt/My App/bin", "--flag", "--name", "a b", "%"]
        );
    }

    #[test]
    fn parse_desktop_entry_respects_nodisplay_and_onlyshowin() {
        let raw = "[Desktop Entry]\nType=Application\nName=HiddenApp\nExec=true\nNoDisplay=true\n";
        assert!(parse_desktop_entry("hidden.desktop", Path::new("/tmp/h.desktop"), raw, &[], &[]).is_none());

        let raw_only = "[Desktop Entry]\nType=Application\nName=HyprOnly\nName[es]=SoloHypr\nExec=true\nOnlyShowIn=Hyprland;\nCategories=Utility;System;\n";
        let hypr = vec!["hyprland".to_string()];
        let gnome = vec!["gnome".to_string()];
        let locales = vec!["es".to_string()];
        assert!(parse_desktop_entry("h.desktop", Path::new("/tmp/h.desktop"), raw_only, &gnome, &locales).is_none());
        let entry = parse_desktop_entry("h.desktop", Path::new("/tmp/h.desktop"), raw_only, &hypr, &locales).unwrap();
        assert_eq!(entry.name, "SoloHypr");
        assert_eq!(entry.categories, vec!["Utility", "System"]);
    }

    #[test]
    fn hypr_lua_formatting_handles_numeric_and_quoted_targets() {
        assert_eq!(
            format_hypr_lua_workspace_focus("1"),
            "hl.dispatch(hl.dsp.focus({ workspace = 1 }))"
        );
        assert_eq!(
            format_hypr_lua_workspace_focus(" -2 "),
            "hl.dispatch(hl.dsp.focus({ workspace = -2 }))"
        );
        assert_eq!(
            format_hypr_lua_workspace_focus("special:scratch's\\pad"),
            "hl.dispatch(hl.dsp.focus({ workspace = 'special:scratch\\'s\\\\pad' }))"
        );
        assert_eq!(
            format_hypr_lua_window_focus("0x55a1b2c3"),
            "hl.dispatch(hl.dsp.focus({ window = 'address:0x55a1b2c3' }))"
        );
        assert_eq!(
            format_hypr_lua_window_focus("address:0x55a1b2c3"),
            "hl.dispatch(hl.dsp.focus({ window = 'address:0x55a1b2c3' }))"
        );
        assert_eq!(
            format_hypr_lua_window_close("0xdeadbeef"),
            "hl.dispatch(hl.dsp.window.close({ window = 'address:0xdeadbeef' }))"
        );
    }

    #[test]
    fn match_window_and_cycling_work_across_common_apps() {
        let mk = |id: &str, name: &str, bin: &str, wm: Option<&str>| DesktopAppEntry {
            id: id.to_string(),
            desktop_path: PathBuf::from(format!("/usr/share/applications/{id}")),
            name: name.to_string(),
            generic_name: None,
            comment: None,
            exec: bin.to_string(),
            exec_bin: Some(bin.to_string()),
            icon_name: None,
            categories: Vec::new(),
            keywords: Vec::new(),
            startup_wm_class: wm.map(String::from),
            terminal: false,
            dbus_activatable: false,
        };
        let apps = vec![
            mk("brave-browser.desktop", "Brave", "brave", Some("Brave-browser")),
            mk("kitty.desktop", "kitty", "kitty", Some("kitty")),
            mk("thunar.desktop", "Thunar", "thunar", Some("Thunar")),
            mk("antigravity.desktop", "Antigravity", "antigravity", None),
            mk("org.mozilla.firefox.desktop", "Firefox", "firefox", None),
        ];

        let clients = serde_json::json!([
            {
                "address": "0x1",
                "mapped": true,
                "class": "brave-browser",
                "initialClass": "Brave-browser",
                "title": "New Tab",
                "initialTitle": "Brave",
                "focusHistoryID": 2
            },
            {
                "address": "0x2",
                "mapped": true,
                "class": "kitty",
                "initialClass": "kitty",
                "title": "firefox - nvim",
                "initialTitle": "kitty",
                "focusHistoryID": 0
            },
            {
                "address": "0x3",
                "mapped": true,
                "class": "brave-browser",
                "initialClass": "Brave-browser",
                "title": "Docs",
                "initialTitle": "Brave",
                "focusHistoryID": 1
            },
            {
                "address": "0x4",
                "mapped": false,
                "class": "thunar",
                "initialClass": "Thunar"
            },
            {
                "address": "0x5",
                "mapped": true,
                "class": "firefox",
                "initialClass": "firefox",
                "focusHistoryID": 3
            },
            {
                "address": "0x6",
                "mapped": true,
                "class": "",
                "initialClass": "",
                "initialTitle": "Antigravity",
                "focusHistoryID": 4
            }
        ]);

        let parsed = parse_hyprland_clients(clients.as_array().unwrap(), Some("0x1"));
        // Unmapped window 0x4 is skipped
        assert_eq!(parsed.len(), 5);
        assert_eq!(match_window_to_app(&parsed[0], &apps).as_deref(), Some("brave-browser.desktop"));
        // Terminal with "firefox - nvim" title still matches kitty via class
        assert_eq!(match_window_to_app(&parsed[1], &apps).as_deref(), Some("kitty.desktop"));
        assert_eq!(match_window_to_app(&parsed[3], &apps).as_deref(), Some("org.mozilla.firefox.desktop"));
        // Empty class falls back to initialTitle
        assert_eq!(match_window_to_app(&parsed[4], &apps).as_deref(), Some("antigravity.desktop"));

        // Cycling when 0x1 is active picks 0x3; prefer_active picks 0x1
        let brave_matches: Vec<RunningWindow> = parsed
            .iter()
            .filter(|w| match_window_to_app(w, &apps).as_deref() == Some("brave-browser.desktop"))
            .cloned()
            .collect();
        assert_eq!(select_matching_window_id(&brave_matches, false).as_deref(), Some("0x3"));
        assert_eq!(select_matching_window_id(&brave_matches, true).as_deref(), Some("0x1"));

        // When neither brave window is active, picks lowest focusHistoryID (0x3 has focusHistoryID 1 < 2)
        let parsed_inactive = parse_hyprland_clients(clients.as_array().unwrap(), Some("0x2"));
        let brave_inactive: Vec<RunningWindow> = parsed_inactive
            .iter()
            .filter(|w| match_window_to_app(w, &apps).as_deref() == Some("brave-browser.desktop"))
            .cloned()
            .collect();
        assert_eq!(select_matching_window_id(&brave_inactive, false).as_deref(), Some("0x3"));
    }

    #[test]
    fn audio_volume_and_system_action_validation() {
        assert_eq!(parse_wpctl_volume("Volume: 0.45"), (Some(45), Some(false)));
        assert_eq!(parse_wpctl_volume("Volume: 0.72 [MUTED]"), (Some(72), Some(true)));
        assert_eq!(parse_wpctl_volume("Volume: 1.25"), (Some(100), Some(false)));
        assert_eq!(
            parse_pactl_volume("Volume: front-left: 32768 /  50% / -18.06 dB,   front-right: 32768 /  50% / -18.06 dB"),
            Some(50)
        );
        assert_eq!(parse_pactl_mute("Mute: yes"), Some(true));
        assert_eq!(parse_pactl_mute("Mute: no"), Some(false));

        assert!(perform_system_action("workspace_switch", None, &DesktopEnv::Hyprland).is_err());
        assert!(perform_system_action("workspace_switch", Some("   "), &DesktopEnv::Hyprland).is_err());
        assert!(perform_system_action("unknown_action", None, &DesktopEnv::Hyprland).is_err());
    }
}
