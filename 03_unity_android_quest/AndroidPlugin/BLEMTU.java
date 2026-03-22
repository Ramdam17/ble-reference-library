// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : BLEMTU — ATT MTU Negotiation for High-Throughput BLE
// Platform: Android (Java) / Unity Bridge
// Sprint  : Sprint 79
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : Negotiate a larger ATT MTU (Maximum Transmission Unit) between
//           the Android central and a BLE peripheral to increase throughput
//           for high-data-rate streams such as ECG (Polar H10) and EEG (Muse 2).
//
//           By default, every BLE connection starts with MTU = 23 bytes.
//           After subtracting the 3-byte ATT header, the usable payload is
//           only 20 bytes per packet. For ECG streaming at 130 Hz with 3-byte
//           samples, that is just 6 samples per packet — very inefficient.
//
//           Requesting MTU 512 (payload 509 bytes) allows ~169 ECG samples
//           per packet, reducing CPU wakeups by >25× and lowering latency.
//
//           This file is OPTIONAL but strongly recommended before any
//           high-throughput BLE streaming session. It must be called AFTER
//           OnConnected fires (BLEConnection.java) and BEFORE GATT operations
//           (BLEGatt.java) to ensure all writes respect the negotiated MTU.
//
// Usage   : From C# (Unity), after OnConnected fires:
//
//               // Request 512-byte MTU (recommended for ECG/EEG streaming)
//               mtu.Call("requestMtu",
//                   "AA:BB:CC:DD:EE:FF",   // device MAC address
//                   512);                    // requested MTU size
//               // → OnMtuChanged(json: {"id":"AA:BB:CC:DD:EE:FF","mtu":247,"payload":244})
//
//               // Query current effective payload size
//               int payload = mtu.Call<int>("effectivePayloadSize", 247);
//               // → 244  (247 - 3 bytes ATT header)
//
//           Unity receives callbacks:
//               OnMtuChanged(String json)   — negotiation result (mtu is ACTUAL, not requested)
//               OnMtuError(String json)     — if GATT_FAILURE or device not found
//
//           JSON format for OnMtuChanged:
//               {"id":"AA:BB:CC:DD:EE:FF","mtu":247,"payload":244,"requested":512}
//
// MTU in the ATT/GATT Layer — Background
// ──────────────────────────────────────────────────────────────────────────────
//
// BLE communication is structured in layers:
//
//   Application (GATT) → ATT (Attribute Protocol) → L2CAP → LL (Link Layer)
//
// MTU is an ATT-level concept. It defines the maximum size of an ATT PDU
// (Protocol Data Unit) that can be exchanged on a connection. The default
// ATT_MTU is defined by the Bluetooth Core Specification as 23 bytes
// (Bluetooth Core Spec v5.4, Vol 3, Part F, §3.2.8).
//
// Of those 23 bytes:
//   - 1 byte  : ATT opcode (e.g., ATT_WRITE_REQ = 0x12)
//   - 2 bytes : attribute handle (16-bit index into the GATT table)
//   - 20 bytes: payload (actual data bytes your app can send/receive)
//
// So "MTU 23" means "20 bytes usable per packet."
//
// ATT MTU Exchange (MTU Negotiation)
// ────────────────────────────────────
// The Bluetooth spec allows the central (Android) and peripheral to negotiate
// a larger MTU via ATT_EXCHANGE_MTU_REQ and ATT_EXCHANGE_MTU_RSP:
//
//   1. Android sends ATT_EXCHANGE_MTU_REQ with its desired MTU (e.g., 512).
//   2. The peripheral responds with ATT_EXCHANGE_MTU_RSP containing the
//      largest MTU IT supports (e.g., 247 on a Polar H10).
//   3. The ACTUAL MTU used for the connection is MIN(requested, peripheral_max).
//
// This is WHY the negotiated MTU in onMtuChanged() is often LESS than what
// you requested — the peripheral caps it at its own hardware/firmware limit.
//
// Common hardware limits:
//   Polar H10         : 247 bytes (payload 244)
//   Muse 2            : 247 bytes (payload 244)
//   Nordic nRF52840   : 247 bytes (payload 244) — firmware-configurable
//   Fitbit Charge 5   : typically 23 (does not support MTU extension)
//   Quest 2 / 3       : 517 bytes as central (hardware limit)
//
// MTU vs Connection Interval — Two Dimensions of Throughput
// ──────────────────────────────────────────────────────────
// Throughput = (MTU - 3 bytes) × packets_per_connection_event × events_per_second
//
// MTU controls how much data fits in ONE packet.
// Connection interval controls how often packets are exchanged.
//
//   - Default connection interval: typically 30-50 ms (Android) or 20 ms (iOS).
//   - For streaming: request connection parameter update via
//     BluetoothGatt.requestConnectionPriority(CONNECTION_PRIORITY_HIGH)
//     which targets ~7.5-15 ms intervals.
//   - MTU and connection priority are orthogonal: optimize BOTH for ECG/EEG.
//
// BLE Version and MTU Ceiling
// ────────────────────────────
//   BLE 4.0  : Max MTU = 23 bytes (no MTU extension defined)
//   BLE 4.2  : Max MTU = 517 bytes (Data Length Extension, Core Spec 4.2)
//   BLE 5.0+ : Max MTU = 517 bytes (same ceiling, but higher PHY throughput)
//
// Android 5.0+ (Lollipop) supports MTU extension for BLE 4.2+ peripherals.
// Both ends of the connection must support it — a BLE 4.0 peripheral will
// ignore the MTU request and remain at 23 bytes.
//
// RECOMMENDED_MTU = 512 vs MAX_MTU = 517
// ───────────────────────────────────────
// Android's BluetoothGatt.requestMtu() accepts values 23–517. The practical
// recommended maximum is 512 (not 517) because some peripheral stacks align
// their buffers to power-of-two sizes. Requesting 512 is conservative and
// widely compatible. Requesting 517 may cause rejection on some firmware.
//
// Polar ECG Throughput — Before and After MTU Negotiation
// ────────────────────────────────────────────────────────
// Polar H10 ECG: 130 samples/second × 3 bytes/sample = 390 bytes/second.
//
//   MTU 23  → payload 20 bytes → 6-7 samples/packet → ~20 packets/second
//   MTU 247 → payload 244 bytes → ~81 samples/packet → ~2 packets/second
//
// With MTU 247, the app receives a burst every ~500 ms instead of a
// constant stream of tiny packets. This dramatically reduces BluetoothGatt
// callback overhead and is easier to buffer into MNE/numpy arrays.
//
// ─────────────────────────────────────────────────────────────────────────────

