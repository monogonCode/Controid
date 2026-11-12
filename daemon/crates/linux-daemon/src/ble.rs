use anyhow::{Context, Result, anyhow, bail};
use core_protocol::{IdentityKeyPair, LinkStatus};
use futures::StreamExt;
use std::collections::{BTreeMap, HashMap};
use std::pin::Pin;
use std::time::Duration;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use tokio::time::{Instant, Sleep};
use tracing::{debug, info, warn};
use zbus::fdo::{InterfacesAdded, ManagedObjects, ObjectManagerProxy, PropertiesChanged};
use zbus::message::Type as MessageType;
use zbus::proxy::CacheProperties;
use zbus::zvariant::{OwnedObjectPath, OwnedValue, Value};
use zbus::{MatchRule, MessageStream};

pub const SERVICE_UUID: &str = "0000a001-0000-1000-8000-00805f9b34fb";
pub const CHAR_STATE_TRIGGER_UUID: &str = "0000b001-0000-1000-8000-00805f9b34fb";
pub const CHAR_AUTH_CHALLENGE_UUID: &str = "0000b002-0000-1000-8000-00805f9b34fb";
pub const CHAR_LINK_STATUS_UUID: &str = "0000b003-0000-1000-8000-00805f9b34fb";
pub const HID_PROFILE_UUID: &str = "00001124-0000-1000-8000-00805f9b34fb";

const BLUEZ_SERVICE: &str = "org.bluez";
const BLUEZ_ADAPTER_IFACE: &str = "org.bluez.Adapter1";
const BLUEZ_DEVICE_IFACE: &str = "org.bluez.Device1";
const BLUEZ_BEARER_LE1_IFACE: &str = "org.bluez.Bearer.LE1";
const BLUEZ_BEARER_BREDR1_IFACE: &str = "org.bluez.Bearer.BREDR1";
const BLUEZ_GATT_CHAR_IFACE: &str = "org.bluez.GattCharacteristic1";
const DBUS_PROPS_IFACE: &str = "org.freedesktop.DBus.Properties";

const DIRECT_CONNECT_TIMEOUT: Duration = Duration::from_secs(6);
const DISCOVERY_TIMEOUT: Duration = Duration::from_secs(10);
const SERVICES_RESOLVED_TIMEOUT: Duration = Duration::from_secs(6);
const LINK_STATUS_TIMEOUT: Duration = Duration::from_secs(25);
const DISCONNECT_TIMEOUT: Duration = Duration::from_secs(2);
const DBUS_CALL_TIMEOUT: Duration = Duration::from_secs(6);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredDevice {
    pub mac: String,
    pub name: Option<String>,
    pub paired: bool,
    pub connected: bool,
}

struct GattCharacteristics {
    auth_challenge: OwnedObjectPath,
    state_trigger: OwnedObjectPath,
    link_status: OwnedObjectPath,
}

pub struct BleGattClient;

fn value_as_bool(val: &Value<'_>) -> Option<bool> {
    bool::try_from(val).ok()
}

fn value_as_str(val: &Value<'_>) -> Option<String> {
    <&str>::try_from(val).ok().map(ToOwned::to_owned)
}

fn value_as_bytes(val: &Value<'_>) -> Option<Vec<u8>> {
    let arr = <&zbus::zvariant::Array>::try_from(val).ok()?;
    arr.iter().map(|v| u8::try_from(v).ok()).collect()
}

fn value_as_str_vec(val: &Value<'_>) -> Option<Vec<String>> {
    let arr = <&zbus::zvariant::Array>::try_from(val).ok()?;
    arr.iter().map(value_as_str).collect()
}

fn extract_alias(props: &HashMap<String, OwnedValue>, mac: &str) -> Option<String> {
    let name = props
        .get("Name")
        .and_then(|v| value_as_str(v))
        .filter(|s| !s.trim().is_empty());
    let alias = props
        .get("Alias")
        .and_then(|v| value_as_str(v))
        .filter(|s| !s.trim().is_empty());

    match alias {
        Some(a)
            if !a.eq_ignore_ascii_case(mac)
                && !a.eq_ignore_ascii_case(&mac.replace(':', "-")) =>
        {
            Some(a)
        }
        _ => name,
    }
}

fn extract_alias_from_borrowed(
    props: &HashMap<&str, Value<'_>>,
    mac: &str,
) -> Option<String> {
    let name = props
        .get("Name")
        .and_then(value_as_str)
        .filter(|s| !s.trim().is_empty());
    let alias = props
        .get("Alias")
        .and_then(value_as_str)
        .filter(|s| !s.trim().is_empty());

    match alias {
        Some(a)
            if !a.eq_ignore_ascii_case(mac)
                && !a.eq_ignore_ascii_case(&mac.replace(':', "-")) =>
        {
            Some(a)
        }
        _ => name,
    }
}

fn has_uuid(uuids: &[String], target_uuid: &str) -> bool {
    uuids.iter().any(|u| u.eq_ignore_ascii_case(target_uuid))
}

fn path_matches_mac(path: &str, mac: &str) -> bool {
    if mac.is_empty() {
        return false;
    }
    let expected = format!("dev_{}", mac.replace(':', "_"));
    path.rsplit('/')
        .next()
        .is_some_and(|seg| seg.eq_ignore_ascii_case(&expected))
}

fn mac_from_device_path(path: &str) -> Option<String> {
    let seg = path.rsplit('/').next()?;
    let hex_part = seg.strip_prefix("dev_")?;
    let octets: Vec<&str> = hex_part.split('_').collect();
    if octets.len() == 6 && octets.iter().all(|o| o.len() == 2) {
        Some(octets.join(":").to_ascii_uppercase())
    } else {
        None
    }
}

pub fn map_link_status_error(status: LinkStatus) -> Option<anyhow::Error> {
    match status {
        LinkStatus::Idle | LinkStatus::Starting | LinkStatus::Active => None,
        LinkStatus::ErrUnknown => Some(anyhow!(
            "Android hotspot error (0x80): unknown error"
        )),
        LinkStatus::ErrShizukuUnavailable => Some(anyhow!(
            "Android hotspot error (0x81): Shizuku service unavailable"
        )),
        LinkStatus::ErrProvisioningFailed => Some(anyhow!(
            "Android hotspot error (0x82): carrier provisioning failed"
        )),
        LinkStatus::ErrWifiHardware => Some(anyhow!(
            "Android hotspot error (0x83): Wi-Fi hardware error"
        )),
        LinkStatus::ErrAssociationTimeout => Some(anyhow!(
            "Android hotspot error (0x84): Wi-Fi association timeout"
        )),
    }
}

