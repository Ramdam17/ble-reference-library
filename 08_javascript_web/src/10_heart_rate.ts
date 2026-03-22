// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 10 — Heart Rate + Polar ECG
// Sprint  : Sprint 46
//
// Parsers:
//   HeartRateParser — GATT 0x2A37 (standard HRM profile)
//   PolarEcgParser  — Polar PMD proprietary ECG data
//
// Reference:
//   GATT Heart Rate Service Specification v1.0
//   Polar BLE SDK — pmd_data.md
// ─────────────────────────────────────────────────────

export const HEART_RATE_SERVICE_UUID = "heart_rate";
export const HEART_RATE_MEASUREMENT_UUID = "heart_rate_measurement";
export const POLAR_PMD_SERVICE_UUID   = "fb005c80-02e7-f387-1cad-8acd2d8df0c8";
export const POLAR_PMD_CONTROL_UUID   = "fb005c81-02e7-f387-1cad-8acd2d8df0c8";
export const POLAR_PMD_DATA_UUID      = "fb005c82-02e7-f387-1cad-8acd2d8df0c8";
export const POLAR_ECG_START_COMMAND = new Uint8Array([0x02, 0x00, 0x00, 0x01, 0x82, 0x00, 0x01, 0x01, 0x0E, 0x00]);
export const POLAR_ECG_STOP_COMMAND  = new Uint8Array([0x03, 0x00]);
export const POLAR_ECG_SAMPLE_RATE = 130;

export interface HeartRateMeasurement {
  bpm: number;
  isUint16: boolean;
  sensorContact: boolean | null;
  rrIntervals: number[];
}

export interface PolarEcgFrame {
  timestampNs: bigint;
  samplesUv: number[];
}

export enum HeartRateParser { _unused = 0 }
export namespace HeartRateParser {
  export function parse(data: DataView): HeartRateMeasurement | null {
    if (data.byteLength < 2) return null;
    const flags = data.getUint8(0);
    const isUint16 = (flags & 0x01) !== 0;
    const sensorContact = (flags & 0x04) ? (flags & 0x06) === 0x06 : null;
    const hasEnergy = (flags & 0x08) !== 0;
    const hasRR = (flags & 0x10) !== 0;
    let offset = 1;
    const bpm = isUint16 ? data.getUint16(offset, true) : data.getUint8(offset);
    offset += isUint16 ? 2 : 1;
    if (hasEnergy) offset += 2;
    const rrIntervals: number[] = [];
    if (hasRR) {
      while (offset + 1 < data.byteLength) {
        rrIntervals.push(data.getUint16(offset, true));
        offset += 2;
      }
    }
    return { bpm, isUint16, sensorContact, rrIntervals };
  }
}

export enum PolarEcgParser { _unused = 0 }
export namespace PolarEcgParser {
  export function parse(data: DataView): PolarEcgFrame | null {
    if (data.byteLength < 10) return null;
    if (data.getUint8(0) !== 0x00) return null;
    const lo = BigInt(data.getUint32(1, true));
    const hi = BigInt(data.getUint32(5, true));
    const timestampNs = lo | (hi << 32n);
    const samplesUv: number[] = [];
    let offset = 10;
    while (offset + 2 < data.byteLength) {
      const b0 = data.getUint8(offset);
      const b1 = data.getUint8(offset + 1);
      const b2 = data.getUint8(offset + 2);
      let value = b0 | (b1 << 8) | (b2 << 16);
      if (value & 0x800000) value -= 0x1000000;
      samplesUv.push(value);
      offset += 3;
    }
    return { timestampNs, samplesUv };
  }
}
