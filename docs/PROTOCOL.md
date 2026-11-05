# AirBridge Protocol v2

Status: **normative** for all AirBridge implementations (Android, macOS, Windows, and future Linux/iOS).
Every implementation must follow this document exactly. Where a platform cannot do something, it must
degrade gracefully (ignore the message, or omit the capability from `caps`), never invent a new message.

v2 is **not** backward compatible with the old colon-delimited v1 protocol. All apps are updated together.

---

## 1. Overview

```
 ┌────────────┐   WebSocket (ws://host:port/v2)   ┌────────────┐
 │  Client    │ ─────────────────────────────────▶ │   Host     │
 │ (Android,  │   JSON text frames  = control      │ (macOS,    │
 │  Windows   │   binary frames     = file chunks  │  Windows)  │
 │  as client)│ ◀───────────────────────────────── │            │
 └────────────┘                                    └────────────┘
```

- **Host** = a device that runs a WebSocket server and advertises itself over mDNS. Desktops (macOS, Windows,
  Linux) are hosts.
- **Client** = a device that dials a host. Phones (Android, iOS) are clients only. Windows is *also* a client
  when it connects to a Mac. macOS may dial out to another host from its menu.
- A **session** is one authenticated WebSocket connection between two devices. A device may have many
  sessions at once (multi-device). All messages below are symmetric unless stated: either side may send them.
- The protocol is **transport-agnostic** (see §10). WebSocket is the LAN transport. Android↔Android uses
  Nearby Connections carrying the same messages.

### Design rules

1. Every text frame is **one JSON object** with a string field `"t"` (type). Unknown `t` → ignore.
2. Every binary frame starts with **one kind byte** (§6). Unknown kind → ignore.
3. **Clipboard text is never confused with commands**: it only ever travels inside `{"t":"clip"}`.
4. State is **per session**. Two peers sending files at the same time must not interfere.
5. **No authentication, no commands.** Nothing but `hello`/`auth`/`pair*` is accepted before a session is
   `ready`, and **everything after `ready` is encrypted** (§3.6). A plaintext frame after `ready` is a
   protocol error → close.
6. Key codes are **USB HID usage IDs** (page 0x07) — never platform virtual-key codes (§8.4).
7. Numbers may be floats where noted. Text is UTF-8. Base64 is standard (RFC 4648, with padding).

---

## 2. Identity, discovery, and addressing

### 2.1 Device identity

| Field      | Value |
|------------|-------|
| `id`       | UUID v4, lowercase, generated on first run, persisted forever (survives renames and IP changes). |
| `name`     | User-visible name. Default: computer name / `Build.MODEL`. May change. |
| `plat`     | `android` · `macos` · `windows` · `linux` · `ios` |
| `app`      | App version string, e.g. `"2.0.0"`. Informational. |

Peers are always remembered and looked up **by `id`**, never by IP or port.

### 2.2 Port

Hosts listen on TCP **47632**. If busy, try 47633 … 47641 in order. The port actually bound is advertised
over mDNS and encoded in the pairing QR. Clients must never assume the port.

WebSocket path: **`/v2`**. A host must reject any other path (HTTP 404) so v1 clients fail fast.

### 2.3 mDNS / Bonjour

Service type **`_airbridge._tcp`**, domain `local.`. Instance name = the device `name` (the OS may append
` (2)` on collision — that is fine, clients use the TXT record).

TXT record (all keys required):

| Key    | Value |
|--------|-------|
| `v`    | `2` |
| `id`   | device id |
| `name` | device name |
| `plat` | platform |

Clients browse continuously while the app is running and resolve `id → (ip, port)` from TXT + SRV/A records.
A paired peer is reconnected to **whatever address mDNS currently reports for its `id`**; the last known
address is only a fallback when mDNS has nothing. IPv6 link-local addresses must be bracketed in URLs or
avoided; prefer IPv4 when both are present.

### 2.4 Pairing QR / link

Hosts display a QR encoding:

```
airbridge://pair?v=2&id=<hostId>&name=<urlencoded name>&plat=<plat>&addr=<ip>&port=<port>&k=<base64url secret>
```

- `k` is 32 random bytes, base64url without padding. It is the **pairing secret** (§3.3). A secret lives at
  most 10 minutes; the host also rotates it the next time the QR is shown after a successful pairing.
- `addr`/`port` are hints; the client still prefers the mDNS address for `id` if available.

When the client cannot scan (e.g. Windows pairing with a Mac, or a phone that picked the host from the
discovery list), pairing uses **numeric comparison** (§3.3, mode `confirm`): both devices display the same
6 digits derived from the key exchange and both users confirm. Nothing is typed, and a sniffer cannot
brute-force anything offline.

---

## 3. Session handshake

Timeline for every new WebSocket connection. The handshake must complete within **10 seconds** or the host
closes the socket.

```
Client                                   Host
  │── hello ───────────────────────────────▶│
  │◀────────────────────────────── hello ───│   (host says whether it already knows this client)
  │                                         │
  │  paired:                                │
  │── auth ────────────────────────────────▶│
  │◀──────────────────── auth_ok | auth_fail│
  │                                         │
  │  not paired:                            │
  │── pair ────────────────────────────────▶│
  │◀──────────────────── pair_ok | pair_fail│
  │                                         │
  │            session is READY             │
```

### 3.1 `hello` (both directions)

```json
{"t":"hello","v":2,"id":"<uuid>","name":"Shazan's Mac","plat":"macos","app":"2.0.0",
 "caps":["clip","xfer","ctl_source","ctl_target","trackpad"],
 "nonce":"<base64 16 random bytes>",
 "pk":"<base64 65-byte uncompressed P-256 public key>",
 "screen":{"w":1080,"h":2400},
 "paired":true}
```

