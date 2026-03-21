// Tests — Module 11: Muse EEG (Sprint 29)

import XCTest
@testable import BLEReference

final class Test11_MuseEEG: XCTestCase {

    func testUnpackUint12() {
        // 3 bytes → 2 samples: [0xAB, 0xCD, 0xEF]
        // sample_a = (0xAB << 4) | (0xCD >> 4) = 0xABC = 2748
        // sample_b = ((0xCD & 0x0F) << 8) | 0xEF = 0xDEF = 3567
        let data = Data([0xAB, 0xCD, 0xEF])
        let samples = unpackUint12(data, offset: 0, count: 1)
        XCTAssertEqual(samples, [2748, 3567])
    }

    func testMuseEegParse() {
        // Build a 20-byte Muse packet
        var data = Data(count: 20)
        data[0] = 0x00  // Counter high byte
        data[1] = 0x05  // Counter low byte → counter=5

        // Fill bytes 2-19 with known pattern
        for i in 2..<20 {
            data[i] = UInt8(i)
        }

        let packet = MuseEegParser.parse(data)
        XCTAssertNotNil(packet)
        XCTAssertEqual(packet?.counter, 5)
        XCTAssertEqual(packet?.rawSamples.count, 12)
    }

    func testMuseEegParseTooShort() {
        XCTAssertNil(MuseEegParser.parse(Data([0x00, 0x01])))
    }

    func testMuseScaling() {
        // Raw 2048 → 0 µV (baseline)
        let packet = MuseEegPacket(counter: 0, rawSamples: [2048, 4095, 0])
        let uv = packet.samplesUv
        XCTAssertEqual(uv[0], 0.0, accuracy: 0.001)
        XCTAssertTrue(uv[1] > 0)  // Above baseline
        XCTAssertTrue(uv[2] < 0)  // Below baseline
    }

    func testMuseConstants() {
        XCTAssertEqual(kMuseSampleRate, 256.0)
        XCTAssertEqual(kMuseChannelNames.count, 4)
        XCTAssertEqual(kMuseEegChannelUUIDs.count, 4)
        XCTAssertEqual(kMuseAskCommand.count, 3)
        XCTAssertEqual(kMuseStreamCommand.count, 3)
    }
}
