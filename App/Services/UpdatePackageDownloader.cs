using System;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading;
using System.Threading.Tasks;

namespace CustomerDisplay.Services;

/// <summary>Resumable public-artifact download with a deadline that includes the response body.</summary>
public sealed class UpdatePackageDownloader
{
    private readonly HttpClient _http;
    private readonly Action<string> _log;
    private readonly TimeSpan _budget;
    private readonly TimeSpan _idle;
    private readonly TimeSpan _retry;
    private readonly int _attempts;
    private const long MaxPackageBytes = 2L * 1024 * 1024 * 1024;

    public UpdatePackageDownloader(HttpClient http, Action<string> log,
        TimeSpan? budget = null, TimeSpan? idle = null, TimeSpan? retry = null, int attempts = 4)
    {
        _http = http;
        _log = log;
        _budget = budget ?? TimeSpan.FromMinutes(30);
        _idle = idle ?? TimeSpan.FromSeconds(60);
        _retry = retry ?? TimeSpan.FromSeconds(3);
        _attempts = attempts;
    }

    public async Task<bool> DownloadAsync(string url, string destination, CancellationToken cancellation = default)
    {
        using var budget = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        budget.CancelAfter(_budget);
        var part = destination + ".part";
        for (var attempt = 1; attempt <= _attempts; attempt++)
        {
            try
            {
                EnsureDownloadPaths(destination, part);
                var length = File.Exists(part) ? new FileInfo(part).Length : 0;
                if (length > MaxPackageBytes) throw new InvalidDataException("Update exceeds size limit.");
                using var request = new HttpRequestMessage(HttpMethod.Get, url);
                // Never send a pharmacy/device credential to the artifact origin.
                if (length > 0) request.Headers.Range = new RangeHeaderValue(length, null);
                using var response = await _http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, budget.Token)
                    .ConfigureAwait(false);
                EnsureDownloadPaths(destination, part);
                if (response.StatusCode == HttpStatusCode.RequestedRangeNotSatisfiable && length > 0 &&
                    response.Content.Headers.ContentRange?.Length == length)
                {
                    File.Move(part, destination, true);
                    return true; // Caller still MUST verify the signed SHA-256.
                }
                response.EnsureSuccessStatusCode();
                var append = length > 0 && response.StatusCode == HttpStatusCode.PartialContent;
                var range = response.Content.Headers.ContentRange;
                if (response.StatusCode == HttpStatusCode.PartialContent &&
                    (range?.From != length || range.To < range.From || !range.HasLength))
                    throw new InvalidDataException("Invalid update Content-Range.");
                if (!append) length = 0;
                var expected = range?.Length ?? (response.Content.Headers.ContentLength is long count ? length + count : (long?)null);
                if (expected > MaxPackageBytes) throw new InvalidDataException("Update exceeds size limit.");
                await using var source = await response.Content.ReadAsStreamAsync(budget.Token).ConfigureAwait(false);
                EnsureDownloadPaths(destination, part);
                await using (var target = new FileStream(part, append ? FileMode.Append : FileMode.Create,
                    FileAccess.Write, FileShare.None, 128 * 1024, true))
                {
                    var buffer = new byte[128 * 1024];
                    while (true)
                    {
                        using var idle = CancellationTokenSource.CreateLinkedTokenSource(budget.Token);
                        idle.CancelAfter(_idle);
                        // WaitAsync also bounds a misbehaving stream which ignores cancellation.
                        var read = await source.ReadAsync(buffer.AsMemory(), idle.Token).AsTask().WaitAsync(idle.Token)
                            .ConfigureAwait(false);
                        if (read == 0) break;
                        length += read;
                        if (length > MaxPackageBytes) throw new InvalidDataException("Update exceeds size limit.");
                        await target.WriteAsync(buffer.AsMemory(0, read), budget.Token).ConfigureAwait(false);
                    }
                    await target.FlushAsync(budget.Token).ConfigureAwait(false);
                }
                if (expected.HasValue && length != expected.Value)
                    throw new IOException("Update body was truncated.");
                EnsureDownloadPaths(destination, part);
                File.Move(part, destination, true);
                return true;
            }
            catch (OperationCanceledException) when (cancellation.IsCancellationRequested) { throw; }
            catch (Exception ex)
            {
                _log($"update: скачивание {attempt}/{_attempts}: {ex.GetType().Name}");
                if (budget.IsCancellationRequested) break;
                if (attempt < _attempts)
                {
                    try { await Task.Delay(_retry, budget.Token).ConfigureAwait(false); }
                    catch (OperationCanceledException) when (!cancellation.IsCancellationRequested) { break; }
                }
            }
        }
        _log("update: срок скачивания истёк или пакет недоступен; частичный файл сохранён, следующая проверка разрешена");
        return false;
    }

    private static void EnsureDownloadPaths(string destination, string part)
    {
        UpdateTransaction.EnsurePhysical(destination);
        UpdateTransaction.EnsurePhysical(part);
    }
}
