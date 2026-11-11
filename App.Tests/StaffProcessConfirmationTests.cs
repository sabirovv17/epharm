using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StaffProcessConfirmationTests
{
    [Fact]
    public async Task UnknownObservationHasABoundedRetryCountAndNeverBecomesProof()
    {
        var count = 0;
        Assert.False(await StaffProcessConfirmation.ConfirmAsync("expected", _ => {
            count++; return Task.FromResult(new StaffProcessPresence("no_process"));
        }, () => true, TestContext.Current.CancellationToken, (_, _) => Task.CompletedTask));
        Assert.Equal(9, count);
    }

    [Fact]
    public async Task AChangedGenerationDuringObservationCannotBeAccepted()
    {
        var current = true;
        Assert.False(await StaffProcessConfirmation.ConfirmAsync("expected", _ => {
            current = false; return Task.FromResult(new StaffProcessPresence("resolved", "expected"));
        }, () => current, TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task MatchingIdentityWithAnUnreadablePeerIsNotFreshProof()
    {
        var count = 0;
        Assert.True(await StaffProcessConfirmation.ConfirmAsync("expected", _ => Task.FromResult(++count == 1
            ? new StaffProcessPresence("process_unavailable", "expected") : new("resolved", "expected")),
            () => true, TestContext.Current.CancellationToken, (_, _) => Task.CompletedTask));
        Assert.Equal(2, count);
    }
}
