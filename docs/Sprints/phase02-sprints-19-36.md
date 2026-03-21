# Phase 02 — Swift / CoreBluetooth (Apple — iOS · macOS · iPadOS)
## Sprints 18–34

> **Stack:** Swift 5.9+, CoreBluetooth framework (no third-party BLE dependency)
> **Xcode:** 15+
> **Targets:** Shared code in `09_xcode_apple/Shared/`, platform-specific config in `iOS/`, `macOS/`, `iPadOS/`
> **Role:** Native Apple implementation. Shared Swift code covers 95% of logic; sub-folders contain only entitlements, Info.plist, and background mode config.
> **Git base branch:** `develop`

---

## Global Git Rules

| Rule | Value |
|------|-------|
| Base branch | `develop` |
| Feature branch naming | `feature/phase02-sprint<NN>-<slug>` |
| Commit convention | `feat(ble-swift): <description>` |
| PR target | `develop` |
| Merge strategy | Squash and merge |
| Tag on phase completion | `phase02-complete` |

---

## Platform Configuration Reference

| Config item | iOS | macOS | iPadOS |
|-------------|-----|-------|--------|
| Entitlement | `com.apple.developer.bluetooth` (not required, implicit) | `com.apple.security.device.bluetooth` | same as iOS |
| Info.plist key | `NSBluetoothAlwaysUsageDescription` | `NSBluetoothAlwaysUsageDescription` | same as iOS |
| Background mode | `bluetooth-central` (optional) | not applicable | same as iOS |
| Min OS | iOS 13+ | macOS 11+ | iPadOS 13+ |
| Address format | UUID string (no MAC) | UUID string (no MAC) | UUID string (no MAC) |

> All 17 sprint deliverables live in `Shared/`. The platform sub-folders each contain only a `README.md` explaining their specific configuration differences.

---

## Sprint 18 — Passive Scan

### Metadata
- **File:** `09_xcode_apple/Shared/01_BLEScan.swift`
- **Phase 01 reference:** `01_python_bleak/01_scan.py`
- **Dependencies:** none
- **Estimated complexity:** low-medium

### Objective
Implement a CBCentralManager-based BLE scanner that discovers all advertising peripherals and returns their UUID, name, and RSSI.

### Deliverables
| File | Description |
|------|-------------|
| `01_BLEScan.swift` | BLEScanManager class |
| `01_BLEScanTests.swift` | Unit tests with mock CBCentralManager |

### Expected API
```swift
public class BLEScanManager: NSObject, CBCentralManagerDelegate {
    public struct DiscoveredDevice {
        public let uuid: UUID
        public let name: String?
        public let rssi: Int
    }

    public func startScan(duration: TimeInterval = 5.0, completion: @escaping ([DiscoveredDevice]) -> Void)
    public func stopScan()
}
```

### Technical Constraints
- `CBCentralManager` must be initialized with a dispatch queue
- Scan must not start until `centralManagerDidUpdateState` fires with `.poweredOn`
- Use `DispatchQueue.main` for callbacks unless caller specifies queue
- `duration` timer stops scan and calls completion automatically

### Unit Tests
```swift
test_scanReturnsDiscoveredDevices()
test_scanWithUnknownNameHandled()     // name = nil → device still in results
test_stopScanCallsCompletion()
test_scanDoesNotStartWhenPoweredOff()
```

### Risks & Mitigations
| Risk | Mitigation |
|------|-----------|
| CBCentralManager state not `.poweredOn` immediately | Buffer start request; fire when state transitions to `.poweredOn` |
| macOS Sandbox blocks BLE without entitlement | Document entitlement requirement in README |

### Acceptance Criteria
- [ ] Scan starts only when BT powered on
- [ ] Completion called after duration or stopScan
- [ ] All 4 unit tests pass (mocked CBCentralManager)

### Git Workflow
```bash
git checkout -b feature/phase02-sprint18-passive-scan
git commit -m "feat(ble-swift): passive BLE scan with CBCentralManager"
```

---

## Sprint 19 — Filtered Scan

### Metadata
- **File:** `09_xcode_apple/Shared/02_BLEScanFiltered.swift`
- **Phase 01 reference:** `01_python_bleak/02_scan_filtered.py`
- **Dependencies:** Sprint 18
- **Estimated complexity:** low-medium

