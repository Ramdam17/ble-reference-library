// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 13 — MTU Negotiation
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 31
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Query and compute MTU-related values.
//
// Depends : Sprint 21 (Connection)
// ─────────────────────────────────────────────────────

// MARK: - Overview
//
// On Apple platforms (iOS/macOS), MTU negotiation is **fully automatic**.
// CoreBluetooth negotiates the maximum MTU during connection — the
// application cannot request a specific value.
//
// `peripheral.maximumWriteValueLength(for:)` returns the effective
// payload size (MTU - 3 ATT overhead) for a given write type.
//
// Typical negotiated MTUs on Apple:
// - iOS ↔ modern peripheral: 185–512 bytes
// - macOS ↔ modern peripheral: 512 bytes
// - iOS ↔ older peripheral: 23 bytes (BLE 4.0)

@preconcurrency import CoreBluetooth
import Foundation

// MARK: - Constants

/// BLE specification minimum MTU (bytes).
public let kDefaultBLEMTU: Int = 23

/// ATT protocol overhead per packet (bytes).
public let kATTHeaderBytes: Int = 3

/// Maximum allowed MTU per BLE specification.
public let kMaxBLEMTU: Int = 517

// MARK: - MTU Utilities

/// MTU-related utilities for BLE connections.
public enum BLEMTU {

    /// Compute the usable payload size for a given MTU.
    ///
    /// - Parameter mtu: Negotiated MTU in bytes.
    /// - Returns: Payload size (MTU - 3).
    /// - Throws: If MTU is below the BLE minimum (23).
    public static func effectivePayloadSize(mtu: Int) throws -> Int {
        guard mtu >= kDefaultBLEMTU else {
            throw BLEError.timeout(
                operation: "MTU \(mtu) is below the BLE minimum (\(kDefaultBLEMTU))",
                seconds: 0
            )
        }
        return mtu - kATTHeaderBytes
    }

    /// Get the maximum write payload for a connected peripheral.
    ///
    /// - Parameters:
    ///   - peripheral: A connected CBPeripheral.
    ///   - type: Write type (.withResponse or .withoutResponse).
    ///
    /// - Returns: Maximum bytes per write operation.
    ///
    /// - Note: On Apple platforms, this reflects the OS-negotiated
    ///   MTU. You cannot change it — it's informational only.
    public static func maxWriteLength(
        for peripheral: CBPeripheral,
        type: CBCharacteristicWriteType = .withoutResponse
    ) -> Int {
        peripheral.maximumWriteValueLength(for: type)
    }
}
