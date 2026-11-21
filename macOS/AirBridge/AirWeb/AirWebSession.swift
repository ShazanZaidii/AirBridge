import AppKit
import Combine
import CoreImage.CIFilterBuiltins
import UniformTypeIdentifiers

/// One AirWeb session: the server, what it offers, what came in. Lives as long as the AirWeb window.
/// Same token and, when free, the same port after a restart, so a page that is already open keeps working.
@MainActor final class AirWebSession: ObservableObject {
    struct Offered: Identifiable, Equatable {
        let id: Int
        let url: URL
        let name: String
        let size: Int64
        let added: Date
        var downloads = 0
    }

    /// One upload from a browser: in progress, saved (`saved` set) or failed. A saved one is in the shared pool,
    /// downloadable by everyone connected, until `shared` is turned off here.
    struct Received: Identifiable, Equatable {
        let id: Int
        var name: String
        let size: Int64
        var done: Int64 = 0
        let started: Date
        var finished: Date?
        var saved: URL?
        var failed = false
        /// Which browser sent it (its device label) and that page's client id.
        var by: String?
        var owner: String?
        var shared = true
        var downloads = 0
    }

    @Published private(set) var offered: [Offered] = [] {
        didSet { bridge.setOffered(offered.map(Self.webFile)) }
    }
    @Published private(set) var received: [Received] = []
    /// The browsers connected right now.
    @Published private(set) var peers: [AirWebPeer] = []
    @Published private(set) var addresses: [String] = []
    @Published private(set) var address: String?
    @Published private(set) var port: UInt16 = 0
    @Published private(set) var failed = false
    @Published private(set) var deviceName = Identity.name
    @Published private(set) var link: String?
    @Published private(set) var qr: CGImage?
    @Published private(set) var notice: String?

    var token: String { server.token }
    private let bridge: AirWebBridge
    private let server: AirWebServer
    private var nextId = 1
    private var lastPort: UInt16 = 0
    /// Share-extension staging folders whose files are offered here; removed when the session ends.
    private var batches: [URL] = []
    private var activity: NSObjectProtocol?
    private var ticker: Timer?
    private var noticeToken = 0
    private var ended = false

    init() {
        let t = AirWebServer.newToken(), b = AirWebBridge()
        bridge = b
        server = AirWebServer(token: t, host: b)
    }

    /// Anything moving right now (a download or an upload).
    var isBusy: Bool { server.activeTransfers > 0 || received.contains { $0.finished == nil && !$0.failed } }

    // MARK: Life cycle

