# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 11 — Muse EEG Example (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 11
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for Muse EEG packet parser with forged byte sequences.
#
# Usage   : pytest tests/01_python_bleak/test_11_muse_example.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 11_muse_example.py — Muse EEG parser.

All tests use forged bytearrays — no BLE hardware required.
Byte sequences follow the Muse 2 proprietary BLE protocol:
2 bytes counter (uint16 BE) + 18 bytes packed 12-bit samples.
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

_mod = importlib.import_module("11_muse_example")
parse_eeg_packet = _mod.parse_eeg_packet
parse_telemetry = _mod.parse_telemetry
subscribe_muse_eeg = _mod.subscribe_muse_eeg
stop_muse_eeg = _mod.stop_muse_eeg
MuseEegPacket = _mod.MuseEegPacket
MuseTelemetry = _mod.MuseTelemetry
MUSE_SERVICE_UUID = _mod.MUSE_SERVICE_UUID
MUSE_CONTROL_UUID = _mod.MUSE_CONTROL_UUID
MUSE_CHANNEL_UUIDS = _mod.MUSE_CHANNEL_UUIDS
MUSE_CMD_ASK = _mod.MUSE_CMD_ASK
MUSE_CMD_STREAM = _mod.MUSE_CMD_STREAM
MUSE_SCALE_FACTOR = _mod.MUSE_SCALE_FACTOR
MUSE_SAMPLES_PER_PACKET = _mod.MUSE_SAMPLES_PER_PACKET
_unpack_uint12 = _mod._unpack_uint12


# ─── Helpers ─────────────────────────────────────────


def _make_service(uuid: str):
    """Create a mock BleakGATTService."""
    service = MagicMock()
    service.uuid = uuid
    return service


def _make_client(has_muse: bool = True):
    """Create a mock BleakClient with optional Muse service."""
    client = MagicMock()
    services = []
    if has_muse:
        services.append(_make_service(MUSE_SERVICE_UUID))
    client.services = services
    client.start_notify = AsyncMock()
    client.write_gatt_char = AsyncMock()
    client.stop_notify = AsyncMock()
    return client


def _pack_uint12_pair(a: int, b: int) -> bytes:
    """Pack two 12-bit values into 3 bytes.

    Layout: AAAAAAAA AAAABBBB BBBBBBBB
    """
    byte0 = (a >> 4) & 0xFF
    byte1 = ((a & 0x0F) << 4) | ((b >> 8) & 0x0F)
    byte2 = b & 0xFF
    return bytes([byte0, byte1, byte2])


def _build_eeg_packet(counter: int, raw_samples: list[int]) -> bytearray:
    """Forge a 20-byte Muse EEG notification from known values.

    Parameters
    ----------
    counter : int
        Packet sequence number (uint16 BE).
    raw_samples : list[int]
        12 raw 12-bit sample values (0–4095).
    """
    assert len(raw_samples) == 12
    data = bytearray(counter.to_bytes(2, "big"))
    for i in range(0, 12, 2):
        data += _pack_uint12_pair(raw_samples[i], raw_samples[i + 1])
    return data


# ─── Parser Tests ────────────────────────────────────


def test_unpack_uint12_basic():
    """_unpack_uint12 must correctly unpack 12 × 12-bit values from 18 bytes."""
    # All samples = 2048 (ADC midpoint, 0x800)
    raw = [2048] * 12
    packet = _build_eeg_packet(counter=0, raw_samples=raw)
    result = _unpack_uint12(packet, offset=2)
    assert result == raw


def test_unpack_uint12_mixed_values():
    """_unpack_uint12 must handle mixed 12-bit values including 0 and 4095."""
    raw = [0, 4095, 1, 2047, 2048, 2049, 100, 3000, 500, 3500, 1234, 4000]
    packet = _build_eeg_packet(counter=42, raw_samples=raw)
    result = _unpack_uint12(packet, offset=2)
    assert result == raw


def test_parse_eeg_packet_basic():
    """parse_eeg_packet must decode counter and convert samples to µV."""
    # All samples at ADC midpoint (2048) → 0 µV
    raw = [2048] * 12
    packet = _build_eeg_packet(counter=100, raw_samples=raw)

    result = parse_eeg_packet("TP9", packet)

    assert result.channel == "TP9"
    assert result.counter == 100
    assert len(result.samples_uv) == 12
    assert all(s == pytest.approx(0.0) for s in result.samples_uv)


