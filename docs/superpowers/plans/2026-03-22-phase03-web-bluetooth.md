# Phase 03 — Web Bluetooth TypeScript Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an 18-module framework-agnostic TypeScript library for Web Bluetooth, mirroring Phase 01 (Python) and Phase 02 (Swift), plus a static HTML TestApp for real-device integration tests in Chrome.

**Architecture:** Pure TypeScript source in `08_javascript_web/src/`, zero runtime dependencies, built with `tsup` to ESM + CJS + `.d.ts`. Tests use Jest with a mock `navigator.bluetooth`. A static `TestApp/index.html` is the integration test harness (open in Chrome, click buttons).

**Tech Stack:** TypeScript 5, tsup, Jest, `@types/web-bluetooth`, Chart.js (CDN, TestApp only)

---

## Web Bluetooth API Constraints vs Phase 01/02

These differences drive several module designs:

| Constraint | Impact |
|------------|--------|
| `requestDevice()` requires user gesture | No passive background scan; 01_scan wraps `requestDevice()` |
| No RSSI during scan | 16_rssi_distance works on post-connection RSSI only |
| Central-only (no peripheral mode) | 15_peripheral is a documented stub |
| MTU managed by browser | 13_mtu is informational only |
| Pairing is OS/browser-automatic | 14_pairing is a documented stub (same as Phase 02) |
| Advertisement scan (`requestLEScan`) experimental | 07_scan_no_connect wraps it with clear compatibility note |

---

## File Map

### Library (`08_javascript_web/`)

```
08_javascript_web/
├── src/
│   ├── 01_scan.ts                  BLEScanner — wraps requestDevice()
│   ├── 02_scan_filtered.ts         ScanFilter — RequestDeviceOptions builders
│   ├── 03_connection.ts            BLEConnector — gatt.connect/disconnect lifecycle
│   ├── 04_services_discovery.ts    BLEServiceDiscoverer — getPrimaryServices + getCharacteristics
│   ├── 05_read_write.ts            BLEReadWriter — readValue / writeValue
│   ├── 06_notifications.ts         BLENotificationSubscriber — startNotifications + events
│   ├── 07_scan_no_connect.ts       BLEAdvertisementScanner — requestLEScan (experimental)
│   ├── 08_battery.ts               BLEBattery — Battery Service 0x180F
│   ├── 09_device_info.ts           BLEDeviceInfo — DIS 0x180A
│   ├── 10_heart_rate.ts            HeartRateParser + PolarEcgParser
│   ├── 11_muse_eeg.ts              MuseEegParser — 12-bit packed samples
│   ├── 12_errors.ts                BLEError hierarchy + withBLETimeout
│   ├── 13_mtu.ts                   BLEMTU — informational stub, browser-managed
│   ├── 14_pairing.ts               BLEPairing — stub, OS/browser-automatic
│   ├── 15_peripheral.ts            BLEPeripheral — stub, not available in Web BT
│   ├── 16_rssi_distance.ts         BLERSSIDistance — estimation from post-connection RSSI
│   ├── 17_command_queue.ts         BLECommandQueue — Promise-based serial queue
│   ├── 18_ota_dfu.ts               BLEDFU — firmware chunking + transfer + SHA-256
│   └── index.ts                    Re-exports everything
├── tests/
│   ├── 01_scan.test.ts
│   ├── 02_scan_filtered.test.ts
│   ├── 03_connection.test.ts
│   ├── 04_services_discovery.test.ts
│   ├── 05_read_write.test.ts
│   ├── 06_notifications.test.ts
│   ├── 07_scan_no_connect.test.ts
│   ├── 08_battery.test.ts
│   ├── 09_device_info.test.ts
│   ├── 10_heart_rate.test.ts
│   ├── 11_muse_eeg.test.ts
│   ├── 12_errors.test.ts
│   ├── 13_mtu.test.ts
│   ├── 14_pairing.test.ts
│   ├── 15_peripheral.test.ts
│   ├── 16_rssi_distance.test.ts
│   ├── 17_command_queue.test.ts
│   └── 18_ota_dfu.test.ts
├── TestApp/
│   ├── index.html                  Tab UI: Polar | Muse | Logs
│   ├── polar-runner.js             11-step Polar H10 flow (imports from dist/)
│   └── muse-runner.js              11-step Muse 2 flow (imports from dist/)
├── package.json
├── tsconfig.json
├── tsup.config.ts
└── jest.config.ts
```

### Modifications to existing files

- `docs/Sprints/SPRINT_TRACKER.yaml` — rename phase_03, remove Node.js folder ref, update sprint IDs 37–54 names
- `docs/integration-test-protocol.md` — update Phase 03 row: "Web Bluetooth (TypeScript)"
- `.gitignore` — add `08_javascript_web/dist/`, `08_javascript_web/node_modules/`

---

## Task 00: Project Scaffolding

**Files:**
- Create: `08_javascript_web/package.json`
- Create: `08_javascript_web/tsconfig.json`
- Create: `08_javascript_web/tsup.config.ts`
- Create: `08_javascript_web/jest.config.ts`
- Modify: `.gitignore`

- [ ] **Step 1: Create `package.json`**

```json
{
  "name": "ble-reference-web",
  "version": "0.1.0",
  "description": "Framework-agnostic Web Bluetooth TypeScript reference library",
  "type": "module",
  "main": "./dist/index.cjs",
  "module": "./dist/index.js",
  "types": "./dist/index.d.ts",
  "exports": {
    ".": {
      "import": "./dist/index.js",
      "require": "./dist/index.cjs",
      "types": "./dist/index.d.ts"
    }
  },
  "scripts": {
    "build": "tsup",
    "test": "jest",
    "test:watch": "jest --watch"
  },
  "devDependencies": {
    "@types/web-bluetooth": "^0.0.20",
    "tsup": "^8.0.0",
    "typescript": "^5.4.0",
    "jest": "^29.0.0",
    "ts-jest": "^29.0.0",
    "@types/jest": "^29.0.0"
  }
}
```

- [ ] **Step 2: Create `tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "module": "ESNext",
    "moduleResolution": "node16",
    "lib": ["ES2020", "DOM"],
    "strict": true,
    "declaration": true,
    "outDir": "./dist",
    "rootDir": "./src"
  },
  "include": ["src/**/*"],
  "exclude": ["node_modules", "dist", "tests"]
}
```

- [ ] **Step 3: Create `tsup.config.ts`**

```typescript
import { defineConfig } from "tsup";

export default defineConfig({
  entry: ["src/index.ts"],
  format: ["esm", "cjs"],
  dts: true,
  clean: true,
  sourcemap: true,
});
```

- [ ] **Step 4: Create `jest.config.ts`**

```typescript
export default {
  preset: "ts-jest",
  testEnvironment: "node",
  extensionsToTreatAsEsm: [".ts"],
  moduleNameMapper: {
    "^(\\.{1,2}/.*)\\.js$": "$1",
  },
  transform: {
    "^.+\\.tsx?$": ["ts-jest", { useESM: true }],
  },
};
```

- [ ] **Step 5: Install dependencies**

```bash
cd 08_javascript_web && npm install
```

Expected: `node_modules/` created, no errors.

- [ ] **Step 6: Add to .gitignore**

Append to root `.gitignore`:
```
# JavaScript / Web Bluetooth
08_javascript_web/dist/
08_javascript_web/node_modules/
```

- [ ] **Step 7: Commit**

```bash
git add 08_javascript_web/package.json 08_javascript_web/tsconfig.json 08_javascript_web/tsup.config.ts 08_javascript_web/jest.config.ts .gitignore
git commit -m "feat(phase03): scaffold Web Bluetooth TypeScript project"
```

---

## Task 01: BLEScanner — `requestDevice()` wrapper

**Files:**
- Create: `08_javascript_web/src/01_scan.ts`
- Create: `08_javascript_web/tests/01_scan.test.ts`

**Web Bluetooth note:** There is no passive scan. `requestDevice()` opens a browser picker. The "scan" abstraction here wraps `requestDevice()` so it fits the same API shape as Phase 01/02.

- [ ] **Step 1: Write failing test**

```typescript
// tests/01_scan.test.ts
import { BLEScanner, DiscoveredDevice } from "../src/01_scan.js";

const mockDevice = {
  id: "test-id",
  name: "Polar H10",
  gatt: { connect: jest.fn() },
  addEventListener: jest.fn(),
};

beforeEach(() => {
  (global as any).navigator = {
    bluetooth: {
      requestDevice: jest.fn().mockResolvedValue(mockDevice),
    },
  };
});

test("scan returns DiscoveredDevice wrapping the chosen device", async () => {
  const scanner = new BLEScanner();
  const device = await scanner.requestDevice({ acceptAllDevices: true });
  expect(device.name).toBe("Polar H10");
  expect(device.id).toBe("test-id");
});

test("scan throws BLEScanError if Bluetooth unavailable", async () => {
  (global as any).navigator = { bluetooth: undefined };
  const scanner = new BLEScanner();
  await expect(scanner.requestDevice({ acceptAllDevices: true })).rejects.toThrow("Bluetooth not available");
});
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
cd 08_javascript_web && npm test -- 01_scan
```

- [ ] **Step 3: Implement `src/01_scan.ts`**

