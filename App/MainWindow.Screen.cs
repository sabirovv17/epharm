using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Threading;
using LibVLCSharp.Shared;

namespace CustomerDisplay
{
    /// <summary>
    /// Stage 3: плейлист 2-го монитора (клиентский экран). Видео грузится из админ-панели,
    /// играет в аптеке через VLC. Стартует с локального promo.mp4, затем подменяется плейлистом
    /// с backend (если доступен). Оффлайн/пусто → остаёмся на promo.mp4.
    /// </summary>
    public partial class MainWindow
    {
        private List<string> _videoSources = new();
        private int _videoIndex = -1;
        private DateTime _lastVideoStart = DateTime.MinValue;
        private DispatcherTimer? _videoWatchdog;
        private long _lastMediaTime = -1;
        private int _videoStallTicks = 0;

        // Поллинг плейлиста: подпись текущего набора роликов (чтобы переключать ТОЛЬКО при
        // реальном изменении, а не перезапускать видео на каждый опрос). null = ещё не грузили
        // backend-плейлист (крутится локальный promo.mp4).
        private string? _currentPlaylistSig = null;
        private DispatcherTimer? _playlistPollTimer;

        // Heartbeat кассы (T4): периодический «я жив» на backend, чтобы он считал подключённые
        // кассы (Redis TTL). Отдельный таймер, ~60с, независим от видео/плейлиста.
        private DispatcherTimer? _heartbeatTimer;
        private const int HeartbeatSec = 60;

