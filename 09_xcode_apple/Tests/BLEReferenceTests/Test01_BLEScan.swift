// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 01 — Passive Scan (Tests)
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 19
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Unit tests for BLE scanning data structures.
//           CoreBluetooth requires a real Bluetooth stack, so
//           we test the data model and error types here.
//           Real-device scanning is validated via TestApp.
//
// Usage   : swift test --filter Test01_BLEScan
//
// Depends : None
// ─────────────────────────────────────────────────────

import XCTest
@testable import BLEReference

final class Test01_BLEScan: XCTestCase {

    // MARK: - DiscoveredDevice

    func testDiscoveredDeviceProperties() {
        // DiscoveredDevice must store all discovery metadata.
        let id = UUID()
        let device = DiscoveredDevice(
            id: id,
            name: "Polar H10 A1B2C3",
            rssi: -65,
            advertisementData: ["kCBAdvDataLocalName": "Polar H10 A1B2C3"],
            discoveredAt: Date(),
            peripheral: nil
        )

        XCTAssertEqual(device.id, id)
        XCTAssertEqual(device.name, "Polar H10 A1B2C3")
        XCTAssertEqual(device.rssi, -65)
    }

    func testDiscoveredDeviceNilName() {
        // Devices without a local name must have name == nil.
        let device = DiscoveredDevice(
            id: UUID(),
            name: nil,
            rssi: -80,
            advertisementData: [:],
            discoveredAt: Date(),
            peripheral: nil
        )

        XCTAssertNil(device.name)
    }

    func testDiscoveredDeviceIdentifiable() {
        // DiscoveredDevice must conform to Identifiable with UUID as id.
        let id = UUID()
        let device = DiscoveredDevice(
            id: id,
            name: "Test",
            rssi: -50,
            advertisementData: [:],
            discoveredAt: Date(),
            peripheral: nil
        )

        XCTAssertEqual(device.id, id)
    }

    // MARK: - BLEScanError

    func testScanErrorBluetoothOff() {
        // BLEScanError must produce human-readable descriptions.
        let error = BLEScanError.bluetoothUnavailable(reason: "Bluetooth is powered off")
        XCTAssertTrue(error.localizedDescription.contains("powered off"))
    }

    func testScanErrorUnauthorized() {
        let error = BLEScanError.bluetoothUnavailable(reason: "permission denied")
        XCTAssertTrue(error.localizedDescription.contains("permission denied"))
    }
}
