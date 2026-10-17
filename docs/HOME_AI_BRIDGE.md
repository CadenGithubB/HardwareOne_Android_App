# Home AI bridge

Use HardwareOne and the G2 glasses away from home while the AI still runs on the home Mac. The
phone's hotspot carries the ESP32's requests to the app; the app forwards them to the home relay
through the WireGuard tunnel the official WireGuard app already provides.

```text
G2 glasses ↔ Bluetooth ↔ HardwareOne ↔ phone hotspot ↔ HardwareOne app (bridge)
                                                          └─ WireGuard over cellular ─▶ home relay (HTTPS :18790)
                                                                                           └─ Terminal host / OpenClaw (loopback)
```

The bridge is optional and off by default. It is independent of the Bluetooth console: it keeps
working while the ESP32 is in `blemode client` talking to the glasses and the app shows
Bluetooth *disconnected*, and it keeps waiting while the ESP32 is away from the hotspot.

## Why the phone has to do this at all

Stock Android does not route hotspot clients through the phone's VPN. A tethered ESP32 reaches the
Internet over cellular directly, so it cannot reach the home VLAN. The app therefore terminates the
ESP32's connection on the hotspot and opens its own connection, bound to the VPN network, to the
home relay. The WireGuard app stays the only VPN; the bridge creates no `VpnService`, needs no root,
and never falls back to a non-VPN route.

## Design: a constrained application relay

Two shapes were evaluated against the actual firmware and relay contracts
(`System_LLMHttp.cpp` / `System_LLMHttpTerminal.inc`, `relay/relay.py`):

| | Fixed-destination TCP forwarder | **Constrained application relay (chosen)** |
|---|---|---|
| What the phone does | Pumps bytes between the ESP32 and the relay; one TLS session end to end | Terminates the ESP32's TLS, admits two POST routes, opens its own TLS session to the relay |
| ESP32 certificate check | Breaks: `esp_http_client` verifies the relay certificate against the URL host, which is now the **hotspot address**, while the relay certificate names the **home IP** (`subjectAltName = IP:<home>`) | Works with current firmware: the ESP32 trusts the bridge's own CA (`llmhttpca`) and the bridge's certificate names the hotspot address |
| Needs firmware change | Yes (a `common_name` / expected-name override, see below) or a reissued relay certificate | No |
| Phone sees | Nothing (opaque TLS); cannot tell auth from certificate failures | Status codes and TLS outcomes, so errors are specific; the token and bodies pass through memory only |
| Restricts operations | Only by destination | Destination, method, the two paths, header vocabulary, body and reply sizes, deadlines |

The relay is deliberately thin: it does not interpret prompts or Terminal JSON. relay.py remains
the application boundary (fields, agent, namespaces); the bridge is a transport boundary that
mirrors relay.py's own limits (`MAX_BODY 14336`, `MAX_TERMINAL 4096`, `MAX_REPLY 1 MiB`,
`TOTAL_TIMEOUT 300 s`, `IO_TIMEOUT 30 s`, four clients) so it never admits what the relay would
refuse.

### Trust on each leg

- **Hotspot leg (ESP32 → phone).** TLS 1.2/1.3 with an ECDSA P-256 certificate issued by the
  app's own CA ("HW1 Bridge CA"). The CA private key is generated on the phone and stored encrypted
  with a non-exportable Keystore key (same `SecretBox` mechanism as the channel passphrase). The
  ESP32 is given only the CA certificate (`llmhttpca`), so the server certificate can be reissued
  for whatever address the hotspot has today without touching the device again. The server
  certificate's `subjectAltName` is exactly the hotspot IPv4 address; mbedTLS in the firmware
  (3.6.x) verifies IP SANs.
- **Home leg (phone → relay).** TLS pinned to the imported relay CA (`ca-cert.pem`) and nothing
  else: no system trust store, no plaintext option, no redirects. The platform hostname check is
  enabled and an explicit SAN check runs after the handshake; the relay certificate must name the
  configured host (its IP, as `setup_relay.py` issues it).
