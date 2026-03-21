# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 06 — Notifications & Indications
# Platform: Python / Bleak
# Sprint  : Sprint 06
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Subscribe to and unsubscribe from GATT notifications and
#           indications. Routes incoming data to registered callbacks.
#
# Usage   : python 01_python_bleak/06_notifications.py <addr> <char_uuid>
#           or: from 06_notifications import NotificationManager
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
GATT notification and indication subscription manager using Bleak.

Provides ``NotificationManager`` — a class that tracks active subscriptions,
wraps Bleak's raw callback signature, and provides clean subscribe/
unsubscribe lifecycle management.

Notifications vs. Indications
-----------------------------
Both allow a BLE peripheral to push data to the central without polling.

- **Notification** (GATT property ``notify``): fire-and-forget. The
  peripheral sends data; no acknowledgement from the central. Faster,
  but data may be lost if the link is noisy.
- **Indication** (GATT property ``indicate``): requires an explicit ACK
  from the central before the next indication can be sent. Slower but
  reliable — guarantees delivery.

Bleak uses the same API (``start_notify`` / ``stop_notify``) for both.
The GATT stack determines whether to use notification or indication
based on the characteristic's property flags.

Platform Notes
--------------
- On **macOS**, ``stop_notify`` may silently succeed even if no
  subscription was active. This is a CoreBluetooth behavior.
- On **Linux/BlueZ**, calling ``stop_notify`` on a non-subscribed
  characteristic raises a D-Bus error. This manager guards against it.
"""

from __future__ import annotations

import logging
from collections.abc import Callable

from bleak import BleakClient

logger = logging.getLogger(__name__)

# ─── Subscription Manager ───────────────────────────


class NotificationManager:
    """
    Manages GATT notification/indication subscriptions.

    Tracks active subscriptions and provides a simplified callback
    interface that exposes only the data payload (not the sender handle).

    Example
    -------
    >>> manager = NotificationManager()
    >>> await manager.subscribe(client, HR_CHAR_UUID, on_heart_rate)
    >>> # ... data flows via on_heart_rate(data: bytearray) ...
    >>> await manager.unsubscribe(client, HR_CHAR_UUID)
    """

    def __init__(self) -> None:
        self._subscriptions: dict[str, Callable[[bytearray], None]] = {}

    async def subscribe(
        self,
        client: BleakClient,
        uuid: str,
        callback: Callable[[bytearray], None],
    ) -> None:
        """
        Subscribe to notifications/indications on a characteristic.

        The ``callback`` receives only the data payload (``bytearray``).
        Bleak's native ``(sender, data)`` signature is wrapped internally.

        If already subscribed to this UUID, the call is a no-op (no
        duplicate subscriptions are created).

        Parameters
        ----------
        client : BleakClient
            A connected BleakClient.
        uuid : str
            Characteristic UUID to subscribe to.
        callback : Callable[[bytearray], None]
            Function called with each incoming data payload.
        """
        normalized = uuid.lower()
        if normalized in self._subscriptions:
            logger.debug("Already subscribed to %s — skipping", uuid)
            return

        # Wrap user callback to match Bleak's (sender, data) signature
        def _wrapped(sender: int, data: bytearray) -> None:
            callback(data)

        await client.start_notify(uuid, _wrapped)
        self._subscriptions[normalized] = callback

    async def unsubscribe(
        self,
        client: BleakClient,
        uuid: str,
    ) -> None:
        """
        Unsubscribe from notifications/indications on a characteristic.

        If not currently subscribed, logs a warning and returns without
        raising an exception.

        Parameters
        ----------
        client : BleakClient
            A connected BleakClient.
        uuid : str
            Characteristic UUID to unsubscribe from.
        """
        normalized = uuid.lower()
        if normalized not in self._subscriptions:
            logger.warning("Not subscribed to %s — nothing to unsubscribe", uuid)
            return

        await client.stop_notify(uuid)
        del self._subscriptions[normalized]

    async def unsubscribe_all(self, client: BleakClient) -> None:
        """
        Unsubscribe from all active subscriptions.

        Parameters
        ----------
        client : BleakClient
            A connected BleakClient.
        """
        # Copy keys to avoid modifying dict during iteration
        uuids = list(self._subscriptions.keys())
        for uuid in uuids:
            await client.stop_notify(uuid)
        self._subscriptions.clear()

    def is_subscribed(self, uuid: str) -> bool:
        """
        Check if a subscription is active for the given UUID.

        Parameters
        ----------
        uuid : str
            Characteristic UUID to check.

        Returns
        -------
        bool
            ``True`` if currently subscribed, ``False`` otherwise.
        """
        return uuid.lower() in self._subscriptions


# ─── Entrypoint ──────────────────────────────────────

if __name__ == "__main__":
    import asyncio
    import sys

    if len(sys.argv) < 3:
        print("Usage: python 06_notifications.py <device_address> <char_uuid>")
        sys.exit(1)

    address, char_uuid = sys.argv[1], sys.argv[2]

    async def main() -> None:
        def on_data(data: bytearray) -> None:
            print(f"  Received: {data.hex()} ({len(data)} bytes)")

        async with BleakClient(address) as client:
            print(f"Connected to {address}")
            manager = NotificationManager()
            await manager.subscribe(client, char_uuid, on_data)
            print(f"Subscribed to {char_uuid} — listening for 10 s...")
            await asyncio.sleep(10.0)
            await manager.unsubscribe(client, char_uuid)
            print("Unsubscribed.")

    asyncio.run(main())
