// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : BLEPeripheral — Android BLE Peripheral Mode (Advertiser + GATT Server)
// Platform: Android (Java) / Unity Bridge
// Sprint  : Sprint 79
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : Enable the Android/Quest device to act as a BLE PERIPHERAL —
//           advertising services, hosting a GATT server, and pushing notifications
//           to connected centrals (iOS, Python/Bleak, another Android, Web BLE).
//
//           All previous modules (BLEScan, BLEConnection, BLEGatt) operate in
//           CENTRAL mode: Android is the initiator that scans and connects.
//           This module inverts the role: Android becomes the server that
//           waits for centrals to connect.
//
//           Use cases for Quest / Android as peripheral:
//             - A Quest 3 sending biometric data (heart rate from an attached
//               sensor) to a nearby iPhone running the monitoring app.
//             - A Quest acting as a BLE HID device (custom controller).
//             - A custom data-forwarding bridge: Quest receives data from a
//               Polar H10 (central role), then re-broadcasts it as a BLE
//               peripheral to other devices simultaneously.
//             - Peer-to-peer data exchange between two Android devices without
//               internet or Wi-Fi.
//
// Usage   : From C# (Unity):
//
//               // 1. Start advertising as a Heart Rate peripheral
//               peripheral.Call("startAdvertising",
//                   "0000180D-0000-1000-8000-00805f9b34fb",  // HR service UUID
//                   "QuestBLE");                               // device name
//               // → OnAdvertisingStarted("")
//
//               // 2. Add a notifiable characteristic to the server
//               peripheral.Call("addCharacteristic",
//                   "0000180D-0000-1000-8000-00805f9b34fb",  // service UUID
//                   "00002A37-0000-1000-8000-00805f9b34fb",  // char UUID (HR Measurement)
//                   3,    // PROPERTY_READ | PROPERTY_NOTIFY (1 | 2)
//                   1);   // PERMISSION_READ
//               // → OnCharacteristicAdded(json: {charUUID})
//
//               // 3. When a central connects: OnCentralConnected fires
//               // 4. Push data to all connected centrals
//               peripheral.Call("notifyClients",
//                   "00002A37-0000-1000-8000-00805f9b34fb",
//                   new byte[]{0x00, 0x48});  // HR format byte + 72 bpm
//               // → (no callback — fire and forget)
//
//               // 5. Stop advertising
//               peripheral.Call("stopAdvertising");
//               // → OnAdvertisingStopped("")
//
//           Unity receives callbacks:
//               OnAdvertisingStarted(String "")          — advertising is live
//               OnAdvertisingError(String errorCode)     — failed to start
//               OnAdvertisingStopped(String "")          — advertising stopped
//               OnCentralConnected(String json)          — a central connected
//               OnCentralDisconnected(String json)       — a central disconnected
//               OnCharacteristicAdded(String json)       — char registered in GATT server
//               OnCharacteristicWriteRequest(String json)— central wrote to a characteristic
//
//           JSON for OnCentralConnected / OnCentralDisconnected:
//               {"address":"AA:BB:CC:DD:EE:FF"}
//
//           JSON for OnCharacteristicWriteRequest:
//               {"charUUID":"00002A39-...", "data_b64":"AQI=", "address":"AA:BB:CC:DD:EE:FF"}
//
// Peripheral Mode Availability — Hardware Check Required
// ──────────────────────────────────────────────────────────────────────────────
//
// BLE peripheral mode (a.k.a. "multi-advertisement" or "BLE advertiser") is
// supported by:
//   - Android 5.0+ (API 21+) on devices with compatible hardware
//   - Meta Quest 2, 3, Pro (Android 10+, standard Qualcomm BT chips)
//   - Most flagship phones since 2015
//
// NOT available on:
//   - Web Bluetooth (Chrome) — browser cannot become a peripheral.
//     See Phase 03 (TypeScript/WebBLE): peripheral mode is explicitly noted
//     as unavailable with no workaround in the current Web Bluetooth spec.
//   - iOS — CoreBluetooth CBPeripheralManager exists but is restricted:
//     it works fine for custom apps but cannot impersonate standard profiles
//     when the app is backgrounded.
//   - Some low-end or older Android devices lacking the BT hardware feature.
//
// Always call:
//   boolean supported = bluetoothAdapter.isMultipleAdvertisementSupported();
// BEFORE calling startAdvertising(). Calling BluetoothLeAdvertiser.startAdvertising()
// on unsupported hardware crashes with a NullPointerException because
// bluetoothAdapter.getBluetoothLeAdvertiser() returns null.
//
// AdvertiseSettings — Connectable vs. Non-Connectable
// ─────────────────────────────────────────────────────
// Non-connectable advertising (ADVERTISE_MODE_LOW_LATENCY + not connectable):
//   Device broadcasts packets but refuses incoming connections.
//   Use case: Beacons, proximity sensors, indoor positioning.
//
// Connectable advertising (ADVERTISE_MODE_LOW_LATENCY + IS_CONNECTABLE = true):
//   Device broadcasts and accepts incoming connections.
//   Use case: This module — we WANT centrals to connect to our GATT server.
//
// Advertising Power Levels:
//   ADVERTISE_TX_POWER_ULTRA_LOW  (-21 dBm) — very short range, ~1-2 m
//   ADVERTISE_TX_POWER_LOW        (-15 dBm) — short range, ~3-5 m
//   ADVERTISE_TX_POWER_MEDIUM     ( -7 dBm) — medium range, ~5-10 m
//   ADVERTISE_TX_POWER_HIGH       (  0 dBm) — full range, up to ~100 m (open space)
//
// For a Quest in a research lab or clinical setting: MEDIUM is a good default.
//
// GATT Server Architecture — How Notifications Work to Centrals
// ──────────────────────────────────────────────────────────────────────────────
// When acting as a peripheral, the Android hosts a BluetoothGattServer that
// contains one or more BluetoothGattService objects, each containing
// BluetoothGattCharacteristic objects.
//
// For a central to receive notifications:
//   1. Central discovers our service and characteristic.
//   2. Central writes ENABLE_NOTIFICATION_VALUE (0x01 0x00) to the CCCD
//      descriptor of the characteristic (just as we do in BLEGatt.java).
//   3. Android fires onDescriptorWriteRequest() in our BluetoothGattServerCallback.
//   4. We call gattServer.sendResponse() to acknowledge the write.
//   5. We now track this central as a subscriber.
//   6. When we have data, we call notifyClients() which calls
//      gattServer.notifyCharacteristicChanged() for each subscribed central.
//
// ─────────────────────────────────────────────────────────────────────────────

