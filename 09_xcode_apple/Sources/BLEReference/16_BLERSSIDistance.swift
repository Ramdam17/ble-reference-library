// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 16 — RSSI & Distance Estimation
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 34
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Estimate distance from RSSI using log-distance model.
//           Matches Python reference (Sprint 16).
//
// Depends : Sprint 19 (Passive Scan)
// ─────────────────────────────────────────────────────

import Foundation

// MARK: - Constants

/// Default TX power at 1 meter (dBm). iBeacon standard.
public let kDefaultTxPower: Int = -59

/// Default path loss exponent (free space).
public let kDefaultPathLossExponent: Double = 2.0

// MARK: - Proximity Zone

/// iBeacon-style proximity classification.
public enum ProximityZone: String, Sendable {
    case immediate  // < 0.5 m
    case near       // 0.5 – 3.0 m
    case far        // > 3.0 m
}

// MARK: - Distance Estimation

/// RSSI-based distance estimation utilities.
///
/// Uses the log-distance path loss model:
///     d = 10 ^ ((txPower - rssi) / (10 × n))
///
/// Warning: Accuracy is ±50–200%. Use for relative proximity only.
///
/// Reference: Rappaport, T.S. (2002). Wireless Communications.
public enum BLERSSIDistance {

    /// Estimate distance from an RSSI measurement.
    ///
    /// - Parameters:
    ///   - rssi: Measured RSSI in dBm.
    ///   - txPower: Calibrated TX power at 1 meter (default: -59).
    ///   - n: Path loss exponent (default: 2.0 for free space).
    ///
    /// - Returns: Estimated distance in meters.
    public static func estimateDistance(
        rssi: Int,
        txPower: Int = kDefaultTxPower,
        n: Double = kDefaultPathLossExponent
    ) -> Double {
        let ratio = Double(txPower - rssi) / (10.0 * n)
        return pow(10.0, ratio)
    }

    /// Classify distance into a proximity zone.
    public static func classifyProximity(distanceM: Double) -> ProximityZone {
        if distanceM < 0.5 { return .immediate }
        if distanceM <= 3.0 { return .near }
        return .far
    }

    /// Convert RSSI to a 0–1 signal strength ratio.
    public static func signalRatio(
        rssi: Int,
        txPower: Int = kDefaultTxPower,
        floor: Int = -100
    ) -> Double {
        let clamped = max(floor, min(rssi, txPower))
        return Double(clamped - floor) / Double(txPower - floor)
    }

    /// Smooth RSSI readings with exponential moving average.
    public static func smoothRSSI(
        readings: [Int],
        alpha: Double = 0.3
    ) -> [Double] {
        guard !readings.isEmpty else { return [] }
        var smoothed = [Double(readings[0])]
        for i in 1..<readings.count {
            let s = alpha * Double(readings[i]) + (1 - alpha) * smoothed[i - 1]
            smoothed.append(s)
        }
        return smoothed
    }
}