async fn get_managed_objects(conn: &zbus::Connection) -> Result<ManagedObjects> {
    let om = tokio::time::timeout(
        DBUS_CALL_TIMEOUT,
        ObjectManagerProxy::builder(conn)
            .destination(BLUEZ_SERVICE)?
            .path("/")?
            .cache_properties(CacheProperties::No)
            .build(),
    )
    .await
    .context("BlueZ ObjectManagerProxy build timed out")??;
    tokio::time::timeout(DBUS_CALL_TIMEOUT, om.get_managed_objects())
        .await
        .context("BlueZ GetManagedObjects timed out")?
        .context("BlueZ GetManagedObjects failed")
}

async fn get_iface_prop<T>(
    conn: &zbus::Connection,
    path: &str,
    iface: &str,
    prop: &str,
) -> Result<T>
where
    T: TryFrom<OwnedValue>,
    T::Error: std::error::Error + Send + Sync + 'static,
{
    let reply = tokio::time::timeout(
        DBUS_CALL_TIMEOUT,
        conn.call_method(
            Some(BLUEZ_SERVICE),
            path,
            Some(DBUS_PROPS_IFACE),
            "Get",
            &(iface, prop),
        ),
    )
    .await
    .with_context(|| format!("BlueZ D-Bus timeout reading {iface}.{prop}"))??;
    let val: OwnedValue = reply.body().deserialize()?;
    T::try_from(val).map_err(|e| anyhow!("invalid BlueZ property {iface}.{prop}: {e}"))
}

async fn get_device_prop<T>(conn: &zbus::Connection, dev_path: &str, prop: &str) -> Result<T>
where
    T: TryFrom<OwnedValue>,
    T::Error: std::error::Error + Send + Sync + 'static,
{
    get_iface_prop(conn, dev_path, BLUEZ_DEVICE_IFACE, prop).await
}

fn find_adapter(objects: &ManagedObjects) -> Result<OwnedObjectPath> {
    let mut fallback: Option<OwnedObjectPath> = None;
    for (path, ifaces) in objects {
        if let Some(props) = ifaces.get(BLUEZ_ADAPTER_IFACE) {
            let powered = props
                .get("Powered")
                .and_then(|v| value_as_bool(v))
                .unwrap_or(false);
            if powered {
                return Ok(path.clone());
            }
            if fallback.is_none() {
                fallback = Some(path.clone());
            }
        }
    }
    if let Some(path) = fallback {
        bail!("Bluetooth adapter {path} is powered off");
    }
    bail!("no Bluetooth adapter found in BlueZ")
}

fn find_device_by_mac(objects: &ManagedObjects, mac: &str) -> Option<OwnedObjectPath> {
    for (path, ifaces) in objects {
        if let Some(props) = ifaces.get(BLUEZ_DEVICE_IFACE)
            && let Some(addr) = props.get("Address").and_then(|v| value_as_str(v))
            && addr.eq_ignore_ascii_case(mac)
        {
            return Some(path.clone());
        }
    }
    None
}

fn is_unknown_iface_or_method(err: &zbus::Error) -> bool {
    let msg = err.to_string();
    msg.contains("UnknownInterface")
        || msg.contains("UnknownMethod")
        || msg.contains("UnknownProperty")
        || msg.contains("No such interface")
}

async fn is_bredr_connected(conn: &zbus::Connection, dev_path: &str) -> bool {
    get_iface_prop::<bool>(conn, dev_path, BLUEZ_BEARER_BREDR1_IFACE, "Connected")
        .await
        .unwrap_or(false)
}

async fn disconnect_device_best_effort(conn: &zbus::Connection, dev_path: &str) {
    let le_call = conn.call_method(
        Some(BLUEZ_SERVICE),
        dev_path,
        Some(BLUEZ_BEARER_LE1_IFACE),
        "Disconnect",
        &(),
    );
    match tokio::time::timeout(DISCONNECT_TIMEOUT, le_call).await {
        Ok(Ok(_)) => {
            debug!(device = dev_path, "disconnected BlueZ Bearer.LE1");
            return;
        }
        Ok(Err(err)) if !is_unknown_iface_or_method(&err) => {
            debug!(device = dev_path, error = %err, "BlueZ Bearer.LE1 Disconnect ignored");
            return;
        }
        Err(_) => {
            debug!(device = dev_path, "BlueZ Bearer.LE1 Disconnect timed out");
            return;
        }
        Ok(Err(_)) => {}
    }

    if is_bredr_connected(conn, dev_path).await {
        debug!(device = dev_path, "skipping Device1.Disconnect because BR/EDR bearer is active");
        return;
    }

    let call = conn.call_method(
        Some(BLUEZ_SERVICE),
        dev_path,
        Some(BLUEZ_DEVICE_IFACE),
        "Disconnect",
        &(),
    );
    match tokio::time::timeout(DISCONNECT_TIMEOUT, call).await {
        Ok(Ok(_)) => debug!(device = dev_path, "disconnected BlueZ device"),
        Ok(Err(err)) => debug!(device = dev_path, error = %err, "BlueZ Disconnect ignored"),
        Err(_) => debug!(device = dev_path, "BlueZ Disconnect timed out"),
    }
}

