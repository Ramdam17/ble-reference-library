// ─────────────────────────────────────────────────────
// Module  : 13 — MTU (informational)
// Sprint  : Sprint 49
//
// Web Bluetooth limitation:
//   MTU is negotiated automatically by the browser.
//   No API to query or set MTU.
// ─────────────────────────────────────────────────────

export const DEFAULT_BLE_MTU = 23;
export const ATT_HEADER_BYTES = 3;
export const MAX_BLE_MTU = 512;

export function effectivePayloadSize(mtu: number): number {
  return Math.max(1, mtu - ATT_HEADER_BYTES);
}
