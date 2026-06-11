# HardwareOne Secure Channel v1 — app-side contract

What the **Android app (initiator)** sends and expects, so the firmware (responder) can
be built to match exactly. App-layer encryption over the existing GATT — **no BLE
pairing/bonding/link-encryption** (that approach is abandoned; it created unremovable
bonds on GrapheneOS). The app side below is implemented and unit-test-verified
(`SecureChannel.kt`); the test vectors in §10 come from that implementation.

## 1. GATT layer (unchanged)
- Same Command service: **REQUEST** (`…de01`, write) app→device, **RESPONSE** (`…de02`,
  notify + CCCD `0x2902`) device→app, **STATUS** (`…de03`, read), Device Info `180A`.
- Connect sequence unchanged: `connectGatt(autoConnect=false, TRANSPORT_LE)` →
  `discoverServices()` → `requestMtu(517)` → enable RESPONSE notifications (write
  `0x0001` to CCCD).
- **The device must NOT require link-layer encryption / bonding on any characteristic.**
  All security is app-layer, below, riding on REQUEST/RESPONSE as opaque binary messages.

## 2. Two modes
- **Plaintext** (app has no passphrase set): exactly today's behavior — UTF-8 command
  lines on REQUEST, UTF-8 replies on RESPONSE, `login`, etc.
- **Secure** (app has a passphrase set): the app runs the handshake (below) immediately
  after enabling notifications and **before login**; thereafter every REQUEST write and
  RESPONSE notification is a binary secure-channel message.
- v1 assumes **both sides are configured consistently** (operator sets the same passphrase
  on device via `blesecret` and in the app). The app does not auto-probe device mode; on a
  mismatch the handshake simply fails and the app reports it. In secure mode the **first
  REQUEST write will be `HELLO` (0x01)** — the device should expect that.

## 3. Message framing
- Each REQUEST write / RESPONSE notification = one message = `type(1) ‖ payload` (binary).
- Keep each ≤ `negotiated_MTU − 3`. Handshake msgs are ≤49 B. For DATA, AEAD overhead is
  1 (type) + 8 (counter) + 16 (tag) = **25 B**, so usable plaintext per frame =
  `MTU − 3 − 25 = MTU − 28`. Longer replies must be **chunked** (or truncated, same caveat
  as today until chunking lands).
- Types: `0x01 HELLO`, `0x02 HELLO_ACK`, `0x03 CONFIRM`, `0x04 CONFIRM_ACK`, `0x10 DATA`.

## 4. Primitives
- **X25519** (RFC 7748) · **HKDF-SHA256** (RFC 5869) · **ChaCha20-Poly1305-IETF**
  (RFC 8439; 12-byte nonce, 16-byte tag, **empty AAD**).
- **PSK** = `PBKDF2-HMAC-SHA256(passphrase_utf8, salt = ASCII "HW1-SC-v1", iters = 100000,
  dkLen = 32)`.

## 5. Handshake (app = initiator)
```
1. App → REQUEST : HELLO       = 0x01 ‖ appEphPub(32) ‖ appNonce(16)      // 49 B
2. Dev → RESPONSE: HELLO_ACK   = 0x02 ‖ devEphPub(32) ‖ devNonce(16)      // 49 B
3. both: ss = X25519(ownEphPriv, peerEphPub)        // raw 32 B, no hashing
4. both: K  = HKDF-SHA256(ikm = ss ‖ PSK,
                          salt = appNonce ‖ devNonce,
                          info = ASCII "HW1-SC-v1", L = 64)
         K_c2d = K[0:32]   (app→device)
         K_d2c = K[32:64]  (device→app)
5. App → REQUEST : CONFIRM     = 0x03 ‖ AEAD(K_c2d, nonce(c2d,0), pt="ok")  // dev verifies
6. Dev → RESPONSE: CONFIRM_ACK = 0x04 ‖ AEAD(K_d2c, nonce(d2c,0), pt="ok")  // app verifies
   any AEAD-open failure ⇒ wrong PSK / MITM ⇒ both sides drop the link
```
- `nonce(dir, counter)` = `dirTag(4 B, big-endian) ‖ counter(8 B, big-endian)` = 12 B.
  `dirTag`: **c2d = 0x00000000**, **d2c = 0x00000001**.
