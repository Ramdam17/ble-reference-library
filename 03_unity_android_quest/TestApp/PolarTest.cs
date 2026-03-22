// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : PolarTest.cs — Polar H10 Integration Test (Quest / Android)
// Platform: Android (Unity) — Quest 2 / 3 / Pro
// Sprint  : Sprint 81
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : 11-step integration test that validates the BLEPluginAndroid bridge
//           against a real Polar H10 heart rate sensor. Mirrors the equivalent
//           test sequences in:
//             Phase 02 — Swift/CoreBluetooth  (09_xcode_apple/TestApp/)
//             Phase 03 — Web BLE / TypeScript (03_web_ble/TestApp/polar-runner.js)
//
// Test sequence:
//   Step 01 — Initialize BLE (BLEManager.Initialize)
//   Step 02 — Request permissions (BLUETOOTH_SCAN, BLUETOOTH_CONNECT)
//   Step 03 — Scan for Polar device (name filter "Polar", 10 s timeout)
//   Step 04 — Connect (15 s timeout, GATT error-133 retry once)
//   Step 05 — Discover services
//   Step 06 — Read Device Information Service (DIS)
//   Step 07 — Read Battery Level
//   Step 08 — Subscribe to Heart Rate Measurement (0x2A37, service 0x180D)
//   Step 09 — Start ECG stream (write PMD control command — 130 Hz)
//   Step 10 — Collect 30 s of HR + ECG data
//   Step 11 — Disconnect + report (samples, HR range, ECG std dev)
//
// Pass criteria:
//   • At least one HRM notification received with HR in [30, 220] bpm
//   • ECG std dev > 10 µV (confirms live signal, not a flat line)
//
// Polar PMD Protocol references:
//   Polar Sensor SDK — polar-ble-sdk GitHub (polar-flow-app/sdk)
//   ECG command byte sequence: start ECG at 130 Hz, resolution 14-bit → 1 µV/LSB
//   PMD UUIDs: see constants below (verified against SDK source, March 2024)
//
// Usage:
//   Attach to any GameObject in the TestApp scene.
//   Wire BLETestUI via the Inspector, OR call Run(ui) from BLETestUI button handler.
// ─────────────────────────────────────────────────────────────────────────────

using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using UnityEngine;

namespace BLEReference
{
    /// <summary>
    /// Polar H10 integration test for the Phase 04 Unity / Quest TestApp.
    ///
    /// Runs an 11-step coroutine that exercises BLEManager scan → connect →
    /// discover → read → subscribe → ECG stream → disconnect. Reports pass/fail
    /// with HR statistics and ECG signal quality metrics to BLETestUI.
    /// </summary>
    public class PolarTest : MonoBehaviour
    {
        // ══════════════════════════════════════════════════════════════════════
        // BLE UUIDs — Polar H10
        // ══════════════════════════════════════════════════════════════════════

        // Standard Bluetooth SIG — Heart Rate Service
        // Spec: GATT Assigned Numbers, Service 0x180D
        private const string HEART_RATE_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb";

        // Standard GATT — Heart Rate Measurement characteristic
        // Format: flags(1B) + hr_value(1-2B) + [rr_intervals(0-nB)]
        private const string HEART_RATE_CHAR    = "00002a37-0000-1000-8000-00805f9b34fb";

        // Standard Bluetooth SIG — Device Information Service (DIS)
        private const string DEVICE_INFO_SERVICE = "0000180a-0000-1000-8000-00805f9b34fb";

        // Standard Bluetooth SIG — Battery Service
        private const string BATTERY_SERVICE     = "0000180f-0000-1000-8000-00805f9b34fb";

        // Polar Measurement Data (PMD) Service — proprietary Polar UUID
        // Source: polar-ble-sdk / sdk/src/.../PolarBleApiDefaultImpl.kt
        // Note: The full 128-bit UUID encodes the Polar company identifier.
        private const string POLAR_PMD_SERVICE  = "fb005c80-02e7-f387-1cad-8acd2d8df0c8";