### Objective
Scan with filters: service UUID (hardware-level via CoreBluetooth), device name (substring), RSSI threshold, and advertisement data extraction.

### Expected API
```swift
public extension BLEScanManager {
    func startScan(serviceUUIDs: [CBUUID]? = nil,
                   nameFilter: String? = nil,
                   minRSSI: Int? = nil,
                   duration: TimeInterval = 5.0,
                   completion: @escaping ([DiscoveredDevice]) -> Void)

    func startScanWithAdvertisementData(
        duration: TimeInterval = 5.0,
        completion: @escaping ([(CBPeripheral, [String: Any])]) -> Void)
}
```

### Technical Constraints
- `serviceUUIDs` passed directly to `scanForPeripherals(withServices:options:)`
- `nameFilter` = case-insensitive `contains` on `peripheral.name`
- `minRSSI` filter applied in `didDiscoverPeripheral` delegate callback
- Advertisement data dictionary keys: `CBAdvertisementDataLocalNameKey`, `CBAdvertisementDataServiceUUIDsKey`, `CBAdvertisementDataManufacturerDataKey`, `CBAdvertisementDataTxPowerLevelKey`

### Unit Tests
```swift
test_filterByServiceUUID()
test_filterByNameCaseInsensitive()
test_filterByRSSIThreshold()
test_advertisementDataContainsExpectedKeys()
```

### Acceptance Criteria
- [ ] All 4 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase02-sprint19-filtered-scan
git commit -m "feat(ble-swift): filtered scan by UUID, name, RSSI"
```

---

## Sprint 20 — Connection, Disconnection, Reconnection

### Metadata
- **File:** `09_xcode_apple/Shared/03_BLEConnection.swift`
- **Phase 01 reference:** `01_python_bleak/03_connection.py`
- **Dependencies:** Sprint 18
- **Estimated complexity:** medium

### Objective
Full connection lifecycle: connect by UUID, graceful disconnect, auto-reconnect with exponential backoff, and connection state callbacks.

### Expected API
```swift
public enum BLEConnectionState {
    case disconnected, connecting, connected, reconnecting, error(Error)
}

public class BLEConnectionManager: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    public var onStateChange: ((UUID, BLEConnectionState) -> Void)?

    public func connect(uuid: UUID, timeout: TimeInterval = 10.0)
    public func disconnect(uuid: UUID)
    public func connectWithRetry(uuid: UUID, maxAttempts: Int = 5, backoffBase: TimeInterval = 2.0)
}
```

### Technical Constraints
- CoreBluetooth does not expose a native timeout — implement with `DispatchWorkItem` cancel pattern
- Reconnect uses `DispatchQueue.main.asyncAfter` with `pow(backoffBase, attempt)` delay
- `centralManager(_:didDisconnectPeripheral:error:)` delegate fires on unexpected disconnect → triggers reconnect if `autoReconnect = true`
- Intentional disconnect sets `_intentionalDisconnect = true` before calling `cancelPeripheralConnection`

### Unit Tests
```swift
test_connectCallsDelegateConnected()
test_disconnectSuppressesReconnect()
test_reconnectTriesMaxAttempts()
test_timeoutTriggersDisconnect()
test_unexpectedDisconnectTriggersReconnect()
test_exponentialBackoffDelays()
```

### Acceptance Criteria
- [ ] `_intentionalDisconnect` flag prevents spurious reconnect
- [ ] All 6 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase02-sprint20-connection
git commit -m "feat(ble-swift): BLE connection lifecycle with timeout and retry"
```

---

## Sprint 21 — Services & Characteristics Discovery

### Metadata
- **File:** `09_xcode_apple/Shared/04_BLEServicesDiscovery.swift`
- **Phase 01 reference:** `01_python_bleak/04_services_characteristics.py`
- **Dependencies:** Sprint 20
- **Estimated complexity:** low-medium

### Objective
Discover all GATT services and characteristics after connection. Expose properties as a Swift struct.

### Expected API
```swift
public struct GATTCharacteristicInfo {
    public let uuid: CBUUID
    public let properties: CBCharacteristicProperties
    public var canRead: Bool { properties.contains(.read) }
    public var canWrite: Bool { properties.contains(.write) }
    public var canNotify: Bool { properties.contains(.notify) }
    public var canIndicate: Bool { properties.contains(.indicate) }
}

public extension BLEConnectionManager {
    func discoverServices(for uuid: UUID, serviceUUIDs: [CBUUID]? = nil,
                          completion: @escaping ([CBService]) -> Void)
    func discoverCharacteristics(for service: CBService,
                                 completion: @escaping ([GATTCharacteristicInfo]) -> Void)
}
```

