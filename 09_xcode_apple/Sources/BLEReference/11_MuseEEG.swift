// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 11 — Muse 2 EEG
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 29
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Parse Muse 2 EEG packets (12-bit packed samples).
//           Cross-validates against Sprint 11 (Python).
//
// Usage   : let packet = MuseEegParser.parse(data)
//
// Depends : Sprint 24 (Notifications)
// ─────────────────────────────────────────────────────

import Foundation
@preconcurrency import CoreBluetooth

// MARK: - Constants

/// Muse GATT service UUID (proprietary).
public let kMuseServiceUUID = CBUUID(string: "0000FE8D-0000-1000-8000-00805F9B34FB")

/// Muse control characteristic (write-without-response).
public let kMuseControlUUID = CBUUID(string: "273E0001-4C4D-454D-96BE-F03BAC821358")

/// EEG channel characteristic UUIDs (TP9, AF7, AF8, TP10).
public let kMuseEegChannelUUIDs: [CBUUID] = [
    CBUUID(string: "273E0003-4C4D-454D-96BE-F03BAC821358"),  // TP9
    CBUUID(string: "273E0004-4C4D-454D-96BE-F03BAC821358"),  // AF7
    CBUUID(string: "273E0005-4C4D-454D-96BE-F03BAC821358"),  // AF8
    CBUUID(string: "273E0006-4C4D-454D-96BE-F03BAC821358"),  // TP10
]

/// Muse channel names.
public let kMuseChannelNames = ["TP9", "AF7", "AF8", "TP10"]

/// Muse EEG sample rate (Hz).
public let kMuseSampleRate: Double = 256.0

/// Scale factor: raw 12-bit → µV.
/// µV = 0.48828125 × (raw - 2048)
public let kMuseScaleFactor: Double = 0.48828125
public let kMuseOffset: Double = 2048.0

/// ASK command (request version/handshake).
public let kMuseAskCommand = Data([0x02, 0x73, 0x0A])

/// STREAM command (start streaming).
public let kMuseStreamCommand = Data([0x02, 0x64, 0x0A])

// MARK: - Data Structures

/// A single Muse EEG packet (one channel, 12 samples).
public struct MuseEegPacket: Sendable {
    /// Packet counter (0–65535).
    public let counter: Int

    /// Raw 12-bit samples (0–4095).
    public let rawSamples: [Int]

    /// Samples converted to microvolts.
    public var samplesUv: [Double] {
        rawSamples.map { kMuseScaleFactor * (Double($0) - kMuseOffset) }
    }
}

// MARK: - 12-bit Unpacking

/// Unpack 12-bit packed samples from Muse EEG data.
///
/// Each group of 3 bytes encodes 2 samples:
/// ```
/// sample_a = (b0 << 4) | (b1 >> 4)
/// sample_b = ((b1 & 0x0F) << 8) | b2
/// ```
///
/// A Muse packet has 18 bytes of sample data (bytes 2–19),
/// producing 12 samples per packet.
public func unpackUint12(_ data: Data, offset: Int, count: Int = 6) -> [Int] {
    var samples: [Int] = []
    for i in 0..<count {
        let idx = offset + i * 3
        guard idx + 2 < data.count else { break }
        let b0 = Int(data[idx])
        let b1 = Int(data[idx + 1])
        let b2 = Int(data[idx + 2])
        samples.append((b0 << 4) | (b1 >> 4))
        samples.append(((b1 & 0x0F) << 8) | b2)
    }
    return samples
}

// MARK: - MuseEegParser

/// Parses Muse 2 EEG notification data.
///
/// Muse EEG packet format (20 bytes):
/// - Bytes 0–1: Counter (uint16 BE)
/// - Bytes 2–19: 12 packed 12-bit samples (6 groups of 3 bytes)
public enum MuseEegParser {

    /// Parse a Muse EEG notification.
    ///
    /// - Parameter data: Raw notification data (20 bytes expected).
    /// - Returns: Parsed packet, or `nil` if data is too short.
    public static func parse(_ data: Data) -> MuseEegPacket? {
        guard data.count >= 20 else { return nil }

        let counter = (Int(data[0]) << 8) | Int(data[1])
        let samples = unpackUint12(data, offset: 2, count: 6)

        return MuseEegPacket(
            counter: counter,
            rawSamples: samples
        )
    }
}
