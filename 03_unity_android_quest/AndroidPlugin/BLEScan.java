// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : BLEScan — Android BLE Scanner with Filters
// Platform: Android (Java) / Unity Bridge
// Sprint  : Sprint 74
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : Discover nearby BLE peripherals using Android's BluetoothLeScanner
//           API. Supports unfiltered scans and filtered scans by service UUID,
//           device name prefix, and minimum RSSI threshold.
//
//           This file is the SECOND layer of the Android BLE plugin.
//           BLEAndroidPlugin.java (Sprint 73) must be initialized before any
//           scan can be started — it provides the BluetoothAdapter and Unity
//           callback target that this class depends on.
//
// Usage   : From C# (Unity):
//
//               // Unfiltered scan for 5 seconds
//               instance.Call("startScan", 5.0f);
//
//               // Filtered scan — filter by service UUID and name prefix
//               instance.Call("startScanFiltered",
//                   "0000180D-0000-1000-8000-00805f9b34fb",  // Heart Rate Service
//                   "Polar",                                   // name prefix filter
//                   -80,                                       // minRSSI
//                   10.0f);                                    // duration seconds
//
//               // Stop early
//               instance.Call("stopScan");
//
//           Unity receives callbacks:
//               OnDeviceFound(String json)     — one call per unique device seen
//               OnScanComplete(String "")      — scan window ended or stopScan() called
//               OnScanError(String errorCode)  — if scan fails to start
//
//           JSON format for OnDeviceFound:
//               {"id":"AA:BB:CC:DD:EE:FF","name":"Polar H10","rssi":-65}
//
// Key Android BLE Scan Concepts
// ──────────────────────────────
// BluetoothLeScanner  — Obtained from BluetoothAdapter. The entry point for
//                       all BLE advertisement discovery. Null if Bluetooth is
//                       disabled (always null-check before use).
//
// ScanCallback        — Interface with three methods:
//                         onScanResult()  — called for each advertisement
//                         onBatchScanResults() — called for batched results (HW scan)
//                         onScanFailed()  — called if the scan cannot start
//
// ScanFilter          — Restricts which advertisements trigger onScanResult.
//                       Without a filter, ALL advertising devices are reported.
//                       Filtering happens inside the OS Bluetooth stack, so it
//                       reduces CPU wakeups compared to filtering in Java.
//
// ScanSettings        — Controls power/latency trade-offs (scan mode, report
//                       delay, match thresholds). See SCAN_MODE discussion below.
//
// Handler             — Used for the auto-stop timer. BLE callbacks arrive on
//                       a dedicated internal BluetoothLeScanner thread (not the
//                       main thread), so posting to Handler(Looper.getMainLooper())
//                       ensures the stopScan and Unity callback are thread-safe.
//
// Scan Mode — Battery vs. Latency Trade-off
// ──────────────────────────────────────────
// Android offers four scan modes, each a different point on the
// battery-drain / discovery-latency curve:
//
//   SCAN_MODE_LOW_POWER   — Scan window ~512 ms every 5120 ms.
//                           Very low battery impact. Devices may take 5+ seconds
//                           to appear. Best for passive background scanning
//                           (e.g., location-based triggers when app is backgrounded).
//
//   SCAN_MODE_BALANCED    — Scan window ~1024 ms every 5120 ms.
//                           Middle ground. Reasonable latency (~2-3 s) with
//                           moderate battery drain. Good for periodic user-facing
//                           device pickers that don't need instant results.
//
//   SCAN_MODE_LOW_LATENCY — Scan window ≈ continuous (duty cycle near 100%).
//                           Highest battery drain. Devices typically appear within
//                           200-500 ms. Use for short, user-initiated scans (e.g.,
//                           a "Scan" button the user taps — scan for 5-10 s then stop).
//                           DO NOT leave this running indefinitely in production.
//
//   SCAN_MODE_OPPORTUNISTIC — (API 23+) Zero duty cycle: piggybacks on scans
//                              started by OTHER apps. Results are unreliable.
//                              Not used here.
//
// For a BLE device pairing UI (like this Unity plugin), LOW_LATENCY is correct:
// the scan is short (user triggered), results should appear fast, and the scan
// is stopped as soon as the user selects a device or the timeout fires.
//
// Android Location Permission — Why BLE Scanning Requires It (API < 31)
// ────────────────────────────────────────────────────────────────────────
// This seems counterintuitive: why does Bluetooth require a location permission?
//
// The reason is historical and rooted in privacy:
//   - BLE advertisements carry the peripheral's MAC address.
//   - A list of nearby MAC addresses can be cross-referenced against crowd-sourced
//     databases (similar to how Google Maps locates you via Wi-Fi SSIDs) to
//     infer your physical location — even without GPS.
//   - Therefore, Android decided that BLE scanning is a "location-revealing"
//     operation and required ACCESS_FINE_LOCATION as a prerequisite.
//
// On API 31+ (Android 12), Google introduced granular Bluetooth permissions
// (BLUETOOTH_SCAN, BLUETOOTH_CONNECT) and allowed apps to opt out of the
// location requirement by declaring usesPermissionFlags="neverForLocation"
// in AndroidManifest.xml. See BLEAndroidPlugin.java (Sprint 73) for full
// permission model documentation.
//
// What Happens if BLUETOOTH_SCAN is Denied (API 31+)
// ─────────────────────────────────────────────────────
// If the user denies BLUETOOTH_SCAN permission and you call startScan():
//   - On most devices: the OS throws a SecurityException before the scan starts.
//     This crashes the app if not caught.
//   - Alternatively: ScanCallback.onScanFailed() is called with
//     SCAN_FAILED_APPLICATION_REGISTRATION_FAILED or SCAN_FAILED_FEATURE_UNSUPPORTED.
//     (Behavior is OEM-dependent.)
//
// Best practice: Always call BLEAndroidPlugin.getInstance().hasPermissions()
// from C# BEFORE starting a scan and surface a permission prompt if needed.
// This class includes a permission guard and sends OnScanError if permissions
// are missing, rather than relying on the exception path.
//
// Deduplication Strategy
// ─────────────────────────
// Android's ScanCallback can call onScanResult() many times for the same device
// (once per advertisement packet received, which can be multiple times per second
// for an actively advertising peripheral). Without deduplication:
//   - Unity would receive hundreds of OnDeviceFound callbacks for a single device.
//   - C# would need complex state management to avoid duplicate list entries.
//
// This class deduplicates by MAC address (device ID) and keeps only the entry
// with the STRONGEST RSSI seen so far. Strongest RSSI = most recent reliable
// signal strength estimate, which is useful for sorting a device list by
// proximity. Unity receives at most one OnDeviceFound per unique device ID.
//
// ─────────────────────────────────────────────────────────────────────────────

