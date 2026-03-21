// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 10 — Heart Rate Monitor + Polar ECG
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 28
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Parse Heart Rate Measurement (0x2A37) notifications
//           and Polar PMD ECG data. Provides the same dual
//           functionality as Sprint 10 (Python).
//
// Usage   : let hr = HeartRateParser.parse(data)
//           let ecg = PolarEcgParser.parse(data)
//
// Depends : Sprint 24 (Notifications)
// ─────────────────────────────────────────────────────

// MARK: - Overview
//
// Heart Rate Measurement (BLE standard):
// - Service: 0x180D (Heart Rate Service)
// - Characteristic: 0x2A37 (Heart Rate Measurement) — NOTIFY
// - Format: Byte 0 = flags, then BPM (uint8 or uint16),
//   optional RR-intervals.
//
// Polar PMD ECG (vendor-specific):
// - Service: FB005C80-02E7-F387-1CAD-8ACD2D8DF0C8
// - Control Point: FB005C81-... (write to start/stop)
// - Data: FB005C82-... (notify, ECG samples)
// - Format: 1-byte type, 8-byte timestamp, 2-byte frame type,
//   then int24 signed samples (µV) at 130 Hz.
//
// Reference:
// Bluetooth SIG — Heart Rate Profile v1.0.
// Polar Developer Documentation — PMD Protocol.

import Foundation

// MARK: - Constants

/// Standard Heart Rate Service UUID.
public let kHeartRateServiceUUID = CBUUID(string: "180D")

/// Standard Heart Rate Measurement characteristic UUID.
public let kHeartRateMeasurementUUID = CBUUID(string: "2A37")

/// Polar PMD Service UUID.
public let kPolarPMDServiceUUID = CBUUID(string: "FB005C80-02E7-F387-1CAD-8ACD2D8DF0C8")

/// Polar PMD Control Point UUID (write to start/stop streaming).
public let kPolarPMDControlUUID = CBUUID(string: "FB005C81-02E7-F387-1CAD-8ACD2D8DF0C8")

/// Polar PMD Data UUID (notify with ECG/ACC/PPG data).
public let kPolarPMDDataUUID = CBUUID(string: "FB005C82-02E7-F387-1CAD-8ACD2D8DF0C8")

/// Command to start ECG streaming on Polar devices.
///
/// Format: [0x02, 0x00, 0x00, 0x01, 0x82, 0x00, 0x01, 0x01, 0x0E, 0x00]
/// - 0x02 = start streaming
/// - 0x00 = ECG measurement type
/// - Sample rate = 130 Hz (0x82, 0x00)
/// - Resolution = 14 bit (0x0E)
public let kPolarEcgStartCommand = Data([0x02, 0x00, 0x00, 0x01, 0x82, 0x00, 0x01, 0x01, 0x0E, 0x00])

/// Command to stop ECG streaming.
public let kPolarEcgStopCommand = Data([0x03, 0x00])

/// Polar ECG sampling rate in Hz.
public let kPolarEcgSampleRate: Double = 130.0

// MARK: - Heart Rate Measurement

/// Parsed BLE Heart Rate Measurement.
public struct HeartRateMeasurement: Sendable {
    /// Heart rate in beats per minute.
    public let bpm: Int

    /// Whether the BPM value is uint16 (true) or uint8 (false).
    public let isUint16: Bool

    /// Skin contact detected (if supported by the sensor).
    public let sensorContact: Bool?

    /// RR-intervals in milliseconds (time between heartbeats).
    ///
    /// Not all sensors report RR-intervals. When present, there
    /// may be multiple intervals per notification (the sensor
    /// batches them between transmissions).
    ///
    /// RR-intervals are essential for HRV (Heart Rate Variability)
    /// analysis — a key metric in hyperscanning and stress research.
    public let rrIntervals: [Double]
}

// MARK: - HeartRateParser

/// Parses BLE Heart Rate Measurement (0x2A37) notification data.
///
/// Supports both uint8 and uint16 BPM formats, sensor contact
/// detection, and RR-interval extraction.
///
/// ## Example
/// ```swift
/// // In notification handler:
/// if let hr = HeartRateParser.parse(data) {
///     print("\(hr.bpm) BPM")
///     for rr in hr.rrIntervals {
///         print("  RR: \(rr) ms")
///     }
/// }
/// ```
///
/// Reference: Bluetooth SIG, Heart Rate Measurement characteristic
/// format (GATT Specification Supplement, Section 3.107).
public enum HeartRateParser {

