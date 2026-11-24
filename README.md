# AirBridge

**Apple's ecosystem, open to every device.** On Apple devices, copying on one and pasting on another, sending files, sharing one keyboard and mouse, taking calls and mirroring the phone all just work, but only between Apple devices. AirBridge makes the same things work across Android, Mac and Windows. It is open source, runs on your own network, and needs no account and no cloud.

| On Apple devices | With AirBridge |
|---|---|
| AirDrop | Send files between Android, Mac and Windows |
| Universal Clipboard | One clipboard across all of them: text, pictures and files |
| Universal Control | One keyboard and mouse for all of them |
| iPhone Mirroring | Your phone's screen on your Mac or PC, and your Mac's or PC's screen on your phone |
| Calls and notifications on the Mac | Your phone's calls and notifications on your Mac or PC |
| Devices in the Batteries widget | Your phone's and PC's battery in the Mac's Notification Center |
| - | **Control anything from anything.** Your Mac or PC keyboard and mouse can drive another computer or an Android phone, and your phone doubles as a trackpad and keyboard for any computer or another Android phone |
| - | **AirWeb: share with anyone, even with no app.** Show a QR code and anyone with a browser can drop files into one shared pool. No install, no account |
| - | **Mix any devices on one layout.** A Mac, a PC and a phone side by side, in whatever order you like |
| - | **Drag files across screens.** Pick up files on your Mac or PC and carry them over the edge onto another computer or your Android phone |
| - | **No Apple ID. No iCloud. No sign-in.** Everything stays on your own network |

AirBridge is an independent project and is not affiliated with Apple. The Apple names above are used only to say what each feature is like.

