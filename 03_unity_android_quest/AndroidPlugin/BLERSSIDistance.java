// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : BLERSSIDistance — RSSI Polling and Distance Estimation
// Platform: Android (Java) / Unity Bridge
// Sprint  : Sprint 80
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : Read the Received Signal Strength Indicator (RSSI) of an active
//           BLE connection and optionally estimate physical distance using the
//           log-distance path loss model. Supports both one-shot reads and
//           periodic polling with exponential moving average (EMA) smoothing.
//
// Usage   : From C# (Unity), after OnConnected fires:
//
//               // Single RSSI read
//               rssi.Call("readRSSI", "AA:BB:CC:DD:EE:FF");
//               // → OnRSSIRead(json: {"id":"AA:BB:CC:DD:EE:FF","rssi":-68,
//               //                     "distance":2.51,"proximity":"near"})
//
//               // Start periodic polling every 1 second
//               rssi.Call("startRSSIPolling", "AA:BB:CC:DD:EE:FF", 1000);
//               // → repeated OnRSSIRead callbacks
//
//               // Stop polling
//               rssi.Call("stopRSSIPolling", "AA:BB:CC:DD:EE:FF");
//
//           Unity receives callbacks:
//               OnRSSIRead(String json)    — RSSI value and distance estimate
//               OnRSSIError(String json)   — if read fails or device not found
//
//           JSON format for OnRSSIRead:
//               {"id":"AA:BB:CC:DD:EE:FF","rssi":-68,"smoothed":-65,"distance":2.51,"proximity":"near"}
//
// What Is RSSI?
// ──────────────────────────────────────────────────────────────────────────────
//
// RSSI stands for Received Signal Strength Indicator. It is measured in dBm
// (decibel-milliwatts) and is always negative for BLE:
//
//   -30 dBm: Exceptionally strong (device is essentially touching the antenna)
//   -50 dBm: Strong (device within ~1 m, line of sight)
//   -65 dBm: Good   (typical ~1-3 m range, moderate obstacles)
//   -80 dBm: Fair   (5-10 m, walls, body occlusion)
//   -95 dBm: Weak   (near the noise floor — unreliable)
//
// "Less negative" = stronger signal = device is closer.
// "More negative" = weaker signal  = device is farther or obstructed.
//
// RSSI During Scan vs. RSSI on an Active Connection
// ──────────────────────────────────────────────────────────────────────────────
//
// RSSI DURING SCAN (BLEScan.java — Sprint 74):
//   ScanResult.getRssi() provides the RSSI of each advertisement packet.
//   This is measured from the advertisement broadcast, not a connected link.
//   Available WITHOUT connecting to the device.
//
// RSSI ON AN ACTIVE CONNECTION (this file):
//   gatt.readRemoteRssi() explicitly requests the BT controller to measure
//   the signal strength of the ACTIVE link layer. This requires a connected
//   GATT session. The result is reported asynchronously in onReadRemoteRssi().
//
// THIS IS THE SAME CONSTRAINT AS WEB BLUETOOTH (Phase 03):
//   The Web Bluetooth API also provides RSSI only on active connections, not
//   during scan. navigator.bluetooth.requestDevice() does not surface RSSI,
//   and there is no background RSSI polling in Web Bluetooth.
//   See Phase 03 TypeScript stub for the deliberate gap in the web implementation.
//
// Why Android Cannot Read RSSI Mid-Scan:
//   The Bluetooth LE scan path (HW/FW) and the GATT connection path use
//   separate measurement circuits on Qualcomm/MediaTek chips. The OS exposes
//   scan-path RSSI as a first-class value in ScanResult, but provides no
//   API to retrieve connection-path RSSI without an explicit readRemoteRssi()
//   request over the active connection.
//
// Log-Distance Path Loss Model
// ──────────────────────────────────────────────────────────────────────────────
//
// The most common distance estimation formula for BLE proximity:
//
//   d = 10 ^ ((measuredPower - RSSI) / (10 × n))
//
// Where:
//   d             : estimated distance in meters
//   measuredPower : RSSI at exactly 1 meter from the transmitting antenna
//                   (a per-device calibration constant; typically -59 to -69 dBm)
//   RSSI          : current RSSI reading in dBm
//   n             : path loss exponent (dimensionless)
//                     n = 2.0 → free-space propagation (outdoors, no obstacles)
//                     n = 2.5 → typical indoor office environment
//                     n = 3.0 → heavy indoor obstacles (concrete walls, dense furniture)
//                     n = 4.0 → worst-case indoor (metal structures, interference)
//
// Reference:
//   Rappaport, T.S. (2001). Wireless Communications: Principles and Practice.
//   Prentice Hall. (Path loss models, Chapter 3.)
//
// WHY RSSI DISTANCE IS UNRELIABLE — Read This Before Using estimateDistance()
// ──────────────────────────────────────────────────────────────────────────────
//
// The log-distance model assumes isotropic signal propagation (equal in all
// directions). In practice, BLE distance estimation from RSSI is unreliable
// because of:
//
// 1. MULTIPATH FADING
//    Radio waves reflect off walls, floors, furniture, and human bodies.
//    Reflected copies of the signal arrive at the receiver with different
//    delays and phases, causing constructive or destructive interference.
//    A device at a fixed distance can vary by ±10 dBm just due to multipath.
//
// 2. BODY OCCLUSION / BODY ABSORPTION
//    The human body absorbs 2.4 GHz RF energy significantly. A Polar H10
//    worn on the chest may show RSSI = -70 dBm when the wearer faces the
//    Quest, but -85 dBm when their back is turned — with no change in distance.
//
// 3. DEVICE ORIENTATION
//    BLE antennas are not omnidirectional. A device's RSSI can vary 10-15 dBm
//    depending on whether it is lying flat, stood upright, or in a pocket.
//
// 4. ANTENNA PLACEMENT VARIATION
//    measuredPower calibration for "1 meter" is hardware-specific and changes
//    with firmware updates. Polar H10, Muse 2, and Quest all have different
//    antenna designs, TX power levels, and frequency offsets.
//
// 5. INTERFERENCE
//    Wi-Fi (2.4 GHz), microwave ovens, and other BLE devices all share the
//    2.4 GHz ISM band. Interference causes momentary RSSI drops.
//
// CONCLUSION:
//   Use estimateDistance() for coarse proximity categories ("immediate", "near",
//   "far") — NOT for precise localization. Apple's iBeacon spec uses three
//   identical categories (immediate: <0.5m, near: <3m, far: ≥3m).
//   For accurate indoor positioning, use UWB (Ultra-Wideband) instead.
//   Quest Pro and Quest 3 do NOT have UWB hardware.
//
// RSSI Smoothing with Exponential Moving Average
// ──────────────────────────────────────────────────────────────────────────────
//
// Raw RSSI values are noisy — consecutive readings can jump ±10 dBm.
// An EMA (Exponential Moving Average) smooths the signal:
//
//   smoothed_t = α × raw_t + (1-α) × smoothed_{t-1}
//
// Where α ∈ (0, 1) controls the smoothing weight:
//   α = 0.1 : heavy smoothing; slow to respond to real movement
//   α = 0.2 : moderate (default here); good for real-time proximity
//   α = 0.5 : light smoothing; more responsive but noisier
//
// This is identical to the smoothing used in Apple's CoreLocation iBeacon
// proximity estimation and is standard in BLE proximity libraries.
//
// ─────────────────────────────────────────────────────────────────────────────

