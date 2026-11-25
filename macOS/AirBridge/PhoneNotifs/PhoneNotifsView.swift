import AppKit
import SwiftUI

/// The phone notifications, as a phone-sized panel that drops down from AirBridge's menu bar icon (one at a time).
/// It closes as soon as it loses focus: a click anywhere else, Esc, or the menu bar icon again. Nothing to close by hand.
@MainActor final class PhoneNotifsWindow: NSObject, NSWindowDelegate {
    static let shared = PhoneNotifsWindow()
    private var panel: NotifsPanel?
    private var clickMonitor: Any?
    private var swipeMonitor: Any?
    private let tracker = SwipeAway()
    private var swipeRow: String?
    /// Each row's frame in the panel (top-left origin), from the list.
    var rowFrames: [String: CGRect] = [:]
    /// Pixel 9 Pro in points (412 × 915 dp): the list reads like the phone's own shade. Capped to the screen.
    static let phoneSize = NSSize(width: 412, height: 915)
    static let corner: CGFloat = 34

    var isShown: Bool { panel?.isVisible == true }

    func toggle() { isShown ? close() : show() }

    func show() {
        PhoneNotifCenter.shared.unseen = 0
        PhoneNotifCenter.shared.checkPermission()
        AppWindows.closeMenuPanel() // the menu bar panel would sit on top of it
        let p = panel ?? makePanel()
        panel = p
        p.setFrame(Self.frame(), display: false)
        if !p.isVisible {
            p.alphaValue = 0
            NSApp.activate()
            p.makeKeyAndOrderFront(nil)
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = 0.16
                p.animator().alphaValue = 1
            }
        } else {
            NSApp.activate()
            p.makeKeyAndOrderFront(nil)
        }
        if swipeMonitor == nil {
            swipeMonitor = NSEvent.addLocalMonitorForEvents(matching: [.scrollWheel]) { e in
                MainActor.assumeIsolated { PhoneNotifsWindow.shared.scroll(e) }
            }
        }
        // Clicks in other apps don't always take key status from an app that wasn't active before: watch for them too.
        if clickMonitor == nil {
            clickMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown, .otherMouseDown]) { _ in
                MainActor.assumeIsolated { PhoneNotifsWindow.shared.close() }
            }
        }
    }

    func close() {
        if let m = clickMonitor { NSEvent.removeMonitor(m); clickMonitor = nil }
        if let m = swipeMonitor { NSEvent.removeMonitor(m); swipeMonitor = nil }
        swipeRow = nil
        PhoneNotifCenter.shared.rowSwipe = nil
        guard let p = panel, p.isVisible else { return }
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.12
            p.animator().alphaValue = 0
        }, completionHandler: {
            MainActor.assumeIsolated { p.orderOut(nil); p.alphaValue = 1 }
        })
    }

    private func makePanel() -> NotifsPanel {
        let root = PhoneNotifsView(center: PhoneNotifCenter.shared, model: AppModel.shared, topInset: 22)
            .clipShape(RoundedRectangle(cornerRadius: Self.corner, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Self.corner, style: .continuous).strokeBorder(Ink.hairline, lineWidth: 1))
        // A plain container that follows the panel, so SwiftUI never resizes the panel to the list's height.
        let hosting = NSHostingView(rootView: root)
        hosting.sizingOptions = []
        let size = Self.phoneSize
        let container = NSView(frame: NSRect(origin: .zero, size: size))
        hosting.frame = container.bounds
        hosting.autoresizingMask = [.width, .height]
        container.addSubview(hosting)
        let p = NotifsPanel(contentRect: NSRect(origin: .zero, size: size), styleMask: [.borderless],
                            backing: .buffered, defer: false)
        p.isOpaque = false
        p.backgroundColor = .clear
        p.hasShadow = true
        p.level = .popUpMenu
        p.isReleasedWhenClosed = false
        p.hidesOnDeactivate = false
        p.animationBehavior = .none
        p.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary, .ignoresCycle, .transient]
        p.title = "Notifications"
        p.contentView = container
        p.delegate = self
        p.onEscape = { PhoneNotifsWindow.shared.close() }
        return p
    }

    /// Under the menu bar, its right edge lined up with AirBridge's menu bar icon, on that icon's screen.
    static func frame() -> NSRect {
        let icon = NSApp.windows.first { String(describing: type(of: $0)).contains("StatusBar") && $0.isVisible }
        let screen = icon?.screen ?? NSScreen.main ?? NSScreen.screens[0]
        let vis = screen.visibleFrame
        let margin: CGFloat = 6
        let w = min(phoneSize.width, vis.width - 2 * margin)
        let h = min(phoneSize.height, vis.height - 2 * margin)
        var x = (icon.map { $0.frame.maxX + 12 } ?? vis.maxX - margin) - w
        x = min(max(x, vis.minX + margin), vis.maxX - w - margin)
        return NSRect(x: x, y: vis.maxY - h - margin, width: w, height: h)
    }

    // MARK: Swipe a notification away (two fingers sideways, like on the phone)

    private func row(at e: NSEvent) -> PhoneNotif? {
        guard let p = panel, let content = p.contentView else { return nil }
        let pt = CGPoint(x: e.locationInWindow.x, y: content.bounds.height - e.locationInWindow.y)
        let center = PhoneNotifCenter.shared
        guard let id = rowFrames.first(where: { $0.value.contains(pt) })?.key, let n = center.store.items[id] else { return nil }
        return n
    }

    /// Horizontal two-finger swipes move the row under the pointer; vertical ones scroll the list as usual.
    fileprivate func scroll(_ e: NSEvent) -> NSEvent? {
        guard let p = panel, e.window === p else { return e }
        if e.phase.contains(.began) { swipeRow = row(at: e)?.id }
        guard let id = swipeRow else { return e }
        let center = PhoneNotifCenter.shared
        switch tracker.handle(e) {
        case .ignored:
            return e
        case .moved(let dx):
            // One the phone won't let anyone clear stretches a little and no further, as on the phone.
            let locked = center.store.items[id].map { !$0.canClear } ?? false
            center.rowSwipe = .init(id: id, dx: locked ? max(-36, min(36, dx * 0.25)) : dx)
            return nil
        case .away where center.store.items[id].map({ !$0.canClear }) ?? false:
            swipeRow = nil
            withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) { center.rowSwipe = nil }
            if let n = center.store.items[id] { center.dismiss(n) } // says why it stays
            return nil
        case .away(let dx):
            swipeRow = nil
            withAnimation(.easeIn(duration: 0.16)) { center.rowSwipe = .init(id: id, dx: dx > 0 ? 460 : -460) }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.16) {
                MainActor.assumeIsolated {
                    // Cleared here and on the phone. One the phone refuses to clear comes back, with the reason.
                    if let n = center.store.items[id] { withAnimation(.easeOut(duration: 0.2)) { center.dismiss(n) } }
                    center.rowSwipe = nil
                }
            }
            return nil
        case .back:
            swipeRow = nil
            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { center.rowSwipe = nil }
            return nil
        }
    }

    func windowDidResignKey(_ notification: Notification) { close() }
    func windowDidBecomeKey(_ notification: Notification) { PhoneNotifCenter.shared.unseen = 0 }

    #if DEBUG
    func debugClose() { close() }
    #endif
}