- **Authentication.** The ESP32's `Authorization: Bearer <device token>` header is validated for
  shape and passed to the relay unchanged. The phone never stores the token. The relay answers
  401/403 for a wrong token; the bridge forwards that and flags it as "rejected device token".
- **Routing.** Outbound sockets are bound per socket (`Network.bindSocket`) to the VPN network
  found through `ConnectivityManager` (`TRANSPORT_VPN`). The process is never bound to a network.
  Without a visible VPN network (WireGuard off, or HardwareOne excluded from the tunnel) the
  device receives `502` and the status says *no VPN*; the bridge does not try cellular or Wi-Fi.
  The tunnel's routes are checked first, so a missing `AllowedIPs` entry shows up as *not routed by
  VPN* rather than a vague timeout.
  The hotspot leg has the opposite constraint. Android routes *every* packet of an app covered by
  a non-bypassable VPN into the tunnel first (policy rule 13000, ahead of the hotspot's own rules
  at 16000/20000), so with `AllowedIPs 0.0.0.0/0` even the SYN-ACK answering the ESP32 leaves
  through WireGuard with the tunnel address as its source, and the device times out. No socket
  option an app can set changes that: the VPN rule tests only the "protected from VPN" mark, which
  only the VPN app itself can set. The runtime therefore asks the kernel which source address a
  reply to a hotspot client would carry (a connected UDP socket; nothing is sent) and the page
  reports *replies captured by the VPN* with the exact prefixes to fix in WireGuard. The listener
  socket is deliberately not bound to a network: the explicit-network rule sits below the VPN rule
  anyway, and once the tunnel leaves the hotspot subnet out, unbound sockets reach it through the
  local-network rule.
- **Logging.** Only categories, status codes, byte counts and addresses are recorded. No token,
  prompt or reply text is logged or kept.

### What is forwarded

| Device request | Bridge behaviour |
|---|---|
| `POST /v1/chat/completions`, JSON, 1–14336 bytes, bearer token | Forwarded; the reply (`text/event-stream`) streams through as bytes arrive, up to 1 MiB and 5 minutes; a stream that closes without `data: [DONE]` gets an SSE `event: error` and is recorded as *outcome unknown* |
| `POST /v1/terminal`, JSON, 1–4096 bytes, bearer token | Forwarded; the JSON reply must carry `Content-Length ≤ 4096` and is delivered exactly; 30-second deadline |
| Anything else | Answered locally (404/405/401/413/415/431/408/400) without contacting the relay |
| Second request on a route while one is in flight | `503 Busy`, as relay.py does |
| Fifth concurrent connection | Closed without a reply, as relay.py does |

Nothing is ever retried. If the relay closed the connection after the request was written, the
device gets `502` (or an SSE error event mid-stream) saying the outcome is unknown; the firmware
already treats such turns as uncertain and never replays a prompt.

## Setup

### Home (already in place for the relay)

- The HardwareOne G2 Terminal Bridge relay is running on the home server with its
  private CA, listening on the LAN address in its certificate (default port 18790). Optional
  Terminal (`/v1/terminal`) works through the same relay.
