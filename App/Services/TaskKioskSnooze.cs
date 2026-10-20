using System;
using System.Collections.Generic;
using System.Linq;

namespace CustomerDisplay.Services;

/// <summary>Close and minimize defer only the displayed delivery, never a newly issued task.</summary>
public sealed class TaskKioskSnooze
{
    private readonly Dictionary<string, DateTimeOffset> _until = new();
    public void Defer(IEnumerable<TaskKioskItem> tasks, DateTimeOffset now)
    {
        foreach (var task in tasks) _until[task.NotificationKey] = now.AddMinutes(10);
    }

    public bool ShouldShow(IEnumerable<TaskKioskItem> tasks, DateTimeOffset now)
    {
        var live = tasks.Where(task => task.IsCurrent(now)).ToArray();
        var keys = live.Select(task => task.NotificationKey).ToHashSet();
        foreach (var key in _until.Keys.Where(key => !keys.Contains(key)).ToArray()) _until.Remove(key);
        return live.Any(task => !_until.TryGetValue(task.NotificationKey, out var until) || until <= now);
    }
}