/// Where each notification row sits in the panel, for two-finger swipes.
private struct NotifRowFramesKey: PreferenceKey {
    static let space = "notif-panel"
    static let defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) { value.merge(nextValue()) { $1 } }
}

/// Borderless, but takes the keyboard (replies are typed here) and closes on Esc.
final class NotifsPanel: NSPanel {
    var onEscape: (() -> Void)?
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
    override func cancelOperation(_ sender: Any?) { onEscape?() }
}

// MARK: - The list, in AirBridge's charcoal design language (Design/Charcoal.swift)

struct PhoneNotifsView: View {
    @ObservedObject var center: PhoneNotifCenter
    @ObservedObject var model: AppModel
    /// Debug snapshots: draw the list without its scroll view.
    var flat = false
    /// Space above the header (the drop-down panel has no title bar to clear).
    var topInset: CGFloat = 38
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The device picked at the top (nil = all), or nil when it isn't around any more.
    private var device: String? {
        guard let d = center.focusDevice, center.notifDevices.contains(where: { $0.id == d }) else { return nil }
        return d
    }
    private var groups: [PhoneNotifStore.Group] { center.store.groups(device: device) }
    /// The device's ongoing and silent ones (VPN, USB, music), listed under them like its shade does.
    private var statusGroups: [PhoneNotifStore.Group] { center.store.groups(status: true, device: device) }
    /// Cards name their device when more than one is on screen.
    private var multiplePhones: Bool { device == nil && Set(center.store.items.values.map(\.phoneId)).count > 1 }
    /// Like the phone's "Clear all": everything that can be cleared, silent ones included; ongoing ones stay.
    private var clearable: [PhoneNotifStore.Group] {
        (groups + statusGroups).filter { center.isConnected($0.phoneId) && $0.items.contains(where: \.canClear) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(.horizontal, 20)
                .padding(.top, topInset)
                .padding(.bottom, 14)
            if center.notifDevices.count > 1 {
                DevicePicker(center: center, selected: device)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 12)
            }
            notices
                .padding(.horizontal, 16)
            BackgroundSection(hud: TransferHUD.shared, board: CallPanels.shared.board)
                .padding(.horizontal, 16)
            if groups.isEmpty && statusGroups.isEmpty {
                VStack(spacing: 12) {
                    empty
                    BlockedSection(center: center)
                }
                .padding(16)
            } else if flat {
                list
            } else {
                ScrollView { list }
            }
        }
        // Pinned to the top: with little content SwiftUI otherwise centres it, and the header floated down the window.
        .coordinateSpace(.named(NotifRowFramesKey.space))
        .onPreferenceChange(NotifRowFramesKey.self) { f in MainActor.assumeIsolated { PhoneNotifsWindow.shared.rowFrames = f } }
        .frame(minWidth: 340, maxWidth: .infinity, minHeight: 320, maxHeight: .infinity, alignment: .top)
        // Under the (transparent) title bar, like the Screen Time page: one charcoal surface from the top edge, with the
        // header just below the window buttons.
        .ignoresSafeArea(.container, edges: .top)
        .background(Ink.page.ignoresSafeArea())
        .foregroundStyle(Ink.text)
    }

    private var list: some View {
        VStack(alignment: .leading, spacing: 0) {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        ForEach(groups) { g in
                            GroupCard(group: g, center: center, showPhone: multiplePhones)
                                .transition(.asymmetric(insertion: .move(edge: .top).combined(with: .opacity), removal: .opacity))
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 4)
                    .padding(.bottom, 16)
                    .animation(reduceMotion ? nil : .easeOut(duration: 0.22), value: center.store.items.keys.sorted())
        if !statusGroups.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Eyebrow(text: "Silent")
                    .padding(.leading, 4)
                    .help("Ongoing and silent notifications on the phone. They never pop up on this Mac.")
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(statusGroups) { g in GroupCard(group: g, center: center, showPhone: multiplePhones) }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
        }
        BlockedSection(center: center)
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
        }
    }

    private var header: some View {
        HStack(alignment: .lastTextBaseline) {
            VStack(alignment: .leading, spacing: 3) {
                Text("Notifications").font(.system(size: 22, weight: .semibold)).tracking(-0.2)
                Text(subtitle).font(.system(size: 12.5)).foregroundStyle(Ink.secondary)
            }
            Spacer()
            if !clearable.isEmpty {
                QuietButton(title: "Clear all") { clearable.forEach(center.dismissAll) }
                    .help("Clear every notification here and on the phone")
            }
        }
    }

    private var subtitle: String {
        if let d = device {
            let n = groups.reduce(0) { $0 + $1.items.count }
            return n == 0 ? center.phoneName(d) : "\(n) from \(center.phoneName(d))"
        }
        let n = center.store.count
        let phones = Set(center.store.items.values.map(\.phoneId)).map(center.phoneName).sorted()
        if n == 0 { return center.sourcePhones.isEmpty ? "No phone connected" : "From \(center.sourcePhones.map(\.name).joined(separator: ", "))" }
        return "\(n) from \(phones.joined(separator: ", "))"
    }

    /// Things that stop notifications from showing, each with the way to fix it.
    @ViewBuilder private var notices: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let problem = center.bannerProblem {
                Notice(symbol: "bell.slash", text: problem, action: ("Open Settings", { center.openNotificationSettings() }))
            }
            ForEach(center.sourcePhones) { p in
                if let st = center.states[p.id], !st.notifs {
                    Notice(symbol: "iphone.slash",
                           text: st.why == "no_access"
                               ? "Allow notification access for AirBridge on \(p.name) to see its notifications here."
                               : "Turn on “Show phone notifications on your computers” in AirBridge on \(p.name).",
                           action: nil)
                }
            }
            if !PhoneNotifSettings.showNotifs {
                Notice(symbol: "bell.slash", text: "Phone notifications are turned off in AirBridge Settings on this Mac.", action: nil)
            }
            if let err = center.lastError {
                Notice(symbol: "exclamationmark.circle", text: err, action: ("Dismiss", { center.lastError = nil }))
            }
        }
        .padding(.bottom, 8)
    }

    private var empty: some View {
        Panel(padding: 32) {
            VStack(spacing: 10) {
                Image(systemName: "bell").font(.system(size: 24, weight: .light)).foregroundStyle(Ink.secondary)
                Text("You're all caught up").font(.system(size: 15, weight: .semibold))
                Text(center.sourcePhones.isEmpty
                     ? "Connect your phone and turn on notifications in AirBridge on the phone."
                     : "New notifications from your phone show up here.")
                    .font(.system(size: 12.5)).foregroundStyle(Ink.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 280)
            }
            // Down to the same 16-point margin as the sides, so the window reads as one even frame.
            .frame(maxWidth: .infinity, minHeight: 200, maxHeight: .infinity)
        }
    }
}