- WireGuard: the phone's tunnel must route the relay's address and must **not** route the hotspot
  subnet. `AllowedIPs = 0.0.0.0/0` fails the second condition: Android then sends the bridge's
  replies to hotspot clients into the tunnel (Design → Routing) and the ESP32 cannot connect. In
  the WireGuard app edit the tunnel, tap **Exclude private IPs** under Allowed IPs, then add the
  home prefixes back, e.g. `192.168.1.0/24, 10.66.66.0/24` (the LAN with the relay, and the
  tunnel's own subnet). More specific entries win, so those still go through the tunnel while the
  randomized hotspot subnet (`10.x`, `172.16.x` or `192.168.x`) does not; a plain split tunnel
  with only the home prefixes works too. The HardwareOne app must **not** be in WireGuard's
  *Excluded applications* (or must be in *Included applications* if that mode is used). "Always-on
  VPN" is recommended so the tunnel survives cellular handovers and reboots; *Block connections
  without VPN* (lockdown) must stay off (Android lifecycle, below).
- Gateway firewall: if the relay's VLAN is isolated from the VPN client network (a common
  zone-based or "isolate network" setup), add one rule allowing the VPN client subnet to reach the
  relay host on its TCP port (e.g. `192.0.2.0/24 → 192.0.2.10 TCP 18790`), placed before the
  rule that drops VPN → internal traffic. Nothing else needs opening; ICMP may stay blocked. A
  quick check from the relay host is `ping <phone tunnel address>` while the phone is connected:
  if the relay works on the LAN but that ping fails and the bridge reports *unreachable*, the
  gateway is the cause, not the relay.

### Phone

0. **GrapheneOS only:** the OS treats `INTERNET` as a per-app *Network* toggle and leaves it
   **off** for an app that gains the permission in an update. Enable it under Settings → Apps →
   HardwareOne → Permissions → Network, or the bridge cannot open a single socket. The page says
   so if it is missing.
1. Copy the relay's **public** `ca-cert.pem` to the phone (anything that can share a file).
2. In the app: **⚙ Settings → Home AI bridge**. Enter the relay host (the IP named in its
   certificate) and port, tap **Import CA** and choose `ca-cert.pem`, then **Save**.
3. Turn on the Wi-Fi hotspot. In the hotspot settings disable *Turn off hotspot automatically*,
   otherwise Android switches it off ten minutes after the ESP32 last connected.
4. Tap **Start**. Allow the notification. The status card shows the **hotspot listener**
   (address and port, or why it is waiting) and the **home relay** (reachable / why not),
   separately. **Test relay** sends an unauthenticated probe (the relay answers 401; no prompt is
   sent).
5. The *Device settings* card shows exactly what to apply on the ESP32.

### HardwareOne (one-time CA, URL whenever the hotspot address changes)

On the device's web CLI or serial console (the CA command is ~550 bytes, longer than one
Bluetooth command carries):

```text
llmhttpca -----BEGIN CERTIFICATE-----\n…\n-----END CERTIFICATE-----
llmhttpurl https://<hotspot-address>:18790/v1
llmhttpplaintext 0
llmhttpenabled 1
```

**Copy commands** on the bridge page puts the exact lines on the clipboard. While the app is
logged in over Bluetooth (device in `blemode server`), **Send URL via BT** pushes `llmhttpurl`
directly; **Send CA via BT** appears only when the command fits the live link. `llmhttptoken` and
`llmhttpmodel` stay as configured for the relay. The ESP32 must also have the hotspot saved as a
Wi-Fi network and a valid clock (NTP works through the hotspot's ordinary Internet path).

Then switch the device back to glasses mode (`blemode client`): the bridge keeps working with the
console disconnected.

Android randomizes the hotspot subnet: on the Pixel Fold (GrapheneOS, Android 17) it came up as
`10.42.0.197/24`, and other builds use `192.168.x.1`. The bridge binds whatever private IPv4
address the tethering interface has, reissues its server certificate for it, and shows the
resulting URL; the device's `llmhttpurl` must be updated whenever that address changes (the page
flags the mismatch and remembers the URL you last applied). If your phone keeps a stable address,
nothing changes.

## Status and errors

