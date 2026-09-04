using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;

namespace CustomerDisplay.Config
{
    /// <summary>
    /// Конфиг POSM-клиента: адрес backend, ключ устройства, идентификаторы аптеки/фармацевта,
    /// таймауты. Грузится из C:\Epharm\posm.json (или путь в env EPHARM_POSM_CONFIG),
    /// с переопределением через переменные окружения. Если файла нет и Enabled не задан —
    /// интеграция выключена (касса работает как раньше, без рекомендаций).
    /// </summary>
    public sealed class EpharmConfig
    {
        public bool Enabled { get; set; } = false;
        public string BackendBaseUrl { get; set; } = "http://localhost:8080";
        /// <summary>
        /// Резервные origin URL backend по приоритету. Нужны, когда основной публичный HTTPS
        /// ingress ещё настраивается: POSM продолжает работать через доверенный HTTP fallback.
        /// Путь здесь не указывается: API-клиент сам добавляет /api/posm/*.
        /// env EPHARM_BACKEND_FALLBACK_URLS: URL через ; или ,.
        /// </summary>
        public List<string> BackendFallbackBaseUrls { get; set; } = new();
        public string DeviceKey { get; set; } = "dev-posm-key";
        public string PharmacistId { get; set; } = "";
        public string PharmacyId { get; set; } = "";
        public int RecommendTimeoutMs { get; set; } = 5000;
        public int DebounceMs { get; set; } = 150;
        /// <summary>
        /// Устаревший флаг фоновой перепроверки рекомендаций. По умолчанию выключен:
        /// боевой клиент ходит в backend только после скана/добавления товара в кассе.
        /// env EPHARM_RECOMMEND_REFRESH_SEC.
        /// </summary>
        public int RecommendRefreshSec { get; set; } = 0;
        public int PopupAutoCloseSec { get; set; } = 30;
        public string OutboxDbPath { get; set; } = @"C:\Epharm\outbox.db";
        public int OutboxFlushSec { get; set; } = 5;
        /// <summary>Контур выдачи интернет-заказов. Backend также имеет независимый kill switch.</summary>
        public bool FulfillmentEnabled { get; set; } = true;
        /// <summary>
        /// Отдельный origin API выдачи заказов. Пустое значение наследует основной backend,
        /// поэтому действующие аптечные конфиги продолжают работать без переустановки.
        /// env EPHARM_FULFILLMENT_URL.
        /// </summary>
        public string FulfillmentBaseUrl { get; set; } = "";
        public List<string> FulfillmentFallbackBaseUrls { get; set; } = new();
        public int FulfillmentPollSec { get; set; } = 10;
        public string FulfillmentCredentialPath { get; set; } = @"C:\Epharm\fulfillment-device.dat";
        public string FulfillmentCachePath { get; set; } = @"C:\Epharm\fulfillment-orders.json";
        /// <summary>
        /// Exact-only захват чеков. POSM принимает только оригинальный PDF/PNG из доверенного
        /// read-only адаптера ККМ/OFD; реконструкция фискального документа запрещена.
        /// </summary>
        public bool ReceiptCaptureEnabled { get; set; } = true;
        public string ReceiptCaptureDir { get; set; } = @"C:\Epharm\receipts";
        public string FiscalReceiptInboxDir { get; set; } = @"C:\Epharm\fiscal-inbox";
        public List<string> FiscalReceiptTrustedSources { get; set; } = new()
        {
            "standardn-kkm-sdk",
            "ofd-api",
        };
        public int FiscalReceiptPollSec { get; set; } = 2;
        public int FiscalReceiptMaxClockSkewSec { get; set; } = 900;
        public int FiscalReceiptMaxArtifactMb { get; set; } = 10;
        /// <summary>Сколько дней держать незавершённый active-черновик после аварии.</summary>
        public int ReceiptCaptureActiveRetentionDays { get; set; } = 2;
        /// <summary>Сколько часов хранить оригинал после ACK фискальных метаданных.</summary>
        public int FiscalReceiptCompletedRetentionHours { get; set; } = 24;
        /// <summary>
        /// Период опроса активного плейлиста (сек). Касса подхватывает смену видео из админки
        /// без перезапуска. 0 — выключить поллинг (env EPHARM_PLAYLIST_POLL_SEC).
        /// </summary>
        public int PlaylistPollSec { get; set; } = 20;
        /// <summary>
        /// Локальный кеш видео с админ-панели. POSM хранит последний плейлист на диске,
        /// докачивает новые ролики в фоне и переключает VLC только на локальные файлы.
        /// Так окончание ролика, временный обрыв сети и замена видео в админке не блокируют
        /// клиентский экран.
        /// env EPHARM_MEDIA_CACHE_DIR.
        /// </summary>
        public string MediaCacheDir { get; set; } = @"C:\Epharm\media-cache";
        /// <summary>
        /// Размещение клиентского экрана (env EPHARM_SCREEN_MODE):
        ///   "dev"  — оконце слева-сверху на основном мониторе (для разработки/тестов; рядом виден
        ///            терминал и лог). ЗНАЧЕНИЕ ПО УМОЛЧАНИЮ, пока не финал.
        ///   "prod" — боевой режим: ЕСТЬ 2-й монитор → полноэкранный киоск на 2-м (клиентский экран);
        ///            ОДИН монитор → клиентский экран НЕ показываем вообще (работают только
        ///            рекомендации фармацевту). EPHARM_DEBUG=1 принудительно даёт "dev".
        /// </summary>
        public string ScreenMode { get; set; } = "dev";
        /// <summary>
        /// Куда писать лог приложения (env EPHARM_APP_LOG). Пусто → C:\Epharm\customerdisplay.log.
        /// Путь печатается в баннере старта, чтобы его было легко найти.
        /// </summary>
        public string AppLogPath { get; set; } = "";
        /// <summary>
        /// Режим «только экран фармацевта» (env EPHARM_PHARMACIST_PREVIEW=true). Для скринов поверх
        /// Стандарт-Н: НЕ запускает киоск/видео/лог, сразу показывает одну карточку-рекомендацию
        /// (frameless, поверх всех окон), без авто-закрытия. Esc/закрытие → выход.
        /// </summary>
        public bool PharmacistPreview { get; set; } = false;
        /// <summary>Авто-обновление клиента из админки. false (env EPHARM_UPDATE_ENABLED=false) — выкл.</summary>
        public bool UpdateEnabled { get; set; } = true;
        /// <summary>Период проверки обновлений (сек). По умолчанию 5 мин (env EPHARM_UPDATE_POLL_SEC).</summary>
        public int UpdatePollSec { get; set; } = 300;
        /// <summary>Проигрывать ли видео. false (env EPHARM_NO_VIDEO=true) — для VM без GPU.</summary>
        public bool VideoEnabled { get; set; } = true;
        /// <summary>
        /// Heartbeat-файл живости процесса. Главный UI-поток периодически пишет в него метку
        /// времени; ВНЕШНИЙ watchdog (scripts/watchdog.ps1, задача планировщика) проверяет
        /// свежесть и перезапускает кассу, если процесс упал ИЛИ завис (heartbeat устарел).
        /// Так прослушка логов Стандарт-Н держится «всегда». env EPHARM_HEARTBEAT_PATH.
        /// </summary>
        public string HeartbeatPath { get; set; } = @"C:\Epharm\heartbeat.txt";
        /// <summary>Период записи heartbeat (сек). env EPHARM_HEARTBEAT_SEC.</summary>
        public int HeartbeatSec { get; set; } = 15;
        /// <summary>
        /// Аргументы инициализации LibVLC (env EPHARM_VLC_ARGS, через пробел). По умолчанию
        /// софт-декод + тишина (--quiet --verbose=0): иначе нативный VLC спамит в консоль
        /// «[h264 …] get_buffer() failed / no frame!» при софт-декоде в VM без GPU.
        /// Для VM можно перебирать вывод: «--avcodec-hw=none --vout=direct3d9 …».
        /// </summary>
        public string VlcArgs { get; set; } = "--avcodec-hw=none --quiet --verbose=0";

