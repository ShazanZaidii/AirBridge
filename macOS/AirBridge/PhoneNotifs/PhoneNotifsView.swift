import AppKit
import SwiftUI

/// The "Phone notifications" window (menu tile, or a click on a phone notification banner).
@MainActor final class PhoneNotifsWindow: NSObject, NSWindowDelegate {
    static let shared = PhoneNotifsWindow()
    private var window: NSWindow?

    func show() {
        PhoneNotifCenter.shared.unseen = 0
        if window == nil {
            let host = NSHostingController(rootView: PhoneNotifsView(center: PhoneNotifCenter.shared, model: AppModel.shared))
            host.sizingOptions = [.minSize]
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 400, height: 560),
                             styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                             backing: .buffered, defer: false)
            w.title = "Phone Notifications"
            w.titlebarAppearsTransparent = true
            w.isReleasedWhenClosed = false
            w.contentViewController = host
            w.setContentSize(NSSize(width: 400, height: 560))
            w.delegate = self
            window = w
        }
        // Every time it opens: top right under the menu bar on the icon's screen, as tall as there is room for,
        // and beside (not over) a phone mirror or call window that's already open.
        if let w = window, !w.isVisible {
            w.setFrame(SmartPlacement.frame(for: NSSize(width: 380, height: 600), avoiding: w), display: false)
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    func windowDidBecomeKey(_ notification: Notification) { PhoneNotifCenter.shared.unseen = 0 }
}

