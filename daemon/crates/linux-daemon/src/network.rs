use anyhow::{Context, Result, anyhow, bail};
use futures::StreamExt;
use std::collections::HashMap;
use std::time::Duration;
use tokio::time::Instant;
use tracing::{debug, info};
use zbus::message::Type as MessageType;
use zbus::zvariant::{OwnedObjectPath, OwnedValue, Value};
use zbus::{MatchRule, MessageStream};

pub struct NetworkManagerClient;

const NM_SERVICE: &str = "org.freedesktop.NetworkManager";
const NM_PATH: &str = "/org/freedesktop/NetworkManager";
const NM_IFACE: &str = "org.freedesktop.NetworkManager";
const NM_SETTINGS_PATH: &str = "/org/freedesktop/NetworkManager/Settings";
const NM_SETTINGS_IFACE: &str = "org.freedesktop.NetworkManager.Settings";
const NM_SETTINGS_CONN_IFACE: &str = "org.freedesktop.NetworkManager.Settings.Connection";
const NM_DEVICE_IFACE: &str = "org.freedesktop.NetworkManager.Device";
const NM_WIRELESS_IFACE: &str = "org.freedesktop.NetworkManager.Device.Wireless";
const NM_AP_IFACE: &str = "org.freedesktop.NetworkManager.AccessPoint";
const NM_ACTIVE_CONN_IFACE: &str = "org.freedesktop.NetworkManager.Connection.Active";
const NM_IP4_CONFIG_IFACE: &str = "org.freedesktop.NetworkManager.IP4Config";
const DBUS_PROPS_IFACE: &str = "org.freedesktop.DBus.Properties";
const NM_DBUS_TIMEOUT: Duration = Duration::from_secs(6);

const NM_DEVICE_TYPE_WIFI: u32 = 2;

const NM_DEVICE_STATE_UNKNOWN: u32 = 0;
const NM_DEVICE_STATE_UNMANAGED: u32 = 10;
const NM_DEVICE_STATE_UNAVAILABLE: u32 = 20;
const NM_DEVICE_STATE_ACTIVATED: u32 = 100;
const NM_DEVICE_STATE_DEACTIVATING: u32 = 110;

const NM_ACTIVE_CONN_STATE_ACTIVATED: u32 = 2;
const NM_ACTIVE_CONN_STATE_DEACTIVATING: u32 = 3;
const NM_ACTIVE_CONN_STATE_DEACTIVATED: u32 = 4;

async fn get_prop<T>(conn: &zbus::Connection, path: &str, iface: &str, prop: &str) -> Result<T>
where
    T: TryFrom<OwnedValue>,
    T::Error: std::error::Error + Send + Sync + 'static,
{
    let reply = tokio::time::timeout(
        NM_DBUS_TIMEOUT,
        conn.call_method(
            Some(NM_SERVICE),
            path,
            Some(DBUS_PROPS_IFACE),
            "Get",
            &(iface, prop),
        ),
    )
    .await
    .with_context(|| format!("D-Bus timeout reading {iface}.{prop} on {path}"))?
    .with_context(|| format!("D-Bus Get {iface}.{prop} on {path}"))?;
    let val: OwnedValue = reply.body().deserialize()?;
    T::try_from(val).map_err(|e| anyhow!("invalid type for {iface}.{prop} on {path}: {e}"))
}

async fn get_devices(conn: &zbus::Connection) -> Result<Vec<OwnedObjectPath>> {
    let reply = tokio::time::timeout(
        NM_DBUS_TIMEOUT,
        conn.call_method(Some(NM_SERVICE), NM_PATH, Some(NM_IFACE), "GetDevices", &()),
    )
    .await
    .context("NetworkManager GetDevices timed out")?
    .context("NetworkManager GetDevices failed")?;
    Ok(reply.body().deserialize()?)
}

async fn connection_matches_ssid(
    conn: &zbus::Connection,
    conn_path: &str,
    ssid_bytes: &[u8],
) -> bool {
    let reply = match conn
        .call_method(
            Some(NM_SERVICE),
            conn_path,
            Some(NM_SETTINGS_CONN_IFACE),
            "GetSettings",
            &(),
        )
        .await
    {
        Ok(r) => r,
        Err(_) => return false,
    };
    let Ok(settings) = reply
        .body()
        .deserialize::<HashMap<String, HashMap<String, OwnedValue>>>()
    else {
        return false;
    };
    let Some(wifi) = settings.get("802-11-wireless") else {
        return false;
    };
    let Some(ssid_val) = wifi.get("ssid") else {
        return false;
    };
    let Ok(arr) = <&zbus::zvariant::Array>::try_from(ssid_val) else {
        return false;
    };
    let Ok(bytes) = arr
        .iter()
        .map(u8::try_from)
        .collect::<std::result::Result<Vec<u8>, _>>()
    else {
        return false;
    };
    bytes == ssid_bytes
}