package com.bleref.unity;

// ─── Android Bluetooth Advertiser Imports ────────────────────────────────────

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;

// ─── Android System Imports ──────────────────────────────────────────────────

import android.content.Context;
import android.os.ParcelUuid;
import android.util.Base64;
import android.util.Log;

// ─── Java Standard Library ───────────────────────────────────────────────────

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// ─────────────────────────────────────────────────────────────────────────────

public class BLEPeripheral {

    // ─── Logging Tag ─────────────────────────────────────────────────────────
    // Filter logcat output with: adb logcat -s BLEPeripheral
    private static final String TAG = "BLEPeripheral";

    // ─── Unity Callback Method Names ─────────────────────────────────────────
    private static final String UNITY_CB_ADVERTISING_STARTED    = "OnAdvertisingStarted";
    private static final String UNITY_CB_ADVERTISING_ERROR      = "OnAdvertisingError";
    private static final String UNITY_CB_ADVERTISING_STOPPED    = "OnAdvertisingStopped";
    private static final String UNITY_CB_CENTRAL_CONNECTED      = "OnCentralConnected";
    private static final String UNITY_CB_CENTRAL_DISCONNECTED   = "OnCentralDisconnected";
    private static final String UNITY_CB_CHAR_ADDED             = "OnCharacteristicAdded";
    private static final String UNITY_CB_CHAR_WRITE_REQUEST     = "OnCharacteristicWriteRequest";

    // ─── CCCD Descriptor UUID ────────────────────────────────────────────────
    // Client Characteristic Configuration Descriptor — standard BLE UUID.
    // Centrals write to this descriptor to enable/disable notifications.
    // See BLEGatt.java (Sprint 76) for a full CCCD explanation.
    private static final UUID CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ─── State ────────────────────────────────────────────────────────────────

    private BluetoothLeAdvertiser  _advertiser;
    private BluetoothGattServer    _gattServer;
    private AdvertiseCallback      _advertiseCallback;
    private boolean                _isAdvertising = false;

