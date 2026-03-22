// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 04 — Services & Characteristics Discovery
// Sprint  : Sprint 40
//
// Web Bluetooth note:
//   Only services listed in optionalServices (at
//   requestDevice() time) can be accessed.
// ─────────────────────────────────────────────────────

export interface DiscoveredCharacteristic {
  uuid: string;
  canRead: boolean;
  canWrite: boolean;
  canWriteWithoutResponse: boolean;
  canNotify: boolean;
  canIndicate: boolean;
  characteristic: BluetoothRemoteGATTCharacteristic;
}

export interface DiscoveredService {
  uuid: string;
  isPrimary: boolean;
  characteristics: DiscoveredCharacteristic[];
  service: BluetoothRemoteGATTService;
}

export class BLEServiceDiscoverer {
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
