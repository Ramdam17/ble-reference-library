# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 13 — MTU Negotiation
# Platform: Python / Bleak
# Sprint  : Sprint 13
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Negotiate and query the Maximum Transmission Unit (MTU)
#           for a BLE connection. Compute effective payload size.
#
# Usage   : from 13_mtu_negotiation import request_mtu, effective_payload_size
#
# Depends : Sprint 03 (Connection)
# ─────────────────────────────────────────────────────

"""
MTU negotiation and payload size utilities.

The Maximum Transmission Unit (MTU) determines the largest single
ATT packet a BLE connection can carry. A larger MTU means fewer
round-trips for bulk transfers (e.g., firmware updates, EEG streaming).

The default BLE MTU is 23 bytes, of which 3 are ATT protocol overhead,
leaving **20 bytes** of usable payload. Modern devices typically
negotiate 247 or 512 bytes.

Platform Behavior
-----------------
MTU negotiation is **platform-dependent** in Bleak:

- **Linux (BlueZ)**: Full MTU negotiation via ``client.mtu_size``.
  The peripheral and central agree on the smaller of their maximums.
- **macOS/iOS (CoreBluetooth)**: The OS negotiates MTU automatically
  during connection. ``client.mtu_size`` returns the current value
  but the application **cannot request** a specific MTU.
- **Windows (WinRT)**: Similar to macOS — OS-managed negotiation.
  Practical cap at 512 bytes. ``client.mtu_size`` available after connect.
- **Android**: Negotiation via ``requestMtu()`` in the Java/Kotlin
  BLE API (not directly exposed by Bleak, but the Android OS
  typically negotiates the maximum supported MTU automatically).

In all cases, ``client.mtu_size`` returns the **negotiated** MTU
after connection. This module wraps that property with validation
and payload computation.

Reference
---------
Bluetooth Core Spec v5.3, Vol 3, Part F, Section 3.4.2 —
Exchange MTU Request/Response.
"""

from __future__ import annotations

import logging

from bleak import BleakClient

logger = logging.getLogger(__name__)

# ─── Constants ───────────────────────────────────────

DEFAULT_MTU: int = 23
"""
BLE specification minimum MTU (bytes).

All BLE devices must support at least 23 bytes. This provides
20 bytes of payload after the 3-byte ATT header.

Reference: Bluetooth Core Spec v5.3, Vol 3, Part F, Section 3.2.9.
"""

ATT_HEADER_BYTES: int = 3
"""
ATT protocol overhead per packet (bytes).

Every ATT PDU includes a 1-byte opcode + 2-byte handle = 3 bytes.
The remaining MTU - 3 bytes are available for payload data.
"""

MAX_BLE_MTU: int = 517
"""
Maximum allowed MTU per BLE specification.

Bluetooth Core Spec v5.3 defines the maximum ATT_MTU as 517 bytes.
"""


# ─── Functions ───────────────────────────────────────


def effective_payload_size(mtu: int) -> int:
    """
    Compute the usable payload size for a given MTU.

    The effective payload is ``mtu - ATT_HEADER_BYTES`` (3 bytes for
    ATT opcode + handle).

    Parameters
    ----------
    mtu : int
        Negotiated MTU in bytes.

    Returns
    -------
    int
        Usable payload size in bytes.

    Raises
    ------
    ValueError
        If ``mtu`` is below the BLE minimum (23 bytes).

    Example
    -------
    >>> effective_payload_size(247)
    244
    >>> effective_payload_size(23)
    20
    """
    if mtu < DEFAULT_MTU:
        raise ValueError(
            f"MTU {mtu} is below the BLE minimum ({DEFAULT_MTU}). "
            "All BLE devices must support at least 23 bytes."
        )
    return mtu - ATT_HEADER_BYTES


async def request_mtu(client: BleakClient, mtu: int = 512) -> int:
    """
    Query the negotiated MTU for an active BLE connection.

    On all platforms, Bleak exposes ``client.mtu_size`` after connection.
    The actual negotiation is handled by the OS — this function returns
    the result and logs it for debugging.

    Parameters
    ----------
    client : BleakClient
        A connected BleakClient.
    mtu : int
        Desired MTU (used for logging only on macOS/Windows where
        the application cannot control negotiation). Default: 512.

    Returns
    -------
    int
        The negotiated MTU in bytes.

    Raises
    ------
    ValueError
        If ``mtu`` is below the BLE minimum (23 bytes).

    Note
    ----
    On **macOS/iOS and Windows**, the ``mtu`` parameter is informational
    only — the OS negotiates the MTU automatically. On **Linux (BlueZ)**,
    the MTU may be influenced by the peripheral's capabilities.
    """
    if mtu < DEFAULT_MTU:
        raise ValueError(
            f"Requested MTU {mtu} is below the BLE minimum ({DEFAULT_MTU})."
        )

    negotiated = client.mtu_size
    payload = effective_payload_size(negotiated)

    logger.info(
        "MTU: requested=%d, negotiated=%d, payload=%d bytes",
        mtu,
        negotiated,
        payload,
    )

    return negotiated
