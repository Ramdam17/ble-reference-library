// Tests — Module 17: Command Queue (Sprint 35)

import XCTest
@testable import BLEReference

final class Test17_BLECommandQueue: XCTestCase {

    func testEnqueueReturnsResult() async throws {
        let queue = BLECommandQueue()
        let result = try await queue.enqueue {
            return "hello"
        }
        XCTAssertEqual(result, "hello")
    }

    func testSequentialExecution() async throws {
        let queue = BLECommandQueue()
        var order: [Int] = []

        for i in 0..<5 {
            try await queue.enqueue {
                order.append(i)
            }
        }

        XCTAssertEqual(order, [0, 1, 2, 3, 4])
    }

    func testErrorPropagation() async {
        let queue = BLECommandQueue()

        do {
            try await queue.enqueue {
                throw BLEError.serviceNotFound(uuid: "180D")
            }
            XCTFail("Should have thrown")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("180D"))
        }
    }
}
