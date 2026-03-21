// ─────────────────────────────────────────────────────
// BLE Reference Library
// Module  : 03 — Connection Lifecycle
// Platform: Swift / CoreBluetooth
// Sprint  : Sprint 21
// Phase   : Phase 02 — Swift / CoreBluetooth (Apple)
//
// Purpose : Connect to, disconnect from, and monitor the connection
//           state of a BLE peripheral using CoreBluetooth.
//
// Usage   : let connector = BLEConnector()
//           try await connector.connect(to: peripheral, timeout: 10)
//
// Depends : Sprint 19 (Passive Scan)
// ─────────────────────────────────────────────────────

// MARK: - Overview
//
// CoreBluetooth connection lifecycle:
//
// 1. Get a CBPeripheral from scanning (Sprint 19).
// 2. Call `centralManager.connect(peripheral, options:)`.
// 3. Wait for `didConnect` delegate callback.
// 4. Discover services and characteristics (Sprint 22).
// 5. When done, call `centralManager.cancelPeripheralConnection()`.
// 6. Receive `didDisconnectPeripheral` callback.
//
// Key differences from Python/Bleak:
// - Bleak: `async with BleakClient(address) as client:` — context manager.
// - CoreBluetooth: Delegate-based connect/disconnect, manual lifecycle.
// - CoreBluetooth does NOT support connection timeouts natively —
//   you must implement your own timer.
//
// Important gotcha:
// - You MUST retain a strong reference to the CBPeripheral.
//   If the peripheral is deallocated, the connection silently fails.
// - CoreBluetooth may auto-reconnect after unexpected disconnection
//   unless you explicitly cancel. Use `_intentionalDisconnect` flag.
//
// Reference:
// Apple Developer Documentation — CBCentralManager.connect(_:options:)

@preconcurrency import CoreBluetooth
import Foundation
import os.log

// MARK: - Connection State

/// Connection state for a BLE peripheral.
///
/// Maps to CoreBluetooth's `CBPeripheralState` with additional
/// states for the connection lifecycle.
public enum BLEConnectionState: String, Sendable {
    case disconnected
    case connecting
    case connected
    case disconnecting

    /// Initialize from CoreBluetooth's peripheral state.
    public init(from state: CBPeripheralState) {
        switch state {
        case .disconnected: self = .disconnected
        case .connecting:   self = .connecting
        case .connected:    self = .connected
        case .disconnecting: self = .disconnecting
        @unknown default:   self = .disconnected
        }
    }
}

// MARK: - Connection Errors

/// Errors specific to BLE connection operations.
public enum BLEConnectionError: LocalizedError, Sendable {
    /// Connection attempt timed out.
    case timeout(seconds: TimeInterval)

    /// Connection was rejected or failed.
    case connectionFailed(reason: String)

    /// Peripheral disconnected unexpectedly.
    case unexpectedDisconnection(reason: String)

    /// Attempted operation on a disconnected peripheral.
    case notConnected

    public var errorDescription: String? {
        switch self {
        case .timeout(let seconds):
            return "Connection timed out after \(seconds)s"
        case .connectionFailed(let reason):
            return "Connection failed: \(reason)"
        case .unexpectedDisconnection(let reason):
            return "Unexpected disconnection: \(reason)"
        case .notConnected:
            return "Peripheral is not connected"
        }
    }
}

// MARK: - BLEConnector

/// Manages the connection lifecycle for BLE peripherals.
///
/// Provides async/await wrappers around CoreBluetooth's delegate-based
/// connection API, with configurable timeout and disconnect handling.
///
/// ## Example
/// ```swift
/// let connector = BLEConnector()
/// try await connector.connect(to: peripheral, timeout: 10)
/// // ... use peripheral ...
/// await connector.disconnect()
/// ```
///
/// ## Intentional vs Unexpected Disconnection
/// CoreBluetooth calls `didDisconnectPeripheral` for both intentional
/// disconnects (you called `cancelPeripheralConnection`) and unexpected
/// ones (device went out of range, battery died, etc.).
///
/// This class uses an `_intentionalDisconnect` flag to distinguish
/// between the two. When disconnection is unexpected, it can optionally
/// notify via a callback for reconnection logic.
public final class BLEConnector: NSObject {

    // MARK: - Properties

    private let centralManager: CBCentralManager
    private let queue: DispatchQueue
    private let logger = Logger(
        subsystem: "com.ble-reference",
        category: "BLEConnection"
    )

    /// The currently connected peripheral (retained to prevent dealloc).
    private(set) var connectedPeripheral: CBPeripheral?

    /// Current connection state.
    public private(set) var state: BLEConnectionState = .disconnected

    /// Flag to distinguish intentional vs unexpected disconnection.
    private var _intentionalDisconnect = false

    /// Continuation for connect operation.
    private var connectContinuation: CheckedContinuation<Void, Error>?

    /// Continuation for disconnect operation.
    private var disconnectContinuation: CheckedContinuation<Void, Never>?

    /// Continuation for waiting until Bluetooth is powered on.
    private var powerOnContinuation: CheckedContinuation<Void, Error>?

    /// Whether Bluetooth is currently powered on.
    private var isPoweredOn = false

    /// Optional callback for unexpected disconnections.
    ///
    /// Set this to implement auto-reconnection or user notification.
    public var onUnexpectedDisconnection: ((CBPeripheral, Error?) -> Void)?

    // MARK: - Init

