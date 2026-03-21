// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 01 — Passive Scan
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 19
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Discover nearby BLE peripherals using CoreBluetooth.
//           Wraps CBCentralManager scanning with a clean async API.
//
// Usage   : let scanner = BLEScanner()
//           let devices = try await scanner.scan(duration: 5.0)
//
// Depends : None
// ─────────────────────────────────────────────────────

// MARK: - Overview
//
// CoreBluetooth scanning follows a strict lifecycle:
//
// 1. Create CBCentralManager → triggers delegate callback.
// 2. Wait for `centralManagerDidUpdateState` → `.poweredOn`.
// 3. Call `scanForPeripherals(withServices:options:)`.
// 4. Receive `didDiscover` callbacks for each found device.
// 5. Call `stopScan()` when done.
//
// Key difference from Python/Bleak:
// - Bleak uses async/await natively.
// - CoreBluetooth is delegate-based — we bridge to Swift concurrency
//   using `CheckedContinuation` (Swift 5.5+).
//
// Reference:
// Apple Developer Documentation — Core Bluetooth Programming Guide.
// https://developer.apple.com/documentation/corebluetooth

@preconcurrency import CoreBluetooth
import Foundation
import os.log

// MARK: - Data Structures

/// A discovered BLE peripheral with its advertisement metadata.
///
/// Wraps the raw CoreBluetooth discovery callback parameters into
/// a clean value type. Each discovery event produces one instance.
///
/// - Note: On Apple platforms, `peripheral.identifier` is a **UUID
///   assigned by the local device**, not the peripheral's MAC address.
///   This UUID is stable per-device-per-phone but changes across
///   different Apple devices scanning the same peripheral.
public struct DiscoveredDevice: Identifiable, Sendable {
    /// Stable local identifier (CBPeripheral.identifier).
    public let id: UUID

    /// Advertised local name, if present.
    ///
    /// Many devices advertise a name (e.g., "Polar H10 A1B2C3").
    /// Some don't — this will be `nil` for silent advertisers.
    public let name: String?

    /// Received Signal Strength Indicator in dBm.
    ///
    /// Typical range: -30 (very close) to -100 (far away).
    /// See Sprint 34 (RSSI & Distance) for distance estimation.
    public let rssi: Int

    /// Raw advertisement data dictionary.
    ///
    /// Keys are `CBAdvertisementData*` constants. Common entries:
    /// - `CBAdvertisementDataLocalNameKey`: device name
    /// - `CBAdvertisementDataServiceUUIDsKey`: advertised service UUIDs
    /// - `CBAdvertisementDataManufacturerDataKey`: vendor-specific data
    /// - `CBAdvertisementDataTxPowerLevelKey`: TX power (for distance)
    public let advertisementData: [String: Any]

    /// Timestamp of discovery.
    public let discoveredAt: Date

    /// The underlying CBPeripheral (needed for connection).
    ///
    /// - Important: You must retain a strong reference to this
    ///   peripheral if you intend to connect. CoreBluetooth does
    ///   not retain discovered peripherals for you.
    ///
    /// This is `nil` only in unit tests where no real Bluetooth
    /// stack is available. In production code, it is always set.
    public let peripheral: CBPeripheral?
}

// MARK: - BLEScanner

/// Scans for nearby BLE peripherals using CoreBluetooth.
///
/// Bridges CoreBluetooth's delegate-based API to Swift's structured
/// concurrency, providing a simple `scan(duration:)` async method.
///
/// ## Example
/// ```swift
/// let scanner = BLEScanner()
/// let devices = try await scanner.scan(duration: 5.0)
/// for device in devices {
///     print("\(device.name ?? "Unknown") — RSSI: \(device.rssi)")
/// }
/// ```
///
/// ## Threading
/// CoreBluetooth callbacks arrive on the queue specified at init.
/// This class uses a dedicated serial queue to avoid blocking the
/// main thread during scanning.
///
/// ## Important
/// On iOS, the app must include `NSBluetoothAlwaysUsageDescription`
/// in Info.plist. Without it, CoreBluetooth silently fails.
public final class BLEScanner: NSObject {

    // MARK: - Properties

    private let centralManager: CBCentralManager
    private let queue: DispatchQueue
    private let logger = Logger(
        subsystem: "com.ble-reference",
        category: "BLEScan"
    )

    /// All devices discovered during the current scan.
    private var discovered: [UUID: DiscoveredDevice] = [:]

    /// Continuation for bridging delegate → async.
    private var scanContinuation: CheckedContinuation<[DiscoveredDevice], Error>?

    /// Continuation for waiting until Bluetooth is powered on.
    private var powerOnContinuation: CheckedContinuation<Void, Error>?

    /// Whether Bluetooth is currently powered on.
    private var isPoweredOn = false

    // MARK: - Init

    /// Creates a new BLE scanner.
    ///
    /// - Parameter queue: Dispatch queue for CoreBluetooth callbacks.
    ///   Defaults to a dedicated serial queue. Never use `.main` for
    ///   BLE operations — delegate callbacks can block the UI.
    public init(queue: DispatchQueue = DispatchQueue(label: "com.ble-reference.scanner")) {
        self.queue = queue
        self.centralManager = CBCentralManager(delegate: nil, queue: queue)
        super.init()
        self.centralManager.delegate = self
    }

    // MARK: - Public API