/// All devices, or one: chips under the header when more than one device sends notifications.
private struct DevicePicker: View {
    @ObservedObject var center: PhoneNotifCenter
    let selected: String?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                chip("All devices", id: nil)
                ForEach(center.notifDevices, id: \.id) { d in chip(d.name, id: d.id) }
            }
        }
    }

    private func chip(_ title: String, id: String?) -> some View {
        let on = selected == id
        return Button { withAnimation(.easeOut(duration: 0.18)) { center.focusDevice = id } } label: {
            HStack(spacing: 5) {
                if let id, !center.isConnected(id) { Circle().fill(Ink.tertiary).frame(width: 5, height: 5) }
                Text(title).font(.system(size: 12, weight: on ? .semibold : .regular)).lineLimit(1)
            }
            .foregroundStyle(on ? Ink.onPill : Ink.secondary)
            .padding(.horizontal, 11).padding(.vertical, 6)
            .background(Capsule().fill(on ? Ink.pill : Ink.raised))
            .overlay(Capsule().strokeBorder(Ink.hairline, lineWidth: on ? 0 : 1))
        }
        .buttonStyle(.plain)
        .help(id.map { center.isConnected($0) ? "Only \(title)" : "\(title) (not connected)" } ?? "Every device together")
    }
}

/// Calls and transfer cards the user swiped away: still going, one click brings them back on screen.
private struct BackgroundSection: View {
    @ObservedObject var hud: TransferHUD
    @ObservedObject var board: CallBoardUI

