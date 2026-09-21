using System;

namespace CustomerDisplay.Services;

/// <summary>
/// Scores a top-level Windows process/window as the Standard-N cashier application. Keeping the
/// heuristic pure makes monitor-role selection testable without enumerating desktop processes.
/// </summary>
internal static class StandardNWindowIdentity
{
    public static int Score(string? processName, string? windowTitle)
    {
        var process = (processName ?? string.Empty).Trim().ToLowerInvariant();
        var title = (windowTitle ?? string.Empty).Trim().ToLowerInvariant();
        var score = 0;

        if (title.Contains("стандарт-н", StringComparison.Ordinal) ||
            title.Contains("standart-n", StringComparison.Ordinal) ||
            title.Contains("standard-n", StringComparison.Ordinal))
            score += 100;

        if (string.Equals(process, "zkassa", StringComparison.Ordinal) ||
            process.Contains("zkassa", StringComparison.Ordinal))
            score += 90;
        else if (process.Contains("standart", StringComparison.Ordinal) ||
                 process.Contains("standard", StringComparison.Ordinal))
            score += 60;

        if (title.Contains("автоматизац", StringComparison.Ordinal)) score += 20;
        if (title.Contains("касса", StringComparison.Ordinal)) score += 15;

        return score;
    }
}
