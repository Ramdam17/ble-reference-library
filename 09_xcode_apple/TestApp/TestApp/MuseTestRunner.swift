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
            connector = BLEConnector(centralManager: scanner!.centralManager)
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
                ) { [weak self, channelName] data in
                    if let packet = MuseEegParser.parse(data) {
                        Task { @MainActor in
                            self?.eegChannels[channelName]?.append(contentsOf: packet.samplesUv)
                        }
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
