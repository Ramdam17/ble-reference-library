// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : BLEDFU — OTA Firmware Update (DFU) via GATT Chunk Writes
// Platform: Android (Java) / Unity Bridge
// Sprint  : Sprint 80
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : Implement a generic BLE OTA (Over-The-Air) firmware update (DFU)
//           by writing a firmware binary in MTU-sized chunks to a designated
//           DFU characteristic. Sends progress callbacks to Unity throughout
//           the transfer and reports completion or error.
//
//           This is NOT a Nordic DFU / Polar DFU implementation.
//           It is the GENERIC chunk-write pattern that underlies all BLE DFU:
//           split the binary into packets, write them sequentially with
//           WRITE_TYPE_DEFAULT (with response), wait for acknowledgement of
//           each before sending the next.
//
//           Building a device-specific DFU (Nordic, Polar, TI OAD, etc.) means
//           implementing that device's proprietary handshake/sequence on top of
//           this chunk-write foundation.
//
// Usage   : From C# (Unity), after OnConnected + OnMtuChanged:
//
//               // Start a DFU transfer
//               byte[] firmware = System.IO.File.ReadAllBytes("firmware.bin");
//               dfu.Call("dfuTransfer",
//                   "AA:BB:CC:DD:EE:FF",                          // device MAC
//                   "00001234-0000-1000-8000-00805f9b34fb",        // DFU char UUID
//                   firmware,                                       // binary data
//                   244);                                           // chunk size (MTU - 3)
//               // → repeated OnDFUProgress(json: {percent:12, chunkIndex:5, totalChunks:42})
//               // → OnDFUComplete(json: {id:..., totalBytes:10240}) on success
//               // → OnDFUError(json: {id:..., error:...})  on failure
//
//               // Validate firmware before transfer
//               bool ok = dfu.Call<bool>("validateFirmware", firmware);
//               if (!ok) { /* reject */ }
//
//           Unity receives callbacks:
//               OnDFUProgress(String json)  — per-chunk progress update
//               OnDFUComplete(String json)  — all chunks written successfully
//               OnDFUError(String json)     — transfer failed
//
//           JSON for OnDFUProgress:
//               {"id":"AA:BB:CC:DD:EE:FF","percent":42,"chunkIndex":17,"totalChunks":41}
//
//           JSON for OnDFUComplete:
//               {"id":"AA:BB:CC:DD:EE:FF","totalBytes":10240,"totalChunks":41}
//
//           JSON for OnDFUError:
//               {"id":"AA:BB:CC:DD:EE:FF","error":"WRITE_FAILED","chunkIndex":12}
//
// Generic DFU vs. Proprietary DFU Protocols
// ──────────────────────────────────────────────────────────────────────────────
//
// The three most common proprietary BLE DFU protocols are:
//
// 1. NORDIC DFU (nRF5 SDK / nRF Connect SDK)
//    The most common DFU protocol in the embedded BLE world. Used by:
//    Polar H10, Wahoo sensors, many custom nRF52-based devices.
//    Nordic DFU has two variants:
//      a) Legacy DFU  (nRF5 SDK 11 and earlier) — simpler, less reliable
//      b) Secure DFU  (nRF5 SDK 12+) — uses Init Packets, CRC validation,
//                     image signing, and a defined state machine.
//
//    Implementing Nordic Secure DFU on top of this file requires:
//      - Writing an Init Packet (firmware metadata JSON → binary)
//        to DFU Control Point characteristic (0x1531)
//      - Receiving PRN (Packet Receipt Notification) every N chunks
//        to verify CRC-32 incrementally
//      - Handling the explicit command sequence:
//          ENTER_DFU → SET_PRN → CREATE_OBJECT → WRITE_CHUNKS → EXECUTE_OBJECT
//    Reference: Nordic Semiconductor nRF5 SDK documentation, section "BLE DFU"
//
// 2. POLAR DFU
//    Polar uses a custom DFU protocol for Polar H10 and related devices.
//    It is not publicly documented but has been reverse-engineered.
//    Polar's own Android SDK (polar-ble-sdk) handles this internally.
//    This generic implementation cannot update Polar H10 firmware.
//
// 3. TI OAD (Over-the-Air Download)
//    Used on Texas Instruments CC26xx/CC13xx devices. Uses a block-based
//    protocol with image ID headers and explicit ACK per image block.
//
// This file implements the COMMON DENOMINATOR pattern that all of the above
// protocols share: write binary data in chunks, sequentially, with acknowledgement.
// Understanding this pattern is prerequisite to implementing any proprietary DFU.
//
// Why WRITE_TYPE_DEFAULT (With Response) Is REQUIRED for DFU
// ──────────────────────────────────────────────────────────────────────────────
//
// BLE has two write types:
//
// WRITE_TYPE_DEFAULT (with response):
//   - Central sends ATT_WRITE_REQUEST
//   - Peripheral responds with ATT_WRITE_RESPONSE
//   - Central receives onCharacteristicWrite() callback with success/failure status
//   - Reliable: the callback confirms the peripheral received and processed the packet
//
// WRITE_TYPE_NO_RESPONSE (WRITE_WITHOUT_RESPONSE):
//   - Central sends ATT_WRITE_COMMAND (no response expected)
//   - Peripheral does NOT acknowledge
//   - No onCharacteristicWrite() callback
//   - Unreliable: if the peripheral's radio buffer is full, the packet is SILENTLY LOST
//   - Faster throughput for streaming (used in ECG/EEG notifications)
//
// For DFU, WRITE_TYPE_DEFAULT is MANDATORY because:
//   1. Firmware correctness is critical — a missed chunk = corrupted binary = bricked device
//   2. The acknowledgement confirms each chunk reached the peripheral's flash buffer
//   3. Without it, the central has no way to detect dropped packets or retry them
//
// The tradeoff: DFU with write-with-response is slower (~2× slower than write-without-response)
// but guarantees delivery. At MTU 247 (payload 244 bytes) with 50 ms connection interval:
//   Throughput ≈ 244 bytes × (1000/50) × 0.5 = ~2.4 KB/s
//   (The 0.5 factor accounts for request+response exchange)
//   A 100 KB firmware file takes ~42 seconds.
//
// Cross-Platform DFU Pattern Consistency
// ──────────────────────────────────────────────────────────────────────────────
//
// PYTHON (Phase 01 — Bleak):
//   await client.write_gatt_char(dfu_char_uuid, chunk, response=True)
//   The Python pattern is identical: sequential writes with response=True,
//   wrapped in a for-loop over chunks. async/await serializes naturally.
//
// SWIFT (Phase 02 — CoreBluetooth):
//   peripheral.writeValue(chunk, for: characteristic, type: .withResponse)
//   The delegate callback (didWriteValueFor:error:) is used to trigger the
//   next write. A state machine tracks chunkIndex.
//
// TYPESCRIPT / WEB BLUETOOTH (Phase 03):
//   await characteristic.writeValueWithResponse(chunk.buffer);
//   Identical pattern. Promise chaining serializes chunk writes.
//
// ALL FOUR PLATFORMS converge on the same pattern:
//   for (chunk in chunks):
//       write(chunk, withResponse=true)
//       await callback
//
// ─────────────────────────────────────────────────────────────────────────────

