# Phase 03 — Web Bluetooth (TypeScript)
## Sprints 37–54

> **Stack:** `08_javascript_web/` — TypeScript library targeting the Web Bluetooth API (browser)
>
> **Note:** Node.js / noble was originally planned as a second sub-stack (`07_javascript_node/`). Descoped — only browser Web Bluetooth was implemented. See `docs/phase03-reference.md` for the authoritative reference.
>
> **Git base branch:** `main`

---

## Global Git Rules

| Rule | Value |
|------|-------|
| Base branch | `develop` |
| Feature branch naming | `feature/phase03-sprint<NN>-<slug>` |
| Commit convention | `feat(ble-js): <description>` / `feat(ble-web): <description>` |
| PR target | `develop` |
| Merge strategy | Squash and merge |
| Tag on phase completion | `phase03-complete` |

---

## Stack Comparison Reference

| Capability | Node.js (noble) | Web Bluetooth |
|-----------|----------------|---------------|
| Passive scan | ✓ | ✗ (requires user gesture + filter) |
| Scan by service UUID | ✓ | ✓ |
| Scan by name | ✓ | ✓ (name prefix only) |
| RSSI during scan | ✓ | ✗ |
| Advertisement data | ✓ | ✗ |
| Connect / disconnect | ✓ | ✓ |
| Auto-reconnect | ✓ | ~ (manual with device cache) |
| GATT read / write | ✓ | ✓ |
| Notifications | ✓ | ✓ |
| Indications | ✓ | ~ (via notify API) |
| Peripheral mode | ✓ (bleno) | ✗ |
| MTU negotiation | ~ | ✗ |
| Bonding / pairing | ~ | ✗ |
| Background execution | ✓ | ✗ |

---

## Sprint 35 — Passive Scan (Node.js only)

### Metadata
- **Files:** `07_javascript_node/01_scan.js`
- **Web Bluetooth:** Not applicable — no passive scan supported
- **Phase 01 reference:** `01_python_bleak/01_scan.py`
- **Dependencies:** none
- **Estimated complexity:** low-medium

### Objective
Implement passive BLE scan in Node.js using noble, discovering all advertising devices.

### Deliverables
| File | Description |
|------|-------------|
| `07_javascript_node/01_scan.js` | Passive scan |
| `07_javascript_node/test_01_scan.test.js` | Jest unit tests |
| `08_javascript_web/01_scan.js` | Stub documenting limitation |

### Expected API (Node.js)
```javascript
// Returns Promise<Array<{address, name, rssi, advertisement}>>
async function scan(durationMs = 5000)

// Example usage
const devices = await scan(5000);
devices.forEach(d => console.log(d.address, d.name, d.rssi));
```

### Technical Constraints
- noble emits `discover` events; collect for `durationMs` then call `noble.stopScanning()`
- Must wait for `noble.state === 'poweredOn'` before scanning (event: `stateChange`)
- Linux: requires root or `CAP_NET_ADMIN` — document in README
- macOS: Bluetooth permission dialog on first run

### Web Bluetooth stub (`01_scan.js`)
```javascript
// Web Bluetooth does not support passive scan.
// Use 02_scan_filtered.js with a service UUID filter instead.
// Reference: https://webbluetoothcg.github.io/web-bluetooth/#dom-bluetooth-requestdevice
export function scan() {
  throw new Error('Passive scan not supported in Web Bluetooth. Use requestDevice() with filters.');
}
```

### Unit Tests (Jest, Node.js)
```javascript
test('scan returns array of devices')
test('scan respects duration')
test('scan handles empty result')
test('scan defers until poweredOn')
```

