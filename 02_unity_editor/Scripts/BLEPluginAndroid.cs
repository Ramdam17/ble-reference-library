// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : BLEPluginAndroid.cs — C# / Java Bidirectional Bridge
// Platform: Android (Unity) — Quest 2 / 3 / Pro, Android phones
// Sprint  : Sprint 56
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : Implement IBLEPlugin for Android by bridging Unity C# to the Java
//           singleton BLE classes via AndroidJavaObject (JNI). Also serves as
//           the UnitySendMessage receiver: Java calls back into Unity by naming
//           the "BLEManager" GameObject, which forwards payloads to this class.
//
// Architecture — Bidirectional Bridge
// ─────────────────────────────────────
// DOWNWARD (C# → Java):
//   BLEManager.Connect(deviceId, timeout, callback)
//     → BLEPluginAndroid.Connect(deviceId, timeout, callback)
//         → _connection.Call("connect", deviceId, timeout)   // JNI / Java
//
// UPWARD (Java → C# via UnitySendMessage):
//   Java: UnityPlayer.UnitySendMessage("BLEManager", "OnConnected", deviceId)
//     → Unity routes to public void OnConnected(string deviceId) on BLEManager
//         → BLEPluginAndroid.OnConnected(deviceId)
//             → _pendingConnectCallbacks[deviceId]?.Invoke(true)
//
// UnitySendMessage is automatically marshalled to the Unity main thread by the
// Android runtime. BLEManager wraps all outgoing callbacks in OnMainThread()
// for consistency with iOS, but the Android path already arrives on the main
// thread and the extra dispatch is a no-op when SynchronizationContext matches.
//
// AndroidJavaObject Pattern
// ──────────────────────────
// Each Java class is a singleton accessed via CallStatic<AndroidJavaObject>:
//
//   var cls      = new AndroidJavaClass("com.bleref.unity.BLEGatt");
//   var instance = cls.CallStatic<AndroidJavaObject>("getInstance");
//   instance.Call("readCharacteristic", deviceId, serviceUUID, charUUID);
//
// All AndroidJavaClass / AndroidJavaObject instances are created once in
// Initialize() and cached for the lifetime of the plugin. Creating them on
// every call is expensive (JNI class resolution + object lookup each time).
//
// Threading Note
// ───────────────
// AndroidJavaObject.Call() is synchronous at the JNI layer but the Java-side
// BLE operations are always asynchronous (Android Bluetooth callbacks arrive
// on a dedicated BluetoothLeScanner thread or a HandlerThread). Call() returns
// immediately — the result is delivered later via UnitySendMessage.
//
// Placement in Unity project:
//   Assets/Scripts/BLE/BLEPluginAndroid.cs
//
// Usage:
//   // Created automatically by BLEManager.CreatePlatformPlugin()
//   // on UNITY_ANDROID && !UNITY_EDITOR builds. Do not instantiate directly.
//
// Java singletons wrapped:
//   com.bleref.unity.BLEAndroidPlugin  — init + permissions
//   com.bleref.unity.BLEScan           — scan
//   com.bleref.unity.BLEConnection     — connect / disconnect
//   com.bleref.unity.BLEGatt           — GATT services, read, write, notify
//   com.bleref.unity.MuseEEG           — Muse 2 EEG streaming (helper section)
// ─────────────────────────────────────────────────────────────────────────────

using System;
using System.Collections.Generic;
using UnityEngine;

namespace BLEReference
{
    /// <summary>
    /// Android implementation of IBLEPlugin. Bridges Unity C# to Java BLE
    /// singletons via AndroidJavaObject (JNI) and receives results through
    /// public UnitySendMessage callback methods.
    ///
    /// This class must be attached to (or reachable from) the "BLEManager"
    /// GameObject so that UnitySendMessage can route callbacks to it.
    /// In practice BLEManager.Initialize() wires this automatically.
    /// </summary>
    public class BLEPluginAndroid : IBLEPlugin
    {
        // ── Android Java singleton references ─────────────────────────────────
        // Cached once in Initialize(). Each field is the getInstance() result
        // of the corresponding Java class. Null in non-Android or Editor builds.

#if UNITY_ANDROID && !UNITY_EDITOR
        private AndroidJavaObject _plugin;     // BLEAndroidPlugin singleton
        private AndroidJavaObject _scan;       // BLEScan singleton
        private AndroidJavaObject _connection; // BLEConnection singleton
        private AndroidJavaObject _gatt;       // BLEGatt singleton
        private AndroidJavaObject _museEeg;    // MuseEEG singleton
#endif