- `v` must be `2`. Otherwise reply `{"t":"auth_fail","reason":"version"}` and close.
- `caps` (capabilities) — subset of:
  - `clip` – sends/accepts clipboard
  - `xfer` – sends/accepts file transfers
  - `ctl_source` – can control others (has a physical pointer that can leave the screen)
  - `ctl_target` – can be controlled (shows a virtual cursor, injects input)
  - `trackpad` – can act as a direct input device (phone trackpad/keyboard mode)
  - `mirror_source` – can stream its screen (§8.7)
  - `mirror_sink` – can display a streamed screen and send absolute input (§8.7)
- `nonce` – fresh per connection, used in auth/pair MACs.
- `pk` – a fresh **ephemeral** NIST P-256 (secp256r1) public key, X9.63 uncompressed (`0x04‖X‖Y`, 65 bytes),
  generated per connection and never reused. It feeds the session keys (§3.6). Reject a `hello` without a
  valid point.
- `screen` – optional, pixel size of the primary display.
- `paired` – **host only**: `true` if the host has a stored key for the client's `id`.

The client sends `hello` first. The host replies with its own `hello`.

### 3.2 `auth` / `auth_ok` / `auth_fail` (already paired)

Let `K` be the stored 32-byte shared key for this peer (§3.3). `cNonce`/`hNonce` are the raw bytes from the
two `hello` messages, `cPk`/`hPk` the raw 65-byte public keys. `HMAC` = HMAC-SHA256. `|` = byte
concatenation of the UTF-8 strings/bytes shown. Define the **transcript**:

```
T = cNonce | "|" | hNonce | "|" | clientId | "|" | hostId | "|" | cPk | "|" | hPk
```

```json
C→H  {"t":"auth","mac":"<base64 HMAC(K, "auth|" | T)>"}
H→C  {"t":"auth_ok","mac":"<base64 HMAC(K, "auth_ack|" | T)>"}
H→C  {"t":"auth_fail","reason":"unpaired|bad_mac|version|busy"}
```

Because `T` contains both ephemeral public keys, a MITM that substitutes keys fails authentication.

The client **must verify** `auth_ok.mac` (mutual authentication) and close if it is wrong.
On `auth_fail` with `unpaired` or `bad_mac`, the client forgets its stored key for that host and shows
"Pair again". The host closes the socket after sending any `auth_fail` or `pair_fail`.

### 3.3 `pair_start` / `pair` / `pair_ok` / `pair_fail` (first contact)

Both sides first compute the ECDH shared secret `Z` = x-coordinate of ECDH(own ephemeral private key,
peer `pk`) (32 bytes). There are two pairing modes:

| Mode | How the long-term key `K` is derived | User action |
|------|--------------------------------------|-------------|
| `qr` | `K = HMAC-SHA256(key = S, data = "airbridge/v2/pair|" \| clientId \| "|" \| hostId)` where `S` = the 32 raw bytes from the QR's `k` | scanning the QR is consent; no prompt on the host |
| `confirm` | `K = HKDF-SHA256(ikm = Z, salt = cNonce \| hNonce, info = "airbridge/v2/pairkey|" \| clientId \| "|" \| hostId, 32 bytes)` | both devices show the same 6 digits; both users confirm |

The confirmation digits for mode `confirm`:

```
confirm = decimal( first 4 bytes of HMAC-SHA256(key = Z, data = "airbridge/v2/confirm|" | T) as u32 big-endian ) mod 1_000_000, zero-padded to 6 digits
```

An active MITM yields different `Z` on the two sides, hence different digits, which the users notice. A passive
sniffer learns nothing. No code is ever typed.

```json
C→H  {"t":"pair_start","mode":"confirm"}                 // host now shows "<client name> wants to pair · 483920 · Accept / Reject"
C→H  {"t":"pair","mode":"qr|confirm","proof":"<base64 HMAC(K, "pair|" | T)>"}     // confirm: sent after the client's user tapped Confirm
H→C  {"t":"pair_ok","proof":"<base64 HMAC(K, "pair_ack|" | T)>"}                  // confirm: sent after the host's user tapped Accept and the proof verified
H→C  {"t":"pair_fail","reason":"bad_proof|rejected|locked|not_pairing|timeout"}
```

Rules:
- `pair_start` is only needed for `confirm` (it lets the host show the digits early); a `pair {mode:"confirm"}`
  without it counts as an implicit start. A `pair` without `mode` means `qr`. `pair_start` replaces the 10 s
  handshake timeout with **60 s** on both sides (`timeout`). A client that cancels simply closes the socket.
- A `hello` without a valid on-curve `pk` is closed without reply (hosts may send `auth_fail` with reason
  `bad_hello` first).
- On success both sides persist `K` under the peer's `id` (Keychain on macOS, DPAPI/`ProtectedData` on
  Windows, Keystore-wrapped app-private storage on Android) together with `name` and `plat`.
- Hosts may disable pairing entirely (`not_pairing`). After **5 failed** `pair` proofs in either mode the host
  refuses pairing (`locked`) for 60 s and rotates its QR secret. A host showing more than 3 simultaneous
  confirm prompts answers further ones with `locked`.
- The client must verify `pair_ok.proof`.
- Android↔Android over Nearby Connections: Nearby's own authenticated link already had its digits confirmed by
  both users, so both sides treat mode `confirm` as auto-accepted (no second prompt).

Pairing secrets never travel over the network; only HMACs do.

### 3.4 Duplicate sessions

