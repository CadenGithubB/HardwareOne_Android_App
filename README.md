# HardwareOne Console (Android) — v2.5.1

A **Google-free** Android app that scans for, connects to, and controls an **ESP32-S3
"HardwareOne"** device over **Bluetooth LE** — a terminal-style console plus purpose-built
pages for device status, sensors, battery, and the on-device LLM. It speaks the device's
plaintext CLI **and, optionally, an app-layer encrypted channel** (X25519 +
ChaCha20-Poly1305) that makes the link confidential **without any BLE pairing/bonding**.

- **More than a console:** a **Devices** page (scan / connect / battery) and a **Console**,
  plus drill-in pages for **device status**, **sensors** (with a live gamepad visualizer),
  **battery**, **on-device LLM chat** (replies streamed token-by-token), and a **file
  browser** (browse / view / upload / manage device files over the encrypted channel).
- **No Firebase, no Play Services, no analytics, no Nearby/Fast Pair.** Only
  `android.bluetooth.*` plus FOSS libraries (AndroidX, BouncyCastle).
- **No `INTERNET` permission.** The app cannot talk to the network at all.
- **Security built in:** optional encrypted channel; login credentials encrypted in the
  hardware **Keystore** behind **biometric/PIN**; encrypted on-device **log storage**;
  `FLAG_SECURE` (screenshots/recording blocked).
- Kotlin + Jetpack Compose, single `Activity`, raw `android.bluetooth` GATT. Foldable-aware.
- `minSdk 26` (Android 8.0) → `targetSdk 35` (Android 15). GrapheneOS / AOSP / stock
  Pixel/Samsung.
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
  --out HardwareOne-1.3.1.apk app-release-aligned.apk
