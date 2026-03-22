// tests/01_scan.test.ts
import { BLEScanner, DiscoveredDevice } from "../src/01_scan.js";

const mockDevice = {
  id: "test-id",
  name: "Polar H10",
  gatt: { connect: jest.fn() },
  addEventListener: jest.fn(),
};

beforeEach(() => {
  (global as any).navigator = {
    bluetooth: {
      requestDevice: jest.fn().mockResolvedValue(mockDevice),
    },
  };
});

test("scan returns DiscoveredDevice wrapping the chosen device", async () => {
  const scanner = new BLEScanner();
  const device = await scanner.requestDevice({ acceptAllDevices: true });
  expect(device.name).toBe("Polar H10");
  expect(device.id).toBe("test-id");
});

test("scan throws BLEScanError if Bluetooth unavailable", async () => {
  (global as any).navigator = { bluetooth: undefined };
  const scanner = new BLEScanner();
  await expect(scanner.requestDevice({ acceptAllDevices: true })).rejects.toThrow("Bluetooth not available");
});
