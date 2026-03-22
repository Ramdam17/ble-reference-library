# Phase 03 — Web Bluetooth (TypeScript) — Agent Reference

> For AI agents working on or extending this codebase. Describes what exists, how it fits together, and the critical Web Bluetooth API patterns to understand.

## Overview

Phase 03 implements the BLE Reference Library as a **framework-agnostic TypeScript library** targeting the Web Bluetooth API (browser-native BLE). It covers 18 sprints (Sprints 37–54) mirroring Phase 01/02's module structure. A static HTML TestApp validates parsers against real Polar H10 and Muse 2 hardware in Chrome.

**Library source:** `08_javascript_web/src/` (TypeScript)
**Tests:** `08_javascript_web/tests/` (Jest + ts-jest)
**TestApp:** `08_javascript_web/TestApp/` (static HTML, served via `npx serve`)
**Build output:** `08_javascript_web/dist/` (ESM + CJS + `.d.ts`, gitignored)
**Build command:** `cd 08_javascript_web && npm run build`
**Test command:** `cd 08_javascript_web && npm test`

---

## Module Index

Each module is a single `.ts` file. The public API is re-exported from `src/index.ts`. Import via `../dist/index.js` (browser ESM) or `import from 'ble-reference-web'` (bundled).

| File | Sprint | Purpose | Key exports |
|------|--------|---------|-------------|
| `01_scan.ts` | 37 | Device picker — wraps `requestDevice()` | `BLEScanner` (class), `DiscoveredDevice` interface |
| `02_scan_filtered.ts` | 38 | RequestDeviceOptions builders | `ScanFilter` (class) with `.byName()`, `.byNamePrefix()`, `.byServiceUUID()`, `.acceptAll()`, `.withOptionalServices(...uuids)` |
| `03_connection.ts` | 39 | GATT connect/disconnect lifecycle | `BLEConnector` (class), `.connect()`, `.disconnect()`, `onUnexpectedDisconnection` |
| `04_services_discovery.ts` | 40 | Enumerate services + characteristics | `BLEServiceDiscoverer` (class), `.discoverAll()` → `DiscoveredService[]` |
| `05_read_write.ts` | 41 | Read/write characteristic values | `BLEReadWriter` (class), `.read()`, `.write()`, `.writeWithoutResponse()`, `.readAsString()` |
| `06_notifications.ts` | 42 | Subscribe to characteristic notifications | `BLENotificationSubscriber` (class), `.subscribe()`, `.unsubscribe()`, `.unsubscribeAll()`, `.activeSubscriptionCount` |
| `07_scan_no_connect.ts` | 43 | Advertisement scanning (experimental) | `BLEAdvertisementScanner` (class), `.isSupported()`, `.start()`, `.stop()` |
| `08_battery.ts` | 44 | Battery Service (0x180F) | `BLEBattery.readLevel(server)` → `number \| null` (static) |
| `09_device_info.ts` | 45 | Device Information Service (0x180A) | `BLEDeviceInfo.read(server)` → `DeviceInfo \| null` (static), `DeviceInfo` interface |
| `10_heart_rate.ts` | 46 | HRM (0x2A37) + Polar PMD ECG | `HeartRateParser.parse(dv)`, `PolarEcgParser.parse(dv)`, PMD constants |
| `11_muse_eeg.ts` | 47 | Muse 2 EEG — 12-bit packed samples | `MuseEegParser.parse(dv, channel)`, `unpackUint12()`, Muse constants |
| `12_errors.ts` | 48 | Error hierarchy + timeout utility | `BLEError`, `BLETimeoutError`, `BLEServiceNotFoundError`, `withBLETimeout()` |
| `13_mtu.ts` | 49 | MTU — browser-managed (informational) | `BLEMTU.effectivePayloadSize()` |
| `14_pairing.ts` | 50 | Pairing — OS/browser-automatic (stub) | `BLEPairing.inferPairingStatus()` |
| `15_peripheral.ts` | 51 | Peripheral mode — not available (stub) | `webBluetoothPeripheralModeSupported()` → `false` |
| `16_rssi_distance.ts` | 52 | RSSI-based distance estimation | `estimateDistance()`, `classifyProximity()`, `smoothRSSI()` |
| `17_command_queue.ts` | 53 | Serial GATT operation queue | `BLECommandQueue.enqueue(fn)` → Promise chain |
| `18_ota_dfu.ts` | 54 | OTA firmware update via Web Bluetooth | `chunkFirmware()`, `validateFirmware()`, `dfuTransfer()` |

