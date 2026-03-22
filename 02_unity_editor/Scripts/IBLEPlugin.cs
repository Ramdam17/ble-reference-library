// IBLEPlugin.cs — Shared C# interface for all platform BLE plugins.
//
// ARCHITECTURE: Unity uses a two-layer BLE architecture:
//   Layer 1: IBLEPlugin (this file) — the common C# contract consumed by game code.
//   Layer 2: Platform plugins — implement IBLEPlugin per target:
//            - BLEPluginAndroid  (03_unity_android_quest) — Java/JNI + UnitySendMessage
//            - BLEPluginIOS      (05_unity_ios)           — Obj-C + native callbacks
//            - BLEPluginMacOS    (04_unity_mac)           — Obj-C + native callbacks
//            - BLEPluginEditor   (02_unity_editor)        — stub / simulator for Editor
//
// BLEManager (BLEManager.cs) selects the right plugin at runtime via #if UNITY_ANDROID etc.
// All user-facing code calls BLEManager.Instance.Plugin, never a concrete plugin directly.
//
// CALLBACK THREADING: All callbacks (onComplete, onResult, onData) are guaranteed to be
// invoked on the Unity main thread by BLEManager. Plugin implementations do NOT need to
// marshal to the main thread themselves — BLEManager handles this via SynchronizationContext.
//
// REFERENCE: Phase 01 (Python/Bleak) and Phase 02 (Swift/CoreBluetooth) are the canonical
// behavior references. When in doubt about expected semantics, read those first.

using System;

namespace BLEReference
{
    /// <summary>
    /// Common contract for all platform-specific BLE plugins.
    /// Implemented per-platform; selected by BLEManager at runtime.
    /// </summary>
    public interface IBLEPlugin
    {
        // ── Lifecycle ────────────────────────────────────────────────────────────────

        /// <summary>
        /// Initialize the BLE stack. Must be called before any other method.
        /// On Android: checks Bluetooth adapter availability; requests runtime permissions
        /// (BLUETOOTH_SCAN, BLUETOOTH_CONNECT) if not yet granted.
        /// </summary>
        void Initialize();

        // ── Scanning ─────────────────────────────────────────────────────────────────

        /// <summary>
        /// Start an unfiltered BLE scan. Discovers all advertising peripherals.
        /// Calls <paramref name="onComplete"/> with accumulated results after <paramref name="durationSeconds"/>.
        /// </summary>
        /// <param name="durationSeconds">Scan duration in seconds. Recommended: 5–10s.</param>
        /// <param name="onComplete">Called with the array of discovered devices when the scan ends.</param>
        void StartScan(float durationSeconds, Action<BLEDevice[]> onComplete);

        /// <summary>
        /// Start a filtered BLE scan.
        /// Filters are ANDed: a device must match ALL non-null/non-empty filters to be returned.
        /// </summary>
        /// <param name="serviceUUIDs">Return only devices advertising these service UUIDs. Null = ignore.</param>
        /// <param name="nameFilter">Return only devices whose name contains this substring (case-insensitive). Null = ignore.</param>
        /// <param name="minRSSI">Return only devices with RSSI >= this value. Use int.MinValue to ignore.</param>
        /// <param name="durationSeconds">Scan duration in seconds.</param>
        /// <param name="onComplete">Called with filtered results when scan ends.</param>
        void StartScanFiltered(string[] serviceUUIDs, string nameFilter, int minRSSI,
                               float durationSeconds, Action<BLEDevice[]> onComplete);

        /// <summary>Stop an in-progress scan immediately.</summary>
        void StopScan();

        // ── Connection ───────────────────────────────────────────────────────────────

        /// <summary>
        /// Connect to a peripheral by its platform-specific device ID.
        /// </summary>
        /// <param name="deviceId">Device identifier from BLEDevice.Id.</param>
        /// <param name="timeoutSeconds">Abandon connection attempt after this many seconds.</param>
        /// <param name="onResult">Called with true on success, false on failure/timeout.</param>
        void Connect(string deviceId, float timeoutSeconds, Action<bool> onResult);