async fn connect_device_with_timeout(
    conn: &zbus::Connection,
    dev_path: &str,
    timeout_dur: Duration,
) -> Result<()> {
    match get_iface_prop::<bool>(conn, dev_path, BLUEZ_BEARER_LE1_IFACE, "Connected").await {
        Ok(true) => {
            debug!(device = dev_path, "BlueZ Bearer.LE1 already connected");
            return Ok(());
        }
        Ok(false) => {
            let le_call = conn.call_method(
                Some(BLUEZ_SERVICE),
                dev_path,
                Some(BLUEZ_BEARER_LE1_IFACE),
                "Connect",
                &(),
            );
            match tokio::time::timeout(timeout_dur, le_call).await {
                Ok(Ok(_)) => return Ok(()),
                Ok(Err(err)) => {
                    let msg = err.to_string();
                    if msg.contains("AlreadyConnected") {
                        return Ok(());
                    }
                    if msg.contains("InProgress") {
                        let deadline = Instant::now() + timeout_dur;
                        while Instant::now() < deadline {
                            if get_iface_prop::<bool>(
                                conn,
                                dev_path,
                                BLUEZ_BEARER_LE1_IFACE,
                                "Connected",
                            )
                            .await
                            .unwrap_or(false)
                            {
                                return Ok(());
                            }
                            tokio::time::sleep(Duration::from_millis(150)).await;
                        }
                        bail!("BlueZ Bearer.LE1 Connect timed out waiting on InProgress for {dev_path}");
                    }
                    if !is_unknown_iface_or_method(&err) {
                        return Err(anyhow!("BlueZ Bearer.LE1.Connect failed on {dev_path}: {err}"));
                    }
                }
                Err(_) => {
                    disconnect_device_best_effort(conn, dev_path).await;
                    bail!("BlueZ Bearer.LE1.Connect timed out after {timeout_dur:?} on {dev_path}");
                }
            }
        }
        Err(_) => {}
    }

    let call = conn.call_method(
        Some(BLUEZ_SERVICE),
        dev_path,
        Some(BLUEZ_DEVICE_IFACE),
        "Connect",
        &(),
    );
    match tokio::time::timeout(timeout_dur, call).await {
        Ok(Ok(_)) => Ok(()),
        Ok(Err(err)) => {
            let msg = err.to_string();
            if msg.contains("AlreadyConnected") {
                Ok(())
            } else {
                Err(anyhow!("BlueZ Connect failed on {dev_path}: {err}"))
            }
        }
        Err(_) => {
            disconnect_device_best_effort(conn, dev_path).await;
            bail!("BlueZ Connect timed out after {timeout_dur:?} on {dev_path}")
        }
    }
}

async fn find_gatt_characteristics(
    conn: &zbus::Connection,
    dev_path: &str,
) -> Result<GattCharacteristics> {
    let objects = get_managed_objects(conn).await?;
    let prefix = format!("{dev_path}/");

    let mut auth_challenge = None;
    let mut state_trigger = None;
    let mut link_status = None;

    for (path, ifaces) in objects {
        if !path.as_str().starts_with(&prefix) {
            continue;
        }
        if let Some(props) = ifaces.get(BLUEZ_GATT_CHAR_IFACE)
            && let Some(uuid) = props.get("UUID").and_then(|v| value_as_str(v))
        {
            if uuid.eq_ignore_ascii_case(CHAR_AUTH_CHALLENGE_UUID) {
                auth_challenge = Some(path);
            } else if uuid.eq_ignore_ascii_case(CHAR_STATE_TRIGGER_UUID) {
                state_trigger = Some(path);
            } else if uuid.eq_ignore_ascii_case(CHAR_LINK_STATUS_UUID) {
                link_status = Some(path);
            }
        }
    }

    Ok(GattCharacteristics {
        auth_challenge: auth_challenge.ok_or_else(|| {
            anyhow!("missing GATT characteristic AUTH_CHALLENGE ({CHAR_AUTH_CHALLENGE_UUID})")
        })?,
        state_trigger: state_trigger.ok_or_else(|| {
            anyhow!("missing GATT characteristic STATE_TRIGGER ({CHAR_STATE_TRIGGER_UUID})")
        })?,
        link_status: link_status.ok_or_else(|| {
            anyhow!("missing GATT characteristic LINK_STATUS ({CHAR_LINK_STATUS_UUID})")
        })?,
    })
}

async fn wait_services_resolved(
    conn: &zbus::Connection,
    dev_path: &str,
    timeout_dur: Duration,
) -> Result<GattCharacteristics> {
    if let Ok(chars) = find_gatt_characteristics(conn, dev_path).await {
        return Ok(chars);
    }

    let prop_rule = MatchRule::builder()
        .msg_type(MessageType::Signal)
        .sender(BLUEZ_SERVICE)?
        .path(dev_path)?
        .interface(DBUS_PROPS_IFACE)?
        .member("PropertiesChanged")?
        .add_arg(BLUEZ_DEVICE_IFACE)?
        .build();
    let mut prop_stream = MessageStream::for_match_rule(prop_rule, conn, Some(16)).await?;

    let added_rule = MatchRule::builder()
        .msg_type(MessageType::Signal)
        .sender(BLUEZ_SERVICE)?
        .path("/")?
        .interface("org.freedesktop.DBus.ObjectManager")?
        .member("InterfacesAdded")?
        .build();
    let mut added_stream = MessageStream::for_match_rule(added_rule, conn, Some(64)).await?;

    let deadline = Instant::now() + timeout_dur;
    let prefix = format!("{dev_path}/");

    loop {
        if let Ok(chars) = find_gatt_characteristics(conn, dev_path).await {
            return Ok(chars);
        }

        let now = Instant::now();
        if now >= deadline {
            bail!("timed out waiting for Controid GATT characteristics on {dev_path}");
        }
        let step = (deadline - now).min(Duration::from_millis(350));

        tokio::select! {
            _ = tokio::time::sleep(step) => {}
            maybe_prop = prop_stream.next() => {
                match maybe_prop {
                    Some(Ok(msg)) => {
                        if let Some(sig) = PropertiesChanged::from_message(msg)
                            && let Ok(args) = sig.args()
                            && let Some(val) = args.changed_properties.get("Connected")
                            && value_as_bool(val) == Some(false)
                        {
                            bail!("BlueZ device disconnected before GATT services resolved");
                        }
                    }
                    Some(Err(err)) => {
                        debug!(error = %err, "PropertiesChanged stream error while resolving services");
                    }
                    None => bail!("BlueZ signal stream closed while resolving services"),
                }
            }
            maybe_added = added_stream.next() => {
                if let Some(Ok(msg)) = maybe_added
                    && let Some(sig) = InterfacesAdded::from_message(msg)
                    && let Ok(args) = sig.args()
                    && !args.object_path.as_str().starts_with(&prefix)
                {
                    continue;
                }
            }
        }
    }
}

