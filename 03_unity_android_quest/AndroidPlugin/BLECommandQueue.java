// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : BLECommandQueue — Serial GATT Command Queue
// Platform: Android (Java) / Unity Bridge
// Sprint  : Sprint 80
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : Enforce strictly serial execution of GATT operations on Android.
//           Every call to BluetoothGatt (read, write, subscribe, MTU request)
//           must be issued ONLY after the previous operation's callback has
//           fired. Violating this rule causes silent failure.
//
//           This queue wraps each GATT call in a Runnable, executes them one
//           at a time on a background HandlerThread, and advances to the next
//           command when the current one completes (via onOperationComplete())
//           or times out.
//
//           This file MUST be integrated before the CCCD write + notification
//           subscribe sequence in BLEGatt.java. Subscribing to notifications
//           requires TWO sequential GATT operations:
//             1. setCharacteristicNotification(char, true)  — local registration
//             2. writeDescriptor(cccdDescriptor)            — remote CCCD write
//           Without a queue, step 2 frequently silently fails, causing
//           "notifications work on iOS/Python but not Android" bugs.
//
// Usage   : From any plugin class (BLEGatt, BLEDFU, BLEMTU):
//
//               // Enqueue a GATT read
//               BLECommandQueue.getInstance().enqueue(() -> {
//                   gatt.readCharacteristic(characteristic);
//               });
//
//               // Enqueue a write, then a CCCD write
//               BLECommandQueue.getInstance().enqueue(() -> {
//                   gatt.writeCharacteristic(characteristic);
//               });
//               BLECommandQueue.getInstance().enqueue(() -> {
//                   gatt.writeDescriptor(cccdDescriptor);
//               });
//
//               // In the BluetoothGattCallback, ALWAYS call this:
//               BLECommandQueue.getInstance().onOperationComplete();
//
//           Internal call from callbacks (BLEGatt, BLEMTU, BLEDFU):
//               BLECommandQueue.getInstance().onOperationComplete();
//
//           From C# (Unity) — for monitoring only:
//               int depth = queue.Call<int>("getQueueDepth");
//               bool busy = queue.Call<bool>("isBusy");
//               queue.Call("clearQueue");  // emergency flush
//
// Why Android GATT Requires Serial Execution
// ──────────────────────────────────────────────────────────────────────────────
//
// The Android BluetoothGatt class wraps the C/C++ Bluetooth stack (Bluedroid
// on pre-Oreo, Fluoride/GD on Oreo+). At the L2CAP level, ATT is a request-
// response protocol: only ONE ATT request can be in flight on a connection
// at any given time. The protocol itself enforces serialization — the central
// MUST wait for a response before sending the next request.
//
// Android's Java BluetoothGatt API does NOT enforce this for you. Calling:
//
//     gatt.readCharacteristic(charA);
//     gatt.writeCharacteristic(charB);    // issued before readCharacteristic() callback
//
// results in the SECOND operation being SILENTLY DROPPED. Android logs a
// warning in logcat ("onCharacteristicRead() skipped because prior operation
// is pending") but does NOT throw an exception or return false. The C# caller
// never receives the write callback and has no way to know it failed.
//
// This is documented in the Android source but NOT in the public Android docs,
// which is why it is one of the most common Android BLE bugs.
// Ref: https://android.googlesource.com/platform/packages/modules/Bluetooth/
//      (BluetoothGatt.java, checkOperationValid())
//
// How Other Platforms Handle This
// ──────────────────────────────────────────────────────────────────────────────
//
// PYTHON / BLEAK (Phase 01):
//   Python's async/await model naturally serializes operations. Writing:
//
//       await client.read_gatt_char(char_a)
//       await client.write_gatt_char(char_b, data)
//
//   serializes execution — the second line does NOT execute until the first
//   await returns. Python developers rarely encounter this bug because the
//   language enforces it at the syntax level.
//
// SWIFT / CORE BLUETOOTH (Phase 02):
//   CoreBluetooth also silently drops overlapping operations but is slightly
//   more forgiving: some operations (e.g., readCharacteristic + readCharacteristic
//   for different characteristics) succeed concurrently on iOS due to internal
//   CoreBluetooth queuing. However, best practice is still to serialize, and
//   the Bluetooth SIG explicitly recommends waiting for the callback.
//
//   Swift apps typically use a state machine (an enum with IDLE/BUSY states)
//   rather than a formal queue. The pattern is functionally equivalent.
//
// ANDROID (this file):
//   Android has no implicit serialization. A formal queue is the recommended
//   solution, widely used in production BLE libraries:
//     - RxAndroidBLE uses an Observable chain
//     - Nordic's Android-BLE-Library (the gold standard) uses a command queue
//       essentially identical to this one
//
// WEB BLUETOOTH / TYPESCRIPT (Phase 03):
//   JavaScript Promise chaining naturally serializes:
//       await characteristic.readValue();
//       await descriptor.writeValue(cccdBytes);
//   Similar to Python's async/await — sequential by default.
//
// Timeout Rationale — Why 5 Seconds?
// ──────────────────────────────────────────────────────────────────────────────
// BLE operations on modern hardware typically complete in 30-200 ms.
// The BLE connection interval adds quantized latency: at 50 ms connection
// interval, a write typically takes 50-200 ms end-to-end.
//
// A 5-second timeout is chosen to:
//   - Be long enough to accommodate slow peripherals (low connection interval,
//     congested RF environment, peripheral processing time for DFU writes).
//   - Be short enough to fail fast and allow the queue to recover rather than
//     blocking indefinitely (which would hang the Unity app).
//
// If a 5-second timeout fires, it is almost always a sign of:
//   - A disconnected peripheral (onConnectionStateChange was missed)
//   - A firmware bug on the peripheral (no response to a valid request)
//   - An incorrect characteristic UUID (write to non-existent char = no response)
//
// ─────────────────────────────────────────────────────────────────────────────

