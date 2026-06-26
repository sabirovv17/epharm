using System;
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
        /// Куда писать лог приложения (env EPHARM_APP_LOG). Пусто → Рабочий стол\customerdisplay.log.
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
        /// <summary>Период проверки обновлений (сек). По умолчанию 30 мин (env EPHARM_UPDATE_POLL_SEC).</summary>
        public int UpdatePollSec { get; set; } = 1800;
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
            cfg.DeviceKey = Env("EPHARM_POSM_KEY", cfg.DeviceKey);
            cfg.PharmacistId = Env("EPHARM_PHARMACIST_ID", cfg.PharmacistId);
            cfg.PharmacyId = Env("EPHARM_PHARMACY_ID", cfg.PharmacyId);

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
            cfg.AppLogPath = Env("EPHARM_APP_LOG", cfg.AppLogPath);
            // Режим превью экрана фармацевта (для скринов поверх Стандарт-Н).
            if (Env("EPHARM_PHARMACIST_PREVIEW", "false") == "true") cfg.PharmacistPreview = true;
            // Авто-обновление клиента.
            if (Env("EPHARM_UPDATE_ENABLED", "true") == "false") cfg.UpdateEnabled = false;
            if (int.TryParse(Env("EPHARM_UPDATE_POLL_SEC", ""), out var updSec)) cfg.UpdatePollSec = updSec;
            // Heartbeat (для внешнего watchdog).
            cfg.HeartbeatPath = Env("EPHARM_HEARTBEAT_PATH", cfg.HeartbeatPath);
            if (int.TryParse(Env("EPHARM_HEARTBEAT_SEC", ""), out var hbSec) && hbSec > 0) cfg.HeartbeatSec = hbSec;

            return cfg;
        }

        private static string Env(string key, string fallback)
        {
            var v = Environment.GetEnvironmentVariable(key);
            return string.IsNullOrWhiteSpace(v) ? fallback : v;
        }
    }
}
