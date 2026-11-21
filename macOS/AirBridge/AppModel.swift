import AppKit
import Combine
import SwiftUI
import CoreImage.CIFilterBuiltins
import UserNotifications

/// Main-actor UI state fed by the network half.
@MainActor final class AppModel: ObservableObject {
    static let shared = AppModel()

    @Published var devices: [DeviceRow] = []
    @Published var hosts: [HostRow] = []
    @Published var readyPeers: [PeerInfo] = []
    @Published var localIP: String?
    @Published var port: UInt16?
    @Published var deviceName = Identity.name

    @Published var qrImage: NSImage?
    @Published var allowPairing = true
    @Published var pairingLocked = false
    private var pairingLink: String?

    /// Confirm-mode pairings waiting for the user (§3.3), oldest first.
    @Published var pairPrompts: [PairPrompt] = []

    /// Older entries that look like earlier installs of a device that just connected: asked about, never removed unasked.
    @Published var staleOffer: StaleOffer?

    /// Control state from the input half, e.g. "Controlled by Pixel 9" or
    /// "Controlling Pixel 9 · Ctrl+Opt+Cmd+Esc to return". nil = idle.
    ///
    /// Contract: post `Notification.Name("AirBridgeControlStatus")` on `NotificationCenter.default` from any
    /// thread with `userInfo: ["text": String]`. A missing, nil (`NSNull`) or empty `text` clears the status.
    /// Optional keys: `"peer"` (peer id, String) and `"role"` (`"controlling"` | `"controlled"`) let the menu
    /// mark that device's row; without them only the banner is shown.
    @Published var controlStatus: String?
    @Published var controlPeer: String?
    @Published var controlRole: String?

    /// Screen-recording permission (gates the `mirror_source` capability, §8.7).
    @Published var screenCaptureAllowed = CGPreflightScreenCaptureAccess()

    @Published var transfers: [TransferStatus] = []
    @Published var noticeText: String?
    private var noticeToken = 0

    /// Files handed over by the share extension, waiting for a target device.
    @Published var pendingShare: [URL] = []
    private var pendingBatches: [URL] = []

