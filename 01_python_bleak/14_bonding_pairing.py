# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 14 — Bonding & Pairing
# Platform: Python / Bleak
# Sprint  : Sprint 14
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Trigger OS-level BLE pairing, check paired status,
#           and unpair devices. Bleak delegates pairing to the OS,
#           so behavior is heavily platform-dependent.
#
# Usage   : from 14_bonding_pairing import pair, unpair, is_paired
#
# Depends : Sprint 03 (Connection)
# ─────────────────────────────────────────────────────

"""
BLE bonding and pairing utilities.

BLE pairing establishes an encrypted link between two devices. Bonding
persists the encryption keys so future connections can re-encrypt
without user interaction. Bleak delegates pairing to the host OS,
so behavior varies significantly across platforms.

Platform Behavior
-----------------
**macOS / iOS (CoreBluetooth)**:
    - Pairing is triggered **automatically** by CoreBluetooth when
      accessing an encrypted characteristic. There is no explicit
      ``pair()`` call — the OS presents a pairing dialog when needed.
    - ``client.pair()`` in Bleak is a **no-op** on macOS and always
      returns ``True``.
    - ``client.unpair()`` is **not supported** — devices must be
      unpaired via System Preferences → Bluetooth.
    - Paired status cannot be queried programmatically.

**Linux (BlueZ)**:
    - Full pairing support via D-Bus API.
    - ``client.pair()`` triggers the BlueZ pairing agent.
    - ``client.unpair()`` removes the bonding from BlueZ.
    - Paired status queryable via ``bluetoothctl info <address>``.

**Windows (WinRT)**:
    - ``client.pair()`` triggers the Windows pairing dialog (may
      appear in the system tray notification area).
    - ``client.unpair()`` removes the device from Windows settings.
    - Paired status queryable via the WinRT DeviceInformation API.

Security Levels
---------------
BLE defines several pairing modes:

- **Just Works**: No user interaction, no MITM protection.
- **Passkey Entry**: User enters a 6-digit code on one or both devices.
- **Numeric Comparison**: Both devices display a code, user confirms match.
- **Out of Band (OOB)**: Pairing data exchanged via NFC or similar.

Bleak does not expose security level selection — the OS chooses based
on the device's I/O capabilities (Bluetooth Core Spec v5.3, Vol 3,
Part H, Section 2.3).

Reference
---------
Bluetooth Core Spec v5.3, Vol 3, Part C, Section 9 — Pairing Methods.
"""

from __future__ import annotations

import logging
import platform

from bleak import BleakClient

logger = logging.getLogger(__name__)


# ─── Functions ───────────────────────────────────────


async def pair(client: BleakClient) -> bool:
    """
    Trigger OS-level BLE pairing for a connected device.

    On macOS, this is a no-op (CoreBluetooth pairs automatically).
    On Linux and Windows, this triggers the OS pairing dialog.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.

    Returns
    -------
    bool
        ``True`` if pairing succeeded or was already established.
        ``False`` if the OS rejected the pairing request.

    Note
    ----
    The pairing dialog is **OS-controlled** — Bleak cannot customize
    or suppress it. The user may need to interact with a system dialog.
    """
    os_name = platform.system()
    logger.info("Requesting pairing on %s", os_name)

    try:
        result = await client.pair()
        logger.info("Pairing result: %s", result)
        return result
    except NotImplementedError:
        logger.warning(
            "Pairing not implemented on %s — OS handles it automatically",
            os_name,
        )
        return True
    except Exception as e:
        logger.error("Pairing failed: %s", e)
        return False


async def unpair(client: BleakClient) -> bool:
    """
    Remove BLE bonding for a connected device.

    On macOS, this is **not supported** — devices must be unpaired
    manually via System Preferences → Bluetooth.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.

    Returns
    -------
    bool
        ``True`` if unpairing succeeded.
        ``False`` if the platform does not support programmatic unpairing.
    """
    os_name = platform.system()
    logger.info("Requesting unpair on %s", os_name)

    try:
        result = await client.unpair()
        logger.info("Unpair result: %s", result)
        return result
    except NotImplementedError:
        logger.warning(
            "Unpair not implemented on %s — use OS Bluetooth settings",
            os_name,
        )
        return False
    except Exception as e:
        logger.error("Unpair failed: %s", e)
        return False


async def is_paired(client: BleakClient) -> bool | None:
    """
    Check if the connected device is currently paired/bonded.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.

    Returns
    -------
    bool | None
        ``True`` if paired, ``False`` if not paired, ``None`` if
        the platform cannot determine paired status.

    Note
    ----
    On macOS, paired status is **not queryable** via CoreBluetooth.
    This function returns ``None`` on platforms where the information
    is unavailable.
    """
    os_name = platform.system()

    try:
        # Bleak exposes _is_paired on some backends (undocumented)
        if hasattr(client, "_is_paired"):
            result = client._is_paired
            logger.info("Paired status: %s", result)
            return result

        # Attempt to pair — if already paired, returns True immediately
        # This is a heuristic: if pair() returns True without a dialog,
        # the device was likely already paired.
        logger.info("Paired status not directly queryable on %s", os_name)
        return None
    except Exception as e:
        logger.warning("Could not determine paired status: %s", e)
        return None
