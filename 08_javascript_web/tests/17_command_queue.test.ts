import { BLECommandQueue } from "../src/17_command_queue.js";

test("enqueue runs operations serially in order", async () => {
  const queue = new BLECommandQueue();
  const order: number[] = [];
  await Promise.all([
    queue.enqueue(async () => { order.push(1); }),
    queue.enqueue(async () => { order.push(2); }),
    queue.enqueue(async () => { order.push(3); }),
  ]);
  expect(order).toEqual([1, 2, 3]);
});

test("failed operation does not block subsequent queue entries", async () => {
  const queue = new BLECommandQueue();
  const results: string[] = [];
  await queue.enqueue(async () => { throw new Error("boom"); }).catch(() => {});
  await queue.enqueue(async () => { results.push("ok"); });
  expect(results).toEqual(["ok"]);
});
