# Real-Device Integration Tests — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a multiplatform SwiftUI TestApp that validates the BLEReference library against real Polar H10 (ECG) and Muse 2 (EEG) hardware, and create the cross-phase integration test protocol document.

**Architecture:** SwiftUI app with two test runners (Polar, Muse), each executing a sequential 11-step flow that exercises BLEReference modules on real devices. SwiftUI Charts renders ECG/EEG plots; `ImageRenderer` exports PNG. A shared `TestLogger` provides both UI display and file-based logging.

**Tech Stack:** Swift 5.9+, SwiftUI, SwiftUI Charts, CoreBluetooth, ImageRenderer, BLEReference (local package dependency)

**Spec:** `docs/Specifications/2026-03-21-real-device-integration-tests-design.md`

---

## File Map

| File | Responsibility |
|------|----------------|
| **Create:** `09_xcode_apple/TestApp/TestApp/TestLogger.swift` | Structured logger: appends timestamped entries to an `@Observable` array for UI display + writes to a log file in Documents |
| **Create:** `09_xcode_apple/TestApp/TestApp/ChartExporter.swift` | `ImageRenderer` wrapper: takes a SwiftUI `View`, renders to PNG `Data`, saves to Documents directory |
| **Create:** `09_xcode_apple/TestApp/TestApp/ECGChartView.swift` | SwiftUI Charts view: `LineMark` plotting ECG µV samples vs time (seconds), styled for export |
| **Create:** `09_xcode_apple/TestApp/TestApp/EEGChartView.swift` | SwiftUI Charts view: 4 stacked `LineMark` series (TP9/AF7/AF8/TP10) with distinct colors, µV vs time |
| **Create:** `09_xcode_apple/TestApp/TestApp/PolarTestRunner.swift` | `@Observable` class: 11-step Polar H10 flow (scan → connect → HRM + ECG stream → plot → disconnect) |
| **Create:** `09_xcode_apple/TestApp/TestApp/MuseTestRunner.swift` | `@Observable` class: 11-step Muse 2 flow (scan → connect → handshake → EEG stream → plot → disconnect) |
| **Create:** `09_xcode_apple/TestApp/TestApp/ContentView.swift` | `TabView` with 3 tabs: Polar test, Muse test, full log viewer |
| **Create:** `09_xcode_apple/TestApp/TestApp/TestAppApp.swift` | `@main` entry point |
| **Create:** `09_xcode_apple/TestApp/TestApp/Info.plist` | `NSBluetoothAlwaysUsageDescription` for BLE permission |
| **Create:** `docs/integration-test-protocol.md` | Cross-phase integration test protocol (canonical reference for all 5 phases) |
| **Modify:** `docs/Sprints/SPRINT_TRACKER.yaml` | Add `phase_integration_gate` to Phase 02 |

> **Note on Xcode project:** The `TestApp.xcodeproj` must be created via Xcode (File → New → Project → Multiplatform App). This cannot be done from the CLI. The plan includes a manual step for this, with all source files ready to add.

---

## Task 1: TestLogger — Structured Logging

**Files:**
- Create: `09_xcode_apple/TestApp/TestApp/TestLogger.swift`

This is the foundation — every other component logs through it.

- [ ] **Step 1: Create TestLogger.swift**

```swift
// TestLogger.swift — Structured logger for integration tests
//
// Dual output: @Observable array for SwiftUI display + file in Documents.
// Each entry is timestamped. Log file named: test_log_<timestamp>.txt

import Foundation
import Observation

@Observable
final class TestLogger {
    private(set) var entries: [LogEntry] = []
    private var fileHandle: FileHandle?
    private let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    struct LogEntry: Identifiable {
        let id = UUID()
        let timestamp: Date
        let level: Level
        let message: String

        enum Level: String {
            case info = "INFO"
            case success = "PASS"
            case error = "FAIL"
            case data = "DATA"
        }

        var formatted: String {
            let df = DateFormatter()
            df.dateFormat = "HH:mm:ss.SSS"
            return "[\(df.string(from: timestamp))] [\(level.rawValue)] \(message)"
        }
    }

    func start(prefix: String) {
        let timestamp = ISO8601DateFormatter().string(from: Date())
            .replacingOccurrences(of: ":", with: "")
            .replacingOccurrences(of: "-", with: "")
        let filename = "\(prefix)_\(timestamp).txt"

        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let url = docs.appendingPathComponent(filename)
        FileManager.default.createFile(atPath: url.path, contents: nil)
        fileHandle = try? FileHandle(forWritingTo: url)

        log(.info, "Log started: \(filename)")
    }

    func log(_ level: LogEntry.Level, _ message: String) {
        let entry = LogEntry(timestamp: Date(), level: level, message: message)
        entries.append(entry)
        if let data = (entry.formatted + "\n").data(using: .utf8) {
            fileHandle?.write(data)
        }
    }

    func stop() {
        fileHandle?.closeFile()
        fileHandle = nil
    }
}
```

- [ ] **Step 2: Verify it compiles**

This file will be compiled as part of the Xcode project (Task 8). For now, verify syntax by reading it back.

- [ ] **Step 3: Commit**

```bash
git add 09_xcode_apple/TestApp/TestApp/TestLogger.swift
git commit -m "feat(testapp): add TestLogger — dual output structured logger"
```

---

