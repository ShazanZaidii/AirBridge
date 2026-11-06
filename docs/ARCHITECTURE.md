# AirBridge v2 Architecture

Status: **design guide** (informative). `docs/PROTOCOL.md` is the normative wire spec, and this file never overrides it.
Proposals that would change the wire are in §7. They are marked **NOT NORMATIVE**.
Companion docs: [PROXIMITY_RESEARCH.md](PROXIMITY_RESEARCH.md) (which radios work where) and
[PLATFORM_ROADMAP.md](PLATFORM_ROADMAP.md) (Linux, iOS, Android↔Android).

---

## 1. Layers

PROTOCOL.md §10 defines four layers. This document makes them concrete. One rule holds throughout: **each layer only
talks to the layer directly below it.** Services never touch sockets, and transports never parse JSON.

```
 ┌──────────────────────────── INPUT HALF ─────────────────────────────┐
 │ input/ · Input/   controller (edge hooks, trackpad UI) + target      │
 │                   (injection, virtual cursor), layouts, HID maps     │
 └───────────────▲──────────────────────────────┬──────────────────────┘
                 │ controlHandler(peerId,msg)   │ send/broadcast/peers
 ════════════════╪══════════ PeerBus (§12) ═════╪═══════════════════════
                 │                              ▼
 ┌──────────────────────────── NETWORK HALF ───────────────────────────┐
 │ SERVICES   ClipboardService · TransferService · (control passthru)  │
 │               ▲  msg(peerId, json|bin)                              │
 │ SESSION    SessionManager ── Map<peerId, Session>                   │
 │            Session = handshake FSM · HMAC · liveness · per-session  │
 │                      xfer state · exactly ONE active Link           │
 │               ▲  sendText/sendBinary/onText/onBinary/close          │
 │ TRANSPORT  Link impls: WsLink (LAN) · NearbyLink · FramedStreamLink │
 │            (Wi-Fi Aware / Wi-Fi Direct / hotspot TCP / BLE L2CAP)   │
 │               ▲  dial(endpoint) / accept()                          │
 │ DISCOVERY  DiscoverySource impls: mDNS · Nearby · BLE · Wi-Fi Aware │
 │            → Candidate(id,name,plat,endpoint,source,quality)        │
 │            → CandidateTable (merged by id) → LinkSelector (§5)      │
 └─────────────────────────────────────────────────────────────────────┘
```

## 2. Per-platform mapping (target layout after the v2 rewrite)

File ownership follows the rewrite plan. The network half lives in `net/` or `Net/`, and the input half lives in
`input/` or `Input/`. The class names below are the *target shape*. If the rewrite agents pick other names, the layer
boundaries still apply.

| Layer | Android (`com.shazan.airbridge`) | macOS (`AirBridge/`) | Windows (`AirBridge/`) |
|---|---|---|---|
| Discovery | `net/discovery/NsdDiscovery` (NsdManager, `_airbridge._tcp`), `net/discovery/NearbyDiscovery` (Play-services Nearby; today `Strategy.P2P_CLUSTER`) | `Net/BonjourDiscovery` (NWBrowser `.bonjour`) | `Net/MdnsDiscovery` (Makaretu.Dns.Multicast; drop the 2–3 s `Zeroconf.ResolveAsync` polling) |
| Host listener | none (phones only dial) | `Net/HostListener` (NWListener + `NWProtocolWebSocket`, Bonjour advertise, port 47632…47641) | `Net/WsHost` (Fleck `WebSocketServer`, rejects any path except `/v2`) |
| Transport | `net/link/WsLink` (OkHttp), `net/link/NearbyLink` (BYTES payloads + §10 framing), later `net/link/FramedStreamLink` | `Net/WebSocketLink` (NWConnection + WS metadata), later `Net/FramedStreamLink` | `Net/FleckLink`, `Net/ClientWsLink` (ClientWebSocket), later `Net/FramedStreamLink` |
| Session | `net/session/Session`, `SessionManager`, `PairingStore` (app-private, encrypted), `Crypto` (HMAC-SHA256) | `Net/Session`, `Net/SessionManager`, `Net/KeychainStore` | `Net/Session`, `Net/SessionManager`, `Net/DpapiStore` (`ProtectedData`) |
| Services | `net/service/ClipboardService`, `net/service/TransferService` (MediaStore writer) | `Net/ClipboardService` (NSPasteboard `changeCount` poll), `Net/TransferService` | `Net/ClipboardService` (`AddClipboardFormatListener`), `Net/TransferService` |
| PeerBus impl | `net/NetPeerTransport : PeerBus.Transport` | `Net/NetPeerTransport : PeerTransport` | `Net/NetPeerTransport : IPeerTransport` |
| Input half | `input/ControlRouter` (registers handlers), `AirBridgeInputService` (accessibility target), `TrackpadActivity`, `input/HidMap` | `Input/InputInterceptor` (CGEventTap), `Input/MacInputSimulator`, `Input/LayoutStore`, `Input/HidMap`, `DisplayArrangementView` | `Input/InputInterceptor` (LL hooks), `Input/InputInjector` (`SendInput` + `KEYEVENTF_SCANCODE`), `Input/LayoutStore`, `Input/HidMap` |
| Process host | `AirBridgeService` owns `SessionManager`. **Switch FGS type `dataSync` → `connectedDevice`**: see the note below | App delegate owns `SessionManager` | `App.xaml.cs` owns `SessionManager` |

