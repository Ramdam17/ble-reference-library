# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 16 — RSSI & Distance Estimation
# Platform: Python / Bleak
# Sprint  : Sprint 16
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Read RSSI from BLE advertisements, estimate distance
#           using the log-distance path loss model, and classify
#           proximity zones (immediate / near / far).
#
# Usage   : from 16_rssi_distance import estimate_distance, classify_proximity
#
# Depends : Sprint 01 (Scanning)
# ─────────────────────────────────────────────────────

"""
RSSI monitoring and distance estimation.

RSSI (Received Signal Strength Indicator) is reported in **dBm** by
BLE radios during scanning. It reflects the power level of received
advertisements and can be used to *estimate* — **not measure** —
distance between devices.

Accuracy Warning
----------------
BLE-based distance estimation is inherently **approximate (±50–200%)**
due to:

- **Multipath**: Signals bounce off walls, floors, furniture.
- **Body absorption**: A device in a pocket reads ~10 dB lower.
- **Antenna orientation**: Polarization mismatch attenuates the signal.
- **Environmental interference**: Wi-Fi, microwaves, other 2.4 GHz devices.
- **TX power variability**: Different devices transmit at different powers.

For research purposes (e.g., proximity detection in hyperscanning setups),
RSSI is useful for **relative proximity** (closer/farther) but should
never be trusted for absolute distance below ~1 m accuracy.

Log-Distance Path Loss Model
-----------------------------
The standard model for BLE distance estimation:

    RSSI = TX_power - 10 × n × log₁₀(d)

Where:
    - ``RSSI``: Measured signal strength (dBm)
    - ``TX_power``: RSSI at 1 meter (dBm), device-specific calibration value
    - ``n``: Path loss exponent (2.0 in free space, 2.5–4.0 indoors)
    - ``d``: Distance in meters

Solving for distance:

    d = 10 ^ ((TX_power - RSSI) / (10 × n))

Reference
---------
Rappaport, T.S. (2002). Wireless Communications: Principles and Practice.
Chapter 4 — Large-Scale Path Loss.

iBeacon proximity zones follow Apple's classification:
    - Immediate: < 0.5 m
    - Near: 0.5 – 3.0 m
    - Far: > 3.0 m
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass
from enum import Enum

logger = logging.getLogger(__name__)


# ─── Constants ───────────────────────────────────────

DEFAULT_TX_POWER: int = -59
"""
Default TX power at 1 meter (dBm).

This is the iBeacon standard calibration value. Real devices vary:
- Polar H10: ~-65 dBm
- Apple Watch: ~-59 dBm
- Muse 2: ~-55 dBm

For best results, calibrate per-device by measuring RSSI at exactly
1 meter in a clear line-of-sight environment.
"""

DEFAULT_PATH_LOSS_EXPONENT: float = 2.0
"""
Path loss exponent for the log-distance model.

Common values:
- 2.0: Free space (theoretical ideal)
- 2.5: Open indoor (lab, large room)
- 3.0: Typical indoor (office, corridors)
- 4.0: Dense indoor (cluttered, many walls)