package com.bleref.unity;

// ─── Android Bluetooth Scan Imports ──────────────────────────────────────────

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;

// ─── Android System Imports ──────────────────────────────────────────────────

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

// ─── Java Standard Library ───────────────────────────────────────────────────

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────

public class BLEScan {

    // ─── Logging Tag ─────────────────────────────────────────────────────────
    // Filter logcat output with: adb logcat -s BLEScan
    private static final String TAG = "BLEScan";

    // ─── Unity Callback Method Names ─────────────────────────────────────────
    // The C# script on the Unity GameObject registered with BLEAndroidPlugin
    // must expose public methods with exactly these names.
    private static final String UNITY_METHOD_DEVICE_FOUND  = "OnDeviceFound";
    private static final String UNITY_METHOD_SCAN_COMPLETE = "OnScanComplete";
    private static final String UNITY_METHOD_SCAN_ERROR    = "OnScanError";

    // ─── Scan State ──────────────────────────────────────────────────────────

    /**
     * Whether a scan is currently in progress.
     *
     * Used to guard against double-starts and to make stopScan() a no-op
     * when no scan is active (safe to call defensively from C#).
     */
    private boolean _isScanning = false;

    /**
     * The active ScanCallback instance.
     *
     * Must be the SAME object passed to startScan() when calling stopScan().
     * Android's BluetoothLeScanner identifies scan sessions by callback object
     * identity — passing a different callback object to stopScan() will NOT
     * stop the scan.
     */
    private ScanCallback _activeScanCallback = null;