> **Android FGS note.** The manifest declares `foregroundServiceType="dataSync"` with `targetSdk = 36`. Two
> Android 15 rules then apply ([behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15)):
> - A `dataSync` FGS may run for only **6 h in any 24 h** before `onTimeout`. An always-on session host breaks.
> - `BOOT_COMPLETED` receivers may **not** start a `dataSync` FGS. The current `BootReceiver` would get a
>   `ForegroundServiceStartNotAllowedException`.
>
> `connectedDevice` describes the real use: "interactions with external devices that require a Bluetooth, NFC, IR,
> USB, or network connection". It has no timeout and is allowed from boot. Its prerequisite is met by declaring
> `CHANGE_NETWORK_STATE` or `CHANGE_WIFI_STATE`, or by holding a granted `BLUETOOTH_*` permission
> ([FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)).

The `PeerBus` seam is already in the repos: [`net/PeerBus.kt`](../app/src/main/java/com/shazan/airbridge/net/PeerBus.kt),
`AirBridge/PeerBus.swift`, and `AirBridge/PeerBus.cs`. It stays transport-agnostic. The input half sees a `peerId`
and never a link. A transport switch is therefore invisible to the input half.

## 3. Transport plug-in contract

A transport delivers **ordered, reliable, duplex, message-framed** text and binary data (PROTOCOL §10). Kotlin is
the reference below; Swift and C# mirror it.

```kotlin
enum class LinkType { LAN_WS, LAN_TCP, WIFI_AWARE, WIFI_DIRECT, HOTSPOT, AWDL, NEARBY, BLE_L2CAP, BLE_GATT }

interface Link {
    val type: LinkType
    val metrics: LinkMetrics            // rttMs (EWMA from ping/pong), estBps, maxFrame, metered
    var listener: LinkListener?         // onText(String) · onBinary(ByteArray) · onClosed(reason)
    fun sendText(s: String): Boolean    // never blocks; false = closed or queue over high-water mark
    fun sendBinary(b: ByteArray): Boolean
    val queuedBytes: Long               // backpressure signal for TransferService
    fun close(reason: String)
}
interface LinkFactory { fun dial(ep: Endpoint): Link; fun listen(onAccept: (Link) -> Unit): Closeable }
```

**Framing for non-WebSocket channels.** These are TCP over Wi-Fi Aware/Direct/hotspot, BLE L2CAP CoC, USB, and a
Nearby STREAM payload. Each one uses a single reusable `FramedStreamLink(input, output)`:

```
 ┌─────────┬──────────────────┬───────────────────────────┐
 │ u8 kind │ u32 BE length    │ payload (length bytes)    │   kind 0 = text (UTF-8 JSON), 1 = binary
 └─────────┴──────────────────┴───────────────────────────┘   length ≤ 1 MiB + 17 (PROTOCOL §9)
```

Implementation rules:
- There is one writer thread or actor per link, fed by a bounded queue. `TCP_NODELAY` is on. The reader rejects
  `length` above the limit and closes the link. This blocks memory-exhaustion attacks.
