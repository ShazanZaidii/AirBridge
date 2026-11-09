import AVFoundation
import AudioToolbox
import CoreAudio
import Foundation
import IOBluetooth
import os

/// Carries the call audio between the phone's Bluetooth audio device and this Mac while the hands-free audio
/// connection (SCO) is open:
///
///   phone ──SCO──▶ phone device input  ──▶ ring ──▶ the Mac output chosen for the call (speakers, AirPods…)
///   phone ◀─SCO── phone device output ◀── ring ◀── the Mac microphone chosen for the call
///
/// Which Mac devices: `deviceResolver` (MacAudioDevices) says, per direction, either "follow the system default"
/// (nil) or an explicit device. With both on the defaults the Mac side runs on one AVAudioEngine with Apple's
/// voice processing (echo cancellation: the other person doesn't hear themselves back from the speakers).
/// With an explicit device it runs on two plain engines bound to those devices (no voice processing; the
/// usual reason to pick a device is a headset, where there is no echo). The system default devices are never
/// changed. Device changes (plugging in headphones, AirPods connecting, a new choice) rebuild the Mac side;
/// the phone side keeps running.
nonisolated final class CallAudioBridge: @unchecked Sendable {
    enum BridgeError: Error { case noPhoneDevice, engine(String), hal(String, OSStatus) }

    /// Called (on the main thread) when the bridge stopped working and could not recover.
    var onFailure: (@MainActor @Sendable () -> Void)?
    /// Picks the Mac devices for the call, given the phone's own device IDs and the defaults from before the
    /// call: nil per direction = follow the system default.
    var deviceResolver: (@Sendable (_ phone: Set<AudioDeviceID>, _ fallback: (input: AudioDeviceID, output: AudioDeviceID)?)
                         -> (input: AudioDeviceID?, output: AudioDeviceID?))?

    private let lock = OSAllocatedUnfairLock()
    private var phone: PhoneSide?
    private var local: LocalSide?
    private var downRing: FloatRing?
    private var upRing: FloatRing?
    private let micMuted = AtomicFlag()
    private var configObservers: [NSObjectProtocol] = []
    private var restartWork: DispatchWorkItem?
    private var phoneInput: AudioDeviceID = 0
    private var phoneOutput: AudioDeviceID = 0
    private var savedDefaults: (input: AudioDeviceID, output: AudioDeviceID)?

    var isRunning: Bool { lock.withLock { phone != nil } }
    /// The phone's Core Audio devices while the bridge runs (left out of the Mac device lists).
    var phoneDevices: Set<AudioDeviceID> { lock.withLock { phone == nil ? [] : [phoneInput, phoneOutput] } }

    /// Remembers this Mac's default input and output from before the phone's audio device appears: if macOS
    /// makes the phone the default, the call uses these instead (the defaults themselves are left alone).
    func rememberDefaults() {
        let d = (CoreAudioDevices.defaultDevice(input: true), CoreAudioDevices.defaultDevice(input: false))
        lock.withLock { savedDefaults = d }
        CallAudioLog.shared.log("bridge: defaults in \(CoreAudioDevices.name(d.0) ?? "?") out \(CoreAudioDevices.name(d.1) ?? "?")")
    }

    /// Starts carrying audio. `input`/`output` are the phone's Core Audio device(s).
    func start(phoneInput input: AudioDeviceID, phoneOutput output: AudioDeviceID) throws {
        stop()
        lock.withLock { phoneInput = input; phoneOutput = output }

        let inRate = CoreAudioDevices.sampleRate(input, input: true) ?? 8000
        let outRate = CoreAudioDevices.sampleRate(output, input: false) ?? inRate
        CallAudioLog.shared.log("bridge: phone in \(CoreAudioDevices.name(input) ?? "?") @\(Int(inRate)) Hz, out \(CoreAudioDevices.name(output) ?? "?") @\(Int(outRate)) Hz")

        // ~1 s of room, ~60 ms target latency each way.
        let down = FloatRing(capacity: Int(inRate), target: Int(inRate * 0.06))
        let up = FloatRing(capacity: Int(outRate), target: Int(outRate * 0.06))
        let p = try PhoneSide(input: input, inputRate: inRate, output: output, outputRate: outRate, down: down, up: up)
        let l: LocalSide
        do { l = try makeLocal(downRate: inRate, upRate: outRate, down: down, up: up) }
        catch { p.stop(); throw error }
        lock.withLock { phone = p; local = l; downRing = down; upRing = up }
        observe(l)
    }

    func stop() {
        restartWork?.cancel(); restartWork = nil
        unobserve()
        let (p, l) = lock.withLock { () -> (PhoneSide?, LocalSide?) in
            let r = (phone, local); phone = nil; local = nil; downRing = nil; upRing = nil; return r
        }
        l?.stop()
        p?.stop()
        if p != nil { CallAudioLog.shared.log("bridge: stopped") }
    }

    func setMicMuted(_ m: Bool) { micMuted.set(m) }

    /// A device choice changed: move the Mac side to it (no-op when not running).
    func reconfigure() { if isRunning { scheduleLocalRestart() } }

    private func makeLocal(downRate: Double, upRate: Double, down: FloatRing, up: FloatRing) throws -> LocalSide {
        let (pin, pout, saved) = lock.withLock { (phoneInput, phoneOutput, savedDefaults) }
        let devices: (input: AudioDeviceID?, output: AudioDeviceID?) = deviceResolver?([pin, pout], saved) ?? (nil, nil)
        let l = try LocalSide(downRate: downRate, upRate: upRate, down: down, up: up, muted: micMuted,
                              input: devices.input, output: devices.output)
        let inName = CoreAudioDevices.name(devices.input ?? CoreAudioDevices.defaultDevice(input: true)) ?? "?"
        let outName = CoreAudioDevices.name(devices.output ?? CoreAudioDevices.defaultDevice(input: false)) ?? "?"
        CallAudioLog.shared.log("bridge: Mac side mic \(inName), output \(outName)\(l.voiceProcessing ? " (voice processing)" : "")")
        return l
    }

    private func observe(_ l: LocalSide) {
        unobserve()
        configObservers = l.engines.map { e in
            NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: e, queue: nil) { [weak self] _ in
                self?.scheduleLocalRestart()
            }
        }
    }

    private func unobserve() {
        configObservers.forEach { NotificationCenter.default.removeObserver($0) }
        configObservers = []
    }

    private func scheduleLocalRestart() {
        restartWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.restartLocal() }
        restartWork = work
        DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + 0.3, execute: work)
    }

    private func restartLocal() {
        let (old, down, up) = lock.withLock { (local, downRing, upRing) }
        guard let old, let down, let up else { return }
        unobserve()
        old.stop()
        do {
            let l = try makeLocal(downRate: old.downRate, upRate: old.upRate, down: down, up: up)
            lock.withLock { local = l }
            observe(l)
        } catch {
            CallAudioLog.shared.log("bridge: Mac side restart failed: \(error)")
            stop()
            if let f = onFailure { DispatchQueue.main.async { f() } }
        }
    }
}

