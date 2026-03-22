// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 09 — Device Information Service (0x180A)
// Sprint  : Sprint 45
// ─────────────────────────────────────────────────────

export const DIS_SERVICE_UUID = "device_information";   // 0x180A

const DIS_CHARS = {
  serialNumber:     "serial_number_string",             // 0x2A25
  modelNumber:      "model_number_string",              // 0x2A24
  firmwareRevision: "firmware_revision_string",         // 0x2A26
  hardwareRevision: "hardware_revision_string",         // 0x2A27
  manufacturerName: "manufacturer_name_string",         // 0x2A29
};

export interface DeviceInfo {
  serialNumber: string | null;
  modelNumber: string | null;
  firmwareRevision: string | null;
  hardwareRevision: string | null;
  manufacturerName: string | null;
  identifier: string;
}

export class BLEDeviceInfo {
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