### Acceptance Criteria
- [ ] Noble scan collects devices for the specified duration
- [ ] Web Bluetooth stub explains limitation with spec link
- [ ] All 4 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase03-sprint35-passive-scan
git commit -m "feat(ble-js): passive scan with noble; stub for Web Bluetooth limitation"
```

---

## Sprint 36 — Filtered Scan

### Metadata
- **Files:** `07_javascript_node/02_scan_filtered.js`, `08_javascript_web/02_scan_filtered.js`
- **Phase 01 reference:** `01_python_bleak/02_scan_filtered.py`
- **Dependencies:** Sprint 35
- **Estimated complexity:** low-medium

### Deliverables
Both files implement the same interface where possible.

### Expected API
```javascript
// Node.js (noble)
async function scanByName(name, durationMs = 5000)
async function scanByServiceUUID(uuid, durationMs = 5000)
async function scanByRSSI(minRSSI = -80, durationMs = 5000)
async function scanWithAdvertisementData(durationMs = 5000)

// Web Bluetooth (requestDevice)
async function requestDeviceByName(namePrefix)
async function requestDeviceByService(serviceUUID)
// RSSI and advertisement data: NOT AVAILABLE — stub with explanation
```

### Technical Constraints (Web Bluetooth)
- `navigator.bluetooth.requestDevice()` must be called in a user gesture handler (button click)
- Filters are required: `{ filters: [{ services: [uuid] }] }` or `{ filters: [{ namePrefix: '...' }] }`
- `acceptAllDevices: true` only works when no filters specified
- No RSSI, no advertisement data — document clearly

### Unit Tests
```javascript
// Node.js
test('filterByName matches substring case-insensitive')
test('filterByServiceUUID returns matching devices')
test('filterByRSSI excludes below threshold')
// Web Bluetooth (mock navigator.bluetooth)
test('requestDeviceByName calls requestDevice with namePrefix filter')
test('requestDeviceByService calls requestDevice with services filter')
```

### Acceptance Criteria
- [ ] Web Bluetooth filter options documented with MDN references
- [ ] All 5 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase03-sprint36-filtered-scan
git commit -m "feat(ble-js): filtered scan for noble and Web Bluetooth"
```

---

## Sprint 37 — Connection, Disconnection, Reconnection

### Metadata
- **Files:** `07_javascript_node/03_connection.js`, `08_javascript_web/03_connection.js`
- **Phase 01 reference:** `01_python_bleak/03_connection.py`
- **Dependencies:** Sprint 36
- **Estimated complexity:** medium

### Expected API
```javascript
// Shared interface (both stacks)
class BLEConnectionManager {
  async connect(deviceOrAddress, timeoutMs = 10000)
  async disconnect()
  async connectWithRetry(deviceOrAddress, { maxAttempts = 5, backoffBase = 2000 } = {})
  onDisconnect(callback)    // callback(device)
}
```

### Technical Constraints (Node.js)
- noble: `peripheral.connect(callback)` — wrap in Promise
- Disconnect event: `peripheral.on('disconnect', ...)`
- Reconnect: re-scan for address then reconnect

### Technical Constraints (Web Bluetooth)
- `device.gatt.connect()` / `device.gatt.disconnect()`
- `device.addEventListener('gattserverdisconnected', ...)`
- Reconnect: call `device.gatt.connect()` again on same cached device object
- No timeout API — implement with `Promise.race` + `setTimeout`

### Unit Tests
```javascript
// Node.js
test('connect resolves on success')
test('connect rejects on timeout')
test('reconnect retries maxAttempts times')
test('disconnect suppresses reconnect callback')
// Web Bluetooth
test('connect calls device.gatt.connect()')
test('disconnect calls device.gatt.disconnect()')
test('reconnect uses cached device object')
```

