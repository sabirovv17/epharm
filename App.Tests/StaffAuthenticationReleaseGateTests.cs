using System.Text.Json;
using CustomerDisplay.Config;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StaffAuthenticationReleaseGateTests
{
    [Fact]
    public void ReviewedReleaseStillAllowsLocalOptOut()
    {
        var config = JsonSerializer.Deserialize<EpharmConfig>("""
            {"StaffAuthenticationEnabled":false,"StaffAuthenticationReleaseEnabled":false}
            """)!;

        Assert.False(config.StaffAuthenticationEnabled);
        Assert.True(EpharmConfig.StaffAuthenticationReleaseEnabled);
        Assert.False(typeof(EpharmConfig).GetProperty(nameof(EpharmConfig.StaffAuthenticationReleaseEnabled))!.CanWrite);
    }
}
