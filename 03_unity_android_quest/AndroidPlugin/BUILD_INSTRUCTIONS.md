# Building the BLE Android Plugin — AAR for Unity

This guide walks through compiling the Java BLE plugin into a `.aar` file that
Unity imports under `Assets/Plugins/Android/`.

---

## Prerequisites

| Requirement | Version |
|---|---|
| Android Studio | Hedgehog (2023.1) or later |
| Android SDK | API 33 (compileSdk) |
| JDK | 11 or later (bundled with Android Studio) |
| Unity | 2022.3 LTS or later (for classes.jar path) |

---

## Step 1 — Clone / open the project

Open `03_unity_android_quest/AndroidPlugin/` as the **root** of an Android
Studio project (File → Open → select the `AndroidPlugin/` folder).

---

## Step 2 — Obtain `unity-classes.jar`

The plugin calls `UnityPlayer.UnitySendMessage`, which is provided by Unity's
Android runtime. You must copy (or symlink) the jar from your Unity installation
into `libs/`:

```
# macOS example — adjust the version number (2022.3.xx)
cp "/Applications/Unity/Hub/Editor/2022.3.xx/PlaybackEngines/AndroidPlayer/\
Variations/mono/Release/Classes/classes.jar" \
   libs/unity-classes.jar
```

Windows path:
```
C:\Program Files\Unity\Hub\Editor\2022.3.xx\Editor\Data\PlaybackEngines\
    AndroidPlayer\Variations\mono\Release\Classes\classes.jar
```

The jar is excluded from version control (see `.gitignore`). You must repeat
this step on each machine where you build.

---

## Step 3 — Move the Java source files

The `.java` source files currently live in `AndroidPlugin/` (next to
`build.gradle`). Android Gradle requires them at the standard source-set path:

```
src/main/java/com/bleref/unity/
```

Move all `.java` files there:

```bash
mv BLEAndroidPlugin.java BLECommandQueue.java BLEConnection.java \
   BLEDeviceInfo.java BLEDFU.java BLEGatt.java BLEMTU.java \
   BLEPairing.java BLEPeripheral.java BLERSSIDistance.java \
   BLEScan.java MuseEEG.java \
   src/main/java/com/bleref/unity/
```

---

## Step 4 — Sync Gradle

In Android Studio: **File → Sync Project with Gradle Files** (or click the
elephant icon in the toolbar). Resolve any SDK/JDK version prompts.

---

## Step 5 — Build the release AAR

**From Android Studio:**
Build → Make Project, or run the `assembleRelease` task from the Gradle panel
(View → Tool Windows → Gradle → Tasks → build → assembleRelease).

**From the command line** (no Android Studio needed after initial sync):

```bash
cd 03_unity_android_quest/AndroidPlugin
./gradlew assembleRelease
```

---

## Step 6 — Locate the output

The compiled library is at:

```
build/outputs/aar/BLEAndroidPlugin-release.aar
```

---

## Step 7 — Copy the AAR into Unity

Copy the `.aar` to your Unity project:

```
Assets/Plugins/Android/BLEAndroidPlugin-release.aar
```

Unity detects `.aar` files in that directory automatically. No further
configuration is needed for the plugin to be bundled in Android builds.

---

## Step 8 — Copy the BLE manifest into Unity

Copy the permissions manifest so Unity merges it at build time:

```
AndroidPlugin/AndroidManifest.xml  →  Assets/Plugins/Android/AndroidManifest.xml
```

If other plugins already supply a manifest in that folder, merge the BLE
`<uses-permission>` and `<uses-feature>` entries manually.

---

## Step 9 — Build your Unity project for Android

In Unity: File → Build Settings → select **Android** → Build.

The BLE plugin and its permissions will be bundled automatically.

---

## Troubleshooting

| Problem | Likely cause | Fix |
|---|---|---|
| `cannot find symbol: UnitySendMessage` | `libs/unity-classes.jar` missing | Repeat Step 2 |
| `Duplicate class com.unity3d...` | classes.jar accidentally bundled | Ensure it is `compileOnly` in build.gradle |
| `Manifest merger failed` | Two manifests with conflicting entries | Consolidate into one manifest or use merger rules |
| `minSdk too low` | Quest requires API 26 | Already set in build.gradle — do not lower |
