import { webcrypto } from "node:crypto";
(global as any).crypto = webcrypto;

import { chunkFirmware, validateFirmware, dfuTransfer } from "../src/18_ota_dfu.js";

test("chunkFirmware(244 bytes, 244) = 1 chunk", () => {
  const data = new Uint8Array(244);
  const chunks = chunkFirmware(data, 244);
  expect(chunks).toHaveLength(1);
  expect(chunks[0].byteLength).toBe(244);
});

test("chunkFirmware(245 bytes, 244) = 2 chunks", () => {
  const data = new Uint8Array(245);
  const chunks = chunkFirmware(data, 244);
  expect(chunks).toHaveLength(2);
  expect(chunks[0].byteLength).toBe(244);
  expect(chunks[1].byteLength).toBe(1);
});

test("dfuTransfer calls writeFn for each chunk", async () => {
  const data = new Uint8Array(10).fill(0xAB);
  const chunks = chunkFirmware(data, 5);
  const written: Uint8Array[] = [];
  await dfuTransfer(chunks, async (c) => { written.push(c); }, undefined, 0);
  expect(written).toHaveLength(2);
});

test("validateFirmware returns true for matching SHA-256", async () => {
  const data = new Uint8Array([1, 2, 3]);
  // Compute expected hash
  const hashBuffer = await webcrypto.subtle.digest("SHA-256", data);
  const hex = Array.from(new Uint8Array(hashBuffer)).map((b) => b.toString(16).padStart(2, "0")).join("");
  expect(await validateFirmware(data, hex)).toBe(true);
});

test("validateFirmware returns false for wrong SHA-256", async () => {
  const data = new Uint8Array([1, 2, 3]);
  expect(await validateFirmware(data, "deadbeef")).toBe(false);
});
