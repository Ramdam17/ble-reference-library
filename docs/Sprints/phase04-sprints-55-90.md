# Phase 04 — Unity (Editor · Android/Quest · Mac · iOS)
## Sprints 52–85

> **Architecture:** Two-layer design
> - **Layer 1 — C# Interface** (`IBLEPlugin`): common API used by all Unity targets
> - **Layer 2 — Native Plugins**: platform-specific implementations injected at runtime
>
> **Targets:**
> - `02_unity_editor/` — Windows/Mac Editor (development & testing)
> - `03_unity_android_quest/` — Meta Quest (Android 12+)
> - `04_unity_mac/` — macOS standalone build
> - `05_unity_ios/` — iOS build
>
> **Sprints 52–68:** C# interface + Editor plugin (shared logic)
> **Sprints 69–85:** Platform-specific plugin variations (Android/Quest, Mac, iOS)
>
> **Git base branch:** `develop`

---

## Global Git Rules

| Rule | Value |
|------|-------|
| Base branch | `develop` |
| Feature branch naming | `feature/phase04-sprint<NN>-<slug>` |
| Commit convention | `feat(ble-unity): <description>` / `feat(ble-quest): <description>` |
| PR target | `develop` |
| Merge strategy | Squash and merge |
| Tag on phase completion | `phase04-complete` |

---

## Plugin Architecture Reference

```
Assets/
└── BLELibrary/
    ├── Scripts/
    │   ├── IBLEPlugin.cs           ← C# interface (all targets)
    │   ├── BLEManager.cs           ← MonoBehaviour facade
    │   └── Data/
    │       ├── BLEDevice.cs
    │       ├── BLECharacteristic.cs
    │       └── BLEError.cs
    └── Plugins/
        ├── Editor/                 ← Windows/Mac Editor plugin
        │   └── BLEPluginEditor.cs
        ├── Android/                ← .aar plugin (JNI)
        │   └── ble-android.aar
        ├── macOS/                  ← .bundle (Obj-C/Swift)
        │   └── BLEPluginMac.bundle
        └── iOS/                    ← .framework (Obj-C/Swift)
            └── BLEPluginIOS.framework
```

---

## Sprint 52 — C# Interface Definition

### Metadata
- **File:** `02_unity_editor/Scripts/IBLEPlugin.cs`
- **Phase 01 reference:** overall structure mirrors Python modules
- **Dependencies:** none
- **Estimated complexity:** low

### Objective
Define the `IBLEPlugin` interface and all data transfer objects (DTOs) used across all Unity targets.

### Deliverables
| File | Description |
|------|-------------|
| `IBLEPlugin.cs` | C# interface |
| `BLEDevice.cs` | Scanned/connected device DTO |
| `BLECharacteristic.cs` | Characteristic info DTO |
| `BLEError.cs` | Error enum |

### Expected API
```csharp
public interface IBLEPlugin {
    void Initialize();
    void StartScan(float durationSeconds, Action<BLEDevice[]> onComplete);
    void StartScanFiltered(string[] serviceUUIDs, string nameFilter, int minRSSI,
                           float durationSeconds, Action<BLEDevice[]> onComplete);
    void StopScan();
    void Connect(string deviceId, float timeoutSeconds, Action<bool> onResult);
    void Disconnect(string deviceId);
    void DiscoverServices(string deviceId, Action<string[]> onComplete);
    void DiscoverCharacteristics(string deviceId, string serviceUUID,
                                  Action<BLECharacteristic[]> onComplete);
    void ReadCharacteristic(string deviceId, string charUUID, Action<byte[]> onResult);
    void WriteCharacteristic(string deviceId, string charUUID, byte[] data,
                              bool withResponse, Action<bool> onResult);
    void Subscribe(string deviceId, string charUUID, Action<byte[]> onData);
    void Unsubscribe(string deviceId, string charUUID);
    void ReadBattery(string deviceId, Action<int> onResult);
    event Action<string, bool> OnConnectionStateChanged;  // deviceId, isConnected
}

[Serializable]
public class BLEDevice {
    public string Id;        // UUID (all platforms) or MAC (Windows)
    public string Name;
    public int RSSI;
}

[Serializable]
public class BLECharacteristic {
    public string UUID;
    public bool CanRead;
    public bool CanWrite;
    public bool CanNotify;
    public bool CanIndicate;
}

public enum BLEError {
    None, NotInitialized, ScanFailed, ConnectionFailed,
    ConnectionTimeout, ServiceNotFound, CharacteristicNotFound,
    ReadFailed, WriteFailed, NotConnected
}
```

