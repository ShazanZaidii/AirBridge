# Android ↔ Windows Direct Wi-Fi (no shared router): Research and Build Spec

Status: **research + design (informative)**, 2026-11-02. [PROTOCOL.md](PROTOCOL.md) stays normative. This file
refines [PROXIMITY_DESIGN.md](PROXIMITY_DESIGN.md) §0, §3.1(d), §3.3, §3.4 and §4.2 for the Android ↔ Windows pair,
and corrects them where noted. Labels: **[C]** confirmed (official docs or source code read), **[L]** likely (several
consistent field reports), **[S]** speculation (needs a device spike).

**Bottom line**
- It works, and the APIs are public. But it is **not** "start the publisher and the phone joins." Google ships the
  same design in Quick Share for Windows and needed workarounds for it. Microsoft's own docs describe a feature with
  no band control and a 2-minute client-drop bug.
- **Primary (T1):** Windows hosts a Wi-Fi Direct *legacy-mode* group owner (GO). The phone joins it as an ordinary
  Wi-Fi client with `WifiNetworkSpecifier`, using an SSID/passphrase derived from `K` that **stays fixed per
  pairing**. The PC keeps its Wi-Fi. There are no Windows prompts, and one Android dialog per pairing on Android 13+.
- **Fallback / "fast mode" (T2):** the phone is an autonomous Wi-Fi Direct GO, and Windows joins its `DIRECT-…`
  SSID as a plain Wi-Fi client through the Native Wi-Fi API. This one is fast, but **the PC leaves its own Wi-Fi**
  for the duration.
- **Throughput:** T1 inherits the PC's current channel. Expect **~8–25 MB/s when the PC sits on 5 GHz** and
  **3–8 MB/s on 2.4 GHz or with no Wi-Fi uplink** [S]. T2 reaches the **25–40 MB/s, peak ~100 MB/s** that Quick Share
  users see [C/L]. The owner's ≥ 20 MB/s target is reliable only with T2, or with T1 when the PC is on 5 GHz.
- **True P2P negotiation (T3): don't.** Google compiled out the Windows-GO role after measuring a 20–60 %
  connection failure rate.

---

## 1. Topologies

### 1.1 Evaluation