async fn start_le_discovery(
    conn: &zbus::Connection,
    adapter_path: &str,
    service_uuid: &str,
) -> Result<bool> {
    let mut filter: HashMap<&str, Value<'_>> = HashMap::new();
    filter.insert("UUIDs", Value::from(vec![service_uuid.to_owned()]));
    filter.insert("Transport", Value::from("le"));
    filter.insert("DuplicateData", Value::from(true));

    let primary = tokio::time::timeout(
        DBUS_CALL_TIMEOUT,
        conn.call_method(
            Some(BLUEZ_SERVICE),
            adapter_path,
            Some(BLUEZ_ADAPTER_IFACE),
            "SetDiscoveryFilter",
            &(filter,),
        ),
    )
    .await;

    if !matches!(primary, Ok(Ok(_))) {
        let mut fallback: HashMap<&str, Value<'_>> = HashMap::new();
        fallback.insert("UUIDs", Value::from(vec![service_uuid.to_owned()]));
        fallback.insert("Transport", Value::from("le"));
        tokio::time::timeout(
            DBUS_CALL_TIMEOUT,
            conn.call_method(
                Some(BLUEZ_SERVICE),
                adapter_path,
                Some(BLUEZ_ADAPTER_IFACE),
                "SetDiscoveryFilter",
                &(fallback,),
            ),
        )
        .await
        .context("BlueZ Adapter1.SetDiscoveryFilter timed out")?
        .context("BlueZ Adapter1.SetDiscoveryFilter failed")?;
    }

    match tokio::time::timeout(
        DBUS_CALL_TIMEOUT,
        conn.call_method(
            Some(BLUEZ_SERVICE),
            adapter_path,
            Some(BLUEZ_ADAPTER_IFACE),
            "StartDiscovery",
            &(),
        ),
    )
    .await
    .context("BlueZ Adapter1.StartDiscovery timed out")?
    {
        Ok(_) => Ok(true),
        Err(err) if err.to_string().contains("InProgress") => {
            debug!(adapter = adapter_path, "BlueZ discovery already in progress");
            Ok(false)
        }
        Err(err) => Err(anyhow!("BlueZ Adapter1.StartDiscovery failed: {err}")),
    }
}

async fn stop_le_discovery(conn: &zbus::Connection, adapter_path: &str, started_by_us: bool) {
    if started_by_us {
        let _ = tokio::time::timeout(
            DISCONNECT_TIMEOUT,
            conn.call_method(
                Some(BLUEZ_SERVICE),
                adapter_path,
                Some(BLUEZ_ADAPTER_IFACE),
                "StopDiscovery",
                &(),
            ),
        )
        .await;
    }
    let empty: HashMap<&str, Value<'_>> = HashMap::new();
    let _ = tokio::time::timeout(
        DISCONNECT_TIMEOUT,
        conn.call_method(
            Some(BLUEZ_SERVICE),
            adapter_path,
            Some(BLUEZ_ADAPTER_IFACE),
            "SetDiscoveryFilter",
            &(empty,),
        ),
    )
    .await;
}

fn props_is_paired(props: &HashMap<String, OwnedValue>) -> bool {
    props
        .get("Paired")
        .and_then(|v| value_as_bool(v))
        .unwrap_or(false)
        || props
            .get("Bonded")
            .and_then(|v| value_as_bool(v))
            .unwrap_or(false)
}

fn find_paired_matching_devices(
    objects: &ManagedObjects,
    service_uuid: &str,
    skip_mac: Option<&str>,
) -> Vec<(OwnedObjectPath, String)> {
    let mut connected_first = Vec::new();
    let mut offline_paired = Vec::new();

    for (path, ifaces) in objects {
        let Some(props) = ifaces.get(BLUEZ_DEVICE_IFACE) else {
            continue;
        };
        if !props_is_paired(props) {
            continue;
        }
        let Some(uuids) = props.get("UUIDs").and_then(|v| value_as_str_vec(v)) else {
            continue;
        };
        if !has_uuid(&uuids, service_uuid) {
            continue;
        }
        let Some(addr) = props.get("Address").and_then(|v| value_as_str(v)) else {
            continue;
        };
        if skip_mac.is_some_and(|s| addr.eq_ignore_ascii_case(s)) {
            continue;
        }
        let is_conn = props
            .get("Connected")
            .and_then(|v| value_as_bool(v))
            .unwrap_or(false);
        let mac_up = addr.to_ascii_uppercase();
        if is_conn {
            connected_first.push((path.clone(), mac_up));
        } else {
            offline_paired.push((path.clone(), mac_up));
        }
    }

    connected_first.extend(offline_paired);
    connected_first
}