### Acceptance Criteria
- [ ] `_intentionalDisconnect` flag pattern consistent with Python/Swift reference
- [ ] All 7 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase03-sprint37-connection
git commit -m "feat(ble-js): BLE connection lifecycle for noble and Web Bluetooth"
```

---

## Sprint 38 — Services & Characteristics Discovery

### Metadata
- **Files:** `07_javascript_node/04_services_characteristics.js`, `08_javascript_web/04_services_characteristics.js`
- **Phase 01 reference:** `01_python_bleak/04_services_characteristics.py`
- **Dependencies:** Sprint 37
- **Estimated complexity:** low-medium

### Expected API
```javascript
async function listServices(peripheral)          // noble: peripheral object
async function listServices(device)              // web: BluetoothDevice object
async function listCharacteristics(service)
function characteristicProperties(char)          // returns { read, write, writeWithoutResponse, notify, indicate }
function printServiceTree(peripheral)
```

### Technical Constraints (Node.js noble)
- `peripheral.discoverServices([], callback)` then `service.discoverCharacteristics([], callback)`
- Properties accessed via `characteristic.properties` array of strings

### Technical Constraints (Web Bluetooth)
- `await server.getPrimaryServices()` — returns array of BluetoothRemoteGATTService
- `await service.getCharacteristics()` — returns array of BluetoothRemoteGATTCharacteristic
- Properties via `characteristic.properties` (BluetoothCharacteristicProperties object)

### Unit Tests
```javascript
test('listServices returns all services')
test('listCharacteristics returns all for service')
test('characteristicProperties has all 5 keys')
test('notifyChar has notify=true')
```

### Acceptance Criteria
- [ ] All 4 unit tests pass for each stack (8 total)

### Git Workflow
```bash
git checkout -b feature/phase03-sprint38-services-discovery
git commit -m "feat(ble-js): GATT services and characteristics discovery"
```

---

## Sprint 39 — Read & Write

### Metadata
- **Files:** `07_javascript_node/05_read_write.js`, `08_javascript_web/05_read_write.js`
- **Phase 01 reference:** `01_python_bleak/05_read_write.py`
- **Dependencies:** Sprint 38
- **Estimated complexity:** low-medium

### Expected API
```javascript
async function readCharacteristic(char)          // returns Buffer (node) or DataView (web)
async function writeCharacteristic(char, data, withResponse = true)
async function readAsInt(char, byteOrder = 'little')
async function readAsString(char, encoding = 'utf-8')
async function writeInt(char, value, byteLength = 1, byteOrder = 'little', withResponse = true)
```

### Technical Constraints (Node.js)
- noble: `characteristic.read(callback)` → Buffer
- `characteristic.write(buffer, withoutResponse, callback)`

### Technical Constraints (Web Bluetooth)
- `await char.readValue()` → DataView
- `await char.writeValue(buffer)` (with response) or `await char.writeValueWithoutResponse(buffer)`
- `DataView` helper: `new DataView(buffer.buffer)`

### Unit Tests
```javascript
// 5 tests per stack = 10 total
test('readCharacteristic returns data')
test('readAsInt littleEndian')
test('readAsInt bigEndian')
test('writeWithResponse')
test('writeWithoutResponse')
```

### Acceptance Criteria
- [ ] All 10 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase03-sprint39-read-write
git commit -m "feat(ble-js): GATT read and write for noble and Web Bluetooth"
```

---

## Sprint 40 — Notifications, Indications & Subscription Management

### Metadata
- **Files:** `07_javascript_node/06_notifications.js`, `08_javascript_web/06_notifications.js`
- **Phase 01 reference:** `01_python_bleak/06_notifications.py`
- **Dependencies:** Sprint 39
- **Estimated complexity:** medium

### Expected API
```javascript
class NotificationManager {
  async subscribe(char, callback)         // callback(Buffer | DataView)
  async unsubscribe(char)
  async unsubscribeAll()
  isSubscribed(charUUID)
}
```

### Technical Constraints (Node.js noble)
- `characteristic.subscribe(callback)` / `characteristic.unsubscribe(callback)`
- `characteristic.on('data', handler)`

### Technical Constraints (Web Bluetooth)
- `await char.startNotifications()`
- `char.addEventListener('characteristicvaluechanged', handler)`
- `await char.stopNotifications()`
- `char.removeEventListener(...)`
- Indication vs notification: same API, distinction is in characteristic properties — document this

### Unit Tests
```javascript
// Node.js
test('subscribe calls noble subscribe')
test('callback invoked on data')
test('isSubscribed true after subscribe')
test('unsubscribeAll clears registry')
// Web Bluetooth
test('subscribe calls startNotifications')
test('handler invoked on characteristicvaluechanged')
test('unsubscribe calls stopNotifications')
```

