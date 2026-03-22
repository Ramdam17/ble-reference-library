// BLEError.cs — Typed error enum for the IBLEPlugin contract.
// Avoids magic strings / integer error codes across platform plugins.
namespace BLEReference
{
    /// <summary>
    /// Typed error codes for BLE operations. Passed to error callbacks and logged for diagnostics.
    /// All IBLEPlugin implementations must map platform-specific errors to this enum.
    /// </summary>
    public enum BLEError
    {
        /// <summary>No error.</summary>
        None = 0,

        /// <summary>BLEManager.Initialize() was not called before the operation.</summary>
        NotInitialized = 1,

        /// <summary>Bluetooth adapter is unavailable or disabled on this device.</summary>
        BluetoothUnavailable = 2,

        /// <summary>The scan operation failed to start (e.g. missing BLUETOOTH_SCAN permission on Android).</summary>
        ScanFailed = 3,

        /// <summary>Could not establish a GATT connection to the peripheral.</summary>
        ConnectionFailed = 4,

        /// <summary>GATT connection attempt exceeded the timeout specified in Connect().</summary>
        ConnectionTimeout = 5,

        /// <summary>The requested GATT service UUID was not found on the connected peripheral.</summary>
        ServiceNotFound = 6,

        /// <summary>The requested GATT characteristic UUID was not found in the service.</summary>
        CharacteristicNotFound = 7,

        /// <summary>A ReadCharacteristic operation returned an error status from the peripheral.</summary>
        ReadFailed = 8,

        /// <summary>A WriteCharacteristic operation returned an error status from the peripheral.</summary>
        WriteFailed = 9,

        /// <summary>Operation attempted on a peripheral that is not currently connected.</summary>
        NotConnected = 10,

        /// <summary>Required Bluetooth permissions (e.g. BLUETOOTH_SCAN, BLUETOOTH_CONNECT on Android API 31+) were denied.</summary>
        PermissionDenied = 11,
    }
}