async fn discover_first_matching_device(
    conn: &zbus::Connection,
    service_uuid: &str,
    timeout_dur: Duration,
    skip_mac: Option<&str>,
) -> Result<(OwnedObjectPath, String, bool)> {
    let objects = get_managed_objects(conn).await?;
    let adapter_path = find_adapter(&objects)?;
    let adapter_prefix = format!("{}/", adapter_path.as_str());

    let added_rule = MatchRule::builder()
        .msg_type(MessageType::Signal)
        .sender(BLUEZ_SERVICE)?
        .path("/")?
        .interface("org.freedesktop.DBus.ObjectManager")?
        .member("InterfacesAdded")?
        .build();
    let mut added_stream = MessageStream::for_match_rule(added_rule, conn, Some(64)).await?;

    let prop_rule = MatchRule::builder()
        .msg_type(MessageType::Signal)
        .sender(BLUEZ_SERVICE)?
        .interface(DBUS_PROPS_IFACE)?
        .member("PropertiesChanged")?
        .add_arg(BLUEZ_DEVICE_IFACE)?
        .build();
    let mut prop_stream = MessageStream::for_match_rule(prop_rule, conn, Some(64)).await?;

    let started = start_le_discovery(conn, adapter_path.as_str(), service_uuid).await?;

    let result = async {
        // Check connected devices matching UUID first, then live advertisements.
        let current = get_managed_objects(conn).await?;
        for (path, ifaces) in current {
            if !path.as_str().starts_with(&adapter_prefix) {
                continue;
            }
            if let Some(props) = ifaces.get(BLUEZ_DEVICE_IFACE)
                && props.get("Connected").and_then(|v| value_as_bool(v)) == Some(true)
                && let Some(uuids) = props.get("UUIDs").and_then(|v| value_as_str_vec(v))
                && has_uuid(&uuids, service_uuid)
                && let Some(addr) = props.get("Address").and_then(|v| value_as_str(v))
                && skip_mac.is_none_or(|skip| !addr.eq_ignore_ascii_case(skip))
            {
                let paired = props_is_paired(props);
                return Ok((path, addr.to_ascii_uppercase(), paired));
            }
        }

        let deadline = Instant::now() + timeout_dur;
        loop {
            let now = Instant::now();
            if now >= deadline {
                bail!("timed out scanning BLE for Controid service UUID {service_uuid}");
            }
            let rem = deadline - now;

            tokio::select! {
                _ = tokio::time::sleep(rem) => {
                    bail!("timed out scanning BLE for Controid service UUID {service_uuid}");
                }
                maybe_added = added_stream.next() => {
                    if let Some(Ok(msg)) = maybe_added
                        && let Some(sig) = InterfacesAdded::from_message(msg)
                        && let Ok(args) = sig.args()
                        && args.object_path.as_str().starts_with(&adapter_prefix)
                        && let Some(props) = args.interfaces_and_properties.get(BLUEZ_DEVICE_IFACE)
                        && let Some(uuids) = props.get("UUIDs").and_then(value_as_str_vec)
                        && has_uuid(&uuids, service_uuid)
                    {
                        let addr = props
                            .get("Address")
                            .and_then(value_as_str)
                            .or_else(|| mac_from_device_path(args.object_path.as_str()))
                            .unwrap_or_default()
                            .to_ascii_uppercase();
                        if !addr.is_empty() {
                            let paired = props
                                .get("Paired")
                                .and_then(value_as_bool)
                                .unwrap_or(false)
                                || props
                                    .get("Bonded")
                                    .and_then(value_as_bool)
                                    .unwrap_or(false);
                            return Ok((args.object_path.into(), addr, paired));
                        }
                    }
                }
                maybe_prop = prop_stream.next() => {
                    if let Some(Ok(msg)) = maybe_prop
                        && let Some(path) = msg.header().path().map(|p| p.to_owned())
                        && path.as_str().starts_with(&adapter_prefix)
                        && let Some(sig) = PropertiesChanged::from_message(msg)
                        && let Ok(args) = sig.args()
                    {
                        let matches_uuid = args
                            .changed_properties
                            .get("UUIDs")
                            .and_then(value_as_str_vec)
                            .is_some_and(|u| has_uuid(&u, service_uuid));
                        let rssi_or_connected = args.changed_properties.contains_key("RSSI")
                            || args.changed_properties.contains_key("ServiceData");
                        if matches_uuid {
                            let addr = get_device_prop::<String>(conn, path.as_str(), "Address")
                                .await
                                .ok()
                                .or_else(|| mac_from_device_path(path.as_str()))
                                .unwrap_or_default()
                                .to_ascii_uppercase();
                            if !addr.is_empty() {
                                let paired = get_device_prop::<bool>(conn, path.as_str(), "Paired")
                                    .await
                                    .unwrap_or(false);
                                return Ok((OwnedObjectPath::from(path), addr, paired));
                            }
                        } else if rssi_or_connected
                            && let Ok(uuids) = get_device_prop::<Vec<String>>(conn, path.as_str(), "UUIDs").await
                            && has_uuid(&uuids, service_uuid)
                        {
                            let addr = get_device_prop::<String>(conn, path.as_str(), "Address")
                                .await
                                .ok()
                                .or_else(|| mac_from_device_path(path.as_str()))
                                .unwrap_or_default()
                                .to_ascii_uppercase();
                            if !addr.is_empty() {
                                let paired = get_device_prop::<bool>(conn, path.as_str(), "Paired")
                                    .await
                                    .unwrap_or(false);
                                return Ok((OwnedObjectPath::from(path), addr, paired));
                            }
                        }
                    }
                }
            }
        }
    }
    .await;

    stop_le_discovery(conn, adapter_path.as_str(), started).await;
    result
}

async fn try_connect_and_resolve(
    conn: &zbus::Connection,
    dev_path: &OwnedObjectPath,
    timeout_dur: Duration,
) -> Result<GattCharacteristics> {
    connect_device_with_timeout(conn, dev_path.as_str(), timeout_dur).await?;
    match wait_services_resolved(conn, dev_path.as_str(), SERVICES_RESOLVED_TIMEOUT).await {
        Ok(chars) => Ok(chars),
        Err(err) => {
            disconnect_device_best_effort(conn, dev_path.as_str()).await;
            Err(err)
        }
    }
}

async fn resolve_and_connect_hybrid(
    conn: &zbus::Connection,
    mac: &str,
    service_uuid: &str,
) -> Result<(OwnedObjectPath, String, bool, GattCharacteristics)> {
    let trimmed_mac = mac.trim();
    let objects = get_managed_objects(conn).await?;

    if !trimmed_mac.is_empty() {
        if let Some(dev_path) = find_device_by_mac(&objects, trimmed_mac) {
            let paired = get_device_prop::<bool>(conn, dev_path.as_str(), "Paired")
                .await
                .unwrap_or(false);
            debug!(mac = trimmed_mac, path = %dev_path, paired, "attempting direct BLE connect to persisted MAC");
            match try_connect_and_resolve(conn, &dev_path, DIRECT_CONNECT_TIMEOUT).await {
                Ok(chars) => {
                    return Ok((dev_path, trimmed_mac.to_ascii_uppercase(), paired, chars));
                }
                Err(err) => {
                    warn!(mac = trimmed_mac, error = %err, "direct BLE connect failed; checking paired BlueZ cache / discovery");
                }
            }
        } else {
            debug!(mac = trimmed_mac, "persisted MAC not in BlueZ cache; checking paired BlueZ cache / discovery");
        }
    }

    let skip_mac = (!trimmed_mac.is_empty()).then_some(trimmed_mac);
    for (dev_path, paired_mac) in find_paired_matching_devices(&objects, service_uuid, skip_mac) {
        info!(mac = %paired_mac, path = %dev_path, "trying paired BlueZ Controid device");
        match try_connect_and_resolve(conn, &dev_path, DIRECT_CONNECT_TIMEOUT).await {
            Ok(chars) => return Ok((dev_path, paired_mac, true, chars)),
            Err(err) => {
                warn!(mac = %paired_mac, error = %err, "paired cached BLE connect failed");
            }
        }
    }

    let (dev_path, active_mac, paired) =
        discover_first_matching_device(conn, service_uuid, DISCOVERY_TIMEOUT, skip_mac).await?;
    info!(mac = %active_mac, path = %dev_path, paired, "discovered Controid BLE device");

    let chars = try_connect_and_resolve(conn, &dev_path, DIRECT_CONNECT_TIMEOUT).await?;
    Ok((dev_path, active_mac, paired, chars))
}

async fn read_char_value(conn: &zbus::Connection, char_path: &str) -> Result<Vec<u8>> {
    let opts: HashMap<&str, Value<'_>> = HashMap::new();
    let reply = tokio::time::timeout(
        DBUS_CALL_TIMEOUT,
        conn.call_method(
            Some(BLUEZ_SERVICE),
            char_path,
            Some(BLUEZ_GATT_CHAR_IFACE),
            "ReadValue",
            &(opts,),
        ),
    )
    .await
    .with_context(|| format!("GATT ReadValue timed out on {char_path}"))?
    .with_context(|| format!("GATT ReadValue failed on {char_path}"))?;
    Ok(reply.body().deserialize()?)
}