package com.bleref.unity;

// ─── Android Bluetooth Imports ───────────────────────────────────────────────

import android.bluetooth.BluetoothGatt;

// ─── Android System Imports ──────────────────────────────────────────────────

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

// ─── Java Standard Library ───────────────────────────────────────────────────

import java.util.HashMap;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────

public class BLERSSIDistance {

    // ─── Logging Tag ─────────────────────────────────────────────────────────
    // Filter logcat output with: adb logcat -s BLERSSIDistance
    private static final String TAG = "BLERSSIDistance";

    // ─── Unity Callback Method Names ─────────────────────────────────────────
    private static final String UNITY_CB_RSSI_READ  = "OnRSSIRead";
    private static final String UNITY_CB_RSSI_ERROR = "OnRSSIError";

    // ─── Path Loss Model Constants ────────────────────────────────────────────
    //
    // DEFAULT_MEASURED_POWER:
    //   RSSI (dBm) measured at exactly 1 meter from the peripheral antenna,
    //   in free-space conditions. Device-specific — calibrate for your hardware.
    //
    //   Published reference values:
    //     Polar H10       : -65 dBm at 1 m (measured, lab conditions)
    //     Muse 2          : -63 dBm at 1 m (approximate)
    //     Generic Nordic  : -59 dBm at 1 m (nRF52 DK reference design)
    //     Generic wearable: -69 dBm at 1 m (conservative default)
    //
    // PATH_LOSS_EXPONENT:
    //   n = 2.0 assumes free-space propagation. For a typical indoor lab:
    //   use n = 2.5 or 3.0 for slightly better accuracy. The default 2.0
    //   is the theoretically "correct" value for unobstructed line-of-sight.

