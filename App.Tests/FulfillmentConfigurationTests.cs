using CustomerDisplay.Config;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class FulfillmentConfigurationTests
{
    [Fact]
    public void FulfillmentInheritsExistingBackendEndpoints()
    {
        var config = new EpharmConfig
        {
            BackendBaseUrl = "https://epharm.inkar.kz",
            BackendFallbackBaseUrls = new() { "http://epharm.inkar.kz:8060" },
        };

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
            new[] { "https://orders.inkar.kz/", "http://10.10.1.80:8080/" },
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