## Task 2: ChartExporter — PNG Export via ImageRenderer

**Files:**
- Create: `09_xcode_apple/TestApp/TestApp/ChartExporter.swift`

- [ ] **Step 1: Create ChartExporter.swift**

```swift
// ChartExporter.swift — Export SwiftUI views to PNG
//
// Uses ImageRenderer (iOS 16+ / macOS 13+) to render any SwiftUI
// view to PNG Data, then saves to the app's Documents directory.

import SwiftUI

enum ChartExporter {

    /// Render a SwiftUI view to PNG and save to Documents.
    ///
    /// - Parameters:
    ///   - view: The SwiftUI view to render.
    ///   - filename: Output filename (e.g., "ecg_plot_20260321.png").
    ///   - size: Render size in points (default: 1200×600).
    /// - Returns: The file URL where the PNG was saved, or nil on failure.
    @MainActor
    static func exportPNG<V: View>(
        _ view: V,
        filename: String,
        size: CGSize = CGSize(width: 1200, height: 600)
    ) -> URL? {
        let renderer = ImageRenderer(content: view.frame(width: size.width, height: size.height))
        renderer.scale = 2.0  // @2x for high-resolution (equivalent to dpi=300)

        guard let image = renderer.cgImage else { return nil }

        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let url = docs.appendingPathComponent(filename)

        #if os(iOS)
        guard let data = UIImage(cgImage: image).pngData() else { return nil }
        #else
        let nsImage = NSImage(cgImage: image, size: NSSize(width: size.width, height: size.height))
        guard let tiffData = nsImage.tiffRepresentation,
              let bitmap = NSBitmapImageRep(data: tiffData),
              let data = bitmap.representation(using: .png, properties: [:]) else { return nil }
        #endif

        do {
            try data.write(to: url)
            return url
        } catch {
            return nil
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add 09_xcode_apple/TestApp/TestApp/ChartExporter.swift
git commit -m "feat(testapp): add ChartExporter — ImageRenderer to PNG export"
```

---

## Task 3: ECGChartView — ECG Plot with SwiftUI Charts

**Files:**
- Create: `09_xcode_apple/TestApp/TestApp/ECGChartView.swift`

- [ ] **Step 1: Create ECGChartView.swift**

```swift
// ECGChartView.swift — ECG trace plot using SwiftUI Charts
//
// Renders ECG samples (µV vs time in seconds) as a continuous
// LineMark. Designed for both on-screen display and PNG export.

import Charts
import SwiftUI

struct ECGChartView: View {
    let samplesUv: [Int]
    let sampleRate: Double

    /// Data points for the chart — computed once from raw samples.
    private var dataPoints: [ECGDataPoint] {
        samplesUv.enumerated().map { index, uv in
            ECGDataPoint(
                id: index,
                time: Double(index) / sampleRate,
                uv: Double(uv)
            )
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("ECG — Polar H10 (\(samplesUv.count) samples, \(Int(sampleRate)) Hz)")
                .font(.headline)

            Chart(dataPoints) { point in
                LineMark(
                    x: .value("Time (s)", point.time),
                    y: .value("µV", point.uv)
                )
                .foregroundStyle(.red)
                .lineStyle(StrokeStyle(lineWidth: 0.5))
            }
            .chartXAxisLabel("Time (seconds)")
            .chartYAxisLabel("Amplitude (µV)")
            .frame(minHeight: 300)
        }
        .padding()
    }
}

private struct ECGDataPoint: Identifiable {
    let id: Int
    let time: Double
    let uv: Double
}
```

- [ ] **Step 2: Commit**

```bash
git add 09_xcode_apple/TestApp/TestApp/ECGChartView.swift
git commit -m "feat(testapp): add ECGChartView — SwiftUI Charts ECG trace"
```

---

## Task 4: EEGChartView — 4-Channel EEG Plot

**Files:**
- Create: `09_xcode_apple/TestApp/TestApp/EEGChartView.swift`

- [ ] **Step 1: Create EEGChartView.swift**

```swift
// EEGChartView.swift — 4-channel EEG plot using SwiftUI Charts
//
// Renders TP9, AF7, AF8, TP10 as 4 colored LineMark series.
// Channels are offset vertically for readability (stacked montage).

import Charts
import SwiftUI

struct EEGChartView: View {
    /// Per-channel samples in µV. Keys: "TP9", "AF7", "AF8", "TP10".
    let channels: [String: [Double]]
    let sampleRate: Double

    private let channelColors: [String: Color] = [
        "TP9": .blue,
        "AF7": .green,
        "AF8": .orange,
        "TP10": .purple,
    ]

    private let channelOrder = ["TP9", "AF7", "AF8", "TP10"]

    /// Vertical offset between channels (µV) for stacked display.
    private let channelSpacing: Double = 300.0

    private var dataPoints: [EEGDataPoint] {
        var points: [EEGDataPoint] = []
        var globalIdx = 0
        for (chIdx, name) in channelOrder.enumerated() {
            guard let samples = channels[name] else { continue }
            let offset = Double(chIdx) * channelSpacing
            for (i, uv) in samples.enumerated() {
                points.append(EEGDataPoint(
                    id: globalIdx,
                    time: Double(i) / sampleRate,
                    uv: uv + offset,
                    channel: name
                ))
                globalIdx += 1
            }
        }
        return points
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            let totalSamples = channels.values.first?.count ?? 0
            Text("EEG — Muse 2 (\(totalSamples) samples/ch, \(Int(sampleRate)) Hz)")
                .font(.headline)

            Chart(dataPoints) { point in
                LineMark(
                    x: .value("Time (s)", point.time),
                    y: .value("µV", point.uv)
                )
                .foregroundStyle(by: .value("Channel", point.channel))
                .lineStyle(StrokeStyle(lineWidth: 0.5))
            }
            .chartForegroundStyleScale([
                "TP9": Color.blue,
                "AF7": Color.green,
                "AF8": Color.orange,
                "TP10": Color.purple,
            ])
            .chartXAxisLabel("Time (seconds)")
            .chartYAxisLabel("Amplitude (µV, offset per channel)")
            .chartLegend(position: .top)
            .frame(minHeight: 400)
        }
        .padding()
    }
}

private struct EEGDataPoint: Identifiable {
    let id: Int
    let time: Double
    let uv: Double
    let channel: String
}
```

