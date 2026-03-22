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
//   - NOT available in production Chrome without flag
//   - NOT available in Firefox, Safari, or Edge
//
// Recommendation: Use isSupported() guard before calling start().
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

export class BLEAdvertisementScanner {
  private _scan: any = null;

  static isSupported(): boolean {
    return typeof (navigator as any)?.bluetooth?.requestLEScan === "function";
  }

  async start(handler: AdvertisementHandler, options?: any): Promise<void> {
    if (!BLEAdvertisementScanner.isSupported()) {
      throw new Error(
        "requestLEScan() not available. Enable chrome://flags/#enable-experimental-web-platform-features"
      );
    }
    this._scan = await (navigator as any).bluetooth.requestLEScan(options ?? { acceptAllAdvertisements: true });
    (navigator as any).addEventListener("advertisementreceived", (event: any) => {
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

  stop(): void {
    this._scan?.stop();
    this._scan = null;
  }
}
