using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace CustomerDisplay.Services;

/// <summary>Atomic, DPAPI-protected evidence of an actual QR display; never stores its public URL.</summary>
public sealed class MerchShownOutbox
{
    public sealed record Receipt(string DispatchId, string DeliveryToken, DateTimeOffset ExpiresAt,
        int Attempts = 0, DateTimeOffset RetryAt = default);
    private sealed record Snapshot(string PharmacyId, string DeviceId, List<Receipt> Receipts);
    private readonly string _path;
    private readonly string _pharmacyId;
    private readonly string _deviceId;
    private readonly Func<byte[], byte[]> _protect;
    private readonly Func<byte[], byte[]> _unprotect;
    private readonly Func<DateTimeOffset> _clock;
    private readonly Action<string>? _log;
    private readonly object _lock = new();
    private readonly SemaphoreSlim _flush = new(1, 1);
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("Epharm.MerchShown.v1");
    private const int MaxEntries = 200;
    private const int MaxFileBytes = 1024 * 1024;

    public MerchShownOutbox(string path, string pharmacyId, string deviceId,
        Func<byte[], byte[]>? protect = null, Func<byte[], byte[]>? unprotect = null,
        Func<DateTimeOffset>? clock = null, Action<string>? log = null)
    {
        _path = path;
        _pharmacyId = pharmacyId;
        _deviceId = deviceId;
        _protect = protect ?? ProtectWindows;
        _unprotect = unprotect ?? UnprotectWindows;
        _clock = clock ?? (() => DateTimeOffset.UtcNow);
        _log = log;
    }

    public void Enqueue(TaskKioskItem task)
    {
        if (task.ExpiresAt <= _clock()) return;
        if (string.IsNullOrWhiteSpace(task.Id) || task.Id.Length > 128 ||
            string.IsNullOrWhiteSpace(task.NotificationKey) || task.NotificationKey.Length > 4096)
            throw new InvalidOperationException("Invalid task delivery identity.");
        lock (_lock)
        {
            var items = Read();
            if (items.Any(item => item.DeliveryToken == task.NotificationKey)) return;
            // Bound retention even when an upstream accidentally returns a very distant expiry.
            var expiry = task.ExpiresAt < _clock().AddDays(7) ? task.ExpiresAt : _clock().AddDays(7);
            items.Add(new Receipt(task.Id, task.NotificationKey, expiry, RetryAt: _clock()));
            if (items.Count > MaxEntries) items.RemoveRange(0, items.Count - MaxEntries);
            Write(items);
        }
    }

    public async Task FlushAsync(IMerchTaskApi api, Action<string>? delivered = null,
        CancellationToken cancellation = default)
    {
        if (!await _flush.WaitAsync(0, cancellation).ConfigureAwait(false)) return;
        try
        {
            List<Receipt> ready;
            lock (_lock)
            {
                var items = Read();
                Write(items); // Remove expired receipts even if no further QR is displayed.
                ready = items.Where(item => item.RetryAt <= _clock()).Take(10).ToList();
            }
            foreach (var item in ready)
            {
                cancellation.ThrowIfCancellationRequested();
                if (item.ExpiresAt <= _clock()) continue;
                bool accepted;
                var transientFailure = false;
                try
                {
                    accepted = await api.AcknowledgeMerchTaskAsync(item.DispatchId, _pharmacyId,
                        _deviceId, item.DeliveryToken, cancellation).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { throw; }
                catch { accepted = false; transientFailure = true; }
                lock (_lock)
                {
                    var items = Read();
                    var index = items.FindIndex(entry => entry.DeliveryToken == item.DeliveryToken);
                    if (index >= 0)
                    {
                        // A valid negative reply means this capability is closed/revoked. Only
                        // transport/unavailability exceptions deserve a retry after backoff.
                        if (!transientFailure) items.RemoveAt(index);
                        else items[index] = item with { Attempts = Math.Min(10, item.Attempts + 1),
                            RetryAt = _clock().AddSeconds(Math.Min(300, 30 * Math.Pow(2, Math.Min(4, item.Attempts)))) };
                        Write(items);
                    }
                }
                if (accepted) delivered?.Invoke(item.DeliveryToken);
                else if (!transientFailure)
                    _log?.Invoke("Задания аптек: отклонённое сервером подтверждение показа удалено из очереди.");
            }
        }
        finally { _flush.Release(); }
    }

    private static byte[] ProtectWindows(byte[] bytes)
    {
        if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("Windows DPAPI is required.");
        return ProtectedData.Protect(bytes, Entropy, DataProtectionScope.CurrentUser);
    }

    private static byte[] UnprotectWindows(byte[] bytes)
    {
        if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("Windows DPAPI is required.");
        return ProtectedData.Unprotect(bytes, Entropy, DataProtectionScope.CurrentUser);
    }

    private List<Receipt> Read()
    {
        if (!File.Exists(_path)) return [];
        try
        {
            if (new FileInfo(_path).Length > MaxFileBytes) throw new InvalidDataException("Task delivery queue is too large.");
            var bytes = File.ReadAllBytes(_path);
            var plain = _unprotect(bytes);
            try
            {
                var snapshot = JsonSerializer.Deserialize<Snapshot>(plain);
                if (snapshot == null || snapshot.Receipts == null)
                    throw new InvalidDataException("Task delivery queue is incomplete.");
                if (snapshot.PharmacyId != _pharmacyId || snapshot.DeviceId != _deviceId) return [];
                if (snapshot.Receipts.Any(item => item == null || string.IsNullOrWhiteSpace(item.DispatchId) ||
                    item.DispatchId.Length > 128 || string.IsNullOrWhiteSpace(item.DeliveryToken) || item.DeliveryToken.Length > 4096))
                    throw new InvalidDataException("Task delivery queue contains an invalid receipt.");
                return snapshot.Receipts.Where(item => item.ExpiresAt > _clock()).TakeLast(MaxEntries).ToList();
            }
            finally { CryptographicOperations.ZeroMemory(plain); }
        }
        catch (Exception ex) when (ex is CryptographicException or JsonException or InvalidDataException)
        {
            // Keep the most recent encrypted corrupt snapshot beside its protected queue. Rename
            // must succeed before starting afresh; permission/I/O errors retain the original.
            // A fixed destination bounds diagnostic retention to one snapshot per identity scope.
            File.Move(_path, _path + ".corrupt", overwrite: true);
            _log?.Invoke($"Задания аптек: повреждённая очередь подтверждений сохранена в карантин ({ex.GetType().Name}).");
            return [];
        }
    }

    private void Write(List<Receipt> items)
    {
        var plain = JsonSerializer.SerializeToUtf8Bytes(new Snapshot(_pharmacyId, _deviceId, items));
        byte[] encrypted;
        try { encrypted = _protect(plain); }
        finally { CryptographicOperations.ZeroMemory(plain); }
        if (encrypted.Length > MaxFileBytes) throw new InvalidDataException("Task delivery queue is too large.");
        var directory = Path.GetDirectoryName(_path);
        if (!string.IsNullOrEmpty(directory)) Directory.CreateDirectory(directory);
        var temp = _path + ".tmp";
        using (var stream = new FileStream(temp, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            stream.Write(encrypted);
            stream.Flush(flushToDisk: true);
        }
        File.Move(temp, _path, overwrite: true);
    }
}