package com.bleref.unity;

// ─── Android Bluetooth Imports ───────────────────────────────────────────────

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

// ─── Android System Imports ──────────────────────────────────────────────────

import android.util.Log;

// ─── Java Standard Library ───────────────────────────────────────────────────

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// ─────────────────────────────────────────────────────────────────────────────

public class BLEDFU {

    // ─── Logging Tag ─────────────────────────────────────────────────────────
    // Filter logcat output with: adb logcat -s BLEDFU
    private static final String TAG = "BLEDFU";

    // ─── Unity Callback Method Names ─────────────────────────────────────────
    private static final String UNITY_CB_PROGRESS = "OnDFUProgress";
    private static final String UNITY_CB_COMPLETE = "OnDFUComplete";
    private static final String UNITY_CB_ERROR    = "OnDFUError";

    // ─── Firmware Size Limits ────────────────────────────────────────────────
    //
    // MAX_FIRMWARE_BYTES: 512 KB is a reasonable upper bound for consumer
    // wearable firmware binaries. Real values:
    //   Polar H10 firmware : ~100-150 KB
    //   Nordic nRF52840    : up to 1 MB flash, but DFU images ~200 KB
    //   TI CC2640R2        : up to 128 KB
    //
    // validateFirmware() rejects images above this limit to prevent accidental
    // transmission of wrong files (e.g., passing a full app APK instead of a
    // firmware binary). Adjust this constant if your target device has larger firmware.

    private static final int MAX_FIRMWARE_BYTES = 512 * 1024; // 512 KB

    // ─── DFU Transfer State ───────────────────────────────────────────────────
    //
    // These fields track the current (or most recent) DFU transfer.
    // Only one DFU transfer is supported at a time — a second call to
    // dfuTransfer() while one is in progress will be rejected.

