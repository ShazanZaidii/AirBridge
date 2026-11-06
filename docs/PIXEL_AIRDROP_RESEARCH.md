# Android "Quick Share ↔ AirDrop": software trick or hardware feature?

Status: **research (informative)**, 2026-11-02. This refines §1.2/§1.3 of [PROXIMITY_DESIGN.md](PROXIMITY_DESIGN.md) and
does not repeat it. Confidence labels: **[confirmed]** = primary source or direct artefact; **[likely]** = several
consistent secondary or community sources; **[speculation]** = inference.

**Short answer.** It is not a pure software trick, and it is no longer Pixel 10 only. Google wrote its own AWDL +
AirDrop stack in Rust. That stack runs as a privileged vendor daemon (`mosey_server`), and it can only reach the air
through a Google-defined Wi-Fi driver hook, **WonderTap**. Each Wi-Fi chip's driver and firmware has to implement
that hook. So the feature can be ported, and Google and OEMs are porting it model by model. But on a phone whose
Wi-Fi driver or firmware lacks the hook, root plus flag flips gets you nothing. No public API exposes it.

---

## 1. What Google shipped

| Item | Facts | Conf. |
|---|---|---|
| Announcement | 2025-11-20, [Google Security Blog](https://blog.google/security/android-quick-share-support-for-airdrop-security/) (Dave Kleidermacher) and [Keyword](https://blog.google/products-and-platforms/platforms/android/quick-share-airdrop/). It started with the Pixel 10 family. | confirmed |
| Rollout so far | Pixel 9 series in Feb 2026, though [not the 9a at first](https://heise.de/-11181190); the files were found in the Canary build ([AA](https://www.androidauthority.com/pixel-9-airdrop-quick-share-support-android-canary-3630861/)). Galaxy S26 on One UI 8.5 in Mar 2026, off by default behind "Share with Apple devices" ([9to5Google](https://9to5google.com/2026/03/22/samsung-galaxy-s26-airdrop-quick-share/)). In Jun 2026 Google's [official list](https://www.android.com/quick-share/with-iphone/) named Pixel 10/10a, 9/9a and 8a, Galaxy S24–S26 and Z Fold/Flip 6–7, OnePlus 15, OPPO Find X9/N6, vivo X300, Xiaomi 17T Pro and Honor Magic V6 ([MacRumors](https://www.macrumors.com/2026/06/02/google-airdrop-support-more-android-phones/)). Since Sep 2026, Pixel 7/8 owners report a staged rollout, but Google has not confirmed it ([AA](https://www.androidauthority.com/pixel-airdrop-3707826/)). | confirmed (7/8: likely) |
| Apple side | Works with iPhone, iPad and Mac. The Apple device must be set to **"Everyone for 10 minutes"**. "Contacts Only" does not work, and Google says it needs Apple's help for that ([Pixel Help](https://support.google.com/pixelphone/answer/9286773)). Mac→Pixel works through the normal Finder AirDrop sheet with the phone in Quick Share Receive mode. Tester reports are mixed. | confirmed |
| Direction / content | Both directions, files and links, and the user must accept each transfer. Pixel Help still says receiving needs "Pixel 9 or later (excluding 9a)" while sending works on "8a or later". The page contradicts the June device list, so it is probably stale. | confirmed / page inconsistent |
| Security claims | Core in **memory-safe Rust**. The connection is direct peer-to-peer, with no server and "no workaround". NetSPI pentested it (Aug 2025) and found one Low issue, sensitive data in logcat, which was fixed ([report PDF](https://www.netspi.com/wp-content/uploads/2025/11/google-feature-review-report.pdf)). Google never names the radio protocol. | confirmed |
| Apple cooperation | **None.** Google said "we accomplished this through our own implementation" ([AA](https://www.androidauthority.com/quick-share-airdrop-compatible-without-apple-3618067/)). Apple has not blocked it so far. | confirmed |
| Components | Distributed as the **Quick Share Extension** APK, `com.google.android.mosey`, through Play System Update ([AA](https://www.androidauthority.com/quick-share-extension-app-for-airdrop-compat-3618127/)). It also needs the vendor-image pieces described in §3. | confirmed |

## 2. How it technically works: real AWDL, not Wi-Fi Aware

**It speaks Apple's real AWDL link layer and the AirDrop HTTPS protocol.** The evidence:

1. **NetSPI's published logs** [confirmed]. The Pixel 10 Pro logcat shows AirDrop's own protocol:
   `POST /Ask … User-Agent: AirDrop/1.0`, `/Discover`, plist keys `SenderRecordData` and `ReceiverComputerName`, and a
   BLE scan for Apple manufacturer data (company 76 = 0x004C, type 0x05 = AirDrop). The peer is
   `fe80::…%awdl0`, and the Pixel's own interface is **`mosey0`**. NetSPI tested interop against **OpenDrop**, which
   only runs over OWL's AWDL. So the Pixel interoperates with open-source AWDL, which settles the link layer.
2. **Macs are supported** [confirmed]. Macs have no Wi-Fi Aware ([Apple docs](https://developer.apple.com/documentation/wifiaware)
   list iOS, iPadOS and Mac Catalyst 26 only. DTS says there is no Mac support. `WACapabilities` has the same
   metadata). So the shared link cannot be NAN.
3. **Pixel kernel source** [confirmed, read via a community tree that builds Google's `google-modules`]. The
   "Google Wonder WiFi Virtual Soft-MAC Driver" (`google-modules/wlan/wonder`, GPL, "Google Android WiFi Team",
   © 2025) defines **WonderTap**: "the complete API contract that a vendor-specific driver must implement". It offers
   raw 802.11 TX/RX on a fixed channel with fixed or adaptive TX rates and frame filters. In that tree only the
   **bcm4390** driver (Pixel 9) implements it, and the Tensor G4 device tree has a `zumapro-wonder.dtsi`
   (`wondertap-provider = <&wlan_node>`) ([tree](https://github.com/Ante0/SULTAN17)).
4. **Community reverse engineering** [likely]. Per [mosey-extended](https://github.com/thelok1s/mosey-extended),
   `mosey_server` is a Rust native Binder daemon in the **Pixel 10 vendor image**. It creates `wonder0` (monitor
   mode, ch 149 / 5745 MHz) through nl80211, sends vendor commands with `vendor_id 0x1A11` (Google's OUI, 00:1A:11),
   and does 802.11 I/O over `PF_PACKET/TPACKET_V3`. [OPAirDrop](https://github.com/Weverses/OPAirDrop) got
   **Mac→OnePlus (SM8750) transfers over AWDL** working by teaching the stock Qualcomm `qcacld-3.0` driver WonderTap.
   That driver already contains a `WMI_VDEV_TYPE_WIFI_PASSTHRU` (=9) vdev, but the stock firmware asserts on it. The
   project sends AWDL QoS-data frames with Apple's LLC/SNAP `aa aa 03 00 17 f2 08 00` and hops social channels
   149/44 ([doc](https://github.com/Weverses/OPAirDrop/blob/HEAD/docs/SM8750-AWDL.md)).
5. **Side effects fit AWDL** [likely]. AWDL time-shares the radio. At launch some Pixel 10s dropped Wi-Fi when the
   share sheet opened ([TechRadar](https://www.techradar.com/phones/google-pixel-phones/the-new-airdrop-feature-on-the-google-pixel-10-is-proving-buggy-for-some-users)).

**What is not true** [confirmed / likely]. Early press claimed the "EU forced Apple onto Wi-Fi Aware, so Android can
now AirDrop" ([HN](https://news.ycombinator.com/item?id=46062504), Medium). AirDrop still runs on AWDL. Mishaal Rahman's
source said Google implemented AWDL ([AA podcast](https://www.androidauthority.com/authority-insights-podcast-014-3618309/)),
and the artefacts above agree. The WonderTap code reuses a NAN *data-interface* vdev only as a firmware vehicle
(OPAirDrop). It does not speak the Wi-Fi Aware protocol. "Wi-Fi Direct" claims in consumer press are wrong. The
"Wi-Fi firmware update via Mainline" theory is half right: the firmware and driver matter, but the parts that gate
the feature live in the vendor and kernel images.

**Stack:**

| Layer | Component | Where it lives | Conf. |
|---|---|---|---|
| UI / share sheet | Quick Share (GMS) + `com.google.android.mosey` (Java) | Play / Play System Update | confirmed |
| Protocol | `mosey_server` + `libmosey_daemon_ffi.so` (Rust): AirDrop BLE adverts, AWDL MAC, mDNS, HTTPS `/Discover` `/Ask` `/Upload`. Binder `com.google.android.moseyservice.IMoseyService/default` | **vendor partition**, SELinux domain, NET_ADMIN/NET_RAW | likely |
| Virtual MAC | `wonder.ko` (mac80211 soft-MAC) → netdev `mosey0` (IPv6 link-local) | kernel module + device tree | confirmed |
| Radio hook | WonderTap ops in the Wi-Fi driver (bcmdhd 4390/4398, Qualcomm passthru, MediaTek ?) + firmware raw-TX support | vendor kernel + Wi-Fi firmware | confirmed (BCM4390) / likely (others) |
| Gating | Capability check over Binder, Pixel-experience / GMS flags, per-model allow-list | system + server | likely |

## 3. Software-only or hardware-dependent?

**It depends on firmware and driver rather than on special silicon, but no user can add it after the fact.**

| Requirement | Detail | Conf. |
|---|---|---|
| Wi-Fi driver/firmware that can transmit and receive **arbitrary 802.11 frames on 5 GHz ch 149/44 while staying associated** (STA concurrency, fixed-rate injection, RX address filters, BA) | Most modern chips can do this physically. The vendor has to expose it as a WonderTap provider. On Pixels, Google ships this in bcmdhd 4390/4398. Qualcomm's public driver has the passthru plumbing, but older firmware lacks it. | confirmed / likely |
| Vendor-image daemon + SELinux + init service | `/vendor/bin/mosey_server`, vendor sepolicy, service contexts. These can't be sideloaded, which is why it arrives through OS builds such as the Pixel 9 Canary or Android 16 QPR3 ([GSMArena](https://m.gsmarena.com/google_pixel_9_series_could_soon_get_airdrop_support_in_quick_share-news-71029.php)) or One UI 8.5. | likely |
| Kernel module + device-tree node | `wonder.ko` plus a `google,wonder-drv-v1` node bound to the Wi-Fi node | confirmed |
| Android version | Shipped on Android 16 (Pixel 10 stable, One UI 8.5). Google publishes no minimum. | likely |
| CPU | The Pixel 10 `mosey_server` binary hits **SIGILL** on Tensor G2 (ARMv8.2), so it is built for a newer ISA ([issue #9](https://github.com/thelok1s/mosey-extended/issues/9)). Google rebuilds it for each device. | likely |

**Rooted Pixel 9 / older devices.**
- **Pixel 9 (non-a):** officially supported, so root adds nothing.
- **Pixel 7/8 (BCM4389):** the public tree has no WonderTap in bcm4389. The best community port loads a *virtual* PHY
  so `mosey_server` starts. The README says it has "no real RF … real 802.11 frame I/O will not work". Google seems
  to be back-porting through OTAs (Sep 2026 reports, and the 9a and 8a are now listed). **Waiting for the OTA beats
  root.**
- **Non-Pixel Qualcomm with open driver source (OnePlus SM8750):** possible with heavy kernel-driver work, proven by
  OPAirDrop. It is only verified one way (Mac→phone, small text), and receives skip any sender check.
- **Flag flipping alone never works** without the driver hook [likely].
- These ports also lift `mosey_server` out of Google firmware. That is proprietary code, and redistributing it is a
  licence problem; mosey-extended deliberately doesn't ship it.

## 4. Can AirBridge benefit?

| Path | Verdict | Conf. |
|---|---|---|
| Public SDK / Nearby / Quick Share API to an AirDrop endpoint | **None.** Mosey is a closed privileged service. Nearby Connections never reaches Apple devices. | confirmed |
| Use `mosey0` / AWDL from an app, e.g. Mac `NWParameters.includePeerToPeer` → phone | **No.** The interface belongs to the mosey process under SELinux, and it only serves `_airdrop._tcp`. An app cannot publish its own Bonjour service on it. | likely |
| **Hand off to the OS share UIs** | **Yes, with public APIs.** Mac→phone: `NSSharingService(named: .sendViaAirDrop)` / `NSSharingServicePicker`, with the phone in Quick Share Receive mode. Phone→Mac: `Intent.createChooser(ACTION_SEND…)` → Quick Share → Mac, with the Mac set to "Everyone (10 min)". The user picks the target every time. There is no programmatic targeting, completion callback or resume. | confirmed (APIs) / speculation (UX fit) |
| Detect support | Heuristics only: the `com.google.android.mosey` package is present (needs a `<queries>` entry) plus a model allow-list. On Samsung, the "Share with Apple devices" toggle is not readable. | speculation |

Caveats:
- Re-implementing AWDL (as Google did) is legal ground Google chose to stand on. Apple has not acted.
- Shipping extracted Google binaries is not acceptable.
- AirDrop "Everyone" receive accepts any nearby Apple device, so the user's accept prompt is the only gate.

## 5. Wi-Fi Aware status (the would-be common substrate)

| Platform | Status | Conf. |
|---|---|---|
| Android | API 26+, **optional**. It needs a vendor NAN HAL and firmware (`FEATURE_WIFI_AWARE`) ([AOSP](https://source.android.com/docs/core/connect/wifi-aware)). It may switch off during SoftAP or P2P. Pairing (Wi-Fi Aware 4.0 NPK/NIK) has been available since API 34. Most recent Pixels and Samsung flagships report support. Check each device. | confirmed / likely |
| iOS/iPadOS 26+ | iPhone 12+ and some iPads. It **requires an Apple-approved entitlement** (request form) and **mandatory 6-digit-PIN pairing**, with no open data path ([Espressif](https://developer.espressif.com/blog/2026/08/wifi-aware-esp-to-iphone/)). ESP32 and Realtek have shown interop. I found no public Android↔iPhone app demo. | confirmed / likely |
| macOS 26/27 | **Not available.** Docs list Mac Catalyst, but DTS says no. The macOS 27 SDK diff adds only `WAPerformanceForecast` ([summary](https://miniswift.run/whats-new/wifiaware/)). Nobody has published a `WACapabilities` result. | likely |
| Relevance to AirDrop | **None today.** AirDrop interop runs on AWDL (§2). | confirmed |

For AirBridge, Wi-Fi Aware is a decent Android↔Android medium; Nearby Connections already uses it where available.
It is an Android↔iPhone option only if an iOS app ever exists. It is useless for Mac.

## 6. Bottom line for AirBridge

**(a) AirDrop-class proximity for phones that aren't on the list.** No viable path. The Rust stack itself is not the
hard part; the per-chip driver and firmware raw-frame hook is. A Play-store app can't do it, and root only works on
open-driver Qualcomm devices with weeks of kernel work. The listed devices (about 30 models, mostly flagships from
2024 on) already get it natively.

**(b) Android↔Mac.** Keep the BLE + hotspot design. Add one cheap, honest feature: **"Send via AirDrop / Quick
Share" hand-off**.
- Mac menu: when a paired phone is a known-supported model, show "Send to phone via AirDrop" (`NSSharingService`).
- Android: show "Send to Mac via Quick Share" (system chooser).
- Use it for large files when there's no shared LAN, as an alternative to the disruptive hotspot hop. The Mac keeps
  its Wi-Fi (AWDL).
- Limits: the user picks the target, there are no AirBridge progress or resume semantics, and the Mac must be in
  "Everyone" for phone→Mac.
- Treat this as an extra transport-selection option, not a protocol change, and validate it with a spike on a
  supported phone.

**Corrections to PROXIMITY_DESIGN.md**

| Design doc says | New evidence |
|---|---|
| Google "reimplemented AWDL inside a privileged Pixel system module" (inference) | **Confirmed, and more precise.** It is a Rust vendor daemon (`mosey_server`) plus Google's `wonder` soft-MAC plus a WonderTap hook in each Wi-Fi driver. It does not come from a Mainline module. |
| Expansion "in 2026" without a device list; "Pixel 10 owners can AirDrop Mac→phone" | It now covers about 30 models across Pixel 8a–10, Samsung S24–S26 and Fold/Flip 6–7, OnePlus, OPPO, vivo, Xiaomi and Honor, with Pixel 7/8 rolling out. |
| "AirBridge cannot hook into that" | It cannot drive it programmatically, but it **can hand off** to it through `NSSharingService` / the Android chooser (§4). That is worth a small UX feature. |
| OWL is archived, and a Play app cannot speak AWDL | Still true for apps. A rooted Qualcomm phone *can* (OPAirDrop) by reusing Google's stack, which is not a basis for AirBridge. |