    private var calls: [CallUI] { board.calls.filter(\.inBackground) }
    private var cards: [HUDCard] { hud.backgroundCards }

    var body: some View {
        if !calls.isEmpty || !cards.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                Eyebrow(text: "In the background")
                    .padding(.horizontal, 14).padding(.top, 12).padding(.bottom, 6)
                ForEach(calls) { ui in
                    Rectangle().fill(Ink.hairline).frame(height: 1)
                    BackgroundCallRow(ui: ui).padding(.horizontal, 14).padding(.vertical, 9)
                }
                ForEach(cards) { card in
                    Rectangle().fill(Ink.hairline).frame(height: 1)
                    TransferListRow(card: card, hud: hud).buttonStyle(.plain).padding(.horizontal, 14).padding(.vertical, 9)
                }
            }
            .background(RoundedRectangle(cornerRadius: Ink.radius, style: .continuous).fill(Ink.surface))
            .overlay(RoundedRectangle(cornerRadius: Ink.radius, style: .continuous).strokeBorder(Ink.hairline, lineWidth: 1))
            .padding(.bottom, 12)
            .transition(.opacity)
        }
    }
}

private struct BackgroundCallRow: View {
    @ObservedObject var ui: CallUI

    var body: some View {
        Button { CallPanels.shared.showCall(phone: ui.phoneId) } label: {
            HStack(spacing: 10) {
                CallerAvatar(photo: ui.photo, name: ui.call.name, size: 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text(ui.title).font(.system(size: 12.5, weight: .medium)).lineLimit(1)
                    CallStatusText(ui: ui).font(.system(size: 11.5)).foregroundStyle(Ink.secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: ui.call.state == .ringing ? "phone.arrow.down.left" : "phone")
                    .font(.system(size: 12)).foregroundStyle(Ink.tertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .help("Show the call")
        .accessibilityLabel("Call with \(ui.title). Show the call")
    }
}

/// A quiet capsule button: raised surface, hairline, secondary text.
private struct QuietButton: View {
    let title: String
    var small = false
    let action: () -> Void
    @State private var hover = false
    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: small ? 11.5 : 12, weight: .medium))
                .foregroundStyle(hover ? Ink.text : Ink.secondary)
                .padding(.horizontal, small ? 10 : 12)
                .padding(.vertical, small ? 4 : 5)
                .background(Capsule().fill(Ink.raised))
                .overlay(Capsule().strokeBorder(Ink.hairline, lineWidth: 1))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}

/// One line that explains why something doesn't work, with an optional fix.
private struct Notice: View {
    let symbol: String
    let text: String
    let action: (String, () -> Void)?
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: symbol).font(.system(size: 12, weight: .medium)).foregroundStyle(Ink.secondary).frame(width: 16).padding(.top, 1)
            Text(text).font(.system(size: 12)).foregroundStyle(Ink.secondary).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 6)
            if let action { QuietButton(title: action.0, small: true, action: action.1) }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Ink.surface))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Ink.hairline, lineWidth: 1))
    }
}

