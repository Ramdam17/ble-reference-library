// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 05 — Read & Write
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 23
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Read from and write to GATT characteristics.
//
// Usage   : let data = try await reader.read(characteristic, on: peripheral)
//
// Depends : Sprint 22 (Services Discovery)
// ─────────────────────────────────────────────────────

// MARK: - Overview
//
// CoreBluetooth read/write lifecycle:
//
// **Read:**
// 1. `peripheral.readValue(for: characteristic)`
// 2. Delegate: `didUpdateValueFor` callback with `characteristic.value`
//
// **Write (with response):**
// 1. `peripheral.writeValue(data, for: characteristic, type: .withResponse)`
// 2. Delegate: `didWriteValueFor` callback confirms write.
//
// **Write (without response):**
// 1. `peripheral.writeValue(data, for: characteristic, type: .withoutResponse)`
// 2. No delegate callback — fire-and-forget.
//    Use `peripheralIsReady(toSendWriteWithoutResponse:)` for flow control.
//
// Key difference from Python/Bleak:
// - Bleak: `await client.read_gatt_char(uuid)` returns bytes directly.
// - CoreBluetooth: You call readValue, then get bytes in a delegate callback.
//   This module bridges the gap with async/await.

@preconcurrency import CoreBluetooth
import Foundation
import os.log

// MARK: - Read/Write Errors

/// Errors for GATT read/write operations.
public enum BLEReadWriteError: LocalizedError, Sendable {
    /// Characteristic does not support the requested operation.
    case unsupportedOperation(String)

    /// Read returned no data.
    case noData

    /// Write failed.
    case writeFailed(reason: String)

    public var errorDescription: String? {
        switch self {
        case .unsupportedOperation(let op):
            return "Characteristic does not support \(op)"
        case .noData:
            return "Read returned no data"
        case .writeFailed(let reason):
            return "Write failed: \(reason)"
        }
    }
}

// MARK: - BLEReadWriter

/// Reads from and writes to GATT characteristics.
///
/// Bridges CoreBluetooth's delegate-based read/write to async/await.
///
/// ## Example
/// ```swift
/// let rw = BLEReadWriter()
/// let batteryData = try await rw.read(batteryChar, on: peripheral)
/// let level = batteryData.first ?? 0
/// ```
public final class BLEReadWriter: NSObject {

    private let logger = Logger(
        subsystem: "com.ble-reference",
        category: "BLEReadWrite"
    )

    private var readContinuation: CheckedContinuation<Data, Error>?
    private var writeContinuation: CheckedContinuation<Void, Error>?

    // MARK: - Read

    /// Read a characteristic's current value.
    ///
    /// - Parameters:
    ///   - characteristic: The characteristic to read.
    ///   - peripheral: The connected peripheral.
    ///
    /// - Returns: The characteristic's value as `Data`.
    ///
    /// - Throws: `BLEReadWriteError.unsupportedOperation` if the
    ///   characteristic does not have the `.read` property.
    public func read(
        _ characteristic: CBCharacteristic,
        on peripheral: CBPeripheral
    ) async throws -> Data {
        guard characteristic.properties.contains(.read) else {
            throw BLEReadWriteError.unsupportedOperation("read")
        }

        return try await withCheckedThrowingContinuation { cont in
            self.readContinuation = cont
            peripheral.delegate = self
            peripheral.readValue(for: characteristic)
        }
    }

    // MARK: - Write

    /// Write data to a characteristic (with response).
    ///
    /// - Parameters:
    ///   - data: Bytes to write.
    ///   - characteristic: Target characteristic.
    ///   - peripheral: The connected peripheral.
    ///
    /// - Throws: `BLEReadWriteError.unsupportedOperation` if the
    ///   characteristic does not support `.write`.
    public func write(
        _ data: Data,
        to characteristic: CBCharacteristic,
        on peripheral: CBPeripheral
    ) async throws {
        guard characteristic.properties.contains(.write) else {
            throw BLEReadWriteError.unsupportedOperation("write")
        }

        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            self.writeContinuation = cont
            peripheral.delegate = self
            peripheral.writeValue(data, for: characteristic, type: .withResponse)
        }
    }

    /// Write data without response (fire-and-forget).
    ///
    /// - Parameters:
    ///   - data: Bytes to write.
    ///   - characteristic: Target characteristic.
    ///   - peripheral: The connected peripheral.
    ///
    /// - Note: No confirmation callback — the write is best-effort.
    ///   Use for high-throughput scenarios (e.g., streaming commands).
    public func writeWithoutResponse(
        _ data: Data,
        to characteristic: CBCharacteristic,
        on peripheral: CBPeripheral
    ) throws {
        guard characteristic.properties.contains(.writeWithoutResponse) else {
            throw BLEReadWriteError.unsupportedOperation("writeWithoutResponse")
        }

        peripheral.writeValue(data, for: characteristic, type: .withoutResponse)
        logger.debug("Write-without-response: \(data.count) bytes")
    }
}

// MARK: - CBPeripheralDelegate

extension BLEReadWriter: CBPeripheralDelegate {

    public func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        if let error {
            readContinuation?.resume(throwing: error)
            readContinuation = nil
            return
        }

        guard let value = characteristic.value else {
            readContinuation?.resume(throwing: BLEReadWriteError.noData)
            readContinuation = nil
            return
        }

        logger.debug("Read \(value.count) bytes from \(characteristic.uuid)")
        readContinuation?.resume(returning: value)
        readContinuation = nil
    }

    public func peripheral(
        _ peripheral: CBPeripheral,
        didWriteValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        if let error {
            writeContinuation?.resume(throwing: error)
        } else {
            logger.debug("Write confirmed for \(characteristic.uuid)")
            writeContinuation?.resume()
        }
        writeContinuation = nil
    }
}
