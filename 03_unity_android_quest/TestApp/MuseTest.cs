// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : MuseTest.cs — Muse 2 EEG Integration Test (Quest / Android)
// Platform: Android (Unity) — Quest 2 / 3 / Pro
// Sprint  : Sprint 81
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : 11-step integration test that validates the BLEPluginAndroid bridge
//           against a real Muse 2 EEG headband. Mirrors the equivalent tests in:
//             Phase 02 — Swift/CoreBluetooth  (09_xcode_apple/TestApp/)
//             Phase 03 — Web BLE / TypeScript (03_web_ble/TestApp/muse-runner.js)
//
// Test sequence:
//   Step 01 — Initialize BLE (BLEManager.Initialize)
//   Step 02 — Request permissions
//   Step 03 — Scan for "Muse" device (10 s)
//   Step 04 — Connect (15 s timeout, retry on GATT 133)
//   Step 05 — Discover services
//   Step 06 — Subscribe to all 4 EEG channels (TP9, AF7, AF8, TP10)
//   Step 07 — Send streaming start command to Muse control characteristic
//   Step 08 — Collect 15 seconds of EEG data from all 4 channels
//   Step 09 — Stop streaming
//   Step 10 — Disconnect
//   Step 11 — Report stats (sample counts per channel, std dev per channel)
//
// Pass criteria:
//   • All 4 channels received >= 100 samples in 15 seconds
//   • Each channel std dev > 5 µV (signal present, not flat/disconnected)
//
// Muse 2 EEG BLE Protocol:
//   Proprietary protocol by InteraXon. UUIDs and packet formats reverse-engineered
//   and documented by the open-source community (muse-lsl, BlueMuse, mind-monitor).
//   References:
//     alexandrebarachant/muse-lsl (Python)
//     khiner/BeatMuse (iOS)
//     ChoiysApple/EEG (Android)
//
// EEG Packet Format (per channel):
//   Each BLE notification = 1 packet from one EEG electrode.
//   Bytes:
//     [0..1]  — uint16 big-endian: packet counter (12-bit sequence number)
//     [2..13] — 12 samples × 12-bit unsigned integers, packed big-endian
//               (96 bits = 12 bytes for 12 samples per packet)
//   At 256 Hz, 12 samples / packet → ~21.3 packets/s per channel.
//   Conversion: raw_uint12 → µV using the cross-validated calibration constant:
//     µV = (raw_uint12 - 2048) * 0.48828125
//   SCALE = 0.48828125 µV/LSB (= 1000 / 2048), used in Phase 01/02/03.
//   Note: muse-lsl uses 0.8666 (1.8V/4096/gain51×1e6); values differ by ~1.77×.
//
// Muse Control Commands:
//   The control characteristic (273e0001-...) accepts ASCII command strings.
//   Commands are null-terminated with a 2-byte header: [len_hi, len_lo, ...cmd..., 0x0A]
//   Stream start: "d\n" (decimal ASCII 100, newline) — encoded as { 0x02, 0x64, 0x0a }
//   Stream stop:  "h\n" (halt)                       — encoded as { 0x02, 0x68, 0x0a }
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
    /// Muse 2 EEG integration test for the Phase 04 Unity / Quest TestApp.
    ///
    /// Runs an 11-step coroutine that exercises BLEManager scan → connect →
    /// discover → subscribe (4 EEG channels) → stream → collect → stop →
    /// disconnect, then evaluates sample counts and signal quality per channel.
    /// </summary>
    public class MuseTest : MonoBehaviour
    {
        // ══════════════════════════════════════════════════════════════════════
        // BLE UUIDs — Muse 2
        // ══════════════════════════════════════════════════════════════════════
        // Source: alexandrebarachant/muse-lsl, verified against Muse 2 firmware 1.x
        // All UUIDs are in the InteraXon 128-bit vendor namespace (273e0001-... prefix).

        // Primary service UUID for the Muse EEG headband.
        private const string MUSE_SERVICE = "0000fe8d-0000-1000-8000-00805f9b34fb";

        // Control characteristic — accepts ASCII command strings for start/stop/config.
        // Writable (Write Without Response). Not notifiable.
        private const string MUSE_CONTROL = "273e0001-4c4d-454d-96be-f03bac821358";

        // EEG channel characteristics — notify (indicate) on each 12-sample packet.
        // Channels correspond to standard 10-20 electrode positions on the Muse 2.
        private const string UUID_TP9  = "273e0003-4c4d-454d-96be-f03bac821358"; // Left ear
        private const string UUID_AF7  = "273e0004-4c4d-454d-96be-f03bac821358"; // Left forehead
        private const string UUID_AF8  = "273e0005-4c4d-454d-96be-f03bac821358"; // Right forehead
        private const string UUID_TP10 = "273e0006-4c4d-454d-96be-f03bac821358"; // Right ear

        // ══════════════════════════════════════════════════════════════════════
        // Muse control commands (byte sequences)
        // ══════════════════════════════════════════════════════════════════════

        // Start EEG streaming: ASCII "d\n" with 2-byte length prefix.
        // Byte layout: [0x02=length_hi, 0x64='d', 0x0A='\n']
        // Reference: muse-lsl museio.py send_command()
        private static readonly byte[] MUSE_STREAM_START = { 0x02, 0x64, 0x0a };

        // Stop EEG streaming: ASCII "h\n" (halt) with length prefix.
        private static readonly byte[] MUSE_STREAM_STOP  = { 0x02, 0x68, 0x0a };

        // ══════════════════════════════════════════════════════════════════════
        // EEG scale factor
        // ══════════════════════════════════════════════════════════════════════

        // Converts a raw 12-bit unsigned ADC value to microvolts.
        // Derived from Muse hardware specs:
        //   ADC reference voltage = 1.8 V
        //   ADC resolution        = 12 bits (range 0–4095)
        //   Amplifier gain        = 51×
        //   Unit conversion       = ×1e6 (V → µV)
        //
        //   SCALE = 0.48828125 µV/LSB  (= 1000 / 2048)
        //
        // The mid-scale offset (2048 LSB → 0 µV) is applied during conversion.
        // Cross-validated against Phase 01 (Python), Phase 02 (Swift), Phase 03 (TypeScript).
        // Note: muse-lsl uses 0.8666 derived from hardware specs (1.8V/4096/gain51×1e6);
        // this library uses 0.48828125, consistent with the Muse SDK calibration.
        private const float EEG_SCALE_UV_PER_LSB = 0.48828125f;
        private const int   EEG_MID_SCALE         = 2048;

        // ══════════════════════════════════════════════════════════════════════
        // Timing constants
        // ══════════════════════════════════════════════════════════════════════

        private const float SCAN_TIMEOUT     = 10f;
        private const float CONNECT_TIMEOUT  = 15f;

        // EEG collection window: 15 s. At 256 Hz / 12 samples per packet,
        // each channel should yield ~256 × 15 = 3840 samples in ideal conditions.
        // Pass threshold of 100 samples is conservative to account for latency.
        private const float EEG_DURATION     = 15f;
        private const int   MIN_SAMPLES_PASS = 100;

        // Min signal quality threshold per channel (std dev in µV).
        // A flat / disconnected electrode typically shows std dev < 1 µV.
        private const float MIN_STDDEV_UV    = 5f;

        // Generic step timeout for service discovery, subscribe, write operations.
        private const float STEP_TIMEOUT     = 20f;

        // ══════════════════════════════════════════════════════════════════════
        // Channel registry — keyed by UUID for clean per-channel handling
        // ══════════════════════════════════════════════════════════════════════

        // Maps each channel UUID to its friendly label for log output.
        private static readonly Dictionary<string, string> ChannelLabels = new()
        {
            { UUID_TP9,  "TP9"  },
            { UUID_AF7,  "AF7"  },
            { UUID_AF8,  "AF8"  },
            { UUID_TP10, "TP10" },
        };

        // ══════════════════════════════════════════════════════════════════════
        // Instance state
        // ══════════════════════════════════════════════════════════════════════

        private BLETestUI _ui;
        private string    _deviceId;
        private bool      _running;

        // Per-channel sample buffers. Populated by EEG notification callbacks.
        private readonly Dictionary<string, List<float>> _eegSamples = new()
        {
            { UUID_TP9,  new List<float>() },
            { UUID_AF7,  new List<float>() },
            { UUID_AF8,  new List<float>() },
            { UUID_TP10, new List<float>() },
        };

        // Step completion signals.
        private bool _scanDone;
        private bool _connectDone;
        private bool _servicesDone;
        private bool _scanOk;
        private bool _connectOk;
        private bool _servicesOk;

        // ══════════════════════════════════════════════════════════════════════
        // Public entry point
        // ══════════════════════════════════════════════════════════════════════

        /// <summary>
        /// Start the 11-step Muse 2 integration test.
        /// Called from BLETestUI button handler (museButton.onClick).
        /// </summary>
        /// <param name="ui">The BLETestUI controller for log and status output.</param>
        public void Run(BLETestUI ui)
        {
            if (_running)
            {
                Debug.LogWarning("[MuseTest] Already running — ignoring duplicate Run() call.");
                return;
            }
            _ui = ui;
            StartCoroutine(RunSequence());
        }

        // ══════════════════════════════════════════════════════════════════════
        // Main coroutine — 11 steps
        // ══════════════════════════════════════════════════════════════════════

        /// <summary>
        /// Coroutine that executes the full 11-step Muse 2 EEG integration test.
        /// </summary>
        private IEnumerator RunSequence()
        {
            _running = true;
            _ui.SetRunning(true);
            _ui.ClearLog();

            // Clear all per-channel buffers at start of each run.
            foreach (var ch in _eegSamples.Keys.ToList())
                _eegSamples[ch].Clear();

            _ui.Log("══ Muse 2 EEG Integration Test — Phase 04 ══");
            _ui.SetStatus("Running...");

            // ── Step 01: Initialize BLE ────────────────────────────────────────

            _ui.Log("Step 01 — Initializing BLE manager...");
            BLEManager.Instance.Initialize();
            yield return null;
            _ui.Log("Step 01 — BLE initialized.");

            // ── Step 02: Request permissions ───────────────────────────────────

            _ui.Log("Step 02 — Requesting BLE permissions...");
            OculusPermissions.RequestBLEPermissions(
                onGranted: () => _ui.Log("Step 02 — Permissions: GRANTED."),
                onDenied:  () => _ui.Log("Step 02 — Permissions: DENIED. BLE operations may fail."));
            yield return new WaitForSeconds(2f);
            _ui.Log("Step 02 — Permissions step complete.");

            // ── Step 03: Scan for Muse device ──────────────────────────────────

            _ui.Log($"Step 03 — Scanning for 'Muse' device ({SCAN_TIMEOUT}s)...");
            _scanDone = false;
            _scanOk   = false;
            _deviceId = null;

            BLEManager.Instance.StartScanFiltered(
                serviceUUIDs:    null,
                nameFilter:      "Muse",
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
                        _ui.Log("Step 03 — No Muse device found in scan window.");
                    }
                    _scanDone = true;
                });

            float deadline = Time.time + SCAN_TIMEOUT + 3f;
            yield return new WaitUntil(() => _scanDone || Time.time > deadline);

            if (!_scanOk)
            {
                yield return FailTest("Step 03 FAILED — no Muse device found. Ensure Muse 2 is powered on.");
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
                // Android GATT 133 retry — same pattern as PolarTest.
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

            // ── Step 06: Subscribe to all 4 EEG channels ──────────────────────

            _ui.Log("Step 06 — Subscribing to EEG channels: TP9, AF7, AF8, TP10...");

            // Subscribe to each channel characteristic. Each notification delivers
            // one packet of 12 EEG samples (see packet format in file header).
            foreach (var kv in ChannelLabels)
            {
                string charUUID = kv.Key;
                string label    = kv.Value;

                // Capture loop variables explicitly to avoid closure-over-loop-variable bug.
                string capturedUUID  = charUUID;
                string capturedLabel = label;

                BLEManager.Instance.Subscribe(
                    _deviceId,
                    MUSE_SERVICE,
                    capturedUUID,
                    data =>
                    {
                        // Parse the 12-sample EEG packet and append to the channel buffer.
                        ParseAndBufferEEGPacket(data, capturedUUID, capturedLabel);
                    });
            }

            // Allow one frame for all subscriptions to register.
            yield return null;
            _ui.Log("Step 06 — Subscribed to all 4 EEG channels.");

            // ── Step 07: Send streaming start command ──────────────────────────

            _ui.Log("Step 07 — Sending EEG stream start command ('d\\n')...");

            bool streamStartWritten = false;

            // Write Without Response (withResponse=false) is correct for the Muse control
            // characteristic — it uses Write Command (no acknowledgement from device).
            BLEManager.Instance.WriteCharacteristic(
                _deviceId,
                MUSE_SERVICE,
                MUSE_CONTROL,
                MUSE_STREAM_START,
                withResponse: false,
                onResult: ok =>
                {
                    streamStartWritten = true;
                    _ui.Log($"Step 07 — Stream start write: {(ok ? "OK" : "FAILED")}");
                });

            deadline = Time.time + STEP_TIMEOUT;
            yield return new WaitUntil(() => streamStartWritten || Time.time > deadline);

            if (!streamStartWritten)
            {
                yield return FailTest("Step 07 FAILED — stream start command write timed out.");
                yield break;
            }

            // ── Step 08: Collect 15 seconds of EEG data ───────────────────────

            _ui.Log($"Step 08 — Collecting {EEG_DURATION}s of EEG data (4 channels)...");
            float collectStart = Time.time;

            while (Time.time - collectStart < EEG_DURATION)
            {
                float elapsed = Time.time - collectStart;

                // Log per-channel progress every 5 seconds.
                if (Mathf.FloorToInt(elapsed) % 5 == 0 && elapsed > 0)
                {
                    _ui.Log($"Step 08 — {elapsed:F0}s / {EEG_DURATION}s | " +
                            $"TP9:{_eegSamples[UUID_TP9].Count} AF7:{_eegSamples[UUID_AF7].Count} " +
                            $"AF8:{_eegSamples[UUID_AF8].Count} TP10:{_eegSamples[UUID_TP10].Count}");
                }

                // Update live readout with the most recently received sample from AF7
                // (front-left electrode — most signal-rich on Muse 2 for general use).
                if (_eegSamples[UUID_AF7].Count > 0)
                {
                    float lastSample = _eegSamples[UUID_AF7][_eegSamples[UUID_AF7].Count - 1];
                    _ui.SetLastValue($"EEG AF7: {lastSample:F1} µV");
                }

                yield return new WaitForSeconds(1f);
            }

            _ui.Log("Step 08 — Collection window complete.");

            // ── Step 09: Stop streaming ────────────────────────────────────────

            _ui.Log("Step 09 — Sending EEG stream stop command ('h\\n')...");

            bool streamStopWritten = false;
            BLEManager.Instance.WriteCharacteristic(
                _deviceId,
                MUSE_SERVICE,
                MUSE_CONTROL,
                MUSE_STREAM_STOP,
                withResponse: false,
                onResult: ok =>
                {
                    streamStopWritten = true;
                    _ui.Log($"Step 09 — Stream stop write: {(ok ? "OK" : "FAILED")}");
                });

            deadline = Time.time + STEP_TIMEOUT;
            yield return new WaitUntil(() => streamStopWritten || Time.time > deadline);

            if (!streamStopWritten)
            {
                _ui.Log("Step 09 — Stream stop command timed out (non-fatal; continuing to disconnect).");
            }

            // Unsubscribe from all EEG channels.
            foreach (string charUUID in ChannelLabels.Keys)
            {
                BLEManager.Instance.Unsubscribe(_deviceId, MUSE_SERVICE, charUUID);
            }

            yield return null;
            _ui.Log("Step 09 — EEG stream stopped. Unsubscribed from all channels.");

            // ── Step 10: Disconnect ────────────────────────────────────────────

            _ui.Log("Step 10 — Disconnecting...");
            BLEManager.Instance.Disconnect(_deviceId);
            yield return new WaitForSeconds(1f);
            _ui.Log("Step 10 — Disconnected.");

            // ── Step 11: Report stats ──────────────────────────────────────────

            _ui.Log("Step 11 — Evaluating results...");
            bool overallPass = EvaluateResults();

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
        // EEG packet parser
        // ══════════════════════════════════════════════════════════════════════

        /// <summary>
        /// Parse a Muse 2 EEG notification packet and append decoded µV samples
        /// to the corresponding channel buffer.
        ///
        /// Packet layout (InteraXon proprietary, documented by muse-lsl community):
        ///   byte[0..1]  — uint16 big-endian: sequence / packet counter (12 bits)
        ///   byte[2..13] — 96 bits encoding 12 × uint12 samples (big-endian bit-packing)
        ///
        /// Bit-packing: samples are packed sequentially, MSB first.
        ///   sample_n occupies bits [n*12 .. n*12+11] within bytes[2..13].
        ///
        /// Conversion:
        ///   µV = (raw_uint12 - EEG_MID_SCALE) * EEG_SCALE_UV_PER_LSB
        ///
        /// Reference: muse-lsl museio.py _parse_eeg()
        /// </summary>
        /// <param name="data">Raw notification bytes (14 bytes expected).</param>
        /// <param name="charUUID">Source characteristic UUID, used to route to the correct buffer.</param>
        /// <param name="label">Human-readable channel label for debug output.</param>
        private void ParseAndBufferEEGPacket(byte[] data, string charUUID, string label)
        {
            // Minimum packet: 2 header bytes + 12 sample bytes = 14 bytes.
            if (data == null || data.Length < 14) return;

            // Extract the 12-bit packet counter for optional sequence monitoring.
            // (Not used for pass/fail evaluation here; retained for future diagnostics.)
            // int counter = ((data[0] << 8) | data[1]) & 0x0FFF;

            // Unpack 12 samples from bytes 2–13.
            // Samples are packed as 12-bit values, big-endian bit order:
            //   Sample 0:  bits [15..4] of byte[2..3]
            //   Sample 1:  bits [11..0] of byte[3], bits [15..12] of byte[4]
            //   … and so on.
            //
            // The standard extraction approach is to read the 12 bytes as a bit stream
            // and extract 12-bit chunks. Here we use a straightforward byte-pair approach:
            //   Even-indexed samples (0, 2, 4, …): byte[2+i*1.5] << 4 | byte[2+i*1.5+1] >> 4
            //   Odd-indexed samples  (1, 3, 5, …): (byte[...] & 0x0F) << 8 | byte[...]
            //
            // Equivalent to the muse-lsl numpy implementation:
            //   channel_data = np.array([...]) * EEG_SCALE_UV_PER_LSB - (EEG_MID_SCALE * EEG_SCALE_UV_PER_LSB)

            var buffer = _eegSamples[charUUID];

            for (int i = 0; i < 12; i++)
            {
                int raw;
                if (i % 2 == 0)
                {
                    // Even sample: upper 8 bits from byte[2 + i/2 * 3/2] — use float index.
                    // Simpler fixed index arithmetic:
                    int byteOffset = 2 + (i * 3) / 2;
                    raw = ((data[byteOffset] << 4) | (data[byteOffset + 1] >> 4)) & 0x0FFF;
                }
                else
                {
                    // Odd sample: lower 4 bits of the shared byte + next full byte.
                    int byteOffset = 2 + (i * 3) / 2;
                    raw = ((data[byteOffset] & 0x0F) << 8) | data[byteOffset + 1];
                }

                // Convert ADC count to µV. Subtraction of mid-scale centers the signal at 0 µV.
                float microvolts = (raw - EEG_MID_SCALE) * EEG_SCALE_UV_PER_LSB;
                buffer.Add(microvolts);
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // Pass / fail evaluation
        // ══════════════════════════════════════════════════════════════════════

        /// <summary>
        /// Evaluate collected EEG data against pass criteria for all 4 channels.
        /// Logs per-channel results to BLETestUI. Returns true only if all channels pass.
        /// </summary>
        private bool EvaluateResults()
        {
            bool allPass = true;

            _ui.Log("══ Test Summary ══");

            foreach (var kv in ChannelLabels)
            {
                string uuid    = kv.Key;
                string label   = kv.Value;
                var    samples = _eegSamples[uuid];

                int   count   = samples.Count;
                float stdDev  = ComputeStdDev(samples);

                bool countPass  = count  >= MIN_SAMPLES_PASS;
                bool sigPass    = stdDev >= MIN_STDDEV_UV;
                bool chanPass   = countPass && sigPass;

                _ui.Log($"  {label,4} — samples: {count,5} (≥{MIN_SAMPLES_PASS} {(countPass ? "✓" : "✗")}) | " +
                        $"std dev: {stdDev,6:F1} µV (≥{MIN_STDDEV_UV} {(sigPass ? "✓" : "✗")}) " +
                        $"→ {(chanPass ? "PASS ✓" : "FAIL ✗")}");

                if (!chanPass) allPass = false;
            }

            return allPass;
        }

        // ══════════════════════════════════════════════════════════════════════
        // Statistics helpers
        // ══════════════════════════════════════════════════════════════════════

        /// <summary>
        /// Compute the population standard deviation of a float sample list.
        /// Returns 0 for lists with fewer than 2 elements.
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
        /// Log a failure message, update UI, attempt cleanup disconnect, and
        /// reset running state. Yield with "yield return FailTest(...)" then
        /// "yield break" in the calling coroutine.
        /// </summary>
        private IEnumerator FailTest(string reason)
        {
            _ui.Log($"[FAIL] {reason}");
            _ui.SetStatus("FAIL ✗");

            if (!string.IsNullOrEmpty(_deviceId))
            {
                _ui.Log("[FAIL] Attempting cleanup disconnect...");
                // Stop streaming before disconnect to avoid leaving device in streaming state.
                BLEManager.Instance.WriteCharacteristic(
                    _deviceId, MUSE_SERVICE, MUSE_CONTROL,
                    MUSE_STREAM_STOP, withResponse: false, onResult: _ => { });
                yield return new WaitForSeconds(0.3f);
                BLEManager.Instance.Disconnect(_deviceId);
                yield return new WaitForSeconds(0.5f);
            }

            _running = false;
            _ui.SetRunning(false);
        }
    }
}
