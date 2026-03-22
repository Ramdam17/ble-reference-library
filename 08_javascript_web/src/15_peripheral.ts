// ─────────────────────────────────────────────────────
// Module  : 15 — Peripheral Mode (not available)
// Sprint  : Sprint 51
//
// Web Bluetooth limitation:
//   Central-only. No peripheral (GATT server) mode.
//
//   Alternatives: Electron + noble/bleno, React Native,
//   or a native app (Swift CBPeripheralManager).
// ─────────────────────────────────────────────────────

export function webBluetoothPeripheralModeSupported(): false {
  return false;
}
