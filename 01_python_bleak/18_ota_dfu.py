# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 18 — OTA / DFU (Over-the-Air Device Firmware Update)
# Platform: Python / Bleak
# Sprint  : Sprint 18
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Chunk firmware binaries, transfer over BLE with progress
#           reporting, and manage the DFU lifecycle (init → transfer
#           → validate → activate).
#
# Usage   : from 18_ota_dfu import chunk_firmware, dfu_transfer
#
# Depends : Sprint 05 (Read/Write), Sprint 12 (Error Handling),
#           Sprint 17 (Command Queue)
# ─────────────────────────────────────────────────────

"""
Over-the-Air Device Firmware Update (OTA/DFU).

DFU allows updating a BLE device's firmware without physical access.
The process transfers a binary image in chunks over GATT, with
integrity checks and a reboot into the new firmware.

DFU Lifecycle
-------------
Most BLE DFU implementations follow this sequence:

1. **Init**: Write a DFU init packet (metadata: firmware version,
   CRC, image size) to the DFU Control Point characteristic.
2. **Transfer**: Write firmware data in chunks to the DFU Data
   characteristic. Each chunk is ``MTU - 3`` bytes (ATT payload).
3. **Validate**: Request CRC validation of the transferred image.
4. **Activate**: Command the device to reboot into new firmware.

The device typically enters a special "DFU mode" (bootloader) before
accepting firmware data. This may involve writing to a control
characteristic or physically pressing a button.

Chunking
--------
BLE transfers are limited by MTU. With a typical negotiated MTU of
247 bytes, the ATT payload is 244 bytes. A 100 KB firmware image
requires ~420 writes.

Chunk size should match ``MTU - ATT_HEADER_BYTES`` (from Sprint 13).
Using a fixed default of 244 bytes works for most devices.

Vendor-Specific DFU Protocols
-----------------------------
Each BLE chip vendor has their own DFU protocol:

- **Nordic Semiconductor (nRF)**: Nordic DFU via ``nrfutil`` or
  the ``pc-ble-driver-py`` library. Uses a ZIP package containing
  init packet + firmware binary.
- **Texas Instruments (CC26xx)**: TI OAD (Over-the-Air Download).
- **Dialog Semiconductor**: SUOTA (Software Update Over The Air).
- **Espressif (ESP32)**: Custom GATT service for OTA.

This module provides **vendor-agnostic** chunking and transfer
utilities that work with any DFU protocol. Vendor-specific init
packets and validation commands must be implemented per-device.

Reference
---------
Nordic Semiconductor. (2023). DFU Protocol Specification.
Bluetooth SIG. Device Firmware Update Profile (Draft).
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import math
from dataclasses import dataclass, field
from enum import Enum, auto
from typing import Callable

logger = logging.getLogger(__name__)


# ─── Constants ───────────────────────────────────────

DEFAULT_CHUNK_SIZE: int = 244
"""
Default chunk size in bytes (MTU 247 - 3 ATT header).

This is the maximum ATT payload for the most common negotiated MTU.
Adjust based on actual ``client.mtu_size - 3``.
"""

DEFAULT_WRITE_DELAY: float = 0.02
"""
Delay between chunk writes in seconds.

