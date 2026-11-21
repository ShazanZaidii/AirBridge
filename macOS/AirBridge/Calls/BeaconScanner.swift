import CoreBluetooth
import Foundation

/// Listens for the phones' proximity beacons (§13.4) and estimates how far each phone is. Only runs while
/// "Ring on this Mac" is Smart and a connected phone shares its beacon key, so Bluetooth (and its permission
/// prompt) is not touched otherwise. Ids rotate every period; only keys from paired phones match.
@MainActor final class BeaconScanner: NSObject {
    static let shared = BeaconScanner()

    private struct Phone { var key: Data; var uuid: CBUUID; var period: Int }
    private var phones: [String: Phone] = [:]
    private var estimators: [String: ProximityEstimator] = [:]
    /// Bluetooth's (temporary) identifier for a beacon → which phone it was last recognised as.
    private var known: [UUID: String] = [:]
    private var central: CBCentralManager?
    private var scanning = false

    /// `call_beacon` from a phone: its key, or nil when it stopped advertising.
    func setBeacon(phone: String, key: Data?, uuid: String, period: Int) {
        if let key, let u = UUID(uuidString: uuid) {
            phones[phone] = Phone(key: key, uuid: CBUUID(nsuuid: u), period: period)
        } else {
            phones[phone] = nil
            estimators[phone] = nil
            known = known.filter { $0.value != phone }
        }
        update()
    }

    func phoneGone(_ phone: String) { setBeacon(phone: phone, key: nil, uuid: "", period: 0) }

    /// Near / far / unknown for this phone right now.
    func proximity(_ phone: String) -> Proximity {
        guard var e = estimators[phone] else { return .unknown }
        let p = e.tick(now: Date())
        estimators[phone] = e
        return p
    }

    /// Starts or stops scanning to match the setting and the known phones.
    func update() {
        let want = RingMode.current == .smart && !phones.isEmpty
        if want {
            if central == nil { central = CBCentralManager(delegate: self, queue: .main) }
            startIfReady()
        } else if scanning {
            central?.stopScan()
            scanning = false
        }
    }

    private func startIfReady() {
        guard let c = central, c.state == .poweredOn else { return }
        let uuids = Array(Set(phones.values.map(\.uuid)))
        guard !uuids.isEmpty else { return }
        c.scanForPeripherals(withServices: uuids, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        scanning = true
    }

    fileprivate func discovered(_ id: UUID, data: [String: Any], rssi: Int) {
        let now = Date()
        var phone = known[id]
        if let sd = data[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data] {
            for (uuid, payload) in sd {
                if let match = phones.first(where: { $0.value.uuid == uuid && BeaconCrypto.matches(payload, key: $0.value.key, at: now, period: $0.value.period) }) {
                    phone = match.key
                    known[id] = match.key
                }
            }
        }
        guard let phone, phones[phone] != nil else { return }
        let tx = (data[CBAdvertisementDataTxPowerLevelKey] as? NSNumber)?.intValue
        estimators[phone, default: ProximityEstimator()].add(rssi: rssi, txPower: tx, at: now)
        if known.count > 64 { known = known.filter { $0.value == phone } }
    }
}

extension BeaconScanner: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            if central.state == .poweredOn { startIfReady() } else { scanning = false }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let id = peripheral.identifier
        let rssi = RSSI.intValue
        nonisolated(unsafe) let data = advertisementData
        MainActor.assumeIsolated { discovered(id, data: data, rssi: rssi) }
    }
}
