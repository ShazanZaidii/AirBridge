import Foundation
import IOKit.ps

// Checks Widgets/Integration/DevicePowerSources.swift against the real system battery list (what the Batteries widget reads).
@_silgen_name("IOPSCopyPowerSourcesByType") func _copyByType(_ t: Int32) -> Unmanaged<CFTypeRef>?
@_silgen_name("IOPSCopyPowerSourcesList") func _list(_ blob: CFTypeRef) -> Unmanaged<CFArray>?
@_silgen_name("IOPSGetPowerSourceDescription") func _desc(_ blob: CFTypeRef, _ ps: CFTypeRef) -> Unmanaged<CFDictionary>?

var passed = 0, failed = 0
func check(_ name: String, _ ok: Bool) { if ok { passed += 1 } else { failed += 1; print("FAIL: \(name)") } }

func accessories() -> [[String: Any]] {
    guard let blob = _copyByType(4)?.takeRetainedValue(), let arr = _list(blob)?.takeRetainedValue() as? [CFTypeRef] else { return [] }
    return arr.compactMap { _desc(blob, $0)?.takeUnretainedValue() as? [String: Any] }
}
/// The system applies a change a moment after it is made, so give it a moment before reading.
func ours() -> [[String: Any]] { usleep(150_000); return accessories().filter { ($0["Name"] as? String)?.hasPrefix("Probe ") == true } }

final class Fake: PeerTransport {
    var list: [PeerInfo] = []
    func send(to peerId: String, _ msg: [String: Any]) -> Bool { true }
    func broadcast(_ msg: [String: Any]) {}
    func sendBinary(to peerId: String, _ frame: Data) -> Bool { true }
    func peers() -> [PeerInfo] { list }
}
let fake = Fake()
PeerBus.shared.transport = fake
let ps = DevicePowerSources.shared
UserDefaults.standard.removeObject(forKey: DevicePowerSources.showKey)

// 1. a level from a phone that is not (yet) a ready peer is not listed
ps.receive(from: "p1", ["t": "dev_battery", "pct": 64, "charging": false])
check("not listed before the phone is a ready peer", ours().isEmpty)

// 2. once the phone is ready it is listed with its level
fake.list = [PeerInfo(id: "p1", name: "Probe Phone", platform: "android", caps: [])]
ps.peersChanged(fake.list)
var o = ours()
check("listed when ready", o.count == 1)
check("level", (o.first?["Current Capacity"] as? Int) == 64)
check("max", (o.first?["Max Capacity"] as? Int) == 100)
check("not charging", (o.first?["Is Charging"] as? Bool) == false)
check("category", (o.first?["Accessory Category"] as? String) == DevicePowerSources.category)
check("phone picture: the USB / Apple USB vendor / product 5016 rule", (o.first?["Transport Type"] as? String) == "USB" && (o.first?["Vendor ID"] as? Int) == 1452 && (o.first?["Product ID"] as? Int) == 5016)
check("battery state", (o.first?["Power Source State"] as? String) == "Battery Power")
let id1 = o.first?["Accessory Identifier"] as? String

// 3. a new level replaces the old one, same entry
ps.receive(from: "p1", ["t": "dev_battery", "pct": 65, "charging": true])
o = ours()
check("one entry after update", o.count == 1)
check("new level", (o.first?["Current Capacity"] as? Int) == 65)
check("charging", (o.first?["Is Charging"] as? Bool) == true)
check("AC state", (o.first?["Power Source State"] as? String) == "AC Power")
check("same identifier", (o.first?["Accessory Identifier"] as? String) == id1)

// 4. bad input is ignored
ps.receive(from: "p1", ["t": "dev_battery", "pct": 400])
ps.receive(from: "p1", ["t": "dev_battery"])
check("out-of-range level ignored", (ours().first?["Current Capacity"] as? Int) == 65)

// 5. rename follows
fake.list = [PeerInfo(id: "p1", name: "Probe Renamed", platform: "android", caps: [])]
ps.peersChanged(fake.list)
check("renamed", (ours().first?["Name"] as? String) == "Probe Renamed")

// 6. setting off removes it, on brings it back
UserDefaults.standard.set(false, forKey: DevicePowerSources.showKey); ps.refresh()
check("hidden when the setting is off", ours().isEmpty)
UserDefaults.standard.set(true, forKey: DevicePowerSources.showKey); ps.refresh()
check("back when the setting is on", ours().count == 1)