        // ── Pending callbacks ─────────────────────────────────────────────────
        // Keyed by deviceId. Stored while the async operation is in-flight and
        // invoked (then removed) when the corresponding UnitySendMessage arrives.

        private readonly Dictionary<string, Action<bool>>                _pendingConnectCallbacks     = new();
        private readonly Dictionary<string, Action<string[]>>            _pendingServicesCallbacks    = new();
        private readonly Dictionary<string, Action<BLECharacteristic[]>> _pendingCharCallbacks        = new();

        // Read / write callbacks keyed by "<deviceId>|<charUUID>"
        private readonly Dictionary<string, Action<byte[]>>              _pendingReadCallbacks        = new();
        private readonly Dictionary<string, Action<bool>>                _pendingWriteCallbacks       = new();
        private Action<int>                                               _pendingBatteryCallback;
        private string                                                    _pendingBatteryDeviceId;

        // Scan completion callback (single active scan at a time)
        private Action<BLEDevice[]>             _pendingScanCallback;
        private readonly List<BLEDevice>        _scanResults = new();

        // Notification/subscription callbacks keyed by charUUID
        private readonly Dictionary<string, Action<byte[]>> _subscribeCallbacks = new();

        // ── IBLEPlugin.OnConnectionStateChanged ──────────────────────────────

        /// <inheritdoc/>
        public event Action<string, bool> OnConnectionStateChanged;

        // ── IBLEPlugin: Initialize ────────────────────────────────────────────

        /// <summary>
        /// Acquire all Java singleton instances and call BLEAndroidPlugin.initialize().
        /// Must be called once before any scan or connect operation.
        /// </summary>
        public void Initialize()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            Debug.Log("[BLEPluginAndroid] Initialize — acquiring Java singletons.");

            // ── Acquire each Java singleton via CallStatic ────────────────────
            // The pattern:
            //   new AndroidJavaClass("com.bleref.unity.Foo")
            //       .CallStatic<AndroidJavaObject>("getInstance")
            // resolves the Java class by name through JNI and calls the static
            // factory method. The returned AndroidJavaObject is the singleton
            // instance and can be used directly for subsequent .Call() invocations.

            using (var cls = new AndroidJavaClass("com.bleref.unity.BLEAndroidPlugin"))
                _plugin = cls.CallStatic<AndroidJavaObject>("getInstance");

            using (var cls = new AndroidJavaClass("com.bleref.unity.BLEScan"))
                _scan = cls.CallStatic<AndroidJavaObject>("getInstance");

            using (var cls = new AndroidJavaClass("com.bleref.unity.BLEConnection"))
                _connection = cls.CallStatic<AndroidJavaObject>("getInstance");

            using (var cls = new AndroidJavaClass("com.bleref.unity.BLEGatt"))
                _gatt = cls.CallStatic<AndroidJavaObject>("getInstance");

            using (var cls = new AndroidJavaClass("com.bleref.unity.MuseEEG"))
                _museEeg = cls.CallStatic<AndroidJavaObject>("getInstance");

            // ── Initialize the BLE stack ──────────────────────────────────────
            // "BLEManager" is the name of the Unity GameObject that will receive
            // all UnitySendMessage callbacks. BLEManager creates a GameObject
            // with this exact name in its singleton setup.
            _plugin.Call("initialize", "BLEManager");

            Debug.Log("[BLEPluginAndroid] Java singletons acquired; initialize() called.");
#else
            Debug.Log("[BLEPluginAndroid] Initialize called outside UNITY_ANDROID — no-op.");
#endif
        }

        // ── IBLEPlugin: Scan ──────────────────────────────────────────────────

        /// <inheritdoc/>
        public void StartScan(float durationSeconds, Action<BLEDevice[]> onComplete)
        {
            _pendingScanCallback = onComplete;
            _scanResults.Clear();
#if UNITY_ANDROID && !UNITY_EDITOR
            Debug.Log($"[BLEPluginAndroid] StartScan({durationSeconds}s)");
            _scan.Call("startScan", durationSeconds);
#endif
        }