async fn write_char_value(
    conn: &zbus::Connection,
    char_path: &str,
    value: Vec<u8>,
) -> Result<()> {
    let opts: HashMap<&str, Value<'_>> = HashMap::new();
    tokio::time::timeout(
        DBUS_CALL_TIMEOUT,
        conn.call_method(
            Some(BLUEZ_SERVICE),
            char_path,
            Some(BLUEZ_GATT_CHAR_IFACE),
            "WriteValue",
            &(value, opts),
        ),
    )
    .await
    .with_context(|| format!("GATT WriteValue timed out on {char_path}"))?
    .with_context(|| format!("GATT WriteValue failed on {char_path}"))?;
    Ok(())
}

async fn authenticate_gatt(
    conn: &zbus::Connection,
    auth_char_path: &str,
    signer: &IdentityKeyPair,
) -> Result<()> {
    let nonce = read_char_value(conn, auth_char_path).await?;
    if nonce.len() != 16 {
        bail!(
            "AUTH_CHALLENGE expected 16-byte nonce, received {} bytes",
            nonce.len()
        );
    }
    let signature = signer.sign(&nonce);
    let mut payload = Vec::with_capacity(96);
    payload.extend_from_slice(&signer.public_key_bytes());
    payload.extend_from_slice(&signature);
    write_char_value(conn, auth_char_path, payload).await?;
    Ok(())
}

pub async fn trigger_hotspot_and_wait(
    conn: &zbus::Connection,
    mac: &str,
    service_uuid: &str,
    signer: &IdentityKeyPair,
) -> Result<(String, bool)> {
    let (dev_path, active_mac, active_paired, chars) =
        resolve_and_connect_hybrid(conn, mac, service_uuid).await?;

    let gatt_result = async {
        authenticate_gatt(conn, chars.auth_challenge.as_str(), signer).await?;

        let status_rule = MatchRule::builder()
            .msg_type(MessageType::Signal)
            .sender(BLUEZ_SERVICE)?
            .path(chars.link_status.as_str())?
            .interface(DBUS_PROPS_IFACE)?
            .member("PropertiesChanged")?
            .add_arg(BLUEZ_GATT_CHAR_IFACE)?
            .build();
        let mut status_stream = MessageStream::for_match_rule(status_rule, conn, Some(16)).await?;

        let disc_rule = MatchRule::builder()
            .msg_type(MessageType::Signal)
            .sender(BLUEZ_SERVICE)?
            .path(dev_path.as_str())?
            .interface(DBUS_PROPS_IFACE)?
            .member("PropertiesChanged")?
            .add_arg(BLUEZ_DEVICE_IFACE)?
            .build();
        let mut disc_stream = MessageStream::for_match_rule(disc_rule, conn, Some(8)).await?;

        tokio::time::timeout(
            DBUS_CALL_TIMEOUT,
            conn.call_method(
                Some(BLUEZ_SERVICE),
                chars.link_status.as_str(),
                Some(BLUEZ_GATT_CHAR_IFACE),
                "StartNotify",
                &(),
            ),
        )
        .await
        .context("GATT StartNotify timed out on LINK_STATUS")?
        .context("GATT StartNotify on LINK_STATUS failed")?;

        write_char_value(conn, chars.state_trigger.as_str(), vec![0x01]).await?;

        let deadline = Instant::now() + LINK_STATUS_TIMEOUT;
        let mut poll_interval = tokio::time::interval(Duration::from_secs(3));
        poll_interval.tick().await;

        let check_status_byte = |code: u8| -> Option<Result<(String, bool)>> {
            match LinkStatus::try_from(code) {
                Ok(LinkStatus::Active) => Some(Ok((active_mac.clone(), active_paired))),
                Ok(LinkStatus::Idle | LinkStatus::Starting) => {
                    debug!(status = code, "LINK_STATUS intermediate state");
                    None
                }
                Ok(err_status) => map_link_status_error(err_status).map(Err),
                Err(_) => Some(Err(anyhow!("unknown LINK_STATUS byte 0x{code:02x}"))),
            }
        };

        let outcome = loop {
            let now = Instant::now();
            if now >= deadline {
                break Err(anyhow!("timed out waiting for LINK_STATUS ACTIVE (0x02)"));
            }
            let rem = deadline - now;

            tokio::select! {
                _ = tokio::time::sleep(rem) => {
                    break Err(anyhow!("timed out waiting for LINK_STATUS ACTIVE (0x02)"));
                }
                _ = poll_interval.tick() => {
                    if let Ok(bytes) = read_char_value(conn, chars.link_status.as_str()).await
                        && let Some(&code) = bytes.first()
                        && let Some(res) = check_status_byte(code)
                    {
                        break res;
                    }
                }
                maybe_msg = status_stream.next() => {
                    match maybe_msg {
                        Some(Ok(msg)) => {
                            if let Some(sig) = PropertiesChanged::from_message(msg)
                                && let Ok(args) = sig.args()
                                && let Some(val) = args.changed_properties.get("Value")
                                && let Some(bytes) = value_as_bytes(val)
                                && let Some(&code) = bytes.first()
                                && let Some(res) = check_status_byte(code)
                            {
                                break res;
                            }
                        }
                        Some(Err(err)) => {
                            debug!(error = %err, "LINK_STATUS stream error");
                        }
                        None => break Err(anyhow!("LINK_STATUS D-Bus stream closed unexpectedly")),
                    }
                }
                maybe_disc = disc_stream.next() => {
                    if let Some(Ok(msg)) = maybe_disc
                        && let Some(sig) = PropertiesChanged::from_message(msg)
                        && let Ok(args) = sig.args()
                        && let Some(val) = args.changed_properties.get("Connected")
                        && value_as_bool(val) == Some(false)
                    {
                        break Err(anyhow!("BLE device disconnected while waiting for hotspot start"));
                    }
                }
            }
        };

        let _ = tokio::time::timeout(
            DISCONNECT_TIMEOUT,
            conn.call_method(
                Some(BLUEZ_SERVICE),
                chars.link_status.as_str(),
                Some(BLUEZ_GATT_CHAR_IFACE),
                "StopNotify",
                &(),
            ),
        )
        .await;

        outcome
    }
    .await;

    disconnect_device_best_effort(conn, dev_path.as_str()).await;
    gatt_result
}

