import Foundation
import Darwin

// Reproduces the 3.2 crash: a SocketTransport dropped (never cancelled) while its write source is inactive or
// suspended. libdispatch traps on release in that state; with the fix the process lives and the descriptors close.
var failures = 0
func check(_ ok: Bool, _ what: String) { print(ok ? "ok   \(what)" : "FAIL \(what)"); if !ok { failures += 1 } }

func openFds() -> Int { (0..<4096).filter { fcntl(Int32($0), F_GETFD) != -1 }.count }

let q = DispatchQueue(label: "t")
// The system keeps a few descriptors of its own once a dial has run (resolver, kqueue): do one before counting.
do { var t: SocketTransport? = SocketTransport(host: "10.255.255.1", port: 9); q.sync { t!.start(queue: q) }; usleep(50_000); q.sync { t = nil }; usleep(100_000) }
let base = openFds()

// 1. Dropped right after connecting: write source never resumed (inactive).
for _ in 0..<50 {
    var sv: [Int32] = [0, 0]
    socketpair(AF_UNIX, SOCK_STREAM, 0, &sv)
    let ready = DispatchSemaphore(value: 0)
    q.sync {
        var t: SocketTransport? = SocketTransport(accepted: sv[0])
        t!.onReady = { ready.signal() }
        t!.start(queue: q)
        _ = t
        q.async { t = nil }
    }
    _ = ready.wait(timeout: .now() + 1)
    q.sync {}
    close(sv[1])
}
q.sync {}
usleep(100_000)
check(true, "dropped with an inactive write source: no trap")

// 2. Dropped after the write source was armed then suspended again (sent more than the buffer, then drained).
for _ in 0..<20 {
    var sv: [Int32] = [0, 0]
    socketpair(AF_UNIX, SOCK_STREAM, 0, &sv)
    var t: SocketTransport? = SocketTransport(accepted: sv[0])
    let sent = DispatchSemaphore(value: 0)
    q.sync {
        t!.start(queue: q)
        t!.send(Data(count: 4 << 20)) { _ in sent.signal() }
    }
    // Drain the peer so the write completes and the source is suspended again.
    let drain = Thread {
        var buf = [UInt8](repeating: 0, count: 1 << 16)
        var got = 0
        while got < 4 << 20 { let n = read(sv[1], &buf, buf.count); if n <= 0 { break }; got += n }
    }
    drain.start()
    _ = sent.wait(timeout: .now() + 5)
    q.sync { t = nil }
    close(sv[1])
}
q.sync {}
usleep(100_000)
check(true, "dropped with a suspended write source: no trap")

// 3. Normal cancel, then drop: no double resume / double close.
for _ in 0..<20 {
    var sv: [Int32] = [0, 0]
    socketpair(AF_UNIX, SOCK_STREAM, 0, &sv)
    var t: SocketTransport? = SocketTransport(accepted: sv[0])
    q.sync { t!.start(queue: q) }
    q.sync {}
    q.sync { t!.cancel(); t = nil }
    close(sv[1])
}
q.sync {}
usleep(200_000)
q.sync {}
check(true, "cancelled then dropped: no trap")

// 4. Dial that never connects (blackholed address), dropped mid-connect.
for _ in 0..<15 {
    var t: SocketTransport? = SocketTransport(host: "10.255.255.1", port: 9)
    q.sync { t!.start(queue: q) }
    usleep(50_000)
    q.sync { t = nil }
}
q.sync {}
usleep(200_000)
check(true, "dropped mid-connect: no trap")

let after = openFds()
check(after <= base, "descriptors closed (before \(base), after \(after))")
print(failures == 0 ? "ALL PASS" : "\(failures) FAILED")
exit(failures == 0 ? 0 : 1)
