// ─────────────────────────────────────────────────────
// BLE Reference Library — Tests
// Module  : 07 — Scan Without Connection (Sprint 25)
// Usage   : swift test --filter Test07_BLEScanNoConnect
// ─────────────────────────────────────────────────────

import CoreBluetooth
import XCTest
@testable import BLEReference

final class Test07_BLEScanNoConnect: XCTestCase {

    func testParseEmptyAdvertisement() {
        let parsed = AdvertisementParser.parse([:])
        XCTAssertNil(parsed.localName)
        XCTAssertNil(parsed.txPowerLevel)
        XCTAssertTrue(parsed.serviceUUIDs.isEmpty)
        XCTAssertFalse(parsed.isConnectable)
        XCTAssertNil(parsed.manufacturerData)
        XCTAssertTrue(parsed.serviceData.isEmpty)
    }

    func testParseFullAdvertisement() {
        let hrUUID = CBUUID(string: "180D")
        let advData: [String: Any] = [
            CBAdvertisementDataLocalNameKey: "Polar H10",
            CBAdvertisementDataTxPowerLevelKey: NSNumber(value: -59),
            CBAdvertisementDataServiceUUIDsKey: [hrUUID],
            CBAdvertisementDataIsConnectable: true,
            CBAdvertisementDataManufacturerDataKey: Data([0x6B, 0x00, 0x01]),
        ]

        let parsed = AdvertisementParser.parse(advData)
        XCTAssertEqual(parsed.localName, "Polar H10")
        XCTAssertEqual(parsed.txPowerLevel, -59)
        XCTAssertEqual(parsed.serviceUUIDs.count, 1)
        XCTAssertTrue(parsed.isConnectable)
        XCTAssertEqual(parsed.manufacturerData, Data([0x6B, 0x00, 0x01]))
    }

    func testParseNonConnectable() {
        let advData: [String: Any] = [
            CBAdvertisementDataIsConnectable: false,
        ]
        let parsed = AdvertisementParser.parse(advData)
        XCTAssertFalse(parsed.isConnectable)
    }
}
