using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Reflection;
using System.Threading;
using System.Threading.Tasks;
using CustomerDisplay.Config;

namespace CustomerDisplay.Services;

/// <summary>Signed, resumable updates; verified backups and an explicit local UI readiness gate.</summary>
public sealed class AppUpdater : IDisposable
{
    private readonly EpharmConfig _cfg;
    private readonly EpharmApiClient _api;
    private readonly Action<string> _log;
    private readonly HttpClient _downloadHttp = new() { Timeout = TimeSpan.FromMinutes(30) };
    private int _busy;

    public AppUpdater(EpharmConfig cfg, EpharmApiClient api, Action<string> log)
    {
        _cfg = cfg; _api = api; _log = log;
    }

    public static Version CurrentVersion() => Assembly.GetEntryAssembly()?.GetName().Version ?? new Version(0, 0, 0);

    public async Task<bool> CheckAndApplyAsync(CancellationToken ct = default)
    {
        if (Interlocked.Exchange(ref _busy, 1) == 1) return false;
        try
        {
            var install = AppContext.BaseDirectory;
            if (File.Exists(UpdateTransaction.ActivePath(install))) return false;
            // Never apply against dotnet.exe/development host or another process image.
            if (!string.Equals(Path.GetFileName(Environment.ProcessPath), "CustomerDisplay.exe", StringComparison.OrdinalIgnoreCase))
                return false;
            var local = CurrentVersion();
            var info = await _api.GetAppVersionAsync("win-x64", _cfg.ResolveDeviceId(), local.ToString(), ct).ConfigureAwait(false);
            if (info == null || !info.Current || info.Platform != "win-x64" || string.IsNullOrWhiteSpace(info.Url) ||
                !Version.TryParse(info.Version.Trim().Split('-', '+')[0], out var remote) || remote <= local) return false;
            if (!UpdateManifestVerifier.Verify(info, _cfg.UpdateManifestPublicKeySpki))
            {
                _log("update: подпись манифеста не прошла проверку — обновление отклонено");
                return false;
            }
            if (info.Sha256.Length != 64 || !info.Sha256.All(Uri.IsHexDigit)) return false;
            if (UpdateTransaction.IsRejected(install, info.Sha256))
            {
                _log("update: запуск этого пакета был отменён с возвратом предыдущей версии; ожидается исправленный релиз");
                return false;
            }
            var url = RewriteHost(info.Url);
            if (!Uri.TryCreate(url, UriKind.Absolute, out var origin) ||
                !(origin.Scheme == Uri.UriSchemeHttps || origin.Scheme == Uri.UriSchemeHttp && origin.IsLoopback)) return false;
            var work = Path.Combine(Path.GetTempPath(), "epharm-update");
            UpdateTransaction.EnsurePhysical(work);
            Directory.CreateDirectory(work);
            UpdateTransaction.EnsurePhysical(work);
            var archive = Path.Combine(work, $"epharm-{remote}-{info.Sha256[..12]}.zip");
            UpdateTransaction.EnsurePhysical(archive);
            if (!File.Exists(archive) || !UpdateTransaction.Hash(archive).Equals(info.Sha256, StringComparison.OrdinalIgnoreCase))
            {
                if (File.Exists(archive))
                {
                    UpdateTransaction.EnsurePhysical(archive);
                    File.Delete(archive);
                }
                if (!await new UpdatePackageDownloader(_downloadHttp, _log).DownloadAsync(url, archive, ct).ConfigureAwait(false)) return false;
            }
            if (!UpdateTransaction.Hash(archive).Equals(info.Sha256, StringComparison.OrdinalIgnoreCase))
            {
                _log("update: SHA-256 не совпал; пакет отклонён");
                UpdateTransaction.EnsurePhysical(archive);
                File.Delete(archive);
                return false;
            }
            var plan = UpdateTransaction.Prepare(install, archive, info.Version, info.Sha256, UpdateStartup.ReadApplierScript(), candidate =>
            {
                var version = AssemblyName.GetAssemblyName(Path.Combine(candidate, "CustomerDisplay.dll")).Version;
                if (version == null || version.Major != remote.Major || version.Minor != remote.Minor ||
                    version.Build != remote.Build || Math.Max(version.Revision, 0) != Math.Max(remote.Revision, 0))
                    throw new InvalidDataException("Candidate assembly version does not match the signed manifest.");
            });
            try { UpdateStartup.LaunchApplier(plan, recovery: false); }
            catch
            {
                // No external writer started, so the untouched application can continue normally.
                File.Delete(UpdateTransaction.ActivePath(install));
                throw;
            }
            _log("update: кандидат и резервная копия проверены; запуск с проверкой готовности и откатом при сбое");
            System.Windows.Application.Current.Dispatcher.Invoke(() => System.Windows.Application.Current.Shutdown());
            return true;
        }
        catch (Exception ex)
        {
            _log($"update error (fail-safe, касса работает дальше): {ex.Message}");
            return false;
        }
        finally { Interlocked.Exchange(ref _busy, 0); }
    }

    private string RewriteHost(string url)
    {
        try
        {
            var host = new Uri(_cfg.BackendBaseUrl).Host;
            return host == "localhost" ? url : url.Replace("://localhost", "://" + host).Replace("://127.0.0.1", "://" + host);
        }
        catch { return url; }
    }

    public void Dispose() => _downloadHttp.Dispose();
}