    // ─── Connected Centrals ───────────────────────────────────────────────────
    // Tracks all currently connected central devices.
    // Key: MAC address string. Value: BluetoothDevice reference.
    // Used by notifyClients() to push notifications to all subscribers.
    private final Map<String, BluetoothDevice> _connectedCentrals = new HashMap<>();

    // ─── Subscribed Centrals Per Characteristic ───────────────────────────────
    // Tracks which centrals have enabled notifications on which characteristic.
    // Key: characteristic UUID string. Value: list of subscribed device MACs.
    // A central is added here when it writes ENABLE_NOTIFICATION_VALUE to CCCD.
    private final Map<String, List<String>> _subscribedCentrals = new HashMap<>();

    // ─── Singleton ────────────────────────────────────────────────────────────

    private static BLEPeripheral _instance;

    /**
     * Returns the singleton BLEPeripheral instance, creating it if needed.
     *
     * Called from C# via:
     *   var peripheral = new AndroidJavaClass("com.bleref.unity.BLEPeripheral")
     *       .CallStatic<AndroidJavaObject>("getInstance");
     *
     * @return The shared BLEPeripheral singleton.
     */
    public static BLEPeripheral getInstance() {
        if (_instance == null) {
            _instance = new BLEPeripheral();
            Log.d(TAG, "BLEPeripheral singleton created.");
        }
        return _instance;
    }

    private BLEPeripheral() {}

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Start BLE advertising. The device becomes visible to centrals as a
     * connectable BLE peripheral.
     *
     * Advertising sends out one packet per advertising interval (~100-250 ms
     * in low-latency mode) containing the service UUID and device name.
     * Centrals scanning for the service UUID will discover this device and
     * can initiate a GATT connection.
     *
     * A BluetoothGattServer is opened simultaneously to receive connections
     * and handle GATT requests from centrals.
     *
     * Precondition: addCharacteristic() should be called AFTER this method
     * to populate the GATT server with the services the peripheral offers.
     *
     * Typical call from C#:
     *   peripheral.Call("startAdvertising",
     *       "0000180D-0000-1000-8000-00805f9b34fb",
     *       "QuestBLE");
     *
     * @param serviceUUID 128-bit service UUID string to include in the advertisement.
     *                    Centrals can filter scans for this UUID to find us.
     * @param deviceName  Local name to include in the advertisement packet.
     *                    Visible in the central's device discovery UI.
     */
    public void startAdvertising(String serviceUUID, String deviceName) {
        Log.d(TAG, "startAdvertising: serviceUUID=" + serviceUUID + " name=" + deviceName);

        // ── Check hardware support ─────────────────────────────────────────────
        // isMultipleAdvertisementSupported() is the correct check for peripheral mode.
        // This returns false on emulators and BLE 4.0-only hardware.
        BluetoothAdapter adapter = BLEAndroidPlugin.getInstance().getBluetoothAdapter();
        if (adapter == null || !adapter.isMultipleAdvertisementSupported()) {
            Log.e(TAG, "BLE peripheral mode not supported on this hardware.");
            BLEAndroidPlugin.getInstance()
                .sendToUnity(UNITY_CB_ADVERTISING_ERROR, "PERIPHERAL_MODE_NOT_SUPPORTED");
            return;
        }

        if (_isAdvertising) {
            Log.w(TAG, "Already advertising — call stopAdvertising() first.");
            BLEAndroidPlugin.getInstance()
                .sendToUnity(UNITY_CB_ADVERTISING_ERROR, "ALREADY_ADVERTISING");
            return;
        }

        Context context = BLEAndroidPlugin.getInstance().getContext();
        BluetoothManager bluetoothManager =
            (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);

        // ── Open GATT Server ──────────────────────────────────────────────────
        // The GATT server is the service registry that connected centrals interact
        // with. Opening it before advertising ensures that any central connecting
        // immediately after discovering us finds a live server.
        _gattServer = bluetoothManager.openGattServer(context, _gattServerCallback);
        if (_gattServer == null) {
            Log.e(TAG, "Failed to open GATT server.");
            BLEAndroidPlugin.getInstance()
                .sendToUnity(UNITY_CB_ADVERTISING_ERROR, "GATT_SERVER_OPEN_FAILED");
            return;
        }

        // ── Build AdvertiseSettings ──────────────────────────────────────────
        // ADVERTISE_MODE_LOW_LATENCY: fastest advertising interval (~100 ms).
        //   Battery cost is higher but centrals discover us faster.
        //   Appropriate for a user-facing demo or research session.
        //
        // ADVERTISE_TX_POWER_MEDIUM: balanced range (~5-10 m in open space).
        //   Sufficient for lab / clinical settings. Use HIGH for a larger venue.
        //
        // setConnectable(true): Accept incoming GATT connections from centrals.
        //   Must be true for the GATT server to receive connections.
        //   Set to false only for beacon use cases (advertise-only, no connections).
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0) // 0 = advertise indefinitely (until stopAdvertising())
            .build();

