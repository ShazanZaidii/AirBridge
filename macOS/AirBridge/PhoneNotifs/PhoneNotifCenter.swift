import AppKit
import Combine
import SwiftUI
import UserNotifications

/// Settings keys (UserDefaults) for phone notifications and calls on this Mac.
enum PhoneNotifSettings {
    static let showNotifsKey = "PhoneNotifsShow"
    static let showCallsKey = "PhoneCallsShow"
    static let soundsKey = "PhoneNotifsSound"

    static var showNotifs: Bool { UserDefaults.standard.object(forKey: showNotifsKey) as? Bool ?? true }
    static var showCalls: Bool { UserDefaults.standard.object(forKey: showCallsKey) as? Bool ?? true }
    static var sounds: Bool { UserDefaults.standard.object(forKey: soundsKey) as? Bool ?? true }
}

/// Phone notifications and calls on this Mac (PROTOCOL §13): keeps the list, shows them as macOS notifications
/// (with Reply where the phone supports it), shows the call panel, and sends dismiss / reply / actions / call
/// controls back to the phone.
@MainActor final class PhoneNotifCenter: ObservableObject {
    static let shared = PhoneNotifCenter()

    @Published private(set) var store = PhoneNotifStore()
    @Published private(set) var calls: [String: PhoneCall] = [:]
    @Published private(set) var states: [String: PhoneRelayState] = [:]
    @Published private(set) var icons: [String: NSImage] = [:]
    /// Notifications that arrived since the list was last looked at (badge on the menu tile).
    @Published var unseen = 0
    /// Last failure to show in the list window, e.g. "Couldn't send the reply".
    @Published var lastError: String?

    private var board = PhoneCallBoard()
    private var peerIds: Set<String> = []
    private var cancellables: Set<AnyCancellable> = []
    private var lastSubs: [String: [String: Any]] = [:]
    private var pendingReplies: [String: String] = [:]

    nonisolated static let categoryReply = "PHONE_NOTIF_REPLY"
    nonisolated static let categoryPlain = "PHONE_NOTIF"
    nonisolated static let actionReply = "PHONE_REPLY"
    nonisolated static let actionOpenOnPhone = "PHONE_OPEN"
    nonisolated static let userInfoKey = "phoneNotif"
    private static let idPrefix = "phone|"

    /// Notification categories for the app delegate's `setNotificationCategories`.
    nonisolated static var categories: [UNNotificationCategory] {
        let reply = UNTextInputNotificationAction(identifier: actionReply, title: "Reply", options: [],
                                                  textInputButtonTitle: "Send", textInputPlaceholder: "Message")
        let open = UNNotificationAction(identifier: actionOpenOnPhone, title: "Open on Phone", options: [])
        return [
            UNNotificationCategory(identifier: categoryReply, actions: [reply, open], intentIdentifiers: [],
                                   options: [.customDismissAction]),
            UNNotificationCategory(identifier: categoryPlain, actions: [open], intentIdentifiers: [],
                                   options: [.customDismissAction]),
        ]
    }

    func start() {
        AppModel.shared.$readyPeers
            .sink { [weak self] peers in self?.peersChanged(peers) }
            .store(in: &cancellables)
        NotificationCenter.default.publisher(for: UserDefaults.didChangeNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.settingsMaybeChanged() }
            .store(in: &cancellables)
    }

    // MARK: Network → UI

