# Device Status page — `status json` contract

The **Device status** page (Device ▾ → *Status page*) is populated entirely over the
existing command channel — **no web/HTTP dependency**. It sends the literal command
`status json` and parses the single JSON object that comes back.

This file is the app-side record of the firmware contract. The firmware is the source of
truth; if the schema changes there, update [`DeviceStatus.kt`](../app/src/main/java/com/hardwareone/console/ble/DeviceStatus.kt).

## How the app talks to it

1. The page sends `status json` via the **off-console capture** path
   (`BleManager.sendCaptured(..., tag = "status")`) so the JSON never spams the console.
2. Reply fragments are reassembled and delivered when the stream goes quiet
   (`CAPTURE_QUIET_MS`, ~450 ms) — the firmware has no end-of-reply marker, so this is
   idle-delimited, exactly as the contract recommends. A 5 s timeout covers "no reply".
3. The assembled text is `JSON.parse`d once (`DeviceStatus.parse`). On parse failure or
   timeout the page shows "unavailable" and retries on the next tick.
4. While the page is open it re-requests every **3 s** (request/response — there is no
   push/subscribe). Polling stops when the page is closed.

Over a secure channel the reply is encrypted + chunked like every other command result;
reassembly is identical.

## Notes that shaped the parser

- Top-level **`v`** is the schema version (currently `1`). Parsed but not yet branched on.
- **`status json`** returns the **compact** form (~800 B); it omits the unbounded I²C
  per-device list (`i2c.deviceList`) that only the web `/api/system` includes.
- Plain `status` (the **Read status** menu item) is the human-readable text form and is
  **not** parsed — it still streams to the console as before.
- The command needs an **authenticated session** when BLE auth is enabled (the app logs
  in first anyway). `status` itself is not admin-only.
- **Soft failure:** `{"error":"oom"}` → treated as "device busy", keeps the last snapshot
  and retries.

## I²C device list — `devices json` (lazy-loaded)

`status json` deliberately omits the per-device I²C list to keep the BLE blob small
(~800 B). The list is fetched **on demand** by a dedicated command when the user expands
the I²C card:

```
devices json  →  {"v":1,"count":3,"devices":[{"name":"DS3231","addr":104,"bus":0}, ...]}
```

- `addr` is **decimal** (104 == `0x68`); the app renders it as hex (`I2cDevice.addrHex`).
- Same shared firmware builder as the web dashboard, so the BLE / CLI / web lists can't drift.
- Captured via the same off-console path under tag `devices`. Captures are **serialised** in
  `BleManager` (a small queue), so a `devices json` request and the 3 s `status json` poll
  can't clobber each other's buffer.
- The card shows `connectivity.i2c.devices` (the count) immediately; the full list loads on
  first expand and is cached until the page is closed.

## Field handling

- `system_time == ""` → "— (not synced)".
- `net.ip == ""` → render "disconnected", skip the signal/SSID rows; MAC always shown.
- Every `connectivity.*` sub-object is **optional** — absent means "not in this build", so
  the card is simply not rendered (never shown as "off").

See the firmware-side contract for the full schema; the Kotlin model mirrors it
key-for-key.