### Acceptance Criteria
- [ ] All 7 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase03-sprint40-notifications
git commit -m "feat(ble-js): notification subscription manager for noble and Web Bluetooth"
```

---

## Sprint 41 — Scan Without Connection (Node.js only)

### Metadata
- **Files:** `07_javascript_node/07_scan_without_connect.js`, `08_javascript_web/07_scan_without_connect.js` (stub)
- **Phase 01 reference:** `01_python_bleak/07_scan_without_connect.py`
- **Dependencies:** Sprint 35
- **Estimated complexity:** low

### Expected API (Node.js)
```javascript
async function scanAdvertisementData(durationMs = 5000)
// Returns: [{ address, name, manufacturerData, serviceData, txPowerLevel, serviceUUIDs }]

function parseManufacturerData(advertisement)    // returns { companyId: Buffer }
function parseServiceData(advertisement)         // returns { uuid: Buffer }
function parseTxPower(advertisement)             // returns number | null
```

### Technical Constraints
- noble `advertisement` object: `localName`, `manufacturerData` (Buffer), `serviceData` (array of {uuid, data}), `txPowerLevel`
- Web Bluetooth: not supported — stub with clear explanation and spec reference

### Unit Tests
```javascript
test('parseManufacturerData returns object')
test('parseTxPower returns number')
test('missingFieldsReturnNull')
```

### Acceptance Criteria
- [ ] All 3 unit tests pass (Node.js)
- [ ] Web Bluetooth stub references Web Bluetooth spec limitation

### Git Workflow
```bash
git checkout -b feature/phase03-sprint41-adv-data
git commit -m "feat(ble-js): advertisement data parsing (noble); stub for Web Bluetooth"
```

---

## Sprint 42 — Battery Level

### Metadata
- **Files:** `07_javascript_node/08_battery.js`, `08_javascript_web/08_battery.js`
- **Phase 01 reference:** `01_python_bleak/08_battery.py`
- **Dependencies:** Sprint 39
- **Estimated complexity:** low

### Expected API
```javascript
const BATTERY_SERVICE_UUID = '0000180f-0000-1000-8000-00805f9b34fb';
const BATTERY_CHAR_UUID    = '00002a19-0000-1000-8000-00805f9b34fb';

async function readBatteryLevel(peripheral)           // returns number | null
async function subscribeBatteryNotifications(peripheral, callback)  // callback(number)
```

### Unit Tests
```javascript
test('readBatteryReturnsInt')
test('batteryServiceNotFoundReturnsNull')
test('batteryNotificationCallbackFired')
```

### Acceptance Criteria
- [ ] All 3 unit tests pass for each stack (6 total)

### Git Workflow
```bash
git checkout -b feature/phase03-sprint42-battery
git commit -m "feat(ble-js): battery level read and notification for noble and Web Bluetooth"
```

---

## Sprint 43 — Heart Rate Monitor Example

### Metadata
- **Files:** `07_javascript_node/09_heartrate_example.js`, `08_javascript_web/09_heartrate_example.js`
- **Phase 01 reference:** `01_python_bleak/09_heartrate_example.py`
- **Dependencies:** Sprints 40, 42
- **Estimated complexity:** medium

### Expected API
```javascript
function parseHeartRateMeasurement(dataView)
// Returns: { bpm: number, rrIntervals: number[], sensorContact: boolean | null }

// Node.js
async function runHRMExample(address)
// Web Bluetooth
async function runHRMExampleWeb()   // calls requestDevice internally
```

### Unit Tests
```javascript
test('parseHRMUint8BPM')
test('parseHRMUint16BPM')
test('parseHRMWithRRIntervals')
test('rrIntervalConversionToSeconds')
test('sensorContactFlag')
```

### Acceptance Criteria
- [ ] Parser identical logic across both stacks
- [ ] All 5 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase03-sprint43-heartrate
git commit -m "feat(ble-js): heart rate monitor example for noble and Web Bluetooth"
```

---

## Sprint 44 — Muse EEG Example