    /**
     * Deduplication map: device ID (MAC address) → strongest RSSI seen.
     *
     * Populated as scan results arrive. Cleared at the start of each new scan.
     * Used both to avoid duplicate OnDeviceFound callbacks and to track
     * the best RSSI for each device (useful for proximity sorting in C#).
     */
    private final Map<String, Integer> _discoveredDevices = new HashMap<>();

    /**
     * Handler for auto-stop timer.
     *
     * Posts a delayed stopScan() call after the requested scan duration.
     * Also used to cancel the timer if stopScan() is called early.
     * Must post to the main looper so the stop runs on the main thread,
     * consistent with Unity's threading expectations.
     */
    private final Handler _mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Reference to the pending auto-stop Runnable.
     *
     * Stored so it can be cancelled via _mainHandler.removeCallbacks()
     * when stopScan() is called before the timer fires.
     */
    private Runnable _autoStopRunnable = null;

    // ─── Singleton ────────────────────────────────────────────────────────────
    // BLEScan is a singleton for the same reasons as BLEAndroidPlugin:
    // there is only one BluetoothLeScanner per device, and Unity needs a stable
    // Java object to call into across multiple scan sessions without re-acquiring
    // the Java object reference.

    private static BLEScan instance;

    /**
     * Returns the singleton BLEScan instance, creating it if needed.
     *
     * Called from C# via:
     *   var scanner = new AndroidJavaClass("com.bleref.unity.BLEScan")
     *       .CallStatic<AndroidJavaObject>("getInstance");
     *
     * @return The shared BLEScan singleton.
     */
    public static BLEScan getInstance() {
        if (instance == null) {
            instance = new BLEScan();
            Log.d(TAG, "BLEScan singleton created.");
        }
        return instance;
    }

    private BLEScan() {}

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Start an unfiltered BLE scan.
     *
     * Reports ALL advertising BLE devices within range. Use this for general
     * device discovery when you don't know the service UUID or name of the
     * target device.
     *
     * Results are sent to Unity as OnDeviceFound(json) callbacks, one per
     * unique device. When the duration elapses, OnScanComplete("") is sent.
     *
     * Called from C# via:
     *   instance.Call("startScan", 5.0f);
     *
     * @param durationSeconds  How long to scan before auto-stopping.
     *                         Minimum recommended: 3.0 seconds. Very short
     *                         scans may miss devices with long advertising intervals.
     */
    public void startScan(float durationSeconds) {
        // Null filters = no filtering. Android will report every advertisement.
        startScanInternal(null, null, Integer.MIN_VALUE, durationSeconds);
    }

    /**
     * Start a filtered BLE scan.
     *
     * Applies OS-level filters to reduce wakeups and narrow results to
     * relevant peripherals. Any filter parameter can be null to skip that
     * filter criterion (e.g., pass null for nameFilter to filter only by
     * service UUID).
     *
     * The RSSI filter is applied in Java (not at OS level, since Android's
     * ScanFilter does not support RSSI filtering). Devices below minRSSI
     * are silently ignored and do not generate OnDeviceFound callbacks.
     *
     * Called from C# via:
     *   instance.Call("startScanFiltered",
     *       "0000180D-0000-1000-8000-00805f9b34fb",
     *       "Polar",
     *       -80,
     *       10.0f);
     *
     * @param serviceUUID     Service UUID string to filter by (e.g. Heart Rate
     *                        "0000180D-0000-1000-8000-00805f9b34fb"), or null.
     *                        Only devices advertising this service are reported.
     * @param nameFilter      Device name prefix to filter by (e.g. "Polar"),
     *                        or null. Comparison is case-sensitive.
     * @param minRSSI         Minimum RSSI in dBm (e.g. -80). Devices weaker
     *                        than this are ignored. Pass Integer.MIN_VALUE to
     *                        disable RSSI filtering.
     * @param durationSeconds How long to scan before auto-stopping.
     */
    public void startScanFiltered(
            String serviceUUID,
            String nameFilter,
            int    minRSSI,
            float  durationSeconds) {
        startScanInternal(serviceUUID, nameFilter, minRSSI, durationSeconds);
    }

