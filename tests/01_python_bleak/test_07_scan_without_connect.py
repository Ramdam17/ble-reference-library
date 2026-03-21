# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 07 — Scan Without Connection (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 07
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for advertisement data parsing.
#
# Usage   : pytest tests/01_python_bleak/test_07_scan_without_connect.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 07_scan_without_connect.py — advertisement data parsing.

All tests use mock AdvertisementData — no BLE hardware required.
"""

from __future__ import annotations

import importlib
import sys
from pathlib import Path
from unittest.mock import MagicMock

import pytest

# ─── Module import ───────────────────────────────────

_MODULE_DIR = Path(__file__).resolve().parent.parent.parent / "01_python_bleak"
sys.path.insert(0, str(_MODULE_DIR))

_mod = importlib.import_module("07_scan_without_connect")
parse_manufacturer_data = _mod.parse_manufacturer_data
parse_service_data = _mod.parse_service_data
parse_local_name = _mod.parse_local_name
parse_tx_power = _mod.parse_tx_power


# ─── Helpers ─────────────────────────────────────────


def _make_adv(
    manufacturer_data: dict[int, bytes] | None = None,
    service_data: dict[str, bytes] | None = None,
    local_name: str | None = None,
    tx_power: int | None = None,
):
    """Create a mock AdvertisementData."""
    adv = MagicMock()
    adv.manufacturer_data = manufacturer_data or {}
    adv.service_data = service_data or {}
    adv.local_name = local_name
    adv.tx_power = tx_power
    return adv


# ─── Tests ───────────────────────────────────────────


def test_manufacturer_data_parsed():
    """Manufacturer data must be returned as dict[int, bytes]."""
    # 0x006B = Polar, 0x004C = Apple
    adv = _make_adv(manufacturer_data={
        0x006B: b"\x01\x02\x03",
        0x004C: b"\xFF\xAA",
    })

    result = parse_manufacturer_data(adv)

    assert result == {0x006B: b"\x01\x02\x03", 0x004C: b"\xFF\xAA"}


def test_service_data_parsed():
    """Service data must be returned as dict[str, bytes] with lowercase UUIDs."""
    adv = _make_adv(service_data={
        "0000180D-0000-1000-8000-00805F9B34FB": b"\x10\x48",
    })

    result = parse_service_data(adv)

    assert "0000180d-0000-1000-8000-00805f9b34fb" in result
    assert result["0000180d-0000-1000-8000-00805f9b34fb"] == b"\x10\x48"


def test_local_name_present():
    """parse_local_name must return the name when present."""
    adv = _make_adv(local_name="Polar H10")

    assert parse_local_name(adv) == "Polar H10"


def test_local_name_absent_returns_none():
    """parse_local_name must return None when no name is advertised."""
    adv = _make_adv(local_name=None)

    assert parse_local_name(adv) is None


def test_tx_power_present():
    """parse_tx_power must return the TX power when present."""
    adv = _make_adv(tx_power=-12)

    assert parse_tx_power(adv) == -12


def test_tx_power_absent_returns_none():
    """parse_tx_power must return None when TX power is not advertised."""
    adv = _make_adv(tx_power=None)

    assert parse_tx_power(adv) is None


def test_empty_advertisement_no_exception():
    """All parsers must handle an empty advertisement without raising."""
    adv = _make_adv()

    assert parse_manufacturer_data(adv) == {}
    assert parse_service_data(adv) == {}
    assert parse_local_name(adv) is None
    assert parse_tx_power(adv) is None
