# Phase 03 — Web Bluetooth (TypeScript) — Problems Encountered & Solutions

> Documenting real issues hit during development and real-device testing, with root causes and fixes.

---

## 1. `npx serve TestApp/` blocks `../dist/` — 404 on index.js

**Symptom:** Opened `http://localhost:3000` after running `npx serve` from `TestApp/`. Chrome console: `Failed to load resource: 404 — index.js`. `window.runPolar is not a function`.

**Root cause:** `serve` (and most static file servers) block path traversal — requests for `../dist/index.js` are rejected as a security measure. The runners import from `"../dist/index.js"` which resolves above the served directory.

**Fix:** Serve from `08_javascript_web/` (the parent) instead of `TestApp/`:
```bash
cd 08_javascript_web
npx serve .
# Open http://localhost:3000/TestApp/ (NOT http://localhost:3000)
```

**Lesson:** When a static TestApp imports from a sibling directory (`../dist/`), the server root must be the common parent — not the TestApp directory itself.

---

## 2. `window.runPolar is not a function` — ES module scope is not global

**Symptom:** Page loaded, no 404 errors, but clicking "Run Polar H10 Test" threw `TypeError: window.runPolar is not a function`.

**Root cause:** HTML inline `onclick` handlers (`onclick="runPolar()"`) look up functions in the global (`window`) scope. Code inside `<script type="module">` runs in its own module scope — functions defined there are **not** on `window`. A function `runPolar()` inside a module is invisible to `onclick`.

**Fix:** Explicitly assign to `window` in the module:
```javascript
// WRONG — module scope, not reachable from onclick:
async function runPolar() { ... }

// CORRECT — explicitly hoisted to global scope:
window.runPolar = async function() { ... }
```

**Lesson:** Always use `window.functionName = ...` when ES module functions need to be callable from inline HTML event handlers.

---

## 3. Instance classes called as static methods — TypeError at Step 1

**Symptom:** After loading the page and clicking "Run Polar H10 Test": `TypeError: BLEScanner.requestDevice is not a function`. Test failed immediately at Step 1.

**Root cause:** The TestApp runners were originally generated calling `BLEScanner.requestDevice(...)` directly on the class. But `BLEScanner`, `BLEConnector`, `BLEServiceDiscoverer`, and `BLENotificationSubscriber` are all **instance classes** — their methods require `new` instantiation. Calling methods on the class constructor (`BLEScanner.requestDevice`) calls a non-existent static method.

`BLEBattery` and `BLEDeviceInfo` use actual static methods and were unaffected.

**Fix:** Instantiate before calling:
```javascript
// WRONG:
const result = await BLEScanner.requestDevice(opts);

// CORRECT:
const scanner = new BLEScanner();
const result = await scanner.requestDevice(opts);
```

Similarly for `BLEConnector`, `BLEServiceDiscoverer`, `BLENotificationSubscriber`.

**Lesson:** In TypeScript/JavaScript, a class method is not callable on the class constructor unless explicitly declared `static`. Read the source (or check with `console.log(BLEScanner.requestDevice)`) to verify before calling.

---

## 4. subscribe() return value used as unsubscribe callback — TypeError at Step 9

**Symptom:** Polar test ran 30 seconds successfully (ECG data accumulated), but crashed during cleanup (Step 9) with `TypeError: unsubHrm is not a function`.

**Root cause:** The runner stored the return value of `subscriber.subscribe()`:
```javascript
const unsubHrm = await subscriber.subscribe(hrmChar, handler);
// ... later:
unsubHrm(); // TypeError — subscribe() returns void, not a callback
```

`BLENotificationSubscriber.subscribe()` returns `Promise<void>`. The unsubscribe mechanism is a separate `unsubscribe(char)` / `unsubscribeAll([...])` call on the same subscriber instance.

**Fix:** Store the characteristic references and call `unsubscribeAll` during cleanup:
```javascript
const subscriber = new BLENotificationSubscriber();

await subscriber.subscribe(hrmChar, handleHrm);
await subscriber.subscribe(pmdDataChar, handleEcg);

// Cleanup:
await subscriber.unsubscribeAll([hrmChar, pmdDataChar]);
```

**Lesson:** Web Bluetooth and the library's subscriber pattern use explicit unsubscribe calls (not returned teardown callbacks). Don't assume an `addEventListener`-style return value.

---

## 5. withOptionalServices called with array instead of rest args — Web Bluetooth TypeError

**Symptom:** Chrome threw `TypeError: Failed to execute 'requestDevice' on 'Bluetooth': optionalServices must be a sequence of BluetoothServiceUUID.` Device picker never appeared.