---

## Web Bluetooth Constraints vs Phase 01/02

| Constraint | Impact on library |
|------------|------------------|
| `requestDevice()` requires user gesture | `BLEScanner.requestDevice()` must be called from a button click — cannot scan automatically |
| No passive background scan | `01_scan` wraps the device picker dialog, not a background scan |
| Central-only | `15_peripheral` is a documented stub (always returns `false`) |
| MTU browser-managed | `13_mtu` is informational — no negotiation API |
| Pairing OS/browser-automatic | `14_pairing` is a documented stub |
| `requestLEScan()` experimental | `07_scan_no_connect` checks `isSupported()` before use; requires Chrome flag |
| HTTPS or localhost required | TestApp served via `npx serve .` — never open via `file://` |
| No RSSI during scan | `16_rssi_distance` works on post-connection RSSI only |

---

## Critical API Patterns

### 1. All BLE classes are instances, not static singletons

`BLEScanner`, `BLEConnector`, `BLEServiceDiscoverer`, `BLENotificationSubscriber`, `BLEReadWriter` are **instance classes**. Always instantiate them:

```typescript
const scanner = new BLEScanner();
const device = await scanner.requestDevice(ScanFilter.byName("Polar H10").toRequestDeviceOptions());

const connector = new BLEConnector();
const server = await connector.connect(device.device);

const discoverer = new BLEServiceDiscoverer();
const services = await discoverer.discoverAll(server);
```

`BLEBattery` and `BLEDeviceInfo` use **static methods** (no `new`):
```typescript
const battery = await BLEBattery.readLevel(server);
const info = await BLEDeviceInfo.read(server);
```

### 2. ScanFilter.withOptionalServices takes rest args

`withOptionalServices` takes **rest parameters** (`...uuids: string[]`), not a single array:

```typescript
// WRONG — passes a nested array:
filter.withOptionalServices(["heart_rate", "battery_service"])

// CORRECT — spread or list individually:
filter.withOptionalServices("heart_rate", "battery_service")
filter.withOptionalServices(...myUuidArray)
```

### 3. optionalServices — service UUIDs only

`optionalServices` in `requestDevice()` requires **service** UUIDs. Characteristic UUIDs are ignored by the browser. For Polar H10:
```typescript
ScanFilter.byNamePrefix("Polar").withOptionalServices(
  HEART_RATE_SERVICE_UUID,   // "heart_rate"
  "battery_service",
  "device_information",
  POLAR_PMD_SERVICE_UUID,    // the PMD *service* UUID
  POLAR_PMD_DATA_UUID,       // also a service UUID for PMD data
)
```

### 4. BLENotificationSubscriber.unsubscribe pattern

`subscribe()` returns `Promise<void>` — it does **not** return an unsubscribe callback. Unsubscribe explicitly:

```typescript
const subscriber = new BLENotificationSubscriber();

// Subscribe:
await subscriber.subscribe(hrmChar, (dv) => handleData(dv));
await subscriber.subscribe(pmdChar, (dv) => handleEcg(dv));

// Unsubscribe (Step 9 cleanup):
await subscriber.unsubscribeAll([hrmChar, pmdChar]);
// or individually:
await subscriber.unsubscribe(hrmChar);
```

### 5. Module path aliases — .js extensions required

TypeScript source uses `.js` extensions in all import paths (required by `moduleResolution: "Node16"`):
```typescript
import { BLEScanner } from "./01_scan.js";   // NOT ".ts"
```

When writing new modules, follow this pattern.

### 6. Web Bluetooth enum+namespace pattern

Parsers use the TypeScript enum+namespace pattern to allow both `namespace.method()` calls and type usage:

```typescript
export enum HeartRateParser { _unused = 0 }
export namespace HeartRateParser {
  export function parse(data: DataView): HeartRateMeasurement | null { ... }
}
```

Usage: `HeartRateParser.parse(dv)`.

---

## PMD Constants (Polar H10)

