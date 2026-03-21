# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 05 — Read & Write
# Platform: Python / Bleak
# Sprint  : Sprint 05
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Read and write GATT characteristics with encoding/decoding
#           helpers for int and string values.
#
# Usage   : python 01_python_bleak/05_read_write.py <addr> <char_uuid>
#           or: from 05_read_write import read_characteristic
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
GATT characteristic read and write operations using Bleak.

Provides raw byte-level read/write plus convenience helpers for
common data types (integers, strings). All functions validate
characteristic properties before attempting the operation.

Platform Notes
--------------
- BLE uses **little-endian** byte order by default (Bluetooth Core Spec
  Vol 3, Part G, §3.1). The helpers default to ``byteorder="little"``.
- ``write_without_response`` (a.k.a. Write Command) is faster but
  unreliable — no ACK from the peripheral. Use ``response=True``
  (Write Request) when data integrity matters.
"""

from __future__ import annotations

import asyncio

from bleak import BleakClient

# ─── Helpers ─────────────────────────────────────────


def _get_characteristic(client: BleakClient, uuid: str):
    """
    Look up a characteristic by UUID and return it.

    Raises
    ------
    ValueError
        If the characteristic is not found on the device.
    """
    char = client.services.get_characteristic(uuid)
    if char is None:
        raise ValueError(
            f"Characteristic {uuid} not found on device. "
            f"Did you connect and discover services?"
        )
    return char


def _assert_readable(client: BleakClient, uuid: str) -> None:
    """Raise ValueError if characteristic does not support read."""
    char = _get_characteristic(client, uuid)
    if "read" not in char.properties:
        raise ValueError(
            f"Characteristic {uuid} does not support 'read'. "
            f"Properties: {char.properties}"
        )


def _assert_writable(client: BleakClient, uuid: str, response: bool) -> None:
    """Raise ValueError if characteristic does not support the write mode."""
    char = _get_characteristic(client, uuid)
    if response and "write" not in char.properties:
        raise ValueError(
            f"Characteristic {uuid} does not support 'write' (with response). "
            f"Properties: {char.properties}"
        )
    if not response and "write-without-response" not in char.properties:
        raise ValueError(
            f"Characteristic {uuid} does not support 'write-without-response'. "
            f"Properties: {char.properties}"
        )


# ─── Core functions ──────────────────────────────────


async def read_characteristic(
    client: BleakClient,
    uuid: str,
) -> bytearray:
    """
    Read raw bytes from a GATT characteristic.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.
    uuid : str
        Characteristic UUID to read from.

    Returns
    -------
    bytearray
        Raw bytes read from the characteristic.

    Raises
    ------
    ValueError
        If the characteristic does not support ``read``.
    """
    _assert_readable(client, uuid)
    return await client.read_gatt_char(uuid)


async def write_characteristic(
    client: BleakClient,
    uuid: str,
    data: bytes,
    response: bool = True,
) -> None:
    """
    Write raw bytes to a GATT characteristic.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.
    uuid : str
        Characteristic UUID to write to.
    data : bytes
        Raw bytes to write.
    response : bool
        If ``True`` (default), use Write Request (with ACK).
        If ``False``, use Write Command (no ACK, faster).

    Raises
    ------
    ValueError
        If the characteristic does not support the requested write mode.
    """
    _assert_writable(client, uuid, response)
    await client.write_gatt_char(uuid, data, response=response)


async def read_as_int(
    client: BleakClient,
    uuid: str,
    byteorder: str = "little",
) -> int:
    """
    Read a characteristic and decode it as an integer.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.
    uuid : str
        Characteristic UUID to read from.
    byteorder : str
        Byte order: ``"little"`` (default, BLE standard) or ``"big"``.

    Returns
    -------
    int
        Decoded integer value.
    """
    data = await read_characteristic(client, uuid)
    return int.from_bytes(data, byteorder=byteorder)


async def read_as_string(
    client: BleakClient,
    uuid: str,
    encoding: str = "utf-8",
) -> str:
    """
    Read a characteristic and decode it as a string.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.
    uuid : str
        Characteristic UUID to read from.
    encoding : str
        Character encoding (default: ``"utf-8"``).

    Returns
    -------
    str
        Decoded string value.
    """
    data = await read_characteristic(client, uuid)
    return data.decode(encoding)


async def write_int(
    client: BleakClient,
    uuid: str,
    value: int,
    length: int = 1,
    byteorder: str = "little",
    response: bool = True,
) -> None:
    """
    Encode an integer and write it to a characteristic.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.
    uuid : str
        Characteristic UUID to write to.
    value : int
        Integer value to encode and write.
    length : int
        Number of bytes for the encoding (default: 1).
    byteorder : str
        Byte order: ``"little"`` (default) or ``"big"``.
    response : bool
        If ``True``, use Write Request; if ``False``, Write Command.
    """
    data = value.to_bytes(length, byteorder=byteorder)
    await write_characteristic(client, uuid, data, response=response)


# ─── Entrypoint ──────────────────────────────────────

if __name__ == "__main__":
    import sys

    if len(sys.argv) < 3:
        print("Usage: python 05_read_write.py <device_address> <char_uuid>")
        print("Example: python 05_read_write.py AA:BB:CC:DD:EE:FF 00002a19-...")
        sys.exit(1)

    address, char_uuid = sys.argv[1], sys.argv[2]

    async def main() -> None:
        async with BleakClient(address) as client:
            print(f"Connected to {address}")
            data = await read_characteristic(client, char_uuid)
            print(f"Raw: {data.hex()}")
            print(f"Int (little-endian): {int.from_bytes(data, 'little')}")
            print(f"String (utf-8): {data.decode('utf-8', errors='replace')}")

    asyncio.run(main())