pub async fn stop_hotspot(
    conn: &zbus::Connection,
    mac: &str,
    service_uuid: &str,
    signer: &IdentityKeyPair,
) -> Result<()> {
    let (dev_path, _active_mac, _paired, chars) =
        resolve_and_connect_hybrid(conn, mac, service_uuid).await?;

    let gatt_result = async {
        authenticate_gatt(conn, chars.auth_challenge.as_str(), signer).await?;
        write_char_value(conn, chars.state_trigger.as_str(), vec![0x00]).await?;
        Ok(())
    }
    .await;

    disconnect_device_best_effort(conn, dev_path.as_str()).await;
    gatt_result
}

pub async fn discover_controid_devices(
    conn: &zbus::Connection,
    service_uuid: &str,
    timeout_secs: u64,
) -> Result<Vec<DiscoveredDevice>> {
    let objects = get_managed_objects(conn).await?;
    let adapter_path = find_adapter(&objects)?;
    let adapter_prefix = format!("{}/", adapter_path.as_str());

    let added_rule = MatchRule::builder()
        .msg_type(MessageType::Signal)
        .sender(BLUEZ_SERVICE)?
        .path("/")?
        .interface("org.freedesktop.DBus.ObjectManager")?
        .member("InterfacesAdded")?
        .build();
    let mut added_stream = MessageStream::for_match_rule(added_rule, conn, Some(64)).await?;

    let prop_rule = MatchRule::builder()
        .msg_type(MessageType::Signal)
        .sender(BLUEZ_SERVICE)?
        .interface(DBUS_PROPS_IFACE)?
        .member("PropertiesChanged")?
        .add_arg(BLUEZ_DEVICE_IFACE)?
        .build();
    let mut prop_stream = MessageStream::for_match_rule(prop_rule, conn, Some(64)).await?;

    let started = start_le_discovery(conn, adapter_path.as_str(), service_uuid).await?;
    let mut found: BTreeMap<String, DiscoveredDevice> = BTreeMap::new();

    let scan_res: Result<()> = async {
        let initial = get_managed_objects(conn).await?;
        for (path, ifaces) in initial {
            if !path.as_str().starts_with(&adapter_prefix) {
                continue;
            }
            if let Some(props) = ifaces.get(BLUEZ_DEVICE_IFACE)
                && let Some(uuids) = props.get("UUIDs").and_then(|v| value_as_str_vec(v))
                && has_uuid(&uuids, service_uuid)
                && let Some(addr) = props.get("Address").and_then(|v| value_as_str(v))
            {
                let mac = addr.to_ascii_uppercase();
                let name = extract_alias(props, &mac);
                let paired = props_is_paired(props);
                let connected = props
                    .get("Connected")
                    .and_then(|v| value_as_bool(v))
                    .unwrap_or(false);
                found.insert(
                    mac.clone(),
                    DiscoveredDevice {
                        mac,
                        name,
                        paired,
                        connected,
                    },
                );
            }
        }

        let deadline = Instant::now() + Duration::from_secs(timeout_secs.max(1));
        loop {
            let now = Instant::now();
            if now >= deadline {
                break;
            }
            let rem = deadline - now;
            tokio::select! {
                _ = tokio::time::sleep(rem) => break,
                maybe_added = added_stream.next() => {
                    if let Some(Ok(msg)) = maybe_added
                        && let Some(sig) = InterfacesAdded::from_message(msg)
                        && let Ok(args) = sig.args()
                        && args.object_path.as_str().starts_with(&adapter_prefix)
                        && let Some(props) = args.interfaces_and_properties.get(BLUEZ_DEVICE_IFACE)
                        && let Some(uuids) = props.get("UUIDs").and_then(value_as_str_vec)
                        && has_uuid(&uuids, service_uuid)
                    {
                        let mac = props
                            .get("Address")
                            .and_then(value_as_str)
                            .or_else(|| mac_from_device_path(args.object_path.as_str()))
                            .unwrap_or_default()
                            .to_ascii_uppercase();
                        if !mac.is_empty() {
                            let name = extract_alias_from_borrowed(props, &mac);
                            let paired = props.get("Paired").and_then(value_as_bool).unwrap_or(false)
                                || props.get("Bonded").and_then(value_as_bool).unwrap_or(false);
                            let connected = props.get("Connected").and_then(value_as_bool).unwrap_or(false);
                            found
                                .entry(mac.clone())
                                .and_modify(|e| {
                                    if e.name.is_none() && name.is_some() {
                                        e.name = name.clone();
                                    }
                                    e.paired |= paired;
                                    e.connected |= connected;
                                })
                                .or_insert(DiscoveredDevice {
                                    mac,
                                    name,
                                    paired,
                                    connected,
                                });
                        }
                    }
                }
                maybe_prop = prop_stream.next() => {
                    if let Some(Ok(msg)) = maybe_prop
                        && let Some(path) = msg.header().path().map(|p| p.to_owned())
                        && path.as_str().starts_with(&adapter_prefix)
                        && let Some(sig) = PropertiesChanged::from_message(msg)
                        && let Ok(args) = sig.args()
                    {
                        let matches_uuid = args
                            .changed_properties
                            .get("UUIDs")
                            .and_then(value_as_str_vec)
                            .is_some_and(|u| has_uuid(&u, service_uuid));
                        let has_name = args.changed_properties.contains_key("Name")
                            || args.changed_properties.contains_key("Alias");

                        if matches_uuid
                            || (has_name
                                && mac_from_device_path(path.as_str())
                                    .is_some_and(|m| found.contains_key(&m)))
                        {
                            let mac = mac_from_device_path(path.as_str())
                                .unwrap_or_default()
                                .to_ascii_uppercase();
                            let mac = if mac.is_empty() {
                                get_device_prop::<String>(conn, path.as_str(), "Address")
                                    .await
                                    .unwrap_or_default()
                                    .to_ascii_uppercase()
                            } else {
                                mac
                            };
                            if !mac.is_empty() {
                                let name = extract_alias_from_borrowed(&args.changed_properties, &mac);
                                if matches_uuid {
                                    let paired = get_device_prop::<bool>(conn, path.as_str(), "Paired")
                                        .await
                                        .unwrap_or(false);
                                    let connected = get_device_prop::<bool>(conn, path.as_str(), "Connected")
                                        .await
                                        .unwrap_or(false);
                                    found
                                        .entry(mac.clone())
                                        .and_modify(|e| {
                                            if name.is_some() {
                                                e.name = name.clone();
                                            }
                                            e.paired |= paired;
                                            e.connected |= connected;
                                        })
                                        .or_insert(DiscoveredDevice {
                                            mac,
                                            name,
                                            paired,
                                            connected,
                                        });
                                } else if let Some(entry) = found.get_mut(&mac)
                                    && name.is_some()
                                {
                                    entry.name = name;
                                }
                            }
                        }
                    }
                }
            }
        }
        Ok(())
    }
    .await;

    stop_le_discovery(conn, adapter_path.as_str(), started).await;
    scan_res?;
    let mut devices: Vec<DiscoveredDevice> = found.into_values().collect();
    devices.sort_by(|a, b| {
        b.paired
            .cmp(&a.paired)
            .then_with(|| b.connected.cmp(&a.connected))
            .then_with(|| b.name.is_some().cmp(&a.name.is_some()))
            .then_with(|| a.mac.cmp(&b.mac))
    });
    Ok(devices)
}