### Metadata
- **Files:** `07_javascript_node/10_muse_example.js`, `08_javascript_web/10_muse_example.js`
- **Phase 01 reference:** `01_python_bleak/10_muse_example.py`
- **Dependencies:** Sprint 40
- **Estimated complexity:** high

### Objective
JavaScript port of the Muse EEG parser. Uses DataView / TypedArray for uint:12 bit unpacking.

### Expected API
```javascript
const MUSE_EEG_SCALE_FACTOR = 0.48828125;
const MUSE_EEG_SAMPLES_PER_PACKET = 12;

// Pure function — same logic for both stacks
function parseEEGPacket(dataView) 
// Returns: { samples: Float64Array(12), unit: 'µV' }

function parseTelemetryPacket(dataView)
// Returns: { batteryPercent: number, fuelGauge: number, adcVoltage: number, temperature: number }

// Node.js
class MuseStreamer {
  constructor(address, { onEEG, onBattery })
  async start()
  async stop()
}

// Web Bluetooth
class MuseStreamerWeb {
  constructor({ onEEG, onBattery })
  async start()   // calls requestDevice internally
  async stop()
}
```

### Technical Constraints (uint:12 in JavaScript)
```javascript
function parseEEGPacket(dataView) {
  // Skip 2-byte counter (uint16)
  // Then extract 12 × uint12 from remaining bytes
  const samples = new Float64Array(12);
  for (let i = 0; i < 12; i++) {
    const bitOffset = 16 + i * 12;
    const byteIndex = Math.floor(bitOffset / 8);
    const bitShift = bitOffset % 8;
    // Read 2 bytes, shift, mask to 12 bits
    const raw = ((dataView.getUint8(byteIndex) << 8) | dataView.getUint8(byteIndex + 1));
    const value12 = (raw >> (4 - bitShift)) & 0xFFF;
    samples[i] = MUSE_EEG_SCALE_FACTOR * (value12 - 2048);
  }
  return { samples };
}
```

### Web Bluetooth note
- Web Bluetooth is viable for Muse on Chrome desktop in the foreground
- High-frequency notifications (256 Hz × 4 channels) may be throttled on some OS/browser combinations
- Document: test with `chrome://bluetooth-internals` to monitor notification rate

### Unit Tests
```javascript
test('parseEEGPacketReturns12Samples')
test('parseEEGScaleFactor')             // known bytes → expected µV
test('parseEEGCounterDiscarded')
test('parseTelemetryBatteryPercent')
test('parseTelemetryAllFields')
test('eegZeroRawValue')                 // raw=2048 → 0.0
```

### Risks & Mitigations
| Risk | Mitigation |
|------|-----------|
| Bit-packing edge cases in JS | Validate against Python reference with identical input bytes |

### Acceptance Criteria
- [ ] JS parser output matches Python reference for identical input bytes
- [ ] All 6 unit tests pass
- [ ] Web Bluetooth version includes notification throttling warning in comments

### Git Workflow
```bash
git checkout -b feature/phase03-sprint44-muse
git commit -m "feat(ble-js): Muse S EEG parser with DataView uint:12 unpacking"
```

---

## Sprint 45 — Error Handling & Timeouts

### Metadata
- **Files:** `07_javascript_node/11_error_handling.js`, `08_javascript_web/11_error_handling.js`
- **Phase 01 reference:** `01_python_bleak/11_error_handling.py`
- **Dependencies:** Sprint 37
- **Estimated complexity:** low-medium

### Expected API
```javascript
class BLEError extends Error {}
class BLEConnectionError extends BLEError {}
class BLETimeoutError extends BLEError {}
class BLEServiceNotFoundError extends BLEError {}

async function withTimeout(promise, timeoutMs, errorMessage = 'BLE operation timed out')
function bleRetry(fn, { maxAttempts = 3, delayMs = 1000, retryOn = [BLEError] } = {})
```

### Unit Tests
```javascript
test('withTimeoutCompletesBeforeDeadline')
test('withTimeoutThrowsBLETimeoutError')
test('bleRetrySucceedsOnSecondAttempt')
test('bleRetryThrowsAfterMaxAttempts')
test('bleRetryDoesNotRetryUnrelatedError')
```