"$BT/apksigner" verify --print-certs HardwareOne-1.3.1.apk
```

`HardwareOne-1.3.1.apk` is what you attach to a GitHub Release. For Obtainium, point it
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
5. **If a secure-channel passphrase is configured**, run the handshake (below) before
   anything else; otherwise talk plaintext.
6. **Log in** (required by default): send `login <user> <pass>` to **REQUEST**. Success
   replies `Login successful …`; any command before login replies `Authentication required …`.
7. Send any CLI command to **REQUEST**; replies arrive asynchronously as **RESPONSE**
   notifications and stream into the log.

### BLE contract (Command service)

| Role | UUID | Properties |
|------|------|-----------|
| Command service | `12345678-…-cdef0` | — |
| REQUEST | `12345678-…-cde01` | Write / Write-No-Response — commands **or** secure frames |
| RESPONSE | `12345678-…-cde02` | Notify (CCCD `0x2902`) — replies **or** secure frames |
| STATUS | `12345678-…-cde03` | Read (JSON, always plaintext) |

The optional **Data service** (`…-cdef1`) is ignored. Standard **Device Information**
(`0x180A`) is read after connecting (firmware/model/manufacturer — shown in the **Device**
menu).

### Secure channel (optional app-layer encryption)

The BLE link uses **no pairing/bonding/link-encryption**. Confidentiality is instead
**app-layer** — *HardwareOne Secure Channel v1* — riding the same REQUEST/RESPONSE
characteristics as opaque binary:

- **X25519** key agreement · **HKDF-SHA256** · **ChaCha20-Poly1305** · pre-shared
  passphrase (PBKDF2). Ephemeral keys per connection ⇒ forward secrecy; the passphrase
  authenticates the peer.
- Enable it in **⚙ → Secure channel** with the **same passphrase** set on the device
  (`blesecret <pass>` then `blesecure on`). The app runs the handshake right after
  subscribing — the console shows a **"securing"** phase, then `Secure channel established`.
- `login` and all commands run **inside** the encrypted channel.
- Implemented in
  [`SecureChannel.kt`](app/src/main/java/com/hardwareone/console/security/SecureChannel.kt);
  the byte-for-byte wire contract + interop test vectors are in
  [`docs/SECURE_CHANNEL_V1.md`](docs/SECURE_CHANNEL_V1.md). The crypto is unit-tested and
  verified against the firmware's libsodium implementation.

> **Replies & truncation:** in **secure** mode the firmware chunks replies into multiple
> encrypted frames and the app **reassembles** the stream, so long output arrives intact.
> In **plaintext** mode a reply is one notification of up to `MTU − 3` bytes and longer
> output is truncated by the firmware. With more than one client connected the firmware
> encrypts **per-connection** (secure) or prefixes `"[ble conn:<id>] "` (plaintext).

---

## 6. Using the app

The app opens on the **Devices** page. A header toggle flips between **Devices** and
**Console**; both share the one connection.

### Devices

1. **SCAN** finds devices advertising the Command service UUID (by UUID, not name);
   **RECONNECT** re-opens the last device.
2. Tap a device to connect. The connection card shows each step (discover → MTU →
   subscribe → [securing →] ready) and, once connected, the device's **battery**.
3. **DISCONNECT** drops the link.

### Console

1. **Log in** — the **LOGIN** button opens a dialog (password masked on screen and in the
   log), or type `login <user> <pass>`. Tick **Remember** to save the credentials
   (biometric/PIN-gated, hardware-Keystore-encrypted) and auto-login next time.
2. Type commands (e.g. `help`, `whoami`) and press **SEND** / the keyboard's send key.
3. The header row packs the controls — the **Devices / Console** toggle, **Console ▾**
   (*Save log* / *Clear log*), **Device ▾**, and **⚙** (Settings) — with the status line
   (online as `<user>`, secure y/n) on the second row.

### Device ▾ — drill-in pages

When connected, **Device ▾** opens the device tools:

- **Status page** — a polled health/connectivity view (firmware, IP, uptime, I²C device
  list, LLM state, …) with a battery card; first load shows a spinner, then refreshes
  silently. See [docs/DEVICE_STATUS.md](docs/DEVICE_STATUS.md).
- **Sensors** — per-sensor cards from `sensors json`, per-sensor settings (`controls json`)
  and documented actions; the gamepad renders a **live graphical visualizer** (joystick +
  buttons), like the web/G2 UI. See [docs/SENSORS_DESIGN.md](docs/SENSORS_DESIGN.md).
- **LLM chat** — on-device LLM over BLE: pick/load a model, send a prompt, and watch the
  reply **stream token-by-token**; **Stop**, **Retry**, and **Clear** (which also resets the
  device-side conversation).
- **Files** — a file browser over BLE: list directories with storage usage, view text files,
  **upload** files from the phone (chunked, base64 over the secure channel), and create /
  rename / delete (each action gated by the firmware's per-entry permission bits). Requires
  the **Secure Channel** and an **admin** login. Large media (> 256 KB) is steered to the web.
- **Read status**, **Sync clock**, or **Reconnect** when disconnected.

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
├── AndroidManifest.xml          BLE perms (neverForLocation), FLAG_SECURE, no INTERNET
├── java/com/hardwareone/console/
│   ├── MainActivity.kt          single Activity; permissions, BT-enable, biometric, nav
│   ├── ble/
│   │   ├── BleConstants.kt       UUIDs + limits
│   │   ├── ConnectionState.kt    connection phases + DeviceInfo / DiscoveredDevice / Capture
│   │   ├── BleManager.kt         GATT state machine; secure-channel framing; off-console capture
│   │   ├── DeviceStatus.kt       `status json` model (connectivity, LLM, …)
│   │   ├── I2cDevice.kt          `devices json` model
│   │   ├── SensorSnapshot.kt     `sensors json` model
│   │   ├── SensorControls.kt     `controls json` model
│   │   ├── BatteryInfo.kt        `batterystatus json` model
│   │   ├── LlmModels.kt          LLM status / result / chat-message models
│   │   └── FileModels.kt         file-browser models (listing/stats/read/write + perms)
│   ├── security/
│   │   ├── SecureChannel.kt      X25519/HKDF/ChaCha20-Poly1305 handshake (BouncyCastle)
│   │   ├── CredentialStore.kt    login password — auth-gated AES-GCM Keystore
│   │   ├── SecretBox.kt          channel passphrase — non-auth AES-GCM Keystore (at rest)
│   │   └── LogVault.kt           encrypted log files (RSA-wrapped AES-GCM; biometric to read)
│   └── ui/
│       ├── ConsoleViewModel.kt   flows → pages; captures, command echo, masking, store facades
│       ├── UiCommon.kt           page toggle + shared buttons / spinner / battery glyph / status
│       ├── DevicesScreen.kt      pairing/selection page (scan, connect, battery)
│       ├── ConsoleScreen.kt      Compose console (responsive + tabletop; Console/Device menus)
│       ├── StatusScreen.kt       polled device-status page (+ battery card)
│       ├── SensorsScreen.kt      per-sensor cards, settings, documented actions
│       ├── SensorVisualizers.kt  live gamepad (joystick / buttons) visualizer
│       ├── SensorActions.kt      per-sensor documented-action registry
│       ├── LlmChatScreen.kt      on-device LLM chat (streamed replies, load/stop/retry/clear)
│       ├── FilesScreen.kt        BLE file browser (browse/view/upload/manage + picker)
│       ├── SettingsScreen.kt     appearance · credentials · logs · secure channel
│       ├── SavedLogsScreen.kt    encrypted saved-log list (+ storage info)
│       ├── LogViewerScreen.kt    decrypted viewer + plaintext export
│       ├── FoldPosture.kt        Jetpack WindowManager → fold posture
│       ├── ThemePreference.kt    theme persistence
│       └── theme/Theme.kt        light/dark "glassmorphism" Material3 theme
├── res/  (+ res/values-night/)  adaptive icon, strings, day/night window themes
└── test/ …/security/            SecureChannel unit tests + interop vectors
docs/SECURE_CHANNEL_V1.md         the secure-channel wire contract (app ↔ firmware)
docs/DEVICE_STATUS.md             status-page data contract
docs/SENSORS_DESIGN.md            sensors-page design + per-sensor actions
```

---

## 9. Security & privacy notes

- **Confidentiality:** the link can be **app-layer encrypted** (Secure Channel v1 — X25519
  + ChaCha20-Poly1305; see §5), using **no BLE pairing/bonding** — so there are no OS-level
  bonds to manage or leak. Without a passphrase the link is plaintext (the device's
  default). `login` is required either way (authorization), on top of encryption
  (confidentiality). If cleartext ever appears on a link that should be encrypted, the app
  **flags it and drops it** rather than displaying it.
- **Credentials at rest:** a saved login password is encrypted with a **hardware Keystore**
  key (StrongBox/TEE) gated by **biometric/PIN**; the channel passphrase uses a non-auth
  Keystore key (encrypted at rest, read automatically at connect). Passwords are **masked**
  in the on-screen log, and the password field uses a no-suggestions keyboard.
- **Saved logs** are encrypted on device (Keystore envelope; biometric/PIN to open) in
  app-private storage. Plaintext only leaves via an explicit **Export**.
- **`FLAG_SECURE`** (always on, not user-toggleable): blocks screenshots, screen recording,
  screen-share, and the recents preview.
- `allowBackup="false"`; **no `INTERNET` permission**; no third-party analytics/SDKs (only
  FOSS AndroidX + BouncyCastle). What you build is what runs.

---

## License

[GNU GPLv3](LICENSE). You may use, study, share, and modify this software; derivative
works must remain under the GPLv3.
