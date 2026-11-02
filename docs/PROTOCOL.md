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
   `ready`.
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

- `k` is 32 random bytes, base64url without padding. It is the **pairing secret** (§3.3). The host rotates
  it every time the QR is shown again after a successful pairing, and at most every 10 minutes.
- `addr`/`port` are hints; the client still prefers the mDNS address for `id` if available.

Hosts also display a **6-digit pairing code** (regenerated after every successful pairing and every 5 minutes)
for the discovery flow where the client cannot scan (e.g. Windows pairing with a Mac).

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
- `nonce` – fresh per connection, used in auth/pair MACs.
- `screen` – optional, pixel size of the primary display.
- `paired` – **host only**: `true` if the host has a stored key for the client's `id`.

The client sends `hello` first. The host replies with its own `hello`.

### 3.2 `auth` / `auth_ok` / `auth_fail` (already paired)

Let `K` be the stored 32-byte shared key for this peer (§3.3). `cNonce`/`hNonce` are the raw bytes from the
two `hello` messages. `HMAC` = HMAC-SHA256. `|` = byte concatenation of the UTF-8 strings/bytes shown.

```json
C→H  {"t":"auth","mac":"<base64 HMAC(K, "auth|" | cNonce | "|" | hNonce | "|" | clientId)>"}
H→C  {"t":"auth_ok","mac":"<base64 HMAC(K, "auth_ack|" | hNonce | "|" | cNonce | "|" | hostId)>"}
H→C  {"t":"auth_fail","reason":"unpaired|bad_mac|version|busy"}
```

The client **must verify** `auth_ok.mac` (mutual authentication) and close if it is wrong.
On `auth_fail` with `unpaired` or `bad_mac`, the client forgets its stored key for that host and shows
"Pair again".

### 3.3 `pair` / `pair_ok` / `pair_fail` (first contact)

The client has a **pairing secret** `S` from one of two sources:

| Flow | `S` |
|------|-----|
| QR scan | the 32 raw bytes decoded from `k` |
| Code entry (user types the 6 digits shown on the host) | the UTF-8 bytes of the 6-digit string, e.g. `"483920"` |

Both sides derive the long-term key:

```
K = HMAC-SHA256(key = S, data = "airbridge/v2/pair|" | clientId | "|" | hostId)
```

```json
C→H  {"t":"pair","proof":"<base64 HMAC(K, "pair|" | cNonce | "|" | hNonce | "|" | clientId)>"}
H→C  {"t":"pair_ok","proof":"<base64 HMAC(K, "pair_ack|" | hNonce | "|" | cNonce | "|" | hostId)>"}
H→C  {"t":"pair_fail","reason":"bad_code|locked|not_pairing"}
```

Host rules:
- The host derives `K` for **both** its current QR secret and its current 6-digit code and accepts the
  proof if either matches.
- On success both sides persist `K` under the peer's `id` (Keychain on macOS, DPAPI/`ProtectedData` on
  Windows, app-private storage on Android) together with `name` and `plat`.
- After **5 failed** `pair` attempts from any source the host refuses pairing (`locked`) for 60 seconds and
  rotates the code.
- The client must verify `pair_ok.proof`.

Pairing secrets never travel over the network; only HMACs do. The QR flow is strong; the 6-digit code flow is
brute-forceable offline by a LAN sniffer and exists for convenience (TLS is a planned follow-up).

### 3.4 Duplicate sessions

If a `hello` arrives from an `id` that already has a ready session, the host finishes the handshake on the
new socket, then sends `{"t":"bye","reason":"replaced"}` on the old one and closes it. Clients do the same
for outbound connections.

### 3.5 Unpairing

`{"t":"unpair"}` — the sender has deleted its key; the receiver deletes its key and closes. "Forget device"
in any UI sends this if connected, then deletes locally.

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
any {"t":"xfer_cancel","id":7,"reason":"user|error|disconnect"}
```

- `id` is chosen by the sender, unique within the session (a counter is fine).
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
  control characters; strip leading dots and spaces; reject Windows reserved names (`CON`, `PRN`, `AUX`,
  `NUL`, `COM1-9`, `LPT1-9`); truncate to 200 bytes; if empty use `file`. If the name exists, append
  ` (2)`, ` (3)` … before the extension.
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
- A target controlled by one session that receives `ctl_enter` from another session accepts the new one and
  sends `ctl_leave` to the old one.
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

- Positive `dy` moves down, positive `dx` moves right. Scroll: positive `dy` = content moves up (natural
  scrolling is the target's preference, applied on the target).
- `ptr_btn` down/up are always paired by the controller. Drags are button-down + moves + button-up; the target
  must support real-time drag (Android: `StrokeDescription` with `willContinue`).
- Controllers may coalesce moves to ≤ 120 messages/s.

### 8.3 Keyboard

```json
{"t":"key","hid":0x04,"down":true,"mods":1,"rep":false,"text":"A"}
```

| Field  | Meaning |
|--------|---------|
| `hid`  | USB HID usage ID, page 0x07 (see §8.4). Modifier keys use their own IDs (0xE0–0xE7). |
| `down` | true = key down, false = key up. Every down must be followed by an up. |
| `mods` | bitmask of modifiers held **after** this event: 1 shift · 2 ctrl · 4 alt/option · 8 gui (cmd/win) · 16 caps-lock on |
| `rep`  | auto-repeat (optional, default false) |
| `text` | the character(s) this key press produces with the current modifiers/layout, if printable (optional, only on `down`). Targets that cannot inject raw keys (Android accessibility) type `text` instead. |

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
| `mission_control` `app_expose` `desktop_left` `desktop_right` `spotlight` `lock` `keyboard_toggle` | macOS / desktop |
| `vol_up` `vol_down` `vol_mute` `brightness_up` `brightness_down` | hardware |
| `media_play_pause` `media_next` `media_prev` | media |
| `assistant` `dnd_toggle` | system |
| `swipe_up` `swipe_down` `swipe_left` `swipe_right` | gesture at the current cursor position |
| `copy` `paste` | clipboard action in the focused app. On Android, `copy` is followed by an automatic `clip` push (§5). |

---

## 9. Limits and encoding

- Text frame ≤ 1 MiB; binary frame ≤ 1 MiB + 17 header bytes.
- JSON: no comments, no NaN/Infinity. Floats are IEEE doubles formatted plainly (`-0.5`, not `-5E-1`).
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

Nearby Connections (Android↔Android): each message is one BYTES payload with the framing above; the
handshake is identical (both sides already have a `K` from pairing or pair via the displayed code).

---

## 11. Example session

```
C→H  {"t":"hello","v":2,"id":"a1…","name":"Pixel 9","plat":"android","app":"2.0.0","caps":["clip","xfer","ctl_target","trackpad"],"nonce":"…","screen":{"w":1080,"h":2400}}
H→C  {"t":"hello","v":2,"id":"b2…","name":"Shazan's MacBook Pro","plat":"macos","app":"2.0.0","caps":["clip","xfer","ctl_source","ctl_target"],"nonce":"…","paired":true}
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

The network half **implements** `send/broadcast/peers` and **calls** the registered handlers; the input half
**registers** the handlers and **calls** `send/broadcast`. Control-type messages routed to the input half are
exactly: `ctl_enter`, `ctl_leave`, `ptr_move`, `ptr_scroll`, `ptr_btn`, `ptr_click`, `key`, `key_state`,
`action`. Everything else stays in the network half.