pub async fn ensure_hid_profile_connected(conn: &zbus::Connection, mac: &str) {
    let trimmed = mac.trim();
    if trimmed.is_empty() {
        return;
    }
    let Ok(objects) = get_managed_objects(conn).await else {
        return;
    };
    let Some(dev_path) = find_device_by_mac(&objects, trimmed) else {
        return;
    };
    let Some(props) = objects.get(&dev_path).and_then(|ifaces| ifaces.get(BLUEZ_DEVICE_IFACE)) else {
        return;
    };
    if !props_is_paired(props) {
        return;
    }
    let has_hid = props
        .get("UUIDs")
        .and_then(|v| value_as_str_vec(v))
        .is_some_and(|uuids| has_uuid(&uuids, HID_PROFILE_UUID));
    if !has_hid {
        return;
    }

    let call = conn.call_method(
        Some(BLUEZ_SERVICE),
        dev_path.as_str(),
        Some(BLUEZ_DEVICE_IFACE),
        "ConnectProfile",
        &(HID_PROFILE_UUID,),
    );
    match tokio::time::timeout(DBUS_CALL_TIMEOUT, call).await {
        Ok(Ok(_)) => info!(mac = trimmed, "connected BlueZ HID profile (0x1124)"),
        Ok(Err(err)) => {
            let msg = err.to_string();
            if !msg.contains("AlreadyConnected") {
                debug!(mac = trimmed, error = %err, "BlueZ HID ConnectProfile skipped");
            }
        }
        Err(_) => debug!(mac = trimmed, "BlueZ HID ConnectProfile timed out"),
    }
}

pub fn spawn_bt_reconnect_monitor(
    conn: zbus::Connection,
    mac: String,
    cooldown_secs: u64,
    resume_tx: mpsc::Sender<()>,
) -> JoinHandle<()> {
    tokio::spawn(async move {
        if mac.trim().is_empty() {
            debug!("BT reconnect monitor skipped: device MAC is empty");
            return;
        }

        let rule = match MatchRule::builder()
            .msg_type(MessageType::Signal)
            .sender(BLUEZ_SERVICE)
            .and_then(|b| b.interface(DBUS_PROPS_IFACE))
            .and_then(|b| b.member("PropertiesChanged"))
            .and_then(|b| b.add_arg(BLUEZ_DEVICE_IFACE))
            .map(|b| b.build())
        {
            Ok(r) => r,
            Err(err) => {
                warn!(error = %err, "failed to build BlueZ Device1 PropertiesChanged match rule");
                return;
            }
        };

        let mut stream = match MessageStream::for_match_rule(rule, &conn, Some(32)).await {
            Ok(s) => s,
            Err(err) => {
                warn!(error = %err, "failed to subscribe to BlueZ Device1 PropertiesChanged");
                return;
            }
        };

        let cooldown = Duration::from_secs(cooldown_secs);
        let mut debounce: Option<Pin<Box<Sleep>>> = None;

        loop {
            let sleep_fut = async {
                match debounce.as_mut() {
                    Some(sleep) => sleep.as_mut().await,
                    None => std::future::pending::<()>().await,
                }
            };

            tokio::select! {
                _ = sleep_fut => {
                    debounce = None;
                    info!(mac = %mac, "BlueZ reconnect trailing-edge debounce elapsed; triggering resume");
                    if resume_tx.send(()).await.is_err() {
                        break;
                    }
                }
                maybe_item = stream.next() => {
                    let Some(item) = maybe_item else {
                        break;
                    };
                    let msg = match item {
                        Ok(m) => m,
                        Err(err) => {
                            debug!(error = %err, "BlueZ signal stream error");
                            continue;
                        }
                    };
                    let Some(path) = msg.header().path().map(|p| p.as_str().to_owned()) else {
                        continue;
                    };
                    if !path_matches_mac(&path, &mac) {
                        continue;
                    }
                    let Some(sig) = PropertiesChanged::from_message(msg) else {
                        continue;
                    };
                    let Ok(args) = sig.args() else {
                        continue;
                    };
                    if let Some(connected_val) = args.changed_properties.get("Connected")
                        && let Some(connected) = value_as_bool(connected_val)
                    {
                        if connected {
                            debug!(mac = %mac, cooldown_secs, "BlueZ Device1 Connected=true; (re)arming trailing-edge debounce");
                            debounce = Some(Box::pin(tokio::time::sleep(cooldown)));
                        } else {
                            debug!(mac = %mac, "BlueZ Device1 Connected=false; clearing pending reconnect debounce");
                            debounce = None;
                        }
                    }
                }
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn path_mac_matching_handles_case_and_separators() {
        assert!(path_matches_mac(
            "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF",
            "aa:bb:cc:dd:ee:ff"
        ));
        assert!(!path_matches_mac(
            "/org/bluez/hci0/dev_11_22_33_44_55_66",
            "aa:bb:cc:dd:ee:ff"
        ));
        assert!(!path_matches_mac(
            "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF",
            ""
        ));
    }

    #[test]
    fn link_status_error_mapping() {
        assert!(map_link_status_error(LinkStatus::Idle).is_none());
        assert!(map_link_status_error(LinkStatus::Starting).is_none());
        assert!(map_link_status_error(LinkStatus::Active).is_none());

        for (st, code) in [
            (LinkStatus::ErrUnknown, "0x80"),
            (LinkStatus::ErrShizukuUnavailable, "0x81"),
            (LinkStatus::ErrProvisioningFailed, "0x82"),
            (LinkStatus::ErrWifiHardware, "0x83"),
            (LinkStatus::ErrAssociationTimeout, "0x84"),
        ] {
            let err = map_link_status_error(st).expect("error expected");
            assert!(err.to_string().contains(code));
        }
    }
}
