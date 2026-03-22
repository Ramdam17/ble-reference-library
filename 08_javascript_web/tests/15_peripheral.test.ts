import { webBluetoothPeripheralModeSupported } from "../src/15_peripheral.js";

test("webBluetoothPeripheralModeSupported() === false", () => {
  expect(webBluetoothPeripheralModeSupported()).toBe(false);
});