    private static final int    DEFAULT_MEASURED_POWER = -65;
    private static final double PATH_LOSS_EXPONENT     = 2.0;

    // ─── EMA Smoothing Factor ─────────────────────────────────────────────────
    //
    // α = 0.2 means each new reading contributes 20% to the smoothed value.
    // The remaining 80% comes from the previous smoothed value.
    // This provides a good balance between noise rejection and responsiveness
    // at typical polling intervals of 500–2000 ms.

    private static final double EMA_ALPHA = 0.2;

    // ─── Proximity Thresholds (meters) ────────────────────────────────────────
    //
    // These match Apple iBeacon CLProximityImmediate / Near / Far categories:
    //   "immediate" : < 0.5 m  (device essentially touching)
    //   "near"      : < 3.0 m  (same room, within arm's reach or nearby)
    //   "far"       : ≥ 3.0 m  (across the room or out of sight)

    private static final double PROXIMITY_IMMEDIATE_METERS = 0.5;
    private static final double PROXIMITY_NEAR_METERS      = 3.0;

    // ─── Per-Device State ─────────────────────────────────────────────────────
    //
    // Each connected device can have:
    //   - An active polling Handler posting periodic readRemoteRssi() calls
    //   - A stored EMA-smoothed RSSI value for continuity between readings
    //   - A measuredPower calibration value (defaults to DEFAULT_MEASURED_POWER)

    private final Map<String, Handler>  _pollingHandlers  = new HashMap<>();
    private final Map<String, Runnable> _pollingRunnables = new HashMap<>();
    private final Map<String, Double>   _smoothedRssi     = new HashMap<>();
    private final Map<String, Integer>  _measuredPowerMap = new HashMap<>();

    // ─── Polling Thread ───────────────────────────────────────────────────────
    //
    // A single HandlerThread serves all polling timers. This keeps RSSI polling
    // off the main Unity thread. Multiple devices are handled by separate
    // Runnable instances posted to this shared thread.

    private final HandlerThread _pollingThread;
    private final Handler       _pollingHandler;

    // ─── Singleton ────────────────────────────────────────────────────────────

    private static BLERSSIDistance _instance;

    /**
     * Returns the singleton BLERSSIDistance instance.
     *
     * Called from C# via:
     *   var rssi = new AndroidJavaClass("com.bleref.unity.BLERSSIDistance")
     *       .CallStatic<AndroidJavaObject>("getInstance");
     *
     * @return The shared BLERSSIDistance singleton.
     */
    public static BLERSSIDistance getInstance() {
        if (_instance == null) {
            _instance = new BLERSSIDistance();
            Log.d(TAG, "BLERSSIDistance singleton created.");
        }
        return _instance;
    }

