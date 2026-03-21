// ─────────────────────────────────────────────────────
// BLE Reference Library — Tests
// Module  : 10 — Heart Rate + Polar ECG (Sprint 28)
// Usage   : swift test --filter Test10_BLEHeartRate
// ─────────────────────────────────────────────────────

import CoreBluetooth
import XCTest
@testable import BLEReference

final class Test10_BLEHeartRate: XCTestCase {

    // MARK: - Heart Rate Parser

    func testParseUint8BPM() {
        // Flags=0x00 (uint8 BPM), BPM=72
        let data = Data([0x00, 72])
        let hr = HeartRateParser.parse(data)

        XCTAssertNotNil(hr)
        XCTAssertEqual(hr?.bpm, 72)
        XCTAssertEqual(hr?.isUint16, false)
    }

    func testParseUint16BPM() {
        // Flags=0x01 (uint16 BPM), BPM=300 (0x012C LE)
        let data = Data([0x01, 0x2C, 0x01])
        let hr = HeartRateParser.parse(data)

        XCTAssertNotNil(hr)
        XCTAssertEqual(hr?.bpm, 300)
        XCTAssertEqual(hr?.isUint16, true)
    }

    func testParseSensorContact() {
        // Flags=0x06 (sensor contact supported + detected), BPM=65
        let data = Data([0x06, 65])
        let hr = HeartRateParser.parse(data)

        XCTAssertNotNil(hr)
        XCTAssertEqual(hr?.sensorContact, true)
    }

    func testParseRRIntervals() {
        // Flags=0x10 (RR present), BPM=68, RR=0x0380 (896/1024*1000 ≈ 875 ms)
        let data = Data([0x10, 68, 0x80, 0x03])
        let hr = HeartRateParser.parse(data)

        XCTAssertNotNil(hr)
        XCTAssertEqual(hr?.bpm, 68)
        XCTAssertEqual(hr?.rrIntervals.count, 1)
        XCTAssertEqual(hr!.rrIntervals[0], 875.0, accuracy: 1.0)
    }

    func testParseTooShort() {
        let data = Data([0x00])
        XCTAssertNil(HeartRateParser.parse(data))
    }

    // MARK: - Polar ECG Parser

    func testParseEcgFrame() {
        // Build a minimal ECG frame:
        // Byte 0: type=0x00 (ECG)
        // Bytes 1-8: timestamp=1000 (LE)
        // Byte 9: frame type
        // Bytes 10+: 2 samples (int24 LE)
        var data = Data([0x00])  // type

        // Timestamp: 1000 ns as uint64 LE
        var ts: UInt64 = 1000
        data.append(Data(bytes: &ts, count: 8))

        data.append(0x00)  // frame type byte

        // Sample 1: 500 µV = [0xF4, 0x01, 0x00]
        data.append(contentsOf: [0xF4, 0x01, 0x00])

        // Sample 2: -100 µV → two's complement int24
        // -100 = 0xFFFF9C → [0x9C, 0xFF, 0xFF]
        data.append(contentsOf: [0x9C, 0xFF, 0xFF])

        let frame = PolarEcgParser.parse(data)
        XCTAssertNotNil(frame)
        XCTAssertEqual(frame?.timestampNs, 1000)
        XCTAssertEqual(frame?.samplesUv.count, 2)
        XCTAssertEqual(frame?.samplesUv[0], 500)
        XCTAssertEqual(frame?.samplesUv[1], -100)
    }

    func testParseEcgFrameWrongType() {
        // Type byte != 0x00 should return nil
        var data = Data([0x01])  // Not ECG
        var ts: UInt64 = 0
        data.append(Data(bytes: &ts, count: 8))
        data.append(0x00)

        XCTAssertNil(PolarEcgParser.parse(data))
    }

    func testParseEcgFrameTooShort() {
        let data = Data([0x00, 0x01, 0x02])
        XCTAssertNil(PolarEcgParser.parse(data))
    }

    func testParseEcgFrameNoSamples() {
        // Valid header but no sample data
        var data = Data([0x00])
        var ts: UInt64 = 500
        data.append(Data(bytes: &ts, count: 8))
        data.append(0x00)

        let frame = PolarEcgParser.parse(data)
        XCTAssertNotNil(frame)
        XCTAssertEqual(frame?.samplesUv.count, 0)
    }

    // MARK: - Constants

    func testPolarEcgConstants() {
        XCTAssertEqual(kPolarEcgSampleRate, 130.0)
        XCTAssertEqual(kPolarEcgStartCommand.count, 10)
        XCTAssertEqual(kPolarEcgStartCommand[0], 0x02)
        XCTAssertEqual(kPolarEcgStopCommand.count, 2)
        XCTAssertEqual(kPolarEcgStopCommand[0], 0x03)
    }

    func testServiceUUIDs() {
        XCTAssertEqual(kHeartRateServiceUUID, CBUUID(string: "180D"))
        XCTAssertEqual(kHeartRateMeasurementUUID, CBUUID(string: "2A37"))
        XCTAssertNotNil(kPolarPMDServiceUUID)
        XCTAssertNotNil(kPolarPMDControlUUID)
        XCTAssertNotNil(kPolarPMDDataUUID)
    }
}