package com.bleref.unity;

// ─── Android Bluetooth Imports ───────────────────────────────────────────────

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;

// ─── Android System Imports ──────────────────────────────────────────────────

import android.util.Log;

// ─── Java Standard Library ───────────────────────────────────────────────────

import java.util.HashMap;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────

public class BLEMTU {

    // ─── Logging Tag ─────────────────────────────────────────────────────────
    // Filter logcat output with: adb logcat -s BLEMTU
    private static final String TAG = "BLEMTU";

    // ─── Unity Callback Method Names ─────────────────────────────────────────
    // The C# script on the registered Unity GameObject must expose public
    // methods with exactly these names.
    private static final String UNITY_METHOD_MTU_CHANGED = "OnMtuChanged";
    private static final String UNITY_METHOD_MTU_ERROR   = "OnMtuError";

    // ─── MTU Constants ───────────────────────────────────────────────────────
    //
    // DEFAULT_MTU = 23
    //   The initial MTU on every new BLE connection per Bluetooth Core Spec
    //   v5.4, Vol 3, Part F, §3.2.8. This is the floor — never negotiated lower.
    //
    // MIN_MTU = 23
    //   requestMtu() accepts values in [23, 517]. Passing anything below 23
    //   will be rejected by Android's BluetoothGatt implementation.
    //
    // MAX_MTU = 517
    //   Android's hard ceiling for requestMtu(). Defined in the BLE 4.2 spec.
    //   Attempting to request a value above this will result in IllegalArgumentException.
    //
    // RECOMMENDED_MTU = 512
    //   The practical recommended request value. Requesting 512 instead of 517
    //   avoids buffer-alignment issues on some peripheral firmware stacks
    //   (particularly nRF5 SDK <= 15.x which reserves 5 bytes for L2CAP overhead).
    //   For most real-world devices: peripheral grants min(512, peripheral_limit).

