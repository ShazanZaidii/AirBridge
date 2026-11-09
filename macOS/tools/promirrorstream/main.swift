import Foundation
import CoreVideo
import Darwin

// Pro mirroring stream check: builds a byte stream exactly as the scrcpy v5.0.1 server writes it in forward mode
// (dummy byte, 64-byte device name, codec id, session packets, config packets, media packets), serves it over a
// real local TCP socket, and reads it back the way ProMirrorSession does: Scrcpy.parseHeader → VideoAssembler →
// MirrorDecoder (VideoToolbox). Real H.264 comes from the Mac's hardware encoder, with SPS/PPS split into a
// separate config packet like Android's MediaCodec does. Includes a rotation (new session + new config).

var failures = 0
func check(_ ok: Bool, _ what: String) { if !ok { failures += 1; print("FAIL: \(what)") } else { print("ok: \(what)") } }

func pixelBuffer(_ w: Int, _ h: Int, _ t: Int) -> CVPixelBuffer {
    var pb: CVPixelBuffer?
    CVPixelBufferCreate(nil, w, h, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                        [kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary] as CFDictionary, &pb)
    let p = pb!
    CVPixelBufferLockBaseAddress(p, [])
    for plane in 0..<2 {
        let base = CVPixelBufferGetBaseAddressOfPlane(p, plane)!.assumingMemoryBound(to: UInt8.self)
        let rows = CVPixelBufferGetHeightOfPlane(p, plane), stride = CVPixelBufferGetBytesPerRowOfPlane(p, plane)
        for y in 0..<rows { for x in 0..<stride { base[y * stride + x] = UInt8(truncatingIfNeeded: x + y + t * 7) } }
    }
    CVPixelBufferUnlockBaseAddress(p, [])
    return p
}

/// Encodes `count` frames; returns (config Annex-B, [(frame Annex-B without parameter sets, key, pts)]).
func encode(_ w: Int, _ h: Int, count: Int, ptsBase: UInt64) -> (Data, [(Data, Bool, UInt64)]) {
    let lock = NSLock()
    var outs: [MirrorEncoder.Output] = []
    guard let enc = MirrorEncoder.make(preferring: [.h264], width: w, height: h, fps: 60, bitrate: 8_000_000, onOutput: { o in
        lock.lock(); outs.append(o); lock.unlock()
    }) else { print("no hardware H.264 encoder"); exit(2) }
    for i in 0..<count {
        enc.encode(pixelBuffer(w, h, i), ptsUs: ptsBase + UInt64(i) * 16_666)
        let deadline = Date().addingTimeInterval(2)
        while Date() < deadline { lock.lock(); let n = outs.count; lock.unlock(); if n > i { break }; usleep(2000) }
    }
    enc.invalidate()
    var config = Data()
    var frames: [(Data, Bool, UInt64)] = []
    let sc = Data([0, 0, 0, 1])
    for o in outs where !o.annexB.isEmpty {
        var rest = Data()
        for u in AnnexB.units(o.annexB) {
            let t = AnnexB.type(o.annexB[u.lowerBound], .h264)
            if AnnexB.isParameterSet(t, .h264) { if config.count < 200 && frames.isEmpty { config += sc + o.annexB.subdata(in: u) } }
            else { rest += sc + o.annexB.subdata(in: u) }
        }
        frames.append((rest, o.keyframe, o.ptsUs))
    }
    return (config, frames)
}

func be32(_ v: UInt32) -> [UInt8] { [UInt8(v >> 24), UInt8((v >> 16) & 0xff), UInt8((v >> 8) & 0xff), UInt8(v & 0xff)] }
func be64(_ v: UInt64) -> [UInt8] { be32(UInt32(v >> 32)) + be32(UInt32(v & 0xffffffff)) }
func session(_ w: Int, _ h: Int) -> [UInt8] { be32(0x8000_0000) + be32(UInt32(w)) + be32(UInt32(h)) }
func media(_ d: Data, pts: UInt64, config: Bool, key: Bool) -> [UInt8] {
    let flags: UInt64 = config ? (1 << 62) : (pts | (key ? (1 << 61) : 0))
    return be64(flags) + be32(UInt32(d.count)) + Array(d)
}

