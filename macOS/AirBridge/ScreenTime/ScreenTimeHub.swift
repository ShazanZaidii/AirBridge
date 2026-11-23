import AppKit
import Darwin
import CoreGraphics
import WidgetKit

/// Screen time for the AirBridge widget (docs/protocol.md §15).
///
/// - Other devices send `screen_time` (and `screen_time_icon`); each becomes a device in the shared snapshot the widget reads.
/// - This Mac's own time comes from the records macOS keeps (MacUsageHistory: today from midnight and the days before AirBridge
///   ran) once Full Disk Access is on. Without it AirBridge counts live: which app is in front, paused while idle (no input for
///   5 minutes), while the screen sleeps and while it is locked, only from when AirBridge runs.
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
                let isNew = !snapshot.devices.contains { $0.id == dev.id }
                snapshot.upsert(dev)
                STStore.save(snapshot)
                if Date() < liveUntil { reloadNow() } else { scheduleReload(soon: isNew) }
                DispatchQueue.main.async { MainActor.assumeIsolated { ScreenTimeWindow.shared.model.reloadIfOpen() } }
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
            scheduleReload(soon: true)
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

    /// Widget redraws: the numbers keep flowing into the shared file all the time (cheap), but the widget is only asked to
    /// redraw about once an hour in the background, so it doesn't spend macOS's small daily redraw allowance. When someone
    /// actually looks (the Screen Time page or the menu bar panel opens), `pullNow` fetches fresh numbers from the devices and
    /// redraws at once; the widget itself also counts its drawing as stale after 5 minutes, so opening Notification Center
    /// shows the latest saved numbers. `soon`: a device's first report or a forgotten device (15 s).
    private static let reloadEvery: TimeInterval = 3600
    /// Until when fresh reports redraw the widget straight away (someone is looking).
    private var liveUntil = Date.distantPast
    static let sourceCap = "screentime_source"
    private var lastReload = Date.distantPast

    private func scheduleReload(soon: Bool = false) {
        guard !reloadScheduled else { return }
        let wait = soon ? 15 : max(15, Self.reloadEvery - Date().timeIntervalSince(lastReload))
        reloadScheduled = true
        queue.asyncAfter(deadline: .now() + wait) { [self] in
            reloadScheduled = false
            lastReload = Date()
            WidgetCenter.shared.reloadTimelines(ofKind: ScreenTimeShared.widgetKind)
        }
    }

    /// Someone is looking (the Screen Time page or the menu bar panel opened): ask every device for its numbers now, refresh
    /// this Mac's, and redraw the widget as they arrive. Requests while AirBridge is in use don't count against the widget
    /// redraw allowance.
    func pullNow() {
        let peers = PeerManager.shared.peers().filter { $0.caps.contains(Self.sourceCap) }
        queue.async { [self] in
            liveUntil = Date().addingTimeInterval(20)
            for p in peers { _ = PeerManager.shared.send(to: p.id, ["t": "screen_time_request"]) }
        }
        onMain {
            LocalScreenTime.shared.publishNow()
            LocalScreenTime.shared.refreshHistory(force: true)
        }
        queue.asyncAfter(deadline: .now() + 1.5) { [self] in reloadNow() }
    }

    private var lastImmediate = Date.distantPast

    /// Redraw now (coalesced to one per second).
    private func reloadNow() {
        guard Date().timeIntervalSince(lastImmediate) > 1 else { return }
        lastImmediate = Date()
        lastReload = Date()
        WidgetCenter.shared.reloadTimelines(ofKind: ScreenTimeShared.widgetKind)
    }

    /// A new AirBridge version is running: redraw the widget once, so it uses the new widget code (an old drawing stays on
    /// screen until WidgetKit redraws it).
    func reloadAfterUpdate() {
        let build = (Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "") + "|" +
            String(Int((try? Bundle.main.executableURL?.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate?.timeIntervalSince1970) ?? 0) ?? 0)
        let key = "ScreenTimeWidgetBuild"
        guard UserDefaults.standard.string(forKey: key) != build else { return }
        UserDefaults.standard.set(build, forKey: key)
        // A widget process started by the old version keeps running the old code from memory (macOS does not restart it when
        // the app is replaced), so it would go on drawing the old widget with the old click behaviour. End it; the system
        // starts the new one for the redraw below.
        Self.endStaleWidgetProcess()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { WidgetCenter.shared.reloadAllTimelines() }
    }

    /// Ends AirBridge's widget extension process(es) that started before this app did.
    private static func endStaleWidgetProcess() {
        let appStart = NSRunningApplication.current.launchDate ?? Date()
        let count = proc_listallpids(nil, 0)
        guard count > 0 else { return }
        var pids = [pid_t](repeating: 0, count: Int(count) + 32)
        let n = proc_listallpids(&pids, Int32(pids.count * MemoryLayout<pid_t>.size))
        for pid in pids.prefix(Int(max(0, n))) where pid > 0 {
            var path = [CChar](repeating: 0, count: 4096)
            guard proc_pidpath(pid, &path, UInt32(path.count)) > 0,
                  String(cString: path).hasSuffix("AirBridgeWidgets.appex/Contents/MacOS/AirBridgeWidgets") else { continue }
            var info = proc_bsdinfo()
            guard proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, Int32(MemoryLayout<proc_bsdinfo>.size)) > 0 else { continue }
            let started = Date(timeIntervalSince1970: TimeInterval(info.pbi_start_tvsec))
            if started < appStart.addingTimeInterval(-2) { kill(pid, SIGTERM) }
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
    /// This Mac's time from macOS's own records, when AirBridge may read them (Full Disk Access). Used instead of the live count.
    private var history: STAccumulator?
    private var historyReading = false
    private var lastHistoryRead = Date.distantPast
    /// Whether macOS's records could be read the last time (for Settings).
    private(set) var historyAvailable = false
    private static let historyEvery: TimeInterval = 120
    /// The power log's display and sleep changes, re-read at most every 10 minutes (it takes a second or two).
    nonisolated(unsafe) private static var power: (read: Date, breaks: [MacUsageHistory.Break]) = (.distantPast, [])

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
        dnc.addObserver(forName: NSNotification.Name("com.apple.screenIsUnlocked"), object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { LocalScreenTime.shared.unlocked() }
        }
        switched(to: NSWorkspace.shared.frontmostApplication)
        timer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { _ in MainActor.assumeIsolated { LocalScreenTime.shared.tick() } }
        refreshHistory(force: true)
    }

    private func unlocked() {
        guard ScreenTimeHub.trackingEnabled else { return }
        acc.unlock(at: Date(), calendar: calendar)
    }

    /// Reads macOS's records again (off the main thread), at most every two minutes unless `force`.
    func refreshHistory(force: Bool = false) {
        guard ScreenTimeHub.trackingEnabled, !historyReading, force || Date().timeIntervalSince(lastHistoryRead) >= Self.historyEvery else { return }
        historyReading = true
        lastHistoryRead = Date()
        let cal = calendar
        DispatchQueue.global(qos: .utility).async {
            let now = Date()
            let since = cal.date(byAdding: .day, value: -(ScreenTimeShared.keepDays - 1), to: cal.startOfDay(for: now)) ?? now
            if now.timeIntervalSince(LocalScreenTime.power.read) > 600 {
                LocalScreenTime.power = (now, MacUsageHistory.powerBreaks(MacUsageHistory.readPowerLog()))
            }
            let r = MacUsageHistory.read(since: since, now: now, power: LocalScreenTime.power.breaks)
            let acc: STAccumulator? = r.spans.isEmpty ? nil
                : MacUsageHistory.accumulate(r.spans, unlocks: r.unlocks, since: since, calendar: cal, name: LocalScreenTime.appName)
            var st = r.status
            if let acc { st.days = acc.days.mapValues { Int($0.seconds.rounded()) } }
            LocalScreenTime.writeStatus(st)
            let bundles = Array(Set(r.spans.map(\.bundle)))
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    let me = LocalScreenTime.shared
                    me.historyReading = false
                    me.history = acc
                    me.historyAvailable = acc != nil
                    for b in bundles { me.saveIcon(bundleId: b) }
                    me.publish()
                }
            }
        }
    }

    /// What was found, for checking against Apple's Screen Time: ScreenTime/mac-history.json in the shared folder.
    nonisolated private static func writeStatus(_ st: MacUsageHistory.Status) {
        guard let url = ScreenTimeShared.folder()?.appendingPathComponent("mac-history.json") else { return }
        let enc = JSONEncoder()
        enc.dateEncodingStrategy = .iso8601
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        try? enc.encode(st).write(to: url, options: .atomic)
    }

    /// An app's name from its bundle id (the installed app's display name).
    nonisolated static func appName(_ bundleId: String) -> String {
        guard let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleId) else { return bundleId }
        return FileManager.default.displayName(atPath: url.path).replacingOccurrences(of: ".app", with: "")
    }

    /// An app's category from its Info.plist, else from the list of well-known apps.
    nonisolated static func appCategory(_ bundleId: String) -> String? {
        if let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleId),
           let c = STCategory.fromApple(Bundle(url: url)?.infoDictionary?["LSApplicationCategoryType"] as? String) { return c }
        return STCategory.guess(bundleId)
    }

    /// The setting was switched: stop counting now, or start again.
    func settingChanged() {
        if ScreenTimeHub.trackingEnabled { resume() } else { pause() }
    }

    private func switched(to app: NSRunningApplication?) {
        close(at: Date())
        guard ScreenTimeHub.trackingEnabled, !paused, let app, let id = app.bundleIdentifier, !Self.ignored.contains(id) else { return }
        current = (id, app.localizedName ?? id, Date())
        acc.open(id: id, name: app.localizedName ?? id, at: Date(), calendar: calendar)
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
        refreshHistory()
    }

    private func close(at end: Date) {
        guard let c = current else { return }
        acc.add(id: c.id, name: c.name, from: c.since, to: max(c.since, end), calendar: calendar)
        current = nil
    }

    /// Writes this Mac's numbers now (the page or menu just opened).
    func publishNow() {
        if let c = current { close(at: Date()); current = (c.id, c.name, Date()) }
        publish()
    }

    private func publish() {
        acc.prune(keep: ScreenTimeShared.keepDays)
        if let file, let data = try? JSONEncoder().encode(acc) {
            try? FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? data.write(to: file, options: .atomic)
        }
        ScreenTimeHub.shared.storeLocal(thisMac())
    }

    private var categories: [String: String?] = [:]

    private func thisMac() -> STDevice {
        let source = history ?? acc
        return source.device(id: Identity.id, name: Identity.name, platform: "macos", now: Date(), calendar: calendar,
                             maxDays: ScreenTimeShared.keepDays) { [self] id in
            if let c = categories[id] { return c }
            let c = Self.appCategory(id)
            categories[id] = c
            return c
        }
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

    /// The icon of an app that may not be running (apps found in macOS's records).
    private func saveIcon(bundleId: String) {
        guard !STStore.hasIcon(appId: bundleId), let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleId) else { return }
        savePNG(NSWorkspace.shared.icon(forFile: url.path), id: bundleId)
    }

    private func saveIcon(for app: NSRunningApplication, id: String) {
        guard !STStore.hasIcon(appId: id), let icon = app.icon else { return }
        savePNG(icon, id: id)
    }

    private func savePNG(_ icon: NSImage, id: String) {
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
