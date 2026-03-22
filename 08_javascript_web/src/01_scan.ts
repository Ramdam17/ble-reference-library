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

export interface DiscoveredDevice {
  id: string;
  name: string | null;
  device: BluetoothDevice;
}

export class BLEScanError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "BLEScanError";
  }
}

export class BLEScanner {
  async requestDevice(options: RequestDeviceOptions): Promise<DiscoveredDevice> {
    if (!navigator?.bluetooth) {
      throw new BLEScanError("Bluetooth not available in this browser. Use Chrome or Edge.");
    }
    const device = await navigator.bluetooth.requestDevice(options);
    return { id: device.id, name: device.name ?? null, device };
  }
}
