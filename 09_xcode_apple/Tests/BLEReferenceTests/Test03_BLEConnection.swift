// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 03 — Connection Lifecycle (Tests)
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 21
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Unit tests for connection state and error types.
//           Real connection tests run via TestApp on device.
//
// Usage   : swift test --filter Test03_BLEConnection
//
// Depends : Sprint 19 (Passive Scan)
// ─────────────────────────────────────────────────────

import CoreBluetooth
import XCTest
@testable import BLEReference

final class Test03_BLEConnection: XCTestCase {

    // MARK: - BLEConnectionState

    func testConnectionStateFromCBPeripheralState() {
        // State mapping must match CoreBluetooth values.
        XCTAssertEqual(BLEConnectionState(from: .disconnected), .disconnected)
        XCTAssertEqual(BLEConnectionState(from: .connecting), .connecting)
        XCTAssertEqual(BLEConnectionState(from: .connected), .connected)
        XCTAssertEqual(BLEConnectionState(from: .disconnecting), .disconnecting)
    }

    func testConnectionStateRawValues() {
        // Raw values must be readable strings for logging.
        XCTAssertEqual(BLEConnectionState.disconnected.rawValue, "disconnected")
        XCTAssertEqual(BLEConnectionState.connected.rawValue, "connected")
        XCTAssertEqual(BLEConnectionState.connecting.rawValue, "connecting")
        XCTAssertEqual(BLEConnectionState.disconnecting.rawValue, "disconnecting")
    }

    // MARK: - BLEConnectionError

    func testTimeoutError() {
        let error = BLEConnectionError.timeout(seconds: 10.0)
        XCTAssertTrue(error.localizedDescription.contains("10"))
        XCTAssertTrue(error.localizedDescription.contains("timed out"))
    }

    func testConnectionFailedError() {
        let error = BLEConnectionError.connectionFailed(reason: "refused by peripheral")
        XCTAssertTrue(error.localizedDescription.contains("refused"))
    }

    func testUnexpectedDisconnectionError() {
        let error = BLEConnectionError.unexpectedDisconnection(reason: "out of range")
        XCTAssertTrue(error.localizedDescription.contains("out of range"))
    }

    func testNotConnectedError() {
        let error = BLEConnectionError.notConnected
        XCTAssertTrue(error.localizedDescription.contains("not connected"))
    }

    // MARK: - BLEConnector Initial State

    func testConnectorInitialState() {
        let connector = BLEConnector()
        XCTAssertEqual(connector.state, .disconnected)
        XCTAssertNil(connector.connectedPeripheral)
    }
}
