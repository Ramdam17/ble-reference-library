// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth TestApp
// Runner : Polar H10 ECG Integration Test
//
// 11-step flow:
//   1.  Scan for "Polar H10"
//   2.  Connect
//   3.  Discover services
//   4.  Read device info
//   5.  Read battery level
//   6.  Subscribe HRM (0x2A37)
//   7.  Start PMD ECG stream (write START command to PMD control characteristic)
//   8.  Stream 30 seconds — accumulate ECG samples from PMD data characteristic
//   9.  Stop + disconnect
//   10. Plot ECG on #polar-chart canvas via Chart.js
//   11. Log summary
//
// NOTE: Functions are assigned to `window` so inline onclick handlers in
//       index.html can reach them from global scope (required for ES modules).
// ─────────────────────────────────────────────────────────────────────────────

import {
  BLEScanner,
  ScanFilter,
  BLEConnector,
  BLEServiceDiscoverer,
  BLENotificationSubscriber,
  BLEBattery,
  BLEDeviceInfo,
  HeartRateParser,
  PolarEcgParser,
  POLAR_PMD_SERVICE_UUID,
  POLAR_PMD_CONTROL_UUID,
  POLAR_PMD_DATA_UUID,
  POLAR_ECG_START_COMMAND,
  POLAR_ECG_STOP_COMMAND,
  HEART_RATE_SERVICE_UUID,
} from "../dist/index.js";

// ── Logging helper ────────────────────────────────────────────────────────────

/**
 * Append a timestamped line to the #polar-log div.
 * @param {string} msg - Message to log.
 */
function log(msg) {
  const el = document.getElementById("polar-log");
  const ts = new Date().toISOString().slice(11, 23); // HH:MM:SS.mmm
  el.textContent += `[${ts}] ${msg}\n`;
  el.scrollTop = el.scrollHeight;
}

// ── Chart helper ──────────────────────────────────────────────────────────────

/**
 * Render accumulated ECG samples on the #polar-chart canvas.
 * Clamps values to ±1500 µV and skips the first 2 seconds of data
 * to avoid baseline artefacts.
 *
 * @param {number[]} samples - Raw ECG samples in µV.
 * @param {number} sampleRate - Sampling rate in Hz (130 for Polar H10).
 */
function renderEcgChart(samples, sampleRate) {
  const SKIP_SECONDS = 2;
  const CLAMP_UV = 1500;
  const skipCount = SKIP_SECONDS * sampleRate;

  const visible = samples.slice(skipCount).map((v) =>
    Math.max(-CLAMP_UV, Math.min(CLAMP_UV, v))
  );

  // Downsample to at most 4000 points for chart performance
  const MAX_POINTS = 4000;
  let plotData = visible;
  if (visible.length > MAX_POINTS) {
    const step = Math.ceil(visible.length / MAX_POINTS);
    plotData = visible.filter((_, i) => i % step === 0);
  }

  const labels = plotData.map((_, i) =>
    ((i / sampleRate) + SKIP_SECONDS).toFixed(2)
  );

  const canvas = document.getElementById("polar-chart");

  // Destroy any existing Chart instance on this canvas
  const existingChart = Chart.getChart(canvas);
  if (existingChart) existingChart.destroy();

  new Chart(canvas, {
    type: "line",
    data: {
      labels,
      datasets: [
        {
          label: "ECG (µV)",
          data: plotData,
          borderColor: "#e63946",
          borderWidth: 1,
          pointRadius: 0,
          tension: 0,
        },
      ],
    },
    options: {
      animation: false,
      responsive: true,
      plugins: {
        legend: { display: true },
        title: {
          display: true,
          text: "Polar H10 — ECG Signal",
        },
      },
      scales: {
        x: {
          title: { display: true, text: "Time (s)" },
          ticks: { maxTicksLimit: 10 },
        },
        y: {
          title: { display: true, text: "Amplitude (µV)" },
          min: -CLAMP_UV,
          max: CLAMP_UV,
        },
      },
    },
  });
}

// ── Main integration test ─────────────────────────────────────────────────────

/**
 * Run the full 11-step Polar H10 ECG integration test.
 * Assigned to window so the inline onclick in index.html can call it.
 */
