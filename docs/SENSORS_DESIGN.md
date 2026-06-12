# Sensor viewing — design doc (DRAFT, for review)

**Status:** design only. No firmware contract and no app code yet — this captures the
direction so it can be reviewed before committing to an interface.

**Goal:** view live sensor readings in the Android app over the BLE command channel, with
**no dependency on the web server** (it isn't always running). Same principle as the Device
Status page and the I²C device list.

---

## 1. How the firmware models & displays sensors today

There are ~15 sensor types (RTC, battery fuel gauge, IR presence, time-of-flight distance,
IMU, GPS, gesture/color, FM radio, thermal camera, servo driver, plus camera & mic). The
pattern is uniform:

- **Data** lives in a per-sensor **cache struct** in the sensor's core `.cpp`
  (e.g. `gPresenceCache` in `i2csensor_sths34pf80.cpp`: `ambientTemp`, `objectTemp`,
  `presenceValue`, `motionDetected`, `lastUpdate`, …), updated by the polling task and
  guarded by a mutex.
- **Serialization exists twice today:**
  - **Web-coupled:** `get<Sensor>DataJson(JsonObject&)` in `i2csensor_<x>_web.h` — used by the
    dashboard cards.
  - **Web-independent (already in core):** `<sensor>BuildDataJSON(char* buf, size_t)` in the
    sensor's core `.cpp` — reads the same cache, no web/HTTP. **Already consumed by ESP-NOW
    and MQTT.** These are registered in a table in `System_ESPNow_Sensors.cpp`:
    `[REMOTE_SENSOR_PRESENCE] = { presenceBuildDataJSON, intervalMs, bufSize }`.
- **State umbrella:** `buildSensorStatusJson()` (`System_I2C.cpp`) carries *which* sensors are
  `compiled` / `enabled` / `connected`, plus a **`seq`** change counter — but **not the
  readings**. Currently only reachable via the web layer.
- **Change notification:** `gSensorStatusSeq` / `sensorStatusBump()` — the web UI re-renders
  when `seq` changes (pushed over SSE).
- **`cmd_sensors`** (CLI) is a static **catalog** of supported chips, not live values.
- Other UIs each have their own per-sensor renderer: `_oled.h` (OLED), `G2_Page_Sensors`
  (glasses), `System_ESPNow_Sensors` (remote), MQTT discovery.

**Takeaway:** the data is core-side and already serialized web-independently for ESP-NOW. The
web `_web.h` serializers are the *redundant* copies. Routing a CLI/BLE command through the
**core** builders is the relocation we want — no new data plumbing, just a new caller.

## 2. Constraints that shape the interface

- **Heterogeneous data.** Scalar (temp, %, distance, time) is trivial; vector (IMU
  orientation, GPS lat/lon) is small; **image/stream** (MLX90640 = 32×24 = 768 values,
  camera, microphone) is **not BLE-viable** — report state only, never bulk data.
- **Bandwidth.** BLE is chunked and slow. Emit readings only for **active/connected**
  sensors; consider a per-sensor command so big sensors are opt-in.
- **Auth / secure channel.** Inherits the existing command path (login + Secure Channel).

## 3. Proposed firmware shape (to detail later as a contract — NOT now)

Mirror `buildI2cDeviceListJson` / `devices json`:

1. **Core aggregator in the I²C/sensor section** (e.g. `buildSensorsJson(out, onlyActive)`)
   that walks the **existing per-sensor core builders** (reuse the ESP-NOW registry table)
   and folds in the `buildSensorStatusJson` state. No web dependency.
2. **Expose via CLI/BLE**, in the I²C/filesystem command group:
   - `sensors json` → all active sensors (compact), and/or
   - `sensor <id> json` → one sensor on demand (keeps replies small; lets big/stream sensors
     be opt-in or excluded).
3. **`seq`-versioned**, so the app can skip re-rendering when nothing changed.

### Reading model — the open decision

The core `<sensor>BuildDataJSON` builders currently emit **sensor-specific keys**
(`ambientTemp`, `objectTemp`, `presenceValue`, …). Two ways to surface that to the app:

- **A. Normalized/generic (recommended):** the aggregator maps each sensor to a uniform
  `{ id, name, connected, enabled, kind, readings:[{label, value, unit}] }`. The app renders
  *any* sensor with one generic card; new sensors need zero app changes. Cost: one mapping
  layer in the firmware aggregator.
- **B. Pass-through/typed:** emit each sensor's native JSON; the app special-cases each type
  for bespoke UI (compass for IMU, map pin for GPS, gauge for battery). Richer, but ~15
  schemas to keep in sync on both sides and per-sensor app work forever.

Recommended: **A** for v1 (generic), leaving room to special-case a few high-value sensors
(B) later — e.g. battery gauge, GPS — without changing the transport.

Proposed normalized shape:

```json
{
  "v": 1,
  "seq": 1234,
  "sensors": [
    { "id": "presence", "name": "STHS34PF80 presence", "connected": true, "enabled": true,
      "kind": "scalar",
      "readings": [
        { "label": "Ambient",  "value": 24.5, "unit": "°C" },
        { "label": "Presence", "value": 312,  "unit": "" },
        { "label": "Detected", "value": "yes","unit": "" }
      ] },
    { "id": "thermal", "name": "MLX90640 thermal", "connected": true, "enabled": false,
      "kind": "stream", "readings": [] }
  ]
}
```

`value` may be number / bool / string (already display-normalized). `kind ∈
{scalar, vector, stream}`; `stream` sensors carry state only.

## 4. App-side plan (when the command exists)

A **Sensors page** (Device ▾ → *Sensors*) that reuses everything built for the Status page:

- **Off-console capture** (`sendCaptured("sensors json", tag="sensors")`) through the existing
  serial capture **queue** — coexists with the status poll without clobbering.
- **Lifecycle-gated polling** (~2–3 s, `RESUMED` only) so it stops on lock/background.
- **Silent-device watchdog** already covers "sent, no reply".
- **Generic renderer:** one card per sensor — name + connected/enabled chip + a list of
  `label: value unit` rows. `kind == stream` → show state + "view on web/OLED" note (no data).
- **`seq`:** skip recomposition when unchanged.
- Optional later: lazy per-sensor expand via `sensor <id> json` (mirrors the I²C card).

## 5. Phasing

1. **State-only** — expose `buildSensorStatusJson` core-side over CLI; app shows what's
   compiled/enabled/connected (no values). Smallest firmware step.
2. **Scalar readings** — the normalized `sensors json` for scalar sensors (presence, battery,
   distance, RTC, GPS fix, FM freq, APDS proximity).
3. **Vector** — IMU/GPS detail, optionally special-cased UI.
4. **Never over BLE** — thermal grid, camera, mic (state only; direct users to web/OLED).

## 6. Open questions for review

- Aggregate `sensors json` vs per-sensor `sensor <id> json` (or both)?
- Normalized (A) vs typed pass-through (B) — confirm A for v1?
- Which sensors ship in v1 (all scalar, or a chosen subset)?
- Long-term: retire the `_web.h` serializers in favour of the core builders to kill the
  duplicate-serialization divergence?
