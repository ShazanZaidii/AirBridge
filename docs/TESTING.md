# AirBridge v2 — build, first run, and test plan

Everything in the v2 branches compiles, and the Mac app plus both desktop network stacks were exercised
against a spec-exact reference peer. **Nothing has run on a real phone or a real Windows machine yet.**
This is the checklist for that first round.

Branches: Android `v2` (`/Users/xaidi/AndroidStudioProjects/AirBridge`), macOS `v2`
(`/Users/xaidi/XcodeProjects/AirBridge-MacOS`), Windows `v2-protocol` (local clone `/Users/xaidi/AirBridge-Windows`,
not pushed).

---

## 0. The reference probe (works on this Mac)

`tools/v2probe.py` (Android repo) is a Python peer that speaks v2 exactly. Use it to test any app without the
other platforms being around.

```bash
# act as a host (what a Mac/PC is): accepts phones; prints the pairing digits, type `accept`
python3 tools/v2probe.py host --name "Probe Host" [--auto-accept]

# act as a client (what a phone is): dial a host, pair (confirm digits shown on both), then type commands
python3 tools/v2probe.py client ws://192.168.1.20:47632      # or an airbridge://pair?... QR url
> clip hello            # push clipboard
> send ~/some/file.zip  # send a file (flow-controlled)
> enter left 0.5        # pretend the cursor entered the target's left edge
> move 10 0 | click 0 | key 0x04 a | action mission_control | leave
> quit
```

State (device id, peer keys) lives in `~/.airbridge-probe.json`; set `AIRBRIDGE_PROBE_STATE=path` to use a
second identity. The second connection to the same host should authenticate without a prompt. All traffic after the handshake is encrypted.

---

## 1. macOS

**Build/first run**
1. Open `AirBridge.xcodeproj` in Xcode with the team signed in and build **AirBridge**. The first build
   registers the new App Group `group.com.shazan.AirBridge` in your developer account (needed by the share
   extension). Command-line unsigned builds work for everything except the share extension.
2. Quit the old AirBridge first (same bundle id). Launch the new one; grant **Local Network**,
   **Accessibility** and **Notifications** when prompted. Accessibility no longer needs a relaunch — the tap
   installs within ~2 s of granting.
3. Menu bar → you should see ip:port, Devices, "Pair a new device" (QR), "Connect to host…",
   "Display Arrangement…", "Clipboard Filters…".

**Checks**
- (rev 2) Pair a client by numeric comparison: a floating panel "<name> (<platform>) wants to pair — 483 920 —
  Accept / Reject" appears without stealing focus (also listed in the menu). Accept only if the client shows the
  same digits; Reject → client gets `pair_fail rejected`; no answer for 60 s → `timeout`. Send `clip` and a file;
  the file lands in `~/Downloads/AirBridge Transfers` and is on the pasteboard. Reconnect without pairing again.
- QR pairing (`mode:"qr"`) still works; five failed proofs → locked for 60 s and the QR secret rotates.
- Connect to host… → Pair on a Windows host → both screens show the same 6 digits → Confirm on the Mac, Accept
  on the PC.
- Everything after `auth_ok`/`pair_ok` is inside `0x02` envelopes (check with Wireshark: only `hello` and the
  handshake are readable).
- Control status: while controlling/controlled, the menu shows a green banner ("Controlling Pixel 9 · …").
- "Allow screen mirroring…" appears until Screen Recording is granted; relaunch afterwards — `hello.caps` then
  includes `mirror_source`.
- Finder → Share → AirBridgeShare → pick a device (needs the signed build).
- Display Arrangement shows every display; drag the peer tile to an edge.
- Universal control to the phone (once Android is installed): push the cursor through the edge; type with
  Shift/Cmd; scroll; volume keys; push back through the phone's edge; also **triple-Esc** or
  **Ctrl+Option+Cmd+Esc** recalls the cursor; disconnecting the phone recalls it.
- Hold Shift while crossing and release it on the phone — nothing stuck on either side.
- Phone trackpad: tap, double-tap, two-finger tap (right click), drag, scroll, three-finger swipes
  (Mission Control / App Exposé / desktops), keyboard incl. sticky modifiers and the Caps Lock LED.
- Dev hook (tests only, never in normal use): `open --env AIRBRIDGE_DEBUG_AUTO_ACCEPT=1 AirBridge.app` auto-accepts
  confirm-mode pairings on both sides (host Accept and client Confirm) without any prompt.

- Universal control to the phone: **push through** the edge (keep pushing ~100 pt within half a second; the
  4 pt edge indicator charges and flashes on capture) — merely touching the edge must not capture; Easy/Normal/Firm
  and "also hold ⌥" live in Display Arrangement. Triple-Esc or Ctrl+Option+Cmd+Esc recalls the cursor.
- Screen mode on the phone: live Mac screen (needs Screen Recording granted + relaunch), tap/drag/scroll land where
  you touch; judge latency (< 100 ms on LAN is the goal).
- Two controllers: control the phone from the Mac while Windows also tries → the second sees "Being used by…"
  (`ctl_busy`); pushing a cursor in from the other desktop takes over; a second phone trackpadding the Mac gets
  `ctl_busy` while the first one is active.

**Not yet verified on macOS 26**: Mission Control / App Exposé via the Mission Control binary,
`lock` (private `SACLockScreenImmediate`, falls back to Ctrl+Cmd+Q), `keyboard_toggle`, cursor hiding from a
menu-bar app (private `SetsCursorInBackground`).

