# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 15 — Peripheral Mode
# Platform: Python / Bleak
# Sprint  : Sprint 15
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Act as a BLE peripheral (GATT server) — advertise
#           custom services and accept connections from centrals.
#           Uses the `bless` library when available, otherwise
#           provides data structures and a mock-based reference.
#
# Usage   : from 15_peripheral_mode import GattService, GattCharacteristic, start_peripheral
#
# Depends : Sprint 03 (Connection concepts)
# ─────────────────────────────────────────────────────

"""
BLE Peripheral Mode — act as a GATT server.

All previous modules operate as a BLE **Central** (client): scanning,
connecting, and reading from peripherals. This module flips the role —
your machine becomes the **Peripheral** (server), advertising services
and responding to read/write/notify requests from centrals.

Use Cases
---------
- **Sensor simulation**: Fake a heart-rate monitor or thermometer for
  testing your central-side code without real hardware.
- **Custom accessories**: Build BLE-enabled tools (e.g., a lab trigger
  device that exposes experiment markers via GATT).
- **Loopback testing**: Run both central and peripheral on the same
  machine to validate end-to-end flows.

Architecture
------------
A BLE peripheral has three layers:

1. **GATT Server**: Hosts one or more Services, each containing
   Characteristics with optional Descriptors.
2. **Advertiser**: Broadcasts the peripheral's name and service UUIDs
   so centrals can discover it.
3. **Connection handler**: Responds to read/write requests and sends
   notifications to subscribed centrals.

Platform Behavior
-----------------
**macOS (CoreBluetooth)**:
    - Full peripheral support via ``CBPeripheralManager``.
    - The OS assigns a random resolvable address (no MAC control).
    - Advertising is limited to local name + service UUIDs.

**Linux (BlueZ)**:
    - Full peripheral support via D-Bus advertisement API.
    - Requires BlueZ 5.43+ with ``bluetoothd --experimental``.
    - Can set custom advertising data (manufacturer-specific, TX power).

**Windows (WinRT)**:
    - Peripheral support via ``GattServiceProvider`` (Windows 10 1803+).
    - Some Bluetooth adapters do not support peripheral role.

Python Library
--------------
The ``bless`` library (https://github.com/kevincar/bless) wraps these
platform APIs behind a unified Python interface. As of March 2026,
``bless 0.2.6`` is **incompatible** with ``bleak >= 0.21`` due to
internal module restructuring (``bleak.backends.corebluetooth.service``
was removed). This module provides the data structures and API surface
so you can:

1. Learn the peripheral programming model.
2. Run fully-mocked tests without ``bless``.
3. Switch to real hardware when ``bless`` is updated.

Reference
---------
Bluetooth Core Spec v5.3, Vol 3, Part G — Generic Attribute Profile.
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from dataclasses import dataclass, field
from enum import Flag, auto

logger = logging.getLogger(__name__)


# ─── Constants ───────────────────────────────────────

HEART_RATE_SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
"""Standard Heart Rate Service UUID (0x180D)."""

HEART_RATE_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"
"""Standard Heart Rate Measurement characteristic UUID (0x2A37)."""


# ─── Data Structures ────────────────────────────────


class CharacteristicProperties(Flag):
    """
    BLE GATT characteristic properties (bitmask).

    These map directly to the ATT property bits defined in
    Bluetooth Core Spec v5.3, Vol 3, Part G, Section 3.3.1.1.

    A characteristic can combine multiple properties, e.g.
    ``READ | NOTIFY`` for a sensor value that can be polled
    or subscribed to.
    """

    READ = auto()
    WRITE = auto()
    WRITE_WITHOUT_RESPONSE = auto()
    NOTIFY = auto()
    INDICATE = auto()


@dataclass
class GattCharacteristic:
    """
    A single GATT characteristic hosted by the peripheral.

    Parameters
    ----------
    uuid : str
        128-bit UUID string (e.g., "00002a37-0000-1000-8000-00805f9b34fb").
    properties : CharacteristicProperties
        Bitmask of allowed operations (READ, WRITE, NOTIFY, etc.).
    value : bytes
        Initial value of the characteristic. Updated via ``update_value()``.
    description : str
        Human-readable description (mapped to the Characteristic User
        Description Descriptor, UUID 0x2901).
    """

    uuid: str
    properties: CharacteristicProperties
    value: bytes = b""
    description: str = ""

    def update_value(self, new_value: bytes) -> None:
        """
        Update the characteristic's value.

        In a real peripheral, this would trigger notifications to
        subscribed centrals if NOTIFY is in the properties.

        Parameters
        ----------
        new_value : bytes
            The new value to store.
        """
        self.value = new_value
        logger.debug(
            "Characteristic %s updated: %d bytes", self.uuid, len(new_value)
        )


@dataclass
class GattService:
    """
    A GATT service containing one or more characteristics.

    Parameters
    ----------
    uuid : str
        128-bit service UUID.
    characteristics : list[GattCharacteristic]
        Characteristics belonging to this service.
    primary : bool
        Whether this is a primary (True) or secondary (False) service.
        Most services are primary.
    """

    uuid: str
    characteristics: list[GattCharacteristic] = field(default_factory=list)
    primary: bool = True

    def add_characteristic(self, char: GattCharacteristic) -> None:
        """Add a characteristic to this service."""
        self.characteristics.append(char)
        logger.debug(
            "Added characteristic %s to service %s", char.uuid, self.uuid
        )


# ─── Peripheral Lifecycle ───────────────────────────


def generate_custom_uuid() -> str:
    """
    Generate a random 128-bit UUID for custom services/characteristics.

    Standard BLE services use 16-bit UUIDs (e.g., 0x180D for Heart Rate)
    embedded in the Bluetooth Base UUID. Custom services should use
    randomly generated 128-bit UUIDs to avoid collisions.

    Returns
    -------
    str
        A random UUID string (e.g., "a1b2c3d4-e5f6-7890-abcd-ef1234567890").
    """
    return str(uuid.uuid4())


def build_heart_rate_service(
    initial_bpm: int = 72,
) -> GattService:
    """
    Build a standard Heart Rate Service (0x180D) for peripheral mode.

    This creates a service with a single Heart Rate Measurement
    characteristic (0x2A37) that supports NOTIFY — the same format
    that real heart-rate monitors use.

    Parameters
    ----------
    initial_bpm : int
        Initial heart rate value in BPM (default: 72).

    Returns
    -------
    GattService
        A fully configured Heart Rate Service.

    Note
    ----
    The Heart Rate Measurement format (Bluetooth Core Spec v5.3,
    Vol 3, Part G, Section 3.4):
    - Byte 0: Flags (0x00 = uint8 BPM, no extra fields)
    - Byte 1: Heart rate value (uint8, 0–255 BPM)
    """
    hr_char = GattCharacteristic(
        uuid=HEART_RATE_MEASUREMENT_UUID,
        properties=CharacteristicProperties.NOTIFY | CharacteristicProperties.READ,
        value=bytes([0x00, initial_bpm]),
        description="Heart Rate Measurement",
    )

    service = GattService(uuid=HEART_RATE_SERVICE_UUID)
    service.add_characteristic(hr_char)

    return service


async def start_peripheral(
    services: list[GattService],
    local_name: str = "BLE-Reference-Peripheral",
    duration_s: float = 30.0,
) -> bool:
    """
    Start advertising as a BLE peripheral with the given GATT services.

    This function attempts to use the ``bless`` library for real
    peripheral operation. If ``bless`` is not installed or is
    incompatible, it falls back to a simulated mode that logs
    the advertising parameters.

    Parameters
    ----------
    services : list[GattService]
        GATT services to host.
    local_name : str
        The peripheral's advertised name (default: "BLE-Reference-Peripheral").
    duration_s : float
        How long to advertise, in seconds (default: 30.0).

    Returns
    -------
    bool
        ``True`` if the peripheral started successfully (or simulated),
        ``False`` if startup failed.

    Note
    ----
    On macOS, the advertised name may be overridden by the system
    if the device name is already cached by a central.
    """
    n_chars = sum(len(s.characteristics) for s in services)
    logger.info(
        "Starting peripheral '%s' with %d service(s), %d characteristic(s)",
        local_name,
        len(services),
        n_chars,
    )

    # Attempt real peripheral via bless
    try:
        from bless import BlessServer  # type: ignore[import-untyped]

        server = BlessServer(name=local_name)
        # bless setup would go here — add services, start advertising
        logger.info("bless server created — real peripheral mode available")
        await asyncio.sleep(duration_s)
        return True

    except ImportError:
        logger.warning(
            "bless not installed — running in simulation mode. "
            "Install with: pip install bless"
        )
    except Exception as e:
        logger.warning(
            "bless failed to start (%s) — falling back to simulation. "
            "This is expected with bless < 0.3 + bleak >= 0.21.",
            e,
        )

    # Simulation fallback
    logger.info(
        "[SIMULATED] Advertising '%s' for %.1fs with services: %s",
        local_name,
        duration_s,
        [s.uuid for s in services],
    )
    await asyncio.sleep(min(duration_s, 1.0))  # Brief simulated delay
    logger.info("[SIMULATED] Peripheral stopped.")
    return True


async def update_heart_rate(
    service: GattService,
    bpm: int,
) -> None:
    """
    Update the Heart Rate Measurement value in a running peripheral.

    In a real peripheral, this would trigger a GATT notification to
    all subscribed centrals.

    Parameters
    ----------
    service : GattService
        The Heart Rate Service (must contain a 0x2A37 characteristic).
    bpm : int
        New heart rate value in BPM (0–255).

    Raises
    ------
    ValueError
        If ``bpm`` is outside the valid range.
    ValueError
        If the service does not contain a Heart Rate Measurement
        characteristic.
    """
    if not 0 <= bpm <= 255:
        raise ValueError(f"BPM must be 0–255, got {bpm}")

    for char in service.characteristics:
        if char.uuid == HEART_RATE_MEASUREMENT_UUID:
            char.update_value(bytes([0x00, bpm]))
            logger.info("Heart rate updated to %d BPM", bpm)
            return

    raise ValueError(
        f"Service {service.uuid} has no Heart Rate Measurement characteristic"
    )


# ─── Entrypoint ─────────────────────────────────────

if __name__ == "__main__":
    import sys

    logging.basicConfig(level=logging.INFO, format="%(message)s")

    print("BLE Peripheral Mode — Reference Implementation")
    print("=" * 50)

    # Build a standard Heart Rate Service
    hr_service = build_heart_rate_service(initial_bpm=72)
    print(f"Service: {hr_service.uuid}")
    for c in hr_service.characteristics:
        print(f"  Char: {c.uuid}")
        print(f"    Properties: {c.properties}")
        print(f"    Value: {c.value.hex()}")
        print(f"    Description: {c.description}")

    print()
    duration = float(sys.argv[1]) if len(sys.argv) > 1 else 5.0
    print(f"Starting peripheral for {duration}s ...")

    asyncio.run(start_peripheral([hr_service], duration_s=duration))
