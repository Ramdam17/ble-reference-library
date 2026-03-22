import { effectivePayloadSize } from "../src/13_mtu.js";

test("effectivePayloadSize(23) === 20", () => {
  expect(effectivePayloadSize(23)).toBe(20);
});

test("effectivePayloadSize(512) === 509", () => {
  expect(effectivePayloadSize(512)).toBe(509);
});