    private BLERSSIDistance() {
        _pollingThread = new HandlerThread("BLE-RSSIPoll");
        _pollingThread.start();
        _pollingHandler = new Handler(_pollingThread.getLooper());
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Request a single RSSI reading from the connected peripheral.
     *
     * The result arrives asynchronously in BluetoothGattCallback.onReadRemoteRssi(),
     * which must call BLERSSIDistance.getInstance().onReadRemoteRssi().
     * Unity receives OnRSSIRead with RSSI in dBm and a distance estimate.
     *
     * This enqueues via BLECommandQueue to avoid overlapping with other GATT
     * operations (RSSI read is a GATT operation and obeys the serial rule).
     *
     * Typical call from C#:
     *   rssi.Call("readRSSI", "AA:BB:CC:DD:EE:FF");
     *
     * @param deviceId MAC address of the connected peripheral.
     */
    public void readRSSI(String deviceId) {
        Log.d(TAG, "readRSSI: " + deviceId);

        BluetoothGatt gatt = BLEConnection.getInstance().getGatt(deviceId);
        if (gatt == null) {
            Log.e(TAG, "readRSSI: no active GATT for " + deviceId);
            String json = String.format("{\"id\":\"%s\",\"error\":\"NOT_CONNECTED\"}", deviceId);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_RSSI_ERROR, json);
            return;
        }

        // ── Enqueue via BLECommandQueue ───────────────────────────────────────
        // readRemoteRssi() is a GATT operation. Issuing it while another GATT
        // operation is in flight causes silent failure. The queue ensures it
        // executes only when the bus is free.
        BLECommandQueue.getInstance().enqueue(() -> {
            boolean ok = gatt.readRemoteRssi();
            if (!ok) {
                Log.w(TAG, "readRemoteRssi() returned false for " + deviceId
                    + " — GATT may be busy or disconnected.");
                BLECommandQueue.getInstance().onOperationComplete();
            }
            // If ok=true: onReadRemoteRssi() callback will call onOperationComplete()
        });
    }

    /**
     * Start periodic RSSI polling for a connected device.
     *
     * Issues a readRSSI() call every intervalMs milliseconds. Each result
     * triggers an OnRSSIRead callback to Unity with the smoothed RSSI and
     * distance estimate. Polling continues until stopRSSIPolling() is called
     * or the device disconnects.
     *
     * Recommended intervalMs:
     *   500 ms  — responsive proximity tracking (e.g., real-time "hot-cold" game)
     *   1000 ms — moderate polling, suitable for displaying distance in UI
     *   2000 ms — light polling, background monitoring
     *
     * Do NOT poll faster than 200 ms — readRemoteRssi() generates a GATT
     * operation, and polling too fast will compete with data streaming
     * (ECG, EEG notifications) and cause dropped packets.
     *
     * Typical call from C#:
     *   rssi.Call("startRSSIPolling", "AA:BB:CC:DD:EE:FF", 1000);
     *
     * @param deviceId   MAC address of the connected peripheral.
     * @param intervalMs Polling interval in milliseconds.
     */
    public void startRSSIPolling(String deviceId, int intervalMs) {
        Log.d(TAG, "startRSSIPolling: device=" + deviceId + " interval=" + intervalMs + "ms");

        // Stop any existing polling for this device before starting fresh
        stopRSSIPolling(deviceId);

        Runnable pollRunnable = new Runnable() {
            @Override
            public void run() {
                // Issue the RSSI read (queued via BLECommandQueue)
                readRSSI(deviceId);
                // Schedule next poll after the interval
                // This is a self-rescheduling runnable: each execution posts
                // the next one, creating a periodic polling chain.
                _pollingHandler.postDelayed(this, intervalMs);
            }
        };

        synchronized (_pollingRunnables) {
            _pollingRunnables.put(deviceId, pollRunnable);
        }

        // Start the first poll immediately
        _pollingHandler.post(pollRunnable);
        Log.i(TAG, "RSSI polling started for " + deviceId + " at " + intervalMs + " ms interval.");
    }