| Shown | Meaning / what to do |
|---|---|
| Hotspot listener: *Waiting — no hotspot interface found* | Turn on the hotspot. The bridge rechecks every 5 s and on tethering changes. |
| Hotspot listener: *Could not listen on … (BindException)* | Another app uses the port; change the listen port under Advanced, then update `llmhttpurl`. |
| Hotspot listener: *Listening … — but unreachable from the hotspot*, "Replies to hotspot clients are routed into the VPN" | WireGuard's AllowedIPs cover the hotspot subnet. Exclude private IPs and re-add the home prefixes (Setup → Home), reconnect the tunnel; the line clears by itself within a few seconds. |
| Hotspot listener: "Android refused to route a reply to hotspot clients" | VPN lockdown (*Block connections without VPN*) is on and the tunnel does not route the hotspot subnet, so the kernel prohibits the reply. Turn lockdown off. |
| Home relay: *no VPN* | WireGuard is off or excludes this app. Connect it; check its application exclusions. |
| Home relay: *not routed by VPN* | The tunnel's AllowedIPs do not cover the relay address. |
| Home relay: *unreachable* | TCP to the relay failed through the tunnel: relay process down, wrong host/port, the home side of the tunnel down, or a gateway firewall that lets VPN clients reach the gateway but not the relay's VLAN (see Setup → Home). |
| Home relay: *certificate untrusted* | The relay's certificate is not signed by the imported CA. Re-import `ca-cert.pem` from `~/.openclaw/hardwareone-relay`. |
| Home relay: *certificate name mismatch* | The configured host is not the address in the relay certificate. Use that address. |
| Home relay: *rejected device token* | The ESP32's `llmhttptoken` does not match the relay's `device-token.txt`. |
| Last request: *outcome unknown* | The request reached the relay but the reply did not complete. Check the OpenClaw session before sending it again; the agent may have acted. |
| Last request: *rejected locally · HTTP 404/401/413…* | The device sent something outside the contract (e.g. `llmhttpurl` not ending in `/v1`, empty token, oversized body). |
| Device: *TLS handshake failed* | The ESP32 does not trust the bridge CA, or its clock is wrong (`time json`). |

## Limits

| Bound | Value |
|---|---|
| Request head | 8 KiB, 32 lines |
| Chat body / Terminal body | 14336 / 4096 bytes (relay.py) |
| Chat reply / Terminal reply | 1 MiB streamed / 4096 bytes |
| Device handshake, head, body | 5 s each |
| Relay connect / handshake | 10 s each |
| Relay silence before headers | 65 s (the firmware gives up at 60 s; the bridge never fails first) |
| Relay silence after headers (agent still thinking) | up to 300 s; every 20 s of silence the bridge sends the device an SSE comment line (`: keep-alive`), which resets the firmware's 60 s stall timer and is ignored by its parser |
| Whole request | 300 s chat, 30 s Terminal |
| Concurrent device connections | 4; one in-flight request per route |
| Wake lock | Partial, only while a request is in flight, hard timeout 315 s |

## Android lifecycle

