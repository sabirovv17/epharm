using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// One confirmed positive warehouse row. SourceId must be the stable local
    /// row identity, not PART_ID: a product can have several batches.
    /// </summary>
    public sealed record StockSnapshotRow(
        string SourceId,
        long PartId,
        string Name,
        string? ManufacturerBarcode,
        string? Barcode,
        decimal Quantity,
        decimal? Price,
        DateOnly? ExpiryDate,
        string? Series,
        string? Unit);

    /// <summary>
    /// Create only after a local read reached EOF successfully. In particular,
    /// an empty snapshot asserts that the pharmacy has no positive stock rows.
    /// </summary>
    public sealed class CompleteStockSnapshot
    {
        public string HqPharmacyId { get; }
        public DateTimeOffset SourceObservedAt { get; }
        public int RowCount => Items.Count;
        public IReadOnlyList<StockSnapshotRow> Items { get; }

        public CompleteStockSnapshot(
            string hqPharmacyId,
            DateTimeOffset sourceObservedAt,
            IReadOnlyList<StockSnapshotRow> items)
        {
            if (string.IsNullOrWhiteSpace(hqPharmacyId))
                throw new ArgumentException("HQ pharmacy ID is required.", nameof(hqPharmacyId));
            if (sourceObservedAt == default)
                throw new ArgumentException("Observation time is required.", nameof(sourceObservedAt));
            if (items is null) throw new ArgumentNullException(nameof(items));
            if (items.Count > StockSnapshotUploadClient.MaxRows)
                throw new InvalidDataException("Snapshot exceeds the row limit.");

            var sourceIds = new HashSet<string>(StringComparer.Ordinal);
            foreach (var row in items)
            {
                if (row is null ||
                    string.IsNullOrWhiteSpace(row.SourceId) || row.SourceId.Length > 256 ||
                    !sourceIds.Add(row.SourceId) ||
                    row.PartId <= 0 ||
                    string.IsNullOrWhiteSpace(row.Name) || row.Name.Length > 1000 ||
                    row.Quantity <= 0 || row.Price < 0 ||
                    row.ManufacturerBarcode?.Length > 128 ||
                    row.Barcode?.Length > 128 ||
                    row.Series?.Length > 256 ||
                    row.Unit?.Length > 64)
                    throw new InvalidDataException("Snapshot contains an invalid or duplicate stock row.");
            }

            HqPharmacyId = hqPharmacyId.Trim();
            SourceObservedAt = sourceObservedAt.ToUniversalTime();
            Items = Array.AsReadOnly(items.ToArray());
        }
    }

    public enum StockSnapshotUploadStatus
    {
        Accepted,
        InvalidSnapshot,
        Unauthorized,
        OlderSnapshot,
        Busy,
        Unavailable,
        TimedOut,
        NetworkError,
        UnexpectedResponse,
    }

    public sealed record StockSnapshotUploadResult(
        StockSnapshotUploadStatus Status,
        HttpStatusCode? HttpStatusCode = null)
    {
        public bool Accepted => Status == StockSnapshotUploadStatus.Accepted;
    }

    /// <summary>
    /// Isolated transport for the stock collector contract. It never reads the
    /// cash database, schedules a run, retries a request, or logs token/body data.
    /// </summary>
    public sealed class StockSnapshotUploadClient
    {
        public const int MaxRows = 20_000;
        public const int MaxRequestBytes = 16 * 1024 * 1024;

        private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

        private readonly HttpClient _http;
        private readonly Uri _endpoint;
        private readonly string _bearerToken;
        private readonly TimeSpan _timeout;

        public StockSnapshotUploadClient(
            HttpClient http,
            Uri serviceOrigin,
            long profileId,
            string bearerToken,
            TimeSpan timeout)
        {
            _http = http ?? throw new ArgumentNullException(nameof(http));
            if (serviceOrigin is null || !serviceOrigin.IsAbsoluteUri ||
                serviceOrigin.Scheme != Uri.UriSchemeHttps ||
                serviceOrigin.AbsolutePath != "/" ||
                serviceOrigin.Query.Length != 0 || serviceOrigin.Fragment.Length != 0 ||
                serviceOrigin.UserInfo.Length != 0)
                throw new ArgumentException("A root HTTPS service origin is required.", nameof(serviceOrigin));
            if (profileId <= 0) throw new ArgumentOutOfRangeException(nameof(profileId));
            if (string.IsNullOrWhiteSpace(bearerToken) ||
                bearerToken.Length is < 32 or > 128 ||
                bearerToken.Any(char.IsWhiteSpace))
                throw new ArgumentException("A scoped collector token is required.", nameof(bearerToken));
            if (timeout <= TimeSpan.Zero || timeout > TimeSpan.FromMinutes(2))
                throw new ArgumentOutOfRangeException(nameof(timeout));

            _endpoint = new Uri(serviceOrigin, $"/internal/v1/pharmacies/{profileId}/snapshot");
            _bearerToken = bearerToken;
            _timeout = timeout;
        }

        public async Task<StockSnapshotUploadResult> UploadAsync(
            CompleteStockSnapshot snapshot,
            CancellationToken cancellationToken = default)
        {
            if (snapshot is null) throw new ArgumentNullException(nameof(snapshot));
            cancellationToken.ThrowIfCancellationRequested();

            var body = JsonSerializer.SerializeToUtf8Bytes(snapshot, JsonOptions);
            if (body.Length > MaxRequestBytes)
                throw new InvalidDataException("Serialized snapshot exceeds the request size limit.");

            using var request = new HttpRequestMessage(HttpMethod.Post, _endpoint)
            {
                Content = new ByteArrayContent(body),
            };
            request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _bearerToken);
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(_timeout);

            try
            {
                using var response = await _http.SendAsync(
                    request, HttpCompletionOption.ResponseHeadersRead, timeout.Token).ConfigureAwait(false);
                return new StockSnapshotUploadResult(Map(response.StatusCode), response.StatusCode);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                return new StockSnapshotUploadResult(StockSnapshotUploadStatus.TimedOut);
            }
            catch (HttpRequestException)
            {
                return new StockSnapshotUploadResult(StockSnapshotUploadStatus.NetworkError);
            }
        }

        private static StockSnapshotUploadStatus Map(HttpStatusCode status)
        {
            if ((int)status is >= 500 and <= 599)
                return StockSnapshotUploadStatus.Unavailable;
            return status switch
            {
                HttpStatusCode.OK => StockSnapshotUploadStatus.Accepted,
                HttpStatusCode.BadRequest or HttpStatusCode.RequestEntityTooLarge =>
                    StockSnapshotUploadStatus.InvalidSnapshot,
                HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden =>
                    StockSnapshotUploadStatus.Unauthorized,
                HttpStatusCode.Conflict => StockSnapshotUploadStatus.OlderSnapshot,
                HttpStatusCode.TooManyRequests => StockSnapshotUploadStatus.Busy,
                HttpStatusCode.RequestTimeout => StockSnapshotUploadStatus.TimedOut,
                _ => StockSnapshotUploadStatus.UnexpectedResponse,
            };
        }
    }
}
