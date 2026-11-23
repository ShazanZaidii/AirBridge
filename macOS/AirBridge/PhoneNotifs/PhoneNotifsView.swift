import AppKit
import SwiftUI

/// The phone notifications list window (one at a time).
@MainActor final class PhoneNotifsWindow: NSObject, NSWindowDelegate {
    static let shared = PhoneNotifsWindow()
    private var window: NSWindow?

    func show() {
        PhoneNotifCenter.shared.unseen = 0
        PhoneNotifCenter.shared.checkPermission()
        AppWindows.closeMenuPanel() // the menu bar panel would sit on top of the window
        // Become a regular app before the window exists, and open a fresh window when the old one isn't on this desktop
        // (or the user is in a full-screen app): macOS then puts it on a normal desktop and takes the user there in one
        // move, instead of flashing it and jumping to a desktop without it.
        if NSApp.activationPolicy() != .regular { NSApp.setActivationPolicy(.regular) }
        if let old = window, !(old.isVisible && old.isOnActiveSpace && !old.isMiniaturized) {
            old.delegate = nil
            old.close()
            window = nil
        }
        if window == nil {
            let host = NSHostingController(rootView: PhoneNotifsView(center: PhoneNotifCenter.shared, model: AppModel.shared))
            host.sizingOptions = [.minSize]
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 420, height: 780),
                             styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                             backing: .buffered, defer: false)
            w.title = "Notifications"
            w.titlebarAppearsTransparent = true
            w.isReleasedWhenClosed = false
            w.contentViewController = host
            w.delegate = self
            // Top right under the menu bar on the icon's screen, as tall as there is room for, beside (not over) a phone
            // mirror or call window that's already open.
            w.setFrame(SmartPlacement.frame(for: NSSize(width: 420, height: 780), avoiding: w), display: false)
            window = w
        }
        NSApp.activate()
        window?.makeKeyAndOrderFront(nil)
    }

    func windowDidBecomeKey(_ notification: Notification) { PhoneNotifCenter.shared.unseen = 0 }
}

// MARK: - The list, in AirBridge's charcoal design language (Design/Charcoal.swift)

struct PhoneNotifsView: View {
    @ObservedObject var center: PhoneNotifCenter
    @ObservedObject var model: AppModel
    /// Debug snapshots: draw the list without its scroll view.
    var flat = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var groups: [PhoneNotifStore.Group] { center.store.groups() }
    private var multiplePhones: Bool { Set(center.store.items.values.map(\.phoneId)).count > 1 }
    private var clearable: [PhoneNotifStore.Group] { groups.filter { center.isConnected($0.phoneId) } }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(.horizontal, 20)
                .padding(.top, 40)
                .padding(.bottom, 14)
            notices
                .padding(.horizontal, 16)
            if groups.isEmpty {
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
        .frame(minWidth: 360, minHeight: 320)
        .background(Ink.page)
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
            .frame(maxWidth: .infinity, minHeight: 200)
        }
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
                if connected {
                    QuietButton(title: "Clear", small: true) { center.dismissAll(group) }
                        .help("Clear these notifications on the phone too")
                } else {
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
    @FocusState private var focused: Bool

    private var connected: Bool { center.isConnected(n.phoneId) }

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
                    ForEach(Array(n.msgs.suffix(4).enumerated()), id: \.offset) { _, m in
                        let who = Text(m.from.map { $0.isEmpty ? "" : "\($0)  " } ?? "You  ").foregroundStyle(Ink.tertiary)
                        Text("\(who)\(Text(m.text).foregroundStyle(Ink.secondary))").font(.system(size: 12.5)).lineLimit(2)
                    }
                }
            } else if !n.body.isEmpty {
                Text(n.body).font(.system(size: 12.5)).foregroundStyle(Ink.secondary).lineLimit(4)
                    .textSelection(.enabled)
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
        .onHover { hovering = $0 }
        .contextMenu {
            if connected {
                Button("Open on Phone") { center.openOnPhone(n) }
                Button("Clear") { center.dismiss(n) }
            }
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
