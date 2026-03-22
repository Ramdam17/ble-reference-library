// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : BLEAndroidPlugin — Android BLE Stack Initializer
// Platform: Android (Java) / Unity Bridge
// Sprint  : Sprint 73
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : Initialize the Android BLE stack from Unity, check and request
//           runtime permissions (API 31+ model), and provide the Java-side
//           bridge between Unity C# and the Android Bluetooth APIs.
//
//           This file is the ENTRY POINT for all BLE operations in the Unity
//           Android plugin. Later classes (BLEScan, BLEConnection, BLEGatt)
//           will follow the same singleton + UnitySendMessage pattern
//           established here.
//
// Usage   : From C# (Unity):
//
//               using (var plugin = new AndroidJavaObject("com.bleref.unity.BLEAndroidPlugin")) {
//                   var instance = plugin.CallStatic<AndroidJavaObject>("getInstance");
//                   instance.Call("initialize", "BLEManager");  // "BLEManager" = your Unity GameObject name
//               }
//
//           The plugin then calls back to Unity via:
//               UnitySendMessage("BLEManager", "OnBleReady",    "adapter_ok");
//               UnitySendMessage("BLEManager", "OnPermissions", "granted");
//               UnitySendMessage("BLEManager", "OnBleError",    "bt_disabled");
//
// Key Android BLE Concepts
// ─────────────────────────
// BluetoothManager   — System service. The gateway to the BLE stack.
//                      Obtain via context.getSystemService(Context.BLUETOOTH_SERVICE).
// BluetoothAdapter   — Represents the device's Bluetooth hardware.
//                      One per device; retrieved through BluetoothManager.
// BluetoothLeScanner — Obtained from BluetoothAdapter. Used for scanning.
//                      (Covered in BLEScan.java — Sprint 74.)
// BluetoothGatt      — Represents a GATT connection to a peripheral.
//                      (Covered in BLEConnection.java / BLEGatt.java — Sprint 76.)
// CCCD               — Client Characteristic Configuration Descriptor.
//                      Must be explicitly written to enable BLE notifications.
//                      Android does NOT enable notifications automatically, unlike
//                      iOS/CoreBluetooth. This is covered in detail in Sprint 76,
//                      but note it here as a critical gotcha.
//
// Permission Model Changes in API 31 (Android 12)
// ─────────────────────────────────────────────────
// API < 31  (Android 11 and below):
//   BLE scanning is tied to location infrastructure. The OS allows apps to
//   infer physical location from nearby BLE beacons, so Google required
//   ACCESS_FINE_LOCATION as a prerequisite for any BLE scan.
//   Required permissions:
//     - BLUETOOTH             (declared, no runtime prompt needed)
//     - BLUETOOTH_ADMIN       (declared, no runtime prompt needed)
//     - ACCESS_FINE_LOCATION  (runtime prompt required)
//
// API 31+ (Android 12 and above):
//   Google separated BLE into purpose-specific permissions, removing the
//   location dependency for apps that genuinely do not use BLE for location.
//   Required permissions (both need runtime prompt):
//     - BLUETOOTH_SCAN     — to discover and scan for peripherals
//     - BLUETOOTH_CONNECT  — to connect to paired/known peripherals
//   The neverForLocation flag on BLUETOOTH_SCAN tells Android that this app
//   explicitly opts out of using BLE scan results for location inference.
//   Without this flag, Android may still require location permission even on
//   API 31+. This flag must appear both in AndroidManifest.xml AND be
//   confirmed correct at runtime.
//
// UnitySendMessage Threading Model
// ─────────────────────────────────
// UnitySendMessage(gameObject, method, param) is the official Unity→Android
// callback mechanism. Calls are automatically marshalled to the Unity main
// thread, so C# handlers do NOT need to use Dispatcher or coroutines to
// interact with Unity objects. This makes it safe to call from:
//   - BLE callback threads (BluetoothGattCallback)
//   - HandlerThread / Looper threads used by BluetoothLeScanner
//   - Any background thread created by the plugin
//
// C# Bridge — How it works
// ─────────────────────────
// 1. C# calls: new AndroidJavaObject("com.bleref.unity.BLEAndroidPlugin")
//    This constructs the Java class using the Unity IL2CPP JNI bridge.
// 2. C# calls: .CallStatic<AndroidJavaObject>("getInstance")
//    This retrieves or creates the singleton instance.
// 3. C# calls: instance.Call("initialize", "BLEManager")
//    This passes the Unity GameObject name so Java knows where to send
//    UnitySendMessage callbacks.
// 4. Java uses UnityPlayer.UnitySendMessage to deliver results back.
//    C# must have public methods matching the method names used here
//    (e.g., OnBleReady, OnPermissions, OnBleError) on the target GameObject.
//
// ─────────────────────────────────────────────────────────────────────────────

