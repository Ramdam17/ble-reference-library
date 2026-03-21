// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 06 — Notifications & Indications
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 24
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Subscribe to and handle GATT notifications/indications.
//
// Usage   : subscriber.subscribe(to: hrChar, on: peripheral) { data in ... }
//
// Depends : Sprint 22 (Services Discovery)
// ─────────────────────────────────────────────────────

// MARK: - Overview
//
// CoreBluetooth notification lifecycle:
//
// 1. `peripheral.setNotifyValue(true, for: characteristic)`
// 2. Delegate: `didUpdateNotificationStateFor` confirms subscription.
// 3. Each notification triggers `didUpdateValueFor` with new data.
// 4. `peripheral.setNotifyValue(false, for: characteristic)` to stop.
//
// Indications work identically from the API perspective — CoreBluetooth
// handles the ACK automatically. The only difference is the
// `.indicate` property flag instead of `.notify`.
//
// Key difference from Python/Bleak:
// - Bleak: `await client.start_notify(uuid, callback)` — one-liner.
// - CoreBluetooth: setNotifyValue + delegate chain. This module
//   wraps it with a clean callback-based API.

@preconcurrency import CoreBluetooth
import Foundation
import os.log

// MARK: - Notification Handler

/// Type alias for notification data callbacks.
public typealias NotificationHandler = @Sendable (Data) -> Void

// MARK: - BLENotificationSubscriber

/// Manages GATT notification subscriptions.
///
/// Supports subscribing to multiple characteristics simultaneously,
/// each with its own handler callback.
///
/// ## Example
/// ```swift
/// let subscriber = BLENotificationSubscriber()
/// subscriber.subscribe(to: hrChar, on: peripheral) { data in
///     let bpm = data[1]
///     print("Heart rate: \(bpm) BPM")
/// }
/// ```
public final class BLENotificationSubscriber: NSObject {

    private let logger = Logger(
        subsystem: "com.ble-reference",
        category: "BLENotifications"
    )

    /// Active handlers keyed by characteristic UUID string.
    private var handlers: [String: NotificationHandler] = [:]

    /// Continuation for subscribe confirmation.
    private var subscribeContinuation: CheckedContinuation<Void, Error>?

    /// Continuation for unsubscribe confirmation.
    private var unsubscribeContinuation: CheckedContinuation<Void, Error>?

    // MARK: - Subscribe

    /// Subscribe to notifications for a characteristic.
    ///
    /// - Parameters:
    ///   - characteristic: Must have `.notify` or `.indicate` property.
    ///   - peripheral: The connected peripheral.
    ///   - handler: Callback fired with each notification's data.
    ///
    /// - Throws: Error if the characteristic doesn't support notifications
    ///   or subscription fails.
    public func subscribe(
        to characteristic: CBCharacteristic,
        on peripheral: CBPeripheral,
        handler: @escaping NotificationHandler
    ) async throws {
        let supportsNotify = characteristic.properties.contains(.notify)
            || characteristic.properties.contains(.indicate)

        guard supportsNotify else {
            throw BLEReadWriteError.unsupportedOperation("notify/indicate")
        }

        handlers[characteristic.uuid.uuidString] = handler

        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            self.subscribeContinuation = cont
            peripheral.delegate = self
            peripheral.setNotifyValue(true, for: characteristic)
        }

        logger.info("Subscribed to \(characteristic.uuid)")
    }

    /// Unsubscribe from notifications.
    ///
    /// - Parameters:
    ///   - characteristic: The characteristic to unsubscribe from.
    ///   - peripheral: The connected peripheral.
    public func unsubscribe(
        from characteristic: CBCharacteristic,
        on peripheral: CBPeripheral
    ) async throws {
        handlers.removeValue(forKey: characteristic.uuid.uuidString)

        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            self.unsubscribeContinuation = cont
            peripheral.setNotifyValue(false, for: characteristic)
        }

        logger.info("Unsubscribed from \(characteristic.uuid)")
    }

    /// Number of active subscriptions.
    public var activeSubscriptionCount: Int { handlers.count }
}

// MARK: - CBPeripheralDelegate

extension BLENotificationSubscriber: CBPeripheralDelegate {

    /// Called when notification state changes (subscribe/unsubscribe confirmed).
    public func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        if let error {
            subscribeContinuation?.resume(throwing: error)
            subscribeContinuation = nil
            unsubscribeContinuation?.resume(throwing: error)
            unsubscribeContinuation = nil
            return
        }

        if characteristic.isNotifying {
            subscribeContinuation?.resume()
            subscribeContinuation = nil
        } else {
            unsubscribeContinuation?.resume()
            unsubscribeContinuation = nil
        }
    }

    /// Called for each notification/indication value update.
    public func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        guard error == nil, let value = characteristic.value else { return }

        let key = characteristic.uuid.uuidString
        handlers[key]?(value)
    }
}
