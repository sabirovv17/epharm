using System.Net;
using System.Text.Json;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class StockSnapshotUploadClientTests
{
    private const string CollectorToken = "0123456789abcdef0123456789abcdef";

    [Fact]
    public async Task CompleteSnapshotUsesCollectorContractAndOnePost()
    {
        var handler = new RecordingHandler(HttpStatusCode.OK);
        using var http = new HttpClient(handler);
        var client = CreateClient(http);
        var snapshot = new CompleteStockSnapshot(
            "sloc_pilot",
            DateTimeOffset.Parse("2026-10-10T10:00:00+05:00"),
            new[]
            {
                new StockSnapshotRow(
                    "7e5b24a5-2f5f-42cc-93af-6f76f2ec53a8",
                    318,
                    "Препарат",
                    "4601234567890",
                    "4601234567890",
                    1.25m,
                    1299.50m,
                    new DateOnly(2027, 5, 1),
                    "A-12",
                    "уп"),
            });

        var result = await client.UploadAsync(snapshot, TestContext.Current.CancellationToken);

        Assert.True(result.Accepted);
        Assert.Equal(1, handler.RequestCount);
        Assert.Equal(HttpMethod.Post, handler.Method);
        Assert.Equal(
            "https://stock.example/internal/v1/pharmacies/432/snapshot",
            handler.RequestUri?.AbsoluteUri);
        Assert.Equal("application/json", handler.ContentType);
        Assert.Equal("Bearer", handler.AuthorizationScheme);
        Assert.Equal(CollectorToken, handler.AuthorizationParameter);
        using var json = JsonDocument.Parse(handler.Body!);
        var root = json.RootElement;
        Assert.Equal("sloc_pilot", root.GetProperty("hqPharmacyId").GetString());
        Assert.Equal(1, root.GetProperty("rowCount").GetInt32());
        Assert.Equal(DateTimeOffset.Parse("2026-10-10T05:00:00Z"),
            root.GetProperty("sourceObservedAt").GetDateTimeOffset());
        var row = Assert.Single(root.GetProperty("items").EnumerateArray());
        Assert.Equal("7e5b24a5-2f5f-42cc-93af-6f76f2ec53a8",
            row.GetProperty("sourceId").GetString());
        Assert.Equal(318, row.GetProperty("partId").GetInt64());
        Assert.Equal("2027-05-01", row.GetProperty("expiryDate").GetString());
        Assert.Equal(1.25m, row.GetProperty("quantity").GetDecimal());
        Assert.DoesNotContain(CollectorToken, handler.Body!);
    }

    [Theory]
    [InlineData(HttpStatusCode.BadRequest, StockSnapshotUploadStatus.InvalidSnapshot)]
    [InlineData(HttpStatusCode.RequestEntityTooLarge, StockSnapshotUploadStatus.InvalidSnapshot)]
    [InlineData(HttpStatusCode.Unauthorized, StockSnapshotUploadStatus.Unauthorized)]
    [InlineData(HttpStatusCode.Conflict, StockSnapshotUploadStatus.OlderSnapshot)]
    [InlineData(HttpStatusCode.TooManyRequests, StockSnapshotUploadStatus.Busy)]
    [InlineData(HttpStatusCode.ServiceUnavailable, StockSnapshotUploadStatus.Unavailable)]
    [InlineData(HttpStatusCode.BadGateway, StockSnapshotUploadStatus.Unavailable)]
    public async Task FailureResponseIsReportedWithoutAnAutomaticRetry(
        HttpStatusCode responseCode, StockSnapshotUploadStatus expected)
    {
        var handler = new RecordingHandler(responseCode);
        using var http = new HttpClient(handler);

        var result = await CreateClient(http).UploadAsync(
            EmptyConfirmedSnapshot(), TestContext.Current.CancellationToken);

        Assert.Equal(expected, result.Status);
        Assert.False(result.Accepted);
        Assert.Equal(1, handler.RequestCount);
    }

    [Fact]
    public async Task TimeoutReturnsTimedOutAndCallerCancellationPropagates()
    {
        var handler = new RecordingHandler(HttpStatusCode.OK)
        {
            DelayUntilCancelled = true,
        };
        using var http = new HttpClient(handler);
        var client = CreateClient(http, TimeSpan.FromMilliseconds(40));

        Assert.Equal(
            StockSnapshotUploadStatus.TimedOut,
            (await client.UploadAsync(
                EmptyConfirmedSnapshot(), TestContext.Current.CancellationToken)).Status);
        Assert.Equal(1, handler.RequestCount);

        using var cancelled = new CancellationTokenSource();
        cancelled.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => client.UploadAsync(EmptyConfirmedSnapshot(), cancelled.Token));
        Assert.Equal(1, handler.RequestCount);
    }

    [Fact]
    public async Task OversizedUtf8BodyIsRejectedBeforeNetworkUse()
    {
        var handler = new RecordingHandler(HttpStatusCode.OK);
        using var http = new HttpClient(handler);
        var name = new string('Ж', 1_000);
        var rows = Enumerable.Range(1, 10_000)
            .Select(id => new StockSnapshotRow(
                id.ToString(), id, name, null, null, 1m, null, null, null, null))
            .ToArray();
        var snapshot = new CompleteStockSnapshot(
            "sloc_pilot", DateTimeOffset.UtcNow, rows);

        await Assert.ThrowsAsync<InvalidDataException>(
            () => CreateClient(http).UploadAsync(snapshot, TestContext.Current.CancellationToken));
        Assert.Equal(0, handler.RequestCount);
    }

    [Fact]
    public void InvalidRowsAndInsecureDestinationFailBeforeTransport()
    {
        var duplicate = new[]
        {
            new StockSnapshotRow("batch", 1, "A", null, null, 1m, null, null, null, null),
            new StockSnapshotRow("batch", 1, "A", null, null, 1m, null, null, null, null),
        };
        Assert.Throws<InvalidDataException>(
            () => new CompleteStockSnapshot("sloc_pilot", DateTimeOffset.UtcNow, duplicate));
        var tooMany = Enumerable.Range(1, StockSnapshotUploadClient.MaxRows + 1)
            .Select(id => new StockSnapshotRow(
                id.ToString(), id, "A", null, null, 1m, null, null, null, null))
            .ToArray();
        Assert.Throws<InvalidDataException>(
            () => new CompleteStockSnapshot("sloc_pilot", DateTimeOffset.UtcNow, tooMany));

        using var http = new HttpClient(new RecordingHandler(HttpStatusCode.OK));
        Assert.Throws<ArgumentException>(
            () => new StockSnapshotUploadClient(
                http, new Uri("http://stock.example/"), 432, CollectorToken,
                TimeSpan.FromSeconds(30)));
    }

    private static StockSnapshotUploadClient CreateClient(
        HttpClient http, TimeSpan? timeout = null) =>
        new(http, new Uri("https://stock.example/"), 432, CollectorToken,
            timeout ?? TimeSpan.FromSeconds(30));

    private static CompleteStockSnapshot EmptyConfirmedSnapshot() =>
        new("sloc_pilot", DateTimeOffset.UtcNow, Array.Empty<StockSnapshotRow>());

    private sealed class RecordingHandler(HttpStatusCode status) : HttpMessageHandler
    {
        public int RequestCount { get; private set; }
        public HttpMethod? Method { get; private set; }
        public Uri? RequestUri { get; private set; }
        public string? ContentType { get; private set; }
        public string? AuthorizationScheme { get; private set; }
        public string? AuthorizationParameter { get; private set; }
        public string? Body { get; private set; }
        public bool DelayUntilCancelled { get; init; }

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            RequestCount++;
            if (DelayUntilCancelled)
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            Method = request.Method;
            RequestUri = request.RequestUri;
            ContentType = request.Content?.Headers.ContentType?.MediaType;
            AuthorizationScheme = request.Headers.Authorization?.Scheme;
            AuthorizationParameter = request.Headers.Authorization?.Parameter;
            Body = request.Content is null
                ? null
                : await request.Content.ReadAsStringAsync(cancellationToken);
            return new HttpResponseMessage(status);
        }
    }
}
