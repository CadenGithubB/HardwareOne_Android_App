# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com) and this project uses
[Semantic Versioning](https://semver.org).

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