// Build the stream: portrait 720x1280 (20 frames), then "rotation" to 1280x720 (10 frames).
let (c1, f1) = encode(720, 1280, count: 20, ptsBase: 0)
let (c2, f2) = encode(1280, 720, count: 10, ptsBase: 1_000_000)
check(!c1.isEmpty && !c2.isEmpty, "encoder produced SPS/PPS for the config packets")
var stream: [UInt8] = [0]                                                     // dummy byte (forward tunnel)
stream += Array("Test Phone".utf8) + [UInt8](repeating: 0, count: 64 - 10)    // device meta
stream += be32(Scrcpy.codecH264)
stream += session(720, 1280) + media(c1, pts: 0, config: true, key: false)
for (d, k, p) in f1 { stream += media(d, pts: p, config: false, key: k) }
stream += session(1280, 720) + media(c2, pts: 0, config: true, key: false)
for (d, k, p) in f2 { stream += media(d, pts: p, config: false, key: k) }

// Serve it on a local socket, in small uneven chunks.
let listener = socket(AF_INET, SOCK_STREAM, 0)
var addr = sockaddr_in(); addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size); addr.sin_family = sa_family_t(AF_INET)
addr.sin_addr.s_addr = inet_addr("127.0.0.1"); addr.sin_port = 0
_ = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(listener, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
listen(listener, 1)
var len = socklen_t(MemoryLayout<sockaddr_in>.size)
_ = withUnsafeMutablePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(listener, $0, &len) } }
let port = Int(UInt16(bigEndian: addr.sin_port))
let payload = stream
Thread {
    let c = accept(listener, nil, nil)
    var i = 0, k = 1
    while i < payload.count {
        let n = min(payload.count - i, 1 + (k * 7919) % 3000); k += 1
        _ = payload[i..<(i + n)].withUnsafeBytes { send(c, $0.baseAddress, n, 0) }
        i += n
    }
    close(c)
}.start()

func readFully(_ fd: Int32, _ n: Int) -> Data? {
    var d = Data(count: n); var got = 0
    let ok = d.withUnsafeMutableBytes { raw -> Bool in
        while got < n { let r = recv(fd, raw.baseAddress! + got, n - got, 0); if r <= 0 { return false }; got += r }
        return true
    }
    return ok ? d : nil
}

let fd = socket(AF_INET, SOCK_STREAM, 0)
var to = sockaddr_in(); to.sin_len = UInt8(MemoryLayout<sockaddr_in>.size); to.sin_family = sa_family_t(AF_INET)
to.sin_addr.s_addr = inet_addr("127.0.0.1"); to.sin_port = in_port_t(UInt16(port).bigEndian)
_ = withUnsafePointer(to: &to) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }

check(readFully(fd, 1) == Data([0]), "dummy byte")
check(Scrcpy.deviceName(readFully(fd, 64)!) == "Test Phone", "device name")
check(Scrcpy.streamStart(Scrcpy.be32(Array(readFully(fd, 4)!), 0)) == .codec(Scrcpy.codecH264), "codec id h264")

var sizes: [(Int, Int)] = []
var decoded: [(Int, Int)] = []
var asm = Scrcpy.VideoAssembler()
let dec = MirrorDecoder(codec: .h264) { img, _ in decoded.append((CVPixelBufferGetWidth(img), CVPixelBufferGetHeight(img))) }
var needKey = 0
while let h = readFully(fd, Scrcpy.headerSize), let header = Scrcpy.parseHeader(Array(h)) {
    var body = Data()
    if case let .media(_, _, _, size) = header { guard let b = readFully(fd, size) else { break }; body = b }
    switch asm.packet(header, payload: body) {
    case let .session(w, hh): sizes.append((w, hh))
    case let .accessUnit(au, pts, key): if case .needKeyframe = dec.decode(au, ptsUs: pts, keyframe: key) { needKey += 1 }
    case .none: break
    }
}
check(sizes.count == 2 && sizes[0] == (720, 1280) && sizes[1] == (1280, 720), "two session packets (start + rotation)")
let portrait = decoded.filter { $0 == (720, 1280) }.count, landscape = decoded.filter { $0 == (1280, 720) }.count
check(portrait == f1.count, "decoded every portrait frame (\(portrait)/\(f1.count))")
check(landscape == f2.count, "decoded every frame after rotation (\(landscape)/\(f2.count))")
check(needKey == 0, "never asked for a keyframe")
print(failures == 0 ? "OK: stream check passed" : "\(failures) FAILED")
exit(failures == 0 ? 0 : 1)