### Technical Constraints
- Must call `discoverCharacteristics(_:for:)` in `didDiscoverServices` delegate
- Discovery is two-step: services first, then characteristics per service
- `CBCharacteristicProperties` is an `OptionSet` — use `.contains()`

### Unit Tests
```swift
test_discoverServicesCallsDelegate()
test_characteristicPropertiesCanRead()
test_characteristicPropertiesCanNotify()
test_discoverCharacteristicsFiltered()
```

### Acceptance Criteria
- [ ] Two-step discovery chain implemented correctly
- [ ] All 4 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase02-sprint21-services-discovery
git commit -m "feat(ble-swift): GATT service and characteristic discovery"
```

---

## Sprint 22 — Read & Write

### Metadata
- **File:** `09_xcode_apple/Shared/05_BLEReadWrite.swift`
- **Phase 01 reference:** `01_python_bleak/05_read_write.py`
- **Dependencies:** Sprint 21
- **Estimated complexity:** low-medium

### Expected API
```swift
public extension BLEConnectionManager {
    func readCharacteristic(_ char: CBCharacteristic,
                            completion: @escaping (Result<Data, Error>) -> Void)
    func writeCharacteristic(_ char: CBCharacteristic,
                             data: Data,
                             type: CBCharacteristicWriteType = .withResponse,
                             completion: @escaping (Result<Void, Error>) -> Void)
    func readAsInt(_ char: CBCharacteristic,
                   byteOrder: ByteOrder = .little,
                   completion: @escaping (Result<Int, Error>) -> Void)
    func readAsString(_ char: CBCharacteristic,
                      encoding: String.Encoding = .utf8,
                      completion: @escaping (Result<String, Error>) -> Void)
}

public enum ByteOrder { case little, big }
```

### Technical Constraints
- `CBCharacteristicWriteType.withResponse` = write with ACK; `.withoutResponse` = fire-and-forget
- Read result arrives in `didUpdateValueFor` delegate; use completion-keyed dictionary internally
- Validate `properties.contains(.read)` before reading; call completion with `.failure` if not

### Unit Tests
```swift
test_readReturnsData()
test_readAsIntLittleEndian()
test_readAsIntBigEndian()
test_writeWithResponse()
test_writeWithoutResponse()
test_readOnNonReadableCharFails()
```

### Acceptance Criteria
- [ ] All 6 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase02-sprint22-read-write
git commit -m "feat(ble-swift): GATT read and write with encoding helpers"
```

---

## Sprint 23 — Notifications, Indications & Subscription Management

### Metadata
- **File:** `09_xcode_apple/Shared/06_BLENotifications.swift`
- **Phase 01 reference:** `01_python_bleak/06_notifications.py`
- **Dependencies:** Sprint 22
- **Estimated complexity:** medium

### Expected API
```swift
public class BLENotificationManager {
    public func subscribe(to char: CBCharacteristic,
                          on peripheral: CBPeripheral,
                          handler: @escaping (Data) -> Void)
    public func unsubscribe(from char: CBCharacteristic, on peripheral: CBPeripheral)
    public func unsubscribeAll(on peripheral: CBPeripheral)
    public func isSubscribed(to char: CBCharacteristic) -> Bool
}
```

### Technical Constraints
- CoreBluetooth uses `setNotifyValue(true/false, for:)` for both notifications and indications — the CCCD descriptor is managed automatically
- Incoming data arrives in `didUpdateValueFor` delegate method — route by characteristic UUID
- Maintain `[CBUUID: (Data) -> Void]` subscription registry

### Unit Tests
```swift
test_subscribeCallsSetNotifyTrue()
test_unsubscribeCallsSetNotifyFalse()
test_handlerInvokedOnData()
test_isSubscribedTrueAfterSubscribe()
test_unsubscribeAllClearsRegistry()
```

