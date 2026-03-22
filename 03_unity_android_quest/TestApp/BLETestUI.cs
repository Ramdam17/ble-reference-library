// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library
// Module  : BLETestUI.cs — Unity Quest TestApp UI Controller
// Platform: Android (Unity) — Quest 2 / 3 / Pro
// Sprint  : Sprint 81
// Phase   : Phase 04 — Unity / Android / Quest
//
// Purpose : Main MonoBehaviour UI controller for the Quest integration TestApp
//           scene. Manages a scrollable log panel, per-device test buttons,
//           status text, and a live "last received value" display. Mirrors the
//           HTML/JS log panel from Phase 03 (03_web_ble/TestApp/index.html) and
//           the Swift TestApp from Phase 02 (09_xcode_apple/TestApp/).
//
// Usage:
//   Attach to a Canvas GameObject in the TestApp scene.
//   Wire public fields to the corresponding UI elements in the Inspector.
//   PolarTest.Run(ui) and MuseTest.Run(ui) use this class for all output.
//
// Scene setup (recommended):
//   Canvas
//   ├── PolarButton      (Button + TMP_Text "Run Polar H10 Test")
//   ├── MuseButton       (Button + TMP_Text "Run Muse 2 Test")
//   ├── StatusText       (TMP_Text — "Idle")
//   ├── LastValueText    (TMP_Text — "--")
//   └── LogPanel
//       └── ScrollRect
//           └── Viewport
//               └── Content
//                   └── LogText (TMP_Text — flexible height)
// ─────────────────────────────────────────────────────────────────────────────

using System;
using System.Text;
using UnityEngine;
using UnityEngine.UI;
using TMPro;

namespace BLEReference
{
    /// <summary>
    /// Main UI controller for the Phase 04 Quest integration TestApp.
    ///
    /// Provides helpers for structured log output, status display, real-time
    /// value readout, and button state management. Both PolarTest and MuseTest
    /// write to this controller for all visual feedback.
    /// </summary>
    public class BLETestUI : MonoBehaviour
    {
        // ── Inspector-wired UI references ─────────────────────────────────────

        [Header("Buttons")]
        [Tooltip("Triggers the Polar H10 11-step integration test.")]
        public Button polarButton;

        [Tooltip("Triggers the Muse 2 11-step EEG integration test.")]
        public Button museButton;

        [Header("Status")]
        [Tooltip("Shows test lifecycle state: Idle / Running... / PASS ✓ / FAIL ✗")]
        public TMP_Text statusText;

        [Tooltip("Shows the last received sensor value: e.g. 'HR: 72 bpm' or 'ECG: 142 µV'")]
        public TMP_Text lastValueText;

        [Header("Log")]
        [Tooltip("Full scrollable log content TMP_Text. Grows downward; parent ScrollRect must have vertical scrollbar.")]
        public TMP_Text logText;

        [Tooltip("The ScrollRect wrapping logText. Used to auto-scroll to the bottom after each log line.")]
        public ScrollRect logScrollRect;

        // ── Internal state ────────────────────────────────────────────────────

        // StringBuilder avoids repeated string concatenation on the log TMP_Text.
        // Flushed to logText.text on every Log() call.
        private readonly StringBuilder _logBuffer = new(4096);

        // Maximum log lines retained to prevent unbounded memory growth in long
        // sessions. Oldest lines are discarded when the limit is exceeded.
        private const int MaxLogLines = 500;
        private int _logLineCount;

        // ── Color tokens for status display ───────────────────────────────────

        // TMP rich-text color codes matching the status semantic:
        //   Running  → neutral white (default)
        //   PASS ✓   → green  (#4CAF50)
        //   FAIL ✗   → red    (#F44336)
        private const string ColorPass  = "#4CAF50";
        private const string ColorFail  = "#F44336";
        private const string ColorError = "#FF9800"; // orange — mid-severity log

        // ── Unity lifecycle ───────────────────────────────────────────────────

        private void Awake()
        {
            // Validate required references and surface config errors at scene load,
            // not silently at runtime when the test is already running.
            if (polarButton    == null) Debug.LogError("[BLETestUI] polarButton is not wired in the Inspector.");
            if (museButton     == null) Debug.LogError("[BLETestUI] museButton is not wired in the Inspector.");
            if (statusText     == null) Debug.LogError("[BLETestUI] statusText is not wired in the Inspector.");
            if (lastValueText  == null) Debug.LogError("[BLETestUI] lastValueText is not wired in the Inspector.");
            if (logText        == null) Debug.LogError("[BLETestUI] logText is not wired in the Inspector.");
            if (logScrollRect  == null) Debug.LogError("[BLETestUI] logScrollRect is not wired in the Inspector.");
        }

