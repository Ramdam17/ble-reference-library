// Tests — Module 12: Error Handling (Sprint 30)

import XCTest
@testable import BLEReference

final class Test12_BLEErrors: XCTestCase {

    func testBLEErrorDescriptions() {
        let e1 = BLEError.timeout(operation: "read", seconds: 5.0)
        XCTAssertTrue(e1.localizedDescription.contains("read"))
        XCTAssertTrue(e1.localizedDescription.contains("5"))

        let e2 = BLEError.serviceNotFound(uuid: "180D")
        XCTAssertTrue(e2.localizedDescription.contains("180D"))
    }

    func testBLEErrorWrapping() {
        let inner = BLEScanError.bluetoothUnavailable(reason: "off")
        let outer = BLEError.scan(inner)
        XCTAssertTrue(outer.localizedDescription.contains("off"))
    }

    func testWithBLETimeout() async throws {
        // Fast operation should succeed.
        let result = try await withBLETimeout(seconds: 1.0) {
            return 42
        }
        XCTAssertEqual(result, 42)
    }

    func testWithBLETimeoutExpired() async {
        do {
            _ = try await withBLETimeout(seconds: 0.05, operation: "slow") {
                try await Task.sleep(nanoseconds: 5_000_000_000)
                return 0
            }
            XCTFail("Should have thrown")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("timed out"))
        }
    }
}