// MARK: - Phone side (two HAL units on the phone's Bluetooth audio device)

private nonisolated final class PhoneSide: @unchecked Sendable {
    private var inUnit: AudioUnit?
    private var outUnit: AudioUnit?
    private let down: FloatRing
    private let up: FloatRing
    private var scratch: UnsafeMutablePointer<Float>
    private let scratchFrames = 8192

    init(input: AudioDeviceID, inputRate: Double, output: AudioDeviceID, outputRate: Double, down: FloatRing, up: FloatRing) throws {
        self.down = down
        self.up = up
        scratch = .allocate(capacity: scratchFrames)
        let me = Unmanaged.passUnretained(self).toOpaque()
        do {
            inUnit = try HAL.make(device: input, capture: true, rate: inputRate, refCon: me)
            outUnit = try HAL.make(device: output, capture: false, rate: outputRate, refCon: me)
            if let u = inUnit { try HAL.check(AudioOutputUnitStart(u), "start input") }
            if let u = outUnit { try HAL.check(AudioOutputUnitStart(u), "start output") }
        } catch {
            stop()
            throw error
        }
    }

    func stop() {
        for u in [inUnit, outUnit].compactMap({ $0 }) {
            AudioOutputUnitStop(u)
            AudioUnitUninitialize(u)
            AudioComponentInstanceDispose(u)
        }
        inUnit = nil
        outUnit = nil
    }

    deinit { scratch.deallocate() }

    /// Input HAL: the phone's audio (the other person) arrived; pull it and queue it for the Mac's output.
    fileprivate func captured(_ flags: UnsafeMutablePointer<AudioUnitRenderActionFlags>, _ ts: UnsafePointer<AudioTimeStamp>,
                              _ bus: UInt32, _ frames: UInt32) -> OSStatus {
        guard let u = inUnit, Int(frames) <= scratchFrames else { return noErr }
        var abl = AudioBufferList(mNumberBuffers: 1, mBuffers: AudioBuffer(
            mNumberChannels: 1, mDataByteSize: frames * 4, mData: UnsafeMutableRawPointer(scratch)))
        let st = AudioUnitRender(u, flags, ts, bus, frames, &abl)
        if st == noErr { down.write(scratch, count: Int(frames)) }
        return st
    }

    /// Output HAL: the phone wants our voice; give it the Mac's microphone.
    fileprivate func render(_ frames: UInt32, _ io: UnsafeMutablePointer<AudioBufferList>?) -> OSStatus {
        guard let io else { return noErr }
        for buf in UnsafeMutableAudioBufferListPointer(io) {
            guard let p = buf.mData?.assumingMemoryBound(to: Float.self) else { continue }
            up.read(into: p, count: Int(buf.mDataByteSize) / 4)
        }
        return noErr
    }

    private enum HAL {
        static func check(_ st: OSStatus, _ what: String) throws {
            if st != noErr { throw CallAudioBridge.BridgeError.hal(what, st) }
        }

        static func make(device: AudioDeviceID, capture: Bool, rate: Double, refCon: UnsafeMutableRawPointer) throws -> AudioUnit {
            var desc = AudioComponentDescription(componentType: kAudioUnitType_Output, componentSubType: kAudioUnitSubType_HALOutput,
                                                 componentManufacturer: kAudioUnitManufacturer_Apple, componentFlags: 0, componentFlagsMask: 0)
            guard let comp = AudioComponentFindNext(nil, &desc) else { throw CallAudioBridge.BridgeError.hal("no HAL unit", -1) }
            var unitOpt: AudioUnit?
            try check(AudioComponentInstanceNew(comp, &unitOpt), "new unit")
            guard let unit = unitOpt else { throw CallAudioBridge.BridgeError.hal("new unit", -1) }
            do {
                var inFlag: UInt32 = capture ? 1 : 0, outFlag: UInt32 = capture ? 0 : 1
                let u32 = UInt32(MemoryLayout<UInt32>.size)
                // Element 1 = input (from the device), element 0 = output (to the device).
                try check(AudioUnitSetProperty(unit, kAudioOutputUnitProperty_EnableIO, kAudioUnitScope_Input, 1,
                                               &inFlag, u32), "enable input")
                try check(AudioUnitSetProperty(unit, kAudioOutputUnitProperty_EnableIO, kAudioUnitScope_Output, 0,
                                               &outFlag, u32), "enable output")
                var dev = device
                try check(AudioUnitSetProperty(unit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0,
                                               &dev, UInt32(MemoryLayout<AudioDeviceID>.size)), "set device")
                var fmt = AudioStreamBasicDescription(
                    mSampleRate: rate, mFormatID: kAudioFormatLinearPCM,
                    mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked | kAudioFormatFlagIsNonInterleaved,
                    mBytesPerPacket: 4, mFramesPerPacket: 1, mBytesPerFrame: 4, mChannelsPerFrame: 1, mBitsPerChannel: 32, mReserved: 0)
                let fsz = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
                if capture {
                    try check(AudioUnitSetProperty(unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Output, 1, &fmt, fsz), "input format")
                    var cb = AURenderCallbackStruct(inputProc: { refCon, flags, ts, bus, frames, _ in
                        Unmanaged<PhoneSide>.fromOpaque(refCon).takeUnretainedValue().captured(flags, ts, bus, frames)
                    }, inputProcRefCon: refCon)
                    try check(AudioUnitSetProperty(unit, kAudioOutputUnitProperty_SetInputCallback, kAudioUnitScope_Global, 0,
                                                   &cb, UInt32(MemoryLayout<AURenderCallbackStruct>.size)), "input callback")
                } else {
                    try check(AudioUnitSetProperty(unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, 0, &fmt, fsz), "output format")
                    var cb = AURenderCallbackStruct(inputProc: { refCon, _, _, _, frames, io in
                        Unmanaged<PhoneSide>.fromOpaque(refCon).takeUnretainedValue().render(frames, io)
                    }, inputProcRefCon: refCon)
                    try check(AudioUnitSetProperty(unit, kAudioUnitProperty_SetRenderCallback, kAudioUnitScope_Input, 0,
                                                   &cb, UInt32(MemoryLayout<AURenderCallbackStruct>.size)), "render callback")
                }
                try check(AudioUnitInitialize(unit), "initialize")
                return unit
            } catch {
                AudioComponentInstanceDispose(unit)
                throw error
            }
        }
    }
}

