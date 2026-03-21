# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 11 — Muse EEG Integration Example
# Platform: Python / Bleak
# Sprint  : Sprint 11
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : End-to-end integration test exercising ALL Sprints 01–09
#           against a real Muse 2 EEG headband. Includes EEG packet
#           parser and 4-channel EEG plot.
#
# Usage   : python 01_python_bleak/11_muse_example.py [device_address]
#           or: from 11_muse_example import parse_eeg_packet
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Muse 2 EEG integration example — validates Sprints 01–09.

This module serves two purposes:

1. **Parser library**: ``parse_eeg_packet`` decodes the proprietary Muse
   EEG packet format (12-bit packed samples) and can be imported standalone.

2. **Integration harness**: ``run_muse_example`` exercises every BLE feature
   from Sprints 01–09 against a real Muse 2 headband, producing a step-by-step
   report. This is the "smoke test" for EEG-class BLE devices.

Integration Steps
-----------------
.. code-block:: text

    Step 01 — Passive Scan          (Sprint 01)
    Step 02 — Filtered Scan by UUID (Sprint 02)
    Step 03 — Advertisement Data    (Sprint 07)
    Step 04 — Connect with Retry    (Sprint 03)
    Step 05 — Service Discovery     (Sprint 04)
    Step 06 — Device Information    (Sprint 09)
    Step 07 — Battery Level         (Sprint 08)
    Step 08 — Read Characteristic   (Sprint 05)
    Step 09 — Telemetry             (Sprint 06 + parser)
    Step 10 — EEG Stream            (Muse proprietary + plot)
    Step 11 — Clean Disconnect      (Sprint 03)

Muse EEG Packet Format
----------------------
Each BLE notification per channel contains 20 bytes:

.. code-block:: text

    Bytes 0-1: Packet counter (UINT16 big-endian)
    Bytes 2-19: 12 × 12-bit unsigned samples (packed, no padding)

    12-bit packing (each 3-byte group → 2 samples):
        sample_a = (byte0 << 4) | (byte1 >> 4)
        sample_b = ((byte1 & 0x0F) << 8) | byte2

    µV conversion: µV = 0.48828125 × (raw - 2048)

The 4 EEG channels (TP9, AF7, AF8, TP10) each have a dedicated GATT
characteristic. Notifications arrive in cycle; AF7 fires last in each batch.

Reference
---------
Muse BLE protocol: reverse-engineered, documented in BleMuse project.
Scale factor derivation: 12-bit ADC, 0–3.3V range, gain = 1.0.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime

from bleak import BleakClient, BleakScanner

logger = logging.getLogger(__name__)

# ─── Muse BLE Constants ─────────────────────────────

MUSE_SERVICE_UUID: str = "0000fe8d-0000-1000-8000-00805f9b34fb"
"""Muse custom GATT service UUID."""

MUSE_CONTROL_UUID: str = "273e0001-4c4d-454d-96be-f03bac821358"
"""Muse stream toggle / control characteristic."""

MUSE_EEG_TP9_UUID: str = "273e0003-4c4d-454d-96be-f03bac821358"
"""EEG channel TP9 (left ear) characteristic UUID."""

MUSE_EEG_AF7_UUID: str = "273e0004-4c4d-454d-96be-f03bac821358"
"""EEG channel AF7 (left forehead) characteristic UUID."""

MUSE_EEG_AF8_UUID: str = "273e0005-4c4d-454d-96be-f03bac821358"
"""EEG channel AF8 (right forehead) characteristic UUID."""

MUSE_EEG_TP10_UUID: str = "273e0006-4c4d-454d-96be-f03bac821358"
"""EEG channel TP10 (right ear) characteristic UUID."""

MUSE_TELEMETRY_UUID: str = "273e000b-4c4d-454d-96be-f03bac821358"
"""Muse telemetry characteristic (battery, temperature)."""

MUSE_CMD_ASK: bytes = b"\x02\x73\x0a"
"""Command to query device state."""

MUSE_CMD_STREAM: bytes = b"\x02\x64\x0a"
"""Command to start EEG streaming."""

MUSE_CMD_STOP: bytes = b"\x02\x68\x0a"
"""Command to stop EEG streaming."""

MUSE_SAMPLE_RATE: int = 256
"""Muse 2 EEG sample rate in Hz."""

MUSE_SCALE_FACTOR: float = 0.48828125
"""Conversion factor from raw 12-bit ADC to microvolts."""

