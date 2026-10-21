using System.Text.Json;
using CustomerDisplay.Config;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StaffAuthenticationReleaseGateTests
{
    [Fact]
    public void ProductionPauseCannotBeEnabledByLocalConfiguration()
    {
        var config = JsonSerializer.Deserialize<EpharmConfig>("""
            {"StaffAuthenticationEnabled":true,"StaffAuthenticationReleaseEnabled":true}
            """)!;

        Assert.True(config.StaffAuthenticationEnabled);
        Assert.False(EpharmConfig.StaffAuthenticationReleaseEnabled);
        Assert.False(typeof(EpharmConfig).GetProperty(nameof(EpharmConfig.StaffAuthenticationReleaseEnabled))!.CanWrite);
    }
}
