# AirBridge Without a Shared Wi-Fi: BLE + Hotspot Design

Status: **design (informative)**, 2026-11-02. [PROTOCOL.md](PROTOCOL.md) is normative and this file does not override
it. Everything marked **PROPOSED** would need a PROTOCOL edit and a simultaneous release on all platforms.
Builds on [PROXIMITY_RESEARCH.md](PROXIMITY_RESEARCH.md) and [ARCHITECTURE.md](ARCHITECTURE.md) §3–§7.
Scope: Android ↔ macOS first, with Android ↔ Windows noted throughout.

**Bottom line**
- BLE gives AirBridge a real "walk up and it works" link with no router. It carries clipboard, trackpad and
  universal-control input, and small files.
- For big files, the phone brings up a short-lived hotspot. The Mac joins it, the transfer runs at Wi-Fi speed,
  and the Mac then rejoins its own network.
- An AirDrop-identical link (both devices stay on their Wi-Fi) is impossible for a third-party Android↔Mac app.
  §1 explains why.

---

## 0. Strategy per device pair (supersedes the phase order in §4.4)

The link is chosen **per pair of platforms**, not globally. Only the Apple pairs need clever workarounds; every other
pair uses a platform-exposed peer-to-peer API directly. The selector (§4.1) holds a small `LinkProfile` table
`(ownPlatform, peerPlatform) → ordered link types`, and falls back down the list.

