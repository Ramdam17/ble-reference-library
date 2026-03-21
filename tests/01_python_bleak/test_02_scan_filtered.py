# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 02 — Filtered Scan (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 02
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for filtered BLE scan with mocked BleakScanner.
#
# Usage   : pytest tests/01_python_bleak/test_02_scan_filtered.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 02_scan_filtered.py — filtered BLE scan.

All tests mock ``BleakScanner.discover()`` so no BLE hardware is required.
"""

from __future__ import annotations

import importlib
import sys
from pathlib import Path
from unittest.mock import AsyncMock, patch

import pytest

# ─── Module import (digit-prefixed filename) ─────────

_MODULE_DIR = Path(__file__).resolve().parent.parent.parent / "01_python_bleak"
sys.path.insert(0, str(_MODULE_DIR))

_mod = importlib.import_module("02_scan_filtered")
scan_by_name = _mod.scan_by_name
scan_by_service_uuid = _mod.scan_by_service_uuid
scan_by_rssi = _mod.scan_by_rssi
scan_with_advertisement_data = _mod.scan_with_advertisement_data
normalize_uuid = _mod.normalize_uuid


# ─── Helpers ─────────────────────────────────────────


def _make_device(
    address: str = "AA:BB:CC:DD:EE:FF",
    name: str | None = "TestDevice",
    rssi: int = -60,
):
    """Create a mock BLEDevice."""
    device = type("BLEDevice", (), {})()
    device.address = address
    device.name = name
    device.rssi = rssi
    return device


def _make_adv_data(
    rssi: int = -60,
    service_uuids: list[str] | None = None,
):
    """Create a mock AdvertisementData."""
    adv = type("AdvertisementData", (), {})()
    adv.rssi = rssi
    adv.service_uuids = service_uuids or []
    return adv


# ─── Tests — Name filtering ─────────────────────────


@pytest.mark.asyncio
async def test_filter_by_name_match():
    """'Muse' must match a device named 'Muse-1234'."""
    muse = _make_device(name="Muse-1234")
    other = _make_device(name="Polar H10", address="11:22:33:44:55:66")
    mock_discover = AsyncMock(return_value=[muse, other])

    with patch.object(_mod, "BleakScanner") as mock_cls:
        mock_cls.discover = mock_discover
        result = await scan_by_name("Muse")

    assert len(result) == 1
    assert result[0].name == "Muse-1234"


@pytest.mark.asyncio
async def test_filter_by_name_case_insensitive():
    """Name matching must be case-insensitive."""
    device = _make_device(name="Polar H10")
    mock_discover = AsyncMock(return_value=[device])

    with patch.object(_mod, "BleakScanner") as mock_cls:
        mock_cls.discover = mock_discover
        result = await scan_by_name("polar")

    assert len(result) == 1


@pytest.mark.asyncio
async def test_filter_by_name_no_match():
    """No matching name returns an empty list."""
    device = _make_device(name="Polar H10")
    mock_discover = AsyncMock(return_value=[device])

    with patch.object(_mod, "BleakScanner") as mock_cls:
        mock_cls.discover = mock_discover
        result = await scan_by_name("Garmin")

    assert result == []


# ─── Tests — Service UUID filtering ─────────────────


@pytest.mark.asyncio
async def test_filter_by_service_uuid():
    """scan_by_service_uuid must forward the normalized UUID to BleakScanner."""
    device = _make_device(name="HR Sensor")
    mock_discover = AsyncMock(return_value=[device])

    with patch.object(_mod, "BleakScanner") as mock_cls:
        mock_cls.discover = mock_discover
        result = await scan_by_service_uuid("0x180D")

    assert result == [device]
    # Verify the UUID was normalized and forwarded
    mock_discover.assert_awaited_once_with(
        timeout=5.0,
        service_uuids=["0000180d-0000-1000-8000-00805f9b34fb"],
    )


# ─── Tests — RSSI filtering ─────────────────────────


@pytest.mark.asyncio
async def test_filter_by_rssi_threshold():
    """Device with RSSI=-90 must be excluded when min_rssi=-80."""
    strong = _make_device(name="Close", address="AA:BB:CC:DD:EE:01")
    weak = _make_device(name="Far", address="AA:BB:CC:DD:EE:02")
    adv_strong = _make_adv_data(rssi=-60)
    adv_weak = _make_adv_data(rssi=-90)

    # return_adv=True returns dict[str, tuple[BLEDevice, AdvertisementData]]
    mock_discover = AsyncMock(return_value={
        strong.address: (strong, adv_strong),
        weak.address: (weak, adv_weak),
    })

    with patch.object(_mod, "BleakScanner") as mock_cls:
        mock_cls.discover = mock_discover
        result = await scan_by_rssi(min_rssi=-80)

    assert len(result) == 1
    assert result[0].name == "Close"


# ─── Tests — Advertisement data ──────────────────────


@pytest.mark.asyncio
async def test_advertisement_data_returned():
    """scan_with_advertisement_data must return (device, adv) tuples."""
    device = _make_device(name="Muse-S")
    adv = _make_adv_data(rssi=-55, service_uuids=["0000180d-0000-1000-8000-00805f9b34fb"])

    mock_discover = AsyncMock(return_value={
        device.address: (device, adv),
    })

    with patch.object(_mod, "BleakScanner") as mock_cls:
        mock_cls.discover = mock_discover
        result = await scan_with_advertisement_data()

    assert len(result) == 1
    returned_device, returned_adv = result[0]
    assert returned_device.name == "Muse-S"
    assert returned_adv.rssi == -55
    assert "0000180d-0000-1000-8000-00805f9b34fb" in returned_adv.service_uuids


# ─── Tests — UUID normalization (pure function) ─────


class TestNormalizeUuid:
    """UUID normalization must handle all Bluetooth UUID formats."""

    def test_short_hex_with_prefix(self):
        assert normalize_uuid("0x180F") == "0000180f-0000-1000-8000-00805f9b34fb"

    def test_short_hex_without_prefix(self):
        assert normalize_uuid("180F") == "0000180f-0000-1000-8000-00805f9b34fb"

    def test_short_hex_lowercase(self):
        assert normalize_uuid("180f") == "0000180f-0000-1000-8000-00805f9b34fb"

    def test_full_uuid_passthrough(self):
        full = "0000180f-0000-1000-8000-00805f9b34fb"
        assert normalize_uuid(full) == full

    def test_full_uuid_uppercase_normalized(self):
        upper = "0000180F-0000-1000-8000-00805F9B34FB"
        assert normalize_uuid(upper) == "0000180f-0000-1000-8000-00805f9b34fb"
