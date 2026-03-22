// BLEManager.cs — Unity MonoBehaviour singleton facade for the IBLEPlugin system.
//
// USAGE:
//   BLEManager.Instance.Initialize();
//   BLEManager.Instance.StartScan(5f, devices => Debug.Log(devices.Length));
//
// PLATFORM SELECTION:
//   At runtime, BLEManager instantiates the correct IBLEPlugin implementation
//   based on the build target via #if UNITY_ANDROID / UNITY_IOS etc.
//   In the Unity Editor, a stub NullBLEPlugin is used (logs all calls).
//
// THREADING:
//   Native BLE callbacks arrive on background threads (iOS/macOS via CoreBluetooth dispatch queues).
//   Android's UnitySendMessage arrives on the main thread, but we normalize via SynchronizationContext
//   for consistency across all platforms.
//   ALL onComplete / onResult / onData callbacks passed to IBLEPlugin methods are guaranteed
//   to be called on the Unity main thread.
//
// LIFECYCLE:
//   BLEManager survives scene loads (DontDestroyOnLoad).
//   Call Initialize() once at app startup before any BLE operations.

using System;
using System.Threading;
using UnityEngine;

namespace BLEReference
{
    /// <summary>
    /// Singleton MonoBehaviour that owns the IBLEPlugin instance and ensures
    /// all BLE callbacks are dispatched on the Unity main thread.
    /// </summary>
    public class BLEManager : MonoBehaviour
    {
        // ── Singleton ────────────────────────────────────────────────────────────────

        private static BLEManager _instance;

        /// <summary>
        /// The singleton instance. Auto-creates a GameObject if not present in the scene.
        /// </summary>
        public static BLEManager Instance
        {
            get
            {
                if (_instance == null)
                {
                    var go = new GameObject("BLEManager");
                    _instance = go.AddComponent<BLEManager>();
                    DontDestroyOnLoad(go);
                }
                return _instance;
            }
        }

        // ── Plugin ───────────────────────────────────────────────────────────────────

        private IBLEPlugin _plugin;

        /// <summary>The active platform plugin. Null until Initialize() is called.</summary>
        public IBLEPlugin Plugin => _plugin;

        // ── Main-thread dispatch ──────────────────────────────────────────────────────

        private SynchronizationContext _mainThreadContext;

        // ── Unity lifecycle ───────────────────────────────────────────────────────────

        private void Awake()
        {
            if (_instance != null && _instance != this)
            {
                Destroy(gameObject);
                return;
            }
            _instance = this;
            DontDestroyOnLoad(gameObject);

            // Capture the main thread SynchronizationContext in Awake (always runs on main thread).
            _mainThreadContext = SynchronizationContext.Current;
        }

        // ── Initialization ────────────────────────────────────────────────────────────

        /// <summary>
        /// Initialize BLE. Creates the platform plugin and calls Initialize() on it.
        /// Must be called before any scan/connect operations.
        /// </summary>
        public void Initialize()
        {
            if (_plugin != null)
            {
                Debug.LogWarning("[BLEManager] Already initialized.");
                return;
            }

            _plugin = CreatePlatformPlugin();
            _plugin.OnConnectionStateChanged += OnConnectionStateChangedInternal;
            _plugin.Initialize();
            Debug.Log($"[BLEManager] Initialized with plugin: {_plugin.GetType().Name}");
        }

        // ── Platform plugin factory ───────────────────────────────────────────────────

        private IBLEPlugin CreatePlatformPlugin()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            // 03_unity_android_quest — Java/JNI plugin via UnitySendMessage
            return new BLEPluginAndroid();
#elif (UNITY_IOS || UNITY_TVOS) && !UNITY_EDITOR
            // 05_unity_ios — Obj-C CoreBluetooth plugin
            return new BLEPluginIOS();
#elif UNITY_STANDALONE_OSX && !UNITY_EDITOR
            // 04_unity_mac — Obj-C CoreBluetooth plugin
            return new BLEPluginMacOS();
#else
            // Unity Editor or unsupported platform — stub that logs all calls
            Debug.LogWarning("[BLEManager] Running NullBLEPlugin (Editor/unsupported platform). No real BLE operations.");
            return new NullBLEPlugin();
#endif
        }

        // ── Forwarding methods (main-thread dispatch) ─────────────────────────────────

        /// <summary>Wraps a callback to guarantee delivery on the Unity main thread.</summary>
        private Action<T> OnMainThread<T>(Action<T> callback)
        {
            if (callback == null) return null;
            return value => _mainThreadContext.Post(_ => callback(value), null);
        }

        /// <summary>Wraps a callback to guarantee delivery on the Unity main thread.</summary>
        private Action<T1, T2> OnMainThread<T1, T2>(Action<T1, T2> callback)
        {
            if (callback == null) return null;
            return (v1, v2) => _mainThreadContext.Post(_ => callback(v1, v2), null);
        }

        // ── Public API (wraps IBLEPlugin with main-thread dispatch) ───────────────────

        public void StartScan(float durationSeconds, Action<BLEDevice[]> onComplete)
            => _plugin.StartScan(durationSeconds, OnMainThread(onComplete));