struct PhoneNotifsView: View {
    @ObservedObject var center: PhoneNotifCenter
    @ObservedObject var model: AppModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var groups: [PhoneNotifStore.Group] { center.store.groups() }
    private var multiplePhones: Bool { Set(center.store.items.values.map(\.phoneId)).count > 1 }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            hints
                .padding(.horizontal, Theme.md)
                .padding(.top, 34)
            if groups.isEmpty {
                empty
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 14) {
                        ForEach(groups) { g in
                            GroupCard(group: g, center: center, showPhone: multiplePhones)
                                .transition(.asymmetric(insertion: .move(edge: .top).combined(with: .opacity), removal: .opacity))
                        }
                    }
                    .padding(Theme.md)
                    .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.85), value: center.store.items.keys.sorted())
                }
            }
        }
        .frame(minWidth: 340, minHeight: 300)
        .background(.regularMaterial)
    }

    @ViewBuilder private var hints: some View {
        let phones = center.sourcePhones
        VStack(alignment: .leading, spacing: Theme.xs) {
            ForEach(phones) { p in
                if let st = center.states[p.id], !st.notifs {
                    Label(st.why == "no_access"
                          ? "Allow notification access for AirBridge on \(p.name) to see its notifications here."
                          : "Turn on “Show phone notifications on your computers” in AirBridge on \(p.name).",
                          systemImage: "info.circle")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if !PhoneNotifSettings.showNotifs {
                Label("Phone notifications are turned off in AirBridge Settings on this Mac.", systemImage: "bell.slash")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let err = center.lastError {
                Label(err, systemImage: "exclamationmark.circle")
                    .font(.caption).foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
                    .onTapGesture { center.lastError = nil }
            }
        }
    }

    private var empty: some View {
        VStack(spacing: Theme.sm) {
            Spacer()
            Image(systemName: "bell").font(.system(size: 28, weight: .light)).foregroundStyle(.secondary)
            Text("No notifications").font(.headline)
            Text(center.sourcePhones.isEmpty
                 ? "Connect your phone and turn on notifications in AirBridge on the phone."
                 : "New notifications from your phone show up here.")
                .font(.callout).foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 260)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }
}

/// One app's notifications.
private struct GroupCard: View {
    let group: PhoneNotifStore.Group
    @ObservedObject var center: PhoneNotifCenter
    let showPhone: Bool
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Theme.sm) {
                AppIconView(image: center.icon(for: group.pkg), size: 18)
                Text(group.app).font(.callout.weight(.semibold)).lineLimit(1)
                if showPhone {
                    Text(center.phoneName(group.phoneId)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                if center.isConnected(group.phoneId) {
                    Button("Clear") { center.dismissAll(group) }
                        .buttonStyle(.borderless).font(.caption)
                        .help("Clear these notifications on the phone too")
                } else {
                    Text("Not connected").font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, Theme.sm + 2)
            .padding(.top, Theme.sm + 2)
            .padding(.bottom, Theme.xs)

            let shown = expanded ? group.items : Array(group.items.prefix(3))
            ForEach(shown) { n in
                NotifRow(n: n, center: center)
                if n.id != shown.last?.id { Divider().padding(.leading, Theme.sm + 2) }
            }
            if group.items.count > 3 {
                Button(expanded ? "Show less" : "Show \(group.items.count - 3) more") { expanded.toggle() }
                    .buttonStyle(.borderless).font(.caption)
                    .padding(.horizontal, Theme.sm + 2).padding(.bottom, Theme.sm)
            }
        }
        .background(RoundedRectangle(cornerRadius: 12).fill(Color.primary.opacity(0.05)))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Color.primary.opacity(0.07)))
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
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline) {
                Text(n.headline).font(.callout.weight(.medium)).lineLimit(1)
                if let conv = n.conv, conv != n.title {
                    Text(conv).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: Theme.sm)
                Text(n.ts, style: .relative).font(.caption2).foregroundStyle(.secondary).monospacedDigit()
                Button { center.dismiss(n) } label: { Image(systemName: "xmark").font(.caption2.weight(.semibold)) }
                    .buttonStyle(.borderless)
                    .opacity(hovering && connected ? 1 : 0)
                    .disabled(!connected)
                    .accessibilityLabel("Clear")
            }
            if n.msgs.count > 1 {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(n.msgs.suffix(4).enumerated()), id: \.offset) { _, m in
                        let who = Text(m.from.map { $0.isEmpty ? "" : "\($0): " } ?? "You: ").foregroundStyle(.secondary)
                        Text("\(who)\(m.text)").font(.callout).lineLimit(2)
                    }
                }
            } else if !n.body.isEmpty {
                Text(n.body).font(.callout).foregroundStyle(.primary.opacity(0.85)).lineLimit(4)
                    .textSelection(.enabled)
            }
            if connected && (n.canReply || !n.actions.isEmpty) && !replying {
                HStack(spacing: Theme.xs) {
                    if n.canReply {
                        Button(n.replyLabel ?? "Reply") {
                            text = center.draft(n.id)
                            withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { replying = true }
                            DispatchQueue.main.async { focused = true }
                        }
                    }
                    ForEach(n.actions, id: \.index) { a in Button(a.label) { center.act(n, a) } }
                    Spacer()
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
            if replying {
                HStack(spacing: Theme.xs) {
                    TextField("Message", text: $text, axis: .vertical)
                        .textFieldStyle(.roundedBorder)
                        .lineLimit(1...4)
                        .focused($focused)
                        .onSubmit(send)
                        .onChange(of: text) { center.setDraft(n.id, text) }
                    Button { send() } label: { Image(systemName: "arrow.up.circle.fill").font(.title3) }
                        .buttonStyle(.borderless)
                        .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
                        .keyboardShortcut(.defaultAction)
                        .accessibilityLabel("Send")
                    Button { withAnimation { replying = false } } label: { Image(systemName: "xmark.circle") }
                        .buttonStyle(.borderless).foregroundStyle(.secondary)
                        .keyboardShortcut(.cancelAction)
                        .accessibilityLabel("Cancel reply")
                }
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .padding(.horizontal, Theme.sm + 2)
        .padding(.vertical, Theme.sm)
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .opacity(connected ? 1 : 0.6)
        .contextMenu {
            if connected {
                Button("Open on Phone") { center.openOnPhone(n) }
                Button("Clear") { center.dismiss(n) }
            }
            Button("Copy Text") {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString([n.title, n.body].filter { !$0.isEmpty }.joined(separator: "\n"), forType: .string)
            }
        }
        .accessibilityElement(children: .contain)
    }

    private func send() {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        center.reply(n, t)
        text = ""
        withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { replying = false }
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
                Image(systemName: "app.badge").resizable().scaledToFit().foregroundStyle(.secondary).padding(1)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.22))
        .accessibilityHidden(true)
    }
}