    /**
     * Stop periodic RSSI polling for a device.
     *
     * Safe to call even if polling is not active for the device — it is a no-op.
     *
     * Typical call from C#:
     *   rssi.Call("stopRSSIPolling", "AA:BB:CC:DD:EE:FF");
     *
     * @param deviceId MAC address of the device to stop polling.
     */
    public void stopRSSIPolling(String deviceId) {
        Runnable existing;
        synchronized (_pollingRunnables) {
            existing = _pollingRunnables.remove(deviceId);
        }
        if (existing != null) {
            _pollingHandler.removeCallbacks(existing);
            Log.i(TAG, "RSSI polling stopped for " + deviceId);
        }
    }

    /**
     * Called by BLEConnection's BluetoothGattCallback.onReadRemoteRssi().
     *
     * Receives the raw RSSI, applies EMA smoothing, computes the distance
     * estimate using the log-distance path loss model, classifies proximity,
     * and sends the full result to Unity.
     *
     * @param gatt   The GATT connection.
     * @param rssi   Raw RSSI in dBm (e.g., -68).
     * @param status BluetoothGatt.GATT_SUCCESS (0) or GATT_FAILURE (257).
     */
    public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
        String deviceId = gatt.getDevice().getAddress();
        Log.d(TAG, String.format("onReadRemoteRssi: device=%s rssi=%d status=%d",
            deviceId, rssi, status));

