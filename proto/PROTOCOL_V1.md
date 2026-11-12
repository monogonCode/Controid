# Controid Binary Multiplexed Protocol Specification (v1.0)

Transport: Oriented TCP stream, Big-Endian (Network Byte Order).
Default Port: `55777` (over Wi-Fi Hotspot gateway, typically `192.168.43.1`).

## 1. Fixed Header (12 Bytes)

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|   MAGIC (0xAA)|   CHANNEL_ID  |          STREAM_ID            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|          FLAGS (16-bit)       |        PAYLOAD_LENGTH         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        SEQUENCE_NUMBER                        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        PAYLOAD_BYTES ...                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

- `MAGIC` (`u8`): `0xAA`. Any mismatch MUST fail fast and close/reset the TCP socket immediately.
- `CHANNEL_ID` (`u8`):
  - `0x00`: `CTRL` (Control, Heartbeat, Credits, L7 Auth, Stream Lifecycle, Status)
  - `0x01`: `CLIP_META` (Clipboard UTF-8 text <= 32 KB / metadata)
  - `0x02`: `CLIP_DATA` (Massive clipboard payloads / files in <= 16 KB chunks)
  - `0x03`: `MEDIA_STREAM` (Raw Annex-B H.264/H.265 NAL stream)
  - `0x04`: `INPUT_FALLBACK` (Fallback pointer/key/scroll events for `/dev/uinput`)
- `STREAM_ID` (`u16` BE): Transaction/stream identifier (`0` reserved for stateless `CTRL` frames).
- `FLAGS` (`u16` BE):
  - `0x0001`: `START` (First chunk; payload begins with `total_len: u64 BE` + `mime_len: u16 BE` + `mime_utf8` + initial data)
  - `0x0002`: `END` (Final chunk; triggers commit)
  - `0x0004`: `ABORT` (Abort stream; purge buffers and spool files; tombstone `STREAM_ID` for 5-10s)
  - `0x0008`: `URGENT` (High priority out-of-band flag)
  - `0x0010`: `ACK_REQ` (Explicit `ACK` requested on `CTRL`)
  - Bits 5..15: Reserved (`0`)
- `PAYLOAD_LENGTH` (`u16` BE): Strict cap `16384` bytes (`16 KB`). Frames declaring `> 16384` MUST close the connection before heap allocation.
- `SEQUENCE_NUMBER` (`u32` BE): Monotonic per `STREAM_ID`, starting at `0` on `START`. Out-of-order sequence aborts only the affected `STREAM_ID`.

## 2. Control Channel (`0x00`) Messages

First byte of payload is `MSG_TYPE` (`u8`):

- `0x01` `CREDIT_GRANT` (8 bytes): `[0x01, target_channel: u8, credits_granted: u16 BE, cumulative_bytes_consumed: u32 BE]`
  - Initial static window: `64` credits (`1 MB` at `16 KB` per chunk).
  - Threshold to grant: `512 KB` (`32` chunks consumed).
- `0x02` `HEARTBEAT` (8 bytes): `[0x02, sub_type: u8 (0x01=Ping, 0x02=Pong), reserved: u16, timestamp_ms: u32 BE]`
  - Emitted every `5s` when idle. Stream capture watchdog aborts if silent for `5s`.
- `0x03` `AUTH_CHALLENGE` (33 bytes): `[0x03, nonce: 32 bytes]` (Android -> Linux immediately on TCP accept)
- `0x04` `AUTH_RESPONSE` (97 bytes): `[0x04, pubkey: 32 bytes, signature: 64 bytes]` (Ed25519 signature over `nonce`)
- `0x05` `AUTH_RESULT` (2 bytes): `[0x05, status: u8 (0x00=OK, 0x01=REJECTED)]`
- `0x10` `STREAM_REQ` (5 bytes): `[0x10, max_fps: u16 BE, bitrate_mbps: u8, codec: u8 (0x01=H264, 0x02=H265)]`
- `0x11` `STREAM_ACK` (6 bytes): `[0x11, status: u8 (0x00=OK, else err), width: u16 BE, height: u16 BE]`
- `0x12` `STREAM_STOP` (1 byte): `[0x12]`
- `0x13` `STREAM_STOPPED` (1 byte): `[0x13]`
- `0x20` `ACK` (7 bytes): `[0x20, stream_id: u16 BE, sequence_number: u32 BE]`
- `0x30` `DEVICE_STATUS` (5 bytes): `[0x30, battery_pct: u8, charging: u8, tethering: u8, hid_connected: u8]`

## 3. Clipboard Threshold & Composite Echo Hash

- RAM Threshold: `32 MiB` (`33554432` bytes). Payloads `<= 32 MiB` buffer in RAM; `> 32 MiB` spool directly to temp disk files in `16 KB` chunks (hard cap `512 MiB`).
- Echo Suppression (`2s` TTL):
  - Text: `SHA-256(utf8_bytes)`
  - Binary/Image: `SHA-256(total_len_u64_be || first_1024_bytes || last_1024_bytes)`

## 4. Fallback Input (`0x04`) Binary Events

- `0x01` `MOUSE_REL`: `[0x01, dx: i16 BE, dy: i16 BE, wheel: i8]`
- `0x02` `MOUSE_BTN`: `[0x02, button: u8 (1=Left,2=Right,3=Middle), pressed: u8]`
- `0x03` `KEY_EVENT`: `[0x03, linux_keycode: u16 BE, pressed: u8]`
