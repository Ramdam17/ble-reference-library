// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : MuseEEG — Muse 2 EEG Streaming, uint:12 Bit-Unpacking
// Platform: Android (Java) / Unity Bridge
// Sprint  : Sprint 78
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : Parse Muse 2 EEG BLE notifications and stream 4-channel EEG data
//           to Unity in real time. Implements the uint:12 bit-unpacking
//           algorithm that converts raw 20-byte BLE packets into microvolts
//           for each of the 4 EEG electrodes (TP9, AF7, AF8, TP10).
//
//           This file is the SIXTH (and final baseline) layer of the plugin:
//             Sprint 73 — BLEAndroidPlugin  (init, permissions)
//             Sprint 74 — BLEScan           (discovery)
//             Sprint 75 — BLEConnection     (connect/disconnect lifecycle)
//             Sprint 76 — BLEGatt           (GATT operations)
//             Sprint 77 — BLEDeviceInfo     (DIS read)
//             Sprint 78 — MuseEEG           (Muse EEG streaming) ← this file
//
// Usage   : From C# (Unity), after connecting to a Muse device:
//
//               // Start streaming all 4 EEG channels
//               museEeg.Call("startStreaming", "AA:BB:CC:DD:EE:FF");
//               // → OnEEGData(json) fires ~4 times per Muse notification batch
//
//               // Stop streaming
//               museEeg.Call("stopStreaming", "AA:BB:CC:DD:EE:FF");
//
//           OnEEGData JSON format (one per channel per notification):
//               {
//                 "id":      "AA:BB:CC:DD:EE:FF",
//                 "channel": "TP9",
//                 "counter": 1024,
//                 "samples": [-12.5, 3.2, -8.8, 0.0, 4.9, ... ]  // 12 floats, µV
//               }
//
//           Unity receives ~10 OnEEGData calls per second per channel at 256 Hz
//           (12 samples / 256 Hz ≈ 46.9 ms between packets per channel).
//
// CRITICAL — CROSS-PLATFORM VALIDATION:
//   The parseEEGPacket() method in this file MUST produce µV values identical
//   to the implementations in:
//     - Python : 01_python_bleak/11_muse_example.py  (parse_eeg_packet)
//     - Swift  : 09_xcode_apple/                     (parseEEGPacket)
//     - JS/TS  : 08_javascript_web/11_muse_eeg.ts    (parseEEGPacket)
//   See the TEST VECTORS section below for the ground-truth values to verify
//   any new port against.
//
// ─────────────────────────────────────────────────────────────────────────────
//
// MUSE EEG PACKET FORMAT — uint:12 Bit-Packing
// ──────────────────────────────────────────────
// Each BLE notification from a Muse EEG characteristic is exactly 20 bytes:
//
//   Bytes 0–1 : Packet counter, UINT16 big-endian (wraps at 65535).
//               Used to detect dropped packets (counter should be +1 each time).
//   Bytes 2–19: 12 EEG samples, each packed as a 12-bit unsigned integer.
//               18 bytes × 8 bits = 144 bits = 12 × 12 bits exactly (no padding).
//
// The 12-bit packing maps 2 samples into every 3 consecutive bytes:
//
//   ┌─────────────────┬─────────────────┬─────────────────┐
//   │    byte[k]      │    byte[k+1]    │    byte[k+2]    │
//   │  76543210       │  76543210       │  76543210       │
//   ├─────────────────┴─────────────────┴─────────────────┤
//   │  AAAAAAAAAAAA  BBBBBBBBBBBB                         │
//   │  ↑ 12 bits A  ↑ 12 bits B                          │
//   └─────────────────────────────────────────────────────┘
//
//   Where:
//     sample_A = (byte[k] << 4) | (byte[k+1] >> 4)
//     sample_B = ((byte[k+1] & 0x0F) << 8) | byte[k+2]
//
//   Concretely for the header-relative bit layout (header = bytes 0–1 = 16 bits):
//     sample[0]  at bit 16,  byte 2,  bitShift 0 → raw = (data[2]<<8)|data[3],  value = raw >> 4
//     sample[1]  at bit 28,  byte 3,  bitShift 4 → raw = (data[3]<<8)|data[4],  value = raw >> 0
//     sample[2]  at bit 40,  byte 5,  bitShift 0 → raw = (data[5]<<8)|data[6],  value = raw >> 4
//     sample[3]  at bit 52,  byte 6,  bitShift 4 → raw = (data[6]<<8)|data[7],  value = raw >> 0
//     sample[4]  at bit 64,  byte 8,  bitShift 0 → ...
//     ... (pattern repeats: even samples shift 4, odd samples shift 0)
//
// µV CONVERSION:
//   raw is a 12-bit unsigned integer in [0, 4095].
//   Midpoint 2048 = 0 µV (ADC reference level).
//   Scale factor = 0.48828125 µV/unit = 1000 / 2048.
//
//   µV = 0.48828125 × (raw − 2048)
//
//   Range: raw 0    → −1000.0 µV    (negative rail)
//          raw 2048 →     0.0 µV    (reference, silence)
//          raw 4095 →  +999.5 µV    (positive rail)
//
// WHY 12-BIT RESOLUTION?
//   EEG signals are tiny: typical scalp EEG is 10–100 µV, with artifacts up
//   to ~500 µV. A 12-bit ADC at this scale gives:
//     Resolution : 0.488 µV/bit  (sufficient for alpha/beta/gamma bands)
//     Range      : ±1000 µV      (covers EEG + most motion artifacts)
//   A 16-bit ADC would give 4× better resolution (0.031 µV/bit), but Muse
//   chose 12 bits to reduce BLE bandwidth: 12 bits × 12 samples = 144 bits
//   per packet, exactly filling 18 bytes with zero padding. Using 16 bits
//   would require 24 bytes per packet, 33% more bandwidth per channel, and
//   the Muse 2 hardware cannot sustain that while running 4 channels + IMU
//   + telemetry simultaneously over BLE 4.0.
//
// ─────────────────────────────────────────────────────────────────────────────
//
// MUSE 4-CHANNEL EEG ELECTRODE LAYOUT
// ──────────────────────────────────────
// The Muse 2 headband uses 4 electrodes in the 10-20 international system:
//
//       TP9                                 TP10
//        ◉ ──── left ear ────────── right ear ──── ◉
//                    ↓                       ↓
//        (temporal-posterior left)   (temporal-posterior right)
//
//       AF7                                 AF8
//        ◉ ──── left forehead ────── right forehead ──── ◉
//                    ↓                       ↓
//       (anterior-frontal left)    (anterior-frontal right)
//
// Signal characteristics by channel:
//   TP9, TP10 — Strong alpha (8–12 Hz) during eyes-closed rest.
//               Good for detecting meditation / relaxation states.
//   AF7, AF8  — Frontal lobe activity. Captures beta bursts during
//               cognitive tasks. More susceptible to EMG artifact.
//
// The Muse 2 reference electrode is the forehead ground strip between AF7/AF8.
// All 4 channels are measured relative to this shared reference.
//
// ─────────────────────────────────────────────────────────────────────────────
//
// MUSE CONTROL CHARACTERISTIC — STREAM START vs. SUBSCRIBE
// ──────────────────────────────────────────────────────────
// There are TWO distinct operations required to receive EEG data. Both are
// necessary; neither alone is sufficient. A common mistake is to only do one.
//
// STEP 1 — Subscribe to EEG characteristics (GATT CCCD write):
//   Call gatt.setCharacteristicNotification(eegChar, true) and write
//   ENABLE_NOTIFICATION_VALUE to the CCCD descriptor for each of the 4
//   EEG channels (TP9, AF7, AF8, TP10). See BLEGatt.java for the CCCD
//   explanation. This configures the Android BLE stack to route incoming
//   notification packets to onCharacteristicChanged().
//
//   Without this step: the Muse may stream data onto the BLE link, but
//   Android silently discards it before onCharacteristicChanged() fires.
//
// STEP 2 — Write MUSE_STREAM_START to the control characteristic (0x273E0001):
//   The Muse hardware has an internal power management system. It does not
//   activate its ADC and the analog front-end unless it receives the explicit
//   stream-start command. This command wakes the EEG amplifier, configures
//   the ADC, and begins pushing 256 Hz EEG samples onto the BLE link.
//
//   Without this step: notifications are subscribed but the Muse sends zero
//   EEG packets — the ADC is simply off to save battery.
//
//   Command bytes: {0x02, 0x64, 0x0A}
//     0x02 = message length (2 bytes follow, before the 0x0A terminator)
//     0x64 = ASCII 'd' (Muse protocol: 'd' = data/start)
//     0x0A = ASCII '\n' (line feed terminator required by Muse parser)
//
//   Stop command: {0x02, 0x68, 0x0A}
//     0x68 = ASCII 'h' (Muse protocol: 'h' = halt)
//
//   The control characteristic supports WRITE_NO_RESPONSE (fire-and-forget,
//   no ATT acknowledgment from Muse). Using write-with-response will timeout.
//
// ─────────────────────────────────────────────────────────────────────────────
//
// CROSS-PLATFORM TEST VECTORS — uint:12 PARSER GROUND TRUTH
// ───────────────────────────────────────────────────────────
// These test vectors were computed by the canonical Python implementation
// (01_python_bleak/11_muse_example.py) using the same algorithm.
// Every platform port MUST produce identical µV values to these.
//
// All vectors use the "group-of-3-bytes" algorithm:
//   sample_a = (byte[k] << 4) | (byte[k+1] >> 4)
//   sample_b = ((byte[k+1] & 0x0F) << 8) | byte[k+2]
//   µV = 0.48828125 × (sample - 2048)
//
// ── Vector 1: All zeros (bytes 0..19 = 0x00) ────────────────────────────────
//   counter = 0
//   raw      = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
//   µV       = [-1000.0, -1000.0, -1000.0, -1000.0, -1000.0, -1000.0,
//               -1000.0, -1000.0, -1000.0, -1000.0, -1000.0, -1000.0]
//
// ── Vector 2: Midpoint — all samples = 0x800 (raw 2048 → 0.0 µV) ────────────
//   Packet bytes: {0x00,0x01, 0x80,0x08,0x00, 0x80,0x08,0x00,
//                              0x80,0x08,0x00, 0x80,0x08,0x00,
//                              0x80,0x08,0x00, 0x80,0x08,0x00}
//   counter = 1
//   raw      = [2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048, 2048]
//   µV       = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]
//
// ── Vector 3: Max — all samples = 0xFFF (raw 4095 → +999.511719 µV) ─────────
//   Packet bytes: {0x00,0x02, 0xFF×18}
//   counter = 2
//   raw      = [4095, 4095, 4095, 4095, 4095, 4095, 4095, 4095, 4095, 4095, 4095, 4095]
//   µV       = [999.511719, 999.511719, 999.511719, 999.511719,
//               999.511719, 999.511719, 999.511719, 999.511719,
//               999.511719, 999.511719, 999.511719, 999.511719]
//
// ── Vector 4: Alternating 0x000 / 0xFFF ─────────────────────────────────────
//   Packet bytes: {0x00,0x03, 0x00,0x0F,0xFF, 0x00,0x0F,0xFF,
//                              0x00,0x0F,0xFF, 0x00,0x0F,0xFF,
//                              0x00,0x0F,0xFF, 0x00,0x0F,0xFF}
//   counter = 3
//   raw      = [0, 4095, 0, 4095, 0, 4095, 0, 4095, 0, 4095, 0, 4095]
//   µV       = [-1000.0, 999.511719, -1000.0, 999.511719, -1000.0, 999.511719,
//               -1000.0, 999.511719, -1000.0, 999.511719, -1000.0, 999.511719]
//
// ── Vector 5: Mixed — samples alternating 0x7AB / 0x3CD ─────────────────────
//   Packet bytes: {0x00,0x04, 0x7A,0xB3,0xCD, 0x7A,0xB3,0xCD,
//                              0x7A,0xB3,0xCD, 0x7A,0xB3,0xCD,
//                              0x7A,0xB3,0xCD, 0x7A,0xB3,0xCD}
//   counter = 4
//   raw      = [1963, 973, 1963, 973, 1963, 973, 1963, 973, 1963, 973, 1963, 973]
//   µV       = [-41.503906, -524.902344, -41.503906, -524.902344,
//               -41.503906, -524.902344, -41.503906, -524.902344,
//               -41.503906, -524.902344, -41.503906, -524.902344]
//
//   Derivation for 0x7A,0xB3,0xCD:
//     sample_a = (0x7A << 4) | (0xB3 >> 4) = 0x7A0 | 0x0B = 0x7AB = 1963
//     sample_b = ((0xB3 & 0x0F) << 8) | 0xCD = (0x03 << 8) | 0xCD = 0x3CD = 973
//     µV_a = 0.48828125 × (1963 − 2048) = 0.48828125 × (−85) = −41.503906 µV
//     µV_b = 0.48828125 × (973  − 2048) = 0.48828125 × (−1075) = −524.902344 µV
//
// These µV values must match Python (01_python_bleak/11_muse_example.py),
// Swift (09_xcode_apple/), and TypeScript (08_javascript_web/11_muse_eeg.ts).
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
import android.util.Log;