### Acceptance Criteria
- [ ] Docstring explains CoreBluetooth's unified notify/indicate handling
- [ ] All 5 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase02-sprint23-notifications
git commit -m "feat(ble-swift): notification and indication subscription manager"
```

---

## Sprint 24 — Scan Without Connection (Advertisement Data)

### Metadata
- **File:** `09_xcode_apple/Shared/07_BLEScanNoConnect.swift`
- **Phase 01 reference:** `01_python_bleak/07_scan_without_connect.py`
- **Dependencies:** Sprint 18
- **Estimated complexity:** low

### Expected API
```swift
public struct AdvertisementInfo {
    public let device: CBPeripheral
    public let localName: String?
    public let manufacturerData: Data?
    public let serviceData: [CBUUID: Data]
    public let txPowerLevel: Int?
    public let serviceUUIDs: [CBUUID]
}

public extension BLEScanManager {
    func scanForAdvertisements(duration: TimeInterval = 5.0,
                               completion: @escaping ([AdvertisementInfo]) -> Void)
}
```

### Technical Constraints
- Parse `CBAdvertisementDataManufacturerDataKey` (Data), `CBAdvertisementDataServiceDataKey` ([CBUUID: Data]), `CBAdvertisementDataTxPowerLevelKey` (NSNumber)
- `CBCentralManagerScanOptionAllowDuplicatesKey: true` to receive repeated advertisements from same device

### Unit Tests
```swift
test_parseManufacturerData()
test_parseServiceData()
test_parseTxPower()
test_missingFieldsHandledGracefully()
```

### Acceptance Criteria
- [ ] All 4 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase02-sprint24-adv-data
git commit -m "feat(ble-swift): advertisement data parsing without connection"
```

---

## Sprint 25 — Battery Level

### Metadata
- **File:** `09_xcode_apple/Shared/08_BLEBattery.swift`
- **Phase 01 reference:** `01_python_bleak/08_battery.py`
- **Dependencies:** Sprint 22
- **Estimated complexity:** low

### Expected API
```swift
public let batteryServiceUUID   = CBUUID(string: "180F")
public let batteryCharUUID      = CBUUID(string: "2A19")

public extension BLEConnectionManager {
    func readBatteryLevel(peripheral: CBPeripheral,
                          completion: @escaping (Int?) -> Void)
    func subscribeBatteryNotifications(peripheral: CBPeripheral,
                                       handler: @escaping (Int) -> Void)
}
```

### Unit Tests
```swift
test_readBatteryReturnsInt()
test_batteryServiceNotFoundReturnsNil()
test_batteryNotificationHandlerInvoked()
```

### Acceptance Criteria
- [ ] Returns nil gracefully when Battery Service absent
- [ ] All 3 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase02-sprint25-battery
git commit -m "feat(ble-swift): battery level read and notification"
```

---

## Sprint 26 — Heart Rate Monitor Example

### Metadata
- **File:** `09_xcode_apple/Shared/09_BLEHeartRate.swift`
- **Phase 01 reference:** `01_python_bleak/09_heartrate_example.py`
- **Dependencies:** Sprints 23, 25
- **Estimated complexity:** medium

### Expected API
```swift
public struct HeartRateMeasurement {
    public let bpm: Int
    public let rrIntervals: [Double]  // seconds
    public let sensorContact: Bool?
}

public func parseHeartRateMeasurement(_ data: Data) -> HeartRateMeasurement?
public class HeartRateMonitor {
    public func start(peripheral: CBPeripheral, handler: @escaping (HeartRateMeasurement) -> Void)
    public func stop(peripheral: CBPeripheral)
}
```

### Technical Constraints
- Bit 0 of flags byte: 0 = UINT8 BPM, 1 = UINT16 BPM
- Bit 4: RR intervals present
- RR intervals: UINT16, unit = 1/1024 s
- Use `Data` extension for bit manipulation

### Unit Tests
```swift
test_parseUInt8BPM()
test_parseUInt16BPM()
test_parseWithRRIntervals()
test_rrIntervalConversion()
test_sensorContactFlag()
```

### Acceptance Criteria
- [ ] All 5 unit tests pass with hardcoded byte sequences

### Git Workflow
```bash
git checkout -b feature/phase02-sprint26-heartrate
git commit -m "feat(ble-swift): heart rate monitor with HRM packet parser"
```

---

## Sprint 27 — Muse EEG Example

### Metadata
- **File:** `09_xcode_apple/Shared/10_MuseEEG.swift`
- **Phase 01 reference:** `01_python_bleak/10_muse_example.py`
- **Dependencies:** Sprints 23, 25
- **Estimated complexity:** high

### Objective
Swift port of the Muse EEG parser. Parses uint:12 packed BLE packets using Swift bitwise operations (no bitstring library available).

### Expected API
```swift
public let MUSE_EEG_SCALE_FACTOR: Double = 0.48828125
public let MUSE_EEG_SAMPLES_PER_PACKET = 12

