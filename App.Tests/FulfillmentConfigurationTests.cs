using CustomerDisplay.Config;
using CustomerDisplay.Services;
using CustomerDisplay.Models.Posm;
using System.Text.Json;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class FulfillmentConfigurationTests
{
    [Fact]
    public void ExistingConfigurationWithoutFulfillmentFlagNowDefaultsToDisabled()
    {
        Assert.False(new EpharmConfig().FulfillmentEnabled);
        var existing = JsonSerializer.Deserialize<EpharmConfig>("{\"PharmacyId\":\"test-pharmacy\"}")!;
        Assert.False(existing.FulfillmentEnabled);
        Assert.True(JsonSerializer.Deserialize<EpharmConfig>("{\"FulfillmentEnabled\":true}")!.FulfillmentEnabled);
    }

    [Theory]
    [InlineData("{}", "true", true)]
    [InlineData("{\"FulfillmentEnabled\":true}", "false", false)]
    [InlineData("{}", "invalid", false)]
    public void ExplicitEnvironmentOptInAndDisableOverrideFile(string json, string enabled, bool expected)
    {
        var path = Path.GetTempFileName();
        var previousPath = Environment.GetEnvironmentVariable("EPHARM_POSM_CONFIG");
        var previousEnabled = Environment.GetEnvironmentVariable("EPHARM_FULFILLMENT_ENABLED");
        try
        {
            File.WriteAllText(path, json);
            Environment.SetEnvironmentVariable("EPHARM_POSM_CONFIG", path);
            Environment.SetEnvironmentVariable("EPHARM_FULFILLMENT_ENABLED", enabled);
            Assert.Equal(expected, EpharmConfig.Load().FulfillmentEnabled);
        }
        finally
        {
            Environment.SetEnvironmentVariable("EPHARM_POSM_CONFIG", previousPath);
            Environment.SetEnvironmentVariable("EPHARM_FULFILLMENT_ENABLED", previousEnabled);
            File.Delete(path);
        }
    }

    [Fact]
    public void CachedQueueNeedsExplicitLocalOptInAndConfirmedServerCapability()
    {
        Assert.False(FulfillmentRules.CanDisplayQueue(true, null));
        Assert.False(FulfillmentRules.CanDisplayQueue(true, new FulfillmentCapabilities()));
        Assert.False(FulfillmentRules.CanDisplayQueue(false, new FulfillmentCapabilities { Enabled = true }));
        Assert.True(FulfillmentRules.CanDisplayQueue(true, new FulfillmentCapabilities { Enabled = true }));
    }

    [Fact]
    public void FulfillmentInheritsExistingBackendEndpoints()
    {
        var config = new EpharmConfig
        {
            BackendBaseUrl = "https://epharm.inkar.kz",
            BackendFallbackBaseUrls = new() { "http://epharm.inkar.kz:8060" },
        };

        Assert.Equal(new[] { "https://epharm.inkar.kz/" }, config.GetBackendBaseUris().Select(x => x.AbsoluteUri));
        Assert.Equal(config.GetBackendBaseUris(), config.GetFulfillmentBaseUris());
    }

    [Fact]
    public void DedicatedFulfillmentEndpointsDoNotChangeRecommendationBackend()
    {
        var config = new EpharmConfig
        {
            BackendBaseUrl = "https://epharm.inkar.kz",
            BackendFallbackBaseUrls = new() { "http://epharm.inkar.kz:8060" },
            FulfillmentBaseUrl = "https://orders.inkar.kz/api/ignored",
            FulfillmentFallbackBaseUrls = new() { "http://10.10.1.80:8080" },
        };

        Assert.Equal("https://epharm.inkar.kz/", config.GetBackendBaseUris().First().AbsoluteUri);
        Assert.Equal(
            new[] { "https://orders.inkar.kz/" },
            config.GetFulfillmentBaseUris().Select(x => x.AbsoluteUri));
    }

    [Fact]
    public void ExplicitInvalidFulfillmentEndpointFailsClosed()
    {
        var config = new EpharmConfig
        {
            BackendBaseUrl = "https://epharm.inkar.kz",
            FulfillmentBaseUrl = "not-a-url",
        };

        Assert.Throws<InvalidOperationException>(() => config.GetFulfillmentBaseUris());
    }

    [Fact]
    public void RemoteHttpOnlyEndpointFailsClosedButLoopbackRemainsAvailableForDevelopment()
    {
        var remote = new EpharmConfig { BackendBaseUrl = "http://epharm.inkar.kz:8060" };
        Assert.Throws<InvalidOperationException>(() => remote.GetBackendBaseUris());

        var local = new EpharmConfig { BackendBaseUrl = "http://localhost:8080" };
        Assert.Equal("http://localhost:8080/", local.GetBackendBaseUris().Single().AbsoluteUri);
    }

    [Fact]
    public void RegistrationBackoffCapsFailuresAndDelaysBlockedConfiguration()
    {
        var schedule = new FulfillmentRetrySchedule();
        var now = DateTimeOffset.Parse("2026-09-04T12:00:00Z");

        Assert.True(schedule.CanAttempt(now));
        Assert.Equal(TimeSpan.FromSeconds(10), schedule.RecordFailure(now, configurationBlocked: false));
        Assert.False(schedule.CanAttempt(now.AddSeconds(9)));
        Assert.Equal(TimeSpan.FromMinutes(5), schedule.RecordFailure(now.AddSeconds(10), configurationBlocked: true));
        Assert.False(schedule.CanAttempt(now.AddMinutes(5)));

        schedule.Reset();
        Assert.True(schedule.CanAttempt(now));
    }

    [Fact]
    public void RecommendationDefersAndCombinesOrderNoticesWithoutLosingEvents()
    {
        var coordinator = new PharmacistNoticeCoordinator();

        Assert.True(coordinator.TryShowFulfillment(2, out var visible));
        Assert.Equal(2, visible);

        coordinator.RecommendationStarting();
        Assert.False(coordinator.TryShowFulfillment(3, out _));
        Assert.Equal(5, coordinator.RecommendationClosed());

        Assert.True(coordinator.TryShowFulfillment(5, out visible));
        Assert.Equal(5, visible);
        coordinator.FulfillmentDismissed();
        Assert.True(coordinator.TryShowFulfillment(1, out visible));
        Assert.Equal(1, visible);
    }
}