If a `hello` arrives from an `id` that already has a ready session, the host finishes the handshake on the
new socket, then sends `{"t":"bye","reason":"replaced"}` on the old one and closes it. Clients do the same
for outbound connections. A side that received `bye replaced` must not dial that peer again until a network
change or an explicit user action; a device never dials a peer it already has a ready session with.

### 3.5 Unpairing

`{"t":"unpair"}` — the sender has deleted its key; the receiver deletes its key and closes. "Forget device"
in any UI sends this if connected, then deletes locally.

### 3.6 Secure channel (mandatory after `ready`)

Once `auth_ok`/`pair_ok` has been exchanged, both sides derive session keys with forward secrecy:

```
keys  = HKDF-SHA256(ikm = Z, salt = cNonce | hNonce, info = "airbridge/v2/session|" | T, length = 64)
k_c2h = keys[0..32)     // client → host
k_h2c = keys[32..64)    // host → client
```

(`Z`, `T` as in §3.2/§3.3. HKDF = RFC 5869 extract-then-expand; implement with HMAC-SHA256 where the
platform lacks it.) The ephemeral private keys are destroyed after derivation.

**Every** subsequent message, in both directions, is one WebSocket **binary** frame:

```
byte 0        : 0x02  (kind = encrypted envelope)
bytes 1..8    : u64 big-endian  seq   (per direction, starts at 0, strictly +1 per frame)
bytes 9..     : AES-256-GCM( key = direction key, nonce = 0x00000000 ‖ seq (12 bytes), aad = bytes 0..8,
                             plaintext = u8 innerKind | payload )   // ciphertext ‖ 16-byte tag
```

- `innerKind` 0 = a JSON text message (payload = UTF-8 JSON), 1 = a binary frame as defined elsewhere
  (payload starts with its own kind byte, e.g. `0x01` file chunk, `0x03` video frame).
- A receiver rejects a frame whose `seq` is not exactly the next expected value (replay/reorder) and closes.
- A plaintext text frame or an unencrypted `0x01`/`0x03` frame after `ready` → close with `bye error` (the
  `bye` itself travels inside the envelope). Unknown outer kinds other than those are ignored. The first
  encrypted frame may follow `auth_ok`/`pair_ok` immediately.
- WebSocket-level ping/pong frames stay unencrypted (they carry no data). `ping`/`pong` messages are encrypted
  like everything else.
- Over transports that already encrypt (Nearby Connections) the envelope is still applied; it is cheap.

Threat model after this section: a LAN sniffer sees only device ids/names/platform in `hello` and ciphertext.
An active attacker cannot pair (confirm digits mismatch) nor hijack an existing pairing (no `K`).

---

## 4. Liveness

- `{"t":"ping","ts":<unix ms>}` → the receiver replies `{"t":"pong","ts":<same>}` immediately.
- Every side sends `ping` after **5 s** of not having sent anything.
- Any received frame counts as liveness. If nothing is received for **15 s**, the side closes the socket and
  (for clients) reconnects.
- WebSocket-level pings may be used in addition, never instead.

### 4.1 Reconnect policy (clients)

- Reconnect with exponential backoff: 1 s, 2 s, 4 s … capped at 30 s, reset on success.
- On any network change (interface up/down, new IP) → re-resolve via mDNS and retry immediately.
- Never keep more than one connection attempt per peer `id` in flight.
- Closing with `bye` reason `replaced` or an explicit user disconnect must **not** trigger reconnect.

---

## 5. Clipboard

```json
{"t":"clip","kind":"text","text":"...","ts":<unix ms>}
```

- `kind` is `text` for now (`image` reserved, will use binary kind `0x02`).
- Max 512 KiB of `text`. Larger content is not sent; the sender shows a local notice.
- Sender: on local clipboard change, if the content differs from the last content set *by* AirBridge, send
  `clip` to **all** ready sessions. Local filter rules (ignore starts-with/contains) apply before sending.
- Receiver: set the local clipboard, remember a hash of the text, and **do not** re-broadcast when the local
  clipboard monitor sees that same content (echo suppression). There is no relaying through a host — each
  device is connected to every device it wants to sync with.
- Android: because Android 10+ only allows clipboard reads from the focused app, the Android app sends `clip`
  when the user triggers a push (tile, bubble, share) and **automatically** after a `copy` action requested by
  a controller (§8.5) and when the app comes to the foreground with changed content.
- Android automatic sync (setting "Send copies to my computers automatically", default on): while the service
  runs, a local copy (clipboard listener where the ROM delivers it in the background; on stock Android 10+ only
  via the accessibility service, from the system clipboard overlay or a "Copy"/"Cut" tap) is debounced (400 ms),
  then read by briefly taking focus (a 1×1 overlay window, else the invisible activity) and sent as `clip`.
  Never sent automatically: clips flagged `android.content.extra.IS_SENSITIVE`, clips from another device
  (`IS_REMOTE_DEVICE`), file lists, and AirBridge's own clips (label `AirBridge`, 2 s echo window, hash). If the
  focused read cannot happen, the bubble shows a dot ("not sent yet"); tapping it sends. Nothing runs while the
  service is not running.
- Android floating bubble: it exists only for manual pushes. It is **hidden** while the phone is under a control
  lease (§8.6) or while the trackpad/mirror screen is open (copies are automatic there), and comes back when
  that ends.

---

## 6. File transfer

Streaming, chunked, with receiver-driven flow control. Never read a whole file into memory.

### 6.1 Control messages

