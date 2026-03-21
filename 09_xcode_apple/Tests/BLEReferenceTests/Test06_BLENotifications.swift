// ─────────────────────────────────────────────────────
// BLE Reference Library — Tests
// Module  : 06 — Notifications (Sprint 24)
// Usage   : swift test --filter Test06_BLENotifications
// ─────────────────────────────────────────────────────

import XCTest
@testable import BLEReference

final class Test06_BLENotifications: XCTestCase {

    func testSubscriberInitialState() {
        let subscriber = BLENotificationSubscriber()
        XCTAssertEqual(subscriber.activeSubscriptionCount, 0)
    }
}
