// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 09 — Device Information Service
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 27
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Read device identity from the standard DIS (0x180A).
//
// Usage   : let info = BLEDeviceInfo.read(on: peripheral)
//
// Depends : Sprint 22-23 (Discovery + Read)
// ─────────────────────────────────────────────────────

@preconcurrency import CoreBluetooth
import Foundation
import os.log

// MARK: - Constants

/// Device Information Service UUID (0x180A).
public let kDISServiceUUID = CBUUID(string: "180A")

/// DIS characteristic UUIDs.
public let kSerialNumberUUID = CBUUID(string: "2A25")
public let kModelNumberUUID = CBUUID(string: "2A24")
public let kFirmwareRevisionUUID = CBUUID(string: "2A26")
public let kHardwareRevisionUUID = CBUUID(string: "2A27")
public let kManufacturerNameUUID = CBUUID(string: "2A29")

// MARK: - DeviceInfo

/// Device identification data from the DIS.
///
/// All fields are optional — not all devices expose every
/// DIS characteristic.
public struct DeviceInfo: Sendable {
    public let serialNumber: String?
    public let modelNumber: String?
    public let firmwareRevision: String?
    public let hardwareRevision: String?
    public let manufacturerName: String?

    /// Best available unique identifier.
    ///
    /// Prefers serial number, falls back to "manufacturer model"
    /// composite, or `nil` if neither is available.
    public var identifier: String? {
        if let serial = serialNumber, !serial.isEmpty {
            return serial
        }
        if let mfr = manufacturerName, let model = modelNumber {
            return "\(mfr) \(model)"
        }
        return modelNumber ?? manufacturerName
    }
}

// MARK: - BLEDeviceInfo

/// Reads device information from the standard DIS (0x180A).
///
/// ## Example
/// ```swift
/// if let info = BLEDeviceInfo.read(on: peripheral) {
///     print("Model: \(info.modelNumber ?? "Unknown")")
///     print("ID: \(info.identifier ?? "N/A")")
/// }
/// ```
public enum BLEDeviceInfo {

    private static let logger = Logger(
        subsystem: "com.ble-reference",
        category: "BLEDeviceInfo"
    )

    /// Check if a peripheral has the Device Information Service.
    public static func hasDIS(on peripheral: CBPeripheral) -> Bool {
        peripheral.services?.contains { $0.uuid == kDISServiceUUID } ?? false
    }

    /// Read all DIS characteristics.
    ///
    /// - Parameter peripheral: A connected peripheral with discovered
    ///   services and characteristics.
    /// - Returns: `DeviceInfo` if DIS is present, `nil` otherwise.
    public static func read(on peripheral: CBPeripheral) -> DeviceInfo? {
        guard let service = peripheral.services?.first(where: { $0.uuid == kDISServiceUUID }) else {
            logger.info("Device Information Service not available")
            return nil
        }

        let chars = service.characteristics ?? []

        func stringValue(for uuid: CBUUID) -> String? {
            guard let char = chars.first(where: { $0.uuid == uuid }),
                  let data = char.value else { return nil }
            return String(data: data, encoding: .utf8)
        }

        let info = DeviceInfo(
            serialNumber: stringValue(for: kSerialNumberUUID),
            modelNumber: stringValue(for: kModelNumberUUID),
            firmwareRevision: stringValue(for: kFirmwareRevisionUUID),
            hardwareRevision: stringValue(for: kHardwareRevisionUUID),
            manufacturerName: stringValue(for: kManufacturerNameUUID)
        )

        logger.info("Device: \(info.identifier ?? "Unknown")")
        return info
    }
}
