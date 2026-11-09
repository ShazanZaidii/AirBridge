import Foundation

/// Call-audio diagnostics, appended to ~/Library/Logs/AirBridge/callaudio.log and NSLog'd.
/// Watch it with: tail -f ~/Library/Logs/AirBridge/callaudio.log
nonisolated final class CallAudioLog: @unchecked Sendable {
    static let shared = CallAudioLog()

    private let queue = DispatchQueue(label: "com.airbridge.callaudiolog", qos: .utility)
    private var handle: FileHandle?
    private let stamp: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    private init() {
        let dir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/AirBridge")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("callaudio.log")
        if let size = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int, size > 1_000_000 {
            try? FileManager.default.removeItem(at: url)
        }
        if !FileManager.default.fileExists(atPath: url.path) { FileManager.default.createFile(atPath: url.path, contents: nil) }
        handle = try? FileHandle(forWritingTo: url)
        handle?.seekToEndOfFile()
    }

    func log(_ line: String) {
        let now = Date()
        queue.async {
            let text = "\(self.stamp.string(from: now)) \(line)\n"
            if let d = text.data(using: .utf8) { self.handle?.write(d) }
        }
        NSLog("[AirBridge] callaudio: %@", line)
    }
}

/// Runs `body` on the main actor: right away when already on the main thread, else on the next turn.
nonisolated func callAudioOnMain(_ body: @escaping @MainActor @Sendable () -> Void) {
    if Thread.isMainThread { MainActor.assumeIsolated { body() } } else { DispatchQueue.main.async { body() } }
}
