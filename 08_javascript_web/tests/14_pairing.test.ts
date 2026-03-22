import { inferPairingStatus } from "../src/14_pairing.js";

test("inferPairingStatus returns pairing_required for SecurityError", () => {
  expect(inferPairingStatus(new DOMException("", "SecurityError"))).toBe("pairing_required");
});

test("inferPairingStatus returns unknown for generic error", () => {
  expect(inferPairingStatus(new Error())).toBe("unknown");
});
