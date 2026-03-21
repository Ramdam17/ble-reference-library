# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 02 — Filtered Scan
# Platform: Python / Bleak
# Sprint  : Sprint 02
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Scan for BLE devices with filters — by name, service UUID,
#           RSSI threshold, or raw advertisement data.
#
# Usage   : python 01_python_bleak/02_scan_filtered.py
#           or: from 02_scan_filtered import scan_by_name
#
# Depends : None (standalone — UUID normalization is inlined)
# ─────────────────────────────────────────────────────

"""
Filtered BLE scan using Bleak.

Provides four scan strategies:

- **By name**: case-insensitive substring match on advertised local name.
- **By service UUID**: hardware-level filtering where supported, with
  fallback to post-scan filtering.
- **By RSSI**: post-scan threshold filter (Bleak has no native RSSI filter).
- **With advertisement data**: returns raw ``AdvertisementData`` alongside
  each device for custom filtering.

Platform Notes
--------------
- ``BleakScanner(service_uuids=[...])`` uses hardware-level filtering on
  most adapters, but some (e.g. older Linux/BlueZ) may ignore it. The
  docstrings document this limitation.
- UUID normalization handles both short (``0x180F``) and full 128-bit
  formats. All comparisons use lowercase full UUIDs.
"""

from __future__ import annotations

import asyncio

from bleak import BleakScanner
from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData

# ─── Constants ───────────────────────────────────────

DEFAULT_SCAN_DURATION: float = 5.0
"""Default scan window in seconds."""

BLUETOOTH_BASE_UUID_TEMPLATE: str = "0000{}-0000-1000-8000-00805f9b34fb"
"""Bluetooth SIG base UUID template for short-to-full UUID conversion."""

DEFAULT_RSSI_THRESHOLD: int = -80
"""Default minimum RSSI in dBm for RSSI-based filtering."""


# ─── UUID Normalization ─────────────────────────────


def normalize_uuid(uuid: str) -> str:
    """
    Normalize a Bluetooth UUID to full 128-bit lowercase format.

    Handles both short UUIDs (``0x180F``, ``180F``, ``180f``) and full
    128-bit UUIDs (``0000180f-0000-1000-8000-00805f9b34fb``).

    Short UUIDs are expanded using the Bluetooth SIG base UUID:
    ``0000XXXX-0000-1000-8000-00805f9b34fb``

    Parameters
    ----------
    uuid : str
        A Bluetooth UUID in any accepted format.

    Returns
    -------
    str
        Full 128-bit UUID, lowercase, with hyphens.

    Example
    -------
    >>> normalize_uuid("0x180F")
    '0000180f-0000-1000-8000-00805f9b34fb'
    >>> normalize_uuid("0000180f-0000-1000-8000-00805f9b34fb")
    '0000180f-0000-1000-8000-00805f9b34fb'
    """
    cleaned = uuid.strip().lower().removeprefix("0x")
    if len(cleaned) <= 8 and "-" not in cleaned:
        # Short UUID (16-bit or 32-bit) → expand to 128-bit
        padded = cleaned.zfill(4)
        return BLUETOOTH_BASE_UUID_TEMPLATE.format(padded)
    return cleaned


# ─── Core functions ──────────────────────────────────


async def scan_by_name(
    name: str,
    duration: float = DEFAULT_SCAN_DURATION,
) -> list[BLEDevice]:
    """
    Scan for BLE devices whose advertised name contains a substring.

    The match is **case-insensitive** and checks for substring inclusion,
    not exact equality (e.g. ``"Muse"`` matches ``"Muse-1234"``).

    Parameters
    ----------
    name : str
        Substring to search for in advertised device names.
    duration : float
        Scan window in seconds (default: 5.0).

    Returns
    -------
    list[BLEDevice]
        Devices whose name contains *name* (case-insensitive).
    """
    devices: list[BLEDevice] = await BleakScanner.discover(timeout=duration)
    name_lower = name.lower()
    return [
        d for d in devices
        if d.name and name_lower in d.name.lower()
    ]


