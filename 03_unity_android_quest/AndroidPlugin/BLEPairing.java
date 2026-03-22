// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : BLEPairing — Android BLE Bonding and Pairing
// Platform: Android (Java) / Unity Bridge
// Sprint  : Sprint 79
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : Manage BLE device bonding (pairing) on Android. Exposes explicit
//           bond/unpair/query APIs that allow Unity C# to participate in the
//           pairing lifecycle — a level of control not available on iOS or
//           in Python/Bleak.
//
//           This file covers the BLE bonding layer, which is SEPARATE from
//           establishing a GATT connection. You can connect to a peripheral
//           without bonding. Bonding adds encryption and authentication to
//           the link, which is required by characteristics that have the
//           "Authenticated" or "Encrypted" permission flags.
//
// Usage   : From C# (Unity):
//
//               // Initiate pairing with a connected or nearby device
//               pairing.Call("pair", "AA:BB:CC:DD:EE:FF");
//               // → OnPairingState(json: {"id":"AA:BB:CC:DD:EE:FF","state":"bonding"})
//               // → OnPairingState(json: {"id":"AA:BB:CC:DD:EE:FF","state":"bonded"})
//
//               // Remove an existing bond (unpair)
//               pairing.Call("unpair", "AA:BB:CC:DD:EE:FF");
//               // → OnPairingState(json: {"id":"AA:BB:CC:DD:EE:FF","state":"none"})
//
//               // Query current bond state without triggering pairing
//               String state = pairing.Call<string>("getBondState", "AA:BB:CC:DD:EE:FF");
//               // → "none" | "bonding" | "bonded"
//
//           Unity receives callbacks:
//               OnPairingState(String json)  — bond state transition or final result
//               OnPairingError(String json)  — if pair() or unpair() fails
//
//           JSON format for OnPairingState:
//               {"id":"AA:BB:CC:DD:EE:FF","state":"bonded"}
//
// Bonding vs. Pairing — Terminology Clarification
// ──────────────────────────────────────────────────────────────────────────────
//
// These terms are often used interchangeably in practice, but the Bluetooth
// spec distinguishes them:
//
// PAIRING: The process of exchanging cryptographic keys between two devices.
//   This involves:
//     1. Feature exchange (key sizes, OOB, MITM protection)
//     2. Authentication (Just Works, Passkey Entry, Numeric Comparison, OOB)
//     3. Key generation and distribution (LTK, IRK, CSRK)
//
// BONDING: The STORAGE of pairing keys to persist them across reconnections.
//   A "bonded" device is one whose keys are saved in persistent storage.
//   On Android, bonded = "paired" in the system Bluetooth settings UI.
//
// "Just Works" pairing is the default for most BLE health devices (Polar H10,
// Muse 2). It provides encryption but no MITM protection, which is acceptable
// for medical sensors where physical proximity implies consent.
//
// Android vs. iOS vs. Python — Why Android Exposes This Explicitly
// ──────────────────────────────────────────────────────────────────────────────
//
// ANDROID (this file):
//   Exposes full bonding lifecycle to the app:
//     - device.createBond()             → app initiates pairing
//     - device.removeBond() [reflection] → app removes bond
//     - ACTION_BOND_STATE_CHANGED       → broadcast with BOND_BONDING / BOND_BONDED
//   This is intentional Android design philosophy: users and apps control
//   the pairing state. The Bluetooth settings screen lets users see and
//   manage bonds manually.
//
// iOS (CoreBluetooth):
//   Pairing is entirely OS-managed and hidden from the app developer.
//   When a characteristic requires authentication, iOS automatically:
//     1. Triggers the system pairing dialog.
//     2. Performs the key exchange.
//     3. Completes the characteristic operation.
//   The app sees only success or an error. There is NO API to trigger pairing
//   proactively or to remove a bond programmatically.
//
// PYTHON / BLEAK:
//   Similarly OS-managed. On Linux (BlueZ), Bleak delegates bonding to the
//   BlueZ agent — the app does not control the pairing flow.
//   On macOS, same behavior as iOS (CoreBluetooth under the hood).
//   On Windows, BleakClient has createPair() and unpair() via WinRT,
//   making Windows closer to Android in this regard.
//
// When Bonding IS Required (vs. Optional)
// ────────────────────────────────────────
// Characteristics with these properties require bonding:
//   - PROPERTY_AUTHENTICATED_SIGNED_WRITES: writes must be signed with CSRK
//   - PERMISSION_READ_ENCRYPTED_MITM: encrypted read with MITM protection
//   - PERMISSION_WRITE_ENCRYPTED_MITM: encrypted write with MITM protection
//
// Most consumer wearables (Polar H10, Muse 2) do NOT require bonding for
// their data streaming characteristics — bonding is optional/convenience.
//
// Devices that DO require bonding (common patterns):
//   - Devices with PRIVATE data (health history, settings, keys)
//   - Devices that send notifications to authenticated centrals only
//   - OTA DFU characteristics on some Nordic-based hardware
//
// The removeBond() Reflection Hack
// ──────────────────────────────────────────────────────────────────────────────
// Android's BluetoothDevice class has a removeBond() method, but it is
// annotated @hide in the Android source tree. This means:
//   - It does NOT appear in the public Android SDK.
//   - It cannot be called directly from compiled Java/Kotlin code.
//   - It CAN be called via Java reflection at runtime.
//
// This method has existed and worked since API 9 (Android 2.3, 2010). It has
// survived all Android versions through API 34 without behavioral changes.
// Despite being hidden, it is the only programmatic way to remove a bond.
//
// Reflection risks:
//   - ProGuard/R8 may strip it if shrinking is enabled (mitigated by keeping
//     the class via @Keep or -keep rules in proguard-rules.pro).
//   - Future Android versions COULD break it, but 15 years of stability
//     suggests it is de facto stable API.
//   - Not available on Go editions of Android (minimal hardware).
//
// ─────────────────────────────────────────────────────────────────────────────