package com.bleref.unity;

// ─── Android Bluetooth Imports ───────────────────────────────────────────────

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;

// ─── Android System Imports ──────────────────────────────────────────────────

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

// ─── AndroidX Permission Helpers ─────────────────────────────────────────────
// Runtime permissions: use direct Android API (Context.checkSelfPermission,
// Activity.requestPermissions) — available from API 23+. Our minSdk is 26,
// so no AndroidX wrappers needed. This keeps the plugin dependency-free.

// ─── Unity Bridge Import ──────────────────────────────────────────────────────
// UnityPlayer is provided by the Unity Android runtime (classes.jar / aar).
// It must be on the classpath when building the plugin. In a standard Unity
// Android export, this is available automatically.
// UnitySendMessage signature:
//   UnityPlayer.UnitySendMessage(String gameObject, String method, String message)

import com.unity3d.player.UnityPlayer;

// ─────────────────────────────────────────────────────────────────────────────

public class BLEAndroidPlugin {

    // ─── Logging Tag ─────────────────────────────────────────────────────────
    // All Android log calls use a tag for filtering in logcat:
    //   adb logcat -s BLEAndroidPlugin
    private static final String TAG = "BLEAndroidPlugin";

    // ─── Permission Request Code ──────────────────────────────────────────────
    // Arbitrary integer used to match onRequestPermissionsResult callbacks.
    // Must be unique across all permission requests in the Activity.
    private static final int REQUEST_BLE_PERMISSIONS = 1001;

    // ─── Unity Callback Method Names ─────────────────────────────────────────
    // These string constants are the names of the C# methods on the Unity
    // GameObject that will receive BLE events. The C# script on "BLEManager"
    // (or whatever name is passed to initialize()) must expose public methods
    // with exactly these names.
    private static final String UNITY_METHOD_BLE_READY   = "OnBleReady";
    private static final String UNITY_METHOD_PERMISSIONS = "OnPermissions";
    private static final String UNITY_METHOD_BLE_ERROR   = "OnBleError";

    // ─── Unity Callback Payload Constants ────────────────────────────────────
    // Simple string tokens used as UnitySendMessage payloads. Keeping these as
    // constants avoids typos and makes the C# parser side deterministic.
    private static final String PAYLOAD_ADAPTER_OK       = "adapter_ok";
    private static final String PAYLOAD_BT_DISABLED      = "bt_disabled";
    private static final String PAYLOAD_BT_UNAVAILABLE   = "bt_unavailable";
    private static final String PAYLOAD_PERMISSIONS_OK   = "granted";
    private static final String PAYLOAD_PERMISSIONS_DENY = "denied";

    // ─── BLE State ───────────────────────────────────────────────────────────

    /**
     * The device's Bluetooth hardware adapter.
     *
     * There is exactly one BluetoothAdapter per Android device. It must be
     * obtained through BluetoothManager (the direct static getter
     * BluetoothAdapter.getDefaultAdapter() is deprecated as of API 33).
     *
     * Null if BLE hardware is unavailable or BluetoothManager cannot be
     * obtained from the current Context.
     */
    private BluetoothAdapter bluetoothAdapter;

    /**
     * Name of the Unity GameObject that receives UnitySendMessage callbacks.
     *
     * Set during initialize(). All UnitySendMessage calls in this class and
     * in subclasses (BLEScan, BLEConnection, BLEGatt) use this name as the
     * first argument. The named GameObject must be present in the scene and
     * must not be destroyed while BLE operations are active.
     */
    private String unityGameObjectName;

    // ─── Singleton ────────────────────────────────────────────────────────────
    //
    // WHY SINGLETON?
    // BluetoothAdapter represents the physical Bluetooth radio. Creating
    // multiple instances does not create multiple adapters — the OS always
    // routes through the same hardware. Using a singleton ensures:
    //   1. Only one initialization path (avoids double-init races).
    //   2. BLEScan, BLEConnection, and BLEGatt can all share the same
    //      adapter reference without passing it through constructors.
    //   3. Unity's C# can call getInstance() at any time without needing
    //      to store a Java reference across scene loads.
    //
    // Thread safety note: getInstance() is not synchronized here for
    // simplicity. In production, double-checked locking or an
    // initialization-on-demand holder would be preferred. Since Unity
    // typically initializes from Awake() on the main thread, this is
    // acceptable for a reference implementation.

    private static BLEAndroidPlugin instance;

