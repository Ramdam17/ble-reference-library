# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 16 — RSSI & Distance Estimation (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 16
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for RSSI distance estimation and utilities.
#
# Usage   : pytest tests/01_python_bleak/test_16_rssi_distance.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 16_rssi_distance.py — RSSI and distance estimation.

All tests are pure — no BLE hardware required.
"""

from __future__ import annotations

import importlib
import math
import sys
from pathlib import Path

import pytest

# ─── Module import ───────────────────────────────────

_MODULE_DIR = Path(__file__).resolve().parent.parent.parent / "01_python_bleak"
sys.path.insert(0, str(_MODULE_DIR))

_mod = importlib.import_module("16_rssi_distance")
estimate_distance = _mod.estimate_distance
classify_proximity = _mod.classify_proximity
rssi_to_ratio = _mod.rssi_to_ratio
smooth_rssi = _mod.smooth_rssi
ProximityZone = _mod.ProximityZone
DEFAULT_TX_POWER = _mod.DEFAULT_TX_POWER


# ─── Tests ──────────────────────────────────────────


def test_estimate_distance_at_tx_power():
    """At TX power, distance must be ~1 meter (10^0 = 1)."""
    est = estimate_distance(rssi_dbm=-59, tx_power_dbm=-59)
    assert est.distance_m == pytest.approx(1.0)
    assert est.zone == ProximityZone.NEAR


def test_estimate_distance_far():
    """A weak RSSI should produce a large distance and FAR zone."""
    est = estimate_distance(rssi_dbm=-85, tx_power_dbm=-59)
    assert est.distance_m > 3.0
    assert est.zone == ProximityZone.FAR


def test_estimate_distance_immediate():
    """A strong RSSI should produce a small distance and IMMEDIATE zone."""
    est = estimate_distance(rssi_dbm=-45, tx_power_dbm=-59)
    assert est.distance_m < 0.5
    assert est.zone == ProximityZone.IMMEDIATE


def test_estimate_distance_path_loss_exponent():
    """Higher path loss exponent should produce shorter distances for same RSSI."""
    est_free = estimate_distance(rssi_dbm=-80, path_loss_exponent=2.0)
    est_indoor = estimate_distance(rssi_dbm=-80, path_loss_exponent=3.0)
    assert est_indoor.distance_m < est_free.distance_m


def test_estimate_distance_positive_rssi_raises():
    """Positive RSSI must raise ValueError."""
    with pytest.raises(ValueError, match="RSSI must be negative"):
        estimate_distance(rssi_dbm=5)


def test_estimate_distance_invalid_exponent_raises():
    """Non-positive path loss exponent must raise ValueError."""
    with pytest.raises(ValueError, match="Path loss exponent must be positive"):
        estimate_distance(rssi_dbm=-70, path_loss_exponent=0)


def test_classify_proximity_boundaries():
    """Proximity zones must match iBeacon thresholds."""
    assert classify_proximity(0.1) == ProximityZone.IMMEDIATE
    assert classify_proximity(0.49) == ProximityZone.IMMEDIATE
    assert classify_proximity(0.5) == ProximityZone.NEAR
    assert classify_proximity(3.0) == ProximityZone.NEAR
    assert classify_proximity(3.01) == ProximityZone.FAR


def test_rssi_to_ratio_bounds():
    """rssi_to_ratio must clamp to [0.0, 1.0]."""
    assert rssi_to_ratio(-59, tx_power_dbm=-59) == pytest.approx(1.0)
    assert rssi_to_ratio(-100, tx_power_dbm=-59) == pytest.approx(0.0)
    # Beyond bounds — should clamp
    assert rssi_to_ratio(-120, tx_power_dbm=-59) == pytest.approx(0.0)
    assert rssi_to_ratio(-30, tx_power_dbm=-59) == pytest.approx(1.0)


def test_rssi_to_ratio_midpoint():
    """Midpoint RSSI should give ~0.5 ratio."""
    mid = (-100 + -59) // 2  # ~ -79
    ratio = rssi_to_ratio(mid, tx_power_dbm=-59)
    assert 0.4 < ratio < 0.6


def test_smooth_rssi_constant():
    """Smoothing constant readings must return the same values."""
    readings = [-70, -70, -70, -70, -70]
    smoothed = smooth_rssi(readings)
    for s in smoothed:
        assert s == pytest.approx(-70.0)


def test_smooth_rssi_reduces_variance():
    """Smoothed RSSI must have lower variance than raw."""
    raw = [-65, -80, -55, -85, -60, -75, -58, -82, -63, -77]
    smoothed = smooth_rssi(raw, alpha=0.3)

    raw_var = sum((r - sum(raw) / len(raw)) ** 2 for r in raw) / len(raw)
    smooth_var = sum((s - sum(smoothed) / len(smoothed)) ** 2 for s in smoothed) / len(smoothed)
    assert smooth_var < raw_var


def test_smooth_rssi_invalid_alpha():
    """Alpha outside (0, 1) must raise ValueError."""
    with pytest.raises(ValueError, match="alpha must be in"):
        smooth_rssi([-70], alpha=0.0)

    with pytest.raises(ValueError, match="alpha must be in"):
        smooth_rssi([-70], alpha=1.0)


def test_smooth_rssi_empty_raises():
    """Empty readings must raise ValueError."""
    with pytest.raises(ValueError, match="readings must not be empty"):
        smooth_rssi([])
