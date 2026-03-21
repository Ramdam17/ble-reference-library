# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 04 — Services & Characteristics Discovery
# Platform: Python / Bleak
# Sprint  : Sprint 04
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Enumerate all GATT services and characteristics of a
#           connected BLE device. Display UUID, handle, properties,
#           and descriptors.
#
# Usage   : python 01_python_bleak/04_services_characteristics.py <addr>
#           or: from 04_services_characteristics import list_services
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
GATT services and characteristics discovery using Bleak.

After connecting to a BLE peripheral, ``client.services`` is automatically
populated with the full GATT table. This module provides helpers to
enumerate, filter, and display services and characteristics.

Platform Notes
--------------
- ``client.services`` is populated during ``connect()``. Accessing it
  before connection raises or returns ``None`` depending on platform.
- Handle values are platform-dependent integers — do not hardcode them.
"""

from __future__ import annotations

import asyncio

from bleak import BleakClient
from bleak.backends.characteristic import BleakGATTCharacteristic
from bleak.backends.service import BleakGATTService

# ─── Constants ───────────────────────────────────────

PROPERTY_KEYS: list[str] = [
    "read",
    "write",
    "write-without-response",
    "notify",
    "indicate",
]
"""Standard GATT characteristic property names as reported by Bleak."""


# ─── Core functions ──────────────────────────────────


def list_services(client: BleakClient) -> list[BleakGATTService]:
    """
    Return all GATT services exposed by the connected device.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient. ``client.services`` must be populated
        (i.e. ``connect()`` must have been called).

    Returns
    -------
    list[BleakGATTService]
        All services discovered on the device.

    Raises
    ------
    RuntimeError
        If ``client.services`` is not available (device not connected).
    """
    if client.services is None:
        raise RuntimeError(
            "No services available — is the client connected? "
            "Call client.connect() first."
        )
    return list(client.services)


def list_characteristics(
    client: BleakClient,
    service_uuid: str | None = None,
) -> list[BleakGATTCharacteristic]:
    """
    Return characteristics, optionally filtered by service UUID.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.
    service_uuid : str | None
        If provided, only return characteristics belonging to the
        service with this UUID. If ``None``, return all characteristics
        across all services.

    Returns
    -------
    list[BleakGATTCharacteristic]
        Matching characteristics.

    Raises
    ------
    RuntimeError
        If ``client.services`` is not available.
    """
    if client.services is None:
        raise RuntimeError(
            "No services available — is the client connected? "
            "Call client.connect() first."
        )
    if service_uuid is not None:
        target_uuid = service_uuid.lower()
        for service in client.services:
            if service.uuid.lower() == target_uuid:
                return list(service.characteristics)
        return []
    return [
        char
        for service in client.services
        for char in service.characteristics
    ]


def characteristic_properties(
    char: BleakGATTCharacteristic,
) -> dict[str, bool]:
    """
    Return a dict of standard GATT properties for a characteristic.

    Parameters
    ----------
    char : BleakGATTCharacteristic
        A discovered characteristic.

    Returns
    -------
    dict[str, bool]
        Keys: ``read``, ``write``, ``write-without-response``,
        ``notify``, ``indicate``. Values: whether the characteristic
        supports that property.

    Example
    -------
    >>> props = characteristic_properties(heart_rate_char)
    >>> props["notify"]
    True
    """
    props = char.properties  # list[str] of property names
    return {key: key in props for key in PROPERTY_KEYS}


def print_service_tree(client: BleakClient) -> None:
    """
    Print a human-readable tree of all services and characteristics.

    Output format::

        Service: 0000180d-... (Heart Rate)
          ├─ 00002a37-... | handle=14 | notify
          └─ 00002a38-... | handle=17 | read

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.
    """
    services = list_services(client)
    for service in services:
        print(f"Service: {service.uuid}  ({service.description or 'Unknown'})")
        chars = list(service.characteristics)
        for i, char in enumerate(chars):
            is_last = i == len(chars) - 1
            prefix = "└─" if is_last else "├─"
            props_str = ", ".join(char.properties)
            print(f"  {prefix} {char.uuid} | handle={char.handle} | {props_str}")


# ─── Entrypoint ──────────────────────────────────────

if __name__ == "__main__":
    import sys

    if len(sys.argv) < 2:
        print("Usage: python 04_services_characteristics.py <device_address>")
        sys.exit(1)

    address = sys.argv[1]

    async def main() -> None:
        async with BleakClient(address) as client:
            print(f"Connected to {address}\n")
            print_service_tree(client)

    asyncio.run(main())
