// ─────────────────────────────────────────────────────────────────────────────
// BLE Reference Library — Web Bluetooth TestApp
// Runner : Muse 2 EEG Integration Test
//
// 11-step flow:
//   1.  Scan for device with name prefix "Muse"
//   2.  Connect
//   3.  Discover services
//   4.  Read device info (if available)
//   5.  Read battery level (if available)
//   6.  Handshake — write MUSE_STREAM_COMMAND to Muse control characteristic
//   7.  Subscribe EEG channels (TP9, AF7, AF8, TP10) via BLENotificationSubscriber
//       + MuseEegParser.parse(dv, channelName)
//   8.  Stream 15 seconds — accumulate EEG packets per channel
//   9.  Stop stream, unsubscribe, disconnect
//   10. Plot 4-channel EEG on #muse-chart canvas via Chart.js (4 lines, offset)
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
  MuseEegParser,
  MUSE_SERVICE_UUID,
  MUSE_CONTROL_UUID,
  MUSE_EEG_CHANNEL_UUIDS,
  MUSE_CHANNEL_NAMES,
  MUSE_STREAM_COMMAND,
  MUSE_STOP_COMMAND,
} from "../dist/index.js";

// ── Logging helper ────────────────────────────────────────────────────────────

/**
 * Append a timestamped line to the #muse-log div.
 * @param {string} msg - Message to log.
 */
function log(msg) {
  const el = document.getElementById("muse-log");
  const ts = new Date().toISOString().slice(11, 23); // HH:MM:SS.mmm
  el.textContent += `[${ts}] ${msg}\n`;
  el.scrollTop = el.scrollHeight;
}

// ── Chart helper ──────────────────────────────────────────────────────────────

/**
 * Render 4-channel EEG data on the #muse-chart canvas.
 * Each channel is vertically offset so traces don't overlap.
 * Values are clamped to ±200 µV per channel before offsetting.
 *
 * @param {Object.<string, number[]>} channelData - Map of channel name to µV samples.
 * @param {number} sampleRate - Sampling rate in Hz (256 for Muse 2).
 */
function renderEegChart(channelData, sampleRate) {
  const CLAMP_UV = 200;
  const CHANNEL_OFFSET_UV = 600; // vertical separation between channels
  const COLORS = ["#e63946", "#2a9d8f", "#e9c46a", "#457b9d"];

  // Find the shortest channel length to align all channels
  const lengths = MUSE_CHANNEL_NAMES.map((ch) => (channelData[ch] || []).length);
  const minLen = Math.min(...lengths.filter((l) => l > 0));

  // Downsample to at most 4000 points per channel for chart performance
  const MAX_POINTS = 4000;
  const step = minLen > MAX_POINTS ? Math.ceil(minLen / MAX_POINTS) : 1;

  const labels = Array.from(
    { length: Math.ceil(minLen / step) },
    (_, i) => ((i * step) / sampleRate).toFixed(2)
  );

  const datasets = MUSE_CHANNEL_NAMES.map((ch, idx) => {
    const raw = (channelData[ch] || []).slice(0, minLen);
    const offset = (MUSE_CHANNEL_NAMES.length - 1 - idx) * CHANNEL_OFFSET_UV;
    const plotData = raw
      .filter((_, i) => i % step === 0)
      .map((v) => Math.max(-CLAMP_UV, Math.min(CLAMP_UV, v)) + offset);

    return {
      label: ch,
      data: plotData,
      borderColor: COLORS[idx],
      borderWidth: 1,
      pointRadius: 0,
      tension: 0,
    };
  });

  const canvas = document.getElementById("muse-chart");

  // Destroy any existing Chart instance on this canvas
  const existingChart = Chart.getChart(canvas);
  if (existingChart) existingChart.destroy();

  new Chart(canvas, {
    type: "line",
    data: { labels, datasets },
    options: {
      animation: false,
      responsive: true,
      plugins: {
        legend: { display: true },
        title: {
          display: true,
          text: "Muse 2 — EEG Signal (4 channels, vertically offset)",
        },
      },
      scales: {
        x: {
          title: { display: true, text: "Time (s)" },
          ticks: { maxTicksLimit: 10 },
        },
        y: {
          title: { display: true, text: "Amplitude (µV + offset)" },
        },
      },
    },
  });
}

// ── Main integration test ─────────────────────────────────────────────────────