        // PMD Control Point — write ECG start/stop commands; notify for responses
        private const string POLAR_PMD_CONTROL  = "fb005c81-02e7-f387-1cad-8acd2d8df0c8";

        // PMD Data — notifications carry ECG (type 0x00) or ACC (type 0x02) packets
        private const string POLAR_PMD_DATA     = "fb005c82-02e7-f387-1cad-8acd2d8df0c8";

        // ══════════════════════════════════════════════════════════════════════
        // Timing constants
        // ══════════════════════════════════════════════════════════════════════

        // Scan window: 10 s is sufficient for a device within ~2 m on Quest.
        private const float SCAN_TIMEOUT          = 10f;

        // GATT connection timeout. Android error 133 (GATT_ERROR) is common on
        // first connection attempt; the coroutine retries once before failing.
        private const float CONNECT_TIMEOUT       = 15f;

        // ECG collection window mirrors Phase 02 and Phase 03 (30 s).
        private const float ECG_DURATION_SECONDS  = 30f;

        // Each step gets a generous async timeout before the test hard-fails.
        private const float STEP_TIMEOUT          = 20f;

        // ══════════════════════════════════════════════════════════════════════
        // PMD ECG start command
        // ══════════════════════════════════════════════════════════════════════
        // Binary sequence to write to POLAR_PMD_CONTROL to start ECG at 130 Hz.
        //
        // Byte breakdown (Polar SDK PMD protocol spec):
        //   [0] 0x02       — operation code: START_MEASUREMENT
        //   [1] 0x00       — measurement type: ECG (0x00)
        //   [2] 0x00       — reserved
        //   [3] 0x01       — setting count: 1
        //   [4] 0x82 0x00  — setting type 0x82 (SAMPLE_RATE), value 0x0000 → 130 Hz
        //   [6] 0x01       — array count: 1
        //   [7] 0x01       — array length
        //   [8] 0x0E 0x00  — 14-bit resolution (0x000E), 1 µV / LSB
        //
        // References:
        //   Polar BLE SDK source: PmdSetting.kt, PmdControlPointCommand.kt
        //   ECG 130 Hz verified on Polar H10 firmware 3.1.x+
        private static readonly byte[] ECG_START_COMMAND = { 0x02, 0x00, 0x00, 0x01, 0x82, 0x00, 0x01, 0x01, 0x0E, 0x00 };

        // ══════════════════════════════════════════════════════════════════════
        // Instance state
        // ══════════════════════════════════════════════════════════════════════

        private BLETestUI  _ui;
        private string     _deviceId;
        private bool       _running;

        // Data buffers populated during Step 10 (collection window).
        private readonly List<int>   _hrmSamples = new();
        private readonly List<float> _ecgSamples = new();

        // Step completion signals — set by BLE callbacks, polled by WaitUntil.
        private bool _scanDone;
        private bool _connectDone;
        private bool _servicesDone;
        private bool _batteryDone;

        private bool _scanOk;
        private bool _connectOk;
        private bool _servicesOk;

        private int  _batteryLevel = -1;

        // ══════════════════════════════════════════════════════════════════════
        // Public entry point
        // ══════════════════════════════════════════════════════════════════════

        /// <summary>
        /// Start the 11-step Polar H10 integration test.
        /// Called from BLETestUI button handler (polarButton.onClick).
        /// </summary>
        /// <param name="ui">The BLETestUI controller for log and status output.</param>
        public void Run(BLETestUI ui)
        {
            if (_running)
            {
                Debug.LogWarning("[PolarTest] Already running — ignoring duplicate Run() call.");
                return;
            }
            _ui = ui;
            StartCoroutine(RunSequence());
        }

        // ══════════════════════════════════════════════════════════════════════
        // Main coroutine — 11 steps
        // ══════════════════════════════════════════════════════════════════════

