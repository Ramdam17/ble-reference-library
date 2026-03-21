// Tests — Module 16: RSSI & Distance (Sprint 34)

import XCTest
@testable import BLEReference

final class Test16_BLERSSIDistance: XCTestCase {

    func testDistanceAtTxPower() {
        let d = BLERSSIDistance.estimateDistance(rssi: -59, txPower: -59)
        XCTAssertEqual(d, 1.0, accuracy: 0.01)
    }

    func testDistanceFar() {
        let d = BLERSSIDistance.estimateDistance(rssi: -85)
        XCTAssertGreaterThan(d, 3.0)
    }

    func testDistanceImmediate() {
        let d = BLERSSIDistance.estimateDistance(rssi: -45)
        XCTAssertLessThan(d, 0.5)
    }

    func testProximityZones() {
        XCTAssertEqual(BLERSSIDistance.classifyProximity(distanceM: 0.3), .immediate)
        XCTAssertEqual(BLERSSIDistance.classifyProximity(distanceM: 1.5), .near)
        XCTAssertEqual(BLERSSIDistance.classifyProximity(distanceM: 5.0), .far)
    }

    func testSignalRatio() {
        XCTAssertEqual(BLERSSIDistance.signalRatio(rssi: -59), 1.0, accuracy: 0.01)
        XCTAssertEqual(BLERSSIDistance.signalRatio(rssi: -100), 0.0, accuracy: 0.01)
    }

    func testSmoothRSSI() {
        let raw = [-70, -70, -70, -70]
        let smoothed = BLERSSIDistance.smoothRSSI(readings: raw)
        for s in smoothed {
            XCTAssertEqual(s, -70.0, accuracy: 0.01)
        }
    }
}