```typescript
// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 01 — Scan (requestDevice)
// Sprint  : Sprint 37
// Phase   : Phase 03 — Web Bluetooth (TypeScript)
//
// Purpose : Discover a BLE device via the browser's
//           requestDevice() picker.
//
// Web Bluetooth note:
//   Unlike Python/Bleak or CoreBluetooth, Web Bluetooth
//   has NO passive scan. requestDevice() opens a browser
//   UI picker — it REQUIRES a user gesture and CANNOT
//   run silently in the background.
//
// Usage   : const device = await scanner.requestDevice({ filters: [{ name: "Polar H10" }] })
// ─────────────────────────────────────────────────────

/** A BLE device returned by the browser picker. */
export interface DiscoveredDevice {
  /** Browser-assigned device ID (opaque, not a MAC address). */
  id: string;
  /** Advertised device name, or null if absent. */
  name: string | null;
  /** The underlying BluetoothDevice for further operations. */
  device: BluetoothDevice;
}

/** Errors thrown by BLEScanner. */
export class BLEScanError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "BLEScanError";
  }
}

/**
 * Wraps the Web Bluetooth `requestDevice()` API.
 *
 * IMPORTANT: Must be called from a user gesture handler (button click, etc.)
 * — browsers block requestDevice() from non-gesture contexts.
 */
export class BLEScanner {
  /**
   * Open the browser's device picker and return the chosen device.
   *
   * @param options - RequestDeviceOptions passed to navigator.bluetooth.requestDevice().
   *   Use `{ filters: [{ name: "Polar H10" }] }` to pre-filter the list.
   *   Use `{ acceptAllDevices: true }` to show all nearby devices.
   * @throws BLEScanError if Web Bluetooth is not available in this browser.
   * @throws DOMException with name "NotFoundError" if the user cancels the picker.
   */
  async requestDevice(options: RequestDeviceOptions): Promise<DiscoveredDevice> {
    if (!navigator?.bluetooth) {
      throw new BLEScanError("Bluetooth not available in this browser. Use Chrome or Edge.");
    }
    const device = await navigator.bluetooth.requestDevice(options);
    return {
      id: device.id,
      name: device.name ?? null,
      device,
    };
  }
}
```

- [ ] **Step 4: Run test — expect PASS**

```bash
cd 08_javascript_web && npm test -- 01_scan
```

- [ ] **Step 5: Commit**

```bash
git add 08_javascript_web/src/01_scan.ts 08_javascript_web/tests/01_scan.test.ts
git commit -m "feat(phase03): Sprint 37 — BLEScanner (requestDevice wrapper)"
```

---

## Task 02: ScanFilter — RequestDeviceOptions builders

**Files:**
- Create: `08_javascript_web/src/02_scan_filtered.ts`
- Create: `08_javascript_web/tests/02_scan_filtered.test.ts`

- [ ] **Step 1: Write failing test**

```typescript
// tests/02_scan_filtered.test.ts
import { ScanFilter } from "../src/02_scan_filtered.js";

test("byName produces name filter", () => {
  const opts = ScanFilter.byName("Polar H10").toRequestDeviceOptions();
  expect(opts.filters).toEqual([{ name: "Polar H10" }]);
});

test("byNamePrefix produces namePrefix filter", () => {
  const opts = ScanFilter.byNamePrefix("Polar").toRequestDeviceOptions();
  expect(opts.filters).toEqual([{ namePrefix: "Polar" }]);
});

test("byServiceUUID produces services filter", () => {
  const opts = ScanFilter.byServiceUUID("180D").toRequestDeviceOptions();
  expect(opts.filters).toEqual([{ services: ["180D"] }]);
  expect(opts.optionalServices).toContain("180D");
});

test("acceptAll produces acceptAllDevices: true", () => {
  const opts = ScanFilter.acceptAll().toRequestDeviceOptions();
  expect(opts.acceptAllDevices).toBe(true);
});
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
cd 08_javascript_web && npm test -- 02_scan_filtered
```

- [ ] **Step 3: Implement `src/02_scan_filtered.ts`**

```typescript
// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 02 — Scan Filtered
// Sprint  : Sprint 38
//
// Purpose : Build RequestDeviceOptions for common filter
//           patterns. Mirrors Python ScanFilter / Swift
//           ScanFilter.
//
// Web Bluetooth note:
//   Filters are passed to requestDevice(), not applied
//   post-scan. The browser sends them to the BLE adapter.
//   optionalServices must list every service UUID you
//   intend to use after connecting, or access will be
//   blocked by the browser security model.
// ─────────────────────────────────────────────────────

/** Builds Web Bluetooth RequestDeviceOptions. */
export class ScanFilter {
  private constructor(private readonly _options: RequestDeviceOptions) {}

  /** Filter by exact device name. */
  static byName(name: string): ScanFilter {
    return new ScanFilter({ filters: [{ name }] });
  }

  /** Filter by name prefix (e.g., "Polar" matches "Polar H10"). */
  static byNamePrefix(namePrefix: string): ScanFilter {
    return new ScanFilter({ filters: [{ namePrefix }] });
  }

  /**
   * Filter by GATT service UUID.
   * Also adds the UUID to optionalServices so characteristics
   * can be accessed after connection.
   */
  static byServiceUUID(uuid: string): ScanFilter {
    return new ScanFilter({
      filters: [{ services: [uuid] }],
      optionalServices: [uuid],
    });
  }

  /** Accept all nearby devices (no filter). Use with care — shows every BLE device. */
  static acceptAll(): ScanFilter {
    return new ScanFilter({ acceptAllDevices: true });
  }

  /**
   * Add optional service UUIDs that you need post-connection
   * but don't want to filter on.
   */
  withOptionalServices(...uuids: string[]): ScanFilter {
    const existing = (this._options.optionalServices as string[]) ?? [];
    return new ScanFilter({
      ...this._options,
      optionalServices: [...existing, ...uuids],
    });
  }

  /** Return the RequestDeviceOptions for use with navigator.bluetooth.requestDevice(). */
  toRequestDeviceOptions(): RequestDeviceOptions {
    return this._options;
  }
}
```

- [ ] **Step 4: Run test — expect PASS**

```bash
cd 08_javascript_web && npm test -- 02_scan_filtered
```

- [ ] **Step 5: Commit**

```bash
git add 08_javascript_web/src/02_scan_filtered.ts 08_javascript_web/tests/02_scan_filtered.test.ts
git commit -m "feat(phase03): Sprint 38 — ScanFilter (RequestDeviceOptions builders)"
```

---

## Task 03: BLEConnector — GATT connect/disconnect

**Files:**
- Create: `08_javascript_web/src/03_connection.ts`
- Create: `08_javascript_web/tests/03_connection.test.ts`

- [ ] **Step 1: Write failing test**

```typescript
// tests/03_connection.test.ts
import { BLEConnector, BLEConnectionError } from "../src/03_connection.js";

function makeDevice(opts: { connectFails?: boolean } = {}) {
  const server = { connected: true, disconnect: jest.fn() };
  return {
    name: "Polar H10",
    gatt: {
      connected: false,
      connect: opts.connectFails
        ? jest.fn().mockRejectedValue(new Error("Connection failed"))
        : jest.fn().mockResolvedValue(server),
    },
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
  } as unknown as BluetoothDevice;
}

test("connect resolves with BluetoothRemoteGATTServer", async () => {
  const connector = new BLEConnector();
  const device = makeDevice();
  const server = await connector.connect(device);
  expect(server).toBeDefined();
  expect(device.gatt!.connect).toHaveBeenCalled();
});

test("connect throws BLEConnectionError on failure", async () => {
  const connector = new BLEConnector();
  const device = makeDevice({ connectFails: true });
  await expect(connector.connect(device)).rejects.toBeInstanceOf(BLEConnectionError);
});

test("disconnect calls gatt.disconnect", async () => {
  const connector = new BLEConnector();
  const device = makeDevice();
  await connector.connect(device);
  connector.disconnect(device);
  expect(device.gatt!.disconnect).toHaveBeenCalled();
});
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
cd 08_javascript_web && npm test -- 03_connection
```

- [ ] **Step 3: Implement `src/03_connection.ts`**

