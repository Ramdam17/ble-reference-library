// tests/07_scan_no_connect.test.ts
import { BLEAdvertisementScanner } from "../src/07_scan_no_connect.js";

test("isSupported returns false when requestLEScan absent", () => {
  (global as any).navigator = { bluetooth: {} };
  expect(BLEAdvertisementScanner.isSupported()).toBe(false);
});

test("isSupported returns true when requestLEScan present", () => {
  (global as any).navigator = { bluetooth: { requestLEScan: jest.fn() } };
  expect(BLEAdvertisementScanner.isSupported()).toBe(true);
});
