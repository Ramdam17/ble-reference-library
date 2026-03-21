# Real-Device Integration Tests — Design Spec

**Date:** 2026-03-21
**Scope:** Phase 02 Swift TestApp + cross-phase integration test protocol
**Status:** Approved

---

## 1. Problem

Unit tests validate parser logic with forged byte arrays, but cannot verify the full BLE stack: advertisement → connection → service discovery → notification subscribe → data stream → parse → plot. Real-device integration tests are required to validate each phase's implementation against actual hardware (Polar H10, Muse 2) before proceeding to advanced sprints.

Phase 01 (Python/Bleak) already has validated integration tests (`10_heartrate_example.py`, `11_muse_example.py`) with evidence in `logs/` (PNG plots + structured logs from 2026-03-20). Phases 02–05 need equivalent validation.

---

## 2. Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Target platforms | iOS + macOS | Validates CoreBluetooth on all Apple platforms; iPhone via USB, Mac natively |
| Plot technology | SwiftUI Charts + `ImageRenderer` | Native, no dependencies; `ImageRenderer` exports PNG programmatically (requires iOS 16 / macOS 13) |
| App structure | Single multiplatform TestApp in `09_xcode_apple/TestApp/` | SwiftUI multiplatform = one codebase; `#if os()` for rare platform differences |
| Interactivity | One "Run Test" button per device | Automatic flow, minimal interaction; results in ScrollView + auto PNG export |
| Cross-phase pattern | Sprint tracker gate + protocol document | Tracker enforces per-sprint status; protocol document is the reusable spec |

---

## 3. TestApp Architecture

```
09_xcode_apple/
├── Package.swift                          # BLEReference library (existing)
├── Sources/BLEReference/                  # 18 modules (existing)
├── Tests/BLEReferenceTests/               # 82 unit tests (existing)
└── TestApp/
    ├── TestApp.xcodeproj                  # Xcode project, iOS + macOS targets
    └── TestApp/
        ├── TestAppApp.swift               # @main entry point
        ├── ContentView.swift              # Tab view: Polar | Muse | Logs
        ├── PolarTestRunner.swift          # Full Polar H10 flow (HRM + ECG)
        ├── MuseTestRunner.swift           # Full Muse 2 flow (EEG)
        ├── ECGChartView.swift             # SwiftUI Charts — ECG trace
        ├── EEGChartView.swift             # SwiftUI Charts — 4-channel EEG
        ├── ChartExporter.swift            # ImageRenderer → PNG export
        ├── TestLogger.swift               # Structured logger (file + UI)
        └── Info.plist                     # NSBluetoothAlwaysUsageDescription
```

The TestApp imports `BLEReference` as a **local package dependency** (Xcode: File → Add Package → Add Local, pointing to `09_xcode_apple/`).

**Minimum platforms:** iOS 16.0 / macOS 13.0 (required for SwiftUI Charts + `ImageRenderer`).

---

## 4. Polar H10 Test Flow (PolarTestRunner)

Mirrors `01_python_bleak/10_heartrate_example.py` steps 1–11:

| Step | Operation | BLEReference module used |
|------|-----------|--------------------------|
| 1 | Scan for "Polar H10" (timeout 10s) | `BLEScanner` + `ScanFilter.byName()` |
| 2 | Connect | `BLEConnector` |
| 3 | Discover services | `BLEServiceDiscoverer` |
| 4 | Read device info (serial, model, firmware) | `BLEDeviceInfo` |
| 5 | Read battery level | `BLEBattery` |
| 6 | Subscribe HRM notifications (0x2A37) | `BLENotificationSubscriber` + `HeartRateParser` |
| 7 | Start PMD ECG stream (write control point) | `BLEReadWriter` + `PolarEcgParser` |
| 8 | Stream 30 seconds — accumulate ECG frames | `PolarEcgParser.parse()` |
| 9 | Stop streaming, disconnect | `BLEConnector.disconnect()` |
| 10 | Plot ECG with SwiftUI Charts, export PNG | `ECGChartView` + `ChartExporter` |
| 11 | Log summary (frames, samples, duration, µV stats) | `TestLogger` |

**Outputs:**
- `ecg_plot_<timestamp>.png` — saved to app's Documents directory
- Structured log displayed in UI + exportable

**Pass criteria:** ≥ 3000 ECG samples in 30s (~130 Hz × 30s = 3900 expected), PNG generated with visible signal.

---

## 5. Muse 2 Test Flow (MuseTestRunner)

