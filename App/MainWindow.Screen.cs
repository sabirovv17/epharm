using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Threading;
using CustomerDisplay.Services;
using LibVLCSharp.Shared;

namespace CustomerDisplay
{
    /// <summary>
    /// Stage 3: плейлист 2-го монитора (клиентский экран). Видео грузится из админ-панели,
    /// играет в аптеке через VLC. Backend отдаёт URL-ы, клиент скачивает ролики в локальный кеш
    /// и переключает VLC только на локальные файлы. Оффлайн/пусто → держим текущий или последний
    /// закешированный плейлист.
    /// </summary>
    public partial class MainWindow
    {
        private List<string> _videoSources = new();
        private int _videoIndex = -1;
        private DateTime _lastVideoStart = DateTime.MinValue;
        private DispatcherTimer? _videoWatchdog;
        private long _lastMediaTime = -1;
        private int _videoStallTicks = 0;
        private bool _videoStartedLogged = false; // «видео запущено» логируем РОВНО один раз за сессию

        // Поллинг плейлиста: подпись текущего набора роликов (чтобы переключать ТОЛЬКО при
        // реальном изменении, а не перезапускать видео на каждый опрос). null = ещё не грузили
        // backend-плейлист (до этого либо пусто, либо запущен прошлый кеш).
        private string? _currentPlaylistSig = null;
        private CancellationTokenSource? _playlistPollCts;
        private CancellationTokenSource? _mediaWarmupCts;
        private int _playlistLoadBusy;
        private MediaCache? _mediaCache;
        private string? _remotePlaylistSig;
        private string? _warmingPlaylistSig;
        private DateTime _lastPlaylistIssueAt = DateTime.MinValue;
        private string? _lastPlaylistIssueKey;

        // Heartbeat кассы (T4): периодический «я жив» на backend, чтобы он считал подключённые
        // кассы (Redis TTL). Отдельный таймер, 30с, независим от видео/плейлиста.
        private DispatcherTimer? _heartbeatTimer;
        private const int HeartbeatSec = 30;
        private int _heartbeatRequestBusy;
        private bool? _heartbeatDelivered;

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
                        // Тихий перезапуск: в VM без GPU это происходит постоянно, лог не нужен
                        // (флуд). Один раз про старт видео уже сказали в PlayNextVideo.
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

            _videoIndex = _videoSources.Count == 1 ? 0 : (_videoIndex + 1) % _videoSources.Count;
            var src = _videoSources[_videoIndex];
            try
            {
                var media = new Media(_libVLC, new Uri(src));
                if (_videoSources.Count == 1)
                {
                    // Один ролик на клиентском экране должен крутиться бесконечно без повторного
                    // похода в сеть и без частого EndReached/reopen цикла VLC.
                    media.AddOption(":input-repeat=65535");
                }
                _mediaPlayer.Play(media);
                _currentMedia?.Dispose();
                _currentMedia = media;
                if (!_videoStartedLogged) { Log("видео запущено"); _videoStartedLogged = true; }
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

            _playlistPollCts?.Cancel();
            _playlistPollCts = new CancellationTokenSource();
            var token = _playlistPollCts.Token;
            _ = Task.Run(async () =>
            {
                while (!token.IsCancellationRequested)
                {
                    try
                    {
                        await Task.Delay(TimeSpan.FromSeconds(sec), token).ConfigureAwait(false);
                        await LoadBackendPlaylistAsync(token).ConfigureAwait(false);
                    }
                    catch (OperationCanceledException) { return; }
                    catch (Exception ex) { Log($"playlist poll error: {ex.Message}"); }
                }
            }, token);
            Log($"Поллинг плейлиста запущен: каждые {sec}с");
        }

        /// <summary>
        /// Heartbeat кассы (T4): шлёт «я жив» на backend сразу и далее каждые 30с, чтобы админка
        /// считала число подключённых касс (backend хранит last-seen в Redis). Транспорт — тот же
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
            _ = SendHeartbeatOnceAsync(deviceId, pharmacyId);