### Acceptance Criteria
- [ ] All 5 unit tests pass for each stack (10 total)

### Git Workflow
```bash
git checkout -b feature/phase03-sprint45-errors
git commit -m "feat(ble-js): BLE error hierarchy, timeout, retry for noble and Web Bluetooth"
```

---

## Sprint 46 — MTU Negotiation

### Metadata
- **Files:** `07_javascript_node/12_mtu.js`, `08_javascript_web/12_mtu.js` (stub)
- **Phase 01 reference:** `01_python_bleak/12_mtu_negotiation.py`
- **Dependencies:** Sprint 37
- **Estimated complexity:** low

### Technical Constraints (Node.js noble)
- noble does not expose explicit MTU negotiation API
- MTU is negotiated automatically by the OS
- Provide `getEffectivePayloadSize()` as utility; document OS-controlled MTU

### Technical Constraints (Web Bluetooth)
- No MTU API in Web Bluetooth spec
- Web Bluetooth automatically fragments writes > negotiated MTU
- Stub with explanation

### Unit Tests
```javascript
test('effectivePayloadSize(247) === 244')
test('effectivePayloadSize(23) === 20')
```

### Acceptance Criteria
- [ ] Both stacks documented with platform behavior
- [ ] Both 2 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase03-sprint46-mtu
git commit -m "feat(ble-js): MTU payload size utility; document OS-controlled negotiation"
```

---

## Sprint 47 — Bonding & Pairing

### Metadata
- **Files:** `07_javascript_node/13_bonding_pairing.js`, `08_javascript_web/13_bonding_pairing.js` (stub)
- **Phase 01 reference:** `01_python_bleak/13_bonding_pairing.py`
- **Dependencies:** Sprint 37
- **Estimated complexity:** low-medium

### Technical Constraints (Node.js noble)
- noble does not provide explicit pairing API
- Pairing triggered implicitly by OS when connecting to a protected characteristic
- Document how to check paired status via OS tools per platform

### Technical Constraints (Web Bluetooth)
- No pairing API in Web Bluetooth spec
- Browser handles pairing implicitly
- Stub with clear explanation

### Unit Tests
```javascript
// Node.js only — minimal
test('pairingDocumentationExists')    // smoke test that module exports expected functions
```

### Acceptance Criteria
- [ ] Docstring explains implicit pairing and OS delegation for both stacks

### Git Workflow
```bash
git checkout -b feature/phase03-sprint47-pairing
git commit -m "feat(ble-js): bonding and pairing documentation; OS delegation notes"
```

---

## Sprint 48 — Peripheral Mode (Node.js only)

### Metadata
- **Files:** `07_javascript_node/14_peripheral_mode.js`, `08_javascript_web/14_peripheral_mode.js` (stub)
- **Phase 01 reference:** `01_python_bleak/14_peripheral_mode.py`
- **Dependencies:** Sprint 37
- **Estimated complexity:** medium

### Technical Constraints (Node.js)
- Requires `bleno` library (`npm install bleno`) — separate from noble
- Linux: same BlueZ/D-Bus requirements as noble
- macOS: `CoreBluetooth` via bleno
- Windows: limited support

### Expected API
```javascript
const bleno = require('bleno');

async function createPeripheralServer({ serviceUUID, charUUID, localName })
async function startAdvertising(server)
async function stopAdvertising(server)
```

### Unit Tests
```javascript
test('serverCreated')
test('advertisingStarts')
test('advertisingStops')
```

### Acceptance Criteria
- [ ] Web Bluetooth stub explains specification limitation with link
- [ ] All 3 unit tests pass (mocked bleno)

### Git Workflow
```bash
git checkout -b feature/phase03-sprint48-peripheral
git commit -m "feat(ble-js): peripheral mode with bleno (Node.js); stub for Web Bluetooth"
```

---

## Sprint 49 — RSSI & Distance Estimation

### Metadata
- **Files:** `07_javascript_node/15_rssi_distance.js`, `08_javascript_web/15_rssi_distance.js`
- **Phase 01 reference:** `01_python_bleak/15_rssi_distance.py`
- **Dependencies:** Sprint 35
- **Estimated complexity:** low

### Expected API
```javascript
const MEASURED_POWER_DEFAULT = -59;
const PATH_LOSS_EXPONENT = 2.0;

