using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services
{
    internal sealed class FulfillmentOrderCache
    {
        private readonly string _path;

        public FulfillmentOrderCache(string path) => _path = path;

        public IReadOnlyList<FulfillmentOrder> Load()
        {
            try
            {
                if (!File.Exists(_path)) return Array.Empty<FulfillmentOrder>();
                var orders = JsonSerializer.Deserialize<List<FulfillmentOrder>>(File.ReadAllBytes(_path), EpharmJson.Options);
                return orders ?? (IReadOnlyList<FulfillmentOrder>)Array.Empty<FulfillmentOrder>();
            }
            catch
            {
                return Array.Empty<FulfillmentOrder>();
            }
        }

        public void Save(IEnumerable<FulfillmentOrder> orders)
        {
            try
            {
                var directory = Path.GetDirectoryName(_path);
                if (string.IsNullOrWhiteSpace(directory)) return;
                Directory.CreateDirectory(directory);
                var temp = _path + ".tmp";
                File.WriteAllBytes(temp, JsonSerializer.SerializeToUtf8Bytes(orders, EpharmJson.Options));
                File.Move(temp, _path, true);
            }
            catch
            {
                // Cache failure must never interfere with Standard-N.
            }
        }
    }
}