```json
S→R {"t":"xfer_offer","id":7,"files":[{"i":0,"name":"IMG_0001.jpg","size":3145728,"mime":"image/jpeg"}],"total":3145728}
R→S {"t":"xfer_accept","id":7,"window":8388608}
R→S {"t":"xfer_reject","id":7,"reason":"busy|disk|user"}
R→S {"t":"xfer_ack","id":7,"received":1048576}
S→R {"t":"xfer_done","id":7}
R→S {"t":"xfer_complete","id":7,"ok":true,"saved":["IMG_0001.jpg"]}
any {"t":"xfer_cancel","id":7,"dir":"out","reason":"user|error|disconnect"}
```

- `id` is chosen by the sender, unique within the session (a counter is fine). Because both directions pick
  their own ids, `xfer_cancel` carries `dir` from the point of view of the device sending the cancel:
  `"out"` = "my outgoing transfer", `"in"` = "the transfer I am receiving".
- The receiver sends `xfer_complete` only after it has processed `xfer_done` and written every earlier chunk.
  If a file is still incomplete when `xfer_done` arrives, the receiver sends `xfer_cancel` with reason `error`
  (there is no `ok:false`).
- `files[].i` is the file index, 0-based, in send order. `size` in bytes (may be 0).
- Paired devices **auto-accept** by default. (A future "ask first" setting may `xfer_reject` with `user`.)
- `xfer_ack.received` is the cumulative number of payload bytes received across all files of the transfer.
  The receiver sends it at least every **1 MiB** and after the last byte of each file.
- `window` (bytes) is chosen by the receiver (default 8 MiB). The sender must never have more than `window`
  unacknowledged bytes in flight: `sentBytes - lastAckedReceived <= window`. This replaces all sleeps.
- `xfer_done` is sent after the last chunk; `xfer_complete` is sent once every file has been fully written
  and closed. `saved` contains the final file names actually used (after de-duplication).
- On `xfer_cancel` or session loss the receiver deletes partial files and clears state for that `id`.

### 6.2 Binary chunk frame

```
byte 0        : 0x01  (kind = file chunk)
bytes 1..4    : u32 big-endian  transfer id
bytes 5..8    : u32 big-endian  file index
bytes 9..16   : u64 big-endian  byte offset of this chunk within the file
bytes 17..    : payload (1 .. 1 MiB bytes; recommended 256–512 KiB)
```

- Chunks of one file are sent in increasing offset order; a receiver may still use the offset to write.
- A file is complete when `offset + payloadLength == size`. Zero-size files have no chunks.
- Chunks for unknown/cancelled `id` are ignored.

### 6.3 Saving

- Destination: `~/Downloads/AirBridge Transfers/` (macOS, Windows, Linux) or MediaStore `Download/AirBridge
  Transfers/` (Android).
- File name sanitisation (receiver, mandatory): take the last path component only; remove `\0`, `/`, `\`,
  control characters and `<>:"|?*`; strip leading dots and spaces and trailing dots and spaces; prefix
  Windows reserved names (`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`, with or without extension) with
  `_`; truncate to 200 bytes; if empty use `file`. If the name exists, append ` (2)`, ` (3)` … before the
  extension.
- After completion the receiver puts the received file URLs on the clipboard (where the platform supports
  file clipboards) and shows a notification.

### 6.4 Speed guidance

- 512 KiB chunks, 8 MiB window, no artificial delays, write to disk off the receive thread (bounded queue).
- Disable Nagle on the TCP socket if the stack allows. Use WebSocket permessage-deflate = **off**.

---

## 7. Device & state messages

```json
{"t":"bye","reason":"replaced|user|shutdown|error"}       // sent right before closing, best effort
{"t":"error","code":"string","msg":"human readable"}      // informational, never fatal by itself
{"t":"name","name":"New Name"}                            // sender renamed itself; receiver updates its record
{"t":"key_state","caps":true}                             // see §8.3
```

---

## 8. Remote control

Terms: the **controller** is the device whose physical pointer/keyboard is used; the **target** is the device
that renders a virtual cursor and injects the input. All messages are direction-agnostic: a phone in trackpad
mode is a controller sending to a Mac target, and a Mac moving its cursor off-screen is a controller sending
to a phone target. A device only has to honour messages matching its `caps`.

### 8.1 Entering / leaving a target

```json
controller→target {"t":"ctl_enter","edge":"left","rel":0.42}
target→controller {"t":"ctl_leave","edge":"left","rel":0.40}
controller→target {"t":"ctl_leave"}
```

- `edge` in `ctl_enter` is the edge **of the target screen** where the cursor appears. A controller that has
  the target arranged on its *right* sends `"edge":"left"`. `rel` ∈ [0,1] is the position along that edge
  (top→bottom for `left`/`right`, left→right for `top`/`bottom`).
- The target shows its virtual cursor at that point and starts applying `ptr_*`/`key` events.
- When the target's virtual cursor is pushed past the edge it entered through, the target sends `ctl_leave`
  with its own edge and `rel`; the controller resumes its local cursor at the matching point of its own edge.
  **This is the only required way back**; a controller may additionally offer a hotkey that sends
  `ctl_leave` (no edge) to recall the cursor.
- On `ctl_leave` in either direction, **both** sides release anything held: the target synthesises key-up for
  every pressed key, button-up for every pressed button; the controller resets its modifier tracking.
