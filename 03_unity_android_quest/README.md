# Unity Android / Meta Quest — BLE Plugin Setup Guide

Phase 04 · Sprint 81

This guide covers integrating the BLE Reference Library Android plugin into a Unity project targeting both Android phones (API 26+) and Meta Quest headsets (Quest 2, 3, Pro).

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Project Setup in Unity](#2-project-setup-in-unity)
3. [Copying Files into Your Unity Project](#3-copying-files-into-your-unity-project)
4. [Meta Quest Specific Notes](#4-meta-quest-specific-notes)
5. [Building the .aar Plugin](#5-building-the-aar-plugin)
6. [Testing Checklist](#6-testing-checklist)
7. [Known Issues](#7-known-issues)

---

## 1. Prerequisites

| Requirement | Version / Notes |
|---|---|
| Unity | 2022.3 LTS (recommended) or 2021.3 LTS minimum |
| Android Build Support | Install via Unity Hub → Installs → Add Modules |
| Android SDK | API 33 (Android 13) — installed by Unity Hub or Android Studio |
| Android NDK | Bundled with Unity; required for IL2CPP builds |
| Java JDK | 11 (required by Unity 2022+). Java 17 works; avoid Java 8 |

> **Android Studio n'est pas nécessaire.** Unity compile les fichiers `.java` directement lors du build Android — pas besoin de pré-compiler en `.aar`.
| Meta Quest device | Quest 2, 3, or Pro. Developer Mode must be enabled (see Section 4) |
| ADB | Included with Android Studio platform-tools |

**Unity modules to install:**
- Android Build Support
- Android SDK & NDK Tools (bundled with Unity is fine)
- OpenJDK (bundled with Unity 2022+)

---

## 2. Project Setup in Unity

### Build Target

1. Open **File → Build Settings**.
2. Select **Android** and click **Switch Platform**.
3. Click **Player Settings**.

### Player Settings — Android

| Setting | Value | Why |
|---|---|---|
| Minimum API Level | API 26 (Android 8.0) | BLE is fully stable from API 26 |
| Target API Level | API 33 (Android 13) | Required for Meta Horizon Store submission |
| Scripting Backend | **IL2CPP** (preferred) or Mono | IL2CPP required for Quest release builds |
| Target Architectures | ARM64 ✓ (required for Quest); ARMv7 optional | Quest 2+ are ARM64-only |
| Internet Access | Auto or Require | Not needed for BLE, but leave at default |
| Write Permission | External (SDCard) | Only if you log to external storage |

### Meta Quest — Additional Player Settings

1. In **Player Settings → XR Plug-in Management**, enable **OpenXR** (for Quest 3 / MR).
2. Under **OpenXR → Feature Groups**, enable **Meta Quest Support**.
3. Set **Stereo Rendering Mode** to **Single Pass Instanced** for Quest 3 performance.

> **Note:** BLE operations do not require XR Plug-in Management. OpenXR is only needed for the VR rendering pipeline. A 2D (pancake) Android build targeting Quest requires neither OpenXR nor the XR Plug-in.

---

## 3. Copying Files into Your Unity Project

The repository structure for this module is:

```
03_unity_android_quest/
├── AndroidPlugin/
│   ├── AndroidManifest.xml          ← Plugin library manifest (merged into .aar)
│   ├── BLEAndroidPlugin.java        ← Entry point: initialize, permissions
│   ├── BLEScan.java                 ← BLE device scanning
│   ├── BLEConnection.java           ← Connect / disconnect
│   ├── BLEGatt.java                 ← GATT service/characteristic access
│   ├── BLECommandQueue.java         ← Serialized GATT command execution
│   ├── BLEDeviceInfo.java           ← Scanned device model
│   ├── BLEDFU.java                  ← Device firmware update support
│   ├── BLEMTU.java                  ← MTU negotiation
│   ├── BLEPairing.java              ← Bonding / pairing
│   ├── BLEPeripheral.java           ← Peripheral model
│   ├── BLERSSIDistance.java         ← RSSI-to-distance estimation
│   └── MuseEEG.java                 ← Muse EEG profile constants
│
├── AndroidManifest.xml              ← Unity project root manifest  ← COPY THIS
├── OculusPermissions.cs             ← Runtime permission helper     ← COPY THIS
└── README.md                        ← This file
```

### Step-by-step file placement

#### 3.1 — AndroidManifest.xml (Unity project root)

Copy `03_unity_android_quest/AndroidManifest.xml` (this directory, NOT the one inside `AndroidPlugin/`) to:

```
YourUnityProject/
└── Assets/
    └── Plugins/
        └── Android/
            └── AndroidManifest.xml    ← destination
```

Create the `Assets/Plugins/Android/` directory if it does not exist. Unity will automatically pick up any manifest at this path during the Gradle build.

> If you already have a manifest at this path from another plugin, merge the `<uses-permission>` and `<uses-feature>` entries manually rather than replacing it. Use Android's manifest merger attributes (`tools:replace`, `tools:merge`) where needed.

#### 3.2 — OculusPermissions.cs

Copy `OculusPermissions.cs` to:

```
Assets/
└── Scripts/
    └── BLE/
        └── OculusPermissions.cs
```

#### 3.3 — Java plugin (fichiers source directement)

Copier tous les fichiers `.java` depuis `AndroidPlugin/` vers :

```
Assets/
└── Plugins/
    └── Android/
        ├── BLEAndroidPlugin.java
        ├── BLEScan.java
        ├── BLEConnection.java
        ├── BLEGatt.java
        ├── BLEDeviceInfo.java
        ├── MuseEEG.java
        ├── BLEMTU.java
        ├── BLEPairing.java
        ├── BLEPeripheral.java
        ├── BLECommandQueue.java
        ├── BLERSSIDistance.java
        └── BLEDFU.java
```

Unity détecte automatiquement les fichiers `.java` dans `Assets/Plugins/Android/` et les compile lors du build Android. **Aucune étape de compilation séparée n'est nécessaire.**

#### 3.4 — C# scripts from Phase 02 (if applicable)

If you are adapting the Phase 02 Unity Editor scripts:

```
02_unity_editor/Scripts/    →    Assets/Scripts/BLE/
```

These scripts use `AndroidJavaObject` calls that target `com.bleref.unity.BLEAndroidPlugin`, which is defined in the .aar.

---

## 4. Meta Quest Specific Notes

### Quest IS Android

Meta Quest headsets run a customized Android OS. The standard `android.bluetooth.*` API is fully supported and behaves identically to any Android phone. There is **no separate Oculus/Meta SDK required** for BLE operations.

What makes Quest different from a phone in BLE terms:

| Aspect | Android Phone | Meta Quest |
|---|---|---|
| BLE API | `android.bluetooth.*` | `android.bluetooth.*` (identical) |
| API level | Varies by device | Quest 2: 29–32 (firmware dependent); Quest 3: 33 |
| Permission model | API 31+ on modern phones | API 31+ (Quest 2 firmware 50+, Quest 3 always) |
| Location needed for BLE | No (API 31+) | No (API 31+) |
| VR manifest extras | Not applicable | `android.hardware.vr.headtracking` feature |

### Enabling Developer Mode (Required for Sideloading)

Before you can install a development build on Quest:

1. Create a Meta developer account at [developer.oculus.com](https://developer.oculus.com).
2. In the **Meta Quest mobile app** (iOS or Android): tap your headset → **More Settings → Developer Mode → Enable**.
3. Put on the headset and **accept the developer mode prompt** that appears.
4. Connect Quest via USB-C. Run `adb devices` — the headset should appear.
5. Inside the headset, a dialog asks "Allow USB debugging?" — tap **Allow**.

Without Developer Mode, `adb install` and Unity's **Build and Run** will fail silently.

### Quest Controller Idle Timeout and BLE Disconnects

Meta Quest applies an **inactivity timeout** (configurable in Settings → Power) that dims and eventually sleeps the display when controllers are idle. When the headset enters sleep:

- Active BLE connections will trigger `onConnectionStateChange` on the Java side with state `STATE_DISCONNECTED`.
- `BLEAndroidPlugin` will fire `OnBleError` or equivalent disconnect callbacks to Unity.
- On wake, the connection is not automatically restored — your C# `BLEManager` must detect the disconnect and reconnect.

**Recommended pattern:**

```csharp
void OnDeviceDisconnected(string deviceAddress)
{
    // Quest may have gone to sleep — attempt reconnect after a short delay.
    StartCoroutine(ReconnectAfterDelay(deviceAddress, delaySeconds: 2f));
}
```

### Testing in VR vs 2D Mode

- **VR mode (recommended for Quest):** Build with OpenXR enabled. The app launches in Quest's VR environment. BLE operations work identically.
- **2D mode ("pancake" / flat):** Disable OpenXR in XR Plug-in Management. The app runs in Quest's 2D app panel. Useful for BLE debugging without VR overhead. Use `adb logcat -s BLEAndroidPlugin` for real-time Java logs.

To switch between modes without rebuilding, you can check `OVRPlugin.nativeXrApi` at runtime (if using the Oculus Integration SDK) or simply build two separate APKs.

---

## 5. Building the .aar Plugin

The Java files in `AndroidPlugin/` must be compiled into an Android Archive (`.aar`) before they can be used in Unity.

### Option A — Android Studio (recommended)

1. **Open Android Studio** → **New Project** → **No Activity** → select **Java**, API 26, package `com.bleref.unity`.
2. Replace the generated `app/src/main/java/com/bleref/unity/` contents with the `.java` files from `AndroidPlugin/`.
3. Copy `AndroidPlugin/AndroidManifest.xml` to `app/src/main/AndroidManifest.xml`.
4. In `app/build.gradle`, ensure:
   ```gradle
   plugins {
       id 'com.android.library'   // NOT 'com.android.application'
   }
   android {
       compileSdk 33
       defaultConfig {
           minSdk 26
           targetSdk 33
       }
   }
   dependencies {
       compileOnly fileTree(dir: 'libs', include: ['*.jar'])   // Unity classes.jar
       implementation 'androidx.core:core:1.10.1'
   }
   ```
5. Copy Unity's `classes.jar` (found in your Unity installation at `Editor/Data/PlaybackEngines/AndroidPlayer/Variations/il2cpp/Release/Classes/classes.jar`) into `app/libs/`.
6. Run **Build → Make Module 'app'**.
7. The `.aar` is output to `app/build/outputs/aar/app-release.aar`.
8. Rename it to `BLEAndroidPlugin.aar` and place it in `Assets/Plugins/Android/`.

### Option B — Command line (Gradle)

```bash
cd YourAndroidStudioProject/
./gradlew :app:assembleRelease
# Output: app/build/outputs/aar/app-release.aar
```

### Verifying the .aar

```bash
# Inspect the .aar contents (it is a zip archive)
unzip -l BLEAndroidPlugin.aar | grep -E "\.class|Manifest"
```

You should see `.class` files for `BLEAndroidPlugin`, `BLEScan`, `BLEConnection`, etc., and `AndroidManifest.xml`.

---

## 6. Testing Checklist

Work through this checklist before running the Phase 04 integration test.

### On Device (Quest or Android Phone)

- [ ] `adb devices` lists the device (Developer Mode enabled, USB debugging allowed)
- [ ] Unity **Build and Run** completes without errors
- [ ] App launches on device without crashing
- [ ] BLE permissions dialog appears on first launch
- [ ] Permissions granted — no `SecurityException` in logcat
- [ ] `adb logcat -s BLEAndroidPlugin` shows `OnBleReady: adapter_ok`
- [ ] BLE scan discovers at least one nearby device (phone, headphones, etc.)
- [ ] Polar H10 appears in scan results (if available)
- [ ] Connection to Polar H10 succeeds
- [ ] HR notifications arrive (values in expected physiological range: 40–200 bpm)
- [ ] ECG notifications arrive (if ECG streaming is enabled in BLEGatt)
- [ ] Disconnect (power off Polar H10) triggers disconnect callback in Unity
- [ ] Reconnect attempt after disconnect succeeds

### Quest-Specific

- [ ] App appears in Quest App Library (VR section if VR manifest filter is present)
- [ ] App does not crash when Quest display goes to sleep mid-stream
- [ ] BLE disconnect event fires within ~5 seconds of Quest entering sleep
- [ ] Reconnect succeeds after Quest wakes from sleep
- [ ] No logcat errors related to `BLUETOOTH_SCAN` or `BLUETOOTH_CONNECT` permissions

### Logcat Filtering

```bash
# All BLE Reference Library logs
adb logcat -s BLEAndroidPlugin BLEScan BLEConnection BLEGatt

# Unity logs only
adb logcat -s Unity

# All logs with timestamps (verbose)
adb logcat -v time | grep -E "BLE|Unity|bluetooth"
```

---

## 7. Known Issues

### Quest Sleep → Unexpected Disconnect

**Symptom:** `onConnectionStateChange` fires with `STATE_DISCONNECTED` after the Quest display times out (no user input for ~30–60 seconds).

**Cause:** Quest's display power management suspends background activity. Android does not hold a BLE connection wake lock by default.

**Workaround:**
- Acquire a `WakeLock` in the Java plugin to prevent CPU sleep while streaming:
  ```java
  PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
  wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BLERef::BLEStream");
  wakeLock.acquire();
  // Release in disconnect / cleanup
  ```
- Add `android.permission.WAKE_LOCK` to `AndroidManifest.xml`.
- Alternatively, use Quest's **Stay Awake** developer option (Settings → Developer → Stay Awake While Charging).

### Quest Battery Saver Throttles BLE Scan

**Symptom:** BLE scan results become sparse or stop entirely when Quest battery drops below ~20%.

**Cause:** Quest's battery saver mode reduces BLE scan duty cycle.

**Workaround:** For development and clinical streaming sessions, keep Quest plugged into USB-C power. For production, detect the battery saver state (`PowerManager.isPowerSaveMode()`) and warn the user.

### Device Name Returns null on API 31+

**Symptom:** `BluetoothDevice.getName()` returns `null` even after a successful scan.

**Cause:** On API 31+, reading the device name requires `BLUETOOTH_CONNECT` permission, not just `BLUETOOTH_SCAN`. If `BLUETOOTH_CONNECT` is not yet granted when `getName()` is called, Android returns null silently.

**Fix:** Always request both `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` before initiating a scan. `OculusPermissions.RequestBLEPermissions()` handles this automatically.

### IL2CPP Stripping Removes AndroidJavaObject Bridge

**Symptom:** Calls to `BLEAndroidPlugin` via `AndroidJavaObject` fail at runtime with `EntryPointNotFoundException` or similar.

**Cause:** IL2CPP code stripping can remove JNI bridge classes that appear unreferenced.

**Fix:** Add a `link.xml` file to your `Assets/` folder:
```xml
<linker>
    <assembly fullname="UnityEngine" preserve="all" />
    <assembly fullname="Assembly-CSharp" preserve="all" />
</linker>
```

### Permission Dialog Does Not Appear (Quest Developer Builds)

**Symptom:** `OculusPermissions.RequestBLEPermissions()` is called but no dialog appears. `HasBLEPermissions()` returns false.

**Cause:** If the app was previously installed with permissions auto-denied or if the Quest OS cached a denied state, the dialog is suppressed.

**Fix:**
1. Uninstall the app: `adb uninstall com.bleref.unity`
2. Reinstall and launch fresh.
3. Alternatively, reset permissions via: `adb shell pm reset-permissions com.bleref.unity`