    func notice(_ text: String) {
        noticeText = text
        noticeToken += 1
        let token = noticeToken
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) {
            MainActor.assumeIsolated {
                if AppModel.shared.noticeToken == token { AppModel.shared.noticeText = nil }
            }
        }
    }

    func applyNetwork(devices: [DeviceRow], hosts: [HostRow], ip: String?, port: UInt16?) {
        if self.devices != devices { self.devices = devices }
        if self.hosts != hosts { self.hosts = hosts }
        if localIP != ip { localIP = ip }
        if self.port != port { self.port = port }
        if deviceName != Identity.name { deviceName = Identity.name }
    }

    func applyPairing(_ snap: Pairing.Snapshot, link: String?) {
        allowPairing = snap.allowPairing
        pairingLocked = (snap.lockedUntil ?? .distantPast) > Date()
        if link != pairingLink {
            pairingLink = link
            qrImage = link.flatMap(AppModel.qrCode)
        }
    }

    // MARK: Control status (input half → menu)

    private static let controlStatusNote = Notification.Name("AirBridgeControlStatus")
    private var controlObserver: NSObjectProtocol?

    /// Starts listening for `AirBridgeControlStatus` (see `controlStatus`).
    func startObservingControlStatus() {
        guard controlObserver == nil else { return }
        controlObserver = NotificationCenter.default.addObserver(
            forName: AppModel.controlStatusNote, object: nil, queue: nil) { note in
            let info = note.userInfo ?? [:]
            let text = (info["text"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            let peer = info["peer"] as? String
            let role = info["role"] as? String
            onMain {
                let m = AppModel.shared
                if m.controlStatus != text { m.controlStatus = text }
                let p = text == nil ? nil : peer, r = text == nil ? nil : role
                if m.controlPeer != p { m.controlPeer = p }
                if m.controlRole != r { m.controlRole = r }
            }
        }
    }

    // MARK: Screen mirroring permission

    func refreshScreenCapture() {
        let now = CGPreflightScreenCaptureAccess()
        if screenCaptureAllowed != now { screenCaptureAllowed = now }
    }

    /// Shows the system prompt (first time) or opens the Screen Recording settings pane.
    func requestScreenCapture() {
        if !CGRequestScreenCaptureAccess(),
           let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") {
            NSWorkspace.shared.open(url)
        }
        refreshScreenCapture()
        if !screenCaptureAllowed {
            notice("After allowing AirBridge under Screen Recording, quit and reopen it to enable mirroring")
        }
    }

    // MARK: Confirm-mode pairing prompts

    func showPairPrompt(_ p: PairPrompt) {
        if let i = pairPrompts.firstIndex(where: { $0.id == p.id }) { pairPrompts[i] = p } else { pairPrompts.append(p) }
        PairPromptPanels.shared.show(p)
    }

    func endPairPrompt(_ id: String) {
        pairPrompts.removeAll { $0.id == id }
        PairPromptPanels.shared.dismiss(id)
    }

    func respond(to p: PairPrompt, accept: Bool) {
        PeerManager.shared.respondToPairing(promptId: p.id, accept: accept)
        if !accept { endPairPrompt(p.id) }
    }

    func updateTransfer(_ s: TransferStatus) {
        TransferHUD.shared.ingest(s)
        if let i = transfers.firstIndex(where: { $0.id == s.id }) { transfers[i] = s } else { transfers.append(s) }
        switch s.phase {
        case .done, .failed:
            let id = s.id
            DispatchQueue.main.asyncAfter(deadline: .now() + 6) {
                MainActor.assumeIsolated { AppModel.shared.transfers.removeAll { $0.id == id } }
            }
        default:
            break
        }
    }

    // MARK: Share extension hand-off

    func addPendingShare(batches: [URL], files: [URL]) {
        let fresh = batches.filter { !pendingBatches.contains($0) }
        guard !fresh.isEmpty else { return }
        let freshFiles = files.filter { f in fresh.contains { f.path.hasPrefix($0.path + "/") } }
        pendingBatches += fresh
        pendingShare += freshFiles
    }

    /// Sends the shared files to every device in `peerIds` at once. The staged copies are deleted
    /// only after the last of those transfers has ended, since each one reads the files as it goes.
    func sendPendingShare(to peerIds: [String]) {
        let batches = pendingBatches
        let files = pendingShare
        pendingBatches = []
        pendingShare = []
        guard !peerIds.isEmpty, !files.isEmpty else { ShareInbox.remove(batches); return }
        let left = Countdown(peerIds.count) { ShareInbox.remove(batches) }
        for id in peerIds { PeerManager.shared.sendFiles(files, to: id) { left.tick() } }
    }

    /// Hands the shared files (and their staging folders, which the taker now deletes) to someone else, e.g. AirWeb.
    func takePendingShare() -> (files: [URL], batches: [URL]) {
        defer { pendingBatches = []; pendingShare = [] }
        return (pendingShare, pendingBatches)
    }

    func cancelPendingShare() {
        ShareInbox.remove(pendingBatches)
        pendingBatches = []
        pendingShare = []
    }

    private static func qrCode(_ string: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: 160, height: 160))
    }
}

/// Main-actor side effects of transfers.
@MainActor enum TransferUI {
    static func update(_ s: TransferStatus) { AppModel.shared.updateTransfer(s) }

    /// A received picture also goes on the clipboard as an image, so it pastes into apps that want the picture itself.
    private static func addPictureToClipboard(first: URL?, after change: Int) {
        guard let first, ClipboardImages.isImage(first) else { return }
        Task.detached(priority: .userInitiated) {
            guard let png = ClipboardImages.pngData(for: first) else { return }
            await MainActor.run {
                if ClipboardImages.addPNG(png, to: NSPasteboard.general, expectedChange: change) {
                    ClipboardSync.shared.noteOwnWrite()
                }
            }
        }
    }

