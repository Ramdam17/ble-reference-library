# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 10 — Heart Rate Monitor Integration Example
# Platform: Python / Bleak
# Sprint  : Sprint 10
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : End-to-end integration test exercising ALL Sprints 01–09
#           against a real Heart Rate Monitor device. Each step
#           validates a specific BLE feature from the library.
#
# Usage   : python 01_python_bleak/10_heartrate_example.py [device_address]
#           or: from 10_heartrate_example import parse_heart_rate_measurement
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Heart Rate Monitor integration example — validates Sprints 01–09.

This module serves two purposes:

1. **Parser library**: ``parse_heart_rate_measurement`` decodes the HRM
   characteristic (0x2A37) and can be imported standalone.

2. **Integration harness**: ``run_hrm_example`` exercises every BLE feature
   from Sprints 01–09 against a real HR device, producing a step-by-step
   report. This is the "smoke test" that proves all building blocks work
   together on real hardware.

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
    Step 09 — HR Notifications      (Sprint 06 + parser)
    Step 10 — Clean Disconnect      (Sprint 03)

Heart Rate Measurement Format
-----------------------------
The characteristic value starts with a **flags byte**:

.. code-block:: text

    Bit 0:   BPM format (0 = UINT8, 1 = UINT16 little-endian)
    Bits 1-2: Sensor Contact (00/01 = unsupported, 10 = no, 11 = yes)
    Bit 3:   Energy Expended present (UINT16, skip)
    Bit 4:   RR-Interval present (UINT16 each, 1/1024s units)

Reference
---------
Bluetooth SIG, Heart Rate Service Specification v1.0, Section 3.1.1.1.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime

from bleak import BleakClient, BleakScanner

logger = logging.getLogger(__name__)

# ─── Constants ───────────────────────────────────────

HEART_RATE_SERVICE_UUID: str = "0000180d-0000-1000-8000-00805f9b34fb"
"""Standard Heart Rate Service UUID (0x180D)."""

HEART_RATE_CHAR_UUID: str = "00002a37-0000-1000-8000-00805f9b34fb"
"""Heart Rate Measurement characteristic UUID (0x2A37)."""

BODY_SENSOR_LOCATION_UUID: str = "00002a38-0000-1000-8000-00805f9b34fb"
"""Body Sensor Location characteristic UUID (0x2A38)."""

RR_INTERVAL_RESOLUTION: float = 1024.0
"""RR interval timer resolution: 1/1024 seconds per unit."""

DEFAULT_STREAM_DURATION: float = 30.0
"""Default streaming duration in seconds for the entrypoint."""


# ─── Data structures ────────────────────────────────


@dataclass
class HeartRateMeasurement:
    """
    Parsed Heart Rate Measurement value.

    Attributes
    ----------
    bpm : int
        Heart rate in beats per minute.
    rr_intervals : list[float]
        RR intervals in seconds. Empty if not present in the packet.
    sensor_contact : bool | None
        ``True`` if sensor contact detected, ``False`` if not detected,
        ``None`` if the device does not support contact detection.
    """

    bpm: int
    rr_intervals: list[float] = field(default_factory=list)
    sensor_contact: bool | None = None


# ─── Core functions ──────────────────────────────────


def parse_heart_rate_measurement(data: bytearray) -> HeartRateMeasurement:
    """
    Parse a Heart Rate Measurement characteristic value.

    Decodes the flags byte and extracts BPM, sensor contact status,
    and RR intervals according to the Bluetooth Heart Rate Service spec.

    Parameters
    ----------
    data : bytearray
        Raw characteristic value from a Heart Rate Measurement notification.

    Returns
    -------
    HeartRateMeasurement
        Parsed measurement with BPM, RR intervals, and contact status.

    Reference
    ---------
    Bluetooth SIG, Heart Rate Service Specification v1.0, Section 3.1.1.1.
    """
    flags = data[0]
    offset = 1

    # Bit 0: BPM format (0 = UINT8, 1 = UINT16 little-endian)
    if flags & 0x01:
        bpm = int.from_bytes(data[offset : offset + 2], byteorder="little")
        offset += 2
    else:
        bpm = data[offset]
        offset += 1

    # Bits 1-2: Sensor Contact Status
    contact_bits = (flags >> 1) & 0x03
    if contact_bits < 2:
        sensor_contact = None  # not supported
    else:
        sensor_contact = bool(contact_bits & 0x01)  # bit 1 of the pair

    # Bit 3: Energy Expended present (UINT16, skip it)
    if flags & 0x08:
        offset += 2

    # Bit 4: RR-Interval present
    rr_intervals: list[float] = []
    if flags & 0x10:
        while offset + 1 < len(data):
            raw_rr = int.from_bytes(data[offset : offset + 2], byteorder="little")
            rr_intervals.append(raw_rr / RR_INTERVAL_RESOLUTION)
            offset += 2

    return HeartRateMeasurement(
        bpm=bpm,
        rr_intervals=rr_intervals,
        sensor_contact=sensor_contact,
    )