window.runPolar = async function () {
  document.getElementById("polar-log").textContent = "";
  log("=== Polar H10 ECG Integration Test ===");

  const STREAM_DURATION_MS = 30_000;
  const SAMPLE_RATE = 130; // Hz — Polar H10 ECG

  let server = null;
  const allSamplesUv = [];
  let hrmCount = 0;

  try {
    // ── Step 1: Scan ───────────────────────────────────────────────────────
    log("Step 1: Scanning for Polar H10...");
    const filter = ScanFilter.byNamePrefix("Polar").withOptionalServices(
      HEART_RATE_SERVICE_UUID,
      "battery_service",
      "device_information",
      POLAR_PMD_SERVICE_UUID,
      POLAR_PMD_DATA_UUID,
    );
    const scanner = new BLEScanner();
    const scanResult = await scanner.requestDevice(filter.toRequestDeviceOptions());
    log(`  Found device: ${scanResult.device.name} (id: ${scanResult.device.id})`);

    // ── Step 2: Connect ────────────────────────────────────────────────────
    log("Step 2: Connecting...");
    const connector = new BLEConnector();
    server = await connector.connect(scanResult.device);
    log("  Connected.");

    // ── Step 3: Discover services ──────────────────────────────────────────
    log("Step 3: Discovering services...");
    const discoverer = new BLEServiceDiscoverer();
    const services = await discoverer.discoverAll(server);
    log(`  Discovered ${services.length} service(s): ${services.map((s) => s.uuid).join(", ")}`);

    // ── Step 4: Read device info ───────────────────────────────────────────
    log("Step 4: Reading device info...");
    try {
      const info = await BLEDeviceInfo.read(server);
      log(`  Manufacturer: ${info.manufacturerName ?? "N/A"}`);
      log(`  Model:        ${info.modelNumber ?? "N/A"}`);
      log(`  Firmware:     ${info.firmwareRevision ?? "N/A"}`);
    } catch (e) {
      log(`  Device info unavailable: ${e.message}`);
    }

    // ── Step 5: Read battery ───────────────────────────────────────────────
    log("Step 5: Reading battery level...");
    try {
      const battery = await BLEBattery.readLevel(server);
      log(`  Battery: ${battery}%`);
    } catch (e) {
      log(`  Battery unavailable: ${e.message}`);
    }

    // ── Step 6: Subscribe HRM ──────────────────────────────────────────────
    log("Step 6: Subscribing to HRM (0x2A37)...");
    const hrmService = await server.getPrimaryService(HEART_RATE_SERVICE_UUID);
    const hrmChar = await hrmService.getCharacteristic("heart_rate_measurement");
    const subscriber = new BLENotificationSubscriber();
    await subscriber.subscribe(hrmChar, (dv) => {
      const hrm = HeartRateParser.parse(dv);
      if (hrm) {
        hrmCount++;
        if (hrmCount <= 3 || hrmCount % 5 === 0) {
          log(`  HRM #${hrmCount}: ${hrm.bpm} bpm`);
        }
      }
    });
    log("  HRM subscription active.");

    // ── Step 7: Start PMD ECG stream ───────────────────────────────────────
    log("Step 7: Starting PMD ECG stream...");
    const pmdService = await server.getPrimaryService(POLAR_PMD_SERVICE_UUID);
    const pmdControl = await pmdService.getCharacteristic(POLAR_PMD_CONTROL_UUID);
    const pmdData = await pmdService.getCharacteristic(POLAR_PMD_DATA_UUID);

    // Subscribe to PMD data notifications before sending START to avoid
    // missing early packets.
    await subscriber.subscribe(pmdData, (dv) => {
      const frame = PolarEcgParser.parse(dv);
      if (frame) {
        allSamplesUv.push(...frame.samplesUv);
      }
    });

    // Write START ECG command to PMD control characteristic
    await pmdControl.writeValueWithResponse(POLAR_ECG_START_COMMAND.buffer);
    log("  PMD ECG stream started.");

    // ── Step 8: Stream 30 seconds ──────────────────────────────────────────
    log(`Step 8: Streaming for ${STREAM_DURATION_MS / 1000}s...`);
    await new Promise((resolve) => setTimeout(resolve, STREAM_DURATION_MS));
    log(`  Accumulated ${allSamplesUv.length} ECG samples.`);

    // ── Step 9: Stop + disconnect ──────────────────────────────────────────
    log("Step 9: Stopping stream and disconnecting...");
    try {
      await pmdControl.writeValueWithResponse(POLAR_ECG_STOP_COMMAND.buffer);
    } catch (e) {
      log(`  STOP command error (non-fatal): ${e.message}`);
    }
    await subscriber.unsubscribeAll([hrmChar, pmdData]);
    server.disconnect();
    log("  Disconnected.");

    // ── Step 10: Plot ECG ──────────────────────────────────────────────────
    log("Step 10: Rendering ECG chart...");
    renderEcgChart(allSamplesUv, SAMPLE_RATE);
    log("  Chart rendered.");

    // ── Step 11: Summary ───────────────────────────────────────────────────
    const durationS = (allSamplesUv.length / SAMPLE_RATE).toFixed(1);
    log("=== Summary ===");
    log(`  HRM packets received : ${hrmCount}`);
    log(`  ECG samples received : ${allSamplesUv.length}`);
    log(`  Effective duration   : ${durationS}s @ ${SAMPLE_RATE} Hz`);
    log("=== PASS ===");
  } catch (err) {
    log(`ERROR: ${err.message ?? err}`);
    console.error("[polar-runner]", err);
    if (server && server.connected) {
      server.disconnect();
    }
  }
};
