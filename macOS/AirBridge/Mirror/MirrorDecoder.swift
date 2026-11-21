import Foundation
import CoreMedia
import CoreVideo
import VideoToolbox

/// Annex-B helpers for the §8.7 video frame (start-code prefixed NAL units, 3- or 4-byte codes).
nonisolated enum AnnexB {
    /// Payload ranges (start code excluded) of every NAL unit in `d`.
    static func units(_ d: Data) -> [Range<Int>] {
        var starts: [Int] = []     // first payload byte
        var codes: [Int] = []      // where the start code begins
        d.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            guard let b = raw.bindMemory(to: UInt8.self).baseAddress else { return }
            let n = raw.count
            var i = 0
            while i + 2 < n {
                if b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1 {
                    codes.append(i > 0 && b[i - 1] == 0 ? i - 1 : i)
                    starts.append(i + 3)
                    i += 3
                } else {
                    i += 1
                }
            }
        }
        var out: [Range<Int>] = []
        out.reserveCapacity(starts.count)
        for k in starts.indices {
            let end = k + 1 < starts.count ? codes[k + 1] : d.count
            if end > starts[k] { out.append(starts[k]..<end) }
        }
        return out
    }

    static func type(_ firstByte: UInt8, _ codec: MirrorCodec) -> Int {
        codec == .hevc ? Int((firstByte >> 1) & 0x3F) : Int(firstByte & 0x1F)
    }

    static func isParameterSet(_ t: Int, _ codec: MirrorCodec) -> Bool {
        codec == .hevc ? (32...34).contains(t) : (t == 7 || t == 8)
    }

    /// Access-unit delimiters carry nothing a decoder needs and some decoders dislike them mid-sample.
    static func isDelimiter(_ t: Int, _ codec: MirrorCodec) -> Bool { codec == .hevc ? t == 35 : t == 9 }
}

