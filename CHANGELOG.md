# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com) and this project uses
[Semantic Versioning](https://semver.org).

## [2.7.1] — 2026-10-17

Code cleanup: the Home AI bridge is removed, now that the BLE relay pipe does its job.

### Removed
- **Home AI bridge.** The hotspot-based bridge added in 2.7.0 is gone: its Settings entry and page,
  its Devices-page status line, its foreground service and its tests. The BLE relay pipe reaches the
  same home relay over Bluetooth, needs no hotspot, and keeps TLS end to end between the device and
  the relay instead of terminating it on the phone. A device that was set up for the bridge still
  has `llmhttpurl` and `llmhttpca` pointing at the phone; set them back to your relay's, then use
  the pipe (see `docs/BLE_RELAY_PIPE.md`).
- **Leftover bridge data is deleted** on the first launch after the update: its settings, its
  private CA key and that key's Keystore entry, and its notification channel.
- **BouncyCastle PKIX dependency.** It was only used to issue the bridge's certificates; the app
  now ships only BouncyCastle's core crypto provider.

### Security
- **Smaller network surface.** The app no longer listens on any port, and the `INTERNET`
  permission is now used only by the BLE relay pipe's VPN-bound outgoing connections.

## [2.7.0] — 2026-10-17

HardwareOne can now reach its AI away from Wi-Fi through the phone, over Bluetooth or the phone's
hotspot, and the app gains device notifications and offline OTA.

### Added
- **BLE relay pipe (optional, experimental).** A HardwareOne P4 away from Wi-Fi can reach its
  existing HTTPS relay through the phone.
  - **How it works:** the phone is a Bluetooth peripheral and forwards the device's TLS bytes,
    unopened, over an ordinary socket through the WireGuard tunnel. TLS stays end to end between the
    device and the relay, so the phone never sees the request, the token or the reply.
  - **Security:** there is no pairing or bond. The Bluetooth hop runs inside its own HW1-PIPE-v1
    channel (X25519, ChaCha20-Poly1305), keyed by a dedicated pipe passphrase entered with the same
    rules as the device's `phonepipesecret`.
  - **Limits:** one session at a time, a 30 s cooldown after a failed authentication, and bounded
    buffers with credit flow control. A failed stream is never reconnected or replayed. VPN loss
    keeps the pipe ready and refuses new streams.
  - **Recovery:** an enabled pipe comes back by itself after Bluetooth off/on, a reboot (once
    unlocked), an app update or a crash. After a background stop by Android, it comes back on the
    next app open.
  - **Tested on hardware** with a HardwareOne P4 in both of its Bluetooth client modes, through the
    VPN on Wi-Fi and on mobile data. See `docs/BLE_RELAY_PIPE.md` for the results and the remaining
    gaps.
  - **Its page** under ⚙ Settings has Start/Stop, the passphrase and destination, background-setup
    hints, endpoint-free diagnostics and an exportable qualification log.
- **Home AI bridge (optional).** Away from home, HardwareOne can keep using the AI on the home
  machine: the device joins the phone's hotspot and sends its HTTPS requests to the app, which
  forwards them to the home relay through the WireGuard tunnel (the WireGuard app stays the only
  VPN; the bridge never routes outside it). It works independently of the Bluetooth console, so the
  device can stay in glasses mode. The page under ⚙ Settings has explicit Start/Stop, separate
  hotspot-listener and home-relay status with actionable messages (no hotspot, no VPN, route
  missing, unreachable, certificate untrusted/mismatch, token rejected), a relay probe that sends
  no prompt, and the exact `llmhttpurl`/`llmhttpca` lines to apply on the device (copy, or push the
  URL over Bluetooth). Runs as a foreground service with a visible notification for locked-screen
  use; follows hotspot address changes and reissues its certificate automatically. Forwards only
  `POST /v1/chat/completions` and `POST /v1/terminal` with the relay's own size/time limits, and
  never retries: an uncertain outcome is reported as such. See `docs/HOME_AI_BRIDGE.md`.
- The bridge page checks that the phone can actually answer hotspot clients. A non-bypassable
  VPN with `AllowedIPs 0.0.0.0/0` makes Android route those replies into the tunnel, so the device
  times out while the listener looks healthy; the page now flags the listener as unreachable and
  names the hotspot prefix to exclude in WireGuard and the home prefixes to keep. VPN lockdown
  (replies refused outright) is reported separately.
- While the home relay is silent after its headers (the agent is still thinking), the bridge sends
  the device one SSE comment line every 20 s. The firmware ignores comment lines but resets its
  60 s stall timer on any byte, so a slow first token no longer ends the turn; the relay may now
  stay silent for up to the 300 s turn limit.
- **The app is now a device notification sink.** HardwareOne's notification system already fans an
  event out to the OLED banner, the web toast and the G2 lens; the app joins as a fourth surface
  (firmware sink `NSINK_APP`, master switch `notifydeviceapp`). Cards arrive unsolicited on the
  existing command link as one `#NOTIF {json}` line, and appear as a banner over whatever page is
  showing plus a list behind a new header bell. Per-kind muting (`notifyusermute`) and the personal
  importance floor (`notifylevel`) are wired to the same device-side prefs the other surfaces use.
- Notification cards are session-scoped: the device only sends them to a logged-in session and
  filters them for that user, so the app drops its list on logout or disconnect. Every card is also
  mirrored into the console log, since the device drops rather than queues cards when the link is
  busy and the console is the durable record.
- **Closed-loop offline OTA over Bluetooth.** The Devices menu now includes an OTA page that imports
  a local `.hw1ota` release bundle, validates its structure/signature/image identity on the phone,
  stages the signed image and manifest through a dedicated encrypted binary BLE protocol, asks HardwareOne's native
  ESP-IDF recovery updater to verify and install it, reconnects after reboot, monitors the 60-second
  health probation, and acknowledges the durable result.
- **Checkpointed bulk transfer.** OTA sends three exact-offset binary frames per burst, then reads the
  device's authoritative offset before continuing. Candidate and manifest each declare an exact size
  and SHA-256 digest, and cancellation asks the device to delete the active partial.
- **Recovery fallback credential setup.** A missing recovery WPA2/HTTP password can be sent once over
  encrypted BLE; it is never written to app storage or command history.

### Changed
- The relay pipe writes connection-state, MTU, subscription and advertising events to the system log
  under the tag `PipeGatt`. It names devices `dev1`, `dev2`, … and never logs a Bluetooth address.
- The app now declares `BLUETOOTH_ADVERTISE` (Nearby devices, Android 12+) for the BLE relay pipe,
  plus the normal `RECEIVE_BOOT_COMPLETED` and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` so an enabled
  pipe can restart after a reboot or update and its page can offer battery "Unrestricted". The pipe
  also uses the existing network/foreground-service permissions below.
- The app now declares `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE` (normal, used only
  as the Android 14 foreground-service prerequisite), `FOREGROUND_SERVICE(_CONNECTED_DEVICE)`,
  `POST_NOTIFICATIONS` and `WAKE_LOCK` for the Home AI bridge (the BLE relay pipe uses them too).
  Only the bridge and the pipe open sockets; both are off until configured and started.

### Security
- The relay pipe never terminates TLS, parses HTTP or stores payloads. It creates no Bluetooth
  bond: every frame on the link is authenticated and encrypted with HW1-PIPE-v1 keys derived from
  the pipe passphrase, which never leaves the phone, and failed authentication starts a 30 s
  cooldown. It connects only to the approved destination (checked before any DNS or TCP), resolves
  and connects only through the VPN network, keeps the passphrase and destination encrypted under
  separate non-exportable Keystore keys, and logs no endpoint or content.
- OTA adds no network or broad storage permission. The system document picker supplies the local
  bundle, OTA uses no network access, and the device independently verifies its embedded release
  trust root before selecting the image.
- The bridge terminates the device's TLS with an app-owned ECDSA CA whose private key is stored
  with a non-exportable Keystore key, pins the home leg to the imported relay CA with hostname/IP
  verification, binds every outbound socket to the VPN network (no process-wide binding, no
  fallback), passes the device token through without storing it, and logs no token or
  conversation text.
- Every OTA control/data step requires the HardwareOne Secure Channel and a live superadmin session;
  the protocol exposes no caller-selected device path.

## [2.6.3] — 2026-07-13

A failed secure connection now explains itself on the Devices page.

### Fixed
- **Clear error when a device has no passphrase.** Connecting to a device that has no secure
  passphrase set — while the app is configured to require one — no longer silently stops on the
  Devices page. A "Secure connection failed" dialog now explains why and what to do (set a
  passphrase on the device with `blesecret`, or clear it in the app to connect in plaintext). The
  same dialog also covers a wrong passphrase and a handshake timeout; previously the reason
  appeared only in the console.

## [2.6.2] — 2026-07-12

Bigger Bluetooth uploads, and long transfers no longer die when the screen sleeps.

### Changed
- **Bluetooth file uploads are no longer capped at 256 KB.** An upload is now allowed as long as it
  fits in the device's free storage, matching the web uploader. Large files still transfer slowly
  over Bluetooth — the device's web page is much faster for big files.
- **The screen stays awake during a file transfer.** While a file is uploading or downloading over
  Bluetooth (map downloads included), the screen no longer times out — previously it could sleep,
  background the app, and drop the transfer. Normal screen timeout resumes the moment it finishes,
  and this now applies on every screen rather than only the map view.

## [2.6.1] — 2026-07-11

Streamlines the offline-map viewer's on-screen controls and fixes a stale status line.

### Changed
- **On-map controls reworked.** Feature search and GPS now live in two matching icon buttons at the
  top of the map, and the separate Search panel is gone. Panning, zooming, and rotating are
  gesture-only (drag, pinch, two-finger twist); the live zoom and angle chips tap to reset to 1× / 0°.
  The old +/− / rotate / recenter buttons were removed.
- **Selected feature is labelled.** Choosing a search result now shows its name with an ✕ to clear the
  selection — the name, the segment cycler, and the map pin all clear together.

### Added
- **GPS updates toggle and rate.** The satellite button turns position polling over Bluetooth on and
  off (dark when off, yellow while acquiring, green once fixed). A new "GPS updates" setting picks the
  poll rate (2 / 5 / 10 / 30 s); both persist across launches.

### Fixed
- **Stale "No reply."** Loading a map that was already downloaded on the phone no longer shows a
  spurious "No reply." above the rendered map.

## [2.6.0] — 2026-07-11

Offline maps: view, search, and navigate the device's map on the phone — no internet, no Google.

### Added
- **Offline map viewer.** Downloads the device's loaded `.hwmap` vector map over the Secure Channel
  and renders it natively on the phone (roads, water, parks, buildings, rail/bus/ferry, land) — fully
  offline, no online tiles or Google services. Touch-first: drag to pan, pinch to zoom, two-finger
  rotate, with the OLED map mode's follow-GPS behavior.
- **Layers, search, and transit.** Per-type layer toggles, feature-name search with result cycling,
  and a list of the map's named transit routes — tap any to locate it.
- **Waypoints.** Add (including long-press-to-drop), rename, note, set as nav target, import/export,
  and clear — synced to the device over the same CLI.
- **GPS & tracks.** Live position with a location context bar ("On <road> • Near <area>"), live track
  recording, device track-log listing/loading, multi-file stitching, and export.
- **Map management.** Download/save, upload `.hwmap` files to the device, load/unload, and organize
  `/maps/`.

### Notes
- Rendering scales to large maps (tens of thousands of features): merged, decimated per-style
  geometry when zoomed out, switching to per-feature detail past ~5×, so panning stays smooth at
  every zoom. BLE map uploads cap at 256 KB — use the device's web page for larger maps.

## [2.5.1] — 2026-06-28

First version of a home- and lock-screen status widget.

### Added
- **Status widget (home & lock screen).** A glanceable, non-sensitive widget showing a connection
  status dot, the device name, and per-widget-configurable fields (battery %, voltage, firmware
  version, last-updated time) picked in a config screen when the widget is added. Resizable, and fits
  a single 1×1 cell.
- **Tap to reconnect.** Tapping the widget opens the app and reconnects to the last device (its
  address is persisted, so it works after a cold start). From the lock screen it prompts unlock first.

### Notes
- A widget can't drive Bluetooth, so it renders the last-known snapshot the app pushed. When that
  snapshot is stale it confirms the dot against the OS's current Bluetooth connection state (a local
  query, no scanning), so a closed app reads "offline" rather than a frozen "connected". Live data
  (battery/voltage) refreshes while the app is open or on tap; the app-closed self-refresh is bounded
  by Android's 30-minute widget tick.

## [2.5.0] — 2026-06-22

ESP-NOW messaging reliability overhaul, plus full-width tool pages on large/unfolded displays.

### Fixed
- **Non-chat records no longer leak into the conversation.** Command results, metadata, and boot
  notices are classified by record type, so they stay out of the message feed — and the fix survives
  an app reload.
- **Reliable message paging.** The BLE reply-capture layer now waits for a complete response instead
  of flushing a partial one, eliminating truncated pages, cross-talk between concurrent requests, and
  the inconsistent message counts seen when re-opening a peer.
- **The message feed no longer wedges** — re-opening a device reliably restarts its poll loop.
- **Metadata sync no longer races the chat feed** (it loads on demand from the Info tab instead).

### Added
- **Scroll back through the full message history**, with auto-scroll only when pinned to the bottom.
- **Pretty-printed JSON** for remote-command results.
- **Clear failure reasons** surfaced for peer file fetches (e.g. file-too-big) instead of a silent timeout.
- **Automation cadence** (daily / weekly / …) shown in trigger summaries.

### Changed
- **Device tool pages fill the full width** on unfolded / large-screen displays, matching the console page.