        /// <summary>
        /// Coroutine that executes the full 11-step integration test in sequence.
        /// Uses WaitUntil() with a per-step deadline to avoid infinite hangs if
        /// the device is unreachable or a callback is never fired.
        /// </summary>
        private IEnumerator RunSequence()
        {
            _running = true;
            _ui.SetRunning(true);
            _ui.ClearLog();
            _hrmSamples.Clear();
            _ecgSamples.Clear();

            _ui.Log("══ Polar H10 Integration Test — Phase 04 ══");
            _ui.SetStatus("Running...");

            // ── Step 01: Initialize BLE ────────────────────────────────────────

            _ui.Log("Step 01 — Initializing BLE manager...");
            BLEManager.Instance.Initialize();
            // Allow one frame for the plugin's Initialize() to complete its JNI calls.
            yield return null;
            _ui.Log("Step 01 — BLE initialized.");

            // ── Step 02: Request permissions ───────────────────────────────────

            _ui.Log("Step 02 — Requesting BLE permissions...");
            // OculusPermissions.cs (03_unity_android_quest) handles BLUETOOTH_SCAN
            // and BLUETOOTH_CONNECT via the Android permission API. We request and
            // then wait one second for the system dialog to resolve.
            // On Quest (Android 10+), BLUETOOTH_SCAN / BLUETOOTH_CONNECT are required
            // at runtime since Android 12+ permission model applies via OVR manifest.
            OculusPermissions.RequestBLEPermissions(
                onGranted: () => _ui.Log("Step 02 — Permissions: GRANTED."),
                onDenied:  () => _ui.Log("Step 02 — Permissions: DENIED. BLE operations may fail."));
            // Allow time for the permission dialog to be dismissed.
            yield return new WaitForSeconds(2f);
            _ui.Log("Step 02 — Permissions step complete.");

            // ── Step 03: Scan for Polar device ─────────────────────────────────

            _ui.Log($"Step 03 — Scanning for 'Polar' device ({SCAN_TIMEOUT}s)...");
            _scanDone = false;
            _scanOk   = false;
            _deviceId = null;

            BLEManager.Instance.StartScanFiltered(
                serviceUUIDs:    null,               // no service filter — scan by name
                nameFilter:      "Polar",
                minRSSI:         -90,
                durationSeconds: SCAN_TIMEOUT,
                onComplete: devices =>
                {
                    if (devices != null && devices.Length > 0)
                    {
                        _deviceId = devices[0].Id;
                        _ui.Log($"Step 03 — Found: {devices[0].Name} ({_deviceId}) RSSI={devices[0].RSSI}");
                        _scanOk = true;
                    }
                    else
                    {
                        _ui.Log("Step 03 — No Polar device found in scan window.");
                    }
                    _scanDone = true;
                });

            float deadline = Time.time + SCAN_TIMEOUT + 3f;
            yield return new WaitUntil(() => _scanDone || Time.time > deadline);

            if (!_scanOk)
            {
                yield return FailTest("Step 03 FAILED — no Polar device found. Ensure H10 is worn and discoverable.");
                yield break;
            }

            // ── Step 04: Connect ───────────────────────────────────────────────

            _ui.Log($"Step 04 — Connecting to {_deviceId} (timeout {CONNECT_TIMEOUT}s)...");
            _connectDone = false;
            _connectOk   = false;

            BLEManager.Instance.Connect(_deviceId, CONNECT_TIMEOUT, ok =>
            {
                _connectOk   = ok;
                _connectDone = true;
                _ui.Log($"Step 04 — Connect result: {(ok ? "SUCCESS" : "FAILED")}");
            });

            deadline = Time.time + CONNECT_TIMEOUT + 3f;
            yield return new WaitUntil(() => _connectDone || Time.time > deadline);

            if (!_connectOk)
            {
                // Android GATT error 133 is transient on many BLE stacks — retry once.
                _ui.Log("Step 04 — First connect attempt failed. Waiting 2s then retrying (GATT 133 mitigation)...");
                yield return new WaitForSeconds(2f);

                _connectDone = false;
                _connectOk   = false;

                BLEManager.Instance.Connect(_deviceId, CONNECT_TIMEOUT, ok =>
                {
                    _connectOk   = ok;
                    _connectDone = true;
                    _ui.Log($"Step 04 — Connect retry result: {(ok ? "SUCCESS" : "FAILED")}");
                });

                deadline = Time.time + CONNECT_TIMEOUT + 3f;
                yield return new WaitUntil(() => _connectDone || Time.time > deadline);
            }

            if (!_connectOk)
            {
                yield return FailTest("Step 04 FAILED — could not connect after retry.");
                yield break;
            }

            // ── Step 05: Discover services ─────────────────────────────────────

            _ui.Log("Step 05 — Discovering services...");
            _servicesDone = false;
            _servicesOk   = false;

            BLEManager.Instance.DiscoverServices(_deviceId, services =>
            {
                if (services != null && services.Length > 0)
                {
                    _ui.Log($"Step 05 — Discovered {services.Length} service(s): {string.Join(", ", services)}");
                    _servicesOk = true;
                }
                else
                {
                    _ui.Log("Step 05 — Service discovery returned empty list.");
                }
                _servicesDone = true;
            });

            deadline = Time.time + STEP_TIMEOUT;
            yield return new WaitUntil(() => _servicesDone || Time.time > deadline);

            if (!_servicesOk)
            {
                yield return FailTest("Step 05 FAILED — service discovery failed or timed out.");
                yield break;
            }

            // ── Step 06: Read Device Information ──────────────────────────────

            _ui.Log("Step 06 — Reading Device Information Service (DIS)...");

            // DIS Manufacturer Name (0x2A29)
            bool disReadDone = false;
            BLEManager.Instance.ReadCharacteristic(
                _deviceId,
                DEVICE_INFO_SERVICE,
                "00002a29-0000-1000-8000-00805f9b34fb",  // Manufacturer Name String
                data =>
                {
                    string manufacturer = (data != null) ? System.Text.Encoding.UTF8.GetString(data) : "N/A";
                    _ui.Log($"Step 06 — Manufacturer: {manufacturer}");
                    disReadDone = true;
                });

            deadline = Time.time + STEP_TIMEOUT;
            yield return new WaitUntil(() => disReadDone || Time.time > deadline);

            if (!disReadDone)
            {
                // DIS read failure is non-fatal; log and continue.
                _ui.Log("Step 06 — DIS read timed out (non-fatal; continuing).");
            }

            // ── Step 07: Read Battery Level ────────────────────────────────────

            _ui.Log("Step 07 — Reading battery level...");
            _batteryDone  = false;
            _batteryLevel = -1;

            BLEManager.Instance.ReadBattery(_deviceId, level =>
            {
                _batteryLevel = level;
                _batteryDone  = true;
                _ui.Log($"Step 07 — Battery: {level}%");
            });

            deadline = Time.time + STEP_TIMEOUT;
            yield return new WaitUntil(() => _batteryDone || Time.time > deadline);

            if (!_batteryDone)
            {
                _ui.Log("Step 07 — Battery read timed out (non-fatal; continuing).");
            }

            // ── Step 08: Subscribe to Heart Rate Measurement ───────────────────

            _ui.Log("Step 08 — Subscribing to Heart Rate Measurement (0x2A37)...");

            bool hrmSubscribed = false;

            // The notification arrives each time the Polar H10 transmits a heartbeat.
            // Packet format: see ParseHRM() below.
            BLEManager.Instance.Subscribe(
                _deviceId,
                HEART_RATE_SERVICE,
                HEART_RATE_CHAR,
                data =>
                {
                    if (!hrmSubscribed)
                    {
                        _ui.Log("Step 08 — First HRM notification received. HRM stream active.");
                        hrmSubscribed = true;
                    }

                    int bpm = ParseHRM(data);
                    if (bpm > 0)
                    {
                        _hrmSamples.Add(bpm);
                        _ui.SetLastValue($"HR: {bpm} bpm");
                    }
                });

            // Allow up to STEP_TIMEOUT for the first HRM notification.
            deadline = Time.time + STEP_TIMEOUT;
            yield return new WaitUntil(() => hrmSubscribed || Time.time > deadline);

            if (!hrmSubscribed)
            {
                // HRM subscription failure is fatal — core test feature.
                yield return FailTest("Step 08 FAILED — no HRM notification within timeout. Check H10 contact and strap.");
                yield break;
            }

            // ── Step 09: Start ECG stream ──────────────────────────────────────

            _ui.Log("Step 09 — Starting ECG stream (PMD command, 130 Hz)...");

            // First subscribe to the PMD Data characteristic to receive ECG packets.
            bool ecgSubscribed = false;
            BLEManager.Instance.Subscribe(
                _deviceId,
                POLAR_PMD_SERVICE,
                POLAR_PMD_DATA,
                data =>
                {
                    // Parse Polar PMD ECG data packet.
                    // Packet structure (Polar proprietary):
                    //   byte[0]      — data type: 0x00 = ECG, 0x02 = ACC
                    //   byte[1..8]   — timestamp (64-bit little-endian nanoseconds; ignored here)
                    //   byte[9..]    — ECG samples, 3 bytes each (24-bit signed, little-endian, 1 µV/LSB)
                    if (data == null || data.Length < 10) return;
                    if (data[0] != 0x00) return; // not ECG

                    if (!ecgSubscribed)
                    {
                        _ui.Log("Step 09 — First ECG data packet received. ECG stream active.");
                        ecgSubscribed = true;
                    }

                    // Unpack 3-byte samples starting at byte 9.
                    // Each sample is a 24-bit signed integer (little-endian).
                    // Scale: 1 µV / LSB (no additional scale factor needed).
                    for (int i = 9; i + 2 < data.Length; i += 3)
                    {
                        // Reconstruct signed 24-bit value from 3 bytes LE.
                        // Shift into 32-bit int, then sign-extend from bit 23.
                        int raw = data[i] | (data[i + 1] << 8) | (data[i + 2] << 16);
                        // Sign-extend: if bit 23 is set, fill upper 8 bits with 1s.
                        if ((raw & 0x800000) != 0) raw |= unchecked((int)0xFF000000);
                        float microvolts = raw; // 1 µV / LSB
                        _ecgSamples.Add(microvolts);
                        _ui.SetLastValue($"ECG: {microvolts:F0} µV");
                    }
                });

            // Subscribe to PMD Control Point to receive the command acknowledgement.
            bool pmdCtrlAckReceived = false;
            BLEManager.Instance.Subscribe(
                _deviceId,
                POLAR_PMD_SERVICE,
                POLAR_PMD_CONTROL,
                data =>
                {
                    // ACK response to ECG_START_COMMAND: byte[0]=0x0F (response code),
                    // byte[1]=0x02 (operation echoed), byte[2]=0x00 (success).
                    if (data != null && data.Length >= 3 && data[0] == 0x0F && data[2] == 0x00)
                    {
                        _ui.Log("Step 09 — PMD control ACK received (ECG start confirmed).");
                        pmdCtrlAckReceived = true;
                    }
                });

            // Send the ECG start command to the control point characteristic.
            // withResponse=true because the PMD control point requires Write With Response (0x08).
            bool ecgWriteDone = false;
            BLEManager.Instance.WriteCharacteristic(
                _deviceId,
                POLAR_PMD_SERVICE,
                POLAR_PMD_CONTROL,
                ECG_START_COMMAND,
                withResponse: true,
                onResult: ok =>
                {
                    ecgWriteDone = true;
                    _ui.Log($"Step 09 — ECG start command write: {(ok ? "OK" : "FAILED")}");
                });

            deadline = Time.time + STEP_TIMEOUT;
            yield return new WaitUntil(() => ecgWriteDone || Time.time > deadline);

            if (!ecgWriteDone)
            {
                yield return FailTest("Step 09 FAILED — ECG start command write timed out.");
                yield break;
            }

            // Wait briefly for the first ECG data packets to arrive.
            deadline = Time.time + 5f;
            yield return new WaitUntil(() => ecgSubscribed || Time.time > deadline);

            if (!ecgSubscribed)
            {
                _ui.Log("Step 09 — Warning: no ECG data packets yet after 5s. Continuing collection...");
            }

            // ── Step 10: Collect 30 seconds of data ────────────────────────────

            _ui.Log($"Step 10 — Collecting {ECG_DURATION_SECONDS}s of HR + ECG data...");
            float collectStart = Time.time;

            while (Time.time - collectStart < ECG_DURATION_SECONDS)
            {
                float elapsed = Time.time - collectStart;
                // Log progress every 5 seconds.
                if (Mathf.FloorToInt(elapsed) % 5 == 0 && elapsed > 0)
                {
                    _ui.Log($"Step 10 — {elapsed:F0}s / {ECG_DURATION_SECONDS}s | HR samples: {_hrmSamples.Count} | ECG samples: {_ecgSamples.Count}");
                }
                yield return new WaitForSeconds(1f);
            }

            _ui.Log($"Step 10 — Collection complete. HR: {_hrmSamples.Count} samples, ECG: {_ecgSamples.Count} samples.");

            // ── Step 11: Disconnect + report ───────────────────────────────────

            _ui.Log("Step 11 — Disconnecting...");

            // Unsubscribe before disconnecting to ensure clean GATT teardown.
            BLEManager.Instance.Unsubscribe(_deviceId, HEART_RATE_SERVICE, HEART_RATE_CHAR);
            BLEManager.Instance.Unsubscribe(_deviceId, POLAR_PMD_SERVICE,  POLAR_PMD_DATA);
            BLEManager.Instance.Unsubscribe(_deviceId, POLAR_PMD_SERVICE,  POLAR_PMD_CONTROL);

            BLEManager.Instance.Disconnect(_deviceId);
            yield return new WaitForSeconds(1f);

            _ui.Log("Step 11 — Disconnected.");

            // ── Pass / Fail evaluation ──────────────────────────────────────────

            bool hrOk  = EvaluateHR();
            bool ecgOk = EvaluateECG();

            _ui.Log("══ Test Summary ══");
            _ui.Log($"  Battery      : {(_batteryLevel >= 0 ? $"{_batteryLevel}%" : "N/A")}");
            _ui.Log($"  HR samples   : {_hrmSamples.Count}");

            if (_hrmSamples.Count > 0)
            {
                int hrMin  = _hrmSamples.Min();
                int hrMax  = _hrmSamples.Max();
                float hrMean = (float)_hrmSamples.Average();
                _ui.Log($"  HR range     : {hrMin}–{hrMax} bpm (mean {hrMean:F1})");
            }

            _ui.Log($"  ECG samples  : {_ecgSamples.Count}");

            if (_ecgSamples.Count > 0)
            {
                float ecgStdDev = ComputeStdDev(_ecgSamples);
                _ui.Log($"  ECG std dev  : {ecgStdDev:F1} µV (threshold > 10 µV)");
            }

            _ui.Log($"  HR check     : {(hrOk  ? "PASS ✓" : "FAIL ✗")}");
            _ui.Log($"  ECG check    : {(ecgOk ? "PASS ✓" : "FAIL ✗")}");

            bool overallPass = hrOk && ecgOk;
            if (overallPass)
            {
                _ui.Log("══ RESULT: PASS ✓ ══");
                _ui.SetStatus("PASS ✓");
            }
            else
            {
                _ui.Log("══ RESULT: FAIL ✗ ══");
                _ui.SetStatus("FAIL ✗");
            }

            _running = false;
            _ui.SetRunning(false);
        }