- [ ] **Step 2: Commit**

```bash
git add 09_xcode_apple/TestApp/TestApp/EEGChartView.swift
git commit -m "feat(testapp): add EEGChartView — 4-channel stacked EEG plot"
```

---

## Task 5: PolarTestRunner — Polar H10 Integration Flow

**Files:**
- Create: `09_xcode_apple/TestApp/TestApp/PolarTestRunner.swift`

This is the core integration test. It exercises BLEReference modules 01–10 against a real Polar H10.

- [ ] **Step 1: Create PolarTestRunner.swift**

```swift
// PolarTestRunner.swift — 11-step Polar H10 integration test
//
// Exercises: BLEScanner, ScanFilter, BLEConnector, BLEServiceDiscoverer,
// BLEDeviceInfo, BLEBattery, BLENotificationSubscriber, BLEReadWriter,
// HeartRateParser, PolarEcgParser.
//
// Flow: scan → connect → discover → device info → battery →
//       subscribe HRM → start ECG → stream 30s → stop → plot → disconnect
//
// Reference: 01_python_bleak/10_heartrate_example.py

import BLEReference
import CoreBluetooth
import Foundation
import Observation

@Observable
final class PolarTestRunner {
    private(set) var isRunning = false
    private(set) var ecgSamples: [Int] = []
    private(set) var hrmReadings: [Int] = []
    private(set) var ecgPlotReady = false

    let logger = TestLogger()

    // BLE objects — kept alive during the test
    private var scanner: BLEScanner?
    private var connector: BLEConnector?
    private var discoverer: BLEServiceDiscoverer?
    private var readWriter: BLEReadWriter?
    private var subscriber: BLENotificationSubscriber?
    private var peripheral: CBPeripheral?

    private let streamDuration: TimeInterval = 30.0
    private let minSamplesForPass = 3000

    func run() async {
        guard !isRunning else { return }
        isRunning = true
        ecgSamples = []
        hrmReadings = []
        ecgPlotReady = false
        logger.start(prefix: "polar_integration")

        do {
            // ── Step 1: Scan ──
            logger.log(.info, "Step 01 — Scanning for Polar H10...")
            scanner = BLEScanner()
            let devices = try await scanner!.scan(duration: 10.0)
            let filter = ScanFilter.byName("Polar H10")
            let filtered = filter.apply(to: devices)

            guard let device = filtered.first else {
                logger.log(.error, "No Polar H10 found")
                stop()
                return
            }
            logger.log(.success, "Found: \(device.name ?? "unnamed") (RSSI: \(device.rssi))")

            guard let cbPeripheral = device.peripheral else {
                logger.log(.error, "No CBPeripheral reference")
                stop()
                return
            }
            self.peripheral = cbPeripheral

            // ── Step 2: Connect ──
            logger.log(.info, "Step 02 — Connecting...")
            connector = BLEConnector()
            try await connector!.connect(to: cbPeripheral, timeout: 10.0)
            logger.log(.success, "Connected")

            // ── Step 3: Discover services ──
            logger.log(.info, "Step 03 — Discovering services...")
            discoverer = BLEServiceDiscoverer()
            let services = try await discoverer!.discoverAll(on: cbPeripheral)
            logger.log(.success, "Found \(services.count) services")
            for svc in services {
                logger.log(.data, "  Service: \(svc.id) (\(svc.characteristics.count) chars)")
            }

            // ── Step 4: Device info ──
            logger.log(.info, "Step 04 — Reading device info...")
            if let info = BLEDeviceInfo.read(on: cbPeripheral) {
                logger.log(.success, "Manufacturer: \(info.manufacturerName ?? "n/a")")
                logger.log(.data, "  Model: \(info.modelNumber ?? "n/a")")
                logger.log(.data, "  Serial: \(info.serialNumber ?? "n/a")")
                logger.log(.data, "  Firmware: \(info.firmwareRevision ?? "n/a")")
            } else {
                logger.log(.info, "DIS not available")
            }

            // ── Step 5: Battery ──
            logger.log(.info, "Step 05 — Reading battery...")
            if let battery = BLEBattery.readLevel(on: cbPeripheral) {
                logger.log(.success, "Battery: \(battery)%")
            } else {
                logger.log(.info, "Battery service not available")
            }

            // ── Step 6: Subscribe HRM ──
            logger.log(.info, "Step 06 — Subscribing to Heart Rate...")
            readWriter = BLEReadWriter()
            subscriber = BLENotificationSubscriber()

            // Find the HRM characteristic
            guard let hrmChar = findCharacteristic(kHeartRateMeasurementUUID, in: cbPeripheral) else {
                logger.log(.error, "HRM characteristic not found")
                stop()
                return
            }

            try await subscriber!.subscribe(
                to: hrmChar,
                on: cbPeripheral
            ) { [weak self] data in
                if let hr = HeartRateParser.parse(data) {
                    self?.hrmReadings.append(hr.bpm)
                    self?.logger.log(.data, "HR: \(hr.bpm) BPM")
                }
            }
            logger.log(.success, "HRM notifications active")

            // ── Step 7: Start PMD ECG ──
            logger.log(.info, "Step 07 — Starting PMD ECG stream...")

            guard let pmdControlChar = findCharacteristic(kPolarPMDControlUUID, in: cbPeripheral),
                  let pmdDataChar = findCharacteristic(kPolarPMDDataUUID, in: cbPeripheral) else {
                logger.log(.error, "PMD characteristics not found")
                stop()
                return
            }

            // Subscribe to PMD data first
            try await subscriber!.subscribe(
                to: pmdDataChar,
                on: cbPeripheral
            ) { [weak self] data in
                if let frame = PolarEcgParser.parse(data) {
                    self?.ecgSamples.append(contentsOf: frame.samplesUv)
                }
            }

            // Write start command to control point
            try await readWriter!.write(kPolarEcgStartCommand, to: pmdControlChar, on: cbPeripheral)
            logger.log(.success, "ECG streaming started (130 Hz)")

            // ── Step 8: Stream for 30s ──
            logger.log(.info, "Step 08 — Streaming for \(Int(streamDuration))s...")
            try await Task.sleep(for: .seconds(streamDuration))
            logger.log(.success, "Streaming complete: \(ecgSamples.count) ECG samples, \(hrmReadings.count) HR readings")

            // ── Step 9: Stop and disconnect ──
            logger.log(.info, "Step 09 — Stopping ECG and disconnecting...")
            try? await readWriter!.write(kPolarEcgStopCommand, to: pmdControlChar, on: cbPeripheral)
            try? await subscriber!.unsubscribe(from: pmdDataChar, on: cbPeripheral)
            try? await subscriber!.unsubscribe(from: hrmChar, on: cbPeripheral)
            await connector!.disconnect()
            logger.log(.success, "Disconnected")

            // ── Step 10: Plot ──
            logger.log(.info, "Step 10 — Generating ECG plot...")
            ecgPlotReady = true  // Triggers chart rendering in SwiftUI

            // ── Step 11: Summary ──
            let sampleCount = ecgSamples.count
            let durationActual = Double(sampleCount) / kPolarEcgSampleRate
            let minUv = ecgSamples.min() ?? 0
            let maxUv = ecgSamples.max() ?? 0
            let meanUv = ecgSamples.isEmpty ? 0 : ecgSamples.reduce(0, +) / sampleCount

            // Std dev for pass/fail
            let meanD = Double(meanUv)
            let variance = ecgSamples.map { pow(Double($0) - meanD, 2) }.reduce(0, +) / Double(max(sampleCount, 1))
            let stdDev = variance.squareRoot()

            logger.log(.info, "Step 11 — Summary")
            logger.log(.data, "  ECG samples: \(sampleCount)")
            logger.log(.data, "  Duration: \(String(format: "%.1f", durationActual))s")
            logger.log(.data, "  Range: \(minUv)–\(maxUv) µV")
            logger.log(.data, "  Mean: \(meanUv) µV")
            logger.log(.data, "  Std dev: \(String(format: "%.1f", stdDev)) µV")

            // Pass/fail
            let passedSamples = sampleCount >= minSamplesForPass
            let passedSignal = stdDev > 10.0
            logger.log(passedSamples ? .success : .error,
                       "Sample count: \(sampleCount) (min \(minSamplesForPass)) — \(passedSamples ? "PASS" : "FAIL")")
            logger.log(passedSignal ? .success : .error,
                       "Signal quality: std dev \(String(format: "%.1f", stdDev)) µV (min 10) — \(passedSignal ? "PASS" : "FAIL")")

        } catch {
            logger.log(.error, "Error: \(error.localizedDescription)")
        }

        stop()
    }

    private func stop() {
        isRunning = false
        logger.stop()
    }

    /// Find a CBCharacteristic by UUID on a connected peripheral.
    private func findCharacteristic(_ uuid: CBUUID, in peripheral: CBPeripheral) -> CBCharacteristic? {
        for service in peripheral.services ?? [] {
            for char in service.characteristics ?? [] {
                if char.uuid == uuid { return char }
            }
        }
        return nil
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add 09_xcode_apple/TestApp/TestApp/PolarTestRunner.swift
git commit -m "feat(testapp): add PolarTestRunner — 11-step Polar H10 integration"
```

