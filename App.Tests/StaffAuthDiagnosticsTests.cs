using System.Net;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StaffAuthDiagnosticsTests
{
    [Theory]
    [InlineData("authenticated", "authenticated")]
    [InlineData("login_visible", "login_visible")]
    [InlineData("database_unavailable", "database_unavailable")]
    [InlineData("stale_session", "stale_session")]
    [InlineData("endpoint_unavailable", "endpoint_unavailable")]
    [InlineData("iin=000000000000 password=secret", "initializing")]
    [InlineData("https://user:password@example.invalid", "initializing")]
    [InlineData("AUTHENTICATED", "initializing")]
    [InlineData(null, "initializing")]
    public void OnlyAllowlistedCodesMayEnterDiagnostics(string? input, string expected) =>
        Assert.Equal(expected, StaffAuthDiagnostics.Normalize(input));

    [Theory]
    [InlineData(401, "device_auth_required")]
    [InlineData(403, "forbidden")]
    [InlineData(404, "endpoint_unavailable")]
    [InlineData(500, "endpoint_unavailable")]
    [InlineData(302, "endpoint_unavailable")]
    public void CapabilityErrorsStayOperationalWithoutResponseDetails(int status, string expected)
    {
        var result = StaffAuthDiagnostics.CapabilityFailure((HttpStatusCode)status);
        Assert.False(result.Enabled);
        Assert.Equal(expected, result.Status);
    }

    [Fact]
    public void CapabilityDistinguishesServerDisabledAndUnsupportedProtocol()
    {
        Assert.Equal(new(true, "enabled"), StaffAuthDiagnostics.Capability(new(true, 1)));
        Assert.Equal(new(false, "server_disabled"), StaffAuthDiagnostics.Capability(new(false, 1)));
        Assert.Equal(new(false, "unsupported_protocol"), StaffAuthDiagnostics.Capability(new(true, 2)));
        Assert.Equal(new(false, "unsupported_protocol"), StaffAuthDiagnostics.Capability(null));
    }

    [Fact]
    public void ActualVisibleWindowAndAcceptedGrantAreDistinctFromClosedWindow()
    {
        Assert.Equal("login_required", StaffAuthDiagnostics.Observation("resolved", false));
        Assert.Equal("login_visible", StaffAuthDiagnostics.Observation("resolved", false, true));
        Assert.Equal("authenticated", StaffAuthDiagnostics.Observation("resolved", true, true));
        Assert.Equal("database_unavailable", StaffAuthDiagnostics.Observation("database_unavailable", true, true));
    }

    [Theory]
    [InlineData("INVALID_CREDENTIALS", true)]
    [InlineData("RATE_LIMITED", false)]
    [InlineData("UNAVAILABLE", false)]
    [InlineData("INVALID_DATA", false)]
    [InlineData("FORBIDDEN", false)]
    public void GenericCredentialFailureOffersSameRegistrationOrResetChoice(string code, bool expected) =>
        Assert.Equal(expected, StaffAuthDiagnostics.OfferRecovery(code));
}
