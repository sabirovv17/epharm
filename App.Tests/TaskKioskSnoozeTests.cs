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
}
