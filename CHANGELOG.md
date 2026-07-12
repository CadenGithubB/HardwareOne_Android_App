# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com) and this project uses
[Semantic Versioning](https://semver.org).

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
