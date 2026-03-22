// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : BLEDeviceInfo — Device Information Service (DIS) 0x180A
// Platform: Android (Java) / Unity Bridge
// Sprint  : Sprint 77
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : Read all Device Information Service (DIS) characteristics from a
//           connected peripheral and deliver them to Unity as a single JSON
//           object. DIS is the standardized way for a BLE peripheral to
//           announce its identity: who made it, what model it is, what firmware
//           it runs.
//
//           This file is the FIFTH layer of the Android BLE plugin stack:
//             Sprint 73 — BLEAndroidPlugin  (init, permissions)
//             Sprint 74 — BLEScan           (discovery)
//             Sprint 75 — BLEConnection     (connect/disconnect lifecycle)
//             Sprint 76 — BLEGatt           (GATT operations)
//             Sprint 77 — BLEDeviceInfo     (DIS read) ← this file
//             Sprint 78 — MuseEEG           (Muse uint:12 parser + streaming)
//
// Usage   : From C# (Unity), after OnConnected and OnServicesDiscovered fire:
//
//               deviceInfo.Call("readDeviceInfo", "AA:BB:CC:DD:EE:FF");
//               // → OnDeviceInfoRead(json)
//
//           JSON result format:
//               {
//                 "id":           "AA:BB:CC:DD:EE:FF",
//                 "manufacturer": "Polar",
//                 "model":        "H10",
//                 "serial":       "A0000000",
//                 "hardware":     "1.0",
//                 "firmware":     "3.1.0"
//               }
//
//           Any characteristic that is absent or unreadable has a null value
//           (JSON `null`). The callback ALWAYS fires — even if no DIS service
//           is present — so Unity does not need a timeout guard.
//
// ─────────────────────────────────────────────────────────────────────────────
//
// WHY DIS IS USEFUL
// ──────────────────
// The Device Information Service is one of the most underused GATT services,
// yet it is critical for real-world production apps. Here is why:
//
// 1. OTA Firmware Update Compatibility
//    A firmware update app MUST know the current firmware version before
//    pushing a new image. Pushing an incompatible image can brick the device.
//    Reading firmware_revision (0x2A26) before any OTA flow is essential.
//    Without DIS, you have no standard way to retrieve this.
//
// 2. Model-Specific Protocol Variations
//    Hardware variants of the same product family (e.g., H10 vs H9 vs H7 vs
//    OH1) often differ in which characteristics are available, their value
//    encodings, or their notification rates. Reading model_number (0x2A24)
//    allows your app to branch cleanly without heuristics.
//
// 3. Support and Diagnostics
//    When a user reports a bug, "what device, firmware, and hardware revision?"
//    is always the first question. Reading DIS and logging it at connection
//    time makes issue triage dramatically faster.
//
// 4. Multi-Vendor Field Study Data
//    In a research context (e.g., EEG/ECG hyperscanning), you may connect to
//    devices from different manufacturers or firmware revisions in the same
//    session. Logging DIS data alongside signal data enables post-hoc quality
//    annotation and reproducibility.
//
// ─────────────────────────────────────────────────────────────────────────────
//
// WHY READS MUST BE SEQUENTIAL (NOT PARALLEL)
// ─────────────────────────────────────────────
// Android's BluetoothGatt stack supports only ONE GATT operation in flight
// per connection at a time. This constraint comes from the Bluetooth Core
// Specification itself: GATT operations are serialized over a single ATT
// (Attribute Protocol) bearer. The ATT protocol does not support out-of-order
// responses or multiplexing within a single connection.
//
// If you call readCharacteristic() for all 5 DIS characteristics at once:
//   - The first call returns true and enqueues a Read Request on the ATT layer.
//   - Calls 2-5 return false immediately — the stack rejects them because an
//     operation is already pending.
//   - Callbacks 2-5 NEVER fire. The corresponding fields stay empty.
//   - You end up with partial data and no error notification.
//
// Correct pattern (implemented here):
//   1. Call readCharacteristic() for characteristic 1.
//   2. Wait for onCharacteristicRead() callback.
//   3. Store the result, then call readCharacteristic() for characteristic 2.
//   4. Repeat until all 5 are done, then send the JSON to Unity.
//
// This sequential chaining is implemented via a simple integer state machine
// (_readStep) that advances one step inside onCharacteristicRead().
//
// For a full-featured command queue that handles arbitrary sequences of reads,
// writes, and descriptor ops, see Sprint 80 (BLECommandQueue.java).
//
// ─────────────────────────────────────────────────────────────────────────────
//
// Device Information Service (DIS) — GATT Specification Reference
// ────────────────────────────────────────────────────────────────
// Service UUID:      0x180A  →  "0000180a-0000-1000-8000-00805f9b34fb"
//
// All DIS characteristics are READ-only. Notifications are not supported.
// Values are encoded as UTF-8 strings with no null terminator.
// Maximum length is 248 bytes per Bluetooth SIG assignment.
//
// ┌──────────────────────┬────────┬────────────────────────────────────────────┐
// │ Characteristic       │ UUID   │ Example value                              │
// ├──────────────────────┼────────┼────────────────────────────────────────────┤
// │ Manufacturer Name    │ 0x2A29 │ "Polar", "Muse", "WHOOP Inc."             │
// │ Model Number         │ 0x2A24 │ "H10", "Muse 2", "4.0"                   │
// │ Serial Number        │ 0x2A25 │ "A0000000", "12345678"                    │
// │ Hardware Revision    │ 0x2A27 │ "1.0", "Rev C"                            │
// │ Firmware Revision    │ 0x2A26 │ "3.1.0", "1.3.4"                         │
// └──────────────────────┴────────┴────────────────────────────────────────────┘
//
// Not all peripherals implement all characteristics. A Polar H10 typically
// provides all five. A Muse 2 may only provide manufacturer and firmware.
// Always handle missing characteristics gracefully (null, not crash).
//
// ─────────────────────────────────────────────────────────────────────────────