            _heartbeatTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(HeartbeatSec) };
            _heartbeatTimer.Tick += (_, __) => _ = SendHeartbeatOnceAsync(deviceId, pharmacyId);
            _heartbeatTimer.Start();
            Log($"Heartbeat кассы запущен: deviceId={deviceId}, каждые {HeartbeatSec}с");
        }

        private async Task SendHeartbeatOnceAsync(string deviceId, string pharmacyId)
        {
            if (_epharm == null || Interlocked.Exchange(ref _heartbeatRequestBusy, 1) == 1) return;
            try
            {
                // Запрашиваем топологию на каждом heartbeat: Windows может увидеть второй
                // монитор уже после запуска приложения (кабель/питание/драйвер).
                var monitorCount = System.Windows.Forms.Screen.AllScreens.Length;
                var delivered = await _epharm
                    .HeartbeatAsync(deviceId, pharmacyId, monitorCount)
                    .ConfigureAwait(false);
                if (delivered && _heartbeatDelivered != true)
                {
                    _heartbeatDelivered = true;
                    Log($"Heartbeat backend подтверждён: {_epharm.ActiveBackendBaseUri}, " +
                        $"deviceId={deviceId}, pharmacyId={pharmacyId}");
                }
                else if (!delivered && _heartbeatDelivered != false)
                {
                    _heartbeatDelivered = false;
                    Log($"Heartbeat backend OFFLINE: {_epharm.LastHeartbeatError ?? "неизвестная ошибка"}; " +
                        "POSM продолжает работу и повторит подключение автоматически");
                }
            }
            catch (Exception ex)
            {
                if (_heartbeatDelivered != false)
                {
                    _heartbeatDelivered = false;
                    Log($"Heartbeat backend OFFLINE: {ex.GetBaseException().Message}");
                }
            }
            finally
            {
                Interlocked.Exchange(ref _heartbeatRequestBusy, 0);
            }
        }

        private void EnsureMediaCache()
        {
            if (_mediaCache == null && _posmConfig != null)
                _mediaCache = new MediaCache(_posmConfig.MediaCacheDir, Log);
        }

        private void LogPlaylistIssue(string key, string message)
        {
            var now = DateTime.Now;
            if (_lastPlaylistIssueKey != key || (now - _lastPlaylistIssueAt).TotalSeconds >= 60)
            {
                Log(message);
                _lastPlaylistIssueKey = key;
                _lastPlaylistIssueAt = now;
            }
        }

        private void TryStartCachedPlaylist()
        {
            EnsureMediaCache();
            var manifest = _mediaCache?.LoadPlaylistManifest();
            if (manifest == null) return;

            var cached = _mediaCache!.CachedSources(manifest.Value.Urls);
            if (cached.Count == 0)
            {
                LogPlaylistIssue("cache-empty", "media-cache: прошлый плейлист найден, но локальных файлов нет");
                return;
            }

            SwitchVideoSources(manifest.Value.RemoteSig, cached, "запущен локальный кеш прошлого плейлиста");
        }

        private void SwitchVideoSources(string remoteSig, List<string> localSources, string reason)
        {
            if (localSources.Count == 0) return;
            var playableSig = remoteSig + "|local=" + string.Join("\n", localSources);
            if (_currentPlaylistSig == playableSig) return;

            _remotePlaylistSig = remoteSig;
            _currentPlaylistSig = playableSig;
            _videoSources = localSources;
            _videoIndex = -1;
            _lastVideoStart = DateTime.MinValue; // разрешить немедленный старт нового локального набора
            PlayNextVideo();
            Log($"Backend-плейлист: {reason}, локальных роликов={localSources.Count}");
        }

        /// <summary>
        /// Тянет активный плейлист из админки (GET /api/posm/playlists/active) и переключает
        /// 2-й монитор на его ролики — ТОЛЬКО после локального кеширования. VLC не стримит сеть:
        /// если интернет отвалился или новый ролик качается, старый локальный контент продолжает
        /// играть, а переключение происходит атомарно после успешной докачки.
        /// MinIO-хост localhost переписываем на адрес backend (из VM/кассы «localhost» — это
        /// сама машина, а не сервер). Вызывается на старте и периодически из StartPlaylistPolling.
        /// </summary>
        private async Task LoadBackendPlaylistAsync(CancellationToken ct = default)
        {
            if (_epharm == null || _posmConfig == null) return;
            if (Interlocked.Exchange(ref _playlistLoadBusy, 1) == 1) return;
            try
            {
                EnsureMediaCache();

                var pl = await _epharm.GetActivePlaylistAsync(_posmConfig.PharmacyId, ct);
                if (pl == null)
                {
                    // Оффлайн/TLS/таймаут → держим то, что уже играет. Это штатный fail-safe,
                    // не пустой плейлист и не повод чернить экран.
                    if (_videoSources.Count == 0) TryStartCachedPlaylist();
                    var reason = _epharm.LastPlaylistError ?? "backend не ответил";
                    LogPlaylistIssue("playlist-unavailable:" + reason,
                        $"Backend-плейлист временно недоступен ({reason}) → продолжаю текущий/локальный контент");
                    return;
                }

                var urls = pl.Slides?
                    .Where(s => s.Kind == "video" && !string.IsNullOrWhiteSpace(s.Url))
                    .Select(s => RewriteMediaHost(s.Url))
                    .ToList();

                if (urls == null || urls.Count == 0)
                {
                    // Backend ответил, но активного видео нет → держим текущий контент.
                    if (_videoSources.Count == 0) TryStartCachedPlaylist();
                    LogPlaylistIssue("playlist-empty", "Backend-плейлист пуст → продолжаю текущий/локальный контент");
                    return;
                }

                // Подпись набора: playlistId + список url. Меняется только при реальном изменении
                // плейлиста/слайдов в админке — тогда и переключаемся.
                var remoteSig = (pl?.PlaylistId ?? "") + "|" + string.Join("\n", urls);
                _mediaCache!.SavePlaylistManifest(remoteSig, urls);

                var cachedNow = _mediaCache.CachedSources(urls);
                var allCached = _mediaCache.HasAllCached(urls);
                if (cachedNow.Count > 0 && (allCached || _videoSources.Count == 0))
                {
                    await Dispatcher.InvokeAsync(() =>
                        SwitchVideoSources(remoteSig, cachedNow, allCached
                            ? "переключён на полностью локальный плейлист"
                            : "запущена доступная локальная часть плейлиста"));
                }

                if (remoteSig == _remotePlaylistSig && allCached)
                {
                    return; // ничего нового — видео не перезапускаем
                }

                _remotePlaylistSig = remoteSig;
                if (!allCached && _warmingPlaylistSig != remoteSig)
                {
                    StartPlaylistWarmup(remoteSig, urls);
                    Log($"Backend-плейлист получен: {urls.Count} ролик(ов); докачиваю в локальный кеш");
                }
            }
            catch (OperationCanceledException) { /* shutdown */ }
            catch (Exception ex)
            {
                Log($"Backend-плейлист error: {ex.Message}");
            }
            finally
            {
                Interlocked.Exchange(ref _playlistLoadBusy, 0);
            }
        }

        private void StartPlaylistWarmup(string remoteSig, List<string> urls)
        {
            // Не Dispose-им старый CTS здесь: его token ещё может быть внутри HttpClient.
            // Старая warmup-задача сама освободит свой CTS в finally после выхода.
            _mediaWarmupCts?.Cancel();
            var cts = _playlistPollCts == null
                ? new CancellationTokenSource()
                : CancellationTokenSource.CreateLinkedTokenSource(_playlistPollCts.Token);
            _mediaWarmupCts = cts;
            _warmingPlaylistSig = remoteSig;
            _ = Task.Run(() => WarmPlaylistCacheAsync(remoteSig, urls, cts), cts.Token);
        }

        private async Task WarmPlaylistCacheAsync(string remoteSig, List<string> urls, CancellationTokenSource cts)
        {
            var ct = cts.Token;
            try
            {
                if (_mediaCache == null || urls.Count == 0) return;
                await _mediaCache.EnsureCachedAsync(urls, ct).ConfigureAwait(false);
                var cached = _mediaCache.CachedSources(urls);
                if (cached.Count == 0)
                {
                    LogPlaylistIssue("cache-download-empty", "media-cache: ролики не скачались → продолжаем текущий контент");
                    return;
                }
                await Dispatcher.InvokeAsync(() =>
                {
                    if (_remotePlaylistSig != remoteSig) return;
                    SwitchVideoSources(remoteSig, cached, "переключён после фоновой докачки");
                });
            }
            catch (OperationCanceledException) { /* shutdown */ }
            catch (Exception ex)
            {
                Log($"media-cache warmup error: {ex.Message}");
            }
            finally
            {
                if (_warmingPlaylistSig == remoteSig) _warmingPlaylistSig = null;
                if (ReferenceEquals(_mediaWarmupCts, cts)) _mediaWarmupCts = null;
                cts.Dispose();
            }
        }

        /// <summary>Media URL приводится к активному POSM origin, включая временный HTTP fallback.</summary>
        private string RewriteMediaHost(string url)
        {
            try
            {
                if (_posmConfig == null) return url;
                var source = new Uri(url);
                var configuredOrigins = _posmConfig.GetBackendBaseUris();
                var activeOrigin = _epharm?.ActiveBackendBaseUri ?? configuredOrigins[0];
                var belongsToBackend = source.Host == "localhost" || source.Host == "127.0.0.1";
                foreach (var configuredOrigin in configuredOrigins)
                {
                    if (string.Equals(source.Host, configuredOrigin.Host, StringComparison.OrdinalIgnoreCase))
                    {
                        belongsToBackend = true;
                        break;
                    }
                }
                if (!belongsToBackend) return url;

                return new UriBuilder(source)
                {
                    Scheme = activeOrigin.Scheme,
                    Host = activeOrigin.Host,
                    Port = activeOrigin.IsDefaultPort ? -1 : activeOrigin.Port,
                }.Uri.AbsoluteUri;
            }
            catch
            {
                return url;
            }
        }
    }
}