        /// <summary>
        /// Читать локальную БД Стандарт-Н (Firebird) для диагностически важных полей, которых может
        /// не быть в zkassa.log: активный фармацевт (ACTIVEUSERS или открытая сессия) и цены товаров.
        /// Fail-safe: недоступная БД не блокирует кассу, POSM остаётся на данных из лога/backend.
        /// </summary>
        public bool StandardNDbEnabled { get; set; } = true;
        public string StandardNDbHost { get; set; } = "localhost";
        public int StandardNDbPort { get; set; } = 3050;
        /// <summary>
        /// Путь к ztrade на кассовом ПК. Если пусто, POSM пробует стандартные пути Стандарт-Н и demo.
        /// env EPHARM_STANDARDN_DB_PATH.
        /// </summary>
        public string StandardNDbPath { get; set; } = "";
        public string StandardNDbUser { get; set; } = "SYSDBA";
        public string StandardNDbPassword { get; set; } = "masterkey";
        public int StandardNDbTimeoutMs { get; set; } = 1000;
        /// <summary>
        /// Poll interval for the authoritative active receipt in DOCS/DOC_DETAIL_ACTIVE.
        /// This is local pharmacy traffic to Standard-N, not traffic to the Epharm backend.
        /// env EPHARM_STANDARDN_RECEIPT_POLL_MS.
        /// </summary>
        public int StandardNReceiptPollMs { get; set; } = 400;

