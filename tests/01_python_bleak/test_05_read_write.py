# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 05 — Read & Write (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 05
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for GATT read/write with mocked BleakClient.
#
# Usage   : pytest tests/01_python_bleak/test_05_read_write.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 05_read_write.py — GATT read and write operations.

All tests mock BleakClient methods — no BLE hardware required.
"""

from __future__ import annotations

import importlib
import sys
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pytest

# ─── Module import ───────────────────────────────────

_MODULE_DIR = Path(__file__).resolve().parent.parent.parent / "01_python_bleak"
sys.path.insert(0, str(_MODULE_DIR))

_mod = importlib.import_module("05_read_write")
read_characteristic = _mod.read_characteristic
write_characteristic = _mod.write_characteristic
read_as_int = _mod.read_as_int
read_as_string = _mod.read_as_string
write_int = _mod.write_int

CHAR_UUID = "00002a19-0000-1000-8000-00805f9b34fb"


# ─── Helpers ─────────────────────────────────────────


def _make_char(uuid: str = CHAR_UUID, properties: list[str] | None = None):
    """Create a mock BleakGATTCharacteristic."""
    char = MagicMock()
    char.uuid = uuid
    char.properties = properties or ["read", "write"]
    return char


def _make_client(char_properties: list[str] | None = None):
    """Create a mock BleakClient with one characteristic."""
    char = _make_char(properties=char_properties or ["read", "write"])
    client = MagicMock()
    client.services.get_characteristic.return_value = char
    client.read_gatt_char = AsyncMock(return_value=bytearray(b"\x42"))
    client.write_gatt_char = AsyncMock()
    return client


# ─── Tests — Read ────────────────────────────────────


@pytest.mark.asyncio
async def test_read_returns_bytearray():
    """read_characteristic must return a bytearray."""
    client = _make_client(["read"])
    client.read_gatt_char = AsyncMock(return_value=bytearray(b"\xAB\xCD"))

    result = await read_characteristic(client, CHAR_UUID)

    assert isinstance(result, bytearray)
    assert result == bytearray(b"\xAB\xCD")


@pytest.mark.asyncio
async def test_read_as_int_little_endian():
    """read_as_int with little-endian must decode correctly."""
    client = _make_client(["read"])
    # 0x0100 in little-endian = 256
    client.read_gatt_char = AsyncMock(return_value=bytearray(b"\x00\x01"))

    result = await read_as_int(client, CHAR_UUID, byteorder="little")

    assert result == 256


@pytest.mark.asyncio
async def test_read_as_int_big_endian():
    """read_as_int with big-endian must decode correctly."""
    client = _make_client(["read"])
    # 0x0001 in big-endian = 1
    client.read_gatt_char = AsyncMock(return_value=bytearray(b"\x00\x01"))

    result = await read_as_int(client, CHAR_UUID, byteorder="big")

    assert result == 1


@pytest.mark.asyncio
async def test_read_as_string_utf8():
    """read_as_string must decode UTF-8 bytes to string."""
    client = _make_client(["read"])
    client.read_gatt_char = AsyncMock(return_value=bytearray(b"Hello"))

    result = await read_as_string(client, CHAR_UUID)

    assert result == "Hello"


# ─── Tests — Write ───────────────────────────────────


@pytest.mark.asyncio
async def test_write_with_response():
    """write_characteristic with response=True must pass response=True."""
    client = _make_client(["read", "write"])

    await write_characteristic(client, CHAR_UUID, b"\x01\x02", response=True)

    client.write_gatt_char.assert_awaited_once_with(
        CHAR_UUID, b"\x01\x02", response=True
    )


@pytest.mark.asyncio
async def test_write_without_response():
    """write_characteristic with response=False must pass response=False."""
    client = _make_client(["read", "write-without-response"])

    await write_characteristic(client, CHAR_UUID, b"\xFF", response=False)

    client.write_gatt_char.assert_awaited_once_with(
        CHAR_UUID, b"\xFF", response=False
    )


@pytest.mark.asyncio
async def test_write_int_encoding():
    """write_int must encode the integer and call write_gatt_char."""
    client = _make_client(["read", "write"])

    await write_int(client, CHAR_UUID, value=300, length=2, byteorder="little")

    # 300 = 0x012C → little-endian bytes: 0x2C, 0x01
    expected_data = (300).to_bytes(2, byteorder="little")
    client.write_gatt_char.assert_awaited_once_with(
        CHAR_UUID, expected_data, response=True
    )


# ─── Tests — Property validation ────────────────────


@pytest.mark.asyncio
async def test_read_raises_on_non_readable_char():
    """read_characteristic must raise ValueError on a non-readable char."""
    client = _make_client(["write"])  # no "read"

    with pytest.raises(ValueError, match="does not support 'read'"):
        await read_characteristic(client, CHAR_UUID)


@pytest.mark.asyncio
async def test_write_raises_on_non_writable_char():
    """write_characteristic must raise ValueError on a non-writable char."""
    client = _make_client(["read"])  # no "write"

    with pytest.raises(ValueError, match="does not support 'write'"):
        await write_characteristic(client, CHAR_UUID, b"\x00", response=True)