        /// <summary>
        /// Сторож зависания видео: если позиция воспроизведения «встала» (~6с без движения, а плеер
        /// должен играть) — перезапускаем ролик. Софт-декод в VM иногда виснет на сложном кадре;
        /// без этого экран замирает навсегда.
        /// </summary>
        private void StartVideoWatchdog()
        {
            _videoWatchdog = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3) };
            _videoWatchdog.Tick += (_, __) =>
            {
                if (_mediaPlayer == null || _videoSources.Count == 0) return;
                if (!_mediaPlayer.IsPlaying) { _videoStallTicks = 0; return; }

                var t = _mediaPlayer.Time; // текущая позиция, мс
                if (t >= 0 && t == _lastMediaTime)
                {
                    _videoStallTicks++;
                    if (_videoStallTicks >= 2) // ~6с без движения → завис
                    {
                        Log($"video stall на {t}мс → перезапуск ролика");
                        _videoStallTicks = 0;
                        _lastMediaTime = -1;
                        PlayNextVideo();
                    }
                }
                else
                {
                    _videoStallTicks = 0;
                    _lastMediaTime = t;
                }
            };
            _videoWatchdog.Start();
        }

        /// <summary>Следующий ролик плейлиста (циклически). Вызывается из EndReached.</summary>
        private void PlayNextVideo()
        {
            if (_videoSources.Count == 0 || _libVLC == null || _mediaPlayer == null) return;

            // Защита от зацикливания: если медиа «заканчивается» мгновенно (сбой декодирования,
            // нет файла, VM без GPU) — EndReached спамит. Не перезапускаем чаще раза в 2 сек,
            // иначе UI-поток захлёбывается и окно подвисает (Q перестаёт работать).
            if ((DateTime.Now - _lastVideoStart).TotalMilliseconds < 2000)
            {
                Task.Delay(2000).ContinueWith(_ =>
                    Dispatcher.BeginInvoke(new Action(PlayNextVideo)));
                return;
            }
            _lastVideoStart = DateTime.Now;

            _videoIndex = (_videoIndex + 1) % _videoSources.Count;
            var src = _videoSources[_videoIndex];
            try
            {
                var media = new Media(_libVLC, new Uri(src));
                _mediaPlayer.Play(media);
                _currentMedia?.Dispose();
                _currentMedia = media;
            }
            catch (Exception ex)
            {
                Log($"video play error для {src}: {ex.Message}");
            }
        }

        /// <summary>
        /// Запускает периодический опрос активного плейлиста (раз в PlaylistPollSec). Так смена
        /// плейлиста в админ-панели подхватывается кассой БЕЗ перезапуска приложения. Транспорт —
        /// HTTP-поллинг (не WebSocket): для digital-signage задержка в минуту не важна, зато
        /// устойчиво к обрывам сети (каждый опрос независим) и не нужен серверный push.
        /// </summary>
        private void StartPlaylistPolling()
        {
            if (_epharm == null || _posmConfig == null) return;
            var sec = _posmConfig.PlaylistPollSec;
            if (sec <= 0) { Log("Поллинг плейлиста выключен (PlaylistPollSec<=0)"); return; }

            _playlistPollTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(sec) };
            _playlistPollTimer.Tick += (_, __) => _ = LoadBackendPlaylistAsync();
            _playlistPollTimer.Start();
            Log($"Поллинг плейлиста запущен: каждые {sec}с");
        }

        /// <summary>
        /// Heartbeat кассы (T4): шлёт «я жив» на backend сразу и далее каждые ~60с, чтобы админка
        /// считала число подключённых касс (backend держит запись с Redis TTL). Транспорт — тот же
        /// HTTP-поллинг, что и плейлист: каждый удар независим, устойчив к обрывам сети, fail-safe
        /// (EpharmApiClient.HeartbeatAsync проглатывает ошибки). deviceId = Environment.MachineName
        /// (стабилен на машине), pharmacyId — из конфига. Вызывается на старте рядом с поллингом
        /// плейлиста; работает даже при EPHARM_NO_VIDEO (касса всё равно «подключена»).
        /// </summary>
        private void StartHeartbeatPolling()
        {
            if (_epharm == null || _posmConfig == null) return;
            var deviceId = Environment.MachineName;
            var pharmacyId = _posmConfig.PharmacyId;

            // Первый удар сразу — касса появляется в счётчике без задержки.
            _ = _epharm.HeartbeatAsync(deviceId, pharmacyId);

            _heartbeatTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(HeartbeatSec) };
            _heartbeatTimer.Tick += (_, __) => _ = _epharm.HeartbeatAsync(deviceId, pharmacyId);
            _heartbeatTimer.Start();
            Log($"Heartbeat кассы запущен: deviceId={deviceId}, каждые {HeartbeatSec}с");
        }

        /// <summary>
        /// Тянет активный плейлист из админки (GET /api/posm/playlists/active) и переключает
        /// 2-й монитор на его ролики — ТОЛЬКО если набор изменился (иначе видео не дёргаем).
        /// MinIO-хост localhost переписываем на адрес backend (из VM/кассы «localhost» — это
        /// сама машина, а не сервер). Вызывается на старте и периодически из StartPlaylistPolling.
        /// </summary>
        private async Task LoadBackendPlaylistAsync()
        {
            if (_epharm == null || _posmConfig == null) return;
            try
            {
                var pl = await _epharm.GetActivePlaylistAsync(_posmConfig.PharmacyId);
                var urls = pl?.Slides?
                    .Where(s => s.Kind == "video" && !string.IsNullOrWhiteSpace(s.Url))
                    .Select(s => RewriteMediaHost(s.Url))
                    .ToList();

                if (urls == null || urls.Count == 0)
                {
                    // Пусто/оффлайн → держим то, что уже играет (не чернеем, не дёргаем видео).
                    Log("Backend-плейлист пуст → остаёмся на текущем контенте");
                    return;
                }

                // Подпись набора: playlistId + список url. Меняется только при реальном изменении
                // плейлиста/слайдов в админке — тогда и переключаемся.
                var sig = (pl?.PlaylistId ?? "") + "|" + string.Join("\n", urls);
                if (sig == _currentPlaylistSig)
                {
                    return; // ничего не изменилось — видео не перезапускаем
                }

                await Dispatcher.InvokeAsync(() =>
                {
                    _currentPlaylistSig = sig;
                    _videoSources = urls;
                    _videoIndex = -1;
                    _lastVideoStart = DateTime.MinValue; // разрешить немедленный старт нового набора
                    PlayNextVideo(); // переключаемся на новый плейлист из админки
                });
                Log($"Backend-плейлист обновлён: {urls.Count} ролик(ов) от админ-панели (sig сменился)");
            }
            catch (Exception ex)
            {
                Log($"Backend-плейлист error: {ex.Message}");
            }
        }

        /// <summary>localhost/127.0.0.1 в media-URL → хост backend (MinIO живёт рядом с backend).</summary>
        private string RewriteMediaHost(string url)
        {
            try
            {
                if (_posmConfig == null) return url;
                var host = new Uri(_posmConfig.BackendBaseUrl).Host;
                if (host == "localhost") return url;
                return url
                    .Replace("://localhost", "://" + host)
                    .Replace("://127.0.0.1", "://" + host);
            }
            catch
            {
                return url;
            }
        }
    }
}
