// BLECharacteristic.cs — Data Transfer Object for a GATT characteristic.
// Exposes capability flags (read/write/notify/indicate) rather than raw property bitmask.
using System;

namespace BLEReference
{
    /// <summary>
    /// Represents a GATT characteristic discovered on a connected peripheral.
    /// Capability flags are pre-parsed from the raw GATT properties bitmask.
    /// </summary>
    [Serializable]
    public class BLECharacteristic
    {
        /// <summary>128-bit UUID string (lowercase, with hyphens). e.g. "00002a37-0000-1000-8000-00805f9b34fb"</summary>
        public string UUID;

        /// <summary>True if the characteristic supports Read operations.</summary>
        public bool CanRead;

        /// <summary>True if the characteristic supports Write (with response) operations.</summary>
        public bool CanWrite;

        /// <summary>True if the characteristic supports WriteWithoutResponse.</summary>
        public bool CanWriteWithoutResponse;

        /// <summary>True if the characteristic supports Notify (client-side, no ACK).</summary>
        public bool CanNotify;

        /// <summary>True if the characteristic supports Indicate (server-side, ACK required).</summary>
        public bool CanIndicate;

        public BLECharacteristic() { }

        public BLECharacteristic(string uuid, bool canRead, bool canWrite, bool canWriteWithoutResponse, bool canNotify, bool canIndicate)
        {
            UUID = uuid;
            CanRead = canRead;
            CanWrite = canWrite;
            CanWriteWithoutResponse = canWriteWithoutResponse;
            CanNotify = canNotify;
            CanIndicate = canIndicate;
        }

        public override string ToString() =>
            $"BLECharacteristic(UUID={UUID}, R={CanRead}, W={CanWrite}, WnR={CanWriteWithoutResponse}, N={CanNotify}, I={CanIndicate})";
    }
}
