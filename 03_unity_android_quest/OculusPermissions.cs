// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : OculusPermissions.cs — Runtime BLE Permission Helper
// Platform: Android / Meta Quest (Unity)
// Sprint  : Sprint 81
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : Request and verify the Android runtime permissions required for
//           BLE scanning and connecting. Handles both the legacy API ≤ 30
//           (ACCESS_FINE_LOCATION) and the modern API 31+ (BLUETOOTH_SCAN +
//           BLUETOOTH_CONNECT) permission models in a single helper.
//
//           Meta Quest 2, 3, and Pro all run Android 12+ (API 32/33). Modern
//           Quest firmware (2024+) always uses the API 31+ model. The legacy
//           path is retained for Android phone compatibility.
//
// Placement in Unity project:
//   Assets/Scripts/BLE/OculusPermissions.cs
//
// Usage:
//   // 1. Check before scanning:
//   if (!OculusPermissions.HasBLEPermissions())
//   {
//       OculusPermissions.RequestBLEPermissions(
//           onGranted: () => BLEManager.Instance.Initialize(),
//           onDenied:  () => Debug.LogError("[BLE] Permission denied.")
//       );
//   }
//   else
//   {
//       BLEManager.Instance.Initialize();
//   }
//
// Permission Model Reference:
//   https://developer.android.com/guide/topics/connectivity/bluetooth/permissions
// ─────────────────────────────────────────────────────────────────────────────

using System;
using UnityEngine;
#if UNITY_ANDROID
using UnityEngine.Android;
#endif

namespace BLEReference
{
    /// <summary>
    /// Static helper that requests and verifies Android BLE runtime permissions
    /// for both Android phones and Meta Quest headsets.
    ///
    /// Quest IS Android — this class works identically on Quest 2, 3, Pro, and
    /// standard Android phones without any platform branching beyond API level.
    ///
    /// Threading: All Unity permission APIs must be called from the main thread.
    /// The callbacks <c>onGranted</c> and <c>onDenied</c> are invoked synchronously
    /// from the same frame when permissions are already resolved, or asynchronously
    /// (on the Unity main thread) when the system permission dialog completes.
    /// </summary>
    public static class OculusPermissions
    {
        // ─── Android Permission String Constants ──────────────────────────────
        // Using string literals matching android.Manifest.permission values.
        // UnityEngine.Android.Permission provides some constants (e.g.,
        // Permission.CoarseLocation) but does NOT include the BLE-specific
        // API 31+ permissions. Defining them explicitly avoids hidden coupling
        // to Unity version-specific constant availability.

        /// <summary>Required on API 31+ to start and stop BLE scans.</summary>
        private const string BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN";

        /// <summary>Required on API 31+ to connect to BLE peripherals and read device names.</summary>
        private const string BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT";

        /// <summary>Required on API ≤ 30 to start BLE scans (legacy location-gated model).</summary>
        private const string ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION";

        // ─── Android API Level Constants ──────────────────────────────────────
        // API 31 = Android 12 ("S" / Snow Cone). This is the threshold at which
        // BLUETOOTH_SCAN + BLUETOOTH_CONNECT replaced the location-based model.

        /// <summary>Android 12 / API 31 — threshold for the modern BLE permission model.</summary>
        private const int API_LEVEL_ANDROID_12 = 31;


        // ─── Public API ───────────────────────────────────────────────────────

        /// <summary>
        /// Returns true if all BLE permissions required on the current device
        /// API level are already granted; false otherwise.
        ///
        /// API 31+ (Android 12 / Quest): checks BLUETOOTH_SCAN + BLUETOOTH_CONNECT.
        /// API ≤ 30 (Android 11 and below): checks ACCESS_FINE_LOCATION.
        ///
        /// On non-Android platforms (Editor, iOS, etc.) this always returns true,
        /// so that Editor-only BLE simulation code is not blocked by permission gates.
        /// </summary>
        /// <returns>True if all required BLE permissions are currently granted.</returns>
        public static bool HasBLEPermissions()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            if (GetAndroidApiLevel() >= API_LEVEL_ANDROID_12)
            {
                // API 31+: need both SCAN and CONNECT.
                bool hasScan    = Permission.HasUserAuthorizedPermission(BLUETOOTH_SCAN);
                bool hasConnect = Permission.HasUserAuthorizedPermission(BLUETOOTH_CONNECT);
                Debug.Log($"[OculusPermissions] API 31+ check — SCAN:{hasScan}  CONNECT:{hasConnect}");
                return hasScan && hasConnect;
            }
            else
            {
                // API ≤ 30: need location for BLE scanning.
                bool hasLocation = Permission.HasUserAuthorizedPermission(ACCESS_FINE_LOCATION);
                Debug.Log($"[OculusPermissions] API ≤ 30 check — ACCESS_FINE_LOCATION:{hasLocation}");
                return hasLocation;
            }
#else
            // In the Editor or on non-Android platforms, permissions are always
            // considered granted so that BLE simulation code runs unblocked.
            return true;
#endif
        }