| | **T1: Windows legacy GO, phone joins** | **T2: Android GO, Windows joins** | **T3: P2P ↔ P2P (GO negotiation / WPS)** |
|---|---|---|---|
| Windows API | `WiFiDirectAdvertisementPublisher`, `Advertisement.IsAutonomousGroupOwnerEnabled = true`, `Advertisement.LegacySettings {IsEnabled, Ssid, Passphrase}`, plus a **mandatory** `WiFiDirectConnectionListener` (§1.2) | (a) Legacy client: `WlanSetProfile` + `WlanConnect` (wlanapi), the way Google does it ([wifi_hotspot_native.cc](https://github.com/google/nearby/tree/main/internal/platform/implementation/windows)) [C]. (b) P2P client: `DeviceInformationCustomPairing.PairAsync(…, WiFiDirectConnectionParameters)` + `WiFiDirectDevice.FromIdAsync` | `WiFiDirectDevice.FromIdAsync`, `WiFiDirectConnectionParameters.GroupOwnerIntent`, `PreferenceOrderedConfigurationMethods` |
| Android API | `WifiNetworkSpecifier` (API 29) → `ConnectivityManager.requestNetwork` | `WifiP2pConfig.Builder` (API 29): `setNetworkName("DIRECT-xy…")`, `setPassphrase`, `setGroupOperatingBand(GROUP_OWNER_BAND_5GHZ)` → `createGroup` | `WifiP2pManager.connect` |
| Windows capabilities | Unpackaged: none. Packaged: the UWP sample declares `proximity` ([manifest](https://raw.githubusercontent.com/microsoft/Windows-universal-samples/main/Samples/WiFiDirect/cs/Package.appxmanifest)); **`wiFiControl` is not needed** [C] ([MS partner doc](https://learn.microsoft.com/en-us/windows-hardware/drivers/partnerapps/wi-fi-direct)) | (a) none for wlanapi from full trust, but 24H2+ gates `WlanQueryInterface`/`WlanScan` behind location consent [C] ([MS](https://learn.microsoft.com/en-us/windows/win32/nativewifi/wi-fi-access-location-changes)) | `proximity` |
| Android permissions | `CHANGE_WIFI_STATE`, `ACCESS_NETWORK_STATE`; FGS when filing the request [C]; `ACCESS_LOCAL_NETWORK` at target 37 [C] | `NEARBY_WIFI_DEVICES` (33+) / `ACCESS_FINE_LOCATION` + Location on (≤ 32) [C] | same as T2 |
| User prompts | **Android: one system dialog per pairing (API 33+)**, §2. Windows: none for legacy clients [L] | Android: none (GO with legacy clients). Windows: none, but location consent on 24H2 for (a) | **Android GO shows "accept" for each joining P2P client** ([AOSP](https://android.googlesource.com/platform/packages/modules/Wifi/+/481171b3fb)); silent approval needs a privileged permission. Windows pairing UI belongs to the app |
| PC keeps internet | **Yes** [C]: the STA and GO time-share one radio ([letsshare](https://github.com/AffanShaikhsurab/letsshare/blob/main/PLAN.md), [erinctech/wifiap](https://github.com/erinctech/wifiap)) | **(a) No**: one STA, so it drops the home Wi-Fi [L]. (b) yes | yes if Windows is the client |
| Phone keeps internet | Cellular always. Home Wi-Fi **only if `isStaConcurrencyForLocalOnlyConnectionsSupported()`** (OEM opt-in; Pixel 6+ yes, others unknown) [C] ([AOSP](https://source.android.com/docs/core/connect/wifi-sta-sta-concurrency)) | Usually yes (STA + GO in SCC) [L]. **Xiaomi and OnePlus left home Wi-Fi** to host Quick Share's `DIRECT-` AP [C] ([Android Authority](https://www.androidauthority.com/quick-share-for-windows-wifi-direct-3494451/)) | — |
| Band / channel | **No API** [C]. The GO follows the PC's STA channel. With no uplink it "tends to pick 2.4 GHz" [L]. Intel: a 5 GHz soft AP only works when the STA is on certain 5 GHz channels [C] ([Intel](https://www.intel.com/content/www/us/en/support/articles/000029083.html)). Intel soft APs often run 20 MHz wide [L] | `setGroupOperatingBand(5GHZ)` fails outright when it can't be honoured (retry with AUTO) [C]. SCC with the phone's STA | — |
| IP | GO at **192.168.137.1/24** (ICS DHCP) in observed runs [L]. **Don't hard-code it**; read it from the interface (§4) | GO at **192.168.49.1**, DHCP .128–.254 [C] | varies |
| Reliability | Shipped by Google as its "hotspot" medium [C]. Flaky points in §1.2 | Shipped as Quick Share's main upgrade path [L]. OEM idle-teardown after ~10 s with no client [C] ([report](https://github.com/pyamsoft/tetherfusenet/issues/602)) | **Windows GO: 20–60 % connection failures, and fails to start after repeated runs**. `ENABLE_WIFI_DIRECT_GO` is compiled out; `kEnableWifiDirect` defaults to false [C] ([google/nearby](https://github.com/google/nearby)) |
| Throughput | 8–25 MB/s with the PC STA on 5 GHz; 3–8 MB/s on 2.4 GHz [S]. Swings of 7–115 Mbps observed on a shared radio [L] | 30–40 MB/s typical, peak 90–110 MB/s (phone GO → Galaxy Book) [C, journalist test] | — |

**Recommendation.**
- **Primary T1.** It is the only topology that is non-disruptive and silent after the first run. The PC never leaves
  its network, Windows shows nothing, and Android remembers the approval. It fits clipboard, mirroring and
  medium-sized files.
- **Fallback T2(a).** Use it when T1 can't start (no GO-capable driver, Mobile Hotspot running), and as an opt-in
  **"fast mode"** for large transfers when T1 reports 2.4 GHz or measures < 15 MB/s. The selector marks it
  *disruptive* for the PC, so the first use needs a consent prompt ("Your PC will leave 'HomeWiFi' for about 1 min").
- **T3 is rejected.** If T2(b) ever becomes worth trying (it keeps the PC online), it needs the Android-side accept
  dialog every time, so it stays a research item.

Windows 10 and 11 behave the same for T1. Microsoft's classic sample runs on 10
([WiFiDirectLegacyAP](https://github.com/microsoft/Windows-classic-samples/tree/main/Samples/WiFiDirectLegacyAP)).
**Optional T1b (Win 11 24H2+, PC online):** `NetworkOperatorTetheringManager.StartTetheringAsync(
NetworkOperatorTetheringSessionAccessPointConfiguration)` adds per-session band selection (2.4/5/6 GHz,
`IsBandSupportedAsync`) and WPA3 ([MS](https://learn.microsoft.com/en-us/uwp/api/windows.networking.networkoperators.networkoperatortetheringsessionaccesspointconfiguration?view=winrt-28000)).
It needs `wiFiControl` and an internet connection profile, and it is the user's visible Mobile Hotspot. Worth one
spike (S-W9) as a 5 GHz booster; not the default.

### 1.2 What is flaky in practice (T1)

| Failure | Evidence | Mitigation |
|---|---|---|
| **Legacy clients are dropped every ~2 min** and the AP eventually stops | Google b/236805122 comment in `wifi_hotspot_medium.cc` [C]; letsshare [L] | In `ConnectionRequested`, call `WiFiDirectDevice.FromIdAsync(request.DeviceInformation.Id)` **per request**. Keep each object alive until teardown and never replace one mid-transfer. Call it on the UI (WPF Dispatcher) thread, as the MS sample requires |
| `FromIdAsync` hangs or times out (0x800705B4), still reported Oct 2026 on build 26300 + Realtek RTL8922AE | [Q&A](https://learn.microsoft.com/en-us/answers/questions/6033741/wifi-p2p-connection-fails-occasionally), [samples #82](https://github.com/microsoft/Windows-classic-samples/issues/82) [C] | Time it out at 5 s and keep going: the socket may still work. Log the adapter and driver |
| `Start()` on a reused publisher fails; transient "Aborted (Success)" | [samples #1400](https://github.com/microsoft/Windows-universal-samples/issues/1400) [C] | Create a fresh publisher and listener per link; retry Aborted once after 1 s |
| Turning Wi-Fi off mid-session leaves `Status == Started` with no event | [Q&A](https://learn.microsoft.com/en-us/answers/questions/2285169/) [C] | Watchdog on the socket plus the WFD interface `OperationalStatus` (§4) |
| Mobile Hotspot "takes precedence over all Wi-Fi Direct scenarios" | [docs](https://learn.microsoft.com/en-us/uwp/api/windows.devices.wifidirect.wifidirectlegacysettings) [C] | Fail with `hotspot_active`. Optional: offer the running hotspot's credentials instead (T1b read path) |
| Intel driver changes: AX210 timeouts with legacy clients; 2025 drivers reportedly dropped soft-AP-class Wi-Fi Direct on AX201 | [Intel forum](https://community.intel.com/t5/Wireless/WiFi-Direct-Dual-Band-mode-2-4GHz-and-5Ghz/td-p/1589153) [C]; letsshare [L] | Adapter matrix spike (S-W10); deny-list by driver version |
| IP appears late; no change notification | Google forces `NetworkInfo::Refresh()` and polls 10 × 500 ms [C] | Same polling on Windows; dial retries on Android |
| PC VPN kill-switch / Hyper-V, WSL or Docker NAT in 192.168.137.0/24; phone always-on VPN in lockdown → `EPERM` on `bindSocket` | FlyingCarpet: "VPNs should be disabled" ([README](https://github.com/spieglt/FlyingCarpet)) [L]; [ics-openvpn #1058](https://github.com/schwabe/ics-openvpn/issues/1058) [L] | Specific `link_fail` reasons and UI text |
| No SSID hiding, no WPA3, no client list or RSSI | [Q&A](https://learn.microsoft.com/en-us/answers/questions/1035363/is-there-a-way-to-hide-the-wifi-direct-ssid) [C]; letsshare [L] | WPA2-PSK with a strong derived passphrase |

---

## 2. Credentials and consent

**Derive the credentials and never rotate them by time.** This corrects §0's "pairing key and a time epoch". The
AOSP approval cache in `WifiNetworkFactory.isAccessPointApprovedInInternalApprovalList` (android16-release) behaves
as follows:
- For WPA2/WPA3 networks it matches **SSID + security type with any BSSID** ("Only require SSID and SecurityType
  match for non-open networks") [C, read the source].
- The cache is a per-app LRU of **50** access points, persisted in WifiConfigStore (`NetworkRequestStoreData`). It
  has no expiry.
- It is cleared on app uninstall or network-settings reset, and the dialog comes back if the user "forgets" the SSID
  [C].

So a per-epoch SSID would bring the dialog back every epoch.

```
wk   = HKDF-SHA256(ikm = K, salt = "airbridge/v2/wfd", info = hostId | "|" | clientId | "|" | u8(gen), 32)
ssid = "DIRECT-AB-" + base32(HMAC(wk, "ssid")[0..5))            // 18 ASCII chars, ≤ 32 bytes, unique per pairing
psk  = base64url(HMAC(wk, "psk")[0..24))                        // 32 ASCII chars (WPA2 needs 8–63 ASCII)
```

- `gen` starts at 0. "Rotate Wi-Fi key" in the device menu bumps it and costs one more dialog. A re-pair changes `K`,
  so it rotates automatically.
- Keep the `DIRECT-` prefix for driver compatibility. The SSID reveals nothing about identity, but it is a stable
  beacon while the AP is up: an accepted, small tracking surface, since the AP only exists during a link.
- **Don't** use `setIsHiddenSsid`. Windows can't hide the SSID anyway, and hidden networks need active probing.

### Android dialog matrix

| Android | Request | Dialog |
|---|---|---|
| 13–17 | `setSsid(ssid)` + `setWpa2Passphrase(psk)`, **no BSSID** | **First run only** per pairing [C] |
| 10–12 | Same | **Every time** unless `setBssid(exact)` with a previously approved BSSID [C]. Cache the BSSID after the first join (from scan results; location is granted ≤ 32). This only works if Windows' WFD adapter BSSID is stable [S: reports say it is derived from the physical MAC] |
| any, STA+STA device | — | The dialog **comes back even if approved** when a secondary STA can't be created at that moment (`revokeNormalBypass`), e.g. while the phone's own hotspot or P2P group is up [C] |
| 8–9 (minSdk 26) | — | No specifier API. **Exclude** from the profile |

- **`WifiNetworkSuggestion` is rejected.** Approval is once per app, but declining revokes the app's
  `CHANGE_WIFI_STATE` app-op. The app can't force a join. It uses the primary STA with internet validation, so a
  no-internet peer AP gets deprioritised [C] ([docs](https://developer.android.com/develop/connectivity/wifi/wifi-suggest)).
- **CompanionDeviceManager** bypass works only per exact BSSID [C]. On 13+ it adds nothing over the internal cache.
- Briar saw a dialog on every request on a Samsung A21s (API 29) [L]
  ([issue](https://code.briarproject.org/briar/public-mesh-research/-/issues/15)): that matches the 10–12 row.

**Windows prompts.**
- **Firewall:** the existing MSIX `windows.firewallRules` entry (TCP 47632–47641, `Profile="all"`) covers the WFD
  adapter, which comes up as an unidentified **Public** network [L]. So there is no firewall prompt.
- **Join consent:** there is no system consent UI for legacy clients [L].
- **Network-location flyout:** none expected [S].
- **T2(a) only:** a location consent on 24H2+.

---

## 3. Signalling without a LAN

**When is Wi-Fi Direct actually needed?** Only when there is no usable LAN session:
- the phone is on mobile data only;
- the PC is on Ethernet or another network;
- the network has guest/client isolation;
- or the LAN path is slow (a 2.4 GHz phone behind a wired PC).

In the first three cases the "persistent paired LAN session" **does not exist**, so it can't carry the negotiation
where Wi-Fi Direct matters most.

| Option | Verdict |
|---|---|
| (a) BLE GATT: Windows central → Android `BluetoothGattServer` (PROXIMITY §2.1/§2.3 `BleGattLink`), carrying a **full v2 session** with §10 framing | **Use.** It reuses `auth` and the §3.6 envelope, so there is no new crypto. Windows keeps it up with `GattSession.MaintainConnection = true` and subscribes to TX notify, so **either side can start a link**. 20–60 KB/s is plenty for `link_*` |
| (a′) A one-message "wake" characteristic with a MAC'd token | Simpler, but it needs its own replay protection and can only be written PC → phone. Only a stop-gap |
| (b) Wi-Fi Direct service discovery | **Reject.** DNS-SD over P2P is widely flaky [L]. Windows Wi-Fi Direct Services speaks ASP, which Android doesn't [S]. Android's `setVendorElements` is gated. It also costs radio time |
| (c) Existing LAN session | **Use when present** (the slow-LAN case). It also lets **Phase A0** test T1 end to end before any BLE code exists |

The combination is (c) when a LAN session is ready, otherwise (a). **Phone on mobile data only:** BLE is required.
After joining, the phone keeps cellular internet. Without BLE (Phase 3 of PROXIMITY §4.4 not built), there is no
link.

### Message changes (PROPOSED; refines PROXIMITY §4.2)

```json
{"t":"link_request","lid":7,"why":"xfer|mirror|user","bytes":734003200,"types":["wfd_legacy","wfd_go"]}
{"t":"link_offer","lid":7,"type":"wfd_legacy","host":"windows","cred":"derived","gen":0,"ssid":"DIRECT-AB-7KQ2M9XA",
 "sec":"wpa2","band":"2.4|5|unknown","addr":["192.168.137.1"],"port":47632,"ttl":120}
{"t":"link_offer","lid":7,"type":"wfd_go","host":"android","cred":"derived","gen":0,"ssid":"DIRECT-AB-…",
 "band":"5","freq":5180,"ttl":120}                                     // T2: Windows joins; no addr (Windows is the WS host)
{"t":"link_ready","lid":7,"addr":"192.168.137.23","freq":5180,"sta":"kept|dropped|none","dlg":false}
{"t":"link_fail","lid":7,"reason":"no_go_support|hotspot_active|radio_off|join_failed|no_ip|approval_denied|vpn|api_level|timeout|busy|user"}
{"t":"link_done","lid":7,"reason":"idle|ttl|user|lost"}
```

- **`type`:** `"hotspot"` stays for the Mac path; new values `wfd_legacy` (T1) and `wfd_go` (T2).
- **`cred:"derived"`:** the `psk` is never sent. `ssid` is included only as a sanity check. `cred:"inline"` plus
  `psk` remains available for T1b or a pre-existing Mobile Hotspot.
- **`addr`:** a list of candidates (IPv4 first, then IPv6 link-local), following Google's address-candidates
  pattern. `port` is the port actually bound (PROTOCOL §2.2).
- **New caps** in `hello`: `wfd_host` (Windows: a GO start has succeeded at least once on this adapter) and
  `wfd_join` (Android API ≥ 29).
- **PROTOCOL §3.4 needs an exception.** "A device never dials a peer it already has a ready session with" must not
  apply to dialing the `addr` of an active `lid`. Without it, make-before-break is illegal.

### Flow: phone shares a 700 MB file, no LAN, BLE session up

1. Phone → PC (BLE): `link_request{lid, bytes, types}`. The phone holds the `xfer_offer` (PROXIMITY §4.1).
2. PC:
   - starts a fresh publisher with the derived credentials;
   - waits for `Started` (≤ 5 s);
   - polls the WFD interface for an IPv4 address (10 × 500 ms);
   - sends `link_offer{type:"wfd_legacy", addr, port}`.
3. Phone joins: `requestNetwork(spec, cb, 15_000)`. On `onAvailable` it sends `link_ready{freq, sta}` over BLE.
4. Phone dials `ws://addr:port/v2` bound to the `Network`. After the handshake, the PC sends `bye replaced` on the
   BLE session (PROTOCOL §3.4), and the phone releases the held `xfer_offer` on the new session.
5. Either side sends `link_done` after 20 s idle or on `ttl`. Teardown follows §4.

PC-initiated sends skip step 1. **Failure path:** any `link_fail`, or no `link_ready` within 20 s → the PC stops the
publisher. Then either send ≤ 2 MiB over BLE or offer T2 "fast mode".

---

## 4. Socket plumbing

**Who listens.**
- **T1:** Windows already listens. `Server.cs` binds `IPv6Any` (dual-mode) / `Any` on 47632–47641, so the WFD
  interface is covered with no change. The phone dials.
- **T2:** Windows is still the WS host. It reports its 192.168.49.x address in `link_ready`. The phone dials it with
  the default socket factory, because traffic to the P2P subnet routes via `p2p0` [L].

**Android binding.**
- Build a dedicated client: `WsClient.http.newBuilder().socketFactory(NoDelaySocketFactory(network.socketFactory)).build()`.
- `NoDelaySocketFactory` currently wraps `getDefault()` and must be generalised to wrap a base factory, otherwise
  Nagle comes back.
- An IP literal needs no DNS.
- **Never `bindProcessToNetwork`.** It is process-wide, breaks the LAN/BLE session and any cloud calls, and every
  socket dies with the network [C].
- Rebuild the client for each new `Network` object.
- Catch `EPERM` (VPN lockdown) → `link_fail{vpn}`.

**Addressing.**
- Prefer the `link_offer.addr` the PC read from its interface (`NetworkInterface.GetAllNetworkInterfaces()`, adapter
  description "Microsoft Wi-Fi Direct Virtual Adapter…", `OperationalStatus.Up`, or Google's method: an
  `IF_TYPE_IEEE80211` LUID not listed by `WlanEnumInterfaces`).
- Fall back to `LinkProperties.dhcpServerAddress` (API 30), or else the default route's gateway.
- `WiFiDirectDevice.GetConnectionEndpointPairs()` gives the PC its own and the phone's IPs for logging.
- mDNS isn't needed.
- After `onAvailable`, retry the dial 3 × 2 s, because the address can be tentative [L].

**Drop detection.**
- Android: `NetworkCallback.onLost` / `onUnavailable`; on API 34+, `WifiManager.addLocalOnlyConnectionFailureListener`
  gives a reason.
- Windows: the session socket, plus a 2 s watchdog on the WFD interface `OperationalStatus`. Publisher events and
  `ConnectionStatusChanged` are unreliable [C].
- Both: PROTOCOL §4 liveness (15 s).

**Migration (code-level findings).**
1. Route the link dial through the **same per-peer `Dialer`** in `WsClient` (new `dialVia(peerId, url, network)`).
   This keeps "one attempt per peer" and lets `Hub` close the old session when the new one is ready (`Hub.kt:168`).
2. When the old session receives `bye replaced`, it sets `pausedByReplace`. That flag is cleared only by a
   *default-network* change (`Hub.registerDefaultNetworkCallback`). **Losing a local-only network is not a
   default-network change**, so after teardown the LAN/BLE path would never redial. `LinkManager.teardown()` must
   explicitly clear the pause and kick the dialer.
3. On phones without STA+STA, joining drops home Wi-Fi. The default network then changes to cellular and fires
   `WsClient`'s network-changed path. That path must not redial while the link session is ready (existing rule, but
   test it).
4. Going back:
   - If the phone kept its STA and the LAN peer is resolvable, redial LAN **before** releasing the link network
     (make-before-break back).
   - Otherwise accept a 2–5 s gap. Reconnect per §4.1.

---

## 5. Linux (short)

- **Easiest:** NetworkManager over D-Bus (`AddAndActivateConnection`, `802-11-wireless.mode=ap`,
  `ipv4.method=shared` → 10.42.x.1/24). polkit lets the active desktop user do this without a prompt [L].
  - On a single interface this **replaces the STA**.
  - Keeping the uplink needs a second virtual interface (`iw dev wlan0 interface add ap0 type __ap`, root) on the
    **same channel** (iwlwifi `{STA, AP} channels ≤ 1`) [C]
    ([iwlwifi](https://github.com/torvalds/linux/blob/master/drivers/net/wireless/intel/iwlwifi/mvm/mac80211.c)).
  - **Intel can't host a 5 GHz AP** because of LAR/NO-IR [L].
- **Closest analogue of T1:** a `wpa_supplicant` autonomous GO (`p2p_group_add freq=5`, legacy clients via
  `p2p_get_passphrase`) [C] ([README-P2P](https://w1.fi/cgit/hostap/plain/wpa_supplicant/README-P2P)). iwlwifi
  allows STA + P2P-GO on 2 channels, but it needs control-socket (root) access.
  - NetworkManager's `wifi-p2p` can't act as an autonomous GO [C]
    ([NM](https://networkmanager.dev/docs/api/latest/settings-wifi-p2p.html)).
- **Profile `(linux, android)`:** `lan > wfd_legacy (if a helper with CAP_NET_ADMIN exists) > wfd_go (Linux joins via
  NM, disruptive) > ble_gatt`. Default to the phone hosting (T2) unless the helper is installed.

---

## 6. Build spec

### 6.1 Selector profile

`(android, windows)` and `(windows, android)` → `lan` › `wfd_legacy` › `wfd_go` (disruptive, consent) › `ble_gatt`.

Upgrade rules:
- **Always** use `wfd_legacy` when there is no LAN and `xfer` > 2 MiB or `mirror_*`.
- With a LAN session, upgrade only if LAN `estMbps` < 80 and bytes > 200 MB.
- Offer `wfd_go` when `wfd_legacy` fails, or reports band 2.4 / < 15 MB/s with > 60 s remaining.

### 6.2 Android (`app/src/main/java/com/shazan/airbridge/net/link/`)

| File | Contents |
|---|---|
| `LinkCreds.kt` | `object LinkCreds { fun wfd(k: ByteArray, hostId: String, clientId: String, gen: Int): WfdCreds }`. HKDF and HMAC shared with `SecureChannel.kt` |
| `LinkManager.kt` | Per-`lid` state machine (IDLE → OFFERED → JOINING → DIALING → READY → TEARDOWN); `ttl` timer extended while bytes flow; hold-until-upgrade for `xfer_offer`; `teardown()` clears `pausedByReplace` |
| `WfdJoiner.kt` | `suspend fun join(c: WfdCreds, bssid: MacAddress?, timeoutMs: Int = 15_000): Joined`. Steps: `WifiNetworkSpecifier.Builder().setSsid().setWpa2Passphrase()` (+ `setBssid` on API 29–32) → `NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI).removeCapability(NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec)` → `cm.requestNetwork(req, cb, timeoutMs)`. `Joined(network, gateway, freq, staKept, dialogShown)`. Reads `freq` from `(caps.transportInfo as WifiInfo).frequency`. API 34: `addLocalOnlyConnectionFailureListener` |
| `P2pGoHost.kt` | `WifiP2pManager.initialize` → `WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(psk).setGroupOperatingBand(GROUP_OWNER_BAND_5GHZ)` → `createGroup`. On failure, retry with `GROUP_OWNER_BAND_AUTO`. Confirm via `requestGroupInfo`. Re-create on removal (idle teardown). `removeGroup` on done |
| `LinkSelector.kt` | `LinkProfile` table and scoring (PROXIMITY §4.1) |
| `WsClient.kt` (edit) | `dialVia(peerId, url, network)`; `NoDelaySocketFactory(base)` |
| `Protocol.kt` / `Session.kt` (edit) | `link_*` and caps `wfd_join` |
| Manifest | Nothing new at target 36. Add `ACCESS_LOCAL_NETWORK` before target 37 |

### 6.3 Windows (`AirBridge/Net/Link/`)

| File | Contents |
|---|---|
| `LinkCreds.cs` | Same derivation |
| `LinkManager.cs` | Same state machine; `link_fail` mapping |
| `WfdLegacyHost.cs` | New `WiFiDirectAdvertisementPublisher` per link. Set `Advertisement.IsAutonomousGroupOwnerEnabled = true`; `Advertisement.LegacySettings.IsEnabled = true`, `.Ssid`, `.Passphrase = new PasswordCredential { Password = psk }`; try `ListenStateDiscoverability = None` (S-W1). Subscribe `StatusChanged` (`Status`, `Error`: `RadioNotAvailable` / `ResourceInUse`). Create a `WiFiDirectConnectionListener` whose `ConnectionRequested` does `FromIdAsync` on the Dispatcher with a 5 s timeout, keeping `Dictionary<string, WiFiDirectDevice>`. Keep the publisher and listener in fields (no GC). `Stop()` and `Dispose()` everything on teardown |
| `WfdInterface.cs` | Find the WFD adapter and its IPv4/IPv6 addresses; poll; `OperationalStatus` watchdog |
| `WlanJoiner.cs` (T2) | P/Invoke `WlanOpenHandle`, `WlanEnumInterfaces`, `WlanQueryInterface(wlan_intf_opcode_current_connection)` (remember the current profile; needs location consent on 24H2), `WlanSetProfile` (temporary WPA2PSK/AES XML `AirBridge-<lid>`), `WlanConnect`, `WlanRegisterNotification`, a 15 s retry loop, `WlanDeleteProfile`, then reconnect the saved profile |
| `TetheringHost.cs` (optional, T1b) | `NetworkOperatorTetheringManager.CreateFromConnectionProfile(...).StartTetheringAsync(sessionConfig)` with `Band = FiveGigahertz` |
| `Package.appxmanifest` | Add `<DeviceCapability Name="proximity"/>` [S: required per the namespace docs; harmless], `<DeviceCapability Name="bluetooth"/>` (BLE), `<DeviceCapability Name="location"/>` (T2 restore), and `<DeviceCapability Name="wiFiControl"/>` only if T1b ships. The firewall rule needs no change |
| `.csproj` | No change: `net8.0-windows10.0.19041.0` already projects `Windows.Devices.WiFiDirect` |

### 6.4 Timeouts, errors, logs

| Step | Timeout | On failure |
|---|---|---|
| Publisher `Started` | 5 s; one retry with a fresh publisher | `no_go_support` / `hotspot_active` (ResourceInUse) / `radio_off` |
| WFD IPv4 address | 10 × 500 ms | `no_ip` |
| Phone join (`requestNetwork`) | **15 s** | `join_failed` / `approval_denied` (dialog dismissed) |
| WS dial + handshake | 3 × 2 s connect, then 10 s handshake | `timeout`; `vpn` on EPERM |
| Whole link | 30 s from `link_request` | `timeout` |
| `ttl` | 120 s, +60 s while bytes flow; `link_done` after 20 s idle | Teardown |

Log one line per transition, in this format:

```
AirBridge.Link lid=7 peer=<id8> type=wfd_legacy ev=go_started ms=830 ip=192.168.137.1 adapter="Intel AX211" drv=23.60.1
AirBridge.Link lid=7 ev=joined ms=4210 freq=5180 sta=kept dlg=false api=35
AirBridge.Link lid=7 ev=ready ms=5050 | ev=xfer MBps=18.4 bytes=734003200 | ev=done reason=idle dur=52s
```

### 6.5 Device tests (≈ 30 min each)

| # | Recipe | Pass |
|---|---|---|
| S-W1 | PC on 5 GHz home Wi-Fi. Debug menu: start/stop the publisher with derived credentials 10×; continuous `ping 1.1.1.1`; log the IP and `netsh wlan show interfaces` channel. Repeat with `ListenStateDiscoverability=None` | 10/10 `Started` ≤ 5 s; ping loss < 1 %; GO channel = STA channel |
| S-W2 | Phone debug activity: `WfdJoiner.join` 10× (first run approves the dialog). Log `isStaConcurrencyForLocalOnlyConnectionsSupported`, freq, join ms; fetch a URL over the default network | Join ≤ 15 s; dialog on run 1 only (API 33+); phone internet stays up |
| S-W3 | Hold the link 10 min with and without per-request `FromIdAsync` | No drop with the workaround (the drop reproduces without it) |
| S-W4 | Throughput: an in-app TCP blaster bound to `network.socketFactory` (Termux `iperf3` would go over cellular), plus a 1 GB AirBridge `xfer`, both directions | **≥ 20 MB/s with the PC STA on 5 GHz**; record the 2.4 GHz baseline |
| S-W5 | T2: phone `createGroup` with 5 GHz; PC `netsh wlan add profile` + `netsh wlan connect` (before writing code), then `WlanJoiner` | ≥ 25 MB/s; PC back on home Wi-Fi ≤ 15 s, 10/10 |
| S-W6 | Conflicts: PC Mobile Hotspot on; PC VPN on; phone always-on VPN in lockdown; Hyper-V/WSL running | Each yields its specific `link_fail` and UI text, with no hang |
| S-W7 | Mid-transfer: PC Wi-Fi off; phone walks out of range; app killed on each side | Both sides detect within 15 s, fall back to LAN/BLE, no zombie publisher or dialer pause |
| S-W8 | End to end, no LAN (phone on cellular): BLE wake → T1 → 1 GB. Reboot both and repeat | **Zero prompts on run 2**; PC keeps internet; phone keeps cellular (and Wi-Fi if concurrent) |
| S-W9 | T1b on 24H2: per-session tethering at 5 GHz while the PC STA is on 2.4 GHz | 5 GHz achieved; ≥ 20 MB/s |
| S-W10 | Adapter matrix S-W1 + S-W4 on Intel AX201/AX211/BE200, Realtek 8852/8922, MediaTek MT7921/7922, Qualcomm (Snapdragon X) | Deny-list or allow-list by driver |

### 6.6 Effort

| Platform | Phase A0 (T1 over LAN-signalled `link_*`) | + T2 fast mode | Notes |
|---|---|---|---|
| Android | 4–5 d | +2–3 d | BLE GATT server and session excluded (PROXIMITY Phase 3, 2–3 wk shared with the Mac track) |
| Windows | 4–5 d | +2–3 d (wlanapi, restore, 24H2 location) | BLE central excluded |
| Spec + device spikes | 1 d + 3 d (S-W1…S-W10) | — | Adapter matrix needs borrowed laptops |

**Corrections to PROXIMITY_DESIGN.**
- §0 / §3.1(d): "both keep internet" holds for the PC. The phone keeps cellular, and keeps Wi-Fi only with STA+STA
  concurrency.
- §0: time-epoch credentials and P2P-discovery wake-up are out.
- §2.5 / §4.3: the legacy AP needs no `wiFiControl`.
- §3.4: Windows legacy AP throughput depends on the PC's STA band (8–25 MB/s on 5 GHz, 3–8 MB/s on 2.4 GHz).
- §3.3: wrap `network.socketFactory` in the no-delay factory; the per-request `FromIdAsync` is mandatory.