def _has_heart_rate_service(client: BleakClient) -> bool:
    """Check if the connected device exposes the Heart Rate Service."""
    if client.services is None:
        return False
    for service in client.services:
        if service.uuid.lower() == HEART_RATE_SERVICE_UUID:
            return True
    return False


async def subscribe_heart_rate(
    client: BleakClient,
    callback: Callable[[HeartRateMeasurement], None],
) -> None:
    """
    Subscribe to Heart Rate Measurement notifications.

    The ``callback`` receives a parsed ``HeartRateMeasurement`` each time
    the device reports a new measurement.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.
    callback : Callable[[HeartRateMeasurement], None]
        Function called with each parsed measurement.

    Raises
    ------
    ValueError
        If the device does not expose the Heart Rate Service.
    """
    if not _has_heart_rate_service(client):
        raise ValueError(
            "Device does not expose the Heart Rate Service (0x180D). "
            "Cannot subscribe to heart rate notifications."
        )

    def _on_notify(sender: int, data: bytearray) -> None:
        measurement = parse_heart_rate_measurement(data)
        callback(measurement)

    await client.start_notify(HEART_RATE_CHAR_UUID, _on_notify)


# ─── Integration harness ─────────────────────────────

BODY_SENSOR_LOCATIONS: dict[int, str] = {
    0: "Other",
    1: "Chest",
    2: "Wrist",
    3: "Finger",
    4: "Hand",
    5: "Ear Lobe",
    6: "Foot",
}
"""Body Sensor Location enum values (Bluetooth SIG assigned numbers)."""

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


async def run_hrm_example(
    address: str | None = None,
    scan_duration: float = 5.0,
    stream_duration: float = 10.0,
) -> None:
    """
    Run the full Heart Rate Monitor integration example.

    Exercises every BLE feature from Sprints 01–09 sequentially,
    printing a step-by-step report. If no address is given, the script
    scans for HR devices and picks the first one found.

    Parameters
    ----------
    address : str | None
        Device address or UUID. If ``None``, scan for HR devices.
    scan_duration : float
        Scan window in seconds (default: 5.0).
    stream_duration : float
        HR notification streaming duration in seconds (default: 10.0).
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
    _header(2, "Filtered Scan (HR Service UUID)", 2)
    hr_results = await BleakScanner.discover(
        timeout=scan_duration,
        return_adv=True,
        service_uuids=[HEART_RATE_SERVICE_UUID],
    )
    hr_devices = list(hr_results.values())
    if hr_devices:
        _ok(f"Found {len(hr_devices)} HR device(s)")
        for device, adv in hr_devices:
            name = device.name or "[unknown]"
            _ok(f"  {device.address}  {name}")
        if address is None:
            address = hr_devices[0][0].address
            _ok(f"Auto-selected: {address}")
    else:
        _fail("No HR devices found via filtered scan")
        if address is None:
            print("\n  Provide a device address manually:")
            print("  python 10_heartrate_example.py <address>")
            return
        _ok(f"Using provided address: {address}")
    results["filtered_scan"] = len(hr_devices) > 0

    # ── Step 03 — Advertisement Data (Sprint 07) ────────
    _header(3, "Advertisement Data (no connection)", 7)
    if hr_devices:
        device, adv = hr_devices[0]
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
        _header(8, "Read Body Sensor Location", 5)
        try:
            data = await client.read_gatt_char(BODY_SENSOR_LOCATION_UUID)
            location = BODY_SENSOR_LOCATIONS.get(data[0], f"Unknown ({data[0]})")
            _ok(f"Sensor location: {location}")
            results["read_char"] = True
        except Exception as e:
            _skip(f"Body Sensor Location not available: {e}")
            results["read_char"] = False

        # ── Step 09 — HR Notifications (Sprint 06 + parser) ─
        _header(9, "Heart Rate Notifications", 6)
        measurements: list[HeartRateMeasurement] = []

        def on_hr(m: HeartRateMeasurement) -> None:
            measurements.append(m)
            rr_str = ""
            if m.rr_intervals:
                rr_ms = [f"{rr * 1000:.0f}ms" for rr in m.rr_intervals]
                rr_str = f"  RR: {', '.join(rr_ms)}"
            contact = ""
            if m.sensor_contact is not None:
                contact = f"  [contact: {'yes' if m.sensor_contact else 'no'}]"
            _ok(f"BPM: {m.bpm}{rr_str}{contact}")

        await subscribe_heart_rate(client, on_hr)
        _ok(f"Subscribed — streaming for {stream_duration}s...")
        await asyncio.sleep(stream_duration)
        await client.stop_notify(HEART_RATE_CHAR_UUID)
        _ok(f"Received {len(measurements)} measurement(s)")
        results["notifications"] = len(measurements) > 0

    finally:
        # ── Step 10 — Clean Disconnect (Sprint 03) ─────────
        _header(10, "Clean Disconnect", 3)
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
    log_file = log_dir / f"hrm_integration_{timestamp}.log"

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
    print("  BLE Reference Library — Heart Rate Integration Test")
    print("  Exercises Sprints 01–09 on a real HR device")
    print(f"  Log: {log_file}")
    print("=" * 55)

    asyncio.run(run_hrm_example(address=address))
