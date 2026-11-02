# Proximity Connectivity Research: "Nearby, without a shared LAN"

Status: research, verified against platform docs on **2026-11-02**. Shipping OSes at that date are Android 16 and 17
(API 36/37), iOS/iPadOS 26 → 27, macOS 26 → 27, and Windows 11 24H2/25H2. "Third-party" means what a normal
signed app can do with public APIs, without private entitlements, root, or jailbreak.
Architecture context: [ARCHITECTURE.md](ARCHITECTURE.md) §3–§5.

## 0. Summary: what the docs show

1. **There is no cross-vendor P2P Wi-Fi that works everywhere.** Every OS has a different one:
   - Android: Wi-Fi Aware and Wi-Fi Direct.
   - Apple: AWDL, which only Apple devices can join; Wi-Fi Aware on **iOS/iPadOS only**.
   - Windows: Wi-Fi Direct.
   - macOS: **no Wi-Fi Direct and no Wi-Fi Aware API at all.**

   AirDrop-like Android↔Mac is **not achievable** for a third-party app without making the Mac leave its Wi-Fi
   network. Google's Quick Share↔AirDrop interop
   ([9to5Google, Nov 2025](https://9to5google.com/2025/11/20/android-quick-share-airdrop-pixel-10/)) is Google's
   own Pixel 10 system feature and is not an API.
2. **iOS Wi-Fi Aware is real but narrow:**
   - availability is iOS/iPadOS 26+ on iPhone 12 or later ([docs](https://developer.apple.com/documentation/wifiaware));
   - it needs the `com.apple.developer.wifi-aware` entitlement and services declared in Info.plist
     ([Adopting](https://developer.apple.com/documentation/wifiaware/adopting-wi-fi-aware));
   - **pairing is mandatory**, done through DeviceDiscoveryUI (Apple↔Apple) or AccessorySetupKit (accessories)
     ([DTS](https://developer.apple.com/forums/thread/791628)).

   Android↔iPhone pairing has been **reported failing** by several developers through 2025–26: missing discovery
   attributes, PASN status 15, and Qualcomm PIN-length limits
   ([thread 801280](https://developer.apple.com/forums/thread/801280),
   [797170](https://developer.apple.com/forums/thread/797170)). Apple's DTS position is that a non-compliant
   third-party device is the vendor's problem. Treat Android↔iOS Wi-Fi Aware as a **research spike, not a roadmap item.**
3. **BLE is fine for discovery and a small control channel, but not for files.** Expect tens to low hundreds of KB/s.
   iOS background advertising moves service UUIDs into Apple's "overflow area", which only iOS scanners see
   ([CBPeripheralManager](https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/startadvertising(_:))).
   A backgrounded iPhone is therefore effectively invisible to Android, Windows, and Linux.
4. **The current Android↔Android path leaves bandwidth on the table.** The code uses Nearby `Strategy.P2P_CLUSTER`,
   which Google documents as "lower bandwidth". `P2P_STAR` and `P2P_POINT_TO_POINT` give higher or highest
   throughput ([strategies](https://developers.google.com/nearby/connections/strategies)). This is a one-line change.
5. **New permission walls are coming on LAN itself:**
   - Android 17 (target SDK 37) blocks local-network access by default unless the app holds `ACCESS_LOCAL_NETWORK`
     or uses the NSD picker ([docs](https://developer.android.com/privacy-and-security/local-network-permission)).
   - macOS 15+ enforces Local Network privacy for apps ([TN3179](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)).
     The macOS `Info.plist` currently lacks `NSLocalNetworkUsageDescription` and `NSBonjourServices`.

## 1. Technology × platform (third-party access)

| Technology | Android | iOS / iPadOS | macOS | Windows 11 | Linux |
|---|---|---|---|---|---|
| **BLE central + peripheral** | ✅ (peripheral needs `isMultipleAdvertisementSupported`) | ✅, but background: no local name, UUIDs in overflow area | ✅ CoreBluetooth | ✅ central; peripheral via `GattServiceProvider` if adapter supports it ([docs](https://learn.microsoft.com/en-us/windows/apps/develop/devices-sensors/gatt-server)) | ✅ BlueZ D-Bus |
| **BLE L2CAP CoC** (stream, ~2–5× GATT) | ✅ API 29+ `createL2capChannel` | ✅ `CBL2CAPChannel` | ✅ | ⚠️ no public WinRT LE-CoC API found; use GATT | ✅ L2CAP sockets |
| **Wi-Fi Aware (NAN)** | ✅ API 26+, **hardware-dependent** (`FEATURE_WIFI_AWARE`); may be unavailable while hotspot or Wi-Fi Direct is active; pairing API 34+, app-managed bootstrap API 37 ([AwarePairingConfig](https://developer.android.com/reference/android/net/wifi/aware/AwarePairingConfig)) | ✅ iOS 26+, iPhone 12+, entitlement plus mandatory pairing; Android interop unproven | ❌ framework not available | ❌ no public API | ❌ no usable stack |
| **Wi-Fi Direct** | ✅ `WifiP2pManager`; GO with chosen `DIRECT-xy…` SSID and passphrase (API 29) so legacy clients can join ([Builder](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig.Builder)); BLE-assisted R2 pairing API 36+ | ❌ | ❌ no public API ([DTS: Apple P2P ≠ Wi-Fi Direct](https://developer.apple.com/forums/thread/12885?page=2)) | ✅ `Windows.Devices.WiFiDirect`; legacy-AP mode via `WiFiDirectAdvertisement.LegacySettings` ([docs](https://learn.microsoft.com/en-us/uwp/api/windows.devices.wifidirect.wifidirectadvertisement.legacysettings)) | ✅ wpa_supplicant / NetworkManager P2P |
| **Host a soft-AP** | ✅ `LocalOnlyHotspot` (random creds; **custom SSID/pass public in API 36** via `startLocalOnlyHotspotWithConfiguration`, [API diff](https://developer.android.com/sdk/api_diff/36/changes/android.net.wifi.WifiManager)) | ❌ | ❌ (Internet Sharing is manual, no API) | ✅ Wi-Fi Direct legacy AP (usually concurrent with STA, adapter-dependent) | ✅ `nmcli … hotspot` |
| **Join a given SSID programmatically** | ✅ `WifiNetworkSpecifier`: system dialog, approval remembered per app+AP, local-only with no internet ([docs](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap)) | ✅ `NEHotspotConfiguration` (user prompt) | ✅ CoreWLAN `associate(to:password:)`; may require admin, Wi-Fi info needs Location auth ([docs](https://developer.apple.com/documentation/corewlan/cwinterface/associate(to:password:))) | ✅ `WiFiAdapter.ConnectAsync` / WLAN API | ✅ nmcli |
| **Apple P2P Wi-Fi (AWDL)** | ❌ undocumented protocol | ✅ `includePeerToPeer` | ✅ | ❌ | ❌ |
| **Nearby Connections** | ✅ Play services | ⚠️ Swift package (BLE, LAN, Wi-Fi hotspot upgrade) ([docs](https://developers.google.com/nearby/connections/swift/get-started)); repo says "not an officially supported Google product" | ⚠️ same package | ❌ no SDK (Google's C++ lib powers Quick Share for PC, unsupported) | ❌ |
| **USB** | USB tethering (RNDIS/NCM) creates an IP link | via Apple's usbmux only | NCM tethering only | RNDIS/NCM ✅ | ✅ |

Multipeer Connectivity is Apple-only and "effectively deprecated"
([DTS](https://developer.apple.com/forums/thread/769043)). Use Network.framework with `includePeerToPeer` instead.

### Throughput and latency (order of magnitude; measure before trusting)

| Link | Typical sustained | Notes |
|---|---|---|
| BLE GATT (2M PHY, DLE, MTU 247–517) | 30–150 KB/s | Connection interval ≥ 7.5 ms (iOS ≥ 15 ms). Fine for clipboard and `ptr_move`. |
| BLE L2CAP CoC | 100–250 KB/s | Fits clipboard and small files. |
| Wi-Fi Aware | ~20 MB/s observed non-iOS→iOS ([818708](https://developer.apple.com/forums/thread/818708)); Android↔Android commonly claimed 100–250 Mbit/s | Throughput drops when the radio also serves infrastructure Wi-Fi. |
| Wi-Fi Direct / hotspot, 5 GHz | 10–40 MB/s | GO-side radio sharing halves throughput if on a different channel. |
| Same LAN (Wi-Fi 5/6 via AP) | 5–40 MB/s | Each frame crosses the air twice. Ethernet↔Wi-Fi is better. |
| Nearby P2P_CLUSTER | BT/BLE-class unless upgraded | Upgrades depend on strategy and `ConnectionType` ([Builder](https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/ConnectionOptions.Builder)). |

## 2. Background constraints and permissions

| Platform | Discovery in background | Prompts / permissions |
|---|---|---|
| Android | BLE scanning needs `ScanFilter`s; unfiltered scans stop with the screen off. Keep a `connectedDevice` FGS (today's `dataSync` is time-limited). Aware sessions survive while the FGS lives. | `BLUETOOTH_SCAN/ADVERTISE/CONNECT` (use `neverForLocation`), `NEARBY_WIFI_DEVICES` (13+), `ACCESS_LOCAL_NETWORK` (target 37), a `WifiNetworkSpecifier` dialog per new AP |
| iOS | No persistent sockets. BLE background scanning requires explicit service UUIDs. Background advertising only in the overflow area. Wi-Fi Aware connections to *paired* devices may run in background "with runtime from existing mechanisms" ([docs](https://developer.apple.com/documentation/wifiaware)). | Local Network, Bluetooth, the pairing UI, and a paste prompt on programmatic reads |
| macOS | None, menu-bar app always runs | Local Network (15+), Bluetooth, Location if reading SSIDs or using CoreWLAN, admin password possibly required to switch networks |
| Windows | None, tray app | Location/radio consent is mostly silent for desktop apps; the firewall prompt on first listen |
| Linux | None | BlueZ policy (usually none); NetworkManager polkit for hotspot/P2P |

## 3. Pair-by-pair matrix (no shared LAN)

| Pair | Feasible transports, in recommended order | Expected throughput | User friction | Verdict |
|---|---|---|---|---|
| **Android↔Android** | Nearby STAR/P2P with Wi-Fi upgrade → raw Wi-Fi Aware (both sides need `FEATURE_WIFI_AWARE`) → Wi-Fi Direct | 10–30 MB/s | Low, one-time permissions. Both phones keep their Wi-Fi with NON_DISRUPTIVE. | ✅ **Best case.** Already partly built. |
| **Android↔Mac** | (a) BLE control channel; (b) Mac joins phone's LOHS or Wi-Fi Direct group via CoreWLAN (**Mac loses its Wi-Fi/internet** during transfer); (c) USB tethering (NCM); (d) Mac on Ethernet plus Wi-Fi to the phone | (a) ~100 KB/s; (b) 10–30 MB/s | (b) needs an explicit opt-in, possibly an admin password, and Location permission | ⚠️ Control works without a LAN. Bulk transfers are only possible "disruptively". |
| **Android↔Windows** | (a) BLE GATT control; (b) **Windows hosts a legacy AP** (Wi-Fi Direct API, usually concurrent with its own Wi-Fi), phone joins via `WifiNetworkSpecifier` (phone may drop its Wi-Fi and fall back to cellular unless it has STA+STA concurrency); (c) phone hosts LOHS or a group, PC joins (PC loses Wi-Fi unless it has a second adapter); (d) true Wi-Fi Direct `WiFiDirectDevice` (interop flaky); (e) USB tethering | (b,c) 10–30 MB/s | (b) one dialog per AP on the phone, remembered afterwards | ✅ **(b) is the best desktop option**: the PC stays online. |
| **Mac↔Windows** | BLE (Mac central ↔ Windows GATT server); Mac joins the Windows legacy AP via CoreWLAN (Mac loses Wi-Fi); Ethernet cable / Thunderbolt bridge | BLE ~100 KB/s; AP 10–30 MB/s | Moderate | ⚠️ Desktops are usually on a LAN anyway. Low priority. |
| **iOS↔Mac** | **AWDL** via Network.framework `includePeerToPeer=true` on both NWListener and NWBrowser. Bonjour works over it, needs no LAN, and both stay on Wi-Fi. | 20–50 MB/s | Local Network prompt only | ✅ **Easy win.** iOS foreground only. |
| **iOS↔Android** | BLE (foreground iOS only); iPhone joins Android LOHS or group via `NEHotspotConfiguration` (prompt each time; this is what Nearby-Swift does); Wi-Fi Aware (spike, interop reported broken) | BLE ~50–100 KB/s; hotspot 10–30 MB/s | High | ⚠️ Foreground, user-initiated only. |
| **iOS↔Windows / Linux** | Same LAN; iPhone joins a Windows/Linux AP | — | High | ❌ beyond LAN |
| **Linux↔Android** | BLE (BlueZ); Linux joins an Android group as a real P2P client (NetworkManager), keeping its Wi-Fi if the chipset supports P2P concurrency; Linux hosts AP, phone joins | 10–30 MB/s | Moderate (polkit) | ✅ Linux is the most capable desktop here. |
| **Linux↔Mac/Windows** | LAN; Linux AP | — | — | Low priority |

## 4. Recommended phased plan

**Phase 0: quick wins (≈2–3 days).** These need no new radios.
- Switch Nearby to `P2P_STAR` (phone as hub) or `P2P_POINT_TO_POINT` for transfers.
- Pass `id|plat|name` in `endpointInfo`.
- Run the v2 handshake over Nearby as PROTOCOL §10 already says.
- Add `NSLocalNetworkUsageDescription` and `NSBonjourServices=_airbridge._tcp` to the macOS app.
- Plan the `ACCESS_LOCAL_NETWORK` request before raising `targetSdk` to 37.

**Phase 1: Android↔Android P2P Wi-Fi behind the Nearby abstraction (≈2–3 weeks).**
- `NearbyDiscovery` and `NearbyLink` become ordinary `DiscoverySource` and `Link` implementations.
- Measure Nearby with STAR/P2P and `ConnectionType.BALANCED` versus `DISRUPTIVE` (the latter only on an explicit
  "send fast" action).
- Only if Nearby underperforms, add `AwareDiscovery` plus `FramedStreamLink` over a `WifiAwareNetworkSpecifier`
  socket. Use a PSK derived from the pairing key `K`, so the NDP is encrypted. Fall back to Nearby when either side
  lacks Aware or `isAvailable()` is false, for example while tethering.

**Phase 2: BLE discovery, BLE control link, LAN handoff (≈4–6 weeks across 3 desktop OSes plus Android).**
- Every device advertises a 128-bit AirBridge service UUID with an 8-byte rotating HMAC tag (ARCHITECTURE §4).
  Desktops scan constantly. Android scans with filters inside the FGS.
- On a match:
  1. Open GATT (or L2CAP where available) and run hello/auth.
  2. Exchange current IPs and ports over the authenticated channel.
  3. Try those addresses directly. This catches "same Wi-Fi, mDNS blocked" and multi-subnet homes.
  4. If that fails, keep BLE as a control-only link (clipboard, trackpad).
- This alone delivers "walk up and it connects" for small payloads.

**Phase 3: hotspot-assisted desktop links (≈3–4 weeks).** This applies only to user-initiated large transfers. The
authenticated BLE session negotiates (later via `link_offer`, ARCHITECTURE §7):
- **Windows:** the PC hosts a Wi-Fi Direct legacy AP with random credentials, and the phone joins via
  `WifiNetworkSpecifier`.
- **Mac/Linux:** the phone hosts LOHS (API 36 custom config) or a Wi-Fi Direct GO with known credentials. The Mac
  joins via CoreWLAN after a confirmation dialog ("Mac will leave 'HomeWiFi' for ~1 min"), then re-associates
  afterwards.

**Phase 4: optional.**
- iOS↔Mac over AWDL. The Mac `NWListener` needs `includePeerToPeer = true`, which is about 1 day once an iOS client
  exists.
- A Wi-Fi Aware iOS↔Android spike on one known-good Android device. Time-box it to 1 week.

## 5. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| OEM fragmentation of Wi-Fi Aware and Wi-Fi Direct (missing features, concurrency limits, firmware bugs) | Phase 1 works on some phone pairs only | Gate at runtime (`hasSystemFeature`, `isAvailable`, `getAvailableAwareResources`); keep Nearby as the fallback |
| Joining a hotspot kills the Mac's or phone's internet, and users blame the app | Support pain | Never automatic. Explicit dialog, auto-revert, cap the duration. |
| CoreWLAN association needs Location and possibly an admin password; behaviour varies across macOS versions | Phase 3 on Mac may be clunky | Fall back to instructions: "Join DIRECT-AB-xxxx manually" plus a QR containing credentials |
| BLE adverts enable tracking | Privacy | Rotating HMAC tag; never broadcast `id` or `name` |
| Cleartext v2 payloads on open or hotspot links | Clipboard leakage | Add TLS-PSK/Noise (ARCHITECTURE §7) before Phase 3 |
| Apple Wi-Fi Aware bugs (e.g. devices reporting "not capable" on 26.5, fixed 26.6 beta) ([828136](https://developer.apple.com/forums/thread/828136)) | iOS spike instability | Keep it a spike |
| FGS type: today's `dataSync` is capped at 6 h/24 h and can't start from `BOOT_COMPLETED` (target ≥ 35) | The always-on service dies silently | Move to `connectedDevice` now (ARCHITECTURE §2); declare it in Play Console |
| Battery (Aware clusters, AWDL, BLE scanning) | Users disable the app | Duty-cycle scans; close P2P sessions after 60 s idle |