    /// Scan for nearby BLE peripherals.
    ///
    /// Waits for Bluetooth to be powered on, then scans for the
    /// specified duration and returns all discovered devices.
    ///
    /// - Parameters:
    ///   - duration: Scan duration in seconds (default: 5.0).
    ///   - serviceUUIDs: Optional filter — only discover peripherals
    ///     advertising these service UUIDs. Pass `nil` to discover all.
    ///   - allowDuplicates: If `true`, the same device triggers multiple
    ///     `didDiscover` callbacks (useful for RSSI tracking). Default: `false`.
    ///
    /// - Returns: Array of discovered devices, deduplicated by identifier.
    ///
    /// - Throws: `BLEScanError.bluetoothUnavailable` if Bluetooth is off
    ///   or unauthorized.
    ///
    /// - Note: On iOS, background scanning requires the
    ///   `bluetooth-central` background mode capability.
    public func scan(
        duration: TimeInterval = 5.0,
        serviceUUIDs: [CBUUID]? = nil,
        allowDuplicates: Bool = false
    ) async throws -> [DiscoveredDevice] {
        // Step 1: Wait for Bluetooth to be ready
        try await waitForPowerOn()

        // Step 2: Scan for the specified duration
        let devices = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<[DiscoveredDevice], Error>) in
            self.queue.async {
                self.discovered.removeAll()
                self.scanContinuation = continuation

                let options: [String: Any] = [
                    CBCentralManagerScanOptionAllowDuplicatesKey: allowDuplicates
                ]

                self.logger.info("Starting scan for \(duration)s")
                self.centralManager.scanForPeripherals(
                    withServices: serviceUUIDs,
                    options: options
                )

                // Stop after duration
                self.queue.asyncAfter(deadline: .now() + duration) {
                    self.stopScan()
                }
            }
        }

        return devices
    }

    /// Stop an ongoing scan and return results immediately.
    public func stopScan() {
        guard centralManager.isScanning else { return }

        centralManager.stopScan()
        logger.info("Scan stopped — \(self.discovered.count) device(s) found")

        let results = Array(discovered.values)
        scanContinuation?.resume(returning: results)
        scanContinuation = nil
    }

    // MARK: - Private

    /// Wait until CBCentralManager reports `.poweredOn`.
    ///
    /// CoreBluetooth requires Bluetooth to be fully initialized
    /// before any operation. This method bridges the delegate
    /// callback to async/await.
    private func waitForPowerOn() async throws {
        if isPoweredOn { return }

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            self.queue.async {
                if self.isPoweredOn {
                    continuation.resume()
                } else {
                    self.powerOnContinuation = continuation
                }
            }
        }
    }
}

// MARK: - CBCentralManagerDelegate

extension BLEScanner: CBCentralManagerDelegate {

    /// Called when the Bluetooth state changes.
    ///
    /// This is the **first** delegate method called after creating
    /// a CBCentralManager. You must wait for `.poweredOn` before
    /// calling any scanning or connection methods.
    ///
    /// Possible states:
    /// - `.poweredOn`: Ready to scan/connect.
    /// - `.poweredOff`: Bluetooth is disabled in Settings.
    /// - `.unauthorized`: App lacks Bluetooth permission.
    /// - `.unsupported`: Device has no BLE hardware.
    /// - `.resetting`: Bluetooth stack is restarting.
    /// - `.unknown`: State not yet determined (transient).
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        logger.info("Bluetooth state: \(String(describing: central.state.rawValue))")

        switch central.state {
        case .poweredOn:
            isPoweredOn = true
            powerOnContinuation?.resume()
            powerOnContinuation = nil

        case .poweredOff:
            isPoweredOn = false
            powerOnContinuation?.resume(throwing: BLEScanError.bluetoothUnavailable(
                reason: "Bluetooth is powered off"
            ))
            powerOnContinuation = nil

        case .unauthorized:
            isPoweredOn = false
            powerOnContinuation?.resume(throwing: BLEScanError.bluetoothUnavailable(
                reason: "Bluetooth permission denied — add NSBluetoothAlwaysUsageDescription to Info.plist"
            ))
            powerOnContinuation = nil

        case .unsupported:
            isPoweredOn = false
            powerOnContinuation?.resume(throwing: BLEScanError.bluetoothUnavailable(
                reason: "BLE not supported on this device"
            ))
            powerOnContinuation = nil

        default:
            // .resetting, .unknown — wait for next state update
            break
        }
    }

    /// Called for each discovered peripheral.
    ///
    /// CoreBluetooth calls this method once per peripheral (unless
    /// `allowDuplicates` is enabled, in which case it fires on
    /// every advertisement packet — useful for RSSI tracking).
    public func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let device = DiscoveredDevice(
            id: peripheral.identifier,
            name: peripheral.name ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String,
            rssi: RSSI.intValue,
            advertisementData: advertisementData,
            discoveredAt: Date(),
            peripheral: peripheral
        )

        discovered[device.id] = device

        logger.debug(
            "Discovered: \(device.name ?? "Unknown") RSSI=\(device.rssi)"
        )
    }
}

// MARK: - Errors

/// Errors specific to BLE scanning operations.
public enum BLEScanError: LocalizedError {
    /// Bluetooth is not available (off, unauthorized, or unsupported).
    case bluetoothUnavailable(reason: String)

    public var errorDescription: String? {
        switch self {
        case .bluetoothUnavailable(let reason):
            return "Bluetooth unavailable: \(reason)"
        }
    }
}
