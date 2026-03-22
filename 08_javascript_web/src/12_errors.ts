// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 12 — Error Hierarchy + Timeout Utility
// Sprint  : Sprint 48
// ─────────────────────────────────────────────────────

export class BLEError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "BLEError";
  }
}

export class BLETimeoutError extends BLEError {
  constructor(operation: string, seconds: number) {
    super(`BLE timeout: ${operation} exceeded ${seconds}s`);
    this.name = "BLETimeoutError";
  }
}

export class BLEServiceNotFoundError extends BLEError {
  constructor(uuid: string) {
    super(`BLE service not found: ${uuid}`);
    this.name = "BLEServiceNotFoundError";
  }
}

export async function withBLETimeout<T>(
  promise: Promise<T>,
  seconds: number,
  operation = "BLE operation"
): Promise<T> {
  const timeout = new Promise<never>((_, reject) =>
    setTimeout(() => reject(new BLETimeoutError(operation, seconds)), seconds * 1000)
  );
  return Promise.race([promise, timeout]);
}