- **Nearby BYTES payloads** are capped at `MAX_BYTES_DATA_SIZE` = 1,047,552 bytes
  ([ConnectionsClient](https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/ConnectionsClient)).
  That is smaller than the 1 MiB + 17 + 5 bytes PROTOCOL allows. `NearbyLink` must therefore cap file-chunk
  payloads at ≤ 1 MiB − 1 KiB − 22 bytes. The recommended 512 KiB is fine.
- **BLE GATT** has no stream semantics. Fragment each frame across notifications or writes using the same 5-byte
  header, and reassemble on the other side. Use GATT only for control messages (§5).
- The dialer is the PROTOCOL "client" and sends `hello` first. A listener on a raw stream applies the same
  10 s handshake timeout as the WebSocket host.

## 4. Discovery plug-in contract

```kotlin
sealed interface Endpoint {
  data class Ip(val host: String, val port: Int, val iface: String?) : Endpoint   // mDNS, Aware/Direct IPv6, QR
  data class NearbyEp(val endpointId: String) : Endpoint
  data class AwarePeer(val handle: Any /* PeerHandle | WAEndpoint */) : Endpoint
  data class Ble(val address: String, val psm: Int?) : Endpoint
}
data class Candidate(val id: String?, val name: String, val plat: String,
                     val endpoint: Endpoint, val source: String, val seenAt: Long, val rssi: Int?)
interface DiscoverySource { fun start(sink: (Candidate) -> Unit, lost: (Candidate) -> Unit); fun stop() }
```

- **mDNS** yields `id` from TXT (PROTOCOL §2.3).
- **Nearby** must carry `id` itself. Pass `v=2|id|plat|name` as `endpointInfo` bytes to `startAdvertising`. Today
  the code passes `Build.MODEL`, so peers can't be matched to stored keys.
- **BLE** advertisements are public and trackable, so they must **not** carry the raw `id`. Advertise a resolvable
  tag instead: `tag = HMAC(K_peer, "ble|" | floor(unixTime/900))[0..7]`. Only a paired peer can match the tag to an
  `id`. Unpaired devices show up as anonymous "nearby AirBridge (pairable)" entries.
- The **CandidateTable** merges candidates by `id`. Each id keeps a set of endpoints with freshness and source.
  This table is the only input to link selection.
- **Session dedup by id** stays exactly PROTOCOL §3.4. A second ready session from the same `id` replaces the
  first, whichever transport it arrived on. `SessionManager` also enforces "≤ 1 dial attempt in flight per id"
  (§4.1) *across all transports*.

## 5. Multi-device routing and link selection

**Routing.** `SessionManager` holds `Map<peerId, Session>`. The send paths work like this:
- `PeerBus.send(id)` looks up the session for that id.
- `broadcast` iterates over ready sessions.
- `clip` goes to all ready sessions (§5). There is no relaying.
- Transfer ids are scoped per session (§6.1), so concurrent transfers to several peers don't interact.

Control routing is owned by the input half:
- Each controller keeps a local `edge → peerId` layout (§8.1, one peer per edge) and sends only to that peer.
- A target accepts the newest `ctl_enter` and sends `ctl_leave` to the previous controller.
- When a session drops while it is the active target, the controller must reclaim its cursor locally. `PeerBus`
  surfaces this through `peersHandler`.

**Link selection.** Several candidates may exist for one id, for example mDNS IPv4, a Nearby endpoint, and a BLE
tag. The `LinkSelector` scores each candidate:

```
score = typePrior + 10·log2(estMbps+1) − rttMs/5 − (metered ? 30 : 0) − (disruptive ? 50 : 0)
typePrior:  LAN_WS/LAN_TCP 100 · WIFI_AWARE / AWDL / WIFI_DIRECT 80 · HOTSPOT 60 · NEARBY 50 · BLE_L2CAP 20 · BLE_GATT 10
```

The intended preference order is **LAN > P2P Wi-Fi > hotspot > Nearby > BLE-only control channel**.

Selection rules:
1. **Connect on the cheapest working link first.** For a phone that is merely nearby, that is often BLE. Run the
   full handshake there so the session is `ready` quickly.
