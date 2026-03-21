# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 17 — Command Queue
# Platform: Python / Bleak
# Sprint  : Sprint 17
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Serialize BLE GATT operations through an async queue
#           to prevent concurrent access, which is undefined
#           behavior in the Bluetooth specification.
#
# Usage   : from 17_command_queue import BleCommandQueue
#
# Depends : Sprint 05 (Read/Write)
# ─────────────────────────────────────────────────────

"""
BLE command queue — serialize GATT operations.

Why a Queue?
------------
The BLE GATT protocol is **inherently serial**: the peripheral
processes one ATT request at a time and responds before accepting
the next. If a central sends a second request before the first
response arrives, behavior is **undefined** per the Bluetooth Core
Spec (Vol 3, Part F, Section 3.4.8).

In practice, Bleak and the underlying OS drivers handle some
serialization internally, but:

1. **Overlapping writes** can silently drop data on some platforms.
2. **Read-during-write** can return stale or corrupted values.
3. **Notification subscribe during write** can race on Linux/BlueZ.

This module provides a ``BleCommandQueue`` that guarantees serial
execution of all GATT operations (reads, writes, subscribes) using
an ``asyncio.Queue`` and a single consumer task.

Pattern
-------
Instead of calling ``client.read_gatt_char(uuid)`` directly, you
enqueue the operation:

.. code-block:: python

    queue = BleCommandQueue()
    await queue.start()

    # These run sequentially, never concurrently:
    value = await queue.enqueue(client.read_gatt_char, uuid_a)
    await queue.enqueue(client.write_gatt_char, uuid_b, data)

    await queue.stop()

The queue also supports:
- **Priority**: Urgent commands (e.g., disconnect) jump ahead.
- **Timeout**: Individual commands can time out without blocking
  the rest of the queue.
- **Error propagation**: Exceptions from a command are re-raised
  in the caller's coroutine, not swallowed.

Reference
---------
Bluetooth Core Spec v5.3, Vol 3, Part F, Section 3.4.8 —
Sequential Request Processing.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from enum import IntEnum
from typing import Any, Callable, Coroutine

logger = logging.getLogger(__name__)


# ─── Constants ───────────────────────────────────────

DEFAULT_COMMAND_TIMEOUT: float = 10.0
"""Default per-command timeout in seconds."""


# ─── Data Structures ────────────────────────────────


class Priority(IntEnum):
    """
    Command priority levels.

    Lower value = higher priority (processed first).

    BLE operations are typically all NORMAL, but disconnect/stop
    commands should use HIGH to avoid waiting behind a long queue
    of reads/writes.
    """

    HIGH = 0
    NORMAL = 1
    LOW = 2


@dataclass(order=True)
class _QueueEntry:
    """
    Internal wrapper for a queued BLE command.

    Ordered by (priority, sequence) to ensure FIFO within
    the same priority level.
    """

    priority: int
    sequence: int
    coro_fn: Callable[..., Coroutine[Any, Any, Any]] = field(compare=False)
    args: tuple[Any, ...] = field(compare=False)
    kwargs: dict[str, Any] = field(compare=False)
    timeout: float = field(compare=False)
    future: asyncio.Future[Any] = field(compare=False)


# ─── Command Queue ──────────────────────────────────


class BleCommandQueue:
    """
    Async command queue for serializing BLE GATT operations.

    All enqueued coroutines execute one at a time in a single
    consumer task, preventing concurrent GATT access.

    Parameters
    ----------
    default_timeout : float
        Default per-command timeout in seconds (default: 10.0).

    Example
    -------
    >>> queue = BleCommandQueue()
    >>> await queue.start()
    >>> value = await queue.enqueue(client.read_gatt_char, "00002a19-...")
    >>> await queue.stop()
    """

    def __init__(self, default_timeout: float = DEFAULT_COMMAND_TIMEOUT) -> None:
        self._default_timeout = default_timeout
        self._queue: asyncio.PriorityQueue[_QueueEntry] = asyncio.PriorityQueue()
        self._consumer_task: asyncio.Task[None] | None = None
        self._sequence: int = 0
        self._running: bool = False

    @property
    def is_running(self) -> bool:
        """Whether the queue consumer is active."""
        return self._running

    @property
    def pending_count(self) -> int:
        """Number of commands waiting in the queue."""
        return self._queue.qsize()

    async def start(self) -> None:
        """
        Start the queue consumer task.

        Must be called before ``enqueue()``. Safe to call multiple
        times — subsequent calls are no-ops.
        """
        if self._running:
            logger.debug("Queue already running")
            return

        self._running = True
        self._consumer_task = asyncio.create_task(self._consumer())
        logger.info("Command queue started")

    async def stop(self) -> None:
        """
        Stop the queue consumer and wait for it to finish.

        Any commands still in the queue will be cancelled with
        a ``CancelledError`` on their futures.
        """
        if not self._running:
            return

        self._running = False

        # Cancel remaining entries
        cancelled = 0
        while not self._queue.empty():
            try:
                entry = self._queue.get_nowait()
                if not entry.future.done():
                    entry.future.cancel()
                    cancelled += 1
            except asyncio.QueueEmpty:
                break

        # Stop consumer
        if self._consumer_task and not self._consumer_task.done():
            self._consumer_task.cancel()
            try:
                await self._consumer_task
            except asyncio.CancelledError:
                pass

        logger.info("Command queue stopped (%d commands cancelled)", cancelled)

    async def enqueue(
        self,
        coro_fn: Callable[..., Coroutine[Any, Any, Any]],
        *args: Any,
        priority: Priority = Priority.NORMAL,
        timeout: float | None = None,
        **kwargs: Any,
    ) -> Any:
        """
        Enqueue a coroutine for serial execution.

        The coroutine will run when all previously enqueued commands
        (at equal or higher priority) have completed.

        Parameters
        ----------
        coro_fn : Callable
            An async callable (e.g., ``client.read_gatt_char``).
        *args
            Positional arguments for ``coro_fn``.
        priority : Priority
            Execution priority (default: NORMAL).
        timeout : float | None
            Per-command timeout in seconds. ``None`` uses the queue default.
        **kwargs
            Keyword arguments for ``coro_fn``.

        Returns
        -------
        Any
            The return value of ``coro_fn(*args, **kwargs)``.

        Raises
        ------
        RuntimeError
            If the queue is not running.
        asyncio.TimeoutError
            If the command exceeds its timeout.
        Exception
            Any exception raised by ``coro_fn`` is re-raised here.
        """
        if not self._running:
            raise RuntimeError(
                "Queue not running. Call start() before enqueue()."
            )

        loop = asyncio.get_running_loop()
        future: asyncio.Future[Any] = loop.create_future()

        self._sequence += 1
        entry = _QueueEntry(
            priority=priority.value,
            sequence=self._sequence,
            coro_fn=coro_fn,
            args=args,
            kwargs=kwargs,
            timeout=timeout if timeout is not None else self._default_timeout,
            future=future,
        )

        await self._queue.put(entry)
        logger.debug(
            "Enqueued command #%d (priority=%s, pending=%d)",
            self._sequence,
            priority.name,
            self._queue.qsize(),
        )

        # Wait for the consumer to process this entry
        return await future

    async def _consumer(self) -> None:
        """
        Consumer loop — process commands one at a time.

        Runs as a background task until ``stop()`` is called.
        """
        logger.debug("Consumer loop started")

        while self._running:
            try:
                entry = await asyncio.wait_for(
                    self._queue.get(), timeout=0.5
                )
            except asyncio.TimeoutError:
                # No commands — loop back and check self._running
                continue

            if entry.future.cancelled():
                logger.debug("Skipping cancelled command #%d", entry.sequence)
                continue

            try:
                result = await asyncio.wait_for(
                    entry.coro_fn(*entry.args, **entry.kwargs),
                    timeout=entry.timeout,
                )
                entry.future.set_result(result)
            except asyncio.CancelledError:
                entry.future.cancel()
            except Exception as exc:
                if not entry.future.done():
                    entry.future.set_exception(exc)

        logger.debug("Consumer loop exited")


# ─── Entrypoint ─────────────────────────────────────

if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(message)s")

    async def demo() -> None:
        """Demonstrate the command queue with simulated operations."""
        print("BLE Command Queue — Demo")
        print("=" * 40)

        queue = BleCommandQueue(default_timeout=5.0)
        await queue.start()

        # Simulate BLE operations
        async def fake_read(name: str) -> str:
            await asyncio.sleep(0.1)
            return f"value_from_{name}"

        async def fake_write(name: str, data: bytes) -> None:
            await asyncio.sleep(0.05)

        # Enqueue operations — they execute sequentially
        r1 = await queue.enqueue(fake_read, "battery")
        print(f"  Read battery: {r1}")

        await queue.enqueue(fake_write, "config", b"\x01\x02")
        print("  Write config: OK")

        r2 = await queue.enqueue(fake_read, "heart_rate")
        print(f"  Read HR: {r2}")

        # High-priority command
        r3 = await queue.enqueue(
            fake_read, "status", priority=Priority.HIGH
        )
        print(f"  Read status (HIGH priority): {r3}")

        await queue.stop()
        print(f"\nQueue stopped. Pending: {queue.pending_count}")

    asyncio.run(demo())
