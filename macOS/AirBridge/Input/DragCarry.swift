import Foundation
import UniformTypeIdentifiers

/// Carrying a file drag across the edge onto a target (protocol.md §8.8). Pure state, no AppKit, so it can
/// be checked by a small harness; `Controller` (tap thread) and `Injector` (its queue) own one each.

/// Controller side: is a carry in progress, and what goes on the wire when it ends.
nonisolated struct DragCarryController: Sendable {
    enum Phase: Equatable, Sendable {
        case idle
        case carrying(id: Int, peer: String)
    }

    /// What to send (and do) after a transition.
    enum Action: Equatable, Sendable {
        case none
        /// `drag_carry` to `peer` (right after `ctl_enter`).
        case carry(peer: String, id: Int)
        /// `drag_drop` to `peer`, then send the files to it (§6).
        case drop(peer: String, id: Int)
        /// `drag_cancel` to `peer`; the local drag goes on.
        case cancel(peer: String, id: Int)
    }

    private(set) var phase: Phase = .idle
    private var nextId: Int

    init(firstId: Int = 1) { nextId = max(1, firstId) }

    var isCarrying: Bool { if case .carrying = phase { return true } else { return false } }
    var peer: String? { if case let .carrying(_, p) = phase { return p } else { return nil } }

    /// The capture into `peer` started during a file drag. An earlier carry (never expected) is replaced silently:
    /// the caller has already ended that capture.
    mutating func begin(peer: String) -> Action {
        let id = nextId
        nextId = nextId >= 0x3FFF_FFFF ? 1 : nextId + 1
        phase = .carrying(id: id, peer: peer)
        return .carry(peer: peer, id: id)
    }

    /// The held button was released while the cursor was on the target.
    mutating func release() -> Action {
        guard case let .carrying(id, p) = phase else { return .none }
        phase = .idle
        return .drop(peer: p, id: id)
    }

    /// The capture ended before a release (cursor came back, ctl_leave, busy, recall, peer gone) or the button
    /// was found up without us seeing the release. `peerGone`: the session is over, nothing can be sent.
    mutating func end(peerGone: Bool = false) -> Action {
        guard case let .carrying(id, p) = phase else { return .none }
        phase = .idle
        return peerGone ? .none : .cancel(peer: p, id: id)
    }

    /// Only peers that understand §8.8 and take files get a carry; everyone else keeps the old rule (a held
    /// button never crosses).
    static func eligible(_ caps: Set<String>) -> Bool {
        caps.contains("drag_files") && caps.contains("xfer") && caps.contains("ctl_target")
    }

    /// The drag pasteboard holds files *for this drag*: it changed after the button went down (a text selection
    /// drag leaves an older file drag's contents in place) and it has file URLs.
    static func dragHasFiles(changeCountAtDown: Int, changeCountNow: Int, fileCount: Int) -> Bool {
        changeCountAtDown >= 0 && changeCountNow != changeCountAtDown && fileCount > 0
    }
}

/// What a carry looks like on the wire (`drag_carry` display fields).
nonisolated struct DragPayload: Equatable, Sendable {
    var names: [String]
    var kinds: [String]
    var count: Int
    var totalBytes: Int64

    static let maxNames = 3

    static func kind(for url: URL, isDirectory: Bool) -> String {
        if isDirectory { return "folder" }
        guard let t = UTType(filenameExtension: url.pathExtension) else { return "file" }
        if t.conforms(to: .image) { return "image" }
        if t.conforms(to: .movie) || t.conforms(to: .video) { return "video" }
        if t.conforms(to: .audio) { return "audio" }
        return "file"
    }

    /// Describes dragged items from the file system. Folders count as one item and add no bytes (estimating a
    /// folder's size could walk a huge tree on the event-tap thread).
    static func describe(_ urls: [URL]) -> DragPayload {
        var names: [String] = [], kinds: [String] = []
        var total: Int64 = 0
        for u in urls {
            let v = try? u.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
            let dir = v?.isDirectory ?? false
            if !dir { total += Int64(v?.fileSize ?? 0) }
            if names.count < maxNames {
                names.append(u.lastPathComponent)
                kinds.append(kind(for: u, isDirectory: dir))
            }
        }
        return DragPayload(names: names, kinds: kinds, count: urls.count, totalBytes: total)
    }

    func message(id: Int) -> [String: Any] {
        ["t": "drag_carry", "id": id, "names": names, "kinds": kinds, "count": count, "totalBytes": totalBytes]
    }
}

