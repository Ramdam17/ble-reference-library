// tests/08_battery.test.ts
import { BLEBattery } from "../src/08_battery.js";

test("readLevel returns percentage from GATT", async () => {
  const dv = new DataView(new Uint8Array([85]).buffer);
  const server = {
    getPrimaryService: jest.fn().mockResolvedValue({
      getCharacteristic: jest.fn().mockResolvedValue({
        readValue: jest.fn().mockResolvedValue(dv),
      }),
    }),
  } as unknown as BluetoothRemoteGATTServer;
  expect(await BLEBattery.readLevel(server)).toBe(85);
});

test("readLevel returns null if service absent", async () => {
  const server = {
    getPrimaryService: jest.fn().mockRejectedValue(new Error("not found")),
  } as unknown as BluetoothRemoteGATTServer;
  expect(await BLEBattery.readLevel(server)).toBeNull();
});
