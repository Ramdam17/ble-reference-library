// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 02 — Filtered Scan (Tests)
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 20
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Unit tests for BLE scan filtering and sorting.
//
// Usage   : swift test --filter Test02_BLEScanFiltered
//
// Depends : Sprint 19 (Passive Scan)
// ─────────────────────────────────────────────────────

import CoreBluetooth
import XCTest
@testable import BLEReference

final class Test02_BLEScanFiltered: XCTestCase {

    // MARK: - Test Helpers

    private func makeDevice(
        name: String? = nil,
        rssi: Int = -60,
        serviceUUIDs: [CBUUID]? = nil
    ) -> DiscoveredDevice {
        var advData: [String: Any] = [:]
        if let uuids = serviceUUIDs {
            advData[CBAdvertisementDataServiceUUIDsKey] = uuids
        }

        return DiscoveredDevice(
            id: UUID(),
            name: name,
            rssi: rssi,
            advertisementData: advData,
            discoveredAt: Date(),
            peripheral: nil
        )
    }

    // MARK: - Name Filter

    func testFilterByName() {
        let devices = [
            makeDevice(name: "Polar H10 A1B2"),
            makeDevice(name: "Muse-1234"),
            makeDevice(name: "polar Sense XYZ"),
            makeDevice(name: nil),
        ]

        let result = ScanFilter.byName("polar").apply(to: devices)
        XCTAssertEqual(result.count, 2, "Case-insensitive 'polar' should match 2 devices")
    }

    func testFilterByExactName() {
        let devices = [
            makeDevice(name: "Polar H10"),
            makeDevice(name: "polar h10"),
        ]

        let result = ScanFilter.byExactName("Polar H10").apply(to: devices)
        XCTAssertEqual(result.count, 1)
    }

    // MARK: - RSSI Filter

    func testFilterByMinRSSI() {
        let devices = [
            makeDevice(rssi: -40),
            makeDevice(rssi: -65),
            makeDevice(rssi: -80),
            makeDevice(rssi: -95),
        ]

        let result = ScanFilter.byMinRSSI(-70).apply(to: devices)
        XCTAssertEqual(result.count, 2)
    }

    // MARK: - Service UUID Filter

    func testFilterByServiceUUID() {
        let hrUUID = CBUUID(string: "180D")
        let devices = [
            makeDevice(name: "HR Monitor", serviceUUIDs: [hrUUID]),
            makeDevice(name: "Thermometer", serviceUUIDs: [CBUUID(string: "1809")]),
            makeDevice(name: "No Services"),
        ]

        let result = ScanFilter.byServiceUUID(hrUUID).apply(to: devices)
        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result.first?.name, "HR Monitor")
    }

    // MARK: - Combined Filters

    func testCombinedFilter() {
        let devices = [
            makeDevice(name: "Polar H10", rssi: -55),
            makeDevice(name: "Polar Sense", rssi: -85),
            makeDevice(name: "Muse-1234", rssi: -50),
        ]

        let filter = ScanFilter.byName("Polar").combine(.byMinRSSI(-70))
        let result = filter.apply(to: devices)
        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result.first?.name, "Polar H10")
    }

    // MARK: - All Filter

    func testAllFilter() {
        let devices = [makeDevice(), makeDevice(), makeDevice()]
        let result = ScanFilter.all.apply(to: devices)
        XCTAssertEqual(result.count, 3)
    }

    // MARK: - Sorting

    func testSortByRSSI() {
        let devices = [
            makeDevice(rssi: -80),
            makeDevice(rssi: -40),
            makeDevice(rssi: -65),
        ]

        let sorted = devices.sorted(by: .byRSSI)
        XCTAssertEqual(sorted[0].rssi, -40)
        XCTAssertEqual(sorted[1].rssi, -65)
        XCTAssertEqual(sorted[2].rssi, -80)
    }

    func testSortByName() {
        let devices = [
            makeDevice(name: "Muse"),
            makeDevice(name: nil),
            makeDevice(name: "Apple Watch"),
        ]

        let sorted = devices.sorted(by: .byName)
        XCTAssertEqual(sorted[0].name, "Apple Watch")
        XCTAssertEqual(sorted[1].name, "Muse")
        XCTAssertNil(sorted[2].name, "nil names should sort last")
    }
}