package com.bleref.unity;

// ─── Android Bluetooth Imports ───────────────────────────────────────────────

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

// ─── Android System Imports ──────────────────────────────────────────────────

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

// ─── Java Standard Library (Reflection for removeBond) ───────────────────────

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

// ─────────────────────────────────────────────────────────────────────────────

public class BLEPairing {

    // ─── Logging Tag ─────────────────────────────────────────────────────────
    // Filter logcat output with: adb logcat -s BLEPairing
    private static final String TAG = "BLEPairing";

    // ─── Unity Callback Method Names ─────────────────────────────────────────
    private static final String UNITY_METHOD_PAIRING_STATE = "OnPairingState";
    private static final String UNITY_METHOD_PAIRING_ERROR = "OnPairingError";

    // ─── Bond State String Constants ─────────────────────────────────────────
    // Human-readable state names sent to Unity. These mirror the Android
    // BluetoothDevice.BOND_* integer constants, expressed as strings so C#
    // can use simple string comparisons without importing Android constants.
    //
    // BOND_NONE    = 10  → "none"
    // BOND_BONDING = 11  → "bonding"  (pairing in progress)
    // BOND_BONDED  = 12  → "bonded"   (keys exchanged and stored)

    private static final String STATE_NONE    = "none";
    private static final String STATE_BONDING = "bonding";
    private static final String STATE_BONDED  = "bonded";
    private static final String STATE_FAILED  = "failed";

    // ─── Receiver Registry ───────────────────────────────────────────────────
    // Tracks device IDs for which we have registered a BroadcastReceiver.
    // This prevents registering duplicate receivers for the same device
    // if pair() is called twice before the callback fires.
    private final Set<String> _pendingPairDevices = new HashSet<>();

    // ─── Singleton ────────────────────────────────────────────────────────────

    private static BLEPairing _instance;

    /**
     * Returns the singleton BLEPairing instance, creating it if needed.
     *
     * Called from C# via:
     *   var pairing = new AndroidJavaClass("com.bleref.unity.BLEPairing")
     *       .CallStatic<AndroidJavaObject>("getInstance");
     *
     * @return The shared BLEPairing singleton.
     */
    public static BLEPairing getInstance() {
        if (_instance == null) {
            _instance = new BLEPairing();
            Log.d(TAG, "BLEPairing singleton created.");
        }
        return _instance;
    }

    private BLEPairing() {}

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Initiate BLE bonding (pairing) with a device.
     *
     * Calls device.createBond() to begin the BLE pairing handshake, then
     * registers a BroadcastReceiver for ACTION_BOND_STATE_CHANGED to track
     * the progression: bonding → bonded (or none on failure).
     *
     * Unity receives intermediate OnPairingState("bonding") and final
     * OnPairingState("bonded") or OnPairingState("failed") callbacks.
     *
     * If the device is already bonded, OnPairingState("bonded") fires
     * immediately with no side effects (createBond() is a no-op for
     * already-bonded devices).
     *
     * Typical call from C#:
     *   pairing.Call("pair", "AA:BB:CC:DD:EE:FF");
     *
     * @param deviceId MAC address of the target device. The device must be
     *                 discoverable (either from a recent scan or still in range).
     *                 The device does NOT need to be currently connected via GATT.
     */
    public void pair(String deviceId) {
        Log.d(TAG, "pair: device=" + deviceId);

        BluetoothDevice device = getDevice(deviceId);
        if (device == null) {
            sendError(deviceId, "DEVICE_NOT_FOUND");
            return;
        }

        // ── Check existing bond state ─────────────────────────────────────────
        // If already bonded, report immediately. No need to re-pair.
        int currentState = device.getBondState();
        if (currentState == BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "Device " + deviceId + " is already bonded.");
            sendState(deviceId, STATE_BONDED);
            return;
        }
        if (currentState == BluetoothDevice.BOND_BONDING) {
            Log.i(TAG, "Device " + deviceId + " is already bonding — waiting for result.");
            sendState(deviceId, STATE_BONDING);
            return;
        }

