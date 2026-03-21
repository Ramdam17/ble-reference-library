# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 15 — Peripheral Mode (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 15
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for BLE peripheral mode data structures
#           and lifecycle — fully mocked, no BLE hardware required.
#
# Usage   : pytest tests/01_python_bleak/test_15_peripheral_mode.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 15_peripheral_mode.py — BLE peripheral mode.

All tests are pure — no BLE hardware or bless library required.
"""

from __future__ import annotations

import importlib
import sys
from pathlib import Path

import pytest

# ─── Module import ───────────────────────────────────

_MODULE_DIR = Path(__file__).resolve().parent.parent.parent / "01_python_bleak"
sys.path.insert(0, str(_MODULE_DIR))

_mod = importlib.import_module("15_peripheral_mode")
CharacteristicProperties = _mod.CharacteristicProperties
GattCharacteristic = _mod.GattCharacteristic
GattService = _mod.GattService
build_heart_rate_service = _mod.build_heart_rate_service
generate_custom_uuid = _mod.generate_custom_uuid
start_peripheral = _mod.start_peripheral
update_heart_rate = _mod.update_heart_rate
HEART_RATE_SERVICE_UUID = _mod.HEART_RATE_SERVICE_UUID
HEART_RATE_MEASUREMENT_UUID = _mod.HEART_RATE_MEASUREMENT_UUID


# ─── Tests ──────────────────────────────────────────


def test_characteristic_properties_flags():
    """CharacteristicProperties must support bitwise OR combination."""
    props = CharacteristicProperties.READ | CharacteristicProperties.NOTIFY
    assert CharacteristicProperties.READ in props
    assert CharacteristicProperties.NOTIFY in props
    assert CharacteristicProperties.WRITE not in props


def test_gatt_characteristic_update_value():
    """GattCharacteristic.update_value must replace the stored value."""
    char = GattCharacteristic(
        uuid="00002a37-0000-1000-8000-00805f9b34fb",
        properties=CharacteristicProperties.READ,
        value=b"\x00\x48",
    )
    assert char.value == b"\x00\x48"

    char.update_value(b"\x00\x90")
    assert char.value == b"\x00\x90"


def test_gatt_service_add_characteristic():
    """GattService.add_characteristic must append to the list."""
    service = GattService(uuid=HEART_RATE_SERVICE_UUID)
    assert len(service.characteristics) == 0

    char = GattCharacteristic(
        uuid=HEART_RATE_MEASUREMENT_UUID,
        properties=CharacteristicProperties.NOTIFY,
    )
    service.add_characteristic(char)
    assert len(service.characteristics) == 1
    assert service.characteristics[0].uuid == HEART_RATE_MEASUREMENT_UUID


def test_build_heart_rate_service():
    """build_heart_rate_service must create a valid HR service with correct initial BPM."""
    service = build_heart_rate_service(initial_bpm=85)
    assert service.uuid == HEART_RATE_SERVICE_UUID
    assert service.primary is True
    assert len(service.characteristics) == 1

    char = service.characteristics[0]
    assert char.uuid == HEART_RATE_MEASUREMENT_UUID
    assert CharacteristicProperties.NOTIFY in char.properties
    assert char.value == bytes([0x00, 85])


def test_generate_custom_uuid_uniqueness():
    """generate_custom_uuid must return unique UUIDs on each call."""
    uuids = {generate_custom_uuid() for _ in range(100)}
    assert len(uuids) == 100  # All unique


@pytest.mark.asyncio
async def test_start_peripheral_simulation():
    """start_peripheral must return True in simulation mode (no bless)."""
    service = build_heart_rate_service()
    result = await start_peripheral([service], duration_s=0.1)
    assert result is True


@pytest.mark.asyncio
async def test_update_heart_rate_valid():
    """update_heart_rate must update the characteristic value."""
    service = build_heart_rate_service(initial_bpm=60)
    await update_heart_rate(service, bpm=120)

    char = service.characteristics[0]
    assert char.value == bytes([0x00, 120])


@pytest.mark.asyncio
async def test_update_heart_rate_out_of_range():
    """update_heart_rate must raise ValueError for BPM outside 0–255."""
    service = build_heart_rate_service()

    with pytest.raises(ValueError, match="BPM must be 0–255"):
        await update_heart_rate(service, bpm=300)

    with pytest.raises(ValueError, match="BPM must be 0–255"):
        await update_heart_rate(service, bpm=-1)


@pytest.mark.asyncio
async def test_update_heart_rate_wrong_service():
    """update_heart_rate must raise ValueError if service has no HR characteristic."""
    service = GattService(uuid="00001800-0000-1000-8000-00805f9b34fb")

    with pytest.raises(ValueError, match="no Heart Rate Measurement"):
        await update_heart_rate(service, bpm=72)
