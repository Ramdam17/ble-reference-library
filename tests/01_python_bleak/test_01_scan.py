# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 01 — Passive Scan (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 01
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for passive BLE scan with mocked BleakScanner.
#
# Usage   : pytest tests/01_python_bleak/test_01_scan.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 01_scan.py — passive BLE scan.

All tests mock ``BleakScanner.discover()`` so no BLE hardware is required.
"""

from __future__ import annotations

import sys
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# Allow importing from 01_python_bleak/ (module name starts with a digit)
_MODULE_DIR = Path(__file__).resolve().parent.parent.parent / "01_python_bleak"
sys.path.insert(0, str(_MODULE_DIR))

# The module filename starts with a digit, so we use importlib
import importlib

_scan_module = importlib.import_module("01_scan")
scan = _scan_module.scan
scan_and_print = _scan_module.scan_and_print
format_device = _scan_module.format_device
UNKNOWN_DEVICE_NAME = _scan_module.UNKNOWN_DEVICE_NAME
DEFAULT_SCAN_DURATION = _scan_module.DEFAULT_SCAN_DURATION


# ─── Helpers ─────────────────────────────────────────


def _make_device(
    address: str = "AA:BB:CC:DD:EE:FF",
    name: str | None = "TestDevice",
    rssi: int = -60,
):
    """Create a mock BLEDevice with the given attributes."""
    device = type("BLEDevice", (), {})()
    device.address = address
    device.name = name
    device.rssi = rssi
    return device


# ─── Tests ───────────────────────────────────────────

# patch.object on the imported module — avoids pkgutil.resolve_name()
# which rejects module names starting with a digit like "01_scan".


@pytest.mark.asyncio
async def test_scan_returns_list():
    """scan() must return a list of BLEDevice objects."""
    mock_devices = [_make_device(), _make_device(address="11:22:33:44:55:66")]
    mock_discover = AsyncMock(return_value=mock_devices)

    with patch.object(_scan_module, "BleakScanner") as mock_scanner_cls:
        mock_scanner_cls.discover = mock_discover
        result = await scan()

    assert isinstance(result, list)
    assert len(result) == 2
    assert result == mock_devices


@pytest.mark.asyncio
async def test_scan_empty_result():
    """scan() must handle empty results without raising."""
    mock_discover = AsyncMock(return_value=[])

    with patch.object(_scan_module, "BleakScanner") as mock_scanner_cls:
        mock_scanner_cls.discover = mock_discover
        result = await scan()

    assert result == []


@pytest.mark.asyncio
async def test_scan_duration_passed():
    """scan() must forward the duration as the timeout argument."""
    mock_discover = AsyncMock(return_value=[])

    with patch.object(_scan_module, "BleakScanner") as mock_scanner_cls:
        mock_scanner_cls.discover = mock_discover
        await scan(duration=10.0)

    mock_discover.assert_awaited_once_with(timeout=10.0)


def test_scan_unknown_name_handled():
    """A device with name=None must be formatted as '[unknown]'."""
    device = _make_device(name=None)

    formatted = format_device(device)

    assert UNKNOWN_DEVICE_NAME in formatted
    assert device.address in formatted