| Pair | High-speed link (no shared router) | Discovery / wake-up | Status |
|---|---|---|---|
| **Android ↔ Windows** | **Wi-Fi Direct**: Windows hosts a legacy P2P group (`WiFiDirectAdvertisementPublisher` + `LegacySettings`) and the phone joins it (`WifiNetworkSpecifier`); or the phone is group owner and Windows joins as a Wi-Fi Direct client. Both sides keep internet in the first form. | P2P device discovery itself, or a tiny BLE GATT "wake" write (Windows needs only GATT here, because bulk data does not use Bluetooth). Credentials are **derived from the pairing key and kept fixed per pairing** (PROTOCOL §10.1; a time epoch would bring Android's join approval dialog back every time) so nothing needs to be exchanged. | Exposed APIs on both sides → **build first** (Phase A) |
| **Android ↔ Linux** | Same idea: NetworkManager hotspot / `wpa_supplicant` P2P group; phone joins | BLE wake or P2P discovery | After the Linux client exists |
| **Android ↔ Android** | Nearby Connections (`P2P_STAR`, Wi-Fi Direct/hotspot upgrade) | Nearby discovery | Already built |
| **Android ↔ Mac** | Phone local-only hotspot that the Mac joins via CoreWLAN (§3), or USB NCM | BLE (§2) | The "make Apple cooperate" research track (Phase B) |
| Windows ↔ Mac, Linux ↔ Mac, Windows ↔ Linux | LAN only for now (a Windows-hosted AP the Mac joins is possible later) | mDNS | — |
| iOS ↔ any | Wi-Fi Aware / Multipeer for Apple peers, otherwise LAN | later | Not started |

**Phase order:** A = Android↔Windows Wi-Fi Direct (new, first), B = Android↔Mac BLE + hotspot spikes and build (§4.4
phases 0–2), C = Linux. Phases A and B are independent and can run in parallel because they sit behind the same
`LinkProfile` selector and the same `link_*` messages.

---

## 1. The AirDrop/AWDL question: settled

### 1.1 How AirDrop works

| Step | What happens | Source |
|---|---|---|
| 1. BLE trigger | The sender broadcasts a BLE advertisement. It carries short truncated SHA-256 hashes of the sender's contact identifiers. Receivers only bring up AWDL and their AirDrop server after they see this advertisement. | [OpenDrop README](https://github.com/seemoo-lab/opendrop); Stute et al., USENIX Security 2019 |
| 2. AWDL link | AWDL is Apple Wireless Direct Link, a proprietary 802.11 ad-hoc protocol. Peers elect a master and stay synchronised with vendor action frames. Each peer hops between its infrastructure channel and a social channel (6/44/149) in short "availability windows". The OS exposes this as `awdl0` (IPv6 link-local). | Stute et al., *One Billion Apples' Secret Sauce*, MobiCom 2018; [OWL](https://github.com/seemoo-lab/owl) |
| 3. Service discovery | mDNS/DNS-SD runs over `awdl0`. | OpenDrop |
| 4. Transfer | HTTPS on `awdl0`: `/Discover`, `/Ask`, `/Upload`. "Contacts Only" mode needs Apple-signed certificates and validation records. | [OpenDrop](https://github.com/seemoo-lab/opendrop) |

Because AWDL time-shares the radio, the Mac stays on its home Wi-Fi during an AirDrop.

### 1.2 What a third party can and cannot touch

| Lever | Verdict |
|---|---|
| **`NWParameters.includePeerToPeer` / MultipeerConnectivity** (Mac side) | ✅ A Mac app can use AWDL through Network.framework ([docs](https://developer.apple.com/documentation/network/nwparameters/includepeertopeer)), but only with peers that themselves speak AWDL. Apple provides no AWDL stack for other OSes. Multipeer is Apple-only and effectively deprecated ([DTS](https://developer.apple.com/forums/thread/769043)). The Mac side is not the blocker: OWL showed that stock macOS interoperates with a correct AWDL implementation. |
| **OWL (open AWDL)** on Android | ❌ OWL needs a Wi-Fi card with active monitor mode and frame injection (the recommended chip is an Atheros AR9280), runs under `sudo` on Linux/macOS, and does not support VMs or WSL. The repo was **archived on 2026-10-12** as "no longer maintained" ([OWL](https://github.com/seemoo-lab/owl)). Android offers no public API for raw 802.11 TX/RX, monitor mode or channel scheduling. Getting them takes root plus patched firmware such as Nexmon, which only exists for some Broadcom chips. Most current phones use Qualcomm or MediaTek radios. **A Play-store app cannot speak AWDL.** |
| **OWL** on the Mac | Pointless. macOS already has AWDL. The missing piece is on the phone. |
| **Apple Wi-Fi Aware framework** | iOS/iPadOS 26+ only. The device list names iPhone 12+ and several iPads and **no Macs** ([docs](https://developer.apple.com/documentation/wifiaware)). DTS has confirmed it is absent on macOS 26 ([thread 787701](https://developer.apple.com/forums/thread/787701)). One loose end: the doc metadata also lists "Mac Catalyst 26.0", and a `nan0` interface showed up in the macOS 27 beta. DTS says only `WACapabilities.supportedFeatures` is authoritative ([thread 834091](https://developer.apple.com/forums/thread/834091)). The EU DMA obligations cover iOS/iPadOS, not macOS ([heise](https://heise.de/-10962179)). → Spike S10. |
| **Pixel 10 Quick Share ↔ AirDrop** | ✅ for the user, ❌ for us. It is Google's system feature, written in Rust and audited by NetSPI. It needs the Apple device in "Everyone for 10 minutes" mode, and Google says it works with iPhone, iPad and Mac ([Google](https://blog.google/security/android-quick-share-support-for-airdrop-security/), [Android Authority](https://www.androidauthority.com/amazed-concerned-google-bold-pixel-airdrop-move-3618462/)). Google did this without Apple ([The Register](https://www.theregister.com/2025/11/21/google_links_androids_quick_share/)). Since Macs have no Wi-Fi Aware, the Mac case implies Google reimplemented AWDL inside a privileged Pixel system module. That is an inference, consistent with the press coverage; Google has not published the protocol. There is **no developer API**. Expansion to more Android devices "in 2026" was announced without a device list ([TechRepublic](https://www.techrepublic.com/article/news-google-air-drop-android-2026/)). |
| **Quick Share on Mac** | No Google client exists. The reverse-engineered NearDrop is "Wi-Fi LAN only", because Apple's AWDL rules out Wi-Fi Direct ([NearDrop](https://github.com/grishka/NearDrop)). |

### 1.3 Precise conclusion

A non-root Android app cannot join AWDL, and macOS 26 offers no other peer-to-peer Wi-Fi: no Wi-Fi Aware, no Wi-Fi
Direct, and no soft-AP API. So no third-party app can get a high-bandwidth Android↔Mac link while the Mac stays on
its Wi-Fi network. The reachable options are:
- **BLE**: concurrent with Wi-Fi, low bandwidth.
- **The Mac temporarily joining a phone-hosted AP**: full bandwidth, but the Mac's Wi-Fi is disrupted.
- **USB NCM**: wired.

Two things could reopen the question. Watch them, but don't plan on them:
- Apple ships Wi-Fi Aware on macOS (S10).
- Google ships an AirDrop-interop API.

Pixel 10 owners can already AirDrop Mac→phone natively. AirBridge cannot hook into that.

---

## 2. BLE as a real transport

### 2.1 Roles

| Pair | Advertiser | Scanner / GATT central (dials) | Server (GATT + L2CAP) | Why |
|---|---|---|---|---|
| Android ↔ Mac | Both | **Android** (phone = v2 client, as on LAN) | **Mac** (`CBPeripheralManager` + `publishL2CAPChannel`) | An Apple central cannot set connection or CoC parameters, so it is slow: ~36 KB/s, 60 KB/s with a 15 ms interval. An Apple **peripheral** reached ~100 KB/s ([thread 723218](https://developer.apple.com/forums/thread/723218)). An Android central can request `CONNECTION_PRIORITY_HIGH` and 2M PHY. |
| Mac-initiated (phone not dialing) | — | Mac (scans phone adverts) | Android (`listenUsingInsecureL2capChannel`) | Fallback when the phone's scan is throttled. Dedup per PROTOCOL §3.4. |
| Android ↔ Windows | Android | **Windows** (`BluetoothLEAdvertisementWatcher`) | **Android** `BluetoothGattServer` (GATT only) | Windows has no LE CoC API (§2.4). With Windows as central, `RequestPreferredConnectionParameters(ThroughputOptimized)` is available on Windows 11 ([docs](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth.bluetoothledevice.requestpreferredconnectionparameters)). Windows never needs to advertise. |

### 2.2 Advertisement format (PROPOSED, cap `ble`)

`CBPeripheralManager` only accepts `LocalName` and `ServiceUUIDs` in its advertisement, so the Mac **cannot send
service or manufacturer data**. The tag therefore goes inside a 128-bit UUID. That is the one format every platform
can both emit and filter:

```
advert UUID  = AB2B1E7C-4D90-5F vv - tttttttt tttttttt
               └ 7-byte AirBridge prefix ┘ └flags┘ └ 8-byte rotating tag ┘
flags (vv)   = bits 7..4 version (2) · bit0 pairable · bit1 has L2CAP server · bit2 can host hotspot
tag          = HMAC-SHA256(advKey_self, "airbridge/v2/ble|" ‖ u32be(floor(unix/900)))[0..8]
GATT service = AB2B1E7C-4D90-5F00-0000-000000000001 (fixed)
```

- `advKey_self` works like Apple's IRK: 32 random bytes per device. It is sent to each peer over the encrypted session
  with `{"t":"adv_key","k":"<b64>"}` after `ready` (**PROPOSED**).
- **Why a per-device key and not the pairing key `K`.** A Mac paired with three phones can only broadcast one
  advertisement, so the tag must come from a key all three phones know.
- **Resolver.** For each paired peer, try epochs e-1, e and e+1 (a 45-minute window). A match maps the advertisement
  to that peer's `id`. The raw `id` and name never appear over the air (ARCHITECTURE §4).
- **Pairing mode.** When the pairable bit is set and the tag does not resolve, show "AirBridge device nearby
  (pairable)". Its name comes from `hello` once connected.

Scanner filters:

| Platform | Filter |
|---|---|
| Android | `ScanFilter.setServiceUuid(prefixUuid, mask FFFFFFFF-FFFF-FF00-0000-000000000000)`. Filtered scans keep running with the screen off. |
| Mac | `scanForPeripherals(withServices: nil, options: [AllowDuplicates: true])`, then match the prefix in code. A menu bar app is not suspended, so it needs no background mode. |
| Windows | `BluetoothLEAdvertisementBytePattern` on data type 0x07 (128-bit UUIDs, little-endian byte order). |

### 2.3 GATT layout and the data channel

Service `…0001` has three characteristics:

| Characteristic | UUID | Properties | Contents |
|---|---|---|---|
| **PSM** | Apple's standard `CBUUIDL2CAPPSMCharacteristicString` = `ABDD3056-28FA-441D-A470-55A75A52553A` | read | u16 PSM |
| **RX** | `…0002` | write-without-response | Data from central to server |
| **TX** | `…0003` | notify | Data from server to central |

The dialer connects GATT, reads PSM, and opens CoC. If CoC fails, it falls back to RX/TX.

Both paths carry the PROTOCOL §10 frames `u8 kind | u32be len | payload`. Each frame wraps the §3.6 encrypted
envelope (26 B overhead), so nothing new is needed for security.
- On **CoC**, frames go straight onto the stream.
- On **GATT**, frames are treated as a byte stream cut into `ATT_MTU − 3` writes. LE link-layer ACKs make that
  stream ordered and reliable. Flow control:
  - Android: `writeCharacteristic` returns `ERROR_GATT_WRITE_REQUEST_BUSY` (API 33) when the queue is full.
  - Apple, writing: check `canSendWriteWithoutResponse` and wait for `peripheralIsReady(toSendWriteWithoutResponse:)`.
  - Apple, notifying: when `updateValue` returns `false`, wait for `peripheralManagerIsReady(toUpdateSubscribers:)`.

| Channel | APIs | Realistic throughput (measure: S1/S2) | Notes |
|---|---|---|---|
| GATT WwR/notify, MTU 247–517, 2M PHY, 11.25–15 ms interval | Android `requestMtu(517)`, `setPreferredPhy(2M)`, `requestConnectionPriority(HIGH)`. Windows `GattSession.MaxPduSize`, ThroughputOptimized. | **20–60 KB/s** | 2M PHY gains about 77%, not 2×. Below a 15 ms interval there is little benefit ([Punch Through](https://punchthrough.com/ble-throughput-optimization-faq/)). |
| **L2CAP CoC** (credit-based) | Mac: `publishL2CAPChannel(withEncryption:false)` → `CBL2CAPChannel` streams. Android API 29+: `createInsecureL2capChannel(psm)` / `listenUsingInsecureL2capChannel()`. | **60–150 KB/s** (Apple as peripheral; forum reports up to 100 KB/s; vendor reports CoC ≈5× GATT ([Ditto](https://www.ditto.com/blog/introducing-v1-0-3-for-ios-now-faster))) | The brief's 100–400 KB/s is optimistic for Apple peers, so plan for ~100 KB/s. Apple can raise latency priority with `setDesiredConnectionLatency(.low, for:)`. |

**Pairing and bonding.** Use the *insecure* variants on both sides. That means no LE bonding, no OS pairing dialog
and no stored LTK; AirBridge's own v2 pairing and §3.6 AES-GCM provide security on top. The secure variants
`createL2capChannel` / `withEncryption:true` would force OS pairing, which would add a second pairing UX and
bond-loss issues (Android 17 adds autonomous re-pairing exactly because bond loss is common
([Android 17 changes](https://developer.android.com/about/versions/17/behavior-changes-all))). Mode `confirm` over
BLE gives a first pairing without a QR code.

**Platform caveats.**
- **Android must dial through the `BluetoothDevice` from the `ScanResult`.** That object keeps the random address
  type. The Mac's resolvable private address rotates, so cached MAC strings go stale.
- **Android 17 CoC reads.** `BluetoothSocket` LE-CoC `read()` returns `-1` on close, so the read loop must check for
  `-1` and not wait for an exception ([Android 17](https://developer.android.com/about/versions/17/behavior-changes-17)).
- **The CoC is not the GATT connection.** When it closes, close the GATT connection too.

### 2.4 Windows

`Windows.Devices.Bluetooth` (winrt-28000) has no L2CAP/PSM class at all
([namespace](https://learn.microsoft.com/en-us/uwp/api/windows.devices.bluetooth?view=winrt-26100)). So **Windows
is GATT-only** for BLE. Classic RFCOMM (`RfcommDeviceService` ↔ Android `listenUsingInsecureRfcommWithServiceRecord`)
might reach 100–200 KB/s, but whether it works without pairing is unverified (spike S8).
- Packaged apps (our `AirBridge.Package`) must declare `<DeviceCapability Name="bluetooth"/>`; unpackaged apps need
  nothing.

### 2.5 Background and permissions

| Platform | Background | Permissions / manifest |
|---|---|---|
| Android | Advertise and scan inside the existing `connectedDevice` FGS. While idle, use `SCAN_MODE_LOW_POWER` with filters; switch to `LOW_LATENCY` while UI is visible. `startScan(filters, settings, PendingIntent)` survives process death. CompanionDeviceManager presence is optional and only reliable for bonded devices, so skip it. | Already declared: `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES`. Add `ACCESS_LOCAL_NETWORK` for target 37 (it is in the same NEARBY_DEVICES group, so there is no extra prompt) ([Android 17](https://developer.android.com/about/versions/17/behavior-changes-17)). |
| macOS | A menu bar app always runs. State restoration is iOS-only and not needed. During sessions, hold `ProcessInfo.beginActivity(.userInitiatedAllowingIdleSystemSleep)` so App Nap doesn't throttle the link. BLE stops when the Mac sleeps. | `NSBluetoothAlwaysUsageDescription` triggers the TCC prompt. No sandbox entitlement is needed: `AirBridge.entitlements` has no app-sandbox. |
| Windows | Tray app, always running. | `bluetooth` (and `wiFiControl` for §3) DeviceCapabilities in `Package.appxmanifest`. |

### 2.6 What works over BLE alone

| Feature | BLE? | Budget |
|---|---|---|
| Clipboard text (≤512 KiB) | ✅ | Typical clips are under 1 s. A 512 KiB maximum takes ~5 s on CoC and ~15 s on GATT. |
| Universal control / trackpad (`ptr_*`, `key`, `ctl_*`) | ✅ | One encrypted `ptr_move` is ~60 B, so 67/s ≈ 4 KB/s. Latency adds one connection interval (11.25–15 ms) plus air time, about 15–30 ms. **Rule: coalesce `ptr_move` deltas to one per connection interval when `link.type` is BLE.** |
| Small files | ✅ ≤ ~2 MiB | 2 MiB ≈ 20 s on CoC. Show the ETA. |
| Big files, screen mirror (§8.7) | ❌ | Upgrade to a hotspot (§3) or refuse. |

---

## 3. Hotspot bootstrap for bandwidth (no shared router)

The BLE session (§2) is already authenticated and encrypted. It carries the AP credentials, so they never travel
in the clear.

### 3.1 Options

| Option | Who is AP | API | Internet during transfer | Verdict |
|---|---|---|---|---|
| **(a) Android LocalOnlyHotspot** | Phone | `startLocalOnlyHotspot` (API 26). The reservation's `SoftApConfiguration` exposes SSID and passphrase (API 30+). **API 36** adds `startLocalOnlyHotspotWithConfiguration` + public `SoftApConfiguration.Builder.setChannels` (band), with `setWifiSsid`/`setPassphrase` from **36.1**. Permissions: `CHANGE_WIFI_STATE` + `NEARBY_WIFI_DEVICES`. Fails with `ERROR_INCOMPATIBLE_MODE` while tethering. No internet. ([WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager), [Builder](https://developer.android.com/reference/android/net/wifi/SoftApConfiguration.Builder)) | Phone: keeps its Wi-Fi only if `isStaApConcurrencySupported()`; otherwise falls back to cellular. **Mac: loses Wi-Fi.** | **Primary for Mac.** Request 5 GHz on API 36+. Use random credentials, which are readable from the reservation. |
| **(b) Android Wi-Fi Direct autonomous GO + legacy client** | Phone | `WifiP2pConfig.Builder` (API 29): `DIRECT-xy…` SSID, passphrase, `setGroupOperatingBand(5GHZ)` → `createGroup`. | Phone usually keeps Wi-Fi, because the GO runs concurrently with STA. Mac loses Wi-Fi. | Fallback. The GO is often pinned to the STA's channel: if the phone is on 2.4 GHz home Wi-Fi, so is the GO. Some OEMs tear the group down. |
| **(c) USB tethering (NCM)** | Phone (wired) | The user toggles it in Settings; `TetheringManager.startTethering` is a SystemApi. macOS has native CDC-NCM. **RNDIS is unsupported on Apple Silicon** (HoRNDIS was a kext). Pixel 6+ uses NCM; other OEMs vary ([zenn](https://zenn.dev/cyberrex/articles/55cd91137c3a5f), [Nothing forum](https://nothing.community/d/57757-migrate-usb-tethering-from-rndis-to-cdc-ncm-for-native-macos-windows-support)). | The Mac may route internet through the phone (metered). | Manual fallback with a docs tip. Windows supports RNDIS and NCM. |
| **(d) Windows hosts a legacy AP** | **PC** | `WiFiDirectAdvertisementPublisher` + `LegacySettings` (SSID/passphrase), usually concurrent with the PC's own Wi-Fi ([docs](https://learn.microsoft.com/en-us/uwp/api/windows.devices.wifidirect.wifidirectadvertisement.legacysettings)). Alternative: Mobile Hotspot via `NetworkOperatorTetheringManager` (`wiFiControl`, needs an internet profile, and **cannot run alongside a legacy Wi-Fi Direct GO**). The phone joins with `WifiNetworkSpecifier`. | **Both keep internet.** The PC keeps its STA. The phone keeps cellular, and keeps its Wi-Fi too if `isStaConcurrencyForLocalOnlyConnectionsSupported()`. | **Primary for Windows.** Use a stable SSID/PSK per pairing so Android's per-app approval dialog appears only once. |
| Mac hosts an AP | — | Internet Sharing has no API and needs root. | — | ❌ |

### 3.2 Mac joining the phone's AP (CoreWLAN)

1. **Join.** `CWWiFiClient.shared().interface()` → `scanForNetworks(withName: ssid)` → `associate(to: net, password: psk)`.
   - The app is non-sandboxed, and Quinn (DTS) says no restricted entitlements exist in the CoreWLAN space.
   - Since macOS 14, scan results have `ssid`/`bssid` = nil unless the app holds **Location** authorization.
     Associating needs the `CWNetwork` from that scan. On 15.1 this broke even *with* authorization in a CLI tool,
     but worked in a proper GUI app ([thread 769950](https://developer.apple.com/forums/thread/769950),
     [732431](https://developer.apple.com/forums/thread/732431)).
   - So: add `NSLocationUsageDescription` and request `CLLocationManager` authorization once, in context ("needed to
     join your phone's Wi-Fi").
   - Admin rights are only needed if "Require administrator authorization to change networks" is on (it is off by
     default).
   - macOS 26 removed the `airport` CLI. Treat `networksetup -setairportnetwork` as an unverified fallback (S5).
2. **Restore.** Before joining, record whether Wi-Fi was associated. Reading the SSID also needs Location. After
   `link_done`, call `disassociate()` and let macOS auto-join its preferred network. If nothing is associated within
   10 s, power-cycle with `setPower(false)` then `setPower(true)`. Measure this in S6.
3. **Cap and recover.** Limit the hotspot stay to `ttl` (default 120 s, extended while bytes flow). If the app
   crashes, a launch-time check restores Wi-Fi.
4. **Escape hatch.** If association fails, show "Join `DIRECT-AB-…` from the Wi-Fi menu" with the passphrase and a
   copy button.

### 3.3 Sockets on the new link

- **Phone is the AP (Mac case).**
  - The Mac's existing `NWListener` on 47632 already listens on the new interface; the Local Network key is already
    in `Info.plist`.
  - The Mac sends `link_ready{addr,port}`, and the phone dials `ws://addr:port/v2`. That is the existing `WsClient`,
    with no new transport.
- **Phone is the client (Windows case).**
  - Bind to the `Network` returned by `ConnectivityManager.requestNetwork(WifiNetworkSpecifier)`.
  - Use OkHttp `.socketFactory(network.socketFactory)`. Otherwise traffic goes out over cellular.

### 3.4 Throughput

| Link | Expected | Limiter |
|---|---|---|
| Phone soft AP 5 GHz, 80 MHz, Wi-Fi 6 → MacBook | **20–60 MB/s** | Phone SoC/thermal, MediaStore writes, single TCP stream |
| Same on 2.4 GHz (GO pinned to the home channel) | 3–8 MB/s | Channel width |
| Windows legacy AP (shares the PC's STA channel) | 10–40 MB/s | Adapter time-sharing |
| USB NCM | 20–60 MB/s | USB 2 vs 3 port |

These are estimates; S7 measures them with iperf3.

### 3.5 UX flow (AirDrop-like)

1. **Share sheet on the phone** → AirBridge → "Nearby: **Shazan's MacBook** · Bluetooth" (the tag resolved).
2. **Tap to send.** Files ≤ 2 MiB go over BLE immediately. Larger files continue below.
3. **Phone** starts LOHS and sends `link_offer` over BLE.
4. **Mac prompt** (first time only): "Receive *video.mp4* (700 MB) from Pixel 9 — your Mac will leave 'HomeWiFi'
   for about 1 min." The user picks Accept or Always for this phone.
5. **Mac** joins the AP and replies `link_ready`.
6. **Phone** dials over the hotspot. The handshake replaces the BLE session (PROTOCOL §3.4 `bye replaced`).
7. **Transfer** runs with `xfer_*`.
8. **`link_done`.** The phone closes LOHS and the Mac rejoins HomeWiFi.
9. **Fallback.** The phone's WS session drops, its reconnect logic sees the network change (PROTOCOL §4.1), and the best
   remaining candidate (BLE, or LAN) redials.

Mac → phone works the same way, except the Mac starts it with `link_request`.

---

## 4. Design for AirBridge

### 4.1 Layer fit

| Layer | New pieces |
|---|---|
| Discovery | `BleDiscovery` emits `Candidate(id?, name?, plat?, Endpoint.Ble(device, psm?), source="ble", rssi)` into the CandidateTable (ARCHITECTURE §4). `id` comes from the tag resolver; an unresolved pairable advert gives `id = null`. |
| Transport | `FramedStreamLink(InputStream, OutputStream)` is shared with Nearby STREAM and with future TCP. On top of it: `BleL2capLink` (CoC sockets), `BleGattLink` (RX/TX fragmentation into the same framer), and the hotspot path, which reuses `WsLink`. |
| Selection | `score = typePrior + 10·log2(estMbps+1) − rtt/5 − disruptive·50`, with priors LAN 100 > HOTSPOT 60 > BLE_L2CAP 20 > BLE_GATT 10 (ARCHITECTURE §5). Hotspot is *disruptive* for Mac (it needs consent) and *not disruptive* for Windows (d). |
| Upgrade trigger | `xfer_offer.total` > 2 MiB on a BLE link, `mirror_*`, or the user tapping "Send fast". Hold the `xfer_offer` until the upgraded session is `ready`, so nothing migrates mid-transfer. |
| Migration | Make-before-break re-handshake (ARCHITECTURE §6) with no wire change. Phase 4 may adopt `resume`/`xfer_resume`. |
| Services | `link.metrics` gates services: BLE allows `clip`, control, and `xfer` ≤ 2 MiB; the trackpad coalesces `ptr_move`. |

### 4.2 Message additions (PROPOSED, behind caps `ble` and `link`, all inside §3.6 envelopes)

```json
{"t":"adv_key","k":"<b64 32 bytes>"}                                   // after ready; peer stores it for tag resolution
{"t":"link_request","why":"xfer|mirror|user","bytes":734003200}        // "please host an AP" (Mac → phone)
{"t":"link_offer","lid":1,"type":"hotspot","ssid":"AndroidShare_4821","psk":"…","sec":"wpa2|wpa3",
 "band":"2.4|5|6","addr":"192.168.49.1","port":47632,"ttl":120}        // AP owner → joiner; addr/port only when the AP owner is the WS host (Windows)
{"t":"link_ready","lid":1,"addr":"192.168.43.17","port":47632}         // joiner joined; addr/port when the joiner is the WS host (Mac)
{"t":"link_fail","lid":1,"reason":"user|join_failed|timeout|unsupported|busy"}
{"t":"link_done","lid":1}                                              // either side; tear down AP, restore Wi-Fi
```

Rules:
- Any side may send `link_fail` before `link_ready`.
- The `ttl` expiry triggers teardown even if no message arrives.
- `type` leaves room for a future `"wifi_aware"` and `"usb"`.

### 4.3 Per-platform file plan and effort

| Platform | Files (network half) | Days |
|---|---|---|
| **Android** `net/` | `ble/BleIds.kt` (UUIDs, tag HMAC, resolver) · `ble/BleAdvertiser.kt` · `ble/BleScanner.kt` (masked filters, PendingIntent) · `ble/BleCentral.kt` (GATT connect, MTU/PHY/priority, PSM read) · `ble/BleL2capLink.kt` · `ble/BleGattLink.kt` · `ble/BleGattServer.kt` + `ble/BleL2capServer.kt` (for Windows and Mac-initiated dials) · `link/FramedStreamLink.kt` (extracted from `NearbyTransport`) · `link/LinkSelector.kt` + CandidateTable in `Discovery.kt`/`Hub.kt` · `hotspot/HotspotHost.kt` (LOHS → P2P GO fallback) · `hotspot/LocalNetJoin.kt` (WifiNetworkSpecifier, for Windows) · `Protocol.kt`/`Session.kt` (`adv_key`, `link_*`) · UI: Nearby rows, "Send fast" | 16–20 |
| **macOS** `Net/` | `Ble/BleIds.swift` · `Ble/BlePeripheral.swift` (advertise, GATT, `publishL2CAPChannel`) · `Ble/BleScanner.swift` (presence + optional dial) · `Ble/L2capLink.swift` · `Ble/GattLink.swift` · `FramedStreamLink.swift` · `Hotspot/WifiJoiner.swift` (CoreWLAN, Location auth, restore, crash recovery) · `LinkSelector.swift` + `PeerManager.swift` hooks · `Info.plist` (`NSBluetoothAlwaysUsageDescription`, `NSLocationUsageDescription`) · menu: Nearby section, consent sheet | 12–15 |
| **Windows** `Net/` | `Ble/BleWatcher.cs` · `Ble/BleCentral.cs` (GattSession, ThroughputOptimized) · `Ble/GattLink.cs` · `FramedStreamLink.cs` · `Hotspot/LegacyApHost.cs` (Wi-Fi Direct legacy, `TetheringHost.cs` fallback) · `LinkSelector.cs` · `Package.appxmanifest` (`bluetooth`, `wiFiControl`) | 9–12 |
| Spec | PROTOCOL §2.5 "BLE advert" + §10 BLE/GATT framing + the `link_*` messages | 1–2 |

### 4.4 Phases

| Phase | Scope | Exit criteria | Effort |
|---|---|---|---|
| **0. Spikes** | S1, S2, S4, S5, S6 (§5) | Numbers recorded; go/no-go on CoC and CoreWLAN | 2–3 d |
| **1. BLE discovery + control/clipboard (Android↔Mac)** | Adverts and tag, Mac peripheral + L2CAP, Android central, GATT fallback, `FramedStreamLink`, BLE candidate in the selector, `confirm` pairing over BLE, ptr coalescing, ≤2 MiB files | With Wi-Fi off on both devices: pairing, clipboard both ways, trackpad and universal control usable (p95 RTT < 60 ms), 1 MiB file in < 20 s. LAN wins automatically when available. | 3–4 wk |
| **2. Hotspot bootstrap for files (Android↔Mac)** | `link_*`, `HotspotHost`, `WifiJoiner`, consent UX, restore, `xfer` hold-until-upgrade | 1 GB at ≥ 20 MB/s on 5 GHz; the Mac is back on its Wi-Fi within 15 s in 10/10 runs | 2–3 wk |
| **3. Windows parity** | Windows central + Android GATT server; Windows legacy AP + Android `WifiNetworkSpecifier` | Same as Phase 1 (GATT figures) and Phase 2, with both devices keeping internet | 2–3 wk |
| 4. Optional | `resume`/`xfer_resume` live migration; USB NCM auto-detect; Wi-Fi Aware if S10 turns positive | — | — |

---

## 5. Risks and unknowns: 30-minute device spikes

| # | Unknown | Recipe (≈30 min each) | Pass |
|---|---|---|---|
| S1 | CoC throughput, M-series Mac on macOS 26/27 as peripheral ← Android central | Mac: a Swift playground app that publishes an L2CAP channel and puts the PSM in the GATT characteristic. Android: a debug activity that scans, connects, applies `requestConnectionPriority(HIGH)` + 2M PHY, opens `createInsecureL2capChannel` and streams 10 MB. Log KB/s every second, then repeat with the Android side as receiver. | ≥ 80 KB/s each way |
| S2 | GATT fallback throughput (same rig) | Same rig, using RX write-without-response with MTU 517, then TX notify. | ≥ 25 KB/s |
| S3 | OEM CoC bugs | Run the S1 APK on Pixel (16/17), Samsung (One UI 8), Xiaomi/HyperOS and a MediaTek device. Do 20 connect/disconnect cycles. Check that `read()` returns -1 on close and the GATT connection comes back. | ≥ 19/20 connects on each phone; failures logged and deny-listed by model |
| S4 | Rotating-UUID adverts, discovery latency | Mac advertises a UUID that matches no GATT service; Android runs a masked `ScanFilter` in the FGS with the screen off. Time to first sighting, ×10. Then reverse: Android advertises and the Mac scans. | Mac allows the UUID; p90 detection < 5 s (screen off < 30 s) |
| S5 | CoreWLAN join on macOS 26 and 27 | Join a phone LOHS using credentials typed into a test menu item, with Location (a) denied and (b) granted. Record `scanForNetworks(withName:)` results, `associate` errors and time to DHCP. Also try `networksetup -setairportnetwork`. | Works with Location granted, without an admin prompt |
| S6 | Mac auto-rejoin reliability | After S5, run 10 cycles of `disassociate()` and time until the home SSID has internet. Then 10 cycles of `setPower` off/on. | 10/10 within 15 s |
| S7 | LOHS band, concurrency and speed | On each test phone: start LOHS while on home Wi-Fi. Log `getBand`/frequency and whether the STA dropped. Run `iperf3 -c` from the Mac for 30 s. Repeat with a P2P GO configured `setGroupOperatingBand(5GHZ)`. | 5 GHz achievable; ≥ 20 MB/s |
| S8 | Windows options | (a) GATT throughput with ThroughputOptimized on Intel and Realtek adapters. (b) Legacy AP concurrent with the PC's Wi-Fi; phone joins with `WifiNetworkSpecifier` and the dialog is remembered on the second run. (c) RFCOMM to an unpaired Android. | (a) ≥ 20 KB/s; (b) the PC keeps internet |
| S9 | App Nap and sleep on macOS | Menu bar app with an idle BLE session: background it for 30 min with the display off, then measure trackpad latency on wake. | No session drop while awake |
| S10 | Wi-Fi Aware on a Mac (future door) | Mac Catalyst stub that prints `WACapabilities.supportedFeatures` on macOS 26 and 27, M-series. | Empty = closed (expected). Non-empty = re-open Android↔Mac P2P research |
| S11 | USB NCM | Enable USB tethering on Pixel and Samsung, then run `ifconfig` and `networksetup -listallhardwareports` on the Mac and check the service order. | NCM interface appears; the Mac does not silently route internet via the phone |

**Other risks**
- **The Mac losing internet** mid-transfer and users blaming the app. Mitigations: explicit consent, TTL, restore,
  and a crash-recovery check.
- **Privacy of adverts.** The 15-minute tag epoch and the BLE address rotation are not aligned, so an advert stays
  briefly linkable. The tag, flags and the prefix all reveal that "an AirBridge device" is present.
- **Battery** from continuous scanning on Android. Mitigations: low-power mode while idle, and BLE closed when a LAN
  session is ready.
- **API 36 / 36.1 gating.** LOHS band selection (`setChannels`) needs API 36, and custom SSID/passphrase need 36.1.
  Older phones get random credentials (fine, because they travel over BLE) and whatever band the OEM picks.
