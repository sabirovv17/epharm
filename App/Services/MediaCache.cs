using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Локальный кеш медиа для клиентского экрана. Плейлист из админки приходит URL-ами, но
    /// касса не должна зависеть от сети на каждом повторе ролика. Этот сервис хранит ролики
    /// на диске и докачивает недостающие файлы в фоне.
    /// </summary>
    public sealed class MediaCache : IDisposable
    {
        private readonly string _dir;
        private readonly Action<string>? _log;
        private readonly HttpClient _http;
        private readonly SemaphoreSlim _downloadGate = new(1, 1);
        private readonly string _manifestPath;

        public MediaCache(string dir, Action<string>? log = null)
        {
            _log = log;
            _dir = ResolveWritableDir(string.IsNullOrWhiteSpace(dir) ? @"C:\Epharm\media-cache" : dir, log);
            _manifestPath = Path.Combine(_dir, "playlist-manifest.json");
            // Не держим зависший download бесконечно: новый плейлист должен иметь шанс отменить
            // старую докачку и не ждать 10 минут на плохой сети.
            _http = new HttpClient { Timeout = TimeSpan.FromMinutes(3) };
        }

        private static string ResolveWritableDir(string preferred, Action<string>? log)
        {
            try
            {
                Directory.CreateDirectory(preferred);
                return preferred;
            }
            catch (Exception ex)
            {
                var fallback = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "Epharm",
                    "media-cache");
                Directory.CreateDirectory(fallback);
                log?.Invoke($"media-cache: {preferred} недоступен ({ex.Message}); использую {fallback}");
                return fallback;
            }
        }

        public string PreferredSource(string url)
        {
            var path = CachePath(url);
            return File.Exists(path) && new FileInfo(path).Length > 0
                ? new Uri(path).AbsoluteUri
                : url;
        }

        public List<string> PreferredSources(IEnumerable<string> urls) =>
            urls.Select(PreferredSource).ToList();

        public List<string> CachedSources(IEnumerable<string> urls) =>
            urls.Select(CachePath)
                .Where(path => File.Exists(path) && new FileInfo(path).Length > 0)
                .Select(path => new Uri(path).AbsoluteUri)
                .ToList();

        public bool HasAllCached(IEnumerable<string> urls)
        {
            foreach (var url in urls)
            {
                var path = CachePath(url);
                if (!File.Exists(path) || new FileInfo(path).Length <= 0) return false;
            }
            return true;
        }

        public void SavePlaylistManifest(string remoteSig, IReadOnlyList<string> urls)
        {
            try
            {
                var manifest = new PlaylistManifest
                {
                    RemoteSig = remoteSig,
                    Urls = urls.ToList(),
                    SavedAtUtc = DateTimeOffset.UtcNow,
                };
                var tmp = _manifestPath + ".tmp";
                File.WriteAllText(tmp, JsonSerializer.Serialize(manifest, ManifestJson.Options));
                if (File.Exists(_manifestPath)) File.Delete(_manifestPath);
                File.Move(tmp, _manifestPath);
            }
            catch (Exception ex)
            {
                _log?.Invoke($"media-cache: manifest save error: {ex.Message}");
            }
        }

        public (string RemoteSig, List<string> Urls)? LoadPlaylistManifest()
        {
            try
            {
                if (!File.Exists(_manifestPath)) return null;
                var manifest = JsonSerializer.Deserialize<PlaylistManifest>(
                    File.ReadAllText(_manifestPath),
                    ManifestJson.Options);
                if (manifest == null || string.IsNullOrWhiteSpace(manifest.RemoteSig) || manifest.Urls.Count == 0)
                    return null;
                return (manifest.RemoteSig, manifest.Urls);
            }
            catch (Exception ex)
            {
                _log?.Invoke($"media-cache: manifest load error: {ex.Message}");
                return null;
            }
        }

        public async Task<List<string>> EnsureCachedAsync(IReadOnlyList<string> urls, CancellationToken ct = default)
        {
            // Один download-pass за раз: поллинг плейлиста не должен наслаивать скачивания.
            await _downloadGate.WaitAsync(ct).ConfigureAwait(false);
            try
            {
                var result = new List<string>(urls.Count);
                foreach (var url in urls)
                {
                    ct.ThrowIfCancellationRequested();
                    result.Add(await EnsureCachedOneAsync(url, ct).ConfigureAwait(false));
                }
                return result;
            }
            finally
            {
                _downloadGate.Release();
            }
        }

        private async Task<string> EnsureCachedOneAsync(string url, CancellationToken ct)
        {
            var path = CachePath(url);
            var tmp = path + ".tmp";
            try
            {
                if (File.Exists(path) && new FileInfo(path).Length > 0)
                    return new Uri(path).AbsoluteUri;

                if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) ||
                    (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
                    return url;

                SafeDelete(tmp);
                using var resp = await _http.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, ct)
                    .ConfigureAwait(false);
                if (!resp.IsSuccessStatusCode)
                {
                    _log?.Invoke($"media-cache: {url} → HTTP {(int)resp.StatusCode}");
                    return url;
                }

                await using (var src = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false))
                await using (var dst = File.Create(tmp))
                {
                    await src.CopyToAsync(dst, ct).ConfigureAwait(false);
                }

                if (File.Exists(path)) File.Delete(path);
                File.Move(tmp, path);
                _log?.Invoke($"media-cache: сохранён {Path.GetFileName(path)}");
                return new Uri(path).AbsoluteUri;
            }
            catch (OperationCanceledException)
            {
                SafeDelete(tmp);
                throw;
            }
            catch (Exception ex)
            {
                _log?.Invoke($"media-cache: не удалось скачать {url}: {ex.Message}");
                SafeDelete(path + ".tmp");
                return File.Exists(path) ? new Uri(path).AbsoluteUri : url;
            }
        }

        private string CachePath(string url)
        {
            var ext = ".mp4";
            try
            {
                var uri = new Uri(url);
                var candidate = Path.GetExtension(uri.AbsolutePath);
                if (!string.IsNullOrWhiteSpace(candidate) && candidate.Length <= 8)
                    ext = candidate;
            }
            catch { /* keep default */ }

            var hash = Convert.ToHexString(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(url)))
                .ToLowerInvariant();
            return Path.Combine(_dir, hash + ext);
        }

        private static void SafeDelete(string path)
        {
            try { if (File.Exists(path)) File.Delete(path); } catch { /* ignore */ }
        }

        public void Dispose()
        {
            _downloadGate.Dispose();
            _http.Dispose();
        }

        private sealed class PlaylistManifest
        {
            public string RemoteSig { get; set; } = "";
            public List<string> Urls { get; set; } = new();
            public DateTimeOffset SavedAtUtc { get; set; }
        }

        private static class ManifestJson
        {
            public static readonly JsonSerializerOptions Options = new()
            {
                PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
                PropertyNameCaseInsensitive = true,
                WriteIndented = true,
            };
        }
    }
}