MUSE_SAMPLES_PER_PACKET: int = 12
"""Number of EEG samples per BLE notification."""

MUSE_CHANNEL_NAMES: list[str] = ["TP9", "AF7", "AF8", "TP10"]
"""EEG channel names in standard order."""

MUSE_CHANNEL_UUIDS: list[str] = [
    MUSE_EEG_TP9_UUID,
    MUSE_EEG_AF7_UUID,
    MUSE_EEG_AF8_UUID,
    MUSE_EEG_TP10_UUID,
]
"""EEG channel characteristic UUIDs in order matching MUSE_CHANNEL_NAMES."""

MUSE_CHANNEL_UUID_TO_NAME: dict[str, str] = dict(
    zip(MUSE_CHANNEL_UUIDS, MUSE_CHANNEL_NAMES)
)
"""Mapping from characteristic UUID to channel name."""


# ─── Standard GATT constants (for integration harness) ─

BATTERY_SERVICE_UUID: str = "0000180f-0000-1000-8000-00805f9b34fb"
BATTERY_CHAR_UUID: str = "00002a19-0000-1000-8000-00805f9b34fb"
DIS_SERVICE_UUID: str = "0000180a-0000-1000-8000-00805f9b34fb"
DIS_CHARACTERISTICS: dict[str, str] = {
    "00002a29-0000-1000-8000-00805f9b34fb": "Manufacturer",
    "00002a24-0000-1000-8000-00805f9b34fb": "Model",
    "00002a25-0000-1000-8000-00805f9b34fb": "Serial",
    "00002a26-0000-1000-8000-00805f9b34fb": "Firmware",
    "00002a27-0000-1000-8000-00805f9b34fb": "Hardware",
}


# ─── Data structures ────────────────────────────────


@dataclass
class MuseEegPacket:
    """
    Parsed Muse EEG notification for a single channel.

    Each BLE notification contains 12 samples from one EEG channel,
    packed as 12-bit unsigned integers and converted to microvolts.

    Attributes
    ----------
    channel : str
        Channel name (``"TP9"``, ``"AF7"``, ``"AF8"``, or ``"TP10"``).
    counter : int
        Packet sequence number (UINT16, wraps at 65535).
    samples_uv : list[float]
        12 EEG samples in microvolts (µV).

    Reference
    ---------
    Scale factor: ``µV = 0.48828125 × (raw - 2048)``.
    Raw 2048 = 0 µV (ADC midpoint for 12-bit unsigned).
    """

    channel: str
    counter: int
    samples_uv: list[float] = field(default_factory=list)


@dataclass
class MuseTelemetry:
    """
    Parsed Muse telemetry notification.

    Attributes
    ----------
    battery_percent : float
        Battery level as a percentage (0–100).
    """

    battery_percent: float


# ─── Parsers ─────────────────────────────────────────


def _unpack_uint12(data: bytearray, offset: int) -> list[int]:
    """
    Unpack 12 × 12-bit unsigned integers from 18 bytes.

    Each group of 3 bytes contains 2 packed 12-bit values:

    .. code-block:: text

        byte0    byte1    byte2
        AAAAAAAA AAAABBBB BBBBBBBB
        sample_a = (byte0 << 4) | (byte1 >> 4)
        sample_b = ((byte1 & 0x0F) << 8) | byte2

    Parameters
    ----------
    data : bytearray
        Raw notification payload.
    offset : int
        Starting byte offset (after the packet counter).

    Returns
    -------
    list[int]
        12 unsigned 12-bit sample values (0–4095).
    """
    samples: list[int] = []
    for i in range(6):  # 6 groups of 3 bytes = 12 samples
        idx = offset + i * 3
        b0 = data[idx]
        b1 = data[idx + 1]
        b2 = data[idx + 2]
        samples.append((b0 << 4) | (b1 >> 4))
        samples.append(((b1 & 0x0F) << 8) | b2)
    return samples


def parse_eeg_packet(channel: str, data: bytearray) -> MuseEegPacket:
    """
    Parse a Muse EEG BLE notification.

    Packet format: 2 bytes (UINT16 counter) + 18 bytes (12 × UINT12 samples).
    Samples are converted to microvolts via ``µV = 0.48828125 × (raw - 2048)``.

    Parameters
    ----------
    channel : str
        Channel name (``"TP9"``, ``"AF7"``, ``"AF8"``, or ``"TP10"``).
    data : bytearray
        Raw 20-byte BLE notification payload.

    Returns
    -------
    MuseEegPacket
        Parsed packet with counter and 12 samples in µV.
    """
    counter = int.from_bytes(data[0:2], byteorder="big", signed=False)
    raw_samples = _unpack_uint12(data, offset=2)
    samples_uv = [MUSE_SCALE_FACTOR * (s - 2048) for s in raw_samples]

    return MuseEegPacket(channel=channel, counter=counter, samples_uv=samples_uv)


