// BLEFoundationTests.cs — NUnit tests for the pure-C# BLE foundation types.
//
// These tests run WITHOUT Unity Editor via `dotnet test`.
// They test BLEDevice, BLECharacteristic, BLEError, and IBLEPlugin mock compliance.
// No MonoBehaviour, no UnityEngine — pure C# data types only.

using NUnit.Framework;
using BLEReference;
using System;

namespace BLEReference.Tests
{
    [TestFixture]
    public class BLEDeviceTests
    {
        [Test]
        public void BLEDevice_DefaultConstructor_AllFieldsDefault()
        {
            var device = new BLEDevice();
            Assert.IsNull(device.Id);
            Assert.IsNull(device.Name);
            Assert.AreEqual(0, device.RSSI);
        }

        [Test]
        public void BLEDevice_ParameterizedConstructor_SetsFields()
        {
            var device = new BLEDevice("mac:00:11:22:33:44:55", "Polar H10", -65);
            Assert.AreEqual("mac:00:11:22:33:44:55", device.Id);
            Assert.AreEqual("Polar H10", device.Name);
            Assert.AreEqual(-65, device.RSSI);
        }

        [Test]
        public void BLEDevice_ToString_ContainsAllFields()
        {
            var device = new BLEDevice("id1", "Muse 2", -70);
            var str = device.ToString();
            Assert.IsTrue(str.Contains("id1"));
            Assert.IsTrue(str.Contains("Muse 2"));
            Assert.IsTrue(str.Contains("-70"));
        }

        [Test]
        public void BLEDevice_NullName_ToStringHandlesNull()
        {
            var device = new BLEDevice("id1", null, -80);
            var str = device.ToString();
            Assert.IsTrue(str.Contains("null"));
        }
    }

    [TestFixture]
    public class BLECharacteristicTests
    {
        [Test]
        public void BLECharacteristic_DefaultConstructor_AllFlagsFalse()
        {
            var c = new BLECharacteristic();
            Assert.IsFalse(c.CanRead);
            Assert.IsFalse(c.CanWrite);
            Assert.IsFalse(c.CanWriteWithoutResponse);
            Assert.IsFalse(c.CanNotify);
            Assert.IsFalse(c.CanIndicate);
        }

        [Test]
        public void BLECharacteristic_NotifyOnly_FlagsCorrect()
        {
            var c = new BLECharacteristic("00002a37-0000-1000-8000-00805f9b34fb",
                canRead: false, canWrite: false, canWriteWithoutResponse: false,
                canNotify: true, canIndicate: false);
            Assert.IsFalse(c.CanRead);
            Assert.IsFalse(c.CanWrite);
            Assert.IsTrue(c.CanNotify);
            Assert.IsFalse(c.CanIndicate);
        }

        [Test]
        public void BLECharacteristic_ReadWrite_FlagsCorrect()
        {
            var c = new BLECharacteristic("uuid", canRead: true, canWrite: true,
                canWriteWithoutResponse: false, canNotify: false, canIndicate: false);
            Assert.IsTrue(c.CanRead);
            Assert.IsTrue(c.CanWrite);
        }

        [Test]
        public void BLECharacteristic_ToString_ContainsUUID()
        {
            var c = new BLECharacteristic("test-uuid", false, false, false, false, false);
            Assert.IsTrue(c.ToString().Contains("test-uuid"));
        }
    }

    [TestFixture]
    public class BLEErrorTests
    {
        [Test]
        public void BLEError_None_IsZero()
        {
            Assert.AreEqual(0, (int)BLEError.None);
        }

        [Test]
        public void BLEError_AllExpectedValuesExist()
        {
            // Verify the enum has all required error cases
            var names = Enum.GetNames(typeof(BLEError));
            Assert.Contains("None", names);
            Assert.Contains("NotInitialized", names);
            Assert.Contains("BluetoothUnavailable", names);
            Assert.Contains("ScanFailed", names);
            Assert.Contains("ConnectionFailed", names);
            Assert.Contains("ConnectionTimeout", names);
            Assert.Contains("ServiceNotFound", names);
            Assert.Contains("CharacteristicNotFound", names);
            Assert.Contains("ReadFailed", names);
            Assert.Contains("WriteFailed", names);
            Assert.Contains("NotConnected", names);
            Assert.Contains("PermissionDenied", names);
        }