        /// <summary>
        /// Requests the BLE permissions appropriate for the current Android API level.
        ///
        /// If all required permissions are already granted, <paramref name="onGranted"/>
        /// is invoked synchronously in the same frame without showing any dialog.
        ///
        /// If any permission is missing, the Android system permission dialog is
        /// shown. The result is delivered asynchronously via Unity's
        /// <c>PermissionCallbacks</c> object. Both <paramref name="onGranted"/>
        /// and <paramref name="onDenied"/> are dispatched on the Unity main thread.
        ///
        /// On non-Android platforms (Editor), <paramref name="onGranted"/> is
        /// called immediately.
        ///
        /// API 31+ (Android 12 / Quest): requests BLUETOOTH_SCAN + BLUETOOTH_CONNECT.
        /// API ≤ 30 (Android 11 and below): requests ACCESS_FINE_LOCATION.
        ///
        /// Important — do NOT request ACCESS_FINE_LOCATION on API 31+ alongside
        /// BLUETOOTH_SCAN when neverForLocation is declared in the manifest.
        /// Doing so is contradictory and will confuse users with an unnecessary
        /// location dialog. The permission split is handled automatically here.
        /// </summary>
        /// <param name="onGranted">
        ///   Callback invoked when all required permissions are granted.
        ///   Called synchronously if permissions are already present; asynchronously
        ///   after the system dialog otherwise. Must not be null.
        /// </param>
        /// <param name="onDenied">
        ///   Callback invoked if any required permission is denied. Optional.
        ///   Called asynchronously after the system dialog.
        /// </param>
        /// <exception cref="ArgumentNullException">
        ///   Thrown if <paramref name="onGranted"/> is null.
        /// </exception>
        public static void RequestBLEPermissions(Action onGranted, Action onDenied = null)
        {
            if (onGranted == null)
                throw new ArgumentNullException(nameof(onGranted));

#if UNITY_ANDROID && !UNITY_EDITOR
            // Fast path: permissions already granted — skip the dialog entirely.
            if (HasBLEPermissions())
            {
                Debug.Log("[OculusPermissions] All BLE permissions already granted — skipping dialog.");
                onGranted.Invoke();
                return;
            }

            int apiLevel = GetAndroidApiLevel();
            Debug.Log($"[OculusPermissions] Requesting BLE permissions for API {apiLevel}.");

            if (apiLevel >= API_LEVEL_ANDROID_12)
            {
                // ── API 31+ (Android 12 / Quest) ──────────────────────────────
                // Request BLUETOOTH_SCAN and BLUETOOTH_CONNECT together.
                // Unity's Permission.RequestUserPermissions() accepts an array
                // and a PermissionCallbacks object for result handling.
                // Note: we do NOT request ACCESS_FINE_LOCATION here — it is
                // unnecessary on API 31+ when neverForLocation is declared in
                // AndroidManifest.xml (see Section 2 of AndroidManifest.xml).
                RequestMultiple(
                    new[] { BLUETOOTH_SCAN, BLUETOOTH_CONNECT },
                    onGranted,
                    onDenied
                );
            }
            else
            {
                // ── API ≤ 30 (Android 11 and below) ──────────────────────────
                // Location permission gates BLE scanning on these older API levels.
                // Unity's Permission.RequestUserPermission() handles single permissions.
                RequestSingle(ACCESS_FINE_LOCATION, onGranted, onDenied);
            }
#else
            // Editor / non-Android: treat as already granted.
            Debug.Log("[OculusPermissions] Non-Android platform — permissions auto-granted.");
            onGranted.Invoke();
#endif
        }