        // ══════════════════════════════════════════════════════════════════════
        // Protocol parsers
        // ══════════════════════════════════════════════════════════════════════

        /// <summary>
        /// Parse a Bluetooth GATT Heart Rate Measurement (0x2A37) notification.
        ///
        /// Packet layout (GATT spec, assigned numbers 2.6.1):
        ///   byte[0]      — flags:
        ///                    bit 0: Heart Rate Value Format (0=uint8, 1=uint16)
        ///                    bit 1: Sensor Contact Status
        ///                    bit 2: Sensor Contact Feature
        ///                    bit 3: Energy Expended Status
        ///                    bit 4: RR-Interval present
        ///   byte[1]      — HR value (uint8) when bit0=0
        ///   byte[1..2]   — HR value (uint16 LE) when bit0=1
        ///   byte[3..]    — optional Energy Expended, RR intervals
        ///
        /// Returns 0 on malformed input.
        /// </summary>
        /// <param name="data">Raw notification bytes from HEART_RATE_CHAR.</param>
        /// <returns>Heart rate in beats per minute.</returns>
        private static int ParseHRM(byte[] data)
        {
            if (data == null || data.Length < 2) return 0;

            // Bit 0 of the flags byte indicates the HR value format.
            bool uint16Format = (data[0] & 0x01) == 1;

            if (uint16Format)
            {
                // 16-bit HR value in bytes 1 (LSB) and 2 (MSB).
                if (data.Length < 3) return 0;
                return (data[2] << 8) | data[1];
            }
            else
            {
                // 8-bit HR value in byte 1.
                return data[1];
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // Pass/fail evaluators
        // ══════════════════════════════════════════════════════════════════════

        /// <summary>
        /// Evaluate collected HRM samples against pass criteria.
        /// Passes if at least one sample falls within the physiological range 30–220 bpm.
        /// </summary>
        private bool EvaluateHR()
        {
            if (_hrmSamples.Count == 0)
            {
                _ui.Log("  [HR FAIL] No HRM samples collected.");
                return false;
            }

            bool anyValid = _hrmSamples.Any(bpm => bpm >= 30 && bpm <= 220);
            if (!anyValid)
            {
                _ui.Log($"  [HR FAIL] All {_hrmSamples.Count} samples out of physiological range [30–220].");
            }
            return anyValid;
        }

        /// <summary>
        /// Evaluate collected ECG samples against pass criteria.
        /// Passes if std dev > 10 µV (signal is not a flat line / noise floor artifact).
        /// </summary>
        private bool EvaluateECG()
        {
            if (_ecgSamples.Count == 0)
            {
                _ui.Log("  [ECG FAIL] No ECG samples collected.");
                return false;
            }

            float stdDev = ComputeStdDev(_ecgSamples);
            bool pass = stdDev > 10f;
            if (!pass)
            {
                _ui.Log($"  [ECG FAIL] std dev {stdDev:F1} µV ≤ 10 µV threshold (flat signal or noise only).");
            }
            return pass;
        }

        // ══════════════════════════════════════════════════════════════════════
        // Statistics helpers
        // ══════════════════════════════════════════════════════════════════════

        /// <summary>
        /// Compute the population standard deviation of a float sample list.
        /// </summary>
        private static float ComputeStdDev(List<float> samples)
        {
            if (samples.Count < 2) return 0f;

            float mean = 0f;
            foreach (float v in samples) mean += v;
            mean /= samples.Count;

            float variance = 0f;
            foreach (float v in samples) variance += (v - mean) * (v - mean);
            variance /= samples.Count;

            return Mathf.Sqrt(variance);
        }

        // ══════════════════════════════════════════════════════════════════════
        // Failure helper
        // ══════════════════════════════════════════════════════════════════════

        /// <summary>
        /// Log a failure message, update UI state, and return a one-frame yield.
        /// Used with "yield return FailTest(...)" followed by "yield break" to
        /// cleanly exit the RunSequence coroutine on unrecoverable errors.
        /// </summary>
        private IEnumerator FailTest(string reason)
        {
            _ui.Log($"[FAIL] {reason}");
            _ui.SetStatus("FAIL ✗");

            // Attempt a clean disconnect even on failure.
            if (!string.IsNullOrEmpty(_deviceId))
            {
                _ui.Log("[FAIL] Attempting cleanup disconnect...");
                BLEManager.Instance.Disconnect(_deviceId);
                yield return new WaitForSeconds(0.5f);
            }

            _running = false;
            _ui.SetRunning(false);
        }
    }
}