        private void Start()
        {
            SetStatus("Idle");
            SetLastValue("--");
            ClearLog();
            Log("[BLETestUI] TestApp ready. Tap a button to begin.");
        }

        // ── Public API ────────────────────────────────────────────────────────

        /// <summary>
        /// Append a timestamped line to the scrollable log panel.
        ///
        /// Automatically scrolls to the bottom after appending. When MaxLogLines
        /// is reached, the oldest half of the buffer is discarded so that TMP_Text
        /// mesh complexity stays bounded during long test runs.
        /// </summary>
        /// <param name="message">Log line text. May include TMP rich-text tags.</param>
        public void Log(string message)
        {
            // Timestamp format: HH:mm:ss.fff — matches Phase 03 console output style.
            string timestamp = DateTime.Now.ToString("HH:mm:ss.fff");
            string line = $"[{timestamp}] {message}";

            // Trim buffer when it exceeds the ceiling to prevent mesh overload.
            if (_logLineCount >= MaxLogLines)
            {
                // Discard the first half: find the newline at ~MaxLogLines/2 and slice.
                string full = _logBuffer.ToString();
                int splitIdx = 0;
                int target = MaxLogLines / 2;
                int found = 0;
                for (int i = 0; i < full.Length; i++)
                {
                    if (full[i] == '\n')
                    {
                        found++;
                        if (found >= target) { splitIdx = i + 1; break; }
                    }
                }
                _logBuffer.Clear();
                _logBuffer.Append(full, splitIdx, full.Length - splitIdx);
                _logLineCount = MaxLogLines - target;
            }

            _logBuffer.AppendLine(line);
            _logLineCount++;

            if (logText != null)
            {
                logText.text = _logBuffer.ToString();
            }

            // Scroll to the bottom on the next frame (content size fitter needs a
            // frame to recalculate layout before normalizedPosition reflects the bottom).
            if (logScrollRect != null)
            {
                Canvas.ForceUpdateCanvases();
                logScrollRect.verticalNormalizedPosition = 0f;
            }

            // Mirror to Unity console so Quest logcat and Editor console both capture it.
            Debug.Log($"[BLETestUI] {line}");
        }

        /// <summary>
        /// Clear all log lines from the panel and reset the internal buffer.
        /// </summary>
        public void ClearLog()
        {
            _logBuffer.Clear();
            _logLineCount = 0;
            if (logText != null) logText.text = string.Empty;
        }

        /// <summary>
        /// Update the status text. Pass isError=true to render in orange (warning)
        /// vs. the semantic green/red of SetStatus with explicit "PASS"/"FAIL" keywords.
        ///
        /// For test lifecycle states use the following conventions:
        ///   SetStatus("Idle")              → neutral
        ///   SetStatus("Running...")        → neutral
        ///   SetStatus("PASS ✓")            → green (auto-detected)
        ///   SetStatus("FAIL ✗")            → red   (auto-detected)
        ///   SetStatus("some error", true)  → orange (explicit)
        /// </summary>
        /// <param name="status">Status string to display.</param>
        /// <param name="isError">If true, renders in orange regardless of content.</param>
        public void SetStatus(string status, bool isError = false)
        {
            if (statusText == null) return;

            if (isError)
            {
                statusText.text = $"<color={ColorError}>{status}</color>";
            }
            else if (status.Contains("PASS"))
            {
                statusText.text = $"<color={ColorPass}>{status}</color>";
            }
            else if (status.Contains("FAIL"))
            {
                statusText.text = $"<color={ColorFail}>{status}</color>";
            }
            else
            {
                // Neutral: Idle, Running…, step labels
                statusText.text = status;
            }
        }

        /// <summary>
        /// Update the live sensor value readout (HR bpm, ECG µV, EEG µV).
        /// Called from PolarTest and MuseTest on each new data packet.
        /// </summary>
        /// <param name="value">Formatted value string, e.g. "HR: 72 bpm" or "ECG: 142 µV"</param>
        public void SetLastValue(string value)
        {
            if (lastValueText != null)
                lastValueText.text = value;
        }

        /// <summary>
        /// Enable or disable both test buttons. Called by test runners to prevent
        /// concurrent test execution — buttons are disabled for the duration of a run
        /// and re-enabled on completion (pass or fail).
        /// </summary>
        /// <param name="running">True while a test is in progress.</param>
        public void SetRunning(bool running)
        {
            bool interactive = !running;
            if (polarButton != null) polarButton.interactable = interactive;
            if (museButton  != null) museButton.interactable  = interactive;
        }
    }
}
