// tests/09_device_info.test.ts
import { BLEDeviceInfo } from "../src/09_device_info.js";

test("read returns null if DIS absent", async () => {
  const server = {
    getPrimaryService: jest.fn().mockRejectedValue(new Error("not found")),
  } as unknown as BluetoothRemoteGATTServer;
  expect(await BLEDeviceInfo.read(server)).toBeNull();
});

test("read returns DeviceInfo with identifier when serial present", async () => {
  const makeChar = (text: string) => ({
    readValue: jest.fn().mockResolvedValue(new DataView(new TextEncoder().encode(text).buffer)),
  });
  const server = {
    getPrimaryService: jest.fn().mockResolvedValue({
      getCharacteristic: jest.fn()
        .mockResolvedValueOnce(makeChar("SN123"))   // serialNumber
        .mockResolvedValueOnce(makeChar("ModelX"))  // modelNumber
        .mockRejectedValue(new Error("not found")), // rest absent
    }),
  } as unknown as BluetoothRemoteGATTServer;
  const info = await BLEDeviceInfo.read(server);
  expect(info).not.toBeNull();
  expect(info!.serialNumber).toBe("SN123");
  expect(info!.identifier).toBe("SN123");
});
