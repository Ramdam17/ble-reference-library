// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 02 — Filtered Scan
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 20
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Filter BLE scan results by name, RSSI threshold,
//           or service UUIDs. Builds on Sprint 19 (Passive Scan).
//
// Usage   : let filtered = ScanFilter.byName("Polar").apply(to: devices)
//
// Depends : Sprint 19 (Passive Scan)
// ─────────────────────────────────────────────────────

// MARK: - Overview
//
// CoreBluetooth supports two levels of filtering:
//
// 1. **Service UUID filter** (hardware-level):
//    Pass service UUIDs to `scanForPeripherals(withServices:)`.
//    The Bluetooth stack filters at the radio level — more efficient
//    but limited to advertised service UUIDs only.
//
// 2. **Software filter** (application-level):
//    Post-process `didDiscover` results by name, RSSI, manufacturer
//    data, etc. More flexible but processes all advertisements.
//
// This module provides composable software filters that work on
// `[DiscoveredDevice]` arrays from Sprint 19.
//
// Key difference from Python/Bleak:
// - Bleak's `BleakScanner(service_uuids=...)` is equivalent to
//   CoreBluetooth's `scanForPeripherals(withServices:)`.
// - Both platforms need software filtering for name/RSSI criteria.

@preconcurrency import CoreBluetooth
import Foundation

// MARK: - ScanFilter

/// A composable filter for BLE scan results.
///
/// Filters can be combined using `combine(_:)` to create complex
/// criteria. All filters operate on `[DiscoveredDevice]` arrays.
///
/// ## Example
/// ```swift
/// let filter = ScanFilter.byName("Polar")
///     .combine(.byMinRSSI(-70))
///
/// let results = filter.apply(to: allDevices)
/// ```
public struct ScanFilter: Sendable {

    /// The filter predicate.
    private let predicate: @Sendable (DiscoveredDevice) -> Bool

    /// Human-readable description of the filter.
    public let description: String

    /// Creates a custom filter with the given predicate.
    ///
    /// - Parameters:
    ///   - description: Human-readable label for logging.
    ///   - predicate: Closure that returns `true` for devices to keep.
    public init(
        description: String,
        predicate: @escaping @Sendable (DiscoveredDevice) -> Bool
    ) {
        self.description = description
        self.predicate = predicate
    }

    /// Apply this filter to an array of discovered devices.
    ///
    /// - Parameter devices: All discovered devices.
    /// - Returns: Devices matching the filter criteria.
    public func apply(to devices: [DiscoveredDevice]) -> [DiscoveredDevice] {
        devices.filter(predicate)
    }

    /// Combine this filter with another (logical AND).
    ///
    /// - Parameter other: Additional filter to apply.
    /// - Returns: A new filter that requires both conditions.
    public func combine(_ other: ScanFilter) -> ScanFilter {
        let p1 = self.predicate
        let p2 = other.predicate
        return ScanFilter(
            description: "\(self.description) AND \(other.description)"
        ) { device in
            p1(device) && p2(device)
        }
    }
}

// MARK: - Built-in Filters

extension ScanFilter {

    /// Filter by device name (case-insensitive substring match).
    ///
    /// - Parameter substring: Text to search for in the device name.
    /// - Returns: Filter matching devices whose name contains `substring`.
    ///
    /// Devices without a name are excluded.
    public static func byName(_ substring: String) -> ScanFilter {
        let lower = substring.lowercased()
        return ScanFilter(description: "name contains '\(substring)'") { device in
            guard let name = device.name else { return false }
            return name.lowercased().contains(lower)
        }
    }

    /// Filter by exact device name (case-sensitive).
    ///
    /// - Parameter name: Exact name to match.
    /// - Returns: Filter matching devices with this exact name.
    public static func byExactName(_ name: String) -> ScanFilter {
        ScanFilter(description: "name == '\(name)'") { device in
            device.name == name
        }
    }

    /// Filter by minimum RSSI threshold.
    ///
    /// - Parameter minRSSI: Minimum RSSI in dBm (e.g., -70).
    ///   Devices with weaker signals are excluded.
    /// - Returns: Filter matching devices with RSSI >= `minRSSI`.
    ///
    /// Useful for proximity-based filtering: only show nearby devices.
    public static func byMinRSSI(_ minRSSI: Int) -> ScanFilter {
        ScanFilter(description: "RSSI >= \(minRSSI)") { device in
            device.rssi >= minRSSI
        }
    }

    /// Filter by advertised service UUID.
    ///
    /// - Parameter uuid: Service UUID to look for in advertisement data.
    /// - Returns: Filter matching devices advertising this service.
    ///
    /// - Note: For better performance, prefer passing service UUIDs
    ///   directly to `BLEScanner.scan(serviceUUIDs:)` — this filters
    ///   at the Bluetooth stack level rather than in software.
    public static func byServiceUUID(_ uuid: CBUUID) -> ScanFilter {
        ScanFilter(description: "advertises service \(uuid.uuidString)") { device in
            guard let serviceUUIDs = device.advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] else {
                return false
            }
            return serviceUUIDs.contains(uuid)
        }
    }

    /// Filter that accepts all devices (passthrough).
    public static var all: ScanFilter {
        ScanFilter(description: "all") { _ in true }
    }
}

// MARK: - Sorting

/// Sort criteria for scan results.
public enum ScanSortOrder: Sendable {
    /// Sort by RSSI (strongest signal first).
    case byRSSI
    /// Sort by name alphabetically (nil names last).
    case byName
    /// Sort by discovery time (most recent first).
    case byDiscoveryTime
}

extension Array where Element == DiscoveredDevice {

    /// Sort discovered devices by the given criteria.
    ///
    /// - Parameter order: Sort criteria.
    /// - Returns: Sorted array.
    public func sorted(by order: ScanSortOrder) -> [DiscoveredDevice] {
        switch order {
        case .byRSSI:
            return sorted { $0.rssi > $1.rssi }
        case .byName:
            return sorted { ($0.name ?? "zzz") < ($1.name ?? "zzz") }
        case .byDiscoveryTime:
            return sorted { $0.discoveredAt > $1.discoveredAt }
        }
    }
}