function estimateDistance(rssi, measuredPower = MEASURED_POWER_DEFAULT, n = PATH_LOSS_EXPONENT)
// Node.js only: RSSI available during scan via noble
// Web Bluetooth: RSSI not available during scan; partial support post-connection via watchAdvertisements()
```

### Unit Tests
```javascript
test('distanceAt1Meter')       // rssi === measuredPower → 1.0
test('distanceFarther')
test('distanceFormula')
```

### Acceptance Criteria
- [ ] Formula matches Python reference
- [ ] All 3 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase03-sprint49-rssi
git commit -m "feat(ble-js): RSSI and distance estimation"
```

---

## Sprint 50 — Command Queue

### Metadata
- **Files:** `07_javascript_node/16_command_queue.js`, `08_javascript_web/16_command_queue.js`
- **Phase 01 reference:** `01_python_bleak/16_command_queue.py`
- **Dependencies:** Sprint 39
- **Estimated complexity:** medium

### Expected API
```javascript
class BLECommandQueue {
  enqueue(asyncFn)      // returns Promise resolved when command completes
  start()
  stop()
  get size()
  get isRunning()
}
```

### Technical Constraints
- Implemented with an internal `Promise` chain (queue as linked list of `.then()`)
- Or with an explicit array queue + processing loop using `async/await`
- Same interface for both stacks (pure JS, no BLE dependency)

### Unit Tests
```javascript
test('commandsExecuteSerially')
test('enqueueReturnsResult')
test('queueSizeIncrements')
test('exceptionInCommandPropagated')
test('stopPreventsFurtherExecution')
```

### Acceptance Criteria
- [ ] All 5 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase03-sprint50-command-queue
git commit -m "feat(ble-js): serial BLE command queue"
```

---

## Sprint 51 — OTA / DFU

### Metadata
- **Files:** `07_javascript_node/17_ota_dfu.js`, `08_javascript_web/17_ota_dfu.js`
- **Phase 01 reference:** `01_python_bleak/17_ota_dfu.py`
- **Dependencies:** Sprints 39, 45, 50
- **Estimated complexity:** high

### Expected API
```javascript
async function writeChunked(char, data, { chunkSize = 244, onProgress } = {})
// data: Buffer (node) or ArrayBuffer (web)
// onProgress: ({ totalBytes, bytesSent, percent }) => void

async function nrfDFUStart(peripheral, firmwareBuffer, { onProgress } = {})
```

### Unit Tests
```javascript
test('writeChunkedCorrectChunkCount')     // 500 bytes, chunk=244 → 3 writes
test('lastChunkSmallerThanChunkSize')
test('progressCallbackFiredPerChunk')
test('progressReaches100AtEnd')
```

### Acceptance Criteria
- [ ] Chunking logic matches Python reference
- [ ] All 4 unit tests pass for each stack (8 total)

### Git Workflow
```bash
git checkout -b feature/phase03-sprint51-dfu
git commit -m "feat(ble-js): chunked OTA write and nRF DFU for noble and Web Bluetooth"
```

---

## Phase 03 Completion Checklist

- [ ] All 34 files exist (17 × Node.js + 17 × Web Bluetooth, stubs counted)
- [ ] All test files pass (`npm test` in each folder)
- [ ] `package.json` in each folder lists: `@abandonware/noble`, `bleno` (node), Jest
- [ ] Web Bluetooth `README.md` includes browser compatibility table
- [ ] Muse JS parser validated against Python reference output
- [ ] PR from `develop` → `main` created with label `phase03`
- [ ] Tag `phase03-complete` applied on merge

```bash
git checkout main
git merge develop --no-ff -m "feat: Phase 03 complete — JavaScript Node.js and Web Bluetooth BLE reference library"
git tag phase03-complete
git push origin main --tags
```
