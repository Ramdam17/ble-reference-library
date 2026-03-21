// ─────────────────────────────────────────────────────
// BLE Reference Library — Tests
// Module  : 08 — Battery Level (Sprint 26)
// Usage   : swift test --filter Test08_BLEBattery
// ─────────────────────────────────────────────────────

import CoreBluetooth
import XCTest
@testable import BLEReference

final class Test08_BLEBattery: XCTestCase {

    func testBatteryServiceUUID() {
        XCTAssertEqual(kBatteryServiceUUID, CBUUID(string: "180F"))
    }

    func testBatteryLevelUUID() {
        XCTAssertEqual(kBatteryLevelUUID, CBUUID(string: "2A19"))
    }
}