The bridge runs as a foreground service of type `connectedDevice` with an ongoing, silent
notification (tap: opens the page; **Stop** action). Android 14+ requires a declared prerequisite
permission for that type; `CHANGE_NETWORK_STATE` (normal, auto-granted) is declared for this and
is never used to change anything. The service is sticky: if Android kills the process the bridge
restarts with the saved configuration, as long as the user left it enabled. Starting it requires
the app to be in the foreground (Android's background-start rule); the page says so if a
background restart was refused.

Doze does not suspend networking for an app with a foreground service, and incoming TCP wakes the
radio. The hotspot's own auto-off timer and WireGuard's reconnection are outside the app's control.

**VPN lockdown ("Block connections without VPN") and local-network restrictions.** Lockdown adds
a *prohibit* rule right after the VPN rule, so once the tunnel no longer routes the hotspot subnet
the kernel refuses the bridge's replies to hotspot clients outright. The reply-path check reports
this as *Android refused to route a reply*; the bridge cannot work until lockdown is off (the VPN
itself may stay always-on). Android 16+ local-network protection is not enforced for this app's
`targetSdk` (35) on the tested build; raising the target will require the local-network permission
for the listener's replies.

## Verification

### Verified locally (JVM unit tests, `./gradlew :app:testDebugUnitTest`)

`app/src/test/java/com/hardwareone/console/bridge/` runs real TLS on loopback between a fake ESP32
(trusting only the bridge CA), the bridge, and a fake relay (its own CA, scripted replies):

- streamed chat replies pass through incrementally (first event delivered before `[DONE]` exists) with only relay.py's allowed header names upstream, the device token unchanged;
- Terminal JSON replies pass through with exact `Content-Length`;
- 401 from the relay passes through and is flagged as a token problem;
- unknown path, non-POST, missing/malformed token, wrong content type, oversized body, chunked request, oversized head, silent device: refused locally with the matching status, no relay connection;
- untrusted relay CA, relay certificate for another host, unreachable relay, no VPN: distinct failure categories, nothing sent;
- relay closing before headers or mid-stream: `502` / SSE `event: error`, recorded as outcome unknown, exactly one relay connection (no replay);
- device disconnect mid-stream closes the relay connection; the bridge's own deadline and idle timeout end a silent stream as outcome unknown;
- a relay that is silent after its headers gets SSE comment heartbeats forwarded to the device until it speaks (reply completes, heartbeats not counted as relay bytes) or until the idle limit (SSE error, outcome unknown);
- concurrency: `503 Busy` per route, extra connections closed, Terminal not blocked by a chat in flight;
- CA/leaf generation, PEM round trip, SAN checks, config validation, the HTTP head parser's limits.

### Verified on the Pixel Fold (GrapheneOS, Android 17), 2026-10-14

- Install over the existing app with data kept; the service starts as a `connectedDevice`
  foreground service with its notification; START works immediately after configuration.
- GrapheneOS left the new Network (INTERNET) permission off; the app now reports that case.
- With Wi-Fi off, cellular on and WireGuard up, the app binds to the VPN network and the relay
  probe returns the relay's 401 through the tunnel, with the pinned CA and hostname check.
  Before the gateway rule existed the probe reported *unreachable* with a connect timeout, as
  designed; the fix was one gateway firewall rule (VPN subnet → relay host TCP 18790).
- With the hotspot on, the listener bound to the tethering interface (`ap_br_wlan2`,
  `10.42.0.197:18790`) and accepted TCP. Android registers that downstream as a `LOCAL_NETWORK`
  Network; the detector keeps such networks eligible and lists skipped interfaces in the waiting
  message.
- The P4 (firmware 0.99.98.2, C6 radio) joined the hotspot once its security was set to
  WPA2/WPA3 (the Android 17 default, WPA3-only, is refused by the ESP, reason 202) and synced NTP
  through it. Its first `llmgenerate` then failed on the ESP side with `esp-tls: select() timeout`
  two seconds into the connect, and the bridge recorded no connection at all. On the phone,
  `ip rule` and `ip route get 10.42.0.106 uid <app uid>` showed why: WireGuard's
  `AllowedIPs 0.0.0.0/0` makes rule 13000 send the app's reply into `tun0` (source
  `10.66.66.2`) for every socket mark, so the ESP32 never receives the SYN-ACK. Lockdown was
  off. The fix is on the WireGuard side (Setup → Home); the bridge now detects the condition
  and names the prefixes.
- With the tunnel limited to the home prefixes, the next `llmgenerate` connected: TLS against the
  bridge CA, the request forwarded through the tunnel, the relay answered `200 text/event-stream`
  (token accepted). The agent then produced no event for a minute, the firmware's 60 s stall
  timer closed the connection, and the bridge recorded *chat · cancelled by device · HTTP 200 ·
  0 bytes* and closed the relay side. The firmware's SSE parser ignores comment lines, so the
  bridge now sends `: keep-alive` comments while the relay is silent (Limits).