---

## Task 6: MuseTestRunner — Muse 2 Integration Flow

**Files:**
- Create: `09_xcode_apple/TestApp/TestApp/MuseTestRunner.swift`

- [ ] **Step 1: Create MuseTestRunner.swift**

```swift
// MuseTestRunner.swift — 11-step Muse 2 EEG integration test
//
// Exercises: BLEScanner, ScanFilter, BLEConnector, BLEServiceDiscoverer,
// BLEDeviceInfo, BLEBattery, BLENotificationSubscriber, BLEReadWriter,
// MuseEegParser.
//
// Flow: scan → connect → discover → device info → battery →
//       handshake → subscribe EEG × 4 → stream 15s → stop → plot → disconnect
//
// Reference: 01_python_bleak/11_muse_example.py

import BLEReference
import CoreBluetooth
import Foundation
import Observation

@Observable
final class MuseTestRunner {
    private(set) var isRunning = false
    private(set) var eegChannels: [String: [Double]] = [
        "TP9": [], "AF7": [], "AF8": [], "TP10": [],
    ]
    private(set) var eegPlotReady = false

    let logger = TestLogger()

    private var scanner: BLEScanner?
    private var connector: BLEConnector?
    private var discoverer: BLEServiceDiscoverer?
    private var readWriter: BLEReadWriter?
    private var subscriber: BLENotificationSubscriber?
    private var peripheral: CBPeripheral?

    private let streamDuration: TimeInterval = 15.0
    private let minSamplesPerChannel = 3000

    func run() async {
        guard !isRunning else { return }
        isRunning = true
        eegChannels = ["TP9": [], "AF7": [], "AF8": [], "TP10": []]
        eegPlotReady = false
        logger.start(prefix: "muse_integration")

        do {
            // ── Step 1: Scan ──
            logger.log(.info, "Step 01 — Scanning for Muse...")
            scanner = BLEScanner()
            let devices = try await scanner!.scan(duration: 10.0)
            let filter = ScanFilter.byName("Muse")
            let filtered = filter.apply(to: devices)

            guard let device = filtered.first else {
                logger.log(.error, "No Muse device found")
                stop()
                return
            }
            logger.log(.success, "Found: \(device.name ?? "unnamed") (RSSI: \(device.rssi))")

            guard let cbPeripheral = device.peripheral else {
                logger.log(.error, "No CBPeripheral reference")
                stop()
                return
            }
            self.peripheral = cbPeripheral

            // ── Step 2: Connect ──
            logger.log(.info, "Step 02 — Connecting...")
            connector = BLEConnector()
            try await connector!.connect(to: cbPeripheral, timeout: 10.0)
            logger.log(.success, "Connected")

            // ── Step 3: Discover services ──
            logger.log(.info, "Step 03 — Discovering services...")
            discoverer = BLEServiceDiscoverer()
            let services = try await discoverer!.discoverAll(on: cbPeripheral)
            logger.log(.success, "Found \(services.count) services")

            // ── Step 4: Device info ──
            logger.log(.info, "Step 04 — Reading device info...")
            if let info = BLEDeviceInfo.read(on: cbPeripheral) {
                logger.log(.success, "Model: \(info.modelNumber ?? "n/a")")
            } else {
                logger.log(.info, "DIS not available (expected for Muse)")
            }

            // ── Step 5: Battery ──
            logger.log(.info, "Step 05 — Reading battery...")
            if let battery = BLEBattery.readLevel(on: cbPeripheral) {
                logger.log(.success, "Battery: \(battery)%")
            } else {
                logger.log(.info, "Battery service not available")
            }

            // ── Step 6: Handshake ──
            logger.log(.info, "Step 06 — Muse handshake (ASK + STREAM)...")
            readWriter = BLEReadWriter()
            guard let controlChar = findCharacteristic(kMuseControlUUID, in: cbPeripheral) else {
                logger.log(.error, "Muse control characteristic not found")
                stop()
                return
            }
            try readWriter!.writeWithoutResponse(kMuseAskCommand, to: controlChar, on: cbPeripheral)
            try await Task.sleep(for: .milliseconds(500))
            try readWriter!.writeWithoutResponse(kMuseStreamCommand, to: controlChar, on: cbPeripheral)
            logger.log(.success, "Handshake complete — streaming started")

            // ── Step 7: Subscribe EEG (4 channels) ──
            logger.log(.info, "Step 07 — Subscribing to 4 EEG channels...")
            subscriber = BLENotificationSubscriber()

            for (idx, channelUUID) in kMuseEegChannelUUIDs.enumerated() {
                let channelName = kMuseChannelNames[idx]
                guard let char = findCharacteristic(channelUUID, in: cbPeripheral) else {
                    logger.log(.error, "Channel \(channelName) characteristic not found")
                    continue
                }

                try await subscriber!.subscribe(
                    to: char,
                    on: cbPeripheral
                ) { [weak self] data in
                    if let packet = MuseEegParser.parse(data) {
                        self?.eegChannels[channelName]?.append(contentsOf: packet.samplesUv)
                    }
                }
                logger.log(.data, "  Subscribed: \(channelName)")
            }
            logger.log(.success, "All 4 channels active")

            // ── Step 8: Stream for 15s ──
            logger.log(.info, "Step 08 — Streaming for \(Int(streamDuration))s...")
            try await Task.sleep(for: .seconds(streamDuration))
            let sampleCounts = kMuseChannelNames.map { "\($0): \(eegChannels[$0]?.count ?? 0)" }
            logger.log(.success, "Streaming complete: \(sampleCounts.joined(separator: ", "))")

            // ── Step 9: Stop and disconnect ──
            logger.log(.info, "Step 09 — Stopping and disconnecting...")
            // Send stop command
            let stopCommand = Data([0x02, 0x68, 0x0A])  // 'h' = halt
            try? readWriter!.writeWithoutResponse(stopCommand, to: controlChar, on: cbPeripheral)
            for channelUUID in kMuseEegChannelUUIDs {
                if let char = findCharacteristic(channelUUID, in: cbPeripheral) {
                    try? await subscriber!.unsubscribe(from: char, on: cbPeripheral)
                }
            }
            await connector!.disconnect()
            logger.log(.success, "Disconnected")

            // ── Step 10: Plot ──
            logger.log(.info, "Step 10 — Generating EEG plot...")
            eegPlotReady = true

            // ── Step 11: Summary ──
            logger.log(.info, "Step 11 — Summary")
            for name in kMuseChannelNames {
                let samples = eegChannels[name] ?? []
                let count = samples.count
                let minUv = samples.min() ?? 0
                let maxUv = samples.max() ?? 0
                let meanUv = samples.isEmpty ? 0 : samples.reduce(0, +) / Double(count)
                let variance = samples.map { pow($0 - meanUv, 2) }.reduce(0, +) / Double(max(count, 1))
                let stdDev = variance.squareRoot()

                logger.log(.data, "  \(name): \(count) samples, range \(String(format: "%.0f", minUv))–\(String(format: "%.0f", maxUv)) µV, std \(String(format: "%.1f", stdDev)) µV")

                let passed = count >= minSamplesPerChannel && stdDev > 5.0
                logger.log(passed ? .success : .error,
                           "\(name): \(passed ? "PASS" : "FAIL")")
            }

        } catch {
            logger.log(.error, "Error: \(error.localizedDescription)")
        }

        stop()
    }

    private func stop() {
        isRunning = false
        logger.stop()
    }

    private func findCharacteristic(_ uuid: CBUUID, in peripheral: CBPeripheral) -> CBCharacteristic? {
        for service in peripheral.services ?? [] {
            for char in service.characteristics ?? [] {
                if char.uuid == uuid { return char }
            }
        }
        return nil
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add 09_xcode_apple/TestApp/TestApp/MuseTestRunner.swift
git commit -m "feat(testapp): add MuseTestRunner — 11-step Muse 2 EEG integration"
```

