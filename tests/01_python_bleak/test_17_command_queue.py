# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 17 — Command Queue (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 17
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for the BLE command queue — serial execution,
#           priority ordering, timeout, and error propagation.
#
# Usage   : pytest tests/01_python_bleak/test_17_command_queue.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 17_command_queue.py — BLE command queue.

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

_mod = importlib.import_module("17_command_queue")
BleCommandQueue = _mod.BleCommandQueue
Priority = _mod.Priority


# ─── Helpers ────────────────────────────────────────


async def _identity(value: str) -> str:
    """Trivial async function that returns its argument."""
    return value


async def _slow_identity(value: str) -> str:
    """Async function with a small delay."""
    await asyncio.sleep(0.05)
    return value


async def _failing() -> None:
    """Async function that always raises."""
    raise ValueError("simulated BLE error")


# ─── Tests ──────────────────────────────────────────


@pytest.mark.asyncio
async def test_enqueue_returns_result():
    """enqueue must return the coroutine's result to the caller."""
    queue = BleCommandQueue()
    await queue.start()

    result = await queue.enqueue(_identity, "hello")
    assert result == "hello"

    await queue.stop()


@pytest.mark.asyncio
async def test_sequential_execution():
    """Commands must execute in FIFO order within the same priority."""
    order: list[int] = []

    async def record(index: int) -> None:
        order.append(index)

    queue = BleCommandQueue()
    await queue.start()

    # Enqueue 5 commands concurrently
    tasks = [
        asyncio.create_task(queue.enqueue(record, i))
        for i in range(5)
    ]
    await asyncio.gather(*tasks)

    assert order == [0, 1, 2, 3, 4]
    await queue.stop()


@pytest.mark.asyncio
async def test_priority_ordering():
    """HIGH priority commands must execute before NORMAL ones."""
    order: list[str] = []

    async def record(label: str) -> None:
        await asyncio.sleep(0.01)
        order.append(label)

    queue = BleCommandQueue()
    await queue.start()

    # First enqueue a slow command to block the queue
    blocker = asyncio.create_task(
        queue.enqueue(_slow_identity, "blocker")
    )
    # Give consumer time to start processing the blocker
    await asyncio.sleep(0.01)

    # Now enqueue normal and high priority — high should go first
    t_normal = asyncio.create_task(
        queue.enqueue(record, "normal", priority=Priority.NORMAL)
    )
    t_high = asyncio.create_task(
        queue.enqueue(record, "high", priority=Priority.HIGH)
    )

    await asyncio.gather(blocker, t_high, t_normal)
    assert order == ["high", "normal"]

    await queue.stop()


@pytest.mark.asyncio
async def test_error_propagation():
    """Exceptions from commands must propagate to the enqueue caller."""
    queue = BleCommandQueue()
    await queue.start()

    with pytest.raises(ValueError, match="simulated BLE error"):
        await queue.enqueue(_failing)

    # Queue should still be functional after an error
    result = await queue.enqueue(_identity, "still works")
    assert result == "still works"

    await queue.stop()


@pytest.mark.asyncio
async def test_timeout():
    """A command exceeding its timeout must raise TimeoutError."""
    async def very_slow() -> None:
        await asyncio.sleep(10.0)

    queue = BleCommandQueue()
    await queue.start()

    with pytest.raises(asyncio.TimeoutError):
        await queue.enqueue(very_slow, timeout=0.05)

    # Queue must recover after timeout
    result = await queue.enqueue(_identity, "recovered")
    assert result == "recovered"

    await queue.stop()


@pytest.mark.asyncio
async def test_enqueue_before_start_raises():
    """enqueue on a stopped queue must raise RuntimeError."""
    queue = BleCommandQueue()

    with pytest.raises(RuntimeError, match="not running"):
        await queue.enqueue(_identity, "nope")


@pytest.mark.asyncio
async def test_stop_cancels_pending():
    """stop() must cancel commands still waiting in the queue."""
    async def block_forever() -> None:
        await asyncio.sleep(1000)

    queue = BleCommandQueue()
    await queue.start()

    # Start a blocking command
    t_block = asyncio.create_task(queue.enqueue(block_forever, timeout=1000))
    await asyncio.sleep(0.01)

    # Enqueue more that will be pending
    t_pending = asyncio.create_task(queue.enqueue(_identity, "pending"))

    # Stop should cancel pending
    await queue.stop()

    assert t_block.cancelled() or t_block.done()
    assert queue.pending_count == 0


@pytest.mark.asyncio
async def test_pending_count():
    """pending_count must reflect the number of waiting commands."""
    queue = BleCommandQueue()
    await queue.start()

    assert queue.pending_count == 0

    result = await queue.enqueue(_identity, "x")
    assert result == "x"
    # After processing, count should be 0
    assert queue.pending_count == 0

    await queue.stop()


@pytest.mark.asyncio
async def test_start_idempotent():
    """Calling start() twice must not create duplicate consumers."""
    queue = BleCommandQueue()
    await queue.start()
    await queue.start()  # Should be a no-op

    result = await queue.enqueue(_identity, "once")
    assert result == "once"

    await queue.stop()
