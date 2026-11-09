import CoreAudio
import CoreBluetooth
import Foundation
import IOBluetooth
import ObjectiveC

// Offline checks for the call-audio code: the IOBluetooth hands-free classes and selectors it relies on exist
// in this macOS, the ring buffer behaves, and Core Audio devices can be listed. It never creates a hands-free
// object, pairs, connects, or opens the microphone. Run: tools/CallAudioProbe/run.sh

var bad = 0
func check(_ ok: Bool, _ what: String) { print(ok ? "ok   \(what)" : "FAIL \(what)"); if !ok { bad += 1 } }

print("macOS \(ProcessInfo.processInfo.operatingSystemVersionString)")

// IOBluetooth hands-free (HF role)
let hfClass: AnyClass? = NSClassFromString("IOBluetoothHandsFreeDevice")
check(hfClass != nil, "IOBluetoothHandsFreeDevice class loads")
check(NSClassFromString("IOBluetoothHandsFree") != nil, "IOBluetoothHandsFree class loads")
for sel in ["initWithDevice:delegate:", "connect", "disconnect", "connectSCO", "disconnectSCO", "isSCOConnected",
            "isConnected", "indicator:", "acceptCall", "endCall", "transferAudioToComputer", "transferAudioToPhone"] {
    check(hfClass.map { class_getInstanceMethod($0, NSSelectorFromString(sel)) != nil } ?? false, "-[IOBluetoothHandsFreeDevice \(sel)]")
}
let reject = hfClass.map { class_getInstanceMethod($0, NSSelectorFromString("setRejectSCOConnection:")) != nil } ?? false
check(reject, "setRejectSCOConnection: present (non-public; used to keep in-band ringing off the Mac)")
for sel in ["inputAudioDeviceID", "outputAudioDeviceID"] {
    let ok = class_getInstanceMethod(IOBluetoothDevice.self, NSSelectorFromString(sel)) != nil
    print(ok ? "ok   -[IOBluetoothDevice \(sel)] present (optional)" : "info -[IOBluetoothDevice \(sel)] missing; falls back to matching by address/name")
}
check(IOBluetoothHandsFreeIndicatorCall == "call" || !IOBluetoothHandsFreeIndicatorCall.isEmpty, "indicator constant call = \(IOBluetoothHandsFreeIndicatorCall)")
check(!IOBluetoothHandsFreeIndicatorCallSetup.isEmpty, "indicator constant callsetup = \(IOBluetoothHandsFreeIndicatorCallSetup)")
print("info Bluetooth authorization for this process: \(CBManager.authorization.rawValue) (0 not determined, 3 allowed)")

// Ring buffer
do {
    let r = FloatRing(capacity: 1000, target: 100)
    var src = (0..<300).map { Float($0) }
    r.write(&src, count: 300)
    var dst = [Float](repeating: -1, count: 200)
    r.read(into: &dst, count: 200)
    check(dst[0] == 0 && dst[199] == 199, "ring: FIFO order")
    r.read(into: &dst, count: 200)
    check(dst[99] == 299 && dst[100] == 0 && dst[199] == 0, "ring: underflow plays silence")
    var big = (0..<2000).map { Float($0) }
    r.write(&big, count: 2000)
    check(r.available == 1000, "ring: overflow keeps the newest \(r.available)")
    var one = [Float](repeating: 0, count: 10)
    r.read(into: &one, count: 10)
    check(r.available <= 100 + 10 && one[9] == 1999 - Float(r.available), "ring: latency cap skips ahead (left \(r.available))")
    // wrap-around
    let w = FloatRing(capacity: 256, target: 64)
    var a = (0..<200).map { Float($0) }
    w.write(&a, count: 200); w.read(into: &one, count: 10)
    var b = (200..<300).map { Float($0) }
    w.write(&b, count: 100)
    var all = [Float](repeating: 0, count: 256)
    w.read(into: &all, count: 256)
    // 290 left > 3*64+256? No: 290 < 448, so no skip; but capacity 256 dropped the oldest 34.
    check(all[0] == 44 && all[255] == 299, "ring: wrap-around and overflow (\(all[0])…\(all[255]))")
}

// Core Audio
let devs = CoreAudioDevices.all()
check(!devs.isEmpty, "Core Audio lists \(devs.count) devices")
let din = CoreAudioDevices.defaultDevice(input: true), dout = CoreAudioDevices.defaultDevice(input: false)
print("info default input: \(CoreAudioDevices.name(din) ?? "none"), output: \(CoreAudioDevices.name(dout) ?? "none")")
for d in devs where CoreAudioDevices.transport(d) == kAudioDeviceTransportTypeBluetooth {
    print("info Bluetooth audio device: \(CoreAudioDevices.name(d) ?? "?") uid \(CoreAudioDevices.uid(d) ?? "?") in \(CoreAudioDevices.hasStreams(d, input: true)) out \(CoreAudioDevices.hasStreams(d, input: false))")
}
check(CoreAudioDevices.phoneDevices(address: "00-00-00-00-00-00", name: "No Such Phone") == nil, "phone device lookup finds nothing for an unknown phone")

// Mac device lists for the call window (what the picker would show on this Mac)
for input in [true, false] {
    let list = MacAudioDevices.list(input: input, excluding: [], phoneName: nil)
    check(!list.isEmpty, "\(input ? "inputs" : "outputs"): \(list.map { "\($0.name) [\($0.symbol)]" }.joined(separator: ", "))")
    let sys = CoreAudioDevices.defaultDevice(input: input)
    check(list.first?.id == CoreAudioDevices.uid(sys), "system default listed first")
    let eff = MacAudioDevices.effective(input: input, excluding: [], phoneName: nil, fallback: nil)
    check(eff != nil, "call would use \(CoreAudioDevices.name(eff ?? 0) ?? "?")")
    // The phone's device is never offered, even when it is the default.
    let without = MacAudioDevices.list(input: input, excluding: [sys], phoneName: nil)
    check(!without.contains { $0.id == CoreAudioDevices.uid(sys) }, "an excluded (phone) device is left out")
    let fb = MacAudioDevices.effective(input: input, excluding: [sys], phoneName: nil, fallback: nil)
    check(fb != sys, "with the default excluded the call falls back to \(CoreAudioDevices.name(fb ?? 0) ?? "none")")
}
let both = MacAudioDevices.callDevices(excluding: [], phoneName: nil, fallback: nil)
check(both.input == nil && both.output == nil, "no remembered choice: the call follows the defaults (voice processing path)")
check(MacAudioDevices.airPodsSymbol(0, name: "shazan's airpods pro") == "airpodspro", "AirPods Pro symbol by name")
check(MacAudioDevices.airPodsSymbol(0, name: "airpods max") == "airpodsmax", "AirPods Max symbol by name")
check(MacAudioDevices.airPodsSymbol(0, name: "asha's airpods") == "airpods", "AirPods symbol by name")
check(MacAudioDevices.airPodsSymbol(0, name: "wh-1000xm5") == nil, "other headphones: no AirPods symbol")
print("info this Mac's symbol: \(MacAudioDevices.macSymbol)")

print(bad == 0 ? "\nall checks passed" : "\n\(bad) checks failed")
exit(bad == 0 ? 0 : 1)