```typescript
// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 03 — Connection Lifecycle
// Sprint  : Sprint 39
//
// Purpose : Connect to and disconnect from a BLE device's
//           GATT server.
//
// Web Bluetooth difference vs Phase 01/02:
//   - Connection is via device.gatt.connect() — returns
//     BluetoothRemoteGATTServer directly (no callback).
//   - Disconnect events come via 'gattserverdisconnected'
//     on the BluetoothDevice (not a central manager).
//   - No native timeout — implemented manually.
// ─────────────────────────────────────────────────────

/** Default connection timeout in milliseconds. */
const DEFAULT_TIMEOUT_MS = 10_000;

/** Errors thrown by BLEConnector. */
export class BLEConnectionError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "BLEConnectionError";
  }
}

/** Manages the GATT connection lifecycle for a Web Bluetooth device. */
export class BLEConnector {
  private _server: BluetoothRemoteGATTServer | null = null;
  private _disconnectHandler: (() => void) | null = null;

  /** Callback fired when the device disconnects unexpectedly. */
  onUnexpectedDisconnection: ((device: BluetoothDevice) => void) | null = null;

  /**
   * Connect to the device's GATT server.
   *
   * @param device - BluetoothDevice from BLEScanner.requestDevice()
   * @param timeoutMs - Connection timeout in ms (default 10s)
   * @throws BLEConnectionError on failure or timeout
   */
  async connect(
    device: BluetoothDevice,
    timeoutMs = DEFAULT_TIMEOUT_MS
  ): Promise<BluetoothRemoteGATTServer> {
    const connectPromise = device.gatt!.connect().catch((err) => {
      throw new BLEConnectionError(`Connection failed: ${err}`, err);
    });
    const timeoutPromise = new Promise<never>((_, reject) =>
      setTimeout(() => reject(new BLEConnectionError(`Connection timeout after ${timeoutMs}ms`)), timeoutMs)
    );
    this._server = await Promise.race([connectPromise, timeoutPromise]);

    // Listen for unexpected disconnects
    this._disconnectHandler = () => {
      if (this.onUnexpectedDisconnection) {
        this.onUnexpectedDisconnection(device);
      }
    };
    device.addEventListener("gattserverdisconnected", this._disconnectHandler);

    return this._server;
  }

  /**
   * Disconnect from the GATT server.
   * Intentional disconnects do NOT fire onUnexpectedDisconnection.
   */
  disconnect(device: BluetoothDevice): void {
    if (this._disconnectHandler) {
      device.removeEventListener("gattserverdisconnected", this._disconnectHandler);
      this._disconnectHandler = null;
    }
    if (device.gatt?.connected) {
      device.gatt.disconnect();
    }
    this._server = null;
  }

  /** The active GATT server, or null if not connected. */
  get server(): BluetoothRemoteGATTServer | null {
    return this._server;
  }
}
```

- [ ] **Step 4: Run test — expect PASS**

```bash
cd 08_javascript_web && npm test -- 03_connection
```

- [ ] **Step 5: Commit**

```bash
git add 08_javascript_web/src/03_connection.ts 08_javascript_web/tests/03_connection.test.ts
git commit -m "feat(phase03): Sprint 39 — BLEConnector (GATT connect/disconnect)"
```

---

## Task 04: BLEServiceDiscoverer — GATT service + characteristic discovery

**Files:**
- Create: `08_javascript_web/src/04_services_discovery.ts`
- Create: `08_javascript_web/tests/04_services_discovery.test.ts`

- [ ] **Step 1: Write failing test**

```typescript
// tests/04_services_discovery.test.ts
import { BLEServiceDiscoverer } from "../src/04_services_discovery.js";

function makeServer() {
  const char = {
    uuid: "00002a37-0000-1000-8000-00805f9b34fb",
    properties: { notify: true, read: false, write: false, writeWithoutResponse: false, indicate: false },
  };
  const service = {
    uuid: "0000180d-0000-1000-8000-00805f9b34fb",
    isPrimary: true,
    getCharacteristics: jest.fn().mockResolvedValue([char]),
  };
  return {
    getPrimaryServices: jest.fn().mockResolvedValue([service]),
  } as unknown as BluetoothRemoteGATTServer;
}

test("discoverAll returns services with characteristics", async () => {
  const discoverer = new BLEServiceDiscoverer();
  const services = await discoverer.discoverAll(makeServer());
  expect(services).toHaveLength(1);
  expect(services[0].uuid).toBe("0000180d-0000-1000-8000-00805f9b34fb");
  expect(services[0].characteristics).toHaveLength(1);
  expect(services[0].characteristics[0].canNotify).toBe(true);
});
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
cd 08_javascript_web && npm test -- 04_services_discovery
```

- [ ] **Step 3: Implement `src/04_services_discovery.ts`**

```typescript
// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 04 — Services & Characteristics Discovery
// Sprint  : Sprint 40
//
// Purpose : Enumerate all GATT services and their
//           characteristics from a connected GATT server.
//
// Web Bluetooth note:
//   Only services listed in optionalServices (at
//   requestDevice() time) can be accessed. Attempting
//   to access unlisted services throws a SecurityError.
// ─────────────────────────────────────────────────────

export interface DiscoveredCharacteristic {
  uuid: string;
  canRead: boolean;
  canWrite: boolean;
  canWriteWithoutResponse: boolean;
  canNotify: boolean;
  canIndicate: boolean;
  /** The underlying characteristic for read/write/notify operations. */
  characteristic: BluetoothRemoteGATTCharacteristic;
}

export interface DiscoveredService {
  uuid: string;
  isPrimary: boolean;
  characteristics: DiscoveredCharacteristic[];
  /** The underlying service for direct operations. */
  service: BluetoothRemoteGATTService;
}

/** Discovers all GATT services and characteristics on a connected server. */
export class BLEServiceDiscoverer {
  /**
   * Discover all primary services and their characteristics.
   *
   * @param server - Connected BluetoothRemoteGATTServer (from BLEConnector.connect())
   * @returns Array of DiscoveredService with nested characteristics
   */
  async discoverAll(server: BluetoothRemoteGATTServer): Promise<DiscoveredService[]> {
    const rawServices = await server.getPrimaryServices();
    return Promise.all(rawServices.map((svc) => this._mapService(svc)));
  }

  private async _mapService(svc: BluetoothRemoteGATTService): Promise<DiscoveredService> {
    const rawChars = await svc.getCharacteristics();
    return {
      uuid: svc.uuid,
      isPrimary: svc.isPrimary,
      service: svc,
      characteristics: rawChars.map((c) => ({
        uuid: c.uuid,
        canRead: c.properties.read,
        canWrite: c.properties.write,
        canWriteWithoutResponse: c.properties.writeWithoutResponse,
        canNotify: c.properties.notify,
        canIndicate: c.properties.indicate,
        characteristic: c,
      })),
    };
  }
}
```

- [ ] **Step 4: Run test — expect PASS**

```bash
cd 08_javascript_web && npm test -- 04_services_discovery
```

- [ ] **Step 5: Commit**

```bash
git add 08_javascript_web/src/04_services_discovery.ts 08_javascript_web/tests/04_services_discovery.test.ts
git commit -m "feat(phase03): Sprint 40 — BLEServiceDiscoverer (GATT discovery)"
```

---

## Task 05: BLEReadWriter — readValue / writeValue

**Files:**
- Create: `08_javascript_web/src/05_read_write.ts`
- Create: `08_javascript_web/tests/05_read_write.test.ts`

- [ ] **Step 1: Write failing test**

```typescript
// tests/05_read_write.test.ts
import { BLEReadWriter, BLEReadWriteError } from "../src/05_read_write.js";

function makeChar(opts: { canRead?: boolean; canWrite?: boolean; value?: DataView } = {}) {
  return {
    properties: { read: opts.canRead ?? true, write: opts.canWrite ?? true, writeWithoutResponse: true },
    readValue: jest.fn().mockResolvedValue(opts.value ?? new DataView(new ArrayBuffer(1))),
    writeValue: jest.fn().mockResolvedValue(undefined),
    writeValueWithoutResponse: jest.fn().mockResolvedValue(undefined),
  } as unknown as BluetoothRemoteGATTCharacteristic;
}

test("read returns DataView", async () => {
  const rw = new BLEReadWriter();
  const dv = new DataView(new Uint8Array([42]).buffer);
  const char = makeChar({ value: dv });
  const result = await rw.read(char);
  expect(result.getUint8(0)).toBe(42);
});

test("read throws if characteristic not readable", async () => {
  const rw = new BLEReadWriter();
  const char = makeChar({ canRead: false });
  await expect(rw.read(char)).rejects.toBeInstanceOf(BLEReadWriteError);
});

test("write calls writeValue", async () => {
  const rw = new BLEReadWriter();
  const char = makeChar({ canWrite: true });
  await rw.write(char, new Uint8Array([1, 2, 3]));
  expect(char.writeValue).toHaveBeenCalled();
});
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
cd 08_javascript_web && npm test -- 05_read_write
```

- [ ] **Step 3: Implement `src/05_read_write.ts`**

```typescript
// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 05 — Read & Write
// Sprint  : Sprint 41
//
// Purpose : Read from and write to GATT characteristics.
//
// Web Bluetooth note:
//   readValue() returns a DataView (not Uint8Array).
//   writeValue() takes BufferSource (ArrayBuffer | ArrayBufferView).
//   writeValueWithoutResponse() is fire-and-forget.
// ─────────────────────────────────────────────────────

export class BLEReadWriteError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "BLEReadWriteError";
  }
}

/** Reads from and writes to GATT characteristics. */
export class BLEReadWriter {
  /**
   * Read a characteristic's current value.
   *
   * @throws BLEReadWriteError if characteristic does not support read.
   */
  async read(characteristic: BluetoothRemoteGATTCharacteristic): Promise<DataView> {
    if (!characteristic.properties.read) {
      throw new BLEReadWriteError(`Characteristic ${characteristic.uuid} does not support read`);
    }
    return characteristic.readValue();
  }

  /**
   * Write data to a characteristic (with response).
   *
   * @throws BLEReadWriteError if characteristic does not support write.
   */
  async write(characteristic: BluetoothRemoteGATTCharacteristic, data: BufferSource): Promise<void> {
    if (!characteristic.properties.write) {
      throw new BLEReadWriteError(`Characteristic ${characteristic.uuid} does not support write`);
    }
    await characteristic.writeValue(data);
  }

  /**
   * Write data without waiting for acknowledgement.
   * Use for high-frequency writes (e.g., streaming commands).
   *
   * @throws BLEReadWriteError if characteristic does not support write-without-response.
   */
  async writeWithoutResponse(
    characteristic: BluetoothRemoteGATTCharacteristic,
    data: BufferSource
  ): Promise<void> {
    if (!characteristic.properties.writeWithoutResponse) {
      throw new BLEReadWriteError(
        `Characteristic ${characteristic.uuid} does not support write-without-response`
      );
    }
    await characteristic.writeValueWithoutResponse(data);
  }

  /**
   * Read as a UTF-8 string (convenience helper for DIS / device info characteristics).
   */
  async readAsString(characteristic: BluetoothRemoteGATTCharacteristic): Promise<string> {
    const dv = await this.read(characteristic);
    return new TextDecoder("utf-8").decode(dv);
  }
}
```

