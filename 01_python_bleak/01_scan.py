# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 01 — Passive Scan
# Platform: Python / Bleak
# Sprint  : Sprint 01
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Discover all advertising BLE devices in range and report
#           their address, name, and RSSI.
#
# Usage   : python 01_python_bleak/01_scan.py
#           or: from 01_scan import scan; devices = await scan()
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Passive BLE scan using Bleak.

Discovers all advertising BLE peripherals within range and returns
them as a list of BLEDevice objects.

Platform Notes
--------------
- **macOS / iOS**: Addresses are CoreBluetooth UUIDs (e.g.
  ``AB12CD34-...``), not hardware MAC addresses. This is an Apple
  privacy restriction — the real MAC is never exposed to userland.
- **Linux / Windows**: Addresses are standard MAC addresses
  (e.g. ``AA:BB:CC:DD:EE:FF``).
- The ``address`` field on ``BLEDevice`` abstracts this difference,
  but the format will vary across platforms.
"""

from __future__ import annotations

import asyncio

from bleak import BleakScanner
from bleak.backends.device import BLEDevice

# ─── Constants ───────────────────────────────────────

DEFAULT_SCAN_DURATION: float = 5.0
"""Default scan window in seconds."""

UNKNOWN_DEVICE_NAME: str = "[unknown]"
"""Placeholder when a device does not advertise a local name."""


# ─── Core functions ──────────────────────────────────


async def scan(duration: float = DEFAULT_SCAN_DURATION) -> list[BLEDevice]:
    """
    Perform a passive BLE scan and return discovered devices.

    Uses ``BleakScanner.discover()`` to listen for BLE advertisements
    for the specified duration.

    Parameters
    ----------
    duration : float
        How long to scan, in seconds (default: 5.0).

    Returns
    -------
    list[BLEDevice]
        All devices discovered during the scan window.
        May be empty if no devices are advertising.

    Example
    -------
    >>> devices = await scan(duration=3.0)
    >>> for d in devices:
    ...     print(d.address, d.name)
    """
    devices: list[BLEDevice] = await BleakScanner.discover(timeout=duration)
    return devices


def format_device(device: BLEDevice) -> str:
    """
    Format a single BLE device as a human-readable string.

    Parameters
    ----------
    device : BLEDevice
        A discovered BLE device.

    Returns
    -------
    str
        Formatted string: ``address  rssi dBm  name``.
    """
    name = device.name if device.name else UNKNOWN_DEVICE_NAME
    rssi = getattr(device, "rssi", None)
    rssi_str = f"{rssi:>4d} dBm" if rssi is not None else "   ? dBm"
    return f"{device.address:<20s}  {rssi_str}  {name}"


async def scan_and_print(duration: float = DEFAULT_SCAN_DURATION) -> None:
    """
    Scan for BLE devices and print results to stdout.

    Parameters
    ----------
    duration : float
        How long to scan, in seconds (default: 5.0).

    Example
    -------
    >>> await scan_and_print(duration=3.0)
    ── BLE Scan (3.0 s) ─────────────────────────────
    AA:BB:CC:DD:EE:FF   -62 dBm  Heart Rate Sensor
    11:22:33:44:55:66   -78 dBm  [unknown]
    ── 2 device(s) found ────────────────────────────
    """
    print(f"── BLE Scan ({duration} s) {'─' * 33}")
    devices = await scan(duration=duration)
    for device in devices:
        print(format_device(device))
    print(f"── {len(devices)} device(s) found {'─' * 32}")


# ─── Entrypoint ──────────────────────────────────────

if __name__ == "__main__":
    asyncio.run(scan_and_print())
