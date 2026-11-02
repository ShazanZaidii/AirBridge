import Foundation
import CoreMedia
import CoreVideo
import VideoToolbox

/// Video codecs a mirror sink may ask for (protocol.md §8.7 `mirror_start.codecs`).
nonisolated enum MirrorCodec: String, Sendable {
    case h264, hevc

    var cmType: CMVideoCodecType { self == .h264 ? kCMVideoCodecType_H264 : kCMVideoCodecType_HEVC }
}

/// Real-time hardware H.264/HEVC encoder producing Annex-B access units for the §8.7 video frame.
/// Thread-safe: `encode` may be called from any one serial queue; outputs arrive on a VideoToolbox thread.
nonisolated final class MirrorEncoder: @unchecked Sendable {
    struct Output {
        let ptsUs: UInt64
        let keyframe: Bool
        let hasParameterSets: Bool
        /// Annex-B elementary stream, 4-byte start codes; parameter sets first on keyframes.
        let annexB: Data
        let encodeMs: Double
    }

    let codec: MirrorCodec
    let width: Int
    let height: Int
    let fps: Int
    private(set) var bitrate: Int

    private let lock = NSLock()
    private var session: VTCompressionSession?
    private var forceKey = true
    private let onOutput: (Output) -> Void

    /// Tries the codecs in order; returns nil if no hardware encoder could be created.
    static func make(preferring codecs: [MirrorCodec], width: Int, height: Int, fps: Int, bitrate: Int,
                     onOutput: @escaping (Output) -> Void) -> MirrorEncoder? {
        for c in codecs {
            if let e = MirrorEncoder(codec: c, width: width, height: height, fps: fps, bitrate: bitrate, onOutput: onOutput) {
                return e
            }
        }
        return nil
    }

    private init?(codec: MirrorCodec, width: Int, height: Int, fps: Int, bitrate: Int, onOutput: @escaping (Output) -> Void) {
        self.codec = codec
        self.width = width
        self.height = height
        self.fps = fps
        self.bitrate = bitrate
        self.onOutput = onOutput

        // Hardware only: a software encoder can't hold the latency budget.
        var spec: [CFString: Any] = [kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder: true]
        if codec == .h264 {
            // Video-conferencing rate control: no frame reordering, no look-ahead, minimal buffering.
            spec[kVTVideoEncoderSpecification_EnableLowLatencyRateControl] = true
        }
        let pbAttrs: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferWidthKey: width, kCVPixelBufferHeightKey: height,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
        ]
        var s: VTCompressionSession?
        var st = VTCompressionSessionCreate(allocator: nil, width: Int32(width), height: Int32(height),
                                            codecType: codec.cmType, encoderSpecification: spec as CFDictionary,
                                            imageBufferAttributes: pbAttrs as CFDictionary, compressedDataAllocator: nil,
                                            outputCallback: nil, refcon: nil, compressionSessionOut: &s)
        if st != noErr, codec == .h264 {
            // Retry without low-latency rate control (older encoders).
            spec.removeValue(forKey: kVTVideoEncoderSpecification_EnableLowLatencyRateControl)
            st = VTCompressionSessionCreate(allocator: nil, width: Int32(width), height: Int32(height),
                                            codecType: codec.cmType, encoderSpecification: spec as CFDictionary,
                                            imageBufferAttributes: pbAttrs as CFDictionary, compressedDataAllocator: nil,
                                            outputCallback: nil, refcon: nil, compressionSessionOut: &s)
        }
        guard st == noErr, let s else {
            NSLog("[AirBridge] mirror: no hardware %@ encoder (%d)", codec.rawValue, st)
            return nil
        }
        session = s

        func set(_ key: CFString, _ value: Any, optional: Bool = false) {
            let r = VTSessionSetProperty(s, key: key, value: value as CFTypeRef)
            if r != noErr && !optional { NSLog("[AirBridge] mirror: %@ not supported (%d)", key as String, r) }
        }
        set(kVTCompressionPropertyKey_RealTime, kCFBooleanTrue!)
        set(kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse!)
        set(kVTCompressionPropertyKey_ProfileLevel,
            codec == .h264 ? kVTProfileLevel_H264_High_AutoLevel : kVTProfileLevel_HEVC_Main_AutoLevel)
        set(kVTCompressionPropertyKey_MaxKeyFrameInterval, 2 * fps)
        set(kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, 2.0)
        set(kVTCompressionPropertyKey_ExpectedFrameRate, fps)
        // Not accepted together with H.264 low-latency rate control (which already favours speed).
        set(kVTCompressionPropertyKey_PrioritizeEncodingSpeedOverQuality, kCFBooleanTrue!, optional: true)
        set(kVTCompressionPropertyKey_ColorPrimaries, kCVImageBufferColorPrimaries_ITU_R_709_2)
        set(kVTCompressionPropertyKey_TransferFunction, kCVImageBufferTransferFunction_ITU_R_709_2)
        set(kVTCompressionPropertyKey_YCbCrMatrix, kCVImageBufferYCbCrMatrix_ITU_R_709_2)
        applyBitrate(s, bitrate)
        VTCompressionSessionPrepareToEncodeFrames(s)
    }

    deinit { invalidate() }

    private func applyBitrate(_ s: VTCompressionSession, _ bps: Int) {
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_AverageBitRate, value: bps as CFNumber)
        // Hard cap: 1.5× the average over any 1 s window keeps keyframe bursts from building a queue.
        let limits = [Double(bps) * 1.5 / 8, 1.0] as CFArray
        let r = VTSessionSetProperty(s, key: kVTCompressionPropertyKey_DataRateLimits, value: limits)
        if r != noErr { NSLog("[AirBridge] mirror: DataRateLimits not supported (%d)", r) }
    }

    func setBitrate(_ bps: Int) {
        lock.lock(); defer { lock.unlock() }
        guard let s = session, bps != bitrate else { return }
        bitrate = bps
        applyBitrate(s, bps)
    }

    func requestKeyframe() {
        lock.lock(); forceKey = true; lock.unlock()
    }

    func invalidate() {
        lock.lock()
        let s = session
        session = nil
        lock.unlock()
        if let s {
            VTCompressionSessionCompleteFrames(s, untilPresentationTimeStamp: .invalid)
            VTCompressionSessionInvalidate(s)
        }
    }

    func encode(_ pb: CVPixelBuffer, ptsUs: UInt64) {
        lock.lock()
        guard let s = session else { lock.unlock(); return }
        let key = forceKey
        forceKey = false
        lock.unlock()

        let pts = CMTime(value: CMTimeValue(ptsUs), timescale: 1_000_000)
        let props: CFDictionary? = key ? [kVTEncodeFrameOptionKey_ForceKeyFrame: kCFBooleanTrue!] as CFDictionary : nil
        let started = CACurrentMediaTimeCompat()
        let codec = self.codec
        let st = VTCompressionSessionEncodeFrame(s, imageBuffer: pb, presentationTimeStamp: pts,
                                                 duration: CMTime(value: 1, timescale: CMTimeScale(fps)),
                                                 frameProperties: props, infoFlagsOut: nil) { [weak self] status, _, sb in
            guard let self, status == noErr else { return }
            guard let sb, let out = Self.annexB(sb, codec: codec) else {
                // Dropped by rate control: report an empty output so flow control forgets the frame.
                self.onOutput(Output(ptsUs: ptsUs, keyframe: false, hasParameterSets: false, annexB: Data(), encodeMs: 0))
                return
            }
            self.onOutput(Output(ptsUs: ptsUs, keyframe: out.key, hasParameterSets: out.params, annexB: out.data,
                                 encodeMs: (CACurrentMediaTimeCompat() - started) * 1000))
        }
        if st != noErr {
            NSLog("[AirBridge] mirror: encode failed (%d)", st)
            if key { requestKeyframe() }
        }
    }

    // MARK: AVCC → Annex-B

    private static let startCode: [UInt8] = [0, 0, 0, 1]

    private static func annexB(_ sb: CMSampleBuffer, codec: MirrorCodec) -> (data: Data, key: Bool, params: Bool)? {
        guard let block = CMSampleBufferGetDataBuffer(sb) else { return nil }
        var key = true
        if let atts = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[CFString: Any]],
           let first = atts.first, let notSync = first[kCMSampleAttachmentKey_NotSync] as? Bool {
            key = !notSync
        }
        let len = CMBlockBufferGetDataLength(block)
        var raw = [UInt8](repeating: 0, count: len)
        guard CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: len, destination: &raw) == noErr else { return nil }

        var out = Data()
        out.reserveCapacity(len + 128)
        var headerLen = 4
        var params = false
        if let fd = CMSampleBufferGetFormatDescription(sb) {
            if key {
                for ps in parameterSets(fd, codec: codec, headerLen: &headerLen) {
                    out.append(contentsOf: startCode)
                    out.append(ps)
                    params = true
                }
            } else {
                _ = parameterSets(fd, codec: codec, headerLen: &headerLen, countOnly: true)
            }
        }
        var i = 0
        while i + headerLen <= len {
            var n = 0
            for k in 0..<headerLen { n = (n << 8) | Int(raw[i + k]) }
            i += headerLen
            guard n > 0, i + n <= len else { break }
            out.append(contentsOf: startCode)
            out.append(contentsOf: raw[i..<(i + n)])
            i += n
        }
        return (out, key, params)
    }

    private static func parameterSets(_ fd: CMFormatDescription, codec: MirrorCodec, headerLen: inout Int,
                                      countOnly: Bool = false) -> [Data] {
        var count = 0
        var hdr: Int32 = 4
        let probe: OSStatus = codec == .h264
            ? CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, parameterSetIndex: 0, parameterSetPointerOut: nil,
                                                                 parameterSetSizeOut: nil, parameterSetCountOut: &count,
                                                                 nalUnitHeaderLengthOut: &hdr)
            : CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(fd, parameterSetIndex: 0, parameterSetPointerOut: nil,
                                                                 parameterSetSizeOut: nil, parameterSetCountOut: &count,
                                                                 nalUnitHeaderLengthOut: &hdr)
        guard probe == noErr else { return [] }
        if (1...4).contains(hdr) { headerLen = Int(hdr) }
        if countOnly { return [] }
        var sets: [Data] = []
        for idx in 0..<count {
            var ptr: UnsafePointer<UInt8>?
            var size = 0
            let st: OSStatus = codec == .h264
                ? CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, parameterSetIndex: idx, parameterSetPointerOut: &ptr,
                                                                     parameterSetSizeOut: &size, parameterSetCountOut: nil,
                                                                     nalUnitHeaderLengthOut: nil)
                : CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(fd, parameterSetIndex: idx, parameterSetPointerOut: &ptr,
                                                                     parameterSetSizeOut: &size, parameterSetCountOut: nil,
                                                                     nalUnitHeaderLengthOut: nil)
            if st == noErr, let ptr, size > 0 { sets.append(Data(bytes: ptr, count: size)) }
        }
        return sets
    }
}

/// Seconds on the mach host clock (same clock as ScreenCaptureKit presentation timestamps).
nonisolated func CACurrentMediaTimeCompat() -> Double {
    CMTimeGetSeconds(CMClockGetTime(CMClockGetHostTimeClock()))
}
