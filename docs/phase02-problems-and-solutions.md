# Phase 02 — Swift / CoreBluetooth — Problems Encountered & Solutions

> Documenting every real issue hit during development and real-device testing on Polar H10 and Muse 2, with root causes, debugging process, and fixes.

---

## 1. CBPeripheral ownership — connection timeout

**Symptom:** Muse 2 found during scan, but `BLEConnector.connect()` timed out after 10 seconds. No error, no `didFailToConnect` callback — just silence.

**Debugging:** The Muse appeared in scan results (RSSI -70), so Bluetooth was working. The `connect()` call executed without error, but `didConnect` never fired. Comparing with the Python implementation revealed the pattern — Bleak manages CBCentralManager internally and ensures the same instance is used throughout.

**Root cause:** `BLEScanner` creates its own `CBCentralManager`. `BLEConnector` also creates its own. In CoreBluetooth, **a CBPeripheral is bound to the CBCentralManager that discovered it**. When you pass a peripheral from scanner's CM to connector's CM, `connect()` silently fails — CoreBluetooth doesn't report an error, it just never connects.

**Fix:**
1. Made `BLEScanner.centralManager` public so it can be shared
2. Added `BLEConnector.init(centralManager:queue:)` accepting an existing CM
3. Updated test runners: `BLEConnector(centralManager: scanner!.centralManager)`

**Commits:** `e091a9e`, `f495c61`

**Lesson:** In CoreBluetooth, always use the same CBCentralManager instance for the entire lifecycle of a peripheral (scan → connect → interact → disconnect). This is fundamentally different from Bleak, which handles this internally.

---

## 2. isPoweredOn not synced — connect() hangs forever

**Symptom:** After fixing the CBCentralManager sharing (Problem 1), `connect()` still hung — but now silently, without even a timeout.

**Debugging:** Added logging to `waitForPowerOn()` — it was blocking indefinitely. The `centralManagerDidUpdateState` callback was never called on the connector.

**Root cause:** When `BLEConnector` reuses an existing CBCentralManager that's already `.poweredOn`, the `centralManagerDidUpdateState` delegate callback **does not fire again** (the state hasn't changed). But the connector's `isPoweredOn` flag was initialized to `false`, so `waitForPowerOn()` waited for a callback that would never come.

**Fix:** In the new `init(centralManager:)`, sync the power state from the CM's current state:
```swift
self.isPoweredOn = centralManager.state == .poweredOn
```

**Commit:** `f495c61`

**Lesson:** When reusing a CoreBluetooth object, always read its current state rather than assuming the delegate will be called to inform you.

---

## 3. peripheral.delegate theft — 0 ECG samples, Step 09 frozen

**Symptom:** Polar H10 connected and HRM subscription worked (1 HR reading), but after 30 seconds of streaming: 0 ECG samples. Step 09 (stop + disconnect) hung indefinitely.

**Debugging:** The log showed exactly 1 HR reading, received right after subscription but before ECG start. After the `readWriter.write(kPolarEcgStartCommand, ...)` call, no more notifications were received. This pointed to a delegate issue.

**Root cause:** `CBPeripheral` has a single `delegate` property. The sequence:
1. `subscriber.subscribe(to: hrmChar)` → sets `peripheral.delegate = subscriber` ✓
2. `subscriber.subscribe(to: pmdDataChar)` → still subscriber ✓
3. `readWriter.write(ecgStartCmd)` → sets `peripheral.delegate = readWriter` ✗

After step 3, all `didUpdateValueFor` callbacks (notifications) went to `readWriter` instead of `subscriber`. The subscriber never received ECG or HRM data again. In Step 09, `subscriber.unsubscribe()` waited for `didUpdateNotificationStateFor` which was routed to `readWriter` — deadlock.

**Fix:** Explicitly restore the delegate after each write:
```swift
try await readWriter!.write(startCmd, to: pmdControlChar, on: peripheral)
cbPeripheral.delegate = subscriber  // Restore
```

**Commit:** `b31a60f`

**Lesson:** CoreBluetooth's single-delegate pattern means any class that sets `peripheral.delegate = self` steals callbacks from all other classes. In a multi-component architecture, you must coordinate delegate ownership. The Muse test didn't hit this because it uses `writeWithoutResponse` (no delegate callback needed).

---

## 4. BLEDeviceInfo.read() returns all nil — synchronous reads on empty cache

**Symptom:** Polar H10 DIS service was discovered (7 characteristics), but `BLEDeviceInfo.read()` returned `nil` for all fields (manufacturer, model, serial, firmware).

**Debugging:** The service was present (`peripheral.services` contained 0x180A), and characteristics were discovered. But `char.value` was `nil` for every DIS characteristic.

**Root cause:** CoreBluetooth does **not** pre-populate `characteristic.value` during service/characteristic discovery. The discovery phase only catalogs what exists — it does not read values. You must explicitly call `peripheral.readValue(for:)` to populate the value. The original `BLEDeviceInfo.read()` was synchronous and simply read the (empty) cache.

This is a fundamental difference from Bleak, where `await client.read_gatt_char(uuid)` performs an actual network read.

**Fix:** Changed `BLEDeviceInfo.read(on:)` to `BLEDeviceInfo.read(on:using:)` — async, accepts a `BLEReadWriter` to perform real reads. Same fix applied to `BLEBattery.readLevel()`.

**Commit:** `fa2ef48`

