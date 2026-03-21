# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 03 — Connection Lifecycle
# Platform: Python / Bleak
# Sprint  : Sprint 03
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Connect, disconnect, and auto-reconnect to a BLE peripheral
#           with exponential backoff and disconnection callbacks.
#
# Usage   : python 01_python_bleak/03_connection.py <device_address>
#           or: from 03_connection import BLEConnectionManager
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
BLE connection lifecycle manager using Bleak.

Provides ``BLEConnectionManager`` — a class handling:

- **Connect** by address with configurable timeout.
- **Disconnect** gracefully, suppressing the disconnect callback.
- **Auto-reconnect** with exponential backoff on transient failures.
- **Disconnection callback** for unexpected disconnects (e.g. device
  went out of range).

The ``_intentional_disconnect`` flag is the key pattern here: it prevents
the disconnect callback from firing when *we* initiated the disconnect,
avoiding spurious reconnect loops. This pattern must be replicated across
all platform implementations.

Platform Notes
--------------
- **macOS / iOS**: Addresses are CoreBluetooth UUIDs, not MAC addresses.
- **Linux**: BlueZ may cache stale connections. If ``connect()`` fails
  after a prior crash, the device may need to be removed from the BlueZ
  cache (``bluetoothctl remove <address>``).
"""

from __future__ import annotations

import asyncio
from collections.abc import Callable

from bleak import BleakClient
from bleak.exc import BleakError

# ─── Constants ───────────────────────────────────────

DEFAULT_CONNECT_TIMEOUT: float = 10.0
"""Default connection timeout in seconds."""

DEFAULT_MAX_ATTEMPTS: int = 5
"""Default maximum reconnection attempts before giving up."""

DEFAULT_BACKOFF_BASE: float = 2.0
"""Base for exponential backoff (wait = base ** attempt)."""


# ─── Connection Manager ─────────────────────────────


class BLEConnectionManager:
    """
    Manages the full BLE connection lifecycle for a single peripheral.

    Handles connect, disconnect, and auto-reconnect with exponential
    backoff. Tracks intentional vs. unexpected disconnects to avoid
    spurious reconnection loops.

    Example
    -------
    >>> manager = BLEConnectionManager()
    >>> manager.on_disconnect(lambda client: print("Lost connection!"))
    >>> client = await manager.connect("AA:BB:CC:DD:EE:FF")
    >>> # ... use client ...
    >>> await manager.disconnect(client)
    """

    def __init__(self) -> None:
        self._disconnect_callbacks: list[Callable[[BleakClient], None]] = []
        self._intentional_disconnect: bool = False
        self._lock: asyncio.Lock = asyncio.Lock()

    def on_disconnect(
        self,
        callback: Callable[[BleakClient], None],
    ) -> None:
        """
        Register a callback to be invoked on *unexpected* disconnection.

        The callback is **not** called when ``disconnect()`` is invoked
        explicitly (intentional disconnect).

        Parameters
        ----------
        callback : Callable[[BleakClient], None]
            Function called with the ``BleakClient`` that disconnected.
        """
        self._disconnect_callbacks.append(callback)

    def _handle_disconnect(self, client: BleakClient) -> None:
        """
        Internal disconnect handler passed to BleakClient.

        Only fires registered callbacks if the disconnect was
        *not* intentional (i.e. not triggered by ``disconnect()``).
        """
        if self._intentional_disconnect:
            return
        for callback in self._disconnect_callbacks:
            callback(client)

    async def connect(
        self,
        address: str,
        timeout: float = DEFAULT_CONNECT_TIMEOUT,
    ) -> BleakClient:
        """
        Connect to a BLE peripheral by address.

        Parameters
        ----------
        address : str
            Device address (MAC on Linux/Windows, UUID on macOS/iOS).
        timeout : float
            Connection timeout in seconds (default: 10.0).

        Returns
        -------
        BleakClient
            Connected client instance.

        Raises
        ------
        BleakError
            If connection fails or times out.
        """
        self._intentional_disconnect = False
        client = BleakClient(
            address,
            timeout=timeout,
            disconnected_callback=self._handle_disconnect,
        )
        await client.connect()
        return client

    async def disconnect(self, client: BleakClient) -> None:
        """
        Gracefully disconnect from a BLE peripheral.

        Sets ``_intentional_disconnect = True`` before disconnecting
        so that registered callbacks are **not** invoked.

        Parameters
        ----------
        client : BleakClient
            The connected client to disconnect.
        """
        self._intentional_disconnect = True
        await client.disconnect()

    async def connect_with_retry(
        self,
        address: str,
        max_attempts: int = DEFAULT_MAX_ATTEMPTS,
        backoff_base: float = DEFAULT_BACKOFF_BASE,
        timeout: float = DEFAULT_CONNECT_TIMEOUT,
    ) -> BleakClient:
        """
        Connect with automatic retry and exponential backoff.

        On each failed attempt, waits ``backoff_base ** attempt`` seconds
        before retrying (e.g. 2s, 4s, 8s, 16s for base=2.0).

        Parameters
        ----------
        address : str
            Device address.
        max_attempts : int
            Maximum number of connection attempts (default: 5).
        backoff_base : float
            Base for exponential backoff formula (default: 2.0).
        timeout : float
            Per-attempt connection timeout in seconds (default: 10.0).

        Returns
        -------
        BleakClient
            Connected client instance.

        Raises
        ------
        BleakError
            If all attempts are exhausted without a successful connection.
        """
        async with self._lock:
            last_error: BleakError | None = None

            for attempt in range(max_attempts):
                try:
                    return await self.connect(address, timeout=timeout)
                except BleakError as e:
                    last_error = e
                    if attempt < max_attempts - 1:
                        wait = backoff_base ** attempt
                        await asyncio.sleep(wait)

            raise BleakError(
                f"Failed to connect to {address} after {max_attempts} attempts: "
                f"{last_error}"
            )


# ─── Entrypoint ──────────────────────────────────────

if __name__ == "__main__":
    import sys

    if len(sys.argv) < 2:
        print("Usage: python 03_connection.py <device_address>")
        print("Example: python 03_connection.py AA:BB:CC:DD:EE:FF")
        sys.exit(1)

    address = sys.argv[1]

    async def main() -> None:
        manager = BLEConnectionManager()
        manager.on_disconnect(
            lambda client: print(f"Unexpected disconnect from {client.address}")
        )

        print(f"Connecting to {address}...")
        client = await manager.connect(address)
        print(f"Connected: {client.address} (is_connected={client.is_connected})")

        print("Disconnecting...")
        await manager.disconnect(client)
        print("Disconnected (callback should NOT fire).")

    asyncio.run(main())
