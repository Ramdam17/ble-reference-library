// Tests — Module 14: Pairing (Sprint 32)

import XCTest
@testable import BLEReference

final class Test14_BLEPairing: XCTestCase {

    func testInferStatusNoError() {
        let status = BLEPairing.inferStatus(from: nil)
        XCTAssertEqual(status, .likelyPaired)
    }

    func testInferStatusAuthError() {
        let error = NSError(domain: "CBATTErrorDomain", code: 0x05)
        let status = BLEPairing.inferStatus(from: error)
        XCTAssertEqual(status, .pairingRequired)
    }

    func testInferStatusOtherError() {
        let error = NSError(domain: "SomeDomain", code: 42)
        let status = BLEPairing.inferStatus(from: error)
        XCTAssertEqual(status, .unknown)
    }
}
