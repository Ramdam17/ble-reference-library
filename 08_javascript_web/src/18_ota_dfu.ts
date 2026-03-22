// ─────────────────────────────────────────────────────
// Module  : 18 — OTA/DFU Firmware Transfer
// Sprint  : Sprint 54
// ─────────────────────────────────────────────────────

export const DEFAULT_DFU_CHUNK_SIZE = 244;
export type DFUState = "idle" | "transferring" | "validating" | "completed" | "failed";

export interface DFUProgress {
  bytesSent: number;
  totalBytes: number;
  chunksSent: number;
  totalChunks: number;
  state: DFUState;
  progressPct: number;
}

export function chunkFirmware(data: Uint8Array, chunkSize = DEFAULT_DFU_CHUNK_SIZE): Uint8Array[] {
  const chunks: Uint8Array[] = [];
  for (let i = 0; i < data.byteLength; i += chunkSize) {
    chunks.push(data.slice(i, i + chunkSize));
  }
  return chunks;
}

export async function validateFirmware(data: Uint8Array, expectedSHA256: string): Promise<boolean> {
  const hashBuffer = await crypto.subtle.digest("SHA-256", data.buffer as ArrayBuffer);
  const hex = Array.from(new Uint8Array(hashBuffer)).map((b) => b.toString(16).padStart(2, "0")).join("");
  return hex === expectedSHA256.toLowerCase();
}

export async function dfuTransfer(
  chunks: Uint8Array[],
  writeFn: (chunk: Uint8Array) => Promise<void>,
  onProgress?: (progress: DFUProgress) => void,
  writeDelayMs = 20
): Promise<DFUProgress> {
  const total = chunks.reduce((s, c) => s + c.byteLength, 0);
  let sent = 0;
  for (let i = 0; i < chunks.length; i++) {
    await writeFn(chunks[i]);
    sent += chunks[i].byteLength;
    onProgress?.({
      bytesSent: sent, totalBytes: total,
      chunksSent: i + 1, totalChunks: chunks.length,
      state: "transferring", progressPct: (sent / total) * 100,
    });
    if (writeDelayMs > 0 && i < chunks.length - 1) {
      await new Promise((r) => setTimeout(r, writeDelayMs));
    }
  }
  return { bytesSent: sent, totalBytes: total, chunksSent: chunks.length, totalChunks: chunks.length, state: "completed", progressPct: 100 };
}
