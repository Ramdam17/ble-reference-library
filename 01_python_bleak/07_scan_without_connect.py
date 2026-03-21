# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 07 — Scan Without Connection (Advertisement Data)
# Platform: Python / Bleak
# Sprint  : Sprint 07
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Read data broadcast in BLE advertisement packets without
#           establishing a connection. Parse manufacturer data, service
#           data, local name, and TX power level.
#
# Usage   : python 01_python_bleak/07_scan_without_connect.py
#           or: from 07_scan_without_connect import parse_manufacturer_data
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
BLE advertisement data parsing without connection using Bleak.

BLE peripherals continuously broadcast advertisement packets containing
metadata: device name, TX power, service UUIDs, manufacturer-specific
data, and service data. This module parses these fields from
``AdvertisementData`` objects obtained via ``BleakScanner.discover()``.

No connection is required — all data comes from passive scanning.

Platform Notes
--------------
- **Manufacturer data** keys are 16-bit Company Identifiers assigned
  by the Bluetooth SIG (e.g. Apple=0x004C, Polar=0x006B).
- **TX power** may not be present in all advertisements. Some devices
  only include it in scan response packets, which may not always be
  captured depending on timing.
- **macOS**: ``BleakScanner`` may coalesce multiple advertisements
  from the same device, so manufacturer data may include the latest
  values only.
"""

from __future__ import annotations

import asyncio

from bleak import BleakScanner
from bleak.backends.device import BLEDevice
from bleak.backends.scanner import AdvertisementData

# ─── Constants ───────────────────────────────────────

DEFAULT_SCAN_DURATION: float = 5.0
"""Default scan window in seconds."""


# ─── Core functions ──────────────────────────────────


async def scan_advertisement_data(
    duration: float = DEFAULT_SCAN_DURATION,
) -> list[tuple[BLEDevice, AdvertisementData]]:
    """
    Scan for BLE devices and return their advertisement data.

    No connection is established — all data comes from passive scanning.

    Parameters
    ----------
    duration : float
        Scan window in seconds (default: 5.0).

    Returns
    -------
    list[tuple[BLEDevice, AdvertisementData]]
        Each element is ``(device, advertisement_data)``.
    """
    results = await BleakScanner.discover(
        timeout=duration,
        return_adv=True,
    )
    return list(results.values())


def parse_manufacturer_data(adv: AdvertisementData) -> dict[int, bytes]:
    """
    Extract manufacturer-specific data from an advertisement.

    Parameters
    ----------
    adv : AdvertisementData
        Advertisement data from a scanned device.

    Returns
    -------
    dict[int, bytes]
        Keys are 16-bit Company Identifiers (e.g. ``0x004C`` for Apple).
        Values are the raw manufacturer payload bytes.
        Empty dict if no manufacturer data is present.

    Example
    -------
    >>> mfr = parse_manufacturer_data(adv)
    >>> if 0x006B in mfr:  # Polar
    ...     print(f"Polar payload: {mfr[0x006B].hex()}")
    """
    return dict(adv.manufacturer_data) if adv.manufacturer_data else {}


def parse_service_data(adv: AdvertisementData) -> dict[str, bytes]:
    """
    Extract service data from an advertisement.

    Parameters
    ----------
    adv : AdvertisementData
        Advertisement data from a scanned device.

    Returns
    -------
    dict[str, bytes]
        Keys are service UUID strings (lowercase).
        Values are the raw service data bytes.
        Empty dict if no service data is present.
    """
    if not adv.service_data:
        return {}
    return {uuid.lower(): bytes(data) for uuid, data in adv.service_data.items()}


def parse_local_name(adv: AdvertisementData) -> str | None:
    """
    Extract the advertised local name from an advertisement.

    Parameters
    ----------
    adv : AdvertisementData
        Advertisement data from a scanned device.

    Returns
    -------
    str | None
        The advertised local name, or ``None`` if not present.
    """
    return adv.local_name if adv.local_name else None


def parse_tx_power(adv: AdvertisementData) -> int | None:
    """
    Extract the TX power level from an advertisement.

    TX power (in dBm) indicates the signal strength at 1 meter from
    the transmitter. Combined with RSSI, it can be used to estimate
    distance to the device.

    Parameters
    ----------
    adv : AdvertisementData
        Advertisement data from a scanned device.

    Returns
    -------
    int | None
        TX power in dBm, or ``None`` if not advertised.
    """
    return adv.tx_power


# ─── Entrypoint ──────────────────────────────────────

if __name__ == "__main__":
    print("── Scanning advertisement data (no connection) ──")

    results = asyncio.run(scan_advertisement_data())

    for device, adv in results:
        name = parse_local_name(adv) or "[unknown]"
        mfr = parse_manufacturer_data(adv)
        svc = parse_service_data(adv)
        tx = parse_tx_power(adv)

        print(f"\n  {device.address}  {name}")
        if tx is not None:
            print(f"    TX Power: {tx} dBm")
        if adv.rssi is not None:
            print(f"    RSSI:     {adv.rssi} dBm")
        if mfr:
            for cid, payload in mfr.items():
                print(f"    Manufacturer 0x{cid:04X}: {payload.hex()}")
        if svc:
            for uuid, data in svc.items():
                print(f"    Service {uuid}: {data.hex()}")

    print(f"\n── {len(results)} device(s) found {'─' * 32}")
