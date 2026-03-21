# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 08 — Battery Level
# Platform: Python / Bleak
# Sprint  : Sprint 08
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Read battery level from a connected device using the
#           standard Battery Service (0x180F). Handle devices that
#           do not expose this service gracefully.
#
# Usage   : python 01_python_bleak/08_battery.py <device_address>
#           or: from 08_battery import read_battery_level
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Battery level reader using the standard BLE Battery Service.

The Battery Service (UUID ``0x180F``) exposes a single characteristic,
Battery Level (UUID ``0x2A19``), which returns a ``uint8`` value
representing battery percentage (0–100).

Not all BLE devices expose this service. When absent,
``read_battery_level`` returns ``None`` rather than raising.

Platform Notes
--------------
- Some devices (e.g. Polar H10) update battery level infrequently.
  The value may not reflect real-time battery state.
- Notification support on Battery Level is optional per the Bluetooth
  spec. Some devices support it, others only allow polling via read.
"""

from __future__ import annotations

import asyncio
from collections.abc import Callable

from bleak import BleakClient

# ─── Constants ───────────────────────────────────────

BATTERY_SERVICE_UUID: str = "0000180f-0000-1000-8000-00805f9b34fb"
"""Standard Battery Service UUID (0x180F)."""

BATTERY_CHAR_UUID: str = "00002a19-0000-1000-8000-00805f9b34fb"
"""Standard Battery Level Characteristic UUID (0x2A19)."""

BATTERY_MIN: int = 0
"""Minimum valid battery percentage."""

BATTERY_MAX: int = 100
"""Maximum valid battery percentage."""


# ─── Core functions ──────────────────────────────────


def _has_battery_service(client: BleakClient) -> bool:
    """Check if the connected device exposes the Battery Service."""
    if client.services is None:
        return False
    for service in client.services:
        if service.uuid.lower() == BATTERY_SERVICE_UUID:
            return True
    return False


def _clamp_battery(value: int) -> int:
    """Clamp a battery value to the valid 0–100 range."""
    return max(BATTERY_MIN, min(BATTERY_MAX, value))


async def read_battery_level(client: BleakClient) -> int | None:
    """
    Read the battery level from a connected BLE device.

    Uses the standard Battery Service (0x180F) and Battery Level
    characteristic (0x2A19). Returns ``None`` if the device does
    not expose the Battery Service.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.

    Returns
    -------
    int | None
        Battery percentage (0–100), or ``None`` if the Battery
        Service is not available on this device.
    """
    if not _has_battery_service(client):
        return None

    data = await client.read_gatt_char(BATTERY_CHAR_UUID)
    raw_value = int.from_bytes(data, byteorder="little")
    return _clamp_battery(raw_value)


async def subscribe_battery_notifications(
    client: BleakClient,
    callback: Callable[[int], None],
) -> None:
    """
    Subscribe to battery level change notifications.

    The ``callback`` receives the battery percentage as an ``int``
    (0–100) each time the device reports a change.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.
    callback : Callable[[int], None]
        Function called with each battery level update.

    Raises
    ------
    ValueError
        If the device does not expose the Battery Service.
    """
    if not _has_battery_service(client):
        raise ValueError(
            "Device does not expose the Battery Service (0x180F). "
            "Cannot subscribe to battery notifications."
        )

    def _on_notify(sender: int, data: bytearray) -> None:
        raw_value = int.from_bytes(data, byteorder="little")
        callback(_clamp_battery(raw_value))

    await client.start_notify(BATTERY_CHAR_UUID, _on_notify)


# ─── Entrypoint ──────────────────────────────────────

if __name__ == "__main__":
    import sys

    if len(sys.argv) < 2:
        print("Usage: python 08_battery.py <device_address>")
        sys.exit(1)

    address = sys.argv[1]

    async def main() -> None:
        async with BleakClient(address) as client:
            print(f"Connected to {address}")
            level = await read_battery_level(client)
            if level is not None:
                print(f"Battery: {level}%")
            else:
                print("Battery Service not available on this device.")

    asyncio.run(main())
