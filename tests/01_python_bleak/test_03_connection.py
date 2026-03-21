# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 03 — Connection Lifecycle (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 03
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for BLEConnectionManager with mocked BleakClient.
#
# Usage   : pytest tests/01_python_bleak/test_03_connection.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 03_connection.py — BLE connection lifecycle.

All tests mock ``BleakClient`` so no BLE hardware is required.
"""

from __future__ import annotations

import importlib
import sys
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from bleak.exc import BleakError

# ─── Module import ───────────────────────────────────

_MODULE_DIR = Path(__file__).resolve().parent.parent.parent / "01_python_bleak"
sys.path.insert(0, str(_MODULE_DIR))

_mod = importlib.import_module("03_connection")
BLEConnectionManager = _mod.BLEConnectionManager

DEVICE_ADDRESS = "AA:BB:CC:DD:EE:FF"


# ─── Helpers ─────────────────────────────────────────


def _make_mock_client(address: str = DEVICE_ADDRESS):
    """Create a mock BleakClient with connect/disconnect as AsyncMocks."""
    client = MagicMock()
    client.address = address
    client.is_connected = True
    client.connect = AsyncMock()
    client.disconnect = AsyncMock()
    return client


# ─── Tests ───────────────────────────────────────────


@pytest.mark.asyncio
async def test_connect_success():
    """connect() must return a connected BleakClient."""
    mock_client = _make_mock_client()

    with patch.object(_mod, "BleakClient", return_value=mock_client) as mock_cls:
        manager = BLEConnectionManager()
        client = await manager.connect(DEVICE_ADDRESS)

    assert client is mock_client
    mock_client.connect.assert_awaited_once()
    # Verify BleakClient was constructed with correct args
    mock_cls.assert_called_once()
    call_kwargs = mock_cls.call_args
    assert call_kwargs[0][0] == DEVICE_ADDRESS
    assert call_kwargs[1]["timeout"] == 10.0


@pytest.mark.asyncio
async def test_connect_timeout():
    """connect() must raise BleakError when connection times out."""
    mock_client = _make_mock_client()
    mock_client.connect = AsyncMock(side_effect=BleakError("Connection timed out"))

    with patch.object(_mod, "BleakClient", return_value=mock_client):
        manager = BLEConnectionManager()
        with pytest.raises(BleakError, match="timed out"):
            await manager.connect(DEVICE_ADDRESS)


@pytest.mark.asyncio
async def test_disconnect_called():
    """disconnect() must call client.disconnect()."""
    mock_client = _make_mock_client()

    with patch.object(_mod, "BleakClient", return_value=mock_client):
        manager = BLEConnectionManager()
        client = await manager.connect(DEVICE_ADDRESS)
        await manager.disconnect(client)

    mock_client.disconnect.assert_awaited_once()


@pytest.mark.asyncio
async def test_reconnect_retries_on_failure():
    """connect_with_retry() must retry and succeed after transient failures."""
    mock_client = _make_mock_client()
    # Fail twice, then succeed
    mock_client.connect = AsyncMock(
        side_effect=[BleakError("fail"), BleakError("fail"), None]
    )

    with (
        patch.object(_mod, "BleakClient", return_value=mock_client),
        patch.object(_mod.asyncio, "sleep", new_callable=AsyncMock) as mock_sleep,
    ):
        manager = BLEConnectionManager()
        client = await manager.connect_with_retry(
            DEVICE_ADDRESS, max_attempts=5, backoff_base=2.0
        )

    assert client is mock_client
    assert mock_client.connect.await_count == 3
    # Should have slept twice (after attempt 0 and 1)
    assert mock_sleep.await_count == 2


@pytest.mark.asyncio
async def test_reconnect_raises_after_max():
    """connect_with_retry() must raise BleakError after max_attempts exhausted."""
    mock_client = _make_mock_client()
    mock_client.connect = AsyncMock(side_effect=BleakError("always fails"))

    with (
        patch.object(_mod, "BleakClient", return_value=mock_client),
        patch.object(_mod.asyncio, "sleep", new_callable=AsyncMock),
    ):
        manager = BLEConnectionManager()
        with pytest.raises(BleakError, match="Failed to connect.*3 attempts"):
            await manager.connect_with_retry(
                DEVICE_ADDRESS, max_attempts=3, backoff_base=2.0
            )

    assert mock_client.connect.await_count == 3


@pytest.mark.asyncio
async def test_disconnect_callback_invoked():
    """Disconnect callback must fire on unexpected (non-intentional) disconnect."""
    callback = MagicMock()
    mock_client = _make_mock_client()

    with patch.object(_mod, "BleakClient", return_value=mock_client):
        manager = BLEConnectionManager()
        manager.on_disconnect(callback)
        await manager.connect(DEVICE_ADDRESS)

        # Simulate unexpected disconnect by calling the internal handler
        manager._handle_disconnect(mock_client)

    callback.assert_called_once_with(mock_client)


@pytest.mark.asyncio
async def test_disconnect_callback_suppressed_on_intentional():
    """Disconnect callback must NOT fire when disconnect() is called explicitly."""
    callback = MagicMock()
    mock_client = _make_mock_client()

    with patch.object(_mod, "BleakClient", return_value=mock_client):
        manager = BLEConnectionManager()
        manager.on_disconnect(callback)
        client = await manager.connect(DEVICE_ADDRESS)
        await manager.disconnect(client)

        # Simulate the callback that BleakClient would fire after disconnect
        manager._handle_disconnect(mock_client)

    callback.assert_not_called()


@pytest.mark.asyncio
async def test_exponential_backoff_timing():
    """Sleep durations must match the exponential backoff formula."""
    mock_client = _make_mock_client()
    # Fail 4 times, succeed on 5th
    mock_client.connect = AsyncMock(
        side_effect=[BleakError("f")] * 4 + [None]
    )

    with (
        patch.object(_mod, "BleakClient", return_value=mock_client),
        patch.object(_mod.asyncio, "sleep", new_callable=AsyncMock) as mock_sleep,
    ):
        manager = BLEConnectionManager()
        await manager.connect_with_retry(
            DEVICE_ADDRESS, max_attempts=5, backoff_base=2.0
        )

    # Expected: 2^0=1, 2^1=2, 2^2=4, 2^3=8
    expected_waits = [1.0, 2.0, 4.0, 8.0]
    actual_waits = [call.args[0] for call in mock_sleep.await_args_list]
    assert actual_waits == expected_waits