    private final AtomicBoolean _isDfuActive   = new AtomicBoolean(false);
    private volatile byte[]     _firmware      = null;
    private volatile byte[][]   _chunks        = null;
    private volatile String     _activeDeviceId  = null;
    private volatile String     _activeDfuCharUUID = null;
    private final AtomicInteger _currentChunkIndex = new AtomicInteger(0);

    // ─── Singleton ────────────────────────────────────────────────────────────

    private static BLEDFU _instance;

    /**
     * Returns the singleton BLEDFU instance.
     *
     * Called from C# via:
     *   var dfu = new AndroidJavaClass("com.bleref.unity.BLEDFU")
     *       .CallStatic<AndroidJavaObject>("getInstance");
     *
     * @return The shared BLEDFU singleton.
     */
    public static BLEDFU getInstance() {
        if (_instance == null) {
            _instance = new BLEDFU();
            Log.d(TAG, "BLEDFU singleton created.");
        }
        return _instance;
    }

    private BLEDFU() {}

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Split a firmware binary into MTU-sized chunks.
     *
     * This is the core decomposition step: divide the firmware byte array
     * into sequential sub-arrays of exactly chunkSize bytes (last chunk
     * may be shorter).
     *
     * The chunk size should be:
     *   chunkSize = negotiatedMtu - 3   (ATT header overhead)
     * Use BLEMTU.getInstance().getNegotiatedPayloadSize(deviceId) to get this
     * value automatically after MTU negotiation (BLEMTU.java, Sprint 79).
     *
     * Example:
     *   firmware = 10240 bytes, chunkSize = 244
     *   → chunks[0..41] = 244 bytes each, chunks[42] = 64 bytes
     *   → totalChunks = 43
     *
     * @param firmware  Firmware binary to split.
     * @param chunkSize Maximum bytes per chunk (≥1).
     * @return          Array of byte arrays, each at most chunkSize bytes.
     *                  Returns null if firmware is null/empty or chunkSize < 1.
     */
    public byte[][] chunkFirmware(byte[] firmware, int chunkSize) {
        if (firmware == null || firmware.length == 0) {
            Log.e(TAG, "chunkFirmware: firmware is null or empty.");
            return null;
        }
        if (chunkSize < 1) {
            Log.e(TAG, "chunkFirmware: chunkSize must be >= 1, got " + chunkSize);
            return null;
        }

        // Compute the number of chunks needed (ceiling division)
        int totalChunks = (int) Math.ceil((double) firmware.length / chunkSize);
        byte[][] chunks = new byte[totalChunks][];

        for (int i = 0; i < totalChunks; i++) {
            int start  = i * chunkSize;
            // The last chunk may be smaller than chunkSize
            int end    = Math.min(start + chunkSize, firmware.length);
            chunks[i]  = Arrays.copyOfRange(firmware, start, end);
        }

        Log.d(TAG, String.format("chunkFirmware: %d bytes → %d chunks of %d bytes",
            firmware.length, totalChunks, chunkSize));
        return chunks;
    }

    /**
     * Validate a firmware binary before attempting a DFU transfer.
     *
     * Performs sanity checks to catch obvious errors (wrong file, empty buffer,
     * unreasonably large binary) before starting a potentially multi-minute
     * transfer that would fail partway through.
     *
     * This validation is intentionally minimal — it does NOT verify CRC,
     * magic bytes, or image headers (those are device-specific). Nordic DFU
     * validation, for example, requires parsing an Init Packet with CRC-32
     * and signature verification, which is beyond this generic implementation.
     *
     * Called from C# before initiating a transfer:
     *   bool ok = dfu.Call<bool>("validateFirmware", firmware);
     *
     * @param firmware  Byte array representing the firmware binary.
     * @return          true if the firmware passes basic sanity checks.
     */
    public boolean validateFirmware(byte[] firmware) {
        if (firmware == null) {
            Log.e(TAG, "validateFirmware: firmware is null.");
            return false;
        }
        if (firmware.length == 0) {
            Log.e(TAG, "validateFirmware: firmware is empty (0 bytes).");
            return false;
        }
        if (firmware.length > MAX_FIRMWARE_BYTES) {
            Log.e(TAG, String.format(
                "validateFirmware: firmware size %d bytes exceeds MAX_FIRMWARE_BYTES %d. "
                + "Verify you are passing a firmware binary, not an APK or other large file.",
                firmware.length, MAX_FIRMWARE_BYTES));
            return false;
        }

        Log.d(TAG, String.format("validateFirmware: %d bytes — OK.", firmware.length));
        return true;
    }

