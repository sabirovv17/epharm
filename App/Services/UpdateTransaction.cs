using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;

namespace CustomerDisplay.Services;

public sealed record UpdateFile(string RelativePath, string NewSha256, string? OldSha256);
public sealed record UpdatePlan(string Id, string InstallRoot, string Version, string PackageSha256,
    string Token, int ParentId, long ParentStartTicks, UpdateFile[] Files);

/// <summary>Prepares a complete verified candidate and last-known-good backup without changing live files.</summary>
public static class UpdateTransaction
{
    public const string StateDirectory = ".epharm-update";
    public static string ControlRoot(string install) => Path.Combine(Path.GetFullPath(install), StateDirectory);
    public static string ActivePath(string install) => Path.Combine(ControlRoot(install), "active.json");
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    // An updater may keep a released handle open while supervising readiness. Object existence
    // is not ownership: createdNew=false must not reject the freshly launched candidate.
    public static bool TryAcquireInstance(Mutex mutex)
    {
        try { return mutex.WaitOne(0); }
        catch (AbandonedMutexException) { return true; }
    }

    public static string Prepare(string install, string zipPath, string version, string packageSha256, string applierScript,
        Action<string>? validateCandidate = null)
    {
        install = Path.GetFullPath(install).TrimEnd(Path.DirectorySeparatorChar);
        EnsurePhysical(install);
        if (!Hash(zipPath).Equals(packageSha256, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("Package changed after signature/hash verification.");
        var control = ControlRoot(install);
        EnsurePhysical(control);
        if (File.Exists(ActivePath(install))) throw new IOException("Another update transaction is pending.");
        Directory.CreateDirectory(control);
        var retainedBytes = RetainCompleted(install);
        var id = Guid.NewGuid().ToString("N");
        var transaction = Path.Combine(control, id);
        var candidate = Path.Combine(transaction, "candidate");
        var backup = Path.Combine(transaction, "backup");
        Directory.CreateDirectory(candidate);
        Directory.CreateDirectory(backup);
        var published = false;
        try
        {
            var files = new List<UpdateFile>();
            var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            long expanded = 0;
            long backupBytes = 0;
            using (var archive = ZipFile.OpenRead(zipPath))
            {
                foreach (var entry in archive.Entries)
                {
                    if (string.IsNullOrEmpty(entry.Name)) continue;
                    var relative = entry.FullName.Replace('/', Path.DirectorySeparatorChar);
                    if (!IsRuntimePath(relative) || !names.Add(relative))
                        throw new InvalidDataException("Unsafe or duplicate update entry: " + entry.FullName);
                    // Unix symlinks and Windows reparse entries may not redirect extraction.
                    if (((entry.ExternalAttributes >> 16) & 0xF000) == 0xA000 ||
                        (entry.ExternalAttributes & (int)FileAttributes.ReparsePoint) != 0)
                        throw new InvalidDataException("Linked update entry is not allowed.");
                    expanded = checked(expanded + entry.Length);
                    if (expanded > 3L * 1024 * 1024 * 1024 || files.Count >= 20000)
                        throw new InvalidDataException("Update expansion limit exceeded.");
                    var target = Child(install, relative);
                    EnsurePhysical(target);
                    var oldBytes = File.Exists(target) ? new FileInfo(target).Length : 0;
                    backupBytes = checked(backupBytes + oldBytes);
                    if (retainedBytes + expanded + backupBytes > 8L * 1024 * 1024 * 1024)
                        throw new IOException("Update recovery storage would exceed 8 GiB.");
                    var drive = new DriveInfo(Path.GetPathRoot(install)!);
                    if (drive.AvailableFreeSpace < entry.Length + oldBytes + 512L * 1024 * 1024)
                        throw new IOException("Insufficient update/rollback space (512 MiB reserve required).");
                    var prepared = Child(candidate, relative);
                    Directory.CreateDirectory(Path.GetDirectoryName(prepared)!);
                    entry.ExtractToFile(prepared, false);
                    string? oldHash = null;
                    if (File.Exists(target))
                    {
                        oldHash = Hash(target);
                        var old = Child(backup, relative);
                        Directory.CreateDirectory(Path.GetDirectoryName(old)!);
                        File.Copy(target, old, false);
                        if (Hash(old) != oldHash || Hash(target) != oldHash)
                            throw new IOException("Installed binary changed during backup.");
                    }
                    files.Add(new UpdateFile(relative, Hash(prepared), oldHash));
                }
            }
            if (!names.Contains("CustomerDisplay.exe") || !names.Contains("CustomerDisplay.dll"))
                throw new InvalidDataException("Update has no application executable/assembly.");
            validateCandidate?.Invoke(candidate);
            using var process = Process.GetCurrentProcess();
            var plan = new UpdatePlan(id, install, version, packageSha256,
                Convert.ToHexString(RandomNumberGenerator.GetBytes(32)), process.Id, process.StartTime.ToUniversalTime().Ticks,
                files.OrderBy(f => f.RelativePath, StringComparer.OrdinalIgnoreCase).ToArray());
            WriteJson(Path.Combine(transaction, "plan.json"), plan);
            WriteApplierScript(transaction, applierScript);
            // Active reference is published last. A failed preparation cannot block startup.
            var activeReference = Path.Combine(transaction, "active-ref.json");
            WriteJson(activeReference, new { Id = id });
            File.Move(activeReference, ActivePath(install), overwrite: false);
            published = true;
            return Path.Combine(transaction, "plan.json");
        }
        catch
        {
            // Only this newly created GUID, before publication. Never remove an active transaction.
            if (!published) DeleteOwnedTransaction(install, id);
            throw;
        }
    }

    private static long RetainCompleted(string install)
    {
        var control = ControlRoot(install);
        var completed = new List<(string Id, DateTime Changed)>();
        foreach (var directory in Directory.EnumerateDirectories(control))
        {
            EnsurePhysical(directory);
            var id = Path.GetFileName(directory);
            if (!Guid.TryParseExact(id, "N", out _)) continue;
            var statePath = Path.Combine(directory, "state.json");
            var planPath = Path.Combine(directory, "plan.json");
            EnsurePhysical(statePath);
            EnsurePhysical(planPath);
            if (!File.Exists(statePath) || !File.Exists(planPath)) continue;
            using var state = JsonDocument.Parse(File.ReadAllText(statePath));
            var status = state.RootElement.GetProperty("Status").GetString();
            if (status is not ("committed" or "rolled-back")) continue;
            var plan = JsonSerializer.Deserialize<UpdatePlan>(File.ReadAllText(planPath));
            if (plan?.Id != id || !plan.InstallRoot.Equals(install, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("Foreign rollback bundle.");
            completed.Add((id, File.GetLastWriteTimeUtc(statePath)));
        }
        // Retain two completed predecessors; never prune incomplete/failed recovery evidence.
        foreach (var old in completed.OrderByDescending(x => x.Changed).Skip(2)) DeleteOwnedTransaction(install, old.Id);
        long bytes = 0;
        foreach (var file in PhysicalFiles(control))
        {
            bytes = checked(bytes + new FileInfo(file).Length);
            if (bytes > 8L * 1024 * 1024 * 1024) throw new IOException("Update recovery storage needs reviewed maintenance (8 GiB limit).");
        }
        return bytes;
    }

    private static IEnumerable<string> PhysicalFiles(string root)
    {
        EnsurePhysical(root);
        foreach (var file in Directory.EnumerateFiles(root)) { EnsurePhysical(file); yield return file; }
        foreach (var directory in Directory.EnumerateDirectories(root))
        {
            EnsurePhysical(directory);
            foreach (var file in PhysicalFiles(directory)) yield return file;
        }
    }

    private static void DeleteOwnedTransaction(string install, string id)
    {
        if (!Guid.TryParseExact(id, "N", out _)) throw new InvalidDataException("Invalid owned transaction id.");
        var root = Child(ControlRoot(install), id);
        var files = PhysicalFiles(root).ToArray();
        foreach (var file in files)
        {
            var relative = Path.GetRelativePath(root, file).Replace('\\', '/');
            var fixedName = relative is "plan.json" or "state.json" or "ready.json" or "apply-update.ps1" or "apply.log" or "active-ref.json" or
                "plan.json.tmp" or "state.json.tmp" or "ready.json.tmp" or "active-ref.json.tmp" or "apply-update.ps1.tmp";
            var payload = relative.StartsWith("candidate/", StringComparison.Ordinal) || relative.StartsWith("backup/", StringComparison.Ordinal);
            if (!fixedName && !(payload && IsRuntimePath(relative[(relative.IndexOf('/') + 1)..])))
                throw new InvalidDataException("Unexpected file in update bundle; maintenance must review it.");
        }
        // Exact physical child was enumerated and validated. No install/config/data target is included.
        Directory.Delete(root, recursive: true);
    }

    public static UpdatePlan? ReadActive(string install)
    {
        var active = ActivePath(install);
        EnsurePhysical(active);
        if (!File.Exists(active)) return null;
        using var document = JsonDocument.Parse(File.ReadAllText(active));
        var id = document.RootElement.GetProperty("Id").GetString();
        if (!Guid.TryParseExact(id, "N", out _)) throw new InvalidDataException("Invalid update reference.");
        var path = Path.Combine(ControlRoot(install), id!, "plan.json");
        EnsurePhysical(path);
        var plan = JsonSerializer.Deserialize<UpdatePlan>(File.ReadAllText(path)) ?? throw new InvalidDataException("Missing update plan.");
        if (plan.Id != id || !Path.GetFullPath(plan.InstallRoot).TrimEnd(Path.DirectorySeparatorChar)
            .Equals(Path.GetFullPath(install).TrimEnd(Path.DirectorySeparatorChar), StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("Foreign update plan.");
        return plan;
    }

    public static bool IsRejected(string install, string packageSha256)
    {
        var path = Path.Combine(ControlRoot(install), "rejected.json");
        EnsurePhysical(path);
        if (!File.Exists(path)) return false;
        using var json = JsonDocument.Parse(File.ReadAllText(path));
        return json.RootElement.GetProperty("PackageSha256").GetString()?.Equals(packageSha256, StringComparison.OrdinalIgnoreCase) == true;
    }

    public static bool IsRuntimePath(string relative)
    {
        var parts = relative.Replace('\\', '/').Split('/');
        if (Path.IsPathRooted(relative) || parts.Any(p => p.Length == 0 || p is "." or ".." ||
            p.Contains(':') || p.EndsWith('.') || p.EndsWith(' ') || p.IndexOfAny(new[] { '*', '?', '"', '<', '>', '|' }) >= 0)) return false;
        // Only runtime payloads. Configuration, credentials, receipts, queues, logs, scripts and
        // other pharmacy state are never package-managed, even if present in a signed archive.
        var file = parts[^1];
        return (parts.Length == 1 || new[] { "runtimes", "libvlc", "x64", "x86", "Assets" }.Contains(parts[0], StringComparer.OrdinalIgnoreCase)) &&
            (file.EndsWith(".dll", StringComparison.OrdinalIgnoreCase) || file.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ||
             parts.Length == 1 && (file.Equals("CustomerDisplay.deps.json", StringComparison.OrdinalIgnoreCase) ||
                                  file.Equals("CustomerDisplay.runtimeconfig.json", StringComparison.OrdinalIgnoreCase)) ||
             file.EndsWith(".ttf", StringComparison.OrdinalIgnoreCase) || file.EndsWith(".otf", StringComparison.OrdinalIgnoreCase));
    }

    public static string Child(string root, string relative)
    {
        var path = Path.GetFullPath(Path.Combine(root, relative));
        if (!path.StartsWith(Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("Update path escaped its root.");
        return path;
    }

    public static void EnsurePhysical(string path)
    {
        for (var current = Path.GetFullPath(path); !string.IsNullOrEmpty(current); current = Path.GetDirectoryName(current))
        {
            FileAttributes attributes;
            // Exists follows links and can hide dangling reparse points. Inspect the entry
            // itself; genuinely absent children are permitted so callers can create them.
            try { attributes = File.GetAttributes(current); }
            catch (FileNotFoundException) { continue; }
            catch (DirectoryNotFoundException) { continue; }
            if ((attributes & FileAttributes.ReparsePoint) != 0)
                throw new InvalidDataException("Update cannot follow a reparse point.");
        }
    }

    public static string Hash(string path)
    {
        EnsurePhysical(path);
        using var file = File.OpenRead(path);
        return Convert.ToHexString(SHA256.HashData(file));
    }

    /// <summary>Publish only the embedded helper, without following an existing helper/temp link.</summary>
    public static void WriteApplierScript(string transaction, string script)
    {
        var path = Child(transaction, "apply-update.ps1");
        var temporary = path + ".tmp";
        EnsurePhysical(path);
        EnsurePhysical(temporary);
        // A prior interrupted write may leave this exact owned temporary file. Never truncate
        // an existing path: CreateNew fails closed if another writer installs one after deletion.
        if (File.Exists(temporary)) File.Delete(temporary);
        using (var stream = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
        {
            using (var writer = new StreamWriter(stream, new UTF8Encoding(true), leaveOpen: true))
            {
                writer.Write(script);
                writer.Flush();
            }
            stream.Flush(true);
        }
        EnsurePhysical(path);
        EnsurePhysical(temporary);
        File.Move(temporary, path, overwrite: true);
    }

    public static void WriteJson<T>(string path, T value)
    {
        var temporary = path + ".tmp";
        EnsurePhysical(path);
        EnsurePhysical(temporary);
        using (var stream = new FileStream(temporary, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            JsonSerializer.Serialize(stream, value, Json);
            stream.Flush(true);
        }
        File.Move(temporary, path, true);
    }
}