async fn wifi_device_active_on_ssid(
    conn: &zbus::Connection,
    dev_path: &str,
    ssid_bytes: &[u8],
) -> bool {
    let Ok(state) = get_prop::<u32>(conn, dev_path, NM_DEVICE_IFACE, "State").await else {
        return false;
    };
    if state != NM_DEVICE_STATE_ACTIVATED {
        return false;
    }

    if let Ok(ap_path) =
        get_prop::<OwnedObjectPath>(conn, dev_path, NM_WIRELESS_IFACE, "ActiveAccessPoint").await
        && ap_path.as_str() != "/"
        && let Ok(ap_ssid) = get_prop::<Vec<u8>>(conn, ap_path.as_str(), NM_AP_IFACE, "Ssid").await
        && ap_ssid == ssid_bytes
    {
        return true;
    }

    if let Ok(active_conn_path) =
        get_prop::<OwnedObjectPath>(conn, dev_path, NM_DEVICE_IFACE, "ActiveConnection").await
        && active_conn_path.as_str() != "/"
        && let Ok(conn_path) = get_prop::<OwnedObjectPath>(
            conn,
            active_conn_path.as_str(),
            NM_ACTIVE_CONN_IFACE,
            "Connection",
        )
        .await
    {
        return connection_matches_ssid(conn, conn_path.as_str(), ssid_bytes).await;
    }

    false
}

pub async fn is_ssid_active(conn: &zbus::Connection, ssid: &str) -> Result<bool> {
    if ssid.is_empty() {
        return Ok(false);
    }
    let ssid_bytes = ssid.as_bytes();
    for dev in get_devices(conn).await? {
        let Ok(dev_type) = get_prop::<u32>(conn, dev.as_str(), NM_DEVICE_IFACE, "DeviceType").await
        else {
            continue;
        };
        if dev_type == NM_DEVICE_TYPE_WIFI
            && wifi_device_active_on_ssid(conn, dev.as_str(), ssid_bytes).await
        {
            return Ok(true);
        }
    }
    Ok(false)
}

pub async fn get_active_wifi_gateway(conn: &zbus::Connection, ssid: &str) -> Result<Option<String>> {
    let ssid_bytes = ssid.trim().as_bytes();
    for dev in get_devices(conn).await? {
        let Ok(dev_type) = get_prop::<u32>(conn, dev.as_str(), NM_DEVICE_IFACE, "DeviceType").await
        else {
            continue;
        };
        if dev_type != NM_DEVICE_TYPE_WIFI {
            continue;
        }
        let Ok(state) = get_prop::<u32>(conn, dev.as_str(), NM_DEVICE_IFACE, "State").await else {
            continue;
        };
        if state != NM_DEVICE_STATE_ACTIVATED {
            continue;
        }
        if !ssid_bytes.is_empty() && !wifi_device_active_on_ssid(conn, dev.as_str(), ssid_bytes).await {
            continue;
        }
        let Ok(ip4_path) =
            get_prop::<OwnedObjectPath>(conn, dev.as_str(), NM_DEVICE_IFACE, "Ip4Config").await
        else {
            continue;
        };
        if ip4_path.as_str() == "/" {
            continue;
        }
        if let Ok(gw) =
            get_prop::<String>(conn, ip4_path.as_str(), NM_IP4_CONFIG_IFACE, "Gateway").await
        {
            let trimmed = gw.trim();
            if !trimmed.is_empty() {
                return Ok(Some(trimmed.to_string()));
            }
        }
        if let Ok(routes) = get_prop::<Vec<HashMap<String, OwnedValue>>>(
            conn,
            ip4_path.as_str(),
            NM_IP4_CONFIG_IFACE,
            "RouteData",
        )
        .await
        {
            for route in routes {
                if let Some(nh) = route
                    .get("next-hop")
                    .and_then(|v| <&str>::try_from(v).ok())
                    .map(str::trim)
                    .filter(|s| !s.is_empty())
                {
                    return Ok(Some(nh.to_string()));
                }
            }
        }
    }
    Ok(None)
}

async fn find_saved_connection(conn: &zbus::Connection, ssid: &str) -> Result<OwnedObjectPath> {
    let reply = conn
        .call_method(
            Some(NM_SERVICE),
            NM_SETTINGS_PATH,
            Some(NM_SETTINGS_IFACE),
            "ListConnections",
            &(),
        )
        .await
        .context("NetworkManager Settings.ListConnections failed")?;
    let conns: Vec<OwnedObjectPath> = reply.body().deserialize()?;
    let ssid_bytes = ssid.as_bytes();

    for cpath in conns {
        if connection_matches_ssid(conn, cpath.as_str(), ssid_bytes).await {
            return Ok(cpath);
        }
    }

    bail!("no NetworkManager saved Wi-Fi connection profile found for SSID '{ssid}'")
}

