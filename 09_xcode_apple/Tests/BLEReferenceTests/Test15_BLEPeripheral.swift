// Tests — Module 15: Peripheral Mode (Sprint 33)

import CoreBluetooth
import XCTest
@testable import BLEReference

final class Test15_BLEPeripheral: XCTestCase {

    func testBuildHeartRateService() {
        let config = buildHeartRatePeripheralService(initialBPM: 85)
        XCTAssertEqual(config.serviceUUID, CBUUID(string: "180D"))
        XCTAssertEqual(config.characteristics.count, 1)

        let char = config.characteristics[0]
        XCTAssertEqual(char.uuid, CBUUID(string: "2A37"))
        XCTAssertTrue(char.properties.contains(.notify))
        XCTAssertTrue(char.properties.contains(.read))
        XCTAssertEqual(char.initialValue, Data([0x00, 85]))
    }

    func testPeripheralCharConfig() {
        let config = PeripheralCharConfig(
            uuid: CBUUID(string: "2A19"),
            properties: [.read],
            permissions: [.readable],
            initialValue: Data([0x64])
        )
        XCTAssertEqual(config.uuid, CBUUID(string: "2A19"))
        XCTAssertEqual(config.initialValue, Data([0x64]))
    }
}