### Unit Tests
```csharp
[Test] public void BLEDevice_SerializesCorrectly()
[Test] public void BLECharacteristic_PropertyFlagsCorrect()
[Test] public void BLEError_HasExpectedValues()
```

### Acceptance Criteria
- [ ] Interface compiles without errors in Unity 2022.3 LTS
- [ ] All 3 unit tests pass (NUnit via Unity Test Framework)

### Git Workflow
```bash
git checkout -b feature/phase04-sprint52-cs-interface
git commit -m "feat(ble-unity): IBLEPlugin interface and data transfer objects"
```

---

## Sprint 53 — BLEManager MonoBehaviour

### Metadata
- **File:** `02_unity_editor/Scripts/BLEManager.cs`
- **Dependencies:** Sprint 52
- **Estimated complexity:** low-medium

### Objective
Singleton MonoBehaviour facade that wraps `IBLEPlugin`, routes calls to the active platform plugin, and exposes Unity-friendly callbacks on the main thread.

### Expected API
```csharp
public class BLEManager : MonoBehaviour {
    public static BLEManager Instance { get; private set; }

    public void Scan(float duration, Action<BLEDevice[]> onComplete)
    public void ScanFiltered(string[] services, string name, int minRSSI,
                              float duration, Action<BLEDevice[]> onComplete)
    public void Connect(string deviceId, Action<bool> onResult)
    public void Disconnect(string deviceId)
    public void DiscoverServices(string deviceId, Action<string[]> onComplete)
    public void ReadCharacteristic(string deviceId, string charUUID, Action<byte[]> onResult)
    public void WriteCharacteristic(string deviceId, string charUUID, byte[] data,
                                     bool withResponse = true, Action<bool> onResult = null)
    public void Subscribe(string deviceId, string charUUID, Action<byte[]> onData)

    public event Action<string, bool> OnConnectionStateChanged;
}
```

### Technical Constraints
- Plugin selected at runtime via `#if UNITY_ANDROID`, `#if UNITY_IOS`, etc.
- All callbacks dispatched on Unity main thread using `UnityMainThreadDispatcher` pattern or `SynchronizationContext.Post`
- Singleton pattern with `DontDestroyOnLoad`

### Unit Tests
```csharp
[Test] public void BLEManager_SingletonNotNull()
[Test] public void BLEManager_CallbackDispatchedOnMainThread()
[Test] public void BLEManager_PluginSelectedForPlatform()
```

### Acceptance Criteria
- [ ] All callbacks guaranteed on main thread
- [ ] All 3 unit tests pass

### Git Workflow
```bash
git checkout -b feature/phase04-sprint53-ble-manager
git commit -m "feat(ble-unity): BLEManager MonoBehaviour singleton with platform plugin routing"
```

---

## Sprints 54–68 — C# Implementations (Editor target)

> These sprints implement modules 01–17 in C# for the Unity Editor target.
> The Editor plugin uses a C# wrapper around the native OS BLE stack (WinRT on Windows, CoreBluetooth on macOS).
> Each sprint follows the same structure as Phase 01.

---

## Sprint 54 — Passive Scan (Editor)

