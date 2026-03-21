# Phase 01 — Python / Bleak — Agent Reference

> For AI agents working on or extending this codebase. Describes what exists, how it fits together, and how to use it.

## Overview

Phase 01 implements a complete BLE (Bluetooth Low Energy) reference library in Python using the [Bleak](https://github.com/hbldh/bleak) library. It covers 18 sprints (Sprints 01–18) from passive scanning through OTA firmware updates, plus two real-device integration tests against Polar H10 and Muse 2 hardware.

**Directory:** `01_python_bleak/`
**Tests:** `tests/01_python_bleak/`
**Runtime:** Python 3.12+, managed with `uv` (never pip)
**Test command:** `uv run pytest tests/01_python_bleak/ -v`

---

## Module Index

Each file is a standalone script with `if __name__ == "__main__":` entrypoint and importable functions/classes.

| File | Sprint | Purpose | Key exports |
|------|--------|---------|-------------|
| `01_scan.py` | 01 | Passive BLE scan | `scan_devices(duration)` → `list[dict]` |
| `02_scan_filtered.py` | 02 | Filtered scan by name/UUID/RSSI | `ScanFilter` dataclass, `scan_filtered(filters, duration)` |
| `03_connection.py` | 03 | Connect/disconnect lifecycle | `connect_device(address, timeout)` context manager |
| `04_services_characteristics.py` | 04 | GATT service/characteristic discovery | `discover_services(client)` → structured dict |
| `05_read_write.py` | 05 | Read/write GATT characteristics | `read_gatt_char(client, uuid)`, `write_gatt_char(client, uuid, data)` |
| `06_notifications.py` | 06 | Subscribe to notifications/indications | `subscribe_notifications(client, uuid, callback)` |
| `07_scan_without_connect.py` | 07 | Extract data from advertisements only | `parse_advertisement(device, adv_data)` |
| `08_battery.py` | 08 | Battery Service (0x180F) | `read_battery_level(client)` → `int \| None` |
| `09_device_info.py` | 09 | Device Information Service (0x180A) | `DeviceInfo` dataclass, `read_device_info(client)`, `device_identifier(client)` |
| `10_heartrate_example.py` | 10 | Polar H10 HRM + PMD ECG integration | `HeartRateParser`, `PolarEcgParser`, full streaming pipeline |
| `11_muse_example.py` | 11 | Muse 2 EEG integration | `MuseEegParser`, `muse_handshake()`, 4-channel streaming |
| `12_error_handling.py` | 12 | BLE error hierarchy + retry | `BLEError` hierarchy, `retry_ble(func, max_retries)`, `with_timeout(coro, seconds)` |
| `13_mtu_negotiation.py` | 13 | MTU size negotiation | `negotiate_mtu(client, requested)`, `get_effective_payload(client)` |
| `14_bonding_pairing.py` | 14 | Bonding and pairing | `is_paired(address)`, `pair_device(client)` |
| `15_peripheral_mode.py` | 15 | Peripheral/GATT server mode | `BLEPeripheralServer` class (advertise + serve characteristics) |
| `16_rssi_distance.py` | 16 | RSSI-based distance estimation | `rssi_to_distance(rssi, tx_power, n)`, `PathLossModel` |
| `17_command_queue.py` | 17 | Serialized BLE command queue | `BLECommandQueue` — prevents concurrent GATT operations |
| `18_ota_dfu.py` | 18 | OTA device firmware update | `DFUManager`, `upload_firmware(client, fw_path)` |

---

## Key Patterns

### Async everywhere
All BLE operations use `async/await`. Entrypoints call `asyncio.run()`.

### Mock strategy for tests
Every test file creates mock `BleakClient` instances via helper functions like `_make_client(services=..., characteristics=...)`. No real Bluetooth hardware needed for unit tests.

```python
# Typical mock pattern
def _make_client(has_battery=True, battery_level=75):
    client = AsyncMock(spec=BleakClient)
    # ... configure services and characteristics
    return client
```

### Standard UUIDs
All standard GATT UUIDs use full 128-bit format: `"0000XXXX-0000-1000-8000-00805f9b34fb"`. Proprietary UUIDs (Polar PMD, Muse) are defined as constants in their respective modules.

### Integration test artifacts
- `logs/ecg_plot_*.png` — Polar H10 ECG matplotlib plot
- `logs/eeg_plot_*.png` — Muse 2 EEG matplotlib plot
- `logs/polar_integration_*.log` — structured log
- `logs/muse_integration_*.log` — structured log

---

## Dependencies Between Modules

```
01_scan ──────────────────────────────────────────────┐
02_scan_filtered ── uses 01 scan results              │
03_connection ─────────────────────────────────────────┤
04_services_characteristics ── requires connection     │
05_read_write ── requires connection + discovery       │
06_notifications ── requires connection + discovery    │
07_scan_without_connect ── standalone (no connection)  │
08_battery ── uses 05 (read)                          │
09_device_info ── uses 05 (read)                      │
10_heartrate ── uses 01-06, 08, 09                    ├── all use bleak
11_muse ── uses 01-06, 08, 09                         │
12_error_handling ── standalone utilities              │
13_mtu ── uses 03 (connection)                        │
14_bonding ── uses 03 (connection)                    │
15_peripheral ── uses bleak peripheral API            │
16_rssi ── uses 01 (scan data)                        │
17_command_queue ── wraps 05, 06                      │
18_ota_dfu ── uses 03, 05                             │
```

---

## How to Run Integration Tests

```bash
# Unit tests (no hardware needed)
uv run pytest tests/01_python_bleak/ -v

# Real-device integration (requires Polar H10 + Muse 2)
uv run python 01_python_bleak/10_heartrate_example.py <polar_address>
uv run python 01_python_bleak/11_muse_example.py <muse_address>
```

---

## Extending This Phase

When adding a new module:
1. Create `01_python_bleak/XX_name.py` following the existing pattern (docstring header, constants, functions, `__main__` entrypoint)
2. Create `tests/01_python_bleak/test_XX_name.py` with mock-based tests
3. Update `docs/Sprints/SPRINT_TRACKER.yaml` sprint status
4. If it involves real-device data: add to the integration test flow