/// One app's notifications.
private struct GroupCard: View {
    let group: PhoneNotifStore.Group
    @ObservedObject var center: PhoneNotifCenter
    let showPhone: Bool
    @State private var expanded = false

    var body: some View {
        let connected = center.isConnected(group.phoneId)
        let shown = expanded ? group.items : Array(group.items.prefix(3))
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                AppIconView(image: center.icon(for: group.pkg), size: 22)
                Text(group.app).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                if group.items.count > 1 {
                    Text("\(group.items.count)").font(.system(size: 11, weight: .medium)).foregroundStyle(Ink.tertiary).monospacedDigit()
                }
                if showPhone {
                    Text(center.phoneName(group.phoneId)).font(.system(size: 11.5)).foregroundStyle(Ink.tertiary).lineLimit(1)
                }
                Spacer()
                if connected && group.items.contains(where: \.canClear) {
                    QuietButton(title: "Clear", small: true) { center.dismissAll(group) }
                        .help("Clear these notifications on the phone too")
                } else if !connected {
                    Text("Not connected").font(.system(size: 11.5)).foregroundStyle(Ink.tertiary)
                }
            }
            .padding(.horizontal, 14)
            .padding(.top, 12)
            .padding(.bottom, 4)

            ForEach(shown) { n in
                NotifRow(n: n, center: center)
                if n.id != shown.last?.id { Rectangle().fill(Ink.hairline).frame(height: 1).padding(.leading, 46) }
            }
            if group.items.count > 3 {
                Button { withAnimation(.easeOut(duration: 0.2)) { expanded.toggle() } } label: {
                    Text(expanded ? "Show less" : "Show \(group.items.count - 3) more")
                        .font(.system(size: 11.5, weight: .medium)).foregroundStyle(Ink.secondary)
                }
                .buttonStyle(.plain)
                .padding(.leading, 46).padding(.bottom, 12).padding(.top, 2)
            }
        }
        .background(RoundedRectangle(cornerRadius: Ink.radius, style: .continuous).fill(Ink.surface))
        .overlay(RoundedRectangle(cornerRadius: Ink.radius, style: .continuous).strokeBorder(Ink.hairline, lineWidth: 1))
        .opacity(connected ? 1 : 0.6)
    }
}