```typescript
import {
  HEART_RATE_SERVICE_UUID,    // "heart_rate"
  POLAR_PMD_SERVICE_UUID,     // "0000fb005a10-d4cf-a715-effa-fd3a1d5d7b85"
  POLAR_PMD_CONTROL_UUID,     // characteristic UUID for PMD control point
  POLAR_PMD_DATA_UUID,        // "0000fb005a10-d4cf-a715-effa-fd3a1d5d7ba4"
  POLAR_ECG_START_COMMAND,    // Uint8Array — starts ECG stream at 130 Hz
  POLAR_ECG_STOP_COMMAND,     // Uint8Array — stops ECG stream
  POLAR_ECG_SAMPLE_RATE,      // 130 (Hz)
} from "../dist/index.js";
```

## Muse 2 Constants

```typescript
import {
  MUSE_SERVICE_UUID,          // Muse GATT service UUID
  MUSE_CONTROL_UUID,          // Muse control characteristic UUID
  MUSE_EEG_CHANNEL_UUIDS,     // Record<string, string> — keyed by channel name
  MUSE_CHANNEL_NAMES,         // ["TP9", "AF7", "AF8", "TP10"]
  MUSE_STREAM_COMMAND,        // string — "\x02\x64\x0a" starts streaming
  MUSE_STOP_COMMAND,          // string — "\x02\x68\x0a" stops streaming
  MUSE_SCALE_FACTOR,          // 0.48828125 µV per raw unit
  MUSE_OFFSET,                // 2048 (12-bit midpoint)
} from "../dist/index.js";
```

---

## TestApp Architecture

Served from `08_javascript_web/` via `npx serve .`, opened at `http://localhost:3000/TestApp/`.

| File | Role |
|------|------|
| `TestApp/index.html` | Tab UI (Polar / Muse), Chart.js CDN, Export PNG buttons |
| `TestApp/polar-runner.js` | 11-step Polar H10 flow — scan → ECG stream 30s → chart → log |
| `TestApp/muse-runner.js` | 11-step Muse 2 flow — scan → EEG stream 15s → 4-ch chart → log |

**Critical:** Both runner functions are on `window` (`window.runPolar`, `window.runMuse`) because `<script type="module">` scope is not global. Inline `onclick` handlers in HTML only see `window`.

**TestApp data flow:**
```
ScanFilter.byNamePrefix() → BLEScanner.requestDevice()
→ BLEConnector.connect() → BLEServiceDiscoverer.discoverAll()
→ BLEDeviceInfo.read() → BLEBattery.readLevel()
→ BLENotificationSubscriber.subscribe() → Parser.parse()
→ Chart.js canvas → canvas.toDataURL() → PNG download
```

---

## Build & Test

```bash
cd 08_javascript_web

# Install deps
npm install

# Run tests (50 tests, 18 suites)
npm test

# Build library (ESM + CJS + .d.ts → dist/)
npm run build

# Serve TestApp
npx serve .
# Open http://localhost:3000/TestApp/ in Chrome
```

**tsconfig notes:**
- `"module": "Node16"` + `"moduleResolution": "node16"` — required for `.js` extension imports in TypeScript
- `"lib": ["ES2020", "DOM"]` — includes Web Bluetooth types via `@types/web-bluetooth`
- Do NOT change to `"moduleResolution": "bundler"` — incompatible with ts-jest

---

## Integration Test Results (2026-03-22)

### Polar H10 — PASS
- ECG streaming: 30s, 130 Hz
- Signal visible, std dev >> 10 µV threshold
- Device: Polar H10

### Muse 2 — PASS
- EEG streaming: 15s, ~256 Hz × 4 channels
- All channels visible on chart
- Signal std dev >> 5 µV threshold

### Platform validated
- Chrome (macOS) via `http://localhost:3000/TestApp/`

---

## Extending This Phase

When adding a new module:
1. Create `08_javascript_web/src/XX_name.ts` with exported class/namespace
2. Create `08_javascript_web/tests/XX_name.test.ts` with Jest tests (mock `navigator.bluetooth`)
3. Add `export * from "./XX_name.js"` to `src/index.ts`
4. Run `npm run build` to verify no TypeScript errors
5. Run `npm test` to verify all tests pass
6. Update `docs/Sprints/SPRINT_TRACKER.yaml`
