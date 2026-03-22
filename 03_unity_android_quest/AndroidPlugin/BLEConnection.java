// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : BLEConnection — Android BLE Connect / Disconnect Lifecycle
// Platform: Android (Java) / Unity Bridge
// Sprint  : Sprint 75
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : Manage the full BLE connection lifecycle for one or more
//           peripherals: explicit connect, intentional disconnect, unexpected
//           disconnect handling, and auto-reconnect with exponential backoff.
//
//           This file is the THIRD layer of the Android BLE plugin stack:
//             Sprint 73 — BLEAndroidPlugin  (init, permissions)
//             Sprint 74 — BLEScan           (discovery)
//             Sprint 75 — BLEConnection     (connect/disconnect) ← this file
//             Sprint 76 — BLEGatt           (services, reads, writes, notify)
//
// Usage   : From C# (Unity):
//
//               // Connect with 10-second timeout
//               instance.Call("connect", "AA:BB:CC:DD:EE:FF", 10.0f);
//
//               // Disconnect
//               instance.Call("disconnect", "AA:BB:CC:DD:EE:FF");
//
//           Unity receives callbacks:
//               OnConnected(String deviceId)         — GATT connection established
//               OnDisconnected(String json)           — connection closed
//               OnConnectionError(String json)        — connection failed
//
//           OnDisconnected JSON:
//               {"id":"AA:BB:CC:DD:EE:FF","reason":"intentional"}
//               {"id":"AA:BB:CC:DD:EE:FF","reason":"unexpected"}
//
//           OnConnectionError JSON:
//               {"id":"AA:BB:CC:DD:EE:FF","error":"TIMEOUT"}
//               {"id":"AA:BB:CC:DD:EE:FF","error":"GATT_ERROR","status":133}
//
// ─────────────────────────────────────────────────────────────────────────────
//
// The Two-Step Disconnect: gatt.disconnect() THEN gatt.close()
// ─────────────────────────────────────────────────────────────
// This is the most commonly misunderstood aspect of Android BLE and the source
// of many resource leak bugs. Here is why BOTH calls are required, in order:
//
//   Step 1 — gatt.disconnect()
//     Sends a BLE disconnection request to the peripheral (HCI Disconnect).
//     The peripheral and the Android BLE stack acknowledge the disconnect.
//     onConnectionStateChange() fires with STATE_DISCONNECTED when complete.
//     After this call, the GATT object still exists and holds resources.
//     You CANNOT reconnect using the same gatt object after disconnect().
//     You CANNOT yet call close() — the connection teardown is in progress.
//
//   Step 2 — gatt.close()
//     Releases all resources associated with the GATT connection on the
//     Android side: clears service discovery cache, removes registered
//     callbacks, frees the GATT client ID from the Bluetooth stack.
//     MUST be called from inside onConnectionStateChange() AFTER
//     STATE_DISCONNECTED is received (or from a safe cleanup path).
//     Calling close() WITHOUT disconnect() first: the BLE link remains
//     open from the peripheral's perspective (it does not receive an
//     HCI Disconnect). The peripheral will time out eventually (supervision
//     timeout, typically 5-10 seconds), but during that window:
//       - The peripheral's connection slot is occupied.
//       - The peripheral cannot accept a new connection from this central.
//       - GATT operations will fail with status 133 (GATT_ERROR).
//     Calling disconnect() WITHOUT close() after: the BluetoothGatt object
//     remains in memory. On most Android devices, you can create at most
//     ~30 GATT client instances at the OS level before getBluetoothGatt()
//     returns null. Apps that repeatedly connect/disconnect without calling
//     close() will eventually exhaust this pool and be unable to connect
//     to any device — without any obvious error message.
//
//   Correct pattern (implemented in this class):
//     1. Call gatt.disconnect() to initiate teardown.
//     2. Wait for onConnectionStateChange(STATE_DISCONNECTED).
//     3. Call gatt.close() inside the callback.
//     4. Nullify the gatt reference.
//
// ─────────────────────────────────────────────────────────────────────────────
//
// autoConnect=false vs autoConnect=true
// ─────────────────────────────────────
// device.connectGatt(context, autoConnect, callback) has a crucial boolean:
//
//   autoConnect=false (RECOMMENDED for explicit connections):
//     - Initiates a direct connection attempt immediately.
//     - Uses the "fast connect" path with a short connection interval.
//     - Times out if the peripheral is not found within ~30 seconds (OS default).
//     - On failure, onConnectionStateChange is called with a non-SUCCESS status.
//     - This is what you want when the user taps "Connect" on a device.
//
//   autoConnect=true (background reconnection):
//     - Does NOT time out. The OS queues the connection request indefinitely.
//     - The OS will connect whenever the peripheral next advertises, even after
//       hours. This is designed for long-term pairing scenarios (e.g., fitness
//       trackers that reconnect automatically after a phone reboot).
//     - Uses a background scan mode internally — low power but high latency.
//     - There is NO callback if the peripheral is unreachable; the request
//       just waits silently. This makes timeout implementation impossible
//       at the app level.
//     - Can cause "ghost connections" that consume the GATT client pool
//       if autoConnect=true requests accumulate over time.
//     - Avoid for interactive Unity applications. Use false.
//
// ─────────────────────────────────────────────────────────────────────────────
//
// TRANSPORT_LE Flag (API 23+)
// ───────────────────────────
// connectGatt() has a four-argument form available from API 23+:
//   device.connectGatt(context, autoConnect, callback, transport)
//
// The transport parameter should be BluetoothDevice.TRANSPORT_LE.
//
// Why? Some Android devices support both classic Bluetooth (BR/EDR) and BLE.
// Without the transport flag, Android may attempt to connect using BR/EDR
// (classic Bluetooth) if the device also supports it, which will fail for
// peripherals that are BLE-only (like Polar H10, Muse 2, etc.) and produces
// a confusing onConnectionStateChange error. Specifying TRANSPORT_LE forces
// the BLE transport path explicitly, avoiding this ambiguity entirely.
//
// This class uses the transport-aware form on API 23+ and falls back to the
// three-argument form on older API levels.
//
// ─────────────────────────────────────────────────────────────────────────────
//
// Connection Timeout Pattern
// ──────────────────────────
// Android does not expose a configurable connection timeout parameter for
// device.connectGatt(). The OS timeout (when autoConnect=false) is typically
// around 30 seconds, which is too long for a responsive UI.
//
// This class implements a manual timeout via Handler.postDelayed():
//   1. When connectGatt() is called, post a Runnable that fires after timeoutSeconds.
//   2. If the timeout fires before onConnectionStateChange(STATE_CONNECTED),
//      call gatt.disconnect() and gatt.close() to clean up.
//   3. If onConnectionStateChange fires first (success or failure), cancel
//      the timeout runnable via Handler.removeCallbacks().
//
// The Runnable reference is stored per-connection in the ConnectionState inner
// class so that multiple simultaneous connection attempts each have their own
// independent timeout.
//
// ─────────────────────────────────────────────────────────────────────────────
//
// GATT Status Code 133 — The Most Common Android BLE Bug
// ────────────────────────────────────────────────────────
// onConnectionStateChange() includes a `status` parameter. Status 0 means
// GATT_SUCCESS. Status 133 (0x85) means GATT_ERROR — a generic failure.
//
// Status 133 is the most notorious Android BLE bug. It appears when:
//   - The peripheral became unreachable mid-connection attempt.
//   - close() was not called after a previous disconnect.
//   - The GATT client pool is exhausted (see two-step disconnect above).
//   - The BLE stack has entered a bad state (requires Bluetooth toggle or reboot).
//
// When status 133 is received, ALWAYS call gatt.close() immediately and
// nullify the reference. Then retry if appropriate. This class handles it.
//
// ─────────────────────────────────────────────────────────────────────────────