    /**
     * Start an OTA firmware update by writing firmware chunks to a DFU characteristic.
     *
     * Chunks are written sequentially, one at a time, using WRITE_TYPE_DEFAULT
     * (with response). After each chunk's onCharacteristicWrite() callback fires
     * with GATT_SUCCESS, the next chunk is enqueued. Progress is reported to
     * Unity as OnDFUProgress after each acknowledged chunk.
     *
     * The writes are executed through BLECommandQueue to guarantee serial execution
     * alongside any other GATT operations that may be in flight. This is the
     * SAME queue used by BLEGatt, BLEMTU, and BLERSSIDistance.
     *
     * PRE-CONDITIONS (must be satisfied before calling):
     *   1. GATT connection active (BLEConnection.java — OnConnected fired)
     *   2. Services discovered (BLEGatt.java — OnServicesDiscovered fired)
     *   3. MTU negotiated (BLEMTU.java — OnMtuChanged fired)
     *      Pass chunkSize = BLEMTU.getInstance().getNegotiatedPayloadSize(deviceId)
     *   4. Firmware validated (validateFirmware() returned true)
     *
     * Typical call from C#:
     *   int chunkSize = mtu.Call<int>("getNegotiatedPayloadSize", "AA:BB:CC:DD:EE:FF");
     *   dfu.Call("dfuTransfer",
     *       "AA:BB:CC:DD:EE:FF",
     *       "00001234-0000-1000-8000-00805f9b34fb",
     *       firmware,
     *       chunkSize);
     *
     * @param deviceId     MAC address of the connected peripheral.
     * @param dfuCharUUID  UUID of the DFU data characteristic to write chunks to.
     * @param firmware     Firmware binary (already validated via validateFirmware()).
     * @param chunkSize    Bytes per chunk. Must be ≤ (negotiated_MTU - 3).
     *                     Exceeding the MTU payload causes GATT_INVALID_ATTRIBUTE_LENGTH.
     */
    public void dfuTransfer(String deviceId, String dfuCharUUID,
                             byte[] firmware, int chunkSize) {
        Log.i(TAG, String.format("dfuTransfer: device=%s charUUID=%s firmware=%d bytes chunkSize=%d",
            deviceId, dfuCharUUID, (firmware != null ? firmware.length : 0), chunkSize));

        // ── Guard: only one DFU at a time ─────────────────────────────────────
        if (_isDfuActive.get()) {
            Log.e(TAG, "dfuTransfer: a DFU transfer is already in progress.");
            String json = String.format("{\"id\":\"%s\",\"error\":\"DFU_ALREADY_ACTIVE\"}", deviceId);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_ERROR, json);
            return;
        }

