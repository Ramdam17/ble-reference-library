// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 08 — Battery Service (0x180F)
// Sprint  : Sprint 44
// ─────────────────────────────────────────────────────

export const BATTERY_SERVICE_UUID = "battery_service";   // 0x180F
export const BATTERY_LEVEL_UUID = "battery_level";       // 0x2A19

export class BLEBattery {
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
