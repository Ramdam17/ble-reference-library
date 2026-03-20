# Coding Standards
## BLE Reference Library

> These rules apply to every file in this library, in every language.
> An agent must read this document before writing any code.

---

## 1. Universal Rules (all languages)

### 1.1 File structure — mandatory header

Every source file must begin with a header block in the appropriate comment syntax:

```
<language comment> ─────────────────────────────────────────────────────
<language comment> BLE Reference Library
<language comment> Module  : <module number and name, e.g. "03 — Connection Lifecycle">
<language comment> Platform: <e.g. "Python / Bleak", "Swift / CoreBluetooth", "C++ / SimpleBLE">
<language comment> Sprint  : <sprint number, e.g. "Sprint 03">
<language comment> Phase   : <phase number and name, e.g. "Phase 01 — Python / Bleak">
<language comment>
<language comment> Purpose : <one sentence describing what this file does>
<language comment>
<language comment> Usage   : <minimal usage example — runnable command or function call>
<language comment>
<language comment> Depends : <"None" or list of modules this file uses from the same platform>
<language comment> Ref     : <cross-reference to Python canonical if not Python, e.g. "01_python_bleak/03_connection.py">
<language comment> ─────────────────────────────────────────────────────
```

### 1.2 Standalone files only

No file may import from another file in this library. All helpers must be either:
- inlined in the file, or
- imported from standard library / declared third-party dependencies only.

Exception: `ble_utils.*` in C++ (declared as a shared utility in the CMake project).

### 1.3 No frameworks, no ORM, no magic

Files in this library are examples. Dependencies must be the minimum required to implement the BLE functionality. No logging frameworks, no DI containers, no configuration loaders beyond what the sprint defines.

### 1.4 Muse test vector cross-validation

Any file implementing `parse_eeg_packet` must include a comment:
```
# Cross-validated against 01_python_bleak/10_muse_example.py
# Test vectors: 00_shared/muse_test_vectors.json
```
And the corresponding test file must use the byte sequences from `muse_test_vectors.json`.

---

## 2. Naming Conventions by Language

### 2.1 Python
| Element | Convention | Example |
|---------|-----------|---------|
| Files | `snake_case` | `03_connection.py` |
| Functions | `snake_case` | `connect_with_retry()` |
| Classes | `PascalCase` | `BLEConnectionManager` |
| Constants | `UPPER_SNAKE_CASE` | `MUSE_EEG_SCALE_FACTOR` |
| Private attributes | `_leading_underscore` | `_intentional_disconnect` |
| Type hints | Always present | `async def scan(duration: float = 5.0) -> list[BLEDevice]` |

### 2.2 Swift
| Element | Convention | Example |
|---------|-----------|---------|
| Files | `PascalCase` prefixed with module number | `03_BLEConnection.swift` |
| Types / Classes / Structs | `PascalCase` | `BLEConnectionManager` |
| Functions / Methods | `camelCase` | `connectWithRetry(uuid:maxAttempts:)` |
| Constants | `camelCase` (let) | `batteryServiceUUID` |
| Enum cases | `camelCase` | `.disconnected`, `.connecting` |
| Private | `private` keyword | `private var _intentionalDisconnect` |

### 2.3 JavaScript (Node.js + Web Bluetooth)
| Element | Convention | Example |
|---------|-----------|---------|
| Files | `snake_case` prefixed with module number | `03_connection.js` |
| Functions | `camelCase` | `connectWithRetry()` |
| Classes | `PascalCase` | `BLEConnectionManager` |
| Constants (module-level) | `UPPER_SNAKE_CASE` | `BATTERY_SERVICE_UUID` |
| Private class fields | `#prefix` (ES2022) or `_prefix` | `#intentionalDisconnect` |
| Exports | Named exports only (no default) | `export { scan, scanFiltered }` |

