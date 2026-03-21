# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 09 — Device Information Service (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 09
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for Device Information Service reader
#           with mocked BleakClient.
#
# Usage   : pytest tests/01_python_bleak/test_09_device_info.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 09_device_info.py — Device Information Service reader.

All tests mock BleakClient — no BLE hardware required.
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

_mod = importlib.import_module("09_device_info")
read_device_info = _mod.read_device_info
device_identifier = _mod.device_identifier
DeviceInfo = _mod.DeviceInfo
DIS_SERVICE_UUID = _mod.DIS_SERVICE_UUID
SERIAL_NUMBER_UUID = _mod.SERIAL_NUMBER_UUID
MODEL_NUMBER_UUID = _mod.MODEL_NUMBER_UUID
FIRMWARE_REV_UUID = _mod.FIRMWARE_REV_UUID
HARDWARE_REV_UUID = _mod.HARDWARE_REV_UUID
MANUFACTURER_NAME_UUID = _mod.MANUFACTURER_NAME_UUID


# ─── Helpers ─────────────────────────────────────────

# Map of UUID → value for building mock responses
_FULL_CHARACTERISTICS: dict[str, str] = {
    SERIAL_NUMBER_UUID: "A1B2C3D4",
    MODEL_NUMBER_UUID: "H10",
    FIRMWARE_REV_UUID: "5.0.0",
    HARDWARE_REV_UUID: "1.0.0",
    MANUFACTURER_NAME_UUID: "Polar Electro Oy",
}


def _make_service(uuid: str):
    """Create a mock BleakGATTService."""
    service = MagicMock()
    service.uuid = uuid
    return service


def _make_client(
    has_dis: bool = True,
    characteristics: dict[str, str] | None = None,
    failing_uuids: set[str] | None = None,
):
    """
    Create a mock BleakClient with optional DIS.

    Parameters
    ----------
    has_dis : bool
        Whether the client exposes the DIS service.
    characteristics : dict[str, str] | None
        UUID → string value mapping for readable characteristics.
        Defaults to all 5 DIS characteristics populated.
    failing_uuids : set[str] | None
        UUIDs that raise an exception when read.
    """
    if characteristics is None:
        characteristics = _FULL_CHARACTERISTICS

    if failing_uuids is None:
        failing_uuids = set()

    client = MagicMock()
    services = []
    if has_dis:
        services.append(_make_service(DIS_SERVICE_UUID))
    client.services = services

    async def _read_gatt_char(uuid: str) -> bytearray:
        if uuid in failing_uuids:
            raise Exception("Read failed")  # noqa: TRY002
        if uuid in characteristics:
            return bytearray(characteristics[uuid].encode("utf-8"))
        raise Exception("Characteristic not found")  # noqa: TRY002

    client.read_gatt_char = AsyncMock(side_effect=_read_gatt_char)
    return client


# ─── Tests ───────────────────────────────────────────


@pytest.mark.asyncio
async def test_read_device_info_all_fields():
    """read_device_info must populate all fields when all characteristics exist."""
    client = _make_client(has_dis=True)

    info = await read_device_info(client)

    assert info is not None
    assert info.serial_number == "A1B2C3D4"
    assert info.model_number == "H10"
    assert info.firmware_revision == "5.0.0"
    assert info.hardware_revision == "1.0.0"
    assert info.manufacturer_name == "Polar Electro Oy"


@pytest.mark.asyncio
async def test_read_device_info_partial_fields():
    """read_device_info must return None for missing characteristics."""
    client = _make_client(
        has_dis=True,
        characteristics={
            SERIAL_NUMBER_UUID: "XY1234",
            MANUFACTURER_NAME_UUID: "Garmin",
        },
    )

    info = await read_device_info(client)

    assert info is not None
    assert info.serial_number == "XY1234"
    assert info.manufacturer_name == "Garmin"
    assert info.model_number is None
    assert info.firmware_revision is None
    assert info.hardware_revision is None


@pytest.mark.asyncio
async def test_read_device_info_service_absent():
    """read_device_info must return None when DIS is not exposed."""
    client = _make_client(has_dis=False)

    info = await read_device_info(client)

    assert info is None
    client.read_gatt_char.assert_not_awaited()


@pytest.mark.asyncio
async def test_device_identifier_serial_preferred():
    """device_identifier must prefer serial number when available."""
    client = _make_client(has_dis=True)

    ident = await device_identifier(client)

    assert ident == "A1B2C3D4"


@pytest.mark.asyncio
async def test_device_identifier_fallback_composite():
    """device_identifier must fall back to manufacturer + model when no serial."""
    client = _make_client(
        has_dis=True,
        characteristics={
            MANUFACTURER_NAME_UUID: "Polar Electro Oy",
            MODEL_NUMBER_UUID: "H10",
        },
    )

    ident = await device_identifier(client)

    assert ident == "Polar Electro Oy H10"


@pytest.mark.asyncio
async def test_device_identifier_no_dis():
    """device_identifier must return None when DIS is absent."""
    client = _make_client(has_dis=False)

    ident = await device_identifier(client)

    assert ident is None


@pytest.mark.asyncio
async def test_read_optional_string_handles_exception():
    """Characteristics that raise on read must result in None fields."""
    client = _make_client(
        has_dis=True,
        characteristics={
            MANUFACTURER_NAME_UUID: "Polar Electro Oy",
        },
        failing_uuids={SERIAL_NUMBER_UUID, MODEL_NUMBER_UUID},
    )

    info = await read_device_info(client)

    assert info is not None
    assert info.serial_number is None
    assert info.model_number is None
    assert info.manufacturer_name == "Polar Electro Oy"


@pytest.mark.asyncio
async def test_device_identifier_manufacturer_only():
    """device_identifier with only manufacturer must return manufacturer name."""
    client = _make_client(
        has_dis=True,
        characteristics={
            MANUFACTURER_NAME_UUID: "Muse",
        },
    )

    ident = await device_identifier(client)

    assert ident == "Muse"