package com.bleref.unity;

// ─── Android Bluetooth Imports ───────────────────────────────────────────────

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothProfile;

// ─── Android System Imports ──────────────────────────────────────────────────

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

// ─── Java Standard Library ───────────────────────────────────────────────────

import java.util.HashMap;
import java.util.Map;

// ─────────────────────────────────────────────────────────────────────────────

public class BLEConnection {

    // ─── Logging Tag ─────────────────────────────────────────────────────────
    private static final String TAG = "BLEConnection";

    // ─── Unity Callback Method Names ─────────────────────────────────────────
    private static final String UNITY_METHOD_CONNECTED         = "OnConnected";
    private static final String UNITY_METHOD_DISCONNECTED      = "OnDisconnected";
    private static final String UNITY_METHOD_CONNECTION_ERROR  = "OnConnectionError";

    // ─── GATT Success Status ─────────────────────────────────────────────────
    // BluetoothGatt.GATT_SUCCESS = 0. Defined as a constant for clarity.
    private static final int GATT_SUCCESS = BluetoothGatt.GATT_SUCCESS;

    // ─── Reconnect Configuration ─────────────────────────────────────────────
    // Maximum number of auto-reconnect attempts after an unexpected disconnect.
    // Exponential backoff: wait = RECONNECT_BACKOFF_BASE_MS * (2 ^ attempt)
    //   attempt 0 → wait 1000 ms (1 s)
    //   attempt 1 → wait 2000 ms (2 s)
    //   attempt 2 → wait 4000 ms (4 s)
    // After MAX_RECONNECT_ATTEMPTS, give up and send OnConnectionError.
    private static final int  MAX_RECONNECT_ATTEMPTS    = 3;
    private static final long RECONNECT_BACKOFF_BASE_MS = 1000L;

