using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Reflection;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using CustomerDisplay.Config;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Авто-обновление POSM-клиента (Windows). Периодически спрашивает у backend текущий релиз;
    /// если он новее установленной версии — скачивает zip, проверяет sha256, распаковывает и
    /// применяет через внешний скрипт (apply-update.cmd): дожидается выхода приложения, копирует
    /// файлы поверх установки и перезапускает. Так не нужно вручную пересобирать кассу.
    ///
    /// ВСЁ fail-safe: любая ошибка (сеть, контрольная сумма, распаковка) логируется и НЕ роняет
    /// кассу — просто обновление не произойдёт в этот раз.
    ///
    /// ⚠️ Рассчитан на ОПУБЛИКОВАННУЮ сборку (dotnet publish → папка exe+dll, запуск самого exe),
    /// а не на `dotnet run` (там процесс — dotnet.exe, перезапуск самого приложения не сработает).
    /// </summary>
    public sealed class AppUpdater : IDisposable
    {
        private readonly EpharmConfig _cfg;
        private readonly EpharmApiClient _api;
        private readonly Action<string> _log;
        private readonly HttpClient _downloadHttp;
        private int _busy; // защита от параллельных проверок

        public AppUpdater(EpharmConfig cfg, EpharmApiClient api, Action<string> log)
        {
            _cfg = cfg;
            _api = api;
            _log = log;
            // Отдельный HttpClient с большим таймаутом — у основного клиента таймаут ~700мс
            // (под рекомендации), для скачивания zip это мало.
            _downloadHttp = new HttpClient { Timeout = TimeSpan.FromMinutes(30) };
            _downloadHttp.DefaultRequestHeaders.Add("X-Posm-Key", cfg.DeviceKey);
        }

        /// <summary>Установленная версия (из сборки). Сравнивается с релизом из админки.</summary>
        public static Version CurrentVersion()
            => Assembly.GetEntryAssembly()?.GetName().Version ?? new Version(0, 0, 0);

        /// <summary>
        /// Проверить и при необходимости применить обновление. Возвращает true, если обновление
        /// запущено (приложение сейчас завершится для подмены файлов).
        /// </summary>
        public async Task<bool> CheckAndApplyAsync(CancellationToken ct = default)
        {
            if (Interlocked.Exchange(ref _busy, 1) == 1) return false;
            try
            {
                var local = CurrentVersion();
                var info = await _api.GetAppVersionAsync(
                    platform: "win-x64",
                    deviceId: Environment.MachineName,
                    currentVersion: local.ToString(),
                    ct: ct).ConfigureAwait(false);
                if (info == null || !info.Current || string.IsNullOrWhiteSpace(info.Url))
                    return false;

                if (!TryParse(info.Version, out var remote))
                {
                    _log($"update: не распарсить версию релиза '{info.Version}' — пропуск");
                    return false;
                }
                if (remote <= local)
                {
                    return false; // уже актуально
                }

                _log($"update: доступна версия {remote} (установлена {local}) — качаю {info.Url}");
                var workDir = Path.Combine(Path.GetTempPath(), "epharm-update");
                Directory.CreateDirectory(workDir);
                var zipPath = Path.Combine(workDir, $"epharm-{remote}.zip");

                var downloadUrl = RewriteHost(info.Url);
                // Требуем https (http разрешён только локально для dev) — иначе zip можно
                // подменить по пути (MITM), а ниже мы его распаковываем и выполняем.
                if (!IsHttpsOrLocal(downloadUrl))
                {
                    _log($"update: небезопасный (не https) URL обновления отклонён: {downloadUrl}");
                    return false;
                }
                if (File.Exists(zipPath) && VerifySha256(zipPath, info.Sha256))
                {
                    _log("update: использую уже скачанный и проверенный пакет");
                }
                else
                {
                    SafeDelete(zipPath);
                    if (!await DownloadAsync(downloadUrl, zipPath, ct).ConfigureAwait(false))
                        return false;
                }

                // sha256 ОБЯЗАТЕЛЕН: пустой ИЛИ несовпавший хеш → отказ. Без него подменённый
                // или повреждённый zip распакуется и выполнится на кассе (RCE).
                if (string.IsNullOrWhiteSpace(info.Sha256) || !VerifySha256(zipPath, info.Sha256))
                {
                    _log("update: sha256 отсутствует или не совпал — обновление отклонено (возможна порча/подмена)");
                    SafeDelete(zipPath);
                    return false;
                }

                var staging = Path.Combine(workDir, $"staging-{remote}");
                if (Directory.Exists(staging)) Directory.Delete(staging, recursive: true);
                ZipFile.ExtractToDirectory(zipPath, staging);

                // Конфигурация принадлежит конкретной аптеке и никогда не должна приходить
                // из общего update ZIP. Это также страхует старые ошибочно собранные релизы,
                // в которые мог попасть posm.json с чужими PharmacyId/DeviceKey.
                SafeDelete(Path.Combine(staging, "posm.json"));
                if (!File.Exists(Path.Combine(staging, "CustomerDisplay.exe")))
                {
                    _log("update: в архиве нет CustomerDisplay.exe — обновление отклонено");
                    Directory.Delete(staging, recursive: true);
                    SafeDelete(zipPath);
                    return false;
                }
                _log($"update: распакован в {staging}");

                LaunchApplyAndExit(staging);
                return true;
            }
            catch (Exception ex)
            {
                _log($"update error (fail-safe, касса работает дальше): {ex.Message}");
                return false;
            }
            finally
            {
                Interlocked.Exchange(ref _busy, 0);
            }
        }

        /// <summary>
        /// Скачивает релиз с докачкой. Незавершённый .part сохраняется между проверками и после
        /// обрыва интернета, поэтому касса не начинает большой пакет с нуля.
        /// </summary>
        private async Task<bool> DownloadAsync(string url, string destPath, CancellationToken ct)
        {
            var partPath = destPath + ".part";
            const int maxAttempts = 4;

            for (var attempt = 1; attempt <= maxAttempts; attempt++)
            {
                try
                {
                    ct.ThrowIfCancellationRequested();
                    var existingLength = File.Exists(partPath) ? new FileInfo(partPath).Length : 0L;
                    using var request = new HttpRequestMessage(HttpMethod.Get, url);
                    if (existingLength > 0)
                        request.Headers.Range = new RangeHeaderValue(existingLength, null);

                    using var resp = await _downloadHttp.SendAsync(
                        request,
                        HttpCompletionOption.ResponseHeadersRead,
                        ct).ConfigureAwait(false);

                    if (resp.StatusCode == HttpStatusCode.RequestedRangeNotSatisfiable &&
                        existingLength > 0 &&
                        resp.Content.Headers.ContentRange?.Length == existingLength)
                    {
                        File.Move(partPath, destPath, overwrite: true);
                        return true;
                    }

                    if (!resp.IsSuccessStatusCode)
                    {
                        _log($"update: скачивание → HTTP {(int)resp.StatusCode}, попытка {attempt}/{maxAttempts}");
                        await RetryDelayAsync(attempt, maxAttempts, ct).ConfigureAwait(false);
                        continue;
                    }

                    var append = existingLength > 0 && resp.StatusCode == HttpStatusCode.PartialContent;
                    if (existingLength > 0 && !append)
                    {
                        _log("update: сервер не поддержал Range — начинаю пакет заново");
                        existingLength = 0;
                    }

                    var expectedLength = resp.Content.Headers.ContentRange?.Length
                        ?? (resp.Content.Headers.ContentLength is long contentLength
                            ? existingLength + contentLength
                            : (long?)null);

                    await using (var src = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false))
                    await using (var dst = new FileStream(
                        partPath,
                        append ? FileMode.Append : FileMode.Create,
                        FileAccess.Write,
                        FileShare.None,
                        bufferSize: 128 * 1024,
                        useAsync: true))
                    {
                        await src.CopyToAsync(dst, 128 * 1024, ct).ConfigureAwait(false);
                        await dst.FlushAsync(ct).ConfigureAwait(false);
                    }

                    var downloadedLength = new FileInfo(partPath).Length;
                    if (expectedLength.HasValue && downloadedLength != expectedLength.Value)
                    {
                        _log(
                            $"update: получено {downloadedLength} из {expectedLength.Value} байт, " +
                            $"повторяю попытку {attempt}/{maxAttempts}");
                        await RetryDelayAsync(attempt, maxAttempts, ct).ConfigureAwait(false);
                        continue;
                    }

                    File.Move(partPath, destPath, overwrite: true);
                    _log($"update: пакет скачан полностью ({downloadedLength / 1024 / 1024.0:F1} МБ)");
                    return true;
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested)
                {
                    throw;
                }
                catch (Exception ex)
                {
                    _log($"update: ошибка скачивания ({attempt}/{maxAttempts}): {ex.Message}");
                    await RetryDelayAsync(attempt, maxAttempts, ct).ConfigureAwait(false);
                }
            }

            _log("update: пакет пока не скачан; частичный файл сохранён для следующей проверки");
            return false;
        }

        private static Task RetryDelayAsync(int attempt, int maxAttempts, CancellationToken ct)
            => attempt >= maxAttempts
                ? Task.CompletedTask
                : Task.Delay(TimeSpan.FromSeconds(Math.Min(15, attempt * 3)), ct);

        private static bool VerifySha256(string path, string expectedHex)
        {
            using var fs = File.OpenRead(path);
            var hash = SHA256.HashData(fs);
            var hex = Convert.ToHexString(hash);
            return string.Equals(hex, expectedHex.Trim(), StringComparison.OrdinalIgnoreCase);
        }

        /// <summary>
        /// https обязателен для скачивания обновления; http допускается только для
        /// локального dev (localhost / 127.0.0.1). Защита от MITM-подмены пакета.
        /// </summary>
        private static bool IsHttpsOrLocal(string url)
        {
            try
            {
                var u = new Uri(url);
                if (u.Scheme == Uri.UriSchemeHttps) return true;
                return u.Host == "localhost" || u.Host == "127.0.0.1";
            }
            catch { return false; }
        }

        /// <summary>
        /// Пишет apply-update.cmd и запускает его отделённым процессом, затем гасит приложение.
        /// Скрипт ждёт выхода нашего PID, копирует staging поверх установки и перезапускает exe.
        /// </summary>
        private void LaunchApplyAndExit(string staging)
        {
            var exePath = Environment.ProcessPath ?? Process.GetCurrentProcess().MainModule!.FileName;
            var installDir = AppContext.BaseDirectory.TrimEnd('\\');
            var pid = Environment.ProcessId;
            var cmdPath = Path.Combine(Path.GetTempPath(), "epharm-update", "apply-update.cmd");

            var script =
$@"@echo off
setlocal
rem epharm POSM auto-update applier — ждём выхода приложения и копируем файлы
if not exist ""C:\Epharm"" mkdir ""C:\Epharm""
set ""LOG=C:\Epharm\update.log""
echo [%date% %time%] apply start, source={staging}>>""%LOG%""
set PID={pid}
:wait
tasklist /FI ""PID eq %PID%"" 2>nul | find ""%PID%"" >nul
if not errorlevel 1 (
  timeout /t 1 /nobreak >nul
  goto wait
)
set ATTEMPT=0
:copy
set /a ATTEMPT+=1
robocopy ""{staging}"" ""{installDir}"" /E /IS /IT /R:5 /W:2 /NFL /NDL /NJH /NJS /NP >>""%LOG%"" 2>&1
if %ERRORLEVEL% LEQ 7 goto copied
if %ATTEMPT% GEQ 5 goto failed
timeout /t 3 /nobreak >nul
goto copy
:copied
echo [%date% %time%] apply success>>""%LOG%""
start """" ""{exePath}""
rem самоудаление скрипта
(goto) 2>nul & del ""%~f0""
:failed
echo [%date% %time%] apply failed after %ATTEMPT% attempts>>""%LOG%""
start """" ""{exePath}""
exit /b 1
";
            File.WriteAllText(cmdPath, script);

            Process.Start(new ProcessStartInfo
            {
                FileName = "cmd.exe",
                Arguments = $"/c \"{cmdPath}\"",
                WorkingDirectory = installDir,
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
            });

            _log($"update: применяю обновление, перезапуск через apply-update.cmd. Закрываю приложение.");
            System.Windows.Application.Current.Dispatcher.Invoke(() =>
                System.Windows.Application.Current.Shutdown());
        }

        /// <summary>localhost/127.0.0.1 в URL → хост backend (как RewriteMediaHost для слайдов).</summary>
        private string RewriteHost(string url)
        {
            try
            {
                var host = new Uri(_cfg.BackendBaseUrl).Host;
                if (host == "localhost") return url;
                return url.Replace("://localhost", "://" + host).Replace("://127.0.0.1", "://" + host);
            }
            catch { return url; }
        }

        private static bool TryParse(string v, out Version version)
        {
            // допускаем "1.2.3" и "1.2.3.4"; обрезаем возможный суффикс ("1.2.3-rc1")
            var core = v.Trim().Split('-', '+')[0];
            return Version.TryParse(core, out version!);
        }

        private void SafeDelete(string path)
        {
            try { if (File.Exists(path)) File.Delete(path); } catch { /* ignore */ }
        }

        public void Dispose() => _downloadHttp.Dispose();
    }
}