---

## Task 7: ContentView + App Entry Point

**Files:**
- Create: `09_xcode_apple/TestApp/TestApp/ContentView.swift`
- Create: `09_xcode_apple/TestApp/TestApp/TestAppApp.swift`
- Create: `09_xcode_apple/TestApp/TestApp/Info.plist`

- [ ] **Step 1: Create ContentView.swift**

```swift
// ContentView.swift — Main tab view for BLE integration tests
//
// Three tabs: Polar H10 test, Muse 2 test, full log viewer.

import SwiftUI

struct ContentView: View {
    @State private var polarRunner = PolarTestRunner()
    @State private var museRunner = MuseTestRunner()

    var body: some View {
        TabView {
            PolarTestView(runner: polarRunner)
                .tabItem { Label("Polar H10", systemImage: "heart.fill") }

            MuseTestView(runner: museRunner)
                .tabItem { Label("Muse EEG", systemImage: "brain.head.profile") }
        }
    }
}

// MARK: - Polar Test View

struct PolarTestView: View {
    @Bindable var runner: PolarTestRunner

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Header + run button
                HStack {
                    Text("Polar H10 Integration Test")
                        .font(.title2.bold())
                    Spacer()
                    Button(runner.isRunning ? "Running..." : "Run Test") {
                        Task { await runner.run() }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(runner.isRunning)
                }

                // Live stats
                if !runner.ecgSamples.isEmpty {
                    HStack(spacing: 24) {
                        StatBadge(label: "ECG", value: "\(runner.ecgSamples.count)")
                        StatBadge(label: "HR readings", value: "\(runner.hrmReadings.count)")
                        if let lastHR = runner.hrmReadings.last {
                            StatBadge(label: "Last BPM", value: "\(lastHR)")
                        }
                    }
                }

                // ECG Chart (appears after streaming)
                if runner.ecgPlotReady {
                    let chartView = ECGChartView(
                        samplesUv: runner.ecgSamples,
                        sampleRate: 130.0
                    )
                    chartView

                    Button("Export PNG") {
                        exportECG(chartView: chartView)
                    }
                    .buttonStyle(.bordered)
                }

                // Log entries
                LogListView(entries: runner.logger.entries)
            }
            .padding()
        }
    }

    @MainActor
    private func exportECG(chartView: ECGChartView) {
        let timestamp = ISO8601DateFormatter().string(from: Date())
            .replacingOccurrences(of: ":", with: "")
        let filename = "ecg_plot_\(timestamp).png"
        if let url = ChartExporter.exportPNG(chartView, filename: filename) {
            runner.logger.log(.success, "ECG plot saved: \(url.lastPathComponent)")
        } else {
            runner.logger.log(.error, "Failed to export ECG plot")
        }
    }
}

// MARK: - Muse Test View

struct MuseTestView: View {
    @Bindable var runner: MuseTestRunner

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("Muse 2 EEG Integration Test")
                        .font(.title2.bold())
                    Spacer()
                    Button(runner.isRunning ? "Running..." : "Run Test") {
                        Task { await runner.run() }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(runner.isRunning)
                }

                // Live stats
                if !runner.eegChannels.values.allSatisfy({ $0.isEmpty }) {
                    HStack(spacing: 16) {
                        ForEach(["TP9", "AF7", "AF8", "TP10"], id: \.self) { ch in
                            StatBadge(label: ch, value: "\(runner.eegChannels[ch]?.count ?? 0)")
                        }
                    }
                }

                // EEG Chart
                if runner.eegPlotReady {
                    let chartView = EEGChartView(
                        channels: runner.eegChannels,
                        sampleRate: 256.0
                    )
                    chartView

                    Button("Export PNG") {
                        exportEEG(chartView: chartView)
                    }
                    .buttonStyle(.bordered)
                }

                LogListView(entries: runner.logger.entries)
            }
            .padding()
        }
    }

    @MainActor
    private func exportEEG(chartView: EEGChartView) {
        let timestamp = ISO8601DateFormatter().string(from: Date())
            .replacingOccurrences(of: ":", with: "")
        let filename = "eeg_plot_\(timestamp).png"
        if let url = ChartExporter.exportPNG(chartView, filename: filename, size: CGSize(width: 1400, height: 800)) {
            runner.logger.log(.success, "EEG plot saved: \(url.lastPathComponent)")
        } else {
            runner.logger.log(.error, "Failed to export EEG plot")
        }
    }
}

// MARK: - Shared Components

struct StatBadge: View {
    let label: String
    let value: String

    var body: some View {
        VStack {
            Text(value).font(.title3.monospacedDigit().bold())
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }
}

struct LogListView: View {
    let entries: [TestLogger.LogEntry]

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(entries) { entry in
                Text(entry.formatted)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(colorForLevel(entry.level))
            }
        }
    }

    private func colorForLevel(_ level: TestLogger.LogEntry.Level) -> Color {
        switch level {
        case .info: return .primary
        case .success: return .green
        case .error: return .red
        case .data: return .secondary
        }
    }
}
```