- [ ] **Step 4: Run test — expect PASS**

```bash
cd 08_javascript_web && npm test -- 05_read_write
```

- [ ] **Step 5: Commit**

```bash
git add 08_javascript_web/src/05_read_write.ts 08_javascript_web/tests/05_read_write.test.ts
git commit -m "feat(phase03): Sprint 41 — BLEReadWriter (readValue/writeValue)"
```

---

## Task 06: BLENotificationSubscriber — startNotifications + events

**Files:**
- Create: `08_javascript_web/src/06_notifications.ts`
- Create: `08_javascript_web/tests/06_notifications.test.ts`

- [ ] **Step 1: Write failing test**

```typescript
// tests/06_notifications.test.ts
import { BLENotificationSubscriber } from "../src/06_notifications.js";

function makeChar(uuid = "2a37") {
  const listeners: Record<string, Function> = {};
  return {
    uuid,
    properties: { notify: true },
    startNotifications: jest.fn().mockResolvedValue(undefined),
    stopNotifications: jest.fn().mockResolvedValue(undefined),
    addEventListener: jest.fn((event: string, cb: Function) => { listeners[event] = cb; }),
    removeEventListener: jest.fn(),
    _trigger: (value: DataView) => listeners["characteristicvaluechanged"]?.({ target: { value } }),
  };
}

test("subscribe registers handler and receives notifications", async () => {
  const sub = new BLENotificationSubscriber();
  const char = makeChar();
  const received: DataView[] = [];
  await sub.subscribe(char as any, (dv) => received.push(dv));
  char._trigger(new DataView(new Uint8Array([73]).buffer));
  expect(received).toHaveLength(1);
  expect(received[0].getUint8(0)).toBe(73);
});

test("unsubscribe stops notifications", async () => {
  const sub = new BLENotificationSubscriber();
  const char = makeChar();
  await sub.subscribe(char as any, () => {});
  await sub.unsubscribe(char as any);
  expect(char.stopNotifications).toHaveBeenCalled();
  expect(char.removeEventListener).toHaveBeenCalled();
});
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
cd 08_javascript_web && npm test -- 06_notifications
```

- [ ] **Step 3: Implement `src/06_notifications.ts`**

```typescript
// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 06 — Notifications
// Sprint  : Sprint 42
//
// Purpose : Subscribe to GATT characteristic notifications.
//
// Web Bluetooth pattern:
//   1. char.startNotifications()
//   2. char.addEventListener("characteristicvaluechanged", handler)
//   3. handler receives Event — value is at event.target.value (DataView)
//
// This module wraps that pattern into a clean callback API.
// ─────────────────────────────────────────────────────

export type NotificationHandler = (value: DataView) => void;

/** Manages GATT notification subscriptions. */
export class BLENotificationSubscriber {
  private _handlers = new Map<string, EventListener>();

  /**
   * Subscribe to notifications from a characteristic.
   *
   * @param characteristic - Must have notify property.
   * @param handler - Called with each new DataView value.
   */
  async subscribe(
    characteristic: BluetoothRemoteGATTCharacteristic,
    handler: NotificationHandler
  ): Promise<void> {
    await characteristic.startNotifications();
    const listener: EventListener = (event) => {
      const value = (event.target as BluetoothRemoteGATTCharacteristic).value;
      if (value) handler(value);
    };
    this._handlers.set(characteristic.uuid, listener);
    characteristic.addEventListener("characteristicvaluechanged", listener);
  }

  /** Unsubscribe from a characteristic's notifications. */
  async unsubscribe(characteristic: BluetoothRemoteGATTCharacteristic): Promise<void> {
    const listener = this._handlers.get(characteristic.uuid);
    if (listener) {
      characteristic.removeEventListener("characteristicvaluechanged", listener);
      this._handlers.delete(characteristic.uuid);
    }
    await characteristic.stopNotifications();
  }

  /** Unsubscribe from all active subscriptions. */
  async unsubscribeAll(characteristics: BluetoothRemoteGATTCharacteristic[]): Promise<void> {
    await Promise.all(characteristics.map((c) => this.unsubscribe(c)));
  }

  get activeSubscriptionCount(): number {
    return this._handlers.size;
  }
}
```

- [ ] **Step 4: Run test — expect PASS**

```bash
cd 08_javascript_web && npm test -- 06_notifications
```

- [ ] **Step 5: Commit**

```bash
git add 08_javascript_web/src/06_notifications.ts 08_javascript_web/tests/06_notifications.test.ts
git commit -m "feat(phase03): Sprint 42 — BLENotificationSubscriber"
```

---

## Task 07: BLEAdvertisementScanner — requestLEScan (experimental)

**Files:**
- Create: `08_javascript_web/src/07_scan_no_connect.ts`
- Create: `08_javascript_web/tests/07_scan_no_connect.test.ts`

**Note:** `requestLEScan()` is a Chrome Origin Trial / experimental API (behind a flag). Document clearly.

- [ ] **Step 1: Write failing test**

```typescript
// tests/07_scan_no_connect.test.ts
import { BLEAdvertisementScanner, ParsedAdvertisement } from "../src/07_scan_no_connect.js";

test("isSupported returns false when requestLEScan absent", () => {
  (global as any).navigator = { bluetooth: {} };
  expect(BLEAdvertisementScanner.isSupported()).toBe(false);
});

test("isSupported returns true when requestLEScan present", () => {
  (global as any).navigator = { bluetooth: { requestLEScan: jest.fn() } };
  expect(BLEAdvertisementScanner.isSupported()).toBe(true);
});
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
cd 08_javascript_web && npm test -- 07_scan_no_connect
```

- [ ] **Step 3: Implement `src/07_scan_no_connect.ts`**

```typescript
// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 07 — Scan Without Connect
// Sprint  : Sprint 43
//
// Purpose : Receive BLE advertisement packets without
//           connecting to the device.
//
// Web Bluetooth status (2026):
//   requestLEScan() is an EXPERIMENTAL API.
//   - Chrome 79+ with #enable-experimental-web-platform-features flag
//   - Or via Origin Trial on supported origins
//   - NOT available in production Chrome without flag
//   - NOT available in Firefox, Safari, or Edge
//
// Recommendation:
//   Use isSupported() guard before calling start().
//   Fall back to requestDevice() + connection for production use.
// ─────────────────────────────────────────────────────

export interface ParsedAdvertisement {
  deviceId: string;
  deviceName: string | null;
  rssi: number | null;
  txPower: number | null;
  serviceUuids: string[];
  manufacturerData: Map<number, DataView>;
}

export type AdvertisementHandler = (adv: ParsedAdvertisement) => void;

/** Wraps the experimental Web Bluetooth Advertisement Scanning API. */
export class BLEAdvertisementScanner {
  private _scan: any = null;

  /** Returns true if requestLEScan() is available in this browser. */
  static isSupported(): boolean {
    return typeof (navigator as any)?.bluetooth?.requestLEScan === "function";
  }

  /**
   * Start receiving advertisement packets.
   *
   * IMPORTANT: Only works in Chrome with experimental flags enabled.
   * Check isSupported() first.
   *
   * @param handler - Called for each received advertisement.
   * @param options - requestLEScan options (filters, keepRepeatedDevices).
   */
  async start(handler: AdvertisementHandler, options?: any): Promise<void> {
    if (!BLEAdvertisementScanner.isSupported()) {
      throw new Error(
        "requestLEScan() not available. Enable chrome://flags/#enable-experimental-web-platform-features"
      );
    }
    this._scan = await (navigator as any).bluetooth.requestLEScan(options ?? { acceptAllAdvertisements: true });
    navigator.addEventListener("advertisementreceived" as any, (event: any) => {
      handler({
        deviceId: event.device?.id ?? "",
        deviceName: event.device?.name ?? null,
        rssi: event.rssi ?? null,
        txPower: event.txPower ?? null,
        serviceUuids: [...(event.uuids ?? [])],
        manufacturerData: event.manufacturerData ?? new Map(),
      });
    });
  }

  /** Stop the active scan. */
  stop(): void {
    this._scan?.stop();
    this._scan = null;
  }
}
```

- [ ] **Step 4: Run test — expect PASS**

```bash
cd 08_javascript_web && npm test -- 07_scan_no_connect
```

- [ ] **Step 5: Commit**

```bash
git add 08_javascript_web/src/07_scan_no_connect.ts 08_javascript_web/tests/07_scan_no_connect.test.ts
git commit -m "feat(phase03): Sprint 43 — BLEAdvertisementScanner (experimental requestLEScan)"
```

---

## Task 08–09: Battery + Device Info

**Files:**
- Create: `08_javascript_web/src/08_battery.ts` + test
- Create: `08_javascript_web/src/09_device_info.ts` + test

These modules are nearly identical to Phase 01/02. Implement them together.