    // ─── Handler for timers ──────────────────────────────────────────────────
    // All delayed operations (timeouts, reconnect backoff) are posted to the
    // main thread handler. BLE callbacks arrive on the GATT callback thread —
    // posting to the main looper ensures thread-safe interaction with the
    // connection state map and with Unity.
    private final Handler _mainHandler = new Handler(Looper.getMainLooper());

    // ─── Connection State Map ─────────────────────────────────────────────────
    // Maps device ID (MAC address) → ConnectionState object.
    // Supports multiple simultaneous connections (e.g., two Polar H10 devices).
    // Access is synchronized on _connections to protect against concurrent
    // modification from the GATT callback thread vs the Unity calling thread.
    private final Map<String, ConnectionState> _connections = new HashMap<>();

    // ─── Inner Class: Per-Connection State ───────────────────────────────────

    /**
     * Holds all mutable state for a single BLE connection attempt/session.
     *
     * Grouping per-connection state into an inner class prevents the Maps-of-Maps
     * proliferation that makes multi-device BLE code hard to follow.
     */
    private static class ConnectionState {

        /** The active GATT connection object, or null if not yet connected. */
        BluetoothGatt gatt = null;

        /**
         * True when disconnect() was called intentionally from Unity C#.
         *
         * This flag is the central pattern for distinguishing voluntary vs.
         * unexpected disconnects. It suppresses the auto-reconnect logic and
         * changes the "reason" field in the OnDisconnected JSON payload.
         *
         * Pattern from Python reference (03_connection.py):
         *   self._intentional_disconnect = True  # set before calling disconnect()
         *   await client.disconnect()
         *   # callback checks flag → does NOT fire reconnect
         */
        boolean intentionalDisconnect = false;

        /** Number of reconnect attempts made so far for the current session. */
        int reconnectAttempts = 0;

        /**
         * Reference to the pending timeout Runnable.
         *
         * Stored here so it can be cancelled (removeCallbacks) when the
         * connection succeeds or fails before the timeout fires.
         * Without this reference, a stale timeout could fire and incorrectly
         * close a later successful connection.
         */
        Runnable timeoutRunnable = null;