package com.bleref.unity;

// ─── Android System Imports ──────────────────────────────────────────────────

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

// ─── Java Standard Library ───────────────────────────────────────────────────

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

// ─────────────────────────────────────────────────────────────────────────────

public class BLECommandQueue {

    // ─── Logging Tag ─────────────────────────────────────────────────────────
    // Filter logcat output with: adb logcat -s BLECommandQueue
    private static final String TAG = "BLECommandQueue";

    // ─── Operation Timeout ────────────────────────────────────────────────────
    //
    // Time in milliseconds to wait for a GATT operation callback before
    // declaring the operation timed out and advancing the queue.
    //
    // 5000 ms is a conservative default. For DFU chunk writes to fast peripherals
    // you may reduce this. For write-to-notify-setup sequences on congested BT
    // environments, you may increase it.
    private static final long OPERATION_TIMEOUT_MS = 5000L;

    // ─── Command Queue ────────────────────────────────────────────────────────
    //
    // A LinkedList is used as the backing store because:
    //   - O(1) offer() (enqueue at tail)
    //   - O(1) poll()  (dequeue from head)
    //   - No capacity limit (unlike ArrayDeque with a fixed size)
    //
    // Access is synchronized on _queueLock for thread safety.
    // GATT callbacks arrive on a Binder thread; enqueue() may be called from
    // the Unity main thread or any background thread — synchronized is required.
    private final Queue<Runnable> _commandQueue = new LinkedList<>();
    private final Object          _queueLock    = new Object();

    // ─── State ────────────────────────────────────────────────────────────────
    //
    // _isBusy: true when a GATT operation is in flight (submitted but callback
    //          not yet received). AtomicBoolean for lock-free read from any thread.
    //
    // The canonical state machine:
    //   IDLE  (isBusy=false): no operation pending; next enqueue() fires immediately
    //   BUSY  (isBusy=true):  operation in flight; enqueue() appends to queue only
    private final AtomicBoolean _isBusy = new AtomicBoolean(false);

    // ─── Executor Thread ─────────────────────────────────────────────────────
    //
    // A dedicated HandlerThread is used to execute queue commands.
    // This keeps GATT operations off the main Unity thread, preventing
    // frame-rate hitches when a write takes >16 ms (one frame at 60 fps).
    //
    // HandlerThread creates its own Looper, allowing Handler.post() to queue
    // runnables onto it.
    private final HandlerThread _workerThread;
    private final Handler       _workerHandler;

    // ─── Timeout Handler ──────────────────────────────────────────────────────
    //
    // Uses the same worker thread for timeout detection. The timeout runnable
    // is posted with a delay and cancelled in onOperationComplete() if the
    // callback arrives on time.
    private Runnable _timeoutRunnable = null;

