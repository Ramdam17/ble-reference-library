// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 02 — Scan Filtered
// Sprint  : Sprint 38
//
// Purpose : Build RequestDeviceOptions for common filter
//           patterns.
//
// Web Bluetooth note:
//   optionalServices must list every service UUID you
//   intend to use after connecting, or access will be
//   blocked by the browser security model.
// ─────────────────────────────────────────────────────

export class ScanFilter {
  private constructor(private readonly _options: RequestDeviceOptions) {}

  static byName(name: string): ScanFilter {
    return new ScanFilter({ filters: [{ name }] });
  }

  static byNamePrefix(namePrefix: string): ScanFilter {
    return new ScanFilter({ filters: [{ namePrefix }] });
  }

  static byServiceUUID(uuid: string): ScanFilter {
    return new ScanFilter({
      filters: [{ services: [uuid] }],
      optionalServices: [uuid],
    });
  }

  static acceptAll(): ScanFilter {
    return new ScanFilter({ acceptAllDevices: true });
  }

  withOptionalServices(...uuids: string[]): ScanFilter {
    const existing = (this._options.optionalServices as string[]) ?? [];
    return new ScanFilter({ ...this._options, optionalServices: [...existing, ...uuids] });
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  toRequestDeviceOptions(): any {
    return this._options;
  }
}
