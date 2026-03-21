# ─────────────────────────────────────────────────────
# BLE Reference Library
# Module  : 04 — Services & Characteristics Discovery (Tests)
# Platform: Python / Bleak
# Sprint  : Sprint 04
# Phase   : Phase 01 — Python / Bleak
#
# Purpose : Unit tests for GATT discovery with mocked BleakClient.
#
# Usage   : pytest tests/01_python_bleak/test_04_services_characteristics.py -v
#
# Depends : None
# ─────────────────────────────────────────────────────

"""
Tests for 04_services_characteristics.py — GATT discovery.

All tests use mock services/characteristics — no BLE hardware required.
"""

from __future__ import annotations

import importlib
import sys
from pathlib import Path
from unittest.mock import MagicMock

import pytest

# ─── Module import ───────────────────────────────────

_MODULE_DIR = Path(__file__).resolve().parent.parent.parent / "01_python_bleak"
sys.path.insert(0, str(_MODULE_DIR))

_mod = importlib.import_module("04_services_characteristics")
list_services = _mod.list_services
list_characteristics = _mod.list_characteristics
characteristic_properties = _mod.characteristic_properties
print_service_tree = _mod.print_service_tree
PROPERTY_KEYS = _mod.PROPERTY_KEYS


# ─── Helpers ─────────────────────────────────────────

HEART_RATE_SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
BATTERY_SERVICE_UUID = "0000180f-0000-1000-8000-00805f9b34fb"
HR_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"
BATTERY_LEVEL_UUID = "00002a19-0000-1000-8000-00805f9b34fb"


def _make_characteristic(
    uuid: str = HR_MEASUREMENT_UUID,
    handle: int = 14,
    properties: list[str] | None = None,
):
    """Create a mock BleakGATTCharacteristic."""
    char = MagicMock()
    char.uuid = uuid
    char.handle = handle
    char.properties = properties or ["notify"]
    char.descriptors = []
    return char


def _make_service(
    uuid: str = HEART_RATE_SERVICE_UUID,
    description: str = "Heart Rate",
    characteristics: list | None = None,
):
    """Create a mock BleakGATTService."""
    service = MagicMock()
    service.uuid = uuid
    service.description = description
    service.characteristics = characteristics or []
    return service


def _make_client_with_services(services: list):
    """Create a mock BleakClient with populated services."""
    client = MagicMock()
    client.services = services
    return client


# ─── Tests ───────────────────────────────────────────


def test_list_services_returns_all():
    """list_services must return all services on the device."""
    hr_char = _make_characteristic(uuid=HR_MEASUREMENT_UUID, properties=["notify"])
    bat_char = _make_characteristic(uuid=BATTERY_LEVEL_UUID, handle=20, properties=["read"])
    hr_service = _make_service(uuid=HEART_RATE_SERVICE_UUID, characteristics=[hr_char])
    bat_service = _make_service(uuid=BATTERY_SERVICE_UUID, description="Battery", characteristics=[bat_char])

    client = _make_client_with_services([hr_service, bat_service])
    result = list_services(client)

    assert len(result) == 2
    assert result[0].uuid == HEART_RATE_SERVICE_UUID
    assert result[1].uuid == BATTERY_SERVICE_UUID


def test_list_characteristics_all_services():
    """list_characteristics without filter must return chars from all services."""
    hr_char = _make_characteristic(uuid=HR_MEASUREMENT_UUID)
    bat_char = _make_characteristic(uuid=BATTERY_LEVEL_UUID, handle=20, properties=["read"])
    hr_service = _make_service(characteristics=[hr_char])
    bat_service = _make_service(uuid=BATTERY_SERVICE_UUID, characteristics=[bat_char])

    client = _make_client_with_services([hr_service, bat_service])
    result = list_characteristics(client)

    assert len(result) == 2


def test_list_characteristics_filtered_by_service():
    """list_characteristics with service_uuid must return only that service's chars."""
    hr_char = _make_characteristic(uuid=HR_MEASUREMENT_UUID)
    bat_char = _make_characteristic(uuid=BATTERY_LEVEL_UUID, handle=20)
    hr_service = _make_service(uuid=HEART_RATE_SERVICE_UUID, characteristics=[hr_char])
    bat_service = _make_service(uuid=BATTERY_SERVICE_UUID, characteristics=[bat_char])

    client = _make_client_with_services([hr_service, bat_service])
    result = list_characteristics(client, service_uuid=HEART_RATE_SERVICE_UUID)

    assert len(result) == 1
    assert result[0].uuid == HR_MEASUREMENT_UUID


def test_characteristic_properties_keys():
    """characteristic_properties must return all 5 standard property keys."""
    char = _make_characteristic(properties=["read", "notify"])
    props = characteristic_properties(char)

    assert set(props.keys()) == set(PROPERTY_KEYS)


def test_characteristic_properties_values():
    """A notify-only characteristic must have notify=True and read=False."""
    char = _make_characteristic(properties=["notify"])
    props = characteristic_properties(char)

    assert props["notify"] is True
    assert props["read"] is False
    assert props["write"] is False
    assert props["indicate"] is False


def test_print_service_tree_no_exception(capsys):
    """print_service_tree must run without exception and produce output."""
    hr_char = _make_characteristic(uuid=HR_MEASUREMENT_UUID, properties=["notify"])
    bat_char = _make_characteristic(uuid=BATTERY_LEVEL_UUID, handle=20, properties=["read"])
    hr_service = _make_service(uuid=HEART_RATE_SERVICE_UUID, characteristics=[hr_char])
    bat_service = _make_service(uuid=BATTERY_SERVICE_UUID, description="Battery", characteristics=[bat_char])

    client = _make_client_with_services([hr_service, bat_service])
    print_service_tree(client)

    output = capsys.readouterr().out
    assert HEART_RATE_SERVICE_UUID in output
    assert BATTERY_SERVICE_UUID in output
    assert "notify" in output
    assert "read" in output
    assert "handle=" in output
