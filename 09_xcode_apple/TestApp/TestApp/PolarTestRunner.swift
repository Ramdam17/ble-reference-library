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
