// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 04 — Services & Characteristics Discovery
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 22
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Discover GATT services and their characteristics
//           on a connected peripheral.
//
// Usage   : let services = try await discoverer.discoverServices(on: peripheral)
//
// Depends : Sprint 21 (Connection)
// ─────────────────────────────────────────────────────

// MARK: - Overview
//
// CoreBluetooth service discovery is a **two-step process**:
//
// 1. `peripheral.discoverServices([...])` → triggers
//    `didDiscoverServices` delegate callback.
// 2. For each service, `peripheral.discoverCharacteristics(nil, for: service)`
//    → triggers `didDiscoverCharacteristicsFor` callback.
//
// This is different from Python/Bleak where `client.services` returns
// the full tree in one call. CoreBluetooth requires chaining the two
// discoveries through delegate callbacks.
//
// Reference:
// Apple Developer Documentation — CBPeripheral.discoverServices(_:)

@preconcurrency import CoreBluetooth
import Foundation
import os.log

// MARK: - Data Structures

/// A discovered GATT service with its characteristics.
///
/// Provides a clean value-type view of CoreBluetooth's `CBService`
/// and `CBCharacteristic` for easier consumption.
public struct DiscoveredService: Identifiable, Sendable {
    /// Service UUID.
    public let id: CBUUID
    /// Whether this is a primary or secondary service.
    public let isPrimary: Bool
    /// Discovered characteristics within this service.
    public let characteristics: [DiscoveredCharacteristic]
}

/// A discovered GATT characteristic.
public struct DiscoveredCharacteristic: Identifiable, Sendable {
    /// Characteristic UUID.
    public let id: CBUUID
    /// Supported properties (read, write, notify, etc.).
    public let properties: CBCharacteristicProperties

    /// Whether this characteristic supports reading.
    public var isReadable: Bool { properties.contains(.read) }
    /// Whether this characteristic supports writing (with response).
    public var isWritable: Bool { properties.contains(.write) }
    /// Whether this characteristic supports write-without-response.
    public var isWritableWithoutResponse: Bool { properties.contains(.writeWithoutResponse) }
    /// Whether this characteristic supports notifications.
    public var isNotifiable: Bool { properties.contains(.notify) }
    /// Whether this characteristic supports indications.
    public var isIndicatable: Bool { properties.contains(.indicate) }
}

// MARK: - BLEServiceDiscoverer

/// Discovers GATT services and characteristics on a connected peripheral.
///
/// Bridges CoreBluetooth's two-step delegate-based discovery chain
/// to Swift async/await.
///
/// ## Example
/// ```swift
/// let discoverer = BLEServiceDiscoverer()
/// let services = try await discoverer.discoverAll(on: peripheral)
/// for service in services {
///     print("Service: \(service.id)")
///     for char in service.characteristics {
///         print("  Char: \(char.id) — \(char.properties.rawValue)")
///     }
/// }
/// ```
public final class BLEServiceDiscoverer: NSObject {

    private let logger = Logger(
        subsystem: "com.ble-reference",
        category: "BLEDiscovery"
    )

    /// The peripheral being discovered.
    private var peripheral: CBPeripheral?

    /// Services pending characteristic discovery.
    private var pendingServices: [CBService] = []

    /// Accumulated results.
    private var discoveredServices: [DiscoveredService] = []

    /// Continuation for the full discovery chain.
    private var continuation: CheckedContinuation<[DiscoveredService], Error>?

    // MARK: - Public API

    /// Discover all services and their characteristics.
    ///
    /// - Parameters:
    ///   - peripheral: A connected CBPeripheral.
    ///   - serviceUUIDs: Optional filter — discover only these services.
    ///     Pass `nil` to discover all services.
    ///
    /// - Returns: Array of discovered services with characteristics.
    ///
    /// - Throws: Error if discovery fails (e.g., peripheral disconnected).
    ///
    /// - Note: This performs the two-step discovery chain automatically:
    ///   services first, then characteristics for each service.
    public func discoverAll(
        on peripheral: CBPeripheral,
        serviceUUIDs: [CBUUID]? = nil
    ) async throws -> [DiscoveredService] {
        self.peripheral = peripheral

        return try await withCheckedThrowingContinuation { cont in
            self.continuation = cont
            self.discoveredServices = []
            self.pendingServices = []

            peripheral.delegate = self

            self.logger.info("Discovering services...")
            peripheral.discoverServices(serviceUUIDs)
        }
    }
}

// MARK: - CBPeripheralDelegate

extension BLEServiceDiscoverer: CBPeripheralDelegate {

    /// Step 1 complete: services discovered.
    public func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverServices error: Error?
    ) {
        if let error {
            logger.error("Service discovery failed: \(error.localizedDescription)")
            continuation?.resume(throwing: error)
            continuation = nil
            return
        }

        guard let services = peripheral.services, !services.isEmpty else {
            logger.info("No services found")
            continuation?.resume(returning: [])
            continuation = nil
            return
        }

        logger.info("Found \(services.count) service(s) — discovering characteristics...")

        // Queue all services for characteristic discovery
        pendingServices = services
        for service in services {
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }

    /// Step 2 complete: characteristics discovered for one service.
    public func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        // Build the discovered service model
        let chars = (service.characteristics ?? []).map { char in
            DiscoveredCharacteristic(
                id: char.uuid,
                properties: char.properties
            )
        }

        let discovered = DiscoveredService(
            id: service.uuid,
            isPrimary: service.isPrimary,
            characteristics: chars
        )
        discoveredServices.append(discovered)

        logger.info(
            "Service \(service.uuid): \(chars.count) characteristic(s)"
        )

        // Remove from pending
        pendingServices.removeAll { $0.uuid == service.uuid }

        // All done?
        if pendingServices.isEmpty {
            logger.info("Discovery complete: \(self.discoveredServices.count) service(s)")
            continuation?.resume(returning: discoveredServices)
            continuation = nil
        }
    }
}
