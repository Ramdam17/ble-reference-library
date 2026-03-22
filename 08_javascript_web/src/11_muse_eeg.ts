// ─────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth
// Module  : 11 — Muse 2 EEG Parser
// Sprint  : Sprint 47
//
// Packet format:
//   Bytes 0-1  : uint16 BE counter
//   Bytes 2-19 : 18 bytes → 12 × 12-bit packed samples
//
// Scaling: µV = MUSE_SCALE_FACTOR × (raw - MUSE_OFFSET)
// ─────────────────────────────────────────────────────

export const MUSE_SERVICE_UUID   = "0000fe8d-0000-1000-8000-00805f9b34fb";
export const MUSE_CONTROL_UUID   = "273e0001-4c4d-454d-96be-f03bac821358";
export const MUSE_TELEMETRY_UUID = "273e000b-4c4d-454d-96be-f03bac821358";
export const MUSE_EEG_CHANNEL_UUIDS: Record<string, string> = {
  TP9:  "273e0003-4c4d-454d-96be-f03bac821358",
  AF7:  "273e0004-4c4d-454d-96be-f03bac821358",
  AF8:  "273e0005-4c4d-454d-96be-f03bac821358",
  TP10: "273e0006-4c4d-454d-96be-f03bac821358",
};
export const MUSE_CHANNEL_NAMES = ["TP9", "AF7", "AF8", "TP10"];
export const MUSE_SAMPLE_RATE = 256;
export const MUSE_SCALE_FACTOR = 0.48828125;
export const MUSE_OFFSET = 2048;
export const MUSE_SAMPLES_PER_PACKET = 12;
export const MUSE_ASK_COMMAND    = new TextEncoder().encode("\x02\x76\x01\x0a");
export const MUSE_STREAM_COMMAND = new TextEncoder().encode("\x02\x64\x0a");
export const MUSE_STOP_COMMAND   = new TextEncoder().encode("\x02\x68\x0a");

export interface MuseEegPacket {
  channel: string;
  counter: number;
  rawSamples: number[];
  samplesUv: number[];
}

export function unpackUint12(data: DataView, offset: number): [number, number] {
  const b0 = data.getUint8(offset);
  const b1 = data.getUint8(offset + 1);
  const b2 = data.getUint8(offset + 2);
  return [(b0 << 4) | (b1 >> 4), ((b1 & 0x0f) << 8) | b2];
}

export enum MuseEegParser { _unused = 0 }
export namespace MuseEegParser {
  export function parse(data: DataView, channel: string): MuseEegPacket | null {
    if (data.byteLength < 20) return null;
    const counter = data.getUint16(0, false);
    const rawSamples: number[] = [];
    for (let i = 0; i < 6; i++) {
      const [a, b] = unpackUint12(data, 2 + i * 3);
      rawSamples.push(a, b);
    }
    const samplesUv = rawSamples.map((r) => MUSE_SCALE_FACTOR * (r - MUSE_OFFSET));
    return { channel, counter, rawSamples, samplesUv };
  }
}
