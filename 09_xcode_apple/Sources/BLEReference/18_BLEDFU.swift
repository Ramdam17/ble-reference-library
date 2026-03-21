// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 18 — OTA / DFU
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 36
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Firmware chunking, transfer, and validation.
//           Matches Python reference (Sprint 18).
//
// Depends : Sprint 23 (Read/Write), Sprint 30 (Errors), Sprint 35 (Queue)
// ─────────────────────────────────────────────────────

import CryptoKit
import Foundation

// MARK: - Constants

/// Default chunk size in bytes (MTU 247 - 3 ATT header).
public let kDefaultDFUChunkSize: Int = 244

// MARK: - DFU State

/// DFU transfer state machine.
public enum DFUState: String, Sendable {
    case idle
    case initializing
    case transferring
    case validating
    case activating
    case completed
    case failed
}

// MARK: - DFU Progress

/// Progress report for a DFU transfer.
public struct DFUProgress: Sendable {
    public let bytesSent: Int
    public let totalBytes: Int
    public let chunksSent: Int
    public let totalChunks: Int
    public let state: DFUState

    /// Transfer progress percentage (0–100).
    public var progressPct: Double {
        guard totalBytes > 0 else { return 100.0 }
        return Double(bytesSent) / Double(totalBytes) * 100.0
    }
}

/// Progress callback type.
public typealias DFUProgressCallback = @Sendable (DFUProgress) -> Void

// MARK: - Firmware Image

/// A firmware binary ready for DFU transfer.
public struct FirmwareImage: Sendable {
    public let data: Data
    public let version: String

    /// SHA-256 hex digest of the firmware data.
    public var sha256: String {
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    /// Firmware size in bytes.
    public var size: Int { data.count }

    public init(data: Data, version: String = "0.0.0") {
        self.data = data
        self.version = version
    }
}

// MARK: - Chunking

/// Split firmware binary into BLE-sized chunks.
///
/// - Parameters:
///   - data: Raw firmware binary.
///   - chunkSize: Maximum bytes per chunk (default: 244).
///
/// - Returns: Ordered list of chunks.
public func chunkFirmware(_ data: Data, chunkSize: Int = kDefaultDFUChunkSize) -> [Data] {
    guard !data.isEmpty, chunkSize > 0 else { return [] }

    var chunks: [Data] = []
    var offset = 0
    while offset < data.count {
        let end = min(offset + chunkSize, data.count)
        chunks.append(data[offset..<end])
        offset = end
    }
    return chunks
}

/// Compute the number of chunks needed.
public func computeTotalChunks(firmwareSize: Int, chunkSize: Int = kDefaultDFUChunkSize) -> Int {
    guard chunkSize > 0 else { return 0 }
    return (firmwareSize + chunkSize - 1) / chunkSize
}

// MARK: - Transfer

/// Transfer firmware chunks with progress reporting.
///
/// - Parameters:
///   - chunks: Ordered firmware chunks.
///   - writeFn: Async function to write one chunk.
///   - onProgress: Optional progress callback.
///   - writeDelay: Delay between writes in seconds (default: 0.02).
///
/// - Returns: Final progress report.
public func dfuTransfer(
    chunks: [Data],
    writeFn: @Sendable (Data) async throws -> Void,
    onProgress: DFUProgressCallback? = nil,
    writeDelay: TimeInterval = 0.02
) async throws -> DFUProgress {
    let totalBytes = chunks.reduce(0) { $0 + $1.count }
    let totalChunks = chunks.count
    var bytesSent = 0

    for (i, chunk) in chunks.enumerated() {
        try await writeFn(chunk)
        bytesSent += chunk.count

        let progress = DFUProgress(
            bytesSent: bytesSent,
            totalBytes: totalBytes,
            chunksSent: i + 1,
            totalChunks: totalChunks,
            state: .transferring
        )
        onProgress?(progress)

        if writeDelay > 0 && i < totalChunks - 1 {
            try await Task.sleep(nanoseconds: UInt64(writeDelay * 1_000_000_000))
        }
    }

    return DFUProgress(
        bytesSent: bytesSent,
        totalBytes: totalBytes,
        chunksSent: totalChunks,
        totalChunks: totalChunks,
        state: .validating
    )
}

// MARK: - Validation

/// Validate firmware integrity.
public func validateFirmware(_ image: FirmwareImage, expectedSHA256: String) -> Bool {
    image.sha256 == expectedSHA256
}
