import Foundation

/// Live input diagnostics: one line per received action / click / button / drop and how it was carried
/// out (path, result, time taken), appended to ~/Library/Logs/AirBridge/input.log and NSLog'd.
/// Watch it with: tail -f ~/Library/Logs/AirBridge/input.log
nonisolated final class InputLog: @unchecked Sendable {
    static let shared = InputLog()

    private let queue = DispatchQueue(label: "com.airbridge.inputlog", qos: .utility)
    private var handle: FileHandle?
    private let stamp: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    private init() {
        let dir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/AirBridge")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("input.log")
        // Keep it small: start over when it passes 2 MB.
        if let size = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int, size > 2_000_000 {
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
        NSLog("[AirBridge] input: %@", line)
    }

    /// Runs `body`, logging `what` with how long it took.
    func timed(_ what: String, _ body: () -> String) {
        let t0 = CFAbsoluteTimeGetCurrent()
        let result = body()
        log("\(what) -> \(result) (\(Int((CFAbsoluteTimeGetCurrent() - t0) * 1000)) ms)")
    }
}
