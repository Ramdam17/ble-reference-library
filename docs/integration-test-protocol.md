# BLE Reference Library — Integration Test Protocol

This document defines the canonical real-device integration test protocol
applied to every phase of the BLE Reference Library.

## Required Devices

- **Polar H10** — chest strap heart rate monitor with ECG capability
- **Muse 2** (or Muse S) — consumer EEG headband, 4 channels

## Polar H10 Protocol

| Parameter | Value |
|-----------|-------|
| Streaming duration | 30 seconds |
| BLE services used | HRM (0x180D) + PMD ECG (proprietary) |
| Expected sample rate | ~130 Hz ECG |
| Minimum samples for pass | 3000 |
| Signal quality threshold | std dev > 10 µV |
| Output artifacts | `ecg_plot_<timestamp>.png` + log file |
| Log must contain | frames, samples, duration, min/max/mean µV, std dev |

## Muse 2 Protocol

| Parameter | Value |
|-----------|-------|
| Streaming duration | 15 seconds |
| BLE services used | Muse GATT (proprietary), 4 EEG channels |
| Expected sample rate | ~256 Hz × 4 channels |
| Minimum samples for pass | 3000 per channel |
| Signal quality threshold | std dev > 5 µV per channel |
| Output artifacts | `eeg_plot_<timestamp>.png` + log file |
| Log must contain | packets, samples/ch, duration, amplitude range, std dev |

## Pass/Fail Criteria

1. Connection succeeds
2. Service discovery finds expected services
3. Sample count above minimum threshold
4. PNG generated with valid signal (std dev above threshold)
5. Clean disconnect (no crash)

## Phase Mapping

Sprint numbers reference `SPRINT_TRACKER.yaml` IDs.

| Phase | Platform | Polar Sprint | Muse Sprint | Plot Technology |
|-------|----------|-------------|-------------|-----------------|
| 01 | Python / Bleak | Sprint 10 | Sprint 11 | matplotlib |
| 02 | Swift / CoreBluetooth | Sprint 28 | Sprint 29 | SwiftUI Charts |
| 03 | Web Bluetooth (TypeScript) | Sprint 46 | Sprint 47 | Chart.js canvas PNG |
| 04 | Unity / C# | Sprint 64 | Sprint 65 | Unity UI |
| 05 | C++ / SimpleBLE | Sprint 82 | Sprint 83 | matplotlibcpp |

## Gate Rule

No sprint beyond a phase's Muse sprint may be marked "done" in the
sprint tracker until both integration tests (Polar + Muse) have produced
their artifacts (PNG + log file) on real hardware.

## Artifacts Location

All integration test artifacts are stored in `logs/` at the repo root:
- `logs/ecg_plot_<timestamp>.png`
- `logs/eeg_plot_<timestamp>.png`
- `logs/polar_integration_<timestamp>.txt` (or `.log`)
- `logs/muse_integration_<timestamp>.txt` (or `.log`)