        // ── Guard against duplicate registration ──────────────────────────────
        synchronized (_pendingPairDevices) {
            if (_pendingPairDevices.contains(deviceId)) {
                Log.w(TAG, "pair() called for " + deviceId + " — already pending. Ignoring.");
                return;
            }
            _pendingPairDevices.add(deviceId);
        }

        // ── Register BroadcastReceiver for bond state changes ─────────────────
        // ACTION_BOND_STATE_CHANGED is broadcast by the OS whenever any device's
        // bond state changes. Our receiver filters by MAC address to handle only
        // the device we initiated pairing with.
        //
        // The receiver is unregistered once a terminal state (bonded or none/failed)
        // is reached, preventing memory leaks from forgotten receivers.
        BroadcastReceiver bondReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                    return;
                }

                // ── Extract the device from the intent ─────────────────────────
                BluetoothDevice changedDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (changedDevice == null || !changedDevice.getAddress().equals(deviceId)) {
                    return; // This broadcast is for a different device — ignore
                }

                int newState  = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                int prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);

                Log.d(TAG, String.format("Bond state changed: device=%s prev=%d new=%d",
                    deviceId, prevState, newState));

                if (newState == BluetoothDevice.BOND_BONDING) {
                    // Pairing in progress — key exchange underway
                    // The OS may show a system pairing dialog to the user
                    // (for passkey-based pairing). For "Just Works" devices,
                    // this state is brief and transitions directly to BOND_BONDED.
                    sendState(deviceId, STATE_BONDING);

                } else if (newState == BluetoothDevice.BOND_BONDED) {
                    // ── Bonding succeeded ────────────────────────────────────
                    // Keys are exchanged and stored in persistent Bluetooth storage.
                    // The device will be visible in system Bluetooth settings.
                    Log.i(TAG, "Bonding succeeded for " + deviceId);
                    sendState(deviceId, STATE_BONDED);
                    cleanup(context, this, deviceId);

                } else if (newState == BluetoothDevice.BOND_NONE) {
                    // ── Bonding failed or was cancelled ──────────────────────
                    // BOND_NONE after BOND_BONDING means the pairing failed.
                    // Common causes:
                    //   - User cancelled the system pairing dialog
                    //   - Passkey mismatch (for non-Just-Works pairing)
                    //   - Peripheral rejected the pairing request
                    //   - Peripheral went out of range during key exchange
                    if (prevState == BluetoothDevice.BOND_BONDING) {
                        Log.w(TAG, "Bonding failed for " + deviceId);
                        sendState(deviceId, STATE_FAILED);
                    } else {
                        // BOND_NONE → BOND_NONE is a spurious broadcast; ignore.
                        return;
                    }
                    cleanup(context, this, deviceId);
                }
            }
        };

        // ── Register the receiver with the app's Context ──────────────────────
        Context appContext = BLEAndroidPlugin.getInstance().getContext();
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        appContext.registerReceiver(bondReceiver, filter);

        // ── Initiate bonding ──────────────────────────────────────────────────
        // createBond() returns true if the bonding process was INITIATED, not
        // that it succeeded. The result comes via ACTION_BOND_STATE_CHANGED.
        boolean started = device.createBond();
        if (started) {
            Log.i(TAG, "Bond creation initiated for " + deviceId);
            sendState(deviceId, STATE_BONDING);
        } else {
            // createBond() returns false if bonding was already in progress,
            // if the device is not reachable, or if the BT stack is busy.
            Log.e(TAG, "createBond() returned false for " + deviceId);
            cleanup(appContext, bondReceiver, deviceId);
            sendError(deviceId, "CREATE_BOND_FAILED");
        }
    }

    /**
     * Remove an existing bond (unpair) with a device.
     *
     * Uses Java reflection to call BluetoothDevice.removeBond(), which is
     * a @hide method not exposed in the public Android SDK. See the file
     * header for a discussion of why this reflection approach is stable.
     *
     * After removal, the device will no longer appear in system Bluetooth
     * settings as paired. On next connection to a characteristic requiring
     * encryption, the OS will re-initiate pairing.
     *
     * Typical call from C#:
     *   pairing.Call("unpair", "AA:BB:CC:DD:EE:FF");
     *
     * @param deviceId MAC address of the device to unpair.
     */
    public void unpair(String deviceId) {
        Log.d(TAG, "unpair: device=" + deviceId);

        BluetoothDevice device = getDevice(deviceId);
        if (device == null) {
            sendError(deviceId, "DEVICE_NOT_FOUND");
            return;
        }

        if (device.getBondState() == BluetoothDevice.BOND_NONE) {
            Log.i(TAG, "Device " + deviceId + " is not bonded — nothing to remove.");
            sendState(deviceId, STATE_NONE);
            return;
        }

        // ── Reflection-based removeBond() ─────────────────────────────────────
        // Step 1: Obtain the Method object by name from BluetoothDevice's class.
        // Step 2: Make it accessible (bypass Java access control).
        // Step 3: Invoke it on our device instance.
        //
        // The method signature is:
        //   public boolean removeBond() throws Exception
        //
        // It returns true if removal succeeded. On failure it returns false
        // (not an exception), unless the method itself doesn't exist (caught below).
        try {
            Method removeBondMethod = device.getClass().getMethod("removeBond");
            // setAccessible(true) is required because the method is @hide:
            // the Java access modifier is 'public' but the SDK strips it.
            removeBondMethod.setAccessible(true);

            boolean removed = (boolean) removeBondMethod.invoke(device);
            if (removed) {
                Log.i(TAG, "Bond removed for " + deviceId);
                sendState(deviceId, STATE_NONE);
            } else {
                Log.w(TAG, "removeBond() returned false for " + deviceId);
                sendError(deviceId, "REMOVE_BOND_FAILED");
            }

        } catch (NoSuchMethodException e) {
            // This should never happen on any Android version with BT support.
            // Log it as a critical error if it occurs.
            Log.e(TAG, "removeBond() method not found via reflection: " + e.getMessage());
            sendError(deviceId, "REMOVE_BOND_METHOD_NOT_FOUND");

        } catch (Exception e) {
            Log.e(TAG, "removeBond() reflection failed: " + e.getMessage());
            sendError(deviceId, "REMOVE_BOND_EXCEPTION");
        }
    }

    /**
     * Query the current bond state of a device without triggering any pairing.
     *
     * Can be called at any time to check whether a device is bonded before
     * attempting to read an encrypted characteristic.
     *
     * Called from C# via:
     *   string state = pairing.Call<string>("getBondState", "AA:BB:CC:DD:EE:FF");
     *
     * @param deviceId MAC address of the device.
     * @return         "none", "bonding", or "bonded". Returns "none" if the
     *                 device cannot be found in the adapter's known device list.
     */
    public String getBondState(String deviceId) {
        BluetoothDevice device = getDevice(deviceId);
        if (device == null) {
            Log.w(TAG, "getBondState: device " + deviceId + " not found.");
            return STATE_NONE;
        }

        int state = device.getBondState();
        switch (state) {
            case BluetoothDevice.BOND_BONDING: return STATE_BONDING;
            case BluetoothDevice.BOND_BONDED:  return STATE_BONDED;
            default:                           return STATE_NONE;
        }
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    /**
     * Retrieve a BluetoothDevice for the given MAC address.
     *
     * BluetoothAdapter.getRemoteDevice() creates a device reference from a
     * MAC address without requiring a scan or connection. The device object
     * is valid even if the peripheral is not currently in range.
     *
     * @param deviceId  MAC address string (AA:BB:CC:DD:EE:FF).
     * @return          BluetoothDevice, or null if adapter is unavailable.
     */
    private BluetoothDevice getDevice(String deviceId) {
        BluetoothAdapter adapter = BLEAndroidPlugin.getInstance().getBluetoothAdapter();
        if (adapter == null) {
            Log.e(TAG, "getDevice: BluetoothAdapter is null.");
            return null;
        }
        try {
            return adapter.getRemoteDevice(deviceId);
        } catch (IllegalArgumentException e) {
            // Malformed MAC address
            Log.e(TAG, "getDevice: invalid MAC address '" + deviceId + "': " + e.getMessage());
            return null;
        }
    }

    /** Unregister the BroadcastReceiver and remove the device from pending set. */
    private void cleanup(Context context, BroadcastReceiver receiver, String deviceId) {
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException e) {
            // Receiver was not registered — safe to ignore
        }
        synchronized (_pendingPairDevices) {
            _pendingPairDevices.remove(deviceId);
        }
    }

    /** Send a pairing state transition to Unity. */
    private void sendState(String deviceId, String state) {
        String json = String.format("{\"id\":\"%s\",\"state\":\"%s\"}", deviceId, state);
        Log.d(TAG, "sendState: " + json);
        BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_PAIRING_STATE, json);
    }

    /** Send a pairing error to Unity. */
    private void sendError(String deviceId, String error) {
        String json = String.format("{\"id\":\"%s\",\"error\":\"%s\"}", deviceId, error);
        Log.e(TAG, "sendError: " + json);
        BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_PAIRING_ERROR, json);
    }
}
