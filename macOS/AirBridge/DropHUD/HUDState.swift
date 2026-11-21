import Foundation

/// One card in the transfer HUD.
nonisolated struct HUDCard: Identifiable, Equatable, Sendable {
    enum Stage: Equatable, Sendable {
        /// Undo-send: nothing has gone out yet; it leaves at `deadline` unless undone.
        case countdown(started: Date, deadline: Date)
        /// Handed to the network, waiting for the first progress report.
        case launching(since: Date)
        /// A real transfer (sending or receiving).
        case transfer(TransferStatus)
    }

    let id: UUID
    var stage: Stage
    let direction: TransferStatus.Direction
    let peerId: String
    let peerName: String
    /// "Report.pdf" or "3 files".
    var title: String
    /// Files to send (countdown/launching) or that were sent, for Retry.
    var files: [URL]
    /// Key of the transfer this card shows (`TransferStatus.id`).
    var transferKey: String?
    /// When the transfer ended (done or failed).
    var finishedAt: Date?
    /// Set when Retry found the files gone.
    var retryUnavailable = false

    var status: TransferStatus? {
        if case .transfer(let s) = stage { return s }
        return nil
    }
    var isFinished: Bool { finishedAt != nil }
    var isDone: Bool { status?.phase == .done }
    var failure: String? {
        if case .failed(let why)? = status?.phase { return why }
        return nil
    }
    var wasCancelledHere: Bool { failure == TransferStatus.cancelledMessage }
    var canRetry: Bool {
        direction == .outgoing && failure != nil && !wasCancelledHere && !files.isEmpty && !retryUnavailable
    }
}

