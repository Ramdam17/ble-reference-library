# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 12 — Error Handling & Timeouts (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 12
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for BLE error hierarchy, timeout wrapper,
#           and retry decorator.
#
# Usage   : pytest tests/01_python_bleak/test_12_error_handling.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 12_error_handling.py — BLE error handling utilities.

All tests are pure async — no BLE hardware required.
"""

from __future__ import annotations

import asyncio
import importlib
import sys
from pathlib import Path

import pytest

# ─── Module import ───────────────────────────────────

_MODULE_DIR = Path(__file__).resolve().parent.parent.parent / "01_python_bleak"
sys.path.insert(0, str(_MODULE_DIR))

_mod = importlib.import_module("12_error_handling")
BLEError = _mod.BLEError
BLEConnectionError = _mod.BLEConnectionError
BLETimeoutError = _mod.BLETimeoutError
BLEServiceNotFoundError = _mod.BLEServiceNotFoundError
with_timeout = _mod.with_timeout
ble_retry = _mod.ble_retry


# ─── Error Hierarchy Tests ──────────────────────────


def test_error_hierarchy():
    """All BLE error subclasses must inherit from BLEError."""
    assert issubclass(BLEConnectionError, BLEError)
    assert issubclass(BLETimeoutError, BLEError)
    assert issubclass(BLEServiceNotFoundError, BLEError)

    # Also verify they are Exception subclasses
    assert issubclass(BLEError, Exception)

    # Instances should be catchable as BLEError
    with pytest.raises(BLEError):
        raise BLEConnectionError("test")

    with pytest.raises(BLEError):
        raise BLETimeoutError("test")

    with pytest.raises(BLEError):
        raise BLEServiceNotFoundError("test")


# ─── Timeout Tests ──────────────────────────────────


@pytest.mark.asyncio
async def test_with_timeout_completes():
    """with_timeout must return the result when the operation completes in time."""

    async def fast_op():
        return 42

    result = await with_timeout(fast_op(), timeout=1.0)
    assert result == 42


@pytest.mark.asyncio
async def test_with_timeout_raises_on_exceed():
    """with_timeout must raise BLETimeoutError when the deadline is exceeded."""

    async def slow_op():
        await asyncio.sleep(10.0)
        return "never"

    with pytest.raises(BLETimeoutError, match="timed out"):
        await with_timeout(slow_op(), timeout=0.05)


# ─── Retry Tests ────────────────────────────────────


@pytest.mark.asyncio
async def test_ble_retry_succeeds_on_second_attempt():
    """ble_retry must retry and succeed when a subsequent attempt works."""
    call_count = 0

    @ble_retry(max_attempts=3, delay=0.01)
    async def flaky():
        nonlocal call_count
        call_count += 1
        if call_count < 2:
            raise BLEConnectionError("Connection refused")
        return "connected"

    result = await flaky()
    assert result == "connected"
    assert call_count == 2


@pytest.mark.asyncio
async def test_ble_retry_raises_after_max_attempts():
    """ble_retry must raise the last exception after exhausting all attempts."""
    call_count = 0

    @ble_retry(max_attempts=3, delay=0.01)
    async def always_fails():
        nonlocal call_count
        call_count += 1
        raise BLEConnectionError(f"Attempt {call_count}")

    with pytest.raises(BLEConnectionError, match="Attempt 3"):
        await always_fails()

    assert call_count == 3


@pytest.mark.asyncio
async def test_ble_retry_does_not_retry_unrelated_exception():
    """ble_retry must not retry exceptions outside the specified types."""
    call_count = 0

    @ble_retry(max_attempts=3, delay=0.01, exceptions=(BLEConnectionError,))
    async def raises_value_error():
        nonlocal call_count
        call_count += 1
        raise ValueError("Not a BLE error")

    with pytest.raises(ValueError, match="Not a BLE error"):
        await raises_value_error()

    # Should have been called only once — no retry for ValueError
    assert call_count == 1