- [ ] **Step 1: Implement `src/08_battery.ts`**

```typescript
// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 08 — Battery Service (0x180F)
// Sprint  : Sprint 44
// ─────────────────────────────────────────────────────

export const BATTERY_SERVICE_UUID = "battery_service";          // 0x180F
export const BATTERY_LEVEL_UUID = "battery_level";              // 0x2A19

export class BLEBattery {
  /**
   * Read battery level from Battery Service (0x180F / 0x2A19).
   *
   * @param server - Connected GATT server
   * @returns Battery percentage 0–100, or null if service absent.
   */
  static async readLevel(server: BluetoothRemoteGATTServer): Promise<number | null> {
    try {
      const service = await server.getPrimaryService(BATTERY_SERVICE_UUID);
      const char = await service.getCharacteristic(BATTERY_LEVEL_UUID);
      const dv = await char.readValue();
      return Math.min(100, Math.max(0, dv.getUint8(0)));
    } catch {
      return null;
    }
  }
}
```

- [ ] **Step 2: Write test for battery**

```typescript
// tests/08_battery.test.ts
import { BLEBattery } from "../src/08_battery.js";

test("readLevel returns percentage from GATT", async () => {
  const dv = new DataView(new Uint8Array([85]).buffer);
  const server = {
    getPrimaryService: jest.fn().mockResolvedValue({
      getCharacteristic: jest.fn().mockResolvedValue({
        readValue: jest.fn().mockResolvedValue(dv),
      }),
    }),
  } as unknown as BluetoothRemoteGATTServer;
  expect(await BLEBattery.readLevel(server)).toBe(85);
});

test("readLevel returns null if service absent", async () => {
  const server = {
    getPrimaryService: jest.fn().mockRejectedValue(new Error("not found")),
  } as unknown as BluetoothRemoteGATTServer;
  expect(await BLEBattery.readLevel(server)).toBeNull();
});
```

- [ ] **Step 3: Implement `src/09_device_info.ts`**

```typescript
// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 09 — Device Information Service (0x180A)
// Sprint  : Sprint 45
// ─────────────────────────────────────────────────────

export const DIS_SERVICE_UUID = "device_information";            // 0x180A

const DIS_CHARS = {
  serialNumber:       "serial_number_string",                   // 0x2A25
  modelNumber:        "model_number_string",                    // 0x2A24
  firmwareRevision:   "firmware_revision_string",               // 0x2A26
  hardwareRevision:   "hardware_revision_string",               // 0x2A27
  manufacturerName:   "manufacturer_name_string",               // 0x2A29
};

export interface DeviceInfo {
  serialNumber: string | null;
  modelNumber: string | null;
  firmwareRevision: string | null;
  hardwareRevision: string | null;
  manufacturerName: string | null;
  /** Stable identifier: serial if available, else manufacturerName+model. */
  identifier: string;
}

export class BLEDeviceInfo {
  /**
   * Read all Device Information Service fields.
   * Returns null if DIS is not available.
   */
  static async read(server: BluetoothRemoteGATTServer): Promise<DeviceInfo | null> {
    try {
      const service = await server.getPrimaryService(DIS_SERVICE_UUID);
      const readOptional = async (uuid: string): Promise<string | null> => {
        try {
          const char = await service.getCharacteristic(uuid);
          const dv = await char.readValue();
          return new TextDecoder("utf-8").decode(dv).trim() || null;
        } catch {
          return null;
        }
      };
      const [serialNumber, modelNumber, firmwareRevision, hardwareRevision, manufacturerName] =
        await Promise.all(Object.values(DIS_CHARS).map(readOptional));
      const identifier =
        serialNumber ?? [manufacturerName, modelNumber].filter(Boolean).join("-") ?? "unknown";
      return { serialNumber, modelNumber, firmwareRevision, hardwareRevision, manufacturerName, identifier };
    } catch {
      return null;
    }
  }
}
```

- [ ] **Step 4: Write test for device info**

```typescript
// tests/09_device_info.test.ts
import { BLEDeviceInfo } from "../src/09_device_info.js";

test("read returns null if DIS absent", async () => {
  const server = {
    getPrimaryService: jest.fn().mockRejectedValue(new Error("not found")),
  } as unknown as BluetoothRemoteGATTServer;
  expect(await BLEDeviceInfo.read(server)).toBeNull();
});
```

- [ ] **Step 5: Run tests**

```bash
cd 08_javascript_web && npm test -- 08_battery && npm test -- 09_device_info
```

- [ ] **Step 6: Commit**

```bash
git add 08_javascript_web/src/08_battery.ts 08_javascript_web/tests/08_battery.test.ts \
        08_javascript_web/src/09_device_info.ts 08_javascript_web/tests/09_device_info.test.ts
git commit -m "feat(phase03): Sprints 44-45 — BLEBattery + BLEDeviceInfo"
```

---

## Task 10: HeartRateParser + PolarEcgParser

**Files:**
- Create: `08_javascript_web/src/10_heart_rate.ts`
- Create: `08_javascript_web/tests/10_heart_rate.test.ts`

This is the most complex parser. Implement with thorough tests.

- [ ] **Step 1: Write failing tests**

```typescript
// tests/10_heart_rate.test.ts
import { HeartRateParser, PolarEcgParser } from "../src/10_heart_rate.js";

// ── Heart Rate ──
test("parse uint8 BPM (flags bit 0 = 0)", () => {
  const data = new DataView(new Uint8Array([0x00, 73]).buffer);
  const result = HeartRateParser.parse(data);
  expect(result?.bpm).toBe(73);
  expect(result?.isUint16).toBe(false);
});

test("parse uint16 BPM (flags bit 0 = 1)", () => {
  const data = new DataView(new Uint8Array([0x01, 0xc8, 0x00]).buffer);  // BPM = 200
  const result = HeartRateParser.parse(data);
  expect(result?.bpm).toBe(200);
  expect(result?.isUint16).toBe(true);
});

test("parse RR intervals (flags bit 4 = 1, uint8 BPM)", () => {
  const data = new DataView(new Uint8Array([0x10, 73, 0x40, 0x02]).buffer);
  const result = HeartRateParser.parse(data);
  expect(result?.bpm).toBe(73);
  expect(result?.rrIntervals).toEqual([0x0240]);
});

// ── Polar ECG ──
test("parse Polar ECG frame with two int24 samples", () => {
  // Byte 0: type 0x00 (ECG)
  // Bytes 1-8: timestamp (uint64 LE) = 0
  // Byte 9: frame type 0x00
  // Bytes 10-12: sample 1 = 100 µV (int24 LE)
  // Bytes 13-15: sample 2 = -100 µV (int24 LE)
  const buf = new Uint8Array(16);
  buf[0] = 0x00;             // ECG type
  // timestamp = 0 (bytes 1-8 already 0)
  buf[9] = 0x00;             // frame type
  buf[10] = 100; buf[11] = 0; buf[12] = 0;   // 100 µV
  buf[13] = 156; buf[14] = 255; buf[15] = 255; // -100 as int24 LE (0xFFFF9C)
  const frame = PolarEcgParser.parse(new DataView(buf.buffer));
  expect(frame).not.toBeNull();
  expect(frame!.samplesUv[0]).toBe(100);
  expect(frame!.samplesUv[1]).toBe(-100);
});
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
cd 08_javascript_web && npm test -- 10_heart_rate
```

- [ ] **Step 3: Implement `src/10_heart_rate.ts`**

