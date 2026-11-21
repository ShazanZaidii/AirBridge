import AppKit
import SwiftUI

// Renders the call UI to PNG (see run.sh): the banner, the call window with its Audio menu open, and the pill.
typealias CreateImageFn = @convention(c) (CGRect, UInt32, UInt32, UInt32) -> Unmanaged<CGImage>?
let cg = dlopen("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", RTLD_NOW)
let createImage = unsafeBitCast(dlsym(cg, "CGWindowListCreateImage"), to: CreateImageFn.self)

let outDir = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "out"
try? FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)

let app = NSApplication.shared
app.setActivationPolicy(.accessory)

let routes = [
    PhoneRoute(id: "phone:earpiece", kind: "earpiece", name: nil, computer: false),
    PhoneRoute(id: "phone:speaker", kind: "speaker", name: nil, computer: false),
    PhoneRoute(id: "phone:bt:AA:01", kind: "bt", name: "Galaxy Buds2", computer: false),
    PhoneRoute(id: "phone:bt:AA:02", kind: "bt", name: "MacBook Pro", computer: true),
]

func call(_ state: PhoneCallState, name: String?, number: String?, label: String?, muted: Bool = false, speaker: Bool = false,
          route: String? = "phone:earpiece") -> PhoneCall {
    PhoneCall(phoneId: "P", id: "c1", state: state, name: name, number: number,
              since: state == .active ? Date().addingTimeInterval(-42) : nil, muted: muted, speaker: speaker,
              can: state == .ringing ? ["answer", "decline"] : ["end", "mute", "speaker", "route"], label: label,
              routes: state == .ringing ? [] : routes, route: route)
}

let photo = NSImage(contentsOfFile: "/Library/User Pictures/Flowers/Sunflower.heic")
let wallpapers = ["/System/Library/Desktop Pictures/.thumbnails/Big Sur Graphic Light.heic",
                  "/System/Library/Desktop Pictures/.thumbnails/Big Sur Graphic Dark.heic"]

@MainActor func backdrop(_ rect: NSRect, dark: Bool) -> NSWindow {
    let w = NSWindow(contentRect: rect, styleMask: [.borderless], backing: .buffered, defer: false)
    let iv = NSImageView()
    iv.image = NSImage(contentsOfFile: wallpapers[dark ? 1 : 0])
    iv.imageScaling = .scaleAxesIndependently
    w.contentView = iv
    w.level = .statusBar
    w.orderFrontRegardless()
    return w
}

@MainActor func capture(topWindowID: UInt32, rect: NSRect, to file: String) {
    let screenH = NSScreen.screens[0].frame.height
    let r = CGRect(x: rect.minX, y: screenH - rect.maxY, width: rect.width, height: rect.height)
    guard let img = createImage(r, (1 << 2) | (1 << 3), topWindowID, 1 << 3)?.takeRetainedValue() else {
        print("capture failed: \(file)"); return
    }
    try? NSBitmapImageRep(cgImage: img).representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: file))
    print("wrote \(file)")
}

@MainActor func area(_ size: NSSize) -> NSRect {
    let v = NSScreen.screens[0].visibleFrame
    return NSRect(x: v.minX + 20, y: v.minY + 20, width: size.width, height: size.height)
}

@MainActor func render(_ name: String, dark: Bool, make: (CallUI) -> NSWindow, ui: CallUI) async {
    NSApp.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
    let a = area(NSSize(width: 520, height: name.hasPrefix("window") ? 440 : 300))
    let back = backdrop(a, dark: dark)
    let w = make(ui)
    w.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
    w.level = NSWindow.Level(rawValue: NSWindow.Level.statusBar.rawValue + 1)
    w.setFrameOrigin(NSPoint(x: a.midX - w.frame.width / 2, y: a.midY - w.frame.height / 2))
    w.orderFrontRegardless()
    try? await Task.sleep(for: .seconds(0.9))
    capture(topWindowID: UInt32(w.windowNumber), rect: a, to: "\(outDir)/\(name)-\(dark ? "dark" : "light").png")
    w.orderOut(nil); back.orderOut(nil)
}

