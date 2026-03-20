# BLE Reference Library — Project Context

## Purpose

This repository is a **reference library for AI agents and developers** implementing Bluetooth Low Energy (BLE) across multiple languages and platforms. Every module is standalone, heavily commented, and written to be read and reused directly — not as a framework, but as a copy-paste-and-adapt knowledge base.

When implementing BLE in any supported language, start here.

## Repository Structure

```
ble-library/
├── CONTEXT.md                  ← this file
├── SPRINT_TRACKER.yaml         ← machine-readable sprint status
├── docs/
│   ├── CODING_STANDARDS.md     ← naming, structure, tests, docs rules
│   ├── COMMIT_CONVENTIONS.md   ← Git Flow rules and commit format
│   └── CODE_REVIEW_CHECKLIST.md
├── 00_shared/                  ← BLE concepts, GATT reference, UUID utils
├── 01_python_bleak/            ← Phase 01 — Python reference implementation
├── 02_unity_editor/            ← Phase 04 — Unity Editor (C#)
├── 03_unity_android_quest/     ← Phase 04 — Unity Android / Meta Quest
├── 04_unity_mac/               ← Phase 04 — Unity macOS
├── 05_unity_ios/               ← Phase 04 — Unity iOS
├── 06_cpp/                     ← Phase 05 — C++ / SimpleBLE
├── 07_javascript_node/         ← Phase 03 — Node.js / noble
├── 08_javascript_web/          ← Phase 03 — Web Bluetooth API
└── 09_xcode_apple/             ← Phase 02 — Swift / CoreBluetooth
    ├── Shared/                 ← Common Swift code (iOS + macOS + iPadOS)
    ├── iOS/
    ├── macOS/
    └── iPadOS/
```

## Covered Features (all platforms)

01 Passive scan · 02 Filtered scan · 03 Connection lifecycle · 04 Services & characteristics · 05 Read & write · 06 Notifications & indications · 07 Scan without connection · 08 Battery level · 09 Heart Rate Monitor example · 10 Muse S EEG example · 11 Error handling & timeouts · 12 MTU negotiation · 13 Bonding & pairing · 14 Peripheral mode · 15 RSSI & distance · 16 Command queue · 17 OTA / DFU

## Platform Stack Reference

| Folder | Language | Library / Framework |
|--------|----------|---------------------|
| `01_python_bleak` | Python 3.10+ | Bleak 0.21+, bitstring, numpy |
| `09_xcode_apple/Shared` | Swift 5.9+ | CoreBluetooth (native) |
| `07_javascript_node` | JS (Node 18+) | @abandonware/noble, bleno |
| `08_javascript_web` | JS (Browser) | Web Bluetooth API |
| `02–05_unity_*` | C# / Unity 2022.3 LTS | IBLEPlugin + native plugins |
| `06_cpp` | C++17 | SimpleBLE 0.7+ |

## Key Architectural Decisions

- **Python is the canonical reference.** All other platforms port from `01_python_bleak/`. When in doubt about expected behavior, read Python first.
- **Muse EEG parser is cross-validated.** The uint:12 bit-unpacking logic in `10_muse_example.*` must produce identical µV values across all platforms for the same input bytes.
- **Unity uses a two-layer architecture.** `IBLEPlugin` (C#) is the common interface; native plugins (`.aar`, `.bundle`, `.framework`) implement it per platform.
- **Web Bluetooth limitations are documented, not hidden.** Stubs in `08_javascript_web/` explain what is unsupported and why.
- **Standalone files only.** No file imports from another module in this library. Every file runs independently.

## Development Phases

| Phase | Folder(s) | Sprints | Status field in YAML |
|-------|-----------|---------|----------------------|
| 01 | `01_python_bleak` | 01–17 | `phase_01` |
| 02 | `09_xcode_apple` | 18–34 | `phase_02` |
| 03 | `07_js_node` + `08_js_web` | 35–51 | `phase_03` |
| 04 | `02–05_unity_*` | 52–85 | `phase_04` |
| 05 | `06_cpp` | 86–102 | `phase_05` |

## Agent Instructions

- Always read the relevant sprint entry in `SPRINT_TRACKER.yaml` before starting work.
- Always read `docs/CODING_STANDARDS.md` before writing any code.
- The sprint entry defines: objective, deliverables, acceptance criteria, and git branch name.
- Do not create dependencies between modules. Each file must be self-contained.
- Cross-validate Muse parsers against `01_python_bleak/10_muse_example.py` using the byte sequences in `00_shared/muse_test_vectors.json`.