    // ─── Singleton ────────────────────────────────────────────────────────────

    private static BLECommandQueue _instance;

    /**
     * Returns the singleton BLECommandQueue instance.
     *
     * The worker HandlerThread is started at creation time and runs for the
     * lifetime of the plugin. There is one queue per app process — all
     * devices share the same queue. For multi-device parallel streaming,
     * consider a per-device queue (not implemented here for simplicity).
     *
     * @return The shared BLECommandQueue singleton.
     */
    public static BLECommandQueue getInstance() {
        if (_instance == null) {
            _instance = new BLECommandQueue();
            Log.d(TAG, "BLECommandQueue singleton created.");
        }
        return _instance;
    }

    private BLECommandQueue() {
        // Start a named background thread for executing GATT commands.
        // The "BLE-CmdQueue" name appears in Android profiler / thread views.
        _workerThread = new HandlerThread("BLE-CmdQueue");
        _workerThread.start();
        _workerHandler = new Handler(_workerThread.getLooper());
        Log.d(TAG, "BLECommandQueue worker thread started.");
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Enqueue a GATT command for serial execution.
     *
     * If the queue is currently idle (no operation in flight), the command
     * is executed immediately on the worker thread. If a prior command is
     * still in progress, the new command is appended to the tail of the
     * queue and will execute after onOperationComplete() advances the queue.
     *
     * Usage pattern (from BLEGatt, BLEDFU, BLEMTU):
     *
     *   // Enqueue a read
     *   BLECommandQueue.getInstance().enqueue(() -> {
     *       boolean ok = gatt.readCharacteristic(characteristic);
     *       if (!ok) {
     *           // Read was rejected by Android (typically: GATT busy or disconnected)
     *           // Advance the queue manually so it doesn't stall
     *           BLECommandQueue.getInstance().onOperationComplete();
     *       }
     *   });
     *
     * IMPORTANT: Every Runnable passed to enqueue() MUST ensure that
     * onOperationComplete() is eventually called — either from the
     * corresponding BluetoothGattCallback or from an error path within the
     * Runnable itself. Failing to call onOperationComplete() stalls the queue.
     *
     * @param command A Runnable that issues exactly one GATT operation.
     *                The Runnable must be short-lived — do NOT block inside it.
     */
    public void enqueue(Runnable command) {
        synchronized (_queueLock) {
            _commandQueue.offer(command);
            Log.d(TAG, "Command enqueued. Queue depth=" + _commandQueue.size()
                + " busy=" + _isBusy.get());
        }
        // Attempt to start execution if queue was idle
        executeNext();
    }

    /**
     * Signal that the current GATT operation has completed.
     *
     * MUST be called from every BluetoothGattCallback method that corresponds
     * to an enqueued operation. This includes:
     *
     *   - onCharacteristicRead()       ← readCharacteristic()
     *   - onCharacteristicWrite()      ← writeCharacteristic()
     *   - onDescriptorWrite()          ← writeDescriptor() (CCCD!)
     *   - onDescriptorRead()           ← readDescriptor()
     *   - onReliableWriteCompleted()   ← executeReliableWrite()
     *   - onMtuChanged()               ← requestMtu()
     *   - onReadRemoteRssi()           ← readRemoteRssi()
     *   - onServicesDiscovered()       ← discoverServices()
     *
     * Calling this method from anywhere else (e.g., a timeout, an error path)
     * is also safe and correct — the queue simply advances to the next command.
     *
     * This method is safe to call from any thread.
     */
    public void onOperationComplete() {
        Log.d(TAG, "onOperationComplete: advancing queue.");

        // ── Cancel the timeout watchdog ───────────────────────────────────────
        // The operation completed on time — we don't need the timeout to fire.
        if (_timeoutRunnable != null) {
            _workerHandler.removeCallbacks(_timeoutRunnable);
            _timeoutRunnable = null;
        }

        // ── Mark as idle ──────────────────────────────────────────────────────
        _isBusy.set(false);

        // ── Execute the next command ──────────────────────────────────────────
        // Post to the worker thread. This ensures:
        //   - We don't recurse (executeNext → callback → onOperationComplete → executeNext)
        //   - The next command runs on the dedicated worker thread, not the caller's thread
        _workerHandler.post(this::executeNext);
    }

    /**
     * Clear all pending (not-yet-executed) commands from the queue.
     *
     * Does NOT cancel the currently in-flight operation (you cannot abort a
     * GATT request once submitted). Use this on disconnect or when you want
     * to abort a queued DFU transfer before it starts executing.
     *
     * After clearQueue(), the queue will be empty once the current operation
     * completes and onOperationComplete() is called.
     *
     * Called from C# in emergency situations:
     *   queue.Call("clearQueue");
     */
    public void clearQueue() {
        synchronized (_queueLock) {
            int count = _commandQueue.size();
            _commandQueue.clear();
            Log.w(TAG, "Queue cleared. Discarded " + count + " pending commands.");
        }
    }

    // ─── Accessors ───────────────────────────────────────────────────────────

    /**
     * Returns the number of commands currently waiting in the queue.
     *
     * This count does NOT include the currently executing command.
     * A depth of 0 with isBusy()=true means one command is in flight.
     *
     * @return Number of pending (not yet started) commands.
     */
    public int getQueueDepth() {
        synchronized (_queueLock) {
            return _commandQueue.size();
        }
    }

    /**
     * Returns true if a GATT operation is currently in flight.
     *
     * A command has been dequeued and its callback has not yet been received.
     *
     * @return true if busy, false if idle.
     */
    public boolean isBusy() {
        return _isBusy.get();
    }

    // ─── Internal Execution Logic ─────────────────────────────────────────────

    /**
     * Dequeue and execute the next command if the queue is idle.
     *
     * This is the core of the serial execution logic:
     *   - If busy: do nothing (the pending callback will call executeNext() later)
     *   - If idle and queue non-empty: dequeue one command, mark busy, run it,
     *     start timeout watchdog
     *   - If idle and queue empty: nothing to do
     *
     * Thread safety: compareAndSet() on _isBusy ensures that even if two threads
     * call executeNext() simultaneously, only one successfully sets busy=true
     * and executes. The other sees busy=true and returns without doing anything.
     */
    private void executeNext() {
        // ── Atomic idle→busy transition ───────────────────────────────────────
        // compareAndSet(false, true) atomically:
        //   - Reads _isBusy
        //   - If it is false, sets it to true and returns true
        //   - If it is true (already busy), returns false
        // This is the core of the thread-safety guarantee.
        if (!_isBusy.compareAndSet(false, true)) {
            // Queue is already busy — wait for onOperationComplete()
            Log.d(TAG, "executeNext: queue is busy, deferring.");
            return;
        }

        // ── Dequeue next command ──────────────────────────────────────────────
        Runnable command;
        synchronized (_queueLock) {
            command = _commandQueue.poll();
        }

        if (command == null) {
            // Queue is empty — release the busy lock and return
            _isBusy.set(false);
            Log.d(TAG, "executeNext: queue empty, now idle.");
            return;
        }

        // ── Start timeout watchdog ─────────────────────────────────────────────
        // If the callback doesn't arrive within OPERATION_TIMEOUT_MS, we assume
        // something went wrong (peripheral disconnected, firmware hung, wrong UUID)
        // and advance the queue to prevent a permanent stall.
        _timeoutRunnable = () -> {
            Log.e(TAG, "GATT operation timed out after " + OPERATION_TIMEOUT_MS + " ms. "
                + "Forcing queue advancement. Check: (1) device still connected? "
                + "(2) correct characteristic UUID? (3) peripheral responding?");
            onOperationComplete(); // Advance the queue despite the missing callback
        };
        _workerHandler.postDelayed(_timeoutRunnable, OPERATION_TIMEOUT_MS);

        // ── Execute the command ───────────────────────────────────────────────
        // Run the Runnable directly on the worker thread. The Runnable calls a
        // single BluetoothGatt method (read/write/subscribe/mtu/rssi).
        // The corresponding callback MUST call onOperationComplete().
        Log.d(TAG, "Executing queued GATT command. Remaining queue depth="
            + getQueueDepth());
        try {
            command.run();
        } catch (Exception e) {
            // Catch any unexpected exception from the command so the queue
            // doesn't silently stall. Log it and advance.
            Log.e(TAG, "Exception during GATT command execution: " + e.getMessage());
            onOperationComplete();
        }
    }
}
