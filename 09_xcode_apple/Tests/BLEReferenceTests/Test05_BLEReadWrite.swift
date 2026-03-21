// ─────────────────────────────────────────────────────
// BLE Reference Library — Tests
// Module  : 05 — Read & Write (Sprint 23)
// Usage   : swift test --filter Test05_BLEReadWrite
// ─────────────────────────────────────────────────────

import XCTest
@testable import BLEReference

final class Test05_BLEReadWrite: XCTestCase {

    func testReadWriteErrorDescriptions() {
        let e1 = BLEReadWriteError.unsupportedOperation("read")
        XCTAssertTrue(e1.localizedDescription.contains("read"))

        let e2 = BLEReadWriteError.noData
        XCTAssertTrue(e2.localizedDescription.contains("no data"))

        let e3 = BLEReadWriteError.writeFailed(reason: "timeout")
        XCTAssertTrue(e3.localizedDescription.contains("timeout"))
    }

    func testReadWriterInstantiation() {
        let rw = BLEReadWriter()
        XCTAssertNotNil(rw)
    }
}