        // Always advance the queue — readRemoteRssi() operation is done
        BLECommandQueue.getInstance().onOperationComplete();

        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.e(TAG, "onReadRemoteRssi: GATT failure, status=" + status);
            String json = String.format(
                "{\"id\":\"%s\",\"error\":\"GATT_FAILURE\",\"status\":%d}", deviceId, status);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_RSSI_ERROR, json);
            return;
        }

        // ── Apply EMA smoothing ───────────────────────────────────────────────
        // On first reading for a device, initialize the EMA to the raw value.
        // Subsequent readings blend in with the running average.
        double smoothed;
        synchronized (_smoothedRssi) {
            if (_smoothedRssi.containsKey(deviceId)) {
                double prev = _smoothedRssi.get(deviceId);
                // EMA: smoothed = α × raw + (1-α) × previous
                smoothed = EMA_ALPHA * rssi + (1.0 - EMA_ALPHA) * prev;
            } else {
                // First reading: initialize to raw value (no history to blend)
                smoothed = rssi;
            }
            _smoothedRssi.put(deviceId, smoothed);
        }

        // ── Estimate distance ─────────────────────────────────────────────────
        // Use the smoothed RSSI for distance to reduce jitter.
        int measuredPower = getMeasuredPower(deviceId);
        double distance = estimateDistance((int) Math.round(smoothed), measuredPower);
        String proximity = classifyProximity(distance);

        Log.d(TAG, String.format(
            "RSSI: raw=%d smoothed=%.1f distance=%.2fm proximity=%s",
            rssi, smoothed, distance, proximity));

        // ── Send to Unity ─────────────────────────────────────────────────────
        String json = String.format(
            "{\"id\":\"%s\",\"rssi\":%d,\"smoothed\":%.1f,\"distance\":%.2f,\"proximity\":\"%s\"}",
            deviceId, rssi, smoothed, distance, proximity);
        BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_RSSI_READ, json);
    }

    // ─── Distance Estimation ─────────────────────────────────────────────────

    /**
     * Estimate physical distance from RSSI using the log-distance path loss model.
     *
     * Formula:
     *   d = 10 ^ ((measuredPower - rssi) / (10 × n))
     *
     * where n = PATH_LOSS_EXPONENT = 2.0 (free space).
     *
     * IMPORTANT CAVEAT: This estimate can easily be wrong by a factor of 2-3×
     * in real environments. See the file header for a detailed explanation of
     * why RSSI-based distance is unreliable. Use classifyProximity() instead
     * of treating the returned value as a precise measurement.
     *
     * Reference:
     *   Rappaport, T.S. (2001). Wireless Communications: Principles and Practice,
     *   2nd ed. Prentice Hall. Chapter 3: Mobile Radio Propagation.
     *
     * Called from C# (for UI display or custom thresholding):
     *   double d = rssi.Call<double>("estimateDistance", -68, -65);
     *
     * @param rssi            Current RSSI in dBm.
     * @param measuredPowerAt1m RSSI at 1 meter for this device (calibration value).
     *                          Use DEFAULT_MEASURED_POWER (-65) if unknown.
     * @return                Estimated distance in meters. Returns 0.0 if RSSI is
     *                        extremely strong (rssi > measuredPower, physically
     *                        possible at very short range).
     */
    public double estimateDistance(int rssi, int measuredPowerAt1m) {
        if (rssi == 0) {
            // RSSI = 0 is a sentinel value meaning "no reading" on some hardware.
            // Return a large distance to indicate uncertainty.
            return Double.MAX_VALUE;
        }

        // exponent = (measuredPower - rssi) / (10 × n)
        double exponent = (double)(measuredPowerAt1m - rssi) / (10.0 * PATH_LOSS_EXPONENT);
        double distance = Math.pow(10.0, exponent);

        // Clamp to a minimum of 0 meters (physically, RSSI > measuredPower can
        // happen when the device is pressed directly against the antenna)
        return Math.max(0.0, distance);
    }

    /**
     * Classify a distance estimate into a coarse proximity category.
     *
     * Categories match Apple's iBeacon CLProximity enum, which has become
     * the de facto standard for BLE proximity classification:
     *
     *   "immediate" : < 0.5 m  — device is essentially touching
     *   "near"      : < 3.0 m  — same room, within a few meters
     *   "far"       : ≥ 3.0 m  — distant or across the room
     *
     * These categories are deliberately coarse because RSSI measurement noise
     * makes sub-meter precision impossible without additional sensors.
     *
     * @param distanceMeters Estimated distance from estimateDistance().
     * @return               "immediate", "near", or "far".
     */
    public String classifyProximity(double distanceMeters) {
        if (distanceMeters < PROXIMITY_IMMEDIATE_METERS) return "immediate";
        if (distanceMeters < PROXIMITY_NEAR_METERS)      return "near";
        return "far";
    }

    /**
     * Set a custom measuredPower calibration value for a specific device.
     *
     * Call this before startRSSIPolling() for better accuracy when the
     * default DEFAULT_MEASURED_POWER (-65 dBm) does not match your hardware.
     *
     * Calibration procedure:
     *   1. Place the device exactly 1 meter from the Quest (line of sight, no obstacles).
     *   2. Take 20+ RSSI readings over 10 seconds.
     *   3. Average them. The mean is your measuredPower for this device.
     *   4. Call setMeasuredPower() with the measured value.
     *
     * @param deviceId     MAC address of the device.
     * @param measuredPower RSSI at 1 meter for this device (e.g., -62).
     */
    public void setMeasuredPower(String deviceId, int measuredPower) {
        synchronized (_measuredPowerMap) {
            _measuredPowerMap.put(deviceId, measuredPower);
        }
        Log.d(TAG, "Set measuredPower for " + deviceId + " = " + measuredPower + " dBm");
    }

    /**
     * Clean up state when a device disconnects.
     *
     * Stops any active polling and removes smoothed RSSI history.
     * Called by BLEConnection on STATE_DISCONNECTED.
     *
     * @param deviceId MAC address of the disconnected device.
     */
    public void onDeviceDisconnected(String deviceId) {
        stopRSSIPolling(deviceId);
        synchronized (_smoothedRssi) {
            _smoothedRssi.remove(deviceId);
        }
        Log.d(TAG, "RSSI state cleared for disconnected device: " + deviceId);
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    /**
     * Returns the calibrated measuredPower for a device, or the default.
     *
     * @param deviceId MAC address.
     * @return         calibrated measuredPower, or DEFAULT_MEASURED_POWER.
     */
    private int getMeasuredPower(String deviceId) {
        synchronized (_measuredPowerMap) {
            Integer mp = _measuredPowerMap.get(deviceId);
            return (mp != null) ? mp : DEFAULT_MEASURED_POWER;
        }
    }
}
