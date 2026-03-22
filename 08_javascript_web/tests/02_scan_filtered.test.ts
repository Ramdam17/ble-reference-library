// tests/02_scan_filtered.test.ts
import { ScanFilter } from "../src/02_scan_filtered.js";

test("byName produces name filter", () => {
  const opts = ScanFilter.byName("Polar H10").toRequestDeviceOptions();
  expect(opts.filters).toEqual([{ name: "Polar H10" }]);
});

test("byNamePrefix produces namePrefix filter", () => {
  const opts = ScanFilter.byNamePrefix("Polar").toRequestDeviceOptions();
  expect(opts.filters).toEqual([{ namePrefix: "Polar" }]);
});

test("byServiceUUID produces services filter", () => {
  const opts = ScanFilter.byServiceUUID("180D").toRequestDeviceOptions();
  expect(opts.filters).toEqual([{ services: ["180D"] }]);
  expect(opts.optionalServices).toContain("180D");
});

test("acceptAll produces acceptAllDevices: true", () => {
  const opts = ScanFilter.acceptAll().toRequestDeviceOptions();
  expect(opts.acceptAllDevices).toBe(true);
});

test("withOptionalServices adds additional service UUIDs", () => {
  const opts = ScanFilter.byServiceUUID("180D")
    .withOptionalServices("180A", "180B")
    .toRequestDeviceOptions();
  expect((opts.optionalServices as string[])).toContain("180D");
  expect((opts.optionalServices as string[])).toContain("180A");
  expect((opts.optionalServices as string[])).toContain("180B");
});
