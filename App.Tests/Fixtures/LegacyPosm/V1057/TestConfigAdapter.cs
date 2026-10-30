// Test-only adapter: never reads config, environment, files or real credentials.
using System;
using System.Collections.Generic;
namespace CustomerDisplay.Core.Tests.Legacy.V1057.Config;
public sealed class EpharmConfig
{
    public string BackendBaseUrl { get; set; } = "http://127.0.0.1:1";
    public string DeviceKey { get; set; } = "synthetic-device-token-never-production";
    public int RecommendTimeoutMs { get; set; } = 5000;
    public IReadOnlyList<Uri> GetBackendBaseUris() => [new Uri(BackendBaseUrl)];
    public IReadOnlyList<Uri> GetFulfillmentBaseUris() => GetBackendBaseUris();
}