        [Test]
        public void BLEError_UniqueValues()
        {
            var values = (int[])Enum.GetValues(typeof(BLEError));
            var unique = new System.Collections.Generic.HashSet<int>(values);
            Assert.AreEqual(values.Length, unique.Count, "All BLEError values must be unique");
        }
    }

    [TestFixture]
    public class MockBLEPluginTests
    {
        // A minimal mock IBLEPlugin for testing the interface contract
        private class MockBLEPlugin : IBLEPlugin
        {
            public bool InitializeCalled;
            public bool StartScanCalled;
            public string LastConnectedDeviceId;

            public event Action<string, bool> OnConnectionStateChanged;

            public void Initialize() => InitializeCalled = true;

            public void StartScan(float duration, Action<BLEDevice[]> onComplete)
            {
                StartScanCalled = true;
                onComplete?.Invoke(new[] { new BLEDevice("mock-id", "MockDevice", -50) });
            }

            public void StartScanFiltered(string[] uuids, string name, int rssi, float duration, Action<BLEDevice[]> onComplete)
                => onComplete?.Invoke(Array.Empty<BLEDevice>());

            public void StopScan() { }

            public void Connect(string id, float timeout, Action<bool> onResult)
            {
                LastConnectedDeviceId = id;
                onResult?.Invoke(true);
            }

            public void Disconnect(string id) { }

            public void DiscoverServices(string id, Action<string[]> onComplete)
                => onComplete?.Invoke(new[] { "heart_rate", "battery_service" });

            public void DiscoverCharacteristics(string id, string svc, Action<BLECharacteristic[]> onComplete)
                => onComplete?.Invoke(Array.Empty<BLECharacteristic>());

            public void ReadCharacteristic(string id, string svc, string chr, Action<byte[]> onResult)
                => onResult?.Invoke(new byte[] { 0x42 });

            public void WriteCharacteristic(string id, string svc, string chr, byte[] data, bool withResp, Action<bool> onResult)
                => onResult?.Invoke(true);

            public void Subscribe(string id, string svc, string chr, Action<byte[]> onData) { }

            public void Unsubscribe(string id, string svc, string chr) { }

            public void ReadBattery(string id, Action<int> onResult) => onResult?.Invoke(85);

            public void FireConnectionStateChanged(string id, bool connected)
                => OnConnectionStateChanged?.Invoke(id, connected);
        }

        [Test]
        public void MockPlugin_Initialize_SetsFlagTrue()
        {
            var plugin = new MockBLEPlugin();
            plugin.Initialize();
            Assert.IsTrue(plugin.InitializeCalled);
        }

        [Test]
        public void MockPlugin_StartScan_ReturnsDevices()
        {
            var plugin = new MockBLEPlugin();
            BLEDevice[] result = null;
            plugin.StartScan(5f, devices => result = devices);
            Assert.IsNotNull(result);
            Assert.AreEqual(1, result.Length);
            Assert.AreEqual("MockDevice", result[0].Name);
        }

        [Test]
        public void MockPlugin_Connect_CallbackWithTrue()
        {
            var plugin = new MockBLEPlugin();
            bool success = false;
            plugin.Connect("device-1", 10f, ok => success = ok);
            Assert.IsTrue(success);
            Assert.AreEqual("device-1", plugin.LastConnectedDeviceId);
        }

        [Test]
        public void MockPlugin_ReadBattery_Returns85()
        {
            var plugin = new MockBLEPlugin();
            int level = -1;
            plugin.ReadBattery("device-1", v => level = v);
            Assert.AreEqual(85, level);
        }

        [Test]
        public void MockPlugin_OnConnectionStateChanged_EventFires()
        {
            var plugin = new MockBLEPlugin();
            string receivedId = null;
            bool receivedState = false;
            plugin.OnConnectionStateChanged += (id, connected) =>
            {
                receivedId = id;
                receivedState = connected;
            };
            plugin.FireConnectionStateChanged("device-1", false);
            Assert.AreEqual("device-1", receivedId);
            Assert.IsFalse(receivedState);
        }

        [Test]
        public void MockPlugin_DiscoverServices_ReturnsServices()
        {
            var plugin = new MockBLEPlugin();
            string[] services = null;
            plugin.DiscoverServices("device-1", s => services = s);
            Assert.IsNotNull(services);
            Assert.AreEqual(2, services.Length);
        }
    }
}
