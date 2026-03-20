# Phase 01 — Python / Bleak
## Sprints 01–17

> **Stack:** Python 3.10+, [Bleak](https://bleak.readthedocs.io/) 0.21+
> **Role:** Reference implementation. Every BLE concept is validated here first before being ported to other platforms.
> **Git base branch:** `develop`

---

## Global Git Rules (apply to all sprints in this phase)

| Rule | Value |
|------|-------|
| Base branch | `develop` |
| Feature branch naming | `feature/phase01-sprint<NN>-<slug>` |
| Commit convention | `feat(ble-py): <description>` / `fix(ble-py): <description>` / `test(ble-py): <description>` |
| PR target | `develop` |
| PR title format | `[Phase01][Sprint<NN>] <Short description>` |
| Merge strategy | Squash and merge |
| Tag on phase completion | `phase01-complete` |

---

## Sprint 01 — Passive Scan

### Metadata
- **File:** `01_python_bleak/01_scan.py`
- **Dependencies:** none
- **Estimated complexity:** low

### Objective
Implement a basic BLE passive scan that discovers all advertising devices in range and prints their address, name, and RSSI.

### Deliverables
| File | Description |
|------|-------------|
| `01_scan.py` | Standalone runnable script |
| `test_01_scan.py` | Unit tests with mock BleakScanner |

### Expected API / Functions
```python
async def scan(duration: float = 5.0) -> list[BLEDevice]
async def scan_and_print(duration: float = 5.0) -> None
```

### Technical Constraints
- Must use `BleakScanner.discover()` async API
- Must handle `asyncio.run()` entrypoint for direct execution
- Duration parameter must be configurable
- Output must include: MAC address (or UUID on macOS/iOS), device name (or `[unknown]` if None), RSSI

### Unit Tests
```
test_scan_returns_list()             # mock BleakScanner, assert return type is list
test_scan_empty_result()             # mock returns [], assert no exception
test_scan_duration_passed()          # assert timeout arg is forwarded correctly
test_scan_unknown_name_handled()     # device.name = None → "[unknown]" in output
```

### Risks & Mitigations
| Risk | Mitigation |
|------|-----------|
| BLE adapter not present on CI | Mock BleakScanner entirely in tests; document hardware requirement for manual run |
| macOS returns UUID instead of MAC | Normalize address field; document platform difference in docstring |

### Acceptance Criteria
- [ ] Script runs without error on Python 3.10+
- [ ] Returns list of `BLEDevice` objects
- [ ] All 4 unit tests pass
- [ ] Docstring explains platform difference (MAC vs UUID)

### Git Workflow
```bash
git checkout develop
git pull origin develop
git checkout -b feature/phase01-sprint01-passive-scan
# ... implement ...
git commit -m "feat(ble-py): passive BLE scan with duration parameter"
git commit -m "test(ble-py): unit tests for passive scan with mocked BleakScanner"
# open PR → develop
```

### Agent Notes
> This file is the entry point for all BLE discovery. When implementing scan in another language, use this file as the reference for expected output format and error handling patterns.

---

## Sprint 02 — Filtered Scan

### Metadata
- **File:** `01_python_bleak/02_scan_filtered.py`
- **Dependencies:** Sprint 01
- **Estimated complexity:** low-medium

### Objective
Implement scan with filters: by device name (substring match), by service UUID, and by RSSI threshold. Also expose raw advertisement data for custom filtering.

### Deliverables
| File | Description |
|------|-------------|
| `02_scan_filtered.py` | Standalone runnable script |
| `test_02_scan_filtered.py` | Unit tests with mock |

### Expected API / Functions
```python
async def scan_by_name(name: str, duration: float = 5.0) -> list[BLEDevice]
async def scan_by_service_uuid(uuid: str, duration: float = 5.0) -> list[BLEDevice]
async def scan_by_rssi(min_rssi: int = -80, duration: float = 5.0) -> list[BLEDevice]
async def scan_with_advertisement_data(duration: float = 5.0) -> list[tuple[BLEDevice, AdvertisementData]]
```

### Technical Constraints
- Use `BleakScanner(service_uuids=[...])` for hardware-level service filtering where supported
- Name filter = case-insensitive substring match (not exact)
- RSSI filter applied post-scan (BleakScanner does not support RSSI filtering natively)
- `scan_with_advertisement_data` must use `BleakScanner.discover()` with `return_adv=True`

### Unit Tests
```
test_filter_by_name_match()          # "Muse" matches "Muse-1234"
test_filter_by_name_case_insensitive()
test_filter_by_name_no_match()       # returns empty list
test_filter_by_service_uuid()        # mock device with matching service UUID in adv data
test_filter_by_rssi_threshold()      # device with RSSI=-90 excluded when min=-80
test_advertisement_data_returned()   # assert AdvertisementData is second element of tuple
```

### Risks & Mitigations
| Risk | Mitigation |
|------|-----------|
| Service UUID filtering not supported on all adapters | Fallback to post-scan filtering; document in docstring |
| UUID format inconsistency (short vs full) | Normalize UUIDs to full 128-bit lowercase before comparison |

### Acceptance Criteria
- [ ] All 4 filter functions implemented and runnable
- [ ] UUID normalization handles both `0x180F` and `0000180f-0000-1000-8000-00805f9b34fb`
- [ ] All 6 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase01-sprint02-filtered-scan
git commit -m "feat(ble-py): filtered scan by name, UUID, RSSI"
git commit -m "test(ble-py): unit tests for filtered scan"
```

### Agent Notes
> UUID normalization utility implemented here should be extracted to `00_shared/uuid_utils.py` and referenced from other platform implementations.

---

## Sprint 03 — Connection, Disconnection, Reconnection

### Metadata
- **File:** `01_python_bleak/03_connection.py`
- **Dependencies:** Sprint 01
- **Estimated complexity:** medium

### Objective
Implement full connection lifecycle: connect by address, graceful disconnect, auto-reconnect with exponential backoff, and disconnection callback.

### Deliverables
| File | Description |
|------|-------------|
| `03_connection.py` | Connection manager class |
| `test_03_connection.py` | Unit tests with mocked BleakClient |

### Expected API / Functions
```python
class BLEConnectionManager:
    async def connect(self, address: str, timeout: float = 10.0) -> BleakClient
    async def disconnect(self, client: BleakClient) -> None
    async def connect_with_retry(
        self,
        address: str,
        max_attempts: int = 5,
        backoff_base: float = 2.0,
        timeout: float = 10.0
    ) -> BleakClient
    def on_disconnect(self, callback: Callable[[BleakClient], None]) -> None
```

### Technical Constraints
- Disconnection callback registered via `BleakClient(address, disconnected_callback=...)`
- Exponential backoff: wait = `backoff_base ** attempt` seconds between retries
- `connect_with_retry` must raise `BleakError` after max_attempts exceeded
- Context manager support: `async with BleakClient(address) as client`

### Unit Tests
```
test_connect_success()               # mock connect() returns True
test_connect_timeout()               # mock raises BleakError, assert raised
test_disconnect_called()             # assert client.disconnect() called
test_reconnect_retries_on_failure()  # mock fails N times then succeeds
test_reconnect_raises_after_max()    # mock always fails, assert BleakError raised
test_disconnect_callback_invoked()   # assert callback called on simulated disconnect
test_exponential_backoff_timing()    # assert sleep durations match backoff formula
```

### Risks & Mitigations
| Risk | Mitigation |
|------|-----------|
| Race condition during reconnect | Use asyncio.Lock to prevent concurrent reconnect attempts |
| Disconnect callback called on intentional disconnect | Track `_intentional_disconnect` flag to suppress callback |

### Acceptance Criteria
- [ ] Connect / disconnect / reconnect all functional
- [ ] Exponential backoff verified in tests
- [ ] Disconnection callback suppressed on intentional disconnect
- [ ] All 7 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase01-sprint03-connection
git commit -m "feat(ble-py): BLEConnectionManager with connect/disconnect/retry"
git commit -m "test(ble-py): connection lifecycle unit tests"
```

### Agent Notes
> The `_intentional_disconnect` flag pattern is critical. Replicate this in all other platform implementations to avoid spurious reconnect loops.

---

## Sprint 04 — Services & Characteristics Discovery

### Metadata
- **File:** `01_python_bleak/04_services_characteristics.py`
- **Dependencies:** Sprint 03
- **Estimated complexity:** low

### Objective
Enumerate all GATT services and characteristics of a connected device. Display UUID, handle, properties (read/write/notify/indicate), and descriptors.

### Deliverables
| File | Description |
|------|-------------|
| `04_services_characteristics.py` | Discovery functions |
| `test_04_services_characteristics.py` | Unit tests with mocked services |

### Expected API / Functions
```python
async def list_services(client: BleakClient) -> list[BleakGATTService]
async def list_characteristics(client: BleakClient, service_uuid: str | None = None) -> list[BleakGATTCharacteristic]
def characteristic_properties(char: BleakGATTCharacteristic) -> dict[str, bool]
def print_service_tree(client: BleakClient) -> None
```

### Technical Constraints
- `client.services` is populated after connect; must assert client is connected
- Properties dict keys: `read`, `write`, `write_without_response`, `notify`, `indicate`
- `print_service_tree` outputs human-readable tree to stdout

### Unit Tests
```
test_list_services_returns_all()
test_list_characteristics_all_services()
test_list_characteristics_filtered_by_service()
test_characteristic_properties_keys()     # assert all 5 property keys present
test_characteristic_properties_values()   # notify char has notify=True, read=False
test_print_service_tree_no_exception()
```

### Risks & Mitigations
| Risk | Mitigation |
|------|-----------|
| `client.services` empty before connect | Add guard assertion with clear error message |

### Acceptance Criteria
- [ ] All 4 functions implemented
- [ ] All 6 unit tests pass
- [ ] Print output shows UUID, handle, and properties per characteristic

### Git Workflow
```bash
git checkout -b feature/phase01-sprint04-services-discovery
git commit -m "feat(ble-py): GATT services and characteristics discovery"
```

---

## Sprint 05 — Read & Write

### Metadata
- **File:** `01_python_bleak/05_read_write.py`
- **Dependencies:** Sprint 04
- **Estimated complexity:** low-medium

### Objective
Implement reading and writing GATT characteristics, with both `write_with_response` and `write_without_response` variants. Include bytearray encoding/decoding helpers.

### Deliverables
| File | Description |
|------|-------------|
| `05_read_write.py` | Read/write functions |
| `test_05_read_write.py` | Unit tests |

### Expected API / Functions
```python
async def read_characteristic(client: BleakClient, uuid: str) -> bytearray
async def write_characteristic(client: BleakClient, uuid: str, data: bytes, response: bool = True) -> None
async def read_as_int(client: BleakClient, uuid: str, byteorder: str = "little") -> int
async def read_as_string(client: BleakClient, uuid: str, encoding: str = "utf-8") -> str
async def write_int(client: BleakClient, uuid: str, value: int, length: int = 1, byteorder: str = "little", response: bool = True) -> None
```

### Technical Constraints
- `write_without_response` = `response=False` → uses `write_gatt_char(..., response=False)`
- Characteristic must have `read` property before calling read; raise `ValueError` if not
- Characteristic must have `write` or `write_without_response` property before writing

### Unit Tests
```
test_read_returns_bytearray()
test_read_as_int_little_endian()
test_read_as_int_big_endian()
test_read_as_string_utf8()
test_write_with_response()
test_write_without_response()
test_write_int_encoding()
test_read_raises_on_non_readable_char()
test_write_raises_on_non_writable_char()
```

### Risks & Mitigations
| Risk | Mitigation |
|------|-----------|
| Write without response on characteristic that doesn't support it | Check property before calling; raise ValueError |

### Acceptance Criteria
- [ ] All 5 functions implemented
- [ ] Property validation in place for read and write
- [ ] All 9 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase01-sprint05-read-write
git commit -m "feat(ble-py): GATT read and write with encoding helpers"
```

---

## Sprint 06 — Notifications, Indications & Subscription Management

### Metadata
- **File:** `01_python_bleak/06_notifications.py`
- **Dependencies:** Sprint 05
- **Estimated complexity:** medium

### Objective
Subscribe to and unsubscribe from GATT notifications and indications. Implement a subscription manager that routes incoming data to registered callbacks.

### Deliverables
| File | Description |
|------|-------------|
| `06_notifications.py` | Subscription manager |
| `test_06_notifications.py` | Unit tests |

### Expected API / Functions
```python
class NotificationManager:
    async def subscribe(self, client: BleakClient, uuid: str, callback: Callable[[bytearray], None]) -> None
    async def unsubscribe(self, client: BleakClient, uuid: str) -> None
    async def unsubscribe_all(self, client: BleakClient) -> None
    def is_subscribed(self, uuid: str) -> bool
```

### Technical Constraints
- Bleak uses the same API for both notifications and indications (`start_notify` / `stop_notify`); distinction is at the GATT characteristic level (property flag)
- Callback signature: `(sender: int, data: bytearray) -> None` — wrap to expose only `data` to user
- Track active subscriptions in `dict[str, bool]` internal state

### Unit Tests
```
test_subscribe_calls_start_notify()
test_unsubscribe_calls_stop_notify()
test_callback_invoked_on_data()
test_is_subscribed_true_after_subscribe()
test_is_subscribed_false_after_unsubscribe()
test_unsubscribe_all_clears_all()
test_double_subscribe_no_duplicate()
```

### Risks & Mitigations
| Risk | Mitigation |
|------|-----------|
| Calling unsubscribe on non-subscribed UUID | Guard check; log warning, no exception |

### Acceptance Criteria
- [ ] Subscribe/unsubscribe functional
- [ ] All 7 unit tests pass
- [ ] Docstring explains notification vs indication difference at GATT level

### Git Workflow
```bash
git checkout -b feature/phase01-sprint06-notifications
git commit -m "feat(ble-py): notification and indication subscription manager"
```

---

## Sprint 07 — Scan Without Direct Connection (Advertisement Data)

### Metadata
- **File:** `01_python_bleak/07_scan_without_connect.py`
- **Dependencies:** Sprint 02
- **Estimated complexity:** low-medium

### Objective
Read data broadcast in BLE advertisement packets without establishing a connection. Covers manufacturer-specific data, service data, local name, and tx power level.

### Deliverables
| File | Description |
|------|-------------|
| `07_scan_without_connect.py` | Advertisement data parser |
| `test_07_scan_without_connect.py` | Unit tests |

### Expected API / Functions
```python
async def scan_advertisement_data(duration: float = 5.0) -> list[tuple[BLEDevice, AdvertisementData]]
def parse_manufacturer_data(adv: AdvertisementData) -> dict[int, bytes]
def parse_service_data(adv: AdvertisementData) -> dict[str, bytes]
def parse_local_name(adv: AdvertisementData) -> str | None
def parse_tx_power(adv: AdvertisementData) -> int | None
```

### Technical Constraints
- Uses `BleakScanner.discover(return_adv=True)` 
- Manufacturer data key = 16-bit company identifier (int)
- Service data key = full UUID string (normalized lowercase)

### Unit Tests
```
test_manufacturer_data_parsed()
test_service_data_parsed()
test_local_name_present()
test_local_name_absent_returns_none()
test_tx_power_present()
test_tx_power_absent_returns_none()
test_empty_advertisement_no_exception()
```

### Acceptance Criteria
- [ ] All 5 parsing functions implemented
- [ ] All 7 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase01-sprint07-scan-no-connect
git commit -m "feat(ble-py): advertisement data parsing without connection"
```

---

## Sprint 08 — Battery Level

### Metadata
- **File:** `01_python_bleak/08_battery.py`
- **Dependencies:** Sprint 05
- **Estimated complexity:** low

### Objective
Read battery level from a connected device using the standard Battery Service (UUID `0x180F`, characteristic `0x2A19`). Handle devices that do not expose this service gracefully.

### Deliverables
| File | Description |
|------|-------------|
| `08_battery.py` | Battery reader |
| `test_08_battery.py` | Unit tests |

### Expected API / Functions
```python
BATTERY_SERVICE_UUID = "0000180f-0000-1000-8000-00805f9b34fb"
BATTERY_CHAR_UUID    = "00002a19-0000-1000-8000-00805f9b34fb"

async def read_battery_level(client: BleakClient) -> int | None
async def subscribe_battery_notifications(
    client: BleakClient,
    callback: Callable[[int], None]
) -> None
```

### Technical Constraints
- Returns `None` if Battery Service not found (do not raise)
- Battery value is a single `uint8` (0–100)
- Notification callback wraps raw bytearray → int before calling user callback

### Unit Tests
```
test_read_battery_returns_int()
test_read_battery_service_not_found_returns_none()
test_subscribe_battery_callback_invoked()
test_battery_value_clamped_0_100()
```

### Acceptance Criteria
- [ ] Returns None gracefully when service absent
- [ ] All 4 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase01-sprint08-battery
git commit -m "feat(ble-py): battery level read and notification subscription"
```

---

## Sprint 09 — Heart Rate Monitor Example

### Metadata
- **File:** `01_python_bleak/09_heartrate_example.py`
- **Dependencies:** Sprints 03, 05, 06, 08
- **Estimated complexity:** medium

### Objective
End-to-end example connecting to a Heart Rate Monitor device, reading the Heart Rate Measurement characteristic (UUID `0x2A37`), parsing the measurement value and RR intervals, and streaming data to console.

### Deliverables
| File | Description |
|------|-------------|
| `09_heartrate_example.py` | Runnable HRM example |
| `test_09_heartrate_example.py` | Parser unit tests |

### Expected API / Functions
```python
HEART_RATE_SERVICE_UUID     = "0000180d-0000-1000-8000-00805f9b34fb"
HEART_RATE_CHAR_UUID        = "00002a37-0000-1000-8000-00805f9b34fb"
BODY_SENSOR_LOCATION_UUID   = "00002a38-0000-1000-8000-00805f9b34fb"

@dataclass
class HeartRateMeasurement:
    bpm: int
    rr_intervals: list[float]   # seconds
    sensor_contact: bool | None

def parse_heart_rate_measurement(data: bytearray) -> HeartRateMeasurement
async def run_hrm_example(address: str) -> None
```

### Technical Constraints
- Heart Rate Measurement format: flag byte (bit 0 = UINT8 vs UINT16 BPM, bit 4 = RR intervals present)
- RR intervals are UINT16 in units of 1/1024 seconds → convert to seconds
- `run_hrm_example` connects, subscribes, prints live BPM, runs for 30s then disconnects cleanly

### Unit Tests
```
test_parse_hrm_uint8_bpm()
test_parse_hrm_uint16_bpm()
test_parse_hrm_with_rr_intervals()
test_parse_hrm_no_rr_intervals()
test_parse_hrm_sensor_contact_flag()
test_rr_interval_unit_conversion()
```

### Risks & Mitigations
| Risk | Mitigation |
|------|-----------|
| Different HRM devices use slightly different flag combinations | Test parser with multiple real-world byte sequences in tests |

### Acceptance Criteria
- [ ] Parser handles UINT8 and UINT16 BPM modes
- [ ] RR intervals correctly converted to seconds
- [ ] All 6 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase01-sprint09-heartrate
git commit -m "feat(ble-py): heart rate monitor example with HRM packet parser"
```

---

## Sprint 10 — Muse EEG Example

### Metadata
- **File:** `01_python_bleak/10_muse_example.py`
- **Dependencies:** Sprints 03, 06, 08
- **Estimated complexity:** high
- **Source:** Adapted from `muse_manager.py` (provided)

### Objective
Standalone Muse S BLE connection and EEG streaming example. Decoupled from `neuropalite` internals — uses a generic callback instead of `CircularBuffer`. Includes EEG packet parsing and telemetry (battery).

### Deliverables
| File | Description |
|------|-------------|
| `10_muse_example.py` | Standalone Muse example |
| `test_10_muse_example.py` | Parser unit tests |

### Expected API / Functions
```python
# Constants (from muse_manager.py)
MUSE_GATT_CUSTOM_SERVICE    = "0000fe8d-0000-1000-8000-00805f9b34fb"
MUSE_GATT_ATTR_STREAM_TOGGLE = "273e0001-4c4d-454d-96be-f03bac821358"
MUSE_GATT_ATTR_TP9          = "273e0003-4c4d-454d-96be-f03bac821358"
MUSE_GATT_ATTR_AF7          = "273e0004-4c4d-454d-96be-f03bac821358"
MUSE_GATT_ATTR_AF8          = "273e0005-4c4d-454d-96be-f03bac821358"
MUSE_GATT_ATTR_TP10         = "273e0006-4c4d-454d-96be-f03bac821358"
MUSE_GATT_ATTR_TELEMETRY    = "273e000b-4c4d-454d-96be-f03bac821358"
MUSE_EEG_SCALE_FACTOR       = 0.48828125
MUSE_EEG_SAMPLES_PER_PACKET = 12

def parse_eeg_packet(raw_data: bytearray) -> np.ndarray
    """Parse 1 BLE notification → 12 float64 samples in µV."""

def parse_telemetry_packet(raw_data: bytearray) -> dict
    """Returns: {battery: float, fuel_gauge: float, adc_voltage: float, temperature: float}"""

class MuseStreamer:
    def __init__(self, address: str, on_eeg: Callable[[np.ndarray, str], None], on_battery: Callable[[float], None] | None = None)
    async def start(self) -> None
    async def stop(self) -> None
```

### Technical Constraints
- `parse_eeg_packet`: uses `bitstring.Bits` — `uint:16` (packet counter, discarded) + 12 × `uint:12` samples
- Conversion: `µV = SCALE_FACTOR × (raw - 2048)`
- EEG callback receives `(samples: np.ndarray shape=(12,), channel_name: str)`
- AF7 is trigger channel — buffer accumulation logic moved to caller, not parser
- No dependency on `neuropalite`, `CircularBuffer`, or YAML config
- `bitstring` and `numpy` are the only non-standard dependencies

### Unit Tests
```
test_parse_eeg_packet_shape()              # assert output shape == (12,)
test_parse_eeg_packet_scale_factor()       # known bytes → expected µV values
test_parse_eeg_packet_counter_discarded()  # different counter, same samples → same µV
test_parse_telemetry_battery_percentage()  # raw bytes → battery percentage
test_parse_telemetry_all_fields_present()  # assert dict has all 4 keys
test_eeg_conversion_zero_raw()             # raw=2048 → 0.0 µV
test_eeg_conversion_max_raw()             # raw=4095 → expected µV
```

### Risks & Mitigations
| Risk | Mitigation |
|------|-----------|
| bitstring not installed | Document in README; add to requirements.txt |
| Muse device not available for integration test | Parser tests use hardcoded byte sequences, no hardware needed |

### Acceptance Criteria
- [ ] Zero dependency on neuropalite or project-specific code
- [ ] All 7 unit tests pass with hardcoded byte sequences
- [ ] Docstring references BleMuse protocol source
- [ ] `MuseStreamer.start()` connects, enables streaming, subscribes to all 4 EEG channels + telemetry

### Git Workflow
```bash
git checkout -b feature/phase01-sprint10-muse
git commit -m "feat(ble-py): standalone Muse S EEG streamer decoupled from neuropalite"
git commit -m "test(ble-py): EEG and telemetry packet parser unit tests"
```

### Agent Notes
> The `parse_eeg_packet` and `parse_telemetry_packet` functions in this file are the canonical reference for the Muse BLE protocol. When porting to Swift, C#, or C++, these functions are the source of truth for byte parsing logic.

---

## Sprint 11 — Error Handling & Timeouts

### Metadata
- **File:** `01_python_bleak/11_error_handling.py`
- **Dependencies:** Sprint 03
- **Estimated complexity:** medium

### Objective
Centralize BLE error handling patterns: classify BleakError subtypes, implement operation timeouts, and provide a retry decorator for flaky operations.

### Deliverables
| File | Description |
|------|-------------|
| `11_error_handling.py` | Error utilities |
| `test_11_error_handling.py` | Unit tests |

### Expected API / Functions
```python
class BLEError(Exception): ...
class BLEConnectionError(BLEError): ...
class BLETimeoutError(BLEError): ...
class BLEServiceNotFoundError(BLEError): ...

async def with_timeout(coro, timeout: float) -> Any
def ble_retry(max_attempts: int = 3, delay: float = 1.0, exceptions: tuple = (BLEError,))
    """Decorator for async functions."""
```

### Unit Tests
```
test_with_timeout_completes()
test_with_timeout_raises_on_exceed()
test_ble_retry_succeeds_on_second_attempt()
test_ble_retry_raises_after_max_attempts()
test_ble_retry_does_not_retry_unrelated_exception()
test_error_hierarchy()   # assert BLEConnectionError is BLEError
```

### Acceptance Criteria
- [ ] All error classes properly inherit
- [ ] Retry decorator works on async functions
- [ ] All 6 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase01-sprint11-error-handling
git commit -m "feat(ble-py): BLE error hierarchy, timeout wrapper, retry decorator"
```

---

## Sprint 12 — MTU Negotiation

### Metadata
- **File:** `01_python_bleak/12_mtu_negotiation.py`
- **Dependencies:** Sprint 03
- **Estimated complexity:** low-medium

### Objective
Negotiate and query the Maximum Transmission Unit (MTU) for a BLE connection. Document platform differences and compute the effective payload size.

### Deliverables
| File | Description |
|------|-------------|
| `12_mtu_negotiation.py` | MTU utilities |
| `test_12_mtu_negotiation.py` | Unit tests |

### Expected API / Functions
```python
DEFAULT_MTU = 23          # BLE spec minimum
ATT_HEADER_BYTES = 3      # ATT protocol overhead

async def request_mtu(client: BleakClient, mtu: int = 512) -> int
    """Returns negotiated MTU. On macOS, returns current MTU (cannot negotiate)."""

def effective_payload_size(mtu: int) -> int
    """Returns mtu - ATT_HEADER_BYTES."""
```

### Technical Constraints
- Bleak 0.21+: `client.mtu_size` property available after connect
- MTU negotiation only possible on Linux (BlueZ) and Android; macOS/Windows return current value
- Document clearly: Windows caps at 512, macOS is OS-controlled

### Unit Tests
```
test_effective_payload_size()        # mtu=247 → payload=244
test_default_mtu_value()
test_mtu_below_minimum_raises()      # mtu < 23 → ValueError
```

### Acceptance Criteria
- [ ] Platform behavior documented in docstring
- [ ] All 3 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase01-sprint12-mtu
git commit -m "feat(ble-py): MTU negotiation and effective payload size utility"
```

---

## Sprint 13 — Bonding & Pairing

### Metadata
- **File:** `01_python_bleak/13_bonding_pairing.py`
- **Dependencies:** Sprint 03
- **Estimated complexity:** medium

### Objective
Document and implement Bleak's approach to pairing: trigger OS-level pairing, check paired status, and unpair. This module is partially supported — Bleak delegates to the OS.

### Deliverables
| File | Description |
|------|-------------|
| `13_bonding_pairing.py` | Pairing utilities |
| `test_13_bonding_pairing.py` | Unit tests |

### Expected API / Functions
```python
async def pair(client: BleakClient) -> bool
    """Triggers OS pairing dialog. Returns True if paired successfully."""

async def unpair(client: BleakClient) -> bool
    """Unpairs device from OS. Platform support varies."""

async def is_paired(address: str) -> bool | None
    """Returns None if platform cannot determine paired status."""
```

### Technical Constraints
- `client.pair()` available in Bleak 0.20+
- `client.unpair()` available but not guaranteed on all platforms
- Windows: pairing dialog appears in system tray
- macOS: pairing managed by CoreBluetooth; programmatic pairing limited
- Linux: uses BlueZ D-Bus API

### Unit Tests
```
test_pair_returns_bool()
test_unpair_returns_bool()
test_is_paired_returns_none_or_bool()
```

### Acceptance Criteria
- [ ] Docstring clearly explains per-platform behavior
- [ ] All 3 unit tests pass (mocked)

### Git Workflow
```bash
git checkout -b feature/phase01-sprint13-bonding
git commit -m "feat(ble-py): bonding and pairing with OS delegation documentation"
```

---

## Sprint 14 — Peripheral Mode (Advertising)

### Metadata
- **File:** `01_python_bleak/14_peripheral_mode.py`
- **Dependencies:** Sprint 03
- **Estimated complexity:** medium-high

### Objective
Implement BLE peripheral (server) mode using `bless` library. Advertise a custom GATT service, handle read/write requests from a central device.

### Deliverables
| File | Description |
|------|-------------|
| `14_peripheral_mode.py` | Peripheral server example |
| `test_14_peripheral_mode.py` | Unit tests |

### Expected API / Functions
```python
async def create_peripheral_server(
    service_uuid: str,
    char_uuid: str,
    local_name: str = "BLE-Peripheral"
) -> BlessServer

async def start_advertising(server: BlessServer) -> None
async def stop_advertising(server: BlessServer) -> None
```

### Technical Constraints
- Requires `bless` library (`pip install bless`) — **not Bleak**
- Linux requires BlueZ and D-Bus
- macOS: `CBPeripheralManager` via bless
- Windows: WinRT `GattServiceProvider` via bless
- Clearly document that Bleak itself does not support peripheral mode

### Unit Tests
```
test_server_created()
test_advertising_starts()
test_advertising_stops()
```

### Risks & Mitigations
| Risk | Mitigation |
|------|-----------|
| `bless` API changes | Pin version in requirements; note in README |

### Acceptance Criteria
- [ ] README section explains Bleak limitation and bless dependency
- [ ] All 3 unit tests pass (mocked BlessServer)

### Git Workflow
```bash
git checkout -b feature/phase01-sprint14-peripheral
git commit -m "feat(ble-py): peripheral mode advertising using bless library"
```

---

## Sprint 15 — RSSI & Distance Estimation

### Metadata
- **File:** `01_python_bleak/15_rssi_distance.py`
- **Dependencies:** Sprint 01
- **Estimated complexity:** low

### Objective
Read RSSI during scan, estimate distance using the log-distance path loss model, and track RSSI over time for a specific device.

### Deliverables
| File | Description |
|------|-------------|
| `15_rssi_distance.py` | RSSI utilities |
| `test_15_rssi_distance.py` | Unit tests |

### Expected API / Functions
```python
MEASURED_POWER_DEFAULT = -59    # RSSI at 1 meter (typical BLE)
PATH_LOSS_EXPONENT = 2.0        # Free space; 2.0–4.0 depending on environment

def estimate_distance(rssi: int, measured_power: int = MEASURED_POWER_DEFAULT, n: float = PATH_LOSS_EXPONENT) -> float
    """Returns estimated distance in meters. Formula: d = 10^((measured_power - rssi) / (10 * n))"""

async def track_rssi(address: str, duration: float = 10.0, interval: float = 1.0) -> list[tuple[float, int]]
    """Returns list of (timestamp, rssi) tuples."""
```

### Unit Tests
```
test_estimate_distance_at_1m()      # rssi == measured_power → 1.0m
test_estimate_distance_farther()    # rssi < measured_power → > 1.0m
test_estimate_distance_closer()     # rssi > measured_power → < 1.0m
test_distance_formula_accuracy()    # spot-check known values
```

### Acceptance Criteria
- [ ] Formula correctly implemented
- [ ] Docstring notes that distance estimation is approximate (±50% typical)
- [ ] All 4 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase01-sprint15-rssi
git commit -m "feat(ble-py): RSSI reading and log-distance path loss estimation"
```

---

## Sprint 16 — Command Queue

### Metadata
- **File:** `01_python_bleak/16_command_queue.py`
- **Dependencies:** Sprint 05
- **Estimated complexity:** medium

### Objective
Implement a serial command queue for BLE operations. BLE is asynchronous but most peripherals cannot handle concurrent GATT operations — a queue ensures sequential execution.

### Deliverables
| File | Description |
|------|-------------|
| `16_command_queue.py` | BLE command queue |
| `test_16_command_queue.py` | Unit tests |

### Expected API / Functions
```python
class BLECommandQueue:
    def __init__(self, client: BleakClient)
    async def enqueue(self, coro_factory: Callable[[], Coroutine]) -> Any
        """Add a coroutine factory to the queue and await its result."""
    async def start(self) -> None
    async def stop(self) -> None
    @property
    def size(self) -> int
    @property
    def is_running(self) -> bool
```

### Technical Constraints
- Internally uses `asyncio.Queue`
- One worker coroutine consumes the queue sequentially
- `enqueue` returns a `Future` resolved when the command completes
- Queue must be started before enqueuing

### Unit Tests
```
test_commands_execute_sequentially()
test_enqueue_returns_result()
test_queue_size_increments()
test_stop_drains_queue()
test_enqueue_before_start_raises()
test_exception_in_command_propagated()
```

### Acceptance Criteria
- [ ] Sequential execution verified in tests
- [ ] Exceptions propagated to caller via Future
- [ ] All 6 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase01-sprint16-command-queue
git commit -m "feat(ble-py): asyncio-based sequential BLE command queue"
```

---

## Sprint 17 — OTA / DFU

### Metadata
- **File:** `01_python_bleak/17_ota_dfu.py`
- **Dependencies:** Sprints 05, 11, 16
- **Estimated complexity:** high

### Objective
Implement generic firmware update (DFU) over BLE. Covers chunked write, progress tracking, and Nordic nRF DFU protocol as a concrete example.

### Deliverables
| File | Description |
|------|-------------|
| `17_ota_dfu.py` | Generic DFU + nRF example |
| `test_17_ota_dfu.py` | Unit tests |

### Expected API / Functions
```python
@dataclass
class DFUProgress:
    total_bytes: int
    bytes_sent: int
    percent: float
    elapsed_seconds: float

async def write_chunked(
    client: BleakClient,
    uuid: str,
    data: bytes,
    chunk_size: int = 244,
    on_progress: Callable[[DFUProgress], None] | None = None
) -> None

async def nrf_dfu_start(
    client: BleakClient,
    firmware_path: str,
    on_progress: Callable[[DFUProgress], None] | None = None
) -> None
    """Nordic nRF DFU protocol: init packet + firmware chunks."""
```

### Technical Constraints
- Default chunk size = 244 bytes (MTU 247 - 3 ATT bytes)
- nRF DFU: uses DFU Service UUID `0000fe59-0000-1000-8000-00805f9b34fb`
- nRF DFU sequence: select command → create command → write init packet → execute → select data → create data → write firmware chunks → execute
- Progress callback invoked after each chunk

### Unit Tests
```
test_write_chunked_correct_chunks()      # 500 bytes, chunk=244 → 3 writes
test_write_chunked_last_chunk_smaller()
test_progress_callback_invoked()
test_progress_percent_100_at_end()
test_dfu_progress_dataclass_fields()
```

### Risks & Mitigations
| Risk | Mitigation |
|------|-----------|
| nRF DFU protocol version differences (DFU v1 vs v2) | Implement DFU v2 (buttonless); note v1 in docstring |
| Firmware file not available in tests | Use synthetic byte sequences; skip hardware test |

### Acceptance Criteria
- [ ] `write_chunked` correctly splits data into ≤ chunk_size pieces
- [ ] Progress callback fires after each chunk
- [ ] nRF DFU sequence documented with protocol reference
- [ ] All 5 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase01-sprint17-dfu
git commit -m "feat(ble-py): chunked OTA write and nRF DFU protocol implementation"
git commit -m "test(ble-py): DFU chunking and progress callback unit tests"
```

---

## Phase 01 Completion Checklist

- [ ] All 17 files exist and are runnable standalone
- [ ] All 17 test files pass (`pytest 01_python_bleak/`)
- [ ] `requirements.txt` lists: `bleak>=0.21`, `bitstring`, `numpy`, `bless`
- [ ] `README.md` in `01_python_bleak/` contains: setup instructions, platform notes, file index
- [ ] PR from `develop` → `main` created with label `phase01`
- [ ] Tag `phase01-complete` applied on merge

```bash
git checkout main
git merge develop --no-ff -m "feat: Phase 01 complete — Python/Bleak BLE reference library"
git tag phase01-complete
git push origin main --tags
```
