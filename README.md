# BLE Reference Library

A multi-platform Bluetooth Low Energy reference library — standalone, heavily commented modules designed to be read, copied, and adapted.

## What This Is

A collection of **self-contained BLE implementation files** covering 17 features across 5 platforms. Each file is a standalone tutorial: no imports between modules, no framework magic. Copy what you need, adapt it, ship it.

**This is not a framework.** It's a knowledge base.

## Platforms

| Folder | Language | Library / Framework | Phase |
|--------|----------|---------------------|-------|
| `01_python_bleak/` | Python 3.14+ | Bleak 0.21+ | 01 |
| `09_xcode_apple/` | Swift 5.9+ | CoreBluetooth | 02 |
| `07_javascript_node/` | Node.js 18+ | @abandonware/noble | 03 |
| `08_javascript_web/` | Browser JS | Web Bluetooth API | 03 |
| `02–05_unity_*/` | C# / Unity 2022.3 | IBLEPlugin + native | 04 |
| `06_cpp/` | C++17 | SimpleBLE 0.7+ | 05 |

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
| 09 | Heart Rate Monitor | Standard Heart Rate Service (0x180D) |
| 10 | Muse EEG Example | Custom BLE service, uint:12 bit-unpacking |
| 11 | Error Handling & Timeouts | Retry logic, timeout management |
| 12 | MTU Negotiation | Request and handle MTU changes |
| 13 | Bonding & Pairing | Secure pairing and key storage |
| 14 | Peripheral Mode | Advertise as a BLE peripheral |
| 15 | RSSI & Distance | Signal strength and distance estimation |
| 16 | Command Queue | Serial GATT operation scheduling |
| 17 | OTA / DFU | Over-the-air firmware updates |

## Architecture

- **Python is the canonical reference.** All other platforms port from `01_python_bleak/`.
- **Every file is standalone.** No cross-module imports. Copy one file, it works.
- **Muse EEG parser is cross-validated.** Identical µV output from identical bytes across all 5 platforms.

## Project Structure

```
├── 00_shared/              # BLE concepts, GATT reference, test vectors
├── 01_python_bleak/        # Python reference implementation
├── 02–05_unity_*/          # Unity (Editor, Android/Quest, Mac, iOS)
├── 06_cpp/                 # C++ / SimpleBLE
├── 07_javascript_node/     # Node.js / noble
├── 08_javascript_web/      # Web Bluetooth API
├── 09_xcode_apple/         # Swift / CoreBluetooth (iOS, macOS, iPadOS)
├── docs/                   # Specifications and sprint plans
└── tests/                  # Test suites (mirrors module structure)
```

## Getting Started (Python)

```bash
# Clone and install
git clone <repo-url>
cd ble-reference-library
pip install -e ".[dev]"

# Run tests
pytest tests/ -v

# Try a scan
python 01_python_bleak/01_passive_scan.py
```

## Development

See [docs/Specifications/](docs/Specifications/) for:
- **CONTEXT.md** — project vision and architectural decisions
- **CODING_STANDARDS.md** — naming conventions, file structure, test rules
- **COMMIT_AND_REVIEW.md** — Git Flow, commit format, PR checklist

Sprint progress is tracked in [docs/Sprints/SPRINT_TRACKER.yaml](docs/Sprints/SPRINT_TRACKER.yaml).

## License

TBD