---

## 2. Android

**Build/first run**
1. `./gradlew :app:assembleDebug` (or Run from Android Studio). minSdk is now 26.
2. Old installs: the v1 saved URLs are ignored; you pair again. If Accessibility was already on, toggle the
   AirBridge service off/on once — its capabilities changed.
3. Grant notifications, overlay, camera (for QR), and turn on the Accessibility service from the app.

**Checks**
- Pair by **QR** (scan the Mac/PC menu) and by **confirm** (pick the host from Discovery; both devices show the same 6 digits; Confirm on the phone, Accept on the host).
- Kill and relaunch the Mac app: the phone reconnects by itself (it looks the Mac up by id, not by IP).
  Switch Wi-Fi networks: it reconnects without reopening the app.
- Clipboard: copy on the Mac → phone clipboard updates. Phone → Mac via the tile/bubble, via Share, and
  automatically after a Cmd+C issued from the Mac while controlling the phone.
- Files both ways, including a >100 MB file and a batch of 20 photos; check speed and that the
  "Sending/Receiving" notification clears.
- Direct Share: the paired devices appear as share targets in the system share sheet.
- Android↔Android: enable P2P visibility on both; pair with the shown digits; clipboard and a file.
- Boot the phone: the service starts (connectedDevice type, no crash on Android 15+). Leave it running >6 h.
- Target mode from the desktop: cursor appears at the matching edge; drag a list item; long-press;
  type into a text field from the desktop (letters, Backspace, arrows with Shift selecting, Enter).

---

## 3. Windows (first run has never happened — expect rough edges)

**Prerequisites**: Windows 10 2004+ / 11, .NET 8 SDK (any newer SDK can also build net8 targets; the .NET 8
Desktop runtime is needed to run), git. Visual Studio 2022 with the "MSIX Packaging Tools" component is only
needed for the installer (`AirBridge.Package`, which also needs your signing certificate).

**Unpackaged build (fastest for testing)**
```powershell
git clone <repo> AirBridge-Windows ; cd AirBridge-Windows ; git checkout v2-protocol
dotnet build AirBridge\AirBridge.csproj -c Debug -p:Platform=x64
.\AirBridge\bin\x64\Debug\net8.0-windows10.0.19041.0\AirBridge.exe
```
Allow the Defender firewall prompt for **private** networks. Logs: `%LOCALAPPDATA%\AirBridge\airbridge.log`;
identity/peers in `%LOCALAPPDATA%\AirBridge\`; layout in `layout.json` there.

**Checks**
- Tray shows the device list, a QR ("Pair from your phone") and the address; `netstat -ano | findstr 47632`
  shows it listening. Pair the phone by QR (no prompt on the PC). Pair the phone from its discovery list
  (mode `confirm`): the PC shows a topmost "<name> (Android) wants to pair — 483 920 — Accept / Reject" plus a
  balloon; the phone shows the same digits. Reject → phone sees "declined"; ignore for 60 s → `timeout`.
- Log line `Session ready (encrypted)` for every session; no `ECDH known-answer test FAILED` /
  `little-endian` warning at startup (if the latter appears, it is compensated, but report it).
- Clipboard both ways; drop files on the tray popup → phone; phone → `Downloads\AirBridge Transfers`.
- "Connect to a Mac or PC…" → pick the Mac → both screens show the same 6 digits → "Codes match" on the PC,
  Accept on the Mac. Restart the Mac app; Windows reconnects (auth, no prompt). Pasting the Mac's
  `airbridge://pair?...` link into the address box pairs in mode `qr`.
- While controlling / being controlled, the tray popup shows the status line set by the input half
  (`App.SetControlStatus`), and the controlled peer's row has a "Control" badge.
- Wi-Fi off/on: the QR address updates and connections come back.
- Universal control: Display Arrangement shows real monitors; push the cursor off the configured edge;
  **Ctrl+Alt+Esc** recalls it; keys aren't stuck after crossing with Shift held; `taskkill /f` while
  controlling, then relaunch → cursors restored.
- Mac → PC control: cursor appears at the entry edge; type; push back out.
- Unpackaged: right-click a file → "Send via AirBridge" with the app closed → app starts, send window opens.
- Packaged (MSIX): Share target works with the app closed. The Explorer context menu is **not** available
  packaged yet (needs an `IExplorerCommand` COM DLL; see `docs/` notes in the Windows repo).

**Test hook**: `set AIRBRIDGE_DEBUG_AUTO_ACCEPT=1` before launching makes the Windows *host* accept every
`confirm` pairing without showing the prompt (logged as a warning). Never ship with it set. Combine with
`AIRBRIDGE_DATA_DIR=<dir>` to run a second, isolated instance.

**Known-unverified Win32 pieces**: CNG `DeriveRawSecretAgreement` byte order (guarded by a startup
known-answer test), hook thread + watchdog, `INPUT` struct marshalling, swapped-mouse-button
compensation, touch-keyboard toggle, DPAPI store, QR rendering, `ToUnicodeEx` dead-key handling.

---

## 4. Cross-platform semantics to eyeball

- Scroll direction on each target with the controller's natural-scrolling on and off (values are "content
  motion"; targets never invert). Android trackpad has its own natural toggle.
- Double-click from the phone trackpad on Mac and Windows (arrives as `n:1` then `n:2`).
- `rel` placement with two monitors (fraction of the combined bounding box).
- Caps Lock LED on the phone keyboard follows the Mac/PC.