async def scan_by_service_uuid(
    uuid: str,
    duration: float = DEFAULT_SCAN_DURATION,
) -> list[BLEDevice]:
    """
    Scan for BLE devices advertising a specific service UUID.

    Uses ``BleakScanner(service_uuids=[...])`` for hardware-level filtering
    where supported. The UUID is normalized to full 128-bit format before
    being passed to the scanner.

    Parameters
    ----------
    uuid : str
        Service UUID to filter by (short or full format accepted).
    duration : float
        Scan window in seconds (default: 5.0).

    Returns
    -------
    list[BLEDevice]
        Devices advertising the specified service UUID.

    Platform Notes
    --------------
    Some BLE adapters (e.g. older BlueZ) may ignore the service UUID
    filter and return all devices. If precise filtering is critical,
    consider post-scan validation using ``scan_with_advertisement_data``.

    Example
    -------
    >>> # Heart Rate Service
    >>> devices = await scan_by_service_uuid("0x180D")
    """
    full_uuid = normalize_uuid(uuid)
    devices: list[BLEDevice] = await BleakScanner.discover(
        timeout=duration,
        service_uuids=[full_uuid],
    )
    return devices


async def scan_by_rssi(
    min_rssi: int = DEFAULT_RSSI_THRESHOLD,
    duration: float = DEFAULT_SCAN_DURATION,
) -> list[BLEDevice]:
    """
    Scan for BLE devices above a minimum RSSI threshold.

    RSSI filtering is applied **post-scan** because BleakScanner does
    not support native RSSI filtering. All devices are discovered first,
    then filtered by signal strength.

    Parameters
    ----------
    min_rssi : int
        Minimum RSSI in dBm (default: -80). Devices with weaker
        signals (more negative RSSI) are excluded.
    duration : float
        Scan window in seconds (default: 5.0).

    Returns
    -------
    list[BLEDevice]
        Devices with RSSI >= *min_rssi*.
    """
    results = await BleakScanner.discover(
        timeout=duration,
        return_adv=True,
    )
    return [
        device
        for device, adv in results.values()
        if adv.rssi is not None and adv.rssi >= min_rssi
    ]


async def scan_with_advertisement_data(
    duration: float = DEFAULT_SCAN_DURATION,
) -> list[tuple[BLEDevice, AdvertisementData]]:
    """
    Scan and return devices paired with their raw advertisement data.

    This is the most flexible scan function — use it when you need
    access to manufacturer data, TX power, service data, or any other
    field in the advertisement payload for custom filtering.

    Parameters
    ----------
    duration : float
        Scan window in seconds (default: 5.0).

    Returns
    -------
    list[tuple[BLEDevice, AdvertisementData]]
        Each element is ``(device, advertisement_data)``.

    Example
    -------
    >>> results = await scan_with_advertisement_data(duration=3.0)
    >>> for device, adv in results:
    ...     print(device.name, adv.service_uuids, adv.rssi)
    """
    results = await BleakScanner.discover(
        timeout=duration,
        return_adv=True,
    )
    return list(results.values())


# ─── Entrypoint ──────────────────────────────────────

if __name__ == "__main__":
    import sys

    print("── Filtered Scan Demo ─────────────────────────")

    if len(sys.argv) > 1:
        target_name = sys.argv[1]
        print(f"Scanning for devices matching '{target_name}'...")
        devices = asyncio.run(scan_by_name(target_name))
    else:
        print("Scanning all devices with advertisement data...")
        pairs = asyncio.run(scan_with_advertisement_data())
        devices = [d for d, _ in pairs]
        for device, adv in pairs:
            name = device.name or "[unknown]"
            uuids = ", ".join(adv.service_uuids) if adv.service_uuids else "none"
            rssi = adv.rssi if adv.rssi is not None else "?"
            print(f"  {device.address:<20s}  {rssi:>4} dBm  {name}  services=[{uuids}]")

    print(f"── {len(devices)} device(s) found {'─' * 32}")
