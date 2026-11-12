# Controid BLE Out-of-Band GATT Specification

## 1. GATT Service & Characteristics

- **Service UUID**: `0000a001-0000-1000-8000-00805f9b34fb`
- **Characteristic `STATE_TRIGGER`**: `0000b001-0000-1000-8000-00805f9b34fb`
  - Properties: `READ | WRITE | WRITE_NO_RESPONSE`
  - Payload (Write): `[cmd: u8, signature_or_tag...]` or `[0x01]` (start hotspot), `[0x00]` (stop hotspot) after `AUTH_CHALLENGE`.
- **Characteristic `AUTH_CHALLENGE`**: `0000b002-0000-1000-8000-00805f9b34fb`
  - Properties: `READ | WRITE`
  - Payload (Read): 16-byte random nonce issued by Android GATT server.
  - Payload (Write): 64-byte Ed25519 signature of the 16-byte nonce (or paired challenge response) to prevent replay attacks.
- **Characteristic `LINK_STATUS`**: `0000b003-0000-1000-8000-00805f9b34fb`
  - Properties: `READ | NOTIFY`
  - Payload (1 byte):
    - `0x00`: `IDLE`
    - `0x01`: `STARTING`
    - `0x02`: `ACTIVE` (Hotspot operational; Linux disconnects BLE immediately and connects via NetworkManager)
    - `0x80`: `ERR_UNKNOWN`
    - `0x81`: `ERR_SHIZUKU_UNAVAILABLE`
    - `0x82`: `ERR_PROVISIONING_FAILED`
    - `0x83`: `ERR_WIFI_HARDWARE`
    - `0x84`: `ERR_ASSOCIATION_TIMEOUT`

## 2. Coexistence & Connection Lifecycle

1. Android MUST prefer 5 GHz (`AP_BAND_5GHZ`) when starting hotspot to eliminate 2.4 GHz RF contention with Bluetooth HID.
2. Linux attempts direct connection to persisted MAC (2s timeout). On failure, falls back to discovery filtered by `SERVICE_UUID` and updates `config.toml` MAC.
3. Upon receiving `LINK_STATUS == 0x02` (or terminal error `>= 0x80`), Linux MUST disconnect GATT immediately before starting Wi-Fi TCP handshake.
4. Passive BlueZ `org.bluez.Device1` `Connected=true` wake trigger uses a 30s trailing-edge debounce cooldown.