    /// Parse a Heart Rate Measurement notification.
    ///
    /// - Parameter data: Raw notification data from 0x2A37.
    /// - Returns: Parsed measurement, or `nil` if data is too short.
    public static func parse(_ data: Data) -> HeartRateMeasurement? {
        guard data.count >= 2 else { return nil }

        let flags = data[0]
        let isUint16 = (flags & 0x01) != 0
        let hasSensorContact = (flags & 0x06) == 0x06
        let sensorContactDetected = (flags & 0x02) != 0
        let hasRRIntervals = (flags & 0x10) != 0

        // Parse BPM
        let bpm: Int
        var offset: Int
        if isUint16 {
            guard data.count >= 3 else { return nil }
            bpm = Int(data[1]) | (Int(data[2]) << 8)
            offset = 3
        } else {
            bpm = Int(data[1])
            offset = 2
        }

        // Skip Energy Expended if present (flags bit 3)
        if (flags & 0x08) != 0 {
            offset += 2
        }

        // Parse RR-intervals
        var rrIntervals: [Double] = []
        if hasRRIntervals {
            while offset + 1 < data.count {
                let rawRR = Int(data[offset]) | (Int(data[offset + 1]) << 8)
                // RR value is in 1/1024 seconds — convert to ms
                let rrMs = Double(rawRR) / 1024.0 * 1000.0
                rrIntervals.append(rrMs)
                offset += 2
            }
        }

        return HeartRateMeasurement(
            bpm: bpm,
            isUint16: isUint16,
            sensorContact: hasSensorContact ? sensorContactDetected : nil,
            rrIntervals: rrIntervals
        )
    }
}

// MARK: - Polar ECG

/// A single Polar PMD ECG data frame.
///
/// Each notification from the PMD Data characteristic contains
/// one frame with a timestamp and multiple ECG samples.
public struct PolarEcgFrame: Sendable {
    /// Device timestamp in nanoseconds.
    public let timestampNs: UInt64

    /// ECG samples in microvolts (µV), signed int24.
    ///
    /// At 130 Hz, each frame typically contains ~13 samples
    /// (~100 ms of data per notification).
    public let samplesUv: [Int]
}

// MARK: - PolarEcgParser

/// Parses Polar PMD ECG data notifications.
///
/// The PMD data format:
/// - Byte 0: Measurement type (0x00 = ECG)
/// - Bytes 1–8: Timestamp (uint64, nanoseconds)
/// - Bytes 9–10: Frame type
/// - Bytes 10+: ECG samples, 3 bytes each (int24, little-endian, signed)
///
/// ## Example
/// ```swift
/// if let frame = PolarEcgParser.parse(data) {
///     print("ECG: \(frame.samplesUv.count) samples")
///     print("Timestamp: \(frame.timestampNs) ns")
/// }
/// ```
public enum PolarEcgParser {

    /// Parse a PMD ECG data notification.
    ///
    /// - Parameter data: Raw notification data from PMD Data characteristic.
    /// - Returns: Parsed ECG frame, or `nil` if data is invalid.
    public static func parse(_ data: Data) -> PolarEcgFrame? {
        // Minimum: 1 type + 8 timestamp + 1 frame type = 10 bytes
        guard data.count >= 10 else { return nil }

        // Byte 0: measurement type (0x00 = ECG)
        guard data[0] == 0x00 else { return nil }

        // Bytes 1-8: timestamp (uint64 LE)
        var timestampNs: UInt64 = 0
        for i in 0..<8 {
            timestampNs |= UInt64(data[1 + i]) << (i * 8)
        }

        // Bytes 10+: ECG samples (int24 LE, signed)
        let sampleStart = 10
        var samples: [Int] = []

        var offset = sampleStart
        while offset + 2 < data.count {
            let b0 = Int(data[offset])
            let b1 = Int(data[offset + 1])
            let b2 = Int(data[offset + 2])

            var value = b0 | (b1 << 8) | (b2 << 16)
            // Sign-extend from 24-bit
            if value >= 0x800000 {
                value -= 0x1000000
            }

            samples.append(value)
            offset += 3
        }

        return PolarEcgFrame(
            timestampNs: timestampNs,
            samplesUv: samples
        )
    }
}

// MARK: - Import for CBUUID

@preconcurrency import CoreBluetooth