- **Acknowledgement (required).** A target answers `ctl_enter` with `{"t":"ctl_ack"}` as soon as its virtual cursor
  is shown and it can inject input. If it cannot (for example Android's accessibility service is off) it answers
  `{"t":"ctl_leave","reason":"unavailable"}` instead. A controller that has no `ctl_ack` within **600 ms** of
  `ctl_enter` ends the capture itself: it puts its cursor back at the entry point, tells the user the device is not
  ready for remote control, and does not capture that peer again for 5 s.
- **Escape is the controller's job.** A controller never depends on the target to give the cursor back. It also
  tracks the depth of the target's cursor from the deltas it sends and returns control when the user moves back
  through the entry edge, on `ctl_leave`, on the recall hotkey, and when the peer disappears. While captured it
  shows a visible hint how to return. On return the cursor reappears where the user's motion left it (the entry edge
  point plus the sideways travel), never at a stale earlier position.
- **Edge assignment is explicit.** A controller must not place a peer on an edge by itself except on a left or right
  edge with a middle span; top and bottom edges (menu bar, Dock) are never auto-assigned.
- A target controlled by one session that receives `ctl_enter` from another session accepts the new one and
  sends `ctl_leave` (with the old controller's entry edge and the current `rel`) to the old one.
- With several displays, `rel` is measured along the bounding box of **all** displays combined; a controller
  places an entering cursor on the outermost display at that coordinate.
- Trackpad/direct mode: a `trackpad` controller sends `ptr_*`/`key`/`action` **without** `ctl_enter`;
  targets accept them from any ready session.

Layouts (which peer sits on which edge, and the span of the edge) are a **local** setting of each controller;
nothing is exchanged. A controller may assign at most one peer per edge.

### 8.2 Pointer

```json
{"t":"ptr_move","dx":1.5,"dy":-2.0}             // relative, in controller pixels; target applies its own gain
{"t":"ptr_scroll","dx":0.0,"dy":-12.0,"px":true} // px=true: pixel deltas (trackpad); px=false: notch/line deltas
{"t":"ptr_btn","b":0,"down":true}               // b: 0 left, 1 right, 2 middle
{"t":"ptr_click","b":0,"n":2}                   // discrete click(s); n = click count (1 single, 2 double)
```

- `ptr_move`: positive `dy` moves down, positive `dx` moves right. Units are the controller's native pointer
  units (points on macOS, physical pixels on Windows and Android); the target applies its own gain.
- `ptr_scroll` values describe **content motion**: positive `dy` = content moves up, positive `dx` = content
  moves left. The controller converts its physical input using its own natural-scrolling preference; targets
  apply the values as-is and never invert again.
- `ptr_btn`: `b:0` is the primary button. Down/up are always paired by the controller. Drags are button-down +
  moves + button-up; the target must support real-time drag (Android: `StrokeDescription` with `willContinue`).
- `ptr_click.n` is the click count **of this click** (like macOS `clickState`): a double tap arrives as `n:1`
  followed by `n:2` within the double-click interval. Targets inject one click per message and must not
  synthesise extra clicks.
- `{"t":"ptr_abs","x":0.52,"y":0.31}` — absolute position, normalised to the target's primary display (or the
  mirrored display in §8.7), 0..1. Used by mirror sinks and any absolute pointing device. Targets move the
  cursor there without gain.

#### Capture intent (controller behaviour)

Reaching a screen edge is not a request to cross. A controller captures the cursor only on a **deliberate
push-through**: the cursor is at an outer edge inside the peer's span **and** the user keeps pushing outward
so that the accumulated outward delta while pinned at the edge reaches **≥ 100 units within 500 ms**. Any
inward movement, a pause > 500 ms, a button held down, or leaving the span resets the accumulator. After a
return from a peer, that edge ignores pushes for 700 ms (no bounce). Implementations may add a "hold a
modifier to cross" or "corner-only" option, and should show a subtle edge indicator while the accumulator is
charging. Edges with no assigned or offline peer never capture.
- Controllers may coalesce moves to ≤ 120 messages/s.

### 8.3 Keyboard

```json
{"t":"key","hid":0x04,"down":true,"mods":1,"rep":false,"text":"A"}
```