- [ ] **Step 2: Create TestAppApp.swift**

```swift
// TestAppApp.swift — Entry point for BLE Integration TestApp

import SwiftUI

@main
struct TestAppApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
```

- [ ] **Step 3: Create Info.plist**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>NSBluetoothAlwaysUsageDescription</key>
    <string>This app uses Bluetooth to connect to BLE devices (Polar H10, Muse 2) for integration testing.</string>
    <key>NSBluetoothPeripheralUsageDescription</key>
    <string>This app uses Bluetooth to connect to BLE devices for integration testing.</string>
</dict>
</plist>
```

- [ ] **Step 4: Commit**

```bash
git add 09_xcode_apple/TestApp/TestApp/ContentView.swift \
       09_xcode_apple/TestApp/TestApp/TestAppApp.swift \
       09_xcode_apple/TestApp/TestApp/Info.plist
git commit -m "feat(testapp): add ContentView, App entry point, and Info.plist"
```

---

## Task 8: Create Xcode Project (Manual Step)

This task requires Xcode and cannot be done from the CLI.

- [ ] **Step 1: Create the Xcode project**

1. Open Xcode
2. File → New → Project → Multiplatform → App
3. Product name: `TestApp`
4. Organization: (your org)
5. Save location: `09_xcode_apple/TestApp/`
6. **Delete** the auto-generated `ContentView.swift` and `TestAppApp.swift` (we already wrote ours)

- [ ] **Step 2: Add BLEReference as local package dependency**

1. In Xcode, File → Add Package Dependencies
2. Click "Add Local..."
3. Navigate to `09_xcode_apple/` (the directory containing `Package.swift`)
4. Add `BLEReference` library to the TestApp target

- [ ] **Step 3: Set deployment targets**

1. Select the TestApp project in the navigator
2. Under each target (iOS, macOS), set:
   - iOS Deployment Target: 16.0
   - macOS Deployment Target: 13.0

- [ ] **Step 4: Add our source files to the target**

If the files aren't already in the project navigator, drag them in from `09_xcode_apple/TestApp/TestApp/`.

- [ ] **Step 5: Build and verify compilation**

Product → Build (⌘B) for both iOS Simulator and macOS targets. Fix any compilation issues.

- [ ] **Step 6: Commit the .xcodeproj**

```bash
git add 09_xcode_apple/TestApp/
git commit -m "feat(testapp): add Xcode project with iOS + macOS targets"
```

---

## Task 9: Real-Device Validation — Polar H10

**Requires:** Polar H10 powered on, worn on chest.

- [ ] **Step 1: Run on macOS**

1. Select the macOS target in Xcode
2. Run (⌘R)
3. Click "Run Test" on the Polar tab
4. Wait 30 seconds for streaming
5. Click "Export PNG"

- [ ] **Step 2: Verify pass criteria**

Check the log output:
- ≥ 3000 ECG samples → "Sample count: PASS"
- std dev > 10 µV → "Signal quality: PASS"
- PNG file exists in `~/Documents/`

- [ ] **Step 3: Run on iOS**

1. Connect iPhone via USB
2. Select the iOS target + your device
3. Run, execute the same test
4. Verify same pass criteria

- [ ] **Step 4: Copy artifacts to repo logs**

```bash
cp ~/Documents/ecg_plot_*.png logs/
cp ~/Documents/polar_integration_*.txt logs/
```

- [ ] **Step 5: Commit artifacts**

```bash
git add logs/ecg_plot_*.png logs/polar_integration_*.txt
git commit -m "test(phase02): Polar H10 ECG integration validated on real device"
```

---

## Task 10: Real-Device Validation — Muse 2

**Requires:** Muse 2 powered on, worn on forehead.

- [ ] **Step 1: Run on macOS**

1. Select macOS target
2. Run, click "Run Test" on the Muse tab
3. Wait 15 seconds
4. Click "Export PNG"

- [ ] **Step 2: Verify pass criteria**

- ≥ 3000 samples per channel → "PASS" for each of TP9, AF7, AF8, TP10
- std dev > 5 µV per channel → "PASS"
- PNG file exists

- [ ] **Step 3: Run on iOS**

Same procedure on iPhone.

- [ ] **Step 4: Copy artifacts and commit**

```bash
cp ~/Documents/eeg_plot_*.png logs/
cp ~/Documents/muse_integration_*.txt logs/
git add logs/eeg_plot_*.png logs/muse_integration_*.txt
git commit -m "test(phase02): Muse 2 EEG integration validated on real device"
```

---

## Task 11: Integration Test Protocol Document

**Files:**
- Create: `docs/integration-test-protocol.md`

- [ ] **Step 1: Create integration-test-protocol.md**

```markdown
# BLE Reference Library — Integration Test Protocol