        /// <inheritdoc/>
        public void StartScanFiltered(string[] serviceUUIDs, string nameFilter, int minRSSI,
                                      float durationSeconds, Action<BLEDevice[]> onComplete)
        {
            _pendingScanCallback = onComplete;
            _scanResults.Clear();
#if UNITY_ANDROID && !UNITY_EDITOR
            // Java BLEScan.startScanFiltered takes a single serviceUUID string.
            // If the caller provides multiple UUIDs we pass the first one; Android
            // OS-level filtering supports one service UUID per ScanFilter. Multiple
            // UUIDs require multiple ScanFilter objects which the current Java layer
            // does not expose — secondary filtering happens on the C# side via
            // the device list returned in OnScanComplete.
            string primaryUUID = (serviceUUIDs != null && serviceUUIDs.Length > 0) ? serviceUUIDs[0] : null;
            Debug.Log($"[BLEPluginAndroid] StartScanFiltered(uuid={primaryUUID} name={nameFilter} rssi={minRSSI} dur={durationSeconds}s)");
            _scan.Call("startScanFiltered", primaryUUID, nameFilter, minRSSI, durationSeconds);
#endif
        }

        /// <inheritdoc/>
        public void StopScan()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            Debug.Log("[BLEPluginAndroid] StopScan");
            _scan.Call("stopScan");
#endif
        }

        // ── IBLEPlugin: Connect / Disconnect ──────────────────────────────────

        /// <inheritdoc/>
        public void Connect(string deviceId, float timeoutSeconds, Action<bool> onResult)
        {
            _pendingConnectCallbacks[deviceId] = onResult;
#if UNITY_ANDROID && !UNITY_EDITOR
            Debug.Log($"[BLEPluginAndroid] Connect({deviceId}, timeout={timeoutSeconds}s)");
            _connection.Call("connect", deviceId, timeoutSeconds);
#endif
        }

        /// <inheritdoc/>
        public void Disconnect(string deviceId)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            Debug.Log($"[BLEPluginAndroid] Disconnect({deviceId})");
            _connection.Call("disconnect", deviceId);
