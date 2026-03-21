# Phase 05 — C++ / SimpleBLE
## Sprints 86–102

> **Stack:** C++17, [SimpleBLE](https://github.com/OpenBluetoothToolbox/SimpleBLE) (cross-platform BLE abstraction)
> **Platforms:** Windows (WinRT), macOS (CoreBluetooth), Linux (BlueZ), Android (via NDK — limited)
> **Role:** Lowest-level reference implementation. Highest feature coverage: full peripheral mode, explicit MTU, explicit bonding. Used as the ground-truth for platform capabilities.
> **Git base branch:** `develop`

---

## Global Git Rules

| Rule | Value |
|------|-------|
| Base branch | `develop` |
| Feature branch naming | `feature/phase05-sprint<NN>-<slug>` |
| Commit convention | `feat(ble-cpp): <description>` / `test(ble-cpp): <description>` |
| PR target | `develop` |
| Merge strategy | Squash and merge |
| Tag on phase completion | `phase05-complete` |

---

## SimpleBLE Overview

SimpleBLE abstracts platform BLE APIs:

| Platform | Backend |
|----------|---------|
| Windows | WinRT `Windows.Devices.Bluetooth` |
| macOS | CoreBluetooth (Obj-C) |
| Linux | BlueZ D-Bus |
| Android | Android BLE API (experimental in SimpleBLE) |

**Install:**
```cmake
# CMakeLists.txt
find_package(simpleble REQUIRED)
target_link_libraries(my_target simpleble::simpleble)
```

**Build:**
```bash
mkdir build && cd build
cmake .. -DSIMPLEBLE_LOG_LEVEL=INFO
cmake --build .
```

---

## Sprint 86 — CMake Project Setup & Shared Utilities

### Metadata
- **File:** `06_cpp/CMakeLists.txt`, `06_cpp/include/ble_utils.h`, `06_cpp/src/ble_utils.cpp`
- **Dependencies:** none
- **Estimated complexity:** low

### Objective
Set up the CMake project structure, install SimpleBLE, and implement shared utility functions used across all modules.

### Deliverables
| File | Description |
|------|-------------|
| `CMakeLists.txt` | Project build config |
| `include/ble_utils.h` | Utility function declarations |
| `src/ble_utils.cpp` | UUID normalization, byte helpers |
| `tests/test_ble_utils.cpp` | Google Test unit tests |

### Expected API
```cpp
namespace ble_utils {
    // UUID normalization: "180F" → "0000180f-0000-1000-8000-00805f9b34fb"
    std::string normalize_uuid(const std::string& uuid);
    bool uuid_equals(const std::string& a, const std::string& b);

    // Byte helpers
    int32_t bytes_to_int(const SimpleBLE::ByteArray& data, bool little_endian = true);
    std::string bytes_to_string(const SimpleBLE::ByteArray& data,
                                 const std::string& encoding = "utf-8");

    // Distance estimation
    double estimate_distance(int rssi, int measured_power = -59, double n = 2.0);

    // Logging
    void log(const std::string& module, const std::string& message);
}
```

### Technical Constraints
- C++17 minimum
- SimpleBLE `ByteArray` = `std::string` (not `std::vector<uint8_t>`) in SimpleBLE 0.7+
- UUID normalization must handle: short form (`180F`), `0x180F`, full lowercase, full uppercase
- Google Test for unit tests

### CMakeLists.txt
```cmake
cmake_minimum_required(VERSION 3.21)
project(BLELibrary CXX)
set(CMAKE_CXX_STANDARD 17)

find_package(simpleble REQUIRED)
find_package(GTest REQUIRED)

add_library(ble_utils src/ble_utils.cpp)
target_include_directories(ble_utils PUBLIC include)
target_link_libraries(ble_utils simpleble::simpleble)

# Module executables
foreach(MODULE 01_scan 02_scan_filtered 03_connection ...)
    add_executable(${MODULE} src/${MODULE}.cpp)
    target_link_libraries(${MODULE} ble_utils simpleble::simpleble)
endforeach()

# Tests
enable_testing()
add_executable(tests tests/test_ble_utils.cpp ...)
target_link_libraries(tests ble_utils GTest::GTest GTest::Main)
gtest_discover_tests(tests)
```

### Unit Tests
```cpp
TEST(BLEUtils, NormalizeUUIDShortForm) {
    EXPECT_EQ(ble_utils::normalize_uuid("180F"),
              "0000180f-0000-1000-8000-00805f9b34fb");
}
TEST(BLEUtils, NormalizeUUIDAlreadyFull) { ... }
TEST(BLEUtils, UUIDEqualsIgnoresCase) { ... }
TEST(BLEUtils, BytesToIntLittleEndian) { ... }
TEST(BLEUtils, BytesToIntBigEndian) { ... }
TEST(BLEUtils, EstimateDistanceAt1m) { ... }
TEST(BLEUtils, EstimateDistanceFarther) { ... }
```

### Acceptance Criteria
- [ ] CMake project builds on target platform
- [ ] All 7 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase05-sprint86-setup
git commit -m "feat(ble-cpp): CMake project setup and shared utilities"
git commit -m "test(ble-cpp): Google Test unit tests for ble_utils"
```

---

## Sprint 87 — Passive Scan

### Metadata
- **File:** `06_cpp/src/01_scan.cpp`
- **Phase 01 reference:** `01_python_bleak/01_scan.py`
- **Dependencies:** Sprint 86
- **Estimated complexity:** low

### Expected API
```cpp
struct BLEDevice {
    std::string identifier;   // UUID (macOS) or MAC (Windows/Linux)
    std::string name;
    int16_t rssi;
    SimpleBLE::Safe::Peripheral peripheral;  // opaque handle
};

std::vector<BLEDevice> scan(int duration_ms = 5000);
void scan_async(int duration_ms, std::function<void(BLEDevice)> on_device_found,
                std::function<void()> on_complete);
```

### Technical Constraints
- `SimpleBLE::Safe::Adapter` — use Safe API (returns `std::optional`, no exceptions)
- `adapter.scan_for(duration_ms)` — blocking scan
- `adapter.set_callback_on_scan_found([](SimpleBLE::Safe::Peripheral p){ ... })` — async variant
- Must check `adapter.bluetooth_enabled()` before scanning
- Windows: adapter must be selected if multiple present

### Unit Tests
```cpp
TEST(BLEScan, ReturnsVectorOfDevices)   // mock adapter
TEST(BLEScan, HandlesEmptyResult)
TEST(BLEScan, BluetoothDisabledReturnsEmpty)
TEST(BLEScan, UnknownNameHandled)       // name() returns empty string
```

### Risks & Mitigations
| Risk | Mitigation |
|------|-----------|
| Multiple adapters on machine | Always use `adapter_list[0]`; document how to select specific adapter |
| Linux requires BlueZ and D-Bus running | Document in README; add `bluetoothctl power on` to setup guide |

### Acceptance Criteria
- [ ] Compiles on at least one platform
- [ ] All 4 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase05-sprint87-passive-scan
git commit -m "feat(ble-cpp): passive BLE scan with SimpleBLE Safe API"
```

---

## Sprint 88 — Filtered Scan

### Metadata
- **File:** `06_cpp/src/02_scan_filtered.cpp`
- **Phase 01 reference:** `01_python_bleak/02_scan_filtered.py`
- **Dependencies:** Sprint 87
- **Estimated complexity:** low-medium

### Expected API
```cpp
std::vector<BLEDevice> scan_by_name(const std::string& name_filter, int duration_ms = 5000);
std::vector<BLEDevice> scan_by_service_uuid(const std::string& service_uuid, int duration_ms = 5000);
std::vector<BLEDevice> scan_by_rssi(int min_rssi = -80, int duration_ms = 5000);
std::vector<std::pair<BLEDevice, SimpleBLE::Safe::Peripheral>>
    scan_with_advertisement_data(int duration_ms = 5000);
```

### Technical Constraints
- SimpleBLE `Peripheral::manufacturer_data()` → `std::map<uint16_t, SimpleBLE::ByteArray>`
- `Peripheral::services()` → `std::vector<SimpleBLE::Safe::Service>` (post-connect) or advertisement UUIDs (pre-connect via `Peripheral::advertised_services()`)
- RSSI filter: `Peripheral::rssi()` during scan callback
- Name filter: `Peripheral::identifier()` contains `name_filter` (case-insensitive)

### Unit Tests
```cpp
TEST(BLEScanFiltered, FilterByNameCaseInsensitive)
TEST(BLEScanFiltered, FilterByServiceUUID)
TEST(BLEScanFiltered, FilterByRSSIThreshold)
TEST(BLEScanFiltered, ManufacturerDataParsed)
```

### Acceptance Criteria
- [ ] UUID normalization from `ble_utils` used for comparison
- [ ] All 4 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase05-sprint88-filtered-scan
git commit -m "feat(ble-cpp): filtered scan by name, UUID, RSSI, advertisement data"
```

---

## Sprint 89 — Connection, Disconnection, Reconnection

### Metadata
- **File:** `06_cpp/src/03_connection.cpp`
- **Phase 01 reference:** `01_python_bleak/03_connection.py`
- **Dependencies:** Sprint 87
- **Estimated complexity:** medium

### Expected API
```cpp
class BLEConnectionManager {
public:
    bool connect(SimpleBLE::Safe::Peripheral& peripheral, int timeout_ms = 10000);
    void disconnect(SimpleBLE::Safe::Peripheral& peripheral);
    bool connect_with_retry(SimpleBLE::Safe::Peripheral& peripheral,
                             int max_attempts = 5, int backoff_base_ms = 2000);
    void set_disconnect_callback(std::function<void(SimpleBLE::Safe::Peripheral&)> cb);

private:
    bool _intentional_disconnect = false;
    std::function<void(SimpleBLE::Safe::Peripheral&)> _on_disconnect;
};
```

### Technical Constraints
- `Peripheral::connect()` is blocking in SimpleBLE Safe API; run in thread for timeout
- `Peripheral::set_callback_on_disconnected([]{...})` for disconnect event
- Reconnect: exponential backoff using `std::this_thread::sleep_for`
- Thread safety: use `std::mutex` for `_intentional_disconnect` flag

### Unit Tests
```cpp
TEST(BLEConnection, ConnectSucceeds)
TEST(BLEConnection, ConnectTimeout)
TEST(BLEConnection, ReconnectRetriesMaxAttempts)
TEST(BLEConnection, IntentionalDisconnectSuppressesCallback)
TEST(BLEConnection, ExponentialBackoffDelays)
```

### Acceptance Criteria
- [ ] Thread-safe disconnect flag
- [ ] All 5 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase05-sprint89-connection
git commit -m "feat(ble-cpp): BLE connection manager with timeout and exponential backoff"
```

---

## Sprint 90 — Services & Characteristics Discovery

### Metadata
- **File:** `06_cpp/src/04_services_characteristics.cpp`
- **Phase 01 reference:** `01_python_bleak/04_services_characteristics.py`
- **Dependencies:** Sprint 89
- **Estimated complexity:** low

### Expected API
```cpp
struct CharacteristicInfo {
    std::string uuid;
    bool can_read;
    bool can_write_request;
    bool can_write_command;
    bool can_notify;
    bool can_indicate;
};

std::vector<SimpleBLE::Safe::Service> list_services(SimpleBLE::Safe::Peripheral& p);
std::vector<CharacteristicInfo> list_characteristics(SimpleBLE::Safe::Peripheral& p,
                                                       const std::string& service_uuid = "");
void print_service_tree(SimpleBLE::Safe::Peripheral& p);
```

### Technical Constraints
- Must be connected before calling `p.services()`
- `Service::characteristics()` → `std::vector<SimpleBLE::Safe::Characteristic>`
- `Characteristic::can_read()`, `can_write_request()`, `can_write_command()`, `can_notify()`, `can_indicate()`

### Unit Tests
```cpp
TEST(BLEServices, ListServicesReturnsAll)
TEST(BLEServices, ListCharacteristicsFiltered)
TEST(BLEServices, CharacteristicPropertiesCorrect)
```

### Git Workflow
```bash
git checkout -b feature/phase05-sprint90-services
git commit -m "feat(ble-cpp): GATT services and characteristics discovery"
```

---

## Sprint 91 — Read & Write

### Metadata
- **File:** `06_cpp/src/05_read_write.cpp`
- **Phase 01 reference:** `01_python_bleak/05_read_write.py`
- **Dependencies:** Sprint 90
- **Estimated complexity:** low-medium

### Expected API
```cpp
SimpleBLE::ByteArray read_characteristic(SimpleBLE::Safe::Peripheral& p,
                                          const std::string& service_uuid,
                                          const std::string& char_uuid);

void write_characteristic(SimpleBLE::Safe::Peripheral& p,
                           const std::string& service_uuid,
                           const std::string& char_uuid,
                           const SimpleBLE::ByteArray& data,
                           bool with_response = true);

int32_t read_as_int(SimpleBLE::Safe::Peripheral& p,
                     const std::string& service_uuid,
                     const std::string& char_uuid,
                     bool little_endian = true);
```

### Technical Constraints
- SimpleBLE requires both service UUID and characteristic UUID for all operations
- `Peripheral::read(service_uuid, char_uuid)` → `ByteArray` (synchronous)
- `Peripheral::write_request(service_uuid, char_uuid, data)` — with response
- `Peripheral::write_command(service_uuid, char_uuid, data)` — without response

### Unit Tests
```cpp
TEST(BLEReadWrite, ReadReturnsData)
TEST(BLEReadWrite, WriteRequest)
TEST(BLEReadWrite, WriteCommand)
TEST(BLEReadWrite, ReadAsIntLittleEndian)
TEST(BLEReadWrite, ReadAsIntBigEndian)
```

### Git Workflow
```bash
git checkout -b feature/phase05-sprint91-read-write
git commit -m "feat(ble-cpp): GATT read and write with SimpleBLE"
```

---

## Sprint 92 — Notifications, Indications & Subscription Management

### Metadata
- **File:** `06_cpp/src/06_notifications.cpp`
- **Phase 01 reference:** `01_python_bleak/06_notifications.py`
- **Dependencies:** Sprint 91
- **Estimated complexity:** medium

### Expected API
```cpp
class NotificationManager {
public:
    void subscribe(SimpleBLE::Safe::Peripheral& p,
                   const std::string& service_uuid,
                   const std::string& char_uuid,
                   std::function<void(SimpleBLE::ByteArray)> callback);

    void unsubscribe(SimpleBLE::Safe::Peripheral& p,
                     const std::string& service_uuid,
                     const std::string& char_uuid);

    void unsubscribe_all(SimpleBLE::Safe::Peripheral& p);
    bool is_subscribed(const std::string& char_uuid) const;
};
```

### Technical Constraints
- `Peripheral::notify(service_uuid, char_uuid, callback)` for notifications
- `Peripheral::indicate(service_uuid, char_uuid, callback)` for indications
- SimpleBLE explicitly distinguishes notify and indicate — document this advantage
- Internal registry: `std::map<std::string, bool>` keyed by char UUID

### Unit Tests
```cpp
TEST(BLENotifications, SubscribeCallsNotify)
TEST(BLENotifications, IndicateUsedForIndications)
TEST(BLENotifications, CallbackInvokedOnData)
TEST(BLENotifications, IsSubscribedAfterSubscribe)
TEST(BLENotifications, UnsubscribeAllClearsRegistry)
```

### Acceptance Criteria
- [ ] Docstring explains SimpleBLE's explicit notify vs indicate — contrast with platforms that don't differentiate
- [ ] All 5 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase05-sprint92-notifications
git commit -m "feat(ble-cpp): notification and indication subscription manager"
```

---

## Sprint 93 — Scan Without Connection

### Metadata
- **File:** `06_cpp/src/07_scan_without_connect.cpp`
- **Phase 01 reference:** `01_python_bleak/07_scan_without_connect.py`
- **Dependencies:** Sprint 88
- **Estimated complexity:** low

### Expected API
```cpp
struct AdvertisementData {
    BLEDevice device;
    std::map<uint16_t, SimpleBLE::ByteArray> manufacturer_data;
    std::vector<std::string> service_uuids;
    std::optional<int8_t> tx_power_level;
};

std::vector<AdvertisementData> scan_advertisement_data(int duration_ms = 5000);
```

### Technical Constraints
- `Peripheral::manufacturer_data()` during scan callback
- `Peripheral::advertised_services()` → `std::vector<std::string>`
- `Peripheral::tx_power()` → `std::optional<int16_t>` in SimpleBLE

### Unit Tests
```cpp
TEST(BLEAdvData, ManufacturerDataParsed)
TEST(BLEAdvData, ServiceUUIDsListed)
TEST(BLEAdvData, TxPowerOptional)
```

### Git Workflow
```bash
git checkout -b feature/phase05-sprint93-adv-data
git commit -m "feat(ble-cpp): advertisement data scan without connection"
```

---

## Sprint 94 — Battery Level

**File:** `06_cpp/src/08_battery.cpp`

```cpp
constexpr auto BATTERY_SERVICE_UUID = "0000180f-0000-1000-8000-00805f9b34fb";
constexpr auto BATTERY_CHAR_UUID    = "00002a19-0000-1000-8000-00805f9b34fb";

std::optional<int> read_battery_level(SimpleBLE::Safe::Peripheral& p);
void subscribe_battery_notifications(SimpleBLE::Safe::Peripheral& p,
                                      std::function<void(int)> callback);
```

Returns `std::nullopt` if Battery Service not found.
**Tests:** read returns int, not found returns nullopt, callback fired.

---

## Sprint 95 — Heart Rate Monitor Example

**File:** `06_cpp/src/09_heartrate_example.cpp`

```cpp
struct HeartRateMeasurement {
    int bpm;
    std::vector<double> rr_intervals;  // seconds
    std::optional<bool> sensor_contact;
};

HeartRateMeasurement parse_heart_rate_measurement(const SimpleBLE::ByteArray& data);
```

**Tests:** same byte sequences as Python/Swift/JS references.

---

## Sprint 96 — Muse EEG Example

### Metadata
- **File:** `06_cpp/src/10_muse_example.cpp`
- **Phase 01 reference:** `01_python_bleak/10_muse_example.py`
- **Dependencies:** Sprints 92, 94
- **Estimated complexity:** high

### Expected API
```cpp
constexpr double MUSE_EEG_SCALE_FACTOR = 0.48828125;
constexpr int MUSE_EEG_SAMPLES_PER_PACKET = 12;

struct MusePacket {
    std::array<double, 12> samples;  // µV
    std::string channel_name;
};

struct MuseTelemetry {
    double battery_percent;
    double fuel_gauge;
    double adc_voltage;
    double temperature;
};

MusePacket parse_eeg_packet(const SimpleBLE::ByteArray& data, const std::string& channel_name);
MuseTelemetry parse_telemetry_packet(const SimpleBLE::ByteArray& data);

class MuseStreamer {
public:
    MuseStreamer(const std::string& address,
                 std::function<void(MusePacket)> on_eeg,
                 std::function<void(double)> on_battery = nullptr);
    void start();
    void stop();
};
```

### Technical Constraints — uint:12 in C++
```cpp
MusePacket parse_eeg_packet(const SimpleBLE::ByteArray& raw, const std::string& channel_name) {
    MusePacket packet;
    packet.channel_name = channel_name;
    const auto* data = reinterpret_cast<const uint8_t*>(raw.data());
    for (int i = 0; i < 12; i++) {
        int bit_offset = 16 + i * 12;
        int byte_index = bit_offset / 8;
        int bit_shift  = bit_offset % 8;
        int raw_val = (static_cast<int>(data[byte_index]) << 8) | data[byte_index + 1];
        int value12 = (raw_val >> (4 - bit_shift)) & 0xFFF;
        packet.samples[i] = MUSE_EEG_SCALE_FACTOR * (value12 - 2048);
    }
    return packet;
}
```

### Unit Tests
```cpp
TEST(MuseEEG, ParsePacketSize)              // assert 12 samples
TEST(MuseEEG, ParsePacketScaleFactor)       // known bytes → expected µV
TEST(MuseEEG, ParsePacketCounterDiscarded)  // different counter → same samples
TEST(MuseEEG, ParseTelemetryBattery)
TEST(MuseEEG, ParseTelemetryAllFields)
TEST(MuseEEG, ZeroRawValue)                // raw=2048 → 0.0
```

### Acceptance Criteria
- [ ] C++ parser output matches Python reference for identical input bytes
- [ ] All 6 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase05-sprint96-muse
git commit -m "feat(ble-cpp): Muse S EEG parser with C++ uint:12 bit unpacking"
```

---

## Sprint 97 — Error Handling & Timeouts

**File:** `06_cpp/src/11_error_handling.cpp`

```cpp
class BLEException : public std::runtime_error { ... };
class BLEConnectionException : public BLEException { ... };
class BLETimeoutException : public BLEException { ... };
class BLEServiceNotFoundException : public BLEException { ... };

template<typename T>
T with_timeout(std::function<T()> fn, int timeout_ms);

template<typename T>
T ble_retry(std::function<T()> fn, int max_attempts = 3,
             int delay_ms = 1000,
             std::vector<std::type_index> retry_on = {typeid(BLEException)});
```

**Tests:** timeout throws, retry succeeds on 2nd attempt, retry not triggered for unrelated exception.

---

## Sprint 98 — MTU Negotiation

**File:** `06_cpp/src/12_mtu_negotiation.cpp`

```cpp
// SimpleBLE: request_mtu available on Linux (BlueZ); read-only on Windows/macOS
std::optional<uint16_t> request_mtu(SimpleBLE::Safe::Peripheral& p, uint16_t requested_mtu);
int effective_payload_size(uint16_t mtu);  // returns mtu - 3
```

**Tests:** payload size calculation, MTU below minimum raises.
Document: Windows WinRT caps at 512; macOS CoreBluetooth auto-negotiates; Linux BlueZ supports `requestMtu`.

---

## Sprint 99 — Bonding & Pairing

**File:** `06_cpp/src/13_bonding_pairing.cpp`

```cpp
// SimpleBLE exposes bonding info on supported platforms
bool pair(SimpleBLE::Safe::Peripheral& p);    // Linux: D-Bus org.bluez.Device1.Pair()
bool unpair(SimpleBLE::Safe::Peripheral& p);  // Linux: D-Bus RemoveDevice
bool is_paired(SimpleBLE::Safe::Peripheral& p);
```

**Advantage over Python/JS/Unity:** Linux via BlueZ offers full programmatic bonding control.
Document per-platform behavior: Linux full API, macOS/Windows implicit.

**Tests:** pair returns bool, unpair returns bool, is_paired reflects state.

---

## Sprint 100 — Peripheral Mode (Advertising)

**File:** `06_cpp/src/14_peripheral_mode.cpp`

SimpleBLE includes SimpleRPA (peripheral/server role):

```cpp
#include "simpleble/ble/peripheral/Peripheral.h"

class BLEPeripheralServer {
public:
    BLEPeripheralServer(const std::string& service_uuid,
                         const std::string& char_uuid,
                         const std::string& local_name);
    void start_advertising();
    void stop_advertising();
    void update_value(const SimpleBLE::ByteArray& data);
    void set_on_read(std::function<SimpleBLE::ByteArray()> handler);
    void set_on_write(std::function<void(SimpleBLE::ByteArray)> handler);
};
```

**Tests:** server created, advertising starts/stops, read/write handlers called.

---

## Sprint 101 — RSSI, Command Queue

**Files:**
- `06_cpp/src/15_rssi_distance.cpp`
- `06_cpp/src/16_command_queue.cpp`

RSSI: `Peripheral::rssi()` during scan; distance formula from `ble_utils`.

Command queue:
```cpp
class BLECommandQueue {
    std::queue<std::packaged_task<void()>> _queue;
    std::mutex _mutex;
    std::condition_variable _cv;
    std::thread _worker;
public:
    template<typename F>
    std::future<void> enqueue(F&& fn);
    void start();
    void stop();
};
```

**Tests:** serial execution, enqueue returns future, exception propagated via future.

---

## Sprint 102 — OTA / DFU

### Metadata
- **File:** `06_cpp/src/17_ota_dfu.cpp`
- **Phase 01 reference:** `01_python_bleak/17_ota_dfu.py`
- **Dependencies:** Sprints 91, 97, 101
- **Estimated complexity:** high

### Expected API
```cpp
struct DFUProgress {
    size_t total_bytes;
    size_t bytes_sent;
    double percent() const { return (double)bytes_sent / total_bytes * 100.0; }
};

void write_chunked(SimpleBLE::Safe::Peripheral& p,
                   const std::string& service_uuid,
                   const std::string& char_uuid,
                   const std::vector<uint8_t>& data,
                   size_t chunk_size = 244,
                   std::function<void(DFUProgress)> on_progress = nullptr);

void nrf_dfu_start(SimpleBLE::Safe::Peripheral& p,
                   const std::string& firmware_path,
                   std::function<void(DFUProgress)> on_progress = nullptr);
```

### Unit Tests
```cpp
TEST(BLEDFU, WriteChunkedCorrectChunkCount)     // 500 bytes, chunk=244 → 3 writes
TEST(BLEDFU, LastChunkSmallerThanChunkSize)
TEST(BLEDFU, ProgressCallbackFiredPerChunk)
TEST(BLEDFU, ProgressReaches100AtEnd)
TEST(BLEDFU, NordicDFUSequenceDocumented)       // smoke test: function exists
```

### Acceptance Criteria
- [ ] Chunking logic matches Python reference
- [ ] All 5 unit tests pass
- [ ] nRF DFU sequence documented with protocol reference comments

### Git Workflow
```bash
git checkout -b feature/phase05-sprint102-dfu
git commit -m "feat(ble-cpp): chunked OTA write and nRF DFU protocol"
git commit -m "test(ble-cpp): DFU chunking and progress unit tests"
```

---

## Phase 05 Completion Checklist

- [ ] CMake project builds on at least 2 platforms (macOS + Linux recommended)
- [ ] All 17 source files exist in `06_cpp/src/`
- [ ] All Google Test unit tests pass (`ctest --output-on-failure`)
- [ ] Muse C++ parser validated against Python reference with identical byte inputs
- [ ] `README.md` in `06_cpp/` contains: build instructions, platform compatibility table, SimpleBLE version
- [ ] PR from `develop` → `main` created with label `phase05`
- [ ] Tag `phase05-complete` applied on merge

```bash
git checkout main
git merge develop --no-ff -m "feat: Phase 05 complete — C++/SimpleBLE BLE reference library"
git tag phase05-complete
git push origin main --tags
```

---

## Library Complete — Final Tag

After all 5 phases merged:

```bash
git checkout main
git tag ble-library-v1.0
git push origin main --tags
```
