# BLE relay pipe — Android side (stage 4, HW1-PIPE-v1)

**Status: experimental; qualified on hardware on 2026-10-17, with the gaps listed under
[Qualification](#qualification--results-2026-10-17).** A Pixel on GrapheneOS carried a HardwareOne P4's
relay traffic under this contract in both P4 client modes, through the phone's VPN, and recovered by
itself from the lifecycle events below. Timings quoted elsewhere on this page are design targets
unless the Qualification section gives a measurement.

The contract is the Android ↔ firmware decision record kept with the firmware project, with the
reasoning in its Android/firmware exchange notes. The IDs in
brackets (R13, FW-5, …) are its item IDs, which the code comments also cite. Where this page and the
firmware working source disagree, the firmware wins for interop and the difference is a bug to report.

## What it is

When a HardwareOne ESP32-P4 is away from usable Wi-Fi it can reach its existing HTTPS relay through
the phone:

```
P4 HTTP → P4 TLS → pipe frames → HW1-PIPE-v1 over BLE → this app (GATT server) → TCP socket → VPN → relay
```

- **TLS is end to end between the P4 and the relay.** The app forwards opaque bytes. It never
  decrypts TLS, parses or rewrites HTTP, injects tokens, substitutes certificates, installs a CA or
  acts as a proxy. The P4 keeps the same URL, CA, certificate identity and token on both paths.
- **The BLE hop has its own encryption.** Every pipe frame travels inside HW1-PIPE-v1, an app-layer
  channel keyed by a dedicated pipe passphrase that both sides hold. There is no Bluetooth pairing,
  no bond and no address authority: proving the passphrase is the only admission.
- The phone sees only the destination, byte counts and timing.
- **The phone is the BLE peripheral**; the P4 is always the central. No hotspot, no tethering, no
  port forwarding, no VPN on the P4.
- Path selection (Wi-Fi first, one pre-HTTP fallback) is the P4's job. The app serves streams; it
  never picks routes, reconnects a failed socket or replays bytes.
- The pipe is separate from the console link and its Secure Channel v1: it shares no code, key or
  bond with it. (It replaces the hotspot-based Home AI bridge, which terminated TLS on the phone and
  was removed in 2.7.1.)

Code: `app/src/main/java/com/hardwareone/console/pipe/`, screen `ui/PhonePipeScreen.kt`.

| Responsibility | Owner |
| --- | --- |
| Pure bounded protocol/stream state (no Android, no sockets, no clocks) | `PipeSession`, `PipeWire`, `PipeTarget` |
| HW1-PIPE-v1 initiator: handshake, DATA sealing, unsigned counters | `PipeSecureChannel` |
| Passphrase entry rules and PSK derivation | `PipeSecret` |
| One session at a time, one notification outstanding, completion deadline, teardown, per-stream sockets, generations | `PipeEngine` |
| VPN-bound DNS + single TCP connect | `VpnPipeConnector` (`PipeNetwork.kt`) |
| GATT server instances, CCCD admission, advertising, instance retirement | `PipeGattServer` |
| ATT request decision table | `PipeAttPolicy` |
| Global 30 s authentication cooldown | `PipeCooldown` |
| Secret and destination storage | `PipeStore`, `PipeConfig` |
| Foreground service, boot/update receiver, runtime (PSK, VPN tracking, adapter receiver, wake lock) | `PhonePipeService`, `PipeBootReceiver`, `PipeRuntime` |
| Process-wide state, intents and status wording | `PhonePipe` |

Threads: Bluetooth callbacks only copy their value and queue an event. One engine thread processes
events in order, submits notifications and runs timers, and never holds the engine lock across the
notify binder call. A separate watchdog thread enforces the notification deadline. Broadcasts, VPN
changes and session ends hop onto one runtime thread. Each stream has its own connect, reader and
writer threads, which block only on their own socket.

## Prerequisites

**Phone**

- Android 8.0+ (API 26) with BLE peripheral support (`isMultipleAdvertisementSupported()`); the
  page says so if advertising is impossible. Qualification uses a Pixel on GrapheneOS 2026061800
  or later (earlier builds can hold every notification completion forever after a congested
  disconnect).
- The app built from this repository, installed as an ordinary APK. No Google services.
- Permissions: **Nearby devices** (`BLUETOOTH_CONNECT` + `BLUETOOTH_ADVERTISE`, Android 12+) is
  required. Notifications (Android 13+) are optional but make the service and the tap-to-resume
  fallback visible. On GrapheneOS the per-app **Network** toggle must be on.
- **WireGuard** (or any VPN app — Android does not let an app tell which) with a tunnel whose
  AllowedIPs route the relay address, and **this app not excluded** from it. If the P4's URL uses
  a DNS name, the tunnel needs a DNS server (`DNS =` in WireGuard) that its own AllowedIPs route;
  otherwise every DNS-name OPEN is refused with code 5 rather than resolved outside the tunnel. An
  IP-literal URL needs no DNS.
- *Always-on VPN* is recommended so the tunnel comes back by itself. *Block connections without
  VPN* (lockdown) is optional: the pipe fails closed on its own (VPN-bound DNS and sockets, route
  checks, stream teardown on loss).
- One-time recovery setup (see "Lifecycle and recovery"): battery **Unrestricted**, GrapheneOS
  Bluetooth timeout left **off** (the default).

**P4 firmware**: a stage-4 build with `ENABLE_BLE_PHONE_PIPE`, not modified by this work; flashing
is coordinated separately. Firmware items F1, F2, F3, F5, A-1, A-3, A-4, A-5 and fixtures X1–X3 are
agreed but not delivered.

## Setup

Nothing is learned from traffic. The phone serves exactly one relay destination and refuses an
OPEN for anything else before DNS or TCP.

1. **On the P4**, as the local admin on its serial console (never over Bluetooth): set the pipe
   passphrase with `phonepipesecret <passphrase>`, select `blemode phone-client` and run
   `phonepipe start`. These commands are the firmware's; its docs are authoritative. In G2 + phone
   mode the phone attaches to the existing central host and, until firmware F2 lands, is admitted
   only while both G2 arms are connected.
2. **On the phone**: ⚙ Settings → **BLE relay pipe**.
   - Enter the same passphrase. The field checks the rules below as you type and shows the
     byte count. It never leaves the phone.
   - Enter the P4's AI base URL exactly as configured there, for example
     `https://relay.example.com:8443/v1`, and tap **Approve destination**. The page shows what will
     be approved (`relay.example.com:8443`, DNS name). IP literals such as `https://192.0.2.10/v1`
     or `https://[2001:db8::10]:8443/v1` work too. Forms the firmware would read differently are
     refused with a reason: shorthand/octal/hex IPv4, IPv6 zone IDs, embedded dotted IPv4 or
     IPv4-mapped IPv6, trailing dots, empty or over-long labels, U-labels (use the `xn--` form),
     ports outside 1–65535.
   - Tap **Start** and grant Nearby devices (and notifications) if asked. The status reads
     *Waiting for HardwareOne*.
3. **On the P4**: `phonepipe enroll` while the phone is advertising, then `phonepipe status`. The
   phone goes *Authenticating* → *Secure channel up* → *Ready (phone side)*, and *Ready* once the P4
   sends its first OPEN. Until firmware F1 makes enrollment set it, also run
   `bleautoreconnect phone on` so the P4 reconnects by itself after the link drops.

The P4 finds the phone by a short passive scan for the service UUID, and Android cannot advertise
faster than about every 100 ms, so an attempt can report `phone not found`; just retry.

The service keeps running (notification "BLE relay pipe", with a **Stop** action) until stopped
from the page or the notification. It is a `connectedDevice` foreground service so that it keeps
running with the screen locked.

## Bluetooth surface

| Attribute | UUID | Properties | Permissions |
| --- | --- | --- | --- |
| Service | `b7e10000-6f7d-4b2e-a8c4-3d9f5021c6a0` | primary | — |
| RX (P4 → phone) | `b7e10001-6f7d-4b2e-a8c4-3d9f5021c6a0` | write without response | plain write (+ read, so a read reaches the app and gets 0x06) |
| TX (phone → P4) | `b7e10002-6f7d-4b2e-a8c4-3d9f5021c6a0` | notify | plain read |
| TX CCCD | `0x2902` | — | plain read + write |

- **No SMP.** The app never calls `createBond`/`removeBond`, sets no encryption or MITM permission
  and keeps no P4 address. The console channel and every other bond on the phone are untouched.
  The P4 refuses SMP requests.
- **Advertising:** connectable legacy advertising, `ADVERTISE_MODE_LOW_LATENCY` (~100 ms), flags
  plus the 128-bit service UUID in the primary payload (21 of 31 bytes), no name, no TX power, no
  scan response, no endpoint. It runs while the server is open, the pipe is configured with its
  PSK derived, nobody is admitted, no instance is being retired and no cooldown runs. It is never
  gated on Wi-Fi or the VPN.
- The service is never added or removed while a session is up.

ATT requests (`PipeAttPolicy`):

| Request | Answer | Effect |
| --- | --- | --- |
| RX write command from the admitted peer | none | delivered to its session |
| RX write command from anyone else | none | dropped |
| RX write request (prepared or not) | 0x06 (0x07 for a nonzero offset) | ends the session if from the admitted peer |
| Write request to any other characteristic | 0x06 | none |
| First valid CCCD `01 00` | 0x00 | admission (below) |
| CCCD `01 00` from a second device, during the cooldown or an instance retirement, or before the PSK is ready | 0x11 | none |
| CCCD `01 00` on a link with negotiated MTU < 64 | 0x0d | none |
| CCCD `01 00` again from the admitted peer | 0x0e | session ends |
| CCCD `00 00` from the admitted peer | 0x00 | session ends; nothing more is sent |
| CCCD of any other 2-byte value (`02 00`, …) | 0x06 | session ends if from the admitted peer |
| CCCD of wrong length / prepared at a nonzero offset / malformed write command | 0x0d / 0x0b / none, answered or dropped by the Android stack itself | none: the app never sees it |
| CCCD write command of length 2 from the admitted peer | none | session ends |
| Any read, Execute Write | 0x06 | none |

A write request that reaches a retired server instance gets 0x06 (a CCCD 0x11). None of the
refusals is charged to the cooldown.

## Admission

Android reports every LE link to every GATT server, including links where the phone is the client
(this app's own console link) and other apps' links. A connection is therefore never a candidate.

1. **Admit** on the first valid CCCD `01 00` write request when the negotiated MTU is at least 64,
   no cooldown runs, the PSK is ready and nobody is admitted. The secure channel and HELLO are
   prepared before the answer; the app then answers 0x00, stops advertising and calls
   `server.connect(device, false)` for that device only, so that `cancelConnection` can later drop
   it. HELLO is submitted right after. A failed link-holder `connect` ends the session as a
   transport failure.
2. **Refuse** any other device's CCCD, and any CCCD during the cooldown, with 0x11. MTU < 64 gets
   0x0d. A duplicate, malformed or disabling CCCD from the admitted peer ends that session.
3. **Unrelated links** are never held, cancelled or disconnected.
4. **Resume advertising** after the session's teardown, or when the cooldown ends.

Every admission gets a fresh session token. Callbacks are forwarded only when they come from the
admitted device on the admitted server instance, and the engine drops any event whose token is not
the current session's (shown as "Stale callbacks dropped").

## HW1-PIPE-v1 handshake

The phone initiates, right after its CCCD answer. `PipeSecureChannel` is a new class, wire-identical
to the firmware responder; it does not reuse the console `SecureChannel`.

| Message | Direction | Bytes | Layout |
| --- | --- | --- | --- |
| HELLO | phone → P4 | 49 | `01 ‖ phonePub(32) ‖ phoneNonce(16)` |
| HELLO_ACK | P4 → phone | 49 | `02 ‖ devicePub(32) ‖ deviceNonce(16)` |
| CONFIRM | phone → P4 | 19 | `03 ‖ AEAD(kP2D, nonce(0, 0), "ok")` (2 + 16 tag) |
| CONFIRM_ACK | P4 → phone | 19 | `04 ‖ AEAD(kD2P, nonce(1, 0), "ok")` |
| REJECT | P4 → phone | 2 | `05 ‖ reason` (handshake only) |
| DATA | both | 25 + n | `10 ‖ counter(u64 BE) ‖ ciphertext(n) ‖ tag(16)` |

Key schedule:

- **PSK** = PBKDF2-HMAC-SHA256(passphrase bytes, salt = ASCII `HW1-PIPE-v1` (11 bytes, no NUL),
  100 000 iterations, 32 bytes). Derived on the runtime thread when the secret loads, before
  advertising, never in a Bluetooth callback.
- **Shared secret** = X25519(fresh ephemeral private key, devicePub), with a fresh 16-byte nonce per
  session. A low-order point or all-zero result is an authentication failure.
- **Keys** = HKDF-SHA256(ikm = shared ‖ PSK, salt = phoneNonce ‖ deviceNonce, info = `HW1-PIPE-v1`,
  L = 64): `kP2D` = bytes 0–31 (phone → P4), `kD2P` = bytes 32–63 (P4 → phone).
- **AEAD**: ChaCha20-Poly1305 (IETF), 16-byte tag, no AAD. The 12-byte nonce is
  `direction(u32 BE) ‖ counter(u64 BE)`, direction 0 = phone → P4, 1 = P4 → phone. Counter 0
  belongs to CONFIRM and CONFIRM_ACK.

**Counters** are unsigned 64-bit, big-endian:

- TX starts at 1 and increases by one per DATA. It never emits 2^64−1: exhaustion is a silent local
  teardown with no cooldown.
- RX accepts a counter only if it is unsigned-greater than the last accepted one (so 0 never is;
  gaps are allowed) and commits it only after the tag verifies. An authenticated 2^64−1 is accepted
  once; the next DATA then fails the strict-increase rule.
- Counters never reset under a key, and there is no resumption.

**Order.** CCCD answer → HELLO → HELLO_ACK → CONFIRM → CONFIRM_ACK verified (established). The P4's
first DATA is counter 1 carrying inner HELLO sequence 0; the phone answers with counter 1 carrying
HELLO_ACK sequence 0. Nothing else is sent before that. Each inner frame (12-byte little-endian
header plus body) travels as exactly one DATA value.

**Deadlines.** Android's 4.5 s runs from its CCCD answer to CONFIRM_ACK, inside the P4's 5 s from
CCCD submission. The inner-HELLO 3 s starts when CONFIRM_ACK verifies; time spent in our own CCCD
answer and link-holder call counts as a local stall, never as the peer's fault. A short wake lock is
taken synchronously before the CCCD answer and the moment a stream's OPEN is admitted, then a
partial wake lock covers the handshake and any teardown; the engine's deadlines and the cooldown
run on `elapsedRealtime`, so they keep counting while the phone is locked.

**Failures.** A crypto or outer-channel failure ends the session silently: no frame is sent, and the
channel wipes its keys. Nothing is ever sent in plaintext after the handshake. Each session's
ephemeral key, shared secret, session keys and counters are wiped at its end. The PSK stays cached
in memory while the pipe is configured and running, and is wiped on a secret change, Forget, Stop
and service shutdown. On the JVM this zeroing is best effort.

## Sizes

The negotiated ATT MTU must be at least 64 before the CCCD. Then `V = min(MTU − 3, 512)` is the
largest GATT value, `P = V − 25` the largest inner frame one DATA carries, and `P − 12` the largest
inner DATA body.

| ATT MTU | V | P | Inner DATA body |
| --- | --- | --- | --- |
| 64 | 61 | 36 | 24 |
| 244 (P4 in G2 mode) | 241 | 216 | 204 |
| 247 | 244 | 219 | 207 |
| 517 (P4 standalone) | 512 | 487 | 475 |

The inner HELLO must offer 20–512 and exactly two streams. HELLO_ACK echoes the P4's nonce, carries
a nonzero phone nonce, says max_streams 2 with reserved bytes 0, and chooses `min(P, offer)` (at
least 20). Everything from HELLO_ACK on is framed at that size.

Before every submit the app checks `value ≤ min(MTU − 3, 512)` and the inner frame against the
limit in use. A violation ends the session locally without submitting: the stack would otherwise
truncate the value silently and still report success.

## Notifications

`onNotificationSent` reports the local enqueue only. Nothing later says the value was transmitted,
received or acted on.

- **One outstanding.** The next notification goes only after the previous completion *and* at
  least 30 ms after the previous submission. 130 ms survives only as a legacy test scenario.
- **Status 0 or 0x8f** (congested, message accepted) = queued locally. Neither is proof of
  delivery.
- **Terminal, with no replay and no RESET:** any other status; a non-SUCCESS return;
  `ERROR_UNKNOWN`; an exception; no completion within 2 s of the submit call. The 2 s starts just
  before the call, so it includes time blocked in the binder; the watchdog thread enforces it even
  if the call never returns, and a SUCCESS returned after it is still terminal. A late return or
  callback never revives a session.
- **A disconnect is terminal** even when a completion arrived just before it.
- **Receipt** shows only in the P4's authenticated responses (its inner sequence, OPEN, CREDIT,
  CLOSE_ACK, PONG). Even those do not prove delivery or execution at the relay, and TCP acceptance
  is not execution.

## Server instances and retirement

A completion carries no operation ID, so a token alone cannot prove which submission it belongs to.
Isolation comes from the server instance (FW-5):

1. Every `openGattServer` gets a fresh instance with its own callback object and numeric ID.
   Callbacks reaching a retired or replaced instance are dropped.
2. An instance has at most one unsettled submission over its whole life. A submission is settled
   by its completion (any status), or by a definite refusal before anything reached the stack: a
   non-SUCCESS other than `ERROR_UNKNOWN`, `IllegalArgumentException`, `NullPointerException` or
   `SecurityException`.
3. A session that ends with a submission unsettled (deadline, `ERROR_UNKNOWN`, a dead binder, a
   disconnect with no completion, or no completion within 300 ms of the teardown) **retires** its
   instance. The app stops advertising, cancels the link, waits for its disconnect callback (at most
   2 s), closes the server, opens a new instance, adds the service and only then advertises again.
   CCCDs during that time get 0x11.

A late callback or a delayed send can therefore never touch a later session's frame, even on a new
connection to the same P4. The cost: re-adding the service sends Service Changed to any other peer
still connected. It happens only after an ambiguous failure, and only once the P4 link is gone.

A failed `openGattServer`, `addService` or advertising start is retried with backoff (2 s, 5 s,
15 s, then every 30 s), checked once a second.

## Session ends, cooldown and RESET

Every session end has one kind, which decides the cooldown and whether an encrypted RESET is
allowed:

| Kind | Causes | 30 s cooldown | Encrypted RESET |
| --- | --- | --- | --- |
| Authentication | Malformed, wrong-size or out-of-phase HELLO_ACK or CONFIRM_ACK; X25519 failure; CONFIRM_ACK tag failure or plaintext other than `ok`; a received `05 xx` during the handshake; after establishment any outer error (bad tag, counter not increasing, wrong type byte, value shorter than 25); the P4 letting the 4.5 s handshake expire | charged | never |
| Transport | Disconnect, Bluetooth off, the CCCD rules above, failed link-holder `connect`, notification refused/failed/uncertain/late, oversize value, TX counter exhaustion, event backlog, a handshake expiry after a local stall | no | never |
| Protocol | Authenticated inner failure: bad frame, sequence or flow, inner HELLO (3 s), reassembly (2 s) or PONG (5 s) deadline, the P4's own stream-0 RESET | no | when safe, never answering the P4's RESET |
| Cancelled | Stop, Forget, secret change, service shutdown | no | RESET 14 when safe |

**Cooldown** (R14). One global window of 30 s on `elapsedRealtime` (it counts through sleep),
charged once per failed attempt, before the admission slot is freed, and never shortened by a
later charge. While it runs, advertising is off and every CCCD gets 0x11. Saving a new passphrase
clears it. Android never sends REJECT. A handshake expiry is charged only when the stage was
waiting on the P4 and Android's own steps were done: CCCD answered within 1 s, each handshake
notification completed within 1 s of its submit, nothing outstanding (A-5). The page says
"Authentication failed or timed out", never "wrong passphrase", because a wrong key and a peer
stall look the same.

**Encrypted RESET** (R7). A best-effort stream-0 RESET is sealed as one more DATA only when the
channel is established, HELLO_ACK's notification has completed, no submission is outstanding or
unsettled, the link is not known to be down, the inner sequence is not exhausted, and the end is
not the P4's own RESET. It goes out after the usual 30 ms gap; the app then disconnects about
200 ms after the stack takes it and never waits for it to arrive. Every teardown ends with
`cancelConnection` on the held link unless the link is already down.

## Streams

The inner protocol is unchanged by stage 4:

- **At most two streams.** A third OPEN gets OPEN_ERR 9 without admission. OPEN order: free slot →
  allowlist (byte-exact type/host/port) → VPN present → connect-thread cap, all before any DNS or
  TCP. Then one VPN-network DNS resolution (DNS names only; the first answer the tunnel routes), a
  route check, a socket bound to the VPN network, and **one** connect. DNS plus connect is bounded at
  2.5 s from the reassembled OPEN (the agreed ceiling is 3 s). OPEN_OK only after the TCP connect; no
  retry, no second address.
- **IDs** are nonzero and strictly increasing; gaps are allowed and skipped IDs are retired. A
  non-OPEN frame for an ID above the high-water mark is a session error. Only inner sequences are
  contiguous (the inner 32-bit sequence stops at 0xFFFFFFFE).
- **Flow control:** 4 KiB ring per direction per stream. OPEN_OK grants the design's initial 1024
  (as in the firmware's goldens); every later credit to the P4 is `consumed + 4096` (Q11), coalesced at 512 bytes or 100 ms, where *consumed* means accepted by a TCP
  write. Grants from the P4 above `sent + 4096` or below a previous grant are flow errors.
- **Ends:** TCP EOF → FIN with the exact byte count after all DATA; the P4's FIN →
  `shutdownOutput()` after queued writes. The P4's CLOSE (reason 1 is its normal end of every
  request) gets CLOSE_ACK and purges anything queued for that stream; no OPEN_OK or OPEN_ERR
  follows it. Android itself never sends CLOSE.
- **PING** only with live streams after 5 s of RX silence; PONG is due within 5 s.

| Code | Name | Sent by Android when |
| --- | --- | --- |
| 1 | BAD_PROTOCOL | malformed or out-of-order frame (session RESET) |
| 2 | UNSUPPORTED_VERSION | inner frame version ≠ 1 |
| 4 | TARGET_DENIED | OPEN_ERR: destination is not the approved one. RESET/OPEN_ERR: streams ended by a destination change |
| 5 | VPN_UNAVAILABLE | no VPN covering this app, Network permission off, socket bind refused, tunnel has no DNS server it routes (DNS names), resolved address not routed; streams ended by VPN loss or a route change |
| 6 | DNS_FAILED | resolution through the VPN failed |
| 7 | CONNECT_FAILED | TCP connect failed |
| 8 | CONNECT_TIMEOUT | DNS + connect over 2.5 s |
| 9 | BUSY | both stream slots live |
| 10 | NO_RESOURCES | four connect attempts (live plus abandoned DNS lookups) outstanding; control queue full |
| 11 | TCP_IO | stream RESET: socket read/write failed (the other stream survives) |
| 12 | FLOW_VIOLATION | DATA beyond credit, bad CREDIT |
| 13 | DEADLINE | reassembly or PONG deadline (an inner-HELLO timeout ends silently: no HELLO_ACK went out) |
| 14 | CANCELLED | session RESET on Stop, Forget, secret change, shutdown. Never sent as OPEN_ERR today; reserved for a local cancellation |

Code 3 (NOT_AUTHORIZED) appears only locally, as the last-end reason after a REJECT; 15 (display
priority) is the P4's. Every OPEN_ERR
goes before OPEN_OK, and no DATA is sent on a stream before its OPEN_OK, so no HTTP byte has passed
the phone when one is sent.

## Configuration changes

**Destination only** (FW-3). *Change destination* keeps the secure channel. The runtime thread is
the single owner: it swaps the allowlist that OPEN admission reads and bumps the target generation;
under the engine lock every live stream ends (OPEN_ERR 4 until its OPEN_OK has started going out, RESET 4 after), its
ring buffers and every not-yet-started control frame for it (OPEN_OK, CREDIT, FIN) are dropped so
nothing but that RESET is sent for it again, and its socket is closed. Each
socket result carries {session generation, target generation, stream ID}; a mismatch is dropped
and the socket closed.
Stream IDs are never reused, so the P4 must OPEN a new ID for the new destination, and no old byte
can reach it. The stream-ID high-water mark, outer counters and inner sequences carry on. While
the pipe is stopped the stored destination is simply replaced.

**Authority changes** end the session: a new passphrase (the old PSK stops admitting and
advertising at once, then best-effort RESET 14 and disconnect; the new PSK is derived and only then
is the cooldown cleared), Forget and Stop. Change the passphrase on both
sides; on the P4, changing `phonepipesecret` drains its pipe and invalidates its enrollment, so
enroll again there.

**Forget** deletes this phone's passphrase, destination and last-authenticated time, and stops the
pipe. It cannot remove anything stored on the P4: clear `phonepipesecret` (or run `phonepipe
forget`) there if you no longer want it.

## Passphrase

`PipeSecret.normalize` accepts only what the P4's serial command `phonepipesecret` stores
unchanged, so both sides derive from the same bytes. Checks, in order:

1. Reject U+0000, CR or LF anywhere (the serial console cannot enter them), and any unpaired
   surrogate.
2. Encode as strict UTF-8.
3. Trim outer bytes in {09 0A 0B 0C 0D 20} only (C-locale `isspace`, never Kotlin `trim()`).
   Interior bytes are kept: tab, VT, FF, spaces, quotes and non-ASCII.
4. Empty → "Enter a passphrase."
5. 10–128 bytes. Lengths are UTF-8 bytes, as the firmware counts them; accented letters and emoji
   take 2–4 each.
6. At least one byte from each ASCII class: upper `A–Z`, lower `a–z`, digit `0–9`, and punctuation
   (0x21–0x2F, 0x3A–0x40, 0x5B–0x60, 0x7B–0x7E). Bytes ≥ 0x80 count toward the length but belong to
   no class.

PBKDF2 runs over exactly the resulting bytes. Refusal messages never contain the input, and the
field is masked by default and never saved in instance state. The P4 itself checks byte length
only (it does not validate UTF-8) and a settings restore there bypasses its policy, so a P4 secret
outside these rules may be impossible to enter on Android; the app never claims otherwise.

## Storage

| Item | Where |
| --- | --- |
| Pipe passphrase (the normalised bytes) | `SecretBox` alias `hw_pipe_key` |
| Approved destination (`{"v":2,"type","host","port"}`) | `SecretBox` alias `hw_pipe_config` |
| `enabled`, last authenticated time, secret/destination versions | plain app preferences |

- Each `SecretBox` is a separate non-exportable AES-256-GCM Keystore key (StrongBox when present).
  The pipe shares no key with the console passphrase or the login credentials.
- **No P4 address and no P4 enrollment marker** are stored. "Last authenticated pipe: <time>" is
  the wall-clock time of the last local READY, not a claim that the P4 stored anything.
- **Migration.** A record from the earlier bonded design (alias `hw_pipe_enrollment`) is read once:
  only its destination is kept (re-validated as an OPEN target), and its address and time are
  dropped with it. The passphrase has to be entered.
- **Readable while locked after the first unlock.** The keys need no unlocked device and no user
  authentication, deliberately, so a restart while the phone is locked can still serve. Before the
  first unlock after a reboot, nothing runs.
- `allowBackup="false"`, and the Keystore keys do not survive backup or device migration anyway.
- The destination is shown only on the pipe page; the notification and logs never contain it.

## VPN loss

Losing the VPN does not end READY (F5). The app closes the affected sockets and ends their
streams (RESET 5 when open, OPEN_ERR 5 while connecting), keeps the authenticated idle pipe, and
advertising is not affected. Until a route returns, every new OPEN gets OPEN_ERR 5 before any DNS
or TCP; nothing is buffered for later. The status reads "Ready · relay network unavailable" (also
when the GrapheneOS Network permission is off). When the same VPN network's routes change, each
stream whose address the tunnel no longer routes is reset with 5. Dropping the pipe instead would
cost the P4 its reconnect backoff after the VPN returns.

## Lifecycle and recovery

The pipe stays **enabled** through transient problems; only Stop and Forget disable it (FW-1). No
restart or recovery-latency guarantee is claimed.

| Event | What the app does | User action |
| --- | --- | --- |
| Phone reboot | `PipeBootReceiver` on `BOOT_COMPLETED` starts the service if enabled; that broadcast comes after the first unlock | Unlock once after every reboot, including GrapheneOS auto-reboot |
| App update | `MY_PACKAGE_REPLACED`, same start | None |
| Process death | Sticky restart if enabled. If Android refuses the background start, the pipe stays enabled and a "BLE relay pipe paused — Tap to resume" notification is posted | One-time: battery **Unrestricted**. Otherwise tap the notification or open the app |
| App opened | An enabled, configured pipe restarts without a Start tap (Nearby devices granted) | None |
| Bluetooth off → on | The session ends at TURNING_OFF/OFF and the server and advertiser close; ON reopens them | Turn Bluetooth back on |
| Nearby devices withdrawn | Paused: server closed, still enabled; resumes within about a second of the grant | Grant it again |
| VPN down → up | READY kept, OPEN_ERR 5 meanwhile (above) | Always-on VPN, with this app not excluded |
| Locked / Doze, idle READY | Foreground service keeps network access; handshake wake lock; suspend-inclusive clocks | None |
| Stop (page or notification) | Disabled; best-effort RESET 14; everything closed | Start again |
| Force stop / Task Manager stop | Nothing runs until the app is opened, then an enabled pipe resumes | Reopen the app |
| Battery "Restricted" | Android stops the service in the background; the pipe stays enabled, the page shows the stop and the "Tap to resume" notification is posted | Do not choose it. Otherwise open the app (or tap the notification) to resume |

Notes:

- Restarts after process death take at least 1 s, longer under memory pressure, may wait until a
  locked phone next wakes, and a crash loop pushes them out to 30 min (AOSP source, not measured).
  Tap-to-resume is a visible fallback, not a pass.
- If the GrapheneOS Bluetooth timeout is on, Bluetooth turns off when no GATT device is connected
  and nothing turns it back on. Leave it off.
- A 5 s wake lock is taken synchronously when a session is admitted and when a stream's OPEN is
  admitted; the partial wake lock then covers admission until READY and any teardown (capped at
  15 s), and live streams (capped at 10 min, re-taken only if stream bytes moved or a new stream
  opened).

## Status wording

The page and the notification use the same line (`PhonePipe.summary`):

| Shown | Meaning |
| --- | --- |
| Off / Off · not configured | Not running (or the reason it did not start) |
| Not configured | No passphrase or no destination |
| Paused · allow the Nearby devices permission / Paused · Bluetooth off | A Bluetooth prerequisite stops the BLE service; still enabled |
| Waiting for HardwareOne | Advertising |
| Authenticating | A candidate is admitted; HW1-PIPE-v1 handshake running |
| Secure channel up | CONFIRM_ACK verified; HELLO_ACK not yet completed locally |
| Ready (phone side) | HELLO_ACK's notification completed locally; the P4 has not sent an OPEN this session |
| Ready / Ready · N streams | The P4 has sent at least one OPEN this session |
| … · relay network unavailable | Suffix while no VPN covers the app or the Network permission is off |
| Authentication failed or timed out · retry in N s | Cooldown running |

READY is local readiness: it does not prove the P4 consumed HELLO_ACK or committed anything.
"Relay reachable" is never a state.

Diagnostics (endpoint-free): Bluetooth and advertising state, session stage, VPN covering this
app, cooldown seconds, ATT MTU / inner frame, sessions, live streams, OPENs this session /
connected, frames in/out, bytes to relay / to device, last OPEN → OPEN_OK time, OPEN_ERR and stream
RESET counts by code, the last session end (kind · error and code) and stale callbacks dropped.

## Android and platform limits

These are facts about public Android APIs and the Bluetooth stack (targetSdk 35), not choices.

1. **Completion is local enqueue.** There is no "transmitted" event, values between MTU − 3 and
   512 are silently truncated and reported as success, and nothing enforces one outstanding
   notification; the app does that itself.
2. **Every LE link is reported** to every GATT server, so admission happens on the CCCD write, never
   on connection.
3. **Shared GATT database.** Any app adding or removing a GATT service sends Service Changed to every
   connected peer, which ends the P4's session; the P4 reconnects on its own schedule. Instance
   retirement does the same, which is why it waits for the P4 link to go first.
4. **`cancelConnection` is unreliable** for links the P4 opened, hence the link-holder `connect`.
   After the app process dies, the stack sends Service Changed and drops the held link about 4 s
   later (Android 17; inferred from source, to be measured).
5. **First advertiser wins** (A-8). The P4 tries the first phone advertising the UUID; a second
   phone running the pipe would be tried, fail authentication and trigger the cooldown. Documented
   v1 limitation.
6. **Advertising is probabilistic** against the P4's short passive scan, so a scan can miss the
   phone.
7. **VPN app and lockdown cannot be verified.** A normal app cannot identify the VPN app or read
   always-on/lockdown state, so the page says "VPN covering this app", not "WireGuard". What the app
   enforces: a VPN network that covers it, sockets bound to it before any lookup, DNS only when the
   tunnel routes its DNS servers, a route check on the chosen address, stream teardown on loss or
   replacement and a per-stream re-check on route changes. A Private DNS hostname server cannot be
   checked.
8. **DNS lookups cannot be interrupted.** A lookup still running at the OPEN deadline is abandoned
   (OPEN_ERR 8); with four connect attempts outstanding, further OPENs get OPEN_ERR 10.
9. **Background starts are restricted.** A background restart can be denied; the boot/update
   broadcasts and a notification tap carry a temporary allowance, and battery Unrestricted makes
   the sticky restart allow-listed.

## Qualification — results (2026-10-17)

Run on a Pixel (GrapheneOS, Android 17) against a HardwareOne P4 whose firmware includes the stage-4
pipe, the phone-reconnect fix and the MTU-after-discovery fix, in G2-client and phone-client mode.

**Passed:**
- **Enrollment and the secure channel.** MTU and inner frame were 244 / 216 in G2-client mode and
  517 / 487 in phone-client mode, with the same session ID on both sides.
- **Chats carried by the phone** with the P4's Wi-Fi down, including with the phone on mobile data
  through the VPN.
- **Clean failures:**
  - VPN off: OPEN_ERR 5, and the session stays up.
  - VPN connected but its server unreachable: connect timeout in under 3 s.
  - A reply cut off by Bluetooth off: recorded as failed after send, and never replayed.
  - A wrong passphrase: each attempt fails authentication, with a 30 s pause between attempts.
- **Automatic recovery,** with no user action except where noted:
  - phone Bluetooth off and on;
  - P4 reboot;
  - phone reboot (after the first unlock);
  - app crash, with battery Unrestricted and with Optimized;
  - app update;
  - Force stop and Task Manager stop (on the next app open);
  - swipe from recents;
  - light and deep Doze;
  - battery Restricted: Android stopped the service after about 5 min, and opening the app resumed it.
- **A final run of 40 Bluetooth off/on cycles:** 40 of 40 reconnected.

**Measured:**
- **Reconnect** after phone Bluetooth comes back: about 8–9 s while the P4's Wi-Fi is idle, and about
  11–13 s while it is associated.
- **Connection setup** is 4–8 s, mostly GATT discovery of the phone's whole database, which runs at
  about half speed under Wi-Fi/Bluetooth coexistence on the P4.

**Not yet covered:**
- an overnight soak;
- a shielded silent loss (the stale-READY window);
- a real low-memory kill;
- two concurrent streams;
- glasses, ring and phone connected together;
- more than one advertising phone.

The procedure below is the checklist the run followed.

Record for every run: APK SHA-256 (`shasum -a 256` of the exact file installed), app source
(`git rev-parse HEAD` plus `git status --short`), application ID, phone model and OS build number,
VPN app version, lockdown on/off, battery mode, firmware build and image SHA-256, and for the
lifecycle cases `ApplicationExitInfo` and restart times. Never record endpoints, addresses,
passphrases or content.

**FW-6 hardware acceptance** (run, see above), in both P4 client modes, after one-time provisioning, enrollment and
enablement, with no setup commands between requests:

1. Reboot the P4 with glasses off → automatic idle READY while Wi-Fi serves a chat (phone counters
   unchanged).
2. Disable the P4's Wi-Fi → the next chat uses the phone (OPENs and byte counters move).
3. With phone use established, make Wi-Fi available and turn phone Bluetooth off → the next chat
   uses Wi-Fi. Test a new request right around Bluetooth-off (the stale-READY window), and
   separately a controlled silent loss (RF shielding or range, waiting out the supervision timeout).
4. Turn Bluetooth back on → automatic return to idle READY; time it.
5. Record non-sensitive path/outcome/stream counts; tell requests interrupted after sending from new
   ones; never manufacture a pass by replaying an uncertain mutation.

**Android lifecycle cases** (android-002 §3):

6. READY idle while locked for at least 10 min, then the FW-6 switching.
7. Process kill while locked, with battery Optimized and with Unrestricted.
8. Phone Bluetooth toggle.
9. VPN toggle: READY kept, "relay network unavailable", OPEN_ERR 5, normal service once the tunnel
   returns.
10. Phone reboot plus unlock.
11. App update (`adb install -r`).

**Also to measure:** the 30 ms gap with one notification outstanding, with glasses off and
separately under G2 traffic (P4 queue high-water mark, handshake duration); CCCD answer time and
handshake duration against the 4.5 s / 5 s caps; OPEN → OPEN_OK margin (diagnostics) and OPEN_OK →
first TLS application data; app death → Service Changed → P4 session end; wrong passphrase on
either side (authentication end, 30 s cooldown); MTU 64 and 244/517; two concurrent streams; a
destination change mid-session; negotiated connection parameters on repeated idle reconnects; a
second advertising phone if one is available.

## Side-by-side test build

```bash
./gradlew assembleDebug -PpipeTest
```

This builds `app/build/outputs/apk/debug/app-debug.apk` as a separate app,
`com.hardwareone.console.pipetest` (version name suffix `-pipetest`), so the everyday
`com.hardwareone.console` install and its data are untouched. The test install has its own
passphrase, destination, Keystore keys, permissions and battery setting; set them up separately,
and make sure a per-app VPN configuration includes its package name. Run the pipe in only one
install at a time: both would advertise the same service UUID, and the P4 takes the first it finds.


## Qualification log

The pipe writes one JSON object per line to app-private storage (`files/pipe/qualification.jsonl`,
rolled over at 512 KiB), exported from the pipe page (**EXPORT LOG**) and cleared with **CLEAR LOG**:

| Line | Fields |
| --- | --- |
| `run` | `elapsed_ms`, `app`, `last_exit` (one per service start; the previous process's ApplicationExitInfo reason, code, importance and time, no description) |
| `session` | `corr`, `ready_ms`, `end_ms`, `end_kind`, `end_code`, `authenticated`, `unsettled`, `stale_callbacks`, `retired_instance_callbacks` |
| `stream` | `corr`, `id`, `open_to_result_ms`, `result` (`open_ok` / `open_err:<code>` / `none`), `end` (`close:<0\|1>` / `reset:<code>` / `session_end` / `none`), `bytes_to_p4`, `bytes_from_p4`, `peer_reset`, `peer_close` |
| `counters` | written at export: per-code OPEN_ERR and RESET counts and session totals for the running pipe |

`corr` is the P4's pipe HELLO nonce as wire-order hex, the same string `phonepipe stats` and the P4's
`LLM_PATH` records print; the pipe page's Diagnostics show the current one ("Session id (corr)").
Times are `elapsedRealtime`, so match the two sides by `corr` and stream id. No line holds an
endpoint, address, passphrase or content.
## Tests

```bash
./gradlew :app:testDebugUnitTest --tests 'com.hardwareone.console.pipe.*'
```

458 JVM tests in `app/src/test/java/com/hardwareone/console/pipe/`, all passing (2026-10-16):

| Suite | Tests | Covers |
| --- | --- | --- |
| `PipeSecureChannelTest` | 30 | the firmware's MTU-247 JSON fixture byte for byte (HELLO, CONFIRM, both DATA values, key schedule recomputed independently), every applicable case of the firmware's channel harness, low-order points, REJECT, unsigned counters at 2^63−1 / 2^63 / 2^64−2 / 2^64−1, TX exhaustion, wiping |
| `PipeEngineSecureTest` | 42 | the handshake and first DATA through the engine against an independent fake P4, pacing, completion statuses, REFUSED / UNCERTAIN / blocked submissions, the 2 s deadline on both clocks, stale completions, every PipeEnd kind and the RESET rules, MTU 64 sizes, stages, retarget and VPN loss |
| `PipeEngineResetAndReadinessTest` | 5 | the teardown RESET never delaying the disconnect, readiness only after the last HELLO_ACK fragment |
| `PipeEngineTest`, `PipeEngineRoutesTest` | 31, 6 | TCP streams, credit, FIN/CLOSE, retirement, no replay, route rechecks, over the encrypted harness |
| `PipeSessionWireTest`, `PipeSessionStreamTest`, `PipeExhaustionTest` | 77, 57, 4 | the inner protocol at P (64/244/247/517), the fixture's inner HELLO/HELLO_ACK, Q12 stream IDs, bounds |
| `PipeAttPolicyTest`, `PipeSecretTest`, `PipeConfigTest`, `PipeCooldownTest`, `PipeTargetTest` | 22, 26, 15, 9, 44 | ATT decisions, passphrase bytes (incl. the fixture PSK), records and migration, cooldown, destinations |
| `PipeFixtureX1SessionsTest` | 10 | the firmware's X1 sessions at MTU 64/244/247/517: every phone value byte for byte (timing aligned where the fixture's scripted phone credits or PINGs differently), every P4 value accepted |
| `PipeFixtureX2NegativeTest` | 32 | all 40 X2 vectors: the four contract vectors rejected (AUTH, no REJECT, one disconnect), phone-side encoder parity, Android's own responses |
| `PipeFixtureX3PassphraseTest` | 39 | all 32 X3 texts: accept/reject, stored bytes and PSK identical to the P4's |
| `PipeLogRecordsTest` | 9 | stream and session records and the JSON line shapes of the qualification log |

`PipeGattServer`, `PipeRuntime` and `PhonePipeService` need Android and are covered only by review
and the hardware qualification below. Tests use synthetic destinations only.