        // ─── Private Helpers ──────────────────────────────────────────────────

#if UNITY_ANDROID && !UNITY_EDITOR

        /// <summary>
        /// Requests a single Android runtime permission and dispatches the
        /// appropriate callback when the user responds to the system dialog.
        /// </summary>
        /// <param name="permission">The Android permission string to request.</param>
        /// <param name="onGranted">Invoked when the permission is granted.</param>
        /// <param name="onDenied">Invoked when the permission is denied. May be null.</param>
        private static void RequestSingle(string permission, Action onGranted, Action onDenied)
        {
            var callbacks = new PermissionCallbacks();

            callbacks.PermissionGranted += _ =>
            {
                Debug.Log($"[OculusPermissions] {permission} — GRANTED.");
                onGranted.Invoke();
            };

            callbacks.PermissionDenied += _ =>
            {
                Debug.LogWarning($"[OculusPermissions] {permission} — DENIED.");
                onDenied?.Invoke();
            };

            callbacks.PermissionDeniedAndDontAskAgain += _ =>
            {
                // User selected "Don't ask again". The app cannot show the dialog
                // again. Direct the user to Settings to grant the permission manually.
                Debug.LogWarning($"[OculusPermissions] {permission} — DENIED (don't ask again). " +
                                 "Direct the user to Settings > Apps > [AppName] > Permissions.");
                onDenied?.Invoke();
            };

            Permission.RequestUserPermission(permission, callbacks);
        }

        /// <summary>
        /// Requests multiple Android runtime permissions simultaneously and
        /// dispatches the appropriate callback only when ALL permissions are resolved.
        ///
        /// <c>onGranted</c> is invoked only if every permission in
        /// <paramref name="permissions"/> was granted. <c>onDenied</c> is invoked
        /// if any single permission was denied.
        /// </summary>
        /// <param name="permissions">Array of Android permission strings to request.</param>
        /// <param name="onGranted">Invoked when all permissions are granted.</param>
        /// <param name="onDenied">Invoked when any permission is denied. May be null.</param>
        private static void RequestMultiple(string[] permissions, Action onGranted, Action onDenied)
        {
            // Track responses: count how many permissions have been resolved
            // (granted or denied) before firing the final callback.
            int total    = permissions.Length;
            int resolved = 0;
            bool anyDenied = false;

            var callbacks = new PermissionCallbacks();

            callbacks.PermissionGranted += permName =>
            {
                Debug.Log($"[OculusPermissions] {permName} — GRANTED.");
                resolved++;
                CheckAllResolved();
            };

            callbacks.PermissionDenied += permName =>
            {
                Debug.LogWarning($"[OculusPermissions] {permName} — DENIED.");
                anyDenied = true;
                resolved++;
                CheckAllResolved();
            };

            callbacks.PermissionDeniedAndDontAskAgain += permName =>
            {
                Debug.LogWarning($"[OculusPermissions] {permName} — DENIED (don't ask again). " +
                                 "Direct the user to Settings > Apps > [AppName] > Permissions.");
                anyDenied = true;
                resolved++;
                CheckAllResolved();
            };

            // Local function: fires the final callback once all permissions are settled.
            void CheckAllResolved()
            {
                if (resolved < total) return;

                if (anyDenied)
                    onDenied?.Invoke();
                else
                    onGranted.Invoke();
            }

            Permission.RequestUserPermissions(permissions, callbacks);
        }

        /// <summary>
        /// Returns the current device's Android API level as an integer.
        ///
        /// Uses AndroidJavaClass to read android.os.Build.VERSION.SDK_INT.
        /// This value is reliable at runtime and matches the API level used
        /// by the permission model checks throughout this class.
        /// </summary>
        /// <returns>Android API level integer (e.g., 33 for Android 13).</returns>
        private static int GetAndroidApiLevel()
        {
            using (var version = new AndroidJavaClass("android.os.Build$VERSION"))
            {
                return version.GetStatic<int>("SDK_INT");
            }
        }

#endif // UNITY_ANDROID && !UNITY_EDITOR

    }
}