### Metadata
- **File:** `02_unity_editor/Scripts/01_BLEScan.cs`
- **Phase 01 reference:** `01_python_bleak/01_scan.py`
- **Dependencies:** Sprint 53
- **Estimated complexity:** medium

### Technical Constraints
- Windows Editor: WinRT `BluetoothLEAdvertisementWatcher` via P/Invoke or `Windows.Devices.Bluetooth`
- macOS Editor: CoreBluetooth via `.mm` plugin bridge
- Unity Editor plugins load from `Assets/Plugins/Editor/`

### Expected API
```csharp
public class BLEScanModule {
    public void StartScan(float durationSeconds, Action<BLEDevice[]> onComplete)
    public void StopScan()
}
```

### Unit Tests
```csharp
[Test] public void Scan_ReturnsMockDevices()
[Test] public void Scan_HandlesEmpty()
[Test] public void StopScan_CallsCompletion()
```

### Git Workflow
```bash
git checkout -b feature/phase04-sprint54-scan-editor
git commit -m "feat(ble-unity): passive scan for Unity Editor"
```

---

## Sprint 55 — Filtered Scan (Editor)
**File:** `02_unity_editor/Scripts/02_BLEScanFiltered.cs`
**Reference:** Sprint 36 (JS), Sprint 02 (Python)

```csharp
public void StartScanFiltered(string[] serviceUUIDs = null, string nameFilter = null,
                               int minRSSI = int.MinValue, float duration = 5f,
                               Action<BLEDevice[]> onComplete = null)
```
**Tests:** filter by UUID, by name substring, by RSSI threshold.

---

## Sprint 56 — Connection Lifecycle (Editor)
**File:** `02_unity_editor/Scripts/03_BLEConnection.cs`
**Reference:** Sprint 37 (JS), Sprint 03 (Python)

Implements connect, disconnect, reconnect with exponential backoff.
- Timeout via `Coroutine` + `WaitForSeconds`
- Reconnect delay: `Mathf.Pow(backoffBase, attempt)` seconds

**Tests:** connect success, timeout, reconnect retry, intentional disconnect suppresses callback.

---

## Sprint 57 — Services & Characteristics Discovery (Editor)
**File:** `02_unity_editor/Scripts/04_BLEServicesDiscovery.cs`

```csharp
public void DiscoverServices(string deviceId, Action<string[]> onComplete)
public void DiscoverCharacteristics(string deviceId, string serviceUUID,
                                     Action<BLECharacteristic[]> onComplete)
```
**Tests:** two-step discovery chain, property flags.

---

## Sprint 58 — Read & Write (Editor)
**File:** `02_unity_editor/Scripts/05_BLEReadWrite.cs`

Encoding helpers: `ReadAsInt`, `ReadAsString`, `WriteInt`.
**Tests:** byte order, encoding, property validation.

---

## Sprint 59 — Notifications & Subscriptions (Editor)
**File:** `02_unity_editor/Scripts/06_BLENotifications.cs`

`Subscribe` / `Unsubscribe` / `UnsubscribeAll`.
**Tests:** callback invoked, double-subscribe guard.

---

## Sprint 60 — Scan Without Connection (Editor)
**File:** `02_unity_editor/Scripts/07_BLEScanNoConnect.cs`

Advertisement data from scan events without connecting.
**Tests:** manufacturer data, service data, tx power.

---

## Sprint 61 — Battery Level (Editor)
**File:** `02_unity_editor/Scripts/08_BLEBattery.cs`

`ReadBatteryLevel`, `SubscribeBatteryNotifications`.
**Tests:** value range, service-not-found returns -1.

---

## Sprint 62 — Heart Rate Monitor Example (Editor)
**File:** `02_unity_editor/Scripts/09_BLEHeartRate.cs`

HRM packet parser (UINT8/UINT16 BPM, RR intervals).
**Tests:** parser with hardcoded byte sequences.

---

