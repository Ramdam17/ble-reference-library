# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 06 — Notifications & Indications (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 06
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for NotificationManager with mocked BleakClient.
#
# Usage   : pytest tests/01_python_bleak/test_06_notifications.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 06_notifications.py — notification subscription manager.

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

_mod = importlib.import_module("06_notifications")
NotificationManager = _mod.NotificationManager

HR_UUID = "00002a37-0000-1000-8000-00805f9b34fb"
BATTERY_UUID = "00002a19-0000-1000-8000-00805f9b34fb"


# ─── Helpers ─────────────────────────────────────────


def _make_client():
    """Create a mock BleakClient with start/stop_notify as AsyncMocks."""
    client = MagicMock()
    client.start_notify = AsyncMock()
    client.stop_notify = AsyncMock()
    return client


# ─── Tests ───────────────────────────────────────────


@pytest.mark.asyncio
async def test_subscribe_calls_start_notify():
    """subscribe() must call client.start_notify with the UUID."""
    client = _make_client()
    manager = NotificationManager()
    callback = MagicMock()

    await manager.subscribe(client, HR_UUID, callback)

    client.start_notify.assert_awaited_once()
    call_args = client.start_notify.call_args
    assert call_args[0][0] == HR_UUID


@pytest.mark.asyncio
async def test_unsubscribe_calls_stop_notify():
    """unsubscribe() must call client.stop_notify with the UUID."""
    client = _make_client()
    manager = NotificationManager()
    await manager.subscribe(client, HR_UUID, MagicMock())

    await manager.unsubscribe(client, HR_UUID)

    client.stop_notify.assert_awaited_once_with(HR_UUID)


@pytest.mark.asyncio
async def test_callback_invoked_on_data():
    """The user callback must receive data when Bleak fires the wrapped callback."""
    client = _make_client()
    manager = NotificationManager()
    received_data = []

    def on_data(data: bytearray) -> None:
        received_data.append(data)

    await manager.subscribe(client, HR_UUID, on_data)

    # Extract the wrapped callback that was passed to start_notify
    wrapped_callback = client.start_notify.call_args[0][1]

    # Simulate Bleak firing with (sender, data)
    test_payload = bytearray(b"\x10\x48")
    wrapped_callback(14, test_payload)  # sender=14 (handle), data=payload

    assert len(received_data) == 1
    assert received_data[0] == test_payload


@pytest.mark.asyncio
async def test_is_subscribed_true_after_subscribe():
    """is_subscribed must return True after subscribing."""
    client = _make_client()
    manager = NotificationManager()
    await manager.subscribe(client, HR_UUID, MagicMock())

    assert manager.is_subscribed(HR_UUID) is True


@pytest.mark.asyncio
async def test_is_subscribed_false_after_unsubscribe():
    """is_subscribed must return False after unsubscribing."""
    client = _make_client()
    manager = NotificationManager()
    await manager.subscribe(client, HR_UUID, MagicMock())
    await manager.unsubscribe(client, HR_UUID)

    assert manager.is_subscribed(HR_UUID) is False


@pytest.mark.asyncio
async def test_unsubscribe_all_clears_all():
    """unsubscribe_all must stop all subscriptions."""
    client = _make_client()
    manager = NotificationManager()
    await manager.subscribe(client, HR_UUID, MagicMock())
    await manager.subscribe(client, BATTERY_UUID, MagicMock())

    await manager.unsubscribe_all(client)

    assert manager.is_subscribed(HR_UUID) is False
    assert manager.is_subscribed(BATTERY_UUID) is False
    assert client.stop_notify.await_count == 2


@pytest.mark.asyncio
async def test_double_subscribe_no_duplicate():
    """Subscribing twice to the same UUID must not create a duplicate."""
    client = _make_client()
    manager = NotificationManager()
    await manager.subscribe(client, HR_UUID, MagicMock())
    await manager.subscribe(client, HR_UUID, MagicMock())

    # start_notify should only be called once
    assert client.start_notify.await_count == 1