        // ── Validate inputs ───────────────────────────────────────────────────
        if (!validateFirmware(firmware)) {
            String json = String.format("{\"id\":\"%s\",\"error\":\"INVALID_FIRMWARE\"}", deviceId);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_ERROR, json);
            return;
        }

        // ── Get GATT connection ───────────────────────────────────────────────
        BluetoothGatt gatt = BLEConnection.getInstance().getGatt(deviceId);
        if (gatt == null) {
            Log.e(TAG, "dfuTransfer: no active GATT connection for " + deviceId);
            String json = String.format("{\"id\":\"%s\",\"error\":\"NOT_CONNECTED\"}", deviceId);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_ERROR, json);
            return;
        }

        // ── Locate the DFU characteristic ─────────────────────────────────────
        // The characteristic must already be discovered. If services haven't
        // been discovered yet (BLEGatt.discoverServices()), getCharacteristic()
        // returns null and we abort with a clear error.
        BluetoothGattCharacteristic dfuChar = findCharacteristic(gatt, dfuCharUUID);
        if (dfuChar == null) {
            Log.e(TAG, "dfuTransfer: DFU characteristic not found: " + dfuCharUUID
                + ". Did you call discoverServices() and wait for OnServicesDiscovered?");
            String json = String.format(
                "{\"id\":\"%s\",\"error\":\"CHARACTERISTIC_NOT_FOUND\",\"charUUID\":\"%s\"}",
                deviceId, dfuCharUUID);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_ERROR, json);
            return;
        }

        // ── Chunk the firmware ────────────────────────────────────────────────
        byte[][] chunks = chunkFirmware(firmware, chunkSize);
        if (chunks == null) {
            String json = String.format("{\"id\":\"%s\",\"error\":\"CHUNKING_FAILED\"}", deviceId);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_ERROR, json);
            return;
        }

        // ── Initialize DFU state ──────────────────────────────────────────────
        _isDfuActive.set(true);
        _firmware            = firmware;
        _chunks              = chunks;
        _activeDeviceId      = deviceId;
        _activeDfuCharUUID   = dfuCharUUID;
        _currentChunkIndex.set(0);

        Log.i(TAG, String.format("DFU transfer starting: %d bytes in %d chunks of %d bytes.",
            firmware.length, chunks.length, chunkSize));

        // ── Set write type to WITH_RESPONSE ───────────────────────────────────
        // WRITE_TYPE_DEFAULT = 0x02 = WRITE_WITH_RESPONSE (ATT_WRITE_REQUEST).
        // This is mandatory for DFU — see file header for detailed explanation.
        // Without this, writes use WRITE_TYPE_NO_RESPONSE (WRITE_WITHOUT_RESPONSE)
        // which silently drops packets when the peripheral buffer is full.
        dfuChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

        // ── Enqueue the first chunk ───────────────────────────────────────────
        // Subsequent chunks are enqueued by onChunkWritten() as each write completes.
        // This "chain of callbacks" pattern ensures exactly one chunk is in flight
        // at any given time, which is required by the BLE ATT protocol.
        enqueueChunk(gatt, dfuChar, 0);
    }

    /**
     * Called by BLEGatt's onCharacteristicWrite() when a DFU chunk write completes.
     *
     * Checks whether the write succeeded, updates progress, and enqueues the
     * next chunk. When all chunks are written, fires OnDFUComplete.
     *
     * This method is the "advance" step in the callback-chained DFU loop.
     *
     * @param gatt   The active GATT connection.
     * @param status BluetoothGatt.GATT_SUCCESS (0) or an error code.
     */
    public void onChunkWritten(BluetoothGatt gatt, int status) {
        if (!_isDfuActive.get()) return; // Not our write (some other GATT write)

        String deviceId = gatt.getDevice().getAddress();
        int chunkIndex  = _currentChunkIndex.get();
        int totalChunks = (_chunks != null) ? _chunks.length : 0;

        // ── Advance the BLECommandQueue ───────────────────────────────────────
        // Every enqueued GATT operation must call onOperationComplete().
        BLECommandQueue.getInstance().onOperationComplete();

        if (status != BluetoothGatt.GATT_SUCCESS) {
            // ── Write failed ──────────────────────────────────────────────────
            // Possible causes:
            //   - Device disconnected mid-transfer
            //   - Peripheral's flash write failed (buffer full, erase failure)
            //   - Peripheral's DFU state machine rejected this chunk
            //     (sequence error, wrong offset, wrong object type for Nordic DFU)
            Log.e(TAG, String.format(
                "DFU chunk write failed: chunkIndex=%d status=%d", chunkIndex, status));

            _isDfuActive.set(false);
            String json = String.format(
                "{\"id\":\"%s\",\"error\":\"WRITE_FAILED\",\"chunkIndex\":%d,\"status\":%d}",
                deviceId, chunkIndex, status);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_ERROR, json);
            resetState();
            return;
        }

        // ── Chunk written successfully ────────────────────────────────────────
        int nextIndex = chunkIndex + 1;

        // ── Report progress ───────────────────────────────────────────────────
        // Progress percentage: (chunks written / total) × 100
        // We report AFTER each successful write so percent reaches 100 only when
        // all chunks are confirmed received.
        int percent = (int) Math.round(100.0 * nextIndex / totalChunks);
        String progressJson = String.format(
            "{\"id\":\"%s\",\"percent\":%d,\"chunkIndex\":%d,\"totalChunks\":%d}",
            deviceId, percent, chunkIndex, totalChunks);
        BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_PROGRESS, progressJson);

        Log.d(TAG, String.format("DFU chunk %d/%d written. %d%%",
            nextIndex, totalChunks, percent));

        // ── Check if transfer is complete ─────────────────────────────────────
        if (nextIndex >= totalChunks) {
            Log.i(TAG, "DFU transfer complete: " + _firmware.length + " bytes sent.");
            _isDfuActive.set(false);

            String completeJson = String.format(
                "{\"id\":\"%s\",\"totalBytes\":%d,\"totalChunks\":%d}",
                deviceId, _firmware.length, totalChunks);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_COMPLETE, completeJson);

            // Note: most peripherals will reset/reboot after receiving the last
            // DFU chunk, causing a disconnect. BLEConnection will handle the
            // STATE_DISCONNECTED callback normally — no special handling needed here.
            resetState();
            return;
        }

        // ── Enqueue the next chunk ────────────────────────────────────────────
        _currentChunkIndex.set(nextIndex);
        BluetoothGattCharacteristic dfuChar = findCharacteristic(gatt, _activeDfuCharUUID);
        if (dfuChar == null) {
            Log.e(TAG, "DFU characteristic disappeared during transfer.");
            _isDfuActive.set(false);
            String json = String.format(
                "{\"id\":\"%s\",\"error\":\"CHARACTERISTIC_LOST\",\"chunkIndex\":%d}",
                deviceId, nextIndex);
            BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_ERROR, json);
            resetState();
            return;
        }

        enqueueChunk(gatt, dfuChar, nextIndex);
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    /**
     * Enqueue a single firmware chunk write via BLECommandQueue.
     *
     * Sets the characteristic value to the chunk bytes and enqueues a
     * WRITE_TYPE_DEFAULT write. The callback (onChunkWritten) will call
     * onOperationComplete() when done and enqueue the next chunk.
     *
     * @param gatt       Active GATT connection.
     * @param dfuChar    The DFU data characteristic to write to.
     * @param chunkIndex Index into the _chunks array.
     */
    private void enqueueChunk(BluetoothGatt gatt, BluetoothGattCharacteristic dfuChar,
                               int chunkIndex) {
        byte[] chunk = _chunks[chunkIndex];

        BLECommandQueue.getInstance().enqueue(() -> {
            // ── Set the chunk payload ──────────────────────────────────────────
            // setValue() stores the bytes in the local characteristic object.
            // writeCharacteristic() then sends these bytes to the peripheral.
            dfuChar.setValue(chunk);

            // ── Issue the write ────────────────────────────────────────────────
            // writeCharacteristic() returns false if:
            //   - Another GATT operation is in progress (shouldn't happen with queue)
            //   - Device has disconnected
            // If false, advance the queue and report an error.
            boolean ok = gatt.writeCharacteristic(dfuChar);
            if (!ok) {
                Log.e(TAG, "DFU writeCharacteristic() returned false at chunk " + chunkIndex
                    + ". Device may have disconnected or GATT is in unexpected state.");
                // onChunkWritten won't fire — manually advance the queue
                BLECommandQueue.getInstance().onOperationComplete();
                _isDfuActive.set(false);
                String json = String.format(
                    "{\"id\":\"%s\",\"error\":\"WRITE_REJECTED\",\"chunkIndex\":%d}",
                    gatt.getDevice().getAddress(), chunkIndex);
                BLEAndroidPlugin.getInstance().sendToUnity(UNITY_CB_ERROR, json);
                resetState();
            }
            // If ok=true: onChunkWritten() will call onOperationComplete() when the
            // peripheral acknowledges the write.
        });
    }

    /**
     * Search all GATT services of a connection for a characteristic by UUID.
     *
     * @param gatt     Active GATT connection with discovered services.
     * @param charUUID UUID string of the characteristic to find.
     * @return         BluetoothGattCharacteristic, or null if not found.
     */
    private BluetoothGattCharacteristic findCharacteristic(BluetoothGatt gatt, String charUUID) {
        if (gatt == null || charUUID == null) return null;
        java.util.UUID targetUUID = java.util.UUID.fromString(charUUID);
        for (BluetoothGattService service : gatt.getServices()) {
            BluetoothGattCharacteristic c = service.getCharacteristic(targetUUID);
            if (c != null) return c;
        }
        return null;
    }

    /** Clear per-transfer state. Called on completion or error. */
    private void resetState() {
        _firmware          = null;
        _chunks            = null;
        _activeDeviceId    = null;
        _activeDfuCharUUID = null;
        _currentChunkIndex.set(0);
    }

    // ─── Accessors ───────────────────────────────────────────────────────────

    /**
     * Returns true if a DFU transfer is currently in progress.
     *
     * @return true if dfuTransfer() was called and has not yet completed or failed.
     */
    public boolean isDfuActive() {
        return _isDfuActive.get();
    }

    /**
     * Returns the current chunk index of the active DFU transfer.
     *
     * Useful for monitoring from a C# coroutine alongside OnDFUProgress callbacks.
     *
     * @return Current chunk index, or 0 if no transfer is active.
     */
    public int getCurrentChunkIndex() {
        return _currentChunkIndex.get();
    }
}