[Website](https://shazanzaidi.com/airbridge/) · [Releases](https://github.com/ShazanZaidii/AirBridge/releases) · [Protocol](docs/protocol.md) · [Report a problem](https://github.com/ShazanZaidii/AirBridge/issues)

| | Where | Status | Needs |
|---|---|---|---|
| Android | `android/` | Released (see Releases) | Android 8.0 or newer |
| macOS | `macOS/` | Released (see Releases) | A Mac with Apple silicon, macOS 26.1 or newer |
| Windows | `windows/` | Released, still new (see Releases) | Windows 10 (version 2004) or Windows 11, 64-bit |
| Linux | `linux/` | Not started | |
| iOS | `iOS/` | Not started | |

## What it does

**Clipboard.** Copy text, a picture or files on one device and paste them on another. Between computers it is automatic. Android only lets an app read the clipboard while it is in front, so on the phone AirBridge sends your copy when it can, and gives you a quick settings tile and a floating bubble for the times it can't. Text can be up to 512 KiB, a picture up to 25 MB, and files up to 20 at a time and 100 MB in total. Folders go through the normal file transfer instead. You can set filters for text that should never be sent.

**Files.** Send one file or a hundred to a paired device, whatever their size. Big files are streamed in pieces instead of being loaded into memory. With a mouse you can also drag files across the edge of your screen onto another device.

**One keyboard and mouse for all of them.** Push the cursor off the edge of your Mac or PC and it carries on, on your other computer or on your Android phone. It is the same idea as Apple's Universal Control, across Mac, Windows and Android. You arrange the screens in the Displays window. Only one device is in control at a time, and control loops are refused.

**Phone as a trackpad and keyboard.** Use your Android phone to move the pointer and type on a Mac, a PC or another Android phone.

**Phone calls and notifications on your computer.** Both are off until you turn them on. Notifications can be dismissed, replied to, or opened on the phone from the computer. Calls show up on the computer, where you can answer, decline or end them. Bluetooth is used to tell which computer the phone is next to.

**Screen mirroring.** Watch your phone's screen on your Mac and control it from there, or show your Mac's or PC's screen on your phone. On Windows, showing the PC's screen on a phone is new and still being tested. The Mac also has an optional "Pro mirroring" mode that uses Android's wireless debugging.

**AirWeb, for devices that don't have AirBridge.** Start AirWeb on your phone, Mac or PC and it shows a QR code. Anyone on the same Wi-Fi opens the link in a browser and uploads or downloads files through one shared pool. No app needed on their side.

**Smaller things.**
- The battery of your phone and your Windows PC shows in the Mac's Notification Center, in the Batteries widget, each with its own icon.
- The Android app is translated into 10 languages: English, Hindi, Bengali, Gujarati, Kannada, Marathi, Tamil, Telugu, German and French.
- The remote-control accessibility services turn themselves off while a banking or payment app is in front, so they are never active during a payment.
- Devices are remembered by a hardware fingerprint, so reinstalling an app doesn't leave a dead duplicate behind.

## Privacy and security

- AirBridge has no server and collects nothing. Your data goes from one of your devices to another over your own network.
- Devices find each other on the local network (mDNS) and are paired once, by scanning a QR code or by confirming matching digits on both screens.
- After pairing, everything is encrypted with keys agreed through an ECDH key exchange (AES-256-GCM). The details are in [docs/protocol.md](docs/protocol.md).
- It has not had an outside security audit. The code and the protocol are open so that anyone can check them.

## Install

The [latest release](https://github.com/ShazanZaidii/AirBridge/releases/latest) has the Android app (an APK), the Mac app and the Windows app. To build it yourself:

- **Android:** open `android/` in Android Studio, or run `cd android && ./gradlew assembleDebug`.
- **macOS:** open `macOS/AirBridge.xcodeproj` in Xcode and run.
- **Windows:** on Windows with the .NET 8 SDK, run `dotnet build windows/AirBridge/AirBridge.csproj -r win-x64 -p:Platform=x64`.

To connect two devices, put them on the same Wi-Fi and open AirBridge on both. They appear in each other's list. Pick one, then scan the QR code or confirm that both screens show the same digits.

## Questions people ask

**Is there an AirDrop alternative for Android and Mac?**
AirBridge does what AirDrop does between Android, macOS and Windows, over your local network, and goes further: it also shares the clipboard, shares one keyboard and mouse, shows your phone's calls and mirrors screens.

**How do I copy on my Android phone and paste on my Mac?**
Install AirBridge on both, pair them once, and copy as usual. The Mac receives the text, picture or files. On Android the system limits background clipboard access, so if a copy doesn't arrive on its own, tap the floating bubble or the quick settings tile.

**Can I copy a picture or a file on one device and paste it on another?**
Yes, between Android, Mac and Windows. A copied picture arrives as a picture you can paste into any app, and copied files arrive as files you can paste into a folder or a chat. The limits are 25 MB for a picture and 100 MB or 20 files for files. Bigger things go through the normal file transfer.

**Can I use my Mac's keyboard and mouse on my Android phone, or on a Windows PC?**
Yes. Move the cursor past the edge of your Mac screen and it continues on the other device, and the same works from a Windows PC. You can also go the other way and use the phone as a trackpad and keyboard for any computer.

**Can I get my phone's calls and notifications on my Mac or Windows PC?**
Yes, once you turn it on in the Android app. You can reply to messages and answer, decline or end calls from the computer.

**Can I see my Android screen on my Mac?**
Yes. AirBridge mirrors the phone to a Mac window and lets you control it. The Mac can also mirror its own screen to the phone, and so can a Windows PC (that part is new on Windows).

**Do I need an account, internet access or a cloud service?**
No. Devices talk directly to each other on the same local network. Some public or guest Wi-Fi networks stop devices from seeing each other. A phone hotspot is a way around that.

**Is it safe to use?**
Pairing is explicit, and all traffic between paired devices is encrypted. The apps have no server and collect no data. There has been no outside audit yet.

**Which versions do I need?**
Android 8.0 or newer. For the Mac source, macOS 26.1 or newer. For Windows, Windows 10 version 2004 or Windows 11.

**Does it work on Windows, Linux and iOS?**
The Windows source is in this repo and has no release yet. Linux and iOS are not started. iOS is uncertain, because the app relies on permissions that iOS restricts.

**Will remote control interfere with my banking apps?**
AirBridge switches its accessibility services off while a banking or payment app is open, so they never run in front of a payment.

**Is it free and open source?**
Yes. It is licensed under the GNU AGPL v3.

## How it compares

- **AirDrop and Universal Control** are made for Apple devices, with little or no Android support. AirBridge connects Android, Mac and Windows.
- **Phone Link** connects an Android phone to a Windows PC. AirBridge covers the Mac as well.
- **KDE Connect** and **LocalSend** are open-source projects with a similar spirit. AirBridge puts its effort into the Mac and Windows experience: crossing the screen edge with the mouse, dragging files between devices, mirroring, and the Mac battery widget.

## How fast it is

Sending files between a Mac and an Android phone, in MB/s (1 MB = 1,000,000 bytes). Measured on a MacBook Pro (M1 Pro, 2021)
and a vivo V2404 over the phone's own 5 GHz hotspot (Wi-Fi 6, 80 MHz, 1200 Mbps link), with files of 300 MB to 1 GB.
Everything AirBridge sends is encrypted.

| | Mac → Android | Android → Mac |
|---|---|---|
| AirBridge 3.1 | 10–11 | 39–41 |
| **AirBridge 3.2** | **60–87** | **73–78** |
| The Wi-Fi link itself (raw TCP, no app, nothing saved) | 72–87 | 75–76 |

AirBridge 3.2 now moves files as fast as the Wi-Fi link carries them. The spread comes from the link: Wi-Fi keeps
renegotiating its rate (it moved between 576 and 1200 Mbps within seconds in these tests). A phone that is nearly full or
hot writes more slowly: with this phone 95% full, 1 GB files arrived at 38–48 MB/s. The Mac in these tests has 2×2 Wi-Fi 6,
so about 90 MB/s is as far as this pair goes; newer Macs and phones with 160 MHz Wi-Fi 6E or Wi-Fi 7 can go further.

**Compared with other apps.** Numbers for the other apps come from public measurements, picked to be as close to the
setup above as possible (Wi-Fi 6, 5 GHz, a phone on the same network). They are not from the same devices, so treat them
as a guide. "Works, untested" means the transfer is possible but there is no measurement yet.

| From ↔ to | AirBridge | AirDrop | LocalSend | KDE Connect | Quick Share |
|---|---|---|---|---|---|
| Mac → Android | **60–87** | Not possible | ~12 ¹ | Works, untested | No Mac app |
| Android → Mac | **73–78** | Not possible | ~23 ¹ | Works, untested | No Mac app |
| Windows ↔ Android | Works, untested | Not possible | 7–21 encrypted, ~100 unencrypted ² | 38–41 ³ | Works, untested |
| Mac ↔ Windows | Works, untested | Not possible | ~60 ⁴ | Works, untested | No Mac app |
| Mac ↔ Mac | Works, untested | ~40 ⁵ | Works, untested | Works, untested | Not possible |
| iPhone ↔ anything | Not yet (no iOS app) | Apple devices only | Works, untested | Works, untested | Not possible |

Where AirBridge comes out behind:
- **LocalSend with encryption turned off (~100 MB/s, Windows ↔ Android)** sends files in the clear, over a PC wired to its
  router. AirBridge always encrypts, and its numbers above are capped by this Mac's Wi-Fi radio, not by the encryption.
  With LocalSend's encryption on (its default), the same reports show 7–21 MB/s.
- **Windows, Mac ↔ Mac and iPhone**: AirBridge has no measurements there yet, and no iOS app.

¹ LocalSend issue [#384](https://github.com/localsend/localsend/issues/384), a user on a 5 GHz phone hotspot (Linux ↔ Android, LocalSend 0.12, 2023).
² Same issue: unencrypted 100–103 MB/s (users, 2025); encrypted at most 21 MB/s and usually 7–10 (LocalSend 1.18.2, Windows ↔ iOS, October 2026).
³ TwinDesk issue [#143](https://github.com/InukaAsith/TwinDesk/issues/143): KDE Connect at 300–330 Mbps between a Windows PC and a Qualcomm Wi-Fi 6 phone on the same router (1201 Mbps link), measured by the issue's author (2026).
⁴ LocalSend's maintainer in issue [#384](https://github.com/localsend/localsend/issues/384): Windows ↔ macOS at 60 MB/s with HTTPS (2023).
⁵ [Eclectic Light](https://eclecticlight.co/2022/05/09/whats-the-fastest-way-to-transfer-files-with-m1-macs/): "a steady 40 MB/s between most recent Mac models, including my M1s" (2022).

## How it works

Each app announces itself on the local network as `_airbridge._tcp`. Paired devices connect to each other over WebSocket and exchange small JSON messages, plus binary chunks for file data. Every message after the handshake travels inside an encrypted envelope. All three apps follow the same written specification, [docs/protocol.md](docs/protocol.md), which is also the place to start if you want to build the Linux or iOS app. [docs/airweb.md](docs/airweb.md) describes AirWeb.

## What is in this repo

- `android/` the Android app (Kotlin, Jetpack Compose)
- `macOS/` the Mac app (Swift, SwiftUI)
- `windows/` the Windows app (C#, WPF)
- `linux/` and `iOS/` not started yet
- `docs/` the protocol every app speaks, the AirWeb page description and the shared test files

Bug reports, ideas and pull requests are welcome in [Issues](https://github.com/ShazanZaidii/AirBridge/issues). AirBridge is licensed under the [GNU AGPL v3](LICENSE.md).

## A note from me

(writing this at 2am on August 14th)

AirBridge is a cross platform tool that allows you to:
- Discover AirBridge devices nearby (should be on same network, Supported Devices - Android, MacOS, Windows)
- Once connected, have shared clipboard,
- Send media in one click like AirDrop,
- Control your mac from Android or Android from Mac 


Also, NO DATA IS COLLECTED. The source code is completely opensource and you are free to inspect. There is literally no server/ DB connected. 


The Windows version is here now, in the `windows/` folder. It sat on my drive in a different city for a while, which is why it took so long. The build will show up in the releases tab once I publish it :) 

Also yes, the Linux support is coming soon. But i cant promise about the iOS support as the app needs several "Trust me bro" permissions.. haha, such as - Accessibility to control device remotely, Clipboard access to actually share clipboard data, Draw over other apps and all. But yeah i will give it a shot.

The android and macOS release can be found in the releases tab.

And if you are asking for a tutorial, please try to discover the app yourself. 😤 (I will attach once i have the windows and linux release with me..) 