async fn wait_for_ready_wifi_device(
    conn: &zbus::Connection,
    ssid_bytes: &[u8],
    deadline: Instant,
) -> Result<Option<OwnedObjectPath>> {
    let state_rule = MatchRule::builder()
        .msg_type(MessageType::Signal)
        .sender(NM_SERVICE)?
        .interface(NM_DEVICE_IFACE)?
        .member("StateChanged")?
        .build();
    let mut state_stream = MessageStream::for_match_rule(state_rule, conn, Some(32))
        .await
        .ok();

    let mut saw_unavailable_wifi = false;
    loop {
        let devices = get_devices(conn).await?;
        let mut ready_dev: Option<OwnedObjectPath> = None;

        for dev in devices {
            let Ok(dev_type) =
                get_prop::<u32>(conn, dev.as_str(), NM_DEVICE_IFACE, "DeviceType").await
            else {
                continue;
            };
            if dev_type != NM_DEVICE_TYPE_WIFI {
                continue;
            }
            if wifi_device_active_on_ssid(conn, dev.as_str(), ssid_bytes).await {
                return Ok(None);
            }
            let state = get_prop::<u32>(conn, dev.as_str(), NM_DEVICE_IFACE, "State")
                .await
                .unwrap_or(NM_DEVICE_STATE_UNKNOWN);

            if matches!(
                state,
                NM_DEVICE_STATE_UNKNOWN
                    | NM_DEVICE_STATE_UNMANAGED
                    | NM_DEVICE_STATE_UNAVAILABLE
                    | NM_DEVICE_STATE_DEACTIVATING
            ) {
                saw_unavailable_wifi = true;
                debug!(device = %dev, state, "Wi-Fi interface not ready yet; waiting");
            } else if ready_dev.is_none() {
                ready_dev = Some(dev);
            }
        }

        if let Some(dev) = ready_dev {
            return Ok(Some(dev));
        }

        let now = Instant::now();
        if now >= deadline {
            if saw_unavailable_wifi {
                bail!("Wi-Fi interface remained unavailable/unmanaged after wake");
            }
            bail!("no Wi-Fi interface found in NetworkManager");
        }

        let wait_step = (deadline - now).min(Duration::from_millis(300));
        if let Some(stream) = state_stream.as_mut() {
            let _ = tokio::time::timeout(wait_step, stream.next()).await;
        } else {
            tokio::time::sleep(wait_step).await;
        }
    }
}

async fn find_ap_for_ssid(
    conn: &zbus::Connection,
    wifi_dev_path: &str,
    ssid_bytes: &[u8],
) -> Result<Option<OwnedObjectPath>> {
    let reply = conn
        .call_method(
            Some(NM_SERVICE),
            wifi_dev_path,
            Some(NM_WIRELESS_IFACE),
            "GetAccessPoints",
            &(),
        )
        .await
        .with_context(|| format!("GetAccessPoints failed on {wifi_dev_path}"))?;
    let aps: Vec<OwnedObjectPath> = reply.body().deserialize()?;

    for ap in aps {
        if let Ok(ap_ssid) = get_prop::<Vec<u8>>(conn, ap.as_str(), NM_AP_IFACE, "Ssid").await
            && ap_ssid == ssid_bytes
        {
            return Ok(Some(ap));
        }
    }
    Ok(None)
}

async fn scan_and_find_ap(
    conn: &zbus::Connection,
    wifi_dev_path: &str,
    ssid: &str,
    deadline: Instant,
) -> Result<OwnedObjectPath> {
    let ssid_bytes = ssid.as_bytes();

    let ap_rule = MatchRule::builder()
        .msg_type(MessageType::Signal)
        .sender(NM_SERVICE)?
        .path(wifi_dev_path)?
        .interface(NM_WIRELESS_IFACE)?
        .member("AccessPointAdded")?
        .build();
    let mut ap_stream = MessageStream::for_match_rule(ap_rule, conn, Some(64))
        .await
        .ok();

    if let Some(ap) = find_ap_for_ssid(conn, wifi_dev_path, ssid_bytes).await? {
        return Ok(ap);
    }

    async fn request_wifi_scan(c: &zbus::Connection, wifi_dev_path: &str) {
        let empty: HashMap<&str, Value<'_>> = HashMap::new();
        if let Err(err) = c
            .call_method(
                Some(NM_SERVICE),
                wifi_dev_path,
                Some(NM_WIRELESS_IFACE),
                "RequestScan",
                &(empty,),
            )
            .await
        {
            debug!(error = %err, "NetworkManager RequestScan returned non-fatal error");
        }
    }

    request_wifi_scan(conn, wifi_dev_path).await;
    let mut last_scan = Instant::now();

    loop {
        if let Some(ap) = find_ap_for_ssid(conn, wifi_dev_path, ssid_bytes).await? {
            return Ok(ap);
        }

        let now = Instant::now();
        if now >= deadline {
            bail!("hotspot SSID '{ssid}' not found in Wi-Fi scan");
        }

        if now.duration_since(last_scan) >= Duration::from_secs(4) {
            request_wifi_scan(conn, wifi_dev_path).await;
            last_scan = Instant::now();
        }

        let wait_step = (deadline - Instant::now()).min(Duration::from_millis(500));
        if let Some(stream) = ap_stream.as_mut() {
            if let Ok(Some(Ok(msg))) = tokio::time::timeout(wait_step, stream.next()).await
                && let Ok(ap) = msg.body().deserialize::<OwnedObjectPath>()
                && let Ok(ap_ssid) =
                    get_prop::<Vec<u8>>(conn, ap.as_str(), NM_AP_IFACE, "Ssid").await
                && ap_ssid == ssid_bytes
            {
                return Ok(ap);
            }
        } else {
            tokio::time::sleep(wait_step).await;
        }
    }
}