/// The call window with its Audio menu open: the menu is a real NSMenu, captured while it tracks.
@MainActor func renderMenu(_ name: String, dark: Bool, ui: CallUI) async {
    NSApp.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
    let a = area(NSSize(width: 560, height: 760))
    let back = backdrop(a, dark: dark)
    let w = InCallWindow(ui: ui)
    w.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
    w.level = NSWindow.Level(rawValue: NSWindow.Level.statusBar.rawValue + 1)
    w.setFrameOrigin(NSPoint(x: a.minX + 40, y: a.maxY - w.frame.height - 30))
    w.orderFrontRegardless()
    try? await Task.sleep(for: .seconds(0.8))
    // Where the Audio button's anchor is in the app: the middle circle, 46 pt above the bottom (flipped view).
    guard let host = (w.contentView as? NSGlassEffectView)?.contentView else { return }
    let anchor = NSView(frame: NSRect(x: host.bounds.midX - 25, y: host.bounds.height - 96, width: 50, height: 50))
    host.addSubview(anchor)
    let menu = AudioRouteMenu(ui: ui)
    let m = menu.make()
    m.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
    let file = "\(outDir)/\(name)-\(dark ? "dark" : "light").png"
    let t = Timer(timeInterval: 0.8, repeats: false) { _ in
        MainActor.assumeIsolated {
            let pid = ProcessInfo.processInfo.processIdentifier
            let infos = (CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as? [[String: Any]]) ?? []
            let menuWin = infos.filter { ($0[kCGWindowOwnerPID as String] as? Int32) == pid && (($0[kCGWindowLayer as String] as? Int) ?? 0) >= 101 }
                .compactMap { $0[kCGWindowNumber as String] as? UInt32 }.first
            capture(topWindowID: menuWin ?? UInt32(w.windowNumber), rect: a, to: file)
            m.cancelTracking()
        }
    }
    RunLoop.main.add(t, forMode: .common)
    m.popUp(positioning: nil, at: NSPoint(x: 0, y: anchor.isFlipped ? anchor.bounds.maxY + 6 : -6), in: anchor)
    w.orderOut(nil); back.orderOut(nil)
}

Task { @MainActor in
    for dark in [false, true] {
        CallAudio.shared.report(route: .phone)
        CallAudio.shared.report(macInputs: [
            .init(id: "in-mbp", name: "MacBook Pro Microphone", symbol: "laptopcomputer"),
            .init(id: "in-airpods", name: "AirPods Pro", symbol: "airpodspro"),
        ], selected: "in-mbp")
        CallAudio.shared.report(macOutputs: [
            .init(id: "out-mbp", name: "MacBook Pro Speakers", symbol: "laptopcomputer"),
            .init(id: "out-airpods", name: "AirPods Pro", symbol: "airpodspro"),
            .init(id: "out-studio", name: "Studio Display Speakers", symbol: "display"),
        ], selected: "out-mbp")

        let ring = CallUI(call: call(.ringing, name: "Asha Kapoor", number: "+91 98765 43210", label: "Mobile"), phoneName: "V2404")
        ring.photo = photo
        await render("banner", dark: dark, make: { CallBannerPanel(ui: $0) }, ui: ring)

        let active = CallUI(call: call(.active, name: "Asha Kapoor", number: "+91 98765 43210", label: "Mobile"), phoneName: "V2404")
        active.photo = photo
        await renderMenu("window-audio-menu", dark: dark, ui: active)

        let buds = CallUI(call: call(.active, name: "Ravi Menon", number: nil, label: nil, muted: true, route: "phone:bt:AA:01"), phoneName: "V2404")
        await render("window-headset-muted", dark: dark, make: { InCallWindow(ui: $0) }, ui: buds)

        let pill = CallUI(call: call(.active, name: "Asha Kapoor", number: nil, label: "Mobile", muted: true), phoneName: "V2404")
        pill.photo = photo
        await render("pill", dark: dark, make: { CallPillPanel(ui: $0) }, ui: pill)

        // Mac route unavailable: the menu explains and offers the setup.
        CallAudio.shared.report(route: .unavailable(reason: "Pair your phone with this Mac over Bluetooth to talk from here."))
        CallAudio.shared.report(macInputs: [], selected: nil)
        CallAudio.shared.report(macOutputs: [], selected: nil)
        let un = CallUI(call: call(.active, name: "Asha Kapoor", number: nil, label: "Mobile", speaker: true, route: "phone:speaker"), phoneName: "V2404")
        await renderMenu("window-audio-menu-unavailable", dark: dark, ui: un)
    }
    exit(0)
}
app.run()
