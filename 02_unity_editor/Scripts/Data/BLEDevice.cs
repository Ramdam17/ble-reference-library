// BLEDevice.cs — Data Transfer Object for a discovered BLE peripheral.
// Part of the IBLEPlugin contract. Serializable for Unity Inspector display.
using System;

namespace BLEReference
{
    /// <summary>
    /// Represents a BLE peripheral discovered during a scan.
    /// Serializable so Unity can display it in the Inspector and serialize to JSON.
    /// </summary>
    [Serializable]
    public class BLEDevice
    {
        /// <summary>Platform-specific device identifier (MAC address on Android, UUID on iOS/macOS).</summary>
        public string Id;

        /// <summary>Advertised local name of the peripheral. May be null if device does not advertise a name.</summary>
        public string Name;

        /// <summary>Received Signal Strength Indicator in dBm. Typically -30 (very close) to -100 (far away).</summary>
        public int RSSI;

        public BLEDevice() { }

        public BLEDevice(string id, string name, int rssi)
        {
            Id = id;
            Name = name;
            RSSI = rssi;
        }

        public override string ToString() => $"BLEDevice(Id={Id}, Name={Name ?? "null"}, RSSI={RSSI})";
    }
}
