// tests/04_services_discovery.test.ts
import { BLEServiceDiscoverer } from "../src/04_services_discovery.js";

function makeServer() {
  const char = {
    uuid: "00002a37-0000-1000-8000-00805f9b34fb",
    properties: { notify: true, read: false, write: false, writeWithoutResponse: false, indicate: false },
  };
  const service = {
    uuid: "0000180d-0000-1000-8000-00805f9b34fb",
    isPrimary: true,
    getCharacteristics: jest.fn().mockResolvedValue([char]),
  };
  return {
    getPrimaryServices: jest.fn().mockResolvedValue([service]),
  } as unknown as BluetoothRemoteGATTServer;
}

test("discoverAll returns services with characteristics", async () => {
  const discoverer = new BLEServiceDiscoverer();
  const services = await discoverer.discoverAll(makeServer());
  expect(services).toHaveLength(1);
  expect(services[0].uuid).toBe("0000180d-0000-1000-8000-00805f9b34fb");
  expect(services[0].characteristics).toHaveLength(1);
  expect(services[0].characteristics[0].canNotify).toBe(true);
});