def test_parse_eeg_packet_scaling():
    """parse_eeg_packet must apply correct µV scaling.

    µV = 0.48828125 × (raw - 2048)
    raw=0 → -1000 µV, raw=4095 → +999.5 µV, raw=2048 → 0 µV
    """
    raw = [0, 4095, 2048, 1024, 3072, 2048, 2048, 2048, 2048, 2048, 2048, 2048]
    packet = _build_eeg_packet(counter=5, raw_samples=raw)

    result = parse_eeg_packet("AF7", packet)

    # raw=0: 0.48828125 * (0 - 2048) = -1000.0
    assert result.samples_uv[0] == pytest.approx(-1000.0)
    # raw=4095: 0.48828125 * (4095 - 2048) = 999.51171875
    assert result.samples_uv[1] == pytest.approx(999.51171875)
    # raw=2048: 0.0
    assert result.samples_uv[2] == pytest.approx(0.0)
    # raw=1024: 0.48828125 * (1024 - 2048) = -500.0
    assert result.samples_uv[3] == pytest.approx(-500.0)
    # raw=3072: 0.48828125 * (3072 - 2048) = +500.0
    assert result.samples_uv[4] == pytest.approx(500.0)


def test_parse_eeg_packet_counter():
    """parse_eeg_packet must extract the uint16 big-endian counter."""
    # Counter = 0x1234 = 4660
    raw = [2048] * 12
    packet = _build_eeg_packet(counter=4660, raw_samples=raw)

    result = parse_eeg_packet("AF8", packet)
    assert result.counter == 4660


def test_parse_telemetry_battery():
    """parse_telemetry must extract battery percentage from raw data."""
    # 5 × uint16 BE: [counter, battery_raw, fuel, adc, temp]
    # battery_raw = 25600 → 25600 / 512.0 = 50.0%
    data = bytearray()
    data += (1).to_bytes(2, "big")      # counter
    data += (25600).to_bytes(2, "big")  # battery_raw → 50%
    data += (0).to_bytes(2, "big")      # fuel gauge
    data += (0).to_bytes(2, "big")      # ADC
    data += (0).to_bytes(2, "big")      # temperature

    result = parse_telemetry(data)
    assert result.battery_percent == pytest.approx(50.0)


# ─── Subscribe Tests ────────────────────────────────


@pytest.mark.asyncio
async def test_subscribe_muse_eeg_handshake():
    """subscribe_muse_eeg must subscribe to 4 channels and write ASK + STREAM."""
    client = _make_client(has_muse=True)

    await subscribe_muse_eeg(client, lambda p: None)

    # 4 channels subscribed
    assert client.start_notify.await_count == 4
    subscribed_uuids = [call[0][0] for call in client.start_notify.call_args_list]
    for uuid in MUSE_CHANNEL_UUIDS:
        assert uuid in subscribed_uuids

    # ASK then STREAM written to control point (write-without-response)
    assert client.write_gatt_char.await_count == 2
    calls = client.write_gatt_char.call_args_list
    assert calls[0] == ((MUSE_CONTROL_UUID, MUSE_CMD_ASK), {"response": False})
    assert calls[1] == ((MUSE_CONTROL_UUID, MUSE_CMD_STREAM), {"response": False})


@pytest.mark.asyncio
async def test_subscribe_muse_eeg_service_absent():
    """subscribe_muse_eeg must raise ValueError when Muse service is absent."""
    client = _make_client(has_muse=False)

    with pytest.raises(ValueError, match="Muse GATT service"):
        await subscribe_muse_eeg(client, lambda p: None)

    client.start_notify.assert_not_awaited()


@pytest.mark.asyncio
async def test_subscribe_muse_eeg_callback_invoked():
    """subscribe_muse_eeg must parse notifications and invoke callback."""
    client = _make_client(has_muse=True)
    received: list[MuseEegPacket] = []

    await subscribe_muse_eeg(client, lambda p: received.append(p))

    # Find the handler registered for TP9 (first channel)
    tp9_call = client.start_notify.call_args_list[0]
    handler = tp9_call[0][1]

    # Simulate a notification with known values
    raw = [2048] * 12  # all zero µV
    packet = _build_eeg_packet(counter=7, raw_samples=raw)
    handler(20, packet)

    assert len(received) == 1
    assert received[0].channel == "TP9"
    assert received[0].counter == 7
    assert all(s == pytest.approx(0.0) for s in received[0].samples_uv)
