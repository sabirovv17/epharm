using CustomerDisplay.Config;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class DeviceIdentityConfigurationTests
{
    [Fact]
    public void ResolveDeviceId_PrefersProvisionedStableIdentity()
    {
        var config = new EpharmConfig { DeviceId = "  POSM-SLOC-123  " };

        Assert.Equal("POSM-SLOC-123", config.ResolveDeviceId("WINDOWS-HOST"));
    }

    [Fact]
    public void ResolveDeviceId_FallsBackToWindowsHostnameForExistingInstalls()
    {
        var config = new EpharmConfig { DeviceId = "" };

        Assert.Equal("WINDOWS-HOST", config.ResolveDeviceId(" WINDOWS-HOST "));
    }

    [Fact]
    public void ResolveDeviceId_BoundsIdentityToBackendLimit()
    {
        var config = new EpharmConfig { DeviceId = new string('x', 140) };

        Assert.Equal(128, config.ResolveDeviceId("WINDOWS-HOST").Length);
    }
}
