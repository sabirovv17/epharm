using System;
using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// HTTP-клиент к backend POSM Rules Engine. Все вызовы fail-safe: при сети/таймауте/ошибке
    /// возвращают null/false и НЕ бросают — касса не должна тормозить или падать из-за backend.
    /// Аутентификация устройства — заголовок X-Posm-Key.
    /// </summary>
    public sealed class EpharmApiClient : IDisposable
    {
        private readonly HttpClient _http;
        private readonly Action<string>? _log;
        private readonly TimeSpan _recommendTimeout;
        // На Windows VM первый DNS/TLS connect к публичному sslip.io домену может занимать
        // заметно дольше, чем на сервере/маке. Эти вызовы фоновые и не блокируют кассу, поэтому
        // лучше дать сети восстановиться, чем регулярно считать живой backend "таймаутом".
        private readonly TimeSpan _playlistTimeout = TimeSpan.FromSeconds(20);
        private readonly TimeSpan _heartbeatTimeout = TimeSpan.FromSeconds(20);
        private DateTime _lastHeartbeatErrorLog = DateTime.MinValue;
        private string? _lastHeartbeatErrorKey;

        public string? LastPlaylistError { get; private set; }

        private static readonly JsonSerializerOptions JsonOpts = new()
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            PropertyNameCaseInsensitive = true,
        };

        public EpharmApiClient(EpharmConfig cfg, Action<string>? log = null)
        {
            _log = log;
            // Рекомендации держим быстрыми (per-call CTS ниже), а ОБЩИЙ таймаут клиента — щедрый:
            // плейлист/heartbeat/sales/cdp при 700мс часто таймаутили на чуть медленной сети
            // (отсюда «видео не стягивается»). Теперь общий лимит не режет фоновые запросы раньше
            // их собственного timeout, а recommend ограничен отдельно.
            _recommendTimeout = TimeSpan.FromMilliseconds(Math.Max(200, cfg.RecommendTimeoutMs));
            _http = new HttpClient
            {
                BaseAddress = new Uri(cfg.BackendBaseUrl),
                Timeout = TimeSpan.FromSeconds(30),
            };
            _http.DefaultRequestHeaders.Add("X-Posm-Key", cfg.DeviceKey);
        }

        /// <summary>Человекочитаемая причина сбоя запроса для лога (таймаут / HTTP-код / сеть).</summary>
        private static string Describe(Exception ex) => ex switch
        {
            TaskCanceledException or OperationCanceledException => "таймаут (нет ответа вовремя)",
            HttpRequestException h when h.StatusCode is { } sc => $"HTTP {(int)sc} {sc}",
            HttpRequestException h when h.Message.Contains("SSL", StringComparison.OrdinalIgnoreCase) ||
                h.Message.Contains("TLS", StringComparison.OrdinalIgnoreCase) =>
                "временный TLS/SSL сбой соединения",
            HttpRequestException => "временно нет связи с backend",
            _ => ex.Message,
        };

        private static bool ShouldLog(ref DateTime lastAt, ref string? lastKey, string key, int everySec = 60)
        {
            var now = DateTime.Now;
            if (lastKey == key && (now - lastAt).TotalSeconds < everySec) return false;
            lastKey = key;
            lastAt = now;
            return true;
        }

        /// <summary>Корзина → рекомендации. null при любой ошибке/таймауте (popup не покажется).</summary>
        public async Task<RecommendResponse?> RecommendAsync(RecommendRequest req, CancellationToken ct = default)
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(_recommendTimeout);
            try
            {
                using var resp = await _http.PostAsJsonAsync("/api/posm/recommend", req, JsonOpts, cts.Token)
                    .ConfigureAwait(false);
                if (!resp.IsSuccessStatusCode)
                {
                    _log?.Invoke($"recommend: HTTP {(int)resp.StatusCode} {resp.StatusCode}");
                    return null;
                }
                return await resp.Content.ReadFromJsonAsync<RecommendResponse>(JsonOpts, cts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                _log?.Invoke($"recommend: {Describe(ex)}");
                return null;
            }
        }

        /// <summary>
        /// Фиксация результата рекомендации (accepted/rejected). false при ошибке —
        /// вызов уходит в OfflineOutbox для гарантированной доставки.
        /// </summary>
        public async Task<bool> RecordOutcomeAsync(string eventId, OutcomeRequest req, CancellationToken ct = default)
        {
            try
            {
                using var resp = await _http
                    .PostAsJsonAsync($"/api/posm/recommendations/{eventId}/outcome", req, JsonOpts, ct)
                    .ConfigureAwait(false);
                return resp.IsSuccessStatusCode;
            }
            catch
            {
                return false;
            }
        }

        /// <summary>
        /// Активный плейлист 2-го монитора (Stage 3). null при ошибке/оффлайне → клиент крутит
        /// локальный promo.mp4. Не бросает.
        /// </summary>
        public async Task<ActivePlaylist?> GetActivePlaylistAsync(string? pharmacyId = null, CancellationToken ct = default)
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(_playlistTimeout);
            try
            {
                var url = "/api/posm/playlists/active";
                if (!string.IsNullOrWhiteSpace(pharmacyId))
                    url += "?pharmacyId=" + Uri.EscapeDataString(pharmacyId);
                var playlist = await _http.GetFromJsonAsync<ActivePlaylist>(url, JsonOpts, cts.Token)
                    .ConfigureAwait(false);
                LastPlaylistError = null;
                return playlist;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                var desc = Describe(ex);
                LastPlaylistError = desc;
                return null;
            }
        }

        /// <summary>CDP (§5.6): поиск клиента лояльности по телефону. null при ошибке/оффлайне.</summary>
        public async Task<CdpLookupResult?> CdpLookupAsync(string phone, CancellationToken ct = default)
        {
            try
            {
                using var resp = await _http
                    .PostAsJsonAsync("/api/posm/cdp/lookup", new CdpLookupRequestDto { Phone = phone }, JsonOpts, ct)
                    .ConfigureAwait(false);
                if (!resp.IsSuccessStatusCode) return null;
                return await resp.Content.ReadFromJsonAsync<CdpLookupResult>(JsonOpts, ct).ConfigureAwait(false);
            }
            catch
            {
                return null;
            }
        }

        /// <summary>CDP (§5.6): регистрация нового клиента. Идемпотентно по телефону. null при ошибке.</summary>
        public async Task<CdpProfile?> CdpRegisterAsync(CdpRegisterRequestDto req, CancellationToken ct = default)
        {
            try
            {
                using var resp = await _http
                    .PostAsJsonAsync("/api/posm/cdp/register", req, JsonOpts, ct)
                    .ConfigureAwait(false);
                if (!resp.IsSuccessStatusCode) return null;
                return await resp.Content.ReadFromJsonAsync<CdpProfile>(JsonOpts, ct).ConfigureAwait(false);
            }
            catch
            {
                return null;
            }
        }

        /// <summary>
        /// Текущий релиз клиента для платформы (авто-обновление). null при ошибке/оффлайне —
        /// апдейт просто не произойдёт в этот раз. Не бросает.
        /// </summary>
        public async Task<AppVersionInfo?> GetAppVersionAsync(string platform = "win-x64", CancellationToken ct = default)
        {
            try
            {
                var url = "/api/posm/app/version?platform=" + Uri.EscapeDataString(platform);
                return await _http.GetFromJsonAsync<AppVersionInfo>(url, JsonOpts, ct).ConfigureAwait(false);
            }
            catch
            {
                return null;
            }
        }

        /// <summary>
        /// Heartbeat живости кассы (T4): POST /api/posm/heartbeat?deviceId=&amp;pharmacyId= (без тела,
        /// X-Posm-Key уже в заголовках по умолчанию). Backend держит счётчик подключённых касс
        /// (Redis TTL). Fail-safe: при сети/таймауте/ошибке просто проглатываем — пропуск одного
        /// удара ничего не ломает, следующий тик досчитается. Не бросает.
        /// </summary>
        public async Task HeartbeatAsync(string deviceId, string? pharmacyId, CancellationToken ct = default)
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(_heartbeatTimeout);
            try
            {
                var url = "/api/posm/heartbeat?deviceId=" + Uri.EscapeDataString(deviceId);
                if (!string.IsNullOrWhiteSpace(pharmacyId))
                    url += "&pharmacyId=" + Uri.EscapeDataString(pharmacyId);
                using var resp = await _http.PostAsync(url, null, cts.Token).ConfigureAwait(false);
                if (!resp.IsSuccessStatusCode)
                    _log?.Invoke($"heartbeat: HTTP {(int)resp.StatusCode} {resp.StatusCode}");
                // тело ответа {ok, deviceId} нам не нужно — важен сам факт удара.
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                var desc = Describe(ex);
                if (ShouldLog(ref _lastHeartbeatErrorLog, ref _lastHeartbeatErrorKey, desc))
                    _log?.Invoke($"heartbeat: временно не доставлен ({desc}); повторю по таймеру");
            }
        }

        /// <summary>
        /// Фиксация фактического показа рекомендации (V032). false при ошибке — вызов уходит в
        /// OfflineOutbox для гарантированной доставки. shownAt — client-время показа попапа.
        /// </summary>
        public async Task<bool> MarkShownAsync(string eventId, DateTimeOffset shownAt, CancellationToken ct = default)
        {
            try
            {
                using var resp = await _http
                    .PostAsJsonAsync(
                        $"/api/posm/recommendations/{eventId}/shown",
                        new MarkShownRequest { ShownAt = shownAt }, JsonOpts, ct)
                    .ConfigureAwait(false);
                return resp.IsSuccessStatusCode;
            }
            catch
            {
                return false;
            }
        }

        /// <summary>Отправка завершённого чека (источник №1 сверки). false при ошибке → outbox.</summary>
        public async Task<bool> PostSaleAsync(SaleReport sale, CancellationToken ct = default)
        {
            try
            {
                using var resp = await _http.PostAsJsonAsync("/api/posm/sales", sale, JsonOpts, ct)
                    .ConfigureAwait(false);
                return resp.IsSuccessStatusCode;
            }
            catch
            {
                return false;
            }
        }

        public void Dispose() => _http.Dispose();
    }
}
