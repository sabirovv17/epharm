using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net.Http;
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
            _downloadHttp = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
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
                var info = await _api.GetAppVersionAsync("win-x64", ct).ConfigureAwait(false);
                if (info == null || !info.Current || string.IsNullOrWhiteSpace(info.Url))
                    return false;

                if (!TryParse(info.Version, out var remote))
                {
                    _log($"update: не распарсить версию релиза '{info.Version}' — пропуск");
                    return false;
                }
                var local = CurrentVersion();
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
                if (!await DownloadAsync(downloadUrl, zipPath, ct).ConfigureAwait(false))
                    return false;

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

        private async Task<bool> DownloadAsync(string url, string destPath, CancellationToken ct)
        {
            try
            {
                using var resp = await _downloadHttp
                    .GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
                if (!resp.IsSuccessStatusCode)
                {
                    _log($"update: скачивание {url} → HTTP {(int)resp.StatusCode}");
                    return false;
                }
                await using var src = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
                await using var dst = File.Create(destPath);
                await src.CopyToAsync(dst, ct).ConfigureAwait(false);
                return true;
            }
            catch (Exception ex)
            {
                _log($"update: ошибка скачивания: {ex.Message}");
                return false;
            }
        }

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
rem epharm POSM auto-update applier — ждём выхода приложения и копируем файлы
set PID={pid}
:wait
tasklist /FI ""PID eq %PID%"" 2>nul | find ""%PID%"" >nul
if not errorlevel 1 (
  timeout /t 1 /nobreak >nul
  goto wait
)
robocopy ""{staging}"" ""{installDir}"" /E /IS /IT /NFL /NDL /NJH /NJS /NP >nul
start """" ""{exePath}""
rem самоудаление скрипта
(goto) 2>nul & del ""%~f0""
";
            File.WriteAllText(cmdPath, script);

            Process.Start(new ProcessStartInfo
            {
                FileName = "cmd.exe",
                Arguments = $"/c \"{cmdPath}\"",
                UseShellExecute = true,
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
