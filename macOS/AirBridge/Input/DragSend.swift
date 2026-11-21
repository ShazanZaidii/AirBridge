import Foundation

/// Sends the items of a carried drag to the device they were dropped on (protocol.md §8.8) as one normal
/// transfer (§6). Files go as they are; folders (and packages such as .app bundles) are zipped first with
/// `ditto`, which keeps resource forks and permissions, into a temporary folder that is removed afterwards.
nonisolated enum DragSend {
    static func send(_ urls: [URL], to peer: String, staged: [URL] = []) {
        guard !urls.isEmpty else { return }
        DispatchQueue.global(qos: .userInitiated).async {
            let temp = FileManager.default.temporaryDirectory
                .appendingPathComponent("AirBridge-drag-\(UUID().uuidString)", isDirectory: true)
            var out: [URL] = []
            var usedTemp = false
            for u in urls {
                let dir = (try? u.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory ?? false
                guard dir else { out.append(u); continue }
                if !usedTemp {
                    try? FileManager.default.createDirectory(at: temp, withIntermediateDirectories: true)
                    usedTemp = true
                }
                if let zip = zipFolder(u, into: temp) { out.append(zip) }
                else { InputLog.shared.log("drag: could not zip \(u.lastPathComponent); skipped") }
            }
            let cleanup: () -> Void = {
                if usedTemp { try? FileManager.default.removeItem(at: temp) }
                DropItems.removeTempLater(staged) // copies of short-lived files (see DropItems.stageVolatile)
            }
            guard !out.isEmpty else { cleanup(); return }
            InputLog.shared.log("drag: sending \(out.count) item(s) to \(peer)")
            PeerManager.shared.sendFiles(out, to: peer, cleanup: cleanup)
        }
    }

    /// `Photos/` → `<temp>/Photos.zip` (de-duplicated if two dragged folders share a name).
    private static func zipFolder(_ src: URL, into temp: URL) -> URL? {
        let base = src.lastPathComponent.isEmpty ? "Folder" : src.lastPathComponent
        var dst = temp.appendingPathComponent(base + ".zip")
        var n = 2
        while FileManager.default.fileExists(atPath: dst.path) {
            dst = temp.appendingPathComponent("\(base) (\(n)).zip")
            n += 1
        }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        p.arguments = ["-c", "-k", "--sequesterRsrc", "--keepParent", src.path, dst.path]
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        do {
            try p.run()
            p.waitUntilExit()
        } catch { return nil }
        return p.terminationStatus == 0 && FileManager.default.fileExists(atPath: dst.path) ? dst : nil
    }
}
