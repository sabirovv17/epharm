using System;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;

namespace CustomerDisplay.Services;

/// <summary>Bounded fresh process proof for a returned grant, never an auth-request retry.</summary>
public static class StaffProcessConfirmation
{
    public static async Task<bool> ConfirmAsync(string expected, Func<CancellationToken, Task<StaffProcessPresence>> observe,
        Func<bool> requestIsCurrent, CancellationToken ct, Func<TimeSpan, CancellationToken, Task>? delay = null)
    {
        delay ??= Task.Delay;
        var started = Stopwatch.GetTimestamp();
        var budget = TimeSpan.FromSeconds(2);
        for (var attempt = 0; attempt < 9; attempt++)
        {
            ct.ThrowIfCancellationRequested();
            if (!requestIsCurrent()) return false;
            var remaining = budget - Stopwatch.GetElapsedTime(started);
            if (remaining <= TimeSpan.Zero) return false;
            StaffProcessPresence process;
            try { process = await observe(ct).WaitAsync(remaining, ct); }
            catch (TimeoutException) { return false; } // At most one outstanding OS read.
            if (!requestIsCurrent()) return false;
            if (process.Status is "resolved" or "ambiguous_process" && process.Identity != null)
                return process.Identity == expected; // A confirmed different process is terminal.
            if (attempt == 8) return false;
            remaining = budget - Stopwatch.GetElapsedTime(started);
            if (remaining <= TimeSpan.Zero) return false;
            await delay(remaining < TimeSpan.FromMilliseconds(250) ? remaining : TimeSpan.FromMilliseconds(250), ct);
        }
        return false;
    }
}
