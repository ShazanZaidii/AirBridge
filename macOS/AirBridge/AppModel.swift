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

    @Published var pairingCode = ""
    @Published var qrImage: NSImage?
    @Published var allowPairing = true
    @Published var pairingLocked = false
    private var pairingLink: String?

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
        pairingCode = snap.code
        allowPairing = snap.allowPairing
        pairingLocked = (snap.lockedUntil ?? .distantPast) > Date()
        if link != pairingLink {
            pairingLink = link
            qrImage = link.flatMap(AppModel.qrCode)
        }
    }

    func updateTransfer(_ s: TransferStatus) {
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

    func sendPendingShare(to peerId: String) {
        let batches = pendingBatches
        PeerManager.shared.sendFiles(pendingShare, to: peerId) { ShareInbox.remove(batches) }
        pendingBatches = []
        pendingShare = []
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

    static func receivedFiles(_ urls: [URL], from peer: String) {
        guard !urls.isEmpty else { return }
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.writeObjects(urls as [NSURL])
        ClipboardSync.shared.noteOwnWrite()

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
