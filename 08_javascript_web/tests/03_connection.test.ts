// tests/03_connection.test.ts
import { BLEConnector, BLEConnectionError } from "../src/03_connection.js";

function makeDevice(opts: { connectFails?: boolean } = {}) {
  const server = { connected: true, disconnect: jest.fn() };
  return {
    name: "Polar H10",
    gatt: {
      connected: false,
      connect: opts.connectFails
        ? jest.fn().mockRejectedValue(new Error("Connection failed"))
        : jest.fn().mockResolvedValue(server),
      disconnect: jest.fn(),
    },
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
  } as unknown as BluetoothDevice;
}

test("connect resolves with BluetoothRemoteGATTServer", async () => {
  const connector = new BLEConnector();
  const device = makeDevice();
  const server = await connector.connect(device);
  expect(server).toBeDefined();
  expect(device.gatt!.connect).toHaveBeenCalled();
});

test("connect throws BLEConnectionError on failure", async () => {
  const connector = new BLEConnector();
  const device = makeDevice({ connectFails: true });
  await expect(connector.connect(device)).rejects.toBeInstanceOf(BLEConnectionError);
});

test("disconnect calls gatt.disconnect", async () => {
  const connector = new BLEConnector();
  const device = makeDevice();
  await connector.connect(device);
  connector.disconnect(device);
  expect(device.gatt!.disconnect).toHaveBeenCalled();
});
