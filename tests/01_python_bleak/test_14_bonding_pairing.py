# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 14 — Bonding & Pairing (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 14
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for BLE pairing utilities with mocked BleakClient.
#
# Usage   : pytest tests/01_python_bleak/test_14_bonding_pairing.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 14_bonding_pairing.py — BLE bonding and pairing utilities.

All tests use mocked BleakClient — no BLE hardware required.
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

_mod = importlib.import_module("14_bonding_pairing")
pair = _mod.pair
unpair = _mod.unpair
is_paired = _mod.is_paired


# ─── Tests ──────────────────────────────────────────


@pytest.mark.asyncio
async def test_pair_returns_bool():
    """pair must return True when client.pair() succeeds."""
    client = MagicMock()
    client.pair = AsyncMock(return_value=True)

    result = await pair(client)
    assert result is True
    client.pair.assert_awaited_once()


@pytest.mark.asyncio
async def test_pair_handles_not_implemented():
    """pair must return True when client.pair() raises NotImplementedError."""
    client = MagicMock()
    client.pair = AsyncMock(side_effect=NotImplementedError)

    result = await pair(client)
    assert result is True


@pytest.mark.asyncio
async def test_pair_handles_failure():
    """pair must return False when client.pair() raises an exception."""
    client = MagicMock()
    client.pair = AsyncMock(side_effect=Exception("Pairing rejected"))

    result = await pair(client)
    assert result is False


@pytest.mark.asyncio
async def test_unpair_returns_bool():
    """unpair must return True when client.unpair() succeeds."""
    client = MagicMock()
    client.unpair = AsyncMock(return_value=True)

    result = await unpair(client)
    assert result is True
    client.unpair.assert_awaited_once()


@pytest.mark.asyncio
async def test_unpair_handles_not_implemented():
    """unpair must return False when the platform doesn't support it."""
    client = MagicMock()
    client.unpair = AsyncMock(side_effect=NotImplementedError)

    result = await unpair(client)
    assert result is False


@pytest.mark.asyncio
async def test_is_paired_returns_none_when_unknown():
    """is_paired must return None when the platform cannot determine status."""
    client = MagicMock(spec=[])  # No _is_paired attribute

    result = await is_paired(client)
    assert result is None


@pytest.mark.asyncio
async def test_is_paired_returns_bool_when_available():
    """is_paired must return the _is_paired value when the backend exposes it."""
    client = MagicMock()
    client._is_paired = True

    result = await is_paired(client)
    assert result is True