- **First complete turn through the bridge, 07:38.** With the heartbeat build installed and the
  device in glasses mode (console disconnected), `llmgenerate json hello` from the P4 completed
  end to end: *chat · completed · HTTP 200*, 122 bytes forwarded, 47 s from request to `[DONE]`.
  The agent's first token took well over 20 s, so the device received heartbeats before the
  reply; without them the same turn had died at the firmware's 60 s stall. The agent's latency
  is a property of the home side (OpenClaw), not of the bridge; the LAN path has the same
  60 s limit and no heartbeat. `llmresult json 0` on the device returned the agent's greeting
  with `done:true` (52 characters).
- **VPN loss, 07:43.** With WireGuard disconnected and the bridge left running, the next
  `llmgenerate` ended within five seconds with the firmware's own `[Agent HTTP 502; request was
  not retried]`: the bridge refused to forward without a VPN network and answered 502, no
  cellular fallback, nothing sent. The page reports *no VPN* until the tunnel returns.

### Still to be checked on hardware

Each is a go/no-go step; the bridge page's two status lines are the instrument.

1. ~~**Hotspot reachability from a client.**~~ Verified above: a full chat turn from the ESP32
   through the bridge and the tunnel to the relay completed (2026-10-14 07:38). Terminal
   (`/v1/terminal`) followed at 07:5x: a Terminal session from the glasses ran a long tool call,
   and the device's `poll` operations each passed through as one completed JSON request, so the
   page's *completed* counter climbs quickly during a long Terminal turn. That is the protocol
   (the device polls the host; nothing is retried by the bridge), not a loop.
2. ~~**VPN loss.**~~ Verified above (07:43): `502` to the device within seconds, no fallback.
3. **ESP32 Wi-Fi + glasses Bluetooth concurrently.** Device in `blemode client` with both temples
   up and the hotspot joined; a Hey Even / Terminal turn completes through the bridge. (The
   firmware's own P4 notes record Wi-Fi + both temples connected; the bridge adds no radio work.)
4. **Locked screen.** Lock the phone for 15+ minutes, then run a turn from the glasses; the
   notification/last-request time updates. Repeat after a cellular handover and after toggling
   WireGuard and the hotspot: the ESP32 reconnects without the app being opened.
5. **Hotspot address change.** Reboot the phone; if the address changed, the page flags it and
   the pushed `llmhttpurl` restores service.
6. **Lockdown.** Repeat step 1 with WireGuard's *Block connections without VPN* on.
7. **Regression.** Bluetooth console, Files and OTA with the bridge running. Deferred: the
   device is either a Bluetooth server (console) or a client (glasses), never both, so while
   the glasses are in use the console is unavailable regardless of the bridge. The bridge
   itself needs no console connection; a dual-role firmware is a separate project.

A successful build and the local tests above do not establish any of these.

## Firmware notes

No firmware change is required for this design. Two optional changes would make it more
convenient; neither has been made, and installed firmware was not modified:

1. **Stable address.** Let `llmhttpurl` accept a fixed alias for the DHCP default gateway (the
   hotspot phone), e.g. resolve a reserved host name to the gateway at request time and verify the
   server certificate against that alias (`esp_http_client_config_t.common_name`, available in
   ESP-IDF 5.5). The bridge would then put a matching dNSName SAN in its certificate and the device
   URL would never change with the hotspot subnet. Owner: `System_LLMHttp.cpp` URL/TLS configuration.
2. **CA provisioning over Bluetooth.** `llmhttpca` takes the whole PEM in one command (~550 bytes
   for the bridge's P-256 CA), which exceeds one BLE command (512 bytes plaintext, `MTU − 28`
   on the secure channel). A file-based form (`llmhttpca @/path.pem`) or an append mode would let
   the app provision it over the console link. Until then the CA is set once via the web CLI or
   serial.

Also relevant, unchanged: `blemode server` (console) and `blemode client` (glasses) remain
mutually exclusive, so device provisioning over Bluetooth happens in server mode, after which the
device is switched back; the bridge itself does not need the console connection.