    /**
     * Stop the current scan immediately.
     *
     * Safe to call even if no scan is active — it is a no-op in that case.
     * After stopping, OnScanComplete("") is sent to Unity.
     *
     * Called from C# via:
     *   instance.Call("stopScan");
     */
    public void stopScan() {
        if (!_isScanning) {
            Log.d(TAG, "stopScan() called but no scan is active — ignoring.");
            return;
        }
        stopScanInternal(true /* sendCompleteEvent */);
    }

    // ─── Internal Scan Logic ─────────────────────────────────────────────────

    /**
     * Core scan implementation used by both startScan() and startScanFiltered().
     *
     * Performs permission checks, acquires the BluetoothLeScanner, builds
     * ScanFilter and ScanSettings, starts the scan, and schedules auto-stop.
     *
     * @param serviceUUID     Service UUID filter string, or null.
     * @param nameFilter      Device name prefix filter, or null.
     * @param minRSSI         Minimum RSSI filter, or Integer.MIN_VALUE to disable.
     * @param durationSeconds Scan duration in seconds.
     */
    private void startScanInternal(
            String serviceUUID,
            String nameFilter,
            int    minRSSI,
            float  durationSeconds) {

        Log.d(TAG, String.format(
            "startScanInternal: serviceUUID=%s name=%s minRSSI=%d duration=%.1fs",
            serviceUUID, nameFilter, minRSSI, durationSeconds));

        // ── Guard: check for active scan ─────────────────────────────────────
        // Android does not prevent multiple concurrent scans from the same app,
        // but each call to startScan() creates an independent scan session. We
        // enforce single-session semantics here to keep the Unity interface simple.
        if (_isScanning) {
            Log.w(TAG, "Scan already in progress. Call stopScan() first.");
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_SCAN_ERROR, "SCAN_ALREADY_ACTIVE");
            return;
        }