#endif
        }

        // ── IBLEPlugin: GATT Discovery ────────────────────────────────────────

        /// <inheritdoc/>
        public void DiscoverServices(string deviceId, Action<string[]> onComplete)
        {
            _pendingServicesCallbacks[deviceId] = onComplete;
#if UNITY_ANDROID && !UNITY_EDITOR
            Debug.Log($"[BLEPluginAndroid] DiscoverServices({deviceId})");
            _gatt.Call("discoverServices", deviceId);
#endif
        }

        /// <inheritdoc/>
        public void DiscoverCharacteristics(string deviceId, string serviceUUID,
                                             Action<BLECharacteristic[]> onComplete)
        {
            // Key: "<deviceId>|<serviceUUID>" in case of concurrent discoveries
            string key = $"{deviceId}|{serviceUUID}";
            _pendingCharCallbacks[key] = onComplete;
#if UNITY_ANDROID && !UNITY_EDITOR
            Debug.Log($"[BLEPluginAndroid] DiscoverCharacteristics({deviceId}, svc={serviceUUID})");
            _gatt.Call("discoverCharacteristics", deviceId, serviceUUID);
#endif
        }

        // ── IBLEPlugin: Characteristic Read / Write ───────────────────────────

        /// <inheritdoc/>
        public void ReadCharacteristic(string deviceId, string serviceUUID, string charUUID,
                                       Action<byte[]> onResult)
        {
            string key = $"{deviceId}|{charUUID}";
            _pendingReadCallbacks[key] = onResult;
#if UNITY_ANDROID && !UNITY_EDITOR
            Debug.Log($"[BLEPluginAndroid] ReadCharacteristic({deviceId}, char={charUUID})");
            _gatt.Call("readCharacteristic", deviceId, serviceUUID, charUUID);
#endif
        }

        /// <inheritdoc/>
        public void WriteCharacteristic(string deviceId, string serviceUUID, string charUUID,
                                        byte[] data, bool withResponse, Action<bool> onResult)
        {
            string key = $"{deviceId}|{charUUID}";
            _pendingWriteCallbacks[key] = onResult;
#if UNITY_ANDROID && !UNITY_EDITOR
            Debug.Log($"[BLEPluginAndroid] WriteCharacteristic({deviceId}, char={charUUID}, {data?.Length ?? 0} bytes, withResp={withResponse})");
            _gatt.Call("writeCharacteristic", deviceId, serviceUUID, charUUID, data, withResponse);
#endif
        }

        // ── IBLEPlugin: Subscribe / Unsubscribe ───────────────────────────────

        /// <inheritdoc/>
        public void Subscribe(string deviceId, string serviceUUID, string charUUID,
                              Action<byte[]> onData)
        {
            // Store the notification handler keyed by charUUID. Multiple devices
            // can subscribe to the same char UUID (e.g., two Polar H10 devices),
            // so we key by "<deviceId>|<charUUID>" for correct dispatch routing.
            string key = $"{deviceId}|{charUUID}";
            _subscribeCallbacks[key] = onData;
#if UNITY_ANDROID && !UNITY_EDITOR
            Debug.Log($"[BLEPluginAndroid] Subscribe({deviceId}, char={charUUID})");
            _gatt.Call("subscribe", deviceId, serviceUUID, charUUID);
#endif
        }

        /// <inheritdoc/>
        public void Unsubscribe(string deviceId, string serviceUUID, string charUUID)
        {
            string key = $"{deviceId}|{charUUID}";
            _subscribeCallbacks.Remove(key);
#if UNITY_ANDROID && !UNITY_EDITOR
            Debug.Log($"[BLEPluginAndroid] Unsubscribe({deviceId}, char={charUUID})");
            _gatt.Call("unsubscribe", deviceId, serviceUUID, charUUID);
#endif
        }

        // ── IBLEPlugin: Battery ───────────────────────────────────────────────

        /// <inheritdoc/>
        public void ReadBattery(string deviceId, Action<int> onResult)
        {
            _pendingBatteryCallback = onResult;
            _pendingBatteryDeviceId = deviceId;
#if UNITY_ANDROID && !UNITY_EDITOR
            Debug.Log($"[BLEPluginAndroid] ReadBattery({deviceId})");
            _gatt.Call("readBattery", deviceId);
#endif
        }

        // ══════════════════════════════════════════════════════════════════════
        // UnitySendMessage callbacks (public — called by Unity runtime)
        //
        // Java sends: UnityPlayer.UnitySendMessage("BLEManager", "OnXxx", payload)
        // Unity routes the call to this public method on the BLEManager GameObject.
        // All payloads arrive on the Unity main thread (Android marshals them).
        // ══════════════════════════════════════════════════════════════════════

        // ── BLEAndroidPlugin callbacks ────────────────────────────────────────

        /// <summary>
        /// Called when the Bluetooth adapter is ready and enabled.
        /// Payload: "adapter_ok"
        /// </summary>
        public void OnBleReady(string payload)
        {
            Debug.Log($"[BLEPluginAndroid] OnBleReady: {payload}");
        }

        /// <summary>
        /// Called with the result of a permission request.
        /// Payload: "granted" | "denied"
        /// </summary>
        public void OnPermissions(string payload)
        {
            Debug.Log($"[BLEPluginAndroid] OnPermissions: {payload}");
        }

        /// <summary>
        /// Called when a fatal BLE stack error occurs (e.g., Bluetooth disabled).
        /// Payload: error description string (e.g., "bt_disabled", "bt_unavailable")
        /// </summary>
        public void OnBleError(string payload)
        {
            Debug.LogError($"[BLEPluginAndroid] OnBleError: {payload}");
        }

        // ── BLEScan callbacks ─────────────────────────────────────────────────

        /// <summary>
        /// Called once per unique device found during a scan.
        /// Payload JSON: {"id":"AA:BB:CC:DD:EE:FF","name":"Polar H10","rssi":-65}
        /// </summary>
        public void OnDeviceFound(string json)
        {
            var parsed = JsonUtility.FromJson<DeviceFoundPayload>(json);
            if (parsed == null)
            {
                Debug.LogWarning($"[BLEPluginAndroid] OnDeviceFound — failed to parse JSON: {json}");
                return;
            }

            var device = new BLEDevice
            {
                Id   = parsed.id,
                Name = parsed.name,
                RSSI = parsed.rssi
            };
            _scanResults.Add(device);
            Debug.Log($"[BLEPluginAndroid] OnDeviceFound: {parsed.name} ({parsed.id}) RSSI={parsed.rssi}");
        }

        /// <summary>
        /// Called when the scan window ends (duration elapsed or StopScan() called).
        /// Payload: "" (empty string)
        /// </summary>
        public void OnScanComplete(string payload)
        {
            Debug.Log($"[BLEPluginAndroid] OnScanComplete — {_scanResults.Count} device(s) found.");
            var callback = _pendingScanCallback;
            _pendingScanCallback = null;
            callback?.Invoke(_scanResults.ToArray());
        }

        /// <summary>
        /// Called when the scan fails to start.
        /// Payload: error code string (e.g., "SCAN_FAILED_INTERNAL_ERROR")
        /// </summary>
        public void OnScanError(string payload)
        {
            Debug.LogError($"[BLEPluginAndroid] OnScanError: {payload}");
            var callback = _pendingScanCallback;
            _pendingScanCallback = null;
            callback?.Invoke(Array.Empty<BLEDevice>());
        }

        // ── BLEConnection callbacks ───────────────────────────────────────────

        /// <summary>
        /// Called when a GATT connection is established successfully.
        /// Payload: deviceId (MAC address string)
        /// </summary>
        public void OnConnected(string deviceId)
        {
            Debug.Log($"[BLEPluginAndroid] OnConnected: {deviceId}");

            if (_pendingConnectCallbacks.TryGetValue(deviceId, out var cb))
            {
                _pendingConnectCallbacks.Remove(deviceId);
                cb.Invoke(true);
            }

            OnConnectionStateChanged?.Invoke(deviceId, true);
        }

        /// <summary>
        /// Called when a connection closes, either intentionally or unexpectedly.
        /// Payload JSON: {"id":"AA:BB:CC:DD:EE:FF","reason":"intentional"|"unexpected"}
        /// </summary>
        public void OnDisconnected(string json)
        {
            var parsed = JsonUtility.FromJson<DisconnectedPayload>(json);
            if (parsed == null)
            {
                Debug.LogWarning($"[BLEPluginAndroid] OnDisconnected — failed to parse JSON: {json}");
                return;
            }

            Debug.Log($"[BLEPluginAndroid] OnDisconnected: {parsed.id} reason={parsed.reason}");
            OnConnectionStateChanged?.Invoke(parsed.id, false);
        }

        /// <summary>
        /// Called when a connection attempt fails (e.g., timeout, GATT error 133).
        /// Payload JSON: {"id":"AA:BB:CC:DD:EE:FF","error":"TIMEOUT","status":133}
        /// </summary>
        public void OnConnectionError(string json)
        {
            var parsed = JsonUtility.FromJson<ConnectionErrorPayload>(json);
            string deviceId = parsed?.id ?? "";
            Debug.LogError($"[BLEPluginAndroid] OnConnectionError: {json}");

            if (_pendingConnectCallbacks.TryGetValue(deviceId, out var cb))
            {
                _pendingConnectCallbacks.Remove(deviceId);
                cb.Invoke(false);
            }
        }

        // ── BLEGatt callbacks ─────────────────────────────────────────────────

        /// <summary>
        /// Called after discoverServices() completes.
        /// Payload JSON: {"id":"AA:BB:CC:DD:EE:FF","services":["uuid1","uuid2"]}
        /// </summary>
        public void OnServicesDiscovered(string json)
        {
            var parsed = JsonUtility.FromJson<ServicesDiscoveredPayload>(json);
            if (parsed == null)
            {
                Debug.LogWarning($"[BLEPluginAndroid] OnServicesDiscovered — failed to parse JSON: {json}");
                return;
            }

            Debug.Log($"[BLEPluginAndroid] OnServicesDiscovered: {parsed.id} — {parsed.services?.Length ?? 0} service(s)");

            if (_pendingServicesCallbacks.TryGetValue(parsed.id, out var cb))
            {
                _pendingServicesCallbacks.Remove(parsed.id);
                cb.Invoke(parsed.services ?? Array.Empty<string>());
            }
        }

        /// <summary>
        /// Called after discoverCharacteristics() completes.
        /// Payload JSON: {"id":"..","service":"uuid","characteristics":[{"uuid":"..","properties":10},...]}
        /// </summary>
        public void OnCharacteristicsDiscovered(string json)
        {
            var parsed = JsonUtility.FromJson<CharacteristicsDiscoveredPayload>(json);
            if (parsed == null)
            {
                Debug.LogWarning($"[BLEPluginAndroid] OnCharacteristicsDiscovered — failed to parse JSON: {json}");
                return;
            }

            Debug.Log($"[BLEPluginAndroid] OnCharacteristicsDiscovered: {parsed.id} svc={parsed.service} — {parsed.characteristics?.Length ?? 0} char(s)");

            string key = $"{parsed.id}|{parsed.service}";
            if (_pendingCharCallbacks.TryGetValue(key, out var cb))
            {
                _pendingCharCallbacks.Remove(key);
                var result = ConvertCharacteristics(parsed.characteristics);
                cb.Invoke(result);
            }
        }

        /// <summary>
        /// Called when a ReadCharacteristic() operation completes.
        /// Payload JSON: {"id":"..","service":"uuid","characteristic":"uuid","value":"base64"}
        /// </summary>
        public void OnCharacteristicRead(string json)
        {
            var parsed = JsonUtility.FromJson<CharacteristicReadPayload>(json);
            if (parsed == null)
            {
                Debug.LogWarning($"[BLEPluginAndroid] OnCharacteristicRead — failed to parse JSON: {json}");
                return;
            }

            Debug.Log($"[BLEPluginAndroid] OnCharacteristicRead: {parsed.id} char={parsed.characteristic}");

            string key = $"{parsed.id}|{parsed.characteristic}";
            if (_pendingReadCallbacks.TryGetValue(key, out var cb))
            {
                _pendingReadCallbacks.Remove(key);
                byte[] data = DecodeBase64Safe(parsed.value);
                cb.Invoke(data);
            }
        }

        /// <summary>
        /// Called when a WriteCharacteristic() operation completes.
        /// Payload JSON: {"id":"..","characteristic":"uuid","success":true}
        /// </summary>
        public void OnCharacteristicWrite(string json)
        {
            var parsed = JsonUtility.FromJson<CharacteristicWritePayload>(json);
            if (parsed == null)
            {
                Debug.LogWarning($"[BLEPluginAndroid] OnCharacteristicWrite — failed to parse JSON: {json}");
                return;
            }

            Debug.Log($"[BLEPluginAndroid] OnCharacteristicWrite: {parsed.id} char={parsed.characteristic} success={parsed.success}");

            string key = $"{parsed.id}|{parsed.characteristic}";
            if (_pendingWriteCallbacks.TryGetValue(key, out var cb))
            {
                _pendingWriteCallbacks.Remove(key);
                cb.Invoke(parsed.success);
            }
        }

        /// <summary>
        /// Called each time a subscribed characteristic sends a notification.
        /// Payload JSON: {"id":"..","characteristic":"uuid","value":"base64"}
        /// </summary>
        public void OnCharacteristicChanged(string json)
        {
            var parsed = JsonUtility.FromJson<CharacteristicChangedPayload>(json);
            if (parsed == null) return;

            string key = $"{parsed.id}|{parsed.characteristic}";
            if (_subscribeCallbacks.TryGetValue(key, out var cb))
            {
                byte[] data = DecodeBase64Safe(parsed.value);
                cb.Invoke(data);
            }
        }

        /// <summary>
        /// Called when ReadBattery() completes.
        /// Payload JSON: {"id":"..","level":85}
        /// </summary>
        public void OnBatteryLevel(string json)
        {
            var parsed = JsonUtility.FromJson<BatteryLevelPayload>(json);
            if (parsed == null)
            {
                Debug.LogWarning($"[BLEPluginAndroid] OnBatteryLevel — failed to parse JSON: {json}");
                return;
            }

            Debug.Log($"[BLEPluginAndroid] OnBatteryLevel: {parsed.id} = {parsed.level}%");

            if (_pendingBatteryDeviceId == parsed.id)
            {
                var cb = _pendingBatteryCallback;
                _pendingBatteryCallback = null;
                _pendingBatteryDeviceId = null;
                cb?.Invoke(parsed.level);
            }
        }

        /// <summary>
        /// Called when a GATT operation fails at the Android API layer.
        /// Payload JSON: {"id":"..","operation":"read","error":"GATT_ERROR"}
        /// </summary>
        public void OnGattError(string json)
        {
            Debug.LogError($"[BLEPluginAndroid] OnGattError: {json}");
            // Surface to pending callbacks so callers don't hang indefinitely.
            var parsed = JsonUtility.FromJson<GattErrorPayload>(json);
            if (parsed == null) return;

            // Read callbacks
            foreach (var kv in new Dictionary<string, Action<byte[]>>(_pendingReadCallbacks))
            {
                if (kv.Key.StartsWith(parsed.id))
                {
                    _pendingReadCallbacks.Remove(kv.Key);
                    kv.Value?.Invoke(null);
                }
            }

            // Write callbacks
            foreach (var kv in new Dictionary<string, Action<bool>>(_pendingWriteCallbacks))
            {
                if (kv.Key.StartsWith(parsed.id))
                {
                    _pendingWriteCallbacks.Remove(kv.Key);
                    kv.Value?.Invoke(false);
                }
            }
        }

        // ── Muse EEG callbacks ────────────────────────────────────────────────

        /// <summary>
        /// Called when EEG streaming starts successfully.
        /// Payload: "started"
        /// </summary>
        public void OnEEGStreaming(string payload)
        {
            Debug.Log($"[BLEPluginAndroid] OnEEGStreaming: {payload}");
        }

        /// <summary>
        /// Called once per EEG channel per BLE notification (~10×/s per channel).
        /// Payload JSON: {"id":"..","channel":"TP9","counter":42,"samples":[...12 floats in µV...]}
        /// Consumers should subscribe to the EEG characteristics via the standard
        /// Subscribe() path; this callback is a convenience hook for diagnostics.
        /// </summary>
        public void OnEEGData(string json)
        {
            // High-frequency path — avoid per-call allocations where possible.
            // Consumers are responsible for parsing and buffering this data.
            // Debug.Log omitted intentionally (256 Hz × 4 channels = ~1000 calls/s).
        }

        /// <summary>
        /// Called when EEG streaming encounters a non-recoverable error.
        /// Payload: error description string
        /// </summary>
        public void OnEEGError(string payload)
        {
            Debug.LogError($"[BLEPluginAndroid] OnEEGError: {payload}");
        }

        // ── Additional optional callbacks ─────────────────────────────────────

        /// <summary>
        /// Called when Device Information Service (DIS) characteristics are read.
        /// Payload JSON: {"id":"..","manufacturer":"..","model":"..","firmware":".."}
        /// </summary>
        public void OnDeviceInfoRead(string json)
        {
            Debug.Log($"[BLEPluginAndroid] OnDeviceInfoRead: {json}");
        }

        /// <summary>
        /// Called when a DIS read fails.
        /// Payload: error description string
        /// </summary>
        public void OnDeviceInfoError(string payload)
        {
            Debug.LogWarning($"[BLEPluginAndroid] OnDeviceInfoError: {payload}");
        }

        /// <summary>
        /// Called when the bonding/pairing state changes.
        /// Payload: "bonded" | "bonding" | "none"
        /// </summary>
        public void OnPairingState(string payload)
        {
            Debug.Log($"[BLEPluginAndroid] OnPairingState: {payload}");
        }

        /// <summary>
        /// Called when an MTU negotiation completes.
        /// Payload JSON: {"id":"..","mtu":185}
        /// </summary>
        public void OnMtuChanged(string json)
        {
            Debug.Log($"[BLEPluginAndroid] OnMtuChanged: {json}");
        }

        /// <summary>
        /// Called when a remote RSSI read completes.
        /// Payload JSON: {"id":"..","rssi":-65}
        /// </summary>
        public void OnRSSIRead(string json)
        {
            Debug.Log($"[BLEPluginAndroid] OnRSSIRead: {json}");
        }

        // ══════════════════════════════════════════════════════════════════════
        // Muse EEG streaming helper (outside IBLEPlugin — device-specific)
        //
        // MuseEEG.startStreaming() / stopStreaming() wrap the Java MuseEEG class
        // which handles the Muse 2 uint:12 bit-unpacking and delivers decoded
        // µV samples via OnEEGData(). The standard Subscribe/Unsubscribe path
        // works for raw bytes; these helpers are a higher-level convenience.
        // ══════════════════════════════════════════════════════════════════════

        /// <summary>
        /// Start high-level Muse EEG streaming for the given device.
        ///
        /// The MuseEEG Java class subscribes to all 4 EEG channels, unpacks
        /// the uint:12 bit-packed samples, converts them to µV, and delivers
        /// results via OnEEGData() (~10 calls/s per channel at 256 Hz).
        ///
        /// Must be called AFTER a successful Connect() and DiscoverServices().
        /// </summary>
        /// <param name="deviceId">MAC address of the connected Muse device.</param>
        public void StartMuseEEGStreaming(string deviceId)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            Debug.Log($"[BLEPluginAndroid] StartMuseEEGStreaming({deviceId})");
            _museEeg.Call("startStreaming", deviceId);