private struct NotifRow: View {
    let n: PhoneNotif
    @ObservedObject var center: PhoneNotifCenter
    @State private var replying = false
    @State private var text = ""
    @State private var hovering = false
    @State private var expanded = false

    /// More than the collapsed row shows: long text or more of the conversation.
    private var expandable: Bool {
        n.msgs.count > 4 || n.body.count > 150 || n.body.filter { $0 == "\n" }.count >= 3
            || n.msgs.contains { $0.text.count > 90 }
    }
    @FocusState private var focused: Bool

    private var connected: Bool { center.isConnected(n.phoneId) }
    private var swipeDx: CGFloat { center.rowSwipe?.id == n.id ? center.rowSwipe!.dx : 0 }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text(n.headline).font(.system(size: 13, weight: .medium)).lineLimit(1)
                if let conv = n.conv, conv != n.title {
                    Text(conv).font(.system(size: 11.5)).foregroundStyle(Ink.tertiary).lineLimit(1)
                }
                Spacer(minLength: 8)
                TimelineView(.periodic(from: .now, by: 30)) { ctx in
                    Text(Self.age(n.ts, now: ctx.date)).font(.system(size: 11)).foregroundStyle(Ink.tertiary).monospacedDigit().lineLimit(1)
                }
                Button { center.block(n) } label: {
                    Image(systemName: "eye.slash").font(.system(size: 9, weight: .bold)).foregroundStyle(Ink.secondary)
                        .frame(width: 18, height: 18)
                        .background(Circle().fill(Ink.raised))
                }
                .buttonStyle(.plain)
                .opacity(hovering ? 1 : 0)
                .help("Don't show this exact notification on this Mac again (it still shows on the phone)")
                .accessibilityLabel("Don't show this again")
                Button { center.dismiss(n) } label: {
                    Image(systemName: "xmark").font(.system(size: 9, weight: .bold)).foregroundStyle(Ink.secondary)
                        .frame(width: 18, height: 18)
                        .background(Circle().fill(Ink.raised))
                }
                .buttonStyle(.plain)
                .opacity(hovering && connected ? 1 : 0)
                .disabled(!connected)
                .accessibilityLabel("Clear")
            }
            if n.msgs.count > 1 {
                VStack(alignment: .leading, spacing: 3) {
                    ForEach(Array((expanded ? Array(n.msgs) : Array(n.msgs.suffix(4))).enumerated()), id: \.offset) { _, m in
                        let who = Text(m.from.map { $0.isEmpty ? "" : "\($0)  " } ?? "You  ").foregroundStyle(Ink.tertiary)
                        Text("\(who)\(Text(m.text).foregroundStyle(Ink.secondary))").font(.system(size: 12.5))
                            .lineLimit(expanded ? nil : 2)
                            .fixedSize(horizontal: false, vertical: expanded)
                    }
                }
            } else if !n.body.isEmpty {
                Text(n.body).font(.system(size: 12.5)).foregroundStyle(Ink.secondary)
                    .lineLimit(expanded ? nil : 4)
                    .fixedSize(horizontal: false, vertical: expanded)
                    .textSelection(.enabled)
            }
            if expandable {
                Button { withAnimation(.easeOut(duration: 0.2)) { expanded.toggle() } } label: {
                    HStack(spacing: 4) {
                        Text(expanded ? "Show less" : "Show more").font(.system(size: 11.5, weight: .medium))
                        Image(systemName: "chevron.down").font(.system(size: 9, weight: .semibold))
                            .rotationEffect(.degrees(expanded ? 180 : 0))
                    }
                    .foregroundStyle(Ink.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(expanded ? "Show less" : "Show the whole notification")
            }
            if connected && (n.canReply || !n.actions.isEmpty) && !replying {
                HStack(spacing: 6) {
                    if n.canReply {
                        QuietButton(title: n.replyLabel ?? "Reply", small: true) {
                            text = center.draft(n.id)
                            withAnimation(.easeOut(duration: 0.2)) { replying = true }
                            DispatchQueue.main.async { focused = true }
                        }
                    }
                    ForEach(n.actions, id: \.index) { a in QuietButton(title: a.label, small: true) { center.act(n, a) } }
                    Spacer()
                }
                .padding(.top, 2)
            }
            if replying {
                HStack(spacing: 8) {
                    TextField("Message", text: $text, axis: .vertical)
                        .textFieldStyle(.plain)
                        .font(.system(size: 12.5))
                        .lineLimit(1...4)
                        .focused($focused)
                        .onSubmit(send)
                        .onChange(of: text) { center.setDraft(n.id, text) }
                        .padding(.horizontal, 10).padding(.vertical, 7)
                        .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Ink.raised))
                        .overlay(RoundedRectangle(cornerRadius: 9, style: .continuous).strokeBorder(Ink.hairline, lineWidth: 1))
                    Button { send() } label: {
                        Image(systemName: "arrow.up").font(.system(size: 11, weight: .bold)).foregroundStyle(Ink.onPill)
                            .frame(width: 26, height: 26)
                            .background(Circle().fill(Ink.pill))
                    }
                    .buttonStyle(.plain)
                    .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
                    .opacity(text.trimmingCharacters(in: .whitespaces).isEmpty ? 0.4 : 1)
                    .keyboardShortcut(.defaultAction)
                    .accessibilityLabel("Send")
                    Button { withAnimation(.easeOut(duration: 0.2)) { replying = false } } label: {
                        Image(systemName: "xmark").font(.system(size: 10, weight: .bold)).foregroundStyle(Ink.secondary)
                            .frame(width: 26, height: 26)
                            .background(Circle().fill(Ink.raised))
                    }
                    .buttonStyle(.plain)
                    .keyboardShortcut(.cancelAction)
                    .accessibilityLabel("Cancel reply")
                }
                .transition(.opacity)
            }
        }
        .padding(.leading, 46)
        .padding(.trailing, 14)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .background(GeometryReader { g in
            Color.clear.preference(key: NotifRowFramesKey.self, value: [n.id: g.frame(in: .named(NotifRowFramesKey.space))])
        })
        .offset(x: swipeDx)
        .opacity(1 - min(Double(abs(swipeDx)) / 320, 0.7))
        // A click opens it, like a tap in the phone's shade: the app opens on the phone, shown in the mirror window.
        .onTapGesture { if connected && !replying && center.canOpen(n) { center.openInMirror(n) } }
        .onHover { hovering = $0 }
        .contextMenu {
            if connected && center.canOpen(n) {
                Button("Open in Mirror") { center.openInMirror(n) }
                Button("Open on Phone") { center.openOnPhone(n) }
            }
            if connected { Button("Clear") { center.dismiss(n) } }
            Button("Don't Show This Again") { center.block(n) }
            Button("Copy Text") {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString([n.title, n.body].filter { !$0.isEmpty }.joined(separator: "\n"), forType: .string)
            }
        }
        .accessibilityElement(children: .contain)
    }

    /// "now", "4m", "2h", "Yesterday", "Mon", "12 Nov": short, like a phone's list.
    static func age(_ t: Date, now: Date) -> String {
        let s = now.timeIntervalSince(t)
        if s < 60 { return "now" }
        if s < 3600 { return "\(Int(s / 60))m" }
        let cal = Calendar.current
        if cal.isDateInToday(t) { return "\(Int(s / 3600))h" }
        if cal.isDateInYesterday(t) { return "Yesterday" }
        if s < 6 * 86_400 { return t.formatted(.dateTime.weekday(.abbreviated)) }
        return t.formatted(.dateTime.day().month(.abbreviated))
    }

    private func send() {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        center.reply(n, t)
        text = ""
        withAnimation(.easeOut(duration: 0.2)) { replying = false }
    }
}