```typescript
// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 10 — Heart Rate + Polar ECG
// Sprint  : Sprint 46
//
// Parsers:
//   HeartRateParser — GATT 0x2A37 (standard HRM profile)
//   PolarEcgParser  — Polar PMD proprietary ECG data
//
// Reference:
//   GATT Heart Rate Service Specification v1.0
//   Polar BLE SDK — pmd_data.md
// ─────────────────────────────────────────────────────

// ── GATT UUIDs ─────────────────────────────────────
export const HEART_RATE_SERVICE_UUID = "heart_rate";              // 0x180D
export const HEART_RATE_MEASUREMENT_UUID = "heart_rate_measurement"; // 0x2A37

// ── Polar PMD UUIDs (proprietary) ──────────────────
export const POLAR_PMD_SERVICE_UUID   = "fb005c80-02e7-f387-1cad-8acd2d8df0c8";
export const POLAR_PMD_CONTROL_UUID   = "fb005c81-02e7-f387-1cad-8acd2d8df0c8";
export const POLAR_PMD_DATA_UUID      = "fb005c82-02e7-f387-1cad-8acd2d8df0c8";

/** Start ECG streaming command (written to PMD control point). */
export const POLAR_ECG_START_COMMAND = new Uint8Array([0x02, 0x00, 0x00, 0x01, 0x82, 0x00, 0x01, 0x01, 0x0E, 0x00]);
/** Stop ECG streaming command. */
export const POLAR_ECG_STOP_COMMAND  = new Uint8Array([0x03, 0x00]);
export const POLAR_ECG_SAMPLE_RATE = 130; // Hz

// ── Data types ──────────────────────────────────────

export interface HeartRateMeasurement {
  bpm: number;
  isUint16: boolean;
  sensorContact: boolean | null;
  rrIntervals: number[];    // 1/1024 second units
}

export interface PolarEcgFrame {
  timestampNs: bigint;
  samplesUv: number[];
}

// ── Parsers ─────────────────────────────────────────

export enum HeartRateParser {
  _unused = 0,
}

export namespace HeartRateParser {
  /**
   * Parse a Heart Rate Measurement characteristic value.
   *
   * Byte 0: flags
   *   bit 0 — BPM format (0=uint8, 1=uint16)
   *   bit 1-2 — sensor contact
   *   bit 3 — energy expended present
   *   bit 4 — RR interval present
   */
  export function parse(data: DataView): HeartRateMeasurement | null {
    if (data.byteLength < 2) return null;
    const flags = data.getUint8(0);
    const isUint16 = (flags & 0x01) !== 0;
    const hasSensorContact = (flags & 0x06) === 0x06;
    const sensorContact = (flags & 0x04) ? hasSensorContact : null;
    const hasEnergy = (flags & 0x08) !== 0;
    const hasRR = (flags & 0x10) !== 0;

    let offset = 1;
    const bpm = isUint16 ? data.getUint16(offset, true) : data.getUint8(offset);
    offset += isUint16 ? 2 : 1;
    if (hasEnergy) offset += 2;

    const rrIntervals: number[] = [];
    if (hasRR) {
      while (offset + 1 < data.byteLength) {
        rrIntervals.push(data.getUint16(offset, true));
        offset += 2;
      }
    }
    return { bpm, isUint16, sensorContact, rrIntervals };
  }
}

export enum PolarEcgParser {
  _unused = 0,
}

export namespace PolarEcgParser {
  /**
   * Parse a Polar PMD ECG data frame.
   *
   * Frame layout:
   *   Byte 0:   type (0x00 = ECG)
   *   Bytes 1-8: timestamp (uint64 LE, nanoseconds)
   *   Byte 9:   frame type (0x00)
   *   Bytes 10+: int24 LE samples in µV
   */
  export function parse(data: DataView): PolarEcgFrame | null {
    if (data.byteLength < 10) return null;
    if (data.getUint8(0) !== 0x00) return null;  // Not ECG type

    // Read uint64 timestamp (BigInt, little-endian)
    const lo = BigInt(data.getUint32(1, true));
    const hi = BigInt(data.getUint32(5, true));
    const timestampNs = lo | (hi << 32n);

    const samplesUv: number[] = [];
    let offset = 10;  // Skip type (1) + timestamp (8) + frame type (1)
    while (offset + 2 < data.byteLength) {
      // Read signed 24-bit little-endian integer
      const b0 = data.getUint8(offset);
      const b1 = data.getUint8(offset + 1);
      const b2 = data.getUint8(offset + 2);
      let value = b0 | (b1 << 8) | (b2 << 16);
      if (value & 0x800000) value -= 0x1000000;  // Sign-extend
      samplesUv.push(value);
      offset += 3;
    }
    return { timestampNs, samplesUv };
  }
}
```

- [ ] **Step 4: Run test — expect PASS**

```bash
cd 08_javascript_web && npm test -- 10_heart_rate
```

- [ ] **Step 5: Commit**

```bash
git add 08_javascript_web/src/10_heart_rate.ts 08_javascript_web/tests/10_heart_rate.test.ts
git commit -m "feat(phase03): Sprint 46 — HeartRateParser + PolarEcgParser"
```

---

## Task 11: MuseEegParser

**Files:**
- Create: `08_javascript_web/src/11_muse_eeg.ts`
- Create: `08_javascript_web/tests/11_muse_eeg.test.ts`

- [ ] **Step 1: Write failing tests**

```typescript
// tests/11_muse_eeg.test.ts
import { MuseEegParser, unpackUint12, MUSE_SCALE_FACTOR, MUSE_OFFSET } from "../src/11_muse_eeg.js";

test("unpackUint12 unpacks 3 bytes into 2 samples", () => {
  // 0xABC = 2748, 0xDEF = 3567
  // bytes: 0xAB, 0xCD, 0xEF
  const data = new DataView(new Uint8Array([0xAB, 0xCD, 0xEF]).buffer);
  const samples = unpackUint12(data, 0);
  expect(samples[0]).toBe(0xABC);
  expect(samples[1]).toBe(0xDEF);
});

test("parse returns 12 µV samples from 20-byte packet", () => {
  const buf = new Uint8Array(20);
  // counter = 1 (bytes 0-1 big-endian)
  buf[0] = 0x00; buf[1] = 0x01;
  // 18 bytes of sample data (all zeros → raw = 0, µV = (0 - 2048) * 0.488... = -1000)
  const packet = MuseEegParser.parse(new DataView(buf.buffer), "TP9");
  expect(packet).not.toBeNull();
  expect(packet!.counter).toBe(1);
  expect(packet!.rawSamples).toHaveLength(12);
  expect(packet!.samplesUv).toHaveLength(12);
  // µV for raw=0: (0 - 2048) * MUSE_SCALE_FACTOR
  expect(packet!.samplesUv[0]).toBeCloseTo(-2048 * MUSE_SCALE_FACTOR, 2);
});
```

- [ ] **Step 2: Run test — expect FAIL**

```bash
cd 08_javascript_web && npm test -- 11_muse_eeg
```

- [ ] **Step 3: Implement `src/11_muse_eeg.ts`**

```typescript
// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 11 — Muse 2 EEG Parser
// Sprint  : Sprint 47
//
// Parses 20-byte EEG packets from Muse 2 / Muse S.
//
// Packet format:
//   Bytes 0-1  : uint16 BE counter
//   Bytes 2-19 : 18 bytes → 12 × 12-bit packed samples
//
// Scaling: µV = MUSE_SCALE_FACTOR × (raw - MUSE_OFFSET)
//   where MUSE_SCALE_FACTOR = 0.48828125, MUSE_OFFSET = 2048
//
// Reference: Muse LSL connector source, mind-monitor protocol notes
// ─────────────────────────────────────────────────────

export const MUSE_SERVICE_UUID   = "0000fe8d-0000-1000-8000-00805f9b34fb";
export const MUSE_CONTROL_UUID   = "273e0001-4c4d-454d-96be-f03bac821358";
export const MUSE_TELEMETRY_UUID = "273e000b-4c4d-454d-96be-f03bac821358";

export const MUSE_EEG_CHANNEL_UUIDS: Record<string, string> = {
  TP9:  "273e0003-4c4d-454d-96be-f03bac821358",
  AF7:  "273e0004-4c4d-454d-96be-f03bac821358",
  AF8:  "273e0005-4c4d-454d-96be-f03bac821358",
  TP10: "273e0006-4c4d-454d-96be-f03bac821358",
};
export const MUSE_CHANNEL_NAMES = ["TP9", "AF7", "AF8", "TP10"];

export const MUSE_SAMPLE_RATE = 256;        // Hz
export const MUSE_SCALE_FACTOR = 0.48828125;
export const MUSE_OFFSET = 2048;
export const MUSE_SAMPLES_PER_PACKET = 12;

// Handshake commands written to MUSE_CONTROL_UUID
export const MUSE_ASK_COMMAND    = new TextEncoder().encode("\x02\x76\x01\x0a");
export const MUSE_STREAM_COMMAND = new TextEncoder().encode("\x02\x64\x0a");
export const MUSE_STOP_COMMAND   = new TextEncoder().encode("\x02\x68\x0a");

export interface MuseEegPacket {
  channel: string;
  counter: number;
  rawSamples: number[];
  /** Scaled samples in µV: MUSE_SCALE_FACTOR × (raw − MUSE_OFFSET) */
  samplesUv: number[];
}

/**
 * Unpack two 12-bit values from 3 bytes.
 * High nibble + full byte = first sample; low nibble + next byte = second.
 *
 * @param data - DataView containing packed data
 * @param offset - Byte offset to start reading (reads 3 bytes)
 * @returns Array of 2 unsigned 12-bit integers
 */
export function unpackUint12(data: DataView, offset: number): [number, number] {
  const b0 = data.getUint8(offset);
  const b1 = data.getUint8(offset + 1);
  const b2 = data.getUint8(offset + 2);
  return [(b0 << 4) | (b1 >> 4), ((b1 & 0x0f) << 8) | b2];
}

export enum MuseEegParser {
  _unused = 0,
}

export namespace MuseEegParser {
  /**
   * Parse a 20-byte Muse EEG notification packet.
   *
   * @param data - 20-byte DataView from the characteristic notification
   * @param channel - Channel name: "TP9" | "AF7" | "AF8" | "TP10"
   * @returns Parsed packet, or null if data is malformed.
   */
  export function parse(data: DataView, channel: string): MuseEegPacket | null {
    if (data.byteLength < 20) return null;
    const counter = data.getUint16(0, false); // Big-endian
    const rawSamples: number[] = [];
    for (let i = 0; i < 6; i++) {
      const [a, b] = unpackUint12(data, 2 + i * 3);
      rawSamples.push(a, b);
    }
    const samplesUv = rawSamples.map((r) => MUSE_SCALE_FACTOR * (r - MUSE_OFFSET));
    return { channel, counter, rawSamples, samplesUv };
  }
}
```

- [ ] **Step 4: Run test — expect PASS**

```bash
cd 08_javascript_web && npm test -- 11_muse_eeg
```

- [ ] **Step 5: Commit**

```bash
git add 08_javascript_web/src/11_muse_eeg.ts 08_javascript_web/tests/11_muse_eeg.test.ts
git commit -m "feat(phase03): Sprint 47 — MuseEegParser (12-bit packed samples)"
```