/// Target side: which ghost, if any, follows our cursor. Mirrors Android's DragCarryTarget.
nonisolated struct DragGhostState: Sendable {
    struct Info: Equatable, Sendable {
        let id: Int
        let from: String
        let names: [String]
        let kinds: [String]
        let count: Int
        let totalBytes: Int64
        var kind: String { kinds.first ?? "file" }
        var firstName: String { names.first ?? "" }
    }

    enum Effect: Equatable, Sendable {
        case none
        case show(Info)
        case hide
        /// Dropped on us: settle the ghost (if still shown) and say what is coming.
        case dropped(Info, ghostWasShown: Bool)
    }

    private(set) var shown: Info?
    /// Hidden by a leave rather than a cancel: a racing drop still gets its confirmation.
    private var recent: Info?

    mutating func carry(from: String, holder: String?, _ info: Info) -> Effect {
        guard let holder, holder == from, info.from == from else { return .none }
        shown = info
        recent = nil
        return .show(info)
    }

    mutating func drop(from: String, id: Int) -> Effect {
        if let s = shown, s.from == from, s.id == id {
            shown = nil; recent = nil
            return .dropped(s, ghostWasShown: true)
        }
        if let r = recent, r.from == from, r.id == id {
            recent = nil
            return .dropped(r, ghostWasShown: false)
        }
        return .none
    }

    mutating func cancel(from: String, id: Int) -> Effect {
        if let r = recent, r.from == from, r.id == id { recent = nil }
        guard let s = shown, s.from == from, s.id == id else { return .none }
        shown = nil
        return .hide
    }

    /// ctl_leave either way, pre-emption, lease end. `peer == nil` = whoever it was.
    mutating func leave(_ peer: String?) -> Effect {
        guard let s = shown, peer == nil || s.from == peer else { return .none }
        shown = nil
        recent = s
        return .hide
    }

    mutating func peerGone(_ peer: String) -> Effect {
        if recent?.from == peer { recent = nil }
        guard let s = shown, s.from == peer else { return .none }
        shown = nil
        return .hide
    }

    static let kinds: Set<String> = ["image", "video", "audio", "folder", "file"]

    /// Builds `Info` from a `drag_carry` message, tolerating odd values. Nil without a usable `id`.
    static func info(from: String, _ m: [String: Any]) -> Info? {
        guard let rawId = Msg.num(m["id"]), rawId.isFinite, rawId >= 0, rawId < 9e15 else { return nil }
        let names = ((m["names"] as? [Any]) ?? []).compactMap { $0 as? String }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
            .prefix(DragPayload.maxNames).map { String($0.prefix(200)) }
        let rawKinds = ((m["kinds"] as? [Any]) ?? []).map { $0 as? String ?? "" }
        let kinds = names.indices.map { i in i < rawKinds.count && Self.kinds.contains(rawKinds[i]) ? rawKinds[i] : "file" }
        func clamped(_ v: Double?, _ hi: Double) -> Double? {
            guard let v, v.isFinite else { return nil }
            return max(0, min(hi, v))
        }
        let count = max(1, names.count, clamped(Msg.num(m["count"]), 1e9).map { Int($0) } ?? names.count)
        let bytes = Int64(clamped(Msg.num(m["totalBytes"]), 9e18) ?? 0)
        return Info(id: Int(rawId), from: from, names: Array(names), kinds: kinds, count: count, totalBytes: bytes)
    }
}
