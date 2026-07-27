using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using CustomerDisplay.Config;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Finds Standard-N cash event logs without scanning an entire workstation. Explicit paths and
    /// the two v1.0.23 paths are watched immediately. Background discovery is bounded to directories
    /// near likely cash-desk processes and top-level folders whose names look like Standard-N/Kassa.
    /// A path that produces a real cash marker is cached for subsequent starts.
    /// </summary>
    internal static class StandardNLogLocator
    {
        private const string ConfirmedPathsFile = @"C:\Epharm\standardn-log-paths.txt";
        private const int MaxSearchRoots = 48;
        private const int MaxMatches = 32;
        private const int MaxDepth = 7;

        private static readonly string[] LegacyPaths =
        {
            @"C:\Standart-N\Kassir\zkassa.log",
            @"C:\Standart-N_DEMO\Apteka_KZ DEMO\Kassir\zkassa.log",
        };

        private static readonly ConcurrentDictionary<string, byte> Confirmed =
            new(StringComparer.OrdinalIgnoreCase);
        private static readonly object CacheLock = new();

        public static IReadOnlyList<string> BootstrapCandidates(EpharmConfig? config = null)
        {
            var result = new List<string>();
            AddPaths(result, config?.StandardNLogPaths);
            AddPaths(result, ReadConfirmedPaths());
            AddPaths(result, LegacyPaths);
            return NormalizeDistinct(result);
        }

        public static IReadOnlyList<string> DiscoverExisting(EpharmConfig? config = null)
        {
            var matches = new List<string>();
            AddPaths(matches, BootstrapCandidates(config).Where(File.Exists));

            foreach (var root in SearchRoots(config).Take(MaxSearchRoots))
            {
                if (matches.Count >= MaxMatches) break;
                FindNamedLogs(root, matches);
            }

            return NormalizeDistinct(matches).Take(MaxMatches).ToArray();
        }

        public static bool IsConfirmedCashLog(string path) =>
            Confirmed.ContainsKey(Normalize(path)) ||
            LegacyPaths.Select(Normalize).Contains(Normalize(path), StringComparer.OrdinalIgnoreCase);

        public static bool IsCashEventLine(string? line) =>
            !string.IsNullOrWhiteSpace(line) &&
            (line.Contains("Add2Cheque", StringComparison.OrdinalIgnoreCase) ||
             line.Contains("ChequeList.OnChange", StringComparison.OrdinalIgnoreCase) ||
             line.Contains("RunScriptByIndex", StringComparison.OrdinalIgnoreCase) ||
             line.Contains("iPartID=", StringComparison.OrdinalIgnoreCase));

        public static void RememberConfirmed(string path)
        {
            var normalized = Normalize(path);
            if (string.IsNullOrWhiteSpace(normalized) || !Confirmed.TryAdd(normalized, 0)) return;

            lock (CacheLock)
            {
                try
                {
                    var parent = Path.GetDirectoryName(ConfirmedPathsFile);
                    if (!string.IsNullOrWhiteSpace(parent)) Directory.CreateDirectory(parent);
                    var paths = ReadConfirmedPaths()
                        .Append(normalized)
                        .Select(Normalize)
                        .Where(p => !string.IsNullOrWhiteSpace(p))
                        .Distinct(StringComparer.OrdinalIgnoreCase)
                        .OrderBy(p => p, StringComparer.OrdinalIgnoreCase)
                        .ToArray();
                    File.WriteAllLines(ConfirmedPathsFile, paths);
                }
                catch
                {
                    // Discovery is fail-safe. A read-only workstation can still use the path now;
                    // it will simply be discovered again after the next launch.
                }
            }
        }

        private static IEnumerable<string> SearchRoots(EpharmConfig? config)
        {
            var roots = new List<string>();
            foreach (var path in BootstrapCandidates(config))
                AddRootAndParent(roots, Path.GetDirectoryName(path));

            try
            {
                foreach (var process in Process.GetProcesses())
                {
                    try
                    {
                        var executable = process.MainModule?.FileName;
                        if (!LooksLikeCashDesk(process.ProcessName) && !LooksLikeCashDesk(executable)) continue;
                        AddRootAndParent(roots, Path.GetDirectoryName(executable));
                    }
                    catch
                    {
                        // Some system processes deny MainModule access. They are unrelated here.
                    }
                    finally
                    {
                        process.Dispose();
                    }
                }
            }
            catch { }

            try
            {
                foreach (var drive in DriveInfo.GetDrives().Where(d => d.IsReady && d.DriveType == DriveType.Fixed))
                {
                    foreach (var relative in new[]
                    {
                        "Standart-N", "Standart-N_DEMO", "StandartN", "Standart_N",
                        "Kassir", "Kassa", "Apteka",
                        @"Program Files\Standart-N", @"Program Files (x86)\Standart-N",
                    })
                        AddRoot(roots, Path.Combine(drive.RootDirectory.FullName, relative));

                    try
                    {
                        foreach (var directory in Directory.EnumerateDirectories(drive.RootDirectory.FullName))
                        {
                            if (LooksLikeCashDesk(Path.GetFileName(directory))) AddRoot(roots, directory);
                        }
                    }
                    catch { }
                }
            }
            catch { }

            return NormalizeDistinct(roots).Where(Directory.Exists);
        }

        private static void FindNamedLogs(string root, List<string> result)
        {
            if (!Directory.Exists(root)) return;
            var queue = new Queue<(string Path, int Depth)>();
            var visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            queue.Enqueue((root, 0));

            while (queue.Count > 0 && result.Count < MaxMatches)
            {
                var current = queue.Dequeue();
                var normalized = Normalize(current.Path);
                if (!visited.Add(normalized)) continue;

                try
                {
                    foreach (var file in Directory.EnumerateFiles(current.Path, "zkassa.log", SearchOption.TopDirectoryOnly))
                    {
                        result.Add(file);
                        if (result.Count >= MaxMatches) return;
                    }

                    if (current.Depth >= MaxDepth) continue;
                    foreach (var directory in Directory.EnumerateDirectories(current.Path))
                    {
                        try
                        {
                            var attributes = File.GetAttributes(directory);
                            if ((attributes & FileAttributes.ReparsePoint) != 0) continue;
                            queue.Enqueue((directory, current.Depth + 1));
                        }
                        catch { }
                    }
                }
                catch
                {
                    // Access denied or a directory disappearing during enumeration is expected on
                    // managed pharmacy workstations; continue with the other bounded roots.
                }
            }
        }

        private static bool LooksLikeCashDesk(string? value)
        {
            if (string.IsNullOrWhiteSpace(value)) return false;
            var text = value.ToLowerInvariant();
            return text.Contains("standart") || text.Contains("standard") ||
                   text.Contains("kass") || text.Contains("cash") ||
                   text.Contains("apte") || text.Contains("managerxp") ||
                   text.Contains("касс") || text.Contains("аптек");
        }

        private static IReadOnlyList<string> ReadConfirmedPaths()
        {
            try
            {
                if (!File.Exists(ConfirmedPathsFile)) return Array.Empty<string>();
                var paths = File.ReadAllLines(ConfirmedPathsFile)
                    .Select(Normalize)
                    .Where(p => !string.IsNullOrWhiteSpace(p))
                    .Distinct(StringComparer.OrdinalIgnoreCase)
                    .ToArray();
                foreach (var path in paths) Confirmed.TryAdd(path, 0);
                return paths;
            }
            catch
            {
                return Array.Empty<string>();
            }
        }

        private static void AddPaths(List<string> target, IEnumerable<string>? paths)
        {
            if (paths == null) return;
            foreach (var path in paths)
            {
                var normalized = Normalize(path);
                if (!string.IsNullOrWhiteSpace(normalized)) target.Add(normalized);
            }
        }

        private static void AddRootAndParent(List<string> roots, string? directory)
        {
            AddRoot(roots, directory);
            if (string.IsNullOrWhiteSpace(directory)) return;
            try
            {
                var parent = Directory.GetParent(directory)?.FullName;
                if (!string.IsNullOrWhiteSpace(parent) && Directory.GetParent(parent) != null)
                    AddRoot(roots, parent);
            }
            catch { }
        }

        private static void AddRoot(List<string> roots, string? path)
        {
            var normalized = Normalize(path);
            if (!string.IsNullOrWhiteSpace(normalized)) roots.Add(normalized);
        }

        private static IReadOnlyList<string> NormalizeDistinct(IEnumerable<string> paths) =>
            paths.Select(Normalize)
                .Where(p => !string.IsNullOrWhiteSpace(p))
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToArray();

        private static string Normalize(string? path)
        {
            if (string.IsNullOrWhiteSpace(path)) return "";
            try { return Path.GetFullPath(path.Trim().Trim('"')); }
            catch { return ""; }
        }
    }
}