## Sprint 63 — Muse EEG Example (Editor)
**File:** `02_unity_editor/Scripts/10_MuseEEG.cs`
**Reference:** `01_python_bleak/10_muse_example.py`

### Technical Constraints — uint:12 in C#
```csharp
public static float[] ParseEEGPacket(byte[] data) {
    // Skip 2 bytes (uint16 counter)
    var samples = new float[12];
    for (int i = 0; i < 12; i++) {
        int bitOffset = 16 + i * 12;
        int byteIndex = bitOffset / 8;
        int bitShift = bitOffset % 8;
        int raw = (data[byteIndex] << 8) | data[byteIndex + 1];
        int value12 = (raw >> (4 - bitShift)) & 0xFFF;
        samples[i] = 0.48828125f * (value12 - 2048);
    }
    return samples;
}
```

**Tests:** same byte sequences as Python reference, cross-validated µV values.

---

## Sprint 64 — Error Handling & Timeouts (Editor)
**File:** `02_unity_editor/Scripts/11_BLEErrors.cs`

`BLEException` subclasses, `WithTimeout` coroutine wrapper, `BLERetry` utility.

---

## Sprint 65 — MTU Negotiation (Editor)
**File:** `02_unity_editor/Scripts/12_BLEMTU.cs`

`EffectivePayloadSize(mtu)` utility. Document: WinRT caps at 512, macOS OS-controlled.

---

## Sprint 66 — Bonding & Pairing (Editor)
**File:** `02_unity_editor/Scripts/13_BLEPairing.cs`

OS-delegated pairing. Windows: WinRT `DevicePairingKinds`. macOS: implicit via CoreBluetooth.

---

## Sprint 67 — Peripheral Mode (Editor)
**File:** `02_unity_editor/Scripts/14_BLEPeripheral.cs`

`IBLEPeripheralPlugin` interface + Editor implementation.
Note: limited support on Windows WinRT, full support on macOS.

---

## Sprint 68 — RSSI, Command Queue, OTA/DFU (Editor)
**Files:**
- `02_unity_editor/Scripts/15_BLERSSIDistance.cs`
- `02_unity_editor/Scripts/16_BLECommandQueue.cs`
- `02_unity_editor/Scripts/17_BLEDFU.cs`

Command queue uses `Queue<Func<IEnumerator>>` processed by a Coroutine.
DFU: chunked write with `WriteCharacteristic` in loop + progress callback.

---

## Sprints 69–76 — Android / Meta Quest Platform Plugin

