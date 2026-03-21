// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 07 — Scan Without Connection (Advertisement Data)
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 25
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Extract and parse advertisement data from BLE scans
//           without connecting to the peripheral.
//
// Usage   : let parsed = AdvertisementParser.parse(advertisementData)
//
// Depends : Sprint 19 (Passive Scan)
// ─────────────────────────────────────────────────────

@preconcurrency import CoreBluetooth
import Foundation

// MARK: - Parsed Advertisement

/// Parsed BLE advertisement data.
///
/// Extracts the useful fields from CoreBluetooth's raw
/// `[String: Any]` advertisement dictionary into typed properties.
public struct ParsedAdvertisement: Sendable {
    /// Advertised local name.
    public let localName: String?

    /// TX power level in dBm (for distance estimation).
    public let txPowerLevel: Int?

    /// Advertised service UUIDs.
    public let serviceUUIDs: [CBUUID]

    /// Whether the device is connectable.
    public let isConnectable: Bool

    /// Manufacturer-specific data (company ID + payload).
    public let manufacturerData: Data?

    /// Service-specific data keyed by service UUID.
    public let serviceData: [CBUUID: Data]
}

// MARK: - Advertisement Parser

/// Parses CoreBluetooth advertisement data dictionaries.
///
/// CoreBluetooth provides advertisement data as `[String: Any]` with
/// `CBAdvertisementData*` constant keys. This parser extracts and
/// types each field for clean consumption.
public enum AdvertisementParser {

    /// Parse a raw advertisement data dictionary.
    ///
    /// - Parameter advertisementData: The dictionary from
    ///   `centralManager(_:didDiscover:advertisementData:rssi:)`.
    /// - Returns: A typed `ParsedAdvertisement`.
    public static func parse(
        _ advertisementData: [String: Any]
    ) -> ParsedAdvertisement {
        ParsedAdvertisement(
            localName: advertisementData[CBAdvertisementDataLocalNameKey] as? String,
            txPowerLevel: (advertisementData[CBAdvertisementDataTxPowerLevelKey] as? NSNumber)?.intValue,
            serviceUUIDs: advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? [],
            isConnectable: advertisementData[CBAdvertisementDataIsConnectable] as? Bool ?? false,
            manufacturerData: advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
            serviceData: advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data] ?? [:]
        )
    }
}