**Root cause:** `ScanFilter.withOptionalServices` takes **rest parameters** (`...uuids: string[]`). Passing a single array as the argument produces a nested array:
```javascript
filter.withOptionalServices(["heart_rate", "battery_service"])
// → optionalServices: [["heart_rate", "battery_service"]]  (WRONG — nested)
```
Web Bluetooth expects a flat array of UUID strings, not a nested array.

**Fix:** Spread the array or pass items individually:
```javascript
// CORRECT:
filter.withOptionalServices("heart_rate", "battery_service")
filter.withOptionalServices(...myUuidArray)
```

**Lesson:** Rest parameters look like array parameters from the call site but are semantically different. TypeScript enforces this with type checking, but JavaScript silently accepts the wrong form.

---

## 6. `moduleResolution: "bundler"` incompatible with ts-jest

**Symptom:** `npm test` ran fine locally, but `npm run build` failed with DTS emit errors: TypeScript rejected the config when generating `.d.ts` declaration files.

**Root cause:** `"moduleResolution": "bundler"` is designed for Vite/webpack bundlers. It doesn't support the `module: "Node16"` output mode required to produce `.d.ts` files compatible with both ESM and CJS consumers. TypeScript requires `module` and `moduleResolution` to be aligned: `Node16`/`node16` go together.

**Fix:** Changed `tsconfig.json`:
```json
{
  "module": "Node16",
  "moduleResolution": "node16"
}
```

The `"bundler"` resolution was also incompatible with ts-jest (which runs in Node.js, not a bundler context).

**Lesson:** `moduleResolution: "bundler"` is for Vite/webpack apps, not for libraries that emit `.d.ts`. Library TypeScript packages should use `"node16"` for compatibility with all consumers.

---

## 7. Polar H10 not visible in Chrome Bluetooth picker — device connected to phone

**Symptom:** Clicked "Run Polar H10 Test", Chrome Bluetooth picker appeared, but Polar H10 was not listed. The phone found it immediately.

**Root cause (most likely):** The Polar H10 was already connected to the phone's app. BLE devices typically accept only one central connection at a time. When connected to the phone, the Polar H10 is not advertising and/or not accepting new connections.

**Root cause (secondary):** `ScanFilter.byName("Polar H10")` uses an exact name match. If the device name has a suffix (e.g., `"Polar H10 B5F3A2"`), it won't appear with an exact filter.

**Fix:**
1. Disconnect the phone app (or disable phone Bluetooth), then retry.
2. Changed the scan filter from exact name to prefix:
```javascript
// BEFORE:
ScanFilter.byName("Polar H10")

// AFTER:
ScanFilter.byNamePrefix("Polar")
```

**Lesson:** BLE is point-to-point central-to-peripheral. Always disconnect from other central devices before connecting from a new one. When the exact device name is uncertain, use `byNamePrefix`.

---

## 8. Log display — no line breaks (all on one line)

**Symptom:** Log messages were appended correctly but displayed as one continuous block, with no visible line breaks.

**Root cause:** The logger used `el.textContent += msg + "\n"`. In HTML, `\n` in `textContent` is not rendered as a line break — the browser collapses whitespace unless told otherwise.

**Fix:** Added `white-space: pre-wrap` to the log element's CSS:
```css
#polar-log, #muse-log {
  white-space: pre-wrap;
}
```

This causes the DOM to render `\n` as visible line breaks.

**Lesson:** `textContent` with newlines requires `white-space: pre` or `pre-wrap` in CSS. Alternatively, use `innerHTML` with `<br>` tags — but `pre-wrap` is cleaner and safer (no XSS risk).

---

## Summary of Architectural Lessons

| # | Lesson | Web Bluetooth-specific? |
|---|--------|------------------------|
| 1 | Serve from the parent directory, not TestApp | Web static serving |
| 2 | `<script type="module">` scope ≠ global; use `window.fn = ...` | ES modules |
| 3 | Instance classes need `new`; static vs instance is explicit in TS | TypeScript |
| 4 | `subscribe()` returns `void`; unsubscribe via `unsubscribeAll()` | Library API |
| 5 | `withOptionalServices` takes rest args; spread arrays | TypeScript rest params |
| 6 | `moduleResolution: "bundler"` breaks `.d.ts` emit; use `"node16"` | TypeScript |
| 7 | BLE device visible only when not connected to another central | BLE protocol |
| 8 | `textContent + \n` needs `white-space: pre-wrap` | HTML/CSS |