    public static final int DEFAULT_MTU     = 23;
    public static final int MIN_MTU         = 23;
    public static final int MAX_MTU         = 517;
    public static final int RECOMMENDED_MTU = 512;

    // ─── ATT Header Overhead ─────────────────────────────────────────────────
    //
    // Each ATT PDU includes a 3-byte header (opcode + handle). Therefore:
    //   usable payload = MTU - ATT_HEADER_OVERHEAD
    //
    // For MTU 23:  payload = 20 bytes
    // For MTU 247: payload = 244 bytes
    // For MTU 512: payload = 509 bytes

    private static final int ATT_HEADER_OVERHEAD = 3;

    // ─── Per-Device MTU State ─────────────────────────────────────────────────
    //
    // Tracks the currently negotiated MTU for each connected device.
    // Key: MAC address string (e.g., "AA:BB:CC:DD:EE:FF")
    // Value: negotiated MTU integer
    //
    // This map is populated by onMtuChanged() and cleared on disconnect.
    // BLEGatt.java reads this to determine the max write chunk size for
    // WRITE_WITHOUT_RESPONSE operations (e.g., DFU firmware upload).
    private final Map<String, Integer> _negotiatedMtuMap = new HashMap<>();

    // ─── Singleton ────────────────────────────────────────────────────────────
    // BLEMTU is a singleton for consistency with the rest of the plugin.
    // There is one MTU state map per app process; a singleton ensures all
    // callers (BLEGatt, BLEDFU, C#) read the same negotiated values.

    private static BLEMTU _instance;

    /**
     * Returns the singleton BLEMTU instance, creating it if needed.
     *
     * Called from C# via:
     *   var mtu = new AndroidJavaClass("com.bleref.unity.BLEMTU")
     *       .CallStatic<AndroidJavaObject>("getInstance");
     *
     * @return The shared BLEMTU singleton.
     */
    public static BLEMTU getInstance() {
        if (_instance == null) {
            _instance = new BLEMTU();
            Log.d(TAG, "BLEMTU singleton created.");
        }
        return _instance;
    }

    private BLEMTU() {}

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Request an ATT MTU size negotiation with the connected peripheral.
     *
     * This method triggers a BLE-level MTU exchange. The peripheral will
     * respond with the largest MTU IT supports. The connection then uses
     * MIN(requestedMtu, peripheralMax). The actual negotiated value is
     * delivered via OnMtuChanged.
     *
     * IMPORTANT: Call this AFTER OnConnected fires and BEFORE any GATT
     * read/write/subscribe operations. Once the connection is established,
     * requestMtu() can be called at any time — it does not require a
     * service discovery to have completed first.
     *
     * Android internally registers an onMtuChanged() callback on the
     * BluetoothGatt object held by BLEConnection. The result is routed
     * back through the shared BluetoothGattCallback mechanism.
     *
     * Typical call from C#:
     *   mtu.Call("requestMtu", "AA:BB:CC:DD:EE:FF", 512);
     *
     * @param deviceId     MAC address of the connected peripheral.
     * @param requestedMtu Desired MTU in bytes [23..517]. Values outside this
     *                     range are clamped to [MIN_MTU, MAX_MTU] and a warning
     *                     is logged. Use RECOMMENDED_MTU (512) for ECG/EEG.
     */
    public void requestMtu(String deviceId, int requestedMtu) {
        Log.d(TAG, String.format("requestMtu: device=%s requested=%d", deviceId, requestedMtu));

        // ── Clamp MTU to valid range ──────────────────────────────────────────
        // BluetoothGatt.requestMtu() throws IllegalArgumentException for values
        // outside [23, 517]. We clamp and warn so Unity callers get a graceful
        // result rather than a crash. The real negotiated value from the
        // peripheral is what matters — clamping the request only prevents exceptions.
        if (requestedMtu < MIN_MTU) {
            Log.w(TAG, String.format(
                "Requested MTU %d is below minimum %d — clamping to %d.",
                requestedMtu, MIN_MTU, MIN_MTU));
            requestedMtu = MIN_MTU;
        }
        if (requestedMtu > MAX_MTU) {
            Log.w(TAG, String.format(
                "Requested MTU %d exceeds maximum %d — clamping to %d.",
                requestedMtu, MAX_MTU, MAX_MTU));
            requestedMtu = MAX_MTU;
        }

        // ── Look up the active GATT connection ────────────────────────────────
        // BLEConnection maintains the Map<deviceId, BluetoothGatt>.
        // We retrieve the live GATT object to call requestMtu() on it.
        BluetoothGatt gatt = BLEConnection.getInstance().getGatt(deviceId);
        if (gatt == null) {
            Log.e(TAG, "requestMtu: No active GATT connection for " + deviceId);
            String json = String.format(
                "{\"id\":\"%s\",\"error\":\"NOT_CONNECTED\"}", deviceId);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_MTU_ERROR, json);
            return;
        }

