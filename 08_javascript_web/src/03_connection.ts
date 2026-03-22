// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 03 — Connection Lifecycle
// Sprint  : Sprint 39
//
// Purpose : Connect to and disconnect from a BLE device's
//           GATT server.
//
// Web Bluetooth difference vs Phase 01/02:
//   - Connection is via device.gatt.connect()
//   - Disconnect events come via 'gattserverdisconnected'
//   - No native timeout — implemented manually
// ─────────────────────────────────────────────────────

const DEFAULT_TIMEOUT_MS = 10_000;

export class BLEConnectionError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "BLEConnectionError";
  }
}

export class BLEConnector {
  private _server: BluetoothRemoteGATTServer | null = null;
  private _disconnectHandler: (() => void) | null = null;
  onUnexpectedDisconnection: ((device: BluetoothDevice) => void) | null = null;

  async connect(device: BluetoothDevice, timeoutMs = DEFAULT_TIMEOUT_MS): Promise<BluetoothRemoteGATTServer> {
    const connectPromise = device.gatt!.connect().catch((err) => {
      throw new BLEConnectionError(`Connection failed: ${err}`, err);
    });
    const timeoutPromise = new Promise<never>((_, reject) =>
      setTimeout(() => reject(new BLEConnectionError(`Connection timeout after ${timeoutMs}ms`)), timeoutMs)
    );
    this._server = await Promise.race([connectPromise, timeoutPromise]);
    this._disconnectHandler = () => {
      if (this.onUnexpectedDisconnection) this.onUnexpectedDisconnection(device);
    };
    device.addEventListener("gattserverdisconnected", this._disconnectHandler);
    return this._server;
  }

  disconnect(device: BluetoothDevice): void {
    if (this._disconnectHandler) {
      device.removeEventListener("gattserverdisconnected", this._disconnectHandler);
      this._disconnectHandler = null;
    }
    device.gatt?.disconnect();
    this._server = null;
  }

  get server(): BluetoothRemoteGATTServer | null {
    return this._server;
  }
}
