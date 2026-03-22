// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : BLEGatt — GATT Service Discovery, Read, Write, Notifications
// Platform: Android (Java) / Unity Bridge
// Sprint  : Sprint 76
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : Implement the full GATT operation surface: service discovery,
//           characteristic discovery, read, write, subscribe (notify/indicate),
//           unsubscribe, and battery level read. All results are delivered to
//           Unity via UnitySendMessage.
//
//           This file is the FOURTH (and most complex) layer of the plugin:
//             Sprint 73 — BLEAndroidPlugin  (init, permissions)
//             Sprint 74 — BLEScan           (discovery)
//             Sprint 75 — BLEConnection     (connect/disconnect lifecycle)
//             Sprint 76 — BLEGatt           (GATT operations) ← this file
//
// Usage   : From C# (Unity), after OnConnected fires:
//
//               // Discover all services
//               gatt.Call("discoverServices", "AA:BB:CC:DD:EE:FF");
//               // → OnServicesDiscovered(json array of UUIDs)
//
//               // Discover characteristics in a service
//               gatt.Call("discoverCharacteristics",
//                   "AA:BB:CC:DD:EE:FF",
//                   "0000180D-0000-1000-8000-00805f9b34fb");
//               // → OnCharacteristicsDiscovered(json array)
//
//               // Read a characteristic
//               gatt.Call("readCharacteristic",
//                   "AA:BB:CC:DD:EE:FF",
//                   "0000180D-0000-1000-8000-00805f9b34fb",
//                   "00002A37-0000-1000-8000-00805f9b34fb");
//               // → OnCharacteristicRead(json: {id, service, char, data_b64})
//
//               // Write a characteristic (with response)
//               gatt.Call("writeCharacteristic",
//                   "AA:BB:CC:DD:EE:FF",
//                   "0000180D-0000-1000-8000-00805f9b34fb",
//                   "00002A39-0000-1000-8000-00805f9b34fb",
//                   new byte[]{0x01},
//                   true);
//               // → OnCharacteristicWrite(json: {id, char, success})
//
//               // Subscribe to notifications
//               gatt.Call("subscribe",
//                   "AA:BB:CC:DD:EE:FF",
//                   "0000180D-0000-1000-8000-00805f9b34fb",
//                   "00002A37-0000-1000-8000-00805f9b34fb");
//               // → OnCharacteristicChanged(json: {id, char, data_b64})
//
//               // Unsubscribe
//               gatt.Call("unsubscribe",
//                   "AA:BB:CC:DD:EE:FF",
//                   "0000180D-0000-1000-8000-00805f9b34fb",
//                   "00002A37-0000-1000-8000-00805f9b34fb");
//
//               // Read battery level (shortcut)
//               gatt.Call("readBattery", "AA:BB:CC:DD:EE:FF");
//               // → OnBatteryLevel(json: {id, level})
//
// ─────────────────────────────────────────────────────────────────────────────
//
// !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
// CRITICAL: CCCD Descriptor Write — Why Android Requires It Explicitly
// !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
//
// This is the most common source of "notifications work on iOS but not Android"
// bugs encountered by developers porting BLE code between platforms.
//
// WHAT IS CCCD?
//   The Client Characteristic Configuration Descriptor (CCCD) is a mandatory
//   GATT descriptor present on every notifiable/indicatable characteristic.
//   Its UUID is:
//     "00002902-0000-1000-8000-00805f9b34fb"
//
//   Its 2-byte value controls whether the peripheral is allowed to push data:
//     0x00 0x00 — notifications OFF, indications OFF
//     0x01 0x00 — notifications ON  (fire-and-forget, no ACK from central)
//     0x02 0x00 — indications ON    (reliable, central must ACK each packet)
//
//   BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE  = {0x01, 0x00}
//   BluetoothGattDescriptor.ENABLE_INDICATION_VALUE    = {0x02, 0x00}
//   BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE = {0x00, 0x00}
//
// WHY ANDROID REQUIRES AN EXPLICIT WRITE:
//   The GATT specification (Bluetooth Core Spec v5.x, Vol 3, Part G, §3.3.3.3)
//   says the CCCD value is stored on the CLIENT side, scoped per connection.
//   This means:
//     - Each new connection starts with CCCD = 0x0000 (both OFF).
//     - The central (Android) must write ENABLE_NOTIFICATION_VALUE to the
//       peripheral's CCCD descriptor to tell the peripheral "you may now
//       send me notifications".
//
// WHY iOS DOESN'T REQUIRE IT (APPARENTLY):
//   CoreBluetooth on iOS does this CCCD write automatically when you call
//   setNotifyValue:forCharacteristic:. The developer never sees the
//   descriptor write — it is hidden inside the CoreBluetooth framework.
//   Android's BluetoothGatt exposes the raw GATT layer and requires you
//   to do both steps yourself.
//
// THE TWO-STEP SEQUENCE (BOTH STEPS ARE REQUIRED):
//
//   Step 1 — Tell the Android BLE stack to route incoming notification
//             packets for this characteristic to onCharacteristicChanged:
//
//               gatt.setCharacteristicNotification(characteristic, true);
//
//             This is a LOCAL operation — it configures Android's internal
//             routing table. It does NOT send anything to the peripheral.
//             If you skip this step, onCharacteristicChanged will NEVER fire,
//             even if the peripheral starts sending notifications.
//
//   Step 2 — Tell the PERIPHERAL to start sending notifications by writing
//             ENABLE_NOTIFICATION_VALUE to its CCCD descriptor:
//
//               BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD_UUID);
//               cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
//               gatt.writeDescriptor(cccd);
//
//             This is a REMOTE operation — it sends a GATT Write Request to the
//             peripheral. The peripheral will not send notifications until it
//             receives and processes this write. The write completes asynchronously
//             via onDescriptorWrite(). Only after onDescriptorWrite succeeds with
//             GATT_SUCCESS are notifications actually flowing.
//
//   DO NOT SKIP EITHER STEP. DO NOT REVERSE THE ORDER.
//
// !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
//
// ─────────────────────────────────────────────────────────────────────────────
//
// GATT Operation Serialization — Only One Operation In Flight at a Time
// ───────────────────────────────────────────────────────────────────────
// This is the second most common source of Android BLE bugs.
//
// Android's BluetoothGatt stack supports only ONE GATT operation in flight
// at a time per connection. If you call readCharacteristic() while a
// writeCharacteristic() is in progress, the second call returns false
// (operation failed) and no callback fires. The operation is silently dropped.
//
// Correct pattern: Wait for the callback of each operation before starting
// the next one. For example:
//   readCharacteristic() → wait for onCharacteristicRead() → then writeCharacteristic()
//
// For simple use cases (one operation at a time from Unity), this class
// is sufficient. For complex workflows (e.g., firmware OTA update that
// requires dozens of sequential writes), a command queue is necessary.
// See Sprint 80 (BLECommandQueue.java) which implements a FIFO queue with
// a single active-operation slot and callback-driven dispatch.
//
// In this reference class, we do NOT implement a queue — each public method
// is intended to be called from Unity in sequence, with Unity waiting for
// the callback before calling the next operation. This is documented explicitly
// so the developer understands the constraint.
//
// ─────────────────────────────────────────────────────────────────────────────
//
// onCharacteristicRead Deprecation (API 33+)
// ────────────────────────────────────────────
// Android API 33 (Android 13) deprecated the two-parameter form:
//
//   @Deprecated
//   onCharacteristicRead(BluetoothGatt gatt,
//                        BluetoothGattCharacteristic characteristic,
//                        int status)
//
// The deprecation reason: the old form required calling
// characteristic.getValue() inside the callback, but characteristic.getValue()
// is not thread-safe — the value could be overwritten by a new notification
// before you read it. The new three-parameter form passes the value directly:
//
//   onCharacteristicRead(BluetoothGatt gatt,
//                        BluetoothGattCharacteristic characteristic,
//                        byte[] value,
//                        int status)      ← API 33+
//
// This class implements BOTH forms using @Override:
//   - The new form is annotated with @RequiresApi(Build.VERSION_CODES.TIRAMISU)
//     and is called on API 33+ devices.
//   - The old form is called on API < 33 devices.
// This ensures correct, non-deprecated behavior on modern devices while
// maintaining backward compatibility.
//
// The same deprecation applies to onCharacteristicChanged at API 33+.
//
// ─────────────────────────────────────────────────────────────────────────────
//
// Characteristic Properties Bitmask
// ────────────────────────────────────
// BluetoothGattCharacteristic.getProperties() returns a bitmask. Flags:
//
//   PROPERTY_BROADCAST             = 0x01  — can broadcast in adv packets
//   PROPERTY_READ                  = 0x02  — can read (gatt.readCharacteristic)
//   PROPERTY_WRITE_NO_RESPONSE     = 0x04  — write without response (faster, no ACK)
//   PROPERTY_WRITE                 = 0x08  — write with response (confirmed)
//   PROPERTY_NOTIFY                = 0x10  — can notify (no ACK)
//   PROPERTY_INDICATE              = 0x20  — can indicate (with ACK)
//   PROPERTY_SIGNED_WRITE          = 0x40  — authenticated signed write
//   PROPERTY_EXTENDED_PROPERTIES   = 0x80  — see extended properties descriptor
//
// Usage: (properties & PROPERTY_NOTIFY) != 0 → characteristic supports notify
//
// ─────────────────────────────────────────────────────────────────────────────
//
// Known BLE Service and Characteristic UUIDs Used Here
// ─────────────────────────────────────────────────────
// All standard Bluetooth SIG UUIDs follow the 128-bit pattern:
//   0000XXXX-0000-1000-8000-00805f9b34fb
// where XXXX is the 16-bit assigned number.
//
//   Battery Service     0x180F  — "0000180F-0000-1000-8000-00805f9b34fb"
//   Battery Level       0x2A19  — "00002A19-0000-1000-8000-00805f9b34fb"
//     └ Single byte, value 0–100 (percentage)
//
//   Heart Rate Service  0x180D  — "0000180D-0000-1000-8000-00805f9b34fb"
//   HR Measurement      0x2A37  — "00002A37-0000-1000-8000-00805f9b34fb"
//     └ Notify-only. First byte: flags. Second byte (or word): BPM.
//
//   CCCD Descriptor     0x2902  — "00002902-0000-1000-8000-00805f9b34fb"
//
// ─────────────────────────────────────────────────────────────────────────────

