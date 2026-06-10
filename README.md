# HardwareOne Console (Android)

A minimal, **Google-free** Android app that pairs with and messages an **ESP32-S3
"HardwareOne"** device over **Bluetooth LE**. It is a single-screen, terminal-style
console: scan → connect → negotiate MTU → subscribe → log in → send CLI commands and
read the text replies.

- **No Firebase, no Play Services, no analytics, no Nearby/Fast Pair.** Only
  `android.bluetooth.*`.
- **No `INTERNET` permission.** The app cannot talk to the network at all.
- Kotlin + Jetpack Compose, single `Activity`, raw `android.bluetooth` GATT.
- `minSdk 26` (Android 8.0) → `targetSdk 35` (Android 15). Works on GrapheneOS / AOSP
  and stock Pixel/Samsung.
- Distributable as a plain **APK** via GitHub Releases / Obtainium / F-Droid.

---

## 1. Toolchain (FOSS, no Android Studio)

You only need a JDK, the Android SDK, and the bundled Gradle wrapper. The example uses
Homebrew on macOS; any JDK 17 + the Android command-line tools work the same.

```bash
# A FOSS JDK 17 (Eclipse Temurin — not Google). AGP 8.7 requires JDK 17+.
brew install --cask temurin@17

# The Android SDK, without the IDE.
brew install --cask android-commandlinetools
```

Point the tools at an SDK location and install the pieces this project needs:

```bash
# Where the SDK lives (pick any path you like).
export ANDROID_HOME="$HOME/Library/Android/sdk"
mkdir -p "$ANDROID_HOME"

# Make the CLI tools + this build visible in your shell.
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# Accept licenses (Google SDK terms — required to download platform bits).
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses

# Install exactly what the build uses.
sdkmanager --sdk_root="$ANDROID_HOME" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

> **The only unavoidable "Google" bit** is the Android SDK itself (platform + build
> tools). It needs no Google account, phones nothing into your build, and puts nothing
> into your APK. F-Droid's build servers use the same SDK. Android Studio is **not**
> required.

Add `export ANDROID_HOME=...` and the `PATH` line to your `~/.zshrc` so future shells
find the SDK. (Alternatively, create a `local.properties` file in this folder with
`sdk.dir=/absolute/path/to/sdk` — but `local.properties` is git-ignored on purpose.)

---

## 2. Build the APK

From this project directory:

```bash
# Debug APK — installable immediately, signed with the auto-generated debug key.
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Release APK — unsigned until you sign it (see §4).
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release-unsigned.apk
```

The first run downloads Gradle 8.13 and the Android Gradle Plugin; subsequent builds are
fast. No Google sign-in, ever.

---

## 3. Install & run

With the device connected over USB (USB debugging on) **or** using any file transfer:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or just copy the `.apk` to the phone and open it (allow "install unknown apps").

On first launch the app asks for **Nearby devices** (BLUETOOTH_SCAN / BLUETOOTH_CONNECT)
permission. The scan is flagged `neverForLocation`, so **no location permission is
requested on Android 12+**.

---

## 4. Signing a release (for GitHub / Obtainium / F-Droid)

Create a keystore **once** and keep it safe (never commit it):

```bash
keytool -genkeypair -v -keystore hardwareone-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 -alias hardwareone
```

Sign and align the release APK with the SDK's `apksigner`/`zipalign`:

```bash
BT="$ANDROID_HOME/build-tools/35.0.0"
"$BT/zipalign" -v -p 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  app-release-aligned.apk
"$BT/apksigner" sign --ks hardwareone-release.jks \
  --out HardwareOne-1.0.0.apk app-release-aligned.apk