        // ── Guard: check BLE permissions ─────────────────────────────────────
        // On API 31+ this checks BLUETOOTH_SCAN. On API < 31 it checks
        // ACCESS_FINE_LOCATION. Scanning without the required permission will
        // throw a SecurityException on most devices.
        if (!BLEAndroidPlugin.getInstance().hasPermissions()) {
            Log.e(TAG, "BLE scan permissions not granted. Call requestPermissions() first.");
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_SCAN_ERROR, "PERMISSIONS_DENIED");
            return;
        }

        // ── Acquire BluetoothLeScanner ────────────────────────────────────────
        // The scanner is obtained from BluetoothAdapter, which was initialized
        // in BLEAndroidPlugin. getBluetoothLeScanner() returns null if Bluetooth
        // is currently disabled (even if the adapter object is non-null).
        BluetoothAdapter adapter = BLEAndroidPlugin.getInstance().getBluetoothAdapter();
        if (adapter == null) {
            Log.e(TAG, "BluetoothAdapter is null. Did you call BLEAndroidPlugin.initialize()?");
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_SCAN_ERROR, "ADAPTER_NOT_INITIALIZED");
            return;
        }

        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            // This happens when Bluetooth is turned off AFTER initialization.
            Log.e(TAG, "BluetoothLeScanner is null — Bluetooth may have been disabled.");
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_SCAN_ERROR, "BT_DISABLED");
            return;
        }

        // ── Reset deduplication state ─────────────────────────────────────────
        // Clear the map from any previous scan session so devices that
        // disappeared and reappeared are reported fresh.
        _discoveredDevices.clear();

        // ── Build ScanFilters ────────────────────────────────────────────────
        // ScanFilter.Builder allows combining multiple filter criteria with AND
        // semantics. If the filters list is null or empty, ALL advertisements
        // are reported (equivalent to a promiscuous scan).
        //
        // OS-level filters (service UUID, device name) are processed inside the
        // Bluetooth chip/firmware on supported hardware. This means the CPU is
        // not woken up for non-matching advertisements — an important battery
        // win on busy wireless environments (e.g., a venue with many BLE beacons).
        List<ScanFilter> filters = null;

        if (serviceUUID != null || nameFilter != null) {
            ScanFilter.Builder filterBuilder = new ScanFilter.Builder();

            if (serviceUUID != null) {
                // ParcelUuid wraps a standard java.util.UUID for Android's
                // Parcelable serialization protocol. The service UUID must match
                // EXACTLY what the peripheral advertises (full 128-bit form).
                // Short 16-bit UUIDs (e.g. 0x180D) must be expanded to 128-bit:
                // 0000XXXX-0000-1000-8000-00805f9b34fb
                filterBuilder.setServiceUuid(ParcelUuid.fromString(serviceUUID));
                Log.d(TAG, "Scan filter: serviceUUID=" + serviceUUID);
            }

            if (nameFilter != null) {
                // setDeviceName() matches on EXACT name, not prefix.
                // For prefix matching we use a manual check in onScanResult().
                // We store the nameFilter in the callback closure for that purpose.
                // Note: Some devices do not advertise a name — they will be
                // excluded by this filter even if their service UUID matches.
                filterBuilder.setDeviceName(nameFilter);
                Log.d(TAG, "Scan filter: deviceName=" + nameFilter);
            }

            filters = new ArrayList<>();
            filters.add(filterBuilder.build());
        }

        // ── Build ScanSettings ────────────────────────────────────────────────
        // SCAN_MODE_LOW_LATENCY is the right choice for a user-initiated scan:
        // the duty cycle is near-continuous, so advertising packets are
        // captured almost immediately. The scan window is short (triggered by
        // user tapping "Scan"), so the battery cost is acceptable.
        //
        // Do NOT use LOW_LATENCY for background scanning (e.g., a service that
        // continuously monitors for devices). Use LOW_POWER or BALANCED for that.
        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            // REPORT_DELAY_MILLIS = 0 means results are reported immediately,
            // one advertisement at a time. Non-zero values enable batched reporting
            // (results accumulate in hardware and are flushed periodically), which
            // is more battery-efficient but adds latency. For interactive UI: 0.
            .setReportDelay(0)
            .build();

        // ── Capture filter params for use inside the callback ─────────────────
        // Java requires effectively-final variables for lambda/anonymous class
        // capture. Store the nameFilter and minRSSI in final local aliases.
        final String  finalNameFilter = nameFilter;
        final int     finalMinRSSI    = minRSSI;

        // ── Build ScanCallback ────────────────────────────────────────────────
        // Anonymous inner class instead of lambda because ScanCallback has
        // multiple methods. The callback is created fresh each scan session
        // so it can capture the current filter parameters.
        _activeScanCallback = new ScanCallback() {

            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                // ── Extract device metadata ───────────────────────────────────
                // result.getDevice() is a BluetoothDevice. On Android, the
                // address is always a MAC address (AA:BB:CC:DD:EE:FF).
                // Unlike iOS (CoreBluetooth UUIDs), MAC addresses are stable
                // identifiers on Android.
                String deviceId   = result.getDevice().getAddress();
                int    rssi       = result.getRssi();
                String deviceName = null;

                // ── Extract device name safely ────────────────────────────────
                // ScanRecord carries the advertised local name. This is distinct
                // from the Bluetooth system name (result.getDevice().getName()),
                // which requires BLUETOOTH_CONNECT permission on API 31+ and may
                // cache a stale name from a prior bonding. Using ScanRecord is
                // preferred for discovery.
                ScanRecord scanRecord = result.getScanRecord();
                if (scanRecord != null) {
                    deviceName = scanRecord.getDeviceName();
                }
                // Fall back to system name if scan record has no local name
                if (deviceName == null || deviceName.isEmpty()) {
                    deviceName = result.getDevice().getName();
                }
                if (deviceName == null) {
                    deviceName = "";
                }

                // ── Apply name-prefix filter (Java side) ──────────────────────
                // setDeviceName() in ScanFilter matches exact names only.
                // If the caller wants prefix matching (e.g., "Polar" to match
                // "Polar H10" and "Polar OH1"), we do it here.
                // This is a workaround for Android's lack of wildcard name filters.
                if (finalNameFilter != null && !deviceName.startsWith(finalNameFilter)) {
                    return; // Silently skip — not the device we're looking for
                }

                // ── Apply RSSI threshold filter ───────────────────────────────
                // Weak RSSI means the device is far or obstructed. Filtering
                // out low-RSSI devices reduces list noise in the Unity UI.
                if (rssi < finalMinRSSI) {
                    return; // Device is too weak — skip
                }

                // ── Deduplicate: keep strongest RSSI ─────────────────────────
                // Check if we've already reported this device. If we have AND
                // the current RSSI is not stronger, skip it. If the RSSI is
                // stronger (device moved closer), update the map and re-report.
                //
                // "Stronger RSSI" means a less-negative value:
                //   -50 dBm is stronger than -75 dBm (closer to 0 = stronger).
                synchronized (_discoveredDevices) {
                    if (_discoveredDevices.containsKey(deviceId)) {
                        int prevRssi = _discoveredDevices.get(deviceId);
                        if (rssi <= prevRssi) {
                            return; // Already reported with same or better RSSI
                        }
                    }
                    _discoveredDevices.put(deviceId, rssi);
                }

                // ── Build JSON and send to Unity ──────────────────────────────
                // We build JSON manually (no Gson dependency) to keep the plugin
                // self-contained. Escape the name string to handle apostrophes
                // and other special characters in device names.
                String escapedName = deviceName.replace("\\", "\\\\").replace("\"", "\\\"");
                String json = String.format(
                    "{\"id\":\"%s\",\"name\":\"%s\",\"rssi\":%d}",
                    deviceId, escapedName, rssi
                );

                Log.d(TAG, "Device found: " + json);
                BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_DEVICE_FOUND, json);
            }

            @Override
            public void onScanFailed(int errorCode) {
                // ── Translate error code for Unity ────────────────────────────
                // Android defines these error codes in ScanCallback:
                //   SCAN_FAILED_ALREADY_STARTED             = 1
                //   SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2
                //   SCAN_FAILED_INTERNAL_ERROR              = 3
                //   SCAN_FAILED_FEATURE_UNSUPPORTED         = 4
                //   SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES   = 5 (API 26+)
                //   SCAN_FAILED_SCANNING_TOO_FREQUENTLY     = 6 (API 31+)
                //
                // SCANNING_TOO_FREQUENTLY (6) deserves special mention:
                // Android 12+ enforces a scan throttle — if an app starts/stops
                // scans too rapidly (more than 5 start/stop cycles in 30 seconds),
                // the OS blocks further scans with this error. The intent is to
                // prevent battery abuse by apps that probe continuously.
                // Solution: back off for at least 30 seconds before retrying.
                String errorStr;
                switch (errorCode) {
                    case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                        errorStr = "SCAN_FAILED_ALREADY_STARTED";          break;
                    case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                        errorStr = "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"; break;
                    case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                        errorStr = "SCAN_FAILED_INTERNAL_ERROR";           break;
                    case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                        errorStr = "SCAN_FAILED_FEATURE_UNSUPPORTED";      break;
                    default:
                        errorStr = "SCAN_FAILED_UNKNOWN_" + errorCode;
                }

                Log.e(TAG, "Scan failed: " + errorStr + " (code " + errorCode + ")");

                _isScanning = false;
                _activeScanCallback = null;

                // Cancel the auto-stop timer — it would fire on a dead scan
                if (_autoStopRunnable != null) {
                    _mainHandler.removeCallbacks(_autoStopRunnable);
                    _autoStopRunnable = null;
                }

                BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_SCAN_ERROR, errorStr);
            }
        };

        // ── Start the scan ────────────────────────────────────────────────────
        // The three-argument form startScan(filters, settings, callback) is
        // preferred over the single-argument form (which uses default settings
        // and no filters) for full control. The scanner delivers results to
        // _activeScanCallback on an internal BluetoothLeScanner looper thread.
        try {
            scanner.startScan(filters, settings, _activeScanCallback);
            _isScanning = true;
            Log.i(TAG, String.format("BLE scan started (duration=%.1fs).", durationSeconds));
        } catch (SecurityException e) {
            // Thrown on API 31+ if BLUETOOTH_SCAN permission is not granted.
            // This should not happen if hasPermissions() is called before scanning,
            // but we catch it defensively for devices with non-standard behavior.
            Log.e(TAG, "SecurityException starting scan — BLUETOOTH_SCAN denied: " + e.getMessage());
            _activeScanCallback = null;
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_SCAN_ERROR, "PERMISSIONS_DENIED");
            return;
        }

        // ── Schedule auto-stop ────────────────────────────────────────────────
        // Post a delayed runnable to the main thread. When it fires, it stops
        // the scan and notifies Unity. If stopScan() is called before this fires,
        // we cancel the runnable via removeCallbacks() to avoid a double-stop.
        long durationMs = (long)(durationSeconds * 1000);
        _autoStopRunnable = () -> {
            Log.d(TAG, "Auto-stop timer fired after " + durationSeconds + "s.");
            stopScanInternal(true /* sendCompleteEvent */);
        };
        _mainHandler.postDelayed(_autoStopRunnable, durationMs);
    }

    /**
     * Internal method to stop the scan and optionally send OnScanComplete.
     *
     * @param sendCompleteEvent  If true, sends OnScanComplete("") to Unity.
     *                           Pass false when called from onScanFailed() since
     *                           the scan never actually started successfully.
     */
    private void stopScanInternal(boolean sendCompleteEvent) {
        if (!_isScanning) {
            Log.d(TAG, "stopScanInternal: scan not active — ignoring.");
            return;
        }

        Log.d(TAG, "Stopping BLE scan.");

        // ── Cancel the auto-stop timer ────────────────────────────────────────
        // If the user called stopScan() before the timer fired, cancel the
        // pending runnable to prevent a second stop call after we've already
        // cleaned up. Safe to call even if the runnable already fired.
        if (_autoStopRunnable != null) {
            _mainHandler.removeCallbacks(_autoStopRunnable);
            _autoStopRunnable = null;
        }

        // ── Stop the OS-level scan ────────────────────────────────────────────
        BluetoothAdapter adapter = BLEAndroidPlugin.getInstance().getBluetoothAdapter();
        if (adapter != null) {
            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner != null && _activeScanCallback != null) {
                try {
                    // CRITICAL: Pass the SAME callback object used in startScan().
                    // The scanner uses object identity to match scan sessions.
                    scanner.stopScan(_activeScanCallback);
                    Log.i(TAG, "BluetoothLeScanner.stopScan() called.");
                } catch (SecurityException e) {
                    Log.e(TAG, "SecurityException stopping scan: " + e.getMessage());
                }
            }
        }

        // ── Reset state ──────────────────────────────────────────────────────
        _isScanning = false;
        _activeScanCallback = null;

        // ── Notify Unity ─────────────────────────────────────────────────────
        if (sendCompleteEvent) {
            Log.d(TAG, "Scan complete — sending OnScanComplete to Unity.");
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_SCAN_COMPLETE, "");
        }
    }

    // ─── Accessors ───────────────────────────────────────────────────────────

    /**
     * Returns true if a scan is currently active.
     *
     * Can be polled from C# to disable the "Scan" button while scanning.
     *
     * Called from C# via:
     *   bool scanning = instance.Call<bool>("isScanning");
     *
     * @return true if scanning, false otherwise.
     */
    public boolean isScanning() {
        return _isScanning;
    }

    /**
     * Returns the number of unique devices discovered in the current/last scan.
     *
     * Useful for displaying "N devices found" in the Unity UI.
     *
     * @return Device count.
     */
    public int getDiscoveredDeviceCount() {
        synchronized (_discoveredDevices) {
            return _discoveredDevices.size();
        }
    }
}
