// ─────────────────────────────────────────────────────
// Module  : 16 — RSSI Distance Estimation
// Sprint  : Sprint 52
//
// Web Bluetooth note: RSSI not available during requestDevice().
// This module provides the estimation algorithm only.
// ─────────────────────────────────────────────────────

export type ProximityZone = "immediate" | "near" | "far";
export const DEFAULT_TX_POWER = -59;
export const DEFAULT_PATH_LOSS_EXPONENT = 2.0;

export function estimateDistance(rssi: number, txPower = DEFAULT_TX_POWER, n = DEFAULT_PATH_LOSS_EXPONENT): number {
  return Math.pow(10, (txPower - rssi) / (10 * n));
}

export function classifyProximity(distanceM: number): ProximityZone {
  if (distanceM < 0.5) return "immediate";
  if (distanceM < 3.0) return "near";
  return "far";
}

export function smoothRSSI(readings: number[], alpha = 0.3): number[] {
  if (readings.length === 0) return [];
  const result = [readings[0]];
  for (let i = 1; i < readings.length; i++) {
    result.push(alpha * readings[i] + (1 - alpha) * result[i - 1]);
  }
  return result;
}