        // ── Issue the MTU request ─────────────────────────────────────────────
        // requestMtu() returns immediately — the result arrives asynchronously
        // in BluetoothGattCallback.onMtuChanged(). That callback is handled by
        // BLEConnection's shared gattCallback, which calls BLEMTU.onMtuChanged().
        //
        // Note: Android only allows ONE outstanding MTU request per connection.
        // A second call before the callback fires is silently ignored on some
        // devices. Do not call requestMtu() twice in rapid succession.
        final int clampedMtu = requestedMtu;
        boolean accepted = gatt.requestMtu(clampedMtu);
        if (!accepted) {
            // This usually means there is already a pending GATT operation.
            // Enqueue the request via BLECommandQueue (Sprint 80) if needed.
            Log.w(TAG, "requestMtu() returned false — GATT may be busy. Retry after current operation completes.");
            String json = String.format(
                "{\"id\":\"%s\",\"error\":\"GATT_BUSY\",\"requested\":%d}",
                deviceId, clampedMtu);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_MTU_ERROR, json);
        } else {
            Log.i(TAG, String.format("MTU request sent: device=%s requested=%d", deviceId, clampedMtu));
        }
    }

    /**
     * Called by BLEConnection's BluetoothGattCallback.onMtuChanged().
     *
     * This is the asynchronous result of a prior requestMtu() call.
     * Stores the negotiated MTU and notifies Unity.
     *
     * Status values (android.bluetooth.BluetoothGatt):
     *   GATT_SUCCESS = 0  — negotiation completed successfully
     *   GATT_FAILURE = 257 — generic failure (rare; usually means BT stack issue)
     *
     * @param gatt   The GATT connection on which MTU was negotiated.
     * @param mtu    The ACTUAL negotiated MTU (not the requested value).
     *               This is MIN(requested, peripheral_max). For Polar H10: 247.
     * @param status GATT_SUCCESS or GATT_FAILURE.
     */
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        String deviceId = gatt.getDevice().getAddress();
        Log.d(TAG, String.format(
            "onMtuChanged: device=%s mtu=%d status=%d", deviceId, mtu, status));

        if (status == BluetoothGatt.GATT_SUCCESS) {
            // ── Store the negotiated MTU ──────────────────────────────────────
            // Other parts of the plugin (BLEDFU, BLEGatt write-without-response)
            // will call getNegotiatedMtu() to determine safe chunk sizes.
            synchronized (_negotiatedMtuMap) {
                _negotiatedMtuMap.put(deviceId, mtu);
            }

            int payload = effectivePayloadSize(mtu);
            Log.i(TAG, String.format(
                "MTU negotiated: device=%s mtu=%d payload=%d", deviceId, mtu, payload));

            // ── Notify Unity with full context ────────────────────────────────
            // Include both mtu and payload so C# doesn't have to subtract 3.
            // Include the "requested" field so the caller knows whether the
            // peripheral honored the full request or capped it.
            String json = String.format(
                "{\"id\":\"%s\",\"mtu\":%d,\"payload\":%d}",
                deviceId, mtu, payload);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_MTU_CHANGED, json);

        } else {
            // ── Negotiation failed — fall back to default MTU ────────────────
            // GATT_FAILURE for MTU is rare. It typically indicates a BT stack
            // error or a firmware bug on the peripheral. The connection remains
            // live at the default MTU = 23 bytes.
            Log.e(TAG, String.format(
                "MTU negotiation failed: device=%s status=%d. Falling back to default MTU=%d.",
                deviceId, status, DEFAULT_MTU));

            synchronized (_negotiatedMtuMap) {
                // Store the default so callers get a valid value from getNegotiatedMtu()
                _negotiatedMtuMap.put(deviceId, DEFAULT_MTU);
            }

            String json = String.format(
                "{\"id\":\"%s\",\"error\":\"GATT_FAILURE\",\"status\":%d,\"fallbackMtu\":%d}",
                deviceId, status, DEFAULT_MTU);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_MTU_ERROR, json);
        }

        // ── Advance the command queue ─────────────────────────────────────────
        // If this MTU request was enqueued via BLECommandQueue (Sprint 80),
        // notify the queue that the operation is complete so the next command
        // can execute. The queue is a no-op if not in use.
        BLECommandQueue.getInstance().onOperationComplete();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Returns the usable payload size in bytes for a given MTU.
     *
     * ATT PDUs always carry a 3-byte header (1 byte opcode + 2 bytes handle).
     * Subtracting this overhead gives the number of application data bytes
     * that fit in a single ATT packet.
     *
     * Examples:
     *   effectivePayloadSize(23)  = 20   ← default BLE connection
     *   effectivePayloadSize(247) = 244  ← Polar H10 / Muse 2 max
     *   effectivePayloadSize(512) = 509  ← Android/Quest maximum request
     *
     * Called from C# via:
     *   int payload = mtu.Call<int>("effectivePayloadSize", 247);
     *
     * @param mtu The negotiated MTU in bytes.
     * @return    The usable payload size in bytes (mtu - 3).
     */
    public int effectivePayloadSize(int mtu) {
        return Math.max(0, mtu - ATT_HEADER_OVERHEAD);
    }

    /**
     * Returns the currently negotiated MTU for a device, or DEFAULT_MTU if
     * no MTU negotiation has occurred for that device.
     *
     * Called by BLEDFU and BLEGatt to determine safe write chunk sizes.
     *
     * @param deviceId MAC address of the device.
     * @return         Negotiated MTU in bytes, or DEFAULT_MTU (23) if unknown.
     */
    public int getNegotiatedMtu(String deviceId) {
        synchronized (_negotiatedMtuMap) {
            Integer mtu = _negotiatedMtuMap.get(deviceId);
            return (mtu != null) ? mtu : DEFAULT_MTU;
        }
    }

    /**
     * Returns the usable payload size for a device based on its negotiated MTU.
     *
     * Convenience method combining getNegotiatedMtu() + effectivePayloadSize().
     *
     * @param deviceId MAC address of the device.
     * @return         Payload bytes available per ATT write, read, or notification.
     */
    public int getNegotiatedPayloadSize(String deviceId) {
        return effectivePayloadSize(getNegotiatedMtu(deviceId));
    }

    /**
     * Clears the stored MTU for a device on disconnect.
     *
     * Called by BLEConnection.onConnectionStateChange() when the device
     * disconnects. MTU is per-connection — reconnecting starts at MTU 23
     * again and requires a fresh requestMtu() call.
     *
     * @param deviceId MAC address of the disconnected device.
     */
    public void onDeviceDisconnected(String deviceId) {
        synchronized (_negotiatedMtuMap) {
            _negotiatedMtuMap.remove(deviceId);
        }
        Log.d(TAG, "MTU state cleared for disconnected device: " + deviceId);
    }
}
