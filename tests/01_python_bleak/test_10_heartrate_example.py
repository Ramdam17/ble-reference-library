# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 10 — Heart Rate Monitor Example (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 10
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for HRM packet parser with forged byte sequences.
#
# Usage   : pytest tests/01_python_bleak/test_10_heartrate_example.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 10_heartrate_example.py — Heart Rate Measurement parser.

All tests use forged bytearrays — no BLE hardware required.
Byte sequences are based on the Bluetooth Heart Rate Service spec v1.0.
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

_mod = importlib.import_module("10_heartrate_example")
parse_heart_rate_measurement = _mod.parse_heart_rate_measurement
subscribe_heart_rate = _mod.subscribe_heart_rate
HeartRateMeasurement = _mod.HeartRateMeasurement
HEART_RATE_SERVICE_UUID = _mod.HEART_RATE_SERVICE_UUID
HEART_RATE_CHAR_UUID = _mod.HEART_RATE_CHAR_UUID
RR_INTERVAL_RESOLUTION = _mod.RR_INTERVAL_RESOLUTION

parse_ecg_frame = _mod.parse_ecg_frame
subscribe_ecg = _mod.subscribe_ecg
stop_ecg = _mod.stop_ecg
PolarEcgFrame = _mod.PolarEcgFrame
PMD_SERVICE_UUID = _mod.PMD_SERVICE_UUID
PMD_CONTROL_UUID = _mod.PMD_CONTROL_UUID
PMD_DATA_UUID = _mod.PMD_DATA_UUID
ECG_MEASUREMENT_TYPE = _mod.ECG_MEASUREMENT_TYPE
PMD_START_COMMAND = _mod.PMD_START_COMMAND


# ─── Helpers ─────────────────────────────────────────


def _make_service(uuid: str):
    """Create a mock BleakGATTService."""
    service = MagicMock()
    service.uuid = uuid
    return service


def _make_client(has_hr: bool = True, has_pmd: bool = False):
    """Create a mock BleakClient with optional Heart Rate / PMD Service."""
    client = MagicMock()
    services = []
    if has_hr:
        services.append(_make_service(HEART_RATE_SERVICE_UUID))
    if has_pmd:
        services.append(_make_service(PMD_SERVICE_UUID))
    client.services = services
    client.start_notify = AsyncMock()
    client.write_gatt_char = AsyncMock()
    return client


# ─── Parser Tests ────────────────────────────────────


def test_parse_hrm_uint8_bpm():
    """UINT8 BPM format (flag bit 0 = 0): BPM in a single byte."""
    # Flags: 0x00 = UINT8 BPM, no contact, no EE, no RR
    # BPM: 72
    data = bytearray([0x00, 72])

    result = parse_heart_rate_measurement(data)

    assert result.bpm == 72
    assert result.rr_intervals == []
    assert result.sensor_contact is None


def test_parse_hrm_uint16_bpm():
    """UINT16 BPM format (flag bit 0 = 1): BPM in two bytes little-endian."""
    # Flags: 0x01 = UINT16 BPM
    # BPM: 260 = 0x0104 → little-endian: [0x04, 0x01]
    data = bytearray([0x01, 0x04, 0x01])

    result = parse_heart_rate_measurement(data)

    assert result.bpm == 260
    assert result.rr_intervals == []


def test_parse_hrm_with_rr_intervals():
    """RR intervals present (flag bit 4 = 1): one or more UINT16 values."""
    # Flags: 0x10 = UINT8 BPM + RR present
    # BPM: 75
    # RR1: 800 (in 1/1024s units) → 800/1024 = 0.78125s
    # RR2: 850 → 850/1024 ≈ 0.830078s
    rr1 = 800  # 0x0320
    rr2 = 850  # 0x0352
    data = bytearray([0x10, 75])
    data += rr1.to_bytes(2, "little")
    data += rr2.to_bytes(2, "little")

    result = parse_heart_rate_measurement(data)

    assert result.bpm == 75
    assert len(result.rr_intervals) == 2
    assert result.rr_intervals[0] == pytest.approx(800 / 1024.0)
    assert result.rr_intervals[1] == pytest.approx(850 / 1024.0)


def test_parse_hrm_no_rr_intervals():
    """No RR intervals (flag bit 4 = 0): rr_intervals must be empty."""
    # Flags: 0x00 = UINT8 BPM, no RR
    data = bytearray([0x00, 65])

    result = parse_heart_rate_measurement(data)

    assert result.rr_intervals == []


def test_parse_hrm_sensor_contact_flag():
    """Sensor contact status (flag bits 1-2)."""
    # 0b00 or 0b01 = not supported → None
    data_no_support = bytearray([0x00, 70])
    assert parse_heart_rate_measurement(data_no_support).sensor_contact is None

    # 0b10 = supported, NOT detected → False
    # Flags: bits 1-2 = 10 → 0x04
    data_no_contact = bytearray([0x04, 70])
    assert parse_heart_rate_measurement(data_no_contact).sensor_contact is False

    # 0b11 = supported, detected → True
    # Flags: bits 1-2 = 11 → 0x06
    data_contact = bytearray([0x06, 70])
    assert parse_heart_rate_measurement(data_contact).sensor_contact is True


def test_rr_interval_unit_conversion():
    """RR intervals must be converted from 1/1024s units to seconds."""
    # 1024 units = exactly 1.0 second
    # 512 units = exactly 0.5 seconds
    data = bytearray([0x10, 80])
    data += (1024).to_bytes(2, "little")
    data += (512).to_bytes(2, "little")

    result = parse_heart_rate_measurement(data)

    assert result.rr_intervals[0] == pytest.approx(1.0)
    assert result.rr_intervals[1] == pytest.approx(0.5)