### 2.4 C# (Unity)
| Element | Convention | Example |
|---------|-----------|---------|
| Files | `PascalCase` prefixed with module number | `03_BLEConnection.cs` |
| Classes / Interfaces | `PascalCase` | `IBLEPlugin`, `BLEConnectionManager` |
| Public methods | `PascalCase` | `ConnectWithRetry()` |
| Private fields | `_camelCase` | `_intentionalDisconnect` |
| Constants | `PascalCase` | `BatteryServiceUUID` |
| Events | `PascalCase` | `OnConnectionStateChanged` |
| Enums | `PascalCase` values | `BLEConnectionState.Connecting` |

### 2.5 C++ (SimpleBLE)
| Element | Convention | Example |
|---------|-----------|---------|
| Files | `snake_case` prefixed with module number | `03_connection.cpp` |
| Classes | `PascalCase` | `BLEConnectionManager` |
| Public methods | `snake_case` | `connect_with_retry()` |
| Private members | `_snake_case` | `_intentional_disconnect` |
| Constants / constexpr | `UPPER_SNAKE_CASE` | `MUSE_EEG_SCALE_FACTOR` |
| Namespaces | `snake_case` | `namespace ble_utils` |

### 2.6 Objective-C (Unity native plugins)
| Element | Convention | Example |
|---------|-----------|---------|
| Classes | `PascalCase` with `BLE` prefix | `BLEConnectionManager` |
| Methods | `camelCase` | `connectWithUUID:timeout:` |
| C bridge functions | `BLE_PascalCase` | `BLE_StartScan()` |
| Constants | `k` prefix | `kBatteryServiceUUID` |

---

## 3. File Structure Rules

### 3.1 Section order within a file

All files must follow this section order, separated by blank lines and a section comment:

1. **Header block** (see 1.1)
2. **Imports / includes**
3. **Constants** (UUIDs, magic numbers, defaults)
4. **Data structures** (dataclasses, structs, enums, DTOs)
5. **Core functions / class definition**
6. **Example / main entrypoint** (if applicable — wrapped in `if __name__ == "__main__"` or equivalent)

### 3.2 Function length

No function or method may exceed 50 lines. If a function grows beyond this, extract a named helper.

### 3.3 Magic numbers

No raw numeric literals except `0`, `1`, `-1`, and powers of 2 in bit manipulation. All other values must be named constants.

```python
# BAD
samples[i] = 0.48828125 * (value - 2048)

# GOOD
MUSE_EEG_SCALE_FACTOR = 0.48828125
MUSE_12BIT_MIDPOINT   = 2048
samples[i] = MUSE_EEG_SCALE_FACTOR * (value - MUSE_12BIT_MIDPOINT)
```

---

## 4. Test Rules

### 4.1 Test file naming

| Language | Convention | Example |
|----------|-----------|---------|
| Python | `test_<source_file>.py` | `test_03_connection.py` |
| Swift | `<source_file>Tests.swift` | `03_BLEConnectionTests.swift` |
| JavaScript | `<source_file>.test.js` | `03_connection.test.js` |
| C# | `Test_<source_file>.cs` | `Test_03_BLEConnection.cs` |
| C++ | `test_<source_file>.cpp` | `test_03_connection.cpp` |

### 4.2 Test naming

Test names must be descriptive enough to read as a specification:

```python
# BAD
def test_1():

# GOOD
def test_connect_timeout_raises_BLETimeoutError():
def test_reconnect_suppressed_on_intentional_disconnect():
```

Pattern: `test_<what>_<condition>_<expected_result>()` or `test_<scenario>()`

### 4.3 Test coverage minimums

| Scope | Minimum coverage |
|-------|-----------------|
| Pure functions (parsers, formatters, math) | 100% line coverage |
| Async I/O functions | All happy paths + at least one error path |
| Classes | All public methods exercised |
| Platform-specific branches | At least one test per platform path (mocked) |

Coverage is not enforced by tooling in this library — it is a code review criterion.

### 4.4 Mocking rules

- **Never** use a real BLE device in unit tests. All BLE hardware must be mocked.
- Mock at the lowest possible level: mock `BleakScanner`, `CBCentralManager`, `noble`, etc. — not higher-level functions in this library.
- Mocks must be defined in the test file, not in a shared fixture file (standalone rule).
- Use the platform's standard mocking library: `unittest.mock` (Python), `XCTest` mock protocols (Swift), `jest.fn()` (JS), NSubstitute/Moq (C#), GoogleMock (C++).