// 7. disconnect removes it; a reconnect (same id) comes back with the same identifier after a new level
fake.list = []
ps.peersChanged([])
check("removed on disconnect", ours().isEmpty)
ps.receive(from: "p1", ["t": "dev_battery", "pct": 50, "charging": false])
check("nothing for a phone that is gone", ours().isEmpty)
fake.list = [PeerInfo(id: "p1", name: "Probe Phone", platform: "android", caps: [])]
ps.receive(from: "p1", ["t": "dev_battery", "pct": 51, "charging": false])
check("back after reconnect", ours().count == 1 && (ours().first?["Accessory Identifier"] as? String) == id1)

// 8. two phones are two entries
fake.list.append(PeerInfo(id: "p2", name: "Probe Second", platform: "android", caps: []))
ps.receive(from: "p2", ["t": "dev_battery", "pct": 12, "charging": false])
check("two phones, two entries", ours().count == 2)

// 9. every platform gets its own picture, through a type that AirBridge declares in its Info.plist
let plist = (try? PropertyListSerialization.propertyList(from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "AirBridge/Info.plist")), format: nil)) as? [String: Any]
let declared = (plist?["UTExportedTypeDeclarations"] as? [[String: Any]]) ?? []
func symbol(forProduct id: Int) -> String? {
    declared.first { (($0["UTTypeTagSpecification"] as? [String: Any])?["public.bluetooth-vendor-product-id"] as? [String])?.contains("76:\(id)") == true }
        .flatMap { ($0["UTTypeIcons"] as? [String: Any])?["UTTypeSymbolName"] as? String }
}
let expectedSymbols = ["windows": "pc", "linux": "terminal.fill", "macos": "macbook", "other": "desktopcomputer"]
check("the Info.plist declares the four computer types", declared.count == 4)
for (platform, want) in expectedSymbols.sorted(by: { $0.key < $1.key }) {
    check("\(platform) uses a declared type with the symbol \(want)", symbol(forProduct: DevicePowerSources.computerProduct(for: platform)) == want)
}
check("windows, linux and mac all differ", Set([DevicePowerSources.computerProduct(for: "windows"), DevicePowerSources.computerProduct(for: "linux"), DevicePowerSources.computerProduct(for: "macos")]).count == 3)
func providing() -> String {
    let snap = IOPSCopyPowerSourcesInfo().takeRetainedValue()
    return IOPSGetProvidingPowerSourceType(snap).takeUnretainedValue() as String
}
let providingBefore = providing()
fake.list = [PeerInfo(id: "p1", name: "Probe Phone", platform: "android", caps: []),
             PeerInfo(id: "p2", name: "Probe Second", platform: "android", caps: []),
             PeerInfo(id: "pc", name: "Probe PC", platform: "windows", caps: []),
             PeerInfo(id: "lx", name: "Probe Linux", platform: "linux", caps: [])]
ps.receive(from: "pc", ["t": "dev_battery", "pct": 83, "charging": true])
ps.receive(from: "lx", ["t": "dev_battery", "pct": 41, "charging": false])
let pcEntry = ours().first { ($0["Name"] as? String) == "Probe PC" }
let lxEntry = ours().first { ($0["Name"] as? String) == "Probe Linux" }
check("a PC is listed with the Windows picture's product id", (pcEntry?["Product ID"] as? Int) == 64001 && (pcEntry?["Vendor ID"] as? Int) == 76 && (pcEntry?["Transport Type"] as? String) == "Bluetooth")
check("a Linux computer is listed with the Linux picture's product id", (lxEntry?["Product ID"] as? Int) == 64002)
check("a PC is an accessory entry, never an internal battery", (pcEntry?["Type"] as? String) == "Accessory Source")
check("a PC level and charging state", (pcEntry?["Current Capacity"] as? Int) == 83 && (pcEntry?["Is Charging"] as? Bool) == true)
check("a published PC never changes which power this Mac says it runs on", providing() == providingBefore)
check("no published entry is an internal battery", !accessories().contains { ($0["Name"] as? String)?.hasPrefix("Probe ") == true && ($0["Type"] as? String) == "InternalBattery" })
check("phones get the phone picture", (ours().first { ($0["Name"] as? String) == "Probe Second" }?["Product ID"] as? Int) == 5016)
check("an unknown platform gets the safety-net computer picture", DevicePowerSources.computerProduct(for: "somethingnew") == 64005 && symbol(forProduct: 64005) == "desktopcomputer")

// 10. removeAll leaves nothing behind
ps.removeAll()
check("nothing left after removeAll", ours().isEmpty)
check("nothing left of the PC either", ours().first { ($0["Name"] as? String) == "Probe PC" } == nil)

print("\(passed) passed, \(failed) failed")
exit(failed == 0 ? 0 : 1)