Some peripherals need time to process each chunk before accepting
the next. 20 ms works for most Nordic nRF devices. Increase to
50–100 ms for slower bootloaders.
"""


# ─── Data Structures ────────────────────────────────


class DfuState(Enum):
    """
    DFU transfer state machine.

    Tracks the lifecycle of a firmware update.
    """

    IDLE = auto()
    INITIALIZING = auto()
    TRANSFERRING = auto()
    VALIDATING = auto()
    ACTIVATING = auto()
    COMPLETED = auto()
    FAILED = auto()


@dataclass
class DfuProgress:
    """
    Progress report for a DFU transfer.

    Parameters
    ----------
    bytes_sent : int
        Total bytes sent so far.
    total_bytes : int
        Total firmware image size.
    chunks_sent : int
        Number of chunks written.
    total_chunks : int
        Total number of chunks.
    state : DfuState
        Current DFU state.
    """

    bytes_sent: int
    total_bytes: int
    chunks_sent: int
    total_chunks: int
    state: DfuState

    @property
    def progress_pct(self) -> float:
        """Transfer progress as a percentage (0.0–100.0)."""
        if self.total_bytes == 0:
            return 100.0
        return (self.bytes_sent / self.total_bytes) * 100.0


@dataclass
class FirmwareImage:
    """
    A firmware binary ready for DFU transfer.

    Parameters
    ----------
    data : bytes
        Raw firmware binary.
    version : str
        Firmware version string (e.g., "5.1.0").
    sha256 : str
        SHA-256 hex digest of the firmware data.
    """

    data: bytes
    version: str = "0.0.0"
    sha256: str = field(init=False)

    def __post_init__(self) -> None:
        self.sha256 = hashlib.sha256(self.data).hexdigest()

    @property
    def size(self) -> int:
        """Firmware size in bytes."""
        return len(self.data)


# ─── Progress callback type ─────────────────────────

ProgressCallback = Callable[[DfuProgress], None]
"""Type alias for DFU progress callbacks."""


# ─── Functions ───────────────────────────────────────


def chunk_firmware(
    data: bytes,
    chunk_size: int = DEFAULT_CHUNK_SIZE,
) -> list[bytes]:
    """
    Split firmware binary into BLE-sized chunks.

    Parameters
    ----------
    data : bytes
        Raw firmware binary.
    chunk_size : int
        Maximum bytes per chunk (default: 244, matching MTU 247).

    Returns
    -------
    list[bytes]
        Ordered list of chunks. The last chunk may be smaller
        than ``chunk_size``.

    Raises
    ------
    ValueError
        If ``data`` is empty or ``chunk_size`` is not positive.

    Example
    -------
    >>> chunks = chunk_firmware(b"\\x00" * 500, chunk_size=244)
    >>> len(chunks)
    3
    >>> [len(c) for c in chunks]
    [244, 244, 12]
    """
    if not data:
        raise ValueError("Firmware data must not be empty")
    if chunk_size <= 0:
        raise ValueError(f"Chunk size must be positive, got {chunk_size}")

    chunks: list[bytes] = []
    for offset in range(0, len(data), chunk_size):
        chunks.append(data[offset : offset + chunk_size])

    logger.info(
        "Chunked %d bytes → %d chunks (chunk_size=%d, last=%d)",
        len(data),
        len(chunks),
        chunk_size,
        len(chunks[-1]),
    )

    return chunks


def compute_total_chunks(
    firmware_size: int,
    chunk_size: int = DEFAULT_CHUNK_SIZE,
) -> int:
    """
    Compute the number of chunks needed for a firmware transfer.

    Parameters
    ----------
    firmware_size : int
        Size of the firmware binary in bytes.
    chunk_size : int
        Bytes per chunk.

    Returns
    -------
    int
        Number of chunks (ceiling division).
    """
    return math.ceil(firmware_size / chunk_size)


async def dfu_transfer(
    chunks: list[bytes],
    write_fn: Callable[[bytes], object],
    on_progress: ProgressCallback | None = None,
    write_delay: float = DEFAULT_WRITE_DELAY,
) -> DfuProgress:
    """
    Transfer firmware chunks with progress reporting.

    This is the core transfer loop — it calls ``write_fn`` for each
    chunk and reports progress via ``on_progress``.

    Parameters
    ----------
    chunks : list[bytes]
        Ordered firmware chunks (from ``chunk_firmware()``).
    write_fn : Callable[[bytes], Awaitable[None] | None]
        Function to write a single chunk. Typically
        ``client.write_gatt_char(dfu_data_uuid, chunk)``.
        Can be sync or async.
    on_progress : ProgressCallback | None
        Optional callback fired after each chunk.
    write_delay : float
        Delay between writes in seconds (default: 0.02).

    Returns
    -------
    DfuProgress
        Final progress report.

    Raises
    ------
    ValueError
        If ``chunks`` is empty.
    Exception
        Any exception from ``write_fn`` is propagated (state → FAILED).
    """
    if not chunks:
        raise ValueError("No chunks to transfer")

    total_bytes = sum(len(c) for c in chunks)
    total_chunks = len(chunks)
    bytes_sent = 0

    progress = DfuProgress(
        bytes_sent=0,
        total_bytes=total_bytes,
        chunks_sent=0,
        total_chunks=total_chunks,
        state=DfuState.TRANSFERRING,
    )

    logger.info(
        "Starting DFU transfer: %d bytes in %d chunks",
        total_bytes,
        total_chunks,
    )

    for i, chunk in enumerate(chunks):
        try:
            result = write_fn(chunk)
            # Support both sync and async write functions
            if asyncio.iscoroutine(result):
                await result

            bytes_sent += len(chunk)
            progress.bytes_sent = bytes_sent
            progress.chunks_sent = i + 1

            if on_progress is not None:
                on_progress(progress)

            if write_delay > 0 and i < total_chunks - 1:
                await asyncio.sleep(write_delay)

        except Exception as exc:
            progress.state = DfuState.FAILED
            logger.error(
                "DFU transfer failed at chunk %d/%d: %s",
                i + 1,
                total_chunks,
                exc,
            )
            raise

    progress.state = DfuState.VALIDATING
    logger.info("DFU transfer complete — %d bytes sent", bytes_sent)

    return progress


def validate_firmware(
    image: FirmwareImage,
    expected_sha256: str,
) -> bool:
    """
    Validate firmware integrity before or after transfer.

    Parameters
    ----------
    image : FirmwareImage
        The firmware image to validate.
    expected_sha256 : str
        Expected SHA-256 hex digest.

    Returns
    -------
    bool
        ``True`` if the digest matches.
    """
    match = image.sha256 == expected_sha256
    if match:
        logger.info("Firmware validation passed (SHA-256 OK)")
    else:
        logger.error(
            "Firmware validation FAILED: expected %s, got %s",
            expected_sha256[:16] + "...",
            image.sha256[:16] + "...",
        )
    return match


# ─── Entrypoint ─────────────────────────────────────

if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(message)s")

    async def demo() -> None:
        print("BLE OTA/DFU — Reference Implementation")
        print("=" * 45)

        # Simulate a 1 KB firmware image
        firmware_data = bytes(range(256)) * 4  # 1024 bytes
        image = FirmwareImage(data=firmware_data, version="2.0.0")

        print(f"  Firmware: v{image.version}")
        print(f"  Size: {image.size} bytes")
        print(f"  SHA-256: {image.sha256[:32]}...")
        print()

        # Chunk it
        chunks = chunk_firmware(image.data, chunk_size=244)
        print(f"  Chunks: {len(chunks)} (sizes: {[len(c) for c in chunks]})")
        print()

        # Transfer with progress
        writes: list[bytes] = []

        async def mock_write(data: bytes) -> None:
            writes.append(data)

        def on_progress(p: DfuProgress) -> None:
            bar = "█" * int(p.progress_pct / 5) + "░" * (20 - int(p.progress_pct / 5))
            print(f"  [{bar}] {p.progress_pct:5.1f}%  chunk {p.chunks_sent}/{p.total_chunks}")

        result = await dfu_transfer(
            chunks, mock_write, on_progress=on_progress, write_delay=0.05
        )

        print()
        print(f"  State: {result.state.name}")
        print(f"  Bytes sent: {result.bytes_sent}")

        # Validate
        valid = validate_firmware(image, image.sha256)
        print(f"  Validation: {'PASS' if valid else 'FAIL'}")

    asyncio.run(demo())
