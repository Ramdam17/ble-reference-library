// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 05 — Read & Write
// Sprint  : Sprint 41
//
// Web Bluetooth note:
//   readValue() returns a DataView (not Uint8Array).
//   writeValue() takes BufferSource.
// ─────────────────────────────────────────────────────

export class BLEReadWriteError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "BLEReadWriteError";
  }
}

export class BLEReadWriter {
  async read(characteristic: BluetoothRemoteGATTCharacteristic): Promise<DataView> {
    if (!characteristic.properties.read) {
      throw new BLEReadWriteError(`Characteristic ${characteristic.uuid} does not support read`);
    }
    return characteristic.readValue();
  }

  async write(characteristic: BluetoothRemoteGATTCharacteristic, data: BufferSource): Promise<void> {
    if (!characteristic.properties.write) {
      throw new BLEReadWriteError(`Characteristic ${characteristic.uuid} does not support write`);
    }
    await characteristic.writeValue(data);
  }

  async writeWithoutResponse(characteristic: BluetoothRemoteGATTCharacteristic, data: BufferSource): Promise<void> {
    if (!characteristic.properties.writeWithoutResponse) {
      throw new BLEReadWriteError(`Characteristic ${characteristic.uuid} does not support write-without-response`);
    }
    await characteristic.writeValueWithoutResponse(data);
  }

  async readAsString(characteristic: BluetoothRemoteGATTCharacteristic): Promise<string> {
    const dv = await this.read(characteristic);
    return new TextDecoder("utf-8").decode(dv);
  }
}
