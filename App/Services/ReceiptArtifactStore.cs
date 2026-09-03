using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text.Json;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services
{
    public enum FiscalReceiptCaptureStatus
    {
        WaitingForAuthoritativeSource,
        Stored,
        Rejected,
    }

    public sealed class FiscalReceiptCaptureResult
    {
        public FiscalReceiptCaptureStatus Status { get; init; }
        public string? Reason { get; init; }
        public string? StoredPath { get; init; }
    }

    public sealed class StoredFiscalReceiptMetadata
    {
        public int SchemaVersion { get; set; } = 1;
        public string StoredFileName { get; set; } = "";
        public string Sha256 { get; set; } = "";
        public string SourceManifestFileName { get; set; } = "";
        public DateTimeOffset StoredAt { get; set; }
        public FiscalReceiptManifest Manifest { get; set; } = new();
    }

    public sealed class FiscalReceiptRefreshResult
    {
        public int Captured { get; init; }
        public int Waiting { get; init; }
        public int Rejected { get; init; }
        public int Cleaned { get; init; }
    }

    /// <summary>
    /// Stores only an authoritative fiscal PDF/PNG accepted by IFiscalReceiptSource. POSM never
    /// reconstructs a fiscal-looking image from its cart. A local artifact is removed only after
    /// the structured sale was acknowledged and the configured inspection retention elapsed.
    /// </summary>
    public sealed class ReceiptArtifactStore
    {
        private const string PayloadFileName = "sale.json";
        private const string MetadataFileName = "fiscal-receipt.json";
        private const string StatusFileName = "fiscal-capture-status.json";
        private const string DeliveryFileName = "sale-delivered.json";
        private const string FiscalDeliveryFileName = "fiscal-metadata-delivered.json";
        private const string FiscalQueuedFileName = "fiscal-metadata-queued.json";
        private const string HandoffCleanedFileName = "fiscal-handoff-cleaned.json";

        private readonly string _activeRoot;
        private readonly string _pendingRoot;
        private readonly string _quarantineRoot;
        private readonly IFiscalReceiptSource _source;
        private readonly Action<string>? _log;
        private readonly TimeSpan _activeRetention;
        private readonly TimeSpan _completedRetention;
        private readonly Func<DateTimeOffset> _utcNow;
        private readonly object _gate = new();

        public ReceiptArtifactStore(
            string rootPath,
            IFiscalReceiptSource source,
            Action<string>? log = null,
            int activeRetentionDays = 2,
            int completedRetentionHours = 24,
            Func<DateTimeOffset>? utcNow = null)
        {
            if (string.IsNullOrWhiteSpace(rootPath))
                throw new ArgumentException("Receipt capture path is empty.", nameof(rootPath));

            _source = source ?? throw new ArgumentNullException(nameof(source));
            _log = log;
            _activeRetention = TimeSpan.FromDays(Math.Clamp(activeRetentionDays, 1, 30));
            _completedRetention = TimeSpan.FromHours(Math.Clamp(completedRetentionHours, 1, 168));
            _utcNow = utcNow ?? (() => DateTimeOffset.UtcNow);
            _activeRoot = Path.Combine(rootPath, "active");
            _pendingRoot = Path.Combine(rootPath, "pending");
            _quarantineRoot = Path.Combine(rootPath, "quarantine");
            Directory.CreateDirectory(_activeRoot);
            Directory.CreateDirectory(_pendingRoot);
            Directory.CreateDirectory(_quarantineRoot);
        }

        public void SaveDraft(SaleReport sale)
        {
            if (sale == null) throw new ArgumentNullException(nameof(sale));
            lock (_gate)
            {
                var dir = Path.Combine(_activeRoot, SafeId(sale.SaleId));
                Directory.CreateDirectory(dir);
                WriteJsonAtomic(Path.Combine(dir, PayloadFileName), sale);
            }
        }

        /// <summary>
        /// Finalizes the observed sale without touching the KKM. Exact artifact validation runs on
        /// the background refresh so payment/printing never waits on file hashing or an OFD source.
        /// </summary>
        public FiscalReceiptCaptureResult Complete(SaleReport sale)
        {
            if (sale == null) throw new ArgumentNullException(nameof(sale));
            lock (_gate)
            {
                var safeId = SafeId(sale.SaleId);
                var finalDir = Path.Combine(_pendingRoot, safeId);
                if (Directory.Exists(finalDir))
                {
                    var existing = ReadSale(finalDir);
                    RemoveLegacyReconstruction(finalDir, existing);
                    DeleteDirectoryQuietly(Path.Combine(_activeRoot, safeId));
                    return CurrentResult(finalDir);
                }

                var stagingDir = Path.Combine(_pendingRoot, $".{safeId}.tmp-{Guid.NewGuid():N}");
                Directory.CreateDirectory(stagingDir);
                try
                {
                    // No artifactFormat is claimed until an exact source passes validation.
                    sale.ArtifactFormat = null;
                    WriteJsonAtomic(Path.Combine(stagingDir, PayloadFileName), sale);
                    WriteStatusIfChanged(
                        stagingDir,
                        "waiting",
                        "authoritative fiscal PDF/PNG has not been validated yet");
                    Directory.Move(stagingDir, finalDir);
                    DeleteDirectoryQuietly(Path.Combine(_activeRoot, safeId));
                    return new FiscalReceiptCaptureResult
                    {
                        Status = FiscalReceiptCaptureStatus.WaitingForAuthoritativeSource,
                        Reason = "authoritative fiscal PDF/PNG has not been validated yet",
                    };
                }
                catch
                {
                    DeleteDirectoryQuietly(stagingDir);
                    throw;
                }
            }
        }

        /// <summary>
        /// Polls the exact-source inbox. Newly enriched sales are re-enqueued with the same stable
        /// id; backend idempotency updates fiscal metadata without duplicating the sale.
        /// </summary>
        public FiscalReceiptRefreshResult RefreshFiscalArtifacts(OfflineOutbox? outbox = null)
        {
            lock (_gate)
            {
                var captured = 0;
                var waiting = 0;
                var rejected = 0;
                foreach (var dir in PendingDirectories())
                {
                    try
                    {
                        if (TryReadStoredMetadata(dir, out var storedMetadata))
                        {
                            if (File.Exists(Path.Combine(dir, HandoffCleanedFileName)) &&
                                (File.Exists(Path.Combine(dir, FiscalQueuedFileName)) ||
                                 File.Exists(Path.Combine(dir, FiscalDeliveryFileName))))
                            {
                                continue;
                            }

                            var storedSale = ReadSale(dir);
                            VerifyStoredArtifact(dir, storedSale, storedMetadata!);
                            if (!SaleContainsFiscalMetadata(storedSale, storedMetadata!))
                            {
                                ApplyFiscalMetadata(
                                    storedSale,
                                    storedMetadata!.Manifest,
                                    storedMetadata.Sha256,
                                    FileFormat(storedMetadata.StoredFileName));
                                WriteJsonAtomic(Path.Combine(dir, PayloadFileName), storedSale);
                            }
                            CleanupAcceptedHandoff(dir, storedMetadata!);
                            TryEnqueueFiscalMetadata(outbox, storedSale);
                            continue;
                        }
                        var sale = ReadSale(dir);
                        RemoveLegacyReconstruction(dir, sale);
                        var result = TryAttachFiscalArtifact(dir, sale);
                        switch (result.Status)
                        {
                            case FiscalReceiptCaptureStatus.Stored:
                                captured++;
                                TryEnqueueFiscalMetadata(outbox, ReadSale(dir));
                                break;
                            case FiscalReceiptCaptureStatus.Rejected:
                                rejected++;
                                break;
                            default:
                                waiting++;
                                break;
                        }
                    }
                    catch (Exception ex)
                    {
                        Quarantine(dir, ex);
                    }
                }

                return new FiscalReceiptRefreshResult
                {
                    Captured = captured,
                    Waiting = waiting,
                    Rejected = rejected,
                    Cleaned = CleanupCompletedArtifactsLocked(),
                };
            }
        }

        /// <summary>
        /// Re-enqueues crash-surviving sales. A stored fiscal artifact is hash-checked before the
        /// directory is trusted; damaged local evidence is quarantined rather than silently sent.
        /// </summary>
        public int RecoverPending(OfflineOutbox outbox)
        {
            if (outbox == null) throw new ArgumentNullException(nameof(outbox));
            lock (_gate)
            {
                RecoverStagingDirectories();
                CleanupStaleActiveDrafts();
                var recovered = 0;
                foreach (var dir in PendingDirectories())
                {
                    try
                    {
                        var sale = ReadSale(dir);
                        RemoveLegacyReconstruction(dir, sale);
                        if (TryReadStoredMetadata(dir, out var metadata))
                        {
                            VerifyStoredArtifact(dir, sale, metadata!);
                            ApplyFiscalMetadata(sale, metadata!.Manifest, metadata.Sha256, FileFormat(metadata.StoredFileName));
                            WriteJsonAtomic(Path.Combine(dir, PayloadFileName), sale);
                            CleanupAcceptedHandoff(dir, metadata);
                            if (TryEnqueueFiscalMetadata(outbox, sale, force: true)) recovered++;
                        }
                        else if (!File.Exists(Path.Combine(dir, DeliveryFileName)))
                        {
                            if (TryEnqueueSale(outbox, sale)) recovered++;
                        }
                    }
                    catch (Exception ex)
                    {
                        Quarantine(dir, ex);
                    }
                }
                CleanupCompletedArtifactsLocked();
                return recovered;
            }
        }

        public void MarkSaleDelivered(string saleId)
        {
            lock (_gate)
            {
                var path = Path.Combine(_pendingRoot, SafeId(saleId));
                if (!Directory.Exists(path)) return;
                var marker = Path.Combine(path, DeliveryFileName);
                if (!File.Exists(marker))
                {
                    WriteJsonAtomic(marker, new DeliveryMarker { SaleId = saleId, DeliveredAt = _utcNow() });
                    _log?.Invoke($"Backend подтвердил продажу {saleId}; фискальный оригинал хранится до истечения retention");
                }
                CleanupCompletedArtifactsLocked();
            }
        }

        public void MarkFiscalMetadataDelivered(string saleId, string sha256)
        {
            lock (_gate)
            {
                var path = Path.Combine(_pendingRoot, SafeId(saleId));
                if (!Directory.Exists(path) || !TryReadStoredMetadata(path, out var metadata)) return;
                if (!string.Equals(metadata!.Sha256, sha256, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidDataException("backend ACK hash does not match the stored fiscal artifact");

                var marker = Path.Combine(path, FiscalDeliveryFileName);
                if (!File.Exists(marker))
                {
                    WriteJsonAtomic(marker, new DeliveryMarker
                    {
                        SaleId = saleId,
                        Sha256 = metadata.Sha256,
                        DeliveredAt = _utcNow(),
                    });
                    _log?.Invoke($"Backend подтвердил фискальные метаданные: sale={saleId}, sha256={metadata.Sha256}");
                }
                CleanupCompletedArtifactsLocked();
            }
        }

        public int CleanupCompletedArtifacts()
        {
            lock (_gate) return CleanupCompletedArtifactsLocked();
        }

        public void DiscardDraft(string saleId)
        {
            lock (_gate)
            {
                DeleteDirectoryQuietly(Path.Combine(_activeRoot, SafeId(saleId)));
            }
        }

        private FiscalReceiptCaptureResult TryAttachFiscalArtifact(string dir, SaleReport sale)
        {
            var lookup = _source.Find(sale);
            if (lookup.Status == FiscalReceiptLookupStatus.NotReady)
            {
                WriteStatusIfChanged(dir, "waiting", lookup.Reason);
                return new FiscalReceiptCaptureResult
                {
                    Status = FiscalReceiptCaptureStatus.WaitingForAuthoritativeSource,
                    Reason = lookup.Reason,
                };
            }
            if (lookup.Status == FiscalReceiptLookupStatus.Rejected)
            {
                if (WriteStatusIfChanged(dir, "rejected", lookup.Reason))
                    _log?.Invoke($"Фискальный источник отклонён для sale={sale.SaleId}: {lookup.Reason}");
                return new FiscalReceiptCaptureResult
                {
                    Status = FiscalReceiptCaptureStatus.Rejected,
                    Reason = lookup.Reason,
                };
            }

            var manifest = lookup.Manifest ?? throw new InvalidDataException("ready source has no manifest");
            var sourcePath = lookup.DocumentPath ?? throw new InvalidDataException("ready source has no document path");
            var format = lookup.Format ?? throw new InvalidDataException("ready source has no artifact format");
            var sha256 = lookup.Sha256 ?? throw new InvalidDataException("ready source has no artifact hash");
            var storedFileName = "fiscal-receipt." + format;
            var storedPath = Path.Combine(dir, storedFileName);
            var tempPath = Path.Combine(dir, $".{storedFileName}.tmp-{Guid.NewGuid():N}");

            try
            {
                CopyDurably(sourcePath, tempPath);
                var copiedSha = HashFile(tempPath);
                if (!string.Equals(copiedSha, sha256, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidDataException("fiscal artifact changed while it was copied");
                File.Move(tempPath, storedPath, overwrite: true);

                var metadata = new StoredFiscalReceiptMetadata
                {
                    StoredFileName = storedFileName,
                    Sha256 = copiedSha,
                    SourceManifestFileName = Path.GetFileName(lookup.ManifestPath ?? ""),
                    StoredAt = _utcNow(),
                    Manifest = manifest,
                };
                WriteJsonAtomic(Path.Combine(dir, MetadataFileName), metadata);
                ApplyFiscalMetadata(sale, manifest, copiedSha, format);
                WriteJsonAtomic(Path.Combine(dir, PayloadFileName), sale);
                WriteStatusIfChanged(dir, "stored", null);
                CleanupAcceptedHandoff(dir, metadata);
                _log?.Invoke(
                    $"Фискальный оригинал сохранён: sale={sale.SaleId}, doc={manifest.FiscalDocumentNumber}, " +
                    $"format={format}, sha256={copiedSha}");
                return new FiscalReceiptCaptureResult
                {
                    Status = FiscalReceiptCaptureStatus.Stored,
                    StoredPath = storedPath,
                };
            }
            finally
            {
                try { if (File.Exists(tempPath)) File.Delete(tempPath); }
                catch { }
            }
        }

        private static void ApplyFiscalMetadata(
            SaleReport sale,
            FiscalReceiptManifest manifest,
            string sha256,
            string format)
        {
            sale.ArtifactFormat = format;
            sale.ArtifactSha256 = sha256;
            sale.ArtifactSource = manifest.SourceSystem.Trim();
            sale.FiscalId = manifest.FiscalDocumentNumber.Trim();
            sale.FiscalSign = manifest.FiscalSign.Trim();
            sale.CashRegisterRegistrationNumber = manifest.CashRegisterRegistrationNumber.Trim();
            sale.OfdName = manifest.OfdName.Trim();
            if (!string.IsNullOrWhiteSpace(manifest.Shift)) sale.Shift = manifest.Shift.Trim();
            if (!string.IsNullOrWhiteSpace(manifest.Cashier)) sale.Cashier = manifest.Cashier.Trim();
        }

        private void RemoveLegacyReconstruction(string dir, SaleReport sale)
        {
            if (TryReadStoredMetadata(dir, out _)) return;
            var changed = false;
            var legacyPath = Path.Combine(dir, "receipt.png");
            if (File.Exists(legacyPath))
            {
                File.Delete(legacyPath);
                changed = true;
            }
            if (string.IsNullOrWhiteSpace(sale.ArtifactSha256) &&
                (!string.IsNullOrWhiteSpace(sale.ArtifactFormat) || !string.IsNullOrWhiteSpace(sale.ArtifactSource)))
            {
                sale.ArtifactFormat = null;
                sale.ArtifactSource = null;
                changed = true;
            }
            if (!changed) return;

            WriteJsonAtomic(Path.Combine(dir, PayloadFileName), sale);
            var marker = Path.Combine(dir, "legacy-non-fiscal-removed.txt");
            if (!File.Exists(marker))
                File.WriteAllText(marker, "Removed by exact-only fiscal capture migration.");
            _log?.Invoke($"Удалена устаревшая нефискальная реконструкция: sale={sale.SaleId}");
        }

        private FiscalReceiptCaptureResult CurrentResult(string dir)
        {
            if (TryReadStoredMetadata(dir, out var metadata))
            {
                return new FiscalReceiptCaptureResult
                {
                    Status = FiscalReceiptCaptureStatus.Stored,
                    StoredPath = Path.Combine(dir, metadata!.StoredFileName),
                };
            }

            var status = ReadStatus(dir);
            return new FiscalReceiptCaptureResult
            {
                Status = string.Equals(status?.Status, "rejected", StringComparison.OrdinalIgnoreCase)
                    ? FiscalReceiptCaptureStatus.Rejected
                    : FiscalReceiptCaptureStatus.WaitingForAuthoritativeSource,
                Reason = status?.Reason,
            };
        }

        private int CleanupCompletedArtifactsLocked()
        {
            var removed = 0;
            foreach (var dir in PendingDirectories())
            {
                try
                {
                    if (!TryReadStoredMetadata(dir, out _))
                    {
                        var saleDelivery = ReadDeliveryMarker(Path.Combine(dir, DeliveryFileName));
                        var status = ReadStatus(dir);
                        if (saleDelivery == null || status == null ||
                            _utcNow() - status.UpdatedAt < _activeRetention)
                        {
                            continue;
                        }

                        Directory.Delete(dir, recursive: true);
                        removed++;
                        _log?.Invoke(
                            $"Pending без фискального оригинала удалён после окна ожидания: {Path.GetFileName(dir)}");
                        continue;
                    }

                    var fiscalDelivery = ReadDeliveryMarker(Path.Combine(dir, FiscalDeliveryFileName));
                    if (fiscalDelivery == null || _utcNow() - fiscalDelivery.DeliveredAt < _completedRetention)
                        continue;
                    Directory.Delete(dir, recursive: true);
                    removed++;
                    _log?.Invoke($"Временная фискальная копия удалена по retention: {Path.GetFileName(dir)}");
                }
                catch (Exception ex)
                {
                    _log?.Invoke($"Не удалось очистить временный фискальный артефакт {Path.GetFileName(dir)}: {ex.Message}");
                }
            }
            return removed;
        }

        private static SaleReport ReadSale(string dir)
        {
            var payloadPath = Path.Combine(dir, PayloadFileName);
            var sale = JsonSerializer.Deserialize<SaleReport>(File.ReadAllText(payloadPath), EpharmJson.Options)
                       ?? throw new InvalidDataException("sale.json is empty");
            if (string.IsNullOrWhiteSpace(sale.SaleId)) throw new InvalidDataException("saleId is empty");
            return sale;
        }

        private static void EnqueueFiscalMetadata(OfflineOutbox outbox, SaleReport sale)
        {
            if (string.IsNullOrWhiteSpace(sale.ArtifactSha256))
                throw new InvalidDataException("cannot enqueue fiscal metadata without artifactSha256");
            var id = $"fiscal:{sale.SaleId}:{sale.ArtifactSha256[..16]}";
            outbox.Enqueue(id, "fiscal-sale", JsonSerializer.Serialize(sale, EpharmJson.Options));
        }

        private static bool TryReadStoredMetadata(string dir, out StoredFiscalReceiptMetadata? metadata)
        {
            metadata = null;
            var path = Path.Combine(dir, MetadataFileName);
            if (!File.Exists(path)) return false;
            metadata = JsonSerializer.Deserialize<StoredFiscalReceiptMetadata>(File.ReadAllText(path), EpharmJson.Options)
                       ?? throw new InvalidDataException("fiscal-receipt.json is empty");
            if (metadata.SchemaVersion != 1 || string.IsNullOrWhiteSpace(metadata.StoredFileName))
                throw new InvalidDataException("stored fiscal metadata is invalid");
            return true;
        }

        private static void VerifyStoredArtifact(
            string dir,
            SaleReport sale,
            StoredFiscalReceiptMetadata metadata)
        {
            var format = FileFormat(metadata.StoredFileName);
            if ((format != "pdf" && format != "png") ||
                !string.Equals(metadata.StoredFileName, "fiscal-receipt." + format, StringComparison.OrdinalIgnoreCase) ||
                !string.Equals(Path.GetFileName(metadata.StoredFileName), metadata.StoredFileName, StringComparison.Ordinal))
                throw new InvalidDataException("stored fiscal artifact path is invalid");
            if (metadata.Sha256.Length != 64 || !metadata.Sha256.All(Uri.IsHexDigit))
                throw new InvalidDataException("stored fiscal artifact SHA-256 is invalid");
            if (!string.Equals(metadata.Manifest.Sha256?.Trim(), metadata.Sha256, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("stored manifest SHA-256 does not match stored metadata");
            if (!string.Equals(metadata.Manifest.PharmacyId?.Trim(), sale.PharmacyId?.Trim(), StringComparison.Ordinal) ||
                metadata.Manifest.SourceDocumentId != sale.SourceDocumentId ||
                metadata.Manifest.TotalAmount != sale.TotalAmount ||
                (!string.IsNullOrWhiteSpace(metadata.Manifest.SaleId) &&
                 !string.Equals(metadata.Manifest.SaleId.Trim(), sale.SaleId, StringComparison.Ordinal)))
            {
                throw new InvalidDataException("stored fiscal metadata no longer matches the sale identity");
            }
            if (string.IsNullOrWhiteSpace(metadata.Manifest.SourceSystem) ||
                string.IsNullOrWhiteSpace(metadata.Manifest.FiscalDocumentNumber) ||
                string.IsNullOrWhiteSpace(metadata.Manifest.FiscalSign) ||
                string.IsNullOrWhiteSpace(metadata.Manifest.CashRegisterRegistrationNumber) ||
                string.IsNullOrWhiteSpace(metadata.Manifest.OfdName))
            {
                throw new InvalidDataException("stored fiscal provenance is incomplete");
            }

            var path = Path.Combine(dir, metadata.StoredFileName);
            if (!File.Exists(path)) throw new InvalidDataException("stored fiscal artifact is missing");
            using (var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                if (!FiscalReceiptInboxSource.HasValidContainer(stream, format))
                    throw new InvalidDataException("stored fiscal artifact container is invalid");
            }
            if (!string.Equals(HashFile(path), metadata.Sha256, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("stored fiscal artifact SHA-256 is invalid");
        }

        private bool TryEnqueueFiscalMetadata(OfflineOutbox? outbox, SaleReport sale, bool force = false)
        {
            var dir = Path.Combine(_pendingRoot, SafeId(sale.SaleId));
            if (outbox == null || File.Exists(Path.Combine(dir, FiscalDeliveryFileName)) ||
                (!force && File.Exists(Path.Combine(dir, FiscalQueuedFileName))))
                return false;
            try
            {
                EnqueueFiscalMetadata(outbox, sale);
                WriteJsonAtomic(Path.Combine(dir, FiscalQueuedFileName), new DeliveryMarker
                {
                    SaleId = sale.SaleId,
                    Sha256 = sale.ArtifactSha256,
                    DeliveredAt = _utcNow(),
                });
                return true;
            }
            catch (Exception ex)
            {
                _log?.Invoke($"Фискальные метаданные sale={sale.SaleId} пока не поставлены в outbox: " +
                             ex.GetBaseException().Message);
                return false;
            }
        }

        private bool TryEnqueueSale(OfflineOutbox outbox, SaleReport sale)
        {
            try
            {
                outbox.Enqueue(sale.SaleId, "sale", JsonSerializer.Serialize(sale, EpharmJson.Options));
                return true;
            }
            catch (Exception ex)
            {
                _log?.Invoke($"Продажа sale={sale.SaleId} пока не восстановлена в outbox: " +
                             ex.GetBaseException().Message);
                return false;
            }
        }

        private void CleanupAcceptedHandoff(string dir, StoredFiscalReceiptMetadata metadata)
        {
            var marker = Path.Combine(dir, HandoffCleanedFileName);
            if (File.Exists(marker)) return;
            try
            {
                _source.CleanupAccepted(metadata.Manifest, metadata.SourceManifestFileName);
                WriteJsonAtomic(marker, new DeliveryMarker
                {
                    SaleId = metadata.Manifest.SaleId ?? "",
                    Sha256 = metadata.Sha256,
                    DeliveredAt = _utcNow(),
                });
            }
            catch (Exception ex)
            {
                _log?.Invoke("Принятый handoff фискального чека пока не очищен: " +
                             ex.GetBaseException().Message);
            }
        }

        private static bool SaleContainsFiscalMetadata(
            SaleReport sale,
            StoredFiscalReceiptMetadata metadata)
        {
            var manifest = metadata.Manifest;
            return string.Equals(sale.ArtifactFormat, FileFormat(metadata.StoredFileName), StringComparison.OrdinalIgnoreCase) &&
                   string.Equals(sale.ArtifactSha256, metadata.Sha256, StringComparison.OrdinalIgnoreCase) &&
                   string.Equals(sale.ArtifactSource, manifest.SourceSystem?.Trim(), StringComparison.OrdinalIgnoreCase) &&
                   string.Equals(sale.FiscalId, manifest.FiscalDocumentNumber?.Trim(), StringComparison.Ordinal) &&
                   string.Equals(sale.FiscalSign, manifest.FiscalSign?.Trim(), StringComparison.Ordinal) &&
                   string.Equals(
                       sale.CashRegisterRegistrationNumber,
                       manifest.CashRegisterRegistrationNumber?.Trim(),
                       StringComparison.Ordinal) &&
                   string.Equals(sale.OfdName, manifest.OfdName?.Trim(), StringComparison.Ordinal);
        }

        private static DeliveryMarker? ReadDeliveryMarker(string path)
        {
            if (!File.Exists(path)) return null;
            try
            {
                return JsonSerializer.Deserialize<DeliveryMarker>(File.ReadAllText(path), EpharmJson.Options);
            }
            catch
            {
                return null;
            }
        }

        private void Quarantine(string sourceDir, Exception error)
        {
            var name = Path.GetFileName(sourceDir);
            var target = Path.Combine(_quarantineRoot, $"{_utcNow():yyyyMMddHHmmss}-{name}-{Guid.NewGuid():N}");
            try
            {
                Directory.Move(sourceDir, target);
                File.WriteAllText(Path.Combine(target, "recovery-error.txt"), error.GetBaseException().ToString());
            }
            catch
            {
                // A damaged artifact must not block the cashier or processing of other sales.
            }
            _log?.Invoke($"Повреждённый фискальный артефакт изолирован: {name}: {error.GetBaseException().Message}");
        }

        private void RecoverStagingDirectories()
        {
            var threshold = _utcNow().UtcDateTime.AddHours(-1);
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
            var threshold = _utcNow().UtcDateTime - _activeRetention;
            foreach (var dir in Directory.EnumerateDirectories(_activeRoot))
            {
                try
                {
                    if (Directory.GetLastWriteTimeUtc(dir) < threshold) Directory.Delete(dir, true);
                }
                catch { }
            }
        }

        private IEnumerable<string> PendingDirectories() =>
            Directory.EnumerateDirectories(_pendingRoot)
                .Where(path => !Path.GetFileName(path).StartsWith(".", StringComparison.Ordinal));

        private bool WriteStatusIfChanged(string dir, string status, string? reason)
        {
            var current = ReadStatus(dir);
            if (string.Equals(current?.Status, status, StringComparison.OrdinalIgnoreCase) &&
                string.Equals(current?.Reason, reason, StringComparison.Ordinal))
            {
                return false;
            }

            WriteJsonAtomic(
                Path.Combine(dir, StatusFileName),
                new FiscalCaptureStatusFile { Status = status, Reason = reason, UpdatedAt = _utcNow() });
            return true;
        }

        private static FiscalCaptureStatusFile? ReadStatus(string dir)
        {
            var path = Path.Combine(dir, StatusFileName);
            if (!File.Exists(path)) return null;
            try
            {
                return JsonSerializer.Deserialize<FiscalCaptureStatusFile>(File.ReadAllText(path), EpharmJson.Options);
            }
            catch
            {
                return null;
            }
        }

        private static void CopyDurably(string sourcePath, string targetPath)
        {
            using var source = new FileStream(sourcePath, FileMode.Open, FileAccess.Read, FileShare.Read);
            using var target = new FileStream(targetPath, FileMode.CreateNew, FileAccess.Write, FileShare.None);
            source.CopyTo(target);
            target.Flush(flushToDisk: true);
        }

        private static string HashFile(string path)
        {
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
        }

        private static string FileFormat(string fileName) =>
            Path.GetExtension(fileName).TrimStart('.').ToLowerInvariant();

        private static void WriteJsonAtomic<T>(string targetPath, T value)
        {
            var tempPath = targetPath + ".tmp-" + Guid.NewGuid().ToString("N");
            try
            {
                File.WriteAllText(tempPath, JsonSerializer.Serialize(value, EpharmJson.Options));
                File.Move(tempPath, targetPath, overwrite: true);
            }
            finally
            {
                try { if (File.Exists(tempPath)) File.Delete(tempPath); }
                catch { }
            }
        }

        private static string SafeId(string id)
        {
            if (string.IsNullOrWhiteSpace(id)) throw new ArgumentException("saleId is empty", nameof(id));
            var safe = new string(id.Select(ch => char.IsLetterOrDigit(ch) || ch is '-' or '_' ? ch : '_').ToArray());
            return safe.Length <= 64 ? safe : safe[..64];
        }

        private static void DeleteDirectoryQuietly(string path)
        {
            try { if (Directory.Exists(path)) Directory.Delete(path, recursive: true); }
            catch { }
        }

        private sealed class FiscalCaptureStatusFile
        {
            public string Status { get; set; } = "";
            public string? Reason { get; set; }
            public DateTimeOffset UpdatedAt { get; set; }
        }

        private sealed class DeliveryMarker
        {
            public string SaleId { get; set; } = "";
            public string? Sha256 { get; set; }
            public DateTimeOffset DeliveredAt { get; set; }
        }
    }
}