        /**
         * Reference to the pending reconnect Runnable.
         *
         * Stored for the same reason as timeoutRunnable — to cancel the
         * reconnect timer if disconnect() is called during the backoff window.
         */
        Runnable reconnectRunnable = null;
    }

    // ─── Singleton ────────────────────────────────────────────────────────────

    private static BLEConnection instance;

    /**
     * Returns the singleton BLEConnection instance, creating it if needed.
     *
     * Called from C# via:
     *   var conn = new AndroidJavaClass("com.bleref.unity.BLEConnection")
     *       .CallStatic<AndroidJavaObject>("getInstance");
     *
     * @return The shared BLEConnection singleton.
     */
    public static BLEConnection getInstance() {
        if (instance == null) {
            instance = new BLEConnection();
            Log.d(TAG, "BLEConnection singleton created.");
        }
        return instance;
    }

    private BLEConnection() {}

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Initiate a BLE connection to a peripheral by MAC address.
     *
     * Uses autoConnect=false for an immediate, direct connection attempt.
     * Applies the TRANSPORT_LE flag on API 23+ to force BLE (not BR/EDR).
     * If no STATE_CONNECTED event arrives within timeoutSeconds, the
     * attempt is cancelled and OnConnectionError("TIMEOUT") is sent.
     *
     * On success: OnConnected(deviceId) is sent to Unity.
     * On failure: OnConnectionError(json) is sent to Unity.
     *
     * Called from C# via:
     *   instance.Call("connect", "AA:BB:CC:DD:EE:FF", 10.0f);
     *
     * @param deviceId        MAC address of the peripheral (e.g. "AA:BB:CC:DD:EE:FF").
     *                        Obtained from BLEScan's OnDeviceFound callback.
     * @param timeoutSeconds  Connection attempt timeout in seconds.
     *                        Recommended: 10.0. Values below 5.0 may be unreliable
     *                        for peripherals with slow connection intervals.
     */
    public void connect(String deviceId, float timeoutSeconds) {
        Log.d(TAG, "connect() called: deviceId=" + deviceId + ", timeout=" + timeoutSeconds + "s");

        // ── Guard: already connected ─────────────────────────────────────────
        synchronized (_connections) {
            ConnectionState existing = _connections.get(deviceId);
            if (existing != null && existing.gatt != null) {
                Log.w(TAG, "Already connected or connecting to " + deviceId + ". Ignoring.");
                return;
            }
        }

        // ── Guard: permissions ───────────────────────────────────────────────
        // BLUETOOTH_CONNECT permission is required on API 31+ to call connectGatt().
        if (!BLEAndroidPlugin.getInstance().hasPermissions()) {
            Log.e(TAG, "BLE permissions not granted. Cannot connect.");
            String json = buildErrorJson(deviceId, "PERMISSIONS_DENIED", 0);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_CONNECTION_ERROR, json);
            return;
        }

