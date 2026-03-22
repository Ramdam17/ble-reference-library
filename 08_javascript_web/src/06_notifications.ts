// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 06 — Notifications
// Sprint  : Sprint 42
//
// Web Bluetooth pattern:
//   1. char.startNotifications()
//   2. char.addEventListener("characteristicvaluechanged", handler)
//   3. value is at event.target.value (DataView)
// ─────────────────────────────────────────────────────

export type NotificationHandler = (value: DataView) => void;

export class BLENotificationSubscriber {
  private _handlers = new Map<string, EventListener>();

  async subscribe(characteristic: BluetoothRemoteGATTCharacteristic, handler: NotificationHandler): Promise<void> {
    await characteristic.startNotifications();
    const listener: EventListener = (event) => {
      const value = (event.target as BluetoothRemoteGATTCharacteristic).value;
      if (value) handler(value);
    };
    this._handlers.set(characteristic.uuid, listener);
    characteristic.addEventListener("characteristicvaluechanged", listener);
  }

  async unsubscribe(characteristic: BluetoothRemoteGATTCharacteristic): Promise<void> {
    const listener = this._handlers.get(characteristic.uuid);
    if (listener) {
      characteristic.removeEventListener("characteristicvaluechanged", listener);
      this._handlers.delete(characteristic.uuid);
    }
    await characteristic.stopNotifications();
  }

  async unsubscribeAll(characteristics: BluetoothRemoteGATTCharacteristic[]): Promise<void> {
    await Promise.all(characteristics.map((c) => this.unsubscribe(c)));
  }

  get activeSubscriptionCount(): number {
    return this._handlers.size;
  }
}
