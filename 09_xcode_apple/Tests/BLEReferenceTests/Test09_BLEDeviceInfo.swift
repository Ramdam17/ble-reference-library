// ─────────────────────────────────────────────────────
// BLE Reference Library — Tests
// Module  : 09 — Device Information Service (Sprint 27)
// Usage   : swift test --filter Test09_BLEDeviceInfo
// ─────────────────────────────────────────────────────

import CoreBluetooth
import XCTest
@testable import BLEReference

final class Test09_BLEDeviceInfo: XCTestCase {

    func testDISUUIDs() {
        XCTAssertEqual(kDISServiceUUID, CBUUID(string: "180A"))
        XCTAssertEqual(kSerialNumberUUID, CBUUID(string: "2A25"))
        XCTAssertEqual(kModelNumberUUID, CBUUID(string: "2A24"))
        XCTAssertEqual(kFirmwareRevisionUUID, CBUUID(string: "2A26"))
        XCTAssertEqual(kHardwareRevisionUUID, CBUUID(string: "2A27"))
        XCTAssertEqual(kManufacturerNameUUID, CBUUID(string: "2A29"))
    }

    func testDeviceInfoIdentifierPrefersSerial() {
        let info = DeviceInfo(
            serialNumber: "ABC123",
            modelNumber: "H10",
            firmwareRevision: nil,
            hardwareRevision: nil,
            manufacturerName: "Polar"
        )
        XCTAssertEqual(info.identifier, "ABC123")
    }

    func testDeviceInfoIdentifierFallback() {
        let info = DeviceInfo(
            serialNumber: nil,
            modelNumber: "H10",
            firmwareRevision: nil,
            hardwareRevision: nil,
            manufacturerName: "Polar"
        )
        XCTAssertEqual(info.identifier, "Polar H10")
    }

    func testDeviceInfoIdentifierModelOnly() {
        let info = DeviceInfo(
            serialNumber: nil,
            modelNumber: "Muse-2",
            firmwareRevision: nil,
            hardwareRevision: nil,
            manufacturerName: nil
        )
        XCTAssertEqual(info.identifier, "Muse-2")
    }

    func testDeviceInfoIdentifierNil() {
        let info = DeviceInfo(
            serialNumber: nil,
            modelNumber: nil,
            firmwareRevision: nil,
            hardwareRevision: nil,
            manufacturerName: nil
        )
        XCTAssertNil(info.identifier)
    }

    func testDeviceInfoEmptySerialFallback() {
        // Empty string serial should fall back.
        let info = DeviceInfo(
            serialNumber: "",
            modelNumber: "H10",
            firmwareRevision: nil,
            hardwareRevision: nil,
            manufacturerName: "Polar"
        )
        XCTAssertEqual(info.identifier, "Polar H10")
    }
}
