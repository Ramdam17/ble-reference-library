import { estimateDistance, classifyProximity, smoothRSSI } from "../src/16_rssi_distance.js";

test("estimateDistance returns 1.0 when rssi equals txPower", () => {
  expect(estimateDistance(-59, -59)).toBeCloseTo(1.0);
});

test("classifyProximity(0.3) === 'immediate'", () => {
  expect(classifyProximity(0.3)).toBe("immediate");
});

test("classifyProximity(1.5) === 'near'", () => {
  expect(classifyProximity(1.5)).toBe("near");
});

test("classifyProximity(5.0) === 'far'", () => {
  expect(classifyProximity(5.0)).toBe("far");
});

test("smoothRSSI([]) returns []", () => {
  expect(smoothRSSI([])).toEqual([]);
});

test("smoothRSSI([-60, -70]) has 2 elements", () => {
  expect(smoothRSSI([-60, -70])).toHaveLength(2);
});