public struct MusePacket {
    public let samples: [Double]    // 12 samples in µV
    public let channelName: String
}

public struct MuseTelemetry {
    public let batteryPercent: Double
    public let fuelGauge: Double
    public let adcVoltage: Double
    public let temperature: Double
}

public func parseEEGPacket(_ data: Data, channelName: String) -> MusePacket?
public func parseTelemetryPacket(_ data: Data) -> MuseTelemetry?

public class MuseStreamer: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    public init(address: UUID,
                onEEG: @escaping (MusePacket) -> Void,
                onBattery: ((Double) -> Void)? = nil)
    public func start()
    public func stop()
}
```

### Technical Constraints
- uint:12 parsing in Swift: extract 12-bit values from Data using bitwise shifts
  ```swift
  // Packet layout: 2 bytes (counter, discarded) + 18 bytes (12 × 12-bit samples)
  // Sample i starts at bit offset: 16 + (i * 12)
  // Byte index: (16 + i*12) / 8
  // Bit offset within byte: (16 + i*12) % 8
  ```
- Scale: `µV = 0.48828125 * (Double(raw) - 2048.0)`
- Telemetry: 5 × uint16, first discarded (packet counter), battery = `values[1] / 512.0`
- No third-party dependencies — pure Swift / Foundation

### Unit Tests
```swift
test_parseEEGPacketShape()              // assert 12 samples
test_parseEEGScaleFactor()             // known bytes → expected µV
test_parseEEGCounterDiscarded()        // different counter → same samples
test_parseTelemetryBattery()           // raw bytes → percentage
test_parseTelemetryAllFields()
test_eegZeroRawValue()                 // raw=2048 → 0.0 µV
```

### Risks & Mitigations
| Risk | Mitigation |
|------|-----------|
| Bit-packing edge cases in Swift | Cross-validate output byte-for-byte against Python reference parser |

### Acceptance Criteria
- [ ] Swift parser output matches Python reference for identical input bytes
- [ ] All 6 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase02-sprint27-muse
git commit -m "feat(ble-swift): Muse S EEG parser with pure-Swift uint:12 bit unpacking"
```

### Agent Notes
> Validate this parser against `01_python_bleak/10_muse_example.py::parse_eeg_packet` using the same input bytes. Both should return identical µV values.

---

## Sprint 28 — Error Handling & Timeouts

### Metadata
- **File:** `09_xcode_apple/Shared/11_BLEErrors.swift`
- **Phase 01 reference:** `01_python_bleak/11_error_handling.py`
- **Dependencies:** Sprint 20
- **Estimated complexity:** low-medium

### Expected API
```swift
public enum BLEError: Error {
    case bluetoothUnavailable
    case connectionTimeout(UUID)
    case connectionFailed(UUID, Error?)
    case serviceNotFound(CBUUID)
    case characteristicNotFound(CBUUID)
    case readFailed(CBUUID, Error?)
    case writeFailed(CBUUID, Error?)
    case invalidData(String)
}

public func withBLETimeout<T>(_ timeout: TimeInterval,
                               operation: @escaping () async throws -> T) async throws -> T
```

### Unit Tests
```swift
test_timeoutThrowsAfterDeadline()
test_timeoutCompletesBeforeDeadline()
test_bleErrorIsError()
```

### Acceptance Criteria
- [ ] All error cases documented with description
- [ ] All 3 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase02-sprint28-errors
git commit -m "feat(ble-swift): BLEError enum and timeout utility"
```

---

## Sprint 29 — MTU Negotiation

### Metadata
- **File:** `09_xcode_apple/Shared/12_BLEMTU.swift`
- **Phase 01 reference:** `01_python_bleak/12_mtu_negotiation.py`
- **Dependencies:** Sprint 20
- **Estimated complexity:** low

### Expected API
```swift
public let attHeaderBytes = 3

public extension CBPeripheral {
    var effectiveWritePayloadSize: Int {
        maximumWriteValueLength(for: .withoutResponse) // CoreBluetooth handles MTU internally
    }
}

