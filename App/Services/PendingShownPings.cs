using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services;

/// <summary>
/// Dispatcher records only memory. One background writer journals first, then transfers to SQLite.
/// Orderly disposal gives pending journal writes a bounded grace period, without waiting forever
/// for a failed disk/SQLite writer. A power cut before any durable write cannot be guaranteed.
/// </summary>
public sealed class PendingShownPings : IDisposable
{
    private const int Capacity = 4096;
    private readonly object _gate = new();
    private readonly Dictionary<string, (OutboxShownPayload Payload, LinkedListNode<string> Node)> _pending = new(StringComparer.Ordinal);
    private readonly LinkedList<string> _order = new();
    private readonly string _directory;
    private readonly Action<string, string, string> _enqueue;
    private readonly Action<string>? _log;
    private readonly Timer? _timer;
    private readonly TimeSpan _shutdownGrace;
    private Task _writer = Task.CompletedTask;
    private Task? _shutdown;
    private bool _closing;
    private int _recoveryOffset;

    public PendingShownPings(string directory, Action<string, string, string> enqueue, Action<string>? log = null,
        bool autoStart = true, TimeSpan? shutdownGrace = null)
    {
        _directory = Path.GetFullPath(directory);
        _enqueue = enqueue;
        _log = log;
        _shutdownGrace = shutdownGrace ?? TimeSpan.FromSeconds(2);
        if (autoStart) _timer = new Timer(_ => _ = FlushAsync(), null, TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(5));
    }

    public int PendingCount { get { lock (_gate) return _pending.Count; } }

    public bool Record(string eventId, DateTimeOffset shownAt)
    {
        if (string.IsNullOrWhiteSpace(eventId) || eventId.Length > 256 || shownAt == default) return false;
        lock (_gate)
        {
            if (_closing) return false;
            if (_pending.ContainsKey(eventId)) return true;
            if (_pending.Count >= Capacity) return false;
            var node = _order.AddLast(eventId);
            _pending.Add(eventId, (new OutboxShownPayload { EventId = eventId, ShownAt = shownAt }, node));
            return true;
        }
    }

    public Task FlushAsync()
    {
        lock (_gate)
        {
            if (_closing || !_writer.IsCompleted) return Task.CompletedTask;
            return _writer = Task.Run(FlushCore);
        }
    }

    private void FlushCore()
    {
        try
        {
            EnsureDirectory();
            foreach (var payload in Batch(64))
                try { Journal(payload); }
                catch (Exception error) { _log?.Invoke("shown-ping journal pending: " + error.GetType().Name); }

            // Disk recovery is streamed, not merged into the bounded live dictionary. Rotating
            // inspection also prevents retained corrupt/locked files from starving later records.
            var files = Directory.EnumerateFiles(_directory, "*.json").Skip(_recoveryOffset).Take(64).ToArray();
            _recoveryOffset = files.Length < 64 ? 0 : _recoveryOffset + files.Length;
            foreach (var file in files)
            {
                try
                {
                    var payload = Read(file);
                    _enqueue("shown_" + payload.EventId, "shown", JsonSerializer.Serialize(payload, EpharmJson.Options));
                    File.Delete(file); // SQLite is now the durable owner.
                    lock (_gate)
                    {
                        if (_pending.Remove(payload.EventId, out var entry)) _order.Remove(entry.Node);
                    }
                }
                catch (Exception error) { _log?.Invoke("shown-ping retained; will retry: " + error.GetType().Name); }
            }
        }
        catch (Exception error) { _log?.Invoke("shown-ping storage unavailable; memory retained: " + error.GetType().Name); }
    }

    private OutboxShownPayload[] Batch(int maximum)
    {
        lock (_gate)
        {
            var items = _order.Take(maximum).Select(id => _pending[id].Payload).ToArray();
            foreach (var item in items)
            {
                var node = _pending[item.EventId].Node;
                _order.Remove(node); _order.AddLast(node);
            }
            return items;
        }
    }

    private void Journal(OutboxShownPayload payload)
    {
        var path = FileName(payload.EventId);
        if (File.Exists(path)) { Read(path); return; } // First display is immutable.
        var temporary = path + ".tmp";
        RejectLinks(temporary);
        var bytes = JsonSerializer.SerializeToUtf8Bytes(payload, EpharmJson.Options);
        using (var stream = new FileStream(temporary, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            stream.Write(bytes);
            stream.Flush(true);
        }
        File.Move(temporary, path);
    }

    private void EnsureDirectory()
    {
        RejectLinks(_directory);
        Directory.CreateDirectory(_directory);
    }

    private OutboxShownPayload Read(string path)
    {
        RejectLinks(path);
        if (new FileInfo(path).Length > 4096) throw new InvalidDataException("Shown journal is oversized.");
        var payload = JsonSerializer.Deserialize<OutboxShownPayload>(File.ReadAllText(path), EpharmJson.Options);
        if (payload == null || string.IsNullOrWhiteSpace(payload.EventId) || payload.EventId.Length > 256 ||
            payload.ShownAt == default || !string.Equals(Path.GetFullPath(path), FileName(payload.EventId), StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("Shown journal identity is invalid.");
        return payload;
    }

    private string FileName(string eventId) => Path.Combine(_directory,
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(eventId))) + ".json");

    private static void RejectLinks(string path)
    {
        for (var current = Path.GetFullPath(path); !string.IsNullOrEmpty(current); current = Path.GetDirectoryName(current))
            if ((File.Exists(current) || Directory.Exists(current)) && (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                throw new InvalidDataException("Linked journal path.");
    }

    /// <summary>For orderly application shutdown; no new records are accepted after this call.</summary>
    public Task DrainAsync()
    {
        lock (_gate)
        {
            if (_shutdown != null) return _shutdown;
            _closing = true;
            _timer?.Dispose();
            var existing = _writer;
            _shutdown = Task.Run(async () =>
            {
                await existing.ConfigureAwait(false); // One writer only; bounded caller wait below.
                try
                {
                    EnsureDirectory();
                    var clock = Stopwatch.StartNew();
                    foreach (var payload in Batch(Capacity))
                    {
                        if (clock.Elapsed >= _shutdownGrace) { _log?.Invoke("shown-ping shutdown journal deadline reached; pending memory may not be durable"); break; }
                        Journal(payload); // Do not wait for SQLite during shutdown.
                    }
                }
                catch (Exception error) { _log?.Invoke("shown-ping shutdown could not journal all records: " + error.GetType().Name); }
            });
            return _shutdown;
        }
    }

    public void Dispose()
    {
        try
        {
            if (!DrainAsync().Wait(_shutdownGrace))
                _log?.Invoke("shown-ping shutdown deadline reached; pending memory may not be durable");
        }
        catch (Exception error) { _log?.Invoke("shown-ping shutdown: " + error.GetType().Name); }
    }
}
