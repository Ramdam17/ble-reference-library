# BLE Reference Library

A multi-platform Bluetooth Low Energy reference library — standalone, heavily commented modules designed to be read, copied, and adapted.

## What This Is

A collection of **self-contained BLE implementation files** covering 18 features across 5 platforms. Each file is a standalone tutorial: no imports between modules, no framework magic. Copy what you need, adapt it, ship it.

**This is not a framework.** It's a knowledge base.

## Platforms

| Folder | Language | Library / Framework | Phase | Status |
|--------|----------|---------------------|-------|--------|
| `01_python_bleak/` | Python 3.12+ | Bleak 0.21+ | 01 | ✅ Complete |
| `09_xcode_apple/` | Swift 5.9+ | CoreBluetooth | 02 | ✅ Complete |
| `08_javascript_web/` | TypeScript / Browser | Web Bluetooth API | 03 | ✅ Complete |
| `02–05_unity_*/` | C# / Unity 2022.3 | IBLEPlugin + native | 04 | 🔜 Planned |
| `06_cpp/` | C++17 | SimpleBLE 0.7+ | 05 | 🔜 Planned |

## Features (per platform)

| # | Feature | Description |
|---|---------|-------------|
| 01 | Passive Scan | Discover nearby BLE peripherals |
| 02 | Filtered Scan | Scan by name, service UUID, or RSSI |
| 03 | Connection Lifecycle | Connect, disconnect, auto-reconnect |
| 04 | Services & Characteristics | GATT discovery and enumeration |
| 05 | Read & Write | Read/write characteristic values |
| 06 | Notifications & Indications | Subscribe to characteristic changes |
| 07 | Scan Without Connection | Parse advertisement data |
| 08 | Battery Level | Standard Battery Service (0x180F) |
| 09 | Device Information | Device Information Service (0x180A) — serial, model, firmware |
| 10 | Heart Rate + ECG | HRM (0x180D) + Polar PMD proprietary ECG stream |
| 11 | Muse EEG | Custom BLE service, uint:12 bit-unpacking, 4-channel EEG |
| 12 | Error Handling & Timeouts | Retry logic, timeout management |
| 13 | MTU Negotiation | Request and handle MTU changes |
| 14 | Bonding & Pairing | Secure pairing and key storage |
| 15 | Peripheral Mode | Advertise as a BLE peripheral |
| 16 | RSSI & Distance | Signal strength and distance estimation |
| 17 | Command Queue | Serial GATT operation scheduling |
| 18 | OTA / DFU | Over-the-air firmware updates |

## Architecture

- **Python is the canonical reference.** All other platforms port from `01_python_bleak/`.
- **Every file is standalone.** No cross-module imports. Copy one file, it works.
- **Muse EEG parser is cross-validated.** Identical µV output from identical bytes across all 5 platforms.

## Project Structure

```
├── 00_shared/              # BLE concepts, GATT reference, test vectors
├── 01_python_bleak/        # Phase 01 — Python / Bleak
├── 02–05_unity_*/          # Phase 04 — Unity (Editor, Android/Quest, Mac, iOS)
├── 06_cpp/                 # Phase 05 — C++ / SimpleBLE
├── 08_javascript_web/      # Phase 03 — TypeScript / Web Bluetooth API
├── 09_xcode_apple/         # Phase 02 — Swift / CoreBluetooth (iOS, macOS, iPadOS)
├── docs/                   # Specifications, sprint plans, reference docs
└── tests/                  # Test suites (mirrors module structure)
```

## Getting Started

### Phase 01 — Python

```bash
uv sync --extra dev
uv run pytest tests/ -v
uv run python 01_python_bleak/01_scan.py
```

### Phase 02 — Swift

```bash
cd 09_xcode_apple
swift test
# TestApp: open TestApp/TestApp.xcodeproj in Xcode → run on iPhone/Mac
```

### Phase 03 — Web Bluetooth (TypeScript)

```bash
cd 08_javascript_web
npm install
npm test              # 50 tests
npm run build         # → dist/
npx serve .           # Serve TestApp
# Open http://localhost:3000/TestApp/ in Chrome
```

## Reference Documentation

| Phase | Reference | Problems & Solutions |
|-------|-----------|---------------------|
| 01 Python / Bleak | [phase01-reference.md](docs/phase01-reference.md) | [phase01-problems-and-solutions.md](docs/phase01-problems-and-solutions.md) |
| 02 Swift / CoreBluetooth | [phase02-reference.md](docs/phase02-reference.md) | [phase02-problems-and-solutions.md](docs/phase02-problems-and-solutions.md) |
| 03 Web Bluetooth (TS) | [phase03-reference.md](docs/phase03-reference.md) | [phase03-problems-and-solutions.md](docs/phase03-problems-and-solutions.md) |

## Development

See [docs/Specifications/](docs/Specifications/) for:
- **CONTEXT.md** — project vision and architectural decisions
- **CODING_STANDARDS.md** — naming conventions, file structure, test rules
- **COMMIT_AND_REVIEW.md** — Git Flow, commit format, PR checklist

Sprint progress is tracked in [docs/Sprints/SPRINT_TRACKER.yaml](docs/Sprints/SPRINT_TRACKER.yaml).

## License

TBD
