// tests/06_notifications.test.ts
import { BLENotificationSubscriber } from "../src/06_notifications.js";

function makeChar(uuid = "2a37") {
  const listeners: Record<string, Function> = {};
  return {
    uuid,
    properties: { notify: true },
    startNotifications: jest.fn().mockResolvedValue(undefined),
    stopNotifications: jest.fn().mockResolvedValue(undefined),
    addEventListener: jest.fn((event: string, cb: Function) => { listeners[event] = cb; }),
    removeEventListener: jest.fn(),
    _trigger: (value: DataView) => listeners["characteristicvaluechanged"]?.({ target: { value } }),
  };
}

test("subscribe registers handler and receives notifications", async () => {
  const sub = new BLENotificationSubscriber();
  const char = makeChar();
  const received: DataView[] = [];
  await sub.subscribe(char as any, (dv) => received.push(dv));
  char._trigger(new DataView(new Uint8Array([73]).buffer));
  expect(received).toHaveLength(1);
  expect(received[0].getUint8(0)).toBe(73);
});

test("unsubscribe stops notifications", async () => {
  const sub = new BLENotificationSubscriber();
  const char = makeChar();
  await sub.subscribe(char as any, () => {});
  await sub.unsubscribe(char as any);
  expect(char.stopNotifications).toHaveBeenCalled();
  expect(char.removeEventListener).toHaveBeenCalled();
});