    /**
     * Returns the singleton BLEAndroidPlugin instance, creating it if needed.
     *
     * Called from C# via:
     *   var instance = plugin.CallStatic<AndroidJavaObject>("getInstance");
     *
     * @return The shared BLEAndroidPlugin singleton.
     */
    public static BLEAndroidPlugin getInstance() {
        if (instance == null) {
            instance = new BLEAndroidPlugin();
            Log.d(TAG, "BLEAndroidPlugin singleton created.");
        }
        return instance;
    }

    // Private constructor — enforces singleton usage.
    private BLEAndroidPlugin() {}

    // ─── Initialization ───────────────────────────────────────────────────────

    /**
     * Initialize the Android BLE stack and store the Unity callback target.
     *
     * This must be the FIRST call made from C#. It:
     *   1. Stores the Unity GameObject name for all subsequent callbacks.
     *   2. Obtains the BluetoothManager system service.
     *   3. Retrieves the BluetoothAdapter from BluetoothManager.
     *   4. Validates that BLE is available and enabled on this device.
     *   5. Sends OnBleReady("adapter_ok") or OnBleError(...) back to Unity.
     *
     * Called from C# via:
     *   instance.Call("initialize", "BLEManager");
     *
     * @param gameObjectName  Name of the Unity GameObject that has the
     *                        BLEManager C# component attached. Must be a
     *                        non-null, non-empty string matching the exact
     *                        name of the GameObject in the scene hierarchy.
     */
    public void initialize(String gameObjectName) {
        Log.d(TAG, "initialize() called with gameObjectName=" + gameObjectName);

        // Store the Unity callback target name.
        this.unityGameObjectName = gameObjectName;

        // ── Step 1: Get the current Android Activity context ──────────────────
        // UnityPlayer.currentActivity is the live Unity Activity. We need a
        // Context to call getSystemService(). Using currentActivity (not
        // getApplicationContext()) is preferred for BLE on some OEM devices
        // where the application context lacks window-manager access.
        Context context = UnityPlayer.currentActivity;

        // ── Step 2: Check BLE hardware feature availability ──────────────────
        // Not all Android devices have Bluetooth LE hardware. Quest devices do,
        // but a defensive check ensures this plugin works on emulators or
        // BLE-less test targets gracefully.
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.e(TAG, "BLE hardware not available on this device.");
            sendToUnity(UNITY_METHOD_BLE_ERROR, PAYLOAD_BT_UNAVAILABLE);
            return;
        }

        // ── Step 3: Obtain BluetoothManager ──────────────────────────────────
        // BluetoothManager is the system service that manages all Bluetooth
        // connections and state on Android. It is the correct way to obtain
        // BluetoothAdapter from API 18+ (the deprecated static getter
        // BluetoothAdapter.getDefaultAdapter() was removed in API 33).
        BluetoothManager bluetoothManager =
            (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);

        if (bluetoothManager == null) {
            Log.e(TAG, "BluetoothManager not available (system service returned null).");
            sendToUnity(UNITY_METHOD_BLE_ERROR, PAYLOAD_BT_UNAVAILABLE);
            return;
        }

