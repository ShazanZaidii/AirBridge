import AppKit
import CoreAudio

/// Watches the volume keys (up, down, mute) while a call rings, so a press silences the ringtone as the side
/// buttons do on an iPhone. Two signals, either is enough:
/// - the key itself (a system-defined media key event; needs the Accessibility access AirBridge already has
///   for input sharing);
/// - the Mac's output volume or mute changing (catches the keys without Accessibility, and the volume slider
///   in Control Centre).
@MainActor final class VolumeKeys {
    private var monitors: [Any] = []
    private var device = AudioObjectID(kAudioObjectUnknown)
    private var listening: [AudioObjectPropertyAddress] = []
    private var onPress: (() -> Void)?

    private let listener: AudioObjectPropertyListenerBlock = { _, _ in
        DispatchQueue.main.async { MainActor.assumeIsolated { VolumeKeys.active?.volumeChanged() } }
    }
    /// Volume and mute when watching started (or last seen): only a real change of them counts.
    private var lastLevel: (volume: Float32, muted: Bool)?
    private static weak var active: VolumeKeys?

    var isWatching: Bool { onPress != nil }

    func start(_ onPress: @escaping () -> Void) {
        guard self.onPress == nil else { return }
        self.onPress = onPress
        Self.active = self
        let handler: (NSEvent) -> Void = { [weak self] e in
            // NX_SYSDEFINED subtype 8 = media keys; data1 high word is the key, bit 0x0A00 the key-down state.
            guard e.subtype.rawValue == 8 else { return }
            let key = (e.data1 & 0xFFFF_0000) >> 16
            let down = ((e.data1 & 0xFF00) >> 8) == 0x0A
            // NX_KEYTYPE_SOUND_UP 0, NX_KEYTYPE_SOUND_DOWN 1, NX_KEYTYPE_MUTE 7.
            guard down, key == 0 || key == 1 || key == 7 else { return }
            MainActor.assumeIsolated {
                InputLog.shared.log("call ring: volume key \(key == 0 ? "up" : key == 1 ? "down" : "mute")")
                self?.fire()
            }
        }
        if let g = NSEvent.addGlobalMonitorForEvents(matching: .systemDefined, handler: handler) { monitors.append(g) }
        if let l = NSEvent.addLocalMonitorForEvents(matching: .systemDefined, handler: { handler($0); return $0 }) { monitors.append(l) }
        watchVolume()
    }

    func stop() {
        monitors.forEach(NSEvent.removeMonitor)
        monitors.removeAll()
        for var a in listening { AudioObjectRemovePropertyListenerBlock(device, &a, .main, listener) }
        listening.removeAll()
        device = AudioObjectID(kAudioObjectUnknown)
        lastLevel = nil
        onPress = nil
        if Self.active === self { Self.active = nil }
    }

    private func fire() {
        guard let f = onPress else { return }
        f()
    }

    /// Core Audio reported a volume/mute change: a key press only if the level really moved on the same device
    /// (devices coming and going, or a stream starting, can send notifications without the user doing anything).
    private func volumeChanged() {
        guard onPress != nil, let now = level(device), let before = lastLevel else { return }
        guard defaultOutput() == device else { return }
        lastLevel = now
        guard abs(now.volume - before.volume) >= 0.01 || now.muted != before.muted else { return }
        InputLog.shared.log(String(format: "call ring: volume %.2f→%.2f muted %d→%d", before.volume, now.volume, before.muted ? 1 : 0, now.muted ? 1 : 0))
        fire()
    }

    private func defaultOutput() -> AudioObjectID {
        var out = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        var def = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice,
                                             mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        _ = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &def, 0, nil, &size, &out)
        return out
    }

    private func level(_ dev: AudioObjectID) -> (volume: Float32, muted: Bool)? {
        guard dev != kAudioObjectUnknown else { return nil }
        var vol: Float32 = -1
        for el in [kAudioObjectPropertyElementMain, 1] as [AudioObjectPropertyElement] {
            var a = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyVolumeScalar, mScope: kAudioDevicePropertyScopeOutput, mElement: el)
            var v: Float32 = 0; var size = UInt32(MemoryLayout<Float32>.size)
            if AudioObjectHasProperty(dev, &a), AudioObjectGetPropertyData(dev, &a, 0, nil, &size, &v) == noErr { vol = v; break }
        }
        var m: UInt32 = 0; var msize = UInt32(MemoryLayout<UInt32>.size)
        var ma = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyMute, mScope: kAudioDevicePropertyScopeOutput, mElement: kAudioObjectPropertyElementMain)
        let hasMute = AudioObjectHasProperty(dev, &ma) && AudioObjectGetPropertyData(dev, &ma, 0, nil, &msize, &m) == noErr
        if vol < 0 && !hasMute { return nil }
        return (max(vol, 0), hasMute && m != 0)
    }

    private func watchVolume() {
        var out = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        var def = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice,
                                             mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &def, 0, nil, &size, &out) == noErr,
              out != kAudioObjectUnknown else { return }
        device = out
        lastLevel = level(out)
        // Volume lives on the main element on most devices, on channels 1/2 on some; mute on main.
        let selectors: [(AudioObjectPropertySelector, AudioObjectPropertyElement)] = [
            (kAudioDevicePropertyVolumeScalar, kAudioObjectPropertyElementMain),
            (kAudioDevicePropertyVolumeScalar, 1),
            (kAudioDevicePropertyMute, kAudioObjectPropertyElementMain),
        ]
        for (sel, el) in selectors {
            var a = AudioObjectPropertyAddress(mSelector: sel, mScope: kAudioDevicePropertyScopeOutput, mElement: el)
            guard AudioObjectHasProperty(out, &a) else { continue }
            if AudioObjectAddPropertyListenerBlock(out, &a, .main, listener) == noErr { listening.append(a) }
        }
    }
}
