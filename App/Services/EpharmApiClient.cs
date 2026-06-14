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

        private static readonly JsonSerializerOptions JsonOpts = new()
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            PropertyNameCaseInsensitive = true,
        };

        public EpharmApiClient(EpharmConfig cfg)
        {
            _http = new HttpClient
            {
                BaseAddress = new Uri(cfg.BackendBaseUrl),
                Timeout = TimeSpan.FromMilliseconds(cfg.RecommendTimeoutMs),
            };
            _http.DefaultRequestHeaders.Add("X-Posm-Key", cfg.DeviceKey);
        }

        /// <summary>Корзина → рекомендации. null при любой ошибке/таймауте (popup не покажется).</summary>
        public async Task<RecommendResponse?> RecommendAsync(RecommendRequest req, CancellationToken ct = default)
        {
            try
            {
                using var resp = await _http.PostAsJsonAsync("/api/posm/recommend", req, JsonOpts, ct)
                    .ConfigureAwait(false);
                if (!resp.IsSuccessStatusCode) return null;
                return await resp.Content.ReadFromJsonAsync<RecommendResponse>(JsonOpts, ct).ConfigureAwait(false);
            }
            catch
            {
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
            try
            {
                var url = "/api/posm/playlists/active";
                if (!string.IsNullOrWhiteSpace(pharmacyId))
                    url += "?pharmacyId=" + Uri.EscapeDataString(pharmacyId);
                return await _http.GetFromJsonAsync<ActivePlaylist>(url, JsonOpts, ct).ConfigureAwait(false);
            }
            catch
            {
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
            try
            {
                var url = "/api/posm/heartbeat?deviceId=" + Uri.EscapeDataString(deviceId);
                if (!string.IsNullOrWhiteSpace(pharmacyId))
                    url += "&pharmacyId=" + Uri.EscapeDataString(pharmacyId);
                using var resp = await _http.PostAsync(url, null, ct).ConfigureAwait(false);
                // тело ответа {ok, deviceId} нам не нужно — важен сам факт удара.
            }
            catch
            {
                // оффлайн/таймаут — пропускаем этот удар, касса работает дальше.
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