#else
            Debug.Log($"[BLEPluginAndroid] StartMuseEEGStreaming — non-Android no-op.");
#endif
        }

        /// <summary>
        /// Stop Muse EEG streaming and unsubscribe from all EEG characteristics.
        /// </summary>
        /// <param name="deviceId">MAC address of the connected Muse device.</param>
        public void StopMuseEEGStreaming(string deviceId)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            Debug.Log($"[BLEPluginAndroid] StopMuseEEGStreaming({deviceId})");
            _museEeg.Call("stopStreaming", deviceId);
#else
            Debug.Log($"[BLEPluginAndroid] StopMuseEEGStreaming — non-Android no-op.");
#endif
        }

        // ══════════════════════════════════════════════════════════════════════
        // Private helpers
        // ══════════════════════════════════════════════════════════════════════

        /// <summary>
        /// Decode a Base64 string to a byte array, returning null on failure.
        /// Java sends BLE characteristic values as Base64 to avoid binary
        /// transfer issues through the UnitySendMessage string channel.
        /// </summary>
        private static byte[] DecodeBase64Safe(string base64)
        {
            if (string.IsNullOrEmpty(base64)) return null;
            try
            {
                return Convert.FromBase64String(base64);
            }
            catch (FormatException ex)
            {
                Debug.LogWarning($"[BLEPluginAndroid] Base64 decode failed: {ex.Message}");
                return null;
            }
        }

        /// <summary>
        /// Convert the raw JSON characteristic descriptors from Java into the
        /// C# BLECharacteristic array used by the rest of the BLE layer.
        /// </summary>
        private static BLECharacteristic[] ConvertCharacteristics(CharacteristicDescriptor[] raw)
        {
            if (raw == null) return Array.Empty<BLECharacteristic>();
            var result = new BLECharacteristic[raw.Length];
            for (int i = 0; i < raw.Length; i++)
            {
                result[i] = new BLECharacteristic
                {
                    UUID       = raw[i]?.uuid ?? string.Empty,
                    Properties = raw[i]?.properties ?? 0
                };
            }
            return result;
        }

        // ══════════════════════════════════════════════════════════════════════
        // JSON payload DTOs (JsonUtility requires [Serializable])
        // Using inline private structs keeps all deserialization logic
        // co-located with the callback methods that consume it.
        // ══════════════════════════════════════════════════════════════════════

        [Serializable]
        private class DeviceFoundPayload
        {
            public string id;
            public string name;
            public int    rssi;
        }

        [Serializable]
        private class DisconnectedPayload
        {
            public string id;
            public string reason; // "intentional" | "unexpected"
        }

        [Serializable]
        private class ConnectionErrorPayload
        {
            public string id;
            public string error;
            public int    status;
        }

        [Serializable]
        private class ServicesDiscoveredPayload
        {
            public string   id;
            public string[] services;
        }

        [Serializable]
        private class CharacteristicDescriptor
        {
            public string uuid;
            public int    properties;
        }

        [Serializable]
        private class CharacteristicsDiscoveredPayload
        {
            public string                   id;
            public string                   service;
            public CharacteristicDescriptor[] characteristics;
        }

        [Serializable]
        private class CharacteristicReadPayload
        {
            public string id;
            public string service;
            public string characteristic;
            public string value; // Base64-encoded bytes
        }

        [Serializable]
        private class CharacteristicWritePayload
        {
            public string id;
            public string characteristic;
            public bool   success;
        }

        [Serializable]
        private class CharacteristicChangedPayload
        {
            public string id;
            public string characteristic;
            public string value; // Base64-encoded bytes
        }

        [Serializable]
        private class BatteryLevelPayload
        {
            public string id;
            public int    level;
        }

        [Serializable]
        private class GattErrorPayload
        {
            public string id;
            public string operation;
            public string error;
        }
    }
}