// MARK: - Mac side

private nonisolated final class LocalSide: @unchecked Sendable {
    /// One engine (voice processing, defaults) or two (explicit output / input device).
    let engines: [AVAudioEngine]
    let downRate: Double
    let upRate: Double
    let voiceProcessing: Bool

    init(downRate: Double, upRate: Double, down: FloatRing, up: FloatRing, muted: AtomicFlag,
         input: AudioDeviceID?, output: AudioDeviceID?) throws {
        self.downRate = downRate
        self.upRate = upRate
        guard let downFmt = AVAudioFormat(standardFormatWithSampleRate: downRate, channels: 1),
              let upFmt = AVAudioFormat(standardFormatWithSampleRate: upRate, channels: 1) else {
            throw CallAudioBridge.BridgeError.engine("formats")
        }
        voiceProcessing = input == nil && output == nil
        let outEngine = AVAudioEngine()
        let inEngine = voiceProcessing ? outEngine : AVAudioEngine()
        engines = voiceProcessing ? [outEngine] : [outEngine, inEngine]

        if voiceProcessing {
            do { try inEngine.inputNode.setVoiceProcessingEnabled(true) }
            catch { throw CallAudioBridge.BridgeError.engine("voice processing: \(error)") }
            // Don't turn other sounds on the Mac down more than needed.
            inEngine.inputNode.voiceProcessingOtherAudioDuckingConfiguration = .init(enableAdvancedDucking: false, duckingLevel: .min)
        } else {
            do {
                if let output { try outEngine.outputNode.auAudioUnit.setDeviceID(output) }
                if let input { try inEngine.inputNode.auAudioUnit.setDeviceID(input) }
            } catch { throw CallAudioBridge.BridgeError.engine("device: \(error)") }
        }

        // The other person: from the phone ring into the mixer (resampled to the output by the engine).
        let source = AVAudioSourceNode(format: downFmt) { _, _, frames, abl -> OSStatus in
            for buf in UnsafeMutableAudioBufferListPointer(abl) {
                guard let p = buf.mData?.assumingMemoryBound(to: Float.self) else { continue }
                down.read(into: p, count: min(Int(frames), Int(buf.mDataByteSize) / 4))
            }
            return noErr
        }
        outEngine.attach(source)
        outEngine.connect(source, to: outEngine.mainMixerNode, format: downFmt)

        // Our voice: first channel of the microphone, resampled for the phone.
        let mic = inEngine.inputNode
        let micFmt = mic.outputFormat(forBus: 0)
        guard micFmt.sampleRate > 0, micFmt.channelCount > 0,
              let monoMic = AVAudioFormat(standardFormatWithSampleRate: micFmt.sampleRate, channels: 1),
              let converter = AVAudioConverter(from: monoMic, to: upFmt) else {
            throw CallAudioBridge.BridgeError.engine("no microphone")
        }
        mic.installTap(onBus: 0, bufferSize: 1024, format: micFmt) { buffer, _ in
            let n = Int(buffer.frameLength)
            guard n > 0, let src = buffer.floatChannelData?[0],
                  let mono = AVAudioPCMBuffer(pcmFormat: monoMic, frameCapacity: AVAudioFrameCount(n)),
                  let dst = mono.floatChannelData?[0] else { return }
            if muted.value { dst.update(repeating: 0, count: n) } else { dst.update(from: src, count: n) }
            mono.frameLength = AVAudioFrameCount(n)
            let cap = AVAudioFrameCount(Double(n) * upFmt.sampleRate / monoMic.sampleRate) + 64
            guard let out = AVAudioPCMBuffer(pcmFormat: upFmt, frameCapacity: cap) else { return }
            var fed = false
            var err: NSError?
            converter.convert(to: out, error: &err) { _, status in
                if fed { status.pointee = .noDataNow; return nil }
                fed = true
                status.pointee = .haveData
                return mono
            }
            if err == nil, out.frameLength > 0, let p = out.floatChannelData?[0] { up.write(p, count: Int(out.frameLength)) }
        }
        if !voiceProcessing {
            // The input engine only records: keep its output silent.
            inEngine.connect(mic, to: inEngine.mainMixerNode, format: micFmt)
            inEngine.mainMixerNode.outputVolume = 0
        }

        do {
            for e in engines { e.prepare(); try e.start() }
        } catch {
            mic.removeTap(onBus: 0)
            engines.forEach { $0.stop() }
            throw CallAudioBridge.BridgeError.engine("start: \(error)")
        }
    }

    func stop() {
        engines.last?.inputNode.removeTap(onBus: 0)
        engines.forEach { $0.stop() }
    }
}

