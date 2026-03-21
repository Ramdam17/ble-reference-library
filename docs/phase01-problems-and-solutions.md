# Phase 01 — Python / Bleak — Problems Encountered & Solutions

> Documenting real issues hit during development and real-device testing, with root causes and fixes.

---

## 1. pytest discovering Swift test directories

**Symptom:** `uv run pytest` tried to collect files from `09_xcode_apple/Tests/` and failed on Swift files.

**Root cause:** No `testpaths` configured — pytest defaulted to scanning the entire repo.

**Fix:** Added to `pyproject.toml`:
```toml
[tool.pytest.ini_options]
testpaths = ["tests"]
```

**Commit:** Part of initial project setup.

---

## 2. Bleak scan returns empty on macOS without permissions

**Symptom:** `scan_devices()` returned an empty list on macOS despite nearby BLE devices.

**Root cause:** macOS requires the terminal app (Terminal, iTerm, VS Code) to have Bluetooth permission in System Preferences → Privacy & Security → Bluetooth.

**Fix:** User must grant Bluetooth access to the terminal application. Not a code fix — documented in module docstrings.

**Lesson:** Always document platform-specific permission requirements in the module header.

---

## 3. Muse 2 requires handshake before streaming

**Symptom:** Subscribing to Muse EEG characteristic UUIDs produced no data.

**Root cause:** The Muse 2 requires a proprietary handshake sequence before it starts transmitting EEG data:
1. Write ASK command (`0x01, 0x01, ...`) to control characteristic
2. Wait 500ms
3. Write STREAM command (`0x02, 0x64, 0x0A`) to control characteristic

Without this, the device accepts subscriptions but sends no notifications.

**Fix:** Implemented `muse_handshake(client)` function in `11_muse_example.py` with the correct byte sequences and timing.

**Reference:** Reverse-engineered protocol documented in `11_muse_example.py` module header.

---

## 4. Muse EEG packet parsing — sample ordering

**Symptom:** Reconstructed EEG waveform looked scrambled/noisy despite correct sample count.

**Root cause:** Muse EEG packets contain 12 samples packed as unsigned 12-bit values (not 16-bit). The bit-unpacking order matters — samples are big-endian within each 3-byte pair.

**Fix:** Corrected the bit-unpacking logic in `MuseEegParser.parse()`:
```python
# Each 3 bytes → 2 samples (12 bits each)
sample1 = (byte0 << 4) | (byte1 >> 4)
sample2 = ((byte1 & 0x0F) << 8) | byte2
```

**Lesson:** For proprietary BLE protocols, validate parsed output against known reference data before trusting the parser.

---

## 5. Polar PMD ECG — control point write requires response

**Symptom:** ECG stream never started despite writing the start command to PMD control point.

**Root cause:** The PMD control point requires write-with-response (`write_gatt_char` with `response=True`). Write-without-response was silently ignored.

**Fix:** Ensured the start command uses `response=True`:
```python
await client.write_gatt_char(PMD_CONTROL_UUID, ECG_START_CMD, response=True)
```

**Lesson:** Always check the characteristic properties (`.write` vs `.writeWithoutResponse`) before choosing the write type.

---

## 6. matplotlib PNG export — figure not closing

**Symptom:** Memory usage grew when running integration tests repeatedly; PNG files were 0 bytes.

**Root cause:** `plt.savefig()` was called but `plt.close(fig)` was not, so the figure stayed in memory and the file handle wasn't flushed.

**Fix:** Always close figures after saving:
```python
fig, ax = plt.subplots(figsize=(12, 6))
# ... plot ...
fig.savefig(path, dpi=300, bbox_inches='tight')
plt.close(fig)
```

**Lesson:** Always use `plt.close(fig)` after `savefig()`, especially in scripts that run multiple times.
