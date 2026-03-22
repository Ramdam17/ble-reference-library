// ─────────────────────────────────────────────────────
// Module  : 17 — BLE Command Queue
// Sprint  : Sprint 53
//
// Promise-based serial queue. Serializes GATT operations
// to prevent concurrent access.
// ─────────────────────────────────────────────────────

export class BLECommandQueue {
  private _queue = Promise.resolve();

  enqueue<T>(operation: () => Promise<T>): Promise<T> {
    const result = this._queue.then(operation);
    this._queue = result.then(() => {}, () => {});
    return result;
  }
}