This document defines the canonical real-device integration test protocol
applied to every phase of the BLE Reference Library.

## Required Devices

- **Polar H10** — chest strap heart rate monitor with ECG capability
- **Muse 2** (or Muse S) — consumer EEG headband, 4 channels

## Polar H10 Protocol

| Parameter | Value |
|-----------|-------|
| Streaming duration | 30 seconds |
| BLE services used | HRM (0x180D) + PMD ECG (proprietary) |
| Expected sample rate | ~130 Hz ECG |
| Minimum samples for pass | 3000 |
| Signal quality threshold | std dev > 10 µV |
| Output artifacts | `ecg_plot_<timestamp>.png` + log file |
| Log must contain | frames, samples, duration, min/max/mean µV, std dev |

## Muse 2 Protocol

| Parameter | Value |
|-----------|-------|
| Streaming duration | 15 seconds |
| BLE services used | Muse GATT (proprietary), 4 EEG channels |
| Expected sample rate | ~256 Hz × 4 channels |
| Minimum samples for pass | 3000 per channel |
| Signal quality threshold | std dev > 5 µV per channel |
| Output artifacts | `eeg_plot_<timestamp>.png` + log file |
| Log must contain | packets, samples/ch, duration, amplitude range, std dev |

## Pass/Fail Criteria

