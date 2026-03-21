// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 14 — Bonding & Pairing
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 32
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Document Apple's implicit pairing model.
//
// Depends : Sprint 21 (Connection)
// ─────────────────────────────────────────────────────

// MARK: - Overview
//
// On Apple platforms, BLE pairing is **entirely implicit**:
//
// 1. There is NO `pair()` method in CoreBluetooth.
// 2. Pairing is triggered **automatically** by the OS when the app
//    tries to access an encrypted characteristic.
// 3. The OS presents a system pairing dialog — the app cannot
//    customize or suppress it.
// 4. Once paired, the bond is stored by the OS and persists across
//    app launches and device reboots.
// 5. There is NO `unpair()` method — users must unpair via
//    Settings → Bluetooth → (i) → Forget This Device.
// 6. There is NO way to query paired status programmatically.
//
// This is fundamentally different from:
// - Python/Bleak: `client.pair()` / `client.unpair()` (on Linux)
// - Android: `device.createBond()` / `device.removeBond()`
//
// What this means for your code:
// - Just read/write encrypted characteristics normally.
// - The OS handles pairing transparently.
// - If you need to detect pairing failure, catch the
//   `CBATTError.insufficientAuthentication` error.
//
// Reference:
// Apple Developer Forums — "CoreBluetooth Pairing" (multiple threads).
// Apple does not document pairing in the official CoreBluetooth guide.

import Foundation

// MARK: - Pairing Status

/// Pairing status on Apple platforms.
///
/// Since CoreBluetooth does not expose pairing state, this enum
/// represents what the application can infer from behavior.
public enum BLEPairingStatus: String, Sendable {
    /// Pairing state is unknown (Apple default).
    case unknown

    /// An encrypted characteristic was read successfully,
    /// implying the device is paired.
    case likelyPaired

    /// Access to an encrypted characteristic failed with
    /// `insufficientAuthentication`, implying pairing is needed.
    case pairingRequired
}

/// Utilities for BLE pairing on Apple platforms.
public enum BLEPairing {

    /// Infer pairing status from a characteristic read result.
    ///
    /// - Parameter error: The error from a read attempt, if any.
    /// - Returns: Inferred pairing status.
    ///
    /// - Note: This is a heuristic — Apple does not provide a
    ///   direct pairing status API.
    public static func inferStatus(from error: Error?) -> BLEPairingStatus {
        guard let error = error as NSError? else {
            return .likelyPaired
        }

        // CBATTError.insufficientAuthentication = 0x05
        if error.domain == "CBATTErrorDomain" && error.code == 0x05 {
            return .pairingRequired
        }

        return .unknown
    }
}