Mirrors `01_python_bleak/11_muse_example.py` steps 1–11:

| Step | Operation | BLEReference module used |
|------|-----------|--------------------------|
| 1 | Scan for "Muse" (timeout 10s) | `BLEScanner` + `ScanFilter.byName()` |
| 2 | Connect | `BLEConnector` |
| 3 | Discover services | `BLEServiceDiscoverer` |
| 4 | Read device info (if DIS available) | `BLEDeviceInfo` |
| 5 | Read battery (if available) | `BLEBattery` |
| 6 | Handshake — ASK version + STREAM start | `BLEReadWriter` |
| 7 | Subscribe EEG (4 channels: TP9, AF7, AF8, TP10) | `BLENotificationSubscriber` + `MuseEegParser` |
| 8 | Stream 15 seconds — accumulate EEG packets | `MuseEegParser.parse()` |
| 9 | STREAM stop, unsubscribe, disconnect | `BLEConnector.disconnect()` |
| 10 | Plot 4-channel EEG, export PNG | `EEGChartView` + `ChartExporter` |
| 11 | Log summary (packets, samples/ch, duration, amplitude) | `TestLogger` |

**Outputs:**
- `eeg_plot_<timestamp>.png` — saved to app's Documents directory
- Structured log displayed in UI + exportable

**Pass criteria:** ≥ 3000 samples per channel in 15s (~256 Hz × 15s = 3840 expected), 4 channels visible on PNG.

---

## 6. Integration Test Protocol (Cross-Phase)

This protocol applies to **every phase** of the BLE Reference Library. A separate document (`docs/integration-test-protocol.md`) will contain the canonical version.

### 6.1 Required Devices

- **Polar H10** — worn on chest, powered on (LED blinking)
- **Muse 2** (or Muse S) — powered on, on forehead for real EEG signal

### 6.2 Per-Device Protocol

| | Polar H10 | Muse 2 |
|---|---|---|
| Streaming duration | 30 seconds | 15 seconds |
| Protocol | HRM (0x180D) + PMD ECG proprietary | Muse GATT proprietary |
| Expected sample rate | ~130 Hz ECG | ~256 Hz EEG × 4 channels |
| Minimum samples for pass | 3000 ECG | 3000 per channel |
| Outputs | `ecg_plot_<timestamp>.png` + log | `eeg_plot_<timestamp>.png` + log |
| Log must contain | frames, samples, duration, min/max/mean µV | packets, samples/ch, duration, amplitude range |

### 6.3 Pass/Fail Criteria

1. Connection succeeds
2. Service discovery finds expected services
3. Sample count above minimum threshold
4. PNG generated, signal visible (not a flat line)
5. Clean disconnect (no crash)

### 6.4 Phase Mapping

| Phase | Polar Sprint | Muse Sprint | Plot Technology |
|---|---|---|---|
| 01 Python/Bleak | Sprint 10 | Sprint 11 | matplotlib PNG |
| 02 Swift/CoreBluetooth | Sprint 28 | Sprint 29 | SwiftUI Charts PNG |
| 03 JavaScript | Sprint 46 | Sprint 47 | Chart.js / Canvas PNG |
| 04 Unity C# | Sprint 64 | Sprint 65 | Unity UI + screenshot |
| 05 C++ | Sprint 82 | Sprint 83 | matplotlibcpp or CSV→Python |

### 6.5 Gate Rule

> No sprint beyond the Muse sprint of a phase may be marked "done" until both integration tests (Polar + Muse) have produced their artifacts (PNG + log).

---

## 7. Sprint Tracker Impact

### Phase 02 corrections needed

Sprints 28–29 are currently marked "done" but lack real-device validation. They must be reverted to "in_progress" until the TestApp produces the required artifacts.

Sprints 30–36 (Errors, MTU, Pairing, Peripheral, RSSI, Queue, DFU) were marked "done" based on unit tests alone. Per the gate rule, they cannot be "done" until Sprints 28–29 pass real-device validation. They should be reverted to "blocked_by_integration".

**Pragmatic approach:** Keep unit-test-validated sprints as "done" for the code, but add a tracker-level note that Phase 02 real-device gate is pending. The TestApp work is a separate deliverable.

---

## 8. What This Spec Does NOT Cover

- TestApp UI polish (colors, animations) — minimal functional UI only
- Automated CI/CD for real-device tests (requires physical hardware)
- Muse S-specific differences (same protocol as Muse 2)
- Phase 03–05 implementation details (each will get its own spec when the time comes)