/// The transfer HUD's cards and how they change. Pure value type: the owner feeds it transfer reports and
/// the clock, and carries out what it returns (start a send, run a cleanup).
nonisolated struct HUDState: Equatable, Sendable {
    /// Sends start at once (a drop or a drag onto a device means "send"); a running transfer can still be cancelled.
    static let undoDelay: TimeInterval = 0
    /// Finished cards hide after this when the auto-hide setting is on.
    static let autoHideAfter: TimeInterval = 5
    /// Cards for transfers the user cancelled here go away on their own after this.
    static let cancelledHideAfter: TimeInterval = 1.6
    /// A send that never reported back is shown as failed after this.
    static let launchTimeout: TimeInterval = 20
    /// Most cards kept; beyond this the oldest finished ones are dropped.
    static let maxCards = 6

    private(set) var cards: [HUDCard] = []
    /// Transfers whose cards were closed: later reports for them must not bring the card back.
    private var dismissedKeys = Set<String>()

    // MARK: Undo-send

    /// Adds an undo-send card; the send starts when `due(now:)` returns it.
    @discardableResult
    mutating func queueSend(files: [URL], peerId: String, peerName: String, title: String, now: Date,
                            delay: TimeInterval = HUDState.undoDelay) -> UUID {
        let card = HUDCard(id: UUID(), stage: .countdown(started: now, deadline: now.addingTimeInterval(delay)),
                           direction: .outgoing, peerId: peerId, peerName: peerName, title: title, files: files)
        cards.insert(card, at: 0)
        trim()
        return card.id
    }

    /// Undo pressed: removes the card if nothing has gone out yet. Returns it so the caller can clean up.
    mutating func undo(_ id: UUID) -> HUDCard? {
        guard let i = cards.firstIndex(where: { $0.id == id }), case .countdown = cards[i].stage else { return nil }
        return cards.remove(at: i)
    }

    /// Cards whose countdown has run out; they move to `launching` and the caller must start their sends.
    mutating func due(now: Date) -> [HUDCard] {
        var out: [HUDCard] = []
        for i in cards.indices {
            if case .countdown(_, let deadline) = cards[i].stage, deadline <= now {
                cards[i].stage = .launching(since: now)
                out.append(cards[i])
            }
        }
        return out
    }

    /// A send is being started again (Retry) on an existing card.
    mutating func relaunch(_ id: UUID, now: Date) {
        guard let i = cards.firstIndex(where: { $0.id == id }) else { return }
        if let key = cards[i].transferKey { dismissedKeys.insert(key) }
        cards[i].transferKey = nil
        cards[i].finishedAt = nil
        cards[i].stage = .launching(since: now)
    }

    /// Starting a send failed before any transfer existed (device gone, files missing…).
    mutating func fail(_ id: UUID, reason: String, now: Date, filesGone: Bool = false) {
        guard let i = cards.firstIndex(where: { $0.id == id }) else { return }
        let c = cards[i]
        let s = TransferStatus(id: "local-\(c.id.uuidString)", direction: c.direction, peerName: c.peerName,
                               fileCount: max(1, c.files.count), firstName: c.files.first?.lastPathComponent ?? c.title,
                               total: 0, done: 0, phase: .failed(reason), peerId: c.peerId,
                               sourcePaths: c.files.map(\.path))
        cards[i].stage = .transfer(s)
        cards[i].finishedAt = now
        if filesGone { cards[i].retryUnavailable = true }
    }

    // MARK: Transfer reports

    mutating func ingest(_ s: TransferStatus, now: Date) {
        if dismissedKeys.contains(s.id) { return }
        let finished: Bool
        switch s.phase { case .done, .failed: finished = true; default: finished = false }

        if let i = cards.firstIndex(where: { $0.transferKey == s.id }) {
            apply(s, to: i, finished: finished, now: now)
            return
        }
        // The first report of a send started from a card attaches to that card, so it turns into the progress card.
        if s.direction == .outgoing,
           let i = cards.lastIndex(where: { c in
               if case .launching = c.stage { return c.peerId == s.peerId && c.transferKey == nil }
               return false
           }) {
            cards[i].transferKey = s.id
            apply(s, to: i, finished: finished, now: now)
            return
        }
        var card = HUDCard(id: UUID(), stage: .transfer(s), direction: s.direction, peerId: s.peerId,
                           peerName: s.peerName, title: TransferText.what(s),
                           files: s.sourcePaths.map { URL(fileURLWithPath: $0) }, transferKey: s.id)
        if finished { card.finishedAt = now }
        cards.insert(card, at: 0)
        trim()
    }

    private mutating func apply(_ s: TransferStatus, to i: Int, finished: Bool, now: Date) {
        cards[i].stage = .transfer(s)
        cards[i].title = TransferText.what(s)
        if !s.sourcePaths.isEmpty { cards[i].files = s.sourcePaths.map { URL(fileURLWithPath: $0) } }
        if finished, cards[i].finishedAt == nil { cards[i].finishedAt = now }
        if !finished { cards[i].finishedAt = nil }
    }

    // MARK: Closing

    /// Close button: the card goes away (a running transfer keeps going).
    mutating func dismiss(_ id: UUID) -> HUDCard? {
        guard let i = cards.firstIndex(where: { $0.id == id }) else { return nil }
        if let key = cards[i].transferKey { dismissedKeys.insert(key) }
        return cards.remove(at: i)
    }

    mutating func dismissFinished() {
        for c in cards where c.isFinished { if let k = c.transferKey { dismissedKeys.insert(k) } }
        cards.removeAll { $0.isFinished }
    }

    /// Time-based housekeeping: hides cancelled (and, with `autoHide`, finished) cards and fails sends that
    /// never started.
    mutating func expire(now: Date, autoHide: Bool) {
        for i in cards.indices {
            if case .launching(let since) = cards[i].stage, now.timeIntervalSince(since) >= Self.launchTimeout {
                fail(cards[i].id, reason: "The transfer did not start", now: now)
            }
        }
        let gone = cards.filter { c in
            guard let end = c.finishedAt else { return false }
            let age = now.timeIntervalSince(end)
            if c.wasCancelledHere { return age >= Self.cancelledHideAfter }
            return autoHide && c.isDone && age >= Self.autoHideAfter
        }
        for c in gone { _ = dismiss(c.id) }
    }

    /// When `expire`/`due` next has something to do.
    func nextEvent(autoHide: Bool) -> Date? {
        var dates: [Date] = []
        for c in cards {
            switch c.stage {
            case .countdown(_, let deadline): dates.append(deadline)
            case .launching(let since): dates.append(since.addingTimeInterval(Self.launchTimeout))
            case .transfer: break
            }
            if let end = c.finishedAt {
                if c.wasCancelledHere { dates.append(end.addingTimeInterval(Self.cancelledHideAfter)) }
                else if autoHide && c.isDone { dates.append(end.addingTimeInterval(Self.autoHideAfter)) }
            }
        }
        return dates.min()
    }

    private mutating func trim() {
        while cards.count > Self.maxCards, let i = cards.lastIndex(where: { $0.isFinished }) {
            _ = dismiss(cards[i].id)
        }
    }
}