    func start() {
        guard !ended else { return }
        bridge.sink = { [weak self] event in
            let target = self
            onMain { target?.handle(event) }
        }
        refreshName()
        // The other device loses the page if this Mac sleeps; keep it awake while AirWeb is open.
        if activity == nil {
            activity = ProcessInfo.processInfo.beginActivity(
                options: [.userInitiated, .idleSystemSleepDisabled], reason: "AirWeb is sharing files")
        }
        let dest = TransferService.destination
        DispatchQueue.global(qos: .utility).async { AirWebUpload.purgeLeftovers(in: dest) }
        startServer()
        refreshAddresses()
        ticker = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                // Wi-Fi and Ethernet come and go without telling us; look again every few seconds.
                self?.refreshAddresses()
                self?.refreshName()
            }
        }
    }

    func startServer() {
        do {
            let p = try server.start(preferred: lastPort)
            port = p
            lastPort = p
            failed = false
        } catch {
            port = 0
            failed = true
        }
        updateLink()
    }

    /// Stops the server, lets the Mac sleep again and deletes the staged share files.
    /// `now`: delete them before returning (the app is quitting).
    func end(now: Bool = false) {
        guard !ended else { return }
        ended = true
        ticker?.invalidate()
        ticker = nil
        server.stop()
        port = 0
        peers = []
        updateLink()
        if let activity { ProcessInfo.processInfo.endActivity(activity) }
        activity = nil
        if now {
            for b in batches { try? FileManager.default.removeItem(at: b) }
        } else {
            ShareInbox.remove(batches)
        }
        batches = []
    }

    // MARK: Files offered

    /// Offers `urls`. `batches` are share-extension staging folders to delete when the session ends.
    func add(_ urls: [URL], batches newBatches: [URL] = []) {
        batches += newBatches.filter { !batches.contains($0) }
        var skipped = 0
        var fresh: [Offered] = []
        let now = Date()
        for raw in urls {
            let url = raw.standardizedFileURL.resolvingSymlinksInPath()
            if offered.contains(where: { $0.url == url }) || fresh.contains(where: { $0.url == url }) { continue }
            let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .isReadableKey])
            guard values?.isRegularFile == true, values?.isReadable == true else { skipped += 1; continue }
            let name = url.lastPathComponent.precomposedStringWithCanonicalMapping
            fresh.append(Offered(id: nextId, url: url, name: name, size: Int64(values?.fileSize ?? -1), added: now))
            nextId += 1
        }
        if !fresh.isEmpty { offered += fresh }
        if skipped > 0 {
            show(notice: skipped == urls.count && urls.count == 1
                 ? "Only files can be sent, not folders."
                 : "Some items were skipped. Only files can be sent, not folders.")
        }
    }

    func remove(_ id: Int) { offered.removeAll { $0.id == id } }

    /// A new link on purpose; pages open on the old one are told where to go.
    func newLink() { server.rotate(); updateLink() }

    /// A short, typeable link for a computer that can't scan the code, and how long it still works.
    func shortLink() -> (url: String, secondsLeft: Int64)? {
        guard let a = address, port != 0 else { return nil }
        let s = server.issueShort()
        return ("\(a):\(port)/j/\(s.code)", s.secondsLeft)
    }

    /// Stops sharing an uploaded file with the other browsers (it stays in Downloads on this Mac).
    func stopSharing(_ id: Int) {
        guard let i = received.firstIndex(where: { $0.id == id }) else { return }
        received[i].shared = false
        bridge.unshare(AirWebServer.uploadIdBase + id)
    }

    private static func webFile(_ f: Offered) -> AirWebFile {
        let ext = f.url.pathExtension
        let mime = ext.isEmpty ? nil : UTType(filenameExtension: ext)?.preferredMIMEType
        return AirWebFile(id: f.id, url: f.url, name: f.name, size: f.size, mime: mime,
                          added: Int64(f.added.timeIntervalSince1970 * 1000))
    }

    // MARK: Server events

    private func handle(_ e: AirWebBridge.Event) {
        switch e {
        case let .started(id, name, size, by):
            received.append(Received(id: id, name: name, size: size, started: Date(), by: by?.label, owner: by?.id))
        case let .progress(id, done):
            if let i = received.firstIndex(where: { $0.id == id }) { received[i].done = done }
        case let .finished(id, saved):
            guard let i = received.firstIndex(where: { $0.id == id }) else { return }
            received[i].finished = Date()
            if let saved {
                received[i].saved = saved
                received[i].name = saved.lastPathComponent
                received[i].done = received[i].size
            } else {
                received[i].failed = true
            }
        case let .downloaded(id):
            if id >= AirWebServer.uploadIdBase {
                if let i = received.firstIndex(where: { $0.id == id - AirWebServer.uploadIdBase }) { received[i].downloads += 1 }
            } else if let i = offered.firstIndex(where: { $0.id == id }) {
                offered[i].downloads += 1
            }
        case let .peers(list):
            peers = list
        }
    }

    // MARK: Address, link, QR

    /// Re-reads this Mac's LAN addresses; keeps the one in use if it is still there.
    func refreshAddresses() {
        let list = AirWebAddresses.current()
        if addresses != list { addresses = list }
        let next = address.flatMap { list.contains($0) ? $0 : nil } ?? list.first
        if address != next {
            address = next
            updateLink()
        }
    }

    /// Several addresses (Wi-Fi and Ethernet): show the next one.
    func nextAddress() {
        guard addresses.count > 1 else { return }
        let i = address.flatMap { addresses.firstIndex(of: $0) } ?? -1
        address = addresses[(i + 1) % addresses.count]
        updateLink()
    }

    private func refreshName() {
        let n = Identity.name
        if deviceName != n { deviceName = n }
        bridge.setName(n)
    }

    private func updateLink() {
        let new = address.flatMap { a in port != 0 ? AirWebServer.url(address: a, port: port, token: token) : nil }
        guard new != link else { return }
        link = new
        qr = new.flatMap(Self.qrCode)
    }

    /// Black on white, scaled up with nearest-neighbour sampling so the modules stay sharp.
    private static func qrCode(_ text: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.samplingNearest()
            .transformed(by: CGAffineTransform(scaleX: 12, y: 12)) else { return nil }
        return CIContext().createCGImage(output, from: output.extent.integral)
    }

    private func show(notice text: String) {
        notice = text
        noticeToken += 1
        let token = noticeToken
        DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in
            MainActor.assumeIsolated {
                if let self, self.noticeToken == token { self.notice = nil }
            }
        }
    }
}

