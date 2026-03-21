# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 09 — Device Information Service
# Platform: Python / Bleak
# Sprint  : Sprint 09
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Read device identification data from a connected BLE device
#           using the standard Device Information Service (0x180A).
#           Provides a unique device identifier strategy for multi-sensor
#           setups, especially on macOS/iOS where MAC addresses are hidden.
#
# Usage   : python 01_python_bleak/09_device_info.py <device_address>
#           or: from 09_device_info import read_device_info
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Device Information Service reader using standard BLE DIS (0x180A).

The Device Information Service exposes manufacturer name, model number,
serial number, firmware/hardware revisions, and other identification
data as UTF-8 string characteristics.

Not all devices expose this service, and those that do may only populate
a subset of the characteristics. ``read_device_info`` returns ``None``
when the service is absent; individual fields are ``None`` when the
characteristic is missing or unreadable.

Multi-Sensor Identification
---------------------------
On macOS/iOS, Apple replaces BLE MAC addresses with rotating UUIDs,
making persistent device identification impossible via address alone.
The ``device_identifier`` function provides a stable identifier by
preferring the serial number (unique per device) with a fallback to
a manufacturer + model composite.

Platform Notes
--------------
- **Polar H10**: exposes all 5 DIS characteristics (serial, model,
  firmware, hardware, manufacturer).
- **Some cheap sensors**: may expose DIS with only manufacturer name.
- **Muse headbands**: expose DIS with serial and firmware revision.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass

from bleak import BleakClient

# ─── Constants ───────────────────────────────────────

DIS_SERVICE_UUID: str = "0000180a-0000-1000-8000-00805f9b34fb"
"""Standard Device Information Service UUID (0x180A)."""

SERIAL_NUMBER_UUID: str = "00002a25-0000-1000-8000-00805f9b34fb"
"""Serial Number String characteristic UUID (0x2A25)."""

MODEL_NUMBER_UUID: str = "00002a24-0000-1000-8000-00805f9b34fb"
"""Model Number String characteristic UUID (0x2A24)."""

FIRMWARE_REV_UUID: str = "00002a26-0000-1000-8000-00805f9b34fb"
"""Firmware Revision String characteristic UUID (0x2A26)."""

HARDWARE_REV_UUID: str = "00002a27-0000-1000-8000-00805f9b34fb"
"""Hardware Revision String characteristic UUID (0x2A27)."""

MANUFACTURER_NAME_UUID: str = "00002a29-0000-1000-8000-00805f9b34fb"
"""Manufacturer Name String characteristic UUID (0x2A29)."""


# ─── Data structures ────────────────────────────────


@dataclass
class DeviceInfo:
    """
    Container for Device Information Service fields.

    Each field is ``None`` when the corresponding characteristic
    is absent or could not be read from the device.

    Attributes
    ----------
    serial_number : str | None
        Unique serial number (e.g. ``"A1B2C3D4"``).
    model_number : str | None
        Model identifier (e.g. ``"H10"``).
    firmware_revision : str | None
        Firmware version string (e.g. ``"5.0.0"``).
    hardware_revision : str | None
        Hardware version string (e.g. ``"1.0.0"``).
    manufacturer_name : str | None
        Manufacturer name (e.g. ``"Polar Electro Oy"``).
    """

    serial_number: str | None = None
    model_number: str | None = None
    firmware_revision: str | None = None
    hardware_revision: str | None = None
    manufacturer_name: str | None = None


# ─── Core functions ──────────────────────────────────


def _has_dis_service(client: BleakClient) -> bool:
    """Check if the connected device exposes the Device Information Service."""
    if client.services is None:
        return False
    for service in client.services:
        if service.uuid.lower() == DIS_SERVICE_UUID:
            return True
    return False


async def _read_optional_string(
    client: BleakClient,
    char_uuid: str,
) -> str | None:
    """
    Read a DIS characteristic as a UTF-8 string.

    Returns ``None`` if the characteristic is missing or the read fails.
    Some devices list a DIS characteristic but raise an error when
    reading it (e.g. permission issues, unsupported on the platform).

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.
    char_uuid : str
        The characteristic UUID to read.

    Returns
    -------
    str | None
        The decoded UTF-8 string, or ``None`` on failure.
    """
    try:
        data = await client.read_gatt_char(char_uuid)
        return data.decode("utf-8").strip()
    except Exception:  # noqa: BLE001
        return None


async def read_device_info(client: BleakClient) -> DeviceInfo | None:
    """
    Read all Device Information Service fields from a connected device.

    Returns ``None`` if the device does not expose the DIS (0x180A).
    Individual fields are ``None`` when the characteristic is absent
    or could not be read.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.

    Returns
    -------
    DeviceInfo | None
        Populated ``DeviceInfo``, or ``None`` if DIS is not available.
    """
    if not _has_dis_service(client):
        return None

    return DeviceInfo(
        serial_number=await _read_optional_string(client, SERIAL_NUMBER_UUID),
        model_number=await _read_optional_string(client, MODEL_NUMBER_UUID),
        firmware_revision=await _read_optional_string(client, FIRMWARE_REV_UUID),
        hardware_revision=await _read_optional_string(client, HARDWARE_REV_UUID),
        manufacturer_name=await _read_optional_string(client, MANUFACTURER_NAME_UUID),
    )


async def device_identifier(client: BleakClient) -> str | None:
    """
    Return the best available unique identifier for the connected device.

    Strategy:
    1. **Serial number** — unique per device, preferred.
    2. **Manufacturer + Model** composite — less unique but still useful
       for distinguishing device types in a multi-sensor setup.
    3. ``None`` — DIS absent or no usable fields.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.

    Returns
    -------
    str | None
        A stable identifier string, or ``None`` if no identification
        data is available.
    """
    info = await read_device_info(client)
    if info is None:
        return None

    if info.serial_number:
        return info.serial_number

    parts = [p for p in (info.manufacturer_name, info.model_number) if p]
    if parts:
        return " ".join(parts)

    return None


# ─── Entrypoint ──────────────────────────────────────

if __name__ == "__main__":
    import sys

    if len(sys.argv) < 2:
        print("Usage: python 09_device_info.py <device_address>")
        sys.exit(1)

    address = sys.argv[1]

    async def main() -> None:
        async with BleakClient(address) as client:
            print(f"Connected to {address}")

            info = await read_device_info(client)
            if info is None:
                print("Device Information Service not available.")
                return

            print(f"  Manufacturer: {info.manufacturer_name or '[unknown]'}")
            print(f"  Model:        {info.model_number or '[unknown]'}")
            print(f"  Serial:       {info.serial_number or '[unknown]'}")
            print(f"  Firmware:     {info.firmware_revision or '[unknown]'}")
            print(f"  Hardware:     {info.hardware_revision or '[unknown]'}")

            ident = await device_identifier(client)
            print(f"  Identifier:   {ident or '[none]'}")

    asyncio.run(main())
