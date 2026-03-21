# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 18 — OTA / DFU (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 18
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for DFU chunking, transfer, validation,
#           and progress reporting.
#
# Usage   : pytest tests/01_python_bleak/test_18_ota_dfu.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 18_ota_dfu.py — OTA/DFU utilities.

All tests are pure async — no BLE hardware required.
"""

from __future__ import annotations

import hashlib
import importlib
import sys
from pathlib import Path
from unittest.mock import AsyncMock

import pytest

# ─── Module import ───────────────────────────────────

_MODULE_DIR = Path(__file__).resolve().parent.parent.parent / "01_python_bleak"
sys.path.insert(0, str(_MODULE_DIR))

_mod = importlib.import_module("18_ota_dfu")
chunk_firmware = _mod.chunk_firmware
compute_total_chunks = _mod.compute_total_chunks
dfu_transfer = _mod.dfu_transfer
validate_firmware = _mod.validate_firmware
FirmwareImage = _mod.FirmwareImage
DfuState = _mod.DfuState
DfuProgress = _mod.DfuProgress
DEFAULT_CHUNK_SIZE = _mod.DEFAULT_CHUNK_SIZE


# ─── Tests — Chunking ───────────────────────────────


def test_chunk_firmware_exact_division():
    """Firmware evenly divisible by chunk_size must produce equal chunks."""
    data = b"\x00" * 488  # 2 × 244
    chunks = chunk_firmware(data, chunk_size=244)
    assert len(chunks) == 2
    assert all(len(c) == 244 for c in chunks)


def test_chunk_firmware_remainder():
    """500 bytes / 244 = 3 chunks (244, 244, 12)."""
    data = b"\xAB" * 500
    chunks = chunk_firmware(data, chunk_size=244)
    assert len(chunks) == 3
    assert len(chunks[0]) == 244
    assert len(chunks[1]) == 244
    assert len(chunks[2]) == 12


def test_chunk_firmware_single_chunk():
    """Data smaller than chunk_size must produce one chunk."""
    data = b"\x01\x02\x03"
    chunks = chunk_firmware(data, chunk_size=244)
    assert len(chunks) == 1
    assert chunks[0] == data


def test_chunk_firmware_preserves_data():
    """Concatenating chunks must reconstruct the original firmware."""
    data = bytes(range(256)) * 3  # 768 bytes
    chunks = chunk_firmware(data, chunk_size=244)
    reassembled = b"".join(chunks)
    assert reassembled == data


def test_chunk_firmware_empty_raises():
    """Empty firmware must raise ValueError."""
    with pytest.raises(ValueError, match="must not be empty"):
        chunk_firmware(b"")


def test_chunk_firmware_invalid_size_raises():
    """Non-positive chunk_size must raise ValueError."""
    with pytest.raises(ValueError, match="must be positive"):
        chunk_firmware(b"\x00", chunk_size=0)


def test_compute_total_chunks():
    """compute_total_chunks must match actual chunk count."""
    assert compute_total_chunks(500, 244) == 3
    assert compute_total_chunks(488, 244) == 2
    assert compute_total_chunks(1, 244) == 1
    assert compute_total_chunks(244, 244) == 1


# ─── Tests — FirmwareImage ──────────────────────────


def test_firmware_image_sha256():
    """FirmwareImage must compute correct SHA-256."""
    data = b"hello firmware"
    image = FirmwareImage(data=data, version="1.0.0")
    expected = hashlib.sha256(data).hexdigest()
    assert image.sha256 == expected
    assert image.size == len(data)


# ─── Tests — Transfer ───────────────────────────────


@pytest.mark.asyncio
async def test_dfu_transfer_all_chunks():
    """dfu_transfer must write every chunk and report final progress."""
    data = b"\xFF" * 500
    chunks = chunk_firmware(data, chunk_size=244)
    written: list[bytes] = []

    write_fn = AsyncMock(side_effect=lambda c: written.append(c))

    result = await dfu_transfer(
        chunks, write_fn, write_delay=0.0
    )

    assert len(written) == 3
    assert b"".join(written) == data
    assert result.bytes_sent == 500
    assert result.chunks_sent == 3
    assert result.state == DfuState.VALIDATING


@pytest.mark.asyncio
async def test_dfu_transfer_progress_callback():
    """on_progress must fire once per chunk."""
    chunks = chunk_firmware(b"\x00" * 100, chunk_size=50)
    reports: list[DfuProgress] = []

    write_fn = AsyncMock()

    await dfu_transfer(
        chunks, write_fn,
        on_progress=lambda p: reports.append(
            DfuProgress(
                bytes_sent=p.bytes_sent,
                total_bytes=p.total_bytes,
                chunks_sent=p.chunks_sent,
                total_chunks=p.total_chunks,
                state=p.state,
            )
        ),
        write_delay=0.0,
    )

    assert len(reports) == 2
    assert reports[0].chunks_sent == 1
    assert reports[0].progress_pct == pytest.approx(50.0)
    assert reports[1].chunks_sent == 2
    assert reports[1].progress_pct == pytest.approx(100.0)


@pytest.mark.asyncio
async def test_dfu_transfer_write_failure():
    """Write failure must set state to FAILED and propagate the exception."""
    chunks = chunk_firmware(b"\x00" * 500, chunk_size=244)

    call_count = 0

    async def fail_on_second(data: bytes) -> None:
        nonlocal call_count
        call_count += 1
        if call_count == 2:
            raise ConnectionError("BLE write failed")

    with pytest.raises(ConnectionError, match="BLE write failed"):
        await dfu_transfer(chunks, fail_on_second, write_delay=0.0)


@pytest.mark.asyncio
async def test_dfu_transfer_empty_raises():
    """Empty chunk list must raise ValueError."""
    with pytest.raises(ValueError, match="No chunks"):
        await dfu_transfer([], AsyncMock(), write_delay=0.0)


# ─── Tests — Validation ─────────────────────────────


def test_validate_firmware_pass():
    """validate_firmware must return True for matching digest."""
    data = b"valid firmware"
    image = FirmwareImage(data=data)
    assert validate_firmware(image, image.sha256) is True


def test_validate_firmware_fail():
    """validate_firmware must return False for mismatched digest."""
    image = FirmwareImage(data=b"firmware")
    assert validate_firmware(image, "0" * 64) is False


# ─── Tests — DfuProgress ────────────────────────────


def test_progress_pct():
    """progress_pct must compute correctly."""
    p = DfuProgress(
        bytes_sent=250, total_bytes=500,
        chunks_sent=1, total_chunks=2,
        state=DfuState.TRANSFERRING,
    )
    assert p.progress_pct == pytest.approx(50.0)


def test_progress_pct_zero_total():
    """progress_pct must return 100% for empty firmware."""
    p = DfuProgress(
        bytes_sent=0, total_bytes=0,
        chunks_sent=0, total_chunks=0,
        state=DfuState.COMPLETED,
    )
    assert p.progress_pct == pytest.approx(100.0)