// MARK: - Small real-time helpers

/// Single-producer / single-consumer float ring with a latency cap: if the reader falls behind by more than
/// three times `target`, it skips ahead to `target` (keeps the conversation from drifting late); if it runs
/// dry it plays silence.
nonisolated final class FloatRing: @unchecked Sendable {
    private let buf: UnsafeMutablePointer<Float>
    private let capacity: Int
    private let target: Int
    private var readPos = 0
    private var count = 0
    private let lock: UnsafeMutablePointer<os_unfair_lock>

    init(capacity: Int, target: Int) {
        self.capacity = max(capacity, 256)
        self.target = max(target, 64)
        lock = .allocate(capacity: 1)
        lock.initialize(to: os_unfair_lock())
        buf = .allocate(capacity: self.capacity)
        buf.initialize(repeating: 0, count: self.capacity)
    }

    deinit { buf.deallocate(); lock.deallocate() }

    var available: Int { os_unfair_lock_lock(lock); defer { os_unfair_lock_unlock(lock) }; return count }

    func write(_ src: UnsafePointer<Float>, count n: Int) {
        guard n > 0 else { return }
        os_unfair_lock_lock(lock); defer { os_unfair_lock_unlock(lock) }
        var src = src
        var n = n
        if n > capacity { src += n - capacity; n = capacity }
        // Overflow: drop the oldest samples.
        let overflow = count + n - capacity
        if overflow > 0 { readPos = (readPos + overflow) % capacity; count -= overflow }
        var w = (readPos + count) % capacity
        var left = n
        while left > 0 {
            let chunk = min(left, capacity - w)
            (buf + w).update(from: src, count: chunk)
            src += chunk; left -= chunk; w = (w + chunk) % capacity
        }
        count += n
    }

    func read(into dst: UnsafeMutablePointer<Float>, count n: Int) {
        guard n > 0 else { return }
        os_unfair_lock_lock(lock); defer { os_unfair_lock_unlock(lock) }
        if count > target * 3 + n {
            let skip = count - target - n
            readPos = (readPos + skip) % capacity
            count -= skip
        }
        let take = min(n, count)
        var r = readPos
        var done = 0
        while done < take {
            let chunk = min(take - done, capacity - r)
            (dst + done).update(from: buf + r, count: chunk)
            done += chunk; r = (r + chunk) % capacity
        }
        readPos = r
        count -= take
        if take < n { (dst + take).update(repeating: 0, count: n - take) }
    }
}