        // ── Build AdvertiseData (advertised payload) ──────────────────────────
        // The advertisement packet is constrained to 31 bytes (BLE 4.x legacy
        // advertising). AdvertiseData.Builder helps populate:
        //   - Service UUIDs   : tells centrals what services we offer
        //   - TX Power Level  : allows centrals to estimate distance
        //   - Manufacturer data: custom bytes (we skip this here)
        //
        // setIncludeDeviceName(true) appends the local name. Be aware this
        // consumes additional advertisement bytes. If the name is too long,
        // Android silently truncates or drops it.
        AdvertiseData advertiseData = new AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid.fromString(serviceUUID))
            .setIncludeTxPowerLevel(true)
            .build();

        AdvertiseData scanResponse = new AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build();

        // ── Build AdvertiseCallback ───────────────────────────────────────────
        _advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                _isAdvertising = true;
                Log.i(TAG, "Advertising started: " + settingsInEffect.toString());
                BLEAndroidPlugin.getInstance()
                    .sendToUnity(UNITY_CB_ADVERTISING_STARTED, "");
            }

            @Override
            public void onStartFailure(int errorCode) {
                // ── Translate error codes ─────────────────────────────────────
                // AdvertiseCallback defines:
                //   ADVERTISE_FAILED_DATA_TOO_LARGE         = 1
                //   ADVERTISE_FAILED_TOO_MANY_ADVERTISERS   = 2
                //   ADVERTISE_FAILED_ALREADY_STARTED        = 3
                //   ADVERTISE_FAILED_INTERNAL_ERROR         = 4
                //   ADVERTISE_FAILED_FEATURE_UNSUPPORTED    = 5
                String errorStr;
                switch (errorCode) {
                    case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                        errorStr = "DATA_TOO_LARGE"; break;
                    case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                        errorStr = "TOO_MANY_ADVERTISERS"; break;
                    case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                        errorStr = "ALREADY_STARTED"; break;
                    case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                        errorStr = "INTERNAL_ERROR"; break;
                    case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                        errorStr = "FEATURE_UNSUPPORTED"; break;
                    default:
                        errorStr = "UNKNOWN_" + errorCode;
                }
                Log.e(TAG, "Advertising failed to start: " + errorStr);
                BLEAndroidPlugin.getInstance()
                    .sendToUnity(UNITY_CB_ADVERTISING_ERROR, errorStr);
            }
        };

        // ── Start advertising ─────────────────────────────────────────────────
        // Pass both advertiseData (primary payload) and scanResponse (secondary
        // payload returned when a central does an active scan). Separating them
        // allows the device name to live in scanResponse without consuming bytes
        // from the primary 31-byte advertisement payload.
        _advertiser = adapter.getBluetoothLeAdvertiser();
        adapter.setName(deviceName); // Set the local Bluetooth device name
        _advertiser.startAdvertising(settings, advertiseData, scanResponse, _advertiseCallback);
        Log.d(TAG, "startAdvertising() called.");
    }

    /**
     * Stop BLE advertising and close the GATT server.
     *
     * Connected centrals are NOT forcibly disconnected — their existing
     * GATT connections remain live. Only new centrals are prevented from
     * discovering and connecting to us.
     *
     * Typical call from C#:
     *   peripheral.Call("stopAdvertising");
     */
    public void stopAdvertising() {
        if (!_isAdvertising) {
            Log.d(TAG, "stopAdvertising: not currently advertising.");
            return;
        }

        if (_advertiser != null && _advertiseCallback != null) {
            _advertiser.stopAdvertising(_advertiseCallback);
            Log.i(TAG, "Advertising stopped.");
        }

        if (_gattServer != null) {
            _gattServer.close();
            _gattServer = null;
            Log.d(TAG, "GATT server closed.");
        }

        _isAdvertising = false;
        _connectedCentrals.clear();
        _subscribedCentrals.clear();

        BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_ADVERTISING_STOPPED, "");
    }

    /**
     * Add a GATT characteristic to the peripheral's GATT server.
     *
     * Creates the characteristic with the given UUID, properties, and
     * permissions, then adds it to a service with the given serviceUUID
     * (creating the service if it doesn't already exist in the server).
     *
     * A CCCD descriptor is automatically added to any characteristic with
     * PROPERTY_NOTIFY or PROPERTY_INDICATE, as required by the Bluetooth
     * spec for notifiable characteristics.
     *
     * Properties (android.bluetooth.BluetoothGattCharacteristic):
     *   PROPERTY_READ    = 0x02  — central can read the value
     *   PROPERTY_WRITE   = 0x08  — central can write with response
     *   PROPERTY_NOTIFY  = 0x10  — peripheral can push notifications
     *   PROPERTY_INDICATE= 0x20  — peripheral can push indications (with ACK)
     *   (combine with bitwise OR, e.g., 0x12 = READ | NOTIFY)
     *
     * Permissions (android.bluetooth.BluetoothGattCharacteristic):
     *   PERMISSION_READ  = 0x01  — allow read operations
     *   PERMISSION_WRITE = 0x10  — allow write operations
     *
     * Typical call from C#:
     *   peripheral.Call("addCharacteristic",
     *       "0000180D-0000-1000-8000-00805f9b34fb",   // service UUID
     *       "00002A37-0000-1000-8000-00805f9b34fb",   // char UUID
     *       0x12,  // PROPERTY_READ | PROPERTY_NOTIFY
     *       0x01); // PERMISSION_READ
     *
     * @param serviceUUID  128-bit service UUID string.
     * @param charUUID     128-bit characteristic UUID string.
     * @param properties   Bitfield of BluetoothGattCharacteristic.PROPERTY_* constants.
     * @param permissions  Bitfield of BluetoothGattCharacteristic.PERMISSION_* constants.
     */
    public void addCharacteristic(String serviceUUID, String charUUID,
                                   int properties, int permissions) {
        Log.d(TAG, String.format("addCharacteristic: service=%s char=%s props=%d perms=%d",
            serviceUUID, charUUID, properties, permissions));

        if (_gattServer == null) {
            Log.e(TAG, "addCharacteristic: GATT server not open. Call startAdvertising() first.");
            return;
        }

        // ── Get or create the service ─────────────────────────────────────────
        // BluetoothGattService.SERVICE_TYPE_PRIMARY = the main service type.
        // (SERVICE_TYPE_SECONDARY is for included services, rarely used.)
        BluetoothGattService service = _gattServer.getService(UUID.fromString(serviceUUID));
        if (service == null) {
            service = new BluetoothGattService(
                UUID.fromString(serviceUUID),
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        }

        // ── Create the characteristic ─────────────────────────────────────────
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
            UUID.fromString(charUUID),
            properties,
            permissions);

        // ── Auto-add CCCD descriptor for notifiable characteristics ───────────
        // Any characteristic with PROPERTY_NOTIFY or PROPERTY_INDICATE MUST
        // have a CCCD descriptor. If the descriptor is absent, centrals cannot
        // enable notifications — they will get GATT_WRITE_NOT_PERMITTED when
        // trying to write to it.
        //
        // The CCCD permission is PERMISSION_READ | PERMISSION_WRITE because
        // centrals need to write to it (to enable/disable) and optionally read
        // its current value.
        boolean isNotifiable = (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                            || (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;

        if (isNotifiable) {
            BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
            // Initial value: notifications disabled
            cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            characteristic.addDescriptor(cccd);
            Log.d(TAG, "CCCD descriptor added to notifiable characteristic " + charUUID);
        }

        service.addCharacteristic(characteristic);

        // ── Add the service to the GATT server ────────────────────────────────
        // addService() triggers an asynchronous service registration on the
        // Bluetooth stack. BluetoothGattServerCallback.onServiceAdded() fires
        // when registration is complete (we log it but don't gate further calls on it).
        boolean added = _gattServer.addService(service);
        if (added) {
            String json = String.format("{\"charUUID\":\"%s\",\"serviceUUID\":\"%s\"}",
                charUUID, serviceUUID);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_CHAR_ADDED, json);
            Log.i(TAG, "Service/characteristic added to GATT server: " + charUUID);
        } else {
            Log.e(TAG, "Failed to add service to GATT server for char: " + charUUID);
        }
    }

    /**
     * Send a BLE notification to all centrals subscribed to a characteristic.
     *
     * Only centrals that have previously written ENABLE_NOTIFICATION_VALUE
     * to the CCCD descriptor of this characteristic receive the notification.
     * Non-subscribed centrals are silently skipped.
     *
     * "Notification" vs. "Indication":
     *   Notification: peripheral sends data, central does NOT acknowledge.
     *     Faster, lower overhead. Packet loss is possible.
     *   Indication: peripheral sends data, central MUST acknowledge.
     *     Reliable delivery. This method uses notifications (confirm=false).
     *     For indications, pass confirm=true to notifyCharacteristicChanged().
     *
     * Typical call from C#:
     *   byte[] hrData = new byte[]{0x00, 0x48}; // flags byte + 72 bpm
     *   peripheral.Call("notifyClients",
     *       "00002A37-0000-1000-8000-00805f9b34fb",
     *       hrData);
     *
     * @param charUUID  UUID string of the characteristic to send the value on.
     * @param value     Raw bytes to send. Must fit within ATT payload (≤ MTU-3 bytes).
     *                  For the default MTU of 23: max 20 bytes per notification.
     */
    public void notifyClients(String charUUID, byte[] value) {
        if (_gattServer == null) {
            Log.w(TAG, "notifyClients: GATT server is not open.");
            return;
        }

        // ── Look up the characteristic in the GATT server ─────────────────────
        BluetoothGattCharacteristic characteristic = findCharacteristic(charUUID);
        if (characteristic == null) {
            Log.e(TAG, "notifyClients: characteristic not found: " + charUUID);
            return;
        }

        // ── Update the characteristic value ──────────────────────────────────
        // Required before notifyCharacteristicChanged() — the server reads
        // the value from the characteristic object.
        characteristic.setValue(value);

        // ── Send to each subscribed central ───────────────────────────────────
        List<String> subscribers;
        synchronized (_subscribedCentrals) {
            List<String> raw = _subscribedCentrals.get(charUUID);
            subscribers = (raw != null) ? new ArrayList<>(raw) : new ArrayList<>();
        }

        for (String address : subscribers) {
            BluetoothDevice central;
            synchronized (_connectedCentrals) {
                central = _connectedCentrals.get(address);
            }
            if (central == null) continue; // Central disconnected since we checked

            // confirm = false → notification (no ACK from central)
            // confirm = true  → indication  (central must acknowledge)
            _gattServer.notifyCharacteristicChanged(central, characteristic, false);
            Log.d(TAG, String.format("Notification sent: char=%s central=%s bytes=%d",
                charUUID, address, value.length));
        }
    }

    // ─── GATT Server Callback ─────────────────────────────────────────────────
    //
    // The BluetoothGattServerCallback receives all events from connected centrals:
    // connections, disconnections, read requests, write requests, and descriptor writes.
    //
    // IMPORTANT: All callbacks arrive on a Binder thread (not the main thread).
    // Do NOT update UI or call Unity objects directly — use sendToUnity() which
    // is safe from any thread (UnitySendMessage is thread-safe per Unity docs).

    private final BluetoothGattServerCallback _gattServerCallback =
        new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            String address = device.getAddress();
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Central connected: " + address);
                synchronized (_connectedCentrals) {
                    _connectedCentrals.put(address, device);
                }
                String json = String.format("{\"address\":\"%s\"}", address);
                BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_CENTRAL_CONNECTED, json);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Central disconnected: " + address);
                synchronized (_connectedCentrals) {
                    _connectedCentrals.remove(address);
                }
                // Remove from all subscription lists
                synchronized (_subscribedCentrals) {
                    for (List<String> subscribers : _subscribedCentrals.values()) {
                        subscribers.remove(address);
                    }
                }
                String json = String.format("{\"address\":\"%s\"}", address);
                BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_CENTRAL_DISCONNECTED, json);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device,
                                                 int requestId,
                                                 int offset,
                                                 BluetoothGattCharacteristic characteristic) {
            // Respond with the current characteristic value.
            // For dynamic data, update characteristic.setValue() before responding.
            Log.d(TAG, "Read request: char=" + characteristic.getUuid() + " from=" + device.getAddress());
            _gattServer.sendResponse(device, requestId,
                BluetoothGatt.GATT_SUCCESS, offset, characteristic.getValue());
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device,
                                                  int requestId,
                                                  BluetoothGattCharacteristic characteristic,
                                                  boolean preparedWrite,
                                                  boolean responseNeeded,
                                                  int offset,
                                                  byte[] value) {
            Log.d(TAG, "Write request: char=" + characteristic.getUuid()
                + " from=" + device.getAddress() + " bytes=" + (value != null ? value.length : 0));

            // Store the written value in the characteristic object
            characteristic.setValue(value);

            // If the central expects a response (WRITE_WITH_RESPONSE), send it
            if (responseNeeded) {
                _gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }

            // Notify Unity of the write so C# can react (e.g., apply a setting)
            String dataB64 = (value != null) ? Base64.encodeToString(value, Base64.NO_WRAP) : "";
            String json = String.format(
                "{\"charUUID\":\"%s\",\"data_b64\":\"%s\",\"address\":\"%s\"}",
                characteristic.getUuid().toString().toUpperCase(),
                dataB64,
                device.getAddress());
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_CHAR_WRITE_REQUEST, json);
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device,
                                              int requestId,
                                              BluetoothGattDescriptor descriptor,
                                              boolean preparedWrite,
                                              boolean responseNeeded,
                                              int offset,
                                              byte[] value) {
            // ── CCCD write: central is subscribing or unsubscribing ────────────
            // When a central writes ENABLE_NOTIFICATION_VALUE to our CCCD,
            // it means "please start sending me notifications for this characteristic."
            // We must:
            //   1. Send GATT_SUCCESS response (if responseNeeded)
            //   2. Track the central in _subscribedCentrals
            //
            // If the central writes DISABLE_NOTIFICATION_VALUE, we remove it.
            if (CCCD_UUID.equals(descriptor.getUuid())) {
                String address  = device.getAddress();
                String charUUID = descriptor.getCharacteristic().getUuid().toString().toUpperCase();

                if (java.util.Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                 || java.util.Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                    // Central opted in to notifications
                    synchronized (_subscribedCentrals) {
                        _subscribedCentrals.computeIfAbsent(charUUID, k -> new ArrayList<>());
                        if (!_subscribedCentrals.get(charUUID).contains(address)) {
                            _subscribedCentrals.get(charUUID).add(address);
                        }
                    }
                    Log.i(TAG, "Central subscribed: char=" + charUUID + " address=" + address);

                } else if (java.util.Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    // Central opted out
                    synchronized (_subscribedCentrals) {
                        List<String> subs = _subscribedCentrals.get(charUUID);
                        if (subs != null) subs.remove(address);
                    }
                    Log.i(TAG, "Central unsubscribed: char=" + charUUID + " address=" + address);
                }

                descriptor.setValue(value);
                if (responseNeeded) {
                    _gattServer.sendResponse(device, requestId,
                        BluetoothGatt.GATT_SUCCESS, 0, null);
                }
            }
        }

        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            Log.d(TAG, "Service added to GATT server: " + service.getUuid()
                + " status=" + status);
        }
    };

    // ─── Private Helpers ─────────────────────────────────────────────────────

    /**
     * Search the GATT server's services for a characteristic with the given UUID.
     *
     * @param charUUID  UUID string of the characteristic to find.
     * @return          The BluetoothGattCharacteristic, or null if not found.
     */
    private BluetoothGattCharacteristic findCharacteristic(String charUUID) {
        if (_gattServer == null) return null;
        UUID targetUUID = UUID.fromString(charUUID);
        for (BluetoothGattService service : _gattServer.getServices()) {
            BluetoothGattCharacteristic c = service.getCharacteristic(targetUUID);
            if (c != null) return c;
        }
        return null;
    }

    // ─── Accessors ───────────────────────────────────────────────────────────

    /**
     * Returns true if advertising is currently active.
     *
     * @return true if startAdvertising() was called and succeeded.
     */
    public boolean isAdvertising() {
        return _isAdvertising;
    }

    /**
     * Returns the number of centrals currently connected to this peripheral.
     *
     * Useful for displaying a connected-client count in the Unity debug UI.
     *
     * @return Count of active central connections.
     */
    public int getConnectedCentralCount() {
        synchronized (_connectedCentrals) {
            return _connectedCentrals.size();
        }
    }
}