Reference: Rappaport (2002), Table 4.2.
"""


# ─── Data Structures ────────────────────────────────


class ProximityZone(Enum):
    """
    BLE proximity classification (iBeacon-style).

    These zones provide a human-readable proximity estimate when
    absolute distance is unreliable. The thresholds match Apple's
    iBeacon specification.
    """

    IMMEDIATE = "immediate"  # < 0.5 m
    NEAR = "near"            # 0.5 – 3.0 m
    FAR = "far"              # > 3.0 m
    UNKNOWN = "unknown"      # RSSI unavailable


@dataclass
class DistanceEstimate:
    """
    Result of a BLE distance estimation.

    Parameters
    ----------
    rssi_dbm : int
        Measured RSSI in dBm.
    tx_power_dbm : int
        Calibrated TX power at 1 meter (dBm).
    path_loss_exponent : float
        Environment-specific path loss exponent.
    distance_m : float
        Estimated distance in meters.
    zone : ProximityZone
        Proximity classification.
    """

    rssi_dbm: int
    tx_power_dbm: int
    path_loss_exponent: float
    distance_m: float
    zone: ProximityZone


# ─── Functions ───────────────────────────────────────


def estimate_distance(
    rssi_dbm: int,
    tx_power_dbm: int = DEFAULT_TX_POWER,
    path_loss_exponent: float = DEFAULT_PATH_LOSS_EXPONENT,
) -> DistanceEstimate:
    """
    Estimate distance from a BLE RSSI measurement.

    Uses the log-distance path loss model:
        d = 10 ^ ((tx_power - rssi) / (10 × n))

    Parameters
    ----------
    rssi_dbm : int
        Measured RSSI in dBm (typically -30 to -100).
    tx_power_dbm : int
        Calibrated TX power at 1 meter (dBm). Default: -59 (iBeacon).
    path_loss_exponent : float
        Environment path loss exponent. Default: 2.0 (free space).

    Returns
    -------
    DistanceEstimate
        Estimated distance with proximity zone classification.

    Raises
    ------
    ValueError
        If ``rssi_dbm`` or ``tx_power_dbm`` is positive (invalid dBm).
    ValueError
        If ``path_loss_exponent`` is not positive.

    Example
    -------
    >>> est = estimate_distance(rssi_dbm=-70, tx_power_dbm=-59)
    >>> print(f"{est.distance_m:.1f} m — {est.zone.value}")
    3.5 m — far
    """
    if rssi_dbm > 0:
        raise ValueError(
            f"RSSI must be negative (dBm), got {rssi_dbm}. "
            "Typical BLE range: -30 to -100 dBm."
        )
    if tx_power_dbm > 0:
        raise ValueError(
            f"TX power must be negative (dBm), got {tx_power_dbm}."
        )
    if path_loss_exponent <= 0:
        raise ValueError(
            f"Path loss exponent must be positive, got {path_loss_exponent}."
        )

    # Log-distance path loss model
    ratio = (tx_power_dbm - rssi_dbm) / (10 * path_loss_exponent)
    distance = math.pow(10, ratio)

    zone = classify_proximity(distance)

    logger.info(
        "RSSI=%d dBm → %.2f m (%s) [tx=%d, n=%.1f]",
        rssi_dbm,
        distance,
        zone.value,
        tx_power_dbm,
        path_loss_exponent,
    )

    return DistanceEstimate(
        rssi_dbm=rssi_dbm,
        tx_power_dbm=tx_power_dbm,
        path_loss_exponent=path_loss_exponent,
        distance_m=distance,
        zone=zone,
    )


def classify_proximity(distance_m: float) -> ProximityZone:
    """
    Classify a distance into an iBeacon-style proximity zone.

    Parameters
    ----------
    distance_m : float
        Estimated distance in meters.

    Returns
    -------
    ProximityZone
        IMMEDIATE (< 0.5 m), NEAR (0.5–3.0 m), or FAR (> 3.0 m).
    """
    if distance_m < 0.5:
        return ProximityZone.IMMEDIATE
    elif distance_m <= 3.0:
        return ProximityZone.NEAR
    else:
        return ProximityZone.FAR


def rssi_to_ratio(
    rssi_dbm: int,
    tx_power_dbm: int = DEFAULT_TX_POWER,
) -> float:
    """
    Convert RSSI to a 0–1 signal strength ratio.

    Useful for progress bars, heatmaps, or visual signal indicators.
    Maps the typical BLE range (-100 dBm to tx_power) onto [0.0, 1.0].

    Parameters
    ----------
    rssi_dbm : int
        Measured RSSI in dBm.
    tx_power_dbm : int
        Reference TX power at 1 meter (upper bound).

    Returns
    -------
    float
        Signal ratio in [0.0, 1.0]. Clamped to bounds.

    Example
    -------
    >>> rssi_to_ratio(-59, tx_power_dbm=-59)
    1.0
    >>> rssi_to_ratio(-100, tx_power_dbm=-59)
    0.0
    """
    floor_dbm = -100
    clamped = max(floor_dbm, min(rssi_dbm, tx_power_dbm))
    return (clamped - floor_dbm) / (tx_power_dbm - floor_dbm)


def smooth_rssi(
    readings: list[int],
    alpha: float = 0.3,
) -> list[float]:
    """
    Apply exponential moving average (EMA) smoothing to RSSI readings.

    Raw RSSI fluctuates wildly (±10 dB between consecutive scans).
    EMA smoothing reduces noise while preserving trends.

    Parameters
    ----------
    readings : list[int]
        Sequence of raw RSSI values (dBm).
    alpha : float
        Smoothing factor in (0, 1). Lower = smoother but slower to respond.
        Default: 0.3 (good balance for BLE proximity at ~1 Hz scan rate).

    Returns
    -------
    list[float]
        Smoothed RSSI values.

    Raises
    ------
    ValueError
        If ``alpha`` is not in (0, 1) or ``readings`` is empty.

    Note
    ----
    EMA formula: s[t] = α × x[t] + (1 - α) × s[t-1]

    Reference: Hunter, J.S. (1986). The exponentially weighted moving average.
    Journal of Quality Technology, 18(4), 203–210.
    """
    if not 0 < alpha < 1:
        raise ValueError(f"alpha must be in (0, 1), got {alpha}")
    if not readings:
        raise ValueError("readings must not be empty")

    smoothed: list[float] = [float(readings[0])]
    for i in range(1, len(readings)):
        s = alpha * readings[i] + (1 - alpha) * smoothed[-1]
        smoothed.append(s)

    return smoothed


# ─── Entrypoint ─────────────────────────────────────

if __name__ == "__main__":
    import sys

    logging.basicConfig(level=logging.INFO, format="%(message)s")

    print("BLE RSSI & Distance Estimation")
    print("=" * 40)
    print(f"⚠️  Accuracy: approximate (±50–200%)")
    print()

    # Demo with common RSSI values
    for rssi in [-40, -55, -65, -75, -85, -95]:
        est = estimate_distance(rssi)
        print(f"  RSSI {rssi:4d} dBm → {est.distance_m:6.2f} m ({est.zone.value})")

    print()

    # Demo smoothing
    raw = [-65, -72, -58, -80, -63, -70, -68, -75, -62, -69]
    smoothed = smooth_rssi(raw)
    print("Raw RSSI:     ", [f"{r}" for r in raw])
    print("Smoothed RSSI:", [f"{s:.1f}" for s in smoothed])
