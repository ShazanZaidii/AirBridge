import AppKit
import CoreGraphics
import WidgetKit

/// Screen time for the AirBridge widget (docs/protocol.md §15).
///
/// - Other devices send `screen_time` (and `screen_time_icon`); each becomes a device in the shared snapshot the widget reads.
/// - This Mac counts its own time: which app is in front, paused while idle (no input for 5 minutes), while the screen sleeps
///   and while it is locked. macOS gives apps no access to its own Screen Time data, so this only counts from when AirBridge runs.
/// - This Mac's numbers also go to other computers that advertise `screentime_sink` (another Mac with the widget).
///
/// Nothing leaves your own paired devices. Threading: received messages and the snapshot live on `queue`; the tracker runs on
/// the main thread.
nonisolated final class ScreenTimeHub: @unchecked Sendable {
    static let shared = ScreenTimeHub()

    /// Settings switch "Count this Mac's screen time" (default on).
    static let trackKey = "ScreenTimeTrackThisMac"
    static var trackingEnabled: Bool { UserDefaults.standard.object(forKey: trackKey) as? Bool ?? true }
    static let cap = "screentime_sink"

    private let queue = DispatchQueue(label: "com.shazan.airbridge.screentime")
    private var snapshot = STStore.load()
    private var reloadScheduled = false
    /// Peers we already sent this Mac's report to in their current session, and which icons they have.
    private var sentIcons: [String: Set<String>] = [:]
    private var lastSentAt: [String: Date] = [:]

    // MARK: Reports from other devices (any thread)

    func receive(from peer: PeerInfo, type: String, _ msg: [String: Any]) {
        queue.async { [self] in
            switch type {
            case "screen_time":
                guard let dev = STWire.device(from: msg, id: peer.id, name: peer.name, platform: peer.platform, now: Date()) else { return }
                snapshot.upsert(dev)
                STStore.save(snapshot)
                scheduleReload()
            case "screen_time_icon":
                guard let (id, png) = STWire.icon(from: msg) else { return }
                if STStore.saveIcon(png, appId: id) { scheduleReload() }
            default: break
            }
        }
    }

    /// A device was forgotten: its numbers go too.
    func forget(deviceId: String) {
        queue.async { [self] in
            snapshot.remove(deviceId: deviceId)
            STStore.save(snapshot)
            scheduleReload()
        }
    }

    /// The set of ready peers changed: send this Mac's report to computers that show screen time and have not had it yet.
    func peersChanged(_ peers: [PeerInfo]) {
        queue.async { [self] in
            let ids = Set(peers.map(\.id))
            sentIcons = sentIcons.filter { ids.contains($0.key) }
            lastSentAt = lastSentAt.filter { ids.contains($0.key) }
        }
        onMain { LocalScreenTime.shared.sendIfDue(force: true) }
    }

    /// Stores this Mac's own device and reloads the widget.
    func storeLocal(_ device: STDevice) {
        queue.async { [self] in
            snapshot.upsert(device)
            STStore.save(snapshot)
            scheduleReload()
        }
    }

    /// Sends this Mac's report (and the icons of its top apps, once per session) to every ready sink. At most every 5 minutes
    /// per peer unless `force`.
    func send(_ device: STDevice, icons: [String: Data], force: Bool) {
        let peers = PeerManager.shared.peers().filter { $0.caps.contains(Self.cap) }
        guard !peers.isEmpty else { return }
        let msg = STWire.message(for: device)
        queue.async { [self] in
            let now = Date()
            for p in peers {
                if !force, let last = lastSentAt[p.id], now.timeIntervalSince(last) < 300 { continue }
                lastSentAt[p.id] = now
                _ = PeerManager.shared.send(to: p.id, msg)
                var have = sentIcons[p.id] ?? []
                for (id, png) in icons where !have.contains(id) {
                    if let m = STWire.iconMessage(id: id, png: png) { _ = PeerManager.shared.send(to: p.id, m); have.insert(id) }
                }
                sentIcons[p.id] = have
            }
        }
    }

    /// The widget is told at most every 15 s; WidgetKit rate-limits reloads anyway.
    private func scheduleReload() {
        guard !reloadScheduled else { return }
        reloadScheduled = true
        queue.asyncAfter(deadline: .now() + 15) { [self] in
            reloadScheduled = false
            WidgetCenter.shared.reloadTimelines(ofKind: ScreenTimeShared.widgetKind)
        }
    }
}

/// Counts this Mac's own screen time: the app in front, minus idle, sleep and lock.
@MainActor final class LocalScreenTime {
    static let shared = LocalScreenTime()

    private static let idleLimit: TimeInterval = 300
    private static let ignored: Set<String> = ["com.apple.loginwindow", "com.apple.ScreenSaver.Engine", "com.apple.SecurityAgent"]

    private var acc = STAccumulator()
    private var current: (id: String, name: String, since: Date)?
    private var paused = false
    private var timer: Timer?
    private var started = false
    private var lastSend = Date.distantPast
    private var calendar: Calendar { .current }