1. Connection succeeds
2. Service discovery finds expected services
3. Sample count above minimum threshold
4. PNG generated with valid signal (std dev above threshold)
5. Clean disconnect (no crash)

## Phase Mapping

Sprint numbers reference `SPRINT_TRACKER.yaml` IDs.

| Phase | Platform | Polar Sprint | Muse Sprint | Plot Technology |
|-------|----------|-------------|-------------|-----------------|
| 01 | Python / Bleak | Sprint 10 | Sprint 11 | matplotlib |
| 02 | Swift / CoreBluetooth | Sprint 28 | Sprint 29 | SwiftUI Charts |
| 03 | JavaScript / Web Bluetooth | Sprint 46 | Sprint 47 | Chart.js |
| 04 | Unity / C# | Sprint 64 | Sprint 65 | Unity UI |
| 05 | C++ / SimpleBLE | Sprint 82 | Sprint 83 | matplotlibcpp |

## Gate Rule

No sprint beyond a phase's Muse sprint may be marked "done" in the
sprint tracker until both integration tests (Polar + Muse) have produced
their artifacts (PNG + log file) on real hardware.

## Artifacts Location

All integration test artifacts are stored in `logs/` at the repo root:
- `logs/ecg_plot_<timestamp>.png`
- `logs/eeg_plot_<timestamp>.png`
- `logs/polar_integration_<timestamp>.txt` (or `.log`)
- `logs/muse_integration_<timestamp>.txt` (or `.log`)
```

- [ ] **Step 2: Commit**

```bash
git add docs/integration-test-protocol.md
git commit -m "docs: add cross-phase integration test protocol"
```

---

## Task 12: Update Sprint Tracker

**Files:**
- Modify: `docs/Sprints/SPRINT_TRACKER.yaml`

- [ ] **Step 1: Add phase_integration_gate to Phase 02**

Add after the Phase 02 definition in the `phases:` section:

```yaml
  - id: phase_02
    name: "Swift / CoreBluetooth (Apple)"
    phase_integration_gate:
      status: pending
      polar_artifacts: null
      muse_artifacts: null
```

- [ ] **Step 2: Update gate status after validation (Task 9-10)**

Once both Polar and Muse tests pass, update to:

```yaml
    phase_integration_gate:
      status: validated
      polar_artifacts: "logs/ecg_plot_<actual_timestamp>.png"
      muse_artifacts: "logs/eeg_plot_<actual_timestamp>.png"
```

- [ ] **Step 3: Commit**

```bash
git add docs/Sprints/SPRINT_TRACKER.yaml
git commit -m "docs(tracker): add Phase 02 integration gate"
```