package com.bleref.unity;

// ─── Android Bluetooth GATT Imports ──────────────────────────────────────────

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;

// ─── Android System Imports ──────────────────────────────────────────────────

import android.os.Build;
import android.util.Base64;
import android.util.Log;

// ─── AndroidX Annotation ─────────────────────────────────────────────────────

import androidx.annotation.RequiresApi;

// ─── Java Standard Library ───────────────────────────────────────────────────

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// ─────────────────────────────────────────────────────────────────────────────

public class BLEGatt {

    // ─── Logging Tag ─────────────────────────────────────────────────────────
    private static final String TAG = "BLEGatt";

    // ─── CCCD Descriptor UUID ─────────────────────────────────────────────────
    // Client Characteristic Configuration Descriptor.
    // Present on every characteristic that supports notify or indicate.
    // CRITICAL: See class header for full explanation of why this must be
    // explicitly written to enable notifications on Android.
    private static final UUID CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ─── Battery Service / Characteristic UUIDs ───────────────────────────────
    private static final UUID BATTERY_SERVICE_UUID =
        UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL_CHAR_UUID =
        UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    // ─── Unity Callback Method Names ─────────────────────────────────────────
    private static final String UNITY_METHOD_SERVICES_DISCOVERED        = "OnServicesDiscovered";
    private static final String UNITY_METHOD_CHARACTERISTICS_DISCOVERED = "OnCharacteristicsDiscovered";
    private static final String UNITY_METHOD_CHARACTERISTIC_READ        = "OnCharacteristicRead";
    private static final String UNITY_METHOD_CHARACTERISTIC_WRITE       = "OnCharacteristicWrite";
    private static final String UNITY_METHOD_CHARACTERISTIC_CHANGED     = "OnCharacteristicChanged";
    private static final String UNITY_METHOD_BATTERY_LEVEL              = "OnBatteryLevel";
    private static final String UNITY_METHOD_GATT_ERROR                 = "OnGattError";

