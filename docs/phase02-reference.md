# Phase 02 — Swift / CoreBluetooth — Agent Reference

> For AI agents working on or extending this codebase. Describes what exists, how it fits together, and the critical CoreBluetooth patterns to understand.

## Overview

Phase 02 implements the BLE Reference Library in Swift using Apple's CoreBluetooth framework, distributed as a Swift Package. It covers 18 sprints (Sprints 19–36) mirroring Phase 01's module structure. A multiplatform SwiftUI TestApp validates all parsers against real Polar H10 and Muse 2 hardware on iPhone, iPad, and Mac.

**Library:** `09_xcode_apple/Sources/BLEReference/` (Swift Package)
**Tests:** `09_xcode_apple/Tests/BLEReferenceTests/`
**TestApp:** `09_xcode_apple/TestApp/TestApp/` (Xcode project)
**Test command:** `cd 09_xcode_apple && swift test`
**Minimum platforms:** Library — iOS 15 / macOS 12. TestApp — iOS 16 / macOS 13 (for SwiftUI Charts).

---

## Module Index

Each module is a single `.swift` file exporting public types. The library is imported as `import BLEReference`.

| File | Sprint | Purpose | Key exports |
|------|--------|---------|-------------|
| `01_BLEScan.swift` | 19 | Passive BLE scan | `BLEScanner` (class), `DiscoveredDevice` (struct), `BLEScanError` |
| `02_BLEScanFiltered.swift` | 20 | Scan filtering | `ScanFilter` (struct) with `.byName()`, `.byUUID()`, `.byRSSI()`, `.apply(to:)` |
| `03_BLEConnection.swift` | 21 | Connection lifecycle | `BLEConnector` (class), `BLEConnectionState` (enum), `BLEConnectionError` |
| `04_BLEServicesDiscovery.swift` | 22 | Service/characteristic discovery | `BLEServiceDiscoverer` (class), `DiscoveredService` (struct) |
| `05_BLEReadWrite.swift` | 23 | Read/write GATT | `BLEReadWriter` (class), `.read()`, `.write()`, `.writeWithoutResponse()` |
| `06_BLENotifications.swift` | 24 | Notifications/indications | `BLENotificationSubscriber` (class), `.subscribe()`, `.unsubscribe()` |
| `07_BLEScanNoConnect.swift` | 25 | Advertisement-only data | `BLEScanDataExtractor` utility functions |
| `08_BLEBattery.swift` | 26 | Battery Service (0x180F) | `BLEBattery.readLevel(on:using:)` → `Int?` (async) |
| `09_BLEDeviceInfo.swift` | 27 | Device Information Service (0x180A) | `BLEDeviceInfo.read(on:using:)` → `DeviceInfo?` (async), `DeviceInfo` struct |
| `10_BLEHeartRate.swift` | 28 | Polar H10 HRM + PMD ECG | `HeartRateParser`, `PolarEcgParser`, PMD UUIDs + start/stop commands |
| `11_MuseEEG.swift` | 29 | Muse 2 EEG | `MuseEegParser`, channel UUIDs, handshake commands |
| `12_BLEErrors.swift` | 30 | Error hierarchy | `BLEError` enum with common cases |
| `13_BLEMTU.swift` | 31 | MTU negotiation | `BLEMTU` utility |
| `14_BLEPairing.swift` | 32 | Bonding/pairing | `BLEPairing` utility |
| `15_BLEPeripheral.swift` | 33 | Peripheral mode | `BLEPeripheralManager` |
| `16_BLERSSIDistance.swift` | 34 | RSSI distance estimation | `BLERSSIDistance`, `PathLossModel` |
| `17_BLECommandQueue.swift` | 35 | Serialized command queue | `BLECommandQueue` |
| `18_BLEDFU.swift` | 36 | OTA firmware update | `BLEDFUManager` |

---

## Critical CoreBluetooth Patterns

### 1. Delegate-to-async bridging

CoreBluetooth is entirely delegate-based. Every async operation uses `CheckedContinuation` to bridge:

```swift
try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
    self.queue.async {
        self.continuation = cont
        self.centralManager.connect(peripheral)
    }
}
// Delegate callback later:
func centralManager(_:, didConnect:) {
    continuation?.resume()
    continuation = nil
}
```

### 2. CBPeripheral ownership — CRITICAL

**A CBPeripheral is bound to the CBCentralManager that discovered it.** Passing a peripheral to a different CBCentralManager causes silent connection failures (no error, no timeout — just nothing happens).

```swift
// WRONG — creates a new CBCentralManager, can't connect to scanner's peripheral
let connector = BLEConnector()

// CORRECT — reuses the scanner's CBCentralManager
let connector = BLEConnector(centralManager: scanner.centralManager)
```