    /// A clipboard transfer (§5.1) arrived: one picture becomes an image on the clipboard, anything else a file list.
    /// No notice, no files in Downloads.
    static func receivedClipboard(_ urls: [URL], from peer: String) {
        guard !urls.isEmpty else { return }
        ClipboardInbox.purgeOld()
        if urls.count == 1, ClipboardImages.isImage(urls[0]) {
            let url = urls[0]
            Task.detached(priority: .userInitiated) {
                guard let png = ClipboardImages.pngData(for: url) else { return }
                await MainActor.run {
                    ClipboardImages.setImage(png: png, on: NSPasteboard.general)
                    ClipboardSync.shared.noteReceivedImage(png)
                }
            }
            return
        }
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.writeObjects(urls as [NSURL])
        ClipboardSync.shared.noteOwnWrite()
        addPictureToClipboard(first: urls.first, after: pb.changeCount)
    }

    static func receivedFiles(_ urls: [URL], from peer: String) {
        guard !urls.isEmpty else { return }
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.writeObjects(urls as [NSURL])
        ClipboardSync.shared.noteOwnWrite()
        addPictureToClipboard(first: urls.first, after: pb.changeCount)

        let content = UNMutableNotificationContent()
        content.title = "AirBridge"
        content.subtitle = "Received from \(peer)"
        content.body = urls.count == 1 ? urls[0].lastPathComponent : "\(urls.count) files — copied to the clipboard"
        content.sound = .default
        content.categoryIdentifier = "FILE_TRANSFER"
        content.userInfo = ["filePaths": urls.map(\.path)]
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }
}

/// Runs `done` once after `tick()` has been called `count` times, from any thread.
nonisolated final class Countdown: @unchecked Sendable {
    private let lock = NSLock()
    private var left: Int
    private let done: () -> Void
    init(_ count: Int, done: @escaping () -> Void) { left = count; self.done = done }
    func tick() {
        lock.lock(); left -= 1; let fire = left == 0; lock.unlock()
        if fire { done() }
    }
}

/// Files shared via the share extension arrive in the App Group container:
/// `Pending/<batch-uuid>/<files>` (the extension renames a hidden temp dir into place when done).
nonisolated enum ShareInbox {
    static let groupId = "group.com.shazan.AirBridge"

    static var pendingRoot: URL {
        let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupId)
            ?? FileManager.default.homeDirectoryForCurrentUser
                .appendingPathComponent("Library/Group Containers/\(groupId)", isDirectory: true)
        return base.appendingPathComponent("Pending", isDirectory: true)
    }

    /// Reads pending batches off the main thread and presents the target picker.
    static func open(batch: String?) {
        DispatchQueue.global(qos: .userInitiated).async {
            let fm = FileManager.default
            let root = pendingRoot
            var dirs: [URL]
            if let batch, UUID(uuidString: batch) != nil {
                dirs = [root.appendingPathComponent(batch, isDirectory: true)]
            } else {
                dirs = (try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil,
                                                    options: [.skipsHiddenFiles])) ?? []
            }
            dirs = dirs.filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true }
            var files: [URL] = []
            for d in dirs {
                let items = (try? fm.contentsOfDirectory(at: d, includingPropertiesForKeys: [.isRegularFileKey],
                                                         options: [.skipsHiddenFiles])) ?? []
                files += items.filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
            }
            guard !files.isEmpty else { return }
            let (batches, found) = (dirs, files)
            onMain {
                // A picker closed without choosing leaves stale files behind; don't fold them into this share.
                if AppDelegate.shared?.isTargetSelectionVisible != true { AppModel.shared.cancelPendingShare() }
                AppModel.shared.addPendingShare(batches: batches, files: found)
                AppDelegate.shared?.showTargetSelection()
            }
        }
    }

    static func remove(_ batches: [URL]) {
        guard !batches.isEmpty else { return }
        DispatchQueue.global(qos: .utility).async {
            for b in batches { try? FileManager.default.removeItem(at: b) }
        }
    }

    /// Deletes batches left over from earlier runs (older than a day).
    static func purgeStale() {
        DispatchQueue.global(qos: .utility).async {
            let fm = FileManager.default
            let items = (try? fm.contentsOfDirectory(at: pendingRoot, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
            let cutoff = Date().addingTimeInterval(-86_400)
            for item in items {
                let date = (try? item.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
                if (date ?? .distantPast) < cutoff { try? fm.removeItem(at: item) }
            }
        }
    }
}
