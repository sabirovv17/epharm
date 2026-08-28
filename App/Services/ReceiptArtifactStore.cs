using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services
{
    public interface IReceiptArtifactRenderer
    {
        void Render(SaleReport sale, string outputPath);
    }

    /// <summary>
    /// Хранилище только Epharm-копий чека. Никакие файлы/очереди печати Standard-N не трогаются.
    /// active содержит атомарный JSON-черновик до оплаты, pending — PNG+JSON до HTTP ACK backend.
    /// После ACK удаляется только соответствующая папка pending.
    /// </summary>
    public sealed class ReceiptArtifactStore
    {
        private const string PayloadFileName = "sale.json";
        private const string ImageFileName = "receipt.png";
        private readonly string _activeRoot;
        private readonly string _pendingRoot;
        private readonly string _quarantineRoot;
        private readonly IReceiptArtifactRenderer _renderer;
        private readonly Action<string>? _log;
        private readonly TimeSpan _activeRetention;
        private readonly object _gate = new();

        public ReceiptArtifactStore(
            string rootPath,
            IReceiptArtifactRenderer renderer,
            Action<string>? log = null,
            int activeRetentionDays = 2)
        {
            if (string.IsNullOrWhiteSpace(rootPath))
                throw new ArgumentException("Receipt capture path is empty.", nameof(rootPath));

            _renderer = renderer ?? throw new ArgumentNullException(nameof(renderer));
            _log = log;
            _activeRetention = TimeSpan.FromDays(Math.Clamp(activeRetentionDays, 1, 30));
            _activeRoot = Path.Combine(rootPath, "active");
            _pendingRoot = Path.Combine(rootPath, "pending");
            _quarantineRoot = Path.Combine(rootPath, "quarantine");
            Directory.CreateDirectory(_activeRoot);
            Directory.CreateDirectory(_pendingRoot);
            Directory.CreateDirectory(_quarantineRoot);
        }

        public void SaveDraft(SaleReport sale)
        {
            lock (_gate)
            {
                var dir = Path.Combine(_activeRoot, SafeId(sale.SaleId));
                Directory.CreateDirectory(dir);
                WriteJsonAtomic(Path.Combine(dir, PayloadFileName), sale);
            }
        }

        /// <summary>Создаёт финальный PNG+JSON и возвращает true, если PNG сформирован.</summary>
        public bool Complete(SaleReport sale)
        {
            lock (_gate)
            {
                var safeId = SafeId(sale.SaleId);
                var finalDir = Path.Combine(_pendingRoot, safeId);
                if (Directory.Exists(finalDir))
                {
                    DeleteDirectoryQuietly(Path.Combine(_activeRoot, safeId));
                    return File.Exists(Path.Combine(finalDir, ImageFileName));
                }

                var stagingDir = Path.Combine(_pendingRoot, $".{safeId}.tmp-{Guid.NewGuid():N}");
                Directory.CreateDirectory(stagingDir);
                var rendered = false;
                try
                {
                    WriteJsonAtomic(Path.Combine(stagingDir, PayloadFileName), sale);
                    try
                    {
                        _renderer.Render(sale, Path.Combine(stagingDir, ImageFileName));
                        rendered = true;
                    }
                    catch (Exception ex)
                    {
                        File.WriteAllText(Path.Combine(stagingDir, "render-error.txt"), ex.GetBaseException().Message);
                        _log?.Invoke($"PNG чека {sale.SaleId} пока не сформирован: {ex.GetBaseException().Message}");
                    }

                    Directory.Move(stagingDir, finalDir);
                    DeleteDirectoryQuietly(Path.Combine(_activeRoot, safeId));
                    return rendered;
                }
                catch
                {
                    DeleteDirectoryQuietly(stagingDir);
                    throw;
                }
            }
        }

        /// <summary>
        /// После перезапуска повторно ставит pending-продажи в идемпотентный outbox. Повреждённые
        /// данные не удаляются молча, а перемещаются в quarantine для диагностики.
        /// </summary>
        public int RecoverPending(OfflineOutbox outbox)
        {
            lock (_gate)
            {
                RecoverStagingDirectories();
                CleanupStaleActiveDrafts();
                var recovered = 0;
                foreach (var dir in Directory.EnumerateDirectories(_pendingRoot)
                             .Where(path => !Path.GetFileName(path).StartsWith(".", StringComparison.Ordinal)))
                {
                    try
                    {
                        var payloadPath = Path.Combine(dir, PayloadFileName);
                        var sale = JsonSerializer.Deserialize<SaleReport>(File.ReadAllText(payloadPath), EpharmJson.Options)
                                   ?? throw new InvalidDataException("sale.json is empty");
                        if (string.IsNullOrWhiteSpace(sale.SaleId))
                            throw new InvalidDataException("saleId is empty");

                        var imagePath = Path.Combine(dir, ImageFileName);
                        if (!File.Exists(imagePath))
                        {
                            try
                            {
                                _renderer.Render(sale, imagePath);
                                var errorPath = Path.Combine(dir, "render-error.txt");
                                if (File.Exists(errorPath)) File.Delete(errorPath);
                            }
                            catch (Exception ex)
                            {
                                _log?.Invoke($"Повторный рендер PNG {sale.SaleId} не удался: {ex.GetBaseException().Message}");
                            }
                        }

                        outbox.Enqueue(sale.SaleId, "sale", JsonSerializer.Serialize(sale, EpharmJson.Options));
                        recovered++;
                    }
                    catch (Exception ex)
                    {
                        Quarantine(dir, ex);
                    }
                }
                return recovered;
            }
        }

        public void DeletePending(string saleId)
        {
            lock (_gate)
            {
                var safeId = SafeId(saleId);
                var path = Path.Combine(_pendingRoot, safeId);
                if (Directory.Exists(path))
                {
                    Directory.Delete(path, recursive: true);
                    _log?.Invoke($"Локальная PNG-копия чека удалена после ACK backend: {saleId}");
                }
                DeleteDirectoryQuietly(Path.Combine(_activeRoot, safeId));
            }
        }

        public void DiscardDraft(string saleId)
        {
            lock (_gate)
            {
                DeleteDirectoryQuietly(Path.Combine(_activeRoot, SafeId(saleId)));
            }
        }

        private void Quarantine(string sourceDir, Exception error)
        {
            var name = Path.GetFileName(sourceDir);
            var target = Path.Combine(_quarantineRoot, $"{DateTime.UtcNow:yyyyMMddHHmmss}-{name}-{Guid.NewGuid():N}");
            try
            {
                Directory.Move(sourceDir, target);
                File.WriteAllText(Path.Combine(target, "recovery-error.txt"), error.GetBaseException().ToString());
            }
            catch
            {
                // Даже повреждённый артефакт не должен остановить обработку остальных чеков.
            }
            _log?.Invoke($"Повреждённая локальная копия чека изолирована: {name}: {error.GetBaseException().Message}");
        }

        private void RecoverStagingDirectories()
        {
            var threshold = DateTime.UtcNow.AddHours(-1);
            foreach (var dir in Directory.EnumerateDirectories(_pendingRoot, ".*.tmp-*"))
            {
                try
                {
                    var payloadPath = Path.Combine(dir, PayloadFileName);
                    if (File.Exists(payloadPath))
                    {
                        var sale = JsonSerializer.Deserialize<SaleReport>(File.ReadAllText(payloadPath), EpharmJson.Options)
                                   ?? throw new InvalidDataException("staging sale.json is empty");
                        var finalDir = Path.Combine(_pendingRoot, SafeId(sale.SaleId));
                        if (Directory.Exists(finalDir)) Directory.Delete(dir, true);
                        else Directory.Move(dir, finalDir);
                    }
                    else if (Directory.GetLastWriteTimeUtc(dir) < threshold)
                    {
                        Directory.Delete(dir, true);
                    }
                }
                catch (Exception ex)
                {
                    if (Directory.GetLastWriteTimeUtc(dir) < threshold) Quarantine(dir, ex);
                }
            }
        }

        private void CleanupStaleActiveDrafts()
        {
            var threshold = DateTime.UtcNow - _activeRetention;
            foreach (var dir in Directory.EnumerateDirectories(_activeRoot))
            {
                try
                {
                    if (Directory.GetLastWriteTimeUtc(dir) < threshold) Directory.Delete(dir, true);
                }
                catch { }
            }
        }

        private static void WriteJsonAtomic(string targetPath, SaleReport sale)
        {
            var tempPath = targetPath + ".tmp-" + Guid.NewGuid().ToString("N");
            try
            {
                File.WriteAllText(tempPath, JsonSerializer.Serialize(sale, EpharmJson.Options));
                File.Move(tempPath, targetPath, overwrite: true);
            }
            finally
            {
                if (File.Exists(tempPath)) File.Delete(tempPath);
            }
        }

        private static string SafeId(string id)
        {
            if (string.IsNullOrWhiteSpace(id)) throw new ArgumentException("saleId is empty", nameof(id));
            var safe = new string(id.Select(ch => char.IsLetterOrDigit(ch) || ch is '-' or '_' ? ch : '_').ToArray());
            if (safe.Length > 64) safe = safe[..64];
            return safe;
        }

        private static void DeleteDirectoryQuietly(string path)
        {
            try { if (Directory.Exists(path)) Directory.Delete(path, recursive: true); }
            catch { }
        }
    }
}