    /// Creates a new BLE connector with its own CBCentralManager.
    ///
    /// - Parameter queue: Dispatch queue for CoreBluetooth callbacks.
    public init(queue: DispatchQueue = DispatchQueue(label: "com.ble-reference.connector")) {
        self.queue = queue
        self.centralManager = CBCentralManager(delegate: nil, queue: queue)
        super.init()
        self.centralManager.delegate = self
    }

    /// Creates a BLE connector reusing an existing CBCentralManager.
    ///
    /// Use this when you need to connect to a peripheral discovered
    /// by a BLEScanner — CoreBluetooth requires the same
    /// CBCentralManager that discovered a peripheral to connect to it.
    ///
    /// - Parameters:
    ///   - centralManager: The CBCentralManager from the scanner.
    ///   - queue: Dispatch queue (should match the central manager's queue).
    public init(centralManager: CBCentralManager, queue: DispatchQueue = DispatchQueue(label: "com.ble-reference.connector")) {
        self.queue = queue
        self.centralManager = centralManager
        // Sync power state — the CM is already initialized so
        // centralManagerDidUpdateState won't fire again for .poweredOn.
        self.isPoweredOn = centralManager.state == .poweredOn
        super.init()
        self.centralManager.delegate = self
    }

    // MARK: - Public API

    /// Connect to a BLE peripheral.
    ///
    /// - Parameters:
    ///   - peripheral: The peripheral to connect to (from scan results).
    ///   - timeout: Connection timeout in seconds (default: 10).
    ///     CoreBluetooth does NOT support native timeouts — this is
    ///     implemented via a manual timer.
    ///
    /// - Throws: `BLEConnectionError.timeout` if connection takes too long.
    /// - Throws: `BLEConnectionError.connectionFailed` if the OS rejects.
    ///
    /// - Important: You must retain the `BLEConnector` instance for
    ///   the duration of the connection. If it is deallocated, the
    ///   peripheral will silently disconnect.
    public func connect(
        to peripheral: CBPeripheral,
        timeout: TimeInterval = 10.0
    ) async throws {
        // Wait for Bluetooth to be ready
        try await waitForPowerOn()

        state = .connecting
        _intentionalDisconnect = false
        connectedPeripheral = peripheral  // Retain!

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            self.queue.async {
                self.connectContinuation = continuation

                self.logger.info("Connecting to \(peripheral.identifier)...")
                self.centralManager.connect(peripheral, options: nil)

                // Timeout timer — CoreBluetooth doesn't have one
                self.queue.asyncAfter(deadline: .now() + timeout) {
                    if self.state == .connecting {
                        self.centralManager.cancelPeripheralConnection(peripheral)
                        self.state = .disconnected
                        self.connectContinuation?.resume(
                            throwing: BLEConnectionError.timeout(seconds: timeout)
                        )
                        self.connectContinuation = nil
                    }
                }
            }
        }
    }

    /// Disconnect from the currently connected peripheral.
    ///
    /// This is an intentional disconnection — the `onUnexpectedDisconnection`
    /// callback will NOT fire.
    public func disconnect() async {
        guard let peripheral = connectedPeripheral else { return }

        _intentionalDisconnect = true
        state = .disconnecting

        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            self.queue.async {
                self.disconnectContinuation = continuation
                self.logger.info("Disconnecting from \(peripheral.identifier)...")
                self.centralManager.cancelPeripheralConnection(peripheral)
            }
        }
    }

    // MARK: - Private

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

extension BLEConnector: CBCentralManagerDelegate {

    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        logger.info("Bluetooth state: \(String(describing: central.state.rawValue))")

        switch central.state {
        case .poweredOn:
            isPoweredOn = true
            powerOnContinuation?.resume()
            powerOnContinuation = nil

        case .poweredOff, .unauthorized, .unsupported:
            isPoweredOn = false
            let reason = switch central.state {
            case .poweredOff: "Bluetooth is powered off"
            case .unauthorized: "Bluetooth permission denied"
            case .unsupported: "BLE not supported"
            default: "Unknown"
            }
            powerOnContinuation?.resume(
                throwing: BLEScanError.bluetoothUnavailable(reason: reason)
            )
            powerOnContinuation = nil

        default:
            break
        }
    }

    /// Called when a connection succeeds.
    public func centralManager(
        _ central: CBCentralManager,
        didConnect peripheral: CBPeripheral
    ) {
        state = .connected
        logger.info("Connected to \(peripheral.identifier)")

        connectContinuation?.resume()
        connectContinuation = nil
    }

    /// Called when a connection attempt fails.
    public func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        state = .disconnected
        connectedPeripheral = nil

        let reason = error?.localizedDescription ?? "Unknown reason"
        logger.error("Failed to connect: \(reason)")

        connectContinuation?.resume(
            throwing: BLEConnectionError.connectionFailed(reason: reason)
        )
        connectContinuation = nil
    }

    /// Called when a peripheral disconnects (intentional or unexpected).
    ///
    /// This is where the `_intentionalDisconnect` flag matters:
    /// - If `true`: This was expected (you called `disconnect()`).
    /// - If `false`: The device disconnected on its own — notify
    ///   the caller via `onUnexpectedDisconnection`.
    public func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        state = .disconnected

        if _intentionalDisconnect {
            logger.info("Disconnected (intentional) from \(peripheral.identifier)")
            disconnectContinuation?.resume()
            disconnectContinuation = nil
        } else {
            let reason = error?.localizedDescription ?? "Unknown"
            logger.warning("Disconnected (unexpected): \(reason)")
            onUnexpectedDisconnection?(peripheral, error)
        }

        connectedPeripheral = nil
        _intentionalDisconnect = false
    }
}
