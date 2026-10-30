using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Microsoft.Data.Sqlite;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class ReceiptReplaySafetyTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "epharm-replay-" + Guid.NewGuid().ToString("N"));
    private string DbPath => Path.Combine(_root, "outbox.db");

    [Fact]
    public void ConfirmedPrintWithSameCanonicalIdIsNotLostBehindOldUnsafeQueueRow()
    {
        var queue = new OfflineOutbox(DbPath);
        const string id = "same-physical-document";
        queue.Enqueue(id, "sale", SaleJson(id, "standardn-document-switch"));
        queue.Reschedule(id, 0);
        var original = ReadQueueRow(id);

        queue.Enqueue(id, "sale", SaleJson(id, "standardn-print-log"));

        var ready = Assert.Single(new OfflineOutbox(DbPath).DequeueReady());
        Assert.Equal(id, ready.Id);
        Assert.Equal("standardn-print-log", JsonSerializer.Deserialize<SaleReport>(ready.Payload, EpharmJson.Options)!.CaptureSource);
        Assert.Equal(original, ReadQueueRow(id, archived: true));
        Assert.Equal(0, ready.Attempts);
        queue.Enqueue(id, "sale", SaleJson(id, "standardn-print-log"));
        Assert.Equal(original, ReadQueueRow(id, archived: true));
    }

    [Fact]
    public async Task ResumedPhysicalReceiptSurvivesCrashBeforeEnqueueAndDeliversCurrentPrintOnce()
    {
        var clock = DateTimeOffset.UtcNow;
        var receiptRoot = Path.Combine(_root, "receipts");
        ReceiptArtifactStore Store() => new(receiptRoot,
            new FiscalReceiptInboxSource(Path.Combine(_root, "fiscal-inbox"), ["ofd-api"]), utcNow: () => clock);
        const string id = "same-physical-crash";
        var held = JsonSerializer.Deserialize<SaleReport>(SaleJson(id, "standardn-firebird-close"), EpharmJson.Options)!;
        held.PrintedAt = clock;
        held.TotalAmount = 1500;
        Store().Complete(held);
        var originalFile = File.ReadAllBytes(Path.Combine(receiptRoot, "pending", id, "sale.json"));
        var queue = new OfflineOutbox(DbPath);
        queue.Enqueue(id, "sale", JsonSerializer.Serialize(held, EpharmJson.Options));
        queue.Reschedule(id, 0);
        var originalRow = ReadQueueRow(id);
        clock = clock.AddHours(1);
        var printed = JsonSerializer.Deserialize<SaleReport>(SaleJson(id, "standardn-print-log"), EpharmJson.Options)!;
        printed.PrintedAt = clock;
        printed.TotalAmount = 3000;
        Store().Complete(printed);
        // No Enqueue: emulate a process crash immediately after durable Complete.
        var restarted = new OfflineOutbox(DbPath);
        var recovered = Store();
        Assert.Equal(1, recovered.RecoverPending(restarted));
        using var server = new LoopbackEndpoint();
        using var api = new EpharmApiClient(new EpharmConfig { BackendBaseUrl = server.BaseUrl, DeviceKey = "synthetic-key" });
        using var flusher = new OutboxFlusher(restarted, api, periodSec: 3600,
            onDelivered: item => recovered.MarkSaleDelivered(item.Id));

        await flusher.FlushNowAsync();

        var request = Assert.Single(server.Requests);
        var sent = JsonSerializer.Deserialize<SaleReport>(request.Body, EpharmJson.Options)!;
        Assert.Equal(id, sent.SaleId);
        Assert.Equal(printed.PrintedAt, sent.PrintedAt);
        Assert.Equal(3000, sent.TotalAmount);
        Assert.Equal("standardn-print-log", sent.CaptureSource);
        Assert.Equal(originalFile, File.ReadAllBytes(Path.Combine(receiptRoot, "legacy-unconfirmed", id, "sale.json")));
        Assert.Equal(originalRow, ReadQueueRow(id, archived: true));
        Assert.Equal(0, Store().RecoverPending(new OfflineOutbox(DbPath)));
        await flusher.FlushNowAsync();
        Assert.Single(server.Requests);
    }

    [Theory]
    [InlineData("pharmacy")]
    [InlineData("document")]
    [InlineData("missing-document")]
    [InlineData("empty-pharmacy")]
    [InlineData("sale-id")]
    public void DifferentPhysicalIdentityCannotReplaceUnsafeCanonicalQueueRow(string mismatch)
    {
        var queue = new OfflineOutbox(DbPath);
        const string id = "preserved-physical-identity";
        queue.Enqueue(id, "sale", SaleJson(id, "standardn-document-switch"));
        var original = ReadQueueRow(id);
        var candidate = JsonSerializer.Deserialize<SaleReport>(SaleJson(id, "standardn-print-log"), EpharmJson.Options)!;
        switch (mismatch)
        {
            case "pharmacy": candidate.PharmacyId = "other-pharmacy"; break;
            case "document": candidate.SourceDocumentId = 54321; break;
            case "missing-document": candidate.SourceDocumentId = null; break;
            case "empty-pharmacy": candidate.PharmacyId = ""; break;
            case "sale-id": candidate.SaleId = "other-id"; break;
        }
        Assert.Throws<InvalidDataException>(() => queue.Enqueue(id, "sale", JsonSerializer.Serialize(candidate, EpharmJson.Options)));
        Assert.Equal(original, ReadQueueRow(id));
        Assert.Empty(ReadQueueRow(id, archived: true));
    }

    [Theory]
    [InlineData("{invalid-json")]
    [InlineData("null")]
    public void InvalidPreservedPayloadCannotBeSilentlyOverwritten(string payload)
    {
        const string id = "invalid-original";
        var queue = new OfflineOutbox(DbPath);
        queue.Enqueue(id, "sale", payload);
        var original = ReadQueueRow(id);
        queue.Enqueue(id, "sale", SaleJson(id, "standardn-print-log"));
        Assert.Equal(original, ReadQueueRow(id));
        Assert.Empty(ReadQueueRow(id, archived: true));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("unknown-older-capture")]
    [InlineData("standardn-print-log")]
    public void OtherHistoricalDuplicatesRetainInsertIgnoreSemantics(string? source)
    {
        const string id = "unchanged-historical-duplicate";
        var queue = new OfflineOutbox(DbPath);
        var originalSale = JsonSerializer.Deserialize<SaleReport>(SaleJson(id, "standardn-print-log"), EpharmJson.Options)!;
        originalSale.CaptureSource = source;
        queue.Enqueue(id, "sale", JsonSerializer.Serialize(originalSale, EpharmJson.Options));
        var original = ReadQueueRow(id);
        queue.Enqueue(id, "sale", SaleJson(id, "standardn-print-log"));
        Assert.Equal(original, ReadQueueRow(id));
        Assert.Empty(ReadQueueRow(id, archived: true));
    }

    [Fact]
    public void ConflictingArchivedQueueRowRollsBackPromotionAndPreservesBothRows()
    {
        const string id = "conflicting-archive";
        var queue = new OfflineOutbox(DbPath);
        queue.Enqueue(id, "sale", SaleJson(id, "standardn-document-switch"));
        var original = ReadQueueRow(id);
        using (var connection = new SqliteConnection("Data Source=" + DbPath))
        {
            connection.Open();
            using var insert = connection.CreateCommand();
            insert.CommandText = @"INSERT INTO outbox_legacy_sale_evidence
                SELECT id,kind,'conflicting-original-evidence',created_at,attempts,next_retry_at,'synthetic' FROM outbox;";
            insert.ExecuteNonQuery();
        }
        var archive = ReadQueueRow(id, archived: true);

        Assert.Throws<SqliteException>(() => queue.Enqueue(id, "sale", SaleJson(id, "standardn-print-log")));

        Assert.Equal(original, ReadQueueRow(id));
        Assert.Equal(archive, ReadQueueRow(id, archived: true));
    }

    [Fact]
    public async Task UnsafeBacklogLargerThanBatchCannotReachHttpOrStarveGoodEventsAfterRestartAndReconnect()
    {
        var queue = new OfflineOutbox(DbPath);
        for (var i = 0; i < 45; i++)
        {
            var source = i % 2 == 0 ? "standardn-document-switch" : "standardn-firebird-close";
            queue.Enqueue("held-" + i, "sale", SaleJson("held-" + i, source));
            if (i % 3 == 0) queue.Reschedule("held-" + i, 0);
        }
        var heldBefore = SnapshotHeld();
        using var server = new LoopbackEndpoint();
        using var api = new EpharmApiClient(new EpharmConfig { BackendBaseUrl = server.BaseUrl, DeviceKey = "synthetic-key" });
        var delivered = new List<string>();

        // Simulated process restart: both queue and real HTTP flusher reopen persisted data.
        var restarted = new OfflineOutbox(DbPath);
        using var flusher = new OutboxFlusher(restarted, api, periodSec: 3600, onDelivered: item => delivered.Add(item.Id));
        await flusher.FlushNowAsync();
        Assert.Empty(server.Requests);
        Assert.Empty(delivered);
        AssertHeldUnchanged(heldBefore);

        restarted.Enqueue("genuine-print", "sale", SaleJson("genuine-print", "standardn-print-log"));
        restarted.Enqueue("older-format", "sale", SaleJson("older-format", "unknown-legacy-format"));
        restarted.Enqueue("fiscal-proof", "fiscal-sale", SaleJson("fiscal-proof", "standardn-document-switch"));
        restarted.Enqueue("outcome", "outcome", JsonSerializer.Serialize(new OutboxOutcomePayload
        { EventId = "event-one", Outcome = "accepted", FinalSku = "synthetic-sku" }, EpharmJson.Options));
        restarted.Enqueue("shown", "shown", JsonSerializer.Serialize(new OutboxShownPayload
        { EventId = "event-one", ShownAt = DateTimeOffset.UtcNow }, EpharmJson.Options));

        // A failed network cycle retains legitimate events but never tries held entries.
        server.Available = false;
        await flusher.FlushNowAsync();
        Assert.Equal(5, server.Requests.Count);
        Assert.Empty(delivered);
        Assert.All(restarted.DequeueReady(100), item => Assert.DoesNotContain("held-", item.Id));
        AssertHeldUnchanged(heldBefore);

        server.Available = true;
        await flusher.FlushNowAsync();
        Assert.Equal(10, server.Requests.Count);
        Assert.Equal(new[] { "fiscal-proof", "genuine-print", "older-format", "outcome", "shown" }, delivered.Order());
        Assert.Empty(new OfflineOutbox(DbPath).DequeueReady());
        AssertHeldUnchanged(heldBefore);
        Assert.All(server.Requests, request => Assert.DoesNotContain("held-", request.Body));
        Assert.Contains(server.Requests, request => request.Path == "/api/posm/recommendations/event-one/outcome");
        Assert.Contains(server.Requests, request => request.Path == "/api/posm/recommendations/event-one/shown");
        await flusher.FlushNowAsync();
        Assert.Equal(10, server.Requests.Count);
    }

    [Fact]
    public void JsonSelectionHandlesInvalidPayloadsAndLegacyPropertyCasingWithoutChangingStoredRows()
    {
        var queue = new OfflineOutbox(DbPath);
        queue.Enqueue("pascal", "sale", "{\"CaptureSource\":\"standardn-firebird-close\"}");
        queue.Enqueue("mixed", "sale", "{\"cApTuReSoUrCe\":\"standardn-document-switch\"}");
        queue.Enqueue("duplicated", "sale", "{\"captureSource\":\"standardn-document-switch\",\"CaptureSource\":\"standardn-print-log\"}");
        queue.Enqueue("invalid", "sale", "{invalid JSON");
        queue.Enqueue("scalar", "sale", "42");
        queue.Enqueue("uppercase-source", "sale", "{\"captureSource\":\"STANDARDN-FIREBIRD-CLOSE\"}");
        queue.Enqueue("lookalike", "sale", "{\"captureSource\":\"standardn-firebird-close-legacy\"}");
        queue.Enqueue("missing-source", "sale", "{}");
        queue.Enqueue("non-sale", "shown", "{\"captureSource\":\"standardn-document-switch\"}");

        Assert.Equal(new[] { "invalid", "lookalike", "missing-source", "non-sale", "scalar", "uppercase-source" },
            new OfflineOutbox(DbPath).DequeueReady(20).Select(item => item.Id).Order());
        using var connection = new SqliteConnection("Data Source=" + DbPath);
        connection.Open();
        using var count = connection.CreateCommand();
        count.CommandText = "SELECT COUNT(*) FROM outbox";
        Assert.Equal(9L, count.ExecuteScalar());
    }

    private Dictionary<string, (string Payload, long Attempts)> SnapshotHeld()
    {
        using var connection = new SqliteConnection("Data Source=" + DbPath);
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT id,payload,attempts FROM outbox WHERE id LIKE 'held-%'";
        using var reader = command.ExecuteReader();
        var result = new Dictionary<string, (string, long)>();
        while (reader.Read()) result.Add(reader.GetString(0), (reader.GetString(1), reader.GetInt64(2)));
        return result;
    }

    private string[] ReadQueueRow(string id, bool archived = false)
    {
        using var connection = new SqliteConnection("Data Source=" + DbPath);
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT id,kind,payload,created_at,attempts,next_retry_at FROM " +
                              (archived ? "outbox_legacy_sale_evidence" : "outbox") + " WHERE id=$id";
        command.Parameters.AddWithValue("$id", id);
        using var reader = command.ExecuteReader();
        if (!reader.Read()) return [];
        return Enumerable.Range(0, 6).Select(index => reader.GetValue(index).ToString()!).ToArray();
    }

    private void AssertHeldUnchanged(Dictionary<string, (string Payload, long Attempts)> expected)
    {
        var actual = SnapshotHeld();
        Assert.Equal(45, actual.Count);
        foreach (var row in expected) Assert.Equal(row.Value, actual[row.Key]);
    }

    private static string SaleJson(string id, string source) => JsonSerializer.Serialize(new SaleReport
    { SaleId = id, PharmacyId = "synthetic-pharmacy", PharmacistId = "synthetic-person", SourceDocumentId = 12345, CaptureSource = source }, EpharmJson.Options);

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
    }

    private sealed class LoopbackEndpoint : IDisposable
    {
        private readonly HttpListener _listener = new();
        private readonly Task _worker;
        private int _available = 1;
        public string BaseUrl { get; }
        public bool Available { set => Volatile.Write(ref _available, value ? 1 : 0); }
        public ConcurrentQueue<(string Path, string Body)> Requests { get; } = new();

        public LoopbackEndpoint()
        {
            var socket = new TcpListener(IPAddress.Loopback, 0);
            socket.Start();
            var port = ((IPEndPoint)socket.LocalEndpoint).Port;
            socket.Stop();
            BaseUrl = $"http://127.0.0.1:{port}/";
            _listener.Prefixes.Add(BaseUrl);
            _listener.Start();
            _worker = RunAsync();
        }

        private async Task RunAsync()
        {
            try
            {
                while (_listener.IsListening)
                {
                    var context = await _listener.GetContextAsync();
                    using var reader = new StreamReader(context.Request.InputStream);
                    Requests.Enqueue((context.Request.Url!.AbsolutePath, await reader.ReadToEndAsync()));
                    context.Response.StatusCode = Volatile.Read(ref _available) == 1 ? 204 : 503;
                    context.Response.Close();
                }
            }
            catch (Exception error) when (error is HttpListenerException or ObjectDisposedException) { }
        }

        public void Dispose()
        {
            _listener.Close();
            _worker.GetAwaiter().GetResult();
        }
    }
}