public func effectivePayloadSize(mtu: Int) -> Int { mtu - attHeaderBytes }
```

### Technical Constraints
- CoreBluetooth negotiates MTU automatically on iOS 14+/macOS 11+
- `maximumWriteValueLength(for: .withoutResponse)` returns the current negotiated payload size
- No manual MTU negotiation API exists in CoreBluetooth — document this clearly

### Unit Tests
```swift
test_effectivePayloadSize()      // mtu=247 → 244
test_defaultMTUValue()
```

### Acceptance Criteria
- [ ] Docstring explains CoreBluetooth auto-negotiation
- [ ] All 2 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase02-sprint29-mtu
git commit -m "feat(ble-swift): MTU payload size utility"
```

---

## Sprint 30 — Bonding & Pairing

### Metadata
- **File:** `09_xcode_apple/Shared/13_BLEPairing.swift`
- **Phase 01 reference:** `01_python_bleak/13_bonding_pairing.py`
- **Dependencies:** Sprint 20
- **Estimated complexity:** low-medium

### Technical Constraints
- CoreBluetooth triggers pairing automatically when accessing a protected characteristic
- No explicit `pair()` API — document that pairing is implicit
- `CBCentralManager.retrieveConnectedPeripherals(withServices:)` can detect bonded devices
- Implement `triggerPairing` by attempting to read a protected characteristic

### Expected API
```swift
public extension BLEConnectionManager {
    /// Trigger implicit pairing by reading a protected characteristic.
    func triggerPairing(peripheral: CBPeripheral,
                        protectedChar: CBCharacteristic,
                        completion: @escaping (Bool) -> Void)

    func retrieveBondedPeripherals(serviceUUIDs: [CBUUID]) -> [CBPeripheral]
}
```

### Unit Tests
```swift
test_triggerPairingCallsRead()
test_retrieveBondedPeripheralsReturnsArray()
```

### Acceptance Criteria
- [ ] Docstring explains implicit pairing model in CoreBluetooth
- [ ] All 2 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase02-sprint30-pairing
git commit -m "feat(ble-swift): implicit pairing trigger and bonded device retrieval"
```

---

## Sprint 31 — Peripheral Mode (Advertising)

### Metadata
- **File:** `09_xcode_apple/Shared/14_BLEPeripheral.swift`
- **Phase 01 reference:** `01_python_bleak/14_peripheral_mode.py`
- **Dependencies:** Sprint 20
- **Estimated complexity:** medium-high

### Expected API
```swift
public class BLEPeripheralServer: NSObject, CBPeripheralManagerDelegate {
    public init(serviceUUID: CBUUID, charUUID: CBUUID, localName: String)
    public func startAdvertising()
    public func stopAdvertising()
    public func updateValue(_ data: Data, for characteristic: CBMutableCharacteristic)
    public var onReadRequest: ((CBATTRequest) -> Void)?
    public var onWriteRequest: ((CBATTRequest) -> Void)?
}
```

### Technical Constraints
- Uses `CBPeripheralManager` (not `CBCentralManager`)
- `CBMutableCharacteristic` with desired properties and permissions
- Advertising dictionary: `[CBAdvertisementDataServiceUUIDsKey: [serviceUUID], CBAdvertisementDataLocalNameKey: localName]`
- macOS: requires `NSBluetoothPeripheralUsageDescription` in Info.plist (deprecated but still needed for older macOS)
- iOS: background advertising requires `bluetooth-peripheral` background mode

### Unit Tests
```swift
test_advertisingStartsWhenPoweredOn()
test_advertisingStopsOnRequest()
test_readRequestHandlerCalled()
test_writeRequestHandlerCalled()
```

### Acceptance Criteria
- [ ] Platform config differences documented in `iOS/README.md` and `macOS/README.md`
- [ ] All 4 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase02-sprint31-peripheral
git commit -m "feat(ble-swift): CBPeripheralManager server with advertising and GATT handlers"
```

---

## Sprint 32 — RSSI & Distance Estimation

### Metadata
- **File:** `09_xcode_apple/Shared/15_BLERSSIDistance.swift`
- **Phase 01 reference:** `01_python_bleak/15_rssi_distance.py`
- **Dependencies:** Sprint 20
- **Estimated complexity:** low