### 3. peripheral.delegate is a singleton

`CBPeripheral` has a single `delegate` property. When multiple objects need peripheral callbacks (subscriber for notifications, readWriter for read/write confirmations), they compete:

```swift
// subscriber sets peripheral.delegate = self → receives notifications
// readWriter.write() sets peripheral.delegate = self → STEALS from subscriber
// Fix: restore after write
try await readWriter.write(data, to: char, on: peripheral)
peripheral.delegate = subscriber  // restore
```

### 4. isPoweredOn must be synced when reusing CBCentralManager

When `BLEConnector` reuses an existing CBCentralManager (already `.poweredOn`), the `centralManagerDidUpdateState` callback won't fire again. The connector's `isPoweredOn` flag must be set from the CM's current state in `init`:

```swift
self.isPoweredOn = centralManager.state == .poweredOn
```

### 5. characteristic.value is NOT pre-populated

CoreBluetooth does not read characteristic values during service discovery. `char.value` is `nil` until you explicitly call `peripheral.readValue(for:)`. Functions like `BLEDeviceInfo.read()` and `BLEBattery.readLevel()` must be `async` and use `BLEReadWriter` to perform actual reads.

### 6. @Sendable closure constraints

`@Observable` classes are MainActor-isolated. BLE notification callbacks are `@Sendable` and run off-main. Mutations must be dispatched:

```swift
subscriber.subscribe(to: char, on: peripheral) { [weak self] data in
    Task { @MainActor in
        self?.samples.append(data)  // Must be on MainActor
    }
}
```

Loop variables captured in `@Sendable` closures must be in the capture list:

```swift
for (idx, uuid) in uuids.enumerated() {
    let name = names[idx]  // Local copy
    subscriber.subscribe(...) { [weak self, name] data in  // Capture name
        // ...
    }
}
```

---

## TestApp Architecture

| File | Role |
|------|------|
| `TestAppApp.swift` | `@main` entry point |
| `ContentView.swift` | TabView with Polar/Muse tabs, chart views, log viewer, PNG export |
| `PolarTestRunner.swift` | 11-step Polar H10 integration (HRM + ECG 30s) |
| `MuseTestRunner.swift` | 11-step Muse 2 integration (4-ch EEG 15s) |
| `ECGChartView.swift` | SwiftUI Charts LineMark for ECG (skip 2s drift, clamp ±1500 µV) |
| `EEGChartView.swift` | SwiftUI Charts 4-channel stacked EEG (skip 2s, clamp ±200 µV) |
| `ChartExporter.swift` | `ImageRenderer` → PNG export with `#if os(iOS)` branching |
| `TestLogger.swift` | `@Observable` log entries + file output to Documents/ |

**TestApp data flow:**
```
BLEScanner.scan() → ScanFilter.apply() → BLEConnector.connect()
→ BLEServiceDiscoverer.discoverAll() → BLEDeviceInfo.read() → BLEBattery.readLevel()
→ BLENotificationSubscriber.subscribe() → Parser.parse() → ChartView → ChartExporter → PNG
```

**Artifacts location (macOS):**
```
~/Library/Containers/com.remyramadour.TestApp/Data/Documents/
├── ecg_plot_<timestamp>.png
├── eeg_plot_<timestamp>.png
├── polar_integration_<timestamp>.txt
└── muse_integration_<timestamp>.txt
```

---

## Integration Test Results (2026-03-21)

### Polar H10 — PASS
- 4088 ECG samples in 31.4s (130 Hz)
- 32 HR readings (~1/s)
- Std dev: 2026.2 µV (threshold: 10 µV)
- DIS: Polar Electro Oy, H10, Serial 017ECD3E, FW 5.0.0
- Battery: 100%

### Muse 2 — PASS
- TP9: 3912 samples, std 662.0 µV
- AF7: 3888 samples, std 23.7 µV
- AF8: 3864 samples, std 160.9 µV
- TP10: 3852 samples, std 659.0 µV
- All channels > 3000 samples, std > 5 µV

### Platforms validated
- iPhone (USB)
- iPad (USB)
- Mac (native)

---

## Extending This Phase

When adding a new module:
1. Create `09_xcode_apple/Sources/BLEReference/XX_Name.swift` with public types
2. Create `09_xcode_apple/Tests/BLEReferenceTests/TestXX_Name.swift`
3. Follow the delegate-to-async pattern with `CheckedContinuation`
4. Remember: single `peripheral.delegate` — coordinate with other modules
5. Update `docs/Sprints/SPRINT_TRACKER.yaml`
6. If reading characteristics: make it `async`, accept `BLEReadWriter` parameter
