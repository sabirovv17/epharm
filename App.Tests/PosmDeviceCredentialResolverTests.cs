using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class PosmDeviceCredentialResolverTests
{
    private const string FleetKey = "fleet-key-that-must-not-remain-in-use";
    private const string DeviceToken = "individual-device-token-0123456789abcdef";

    [Fact]
    public void MatchingProtectedCredentialReplacesFleetKey()
    {
        var resolved = PosmDeviceCredentialResolver.Resolve(
            FleetKey,
            "KASSA-1",
            "ph_1",
            "kassa-1",
            "ph_1",
            DeviceToken);

        Assert.Equal(DeviceToken, resolved);
    }

    [Theory]
    [InlineData("OTHER", "ph_1")]
    [InlineData("KASSA-1", "ph_other")]
    public void CredentialForAnotherRegisterOrPharmacyIsRejected(string storedDeviceId, string storedPharmacyId)
    {
        var resolved = PosmDeviceCredentialResolver.Resolve(
            FleetKey,
            "KASSA-1",
            "ph_1",
            storedDeviceId,
            storedPharmacyId,
            DeviceToken);

        Assert.Equal(FleetKey, resolved);
    }
}