/// The server's view of the session, safe to call from its connection threads.
nonisolated final class AirWebBridge: AirWebHost, @unchecked Sendable {
    enum Event: Sendable {
        case started(id: Int, name: String, size: Int64, by: AirWebPeer?)
        case progress(id: Int, done: Int64)
        case finished(id: Int, saved: URL?)
        case downloaded(id: Int)
        case peers([AirWebPeer])
    }

    private let lock = NSLock()
    /// What this Mac added.
    private var files: [AirWebFile] = []
    /// What browsers uploaded (pool id → file). Kept here, not in the session, so a file is in the pool before its
    /// upload is answered (the session only hears about it a moment later, on the main thread).
    private var uploads: [Int: AirWebFile] = [:]
    private var uploaders: [Int: AirWebPeer] = [:]
    private var name = "Mac"
    var sink: (@Sendable (Event) -> Void)? {
        get { lock.withLock { _sink } }
        set { lock.withLock { _sink = newValue } }
    }
    private var _sink: (@Sendable (Event) -> Void)?

    /// Read once from the app bundle.
    private static let pageText: String = {
        guard let url = Bundle.main.url(forResource: "airweb", withExtension: "html"),
              let s = try? String(contentsOf: url, encoding: .utf8) else { return "<!doctype html><title>AirWeb</title>" }
        return s
    }()

    func setOffered(_ list: [AirWebFile]) { lock.withLock { files = list } }
    func setName(_ n: String) { lock.withLock { name = n } }

    func deviceName() -> String { lock.withLock { name } }
    func page() -> String { Self.pageText }
    /// The shared pool: this Mac's files, then the uploads in the order they arrived.
    func offered() -> [AirWebFile] { lock.withLock { files + uploads.values.sorted { $0.id < $1.id } } }

    /// Takes an uploaded file out of the pool (it stays in Downloads on this Mac).
    func unshare(_ poolId: Int) { _ = lock.withLock { uploads.removeValue(forKey: poolId) } }

    func freeSpace() -> Int64 {
        var dir = TransferService.destination
        if !FileManager.default.fileExists(atPath: dir.path) { dir = dir.deletingLastPathComponent() }
        guard let free = try? dir.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage else { return -1 }
        // Keep a margin so a full upload never leaves the Mac with no space at all.
        return max(0, free - 64 * 1024 * 1024)
    }

    func uploadDirectory() throws -> URL {
        let dir = TransferService.destination
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    func cleanName(_ raw: String) -> String {
        Proto.sanitizeFileName(raw.precomposedStringWithCanonicalMapping)
    }

    func uploadStarted(id: Int, name: String, size: Int64, by: AirWebPeer?) {
        if let by { lock.withLock { uploaders[id] = by } }
        sink?(.started(id: id, name: name, size: size, by: by))
    }
    func peersChanged(_ peers: [AirWebPeer]) { sink?(.peers(peers)) }

    /// The QR code of `text` from CoreImage, one module per pixel (no quiet zone; the page adds it).
    func qr(_ text: String) -> [[Bool]]? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let image = filter.outputImage else { return nil }
        let w = Int(image.extent.width), h = Int(image.extent.height)
        guard w > 0, w == h, w < 200 else { return nil }
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        let space = CGColorSpaceCreateDeviceRGB()
        CIContext(options: [.workingColorSpace: NSNull()]).render(image, toBitmap: &pixels, rowBytes: w * 4,
                                                                  bounds: image.extent, format: .RGBA8, colorSpace: space)
        // Row 0 of the bitmap is the top of the image; a dark module has a low red value.
        return (0..<h).map { y in (0..<w).map { x in pixels[(y * w + x) * 4] < 128 } }
    }
    func uploadProgress(id: Int, done: Int64) { sink?(.progress(id: id, done: done)) }
    func uploadFinished(id: Int, saved: URL?) {
        let by: AirWebPeer? = lock.withLock { uploaders.removeValue(forKey: id) }
        if let saved {
            let values = try? saved.resourceValues(forKeys: [.fileSizeKey])
            let ext = saved.pathExtension
            let file = AirWebFile(id: AirWebServer.uploadIdBase + id, url: saved, name: saved.lastPathComponent,
                                  size: Int64(values?.fileSize ?? -1),
                                  mime: ext.isEmpty ? nil : UTType(filenameExtension: ext)?.preferredMIMEType,
                                  added: Int64(Date().timeIntervalSince1970 * 1000), by: by?.label, owner: by?.id)
            lock.withLock { uploads[file.id] = file }
        }
        sink?(.finished(id: id, saved: saved))
    }
    func downloaded(_ file: AirWebFile) { sink?(.downloaded(id: file.id)) }
}