"$BT/apksigner" verify --print-certs HardwareOne-1.0.0.apk
```

`HardwareOne-1.0.0.apk` is what you attach to a GitHub Release. For Obtainium, point it
at your GitHub releases page. For F-Droid, the
[reproducible/`fdroiddata`](https://f-droid.org/docs/) workflow uses this same toolchain.

---

## 5. How the app talks to the device

The connection sequence is exactly what the firmware enforces (see
[`BleManager.kt`](app/src/main/java/com/hardwareone/console/ble/BleManager.kt)):

1. `connectGatt(autoConnect = false, TRANSPORT_LE)`; on connect, request **HIGH**
   connection priority.
2. `discoverServices()`.
3. `requestMtu(517)` — the firmware does *not* start MTU negotiation, so the app must.
   The **granted** value (often 247–517) is used as the real per-notification size; the
   app never assumes it got the full 514-byte payload.
4. Enable notifications on **RESPONSE**: `setCharacteristicNotification(true)` **and**
   write `0x0001` to its CCCD (`0x2902`). Skipping the CCCD write = no data.
5. **Log in** (required by default): send `login <user> <pass>` to **REQUEST**. Success
   replies `"[ble] Login successful. ..."`; any command before login replies
   `"Authentication required. ..."`.
6. Send any CLI command to **REQUEST**; replies arrive asynchronously as **RESPONSE**
   notifications and are appended to the log (multi-line replies render verbatim).

### BLE contract used (Command service)

| Role | UUID | Properties |
|------|------|-----------|
| Command service | `12345678-…-cdef0` | — |
| REQUEST | `12345678-…-cde01` | Write / Write-No-Response |
| RESPONSE | `12345678-…-cde02` | Notify (CCCD `0x2902`) |
| STATUS | `12345678-…-cde03` | Read (JSON) |

The optional **Data service** (`…-cdef1`) and its sensor/stream characteristics are
intentionally **ignored in v1**. Standard **Device Information** (`0x180A`) is read once
after connecting to show the firmware/model/manufacturer strings.

> **Note on truncation:** each reply is a single notification of up to
> `negotiated_MTU − 3` bytes. Output longer than that is truncated *by the firmware*
> today (server-side chunking is a planned firmware feature). The app shows whatever the
> device sends.

> **Multi-client:** if more than one client is connected, the firmware prefixes replies
> with `"[ble conn:<id>] "`. With a single (normal) connection there is no prefix. The
> app displays replies as received.

---

## 6. Using the console

1. **SCAN** — finds devices advertising the Command service UUID (matched by UUID, not by
   name).
2. Tap a device to connect. The log shows each step (discover → MTU → subscribe → ready).
3. **LOGIN** — opens a small dialog (password is masked, both on screen and in the log).
   Or just type `login <user> <pass>` in the input box.
4. Type commands (e.g. `help`, `whoami`, `status`) and press **SEND** / the keyboard's
   send key. **STATUS** reads the JSON status characteristic. **DISCONNECT** /
   **RECONNECT** manage the link.

---

## 7. Foldable / Pixel Fold support

The app is foldable-aware (`androidx.window` + Compose `WindowSizeClass`):

- **Resizes smoothly without recreating** the Activity on fold/unfold (`configChanges` +
  `resizeableActivity`). The typed command and any open dialog survive the transition
  (`rememberSaveable`).
- **Unfolded inner screen / landscape:** content is centred and width-capped (~760dp) so
  log lines and the input field stay readable instead of stretching across the wide,
  near-square display. The folded cover screen uses the normal full-width single column.
- **Tabletop posture** (half-open, laid down like a tiny laptop): the layout splits at the
  hinge — the scrolling **log** sits on the upper, propped-up panel and the **controls +
  input** on the lower, flat panel.

> **Best-effort flag:** the tabletop split keys off the hinge *thickness* and the
> centred-hinge assumption of the Pixel Fold, not pixel-exact hinge coordinates, so it is
> robust but not surveyed to the millimetre. Vertical-hinge "book" posture is detected but
> intentionally rendered like the normal centred layout in v1. Both are easy to extend in
> [`FoldPosture.kt`](app/src/main/java/com/hardwareone/console/ui/FoldPosture.kt) /
> [`ConsoleScreen.kt`](app/src/main/java/com/hardwareone/console/ui/ConsoleScreen.kt).

## 8. Project layout

```
app/src/main/
├── AndroidManifest.xml         BLE permissions (neverForLocation), no INTERNET
├── java/com/hardwareone/console/
│   ├── MainActivity.kt         single Activity; runtime permissions + BT-enable prompt
│   ├── ble/
│   │   ├── BleConstants.kt      UUIDs + limits
│   │   ├── ConnectionState.kt   sealed connection phases + data types
│   │   └── BleManager.kt        coroutine-friendly GATT state machine + op queue
│   └── ui/
│       ├── ConsoleViewModel.kt  flows → log entries; command echo + password masking
│       ├── ConsoleScreen.kt     Compose console (responsive + tabletop layouts)
│       ├── FoldPosture.kt       Jetpack WindowManager → fold posture for the UI
│       └── theme/Theme.kt       dark "terminal" Material3 theme
└── res/                        launcher icon (adaptive), strings, window theme
```

---

## 9. Security & privacy notes

- The BLE link itself is **unencrypted and unbonded** by the device's design; auth is
  **app-layer** (`login`). Treat anything sent over it accordingly — this app simply
  speaks that protocol.
- The app keeps the **password out of the on-screen log** (masked) but sends it verbatim
  over the (unencrypted) link, as the protocol requires.
- `allowBackup="false"` — nothing is backed up off-device.
- No network permission, no third-party SDKs, no telemetry. What you build is what runs.