### 4.5 Hardcoded byte sequences for parsers

Parser tests (EEG, HRM, telemetry) must use hardcoded byte sequences as inputs, not generated data. The expected output must be computed manually and documented as a comment:

```python
# Input: counter=0x0000, samples=[2048, 2048, ...] (all at midpoint)
# Expected: all µV values = 0.48828125 * (2048 - 2048) = 0.0
raw = bytes([0x00, 0x00, 0x80, 0x08, 0x00, 0x80, ...])
result = parse_eeg_packet(raw)
assert all(v == 0.0 for v in result)
```

---

## 5. Documentation Rules

### 5.1 Docstrings — required on

- Every public function / method
- Every class
- Every constant whose name is not self-explanatory

### 5.2 Docstring format by language

**Python** — Google style:
```python
def estimate_distance(rssi: int, measured_power: int = -59, n: float = 2.0) -> float:
    """Estimate distance from RSSI using log-distance path loss model.

    Args:
        rssi: Received signal strength in dBm (negative value).
        measured_power: RSSI at 1 meter calibration value. Default -59 dBm.
        n: Path loss exponent. 2.0 = free space; 2.0–4.0 typical indoors.

    Returns:
        Estimated distance in meters. Note: accuracy is ±50% in typical environments.

    Formula:
        distance = 10 ^ ((measured_power - rssi) / (10 * n))
    """
```

**Swift** — DocC style:
```swift
/// Estimate distance from RSSI using log-distance path loss model.
///
/// - Parameters:
///   - rssi: Received signal strength in dBm.
///   - measuredPower: RSSI at 1 meter. Default -59 dBm.
///   - pathLossExponent: Path loss exponent. Default 2.0 (free space).
/// - Returns: Estimated distance in meters. Accuracy ±50% in typical environments.
```

**JavaScript** — JSDoc:
```javascript
/**
 * Estimate distance from RSSI using log-distance path loss model.
 * @param {number} rssi - RSSI in dBm.
 * @param {number} [measuredPower=-59] - RSSI at 1 meter.
 * @param {number} [n=2.0] - Path loss exponent.
 * @returns {number} Estimated distance in meters (±50% accuracy).
 */
```

**C++** — Doxygen:
```cpp
/// @brief Estimate distance from RSSI using log-distance path loss model.
/// @param rssi RSSI in dBm.
/// @param measured_power RSSI at 1 meter. Default -59.
/// @param n Path loss exponent. Default 2.0 (free space).
/// @return Estimated distance in meters. Accuracy ±50% in typical environments.
```

### 5.3 Platform-specific behavior — always document

Any function whose behavior differs across OS or platform must include a section in its docstring:

```python
def request_mtu(client: BleakClient, mtu: int = 512) -> int:
    """...
    Platform notes:
        Linux (BlueZ): MTU negotiation is supported. Requested value honored up to adapter limit.
        Windows (WinRT): MTU is OS-controlled; this function returns current value only.
        macOS (CoreBluetooth): MTU is OS-controlled; cannot request specific value.
    """
```

### 5.4 README per folder — required sections

Every platform folder must contain a `README.md` with:

1. **Stack** — language, library, version
2. **Setup** — install command, environment requirements
3. **Platform notes** — OS-specific constraints, permissions, entitlements
4. **File index** — table of `XX_file.ext` → one-line description
5. **Running tests** — exact command
6. **Known limitations** — anything marked `~` in the compatibility matrix

---

## 6. Code Review Checklist

> See `docs/CODE_REVIEW_CHECKLIST.md` for the full checklist.
> Summary of blocking items:

- [ ] Mandatory header block present and complete
- [ ] No imports from other library modules
- [ ] All public functions have docstrings
- [ ] Platform-specific behavior documented
- [ ] No magic numbers (except 0, 1, -1, bit ops)
- [ ] All unit tests use mocked BLE hardware
- [ ] Parser tests use hardcoded byte sequences
- [ ] Muse parser cross-validation comment present (if applicable)
- [ ] `_intentional_disconnect` pattern present in connection module
- [ ] Function length ≤ 50 lines