// ─── Java Standard Library ───────────────────────────────────────────────────

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Streams Muse 2 EEG data to Unity via GATT notifications.
 *
 * <p>Each BLE notification contains one EEG packet from one channel (TP9, AF7,
 * AF8, or TP10), carrying 12 EEG samples packed as 12-bit unsigned integers.
 * This class unpacks them to microvolts and forwards them to Unity via
 * {@code OnEEGData(String json)}.
 *
 * <p>Call {@link #startStreaming(String)} to subscribe to all 4 channels and
 * write the Muse stream-start command. Call {@link #stopStreaming(String)} to
 * halt.
 *
 * <p>The parser in {@link #parseEEGPacket(byte[])} is the cross-platform
 * reference implementation — it must produce bit-identical results to the
 * Python, Swift, and TypeScript ports. See the TEST VECTORS section in the
 * file header for ground-truth values.
 */
public class MuseEEG {

    // ─── Logging Tag ─────────────────────────────────────────────────────────
    private static final String TAG = "MuseEEG";

    // ─────────────────────────────────────────────────────────────────────────
    // Muse BLE Constants
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Muse custom GATT service UUID.
     * This is a vendor-specific 128-bit UUID — NOT a Bluetooth SIG assigned UUID.
     * It appears in the Muse 2 advertisement as a 128-bit service UUID.
     * Use this UUID to filter BLE scans for Muse devices.
     */
    public static final String MUSE_SERVICE_UUID = "0000fe8d-0000-1000-8000-00805f9b34fb";

    /**
     * Muse stream control characteristic UUID.
     * Write commands here to start/stop EEG streaming and query device state.
     * Property: WRITE_NO_RESPONSE only. Write-with-response will timeout on Muse 2.
     *
     * <p>This is NOT a notifiable characteristic — subscribing to it is not useful.
     * It is a one-way command sink.
     */
    public static final String MUSE_CONTROL_UUID = "273e0001-4c4d-454d-96be-f03bac821358";

    // ─── EEG Channel Characteristic UUIDs ────────────────────────────────────
    // One UUID per electrode. Each supports NOTIFY.
    // The last hex segment (0003–0006) encodes the channel index.

    /** EEG channel TP9 — left ear, temporal-posterior. */
    public static final String UUID_TP9  = "273e0003-4c4d-454d-96be-f03bac821358";

    /** EEG channel AF7 — left forehead, anterior-frontal. */
    public static final String UUID_AF7  = "273e0004-4c4d-454d-96be-f03bac821358";

    /** EEG channel AF8 — right forehead, anterior-frontal. */
    public static final String UUID_AF8  = "273e0005-4c4d-454d-96be-f03bac821358";

    /** EEG channel TP10 — right ear, temporal-posterior. */
    public static final String UUID_TP10 = "273e0006-4c4d-454d-96be-f03bac821358";

    // ─── Parallel arrays: channel UUIDs and their human names ────────────────
    // Index-matched: CHANNEL_UUIDS[i] corresponds to CHANNEL_NAMES[i].
    // Used in subscribeAllChannels() to iterate over all 4 channels uniformly.
    private static final String[] CHANNEL_UUIDS = {UUID_TP9, UUID_AF7, UUID_AF8, UUID_TP10};
    private static final String[] CHANNEL_NAMES = {"TP9",    "AF7",    "AF8",    "TP10"};

    // ─── ADC Conversion Constants ─────────────────────────────────────────────

    /**
     * Muse EEG ADC scale factor: microvolts per raw ADC unit.
     *
     * <p>Derivation: The Muse ADC has a 12-bit range [0, 4095] mapped to
     * ±1 V (2 V full-scale) with a reference at 2048 (midpoint).
     * With a hardware gain factor that maps ±1 V to ±1000 µV:
     *   resolution = 1000 µV / 2048 = 0.48828125 µV/unit
     *
     * <p>Equivalently: 1000.0f / 2048.0f = 0.48828125f exactly in IEEE 754
     * single precision (no rounding error — this is a power-of-2 fraction).
     */
    public static final float MUSE_SCALE_FACTOR = 0.48828125f;  // µV per raw unit

    /**
     * 12-bit ADC midpoint — raw value corresponding to 0 µV.
     *
     * <p>The Muse uses unsigned 12-bit encoding. The center of the range
     * [0, 4095] is 2048, representing the ADC reference voltage (0 µV differential).
     * Positive voltages produce raw values above 2048; negative below.
     */
    public static final int MUSE_OFFSET = 2048;  // 12-bit midpoint

    // ─── Stream Control Commands ──────────────────────────────────────────────
    // Written to MUSE_CONTROL_UUID with WRITE_NO_RESPONSE.
    //
    // Format: {length, command_byte, 0x0A}
    //   length       = number of bytes before the 0x0A terminator (always 0x02)
    //   command_byte = ASCII letter ('d' = data/start, 'h' = halt)
    //   0x0A         = '\n' line-feed, required by the Muse firmware parser
    //
    // These values are reverse-engineered from the open-source BleMuse project.

    /**
     * Command to start EEG streaming.
     * Write to MUSE_CONTROL_UUID with WRITE_NO_RESPONSE before expecting EEG data.
     * The Muse ADC will remain off until this command is received.
     */
    public static final byte[] MUSE_STREAM_START = {0x02, 0x64, 0x0a};  // 'd'

    /**
     * Command to stop EEG streaming.
     * Write to MUSE_CONTROL_UUID with WRITE_NO_RESPONSE to halt the ADC and
     * stop BLE notifications. The Muse will stop sending packets immediately.
     */
    public static final byte[] MUSE_STREAM_STOP  = {0x02, 0x68, 0x0a};  // 'h'

    // ─── CCCD Descriptor UUID ─────────────────────────────────────────────────
    // Required for enabling BLE notifications on Android. See BLEGatt.java
    // for the full CCCD explanation.
    private static final UUID CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ─── Unity Callback Method Names ─────────────────────────────────────────
    private static final String UNITY_METHOD_EEG_DATA      = "OnEEGData";
    private static final String UNITY_METHOD_EEG_STREAMING = "OnEEGStreaming";
    private static final String UNITY_METHOD_EEG_ERROR     = "OnEEGError";

    // ─── Muse Packet Geometry ─────────────────────────────────────────────────
    // 20 bytes per packet: 2 header + 18 payload.
    private static final int PACKET_LENGTH           = 20;
    private static final int HEADER_BYTES            = 2;   // counter (UINT16 big-endian)
    private static final int PAYLOAD_BYTES           = 18;  // 12 × 12-bit samples
    private static final int SAMPLES_PER_PACKET      = 12;

    // ─── Active Streaming Sessions ────────────────────────────────────────────
    // Maps deviceId → GattCallbackHandler for that session's EEG notifications.
    private final Map<String, GattCallbackHandler> _sessions = new HashMap<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Core Parser — parseEEGPacket()
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses one Muse EEG BLE notification packet into 12 µV samples.
     *
     * <p><b>Packet format:</b>
     * <ul>
     *   <li>Bytes 0–1: packet counter (UINT16 big-endian, ignored by parser)</li>
     *   <li>Bytes 2–19: 12 EEG samples packed at 12 bits each (no padding)</li>
     * </ul>
     *
     * <p><b>12-bit unpacking (group-of-3-bytes method):</b>
     * <pre>
     *   for i in 0..5:
     *     k = 2 + i * 3
     *     sample_a = (data[k] << 4) | (data[k+1] >> 4)
     *     sample_b = ((data[k+1] & 0x0F) << 8) | data[k+2]
     * </pre>
     *
     * <p><b>µV conversion:</b>
     * <pre>
     *   µV = MUSE_SCALE_FACTOR × (raw − MUSE_OFFSET)
     *      = 0.48828125 × (raw − 2048)
     * </pre>
     *
     * <p><b>Cross-platform validation:</b> This method's output must be
     * bit-identical (within IEEE 754 float precision) to the Python, Swift,
     * and TypeScript reference implementations. See the TEST VECTORS block
     * in the file header for ground-truth values.
     *
     * @param data Raw 20-byte BLE notification payload. Must have length ≥ 20.
     * @return Array of 12 floats, each a µV value in the range [−1000.0, +999.5].
     *         Returns an empty array if data is too short.
     */
    public static float[] parseEEGPacket(byte[] data) {
        if (data == null || data.length < PACKET_LENGTH) {
            Log.w(TAG, "parseEEGPacket: invalid packet (length=" + (data == null ? 0 : data.length) + ")");
            return new float[0];
        }

        // data[0..1] = sequence number (UINT16 big-endian).
        // We skip it here; the caller may extract it separately with getPacketCounter().

        float[] samples = new float[SAMPLES_PER_PACKET];

        // Process 6 groups of 3 bytes, each yielding 2 × 12-bit samples.
        // This is the GROUP-OF-3-BYTES algorithm (equivalent to the bit-offset
        // algorithm below, but more readable).
        //
        //   Group 0: bytes 2,3,4   → samples[0], samples[1]
        //   Group 1: bytes 5,6,7   → samples[2], samples[3]
        //   ...
        //   Group 5: bytes 17,18,19 → samples[10], samples[11]
        //
        // Bit layout within each group (byte[k], byte[k+1], byte[k+2]):
        //
        //   76543210 76543210 76543210
        //   AAAAAAAA AAAABBBB BBBBBBBB
        //   ← 12 A → ←── 12 B ──────→
        //
        //   sample_A = (byte[k]   << 4) | (byte[k+1] >> 4)      upper 12 bits
        //   sample_B = ((byte[k+1] & 0x0F) << 8) | byte[k+2]   lower 12 bits

        for (int group = 0; group < 6; group++) {
            int k = HEADER_BYTES + group * 3;  // byte index of this group's first byte

            // & 0xFF masks the signed byte to an unsigned int (Java bytes are signed).
            int b0 = data[k]     & 0xFF;
            int b1 = data[k + 1] & 0xFF;
            int b2 = data[k + 2] & 0xFF;

            // Extract the two 12-bit unsigned integers from this 3-byte group.
            int rawA = (b0 << 4) | (b1 >> 4);           // upper nibble of b1 completes A
            int rawB = ((b1 & 0x0F) << 8) | b2;         // lower nibble of b1 starts B

            // Convert raw [0, 4095] to signed µV:
            //   subtract 2048 (midpoint) then multiply by 0.48828125 µV/unit.
            samples[group * 2]     = MUSE_SCALE_FACTOR * (rawA - MUSE_OFFSET);
            samples[group * 2 + 1] = MUSE_SCALE_FACTOR * (rawB - MUSE_OFFSET);
        }

        return samples;
    }

    /**
     * Extracts the packet counter from the 2-byte big-endian header.
     *
     * <p>The counter increments by 1 with each Muse notification and wraps at
     * 65535. Monitoring this value allows detection of dropped BLE packets.
     * A gap of more than 1 between consecutive counters indicates packet loss.
     *
     * @param data Raw 20-byte BLE notification payload.
     * @return Packet counter in [0, 65535], or −1 if data is too short.
     */
    public static int getPacketCounter(byte[] data) {
        if (data == null || data.length < 2) return -1;
        // UINT16 big-endian: high byte at index 0, low byte at index 1.
        return ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — Streaming Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Subscribes to all 4 EEG channels and sends the stream-start command
     * to the Muse control characteristic.
     *
     * <p>Prerequisites:
     * <ul>
     *   <li>Device must be connected (BLEConnection).</li>
     *   <li>Services must have been discovered (BLEGatt.discoverServices).</li>
     *   <li>The Muse GATT service (0xFE8D) must be present.</li>
     * </ul>
     *
     * <p>This method performs:
     * <ol>
     *   <li>Subscribe to CCCD for TP9, AF7, AF8, TP10 (4 × GATT writes).</li>
     *   <li>Write MUSE_STREAM_START to MUSE_CONTROL_UUID (1 write, no-response).</li>
     * </ol>
     *
     * <p>Unity receives {@code OnEEGStreaming(json)} on success or
     * {@code OnEEGError(json)} on failure, then {@code OnEEGData(json)}
     * for each subsequent notification.
     *
     * <p><b>Note on CCCD serialization:</b> Each CCCD write is a GATT operation.
     * Android requires GATT operations to be sequential (see class header).
     * This method subscribes all 4 channels sequentially using a step-based
     * state machine, then writes the start command after all CCCDs are set.
     *
     * @param deviceId MAC address of the connected Muse device.
     */
    public void startStreaming(String deviceId) {
        Log.i(TAG, "startStreaming: " + deviceId);

        BluetoothGatt gatt = BLEConnection.getInstance().getGatt(deviceId);
        if (gatt == null) {
            Log.e(TAG, "startStreaming: no GATT connection for " + deviceId);
            sendError(deviceId, "NOT_CONNECTED");
            return;
        }

        // Verify the Muse GATT service is present.
        BluetoothGattService museService =
            gatt.getService(UUID.fromString(MUSE_SERVICE_UUID));
        if (museService == null) {
            Log.e(TAG, "startStreaming: Muse service absent on " + deviceId);
            sendError(deviceId, "MUSE_SERVICE_NOT_FOUND");
            return;
        }

        // If already streaming (e.g., Unity called startStreaming twice),
        // stop and restart to avoid duplicate callbacks.
        if (_sessions.containsKey(deviceId)) {
            Log.w(TAG, "startStreaming: already streaming for " + deviceId + " — restarting");
            cancelSession(deviceId, gatt, museService);
        }

        // Register the notification callback and subscribe to all 4 channels.
        // subscribeAllChannels() initiates the sequential CCCD-write chain.
        subscribeAllChannels(deviceId, gatt, museService);
    }

    /**
     * Stops EEG streaming by writing the halt command and unsubscribing from
     * all 4 EEG channels.
     *
     * <p>The Muse ADC is powered down immediately upon receiving the halt command.
     * Any in-flight notifications may still arrive before unsubscription completes;
     * Unity should be prepared to receive a few trailing {@code OnEEGData} messages.
     *
     * @param deviceId MAC address of the streaming Muse device.
     */
    public void stopStreaming(String deviceId) {
        Log.i(TAG, "stopStreaming: " + deviceId);

        BluetoothGatt gatt = BLEConnection.getInstance().getGatt(deviceId);
        if (gatt == null) {
            Log.w(TAG, "stopStreaming: no GATT connection for " + deviceId);
            return;
        }

        BluetoothGattService museService =
            gatt.getService(UUID.fromString(MUSE_SERVICE_UUID));

        cancelSession(deviceId, gatt, museService);

        String json = String.format("{\"id\":\"%s\",\"streaming\":false}", deviceId);
        BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_EEG_STREAMING, json);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private: Subscribe All Channels (Sequential CCCD Writes)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Subscribes to all 4 EEG channels using a sequential CCCD-write state
     * machine, then writes the Muse stream-start command.
     *
     * <p>Why sequential? Android GATT allows only one operation in flight.
     * CCCD writes are GATT operations. We must wait for each
     * {@code onDescriptorWrite()} callback before issuing the next CCCD write.
     *
     * <p>The {@code subscribeStep} counter (0–3) drives the state machine.
     * After all 4 CCCD writes succeed, the start command is sent.
     *
     * @param deviceId    MAC address of the device.
     * @param gatt        Active BluetoothGatt.
     * @param museService The Muse GATT service (pre-fetched).
     */
    private void subscribeAllChannels(
            String deviceId,
            BluetoothGatt gatt,
            BluetoothGattService museService) {

        GattCallbackHandler handler = new GattCallbackHandler(deviceId, gatt, museService);
        _sessions.put(deviceId, handler);
        BLEConnection.getInstance().registerGattCallback(deviceId, handler);

        // Kick off step 0: subscribe to TP9.
        handler.subscribeStep = 0;
        subscribeNextChannel(deviceId, gatt, museService, handler);
    }

    /**
     * Subscribes to the EEG channel indicated by {@code handler.subscribeStep}.
     *
     * <p>Each call issues one CCCD write for one channel. The callback's
     * {@code onDescriptorWrite()} increments {@code subscribeStep} and calls
     * this method again. When all 4 are done, writes the stream-start command.
     *
     * @param deviceId    Device identifier.
     * @param gatt        Active BluetoothGatt.
     * @param museService The Muse GATT service.
     * @param handler     Session's GattCallbackHandler (owns subscribeStep).
     */
    private void subscribeNextChannel(
            String deviceId,
            BluetoothGatt gatt,
            BluetoothGattService museService,
            GattCallbackHandler handler) {

        if (handler.subscribeStep >= CHANNEL_UUIDS.length) {
            // All 4 channels subscribed — now activate the Muse ADC.
            writeStreamStartCommand(deviceId, gatt, museService);
            return;
        }

        int step = handler.subscribeStep;
        String channelUuid = CHANNEL_UUIDS[step];
        String channelName = CHANNEL_NAMES[step];

        BluetoothGattCharacteristic characteristic =
            museService.getCharacteristic(UUID.fromString(channelUuid));

        if (characteristic == null) {
            Log.w(TAG, "Channel " + channelName + " characteristic absent on " + deviceId + " — skipping");
            handler.subscribeStep++;
            subscribeNextChannel(deviceId, gatt, museService, handler);
            return;
        }

        // Step 1 of 2: Tell Android to route incoming notification packets for
        // this characteristic to onCharacteristicChanged(). LOCAL operation —
        // does NOT send anything to the peripheral.
        gatt.setCharacteristicNotification(characteristic, true);

        // Step 2 of 2: Write ENABLE_NOTIFICATION_VALUE to the CCCD descriptor.
        // REMOTE operation — tells the Muse to start sending notifications
        // for this channel. Completes asynchronously via onDescriptorWrite().
        BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD_UUID);
        if (cccd == null) {
            Log.w(TAG, "CCCD absent on " + channelName + " — skip subscribe step");
            handler.subscribeStep++;
            subscribeNextChannel(deviceId, gatt, museService, handler);
            return;
        }

        Log.d(TAG, "Subscribing to " + channelName + " (step " + step + ") on " + deviceId);
        cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        boolean accepted = gatt.writeDescriptor(cccd);
        if (!accepted) {
            // Another GATT operation may be in flight (should not happen in our flow).
            // Skip and move on rather than stalling indefinitely.
            Log.e(TAG, "writeDescriptor rejected for " + channelName + " — skipping");
            handler.subscribeStep++;
            subscribeNextChannel(deviceId, gatt, museService, handler);
        }
        // Success: wait for onDescriptorWrite() callback to advance the step.
    }

    /**
     * Writes the Muse stream-start command to the control characteristic.
     *
     * <p>This is called after all 4 CCCD writes complete. It activates the
     * Muse ADC and begins pushing 256 Hz EEG samples via BLE notifications.
     *
     * <p>Uses WRITE_NO_RESPONSE — the Muse control characteristic does not
     * acknowledge writes. Using write-with-response would cause a timeout.
     *
     * @param deviceId    Device identifier.
     * @param gatt        Active BluetoothGatt.
     * @param museService The Muse GATT service.
     */
    private void writeStreamStartCommand(
            String deviceId,
            BluetoothGatt gatt,
            BluetoothGattService museService) {

        BluetoothGattCharacteristic controlChar =
            museService.getCharacteristic(UUID.fromString(MUSE_CONTROL_UUID));

        if (controlChar == null) {
            Log.e(TAG, "Control characteristic absent on " + deviceId);
            sendError(deviceId, "CONTROL_CHAR_NOT_FOUND");
            return;
        }

        // Set write type to WRITE_NO_RESPONSE (no ATT acknowledgment from Muse).
        // The Muse firmware processes the command even without an ATT-level ACK.
        controlChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        controlChar.setValue(MUSE_STREAM_START);

        boolean accepted = gatt.writeCharacteristic(controlChar);
        if (!accepted) {
            Log.e(TAG, "writeCharacteristic rejected for MUSE_STREAM_START on " + deviceId);
            sendError(deviceId, "WRITE_REJECTED");
            return;
        }

        Log.i(TAG, "EEG streaming started for " + deviceId);
        String json = String.format("{\"id\":\"%s\",\"streaming\":true}", deviceId);
        BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_EEG_STREAMING, json);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private: EEG Data Delivery
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called by {@code GattCallbackHandler.onCharacteristicChanged()} when a
     * notification arrives on one of the 4 EEG channels.
     *
     * <p>Parses the 20-byte packet via {@link #parseEEGPacket(byte[])},
     * builds the JSON payload, and sends it to Unity via {@code OnEEGData}.
     *
     * @param deviceId    MAC address of the source device.
     * @param channelName Channel name: "TP9", "AF7", "AF8", or "TP10".
     * @param data        Raw 20-byte BLE notification payload.
     */
    private void onEEGData(String deviceId, String channelName, byte[] data) {
        float[] samples = parseEEGPacket(data);
        if (samples.length == 0) {
            Log.w(TAG, "onEEGData: parseEEGPacket returned empty for " + channelName);
            return;
        }

        int counter = getPacketCounter(data);

        // Build JSON manually (no external JSON library in this plugin).
        // Format: {"id":"...","channel":"TP9","counter":1024,"samples":[f0,...,f11]}
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"id\":\"").append(deviceId).append("\",");
        sb.append("\"channel\":\"").append(channelName).append("\",");
        sb.append("\"counter\":").append(counter).append(",");
        sb.append("\"samples\":[");
        for (int i = 0; i < samples.length; i++) {
            if (i > 0) sb.append(",");
            // %.6f gives 6 decimal places — sufficient resolution for 12-bit µV values.
            sb.append(String.format(Locale.US, "%.6f", samples[i]));
        }
        sb.append("]}");

        BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_EEG_DATA, sb.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private: Session Teardown
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Unsubscribes all EEG channels and writes the stream-stop command.
     *
     * <p>Unsubscription is best-effort: if a channel characteristic is absent
     * (e.g., device disconnected mid-stream), the error is logged and the loop
     * continues. The session is always removed from {@code _sessions}.
     *
     * @param deviceId    Device identifier.
     * @param gatt        Active BluetoothGatt (may be null if disconnected).
     * @param museService The Muse GATT service (may be null if absent).
     */
    private void cancelSession(
            String deviceId,
            BluetoothGatt gatt,
            BluetoothGattService museService) {

        GattCallbackHandler handler = _sessions.remove(deviceId);
        if (handler != null) {
            BLEConnection.getInstance().unregisterGattCallback(deviceId, handler);
        }

        if (gatt == null || museService == null) return;

        // Write the Muse halt command. Best-effort — do not check return value.
        // If the device is still connected, this stops the ADC immediately.
        BluetoothGattCharacteristic controlChar =
            museService.getCharacteristic(UUID.fromString(MUSE_CONTROL_UUID));
        if (controlChar != null) {
            controlChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            controlChar.setValue(MUSE_STREAM_STOP);
            gatt.writeCharacteristic(controlChar);
            Log.d(TAG, "MUSE_STREAM_STOP written to " + deviceId);
        }

        // Disable CCCD notifications for each channel.
        for (String channelUuid : CHANNEL_UUIDS) {
            BluetoothGattCharacteristic characteristic =
                museService.getCharacteristic(UUID.fromString(channelUuid));
            if (characteristic == null) continue;

            gatt.setCharacteristicNotification(characteristic, false);

            BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD_UUID);
            if (cccd != null) {
                cccd.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(cccd);
            }
        }
        Log.i(TAG, "EEG streaming cancelled for " + deviceId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private: Error Delivery
    // ─────────────────────────────────────────────────────────────────────────

    private void sendError(String deviceId, String errorCode) {
        String json = String.format("{\"id\":\"%s\",\"error\":\"%s\"}", deviceId, errorCode);
        Log.e(TAG, "EEG error for " + deviceId + ": " + errorCode);
        BLEAndroidPlugin.getInstance().sendToUnity(UNITY_METHOD_EEG_ERROR, json);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner Class: Per-Device GATT Callback Handler
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * BluetoothGattCallback for one streaming session.
     *
     * <p>Handles:
     * <ul>
     *   <li>{@code onDescriptorWrite}  — advances the CCCD subscription state machine</li>
     *   <li>{@code onCharacteristicChanged} — delivers parsed EEG packets to Unity</li>
     * </ul>
     *
     * <p>All other callbacks ({@code onServicesDiscovered}, {@code onCharacteristicRead},
     * etc.) are intentionally not overridden to avoid interfering with BLEGatt.
     */
    private class GattCallbackHandler extends BluetoothGattCallback {

        final String deviceId;
        final BluetoothGatt gatt;
        final BluetoothGattService museService;

        /**
         * Subscription step counter.
         * 0–3: channel index being subscribed (TP9=0, AF7=1, AF8=2, TP10=3).
         * 4:   all channels subscribed.
         */
        int subscribeStep = 0;

        /**
         * Per-channel packet counter tracking for dropped-packet detection.
         * Key: channel name. Value: last seen counter (−1 = not seen yet).
         */
        private final Map<String, Integer> _lastCounters = new HashMap<>();

        GattCallbackHandler(String deviceId, BluetoothGatt gatt, BluetoothGattService museService) {
            this.deviceId    = deviceId;
            this.gatt        = gatt;
            this.museService = museService;
            for (String name : CHANNEL_NAMES) _lastCounters.put(name, -1);
        }

        // ─── onDescriptorWrite ────────────────────────────────────────────────
        //
        // Called after each CCCD write completes. Advances subscribeStep and
        // triggers the next CCCD write (or the stream-start command if done).
        //
        // Thread: GATT callback thread.

        @Override
        public void onDescriptorWrite(
                BluetoothGatt g,
                BluetoothGattDescriptor descriptor,
                int status) {

            String descUuid = descriptor.getUuid().toString();
            Log.d(TAG, "onDescriptorWrite: device=" + deviceId
                + " desc=" + descUuid + " status=" + status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                // CCCD write failed. Log and continue (do not abort the whole sequence).
                // The channel will not send notifications, but the others may still work.
                Log.e(TAG, "CCCD write failed for step " + subscribeStep + ": status=" + status);
            }

            // Advance to the next channel regardless of success/failure.
            // A failed CCCD just means that channel won't notify — not fatal.
            subscribeStep++;
            subscribeNextChannel(deviceId, gatt, museService, this);
        }

        // ─── onCharacteristicChanged (API 33+ form) ────────────────────────────
        //
        // API 33 introduced a value-passing form to fix the thread-safety issue
        // in characteristic.getValue(). See BLEGatt.java for full explanation.

        @Override
        public void onCharacteristicChanged(
                BluetoothGatt g,
                BluetoothGattCharacteristic characteristic,
                byte[] value) {
            handleNotification(characteristic, value);
        }

        // ─── onCharacteristicChanged (deprecated form, API < 33) ──────────────
        @SuppressWarnings("deprecation")
        @Override
        public void onCharacteristicChanged(
                BluetoothGatt g,
                BluetoothGattCharacteristic characteristic) {
            // Read value immediately — do NOT defer to avoid the thread-safety window.
            byte[] value = characteristic.getValue();
            handleNotification(characteristic, value);
        }

        /**
         * Shared handler for both onCharacteristicChanged variants.
         *
         * <p>Maps the characteristic UUID to a channel name, checks for
         * dropped packets (counter gap), and forwards to {@code onEEGData()}.
         *
         * @param characteristic The notified characteristic.
         * @param value          Raw 20-byte payload.
         */
        private void handleNotification(
                BluetoothGattCharacteristic characteristic,
                byte[] value) {

            // Identify which EEG channel this notification came from.
            String charUuid = characteristic.getUuid().toString().toLowerCase(Locale.US);
            String channelName = null;
            for (int i = 0; i < CHANNEL_UUIDS.length; i++) {
                if (CHANNEL_UUIDS[i].equals(charUuid)) {
                    channelName = CHANNEL_NAMES[i];
                    break;
                }
            }

            if (channelName == null) {
                // Notification from an unexpected characteristic — ignore.
                return;
            }

            // Detect dropped packets by comparing counter to last seen value.
            // Counter increments by 1 per packet; wraps at 65535.
            if (value != null && value.length >= 2) {
                int currentCounter = getPacketCounter(value);
                int lastCounter = _lastCounters.getOrDefault(channelName, -1);
                if (lastCounter >= 0) {
                    int expected = (lastCounter + 1) & 0xFFFF;  // handle wraparound
                    if (currentCounter != expected) {
                        int dropped = (currentCounter - lastCounter - 1 + 65536) & 0xFFFF;
                        Log.w(TAG, channelName + " dropped " + dropped + " packet(s): "
                            + "expected=" + expected + " got=" + currentCounter);
                    }
                }
                _lastCounters.put(channelName, currentCounter);
            }

            // Forward to the data delivery method.
            onEEGData(deviceId, channelName, value);
        }
    }
}
