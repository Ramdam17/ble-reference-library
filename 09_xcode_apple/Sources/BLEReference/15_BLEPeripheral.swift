// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 15 — Peripheral Mode
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 33
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Act as a BLE peripheral using CBPeripheralManager.
//
// Depends : Sprint 21 (Connection concepts)
// ─────────────────────────────────────────────────────

// MARK: - Overview
//
// Unlike Python (where we needed the `bless` library),
// Apple provides **native** peripheral support via
// `CBPeripheralManager`. This is a first-class API on both
// iOS and macOS.
//
// Peripheral lifecycle:
// 1. Create CBPeripheralManager → wait for .poweredOn
// 2. Build CBMutableService with CBMutableCharacteristic(s)
// 3. Add service via peripheralManager.add(service)
// 4. Start advertising via peripheralManager.startAdvertising(...)
// 5. Handle read/write/subscribe requests via delegate
// 6. Send notifications via peripheralManager.updateValue(...)
//
// Platform differences:
// - iOS: Background advertising limited to service UUIDs only
//   (no local name in background mode).
// - macOS: Full advertising support, no background restrictions.
// - Both: The OS assigns a random resolvable private address.

@preconcurrency import CoreBluetooth
import Foundation
import os.log

// MARK: - Data Structures

/// Configuration for a peripheral's GATT service.
public struct PeripheralServiceConfig: Sendable {
    public let serviceUUID: CBUUID
    public let characteristics: [PeripheralCharConfig]

    public init(serviceUUID: CBUUID, characteristics: [PeripheralCharConfig]) {
        self.serviceUUID = serviceUUID
        self.characteristics = characteristics
    }
}

/// Configuration for a peripheral's GATT characteristic.
public struct PeripheralCharConfig: Sendable {
    public let uuid: CBUUID
    public let properties: CBCharacteristicProperties
    public let permissions: CBAttributePermissions
    public let initialValue: Data?

    public init(
        uuid: CBUUID,
        properties: CBCharacteristicProperties,
        permissions: CBAttributePermissions,
        initialValue: Data? = nil
    ) {
        self.uuid = uuid
        self.properties = properties
        self.permissions = permissions
        self.initialValue = initialValue
    }
}

// MARK: - Heart Rate Peripheral Builder

/// Build a standard Heart Rate Service for peripheral mode.
///
/// Creates a service matching what real HR monitors advertise,
/// useful for testing central-side code without hardware.
public func buildHeartRatePeripheralService(
    initialBPM: UInt8 = 72
) -> PeripheralServiceConfig {
    PeripheralServiceConfig(
        serviceUUID: CBUUID(string: "180D"),
        characteristics: [
            PeripheralCharConfig(
                uuid: CBUUID(string: "2A37"),
                properties: [.notify, .read],
                permissions: [.readable],
                initialValue: Data([0x00, initialBPM])
            )
        ]
    )
}