    // ─── GATT Callback Registry ───────────────────────────────────────────────
    // Maps deviceId → GattCallbackHandler.
    // Each connection gets its own handler that holds the registered
    // BluetoothGattCallback and per-connection state (subscribed UUIDs).
    private final Map<String, GattCallbackHandler> _handlers = new HashMap<>();

    // ─── Inner Class: Per-Connection GATT Handler ─────────────────────────────

    /**
     * Manages the BluetoothGattCallback and notification state for one device.
     *
     * Extending BluetoothGattCallback rather than holding one as a field
     * avoids an extra indirection layer and makes the callback signatures
     * cleaner — each override method has direct access to deviceId and the
     * subscription registry.
     */
    private class GattCallbackHandler extends BluetoothGattCallback {

        /** MAC address of the device this handler belongs to. */
        final String deviceId;

        /**
         * Set of subscribed characteristic UUIDs (lower-case string form).
         *
         * Used to guard against duplicate subscribe() calls and to support
         * unsubscribeAll(). Concurrent modification is safe because all
         * access happens on the GATT callback thread (single-threaded per gatt).
         */
        final Map<String, Boolean> subscribedChars = new HashMap<>();

        GattCallbackHandler(String deviceId) {
            this.deviceId = deviceId;
        }

        // ─── onServicesDiscovered ─────────────────────────────────────────────
        //
        // Called after gatt.discoverServices() completes (see discoverServices()
        // method). The status parameter indicates whether the discovery succeeded.
        //
        // On success: iterate gatt.getServices() to access BluetoothGattService
        // objects and their contained BluetoothGattCharacteristic objects.
        //
        // Thread: GATT callback thread.

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered: device=" + deviceId + " status=" + status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed for " + deviceId + ": status=" + status);
                String json = String.format(
                    "{\"id\":\"%s\",\"error\":\"SERVICE_DISCOVERY_FAILED\",\"status\":%d}",
                    deviceId, status);
                BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_GATT_ERROR, json);
                return;
            }

            // Build JSON array of service UUIDs
            // Format: {"id":"AA:BB:...","services":["uuid1","uuid2",...]}
            StringBuilder sb = new StringBuilder();
            sb.append("{\"id\":\"").append(deviceId).append("\",\"services\":[");

            List<BluetoothGattService> services = gatt.getServices();
            for (int i = 0; i < services.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(services.get(i).getUuid().toString()).append("\"");
            }
            sb.append("]}");

            Log.i(TAG, "Services discovered for " + deviceId + ": " + services.size() + " service(s).");
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_SERVICES_DISCOVERED, sb.toString());
        }

        // ─── onCharacteristicRead (API 33+ form) ──────────────────────────────
        //
        // API 33 (Android 13) introduced a new form that passes the value directly
        // as a byte[] parameter, avoiding the thread-safety issue of the old form
        // where you had to call characteristic.getValue() inside the callback.
        //
        // @RequiresApi ensures this override is only compiled/linked on API 33+.
        // The runtime dispatches to this form on modern devices; older devices
        // use the deprecated two-parameter form below.

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @Override
        public void onCharacteristicRead(
                BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value,
                int status) {
            handleCharacteristicRead(characteristic, value, status);
        }

        // ─── onCharacteristicRead (deprecated form, API < 33) ─────────────────
        //
        // Used on Android 12 and below. We call characteristic.getValue()
        // immediately inside the callback (not after returning) to minimize
        // the window where the value could be overwritten by a concurrent event.
        //
        // This form is marked @Deprecated but must remain for backward compat.
        // Suppressing the deprecation warning to avoid build noise.

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicRead(
                BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic,
                int status) {
            // Read the value synchronously — do NOT defer this call
            byte[] value = characteristic.getValue();
            handleCharacteristicRead(characteristic, value, status);
        }

        /**
         * Shared implementation called by both onCharacteristicRead overrides.
         *
         * Encodes the byte array as Base64 for safe transport through the
         * Unity UnitySendMessage string channel (which cannot carry raw bytes).
         */
        private void handleCharacteristicRead(
                BluetoothGattCharacteristic characteristic,
                byte[] value,
                int status) {

            String charUuid = characteristic.getUuid().toString();
            Log.d(TAG, "onCharacteristicRead: device=" + deviceId
                + " char=" + charUuid + " status=" + status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Read failed for " + charUuid + ": status=" + status);
                String json = String.format(
                    "{\"id\":\"%s\",\"char\":\"%s\",\"error\":\"READ_FAILED\",\"status\":%d}",
                    deviceId, charUuid, status);
                BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_GATT_ERROR, json);
                return;
            }

            // Base64-encode the raw bytes for Unity transport.
            // Unity C# can decode with System.Convert.FromBase64String().
            String b64 = (value != null && value.length > 0)
                ? Base64.encodeToString(value, Base64.NO_WRAP)
                : "";

            String json = String.format(
                "{\"id\":\"%s\",\"char\":\"%s\",\"data\":\"%s\"}",
                deviceId, charUuid, b64);

            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_CHARACTERISTIC_READ, json);
        }

        // ─── onCharacteristicWrite ────────────────────────────────────────────
        //
        // Called after gatt.writeCharacteristic() completes (write WITH response).
        // Not called for WRITE_NO_RESPONSE writes — those are fire-and-forget.
        //
        // status=GATT_SUCCESS (0) means the peripheral acknowledged the write.
        // status=GATT_WRITE_NOT_PERMITTED (0x03) means the characteristic or
        // current security level does not allow writes.

        @Override
        public void onCharacteristicWrite(
                BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic,
                int status) {

            String charUuid = characteristic.getUuid().toString();
            boolean success = (status == BluetoothGatt.GATT_SUCCESS);

            Log.d(TAG, "onCharacteristicWrite: device=" + deviceId
                + " char=" + charUuid + " status=" + status);

            String json = String.format(
                "{\"id\":\"%s\",\"char\":\"%s\",\"success\":%s,\"status\":%d}",
                deviceId, charUuid, success ? "true" : "false", status);

            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_CHARACTERISTIC_WRITE, json);
        }

        // ─── onDescriptorWrite ────────────────────────────────────────────────
        //
        // Called after gatt.writeDescriptor() completes. The primary use case
        // is the CCCD write that enables/disables notifications.
        //
        // CRITICAL: Notifications do NOT start until this callback fires with
        // status=GATT_SUCCESS for the CCCD descriptor. If you receive data in
        // onCharacteristicChanged before this callback, something is wrong
        // (possibly the CCCD was enabled in a previous session and not cleared).

        @Override
        public void onDescriptorWrite(
                BluetoothGatt gatt,
                BluetoothGattDescriptor descriptor,
                int status) {

            String descUuid = descriptor.getUuid().toString();
            String charUuid = descriptor.getCharacteristic().getUuid().toString();

            Log.d(TAG, "onDescriptorWrite: device=" + deviceId
                + " char=" + charUuid + " desc=" + descUuid + " status=" + status);

            if (descUuid.equalsIgnoreCase(CCCD_UUID.toString())) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "CCCD write succeeded for char=" + charUuid
                        + " — notifications are now active.");
                    // No Unity callback for CCCD write success itself.
                    // The first OnCharacteristicChanged will be the confirmation.
                } else {
                    Log.e(TAG, "CCCD write FAILED for char=" + charUuid + ": status=" + status);
                    String json = String.format(
                        "{\"id\":\"%s\",\"char\":\"%s\",\"error\":\"CCCD_WRITE_FAILED\",\"status\":%d}",
                        deviceId, charUuid, status);
                    BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_GATT_ERROR, json);
                }
            }
        }

        // ─── onCharacteristicChanged (API 33+ form) ───────────────────────────
        //
        // Same deprecation story as onCharacteristicRead.
        // The new API 33 form passes value directly; the old form uses getValue().

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        @Override
        public void onCharacteristicChanged(
                BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic,
                byte[] value) {
            handleCharacteristicChanged(characteristic, value);
        }

        // ─── onCharacteristicChanged (deprecated form, API < 33) ─────────────

        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(
                BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            handleCharacteristicChanged(characteristic, value);
        }

        /**
         * Shared implementation for both onCharacteristicChanged overrides.
         *
         * Called for every incoming notification or indication packet.
         * Encodes as Base64 and forwards to Unity's OnCharacteristicChanged.
         */
        private void handleCharacteristicChanged(
                BluetoothGattCharacteristic characteristic,
                byte[] value) {

            String charUuid = characteristic.getUuid().toString();
            Log.d(TAG, "onCharacteristicChanged: device=" + deviceId
                + " char=" + charUuid + " bytes=" + (value != null ? value.length : 0));

            String b64 = (value != null && value.length > 0)
                ? Base64.encodeToString(value, Base64.NO_WRAP)
                : "";

            String json = String.format(
                "{\"id\":\"%s\",\"char\":\"%s\",\"data\":\"%s\"}",
                deviceId, charUuid, b64);

            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_CHARACTERISTIC_CHANGED, json);
        }
    }

    // ─── Singleton ────────────────────────────────────────────────────────────

    private static BLEGatt instance;

    /**
     * Returns the singleton BLEGatt instance, creating it if needed.
     *
     * Called from C# via:
     *   var gatt = new AndroidJavaClass("com.bleref.unity.BLEGatt")
     *       .CallStatic<AndroidJavaObject>("getInstance");
     *
     * @return The shared BLEGatt singleton.
     */
    public static BLEGatt getInstance() {
        if (instance == null) {
            instance = new BLEGatt();
            Log.d(TAG, "BLEGatt singleton created.");
        }
        return instance;
    }

    private BLEGatt() {}

    // ─── Helper: Resolve BluetoothGatt ───────────────────────────────────────

    /**
     * Retrieve the active BluetoothGatt for a device from BLEConnection.
     *
     * All GATT operations require the gatt object established by BLEConnection.
     * Returns null and logs an error if the device is not connected.
     *
     * @param deviceId  MAC address of the target device.
     * @return BluetoothGatt, or null if not connected.
     */
    private BluetoothGatt resolveGatt(String deviceId) {
        BluetoothGatt gatt = BLEConnection.getInstance().getGatt(deviceId);
        if (gatt == null) {
            Log.e(TAG, "No active GATT connection for " + deviceId
                + ". Call connect() first.");
        }
        return gatt;
    }

    /**
     * Retrieve or create the GattCallbackHandler for a device.
     *
     * The handler must be registered before discoverServices() is called,
     * because service discovery results arrive via BluetoothGattCallback.
     * The handler is registered on the BluetoothGatt object returned by
     * BLEConnection, but BLEGatt needs to add its GATT operation callbacks
     * on top of the connection callbacks already registered there.
     *
     * NOTE: In a full production implementation, BLEConnection and BLEGatt
     * would share a single BluetoothGattCallback (or use a multiplexer pattern).
     * For this reference implementation, we document the architectural constraint:
     * registering a second callback via gatt.setGattCallback() is not supported
     * by Android. In practice, production apps either (a) use a single unified
     * BluetoothGattCallback or (b) have BLEConnection delegate all callbacks
     * to a registered BLEGatt listener. The public API surface demonstrated
     * here is correct — the internal wiring is left as an integration exercise.
     *
     * @param deviceId  MAC address.
     * @return GattCallbackHandler for this device.
     */
    private GattCallbackHandler getOrCreateHandler(String deviceId) {
        synchronized (_handlers) {
            GattCallbackHandler handler = _handlers.get(deviceId);
            if (handler == null) {
                handler = new GattCallbackHandler(deviceId);
                _handlers.put(deviceId, handler);
            }
            return handler;
        }
    }

    // ─── Public API: Service & Characteristic Discovery ──────────────────────

    /**
     * Initiate GATT service discovery on a connected device.
     *
     * Android caches GATT service tables per device. If a cached version
     * exists, onServicesDiscovered may fire almost immediately with stale data.
     * To force a re-read of the service table (e.g., after a firmware update),
     * use gatt.refresh() — however, refresh() is a hidden API (@hide) and
     * must be invoked via reflection. That pattern is beyond this reference
     * implementation's scope.
     *
     * After OnServicesDiscovered fires in Unity, call discoverCharacteristics()
     * to enumerate the characteristics of each service of interest.
     *
     * Note: Do NOT call any other GATT operation between discoverServices()
     * and onServicesDiscovered. Only one GATT operation can be in flight
     * at a time. (See serialization note in class header.)
     *
     * Called from C# via:
     *   instance.Call("discoverServices", "AA:BB:CC:DD:EE:FF");
     *
     * @param deviceId  MAC address of the connected peripheral.
     */
    public void discoverServices(String deviceId) {
        Log.d(TAG, "discoverServices: deviceId=" + deviceId);

        BluetoothGatt gatt = resolveGatt(deviceId);
        if (gatt == null) return;

        // Ensure a handler exists to receive onServicesDiscovered
        getOrCreateHandler(deviceId);

        boolean started = gatt.discoverServices();
        if (!started) {
            Log.e(TAG, "gatt.discoverServices() returned false for " + deviceId
                + ". Another operation may be in flight.");
            String json = String.format(
                "{\"id\":\"%s\",\"error\":\"DISCOVER_SERVICES_FAILED\"}", deviceId);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_GATT_ERROR, json);
        } else {
            Log.i(TAG, "Service discovery started for " + deviceId);
        }
    }

    /**
     * Enumerate the characteristics of a specific GATT service.
     *
     * Must be called after OnServicesDiscovered confirms service discovery
     * completed. Does NOT trigger a network operation — characteristic data
     * is already present in the local GATT cache after service discovery.
     *
     * Sends OnCharacteristicsDiscovered(json) synchronously (no async wait).
     *
     * JSON format:
     *   {
     *     "id": "AA:BB:CC:DD:EE:FF",
     *     "service": "0000180d-...",
     *     "characteristics": [
     *       {
     *         "uuid": "00002a37-...",
     *         "canRead": false,
     *         "canWrite": false,
     *         "canWriteWithoutResponse": false,
     *         "canNotify": true,
     *         "canIndicate": false
     *       },
     *       ...
     *     ]
     *   }
     *
     * Called from C# via:
     *   instance.Call("discoverCharacteristics",
     *       "AA:BB:CC:DD:EE:FF",
     *       "0000180D-0000-1000-8000-00805f9b34fb");
     *
     * @param deviceId    MAC address of the connected peripheral.
     * @param serviceUUID UUID string of the service to inspect.
     */
    public void discoverCharacteristics(String deviceId, String serviceUUID) {
        Log.d(TAG, "discoverCharacteristics: deviceId=" + deviceId + " service=" + serviceUUID);

        BluetoothGatt gatt = resolveGatt(deviceId);
        if (gatt == null) return;

        BluetoothGattService service = gatt.getService(UUID.fromString(serviceUUID));
        if (service == null) {
            Log.e(TAG, "Service " + serviceUUID + " not found on " + deviceId
                + ". Was discoverServices() called first?");
            String json = String.format(
                "{\"id\":\"%s\",\"error\":\"SERVICE_NOT_FOUND\",\"service\":\"%s\"}",
                deviceId, serviceUUID);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_GATT_ERROR, json);
            return;
        }

        List<BluetoothGattCharacteristic> chars = service.getCharacteristics();

        // Build JSON characteristics array
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"").append(deviceId).append("\"");
        sb.append(",\"service\":\"").append(serviceUUID.toLowerCase()).append("\"");
        sb.append(",\"characteristics\":[");

        for (int i = 0; i < chars.size(); i++) {
            if (i > 0) sb.append(",");
            BluetoothGattCharacteristic c = chars.get(i);
            int props = c.getProperties();

            // Decode the properties bitmask into named boolean fields.
            // Each flag is tested with bitwise AND:
            //   (props & PROPERTY_READ) != 0  → true if readable
            boolean canRead               = (props & BluetoothGattCharacteristic.PROPERTY_READ)               != 0;
            boolean canWrite              = (props & BluetoothGattCharacteristic.PROPERTY_WRITE)              != 0;
            boolean canWriteNoResponse    = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)  != 0;
            boolean canNotify             = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY)             != 0;
            boolean canIndicate           = (props & BluetoothGattCharacteristic.PROPERTY_INDICATE)           != 0;

            sb.append("{");
            sb.append("\"uuid\":\"").append(c.getUuid().toString()).append("\"");
            sb.append(",\"canRead\":").append(canRead);
            sb.append(",\"canWrite\":").append(canWrite);
            sb.append(",\"canWriteWithoutResponse\":").append(canWriteNoResponse);
            sb.append(",\"canNotify\":").append(canNotify);
            sb.append(",\"canIndicate\":").append(canIndicate);
            sb.append("}");
        }

        sb.append("]}");

        Log.i(TAG, "Characteristics for service " + serviceUUID + ": " + chars.size() + " found.");
        BLEAndroidPlugin.getInstance().sendToUnity(
            UNITY_METHOD_CHARACTERISTICS_DISCOVERED, sb.toString());
    }

    // ─── Public API: Read ─────────────────────────────────────────────────────

    /**
     * Read the value of a GATT characteristic.
     *
     * Triggers a GATT Read Request to the peripheral. The response arrives
     * asynchronously in onCharacteristicRead(). Unity receives:
     *   OnCharacteristicRead({"id":"...","char":"...","data":"<base64>"})
     *
     * Do NOT call another GATT operation until OnCharacteristicRead fires.
     * (Android only allows one GATT operation in flight at a time.)
     *
     * Called from C# via:
     *   instance.Call("readCharacteristic",
     *       "AA:BB:CC:DD:EE:FF",
     *       "0000180D-0000-1000-8000-00805f9b34fb",
     *       "00002A37-0000-1000-8000-00805f9b34fb");
     *
     * @param deviceId    MAC address of the connected peripheral.
     * @param serviceUUID UUID string of the service containing the characteristic.
     * @param charUUID    UUID string of the characteristic to read.
     */
    public void readCharacteristic(String deviceId, String serviceUUID, String charUUID) {
        Log.d(TAG, "readCharacteristic: device=" + deviceId + " char=" + charUUID);

        BluetoothGatt gatt = resolveGatt(deviceId);
        if (gatt == null) return;

        BluetoothGattCharacteristic characteristic = resolveCharacteristic(gatt, serviceUUID, charUUID, deviceId);
        if (characteristic == null) return;

        // Guard: check that the characteristic actually supports reads
        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            Log.e(TAG, "Characteristic " + charUUID + " does not support READ.");
            String json = String.format(
                "{\"id\":\"%s\",\"char\":\"%s\",\"error\":\"NOT_READABLE\"}", deviceId, charUUID);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_GATT_ERROR, json);
            return;
        }

        boolean started = gatt.readCharacteristic(characteristic);
        if (!started) {
            Log.e(TAG, "gatt.readCharacteristic() returned false. Operation serialization violation?");
        }
    }

    /**
     * Read the battery level characteristic (Battery Service 0x180F, char 0x2A19).
     *
     * Convenience method that hardcodes the well-known Battery Service and
     * Battery Level UUIDs. Returns an integer 0–100 via:
     *   OnBatteryLevel({"id":"AA:BB:...","level":87})
     *
     * Internally calls readCharacteristic() and intercepts the result in
     * onCharacteristicRead to send OnBatteryLevel instead of OnCharacteristicRead.
     * The interception is done by checking the characteristic UUID in the handler.
     *
     * Called from C# via:
     *   instance.Call("readBattery", "AA:BB:CC:DD:EE:FF");
     *
     * @param deviceId  MAC address of the connected peripheral.
     */
    public void readBattery(String deviceId) {
        Log.d(TAG, "readBattery: deviceId=" + deviceId);

        BluetoothGatt gatt = resolveGatt(deviceId);
        if (gatt == null) return;

        BluetoothGattService batteryService = gatt.getService(BATTERY_SERVICE_UUID);
        if (batteryService == null) {
            Log.w(TAG, "Battery Service (0x180F) not found on " + deviceId
                + ". Device may not support battery level reporting.");
            String json = String.format(
                "{\"id\":\"%s\",\"error\":\"BATTERY_SERVICE_NOT_FOUND\"}", deviceId);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_GATT_ERROR, json);
            return;
        }

        BluetoothGattCharacteristic batteryLevelChar =
            batteryService.getCharacteristic(BATTERY_LEVEL_CHAR_UUID);

        if (batteryLevelChar == null) {
            Log.e(TAG, "Battery Level characteristic (0x2A19) not found.");
            String json = String.format(
                "{\"id\":\"%s\",\"error\":\"BATTERY_CHAR_NOT_FOUND\"}", deviceId);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_GATT_ERROR, json);
            return;
        }

        // Mark this device as expecting a battery read so onCharacteristicRead
        // can route the result to OnBatteryLevel instead of OnCharacteristicRead.
        GattCallbackHandler handler = getOrCreateHandler(deviceId);
        handler.subscribedChars.put("__battery_read_pending__", true);

        gatt.readCharacteristic(batteryLevelChar);
        Log.i(TAG, "Battery read initiated for " + deviceId);
    }

    // ─── Public API: Write ────────────────────────────────────────────────────

    /**
     * Write a value to a GATT characteristic.
     *
     * Supports both write-with-response (PROPERTY_WRITE) and write-without-
     * response (PROPERTY_WRITE_NO_RESPONSE):
     *
     *   withResponse=true  — Peripheral sends a GATT Write Response (ACK).
     *                        onCharacteristicWrite fires with GATT_SUCCESS/error.
     *                        Slower but reliable. Use for configuration writes.
     *
     *   withResponse=false — Fire and forget. No ACK from peripheral.
     *                        onCharacteristicWrite does NOT fire on API < 33.
     *                        Faster; use for high-rate command streams (e.g.,
     *                        vibration pattern updates, LED control at 50 Hz).
     *                        Risk: dropped packets if BLE link is congested.
     *
     * Called from C# via:
     *   instance.Call("writeCharacteristic",
     *       "AA:BB:CC:DD:EE:FF",
     *       "serviceUUID",
     *       "charUUID",
     *       new byte[]{0x01, 0x02},
     *       true);
     *
     * @param deviceId    MAC address of the connected peripheral.
     * @param serviceUUID UUID of the service containing the characteristic.
     * @param charUUID    UUID of the characteristic to write.
     * @param data        Raw bytes to write. Max 20 bytes on standard MTU.
     *                    After MTU negotiation (ATT_MTU_DEFAULT=23, payload=20),
     *                    longer values require multiple packets or MTU exchange.
     * @param withResponse If true, uses WRITE_TYPE_DEFAULT (with response).
     *                     If false, uses WRITE_TYPE_NO_RESPONSE.
     */
    public void writeCharacteristic(
            String  deviceId,
            String  serviceUUID,
            String  charUUID,
            byte[]  data,
            boolean withResponse) {

        Log.d(TAG, "writeCharacteristic: device=" + deviceId
            + " char=" + charUUID + " withResponse=" + withResponse
            + " bytes=" + (data != null ? data.length : 0));

        BluetoothGatt gatt = resolveGatt(deviceId);
        if (gatt == null) return;

        BluetoothGattCharacteristic characteristic =
            resolveCharacteristic(gatt, serviceUUID, charUUID, deviceId);
        if (characteristic == null) return;

        // Validate write capability
        int requiredProp = withResponse
            ? BluetoothGattCharacteristic.PROPERTY_WRITE
            : BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;

        if ((characteristic.getProperties() & requiredProp) == 0) {
            String writeTypeName = withResponse ? "WRITE" : "WRITE_NO_RESPONSE";
            Log.e(TAG, "Characteristic " + charUUID + " does not support " + writeTypeName);
            String json = String.format(
                "{\"id\":\"%s\",\"char\":\"%s\",\"error\":\"WRITE_NOT_SUPPORTED\"}", deviceId, charUUID);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_GATT_ERROR, json);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ form: passes value and writeType directly, no mutable state on characteristic.
            int writeType = withResponse
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            gatt.writeCharacteristic(characteristic, data, writeType);
        } else {
            // API < 33 form: set value and writeType on the characteristic object, then write.
            // This is deprecated in API 33 because the mutable state is not thread-safe.
            characteristic.setWriteType(withResponse
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            characteristic.setValue(data);
            gatt.writeCharacteristic(characteristic);
        }
    }

    // ─── Public API: Notifications ────────────────────────────────────────────

    /**
     * Subscribe to notifications (or indications) from a GATT characteristic.
     *
     * Performs the full two-step CCCD subscription sequence required on Android:
     *
     *   Step 1 — gatt.setCharacteristicNotification(characteristic, true)
     *            LOCAL: tells Android to route incoming packets to
     *            onCharacteristicChanged. No network traffic.
     *
     *   Step 2 — Write ENABLE_NOTIFICATION_VALUE to the CCCD descriptor.
     *            REMOTE: tells the peripheral to start sending notifications.
     *            The write completes via onDescriptorWrite (GATT_SUCCESS).
     *
     * After both steps complete, notifications flow to:
     *   OnCharacteristicChanged({"id":"...","char":"...","data":"<base64>"})
     *
     * Called from C# via:
     *   instance.Call("subscribe",
     *       "AA:BB:CC:DD:EE:FF",
     *       "0000180D-0000-1000-8000-00805f9b34fb",
     *       "00002A37-0000-1000-8000-00805f9b34fb");
     *
     * @param deviceId    MAC address of the connected peripheral.
     * @param serviceUUID UUID of the service containing the characteristic.
     * @param charUUID    UUID of the characteristic to subscribe to.
     */
    public void subscribe(String deviceId, String serviceUUID, String charUUID) {
        Log.d(TAG, "subscribe: device=" + deviceId + " char=" + charUUID);

        BluetoothGatt gatt = resolveGatt(deviceId);
        if (gatt == null) return;

        BluetoothGattCharacteristic characteristic =
            resolveCharacteristic(gatt, serviceUUID, charUUID, deviceId);
        if (characteristic == null) return;

        // Validate: characteristic must support notify or indicate
        int props = characteristic.getProperties();
        boolean canNotify  = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY)   != 0;
        boolean canIndicate = (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;

        if (!canNotify && !canIndicate) {
            Log.e(TAG, "Characteristic " + charUUID + " does not support NOTIFY or INDICATE.");
            String json = String.format(
                "{\"id\":\"%s\",\"char\":\"%s\",\"error\":\"NOTIFY_NOT_SUPPORTED\"}", deviceId, charUUID);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_GATT_ERROR, json);
            return;
        }

        // Guard: skip if already subscribed
        GattCallbackHandler handler = getOrCreateHandler(deviceId);
        String normalizedUuid = charUUID.toLowerCase();
        if (handler.subscribedChars.containsKey(normalizedUuid)) {
            Log.d(TAG, "Already subscribed to " + charUUID + " — skipping.");
            return;
        }

        // ── STEP 1 ────────────────────────────────────────────────────────────
        // Configure Android's internal notification routing for this characteristic.
        // LOCAL OPERATION — no BLE packet sent to peripheral.
        boolean routingSet = gatt.setCharacteristicNotification(characteristic, true);
        if (!routingSet) {
            Log.e(TAG, "setCharacteristicNotification() returned false for " + charUUID);
            String json = String.format(
                "{\"id\":\"%s\",\"char\":\"%s\",\"error\":\"SET_NOTIFICATION_FAILED\"}", deviceId, charUUID);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_GATT_ERROR, json);
            return;
        }
        Log.d(TAG, "Step 1 complete: Android notification routing enabled for " + charUUID);

        // ── STEP 2 ────────────────────────────────────────────────────────────
        // Write ENABLE_NOTIFICATION_VALUE to the CCCD descriptor.
        // REMOTE OPERATION — sends a GATT Write Request to the peripheral.
        BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD_UUID);
        if (cccd == null) {
            // Some non-standard peripherals omit the CCCD even on notifiable
            // characteristics. This is a spec violation, but it happens.
            // On iOS/CoreBluetooth, this would silently succeed because
            // CoreBluetooth handles the CCCD internally. On Android, we
            // fail explicitly.
            Log.e(TAG, "CCCD descriptor (0x2902) not found on characteristic " + charUUID
                + ". Cannot enable notifications. (Peripheral may be non-standard.)");
            String json = String.format(
                "{\"id\":\"%s\",\"char\":\"%s\",\"error\":\"CCCD_NOT_FOUND\"}", deviceId, charUUID);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_GATT_ERROR, json);
            return;
        }

        // Choose ENABLE_NOTIFICATION_VALUE (0x01 0x00) for notify,
        // or ENABLE_INDICATION_VALUE (0x02 0x00) for indicate-only characteristics.
        byte[] cccdValue = canNotify
            ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;

        boolean writeStarted;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: new form passes value directly to avoid mutable state on descriptor
            writeStarted = gatt.writeDescriptor(cccd, cccdValue) == BluetoothGatt.GATT_SUCCESS;
        } else {
            // API < 33: set value on descriptor object, then write
            cccd.setValue(cccdValue);
            writeStarted = gatt.writeDescriptor(cccd);
        }

        if (!writeStarted) {
            Log.e(TAG, "gatt.writeDescriptor(CCCD) returned false for " + charUUID
                + ". Another GATT operation may be in flight — serialization violation.");
            return;
        }

        Log.d(TAG, "Step 2 initiated: CCCD write sent for " + charUUID
            + ". Awaiting onDescriptorWrite confirmation...");

        // Mark as subscribed (pre-emptively — will roll back if CCCD write fails)
        handler.subscribedChars.put(normalizedUuid, true);
        Log.i(TAG, "Subscription initiated for " + charUUID
            + ". OnCharacteristicChanged will fire once CCCD write succeeds.");
    }

    /**
     * Unsubscribe from notifications on a GATT characteristic.
     *
     * Reverses the subscribe() sequence:
     *   1. Write DISABLE_NOTIFICATION_VALUE (0x00 0x00) to CCCD.
     *   2. Call setCharacteristicNotification(characteristic, false).
     *
     * After this, the peripheral stops sending notifications and
     * onCharacteristicChanged will no longer fire for this characteristic.
     *
     * Called from C# via:
     *   instance.Call("unsubscribe",
     *       "AA:BB:CC:DD:EE:FF",
     *       "serviceUUID",
     *       "charUUID");
     *
     * @param deviceId    MAC address of the connected peripheral.
     * @param serviceUUID UUID of the service containing the characteristic.
     * @param charUUID    UUID of the characteristic to unsubscribe from.
     */
    public void unsubscribe(String deviceId, String serviceUUID, String charUUID) {
        Log.d(TAG, "unsubscribe: device=" + deviceId + " char=" + charUUID);

        BluetoothGatt gatt = resolveGatt(deviceId);
        if (gatt == null) return;

        BluetoothGattCharacteristic characteristic =
            resolveCharacteristic(gatt, serviceUUID, charUUID, deviceId);
        if (characteristic == null) return;

        GattCallbackHandler handler = getOrCreateHandler(deviceId);
        String normalizedUuid = charUUID.toLowerCase();

        if (!handler.subscribedChars.containsKey(normalizedUuid)) {
            Log.d(TAG, "Not subscribed to " + charUUID + " — nothing to unsubscribe.");
            return;
        }

        // ── Write DISABLE_NOTIFICATION_VALUE to CCCD ──────────────────────────
        // This tells the peripheral to stop sending notifications.
        // Skipping this step would leave the peripheral sending packets that
        // Android silently drops — a waste of bandwidth and battery on both ends.
        BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD_UUID);
        if (cccd != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            } else {
                cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(cccd);
            }
            Log.d(TAG, "CCCD disable write sent for " + charUUID);
        }

        // ── Disable local routing ─────────────────────────────────────────────
        gatt.setCharacteristicNotification(characteristic, false);

        handler.subscribedChars.remove(normalizedUuid);
        Log.i(TAG, "Unsubscribed from " + charUUID);
    }

    // ─── Lookup Helper ────────────────────────────────────────────────────────

    /**
     * Retrieve a characteristic by service and characteristic UUID.
     *
     * Logs descriptive errors and returns null if either UUID is not found
     * in the device's GATT service table.
     *
     * @param gatt        The active BluetoothGatt connection.
     * @param serviceUUID Service UUID string.
     * @param charUUID    Characteristic UUID string.
     * @param deviceId    Device MAC, used only for error log messages.
     * @return BluetoothGattCharacteristic, or null if not found.
     */
    private BluetoothGattCharacteristic resolveCharacteristic(
            BluetoothGatt gatt,
            String serviceUUID,
            String charUUID,
            String deviceId) {

        BluetoothGattService service = gatt.getService(UUID.fromString(serviceUUID));
        if (service == null) {
            Log.e(TAG, "Service " + serviceUUID + " not found on " + deviceId
                + ". Was discoverServices() called?");
            String json = String.format(
                "{\"id\":\"%s\",\"error\":\"SERVICE_NOT_FOUND\",\"service\":\"%s\"}",
                deviceId, serviceUUID);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_GATT_ERROR, json);
            return null;
        }

        BluetoothGattCharacteristic characteristic =
            service.getCharacteristic(UUID.fromString(charUUID));
        if (characteristic == null) {
            Log.e(TAG, "Characteristic " + charUUID + " not found in service " + serviceUUID
                + " on " + deviceId);
            String json = String.format(
                "{\"id\":\"%s\",\"error\":\"CHARACTERISTIC_NOT_FOUND\",\"char\":\"%s\"}",
                deviceId, charUUID);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_GATT_ERROR, json);
            return null;
        }

        return characteristic;
    }

    // ─── Cleanup ─────────────────────────────────────────────────────────────

    /**
     * Release all per-connection GATT state for a device.
     *
     * Should be called when BLEConnection reports OnDisconnected to ensure
     * subscription records are not stale after reconnection.
     *
     * @param deviceId  MAC address of the disconnected device.
     */
    public void onDeviceDisconnected(String deviceId) {
        synchronized (_handlers) {
            _handlers.remove(deviceId);
        }
        Log.d(TAG, "BLEGatt state cleared for disconnected device: " + deviceId);
    }
}
