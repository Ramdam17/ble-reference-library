// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 04 — Services & Characteristics Discovery (Tests)
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 22
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Usage   : swift test --filter Test04_BLEServicesDiscovery
// ─────────────────────────────────────────────────────

import CoreBluetooth
import XCTest
@testable import BLEReference

final class Test04_BLEServicesDiscovery: XCTestCase {

    // MARK: - DiscoveredService

    func testDiscoveredServiceIdentifiable() {
        let uuid = CBUUID(string: "180D")
        let service = DiscoveredService(
            id: uuid,
            isPrimary: true,
            characteristics: []
        )
        XCTAssertEqual(service.id, uuid)
        XCTAssertTrue(service.isPrimary)
        XCTAssertTrue(service.characteristics.isEmpty)
    }

    // MARK: - DiscoveredCharacteristic

    func testCharacteristicReadable() {
        let char = DiscoveredCharacteristic(
            id: CBUUID(string: "2A37"),
            properties: [.read, .notify]
        )
        XCTAssertTrue(char.isReadable)
        XCTAssertTrue(char.isNotifiable)
        XCTAssertFalse(char.isWritable)
        XCTAssertFalse(char.isWritableWithoutResponse)
        XCTAssertFalse(char.isIndicatable)
    }

    func testCharacteristicWritable() {
        let char = DiscoveredCharacteristic(
            id: CBUUID(string: "2A39"),
            properties: [.write, .writeWithoutResponse]
        )
        XCTAssertTrue(char.isWritable)
        XCTAssertTrue(char.isWritableWithoutResponse)
        XCTAssertFalse(char.isReadable)
        XCTAssertFalse(char.isNotifiable)
    }

    func testCharacteristicIndicatable() {
        let char = DiscoveredCharacteristic(
            id: CBUUID(string: "2A1C"),
            properties: [.indicate]
        )
        XCTAssertTrue(char.isIndicatable)
        XCTAssertFalse(char.isNotifiable)
    }

    // MARK: - BLEServiceDiscoverer

    func testDiscovererInitialState() {
        // Should be creatable without crashing.
        let discoverer = BLEServiceDiscoverer()
        XCTAssertNotNil(discoverer)
    }
}