/**
 * Run the full 11-step Muse 2 EEG integration test.
 * Assigned to window so the inline onclick in index.html can call it.
 */
window.runMuse = async function () {
  document.getElementById("muse-log").textContent = "";
  log("=== Muse 2 EEG Integration Test ===");

  const STREAM_DURATION_MS = 15_000;
  const SAMPLE_RATE = 256; // Hz — Muse 2

  let server = null;

  // Accumulate samples per channel: { TP9: [], AF7: [], AF8: [], TP10: [] }
  const channelData = Object.fromEntries(MUSE_CHANNEL_NAMES.map((ch) => [ch, []]));
  const packetCounts = Object.fromEntries(MUSE_CHANNEL_NAMES.map((ch) => [ch, 0]));

  // Track subscribed characteristics so we can clean up
  const subscribedChars = [];
  const subscriber = new BLENotificationSubscriber();

  try {
    // ── Step 1: Scan ───────────────────────────────────────────────────────
    log("Step 1: Scanning for Muse 2...");
    const filter = ScanFilter.byNamePrefix("Muse").withOptionalServices(MUSE_SERVICE_UUID);
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

    // ── Step 6: Handshake — send STREAM_COMMAND to Muse control characteristic
    log("Step 6: Sending STREAM_COMMAND to Muse control characteristic...");
    const museService = await server.getPrimaryService(MUSE_SERVICE_UUID);
    const controlChar = await museService.getCharacteristic(MUSE_CONTROL_UUID);
    await controlChar.writeValueWithoutResponse(MUSE_STREAM_COMMAND.buffer);
    log("  Stream command sent.");

    // ── Step 7: Subscribe EEG channels ────────────────────────────────────
    log("Step 7: Subscribing to EEG channels (TP9, AF7, AF8, TP10)...");
    for (const chName of MUSE_CHANNEL_NAMES) {
      const uuid = MUSE_EEG_CHANNEL_UUIDS[chName];
      const char = await museService.getCharacteristic(uuid);
      await subscriber.subscribe(char, (dv) => {
        const packet = MuseEegParser.parse(dv, chName);
        if (packet) {
          channelData[chName].push(...packet.samplesUv);
          packetCounts[chName]++;
        }
      });
      subscribedChars.push(char);
      log(`  Subscribed: ${chName} (${uuid})`);
    }

    // ── Step 8: Stream 15 seconds ──────────────────────────────────────────
    log(`Step 8: Streaming for ${STREAM_DURATION_MS / 1000}s...`);
    await new Promise((resolve) => setTimeout(resolve, STREAM_DURATION_MS));

    const totalSamples = MUSE_CHANNEL_NAMES.reduce(
      (acc, ch) => acc + channelData[ch].length,
      0
    );
    log(`  Accumulated ${totalSamples} total samples across 4 channels.`);
    for (const ch of MUSE_CHANNEL_NAMES) {
      log(`    ${ch}: ${channelData[ch].length} samples (${packetCounts[ch]} packets)`);
    }

    // ── Step 9: Stop stream, unsubscribe, disconnect ───────────────────────
    log("Step 9: Stopping stream and disconnecting...");
    try {
      await controlChar.writeValueWithoutResponse(MUSE_STOP_COMMAND.buffer);
    } catch (e) {
      log(`  STOP command error (non-fatal): ${e.message}`);
    }
    await subscriber.unsubscribeAll(subscribedChars);
    server.disconnect();
    log("  Disconnected.");

    // ── Step 10: Plot EEG ──────────────────────────────────────────────────
    log("Step 10: Rendering EEG chart...");
    renderEegChart(channelData, SAMPLE_RATE);
    log("  Chart rendered.");

    // ── Step 11: Summary ───────────────────────────────────────────────────
    log("=== Summary ===");
    for (const ch of MUSE_CHANNEL_NAMES) {
      const samples = channelData[ch].length;
      const duration = (samples / SAMPLE_RATE).toFixed(1);
      log(`  ${ch}: ${samples} samples — ${duration}s @ ${SAMPLE_RATE} Hz`);
    }
    log("=== PASS ===");
  } catch (err) {
    log(`ERROR: ${err.message ?? err}`);
    console.error("[muse-runner]", err);
    if (subscribedChars.length > 0) {
      try { await subscriber.unsubscribeAll(subscribedChars); } catch (_) { /* ignore */ }
    }
    if (server && server.connected) {
      server.disconnect();
    }
  }
};
