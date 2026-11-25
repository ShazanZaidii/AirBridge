import AppKit
import Foundation

/// "Mirror without asking" (protocol.md §8.10). Android asks before every screen share unless the app holds the
/// PROJECT_MEDIA app-op, which only a shell can grant. This Mac already has an adb link to phones set up for Pro
/// mirroring, so when the phone asks, it runs that one command there:
///
///     phone → `{"t":"mirror_trust","op":"grant"|"revoke","pkg":"com.shazan.airbridge"}`
///     Mac   → `{"t":"mirror_trust_result","op":…,"ok":true}` or `{…,"ok":false,"why":"setup"|"unreachable"|"failed","msg":…}`
///
/// A phone that isn't set up for Pro mirroring yet gets the setup window opened here; the grant runs by itself once the
/// pairing is done.
nonisolated final class MirrorTrust: @unchecked Sendable {
    static let shared = MirrorTrust()
    static let cap = "mirror_trust"

    private let lock = NSLock()
    /// Phones waiting for Pro mirroring setup to finish before their grant runs: peer id → package.
    private var waiting: [String: String] = [:]
    private var observer: NSObjectProtocol?

    /// Advertised in `hello.caps` only when this build carries adb.
    static var available: Bool { ADB.binary != nil }

    func handle(_ peerId: String, _ msg: [String: Any]) {
        guard msg["t"] as? String == "mirror_trust", let op = msg["op"] as? String, op == "grant" || op == "revoke",
              let pkg = msg["pkg"] as? String, Self.validPackage(pkg) else { return }
        Task.detached { await self.run(peerId: peerId, op: op, pkg: pkg) }
    }

    /// Only AirBridge itself: this must never become a way to hand out app-ops to anything else.
    static func validPackage(_ p: String) -> Bool {
        (p == "com.shazan.airbridge" || p.hasPrefix("com.shazan.airbridge.")) && p.count <= 80
            && p.allSatisfy { $0.isLetter || $0.isNumber || $0 == "." || $0 == "_" }
    }

    static func command(op: String, pkg: String) -> String {
        "appops set \(pkg) PROJECT_MEDIA \(op == "grant" ? "allow" : "default") && appops get \(pkg) PROJECT_MEDIA"
    }

    /// `appops get` printed "PROJECT_MEDIA: allow" (grant) or anything else (revoke).
    static func succeeded(op: String, output: String) -> Bool {
        let allowed = output.range(of: "PROJECT_MEDIA: allow", options: .caseInsensitive) != nil
        return op == "grant" ? allowed : !allowed
    }

    private func run(peerId: String, op: String, pkg: String) async {
        let name = PeerBus.shared.peers().first(where: { $0.id == peerId })?.name ?? "the phone"
        guard let record = ProMirrorStore.record(peerId) else {
            guard op == "grant" else { return reply(peerId, op, ok: true) } // nothing was ever granted from here
            wait(peerId: peerId, pkg: pkg)
            await MainActor.run { ProMirrorSetupWindow.shared.show(peerId: peerId, name: name) }
            return reply(peerId, op, ok: false, why: "setup",
                         msg: "Finish setting up Pro mirroring on your Mac. Screen sharing is trusted right after.")
        }
        switch await ADBLink.connect(peerId: peerId, record: record) {
        case .failure(let e):
            if e == .wirelessDebuggingOff { _ = ProMirrorHub.shared.openWirelessDebugging(peerId) }
            reply(peerId, op, ok: false, why: "unreachable", msg: e.message(phone: name))
        case .success(let serial):
            let out = await ADB.run(["-s", serial, "shell", Self.command(op: op, pkg: pkg)], timeout: 10)
            let ok = Self.succeeded(op: op, output: out.text)
            NSLog("mirror_trust %@ for %@: %@", op, pkg, ok ? "done" : ADB.firstLine(out.text))
            reply(peerId, op, ok: ok, why: ok ? nil : "failed",
                  msg: ok ? nil : "\(name) didn't accept the change (\(ADB.firstLine(out.text))).")
        }
    }

    private func wait(peerId: String, pkg: String) {
        lock.lock(); defer { lock.unlock() }
        waiting[peerId] = pkg
        guard observer == nil else { return }
        observer = NotificationCenter.default.addObserver(forName: .proMirrorRecordsChanged, object: nil, queue: nil) { [weak self] _ in
            self?.setupChanged()
        }
    }

    /// A pairing finished: run the grants that were waiting for it.
    private func setupChanged() {
        lock.lock()
        let ready = waiting.filter { ProMirrorStore.record($0.key) != nil }
        ready.keys.forEach { waiting[$0] = nil }
        lock.unlock()
        for (peer, pkg) in ready { Task.detached { await self.run(peerId: peer, op: "grant", pkg: pkg) } }
    }

    private func reply(_ peerId: String, _ op: String, ok: Bool, why: String? = nil, msg: String? = nil) {
        var m: [String: Any] = ["t": "mirror_trust_result", "op": op, "ok": ok]
        if let why { m["why"] = why }
        if let msg { m["msg"] = msg }
        PeerBus.shared.send(to: peerId, m)
    }
}