**Lesson:** In CoreBluetooth, `characteristic.value` is always `nil` until explicitly read. Any function that needs characteristic data must be async and perform actual reads. Never trust the cache after discovery.

---

## 5. BLEBattery.readLevel() returns nil — same root cause as #4

**Symptom:** Battery service discovered on Polar H10 but `readLevel()` returned `nil`.

**Root cause:** Same as Problem 4 — `char.value` was `nil` because no `readValue()` was performed.

**Fix:** Made `BLEBattery.readLevel(on:using:)` async, accepting `BLEReadWriter`. After fix: Battery: 100%.

**Commit:** `fa2ef48` (same commit as Problem 4)

---

## 6. Swift concurrency — @Sendable closure mutation errors

**Symptom:** Xcode warnings: "Main actor-isolated property cannot be mutated from a Sendable closure."

**Root cause:** The test runners use `@Observable` (which makes the class MainActor-isolated), but BLE notification callbacks are `@Sendable` closures that execute on the BLE queue (not MainActor). Directly mutating `self.ecgSamples` from the callback violates Swift's concurrency safety.

**Fix:** Wrap mutations in `Task { @MainActor in }`:
```swift
subscriber.subscribe(to: char, on: peripheral) { [weak self] data in
    if let frame = PolarEcgParser.parse(data) {
        Task { @MainActor in
            self?.ecgSamples.append(contentsOf: frame.samplesUv)
        }
    }
}
```

**Commit:** `6e17820`

---

## 7. Swift 6 capture error — loop variable in @Sendable closure

**Symptom:** Xcode error: "Reference to captured var 'self' in concurrently-executing code; this is an error in the Swift 6 language mode." In `MuseTestRunner`, the `channelName` variable from a `for` loop was captured by reference.

**Root cause:** In Swift 6 strict concurrency, `@Sendable` closures cannot capture mutable variables by reference. Loop variables are mutable (they change each iteration), so they can't be implicitly captured.

**Fix:** Explicit capture list:
```swift
for (idx, channelUUID) in kMuseEegChannelUUIDs.enumerated() {
    let channelName = kMuseChannelNames[idx]
    subscriber.subscribe(...) { [weak self, channelName] data in
        // channelName is now captured by value
    }
}
```

**Commit:** `32ae57c`

---

## 8. Polar H10 not found on Mac — macOS BLE scanning limitations

**Symptom:** `BLEScanner.scan()` on Mac found zero Polar H10 devices, even though the iPhone found it immediately with the same app.

**Debugging:** Checked System Preferences → Bluetooth → Nearby Devices — Polar H10 was not listed there either. This isn't a code bug — Mac's BLE stack is more selective about what it surfaces.

**Root cause:** macOS Bluetooth stack doesn't always discover all BLE peripherals that iOS can see, especially devices that use specific advertisement modes or low TX power. Scanning with specific `serviceUUIDs` helps macOS focus its scanning.

**Fix:** Added service UUID filter to Polar scan:
```swift
scanner.scan(duration: 10.0, serviceUUIDs: [kHeartRateServiceUUID, kPolarPMDServiceUUID])
```

After this fix, Mac found the Polar H10 reliably.

**Lesson:** On macOS, always scan with `serviceUUIDs` when you know what services you need. Unfiltered scanning (`nil`) works reliably on iOS but not always on Mac.

---

## 9. Duplicate Info.plist — build failure on iOS/iPadOS

**Symptom:** Building for iPad produced: "Multiple commands produce Info.plist."

**Root cause:** We created a standalone `Info.plist` file for `NSBluetoothAlwaysUsageDescription`. But Xcode also generates its own `Info.plist` from the target's Info tab settings. Having both caused a conflict.

**Fix:** Deleted the standalone `Info.plist` file. The Bluetooth permission key is set via Xcode target → Info tab, which is the correct approach.

**Commit:** `5248f64`

**Lesson:** In modern Xcode projects, don't create standalone `Info.plist` files — use the target's Info tab. Xcode generates the plist at build time from those settings.

---

## 10. ECG/EEG chart scale crushed by startup drift

**Symptom:** ECG and EEG PNG plots showed a compressed signal — waveform barely visible because the Y axis was stretched by outlier values at the start.

**Root cause:** Both Polar H10 PMD and Muse 2 produce large transient values during the first ~2 seconds of streaming (sensor stabilization / DC offset settling). These outliers dominated the chart's auto-scaled Y range.

**Fix:** Skip the first 2 seconds of samples and clamp amplitude:
- ECG: skip 2s, clamp to ±1500 µV (typical QRS range)
- EEG: skip 2s, clamp to ±200 µV per channel

**Commits:** `810c259`, `631af8f`

**Lesson:** BLE sensor data often has startup transients. Always trim or handle the initial settling period when plotting.

---

## Summary of Architectural Lessons

| # | Lesson | CoreBluetooth-specific? |
|---|--------|------------------------|
| 1 | CBPeripheral is bound to its discovering CBCentralManager | Yes |
| 2 | Delegate callbacks don't re-fire for already-current state | Yes |
| 3 | Single `peripheral.delegate` — coordinate ownership | Yes |
| 4-5 | `characteristic.value` is never pre-populated | Yes |
| 6-7 | `@Observable` + `@Sendable` require MainActor dispatch | Swift concurrency |
| 8 | macOS BLE scanning is more selective than iOS | Apple platform |
| 9 | Xcode generates Info.plist — don't duplicate | Xcode |
| 10 | Sensor startup drift — trim first 2s | BLE sensors general |