def parse_telemetry(data: bytearray) -> MuseTelemetry:
    """
    Parse a Muse telemetry BLE notification.

    Telemetry format: 5 × UINT16 big-endian values.
    Index 1 (after counter) is battery level raw: ``percent = raw / 512.0``.

    Parameters
    ----------
    data : bytearray
        Raw BLE notification payload (at least 10 bytes).

    Returns
    -------
    MuseTelemetry
        Parsed telemetry with battery percentage.
    """
    # 5 × uint16 big-endian: [counter, battery_raw, fuel_gauge, adc, temperature]
    battery_raw = int.from_bytes(data[2:4], byteorder="big", signed=False)
    return MuseTelemetry(battery_percent=battery_raw / 512.0)


# ─── Subscribe / Stop ───────────────────────────────


def _has_muse_service(client: BleakClient) -> bool:
    """Check if the connected device exposes the Muse GATT service."""
    if client.services is None:
        return False
    for service in client.services:
        if service.uuid.lower() == MUSE_SERVICE_UUID:
            return True
    return False


async def subscribe_muse_eeg(
    client: BleakClient,
    callback: Callable[[MuseEegPacket], None],
) -> None:
    """
    Start EEG streaming on a Muse headband.

    Performs the Muse handshake (ASK → STREAM), then subscribes to
    all 4 EEG channel notifications. Each notification is parsed into
    a ``MuseEegPacket`` and passed to the callback.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient to a Muse device.
    callback : Callable[[MuseEegPacket], None]
        Function called with each parsed EEG packet (one per channel
        per notification cycle).

    Raises
    ------
    ValueError
        If the device does not expose the Muse GATT service.
    """
    if not _has_muse_service(client):
        raise ValueError(
            "Device does not expose the Muse GATT service (0xFE8D). "
            "EEG streaming requires a Muse headband."
        )

    # Subscribe to all 4 EEG channels before sending the stream command
    for uuid, name in zip(MUSE_CHANNEL_UUIDS, MUSE_CHANNEL_NAMES):
        def _make_handler(ch_name: str, ch_uuid: str):
            def _on_notify(sender: int, data: bytearray) -> None:
                packet = parse_eeg_packet(ch_name, data)
                callback(packet)
            return _on_notify
        await client.start_notify(uuid, _make_handler(name, uuid))

    # Muse handshake: ASK device state, then START streaming
    # Control char is write-without-response only on Muse 2
    await client.write_gatt_char(MUSE_CONTROL_UUID, MUSE_CMD_ASK, response=False)
    await client.write_gatt_char(MUSE_CONTROL_UUID, MUSE_CMD_STREAM, response=False)
    logger.info("Muse EEG streaming started (ASK + STREAM written)")


async def stop_muse_eeg(client: BleakClient) -> None:
    """
    Stop EEG streaming on a Muse headband.

    Writes the STOP command and unsubscribes from all EEG channels.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient to a Muse device.
    """
    await client.write_gatt_char(MUSE_CONTROL_UUID, MUSE_CMD_STOP, response=False)
    for uuid in MUSE_CHANNEL_UUIDS:
        try:
            await client.stop_notify(uuid)
        except Exception:
            pass  # Channel may not have been subscribed
    logger.info("Muse EEG streaming stopped")


# ─── Integration harness ─────────────────────────────


def _header(step: int, title: str, sprint: int) -> None:
    """Print and log a step header."""
    line = f"Step {step:02d} — {title}  (Sprint {sprint:02d})"
    print(f"\n{'─' * 55}")
    print(f"  {line}")
    print(f"{'─' * 55}")
    logger.info(line)


def _ok(msg: str) -> None:
    print(f"  [OK]   {msg}")
    logger.info("[OK]   %s", msg)


def _skip(msg: str) -> None:
    print(f"  [SKIP] {msg}")
    logger.warning("[SKIP] %s", msg)


def _fail(msg: str) -> None:
    print(f"  [FAIL] {msg}")
    logger.error("[FAIL] %s", msg)