        // ── Step 4: Obtain BluetoothAdapter ──────────────────────────────────
        // BluetoothAdapter represents the local Bluetooth hardware adapter.
        // If the device has no Bluetooth hardware, getAdapter() returns null.
        bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter is null — no Bluetooth hardware.");
            sendToUnity(UNITY_METHOD_BLE_ERROR, PAYLOAD_BT_UNAVAILABLE);
            return;
        }

        // ── Step 5: Check if Bluetooth is enabled ────────────────────────────
        // A present adapter can still be disabled (user turned off Bluetooth).
        // We report this to Unity so the C# layer can prompt the user to
        // enable Bluetooth. We do NOT programmatically enable it here —
        // calling bluetoothAdapter.enable() is deprecated in API 33+ and
        // requires user consent; Unity should display a system dialog instead.
        if (!bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "Bluetooth is present but currently disabled.");
            sendToUnity(UNITY_METHOD_BLE_ERROR, PAYLOAD_BT_DISABLED);
            return;
        }

        // ── Step 6: Success ───────────────────────────────────────────────────
        Log.i(TAG, "BluetoothAdapter initialized successfully.");
        sendToUnity(UNITY_METHOD_BLE_READY, PAYLOAD_ADAPTER_OK);
    }

    // ─── Permission Management ───────────────────────────────────────────────
    //
    // WHY ARE PERMISSIONS COMPLEX FOR BLE?
    // Android's BLE permission model has changed significantly across API
    // levels. This section handles both the legacy (API < 31) and modern
    // (API 31+) models in a single, well-documented code path.
    //
    // API < 31 (Android 11 and below) — Location-gated model:
    //   Android treated BLE scanning as a location-revealing operation
    //   (because BLE beacons can be used to triangulate position). Therefore,
    //   ACCESS_FINE_LOCATION was required at runtime before any scan.
    //   BLUETOOTH and BLUETOOTH_ADMIN were install-time only (declared in
    //   manifest, no runtime prompt required).
    //
    // API 31+ (Android 12 and above) — Purpose-specific model:
    //   Google introduced two new granular permissions:
    //   - BLUETOOTH_SCAN:    required to start/stop BLE scans
    //   - BLUETOOTH_CONNECT: required to connect to BLE peripherals
    //   Both require runtime prompts (like location did before).
    //   If an app only uses BLE for device interaction (not location), it
    //   should declare usesPermissionFlags="neverForLocation" on
    //   BLUETOOTH_SCAN in AndroidManifest.xml. This also means the app
    //   does NOT need ACCESS_FINE_LOCATION at all on API 31+.
    //
    // neverForLocation flag — Why it matters:
    //   Without neverForLocation, Android 12+ may STILL require location
    //   permission even when BLUETOOTH_SCAN is granted, because the OS
    //   suspects the app might use scan results to infer location.
    //   Adding neverForLocation in the manifest AND ensuring ACCESS_FINE_LOCATION
    //   is NOT requested at runtime tells Android definitively that this app
    //   does not use BLE for location. This simplifies the permission flow
    //   for the user and avoids unnecessary location access in permission dialogs.

    /**
     * Check whether all required BLE permissions are currently granted.
     *
     * Handles both the API < 31 (location-based) and API 31+ (scan/connect)
     * permission models transparently.
     *
     * Called from C# via:
     *   bool hasPerms = instance.Call<bool>("hasPermissions");
     *
     * @return true if all required permissions are granted, false otherwise.
     */
    public boolean hasPermissions() {
        Context context = UnityPlayer.currentActivity;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ── API 31+ (Android 12+): Check BLUETOOTH_SCAN + BLUETOOTH_CONNECT ──
            // Both must be granted for full BLE operation. BLUETOOTH_SCAN is
            // needed even just to check device names during scan results.
            boolean hasScan = context.checkSelfPermission(
                android.Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED;

            boolean hasConnect = context.checkSelfPermission(
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED;

            Log.d(TAG, "API 31+ permissions — SCAN: " + hasScan + ", CONNECT: " + hasConnect);
            return hasScan && hasConnect;

        } else {
            // ── API < 31 (Android 11 and below): Check ACCESS_FINE_LOCATION ──
            // On these versions, BLE scanning requires fine location permission.
            // BLUETOOTH and BLUETOOTH_ADMIN are install-time and don't need
            // a runtime check here.
            boolean hasLocation = context.checkSelfPermission(
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED;

            Log.d(TAG, "API < 31 permissions — ACCESS_FINE_LOCATION: " + hasLocation);
            return hasLocation;
        }
    }

    /**
     * Request the required BLE permissions from the user, if not already granted.
     *
     * On API 31+ this requests BLUETOOTH_SCAN + BLUETOOTH_CONNECT.
     * On API < 31  this requests ACCESS_FINE_LOCATION.
     *
     * The result is delivered asynchronously via onRequestPermissionsResult(),
     * which must be called from the Unity Activity's own onRequestPermissionsResult.
     * The final outcome is communicated to Unity via UnitySendMessage:
     *   OnPermissions("granted") — all required permissions were granted
     *   OnPermissions("denied")  — one or more permissions were denied
     *
     * Called from C# via:
     *   instance.Call("requestPermissions");
     */
    public void requestPermissions() {
        // If permissions are already granted, notify Unity immediately.
        if (hasPermissions()) {
            Log.d(TAG, "All BLE permissions already granted.");
            sendToUnity(UNITY_METHOD_PERMISSIONS, PAYLOAD_PERMISSIONS_OK);
            return;
        }

        // Build the permission list based on the current API level.
        String[] permissions;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+: Request scan + connect.
            // Note: We deliberately do NOT request ACCESS_FINE_LOCATION here
            // because we declared neverForLocation in AndroidManifest.xml.
            // Requesting location alongside BLUETOOTH_SCAN when neverForLocation
            // is set would be contradictory and confusing to the user.
            permissions = new String[]{
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
            };
            Log.d(TAG, "Requesting API 31+ BLE permissions: BLUETOOTH_SCAN, BLUETOOTH_CONNECT");
        } else {
            // API < 31: Request location.
            permissions = new String[]{
                android.Manifest.permission.ACCESS_FINE_LOCATION
            };
            Log.d(TAG, "Requesting API < 31 BLE permission: ACCESS_FINE_LOCATION");
        }

        // Activity.requestPermissions (API 23+) triggers the system permission dialog.
        // The result is delivered to the Activity's onRequestPermissionsResult callback,
        // which must forward it to this plugin's onRequestPermissionsResult() method.
        UnityPlayer.currentActivity.requestPermissions(
            permissions,
            REQUEST_BLE_PERMISSIONS
        );
    }

    /**
     * Handle the result of a runtime permission request.
     *
     * This must be called from the Unity Activity's onRequestPermissionsResult():
     *
     *   // In your custom UnityPlayerActivity subclass:
     *   @Override
     *   public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
     *       super.onRequestPermissionsResult(requestCode, permissions, grantResults);
     *       BLEAndroidPlugin.getInstance().onRequestPermissionsResult(requestCode, permissions, grantResults);
     *   }
     *
     * Without this wiring, permission results are silently discarded and Unity
     * will never be notified of the user's decision.
     *
     * @param requestCode   The request code passed to requestPermissions().
     * @param permissions   The permission strings that were requested.
     * @param grantResults  PERMISSION_GRANTED or PERMISSION_DENIED per permission.
     */
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        // Filter only our own permission request — other plugins may also
        // request permissions and their results will arrive here too.
        if (requestCode != REQUEST_BLE_PERMISSIONS) {
            return;
        }

        Log.d(TAG, "onRequestPermissionsResult: requestCode=" + requestCode);

        // Check that ALL requested permissions were granted.
        // A single denial is enough to block BLE operation.
        boolean allGranted = true;
        for (int i = 0; i < grantResults.length; i++) {
            String permName = (permissions != null && i < permissions.length) ? permissions[i] : "?";
            boolean granted = (grantResults[i] == PackageManager.PERMISSION_GRANTED);
            Log.d(TAG, "  " + permName + " → " + (granted ? "GRANTED" : "DENIED"));
            if (!granted) {
                allGranted = false;
            }
        }

        if (allGranted) {
            Log.i(TAG, "All BLE permissions granted by user.");
            sendToUnity(UNITY_METHOD_PERMISSIONS, PAYLOAD_PERMISSIONS_OK);
        } else {
            Log.w(TAG, "One or more BLE permissions denied by user.");
            sendToUnity(UNITY_METHOD_PERMISSIONS, PAYLOAD_PERMISSIONS_DENY);
        }
    }

    // ─── Unity Communication ─────────────────────────────────────────────────

    /**
     * Send a message to the Unity GameObject registered during initialize().
     *
     * Wraps UnityPlayer.UnitySendMessage with null-safety and logging.
     * All callbacks from this class and future BLE classes (BLEScan,
     * BLEConnection, BLEGatt) should route through this method rather
     * than calling UnitySendMessage directly, to ensure consistent logging
     * and a single point of failure/debugging.
     *
     * Threading: UnitySendMessage is thread-safe and automatically marshals
     * the call to the Unity main thread. It is safe to call from any thread,
     * including BLE callback threads (BluetoothGattCallback) or background
     * handler threads.
     *
     * @param method   The name of the C# public method on unityGameObjectName.
     * @param payload  A string payload passed as the method's single argument.
     */
    private void sendToUnity(String method, String payload) {
        if (unityGameObjectName == null || unityGameObjectName.isEmpty()) {
            Log.e(TAG, "sendToUnity called before initialize(). Dropping: " + method + "(" + payload + ")");
            return;
        }
        Log.d(TAG, "→ UnitySendMessage(" + unityGameObjectName + ", " + method + ", " + payload + ")");
        UnityPlayer.UnitySendMessage(unityGameObjectName, method, payload);
    }

    // ─── Accessors ───────────────────────────────────────────────────────────

    /**
     * Returns the initialized BluetoothAdapter.
     *
     * Used by BLEScan, BLEConnection, and BLEGatt to access the shared
     * adapter without re-initializing. Returns null if initialize() has
     * not been called or if initialization failed.
     *
     * @return The BluetoothAdapter, or null.
     */
    public BluetoothAdapter getBluetoothAdapter() {
        return bluetoothAdapter;
    }

    /**
     * Returns the Unity GameObject name used for UnitySendMessage callbacks.
     *
     * Used by subclasses (BLEScan, BLEConnection, BLEGatt) so they can
     * share the same callback target without each storing a copy.
     *
     * @return The Unity GameObject name, or null if not yet initialized.
     */
    public String getUnityGameObjectName() {
        return unityGameObjectName;
    }
}
