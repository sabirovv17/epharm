using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class TaskKioskSnoozeTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-10-14T10:00:00Z");
    private static TaskKioskItem Task(string token) => new("dispatch", "Task", "Pharmacy", "normal",
        null, token, "https://crm.inkar.kz/staff", Now.AddHours(1));

    [Fact]
    public void MinimizeAndLaterDeferSameDeliveryForAtMostTenMinutes()
    {
        var snooze = new TaskKioskSnooze();
        var task = Task("old-delivery");
        snooze.Defer([task], Now);
        Assert.False(snooze.ShouldShow([task], Now.AddMinutes(9)));
        Assert.True(snooze.ShouldShow([task], Now.AddMinutes(10)));
    }

    [Fact]
    public void NewDeliveryAppearsEvenWhenPreviousTaskWasMinimized()
    {
        var snooze = new TaskKioskSnooze();
        snooze.Defer([Task("old-delivery")], Now);
        Assert.True(snooze.ShouldShow([Task("new-delivery")], Now.AddSeconds(30)));
    }

    [Fact]
    public void EmptyOrExpiredAssignmentsNeverOpenPopup()
    {
        var snooze = new TaskKioskSnooze();
        Assert.False(snooze.ShouldShow([], Now));
        Assert.False(snooze.ShouldShow([Task("delivery")], Now.AddHours(2)));
    }

    [Theory]
    [InlineData(-1, false)]
    [InlineData(0, false)]
    [InlineData(1, true)]
    public void TaskDeadlineIsExclusiveEvenWhenQrTokenRemainsValid(int secondsUntilDue, bool expected)
    {
        var task = Task("delivery") with { DueAt = Now.AddSeconds(secondsUntilDue) };

        Assert.Equal(expected, task.IsCurrent(Now));
        Assert.Equal(expected, new TaskKioskSnooze().ShouldShow([task], Now));
    }

    [Fact]
    public void ExpiredTaskIsNotReopenedWhenSnoozeEnds()
    {
        var snooze = new TaskKioskSnooze();
        var task = Task("delivery") with { DueAt = Now.AddMinutes(5) };
        snooze.Defer([task], Now);

        Assert.False(snooze.ShouldShow([task], Now.AddMinutes(10)));
    }

    [Theory]
    [InlineData(-1, false)]
    [InlineData(0, false)]
    [InlineData(1, true)]
    public void TaskWithoutDeadlineStillRequiresUnexpiredQrToken(int secondsUntilExpiry, bool expected)
    {
        var task = Task("delivery") with { DueAt = null, ExpiresAt = Now.AddSeconds(secondsUntilExpiry) };

        Assert.Equal(expected, task.IsCurrent(Now));
        Assert.Equal(expected, new TaskKioskSnooze().ShouldShow([task], Now));
    }
}
