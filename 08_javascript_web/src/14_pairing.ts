// ─────────────────────────────────────────────────────
// Module  : 14 — Pairing (informational stub)
// Sprint  : Sprint 50
//
// Web Bluetooth limitation:
//   Pairing is fully automatic (browser + OS).
//   No programmatic pair/unpair API exists.
// ─────────────────────────────────────────────────────

export type BLEPairingStatus = "unknown" | "pairing_required" | "likely_paired";

export function inferPairingStatus(error: unknown): BLEPairingStatus {
  if (error instanceof DOMException && error.name === "SecurityError") {
    return "pairing_required";
  }
  return "unknown";
}
