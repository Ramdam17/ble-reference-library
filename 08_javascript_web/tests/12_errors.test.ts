import { withBLETimeout, BLETimeoutError } from "../src/12_errors.js";

test("withBLETimeout resolves if promise completes in time", async () => {
  const result = await withBLETimeout(Promise.resolve(42), 1);
  expect(result).toBe(42);
});

test("withBLETimeout throws BLETimeoutError if too slow", async () => {
  const slow = new Promise((r) => setTimeout(r, 500));
  await expect(withBLETimeout(slow, 0.01, "test op")).rejects.toBeInstanceOf(BLETimeoutError);
});