nonisolated final class AtomicFlag: @unchecked Sendable {
    private let state = OSAllocatedUnfairLock(initialState: false)
    var value: Bool { state.withLock { $0 } }
    func set(_ v: Bool) { state.withLock { $0 = v } }
}

// MARK: - Core Audio device lookup

nonisolated enum CoreAudioDevices {
    private static func address(_ selector: AudioObjectPropertySelector,
                                _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
    }

    static func defaultDevice(input: Bool) -> AudioDeviceID {
        var addr = address(input ? kAudioHardwarePropertyDefaultInputDevice : kAudioHardwarePropertyDefaultOutputDevice)
        var id = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &id)
        return id
    }

    static func all() -> [AudioDeviceID] {
        var addr = address(kAudioHardwarePropertyDevices)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size) == noErr, size > 0 else { return [] }
        var ids = [AudioDeviceID](repeating: 0, count: Int(size) / MemoryLayout<AudioDeviceID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &ids) == noErr else { return [] }
        return ids
    }

    static func exists(_ id: AudioDeviceID) -> Bool { all().contains(id) }

    static func isHidden(_ id: AudioDeviceID) -> Bool {
        var addr = address(kAudioDevicePropertyIsHidden)
        var v: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        return AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &v) == noErr && v != 0
    }

    static func canBeDefault(_ id: AudioDeviceID, input: Bool) -> Bool {
        var addr = address(kAudioDevicePropertyDeviceCanBeDefaultDevice,
                           input ? kAudioObjectPropertyScopeInput : kAudioObjectPropertyScopeOutput)
        var v: UInt32 = 1
        var size = UInt32(MemoryLayout<UInt32>.size)
        guard AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &v) == noErr else { return true }
        return v != 0
    }

    static func string(_ id: AudioDeviceID, _ selector: AudioObjectPropertySelector) -> String? {
        var addr = address(selector)
        var value: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &value) == noErr, let v = value else { return nil }
        return v.takeRetainedValue() as String
    }

    static func name(_ id: AudioDeviceID) -> String? { id == 0 ? nil : string(id, kAudioObjectPropertyName) }
    static func uid(_ id: AudioDeviceID) -> String? { string(id, kAudioDevicePropertyDeviceUID) }

    static func transport(_ id: AudioDeviceID) -> UInt32 {
        var addr = address(kAudioDevicePropertyTransportType)
        var t: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &t)
        return t
    }

    static func hasStreams(_ id: AudioDeviceID, input: Bool) -> Bool {
        var addr = address(kAudioDevicePropertyStreams, input ? kAudioObjectPropertyScopeInput : kAudioObjectPropertyScopeOutput)
        var size: UInt32 = 0
        return AudioObjectGetPropertyDataSize(id, &addr, 0, nil, &size) == noErr && size > 0
    }

    static func sampleRate(_ id: AudioDeviceID, input: Bool) -> Double? {
        var addr = address(kAudioDevicePropertyNominalSampleRate)
        var r: Float64 = 0
        var size = UInt32(MemoryLayout<Float64>.size)
        guard AudioObjectGetPropertyData(id, &addr, 0, nil, &size, &r) == noErr, r > 0 else { return nil }
        return r
    }

    /// The Core Audio device(s) macOS made for the phone's hands-free audio. Asks IOBluetooth first (its own
    /// lookup, not public API, used only if present), then matches Bluetooth devices by address or name.
    static func phoneDevices(address: String, name: String?) -> (input: AudioDeviceID, output: AudioDeviceID)? {
        var input: AudioDeviceID = 0, output: AudioDeviceID = 0
        if let dev = IOBluetoothDevice(addressString: address) {
            input = ioBluetoothAudioID(dev, "inputAudioDeviceID")
            output = ioBluetoothAudioID(dev, "outputAudioDeviceID")
        }
        if input == 0 || output == 0 {
            let hex = address.lowercased().filter(\.isHexDigit)
            let bt: Set<UInt32> = [kAudioDeviceTransportTypeBluetooth, kAudioDeviceTransportTypeBluetoothLE]
            for id in all() where bt.contains(transport(id)) {
                let uidHex = (uid(id) ?? "").lowercased().filter(\.isHexDigit)
                let byAddress = !hex.isEmpty && uidHex.contains(hex)
                let byName = name.map { !$0.isEmpty && self.name(id)?.caseInsensitiveCompare($0) == .orderedSame } ?? false
                guard byAddress || byName else { continue }
                if input == 0, hasStreams(id, input: true) { input = id }
                if output == 0, hasStreams(id, input: false) { output = id }
            }
        }
        guard input != 0, output != 0 else { return nil }
        return (input, output)
    }

    private static func ioBluetoothAudioID(_ dev: IOBluetoothDevice, _ selName: String) -> AudioDeviceID {
        let sel = NSSelectorFromString(selName)
        guard dev.responds(to: sel), let imp = dev.method(for: sel) else { return 0 }
        typealias Fn = @convention(c) (AnyObject, Selector) -> UInt32
        let id = unsafeBitCast(imp, to: Fn.self)(dev, sel)
        return id != 0 && exists(id) ? id : 0
    }
}
