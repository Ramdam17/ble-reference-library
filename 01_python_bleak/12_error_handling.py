# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 12 — Error Handling & Timeouts
# Platform: Python / Bleak
# Sprint  : Sprint 12
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Centralize BLE error handling patterns: classify error
#           types, implement operation timeouts, and provide a retry
#           decorator for flaky BLE operations.
#
# Usage   : from 12_error_handling import ble_retry, with_timeout
#
# Depends : Sprint 03 (Connection)
# ─────────────────────────────────────────────────────

"""
BLE error handling utilities.

BLE operations are inherently unreliable — devices disconnect mid-transfer,
GATT reads time out, and service discovery fails intermittently. This module
provides three tools to handle these gracefully:

1. **Error hierarchy** — typed exceptions for BLE failure modes, enabling
   selective catch/retry logic.
2. **Timeout wrapper** — enforce a deadline on any async operation.
3. **Retry decorator** — automatically retry flaky async functions with
   configurable backoff.

Error Hierarchy
---------------
.. code-block:: text

    BLEError
    ├── BLEConnectionError      — connect/disconnect failures
    ├── BLETimeoutError         — operation exceeded deadline
    └── BLEServiceNotFoundError — expected GATT service/char absent

Usage Examples
--------------
.. code-block:: python

    # Timeout a slow read
    data = await with_timeout(client.read_gatt_char(uuid), timeout=5.0)

    # Retry a flaky connection
    @ble_retry(max_attempts=3, delay=2.0)
    async def connect(client):
        await client.connect()
"""

from __future__ import annotations

import asyncio
import functools
import logging
from collections.abc import Callable
from typing import Any, TypeVar

logger = logging.getLogger(__name__)

T = TypeVar("T")


# ─── Error hierarchy ────────────────────────────────


class BLEError(Exception):
    """Base exception for all BLE operations.

    All BLE-specific errors inherit from this class, enabling
    broad exception handling with ``except BLEError``.
    """


class BLEConnectionError(BLEError):
    """Raised when a BLE connection or disconnection fails.

    Examples: device not found, connection refused, unexpected
    disconnect during an operation.
    """


class BLETimeoutError(BLEError):
    """Raised when a BLE operation exceeds its deadline.

    Wraps ``asyncio.TimeoutError`` with BLE-specific context
    so callers can distinguish BLE timeouts from other timeouts.
    """


class BLEServiceNotFoundError(BLEError):
    """Raised when an expected GATT service or characteristic is absent.

    Typical cause: the device does not expose the required service,
    or service discovery has not been performed yet.
    """


# ─── Timeout wrapper ────────────────────────────────


async def with_timeout(coro: Any, timeout: float) -> Any:
    """
    Run an async operation with a timeout.

    Wraps ``asyncio.wait_for`` and converts ``asyncio.TimeoutError``
    into ``BLETimeoutError`` for consistent error handling.

    Parameters
    ----------
    coro : Coroutine
        The async operation to execute.
    timeout : float
        Maximum duration in seconds. Must be positive.

    Returns
    -------
    Any
        The result of the coroutine.

    Raises
    ------
    BLETimeoutError
        If the operation does not complete within ``timeout`` seconds.
    ValueError
        If ``timeout`` is not positive.
    """
    if timeout <= 0:
        raise ValueError(f"Timeout must be positive, got {timeout}")

    try:
        return await asyncio.wait_for(coro, timeout=timeout)
    except asyncio.TimeoutError:
        raise BLETimeoutError(
            f"Operation timed out after {timeout:.1f}s"
        ) from None


# ─── Retry decorator ────────────────────────────────


def ble_retry(
    max_attempts: int = 3,
    delay: float = 1.0,
    exceptions: tuple[type[Exception], ...] = (BLEError,),
) -> Callable:
    """
    Retry decorator for flaky async BLE operations.

    Retries the decorated async function up to ``max_attempts`` times
    when it raises one of the specified exception types. Uses a fixed
    delay between attempts.

    Parameters
    ----------
    max_attempts : int
        Maximum number of attempts (default: 3). Must be >= 1.
    delay : float
        Delay in seconds between attempts (default: 1.0).
    exceptions : tuple[type[Exception], ...]
        Exception types that trigger a retry. Exceptions not in this
        tuple propagate immediately. Default: ``(BLEError,)``.

    Returns
    -------
    Callable
        Decorator that wraps an async function with retry logic.

    Example
    -------
    >>> @ble_retry(max_attempts=3, delay=2.0)
    ... async def connect(client):
    ...     await client.connect()
    """
    def decorator(func: Callable) -> Callable:
        @functools.wraps(func)
        async def wrapper(*args: Any, **kwargs: Any) -> Any:
            last_exception: Exception | None = None
            for attempt in range(1, max_attempts + 1):
                try:
                    return await func(*args, **kwargs)
                except exceptions as e:
                    last_exception = e
                    if attempt < max_attempts:
                        logger.warning(
                            "%s attempt %d/%d failed: %s — retrying in %.1fs",
                            func.__name__,
                            attempt,
                            max_attempts,
                            e,
                            delay,
                        )
                        await asyncio.sleep(delay)
                    else:
                        logger.error(
                            "%s failed after %d attempts: %s",
                            func.__name__,
                            max_attempts,
                            e,
                        )
            raise last_exception  # type: ignore[misc]
        return wrapper
    return decorator