        /// <summary>Disconnect from a connected peripheral.</summary>
        void Disconnect(string deviceId);

        /// <summary>
        /// Raised when a peripheral's connection state changes unexpectedly (e.g. out-of-range drop).
        /// arg1: deviceId, arg2: true = connected, false = disconnected.
        /// Always delivered on the Unity main thread by BLEManager.
        /// </summary>
        event Action<string, bool> OnConnectionStateChanged;

        // ── GATT Discovery ───────────────────────────────────────────────────────────

        /// <summary>
        /// Discover all GATT services on a connected peripheral.
        /// </summary>
        /// <param name="deviceId">Must be connected.</param>
        /// <param name="onComplete">Called with array of service UUID strings.</param>
        void DiscoverServices(string deviceId, Action<string[]> onComplete);

        /// <summary>
        /// Discover all characteristics within a GATT service.
        /// </summary>
        /// <param name="deviceId">Must be connected.</param>
        /// <param name="serviceUUID">UUID of the service to inspect.</param>
        /// <param name="onComplete">Called with array of BLECharacteristic DTOs.</param>
        void DiscoverCharacteristics(string deviceId, string serviceUUID,
                                     Action<BLECharacteristic[]> onComplete);

        // ── Read / Write ─────────────────────────────────────────────────────────────

        /// <summary>Read a characteristic value.</summary>
        /// <param name="deviceId">Must be connected.</param>
        /// <param name="serviceUUID">Service UUID containing the characteristic.</param>
        /// <param name="charUUID">Characteristic UUID to read.</param>
        /// <param name="onResult">Called with raw bytes, or null on error.</param>
        void ReadCharacteristic(string deviceId, string serviceUUID, string charUUID,
                                Action<byte[]> onResult);

        /// <summary>
        /// Write a characteristic value.
        /// </summary>
        /// <param name="deviceId">Must be connected.</param>
        /// <param name="serviceUUID">Service UUID containing the characteristic.</param>
        /// <param name="charUUID">Characteristic UUID to write.</param>
        /// <param name="data">Bytes to write.</param>
        /// <param name="withResponse">If true, use Write With Response (ACK). If false, Write Without Response.</param>
        /// <param name="onResult">Called with true on success, false on failure.</param>
        void WriteCharacteristic(string deviceId, string serviceUUID, string charUUID,
                                 byte[] data, bool withResponse, Action<bool> onResult);

        // ── Notifications ────────────────────────────────────────────────────────────

        /// <summary>
        /// Subscribe to characteristic notifications (or indications).
        /// <para>
        /// ANDROID NOTE: Android requires an explicit CCCD descriptor write to enable notifications.
        /// The Android plugin handles this automatically inside Subscribe().
        /// iOS/macOS CoreBluetooth handles CCCD writes internally — no extra step needed.
        /// </para>
        /// </summary>
        /// <param name="deviceId">Must be connected.</param>
        /// <param name="serviceUUID">Service UUID containing the characteristic.</param>
        /// <param name="charUUID">Characteristic UUID to subscribe to.</param>
        /// <param name="onData">Called each time a notification is received with the raw bytes.</param>
        void Subscribe(string deviceId, string serviceUUID, string charUUID, Action<byte[]> onData);

        /// <summary>Unsubscribe from characteristic notifications.</summary>
        void Unsubscribe(string deviceId, string serviceUUID, string charUUID);

        // ── Convenience ──────────────────────────────────────────────────────────────

        /// <summary>
        /// Read the Battery Level characteristic (0x2A19) from the Battery Service (0x180F).
        /// </summary>
        /// <param name="deviceId">Must be connected.</param>
        /// <param name="onResult">Called with battery percentage (0–100), or -1 on error.</param>
        void ReadBattery(string deviceId, Action<int> onResult);
    }
}