### Expected API
```swift
public let measuredPowerDefault: Double = -59
public let pathLossExponentDefault: Double = 2.0

public func estimateDistance(rssi: Int,
                              measuredPower: Double = measuredPowerDefault,
                              pathLossExponent: Double = pathLossExponentDefault) -> Double

public extension CBPeripheral {
    func readRSSI(completion: @escaping (Int) -> Void)
}
```

### Technical Constraints
- `CBPeripheral.readRSSI()` result arrives in `didReadRSSI(_:error:)` delegate
- Wrap in completion handler using stored callback dictionary

### Unit Tests
```swift
test_distanceAt1Meter()
test_distanceFarther()
test_distanceFormula()
```

### Acceptance Criteria
- [ ] Formula matches Python reference implementation
- [ ] All 3 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase02-sprint32-rssi
git commit -m "feat(ble-swift): RSSI read and distance estimation"
```

---

## Sprint 33 — Command Queue

### Metadata
- **File:** `09_xcode_apple/Shared/16_BLECommandQueue.swift`
- **Phase 01 reference:** `01_python_bleak/16_command_queue.py`
- **Dependencies:** Sprint 22
- **Estimated complexity:** medium

### Expected API
```swift
public class BLECommandQueue {
    public typealias BLECommand = (@escaping () -> Void) -> Void

    public func enqueue(_ command: @escaping BLECommand)
    public func start()
    public func stop()
    public var count: Int { get }
}
```

### Technical Constraints
- Implemented with `OperationQueue(maxConcurrentOperationCount: 1)` for serial execution
- Each command receives a `completion` closure it must call when done
- Alternatively: actor-based queue using Swift concurrency (`async/await`)

### Unit Tests
```swift
test_commandsExecuteSerially()
test_queueCountDecrements()
test_stopPreventsFurtherExecution()
```

### Acceptance Criteria
- [ ] Serial execution verified
- [ ] All 3 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase02-sprint33-command-queue
git commit -m "feat(ble-swift): serial BLE command queue using OperationQueue"
```

---

## Sprint 34 — OTA / DFU

### Metadata
- **File:** `09_xcode_apple/Shared/17_BLEDFU.swift`
- **Phase 01 reference:** `01_python_bleak/17_ota_dfu.py`
- **Dependencies:** Sprints 22, 28, 33
- **Estimated complexity:** high

### Expected API
```swift
public struct DFUProgress {
    public let totalBytes: Int
    public let bytesSent: Int
    public var percent: Double { Double(bytesSent) / Double(totalBytes) * 100 }
}

public class BLEDFUManager {
    public func writeChunked(peripheral: CBPeripheral,
                              characteristic: CBCharacteristic,
                              data: Data,
                              chunkSize: Int = 244,
                              onProgress: ((DFUProgress) -> Void)? = nil,
                              completion: @escaping (Result<Void, Error>) -> Void)

    public func startNordicDFU(peripheral: CBPeripheral,
                                firmwareURL: URL,
                                onProgress: ((DFUProgress) -> Void)? = nil,
                                completion: @escaping (Result<Void, Error>) -> Void)
}
```

### Technical Constraints
- Chunked write uses `writeWithoutResponse` for speed; verify with response for last chunk
- Nordic nRF DFU: DFU Service UUID `FE59`, uses same protocol as Python reference
- Consider referencing nRF Connect SDK for iOS for production use

### Unit Tests
```swift
test_writeChunkedSplitsDataCorrectly()   // 500 bytes, chunk=244 → 3 writes
test_lastChunkSmallerThanChunkSize()
test_progressCallbackFiredPerChunk()
test_progressReaches100AtEnd()
```

### Acceptance Criteria
- [ ] Chunking logic matches Python reference
- [ ] All 4 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase02-sprint34-dfu
git commit -m "feat(ble-swift): chunked OTA write and nRF DFU implementation"
```

---

## Phase 02 Completion Checklist

- [ ] All 17 Swift files exist in `09_xcode_apple/Shared/`
- [ ] `iOS/README.md`, `macOS/README.md`, `iPadOS/README.md` each contain platform config diff table
- [ ] All unit tests pass (`xcodebuild test`)
- [ ] Swift parser for Muse validated against Python reference output
- [ ] PR from `develop` → `main` created with label `phase02`
- [ ] Tag `phase02-complete` applied on merge

```bash
git checkout main
git merge develop --no-ff -m "feat: Phase 02 complete — Swift/CoreBluetooth Apple BLE reference library"
git tag phase02-complete
git push origin main --tags
```
