import Foundation
import Security

/// A short, typeable stand-in for the session link, for a computer where you can't scan the code or type 40 characters
/// (`http://10.0.0.5:47650/j/K3X9QP` instead of `…/?t=7ky5Rro0b…`). The full link stays the real one; this only
/// redirects to it.
///
/// It is weaker than the full link by design, so it is fenced in:
///  - nothing exists until a connected page asks for one, and it expires after `ttlMs` (10 minutes);
///  - six characters from an alphabet without look-alikes (0/O, 1/I/L), about 30 bits;
///  - wrong guesses are counted for the whole server: after `maxMisses` in a `windowMs` window every try is refused
///    until the window ends, right or wrong, so guessing it takes years, not minutes.
nonisolated final class AirWebShortLink: @unchecked Sendable {
    enum Outcome { case match, miss, locked }

    static let alphabet = Array("23456789ABCDEFGHJKMNPQRSTUVWXYZ")
    static let length = 6

    private let ttlMs: Int64
    private let maxMisses: Int
    private let windowMs: Int64
    private let clock: () -> Int64
    private let lock = NSLock()
    private var code: String?
    private var until: Int64 = 0
    private var windowStart: Int64?
    private var misses = 0

    init(ttlMs: Int64 = 10 * 60_000, maxMisses: Int = 8, windowMs: Int64 = 60_000,
         clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.ttlMs = ttlMs
        self.maxMisses = maxMisses
        self.windowMs = windowMs
        self.clock = clock
    }

    /// The live code (made now if there is none or it ran out) and how many seconds it still has.
    func issue() -> (code: String, secondsLeft: Int64) {
        lock.withLock {
            let now = clock()
            if code == nil || now >= until {
                code = String(Self.random(Self.length))
                until = now + ttlMs
            }
            return (code!, (until - now + 999) / 1000)
        }
    }

    /// Checks what someone typed after `/j/`. Letters in any case are accepted.
    func check(_ given: String?) -> Outcome {
        lock.withLock {
            let now = clock()
            if windowStart == nil || now - windowStart! >= windowMs { windowStart = now; misses = 0 }
            if misses >= maxMisses { return .locked }
            if let c = code, now < until, let g = given, g.count == Self.length, Self.sameBytes(g.uppercased(), c) { return .match }
            misses += 1
            return .miss
        }
    }

    private static func sameBytes(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count else { return false }
        var d: UInt8 = 0
        for i in 0..<x.count { d |= x[i] ^ y[i] }
        return d == 0
    }

    private static func random(_ n: Int) -> [Character] {
        var out: [Character] = []
        while out.count < n {
            var b: UInt8 = 0
            if SecRandomCopyBytes(kSecRandomDefault, 1, &b) != errSecSuccess { b = UInt8.random(in: 0...255) }
            // 31 symbols: take bytes below 248 (8 x 31) so every symbol is equally likely.
            if b < 248 { out.append(alphabet[Int(b) % alphabet.count]) }
        }
        return out
    }
}
