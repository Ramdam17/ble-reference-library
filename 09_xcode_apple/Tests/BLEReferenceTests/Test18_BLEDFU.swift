// Tests — Module 18: OTA / DFU (Sprint 36)

import XCTest
@testable import BLEReference

final class Test18_BLEDFU: XCTestCase {

    func testChunkFirmwareRemainder() {
        let data = Data(repeating: 0xAB, count: 500)
        let chunks = chunkFirmware(data, chunkSize: 244)
        XCTAssertEqual(chunks.count, 3)
        XCTAssertEqual(chunks[0].count, 244)
        XCTAssertEqual(chunks[1].count, 244)
        XCTAssertEqual(chunks[2].count, 12)
    }

    func testChunkFirmwareExact() {
        let data = Data(repeating: 0x00, count: 488)
        let chunks = chunkFirmware(data, chunkSize: 244)
        XCTAssertEqual(chunks.count, 2)
    }

    func testChunkFirmwarePreservesData() {
        let original = Data((0..<255).map { UInt8($0) })
        let chunks = chunkFirmware(original, chunkSize: 100)
        let reassembled = chunks.reduce(Data()) { $0 + $1 }
        XCTAssertEqual(reassembled, original)
    }

    func testComputeTotalChunks() {
        XCTAssertEqual(computeTotalChunks(firmwareSize: 500, chunkSize: 244), 3)
        XCTAssertEqual(computeTotalChunks(firmwareSize: 488, chunkSize: 244), 2)
        XCTAssertEqual(computeTotalChunks(firmwareSize: 1, chunkSize: 244), 1)
    }

    func testFirmwareImageSHA256() {
        let data = Data("hello firmware".utf8)
        let image = FirmwareImage(data: data, version: "1.0.0")
        XCTAssertEqual(image.size, data.count)
        XCTAssertFalse(image.sha256.isEmpty)
        XCTAssertEqual(image.sha256.count, 64)  // 32 bytes hex
    }

    func testValidateFirmware() {
        let image = FirmwareImage(data: Data("test".utf8))
        XCTAssertTrue(validateFirmware(image, expectedSHA256: image.sha256))
        XCTAssertFalse(validateFirmware(image, expectedSHA256: String(repeating: "0", count: 64)))
    }

    func testDfuTransfer() async throws {
        let data = Data(repeating: 0xFF, count: 500)
        let chunks = chunkFirmware(data, chunkSize: 244)
        var written: [Data] = []
        var reports: [DFUProgress] = []

        let result = try await dfuTransfer(
            chunks: chunks,
            writeFn: { chunk in written.append(chunk) },
            onProgress: { reports.append($0) },
            writeDelay: 0.0
        )

        XCTAssertEqual(written.count, 3)
        XCTAssertEqual(result.bytesSent, 500)
        XCTAssertEqual(result.state, .validating)
        XCTAssertEqual(reports.count, 3)
        XCTAssertEqual(reports.last?.progressPct ?? 0, 100.0, accuracy: 0.1)
    }
}
