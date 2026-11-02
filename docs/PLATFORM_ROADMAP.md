# Platform Roadmap: Linux, iOS, Android↔Android

Status: plan for a solo developer. Platform facts were checked on 2026-11-02. Effort figures are **focused
developer-weeks**; multiply by about 2 for evenings-only work. Wire behaviour is defined by
[PROTOCOL.md](PROTOCOL.md), the layering by [ARCHITECTURE.md](ARCHITECTURE.md), and radio choices by
[PROXIMITY_RESEARCH.md](PROXIMITY_RESEARCH.md).

---

## 1. Linux client (host, like macOS and Windows)

### 1.1 Recommended stack: a Rust daemon with a thin UI

Why Rust rather than Python:
- **Distribution:** one static binary; no venv or GI version drift across distros.
- **Pointer path:** steady 120 Hz `ptr_move` with no GC pauses.
- **Libraries:** first-class crates exist for every Wayland portal and for libei.

Python (`websockets`, `zeroconf`, `dbus-next`, `python-evdev`) is a valid **2-week prototype**, but packaging it and
the input path become the long-term cost.

| Concern | Choice | Notes |
|---|---|---|
| Async runtime / WS server | `tokio` + `tokio-tungstenite` | Path `/v2` check in the handshake callback; deflate off; `set_nodelay(true)` |
| mDNS | Avahi over D-Bus (`zbus`) where present; `mdns-sd` crate as fallback | Avahi avoids a second responder fighting over UDP 5353. TXT `v,id,name,plat` per §2.3. |
| Crypto / key store | `hmac` + `sha2`, keys in Secret Service (`oo7` or `keyring` crate) | Fall back to an `0600` file under `$XDG_DATA_HOME` |
| Clipboard | X11: `x11rb` + XFixes selection events. Wayland: `wayland-client` with `ext-data-control-v1` / `zwlr-data-control` (KDE, wlroots). `arboard` for set/get. | **GNOME (Mutter) implements no data-control protocol** ([lamco-data-control](https://docs.rs/lamco-data-control)), so background clipboard monitoring is impossible there. Use push-on-hotkey or tray, or ship an optional GNOME Shell extension. |
| `ctl_target` (inject) | X11: XTest. Wayland: RemoteDesktop portal + `ConnectToEIS` via `ashpd` + `reis` (libei). Fallback: **uinput**. | Portal ⇒ a user consent dialog. Store the portal restore token so it's asked once. uinput needs a udev `uaccess` rule but works on every compositor, and HID→evdev is a fixed table (§8.4). |
| `ctl_source` (capture at edge) | X11: XInput2 raw events + XFixes pointer barriers + grab. Wayland: **InputCapture portal** + libei. | InputCapture exists in GNOME ≥ 45 and KDE Plasma ≥ 6.1 ([Phoronix](https://www.phoronix.com/news/KDE-Plasma-6.1-Beta)). wlroots/Sway: none. Deskflow users still report portal gaps in 2026 ([discussion](https://github.com/deskflow/deskflow/discussions/7499)). Ship ctl_source **last**. |
| Tray + UI | `ksni` (StatusNotifierItem) + one small GTK4 window (`gtk4-rs`/`relm4`) for the pairing QR, code, and layout | GNOME has no tray without the AppIndicator extension (Ubuntu ships it). The window must also work standalone. |
| BLE (Phase 2 proximity) | `bluer` (BlueZ D-Bus) | Central + peripheral + L2CAP all available |
| Autostart | `systemd --user` unit + XDG autostart `.desktop` | — |
| Packaging | `cargo-deb`, `cargo-generate-rpm`, AUR PKGBUILD | **Flatpak can't use uinput or evdev**. It works if you accept portal-only input. Avoid AppImage, which can't install udev rules. |

### 1.2 Phasing and effort

| Step | Capabilities after | Effort |
|---|---|---|
| L1: Core host: WS listener 47632…, mDNS advertise and browse, hello/auth/pair (QR + 6-digit), liveness, session manager | Pairs with Android, Mac, Windows | 1.5–2 wk |
| L2: `xfer` streaming + `clip` (X11, KDE, wlroots auto; GNOME push-only) | `caps:["clip","xfer"]` | 1–1.5 wk |
| L3: `ctl_target` via XTest + uinput (portal/libei as a second path) | Mac/PC cursor can enter Linux; phone trackpad works | 1–2 wk |
| L4: `ctl_source` on X11, then the InputCapture portal | Linux cursor can leave to other devices | 2–3 wk |
| L5: Packaging, udev rule, autostart, docs | Installable | 0.5–1 wk |
| **Total** | | **≈ 6–9 wk** |

Linux is also the **best desktop for proximity**:
- NetworkManager supports Wi-Fi P2P as a real client, so it can join an Android Wi-Fi Direct group while keeping
  its own Wi-Fi when the chipset allows.
- NetworkManager can host an AP (`nmcli dev wifi hotspot`).
- BlueZ exposes L2CAP.

---

## 2. iOS client (a client, like Android)

### 2.1 What is possible

| Feature | How | Constraint |
|---|---|---|
| Discovery + session over LAN | `NWBrowser(.bonjour("_airbridge._tcp"))` + `NWConnection` with `NWProtocolWebSocket`, the same code as macOS `Net/` | `NSLocalNetworkUsageDescription` + `NSBonjourServices`. The multicast entitlement is *not* needed for declared Bonjour types ([TN3179](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)). |
| **iOS↔Mac with no shared LAN** | `includePeerToPeer = true` on the iOS browser/connection **and** on the macOS `NWListener` (AWDL) | Apple↔Apple only; foreground. About 1 line on each side. |
| Pairing | QR scan (AVFoundation/VisionKit) → §3.3; or 6-digit code entry | — |
| Clipboard receive | Set `UIPasteboard.general.string` on `clip` | Foreground only. Writing doesn't prompt. |
| Clipboard send | User-initiated: a `UIPasteControl` / SwiftUI `PasteButton` reads without the "Allow Paste" prompt ([docs](https://developer.apple.com/documentation/uikit/uipastecontrol)); share sheet; App Intent / Control Center control | Programmatic reads raise the paste prompt. There is no background monitoring. |
| File send | Share extension streaming §6 chunks, or hand off to the app through an App Group | Extension memory and time limits. Large sends run in the app with `BGContinuedProcessingTask` (iOS 26+, Live Activity progress, user-cancellable) ([docs](https://developer.apple.com/documentation/backgroundtasks/bgcontinuedprocessingtask)). |
| File receive | Write to `Documents/AirBridge Transfers/`, with `UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace` so it shows in Files; optional "Save to Photos" | Foreground (or a continued task) only |
| Trackpad / keyboard mode | Gesture surface sends `ptr_*`, `ptr_click`, `action`; iPad hardware keyboard via `UIKey.keyCode`, which is already HID (§8.4) | `caps:["clip","xfer","trackpad"]` |
| Wi-Fi Aware to Android | WiFiAware framework (iOS 26+, iPhone 12+) | Research spike only. Pairing is mandatory and Android interop is reported broken (PROXIMITY §0). |

### 2.2 What is impossible (plain answer)

- **`ctl_target`**: iOS has no public API to inject input into other apps or draw a system-wide cursor. An iPhone
  can never be "controlled" by a Mac or PC. Omit the cap.
- **`ctl_source` edge-crossing**: an app can't observe the pointer outside its own window.
- **Background clipboard sync**: suspended apps can't read the pasteboard, and reads always risk a prompt. Clip sync
  happens only when the app is open or the user acts.
- **Always-on sessions**: sockets die seconds after backgrounding (local-network operations from the background are
  denied while permission is undetermined, per TN3179). Reconnect on `scenePhase == .active`. VoIP or audio
  background modes used to stay alive will be rejected in App Review.
- **Wi-Fi Direct, hosting a hotspot, being discoverable to Android over BLE while backgrounded** (overflow-area
  adverts).

**App Store and distribution.** For personal use, a free Apple ID gives 7-day provisioning that must be re-signed,
which is annoying. The $99/yr Developer Program gives TestFlight and any entitlement Apple self-serves. A trackpad,
clipboard, and file-sharing utility fits normal review if every clipboard read is user-initiated. Apple's
Universal Clipboard already covers iOS↔Mac, so AirBridge's iOS value is **iOS↔Windows, Android, and Linux**.

### 2.3 Plan and effort

| Step | Effort |
|---|---|
| I0: Extract `AirBridgeCore` Swift package from the macOS `Net/` code (Session, HMAC via CryptoKit, framing, Clipboard/Transfer services, Keychain). It also de-duplicates the macOS code. | 1 wk |
| I1: SwiftUI app: discovery list, QR pairing, session, clip send/receive, file receive | 2 wk |
| I2: Trackpad + keyboard UI (port Android `TrackpadActivity` semantics) | 1 wk |
| I3: Share extension + `BGContinuedProcessingTask` big sends + Live Activity | 1–1.5 wk |
| I4: AWDL (`includePeerToPeer`) on iOS and the macOS listener | 2–3 days |
| **Total** | **≈ 5–6 wk** |

---

## 3. Android↔Android discovery improvements

Today `AirBridgeService` works like this:
- It uses Nearby `Strategy.P2P_CLUSTER`.
- It advertises `Build.MODEL` as the endpoint name and connects manually by endpoint id.
- It sends v1-style `"CLIP:"` BYTES and FILE payloads.

The v2 rewrite moves this onto the v2 handshake (§10). Beyond that:

| Improvement | Why | Effort |
|---|---|---|
| Advertise `endpointInfo = "2|<id>|<plat>|<name>"` (bytes) | Lets discovery match stored keys, so paired phones **auto-connect** with no picker | 0.5 d |
| Strategy: `P2P_STAR` (hub = the phone the user is holding) for 1:N, `P2P_POINT_TO_POINT` for 1:1 bulk. Measure both against CLUSTER. | Google documents Cluster as "lower bandwidth" and Star/P2P as higher or highest ([strategies](https://developers.google.com/nearby/connections/strategies)). Peers must use the same strategy. | 1 d + measurement |
| `ConnectionOptions.setConnectionType`: `NON_DISRUPTIVE` for background clip/ctl; `DISRUPTIVE` only on an explicit "send fast" | Disruptive upgrades may drop the user's Wi-Fi ([Builder](https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/ConnectionOptions.Builder)) | 0.5 d |
| `setLowPower(true)` on advertising while idle; full discovery only with the UI or tile open | Battery | 0.5 d |
| Pairing UX without a QR (**proposal, needs a PROTOCOL §3.3 addition**): on first connection both phones show Nearby's `ConnectionInfo.authenticationDigits`; the user confirms they match; use `rawAuthenticationToken` as pairing secret `S` | Removes the QR or code step for phone↔phone and resists MITM through visual comparison | 1–2 d (+ spec) |
| Frames ≤ 1,047,552 B per BYTES payload; keep 512 KiB chunks plus §6 flow control | Nearby's BYTES ceiling ([ConnectionsClient](https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/ConnectionsClient)) | included |
| Wi-Fi Aware link when both report `FEATURE_WIFI_AWARE` and `isAvailable()`, only if Nearby measurements disappoint (PROXIMITY Phase 1) | Up to hundreds of Mbit/s claimed; encrypted NDP with a PSK derived from `K`. Hardware support varies by OEM, so gate at runtime. | 2–3 wk |
| Consider `CompanionDeviceManager` presence observation for a paired phone | Wakes the app when the peer is near, instead of scanning all day from an FGS | 2–3 d (spike) |

**Recommended order:** endpointInfo → strategy and connection type → measurement → pairing UX → (Wi-Fi Aware only
if needed). Total for the non-Aware items: **≈ 1 week**.

## 4. Overall sequencing for one developer

1. Finish v2 on the existing three platforms, plus the Phase 0 fixes:
   - FGS type → `connectedDevice`;
   - macOS Local Network plist keys;
   - Nearby `endpointInfo` and strategy.
2. **Linux L1–L3** (≈ 4 wk). This has the highest reach per week and reuses the host role.
3. **iOS I0–I2** (≈ 4 wk). Extracting `AirBridgeCore` pays off on macOS too.
4. BLE discovery + LAN handoff (PROXIMITY Phase 2).
5. Linux L4 (ctl_source on Wayland), hotspot-assisted desktop links (Phase 3), iOS share extension, AWDL.
