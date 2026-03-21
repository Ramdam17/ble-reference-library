# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 13 — MTU Negotiation (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 13
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for MTU negotiation utilities.
#
# Usage   : pytest tests/01_python_bleak/test_13_mtu_negotiation.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 13_mtu_negotiation.py — MTU utilities.

All tests are pure — no BLE hardware required.
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

_mod = importlib.import_module("13_mtu_negotiation")
effective_payload_size = _mod.effective_payload_size
request_mtu = _mod.request_mtu
DEFAULT_MTU = _mod.DEFAULT_MTU
ATT_HEADER_BYTES = _mod.ATT_HEADER_BYTES
MAX_BLE_MTU = _mod.MAX_BLE_MTU


# ─── Tests ──────────────────────────────────────────


def test_effective_payload_size():
    """effective_payload_size must subtract ATT overhead from MTU."""
    assert effective_payload_size(247) == 244
    assert effective_payload_size(512) == 509
    assert effective_payload_size(23) == 20


def test_default_mtu_value():
    """DEFAULT_MTU must be 23 per BLE specification."""
    assert DEFAULT_MTU == 23
    assert ATT_HEADER_BYTES == 3
    assert MAX_BLE_MTU == 517


def test_mtu_below_minimum_raises():
    """effective_payload_size must raise ValueError for MTU below 23."""
    with pytest.raises(ValueError, match="below the BLE minimum"):
        effective_payload_size(22)

    with pytest.raises(ValueError, match="below the BLE minimum"):
        effective_payload_size(0)


@pytest.mark.asyncio
async def test_request_mtu_returns_negotiated():
    """request_mtu must return the client's negotiated MTU."""
    client = MagicMock()
    client.mtu_size = 247

    result = await request_mtu(client, mtu=512)
    assert result == 247


@pytest.mark.asyncio
async def test_request_mtu_below_minimum_raises():
    """request_mtu must raise ValueError for requested MTU below 23."""
    client = MagicMock()
    client.mtu_size = 23

    with pytest.raises(ValueError, match="below the BLE minimum"):
        await request_mtu(client, mtu=10)