        // ── Retrieve BluetoothDevice from adapter ────────────────────────────
        // getRemoteDevice() does NOT initiate a connection — it simply returns a
        // BluetoothDevice proxy object for the given address. It can return a
        // non-null object for devices that are not currently in range.
        BluetoothAdapter adapter = BLEAndroidPlugin.getInstance().getBluetoothAdapter();
        if (adapter == null) {
            Log.e(TAG, "BluetoothAdapter null — call BLEAndroidPlugin.initialize() first.");
            String json = buildErrorJson(deviceId, "ADAPTER_NOT_INITIALIZED", 0);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_CONNECTION_ERROR, json);
            return;
        }

        BluetoothDevice device = adapter.getRemoteDevice(deviceId);
        if (device == null) {
            Log.e(TAG, "getRemoteDevice() returned null for " + deviceId);
            String json = buildErrorJson(deviceId, "INVALID_DEVICE_ID", 0);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_CONNECTION_ERROR, json);
            return;
        }

        // ── Create ConnectionState ────────────────────────────────────────────
        ConnectionState state = new ConnectionState();
        state.intentionalDisconnect = false;
        state.reconnectAttempts = 0;

        synchronized (_connections) {
            _connections.put(deviceId, state);
        }

        // ── Build the GATT callback ───────────────────────────────────────────
        // The callback object holds all event handlers for this connection session.
        // It is a single object reused for the full lifetime of the connection.
        BluetoothGattCallback gattCallback = buildGattCallback(deviceId, state);

        // ── Call connectGatt() ────────────────────────────────────────────────
        // Uses the context from the live Unity activity.
        Context context = com.unity3d.player.UnityPlayer.currentActivity;

        BluetoothGatt gatt;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // API 23+ form: explicit TRANSPORT_LE forces BLE connection path.
            // Without this, Android may fall back to BR/EDR on dual-mode devices,
            // causing confusing GATT_ERROR failures for BLE-only peripherals.
            gatt = device.connectGatt(
                context,
                false,              // autoConnect=false: direct/immediate connection
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            );
        } else {
            // API < 23 fallback: no transport parameter available.
            // BLE-only peripherals should still work because the device
            // advertises only LE, but there is a small risk of BR/EDR ambiguity.
            gatt = device.connectGatt(
                context,
                false,
                gattCallback
            );
        }

        if (gatt == null) {
            // connectGatt() returning null is rare but documented. Can happen if
            // the GATT client pool is full (>~30 clients without close()) or if
            // the adapter is in a bad state.
            Log.e(TAG, "connectGatt() returned null for " + deviceId);
            synchronized (_connections) {
                _connections.remove(deviceId);
            }
            String json = buildErrorJson(deviceId, "CONNECT_GATT_RETURNED_NULL", 0);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_CONNECTION_ERROR, json);
            return;
        }

        state.gatt = gatt;
        Log.i(TAG, "connectGatt() called for " + deviceId + ". Awaiting STATE_CONNECTED...");

        // ── Schedule connection timeout ───────────────────────────────────────
        // If STATE_CONNECTED does not fire within timeoutSeconds, cancel the
        // connection and notify Unity. The Runnable is stored in state so it
        // can be cancelled from inside onConnectionStateChange().
        long timeoutMs = (long)(timeoutSeconds * 1000);
        state.timeoutRunnable = () -> {
            Log.w(TAG, "Connection timeout for " + deviceId + " after " + timeoutSeconds + "s.");
            cleanupGatt(state, deviceId);
            String json = buildErrorJson(deviceId, "TIMEOUT", 0);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_CONNECTION_ERROR, json);
        };
        _mainHandler.postDelayed(state.timeoutRunnable, timeoutMs);
    }

    /**
     * Intentionally disconnect from a peripheral.
     *
     * Sets the intentionalDisconnect flag BEFORE calling gatt.disconnect()
     * so that onConnectionStateChange recognizes this as a deliberate teardown
     * and does NOT trigger auto-reconnect logic.
     *
     * OnDisconnected({"id":"...","reason":"intentional"}) is sent when the
     * disconnect is confirmed by onConnectionStateChange(STATE_DISCONNECTED).
     *
     * Called from C# via:
     *   instance.Call("disconnect", "AA:BB:CC:DD:EE:FF");
     *
     * @param deviceId  MAC address of the peripheral to disconnect.
     */
    public void disconnect(String deviceId) {
        Log.d(TAG, "disconnect() called: deviceId=" + deviceId);

        ConnectionState state;
        synchronized (_connections) {
            state = _connections.get(deviceId);
        }

        if (state == null || state.gatt == null) {
            Log.w(TAG, "No active connection for " + deviceId + ". Ignoring disconnect().");
            return;
        }

        // ── Cancel any pending reconnect timer ────────────────────────────────
        // If a reconnect was scheduled (e.g. we were in the backoff window),
        // cancel it — the user has explicitly requested disconnection.
        if (state.reconnectRunnable != null) {
            _mainHandler.removeCallbacks(state.reconnectRunnable);
            state.reconnectRunnable = null;
        }

        // ── Set intentional flag BEFORE calling disconnect() ─────────────────
        // CRITICAL ORDER: The flag must be set BEFORE gatt.disconnect() because
        // onConnectionStateChange can fire synchronously on some devices/OS versions.
        // If the flag is set after, the callback may see it as unexpected and
        // incorrectly start the reconnect flow.
        state.intentionalDisconnect = true;

        // ── Step 1 of 2: Call gatt.disconnect() ──────────────────────────────
        // Initiates GATT connection teardown. Sends an HCI Disconnect to the
        // peripheral. onConnectionStateChange(STATE_DISCONNECTED) will follow.
        // Step 2 (gatt.close()) happens INSIDE onConnectionStateChange.
        Log.d(TAG, "Calling gatt.disconnect() for " + deviceId);
        state.gatt.disconnect();
    }

    // ─── GATT Callback Builder ───────────────────────────────────────────────

    /**
     * Build a BluetoothGattCallback for the given device/state pair.
     *
     * Separated from connect() to keep the method readable. The callback
     * captures deviceId and state by reference (effectively final for lambdas,
     * fields for inner class).
     *
     * @param deviceId  MAC address, used in all log messages and JSON payloads.
     * @param state     The mutable per-connection state for this session.
     * @return A fully configured BluetoothGattCallback.
     */
    private BluetoothGattCallback buildGattCallback(
            String deviceId,
            ConnectionState state) {

        return new BluetoothGattCallback() {

            // ─── onConnectionStateChange ──────────────────────────────────────
            //
            // This is the most important callback in the BLE stack. It fires:
            //   - When a connection attempt succeeds or fails.
            //   - When the peripheral disconnects (intentionally or not).
            //   - Sometimes with status 133 (GATT_ERROR) when the BLE stack
            //     gets into a bad state — always call close() in that case.
            //
            // Parameters:
            //   gatt    — The GATT object for this connection.
            //   status  — GATT_SUCCESS (0) or an error code.
            //   newState — BluetoothProfile.STATE_CONNECTED or STATE_DISCONNECTED.
            //
            // Thread: This callback fires on the Android Bluetooth thread
            // (BluetoothGatt dispatcher), NOT the main thread. UnitySendMessage
            // is thread-safe, but any Java state we mutate must be synchronized.

            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                Log.d(TAG, String.format(
                    "onConnectionStateChange: device=%s status=%d newState=%d",
                    deviceId, status, newState));

                // ── Cancel the connection timeout timer ───────────────────────
                // The connection either succeeded or failed — either way, the
                // timeout is no longer relevant. Cancel before any branching.
                if (state.timeoutRunnable != null) {
                    _mainHandler.removeCallbacks(state.timeoutRunnable);
                    state.timeoutRunnable = null;
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // ── CONNECTION ESTABLISHED ────────────────────────────────

                    if (status != GATT_SUCCESS) {
                        // Connected state with non-zero status is an unusual
                        // Android edge case — treat as a failed connection.
                        Log.w(TAG, "STATE_CONNECTED but status=" + status + " for " + deviceId);
                        handleGattError(gatt, deviceId, status, state);
                        return;
                    }

                    Log.i(TAG, "Connected to " + deviceId);
                    state.reconnectAttempts = 0; // Reset backoff counter on success

                    // Notify Unity. BLEGatt (Sprint 76) will call discoverServices()
                    // after receiving this event.
                    BLEAndroidPlugin.getInstance().sendToUnity(
                        UNITY_METHOD_CONNECTED, deviceId);

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // ── DISCONNECTED ──────────────────────────────────────────

                    if (status == GATT_SUCCESS || state.intentionalDisconnect) {
                        // ── Clean, intentional disconnect ─────────────────────
                        // Either the app called disconnect() (intentionalDisconnect=true)
                        // or the peripheral disconnected cleanly (status=0).
                        Log.i(TAG, "Disconnected from " + deviceId
                            + " (intentional=" + state.intentionalDisconnect + ")");

                        // ── Step 2 of 2: Call gatt.close() ───────────────────
                        // Now that STATE_DISCONNECTED is confirmed, it is safe
                        // to release all GATT resources. close() MUST be called
                        // here — not before, and not after leaving this callback.
                        Log.d(TAG, "Calling gatt.close() for " + deviceId);
                        gatt.close();

                        String reason = state.intentionalDisconnect ? "intentional" : "unexpected";
                        String json = buildDisconnectJson(deviceId, reason);

                        synchronized (_connections) {
                            _connections.remove(deviceId);
                        }

                        BLEAndroidPlugin.getInstance().sendToUnity(
                            UNITY_METHOD_DISCONNECTED, json);

                    } else {
                        // ── Unexpected disconnect (e.g. peripheral out of range) ─
                        Log.w(TAG, "Unexpected disconnect from " + deviceId
                            + " with status=" + status);

                        // Close the GATT object to free resources before retrying
                        Log.d(TAG, "Calling gatt.close() before reconnect attempt.");
                        gatt.close();
                        state.gatt = null;

                        // ── Auto-reconnect with exponential backoff ────────────
                        // Attempt to reconnect automatically up to MAX_RECONNECT_ATTEMPTS.
                        // Each attempt waits longer: 1s, 2s, 4s (base 1000ms × 2^attempt).
                        if (state.reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                            long delayMs = RECONNECT_BACKOFF_BASE_MS
                                * (1L << state.reconnectAttempts); // 2^attempt
                            state.reconnectAttempts++;

                            Log.i(TAG, String.format(
                                "Scheduling reconnect attempt %d/%d for %s in %dms",
                                state.reconnectAttempts, MAX_RECONNECT_ATTEMPTS,
                                deviceId, delayMs));

                            // Store the reconnect runnable so disconnect() can cancel it
                            state.reconnectRunnable = () -> {
                                Log.d(TAG, "Reconnect attempt " + state.reconnectAttempts
                                    + " for " + deviceId);
                                // Re-use the same timeout as the original connect call.
                                // 10 seconds is a safe default for reconnect attempts.
                                connect(deviceId, 10.0f);
                            };
                            _mainHandler.postDelayed(state.reconnectRunnable, delayMs);

                        } else {
                            // ── Reconnect exhausted ───────────────────────────
                            Log.e(TAG, "Max reconnect attempts reached for " + deviceId);

                            synchronized (_connections) {
                                _connections.remove(deviceId);
                            }

                            // Notify Unity of unexpected final disconnection
                            String json = buildDisconnectJson(deviceId, "unexpected");
                            BLEAndroidPlugin.getInstance().sendToUnity(
                                UNITY_METHOD_DISCONNECTED, json);
                        }
                    }
                }
            }

            // Other GATT callbacks (service discovery, reads, writes, notifications)
            // are handled in BLEGatt.java (Sprint 76). This class focuses solely
            // on the connect/disconnect lifecycle. BLEGatt will obtain the gatt
            // reference from BLEConnection.getGatt(deviceId) after OnConnected fires.
        };
    }

    // ─── GATT Error Handler ───────────────────────────────────────────────────

    /**
     * Handle a GATT error status in onConnectionStateChange.
     *
     * The most common case is status 133 (GATT_ERROR). Always close() the
     * gatt object when this occurs to avoid leaking the GATT client slot.
     *
     * @param gatt      The GATT object in error.
     * @param deviceId  Device MAC address.
     * @param status    The GATT error status code.
     * @param state     The mutable connection state.
     */
    private void handleGattError(
            BluetoothGatt gatt,
            String deviceId,
            int status,
            ConnectionState state) {

        Log.e(TAG, "GATT error for " + deviceId + ": status=" + status
            + (status == 133 ? " (GATT_ERROR — see class header for details)" : ""));

        // Always close on error to free the GATT client slot.
        gatt.close();
        state.gatt = null;

        synchronized (_connections) {
            _connections.remove(deviceId);
        }

        String json = buildErrorJson(deviceId, "GATT_ERROR", status);
        BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_CONNECTION_ERROR, json);
    }

    // ─── Cleanup Helper ──────────────────────────────────────────────────────

    /**
     * Cancel timeout, disconnect, and close a GATT connection cleanly.
     *
     * Used by the timeout runnable and other abort paths where we need to
     * tear down a connection that has not yet reached STATE_CONNECTED.
     *
     * @param state     The connection state to clean up.
     * @param deviceId  Device MAC address, for the connection map removal.
     */
    private void cleanupGatt(ConnectionState state, String deviceId) {
        if (state.timeoutRunnable != null) {
            _mainHandler.removeCallbacks(state.timeoutRunnable);
            state.timeoutRunnable = null;
        }
        if (state.reconnectRunnable != null) {
            _mainHandler.removeCallbacks(state.reconnectRunnable);
            state.reconnectRunnable = null;
        }
        if (state.gatt != null) {
            // Calling both disconnect() and close() in sequence:
            // disconnect() may not be needed if we never reached STATE_CONNECTED,
            // but it is harmless and ensures the peripheral receives a proper HCI
            // Disconnect rather than silently timing out.
            state.gatt.disconnect();
            state.gatt.close();
            state.gatt = null;
        }
        synchronized (_connections) {
            _connections.remove(deviceId);
        }
    }

    // ─── JSON Builders ────────────────────────────────────────────────────────

    /**
     * Build the OnDisconnected JSON payload.
     *
     * @param deviceId  MAC address.
     * @param reason    "intentional" or "unexpected".
     * @return JSON string: {"id":"...","reason":"..."}
     */
    private String buildDisconnectJson(String deviceId, String reason) {
        return String.format("{\"id\":\"%s\",\"reason\":\"%s\"}", deviceId, reason);
    }

    /**
     * Build the OnConnectionError JSON payload.
     *
     * @param deviceId   MAC address.
     * @param errorCode  Human-readable error token (e.g. "TIMEOUT", "GATT_ERROR").
     * @param status     Raw GATT status code (0 if not applicable).
     * @return JSON string: {"id":"...","error":"...","status":N}
     */
    private String buildErrorJson(String deviceId, String errorCode, int status) {
        return String.format(
            "{\"id\":\"%s\",\"error\":\"%s\",\"status\":%d}",
            deviceId, errorCode, status
        );
    }

    // ─── Accessors ───────────────────────────────────────────────────────────

    /**
     * Returns the BluetoothGatt object for a connected device.
     *
     * Used by BLEGatt (Sprint 76) to access the GATT connection established
     * here. Returns null if the device is not connected or the connection ID
     * is unknown.
     *
     * @param deviceId  MAC address of the connected peripheral.
     * @return BluetoothGatt, or null.
     */
    public BluetoothGatt getGatt(String deviceId) {
        synchronized (_connections) {
            ConnectionState state = _connections.get(deviceId);
            return (state != null) ? state.gatt : null;
        }
    }

    /**
     * Returns true if a device is currently in the CONNECTED state.
     *
     * Can be polled from C# to guard GATT operations.
     *
     * Called from C# via:
     *   bool connected = instance.Call<bool>("isConnected", "AA:BB:CC:DD:EE:FF");
     *
     * @param deviceId  MAC address of the peripheral.
     * @return true if connected, false otherwise.
     */
    public boolean isConnected(String deviceId) {
        synchronized (_connections) {
            ConnectionState state = _connections.get(deviceId);
            return state != null && state.gatt != null;
        }
    }
}