| Field  | Meaning |
|--------|---------|
| `hid`  | USB HID usage ID, page 0x07 (see §8.4). Modifier keys use their own IDs (0xE0–0xE7). |
| `down` | true = key down, false = key up. Every down must be followed by an up. |
| `mods` | bitmask of modifiers held **after** this event: 1 shift · 2 ctrl · 4 alt/option · 8 gui (cmd/win) · 16 caps-lock on (the controller's caps state) |
| `rep`  | auto-repeat (optional, default false). Controllers send repeats as further `down` events with `rep:true`; targets inject each one and never generate their own repeats. |
| `text` | the character(s) this key press produces with the current modifiers/layout, if printable (optional, only on `down`). Omitted while ctrl or gui is held, so shortcuts never type. Targets that cannot inject raw keys (Android accessibility) type `text` instead. |

- Controllers forward **every** key while controlling — they never hijack letters for special functions.
  Target-side semantics (e.g. Esc → Back on Android, Cmd/Win+H → Home) are decided by the **target**.
- Media/consumer keys (volume, brightness, play/pause…) are **not** sent as `key`; they are `action`s.
- `{"t":"key_state","caps":true|false}` is sent by any device whose physical caps-lock state changed, and on
  `ctl_enter`/when a trackpad session becomes ready. Receivers may show a Caps Lock indicator.

### 8.4 HID usage IDs (page 0x07) — reference

| Keys | HID |
|------|-----|
| A–Z | 0x04–0x1D |
| 1–9, 0 | 0x1E–0x27 |
| Enter, Esc, Backspace, Tab, Space | 0x28, 0x29, 0x2A, 0x2B, 0x2C |
| `-` `=` `[` `]` `\` | 0x2D, 0x2E, 0x2F, 0x30, 0x31 |
| `;` `'` `` ` `` `,` `.` `/` | 0x33, 0x34, 0x35, 0x36, 0x37, 0x38 |
| Caps Lock | 0x39 |
| F1–F12 | 0x3A–0x45 |
| PrintScreen, ScrollLock, Pause | 0x46, 0x47, 0x48 |
| Insert, Home, PageUp, Delete(fwd), End, PageDown | 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E |
| Right, Left, Down, Up | 0x4F, 0x50, 0x51, 0x52 |
| Keypad `/ * - + Enter 1-9 0 .` | 0x54–0x63 |
| F13–F24 | 0x68–0x73 |
| LCtrl, LShift, LAlt, LGui, RCtrl, RShift, RAlt, RGui | 0xE0, 0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7 |

Platform mapping (each implementation owns a complete table):
- **macOS**: Carbon `kVK_*` virtual keycode ↔ HID (e.g. kVK_ANSI_A 0 ↔ 0x04, kVK_Return 36 ↔ 0x28,
  kVK_Delete 51 ↔ 0x2A, kVK_Command 55 ↔ 0xE3, kVK_RightCommand 54 ↔ 0xE7, kVK_Option 58 ↔ 0xE2,
  kVK_Control 59 ↔ 0xE0, kVK_Shift 56 ↔ 0xE1, kVK_LeftArrow 123 ↔ 0x50).
- **Windows**: use the hardware **scan code** (+ extended flag) from `KBDLLHOOKSTRUCT`, mapped with the
  standard PS/2 set-1 → HID table; for injection use `SendInput` with `KEYEVENTF_SCANCODE` (HID → set-1).
- **Android** (target): HID → `KeyEvent` keycode for navigation keys; printable keys use `text`.
- **Linux**: evdev keycodes map 1:1 through the kernel's HID table.
- **iOS**: `UIKey.keyCode` is already a HID usage.

### 8.5 Actions

```json
{"t":"action","name":"home"}
```

| name | Meaning (targets ignore what they can't do) |
|------|------|
| `home` `back` `recents` `last_app` `app_drawer` `notifications` `quick_settings` | Android navigation |
| `mission_control` `app_expose` `desktop_left` `desktop_right` `spotlight` `lock` `keyboard_toggle` | macOS / desktop (`desktop_left` = switch to the desktop on the left, i.e. a three-finger swipe to the right on macOS) |
| `vol_up` `vol_down` `vol_mute` `brightness_up` `brightness_down` | hardware |
| `media_play_pause` `media_next` `media_prev` | media |
| `assistant` `dnd_toggle` | system |
| `swipe_up` `swipe_down` `swipe_left` `swipe_right` | gesture at the current cursor position |
| `copy` `paste` | clipboard action in the focused app. On Android, `copy` is followed by an automatic `clip` push (§5). |

### 8.6 Control arbitration — who controls whom

**Which device gets controlled** is decided on the controller: each outer edge of its screen layout has at most
one assigned peer (Display Arrangement), each peer sits on at most one edge, and the controller's UI always
shows the mapping ("Right edge → Pixel 9"). Pushing through an edge controls exactly that peer. Trackpad and
mirror modes target the peer the user picked on the phone (a chooser appears when more than one desktop is
connected; the last choice is remembered and shown on screen).

**What a target does with competing input** — a target holds at most one **control lease**:

| Event | Effect |
|-------|--------|
| `ctl_enter` from peer A | A takes the lease immediately (a person physically pushing a cursor always wins). If B held it via `ctl_enter`, B receives `ctl_leave` (entry edge + current `rel`); if B held an implicit (trackpad/mirror) lease, B receives `ctl_busy {holder: A}`. Either way the target releases B's held keys/buttons. |
| `ptr_*`/`key`/`action` from peer A while nobody holds a lease | A takes an **implicit** lease (trackpad/mirror use). It expires after **1500 ms** without input from A. |
| Input from peer B while A holds the lease | Dropped. B receives `{"t":"ctl_busy","holder":"<A's name>"}` at most once per 2 s and shows "Being controlled by A". Exception: `ctl_enter` from B pre-empts as above. |
| `ctl_leave` from the holder, holder disconnects, or implicit lease expires | Lease released; held keys/buttons released. |

Targets show their state ("Controlled by Shazan's MacBook"); controllers show theirs ("Controlling Pixel 9 ·
press Ctrl+Opt+Cmd+Esc to return"). `key_state`, `clip` and file transfers are never affected by leases.
A controller that receives `ctl_busy` stops forwarding and restores its local cursor without sending
`ctl_leave`. A device that is itself under a lease does not capture onward (no chaining). An implicit lease
whose holder has a button down is kept until the 5 s button watchdog instead of the 1500 ms idle timer.

### 8.7 Screen mirror ("touchscreen" mode)

A `mirror_sink` (phone) shows a `mirror_source`'s (Mac/PC) screen live and sends absolute input. Latency is
the design goal: encoder in real-time mode, no B-frames, decode straight to a surface, drop late frames.

```json
sink→source   {"t":"mirror_start","codecs":["hevc","h264"],"maxW":1600,"maxH":1000,"fps":60,"bitrate":8000000}
source→sink   {"t":"mirror_info","codec":"h264","w":1512,"h":982,"fps":60,"display":{"w":3024,"h":1964}}
sink→source   {"t":"mirror_key"}                // please send a keyframe now (after loss/decoder reset)
sink→source   {"t":"mirror_ack","pts":123456}   // every 10 frames: flow control
either        {"t":"mirror_stop"}
```

Video frames travel inside the encrypted envelope as inner binary frames:

```
byte 0        : 0x03  (kind = video frame)
bytes 1..8    : u64 big-endian  pts in microseconds (source clock)
byte 9        : flags  bit0 = keyframe, bit1 = contains parameter sets (SPS/PPS/VPS)
bytes 10..    : Annex-B elementary stream (start-code prefixed NAL units) for the negotiated codec
```

Rules:
- Source: scale to fit `maxW×maxH` keeping aspect, `fps` ≤ requested, keyframe at most every 2 s and on
  `mirror_key`, parameter sets repeated with every keyframe, bitrate adaptive from `mirror_ack` lag; never
  queue more than 2 unacknowledged frames beyond the ack window (drop, don't buffer). Stop on `mirror_stop`,
  on sink disconnect, and when the sink stops acking for 3 s.
- Sink: render the newest frame, discard older undecoded frames, keep the decoder in low-latency mode. Ack
  every 10 received frames **or** every 500 ms, whichever comes first (the source may hold at most 12
  unacknowledged frames; it re-sends the last frame at ~10 fps while the screen is static so acks keep
  flowing). Decoders must tolerate SEI NAL units. H.264 keyframes = SPS, PPS, IDR; HEVC keyframes = VPS, SPS,
  PPS, SEI, IDR; 4-byte start codes; `mirror_info.display` is in pixels; `pts` is strictly increasing.
- Errors: `{"t":"error","code":"mirror_permission"}` (screen recording not granted) or `"mirror_codec"` (no
  hardware encoder), each followed by `mirror_stop`.
  Touch → `ptr_abs` (normalised to the mirrored display) + `ptr_click`/`ptr_btn`; two fingers → `ptr_scroll
  px:true`; the on-screen keyboard sends `key`. Mirror input uses the normal lease rules (§8.6).
- A source needs the OS screen-recording permission; it advertises `mirror_source` only when granted.

---

## 9. Limits and encoding

- Text frame ≤ 1 MiB; binary frame ≤ 1 MiB + 17 header bytes (+ 26 bytes of envelope overhead: 9 header,
  1 inner kind, 16 tag).
- Binary kinds: `0x01` file chunk (§6.2), `0x02` encrypted envelope (§3.6, outermost), `0x03` video frame
  (§8.7). Others reserved.
- JSON: no comments, no NaN/Infinity. Floats are IEEE doubles formatted plainly (`-0.5`, not `-5E-1`);
  senders round to at most 4 decimals so stock JSON encoders never fall back to exponent notation.
- Field names are exactly as written here (short, lowercase).
- Timestamps are Unix milliseconds (`ts`).

---

## 10. Transport abstraction

The message layer (everything above) needs only an ordered, reliable, duplex channel that delivers discrete
**text** and **binary** messages. Each implementation separates:

```
Discovery  (mDNS · Nearby · future BLE/Wi-Fi Aware)   →   finds (id, name, plat, address)
Transport  (WebSocket · Nearby payloads · future TCP/Wi-Fi Direct)   →   sendText / sendBinary / onText / onBinary / close
Session    (handshake, liveness, routing by id)        →   one per peer
Services   (clipboard, xfer, control)                  →   stateless w.r.t. transport
```

For transports without native text/binary framing, frame each message as
`u8 kind (0 = text, 1 = binary) | u32 big-endian length | payload`.

Nearby Connections (Android↔Android): one long-lived STREAM payload per direction carries the framed
messages (BYTES payloads are capped at 32 KiB). Strategy `P2P_STAR` with disruptive bandwidth upgrades
allowed, so Nearby brings up Wi-Fi Direct / hotspot links on its own — **no shared network is needed** and
transfers run at Wi-Fi speeds. The handshake is identical (§3), with mode `confirm` auto-accepted after
Nearby's own digit confirmation.

---

### 10.1 Link upgrades (Wi-Fi Direct, Android ↔ Windows)

A second, faster link between two peers that already have a ready session (LAN, or later BLE). Normative for the
`wfd_*` types; other `type` values (`hotspot` for the Mac path) are still proposals (docs/PROXIMITY_DESIGN.md §4.2).

New `hello` caps: `wfd_host` (this device can host a Wi-Fi Direct legacy group; Windows after at least one
successful start), `wfd_join` (can join one; Android API ≥ 29).

```json
{"t":"link_request","lid":7,"why":"xfer|mirror|user","bytes":734003200,"types":["wfd_legacy","wfd_go"]}
{"t":"link_offer","lid":7,"type":"wfd_legacy","cred":"derived","gen":0,"ssid":"DIRECT-AB-7KQ2M9XA","sec":"wpa2",
 "band":"2.4|5|unknown","addr":["192.168.137.1"],"port":47632,"ttl":120}
{"t":"link_ready","lid":7,"addr":"192.168.137.23","freq":5180,"sta":"kept|dropped|none"}
{"t":"link_fail","lid":7,"reason":"no_go_support|hotspot_active|radio_off|join_failed|no_ip|approval_denied|vpn|api_level|timeout|busy|user"}
{"t":"link_done","lid":7,"reason":"idle|ttl|user|lost"}
```

- `lid` is chosen by whoever starts the link and is unique per session; desktops choose lids from 2^30 upwards and
  phones from 1, so they never collide. A host may send `link_offer` unsolicited (host-initiated link, e.g. "Send
  fast" on the PC); a `link_offer` without a prior `link_request` is valid. All `link_*` messages travel inside §3.6
  envelopes.
- `wfd_legacy` (T1): the **Windows** side hosts a Wi-Fi Direct legacy group and the phone joins it as a normal Wi-Fi
  client; the Windows side is the WebSocket host (`addr`/`port` in `link_offer`). `wfd_go` (T2): the **phone** is the
  group owner and Windows joins as a Wi-Fi client; the phone sends `link_offer` with no `addr`, and after
  `link_ready{addr}` the Windows side is still the WebSocket host (it sends the address of its own new interface).
- `cred:"derived"`: no password is sent. Both sides compute the credentials from the pairing key `K` (below).
  `cred:"inline"` with a `psk` field is allowed for a pre-existing network.
- `addr` is a list of candidates (IPv4 first, then IPv6 link-local); `port` is the port actually bound (§2.2).
- Timing: the whole link must be ready within **30 s** of `link_request`; `ttl` (default 120 s) is extended while
  bytes flow; either side sends `link_done` after 20 s idle or at `ttl`. A `link_fail` or no `link_ready` within
  20 s stops the host side and the requester falls back to the existing link.
- The `xfer_offer` that triggered the upgrade is **held** by the sender until the new session is `ready`, then sent
  on it. Migration is make-before-break: dial the new link, handshake (§3), send `bye replaced` on the old
  session. **Exception to §3.4:** a device may dial the `addr` of an active `lid` even though it already has a
  ready session with that peer, and the "do not redial after `bye replaced`" rule does not apply to the session
  that was replaced by a link upgrade (it must be redialled when the link ends, so LAN/BLE comes back). Either side
  may redial the previously replaced path as soon as `link_done` has been sent or received, while the link session
  is still open (up to 3 s); the link session is then closed with `bye replaced`.

**Credential derivation** (fixed per pairing, never rotated by time: Android caches its per-app join approval by
SSID + security type, so a changing SSID would bring the approval dialog back every time):

```
wk   = HKDF-SHA256(ikm = K, salt = "airbridge/v2/wfd", info = hostId | "|" | clientId | "|" | u8(gen), 32 bytes)
ssid = "DIRECT-AB-" + base32(HMAC-SHA256(wk, "ssid")[0..5))      // RFC 4648 base32, 8 chars, no padding
psk  = base64url(HMAC-SHA256(wk, "psk")[0..24))                  // 32 ASCII chars
```

`gen` starts at 0; a "rotate Wi-Fi key" action bumps it and costs one more approval dialog. A re-pair changes `K`.
`hostId` is the WebSocket host's device id and `clientId` the dialing peer's id (as in §3.3).

## 11. Example session

```
C→H  {"t":"hello","v":2,"id":"a1…","name":"Pixel 9","plat":"android","app":"2.0.0","caps":["clip","xfer","ctl_target","trackpad","mirror_sink"],"nonce":"…","pk":"BA…","screen":{"w":1080,"h":2400}}
H→C  {"t":"hello","v":2,"id":"b2…","name":"Shazan's MacBook Pro","plat":"macos","app":"2.0.0","caps":["clip","xfer","ctl_source","ctl_target","mirror_source"],"nonce":"…","pk":"BA…","paired":true}
C→H  {"t":"auth","mac":"…"}
H→C  {"t":"auth_ok","mac":"…"}
H→C  {"t":"key_state","caps":false}
C→H  {"t":"clip","kind":"text","text":"hello","ts":1762070400000}
H→C  {"t":"ctl_enter","edge":"left","rel":0.31}
H→C  {"t":"ptr_move","dx":4,"dy":1}
H→C  {"t":"key","hid":11,"down":true,"mods":0,"text":"h"}
H→C  {"t":"key","hid":11,"down":false,"mods":0}
C→H  {"t":"ctl_leave","edge":"left","rel":0.35}
H→C  {"t":"xfer_offer","id":1,"files":[{"i":0,"name":"notes.txt","size":12}],"total":12}
C→H  {"t":"xfer_accept","id":1,"window":8388608}
H→C  <binary 0x01 | id=1 | i=0 | off=0 | 12 bytes>
H→C  {"t":"xfer_done","id":1}
C→H  {"t":"xfer_ack","id":1,"received":12}
C→H  {"t":"xfer_complete","id":1,"ok":true,"saved":["notes.txt"]}
C→H  {"t":"ping","ts":…}
H→C  {"t":"pong","ts":…}
```

---

## 12. Codebase contracts (how the input and network halves meet)

Each app is split into a **network half** (discovery, sessions, pairing, clipboard, files) and an **input
half** (controller + target behaviour, layouts, trackpad/keyboard UI). They meet through one small bus object
so the halves can be developed in parallel. The exact shapes are in each repo's `PeerBus` file:

- Android: `app/src/main/java/com/shazan/airbridge/net/PeerBus.kt`
- macOS: `AirBridge/PeerBus.swift`
- Windows: `AirBridge/PeerBus.cs`

The network half **implements** `send/broadcast/sendBinary/peers` and **calls** the registered handlers; the
input half **registers** the handlers and **calls** `send/broadcast/sendBinary`. Control-type messages routed
to the input half are exactly: `ctl_enter`, `ctl_leave`, `ctl_ack`, `ctl_busy`, `ptr_move`, `ptr_scroll`, `ptr_btn`,
`ptr_click`, `ptr_abs`, `key`, `key_state`, `action`, `mirror_start`, `mirror_info`, `mirror_key`,
`mirror_ack`, `mirror_stop`, plus `error` messages whose `code` starts with `mirror_`. Inner binary frames of
kind `0x03` (video) are routed to the input half's binary handler; kind `0x01` (file chunks) stays in the
network half. Encryption/decryption (§3.6) is entirely the
network half's job — the input half only ever sees plaintext messages.

(The example in §11 shows messages in the clear for readability; on the wire every message after `auth_ok`
is inside a `0x02` envelope.)