---

## Task 12: BLEError hierarchy + withBLETimeout

**Files:**
- Create: `08_javascript_web/src/12_errors.ts`
- Create: `08_javascript_web/tests/12_errors.test.ts`

- [ ] **Step 1: Implement + test**

```typescript
// src/12_errors.ts
// ─────────────────────────────────────────────────────
// Module  : 12 — Error Hierarchy + Timeout Utility
// Sprint  : Sprint 48
// ─────────────────────────────────────────────────────

export class BLEError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "BLEError";
  }
}

export class BLETimeoutError extends BLEError {
  constructor(operation: string, seconds: number) {
    super(`BLE timeout: ${operation} exceeded ${seconds}s`);
    this.name = "BLETimeoutError";
  }
}

export class BLEServiceNotFoundError extends BLEError {
  constructor(uuid: string) {
    super(`BLE service not found: ${uuid}`);
    this.name = "BLEServiceNotFoundError";
  }
}

/**
 * Race a promise against a timeout.
 * Throws BLETimeoutError if the promise doesn't resolve in time.
 */
export async function withBLETimeout<T>(
  promise: Promise<T>,
  seconds: number,
  operation = "BLE operation"
): Promise<T> {
  const timeout = new Promise<never>((_, reject) =>
    setTimeout(() => reject(new BLETimeoutError(operation, seconds)), seconds * 1000)
  );
  return Promise.race([promise, timeout]);
}
```

```typescript
// tests/12_errors.test.ts
import { withBLETimeout, BLETimeoutError } from "../src/12_errors.js";

test("withBLETimeout resolves if promise completes in time", async () => {
  const result = await withBLETimeout(Promise.resolve(42), 1);
  expect(result).toBe(42);
});

test("withBLETimeout throws BLETimeoutError if too slow", async () => {
  const slow = new Promise((r) => setTimeout(r, 500));
  await expect(withBLETimeout(slow, 0.01, "test op")).rejects.toBeInstanceOf(BLETimeoutError);
});
```

- [ ] **Step 2: Run test + commit**

```bash
cd 08_javascript_web && npm test -- 12_errors
git add 08_javascript_web/src/12_errors.ts 08_javascript_web/tests/12_errors.test.ts
git commit -m "feat(phase03): Sprint 48 — BLEError hierarchy + withBLETimeout"
```

---

## Task 13–15: Informational stubs (MTU, Pairing, Peripheral)

**Files:**
- Create: `08_javascript_web/src/13_mtu.ts` + test
- Create: `08_javascript_web/src/14_pairing.ts` + test
- Create: `08_javascript_web/src/15_peripheral.ts` + test

These three modules document Web Bluetooth limitations. Each is a short stub.

- [ ] **Step 1: Implement `src/13_mtu.ts`**

```typescript
// ─────────────────────────────────────────────────────
// Module  : 13 — MTU (informational)
// Sprint  : Sprint 49
//
// Web Bluetooth limitation:
//   MTU is negotiated automatically by the browser.
//   There is no Web Bluetooth API to query or set MTU.
//   The browser typically negotiates 512 bytes on modern
//   Android/Chrome. On iOS Safari (not supported), N/A.
//
//   maxWriteLength is available as a heuristic.
// ─────────────────────────────────────────────────────

export const DEFAULT_BLE_MTU = 23;     // BLE minimum
export const ATT_HEADER_BYTES = 3;
export const MAX_BLE_MTU = 512;        // Browser-negotiated max

export function effectivePayloadSize(mtu: number): number {
  return Math.max(1, mtu - ATT_HEADER_BYTES);
}
```

- [ ] **Step 2: Implement `src/14_pairing.ts`**

```typescript
// ─────────────────────────────────────────────────────
// Module  : 14 — Pairing (informational stub)
// Sprint  : Sprint 50
//
// Web Bluetooth limitation:
//   Like Apple CoreBluetooth, pairing is fully automatic
//   and managed by the browser + OS. There is no
//   programmatic pair/unpair API.
//
//   Pairing is triggered transparently when the app
//   attempts to access a characteristic that requires
//   authentication. The browser shows its own dialog.
// ─────────────────────────────────────────────────────

export type BLEPairingStatus = "unknown" | "pairing_required" | "likely_paired";

export function inferPairingStatus(error: unknown): BLEPairingStatus {
  if (error instanceof DOMException && error.name === "SecurityError") {
    return "pairing_required";
  }
  return "unknown";
}
```

- [ ] **Step 3: Implement `src/15_peripheral.ts`**

```typescript
// ─────────────────────────────────────────────────────
// Module  : 15 — Peripheral Mode (not available)
// Sprint  : Sprint 51
//
// Web Bluetooth limitation:
//   Web Bluetooth is CENTRAL-ONLY. There is no API for
//   acting as a BLE peripheral (GATT server) from a
//   web page.
//
//   For peripheral mode from a web app:
//   - Use Electron (exposes Node.js noble / bleno)
//   - Use React Native (exposes native peripheral APIs)
//   - Use a native app (Swift CBPeripheralManager, etc.)
// ─────────────────────────────────────────────────────

export function webBluetoothPeripheralModeSupported(): false {
  return false;
}
```

- [ ] **Step 4: Write minimal tests + run + commit**

```bash
cd 08_javascript_web && npm test -- 13_mtu 14_pairing 15_peripheral
git add 08_javascript_web/src/13_mtu.ts 08_javascript_web/src/14_pairing.ts \
        08_javascript_web/src/15_peripheral.ts \
        08_javascript_web/tests/13_mtu.test.ts 08_javascript_web/tests/14_pairing.test.ts \
        08_javascript_web/tests/15_peripheral.test.ts
git commit -m "feat(phase03): Sprints 49-51 — MTU/Pairing/Peripheral stubs with limitation docs"
```

---

## Task 16–18: RSSI/Distance, CommandQueue, DFU

**Files:**
- Create: `08_javascript_web/src/16_rssi_distance.ts` + test
- Create: `08_javascript_web/src/17_command_queue.ts` + test
- Create: `08_javascript_web/src/18_ota_dfu.ts` + test

These three are pure logic modules — no Web Bluetooth API calls. Port directly from Phase 01.

- [ ] **Step 1: Implement `src/16_rssi_distance.ts`**

```typescript
// Sprint 52 — RSSI-to-distance estimation
// Web Bluetooth note: RSSI is NOT available during requestDevice().
// Post-connection RSSI requires vendor-specific characteristics.
// This module provides the estimation algorithm only.

export type ProximityZone = "immediate" | "near" | "far";

export const DEFAULT_TX_POWER = -59;       // dBm at 1m (iBeacon standard)
export const DEFAULT_PATH_LOSS_EXPONENT = 2.0;

/** d = 10 ^ ((txPower - rssi) / (10 × n)) */
export function estimateDistance(rssi: number, txPower = DEFAULT_TX_POWER, n = DEFAULT_PATH_LOSS_EXPONENT): number {
  return Math.pow(10, (txPower - rssi) / (10 * n));
}

export function classifyProximity(distanceM: number): ProximityZone {
  if (distanceM < 0.5) return "immediate";
  if (distanceM < 3.0) return "near";
  return "far";
}

export function smoothRSSI(readings: number[], alpha = 0.3): number[] {
  if (readings.length === 0) return [];
  const result = [readings[0]];
  for (let i = 1; i < readings.length; i++) {
    result.push(alpha * readings[i] + (1 - alpha) * result[i - 1]);
  }
  return result;
}
```

- [ ] **Step 2: Implement `src/17_command_queue.ts`**

```typescript
// Sprint 53 — Promise-based serial BLE command queue
// Serializes GATT operations to prevent concurrent access.

export class BLECommandQueue {
  private _queue = Promise.resolve();

  /**
   * Enqueue an operation. Operations run serially in FIFO order.
   * Each operation waits for the previous to complete (or fail).
   */
  enqueue<T>(operation: () => Promise<T>): Promise<T> {
    const result = this._queue.then(operation);
    // Chain without propagating errors (so one failed op doesn't block the queue)
    this._queue = result.then(() => {}, () => {});
    return result;
  }
}
```

- [ ] **Step 3: Implement `src/18_ota_dfu.ts`**

```typescript
// Sprint 54 — OTA/DFU firmware chunking + transfer

export const DEFAULT_DFU_CHUNK_SIZE = 244;  // MTU 247 - 3 ATT header

export type DFUState = "idle" | "transferring" | "validating" | "completed" | "failed";

export interface DFUProgress {
  bytesSent: number;
  totalBytes: number;
  chunksSent: number;
  totalChunks: number;
  state: DFUState;
  progressPct: number;
}

export function chunkFirmware(data: Uint8Array, chunkSize = DEFAULT_DFU_CHUNK_SIZE): Uint8Array[] {
  const chunks: Uint8Array[] = [];
  for (let i = 0; i < data.byteLength; i += chunkSize) {
    chunks.push(data.slice(i, i + chunkSize));
  }
  return chunks;
}

export async function validateFirmware(data: Uint8Array, expectedSHA256: string): Promise<boolean> {
  const hashBuffer = await crypto.subtle.digest("SHA-256", data);
  const hex = Array.from(new Uint8Array(hashBuffer)).map((b) => b.toString(16).padStart(2, "0")).join("");
  return hex === expectedSHA256.toLowerCase();
}

export async function dfuTransfer(
  chunks: Uint8Array[],
  writeFn: (chunk: Uint8Array) => Promise<void>,
  onProgress?: (progress: DFUProgress) => void,
  writeDelayMs = 20
): Promise<DFUProgress> {
  const total = chunks.reduce((s, c) => s + c.byteLength, 0);
  let sent = 0;
  for (let i = 0; i < chunks.length; i++) {
    await writeFn(chunks[i]);
    sent += chunks[i].byteLength;
    onProgress?.({
      bytesSent: sent, totalBytes: total,
      chunksSent: i + 1, totalChunks: chunks.length,
      state: "transferring", progressPct: (sent / total) * 100,
    });
    if (writeDelayMs > 0 && i < chunks.length - 1) {
      await new Promise((r) => setTimeout(r, writeDelayMs));
    }
  }
  return { bytesSent: sent, totalBytes: total, chunksSent: chunks.length, totalChunks: chunks.length, state: "completed", progressPct: 100 };
}
```

