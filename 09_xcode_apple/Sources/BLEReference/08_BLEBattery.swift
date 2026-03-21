// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 08 — Battery Level
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 26
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Read battery level from the standard Battery Service (0x180F).
//
// Usage   : let level = try await BLEBattery.readLevel(on: peripheral)
//
// Depends : Sprint 23 (Read/Write)
// ─────────────────────────────────────────────────────

@preconcurrency import CoreBluetooth
import Foundation
import os.log

// MARK: - Constants

/// Standard Battery Service UUID (0x180F).
public let kBatteryServiceUUID = CBUUID(string: "180F")

/// Standard Battery Level characteristic UUID (0x2A19).
public let kBatteryLevelUUID = CBUUID(string: "2A19")

// MARK: - BLEBattery

/// Reads battery level from the standard BLE Battery Service.
///
/// The Battery Service (0x180F) is one of the most common GATT
/// services. It exposes a single characteristic (0x2A19) containing
/// the battery percentage as a uint8 (0–100).
///
/// ## Example
/// ```swift
/// if let level = try await BLEBattery.readLevel(on: peripheral) {
///     print("Battery: \(level)%")
/// }
/// ```
///
/// - Note: Returns `nil` if the peripheral does not expose the
///   Battery Service. Not all BLE devices support it.
public enum BLEBattery {

    private static let logger = Logger(
        subsystem: "com.ble-reference",
        category: "BLEBattery"
    )

    /// Check if a peripheral has the Battery Service.
    ///
    /// - Parameter peripheral: A connected peripheral with discovered services.
    /// - Returns: `true` if Battery Service (0x180F) is present.
    public static func hasBatteryService(on peripheral: CBPeripheral) -> Bool {
        peripheral.services?.contains { $0.uuid == kBatteryServiceUUID } ?? false
    }

    /// Read the battery level percentage.
    ///
    /// CoreBluetooth does not pre-populate `characteristic.value`
    /// during discovery — the value must be explicitly read via
    /// `peripheral.readValue(for:)`.
    ///
    /// - Parameters:
    ///   - peripheral: A connected peripheral with discovered
    ///     services and characteristics.
    ///   - readWriter: A `BLEReadWriter` to perform the read.
    /// - Returns: Battery level (0–100), or `nil` if Battery Service
    ///   is not present on this device.
    public static func readLevel(
        on peripheral: CBPeripheral,
        using readWriter: BLEReadWriter
    ) async -> Int? {
        guard let service = peripheral.services?.first(where: { $0.uuid == kBatteryServiceUUID }),
              let char = service.characteristics?.first(where: { $0.uuid == kBatteryLevelUUID })
        else {
            logger.info("Battery Service not available")
            return nil
        }

        guard let data = try? await readWriter.read(char, on: peripheral),
              let level = data.first
        else {
            logger.info("Battery level read failed")
            return nil
        }

        let percentage = Int(level)
        logger.info("Battery level: \(percentage)%")
        return percentage
    }
}