    private var file: URL? { ScreenTimeShared.folder()?.appendingPathComponent("this-mac.json") }

    func start() {
        guard !started else { return }
        started = true
        if let file, let data = try? Data(contentsOf: file), let saved = try? JSONDecoder().decode(STAccumulator.self, from: data) { acc = saved }
        let ws = NSWorkspace.shared.notificationCenter
        ws.addObserver(forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main) { n in
            let app = n.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
            MainActor.assumeIsolated { LocalScreenTime.shared.switched(to: app) }
        }
        for name in [NSWorkspace.screensDidSleepNotification, NSWorkspace.sessionDidResignActiveNotification, NSWorkspace.willSleepNotification] {
            ws.addObserver(forName: name, object: nil, queue: .main) { _ in MainActor.assumeIsolated { LocalScreenTime.shared.pause() } }
        }
        for name in [NSWorkspace.screensDidWakeNotification, NSWorkspace.sessionDidBecomeActiveNotification, NSWorkspace.didWakeNotification] {
            ws.addObserver(forName: name, object: nil, queue: .main) { _ in MainActor.assumeIsolated { LocalScreenTime.shared.resume() } }
        }
        let dnc = DistributedNotificationCenter.default()
        dnc.addObserver(forName: NSNotification.Name("com.apple.screenIsLocked"), object: nil, queue: .main) { _ in MainActor.assumeIsolated { LocalScreenTime.shared.pause() } }
        dnc.addObserver(forName: NSNotification.Name("com.apple.screenIsUnlocked"), object: nil, queue: .main) { _ in MainActor.assumeIsolated { LocalScreenTime.shared.resume() } }
        switched(to: NSWorkspace.shared.frontmostApplication)
        timer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { _ in MainActor.assumeIsolated { LocalScreenTime.shared.tick() } }
    }

    /// The setting was switched: stop counting now, or start again.
    func settingChanged() {
        if ScreenTimeHub.trackingEnabled { resume() } else { pause() }
    }

    private func switched(to app: NSRunningApplication?) {
        close(at: Date())
        guard ScreenTimeHub.trackingEnabled, !paused, let app, let id = app.bundleIdentifier, !Self.ignored.contains(id) else { return }
        current = (id, app.localizedName ?? id, Date())
        saveIcon(for: app, id: id)
    }

    private func pause() {
        close(at: Date())
        paused = true
        publish()
    }

    private func resume() {
        paused = false
        if current == nil { switched(to: NSWorkspace.shared.frontmostApplication) }
    }

    /// Every 30 s: count the time so far (so the widget is close to live) and stop counting while nobody is at the Mac.
    private func tick() {
        let idle = CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: CGEventType(rawValue: ~0)!)
        if idle >= Self.idleLimit {
            if current != nil { close(at: Date().addingTimeInterval(-idle)) }
            return
        }
        if current == nil, !paused { switched(to: NSWorkspace.shared.frontmostApplication); return }
        if let c = current { close(at: Date()); current = (c.id, c.name, Date()) }
        publish()
        sendIfDue(force: false)
    }

    private func close(at end: Date) {
        guard let c = current else { return }
        acc.add(id: c.id, name: c.name, from: c.since, to: max(c.since, end), calendar: calendar)
        current = nil
    }

    private func publish() {
        acc.prune(keep: ScreenTimeShared.keepDays)
        if let file, let data = try? JSONEncoder().encode(acc) {
            try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? data.write(to: file, options: .atomic)
        }
        ScreenTimeHub.shared.storeLocal(thisMac())
    }

    private func thisMac() -> STDevice {
        acc.device(id: Identity.id, name: Identity.name, platform: "macos", now: Date(), calendar: calendar)
    }

    /// Sends this Mac's report to other computers that show screen time (another Mac).
    func sendIfDue(force: Bool) {
        guard ScreenTimeHub.trackingEnabled, force || Date().timeIntervalSince(lastSend) >= 300 else { return }
        lastSend = Date()
        let dev = thisMac()
        var icons: [String: Data] = [:]
        if let folder = ScreenTimeShared.folder(), let today = dev.days.last {
            for a in (today.apps ?? []).prefix(6) {
                if let d = try? Data(contentsOf: ScreenTimeShared.iconURL(appId: a.id, in: folder)) { icons[a.id] = d }
            }
        }
        ScreenTimeHub.shared.send(dev, icons: icons, force: force)
    }

    private func saveIcon(for app: NSRunningApplication, id: String) {
        guard !STStore.hasIcon(appId: id), let icon = app.icon else { return }
        let size = NSSize(width: 64, height: 64)
        let img = NSImage(size: size)
        img.lockFocus()
        icon.draw(in: NSRect(origin: .zero, size: size))
        img.unlockFocus()
        guard let tiff = img.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
              let png = rep.representation(using: .png, properties: [:]) else { return }
        STStore.saveIcon(png, appId: id)
    }
}
