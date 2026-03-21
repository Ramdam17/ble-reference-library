# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 08 — Battery Level (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 08
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for battery level reader with mocked BleakClient.
#
# Usage   : pytest tests/01_python_bleak/test_08_battery.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 08_battery.py — battery level reader.

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

_mod = importlib.import_module("08_battery")
read_battery_level = _mod.read_battery_level
subscribe_battery_notifications = _mod.subscribe_battery_notifications
BATTERY_SERVICE_UUID = _mod.BATTERY_SERVICE_UUID
BATTERY_CHAR_UUID = _mod.BATTERY_CHAR_UUID


# ─── Helpers ─────────────────────────────────────────


def _make_service(uuid: str):
    """Create a mock BleakGATTService."""
    service = MagicMock()
    service.uuid = uuid
    return service


def _make_client(has_battery: bool = True, battery_value: int = 72):
    """Create a mock BleakClient with optional Battery Service."""
    client = MagicMock()
    services = []
    if has_battery:
        services.append(_make_service(BATTERY_SERVICE_UUID))
    client.services = services
    client.read_gatt_char = AsyncMock(
        return_value=bytearray(battery_value.to_bytes(1, "little"))
    )
    client.start_notify = AsyncMock()
    return client


# ─── Tests ───────────────────────────────────────────


@pytest.mark.asyncio
async def test_read_battery_returns_int():
    """read_battery_level must return an int when Battery Service is present."""
    client = _make_client(has_battery=True, battery_value=85)

    result = await read_battery_level(client)

    assert result == 85
    client.read_gatt_char.assert_awaited_once_with(BATTERY_CHAR_UUID)


@pytest.mark.asyncio
async def test_read_battery_service_not_found_returns_none():
    """read_battery_level must return None when Battery Service is absent."""
    client = _make_client(has_battery=False)

    result = await read_battery_level(client)

    assert result is None
    client.read_gatt_char.assert_not_awaited()


@pytest.mark.asyncio
async def test_subscribe_battery_callback_invoked():
    """subscribe_battery_notifications must wrap raw bytes to int callback."""
    client = _make_client(has_battery=True)
    received = []

    await subscribe_battery_notifications(client, lambda level: received.append(level))

    # Extract the wrapped callback passed to start_notify
    wrapped = client.start_notify.call_args[0][1]

    # Simulate notification: battery at 63%
    wrapped(20, bytearray(b"\x3F"))  # 0x3F = 63

    assert received == [63]


@pytest.mark.asyncio
async def test_battery_value_clamped_0_100():
    """Battery values must be clamped to the 0–100 range."""
    # Value > 100 (corrupted data)
    client = _make_client(has_battery=True, battery_value=120)
    result = await read_battery_level(client)
    assert result == 100

    # Value = 0 (empty battery)
    client2 = _make_client(has_battery=True, battery_value=0)
    result2 = await read_battery_level(client2)
    assert result2 == 0