- `"ok"` = bytes `6f 6b`. AAD is empty everywhere.

## 6. Data phase
```
msg = 0x10 ‖ counter(8, BE) ‖ AEAD(K_dir, nonce(dir, counter), plaintext)
```
- `counter` is sent in the clear (it is the nonce input); receiver uses it to build the
  nonce and to enforce monotonicity.
- Counters are **per-direction, strictly monotonic, start at 1** (0 was used by CONFIRM),
  never reused. app→device uses `K_c2d`/c2d; device→app uses `K_d2c`/d2c.
- `plaintext` = the same UTF-8 CLI line / reply as today (may contain `\n`).
- Receiver MUST reject `counter ≤ last_accepted` (replay/out-of-order) and reject on tag
  failure.

## 7. Login / commands
`login <user> <pass>` and all commands run **inside** the data phase (as DATA plaintext).
The device's existing auth/CLI logic is unchanged — it just runs on decrypted text. The
app gates its UI on the existing `Login successful` reply (decrypted from a DATA frame).

## 8. STATUS / Device Info
The app reads Device Info (`180A`) and may read STATUS (`…de03`) as **plaintext GATT
reads**, outside the channel. Please keep those readable without the channel (non-
sensitive). If you encrypt/disable them in secure mode, tell me and I'll gate those reads.

## 9. Multi-connection — IMPORTANT
Each connection performs its **own** handshake → **distinct per-connection keys**. The
plaintext "broadcast the same reply to all clients with a `[ble conn:<id>]` prefix" model
**cannot send one ciphertext to all** in secure mode. The device must encrypt each
client's RESPONSE with **that client's** session key (per-connection state). Flagging this
as the main architectural implication on the firmware side.

## 10. Test vectors (validate against these — fixed, non-random inputs)
Inputs:
- `passphrase = "test-passphrase"`
- `appEphPriv = 0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20`
- `devEphPriv = 2122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40`
- `appNonce   = a0a1a2a3a4a5a6a7a8a9aaabacadaeaf`
- `devNonce   = b0b1b2b3b4b5b6b7b8b9babbbcbdbebf`

Expected:
```
PSK (PBKDF2)       = bb2618c01518a49da4508693a69e803efd6f4bd24f7a67adb53eb5c97a60b8ff
appEphPub          = 07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c
devEphPub          = 5869aff450549732cbaaed5e5df9b30a6da31cb0e5742bad5ad4a1a768f1a67b
X25519 shared (ss) = a84dc7c3c8f058b1b2dc4cd1e9b5dc0a7987f88b6a9564cde3391fc421159e77
K_c2d              = 613cb9d4d1f08af8d37bf2e6eac0cc51b2f6151a0e97a171d3c5761b8f05368b
K_d2c              = 01bf12a0d6e3b1ef2d024894cd213f6ffb72788c033bdb90fb68666451bc6be1
HELLO       (c->d) = 0107a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7ca0a1a2a3a4a5a6a7a8a9aaabacadaeaf
HELLO_ACK   (d->c) = 025869aff450549732cbaaed5e5df9b30a6da31cb0e5742bad5ad4a1a768f1a67bb0b1b2b3b4b5b6b7b8b9babbbcbdbebf
CONFIRM     (c->d) = 032a690c7bf82c3bb321b0c10cf971a71568a1
CONFIRM_ACK (d->c) = 04b20a930da943f0801f14f7e7696496210c71
DATA "help" ctr=1  = 1000000000000000017d06562d2d6324e923d16e3d38c373b5ed3f5437
```
If your `CONFIRM` / `DATA` ciphertext bytes match these exactly, your X25519, HKDF, nonce
layout, and ChaCha20-Poly1305 are all correct. (X25519 private keys are used raw; RFC 7748
clamping is applied internally by both libsodium and the app's BouncyCastle.)

## 11. Please confirm
1. **Empty AAD** everywhere (yes in this spec).
2. **Per-connection encryption** (§9) — not broadcast.
3. STATUS / Device Info remain plaintext reads (§8)?
4. Chunking plan for replies > `MTU − 28` (else long output truncates).
5. v1 mode model = passphrase configured on both sides; first REQUEST in secure mode is
   `HELLO`.