/// Hardware H.264/HEVC decoder for the phone mirror (§8.7 sink). Feed whole Annex-B access units; decoded
/// frames come back through `onFrame` (synchronously, on the caller's queue). Parameter sets found in a frame
/// (re)build the format description and the session when they change. Not thread-safe: use one serial queue.
nonisolated final class MirrorDecoder {
    enum Result { case decoded, needKeyframe, dropped }

    let codec: MirrorCodec
    private var session: VTDecompressionSession?
    private var format: CMVideoFormatDescription?
    private var params: [Data] = []
    private var waitKey = true
    private let onFrame: (CVPixelBuffer, UInt64) -> Void

    init(codec: MirrorCodec, onFrame: @escaping (CVPixelBuffer, UInt64) -> Void) {
        self.codec = codec
        self.onFrame = onFrame
    }

    deinit { invalidate() }

    func invalidate() {
        if let s = session {
            VTDecompressionSessionWaitForAsynchronousFrames(s)
            VTDecompressionSessionInvalidate(s)
        }
        session = nil
    }

    /// Forget everything; decoding resumes at the next keyframe with parameter sets.
    func reset() {
        invalidate()
        format = nil
        params = []
        waitKey = true
    }

    var dimensions: CMVideoDimensions? { format.map { CMVideoFormatDescriptionGetDimensions($0) } }

    func decode(_ annexB: Data, ptsUs: UInt64, keyframe: Bool) -> Result {
        let units = AnnexB.units(annexB)
        guard !units.isEmpty else { return .dropped }
        var ps: [Data] = []
        var payload: [Range<Int>] = []
        for u in units {
            let t = AnnexB.type(annexB[u.lowerBound], codec)
            if AnnexB.isParameterSet(t, codec) { ps.append(annexB.subdata(in: u)) }
            else if !AnnexB.isDelimiter(t, codec) { payload.append(u) }
        }
        if !ps.isEmpty, ps != params {
            guard let fd = Self.formatDescription(ps, codec) else { return .needKeyframe }
            params = ps
            format = fd
            invalidate()
        }
        if keyframe && format != nil { waitKey = false }
        guard !waitKey, let fd = format, !payload.isEmpty else { return waitKey ? .needKeyframe : .dropped }
        if session == nil, !makeSession(fd) { waitKey = true; return .needKeyframe }
        guard let s = session, let sb = Self.sample(annexB, payload, fd, ptsUs) else { return .dropped }

        var ok = true
        let st = VTDecompressionSessionDecodeFrame(s, sampleBuffer: sb, flags: [._1xRealTimePlayback], infoFlagsOut: nil) {
            [onFrame] status, _, image, pts, _ in
            guard status == noErr, let image else { ok = false; return }
            let us = pts.isValid ? UInt64(max(0, CMTimeGetSeconds(pts) * 1_000_000)) : ptsUs
            onFrame(image, us)
        }
        if st != noErr || !ok {
            // Bad data or a session the system invalidated (sleep, GPU change): start over at a keyframe.
            NSLog("[AirBridge] phone mirror: decode failed (%d)", st)
            invalidate()
            waitKey = true
            return .needKeyframe
        }
        return .decoded
    }

    private func makeSession(_ fd: CMVideoFormatDescription) -> Bool {
        let attrs: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
            kCVPixelBufferMetalCompatibilityKey: true,
        ]
        let spec: [CFString: Any] = [kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: true]
        var s: VTDecompressionSession?
        let st = VTDecompressionSessionCreate(allocator: nil, formatDescription: fd, decoderSpecification: spec as CFDictionary,
                                              imageBufferAttributes: attrs as CFDictionary, outputCallback: nil,
                                              decompressionSessionOut: &s)
        guard st == noErr, let s else {
            NSLog("[AirBridge] phone mirror: decoder session failed (%d)", st)
            return false
        }
        VTSessionSetProperty(s, key: kVTDecompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        session = s
        return true
    }

    static func formatDescription(_ ps: [Data], _ codec: MirrorCodec) -> CMVideoFormatDescription? {
        // H.264 wants SPS then PPS; HEVC VPS, SPS, PPS. Keep one of each type, in that order.
        var byType: [Int: Data] = [:]
        for p in ps where !p.isEmpty { byType[AnnexB.type(p[p.startIndex], codec)] = p }
        let order = codec == .hevc ? [32, 33, 34] : [7, 8]
        let sets = order.compactMap { byType[$0] }
        guard sets.count == order.count else { return nil }
        let datas = sets.map { NSData(data: $0) }
        let ptrs = datas.map { $0.bytes.assumingMemoryBound(to: UInt8.self) }
        let sizes = datas.map { $0.length }
        var fd: CMFormatDescription?
        let st: OSStatus = ptrs.withUnsafeBufferPointer { p in
            sizes.withUnsafeBufferPointer { s in
                codec == .hevc
                    ? CMVideoFormatDescriptionCreateFromHEVCParameterSets(allocator: nil, parameterSetCount: sets.count,
                        parameterSetPointers: p.baseAddress!, parameterSetSizes: s.baseAddress!, nalUnitHeaderLength: 4,
                        extensions: nil, formatDescriptionOut: &fd)
                    : CMVideoFormatDescriptionCreateFromH264ParameterSets(allocator: nil, parameterSetCount: sets.count,
                        parameterSetPointers: p.baseAddress!, parameterSetSizes: s.baseAddress!, nalUnitHeaderLength: 4,
                        formatDescriptionOut: &fd)
            }
        }
        withExtendedLifetime(datas) {}
        return st == noErr ? fd : nil
    }

    /// AVCC/HVCC sample (4-byte big-endian lengths) from the picture NAL units.
    static func sample(_ d: Data, _ units: [Range<Int>], _ fd: CMVideoFormatDescription, _ ptsUs: UInt64) -> CMSampleBuffer? {
        let total = units.reduce(0) { $0 + 4 + $1.count }
        var bb: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: total, blockAllocator: nil,
                                                 customBlockSource: nil, offsetToData: 0, dataLength: total, flags: 0,
                                                 blockBufferOut: &bb) == noErr, let bb,
              CMBlockBufferAssureBlockMemory(bb) == noErr else { return nil }
        var off = 0
        let ok: Bool = d.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Bool in
            guard let base = raw.baseAddress else { return false }
            for u in units {
                var len = UInt32(u.count).bigEndian
                guard CMBlockBufferReplaceDataBytes(with: &len, blockBuffer: bb, offsetIntoDestination: off, dataLength: 4) == noErr,
                      CMBlockBufferReplaceDataBytes(with: base + u.lowerBound, blockBuffer: bb, offsetIntoDestination: off + 4,
                                                    dataLength: u.count) == noErr else { return false }
                off += 4 + u.count
            }
            return true
        }
        guard ok else { return nil }
        var timing = CMSampleTimingInfo(duration: .invalid,
                                        presentationTimeStamp: CMTime(value: CMTimeValue(ptsUs), timescale: 1_000_000),
                                        decodeTimeStamp: .invalid)
        var size = total
        var sb: CMSampleBuffer?
        guard CMSampleBufferCreateReady(allocator: nil, dataBuffer: bb, formatDescription: fd, sampleCount: 1,
                                        sampleTimingEntryCount: 1, sampleTimingArray: &timing, sampleSizeEntryCount: 1,
                                        sampleSizeArray: &size, sampleBufferOut: &sb) == noErr else { return nil }
        return sb
    }
}
