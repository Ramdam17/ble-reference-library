// Tests — Module 13: MTU (Sprint 31)

import XCTest
@testable import BLEReference

final class Test13_BLEMTU: XCTestCase {

    func testMTUConstants() {
        XCTAssertEqual(kDefaultBLEMTU, 23)
        XCTAssertEqual(kATTHeaderBytes, 3)
        XCTAssertEqual(kMaxBLEMTU, 517)
    }

    func testEffectivePayloadSize() throws {
        XCTAssertEqual(try BLEMTU.effectivePayloadSize(mtu: 247), 244)
        XCTAssertEqual(try BLEMTU.effectivePayloadSize(mtu: 23), 20)
        XCTAssertEqual(try BLEMTU.effectivePayloadSize(mtu: 517), 514)
    }
}