/// This Mac's LAN IPv4 addresses (Wi-Fi, Ethernet), the built-in Wi-Fi first. VPN tunnels, bridges,
/// AWDL and link-local addresses are left out: the other device can't reach those.
nonisolated enum AirWebAddresses {
    private static let skipped = ["lo", "utun", "bridge", "awdl", "llw", "ipsec", "ppp", "gif", "stf",
                                  "vmnet", "vnic", "anpi", "ap", "feth", "pktap"]

    static func current() -> [String] {
        var found: [(name: String, ip: String)] = []
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0 else { return [] }
        defer { freeifaddrs(list) }
        var p = list
        while let ifa = p?.pointee {
            defer { p = ifa.ifa_next }
            guard let sa = ifa.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) else { continue }
            let flags = Int32(ifa.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_RUNNING != 0, flags & IFF_LOOPBACK == 0 else { continue }
            let name = String(cString: ifa.ifa_name)
            if skipped.contains(where: { name.hasPrefix($0) }) { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0
            else { continue }
            let ip = String(cString: host)
            if ip.hasPrefix("169.254.") || ip.hasPrefix("127.") { continue }
            if !found.contains(where: { $0.ip == ip }) { found.append((name, ip)) }
        }
        return found.sorted { a, b in
            let ra = a.name == "en0" ? 0 : 1, rb = b.name == "en0" ? 0 : 1
            return ra != rb ? ra < rb : a.name.localizedStandardCompare(b.name) == .orderedAscending
        }.map(\.ip)
    }
}

/// "Now", "10 sec ago" … "59 min ago", "1 hr ago" … then a date.
nonisolated func airWebAgo(_ date: Date, now: Date) -> String {
    let s = Int(max(0, now.timeIntervalSince(date)))
    switch s {
    case ..<10: return "Now"
    case ..<60: return "\(s / 10 * 10) sec ago"
    case ..<3600: return s < 120 ? "1 min ago" : "\(s / 60) min ago"
    case ..<86_400: return s < 7200 ? "1 hr ago" : "\(s / 3600) hr ago"
    default:
        let sameYear = Calendar.current.isDate(date, equalTo: now, toGranularity: .year)
        return sameYear ? date.formatted(.dateTime.day().month(.abbreviated))
                        : date.formatted(.dateTime.day().month(.abbreviated).year())
    }
}