package com.bleref.unity;

// ─── Android Bluetooth GATT Imports ──────────────────────────────────────────

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;

// ─── Android System Imports ──────────────────────────────────────────────────

import android.os.Build;
import android.util.Log;

// ─── AndroidX Annotation ─────────────────────────────────────────────────────

import androidx.annotation.RequiresApi;

// ─── Java Standard Library ───────────────────────────────────────────────────

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Reads all Device Information Service (DIS) characteristics sequentially
 * and delivers them to Unity as a single JSON object.
 *
 * <p>This class follows the same singleton + UnitySendMessage pattern as
 * {@code BLEAndroidPlugin}, {@code BLEScan}, {@code BLEConnection}, and
 * {@code BLEGatt}. It piggybacks on the BluetoothGatt objects already managed
 * by {@code BLEConnection} — it does not open a new connection.
 *
 * <p>Call {@link #readDeviceInfo(String)} after OnServicesDiscovered has fired.
 * The result arrives in {@code OnDeviceInfoRead(String json)}.
 */
public class BLEDeviceInfo {

    // ─── Logging Tag ─────────────────────────────────────────────────────────
    private static final String TAG = "BLEDeviceInfo";

    // ─── Device Information Service UUID ─────────────────────────────────────
    // Bluetooth SIG assigned service UUID for DIS.
    // Short form: 0x180A. Expanded to 128-bit: 0000180a-0000-1000-8000-00805f9b34fb
    private static final UUID DIS_SERVICE_UUID =
        UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");

    // ─── DIS Characteristic UUIDs ────────────────────────────────────────────
    // Each is a Bluetooth SIG assigned 16-bit UUID expanded to 128-bit.
    // All are READ-only, UTF-8 encoded, no null terminator.

    /** Manufacturer Name String — e.g., "Polar", "Muse" */
    private static final UUID MANUFACTURER_NAME_UUID =
        UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb");

    /** Model Number String — e.g., "H10", "Muse 2" */
    private static final UUID MODEL_NUMBER_UUID =
        UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb");

    /** Serial Number String — e.g., "A0000000" */
    private static final UUID SERIAL_NUMBER_UUID =
        UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb");

    /** Hardware Revision String — e.g., "1.0" */
    private static final UUID HARDWARE_REVISION_UUID =
        UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb");

    /** Firmware Revision String — e.g., "3.1.0" */
    private static final UUID FIRMWARE_REVISION_UUID =
        UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb");

    // ─── Read order: 5 characteristics, sequential ───────────────────────────
    // The order is fixed for simplicity. readDeviceInfo() works through this
    // array one element at a time, driven by the onCharacteristicRead callback.
    private static final UUID[] READ_ORDER = {
        MANUFACTURER_NAME_UUID,
        MODEL_NUMBER_UUID,
        SERIAL_NUMBER_UUID,
        HARDWARE_REVISION_UUID,
        FIRMWARE_REVISION_UUID,
    };

    // Human-readable field names used in JSON output (index-matched to READ_ORDER)
    private static final String[] FIELD_NAMES = {
        "manufacturer",
        "model",
        "serial",
        "hardware",
        "firmware",
    };

    // ─── Unity Callback Method Names ─────────────────────────────────────────
    private static final String UNITY_METHOD_DEVICE_INFO_READ = "OnDeviceInfoRead";
    private static final String UNITY_METHOD_DIS_ERROR        = "OnDeviceInfoError";

    // ─── Active Read Sessions ─────────────────────────────────────────────────
    // Maps deviceId → ReadSession.
    // A ReadSession tracks progress across the 5 sequential reads for one
    // device. Multiple devices can be read concurrently (each has its own
    // GATT connection and its own ReadSession).
    private final Map<String, ReadSession> _sessions = new HashMap<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Inner class: DeviceInfo POJO
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Value object holding all five DIS fields.
     *
     * <p>All fields are nullable. A {@code null} value means the characteristic
     * was absent from the peripheral or unreadable. Unity receives JSON {@code null}
     * for such fields — the C# side must check for null before use.
     *
     * <p>Example populated instance for a Polar H10:
     * <pre>
     *   manufacturer = "Polar"
     *   model        = "H10"
     *   serial       = "A0000000"
     *   hardware     = "1.0"
     *   firmware     = "3.1.0"
     * </pre>
     */
    public static class DeviceInfo {

        /** Manufacturer Name String (0x2A29). Null if absent. */
        public String manufacturer;

        /** Model Number String (0x2A24). Null if absent. */
        public String model;

        /** Serial Number String (0x2A25). Null if absent. */
        public String serial;

        /** Hardware Revision String (0x2A27). Null if absent. */
        public String hardware;

        /** Firmware Revision String (0x2A26). Null if absent. */
        public String firmware;

        /** Constructs an empty DeviceInfo with all fields null. */
        public DeviceInfo() {
            // All fields start null — populated during sequential reads
        }

        /**
         * Sets a field by index (matches READ_ORDER / FIELD_NAMES indexing).
         *
         * <p>This avoids a switch statement and keeps the sequential-read loop
         * free of field-specific logic.
         *
         * @param index Field index (0=manufacturer, 1=model, 2=serial,
         *              3=hardware, 4=firmware).
         * @param value UTF-8 decoded value, or null if unreadable.
         */
        public void setField(int index, String value) {
            switch (index) {
                case 0: manufacturer = value; break;
                case 1: model        = value; break;
                case 2: serial       = value; break;
                case 3: hardware     = value; break;
                case 4: firmware     = value; break;
                default:
                    Log.w("BLEDeviceInfo", "setField: unknown index " + index);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner class: ReadSession
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * State machine for one device's sequential DIS read flow.
     *
     * <p>Tracks which step (0–4) is currently in flight and accumulates results
     * into a {@code DeviceInfo} instance. When all 5 steps complete (or when
     * there are no more readable characteristics), {@code isComplete()} returns
     * true and the accumulated data can be serialized to JSON.
     */
    private static class ReadSession {

        final String deviceId;
        final DeviceInfo info;

        /** Index into READ_ORDER of the characteristic currently being read. */
        int readStep;

        /**
         * Reference to the BluetoothGattCallback registered for this session.
         * Retained so it can be unregistered from BLEConnection after completion.
         */
        BluetoothGattCallback callback;

        ReadSession(String deviceId) {
            this.deviceId = deviceId;
            this.info = new DeviceInfo();
            this.readStep = 0;
        }

        /** Returns true when all 5 characteristics have been attempted. */
        boolean isComplete() {
            return readStep >= READ_ORDER.length;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reads all Device Information Service characteristics from a connected
     * peripheral, sequentially.
     *
     * <p>Prerequisite: The device must be connected (BLEConnection) and
     * services must have been discovered (BLEGatt.discoverServices). This
     * method retrieves the existing BluetoothGatt object from BLEConnection
     * — it does NOT open a new connection.
     *
     * <p>Result delivered to Unity via:
     * <pre>
     *   OnDeviceInfoRead(String json)
     * </pre>
     * JSON format:
     * <pre>
     *   {"id":"AA:BB:CC:DD:EE:FF","manufacturer":"Polar","model":"H10",
     *    "serial":"A0000000","hardware":"1.0","firmware":"3.1.0"}
     * </pre>
     *
     * <p>If the DIS service is absent, all 5 fields are null and the callback
     * still fires immediately (no timeout needed on the C# side).
     *
     * <p>If a DIS characteristic is present but returns an error status
     * (e.g., READ_NOT_PERMITTED), its field is set to null and the read
     * sequence continues to the next characteristic.
     *
     * @param deviceId MAC address or identifier of the connected peripheral.
     *                 Must be an active connection managed by BLEConnection.
     */
    public void readDeviceInfo(String deviceId) {
        Log.i(TAG, "readDeviceInfo: device=" + deviceId);

        // Retrieve the BluetoothGatt from BLEConnection's registry.
        // BLEConnection is the lifecycle owner — we read from it, not manage it.
        BluetoothGatt gatt = BLEConnection.getInstance().getGatt(deviceId);
        if (gatt == null) {
            Log.e(TAG, "readDeviceInfo: no active GATT connection for " + deviceId);
            sendError(deviceId, "NOT_CONNECTED");
            return;
        }

        // Check if DIS service is present before starting the read loop.
        // If absent, deliver immediately with all null fields — no reads needed.
        BluetoothGattService disService = gatt.getService(DIS_SERVICE_UUID);
        if (disService == null) {
            Log.w(TAG, "DIS service not present on " + deviceId + " — delivering null fields");
            ReadSession emptySession = new ReadSession(deviceId);
            // Mark complete immediately (all fields stay null)
            emptySession.readStep = READ_ORDER.length;
            sendResult(deviceId, emptySession.info);
            return;
        }

        // Start a new read session for this device.
        // If one is already running (e.g., Unity called readDeviceInfo twice),
        // cancel the old one gracefully by replacing it.
        ReadSession session = new ReadSession(deviceId);
        _sessions.put(deviceId, session);

        // Register a GATT callback for this session's sequential reads.
        // The callback drives the state machine: each onCharacteristicRead()
        // fires the next read (or the final JSON delivery if done).
        session.callback = buildGattCallback(deviceId, gatt, disService);
        BLEConnection.getInstance().registerGattCallback(deviceId, session.callback);

        // Kick off the first read (step 0 = Manufacturer Name)
        readNextCharacteristic(deviceId, gatt, disService, session);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private: Sequential Read State Machine
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Attempts to read the next DIS characteristic in the sequence.
     *
     * <p>If the characteristic is absent from this peripheral's DIS service,
     * its field stays null and the sequence advances to the next step
     * immediately (no GATT operation issued). This avoids a stall when a
     * device only implements a subset of DIS.
     *
     * <p>Called from the main thread (first call) and from the GATT callback
     * thread (subsequent calls via onCharacteristicRead). Both are safe because
     * BluetoothGatt.readCharacteristic() is thread-safe.
     *
     * @param deviceId     Device identifier (for logging and session lookup).
     * @param gatt         Active BluetoothGatt for this device.
     * @param disService   The DIS BluetoothGattService (pre-fetched).
     * @param session      The active ReadSession for this device.
     */
    private void readNextCharacteristic(
            String deviceId,
            BluetoothGatt gatt,
            BluetoothGattService disService,
            ReadSession session) {

        // Skip over any characteristics not present on this peripheral.
        // (Not all devices implement all 5 DIS characteristics.)
        while (!session.isComplete()) {
            UUID charUuid = READ_ORDER[session.readStep];
            BluetoothGattCharacteristic characteristic = disService.getCharacteristic(charUuid);

            if (characteristic != null) {
                // Characteristic exists — issue the read and wait for callback.
                Log.d(TAG, "Reading DIS char[" + session.readStep + "] "
                    + charUuid + " from " + deviceId);
                boolean accepted = gatt.readCharacteristic(characteristic);
                if (!accepted) {
                    // Android rejected the read — another GATT operation is in flight.
                    // This should not happen in normal flow (we are sequential), but if
                    // it does, log and skip this characteristic rather than stalling.
                    Log.e(TAG, "readCharacteristic rejected for " + charUuid
                        + " (another op in flight?) — skipping");
                    session.readStep++;
                    // Loop to try the next one
                    continue;
                }
                // Read accepted — return and wait for onCharacteristicRead callback.
                return;
            } else {
                // Characteristic absent — field stays null, advance without a read.
                Log.d(TAG, "DIS char[" + session.readStep + "] " + charUuid
                    + " absent on " + deviceId + " — null");
                session.readStep++;
                // Loop to check the next characteristic
            }
        }

        // All 5 steps complete (either read or skipped) — deliver to Unity.
        Log.i(TAG, "All DIS characteristics processed for " + deviceId);
        sendResult(deviceId, session.info);
        _sessions.remove(deviceId);
        BLEConnection.getInstance().unregisterGattCallback(deviceId, session.callback);
    }

    /**
     * Builds the BluetoothGattCallback that drives the sequential read state machine.
     *
     * <p>Only {@code onCharacteristicRead} is implemented — the other callbacks
     * ({@code onServicesDiscovered}, {@code onCharacteristicWrite}, etc.) are
     * intentionally left as no-ops to avoid interfering with BLEGatt's callbacks.
     *
     * @param deviceId   Device identifier (for session lookup).
     * @param gatt       Active BluetoothGatt (passed through for the next read).
     * @param disService Pre-fetched DIS service (passed through for the next read).
     * @return A new BluetoothGattCallback scoped to this session.
     */
    private BluetoothGattCallback buildGattCallback(
            final String deviceId,
            final BluetoothGatt gatt,
            final BluetoothGattService disService) {

        return new BluetoothGattCallback() {

            // ── API 33+ form: value passed directly (thread-safe) ────────────
            // See BLEGatt.java for a detailed explanation of why Android API 33
            // introduced this new form and why both must be implemented.

            @RequiresApi(Build.VERSION_CODES.TIRAMISU)
            @Override
            public void onCharacteristicRead(
                    BluetoothGatt g,
                    BluetoothGattCharacteristic characteristic,
                    byte[] value,
                    int status) {
                handleRead(characteristic.getUuid(), value, status);
            }

            // ── Pre-API 33 form (deprecated but required for backward compat) ─
            @SuppressWarnings("deprecation")
            @Override
            public void onCharacteristicRead(
                    BluetoothGatt g,
                    BluetoothGattCharacteristic characteristic,
                    int status) {
                // Call getValue() immediately — it is not thread-safe to defer.
                byte[] value = characteristic.getValue();
                handleRead(characteristic.getUuid(), value, status);
            }

            /**
             * Shared handler for both onCharacteristicRead variants.
             *
             * <p>Records the result (UTF-8 decoded string or null on error),
             * advances the step counter, and triggers the next read.
             *
             * @param uuid   UUID of the characteristic just read.
             * @param value  Raw bytes from the peripheral (may be null on error).
             * @param status GATT_SUCCESS (0) or an error code.
             */
            private void handleRead(UUID uuid, byte[] value, int status) {
                ReadSession session = _sessions.get(deviceId);
                if (session == null) {
                    // Session was cancelled (e.g., readDeviceInfo called again)
                    Log.w(TAG, "onCharacteristicRead: no active session for " + deviceId);
                    return;
                }

                int step = session.readStep;
                Log.d(TAG, "onCharacteristicRead: device=" + deviceId
                    + " step=" + step + " uuid=" + uuid + " status=" + status);

                if (status == BluetoothGatt.GATT_SUCCESS && value != null && value.length > 0) {
                    // Decode as UTF-8. DIS spec: all string characteristics are UTF-8.
                    // trim() removes trailing whitespace or null bytes (some peripherals
                    // pad the string to the maximum ATT payload length).
                    String decoded = new String(value, StandardCharsets.UTF_8).trim();
                    session.info.setField(step, decoded);
                    Log.i(TAG, "  " + FIELD_NAMES[step] + " = " + decoded);
                } else {
                    // Read failed or returned empty data — field stays null.
                    // Log the status for debugging but do not abort the sequence.
                    Log.w(TAG, "  " + FIELD_NAMES[step] + " read failed: status=" + status
                        + " (field will be null)");
                    session.info.setField(step, null);
                }

                // Advance to the next characteristic.
                session.readStep++;

                // Continue the sequence. This call may fire the next read, or
                // deliver the final JSON if all steps are done.
                readNextCharacteristic(deviceId, gatt, disService, session);
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private: JSON Serialization and Unity Delivery
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Serializes a {@code DeviceInfo} to JSON and sends it to Unity.
     *
     * <p>Null fields are serialized as JSON {@code null} (not the string "null").
     * Unity C# can check for null with standard null checks after parsing.
     *
     * <p>Example JSON (Polar H10):
     * <pre>
     *   {"id":"AA:BB:CC:DD:EE:FF","manufacturer":"Polar","model":"H10",
     *    "serial":"A0000000","hardware":"1.0","firmware":"3.1.0"}
     * </pre>
     *
     * <p>Example JSON (Muse 2, partial DIS):
     * <pre>
     *   {"id":"AA:BB:CC:DD:EE:FF","manufacturer":"Muse","model":null,
     *    "serial":null,"hardware":null,"firmware":"1.3.4"}
     * </pre>
     *
     * @param deviceId Device identifier (included in JSON for Unity routing).
     * @param info     Populated (or partially populated) DeviceInfo.
     */
    private void sendResult(String deviceId, DeviceInfo info) {
        // Build JSON manually — no Gson/Jackson dependency in this plugin.
        // Null fields are serialized as JSON null (unquoted).
        // String fields are JSON-escaped to handle embedded quotes or backslashes.
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"id\":").append(jsonString(deviceId)).append(",");
        sb.append("\"manufacturer\":").append(jsonStringOrNull(info.manufacturer)).append(",");
        sb.append("\"model\":").append(jsonStringOrNull(info.model)).append(",");
        sb.append("\"serial\":").append(jsonStringOrNull(info.serial)).append(",");
        sb.append("\"hardware\":").append(jsonStringOrNull(info.hardware)).append(",");
        sb.append("\"firmware\":").append(jsonStringOrNull(info.firmware));
        sb.append("}");

        String json = sb.toString();
        Log.i(TAG, "DeviceInfo ready: " + json);
        BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_DEVICE_INFO_READ, json);
    }

    /**
     * Sends an error JSON to Unity on {@code OnDeviceInfoError}.
     *
     * @param deviceId Device identifier.
     * @param error    Error code string (e.g., {@code "NOT_CONNECTED"}).
     */
    private void sendError(String deviceId, String error) {
        String json = String.format(
            "{\"id\":\"%s\",\"error\":\"%s\"}",
            deviceId, error);
        Log.e(TAG, "DeviceInfo error for " + deviceId + ": " + error);
        BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_DIS_ERROR, json);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private: JSON Utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the JSON representation of a non-null string value.
     * Escapes {@code "} and {@code \} for safe embedding in JSON.
     *
     * @param value A non-null string.
     * @return Quoted, escaped JSON string (e.g., {@code "Polar"}).
     */
    private static String jsonString(String value) {
        // Escape backslashes first, then double quotes (order matters).
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    /**
     * Returns {@code "value"} if non-null, or {@code null} (JSON literal) if null.
     *
     * @param value A nullable string.
     * @return Quoted JSON string or the literal {@code null} token.
     */
    private static String jsonStringOrNull(String value) {
        return (value != null) ? jsonString(value) : "null";
    }
}
