using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StaffPromptThrottleTests
{
    [Fact]
    public void RepeatedGenerationsCannotResetTheMonotonicPresentationBudget()
    {
        var time = new Clock(); var policy = new StaffPromptThrottle(time);
        Assert.True(policy.TryShowAutomatically());
        for (var second = 1; second < 120; second++) {
            time.Ticks = TimeSpan.FromSeconds(second).Ticks;
            Assert.False(policy.TryShowAutomatically());
        }
        time.Ticks = TimeSpan.FromMinutes(2).Ticks;
        Assert.True(policy.TryShowAutomatically());
        Assert.False(policy.TryShowAutomatically());
    }

    [Fact]
    public void ManualOpeningRestartsAutomaticBudgetWithoutRestrictingManualAccess()
    {
        var time = new Clock(); var policy = new StaffPromptThrottle(time);
        policy.RecordShown(); time.Ticks += TimeSpan.FromSeconds(119).Ticks;
        policy.RecordShown(); time.Ticks += TimeSpan.FromSeconds(1).Ticks;
        Assert.False(policy.TryShowAutomatically());
        time.Ticks += TimeSpan.FromSeconds(119).Ticks;
        Assert.True(policy.TryShowAutomatically());
    }

    private sealed class Clock : TimeProvider
    {
        public long Ticks;
        public override long TimestampFrequency => TimeSpan.TicksPerSecond;
        public override long GetTimestamp() => Ticks;
    }
}
