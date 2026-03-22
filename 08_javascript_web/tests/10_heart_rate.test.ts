import { HeartRateParser, PolarEcgParser } from "../src/10_heart_rate.js";

test("parse uint8 BPM (flags bit 0 = 0)", () => {
  const data = new DataView(new Uint8Array([0x00, 73]).buffer);
  const result = HeartRateParser.parse(data);
  expect(result?.bpm).toBe(73);
  expect(result?.isUint16).toBe(false);
});

test("parse uint16 BPM (flags bit 0 = 1)", () => {
  const data = new DataView(new Uint8Array([0x01, 0xc8, 0x00]).buffer);
  const result = HeartRateParser.parse(data);
  expect(result?.bpm).toBe(200);
  expect(result?.isUint16).toBe(true);
});

test("parse RR intervals (flags bit 4 = 1, uint8 BPM)", () => {
  const data = new DataView(new Uint8Array([0x10, 73, 0x40, 0x02]).buffer);
  const result = HeartRateParser.parse(data);
  expect(result?.bpm).toBe(73);
  expect(result?.rrIntervals).toEqual([0x0240]);
});

test("parse Polar ECG frame with two int24 samples", () => {
  const buf = new Uint8Array(16);
  buf[0] = 0x00;
  buf[9] = 0x00;
  buf[10] = 100; buf[11] = 0; buf[12] = 0;
  buf[13] = 156; buf[14] = 255; buf[15] = 255; // -100 as int24 LE
  const frame = PolarEcgParser.parse(new DataView(buf.buffer));
  expect(frame).not.toBeNull();
  expect(frame!.samplesUv[0]).toBe(100);
  expect(frame!.samplesUv[1]).toBe(-100);
});
