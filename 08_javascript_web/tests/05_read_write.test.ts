// tests/05_read_write.test.ts
import { BLEReadWriter, BLEReadWriteError } from "../src/05_read_write.js";

function makeChar(opts: { canRead?: boolean; canWrite?: boolean; value?: DataView } = {}) {
  return {
    properties: { read: opts.canRead ?? true, write: opts.canWrite ?? true, writeWithoutResponse: true },
    readValue: jest.fn().mockResolvedValue(opts.value ?? new DataView(new ArrayBuffer(1))),
    writeValue: jest.fn().mockResolvedValue(undefined),
    writeValueWithoutResponse: jest.fn().mockResolvedValue(undefined),
  } as unknown as BluetoothRemoteGATTCharacteristic;
}

test("read returns DataView", async () => {
  const rw = new BLEReadWriter();
  const dv = new DataView(new Uint8Array([42]).buffer);
  const char = makeChar({ value: dv });
  const result = await rw.read(char);
  expect(result.getUint8(0)).toBe(42);
});

test("read throws if characteristic not readable", async () => {
  const rw = new BLEReadWriter();
  const char = makeChar({ canRead: false });
  await expect(rw.read(char)).rejects.toBeInstanceOf(BLEReadWriteError);
});

test("write calls writeValue", async () => {
  const rw = new BLEReadWriter();
  const char = makeChar({ canWrite: true });
  await rw.write(char, new Uint8Array([1, 2, 3]));
  expect(char.writeValue).toHaveBeenCalled();
});