def _plot_eeg(
    channel_data: dict[str, list[float]],
    sample_rate: int,
) -> None:
    """
    Plot 4-channel EEG signal and save as PNG.

    Channels are stacked vertically with an offset for readability,
    following the standard EEG montage display convention.

    Parameters
    ----------
    channel_data : dict[str, list[float]]
        Mapping channel name → list of samples in µV.
    sample_rate : int
        Sample rate in Hz.
    """
    import matplotlib as mpl
    import matplotlib.pyplot as plt
    from pathlib import Path

    mpl.rcParams.update({
        "figure.facecolor": "white",
        "axes.facecolor": "#fafafa",
        "axes.spines.top": False,
        "axes.spines.right": False,
        "font.family": "sans-serif",
        "axes.labelsize": 13,
        "axes.titlesize": 15,
        "xtick.labelsize": 11,
        "ytick.labelsize": 11,
        "legend.frameon": False,
        "figure.dpi": 150,
    })

    channel_names = ["TP9", "AF7", "AF8", "TP10"]
    colors = ["#e63946", "#457b9d", "#2a9d8f", "#e9c46a"]
    n_channels = len(channel_names)

    # Determine offset between channels for stacked display
    all_values = [v for ch in channel_names for v in channel_data.get(ch, [])]
    if not all_values:
        return
    spread = max(abs(min(all_values)), abs(max(all_values)))
    offset = spread * 1.5 if spread > 0 else 100.0

    fig, ax = plt.subplots(figsize=(14, 8))

    yticks = []
    ytick_labels = []

    for i, (ch_name, color) in enumerate(zip(channel_names, colors)):
        samples = channel_data.get(ch_name, [])
        if not samples:
            continue
        n = len(samples)
        time_s = [t / sample_rate for t in range(n)]
        y_offset = -i * offset
        y = [s + y_offset for s in samples]
        ax.plot(time_s, y, color=color, linewidth=0.6, alpha=0.9)
        yticks.append(y_offset)
        ytick_labels.append(ch_name)

    ax.set_yticks(yticks)
    ax.set_yticklabels(ytick_labels)
    ax.set_xlabel("Time (s)")
    ax.set_title(f"Muse 2 — EEG ({sample_rate} Hz, 4 channels)")

    log_dir = Path(__file__).resolve().parent.parent / "logs"
    log_dir.mkdir(exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    plot_path = log_dir / f"eeg_plot_{ts}.png"
    fig.savefig(plot_path, dpi=300, bbox_inches="tight")
    plt.close(fig)

    _ok(f"EEG plot saved: {plot_path}")


async def run_muse_example(
    address: str | None = None,
    scan_duration: float = 5.0,
    stream_duration: float = 5.0,
) -> None:
    """
    Run the full Muse EEG integration example.

    Exercises every BLE feature from Sprints 01–09 sequentially,
    printing a step-by-step report. If no address is given, the script
    scans for Muse devices and picks the first one found.

    Parameters
    ----------
    address : str | None
        Device address or UUID. If ``None``, scan for Muse devices.
    scan_duration : float
        Scan window in seconds (default: 5.0).
    stream_duration : float
        EEG streaming duration in seconds (default: 5.0).
    """
    results = {}

    # ── Step 01 — Passive Scan (Sprint 01) ──────────────
    _header(1, "Passive Scan", 1)
    devices = await BleakScanner.discover(timeout=scan_duration)
    _ok(f"Found {len(devices)} device(s)")
    for d in devices[:5]:
        name = d.name or "[unknown]"
        _ok(f"  {d.address}  {name}")
    if len(devices) > 5:
        _ok(f"  ... and {len(devices) - 5} more")
    results["scan"] = len(devices) > 0

    # ── Step 02 — Filtered Scan by UUID (Sprint 02) ─────
    _header(2, "Filtered Scan (Muse Service UUID)", 2)
    muse_results = await BleakScanner.discover(
        timeout=scan_duration,
        return_adv=True,
        service_uuids=[MUSE_SERVICE_UUID],
    )
    muse_devices = list(muse_results.values())
    if muse_devices:
        _ok(f"Found {len(muse_devices)} Muse device(s)")
        for device, adv in muse_devices:
            name = device.name or "[unknown]"
            _ok(f"  {device.address}  {name}")
        if address is None:
            address = muse_devices[0][0].address
            _ok(f"Auto-selected: {address}")
    else:
        _fail("No Muse devices found via filtered scan")
        if address is None:
            print("\n  Provide a device address manually:")
            print("  python 11_muse_example.py <address>")
            return
        _ok(f"Using provided address: {address}")
    results["filtered_scan"] = len(muse_devices) > 0

    # ── Step 03 — Advertisement Data (Sprint 07) ────────
    _header(3, "Advertisement Data (no connection)", 7)
    if muse_devices:
        device, adv = muse_devices[0]
        name = adv.local_name or "[none]"
        _ok(f"Local name: {name}")
        if adv.tx_power is not None:
            _ok(f"TX Power: {adv.tx_power} dBm")
        else:
            _skip("TX Power not advertised")
        if adv.manufacturer_data:
            for cid, payload in adv.manufacturer_data.items():
                _ok(f"Manufacturer 0x{cid:04X}: {payload.hex()}")
        else:
            _skip("No manufacturer data")
        if adv.service_uuids:
            for uuid in adv.service_uuids:
                _ok(f"Service UUID: {uuid}")
        results["adv_data"] = True
    else:
        _skip("No advertisement data (device found by address)")
        results["adv_data"] = False

    # ── Step 04 — Connect with Retry (Sprint 03) ───────
    _header(4, "Connect", 3)
    client = BleakClient(address)
    max_attempts = 3
    for attempt in range(1, max_attempts + 1):
        try:
            await client.connect(timeout=10.0)
            _ok(f"Connected on attempt {attempt}")
            break
        except Exception as e:
            if attempt < max_attempts:
                wait = 2 ** attempt
                _fail(f"Attempt {attempt} failed: {e}")
                _ok(f"Retrying in {wait}s...")
                await asyncio.sleep(wait)
            else:
                _fail(f"All {max_attempts} attempts failed: {e}")
                results["connect"] = False
                return
    results["connect"] = client.is_connected

    try:
        # ── Step 05 — Service Discovery (Sprint 04) ────────
        _header(5, "Service & Characteristic Discovery", 4)
        services_list = list(client.services)
        _ok(f"Discovered {len(services_list)} service(s)")
        for svc in services_list:
            chars = list(svc.characteristics)
            _ok(f"  {svc.uuid}  ({len(chars)} char(s))")
            for char in chars:
                props = ", ".join(char.properties)
                _ok(f"    {char.uuid}  [{props}]")
        results["discovery"] = len(services_list) > 0

        # ── Step 06 — Device Information (Sprint 09) ───────
        _header(6, "Device Information Service", 9)
        has_dis = any(
            s.uuid.lower() == DIS_SERVICE_UUID for s in services_list
        )
        if has_dis:
            for uuid, label in DIS_CHARACTERISTICS.items():
                try:
                    data = await client.read_gatt_char(uuid)
                    value = data.decode("utf-8").strip()
                    _ok(f"{label}: {value}")
                except Exception:
                    _skip(f"{label}: not readable")
            results["device_info"] = True
        else:
            _skip("Device Information Service not available")
            results["device_info"] = False

        # ── Step 07 — Battery Level (Sprint 08) ────────────
        _header(7, "Battery Level", 8)
        has_battery = any(
            s.uuid.lower() == BATTERY_SERVICE_UUID for s in services_list
        )
        if has_battery:
            try:
                data = await client.read_gatt_char(BATTERY_CHAR_UUID)
                level = min(100, max(0, data[0]))
                _ok(f"Battery: {level}%")
                results["battery"] = True
            except Exception as e:
                _fail(f"Could not read battery: {e}")
                results["battery"] = False
        else:
            _skip("Battery Service not available")
            results["battery"] = False

        # ── Step 08 — Read Characteristic (Sprint 05) ──────
        _header(8, "Read Control Characteristic", 5)
        try:
            data = await client.read_gatt_char(MUSE_CONTROL_UUID)
            _ok(f"Control char value: {data.hex()}")
            results["read_char"] = True
        except Exception as e:
            _skip(f"Control char not readable: {e}")
            results["read_char"] = False

        # ── Step 09 — Telemetry (Sprint 06 + parser) ───────
        _header(9, "Telemetry Notifications", 6)
        telemetry_data: list[MuseTelemetry] = []

        try:
            def on_telemetry(sender: int, data: bytearray) -> None:
                try:
                    t = parse_telemetry(data)
                    telemetry_data.append(t)
                except Exception:
                    pass

            await client.start_notify(MUSE_TELEMETRY_UUID, on_telemetry)
            # Telemetry fires ~once per second, wait briefly
            await asyncio.sleep(3.0)
            await client.stop_notify(MUSE_TELEMETRY_UUID)

            if telemetry_data:
                latest = telemetry_data[-1]
                _ok(f"Battery (telemetry): {latest.battery_percent:.0f}%")
                _ok(f"Received {len(telemetry_data)} telemetry packet(s)")
                results["telemetry"] = True
            else:
                _skip("No telemetry packets received")
                results["telemetry"] = False
        except Exception as e:
            _skip(f"Telemetry not available: {e}")
            results["telemetry"] = False

        # ── Step 10 — EEG Stream (Muse proprietary) ────────
        _header(10, "EEG Stream (Muse)", 11)
        has_muse = any(
            s.uuid.lower() == MUSE_SERVICE_UUID for s in services_list
        )
        if has_muse:
            channel_data: dict[str, list[float]] = {
                ch: [] for ch in MUSE_CHANNEL_NAMES
            }

            def on_eeg(packet: MuseEegPacket) -> None:
                channel_data[packet.channel].extend(packet.samples_uv)

            try:
                await subscribe_muse_eeg(client, on_eeg)
                _ok(f"EEG started — streaming for {stream_duration}s...")
                await asyncio.sleep(stream_duration)
                await stop_muse_eeg(client)

                total_samples = sum(len(v) for v in channel_data.values())
                if total_samples > 0:
                    for ch_name in MUSE_CHANNEL_NAMES:
                        ch_samples = channel_data[ch_name]
                        if ch_samples:
                            ch_min = min(ch_samples)
                            ch_max = max(ch_samples)
                            ch_mean = sum(ch_samples) / len(ch_samples)
                            _ok(
                                f"{ch_name}: {len(ch_samples)} samples, "
                                f"range {ch_min:.0f}..{ch_max:.0f} µV, "
                                f"mean {ch_mean:.0f} µV"
                            )
                        else:
                            _fail(f"{ch_name}: no samples")

                    effective_s = (
                        max(len(v) for v in channel_data.values()) / MUSE_SAMPLE_RATE
                    )
                    _ok(
                        f"Total: {total_samples} samples across 4 channels, "
                        f"{effective_s:.1f}s effective"
                    )

                    # ── EEG Plot ──────────────────────────
                    try:
                        _plot_eeg(channel_data, MUSE_SAMPLE_RATE)
                    except Exception as e:
                        logger.warning("EEG plot failed: %s", e)

                    results["eeg"] = True
                else:
                    _fail("No EEG samples received")
                    results["eeg"] = False
            except Exception as e:
                _fail(f"EEG streaming error: {e}")
                results["eeg"] = False
        else:
            _skip("Muse GATT service not available")
            results["eeg"] = False

    finally:
        # ── Step 11 — Clean Disconnect (Sprint 03) ─────────
        _header(11, "Clean Disconnect", 3)
        if client.is_connected:
            await client.disconnect()
            _ok("Disconnected")
        else:
            _skip("Already disconnected")
        results["disconnect"] = True

    # ── Summary ─────────────────────────────────────────
    print(f"\n{'═' * 55}")
    print("  INTEGRATION SUMMARY")
    print(f"{'═' * 55}")
    passed = sum(1 for v in results.values() if v)
    total = len(results)
    for step, ok in results.items():
        status = "[OK]  " if ok else "[SKIP]"
        print(f"  {status} {step}")
    print(f"\n  Result: {passed}/{total} steps passed")
    print(f"{'═' * 55}")


# ─── Entrypoint ──────────────────────────────────────

if __name__ == "__main__":
    import sys
    from pathlib import Path

    address = sys.argv[1] if len(sys.argv) > 1 else None

    # ── Logging setup: file + console ───────────────────
    log_dir = Path(__file__).resolve().parent.parent / "logs"
    log_dir.mkdir(exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    log_file = log_dir / f"muse_integration_{timestamp}.log"

    logging.basicConfig(
        level=logging.DEBUG,
        format="%(asctime)s  %(levelname)-7s  %(message)s",
        datefmt="%H:%M:%S",
        handlers=[
            logging.FileHandler(log_file),
            logging.StreamHandler(sys.stderr),
        ],
    )
    # Suppress noisy bleak debug logs
    logging.getLogger("bleak").setLevel(logging.WARNING)

    print("=" * 55)
    print("  BLE Reference Library — Muse EEG Integration Test")
    print("  Exercises Sprints 01–09 + EEG on a real Muse 2")
    print(f"  Log: {log_file}")
    print("=" * 55)

    asyncio.run(run_muse_example(address=address, stream_duration=5.0))