2. **Upgrade lazily.** A higher-bandwidth link is set up only when the work needs it:
   - an `xfer_offer` with `total` > 4 MiB;
   - an active trackpad or `ctl` session on a link with RTT > 30 ms;
   - a better candidate appearing for free, such as mDNS after both devices join the same Wi-Fi.
3. **"Disruptive" links never start automatically.** These include anything that makes a device leave its Wi-Fi:
   a Mac joining a phone hotspot, or Nearby `ConnectionType.DISRUPTIVE`. They need an explicit user action
   ("Send fast: temporarily join phone's Wi-Fi?").
4. **Use hysteresis.** Switch only for a score gain of at least 20, and at most once per 30 s. Drop P2P radios after
   60 s idle, because Wi-Fi Aware and AWDL cost battery
   ([Apple](https://developer.apple.com/documentation/network/nwparameters/includepeertopeer)).
5. **BLE-only sessions advertise reduced capability locally.** Services consult `link.metrics`:
   - `clip`, `ctl_*`, `key`, and `action` are allowed. 120 `ptr_move`/s at about 40 B each is roughly 5 KB/s,
     within L2CAP or 2M-PHY GATT budgets.
   - `xfer` above 256 KiB is queued until an upgrade succeeds, or the user is warned.

## 6. Switching links in today's v2 (no spec change)

PROTOCOL v2 binds a session to one connection. A switch is therefore done as a **make-before-break re-handshake**:

```
peer A                                                   peer B
  │── (old link L1, session S ready) ─────────────────────▶│
  │   dial L2 ── hello/auth on L2 ───────────────────────▶│  B: same id already ready → §3.4
  │◀────────────────────────────── auth_ok on L2 ──────────│     finish on L2, send bye{replaced} on L1, close L1
  │   SessionManager rebinds peerId → new Session(L2)      │
```

The cost is that per-session state dies with the old session: in-flight transfers are cancelled (§6.1). The sender
should automatically re-offer cancelled transfers with reason `disconnect`. This is acceptable for Phase 1–2, because
upgrades happen *before* a big transfer starts (rule 2).

## 7. Proposed protocol changes (NOT NORMATIVE — v2.1 draft)

Changes that only clarify the current spec. These are recommended now and don't change the wire:
- **§10**: state that the dialer is the "client", that the 10 s handshake timeout applies to every transport, that
  framed lengths above the §9 limit are fatal, and that on raw streams the first frame must be `hello`.
- **§2**: add normative advert formats for the non-mDNS sources (the Nearby `endpointInfo` and the BLE service-data
  tag from §4). Without them, Android, macOS, Windows, and Linux BLE implementations won't interoperate.
- **§6.2**: note the Nearby 1,047,552-byte payload ceiling.
- **Security**: v2 authenticates but does **not encrypt**. Clipboard text and files travel in cleartext on LAN and
  hotspot links. Derive `sessionKey = HKDF(K, cNonce|hNonce)` and wrap links in TLS-PSK or Noise `NNpsk0`
  before relying on open hotspots. Wi-Fi Aware NDPs are encrypted at L2 when a PSK is set; LAN is not.

Extension for live link migration. Behind cap `link`, and sent only when both `hello`s list it:

```json
A→B {"t":"link_offer","lid":3,"type":"wifi_aware","addr":"fe80::1%aware_data0","port":47700,"ttl":20}
B→A {"t":"link_accept","lid":3}            | {"t":"link_reject","lid":3,"reason":"unsupported|busy|user"}
B dials, then on the NEW link sends: {"t":"hello",...,"resume":"<sid>","lid":3} → normal auth (fresh nonces)
A→B (old link) {"t":"link_switch","lid":3}  // ordering marker: B processes L1 frames up to here, then L2
B→A (old link) {"t":"link_switch","lid":3}; both close L1 after draining
```

- `sid` is a random 16-byte session id that the host returns in its `hello`. On `resume`, xfer, ctl, and
  echo-suppression state stay attached to the session.
- `xfer_resume {"t":"xfer_resume","id":7,"from":<last xfer_ack.received>}` lets the sender continue a transfer that
  was interrupted mid-switch.
- If L2 fails during the TTL, L1 keeps working, so the failure needs no handling.
- Moving this to normative requires an explicit edit to PROTOCOL.md and simultaneous implementation on every
  platform (Design Rule: never invent messages).