### Platform Context
| Item | Value |
|------|-------|
| Base OS | Android 12+ (API 31+) |
| BLE Stack | Android BluetoothLE API (Java/Kotlin via JNI) |
| Permissions | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` (all runtime-requested on API 31+) |
| Meta Quest specific | Quest runs Android; same permissions; no additional BLE APIs needed |
| Plugin format | `.aar` file in `Assets/Plugins/Android/` |

---

## Sprint 69 — Android Plugin: Permissions & Initialization

### Metadata
- **File:** `03_unity_android_quest/AndroidPlugin/BLEAndroidPlugin.java`
- **Dependencies:** Sprint 52
- **Estimated complexity:** medium

### Objective
Android JNI plugin that implements `IBLEPlugin`. Handle runtime Bluetooth permissions (API 31+).

### Technical Constraints
- Request `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` at runtime via `UnityPlayer.currentActivity`
- `BluetoothAdapter` obtained via `BluetoothManager` system service
- Plugin communicates back to Unity via `UnityPlayer.UnitySendMessage(gameObjectName, methodName, payload)`
- AndroidManifest.xml must include `<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />`

### Deliverables
| File | Description |
|------|-------------|
| `BLEAndroidPlugin.java` | Main plugin class |
| `AndroidManifest.xml` | Permission declarations |
| `test_BLEAndroidPlugin.cs` | Unity-side mock tests |

### Unit Tests
```csharp
[Test] public void AndroidPlugin_PermissionsRequested()
[Test] public void AndroidPlugin_InitializesWithBluetoothAdapter()
[Test] public void AndroidPlugin_SendsMessageToUnity()
```

### Git Workflow
```bash
git checkout -b feature/phase04-sprint69-android-init
git commit -m "feat(ble-quest): Android BLE plugin initialization and runtime permissions"
```

---

## Sprint 70 — Android Plugin: Scan

### Metadata
- **File:** `03_unity_android_quest/AndroidPlugin/BLEScan.java`
- **Phase 01 reference:** `01_python_bleak/01_scan.py`
- **Dependencies:** Sprint 69

### Technical Constraints
- `BluetoothLeScanner.startScan(scanCallback)` / `stopScan()`
- `ScanCallback.onScanResult` → serialize to JSON → `UnitySendMessage`
- `ScanFilter.Builder` for service UUID and device name filtering
- `ScanSettings.SCAN_MODE_LOW_LATENCY` for active scan

### Unit Tests
```csharp
[Test] public void AndroidScan_ReturnsMockDevices()
[Test] public void AndroidScan_FiltersByServiceUUID()
[Test] public void AndroidScan_FiltersByName()
```

### Git Workflow
```bash
git checkout -b feature/phase04-sprint70-android-scan
git commit -m "feat(ble-quest): Android BLE scan with ScanFilter"
```

---

## Sprint 71 — Android Plugin: Connection Lifecycle

**File:** `03_unity_android_quest/AndroidPlugin/BLEConnection.java`
**Reference:** Sprint 56

- `BluetoothGatt.connect()` / `disconnect()` / `close()`
- `BluetoothGattCallback.onConnectionStateChange`
- Auto-reconnect with Handler.postDelayed exponential backoff
- `_intentionalDisconnect` flag

---

## Sprint 72 — Android Plugin: GATT Operations

**File:** `03_unity_android_quest/AndroidPlugin/BLEGatt.java`
**Covers:** Services discovery, read, write, notifications, battery.

- `gatt.discoverServices()` → `onServicesDiscovered`
- `gatt.readCharacteristic()` → `onCharacteristicRead`
- `gatt.writeCharacteristic()` → `onCharacteristicWrite`
- `gatt.setCharacteristicNotification()` + write CCCD descriptor for notifications
- **CCCD write required on Android** (unlike iOS/macOS where CoreBluetooth handles this automatically)

---

## Sprint 73 — Android Plugin: Muse EEG

**File:** `03_unity_android_quest/AndroidPlugin/MuseEEG.java`
**Reference:** Sprint 63 (C# uint:12 logic)

uint:12 parsing in Java:
```java
public static float[] parseEEGPacket(byte[] data) {
    float[] samples = new float[12];
    for (int i = 0; i < 12; i++) {
        int bitOffset = 16 + i * 12;
        int byteIndex = bitOffset / 8;
        int bitShift = bitOffset % 8;
        int raw = ((data[byteIndex] & 0xFF) << 8) | (data[byteIndex + 1] & 0xFF);
        int value12 = (raw >> (4 - bitShift)) & 0xFFF;
        samples[i] = 0.48828125f * (value12 - 2048);
    }
    return samples;
}
```

Tests: same byte sequences as Python reference, validated µV values.

---

## Sprint 74 — Android Plugin: Advanced Features

**Files:**
- `03_unity_android_quest/AndroidPlugin/BLEMTU.java` — `requestMtu(int mtu)` → `onMtuChanged`
- `03_unity_android_quest/AndroidPlugin/BLEPairing.java` — `createBond()` / `removeBond()`
- `03_unity_android_quest/AndroidPlugin/BLEPeripheral.java` — `BluetoothLeAdvertiser` + `BluetoothGattServer`

Android supports explicit pairing via `BluetoothDevice.createBond()` — document this advantage over Python/JS.

---

## Sprint 75 — Android Plugin: Command Queue, RSSI, DFU

**Files:**
- `BLECommandQueue.java` — `LinkedBlockingQueue<Runnable>` processed on handler thread
- `BLERSSIDistance.java` — `gatt.readRemoteRssi()` → `onReadRemoteRssi`
- `BLEDFU.java` — chunked write loop

---

## Sprint 76 — Meta Quest Specific Configuration

### Metadata
- **File:** `03_unity_android_quest/README.md` + `AndroidManifest.xml` updates
- **Dependencies:** Sprints 69–75

### Deliverables
| File | Description |
|------|-------------|
| `AndroidManifest.xml` | Quest-specific permissions and features |
| `README.md` | Quest setup guide |
| `OculusPermissions.cs` | Runtime permission helper |

### Technical Constraints
- Meta Quest = Android; no additional BLE API required
- Quest OS (Android 12+) requires `BLUETOOTH_SCAN` with `neverForLocation` flag
- Oculus SDK not required for BLE — standard Android BLE API
- BLE Central only on Quest (no advertising from Quest headset in practice)
- Document: Quest headset runs in the background when controller goes idle → check `onConnectionStateChange` for unexpected disconnects

### Unit Tests
```csharp
[Test] public void QuestPermissions_Requested()
[Test] public void QuestPermissions_GrantedCallback()
```

---

## Sprints 77–85 — macOS and iOS Native Plugins

### Platform Context

| Item | macOS | iOS |
|------|-------|-----|
| Framework | CoreBluetooth (Obj-C/Swift) | CoreBluetooth (Obj-C/Swift) |
| Plugin format | `.bundle` (macOS) | `.framework` (iOS) |
| Entitlement | `com.apple.security.device.bluetooth` | implicit |
| Background BLE | n/a | `bluetooth-central` background mode |
| Implementation | Obj-C bridge to Unity | Same, different entitlements |

---

## Sprint 77 — macOS Plugin: Core Setup

**File:** `04_unity_mac/NativePlugin/BLEPluginMac.mm`

Objective-C implementation of `IBLEPlugin` methods, called from C# via P/Invoke.

```objc
// C-callable bridge functions
extern "C" {
    void BLE_Initialize();
    void BLE_StartScan(float duration, const char* gameObjectName);
    void BLE_Connect(const char* deviceUUID, float timeout, const char* gameObjectName);
    // ... etc
}
```

Callbacks to Unity via `UnitySendMessage(gameObjectName, "OnScanComplete", jsonPayload)`.

---

## Sprint 78 — macOS Plugin: Scan + Filter

**File:** `04_unity_mac/NativePlugin/BLEScan.mm`

`CBCentralManager` scan with service UUID filters and name substring filter.
Serialize discovered peripherals to JSON → UnitySendMessage.

---

## Sprint 79 — macOS Plugin: Connection + GATT

**File:** `04_unity_mac/NativePlugin/BLEConnection.mm`

Full `CBCentralManagerDelegate` + `CBPeripheralDelegate` implementation.
Connect, discover services, discover characteristics, read, write, notify.
`_intentionalDisconnect` flag.

---

## Sprint 80 — macOS Plugin: Muse EEG

**File:** `04_unity_mac/NativePlugin/MuseEEG.mm`

Obj-C uint:12 parser — same algorithm as Python/Swift/C# references.
```objc
+ (NSArray<NSNumber*>*)parseEEGPacket:(NSData*)data {
    NSMutableArray *samples = [NSMutableArray arrayWithCapacity:12];
    const uint8_t *bytes = (const uint8_t *)data.bytes;
    for (int i = 0; i < 12; i++) {
        int bitOffset = 16 + i * 12;
        int byteIndex = bitOffset / 8;
        int bitShift = bitOffset % 8;
        int raw = (bytes[byteIndex] << 8) | bytes[byteIndex + 1];
        int value12 = (raw >> (4 - bitShift)) & 0xFFF;
        double uv = 0.48828125 * (value12 - 2048);
        [samples addObject:@(uv)];
    }
    return samples;
}
```

---

## Sprint 81 — macOS Plugin: Advanced Features

**Files:** MTU query, pairing (implicit), peripheral mode (`CBPeripheralManager`), RSSI, DFU chunked write.

macOS: `maximumWriteValueLengthForType:` for MTU; CoreBluetooth handles negotiation.

---

## Sprint 82 — iOS Plugin: Core Setup & Entitlements

**File:** `05_unity_ios/NativePlugin/BLEPluginIOS.mm`

Same Obj-C code as macOS plugin, different build target.

### iOS-specific differences from macOS
- `NSBluetoothAlwaysUsageDescription` required in `Info.plist`
- Background mode: add `bluetooth-central` to `UIBackgroundModes` for background scanning/connection
- `CBCentralManagerOptionRestoreIdentifierKey` for state restoration after app backgrounding
- No App Sandbox considerations (iOS handles permissions differently)

**File:** `05_unity_ios/README.md` — documents all iOS-specific configuration steps.

---

## Sprint 83 — iOS Plugin: Background Mode

**File:** `05_unity_ios/NativePlugin/BLEBackground.mm`

iOS-specific state restoration API:
```objc
// Restore pending connections after app is relaunched by OS
- (void)centralManager:(CBCentralManager*)central
    willRestoreState:(NSDictionary<NSString*, id>*)dict {
    NSArray *peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey];
    // reconnect to any peripheral that was previously connected
}
```

**Tests:** mock state restoration dictionary → reconnect called.

---

## Sprint 84 — iOS Plugin: Muse EEG + Advanced Features

Same as macOS sprints 80–81 but compiled for iOS target.
Cross-validate Muse parser output against Python reference.

---

## Sprint 85 — Unity Integration Tests & Examples

### Metadata
- **Files:** Example scenes + integration test scripts
- **Dependencies:** All sprints 52–84

### Deliverables
| File | Description |
|------|-------------|
| `Assets/BLELibrary/Examples/ScanExample.cs` | Scan UI example |
| `Assets/BLELibrary/Examples/HRMExample.cs` | Heart rate monitor UI |
| `Assets/BLELibrary/Examples/MuseExample.cs` | Muse EEG streaming UI |
| `Assets/BLELibrary/Tests/BLEIntegrationTests.cs` | Integration tests (requires device) |

### Technical Constraints
- Integration tests use `[UnityTest]` attribute with `IEnumerator`
- Mock plugin for Editor tests; real plugin required for device tests
- MuseExample displays live EEG values per channel in IMGUI

### Unit Tests
```csharp
[Test] public void IntegrationTest_PluginLoadedForPlatform()
[Test] public void IntegrationTest_BLEManagerSingleton()
[UnityTest] public IEnumerator IntegrationTest_ScanCompletesWithinTimeout()
```

### Git Workflow
```bash
git checkout -b feature/phase04-sprint85-examples
git commit -m "feat(ble-unity): example scenes and integration tests"
```

---

## Phase 04 Completion Checklist

- [ ] C# interface compiles on all 4 Unity targets
- [ ] Android `.aar` builds without errors
- [ ] macOS `.bundle` and iOS `.framework` build without errors
- [ ] Muse parser cross-validated across all platform plugins vs Python reference
- [ ] Quest permissions tested on device (Meta Quest 2 or 3)
- [ ] All unit tests pass in Unity Test Runner
- [ ] `README.md` in each platform folder with setup instructions
- [ ] PR from `develop` → `main` with label `phase04`
- [ ] Tag `phase04-complete` applied on merge

```bash
git checkout main
git merge develop --no-ff -m "feat: Phase 04 complete — Unity BLE reference library (Editor, Quest, Mac, iOS)"
git tag phase04-complete
git push origin main --tags
```