def test_parse_hrm_with_energy_expended():
    """Energy Expended field (flag bit 3) must be skipped correctly."""
    # Flags: 0x18 = UINT8 BPM + Energy Expended + RR present
    # BPM: 90
    # Energy Expended: 1500 kJ (UINT16, skip)
    # RR: 750 units
    ee = 1500
    rr = 750
    data = bytearray([0x18, 90])
    data += ee.to_bytes(2, "little")
    data += rr.to_bytes(2, "little")

    result = parse_heart_rate_measurement(data)

    assert result.bpm == 90
    assert len(result.rr_intervals) == 1
    assert result.rr_intervals[0] == pytest.approx(750 / 1024.0)


# ─── Subscribe Tests ────────────────────────────────


@pytest.mark.asyncio
async def test_subscribe_heart_rate_callback_invoked():
    """subscribe_heart_rate must wrap raw bytes into HeartRateMeasurement."""
    client = _make_client(has_hr=True)
    received = []

    await subscribe_heart_rate(client, lambda m: received.append(m))

    # Extract the wrapped callback passed to start_notify
    wrapped = client.start_notify.call_args[0][1]

    # Simulate notification: UINT8 BPM=85, no RR
    wrapped(20, bytearray([0x00, 85]))

    assert len(received) == 1
    assert received[0].bpm == 85


@pytest.mark.asyncio
async def test_subscribe_heart_rate_service_absent():
    """subscribe_heart_rate must raise ValueError when HR Service is absent."""
    client = _make_client(has_hr=False)

    with pytest.raises(ValueError, match="Heart Rate Service"):
        await subscribe_heart_rate(client, lambda m: None)

    client.start_notify.assert_not_awaited()


# ─── ECG Parser Tests ──────────────────────────────


def _build_ecg_packet(timestamp_ns: int, samples: list[int]) -> bytearray:
    """Forge a PMD ECG notification packet from known values."""
    data = bytearray([ECG_MEASUREMENT_TYPE])  # byte 0: type
    data += timestamp_ns.to_bytes(8, "little", signed=False)  # bytes 1-8: timestamp
    data += bytearray([0x00])  # byte 9: frame type (raw)
    for sample in samples:
        data += sample.to_bytes(3, "little", signed=True)  # int24 LE
    return data


def test_parse_ecg_frame_basic():
    """parse_ecg_frame must decode timestamp and positive int24 samples."""
    ts = 1_000_000_000  # 1 second in ns
    samples = [100, 250, 500]
    data = _build_ecg_packet(ts, samples)

    frame = parse_ecg_frame(data)

    assert frame.timestamp_ns == ts
    assert frame.samples_uv == [100, 250, 500]
    assert frame.sample_rate == 130


def test_parse_ecg_frame_negative_values():
    """parse_ecg_frame must handle negative int24 values (signed)."""
    ts = 2_000_000_000
    # Negative values represent descending phase of QRS complex
    samples = [-100, -1, -8388608]  # min int24 = -2^23
    data = _build_ecg_packet(ts, samples)

    frame = parse_ecg_frame(data)

    assert frame.samples_uv == [-100, -1, -8388608]


def test_parse_ecg_frame_empty_samples():
    """parse_ecg_frame with no sample bytes must return empty list."""
    # Just header: type(1) + timestamp(8) + frame_type(1) = 10 bytes, no samples
    data = bytearray([0x00])
    data += (0).to_bytes(8, "little")
    data += bytearray([0x00])

    frame = parse_ecg_frame(data)

    assert frame.samples_uv == []
    assert frame.timestamp_ns == 0


@pytest.mark.asyncio
async def test_subscribe_ecg_start_command():
    """subscribe_ecg must write the START command to the PMD Control Point."""
    client = _make_client(has_hr=True, has_pmd=True)

    await subscribe_ecg(client, lambda f: None)

    # Verify start_notify was called on PMD Data UUID
    client.start_notify.assert_awaited_once()
    notify_uuid = client.start_notify.call_args[0][0]
    assert notify_uuid == PMD_DATA_UUID

    # Verify write_gatt_char was called with START command on Control Point
    client.write_gatt_char.assert_awaited_once_with(
        PMD_CONTROL_UUID, PMD_START_COMMAND, response=True
    )


@pytest.mark.asyncio
async def test_subscribe_ecg_service_absent():
    """subscribe_ecg must raise ValueError when PMD Service is absent."""
    client = _make_client(has_hr=True, has_pmd=False)

    with pytest.raises(ValueError, match="Polar PMD Service"):
        await subscribe_ecg(client, lambda f: None)

    client.start_notify.assert_not_awaited()


@pytest.mark.asyncio
async def test_subscribe_ecg_callback_invoked():
    """subscribe_ecg must parse PMD notifications and invoke callback."""
    client = _make_client(has_hr=True, has_pmd=True)
    received: list[PolarEcgFrame] = []

    await subscribe_ecg(client, lambda f: received.append(f))

    # Extract the notification callback registered on PMD_DATA_UUID
    wrapped = client.start_notify.call_args[0][1]

    # Simulate a PMD ECG notification with 2 samples
    packet = _build_ecg_packet(5_000_000_000, [42, -42])
    wrapped(20, packet)

    assert len(received) == 1
    assert received[0].samples_uv == [42, -42]
    assert received[0].timestamp_ns == 5_000_000_000