- [ ] **Step 4: Write tests for all three + run**

```bash
cd 08_javascript_web && npm test -- 16_rssi 17_command 18_ota
```

- [ ] **Step 5: Commit**

```bash
git add 08_javascript_web/src/16_rssi_distance.ts 08_javascript_web/src/17_command_queue.ts \
        08_javascript_web/src/18_ota_dfu.ts \
        08_javascript_web/tests/16_rssi_distance.test.ts 08_javascript_web/tests/17_command_queue.test.ts \
        08_javascript_web/tests/18_ota_dfu.test.ts
git commit -m "feat(phase03): Sprints 52-54 — RSSIDistance, CommandQueue, DFU"
```

---

## Task 19: index.ts — public re-exports

**Files:**
- Create: `08_javascript_web/src/index.ts`

- [ ] **Step 1: Create `src/index.ts`**

```typescript
// BLE Reference Library — Web Bluetooth
// Public API surface

export * from "./01_scan.js";
export * from "./02_scan_filtered.js";
export * from "./03_connection.js";
export * from "./04_services_discovery.js";
export * from "./05_read_write.js";
export * from "./06_notifications.js";
export * from "./07_scan_no_connect.js";
export * from "./08_battery.js";
export * from "./09_device_info.js";
export * from "./10_heart_rate.js";
export * from "./11_muse_eeg.js";
export * from "./12_errors.js";
export * from "./13_mtu.js";
export * from "./14_pairing.js";
export * from "./15_peripheral.js";
export * from "./16_rssi_distance.js";
export * from "./17_command_queue.js";
export * from "./18_ota_dfu.js";
```

- [ ] **Step 2: Build**

```bash
cd 08_javascript_web && npm run build
```

Expected: `dist/` created with `index.js`, `index.cjs`, `index.d.ts`.

- [ ] **Step 3: Run all tests**

```bash
cd 08_javascript_web && npm test
```

Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add 08_javascript_web/src/index.ts
git commit -m "feat(phase03): public API index"
```

---

## Task 20: TestApp — static HTML integration test harness

**Files:**
- Create: `08_javascript_web/TestApp/index.html`
- Create: `08_javascript_web/TestApp/polar-runner.js`
- Create: `08_javascript_web/TestApp/muse-runner.js`

Open in Chrome served via `npx serve .` (Web Bluetooth requires a secure context — `http://localhost` qualifies). Uses ES module imports from `../dist/index.js`.

- [ ] **Step 1: Create `TestApp/index.html`**

Full tab-based HTML page with:
- Polar H10 tab: "Run Test" button, live log, ECG canvas chart
- Muse 2 tab: "Run Test" button, live log, EEG canvas chart (4 channels)
- Chart.js via CDN (for canvas charting)

(Full HTML code to be written inline — see below for structure)

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>BLE Reference — Web Bluetooth TestApp</title>
  <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
  <style>
    body { font-family: sans-serif; max-width: 900px; margin: 0 auto; padding: 20px; }
    .tabs { display: flex; gap: 8px; margin-bottom: 16px; }
    .tab { padding: 8px 16px; cursor: pointer; border: 1px solid #ccc; border-radius: 4px; }
    .tab.active { background: #0066cc; color: white; border-color: #0066cc; }
    .panel { display: none; }
    .panel.active { display: block; }
    button.run { padding: 10px 20px; font-size: 16px; background: #0066cc; color: white; border: none; border-radius: 4px; cursor: pointer; }
    #polar-log, #muse-log { height: 200px; overflow-y: auto; font-family: monospace; font-size: 12px; background: #f4f4f4; padding: 8px; border-radius: 4px; margin-top: 12px; }
    canvas { max-width: 100%; margin-top: 12px; }
  </style>
</head>
<body>
  <h1>BLE Reference — Web Bluetooth Integration Tests</h1>
  <p>Open in Chrome. Requires Bluetooth permission.</p>

  <div class="tabs">
    <div class="tab active" onclick="showTab('polar')">Polar H10 (ECG)</div>
    <div class="tab" onclick="showTab('muse')">Muse 2 (EEG)</div>
  </div>

  <div id="polar-panel" class="panel active">
    <button class="run" onclick="runPolar()">▶ Run Polar H10 Test</button>
    <button onclick="exportPolarPNG()" style="margin-left:8px">Export PNG</button>
    <div id="polar-log"></div>
    <canvas id="polar-chart" height="200"></canvas>
  </div>

  <div id="muse-panel" class="panel">
    <button class="run" onclick="runMuse()">▶ Run Muse 2 Test</button>
    <button onclick="exportMusePNG()" style="margin-left:8px">Export PNG</button>
    <div id="muse-log"></div>
    <canvas id="muse-chart" height="200"></canvas>
  </div>

  <script type="module" src="./polar-runner.js"></script>
  <script type="module" src="./muse-runner.js"></script>
  <script>
    function showTab(name) {
      document.querySelectorAll('.tab').forEach((t, i) => t.classList.toggle('active', ['polar','muse'][i] === name));
      document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
      document.getElementById(name + '-panel').classList.add('active');
    }
    function exportPolarPNG() {
      const a = document.createElement('a');
      a.href = document.getElementById('polar-chart').toDataURL('image/png');
      a.download = 'ecg_plot_' + new Date().toISOString().replace(/[:.]/g, '-') + '.png';
      a.click();
    }
    function exportMusePNG() {
      const a = document.createElement('a');
      a.href = document.getElementById('muse-chart').toDataURL('image/png');
      a.download = 'eeg_plot_' + new Date().toISOString().replace(/[:.]/g, '-') + '.png';
      a.click();
    }
  </script>
</body>
</html>
```

- [ ] **Step 2: Create `TestApp/polar-runner.js`** (11-step Polar H10 flow, imports from `../dist/`)

  **IMPORTANT:** Functions must be exposed on `window` to be callable from inline `onclick` handlers in the HTML — ES module scope is not global:
  ```js
  import { BLEScanner, ScanFilter, ... } from "../dist/index.js";
  window.runPolar = async function() { ... }
  ```

- [ ] **Step 3: Create `TestApp/muse-runner.js`** (11-step Muse 2 flow, imports from `../dist/`)

  Same `window.runMuse = async function() { ... }` pattern.

- [ ] **Step 4: Serve locally and test in Chrome**

```bash
cd 08_javascript_web/TestApp && npx serve .
```

Open `http://localhost:3000` in Chrome. Click "Run Polar H10 Test", pair with Polar H10.

- [ ] **Step 5: Commit**

```bash
git add 08_javascript_web/TestApp/
git commit -m "feat(phase03): TestApp — static HTML integration harness for Polar + Muse"
```

---

## Task 21: Sprint tracker + docs update

**Files:**
- Modify: `docs/Sprints/SPRINT_TRACKER.yaml`
- Modify: `docs/integration-test-protocol.md`

- [ ] **Step 1: Update `SPRINT_TRACKER.yaml`**

Change phase_03:
```yaml
- id: phase_03
  name: "Web Bluetooth (TypeScript)"
  folders: ["08_javascript_web"]
  sprint_range: [37, 54]
  git_tag: "phase03-complete"
  status: in_progress
```

Update sprint names 37-54 to match actual module names.

- [ ] **Step 2: Update integration-test-protocol.md Phase 03 row**

```markdown
| 03 | Web Bluetooth (TypeScript) | Sprint 46 | Sprint 47 | Chart.js canvas PNG |
```

- [ ] **Step 3: Commit**

```bash
git add docs/Sprints/SPRINT_TRACKER.yaml docs/integration-test-protocol.md
git commit -m "docs: update Phase 03 scope — Web Bluetooth TypeScript (remove Node.js)"
```

---

## Summary

| Task | Sprints | Commits |
|------|---------|---------|
| 00 Scaffolding | — | 1 |
| 01-06 Core BLE (scan/connect/discover/rw/notify) | 37–42 | 6 |
| 07 Advertisement scan | 43 | 1 |
| 08-09 Battery + DeviceInfo | 44-45 | 1 |
| 10 HeartRate + PolarECG | 46 | 1 |
| 11 MuseEEG | 47 | 1 |
| 12 Errors | 48 | 1 |
| 13-15 Stubs | 49-51 | 1 |
| 16-18 RSSI + Queue + DFU | 52-54 | 1 |
| 19 index.ts + build | — | 1 |
| 20 TestApp | — | 1 |
| 21 Docs update | — | 1 |
| **Total** | **18 sprints** | **17 commits** |