struct AppIconView: View {
    let image: NSImage?
    var size: CGFloat = 18
    var body: some View {
        Group {
            if let image {
                Image(nsImage: image).resizable().interpolation(.high)
            } else {
                Image(systemName: "app.badge").resizable().scaledToFit().foregroundStyle(Ink.tertiary).padding(2)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.24, style: .continuous))
        .accessibilityHidden(true)
    }
}

/// Notifications blocked on this Mac, each with Unblock. Collapsed by default.
private struct BlockedSection: View {
    @ObservedObject var center: PhoneNotifCenter
    @State private var open = false

    var body: some View {
        if !center.blocks.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                Button { withAnimation(.easeOut(duration: 0.2)) { open.toggle() } } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "eye.slash").font(.system(size: 11, weight: .medium)).foregroundStyle(Ink.secondary)
                        Text("Blocked on this Mac").font(.system(size: 12.5, weight: .medium))
                        Text("\(center.blocks.count)").font(.system(size: 11, weight: .medium)).foregroundStyle(Ink.tertiary).monospacedDigit()
                        Spacer()
                        Image(systemName: "chevron.down").font(.system(size: 9.5, weight: .bold)).foregroundStyle(Ink.tertiary)
                            .rotationEffect(.degrees(open ? 180 : 0))
                    }
                    .padding(.horizontal, 14).padding(.vertical, 11)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                if open {
                    Text("Only notifications with exactly this text are blocked. Other notifications from the same app still come through, and your phone still shows these.")
                        .font(.system(size: 11.5)).foregroundStyle(Ink.tertiary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 14).padding(.bottom, 8)
                    ForEach(center.blocks) { b in
                        Rectangle().fill(Ink.hairline).frame(height: 1)
                        HStack(alignment: .top, spacing: 10) {
                            AppIconView(image: center.icon(for: b.pkg), size: 18)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(b.title.isEmpty ? b.app : b.title).font(.system(size: 12.5, weight: .medium)).lineLimit(1)
                                if !b.text.isEmpty { Text(b.text).font(.system(size: 12)).foregroundStyle(Ink.secondary).lineLimit(2) }
                                Text(b.app).font(.system(size: 11)).foregroundStyle(Ink.tertiary)
                            }
                            Spacer(minLength: 8)
                            BlockedUnblockButton { center.unblock(b) }
                        }
                        .padding(.horizontal, 14).padding(.vertical, 10)
                    }
                }
            }
            .background(RoundedRectangle(cornerRadius: Ink.radius, style: .continuous).fill(Ink.surface))
            .overlay(RoundedRectangle(cornerRadius: Ink.radius, style: .continuous).strokeBorder(Ink.hairline, lineWidth: 1))
        }
    }
}

private struct BlockedUnblockButton: View {
    let action: () -> Void
    var body: some View { QuietButton(title: "Unblock", small: true, action: action) }
}
