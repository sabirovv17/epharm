using System.Text;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class MerchShownOutboxTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "merch-shown-" + Guid.NewGuid());
    private DateTimeOffset _now = DateTimeOffset.Parse("2026-10-14T10:00:00Z");
    private string PathName => Path.Combine(_directory, "queue.dpapi");
    private static byte[] Transform(byte[] bytes) => bytes.Select(b => (byte)(b ^ 0xa5)).ToArray();
    private MerchShownOutbox Queue(string pharmacy = "p1", string device = "d1") =>
        new(PathName, pharmacy, device, Transform, Transform, () => _now);
    private TaskKioskItem Task(string token = "secret-delivery-token", int expiresMinutes = 60) =>
        new("dispatch-1", "Task", "Pharmacy", "normal", null, token,
            "https://crm.inkar.kz/staff?task=1#task=public-bearer", _now.AddMinutes(expiresMinutes));

    [Fact]
    public async Task FailedAckSurvivesRestartAndRetriesWhileWindowIsClosed()
    {
        var queue = Queue();
        queue.Enqueue(Task());
        var api = new Api { Throws = true };
        await queue.FlushAsync(api, cancellation: TestContext.Current.CancellationToken);
        Assert.Single(api.Calls);
        Assert.DoesNotContain("secret-delivery-token", Encoding.UTF8.GetString(File.ReadAllBytes(PathName)));
        Assert.DoesNotContain("public-bearer", Encoding.UTF8.GetString(Transform(File.ReadAllBytes(PathName))));

        var restarted = Queue();
        await restarted.FlushAsync(api, cancellation: TestContext.Current.CancellationToken);
        Assert.Single(api.Calls); // persisted retry schedule
        _now = _now.AddSeconds(31);
        api.Throws = false;
        await restarted.FlushAsync(api, cancellation: TestContext.Current.CancellationToken);
        Assert.Equal(2, api.Calls.Count);
        Assert.Equal(("dispatch-1", "p1", "d1", "secret-delivery-token"), api.Calls[1]);
        await Queue().FlushAsync(api, cancellation: TestContext.Current.CancellationToken);
        Assert.Equal(2, api.Calls.Count); // successful delivery removed atomically
    }

    [Fact]
    public async Task PermanentNegativeReplyRemovesRevokedReceiptWithoutReportingAcknowledged()
    {
        var log = new List<string>();
        var queue = new MerchShownOutbox(PathName, "p1", "d1", Transform, Transform, () => _now, log.Add);
        queue.Enqueue(Task());
        var api = new Api { Accepted = false };
        var delivered = new List<string>();
        await queue.FlushAsync(api, delivered.Add, TestContext.Current.CancellationToken);
        Assert.Single(api.Calls);
        Assert.Empty(delivered);
        Assert.Single(log);
        Assert.DoesNotContain("secret-delivery-token", log[0]);
        _now = _now.AddMinutes(6);
        await Queue().FlushAsync(api, cancellation: TestContext.Current.CancellationToken);
        Assert.Single(api.Calls);
    }

    [Fact]
    public async Task DifferentPharmacyOrDeviceCannotReplayReceipts()
    {
        Queue().Enqueue(Task());
        var api = new Api();
        await Queue("p2").FlushAsync(api, cancellation: TestContext.Current.CancellationToken);
        Assert.Empty(api.Calls);
        Queue().Enqueue(Task());
        await Queue(device: "d2").FlushAsync(api, cancellation: TestContext.Current.CancellationToken);
        Assert.Empty(api.Calls);
    }

    [Fact]
    public async Task ExpiredDeliveryIsDroppedRatherThanRetried()
    {
        Queue().Enqueue(Task(expiresMinutes: 1));
        _now = _now.AddMinutes(2);
        var api = new Api();
        await Queue().FlushAsync(api, cancellation: TestContext.Current.CancellationToken);
        Assert.Empty(api.Calls);
    }

    [Fact]
    public async Task DuplicateVisibleEventsQueueOnlyOneReceipt()
    {
        var queue = Queue();
        queue.Enqueue(Task());
        queue.Enqueue(Task());
        var api = new Api();
        await queue.FlushAsync(api, cancellation: TestContext.Current.CancellationToken);
        Assert.Single(api.Calls);
    }

    [Fact]
    public async Task NetworkExceptionDoesNotLoseSavedEvidence()
    {
        Queue().Enqueue(Task());
        var api = new Api { Throws = true };
        await Queue().FlushAsync(api, cancellation: TestContext.Current.CancellationToken);
        _now = _now.AddMinutes(6);
        api.Throws = false;
        await Queue().FlushAsync(api, cancellation: TestContext.Current.CancellationToken);
        Assert.Equal(2, api.Calls.Count);
    }

    [Fact]
    public async Task QueueIsBoundedAndRetainsNewestReceipts()
    {
        var queue = Queue();
        for (var i = 0; i < 201; i++) queue.Enqueue(Task("delivery-" + i));
        var api = new Api();
        for (var i = 0; i < 21; i++) await queue.FlushAsync(api, cancellation: TestContext.Current.CancellationToken);
        Assert.Equal(200, api.Calls.Count);
        Assert.DoesNotContain(api.Calls, call => call.Token == "delivery-0");
    }

    [Fact]
    public async Task ReceiptRetentionIsCappedAtSevenDays()
    {
        Queue().Enqueue(Task(expiresMinutes: 60 * 24 * 30));
        _now = _now.AddDays(8);
        var api = new Api();
        await Queue().FlushAsync(api, cancellation: TestContext.Current.CancellationToken);
        Assert.Empty(api.Calls);
    }

    [Fact]
    public async Task CancelledFlushKeepsPendingEvidenceForRestart()
    {
        Queue().Enqueue(Task());
        using var cancelled = new CancellationTokenSource();
        cancelled.Cancel();
        var api = new Api();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => Queue().FlushAsync(api, cancellation: cancelled.Token));
        await Queue().FlushAsync(api, cancellation: TestContext.Current.CancellationToken);
        Assert.Single(api.Calls);
    }

    [Fact]
    public async Task CorruptProtectedFileIsQuarantinedAndNewDisplaysContinueDelivering()
    {
        Directory.CreateDirectory(_directory);
        File.WriteAllBytes(PathName, [0, 1, 2]);
        var log = new List<string>();
        var queue = new MerchShownOutbox(PathName, "p1", "d1", Transform, Transform, () => _now, log.Add);
        queue.Enqueue(Task());
        Assert.Equal(new byte[] { 0, 1, 2 }, File.ReadAllBytes(PathName + ".corrupt"));
        Assert.Single(log);
        Assert.Contains("JsonException", log[0]);
        Assert.DoesNotContain("secret-delivery-token", log[0]);
        var api = new Api();
        await Queue().FlushAsync(api, cancellation: TestContext.Current.CancellationToken);
        Assert.Single(api.Calls);
    }

    [Fact]
    public void FailedQuarantinePreservesOriginalQueueAndDoesNotReplaceIt()
    {
        Directory.CreateDirectory(_directory);
        File.WriteAllBytes(PathName, [0, 1, 2]);
        Directory.CreateDirectory(PathName + ".corrupt"); // rename cannot overwrite a directory
        Assert.ThrowsAny<IOException>(() => Queue().Enqueue(Task()));
        Assert.Equal(new byte[] { 0, 1, 2 }, File.ReadAllBytes(PathName));
    }

    [Fact]
    public void RepeatedCorruptionKeepsOnlyLatestEncryptedDiagnosticSnapshot()
    {
        Directory.CreateDirectory(_directory);
        File.WriteAllBytes(PathName, [0, 1, 2]);
        Queue().Enqueue(Task());
        File.WriteAllBytes(PathName, [3, 4, 5]);
        Queue().Enqueue(Task("new-token"));
        Assert.Equal(new byte[] { 3, 4, 5 }, File.ReadAllBytes(PathName + ".corrupt"));
        Assert.Single(Directory.GetFiles(_directory, "*.corrupt"));
    }

    public void Dispose() { if (Directory.Exists(_directory)) Directory.Delete(_directory, recursive: true); }

    private sealed class Api : IMerchTaskApi
    {
        public bool Accepted { get; set; } = true;
        public bool Throws { get; set; }
        public List<(string Dispatch, string Pharmacy, string Device, string Token)> Calls { get; } = [];
        public Task<MerchTaskDto?> GetActiveMerchTaskAsync(string pharmacyId, string deviceId, CancellationToken ct = default) =>
            System.Threading.Tasks.Task.FromResult<MerchTaskDto?>(null);
        public Task<bool> AcknowledgeMerchTaskAsync(string dispatchId, string pharmacyId, string deviceId, string deliveryToken, CancellationToken ct = default)
        {
            Calls.Add((dispatchId, pharmacyId, deviceId, deliveryToken));
            if (Throws) throw new IOException("offline");
            return System.Threading.Tasks.Task.FromResult(Accepted);
        }
    }
}