        /// <summary>
        /// Optional explicit Standard-N cash log paths. Production installations are not uniform,
        /// so these paths are tried before the legacy defaults and bounded background discovery.
        /// env EPHARM_STANDARDN_LOG_PATHS accepts paths separated by ';'. The legacy singular
        /// EPHARM_LOG_PATH variable is still accepted for diagnostics and older deployments.
        /// </summary>
        public List<string> StandardNLogPaths { get; set; } = new();

        private const string DefaultPath = @"C:\Epharm\posm.json";

        public static EpharmConfig Load()
        {
            var cfg = new EpharmConfig();

            var path = Environment.GetEnvironmentVariable("EPHARM_POSM_CONFIG") ?? DefaultPath;
            try
            {
                if (File.Exists(path))
                {
                    var json = File.ReadAllText(path);
                    var fromFile = JsonSerializer.Deserialize<EpharmConfig>(
                        json,
                        new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
                    if (fromFile != null) cfg = fromFile;
                }
            }
            catch
            {
                // битый/недоступный конфиг не должен ронять кассу — остаёмся на дефолтах.
            }

            // Переопределение из env (приоритетнее файла) — удобно для пилота без правки файла.
            cfg.BackendBaseUrl = Env("EPHARM_BACKEND_URL", cfg.BackendBaseUrl);
            var fallbackUrls = Env("EPHARM_BACKEND_FALLBACK_URLS", "");
            if (!string.IsNullOrWhiteSpace(fallbackUrls))
            {
                cfg.BackendFallbackBaseUrls = new List<string>(
                    fallbackUrls.Split(new[] { ';', ',' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));
            }
            cfg.BackendFallbackBaseUrls ??= new List<string>();
            cfg.DeviceKey = Env("EPHARM_POSM_KEY", cfg.DeviceKey);
            cfg.PharmacistId = Env("EPHARM_PHARMACIST_ID", cfg.PharmacistId);
            cfg.PharmacyId = Env("EPHARM_PHARMACY_ID", cfg.PharmacyId);
            if (Env("EPHARM_FULFILLMENT_ENABLED", cfg.FulfillmentEnabled ? "true" : "false") == "false")
                cfg.FulfillmentEnabled = false;
            cfg.FulfillmentBaseUrl = Env("EPHARM_FULFILLMENT_URL", cfg.FulfillmentBaseUrl);
            var fulfillmentFallbackUrls = Env("EPHARM_FULFILLMENT_FALLBACK_URLS", "");
            if (!string.IsNullOrWhiteSpace(fulfillmentFallbackUrls))
            {
                cfg.FulfillmentFallbackBaseUrls = new List<string>(
                    fulfillmentFallbackUrls.Split(
                        new[] { ';', ',' },
                        StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));
            }
            cfg.FulfillmentFallbackBaseUrls ??= new List<string>();
            if (int.TryParse(Env("EPHARM_FULFILLMENT_POLL_SEC", ""), out var fulfillmentPollSec))
                cfg.FulfillmentPollSec = Math.Clamp(fulfillmentPollSec, 5, 60);
            cfg.FulfillmentCredentialPath = Env("EPHARM_FULFILLMENT_CREDENTIAL_PATH", cfg.FulfillmentCredentialPath);
            cfg.FulfillmentCachePath = Env("EPHARM_FULFILLMENT_CACHE_PATH", cfg.FulfillmentCachePath);
            if (Env("EPHARM_RECEIPT_CAPTURE_ENABLED", cfg.ReceiptCaptureEnabled ? "true" : "false") == "false")
                cfg.ReceiptCaptureEnabled = false;
            cfg.ReceiptCaptureDir = Env("EPHARM_RECEIPT_CAPTURE_DIR", cfg.ReceiptCaptureDir);
            cfg.FiscalReceiptInboxDir = Env("EPHARM_FISCAL_RECEIPT_INBOX_DIR", cfg.FiscalReceiptInboxDir);
            var trustedFiscalSources = Env("EPHARM_FISCAL_RECEIPT_TRUSTED_SOURCES", "");
            if (!string.IsNullOrWhiteSpace(trustedFiscalSources))
            {
                cfg.FiscalReceiptTrustedSources = new List<string>(
                    trustedFiscalSources.Split(
                        new[] { ';', ',' },
                        StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));
            }
            cfg.FiscalReceiptTrustedSources ??= new List<string>();
            if (int.TryParse(Env("EPHARM_FISCAL_RECEIPT_POLL_SEC", ""), out var fiscalPollSec))
                cfg.FiscalReceiptPollSec = Math.Clamp(fiscalPollSec, 1, 60);
            if (int.TryParse(Env("EPHARM_FISCAL_RECEIPT_MAX_CLOCK_SKEW_SEC", ""), out var fiscalClockSkewSec))
                cfg.FiscalReceiptMaxClockSkewSec = Math.Clamp(fiscalClockSkewSec, 30, 3600);
            if (int.TryParse(Env("EPHARM_FISCAL_RECEIPT_MAX_ARTIFACT_MB", ""), out var fiscalMaxArtifactMb))
                cfg.FiscalReceiptMaxArtifactMb = Math.Clamp(fiscalMaxArtifactMb, 1, 50);
            if (int.TryParse(Env("EPHARM_RECEIPT_ACTIVE_RETENTION_DAYS", ""), out var receiptRetentionDays))
                cfg.ReceiptCaptureActiveRetentionDays = Math.Clamp(receiptRetentionDays, 1, 30);
            if (int.TryParse(Env("EPHARM_FISCAL_RECEIPT_RETENTION_HOURS", ""), out var fiscalRetentionHours))
                cfg.FiscalReceiptCompletedRetentionHours = Math.Clamp(fiscalRetentionHours, 1, 168);

            // POSM включается при заданной аптеке. Фармацевт НЕ требуется в конфиге — он берётся
            // из лога кассы (токен kassir=), т.к. фармацевты работают посменно.
            if (!string.IsNullOrWhiteSpace(cfg.PharmacyId))
                cfg.Enabled = cfg.Enabled || Env("EPHARM_POSM_ENABLED", "false") == "true";

            // Отключение видео (для VM без GPU): env EPHARM_NO_VIDEO=true.
            if (Env("EPHARM_NO_VIDEO", "false") == "true") cfg.VideoEnabled = false;
            // Перебор режимов вывода VLC без пересборки (для VM).
            cfg.VlcArgs = Env("EPHARM_VLC_ARGS", cfg.VlcArgs);
            // Период опроса плейлиста (для пилота без правки файла).
            if (int.TryParse(Env("EPHARM_PLAYLIST_POLL_SEC", ""), out var pollSec)) cfg.PlaylistPollSec = pollSec;
            if (int.TryParse(Env("EPHARM_RECOMMEND_DEBOUNCE_MS", ""), out var debounceMs) && debounceMs >= 0)
                cfg.DebounceMs = debounceMs;
            if (int.TryParse(Env("EPHARM_RECOMMEND_REFRESH_SEC", ""), out var recoRefreshSec))
                cfg.RecommendRefreshSec = recoRefreshSec;
            cfg.MediaCacheDir = Env("EPHARM_MEDIA_CACHE_DIR", cfg.MediaCacheDir);
            // Режим размещения клиентского экрана (dev/prod) + путь лога — без правки файла.
            cfg.ScreenMode = Env("EPHARM_SCREEN_MODE", cfg.ScreenMode);
            // A packaged production path is authoritative. Old POSM pilots commonly left a
            // persistent EPHARM_APP_LOG pointing to Desktop; allowing it to override the installed
            // config splits diagnostics across files and makes the current run look silent.
            if (string.IsNullOrWhiteSpace(cfg.AppLogPath))
                cfg.AppLogPath = Env("EPHARM_APP_LOG", cfg.AppLogPath);
            // Режим превью экрана фармацевта (для скринов поверх Стандарт-Н).
            if (Env("EPHARM_PHARMACIST_PREVIEW", "false") == "true") cfg.PharmacistPreview = true;
            // Авто-обновление клиента.
            if (Env("EPHARM_UPDATE_ENABLED", "true") == "false") cfg.UpdateEnabled = false;
            if (int.TryParse(Env("EPHARM_UPDATE_POLL_SEC", ""), out var updSec)) cfg.UpdatePollSec = updSec;
            // Старые аптечные конфиги фиксировали прежний default 1800 секунд. Начиная с
            // v1.0.46 ограничиваем положительный интервал пятью минутами, чтобы дальнейшие
            // массовые релизы доходили до сети быстро даже без перезаписи posm.json.
            if (cfg.UpdatePollSec > 0) cfg.UpdatePollSec = Math.Min(cfg.UpdatePollSec, 300);
            // Heartbeat (для внешнего watchdog).
            cfg.HeartbeatPath = Env("EPHARM_HEARTBEAT_PATH", cfg.HeartbeatPath);
            if (int.TryParse(Env("EPHARM_HEARTBEAT_SEC", ""), out var hbSec) && hbSec > 0) cfg.HeartbeatSec = hbSec;
            // Локальная БД Стандарт-Н (Firebird): активный фармацевт + реальные цены.
            if (Env("EPHARM_STANDARDN_DB_ENABLED", cfg.StandardNDbEnabled ? "true" : "false") == "false")
                cfg.StandardNDbEnabled = false;
            cfg.StandardNDbHost = Env("EPHARM_STANDARDN_DB_HOST", cfg.StandardNDbHost);
            cfg.StandardNDbPath = Env("EPHARM_STANDARDN_DB_PATH", cfg.StandardNDbPath);
            cfg.StandardNDbUser = Env("EPHARM_STANDARDN_DB_USER", cfg.StandardNDbUser);
            cfg.StandardNDbPassword = Env("EPHARM_STANDARDN_DB_PASSWORD", cfg.StandardNDbPassword);
            if (int.TryParse(Env("EPHARM_STANDARDN_DB_PORT", ""), out var fbPort) && fbPort > 0) cfg.StandardNDbPort = fbPort;
            if (int.TryParse(Env("EPHARM_STANDARDN_DB_TIMEOUT_MS", ""), out var fbTimeout) && fbTimeout > 0)
                cfg.StandardNDbTimeoutMs = fbTimeout;
            if (int.TryParse(Env("EPHARM_STANDARDN_RECEIPT_POLL_MS", ""), out var receiptPollMs))
                cfg.StandardNReceiptPollMs = Math.Clamp(receiptPollMs, 200, 5000);

            cfg.StandardNLogPaths ??= new List<string>();
            var configuredLogPaths = Env("EPHARM_STANDARDN_LOG_PATHS", "");
            if (!string.IsNullOrWhiteSpace(configuredLogPaths))
            {
                cfg.StandardNLogPaths.InsertRange(
                    0,
                    configuredLogPaths.Split(
                        ';',
                        StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));
            }
            var legacyLogPath = Env("EPHARM_LOG_PATH", "");
            if (!string.IsNullOrWhiteSpace(legacyLogPath))
                cfg.StandardNLogPaths.Insert(0, legacyLogPath);

            return cfg;
        }

        /// <summary>Список HTTP(S) origin для failover без path/query/fragment и без дублей.</summary>
        public IReadOnlyList<Uri> GetBackendBaseUris()
        {
            var rawUrls = new List<string> { BackendBaseUrl };
            rawUrls.AddRange(BackendFallbackBaseUrls ?? new List<string>());

            return ParseBaseUris(rawUrls, "POSM backend");
        }

        /// <summary>
        /// API выдачи заказов может жить отдельно от админки. Если отдельный origin не задан,
        /// наследуем текущие backend endpoints для обратной совместимости со всеми posm.json.
        /// </summary>
        public IReadOnlyList<Uri> GetFulfillmentBaseUris()
        {
            if (string.IsNullOrWhiteSpace(FulfillmentBaseUrl) &&
                (FulfillmentFallbackBaseUrls == null || FulfillmentFallbackBaseUrls.Count == 0))
            {
                return GetBackendBaseUris();
            }

            var rawUrls = new List<string> { FulfillmentBaseUrl };
            rawUrls.AddRange(FulfillmentFallbackBaseUrls ?? new List<string>());
            return ParseBaseUris(rawUrls, "POSM fulfillment backend");
        }

        private static IReadOnlyList<Uri> ParseBaseUris(IEnumerable<string> rawUrls, string name)
        {
            var endpoints = new List<Uri>();
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var raw in rawUrls)
            {
                if (string.IsNullOrWhiteSpace(raw) ||
                    !Uri.TryCreate(raw.Trim(), UriKind.Absolute, out var parsed) ||
                    (parsed.Scheme != Uri.UriSchemeHttp && parsed.Scheme != Uri.UriSchemeHttps))
                {
                    continue;
                }

                var normalized = new UriBuilder(parsed.Scheme, parsed.Host, parsed.IsDefaultPort ? -1 : parsed.Port).Uri;
                if (seen.Add(normalized.AbsoluteUri)) endpoints.Add(normalized);
            }

            if (endpoints.Count == 0)
                throw new InvalidOperationException($"{name} URL must be an absolute HTTP(S) origin.");

            return endpoints;
        }

        private static string Env(string key, string fallback)
        {
            var v = Environment.GetEnvironmentVariable(key);
            return string.IsNullOrWhiteSpace(v) ? fallback : v;
        }
    }
}
