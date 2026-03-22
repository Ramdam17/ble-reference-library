import { MuseEegParser, unpackUint12, MUSE_SCALE_FACTOR, MUSE_OFFSET } from "../src/11_muse_eeg.js";

test("unpackUint12 unpacks 3 bytes into 2 samples", () => {
  const data = new DataView(new Uint8Array([0xAB, 0xCD, 0xEF]).buffer);
  const samples = unpackUint12(data, 0);
  expect(samples[0]).toBe(0xABC);
  expect(samples[1]).toBe(0xDEF);
});

test("parse returns 12 µV samples from 20-byte packet", () => {
  const buf = new Uint8Array(20);
  buf[0] = 0x00; buf[1] = 0x01;
  const packet = MuseEegParser.parse(new DataView(buf.buffer), "TP9");
  expect(packet).not.toBeNull();
  expect(packet!.counter).toBe(1);
  expect(packet!.rawSamples).toHaveLength(12);
  expect(packet!.samplesUv).toHaveLength(12);
  expect(packet!.samplesUv[0]).toBeCloseTo(-2048 * MUSE_SCALE_FACTOR, 2);
});
