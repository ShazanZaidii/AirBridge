import AppKit
import SwiftUI

/// The call's "Audio" menu, built like the macOS sound menu / FaceTime's: a real NSMenu with a "This Mac" section
/// (Microphone and Speaker, each device with a checkmark on the one in use) and a "Phone" section (earpiece,
/// speaker, wired and Bluetooth headsets by name, from the phone). Talking from the Mac needs the Bluetooth
/// hands-free link (`CallAudio`); while it's unavailable the Mac rows are greyed out with the reason and a
/// "Set up Talk from your Mac…" item.
@MainActor final class AudioRouteMenu: NSObject {
    private let ui: CallUI
    private var handlers: [() -> Void] = []

    init(ui: CallUI) { self.ui = ui }

    func make() -> NSMenu {
        handlers = []
        let ca = CallAudio.shared
        let menu = NSMenu(title: "Audio")
        menu.autoenablesItems = false
        let onMac = ca.route == .mac
        var macReason: String?
        if case .unavailable(let r) = ca.route { macReason = r }

        // This Mac
        menu.addItem(.sectionHeader(title: "This Mac"))
        if ca.macInputs.isEmpty && ca.macOutputs.isEmpty {
            let it = item("Mac Microphone and Speakers", symbol: "laptopcomputer", checked: onMac, enabled: macReason == nil) { ca.useMac() }
            menu.addItem(it)
        } else {
            menu.addItem(subheader("Microphone"))
            for d in ca.macInputs {
                menu.addItem(item(d.name, symbol: d.symbol, checked: onMac && d.id == ca.selectedMacInput, enabled: macReason == nil) {
                    if CallAudio.shared.route != .mac { CallAudio.shared.useMac() }
                    CallAudio.shared.selectMacInput(d.id)
                })
            }
            menu.addItem(subheader("Speaker"))
            for d in ca.macOutputs {
                menu.addItem(item(d.name, symbol: d.symbol, checked: onMac && d.id == ca.selectedMacOutput, enabled: macReason == nil) {
                    if CallAudio.shared.route != .mac { CallAudio.shared.useMac() }
                    CallAudio.shared.selectMacOutput(d.id)
                })
            }
        }
        if let r = macReason {
            menu.addItem(note(r))
            menu.addItem(item("Set up Talk from your Mac…", symbol: nil, checked: false, enabled: true) { CallAudio.shared.openSetup() })
        } else if let n = ca.note {
            menu.addItem(note(n))
        }

        // Phone
        menu.addItem(.separator())
        menu.addItem(.sectionHeader(title: "Phone"))
        let routes = RouteText.menuRoutes(ui.call.routes)
        if routes.isEmpty {
            // An older AirBridge on the phone: only earpiece / speaker.
            let speaker = ui.call.speaker
            menu.addItem(item(ui.phoneName, symbol: "iphone", checked: !onMac && !speaker, enabled: true) { [ui] in ui.perform(.audioPhone) })
            if ui.call.can.contains("speaker") {
                menu.addItem(item("Speaker", symbol: "speaker.wave.2", checked: !onMac && speaker, enabled: true) { [ui] in ui.perform(.audioSpeaker) })
            }
        } else {
            for r in routes {
                menu.addItem(item(RouteText.name(r, phoneName: ui.phoneName), symbol: RouteText.symbol(r),
                                  checked: !onMac && r.id == ui.call.route, enabled: ui.call.can.contains("route")) {
                    CallAudio.shared.selectPhoneRoute(r.id)
                })
            }
        }
        return menu
    }

    /// Shows the menu under `view` (the Audio button).
    func popUp(below view: NSView) {
        let m = make()
        m.popUp(positioning: nil, at: NSPoint(x: 0, y: view.isFlipped ? view.bounds.maxY + 6 : -6), in: view)
    }

    // MARK: Items

    private func item(_ title: String, symbol: String?, checked: Bool, enabled: Bool, _ action: @escaping () -> Void) -> NSMenuItem {
        let it = NSMenuItem(title: title, action: #selector(fire(_:)), keyEquivalent: "")
        it.target = self
        it.tag = handlers.count
        handlers.append(action)
        it.state = checked ? .on : .off
        it.isEnabled = enabled
        if let symbol, let img = NSImage(systemSymbolName: symbol, accessibilityDescription: nil) { it.image = img }
        return it
    }

    private func subheader(_ title: String) -> NSMenuItem {
        let it = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        it.attributedTitle = NSAttributedString(string: title, attributes: [
            .font: NSFont.menuFont(ofSize: NSFont.smallSystemFontSize),
            .foregroundColor: NSColor.secondaryLabelColor,
        ])
        it.isEnabled = false
        return it
    }

    /// A grey explanation, wrapped to a few short lines (menu items don't wrap by themselves).
    private func note(_ raw: String) -> NSMenuItem {
        var lines: [String] = [], line = ""
        for w in raw.split(separator: " ") {
            if !line.isEmpty && line.count + w.count + 1 > 38 { lines.append(line); line = "" }
            line += (line.isEmpty ? "" : " ") + w
        }
        if !line.isEmpty { lines.append(line) }
        let text = lines.joined(separator: "\n")
        let it = NSMenuItem(title: text, action: nil, keyEquivalent: "")
        let p = NSMutableParagraphStyle()
        p.lineBreakMode = .byWordWrapping
        it.attributedTitle = NSAttributedString(string: text, attributes: [
            .font: NSFont.menuFont(ofSize: NSFont.smallSystemFontSize),
            .foregroundColor: NSColor.secondaryLabelColor,
            .paragraphStyle: p,
        ])
        it.isEnabled = false
        return it
    }

    @objc private func fire(_ sender: NSMenuItem) {
        guard sender.tag >= 0, sender.tag < handlers.count else { return }
        handlers[sender.tag]()
    }
}

/// An invisible view behind the Audio button: the menu needs an NSView to open under.
struct MenuAnchor: NSViewRepresentable {
    let onCreate: (NSView) -> Void
    func makeNSView(context: Context) -> NSView {
        let v = NSView()
        DispatchQueue.main.async { onCreate(v) }
        return v
    }
    func updateNSView(_ nsView: NSView, context: Context) {}
}