    /// Called by the session (network queue) for every `notif_*` / `call_*` message.
    nonisolated static func receive(from peerId: String, _ msg: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: msg) else { return }
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                guard let m = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
                PhoneNotifCenter.shared.handle(from: peerId, m)
            }
        }
    }

    private func handle(from phone: String, _ m: [String: Any]) {
        guard let t = m["t"] as? String, isSource(phone) else { return }
        switch t {
        case "notif_icon":
            if let pkg = m["pkg"] as? String, let b64 = m["png"] as? String, b64.count < 400_000,
               let data = Data(base64Encoded: b64), let img = NSImage(data: data) {
                icons[pkg] = img
                saveIcon(pkg, data)
            }
        case "notif_state":
            if let st = PhoneNotifWire.parseState(m) { states[phone] = st }
            applyStore(m, from: phone)
        case "notif_post", "notif_remove", "notif_reset":
            if t == "notif_post" && !PhoneNotifSettings.showNotifs { return }
            applyStore(m, from: phone)
        case "notif_result":
            guard m["ok"] as? Bool == false else { return }
            let op = m["op"] as? String ?? ""
            let why = m["reason"] as? String
            lastError = failureText(op: op, reason: why, phone: phone)
            if let msg = lastError { AppModel.shared.notice(msg) }
        case "call_state":
            guard PhoneNotifSettings.showCalls else { return }
            switch board.apply(m, from: phone) {
            case .shown(let c), .updated(let c):
                calls[phone] = c
                CallPanels.shared.show(c)
            case .ended(let c):
                calls[phone] = nil
                CallPanels.shared.end(c)
            case .none: break
            }
        case "call_photo":
            if let p = PhoneNotifWire.parseCallPhoto(m) { CallPanels.shared.setPhoto(phone: phone, id: p.id, jpg: p.jpg) }
        case "call_presence":
            if let r = PhonePresenceReport.parse(m, at: Date()) { CallPanels.shared.setPresence(phone: phone, r) }
        case "call_beacon":
            if case .some(let b) = PhoneNotifWire.parseBeacon(m) {
                BeaconScanner.shared.setBeacon(phone: phone, key: b?.key, uuid: b?.uuid ?? "", period: b?.period ?? 900)
            }
        case "call_result":
            guard m["ok"] as? Bool == false else { return }
            let reason = m["reason"] as? String
            let text: String
            switch reason {
            case "no_permission": text = "Allow the Phone permission for AirBridge on \(phoneName(phone)) to answer or end calls from here"
            case "off": text = "Calls are turned off in AirBridge on \(phoneName(phone))"
            case "no_call": text = "That call has already ended"
            default: text = "\(phoneName(phone)) couldn't do that. Use the phone instead."
            }
            AppModel.shared.notice(text)
            CallPanels.shared.flash(phone: phone, text)
        default:
            break
        }
    }

    private func applyStore(_ m: [String: Any], from phone: String) {
        switch store.apply(m, from: phone) {
        case .posted(let n, _):
            if n.alert {
                unseen += 1
                deliver(n)
            }
        case .removed(let ids):
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids.map { Self.idPrefix + $0 })
        case .none:
            break
        }
    }

    private func failureText(op: String, reason: String?, phone: String) -> String? {
        let p = phoneName(phone)
        switch reason {
        case "gone": return op == "dismiss" ? nil : "That notification is no longer on \(p)"
        case "no_reply": return "That notification can't be answered from here"
        case "off": return "Notifications are turned off in AirBridge on \(p)"
        default:
            switch op {
            case "reply": return "Couldn't send the reply. Open it on \(p) instead."
            case "open": return "\(p) didn't open it. Unlock the phone and try again."
            default: return "\(p) couldn't do that"
            }
        }
    }

    // MARK: Peers

    private func peersChanged(_ peers: [PeerInfo]) {
        let sources = peers.filter { $0.caps.contains(PhoneNotifCaps.notifSource) || $0.caps.contains(PhoneNotifCaps.callSource) }
        let now = Set(sources.map(\.id))
        for p in sources where !peerIds.contains(p.id) {
            lastSubs[p.id] = nil
            sendSub(to: p.id)
        }
        for gone in peerIds.subtracting(now) {
            lastSubs[gone] = nil
            states[gone] = nil
            // Banners of a phone that left can't be answered any more; the list keeps them (greyed).
            let ids = store.items.values.filter { $0.phoneId == gone }.map { Self.idPrefix + $0.id }
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
            if let c = board.phoneGone(gone) { calls[gone] = nil; CallPanels.shared.end(c) }
            CallPanels.shared.phoneGone(gone)
        }
        peerIds = now
    }

    private func settingsMaybeChanged() {
        for id in peerIds { sendSub(to: id) }
        if !PhoneNotifSettings.showNotifs, store.count > 0 {
            for id in Set(store.items.values.map(\.phoneId)) {
                if case .removed(let ids) = store.removeAll(phone: id) {
                    UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids.map { Self.idPrefix + $0 })
                }
            }
            unseen = 0
        }
        if !PhoneNotifSettings.showCalls {
            for (phone, c) in calls { _ = board.phoneGone(phone); CallPanels.shared.end(c) }
            calls = [:]
        }
    }

    /// `notif_sub` (§13.2), only when it changed for that phone.
    private func sendSub(to peerId: String) {
        let msg = PhoneNotifWire.sub(notifs: PhoneNotifSettings.showNotifs, calls: PhoneNotifSettings.showCalls)
        if let old = lastSubs[peerId], NSDictionary(dictionary: old).isEqual(to: msg) { return }
        lastSubs[peerId] = msg
        PeerBus.shared.send(to: peerId, msg)
    }

    func isConnected(_ phone: String) -> Bool { isSource(phone) }

    /// A ready phone that advertised `notif_source` / `call_source`. Reads the network half's snapshot, which is
    /// current before the menu's peer list is (messages right after `ready` must not be dropped).
    private func isSource(_ phone: String) -> Bool {
        PeerBus.shared.peers().contains { $0.id == phone && ($0.caps.contains(PhoneNotifCaps.notifSource) || $0.caps.contains(PhoneNotifCaps.callSource)) }
    }

    func phoneName(_ phone: String) -> String {
        AppModel.shared.readyPeers.first { $0.id == phone }?.name
            ?? AppModel.shared.devices.first { $0.id == phone }?.name ?? "your phone"
    }

    /// Phones that can send notifications and are connected (for the menu tile and the list's hints).
    var sourcePhones: [PeerInfo] { AppModel.shared.readyPeers.filter { $0.caps.contains(PhoneNotifCaps.notifSource) } }

    // MARK: UI → phone

    func dismiss(_ n: PhoneNotif) {
        PeerBus.shared.send(to: n.phoneId, PhoneNotifWire.dismiss(n.key))
        // Gone here at once; if the phone refuses, its next post brings it back.
        if store.remove(id: n.id) {
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [Self.idPrefix + n.id])
        }
    }

    func dismissAll(_ group: PhoneNotifStore.Group) { group.items.forEach(dismiss) }

    func reply(_ n: PhoneNotif, _ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        guard isConnected(n.phoneId) else { AppModel.shared.notice("\(phoneName(n.phoneId)) isn't connected"); return }
        PeerBus.shared.send(to: n.phoneId, PhoneNotifWire.reply(n.key, t))
        pendingReplies[n.id] = nil
    }

    func act(_ n: PhoneNotif, _ a: PhoneAction) {
        guard isConnected(n.phoneId) else { AppModel.shared.notice("\(phoneName(n.phoneId)) isn't connected"); return }
        PeerBus.shared.send(to: n.phoneId, PhoneNotifWire.action(n.key, a.index))
    }

    func openOnPhone(_ n: PhoneNotif) {
        guard isConnected(n.phoneId) else { AppModel.shared.notice("\(phoneName(n.phoneId)) isn't connected"); return }
        PeerBus.shared.send(to: n.phoneId, PhoneNotifWire.open(n.key))
    }

    func callAction(_ c: PhoneCall, _ op: String) {
        guard isConnected(c.phoneId) else { return }
        PeerBus.shared.send(to: c.phoneId, PhoneNotifWire.callAction(c.id, op))
    }

    /// Draft text of an inline reply (kept while the list re-renders).
    func draft(_ id: String) -> String { pendingReplies[id] ?? "" }
    func setDraft(_ id: String, _ text: String) { pendingReplies[id] = text }

    // MARK: macOS notifications

    private func deliver(_ n: PhoneNotif) {
        let c = UNMutableNotificationContent()
        c.title = n.headline
        var sub = n.app
        if let conv = n.conv, conv != n.title { sub += " · \(conv)" }
        if AppModel.shared.readyPeers.filter({ $0.caps.contains(PhoneNotifCaps.notifSource) }).count > 1 { sub += " · \(phoneName(n.phoneId))" }
        c.subtitle = sub
        c.body = n.body
        c.threadIdentifier = n.phoneId + "|" + n.pkg
        c.categoryIdentifier = n.canReply ? Self.categoryReply : Self.categoryPlain
        c.userInfo = [Self.userInfoKey: n.id]
        if PhoneNotifSettings.sounds { c.sound = .default }
        if let url = attachmentCopy(for: n.pkg),
           let att = try? UNNotificationAttachment(identifier: "icon", url: url,
                                                   options: [UNNotificationAttachmentOptionsTypeHintKey: "public.png"]) {
            c.attachments = [att]
        }
        let req = UNNotificationRequest(identifier: Self.idPrefix + n.id, content: c, trigger: nil)
        UNUserNotificationCenter.current().add(req) { _ in }
    }

    /// The app delegate hands over responses to our notifications (already on the main actor).
    func handleResponse(id: String, action: String, text: String?) {
        guard let n = store.items[id] else {
            if action == UNNotificationDefaultActionIdentifier { PhoneNotifsWindow.shared.show() }
            return
        }
        switch action {
        case Self.actionReply: if let text { reply(n, text) }
        case Self.actionOpenOnPhone: openOnPhone(n)
        case UNNotificationDismissActionIdentifier: if isConnected(n.phoneId) { dismiss(n) }
        default: PhoneNotifsWindow.shared.show()
        }
    }

    // MARK: Icons

    private static let iconDir: URL = {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("AirBridge/PhoneIcons", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private static func safeName(_ pkg: String) -> String {
        let ok = pkg.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) || $0 == "." || $0 == "_" ? Character($0) : "_" }
        let s = String(ok).trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return s.isEmpty ? "app" : String(s.prefix(120))
    }

    private func saveIcon(_ pkg: String, _ png: Data) {
        try? png.write(to: Self.iconDir.appendingPathComponent(Self.safeName(pkg) + ".png"), options: .atomic)
    }

    /// Icons read back from the cache folder (not published: views ask for them while rendering).
    private var diskIcons: [String: NSImage] = [:]

    func icon(for pkg: String) -> NSImage? {
        if let i = icons[pkg] ?? diskIcons[pkg] { return i }
        let url = Self.iconDir.appendingPathComponent(Self.safeName(pkg) + ".png")
        guard let img = NSImage(contentsOf: url) else { return nil }
        diskIcons[pkg] = img
        return img
    }

    /// UNNotificationAttachment moves its file into the notification store, so each banner gets a copy.
    private func attachmentCopy(for pkg: String) -> URL? {
        let src = Self.iconDir.appendingPathComponent(Self.safeName(pkg) + ".png")
        guard FileManager.default.fileExists(atPath: src.path) else { return nil }
        let dst = FileManager.default.temporaryDirectory.appendingPathComponent("airbridge-icon-\(UUID().uuidString).png")
        do { try FileManager.default.copyItem(at: src, to: dst); return dst } catch { return nil }
    }
}