pub async fn connect_hotspot_ssid(
    conn: &zbus::Connection,
    ssid: &str,
    timeout_secs: u64,
) -> Result<()> {
    if ssid.trim().is_empty() {
        bail!("hotspot SSID is empty; configure device.hotspot_ssid first");
    }

    let deadline = Instant::now() + Duration::from_secs(timeout_secs.max(1));
    let ssid_bytes = ssid.as_bytes();

    let Some(wifi_dev_path) = wait_for_ready_wifi_device(conn, ssid_bytes, deadline).await? else {
        info!(ssid, "already connected to target hotspot SSID");
        return Ok(());
    };

    let saved_conn_path = find_saved_connection(conn, ssid).await?;
    let ap_path = scan_and_find_ap(conn, wifi_dev_path.as_str(), ssid, deadline).await?;

    let active_rule = MatchRule::builder()
        .msg_type(MessageType::Signal)
        .sender(NM_SERVICE)?
        .interface(NM_ACTIVE_CONN_IFACE)?
        .member("StateChanged")?
        .build();
    let mut active_stream = MessageStream::for_match_rule(active_rule, conn, Some(32))
        .await
        .ok();

    let reply = conn
        .call_method(
            Some(NM_SERVICE),
            NM_PATH,
            Some(NM_IFACE),
            "ActivateConnection",
            &(
                &*saved_conn_path,
                &*wifi_dev_path,
                &*ap_path,
            ),
        )
        .await
        .context("NetworkManager ActivateConnection failed")?;
    let active_conn_path: OwnedObjectPath = reply.body().deserialize()?;
    info!(ssid, active_connection = %active_conn_path, "activating hotspot Wi-Fi connection");

    loop {
        match get_prop::<u32>(conn, active_conn_path.as_str(), NM_ACTIVE_CONN_IFACE, "State").await
        {
            Ok(NM_ACTIVE_CONN_STATE_ACTIVATED) => {
                info!(ssid, "NetworkManager Wi-Fi hotspot connection activated");
                return Ok(());
            }
            Ok(s @ (NM_ACTIVE_CONN_STATE_DEACTIVATING | NM_ACTIVE_CONN_STATE_DEACTIVATED)) => {
                bail!("NetworkManager connection to '{ssid}' deactivated (state={s})");
            }
            Ok(_) => {}
            Err(_) => {
                if is_ssid_active(conn, ssid).await.unwrap_or(false) {
                    return Ok(());
                }
            }
        }

        let now = Instant::now();
        if now >= deadline {
            bail!("timed out waiting for NetworkManager Wi-Fi activation of '{ssid}'");
        }

        let wait_step = (deadline - now).min(Duration::from_millis(500));
        if let Some(stream) = active_stream.as_mut() {
            if let Ok(Some(Ok(msg))) = tokio::time::timeout(wait_step, stream.next()).await
                && msg
                    .header()
                    .path()
                    .is_some_and(|p| p.as_str() == active_conn_path.as_str())
                && let Ok((state, reason)) = msg.body().deserialize::<(u32, u32)>()
            {
                if state == NM_ACTIVE_CONN_STATE_ACTIVATED {
                    info!(ssid, "NetworkManager Wi-Fi hotspot connection activated");
                    return Ok(());
                }
                if state == NM_ACTIVE_CONN_STATE_DEACTIVATING
                    || state == NM_ACTIVE_CONN_STATE_DEACTIVATED
                {
                    bail!(
                        "NetworkManager connection to '{ssid}' failed (state={state}, reason={reason})"
                    );
                }
            }
        } else {
            tokio::time::sleep(wait_step).await;
        }
    }
}
