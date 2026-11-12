# Controid Local Unix Socket IPC (NDJSON)

Socket Path: `${XDG_RUNTIME_DIR}/controid/daemon.sock` (fallback `/run/user/${UID}/controid/daemon.sock`)
Permissions: `0600` (`S_IRUSR | S_IWUSR`)
Framing: Single-line UTF-8 JSON terminated by `\n` (`0x0A`). Max line length: `65536` bytes (`64 KB`).

## 1. Requests (`CLI/Tray -> Daemon`)

```json
{"id": 1, "method": "hotspot.enable", "params": {}}
{"id": 2, "method": "hotspot.disable", "params": {}}
{"id": 3, "method": "device.status", "params": {}}
{"id": 4, "method": "clipboard.send", "params": {"mime": "text/plain", "data": "Hello"}}
{"id": 5, "method": "stream.start", "params": {"max_fps": 60, "bitrate_mbps": 8}}
{"id": 6, "method": "stream.stop", "params": {}}
{"id": 7, "method": "connection.resume", "params": {}}
{"id": 8, "method": "pair.discover", "params": {"timeout_secs": 10}}
{"id": 9, "method": "events.subscribe", "params": {}}
```

## 2. Responses (`Daemon -> Requester`)

```json
{"id": 1, "result": {"status": "starting"}, "error": null}
{"id": 3, "result": {"state": "connected", "battery": 85, "charging": false, "tethering": true, "hid_connected": true}, "error": null}
{"id": 4, "result": null, "error": {"code": 5001, "message": "Device not connected"}}
```

## 3. Telemetry Broadcast Events (`Daemon -> Subscribers`)

```json
{"event": "link.state_changed", "data": {"state": "connected", "ssid": "AndroidAP"}}
{"event": "error.tethering", "data": {"code": "0x82", "message": "Carrier Provisioning Failed"}}
{"event": "clipboard.received", "data": {"mime": "text/plain", "bytes": 42}}
{"event": "stream.state", "data": {"active": true, "width": 1080, "height": 2400}}
```