        public void StartScanFiltered(string[] serviceUUIDs, string nameFilter, int minRSSI,
                                      float durationSeconds, Action<BLEDevice[]> onComplete)
            => _plugin.StartScanFiltered(serviceUUIDs, nameFilter, minRSSI, durationSeconds, OnMainThread(onComplete));

        public void StopScan() => _plugin?.StopScan();

        public void Connect(string deviceId, float timeoutSeconds, Action<bool> onResult)
            => _plugin.Connect(deviceId, timeoutSeconds, OnMainThread(onResult));

        public void Disconnect(string deviceId) => _plugin?.Disconnect(deviceId);

        public void DiscoverServices(string deviceId, Action<string[]> onComplete)
            => _plugin.DiscoverServices(deviceId, OnMainThread(onComplete));

        public void DiscoverCharacteristics(string deviceId, string serviceUUID,
                                             Action<BLECharacteristic[]> onComplete)
            => _plugin.DiscoverCharacteristics(deviceId, serviceUUID, OnMainThread(onComplete));

        public void ReadCharacteristic(string deviceId, string serviceUUID, string charUUID,
                                       Action<byte[]> onResult)
            => _plugin.ReadCharacteristic(deviceId, serviceUUID, charUUID, OnMainThread(onResult));

        public void WriteCharacteristic(string deviceId, string serviceUUID, string charUUID,
                                        byte[] data, bool withResponse, Action<bool> onResult)
            => _plugin.WriteCharacteristic(deviceId, serviceUUID, charUUID, data, withResponse, OnMainThread(onResult));

        public void Subscribe(string deviceId, string serviceUUID, string charUUID, Action<byte[]> onData)
            => _plugin.Subscribe(deviceId, serviceUUID, charUUID, OnMainThread(onData));

        public void Unsubscribe(string deviceId, string serviceUUID, string charUUID)
            => _plugin?.Unsubscribe(deviceId, serviceUUID, charUUID);

        public void ReadBattery(string deviceId, Action<int> onResult)
            => _plugin.ReadBattery(deviceId, OnMainThread(onResult));

        // ── Connection state event ────────────────────────────────────────────────────

        /// <summary>Raised on the Unity main thread when any peripheral connects or disconnects unexpectedly.</summary>
        public event Action<string, bool> OnConnectionStateChanged;

        private void OnConnectionStateChangedInternal(string deviceId, bool connected)
        {
            _mainThreadContext.Post(_ => OnConnectionStateChanged?.Invoke(deviceId, connected), null);
        }

        // ── NullBLEPlugin (Editor stub) ───────────────────────────────────────────────

        /// <summary>
        /// Stub plugin used in the Unity Editor and on unsupported platforms.
        /// Logs all calls and invokes callbacks with empty/default results so game logic doesn't crash.
        /// </summary>
        private class NullBLEPlugin : IBLEPlugin
        {
            public event Action<string, bool> OnConnectionStateChanged;

            public void Initialize() => Debug.Log("[NullBLEPlugin] Initialize");
            public void StartScan(float duration, Action<BLEDevice[]> onComplete)
            {
                Debug.Log($"[NullBLEPlugin] StartScan({duration}s)");
                onComplete?.Invoke(Array.Empty<BLEDevice>());
            }
            public void StartScanFiltered(string[] uuids, string name, int rssi, float duration, Action<BLEDevice[]> onComplete)
            {
                Debug.Log($"[NullBLEPlugin] StartScanFiltered");
                onComplete?.Invoke(Array.Empty<BLEDevice>());
            }
            public void StopScan() => Debug.Log("[NullBLEPlugin] StopScan");
            public void Connect(string id, float timeout, Action<bool> onResult)
            {
                Debug.Log($"[NullBLEPlugin] Connect({id})");
                onResult?.Invoke(false);
            }
            public void Disconnect(string id) => Debug.Log($"[NullBLEPlugin] Disconnect({id})");
            public void DiscoverServices(string id, Action<string[]> onComplete)
            {
                Debug.Log($"[NullBLEPlugin] DiscoverServices({id})");
                onComplete?.Invoke(Array.Empty<string>());
            }
            public void DiscoverCharacteristics(string id, string svcUUID, Action<BLECharacteristic[]> onComplete)
            {
                Debug.Log($"[NullBLEPlugin] DiscoverCharacteristics");
                onComplete?.Invoke(Array.Empty<BLECharacteristic>());
            }
            public void ReadCharacteristic(string id, string svc, string chr, Action<byte[]> onResult)
            {
                Debug.Log($"[NullBLEPlugin] ReadCharacteristic({chr})");
                onResult?.Invoke(null);
            }
            public void WriteCharacteristic(string id, string svc, string chr, byte[] data, bool withResp, Action<bool> onResult)
            {
                Debug.Log($"[NullBLEPlugin] WriteCharacteristic({chr}, {data?.Length ?? 0} bytes)");
                onResult?.Invoke(false);
            }
            public void Subscribe(string id, string svc, string chr, Action<byte[]> onData)
                => Debug.Log($"[NullBLEPlugin] Subscribe({chr})");
            public void Unsubscribe(string id, string svc, string chr)
                => Debug.Log($"[NullBLEPlugin] Unsubscribe({chr})");
            public void ReadBattery(string id, Action<int> onResult)
            {
                Debug.Log($"[NullBLEPlugin] ReadBattery({id})");
                onResult?.Invoke(-1);
            }
        }
    }
}
