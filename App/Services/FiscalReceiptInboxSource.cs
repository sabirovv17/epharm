using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Sidecar written atomically by an approved read-only KKM/OFD adapter after fiscalization.
    /// The document itself must be the original PDF/PNG returned by that source; POSM never
    /// renders or reconstructs it from cart data.
    /// </summary>
    public sealed class FiscalReceiptManifest
    {
        public int SchemaVersion { get; set; } = 1;
        public string SourceSystem { get; set; } = "";
        public string PharmacyId { get; set; } = "";
        public string? SaleId { get; set; }
        public long? SourceDocumentId { get; set; }
        public string FiscalDocumentNumber { get; set; } = "";
        public string FiscalSign { get; set; } = "";
        public string CashRegisterRegistrationNumber { get; set; } = "";
        public string OfdName { get; set; } = "";
        public string? Shift { get; set; }
        public string? Cashier { get; set; }
        public DateTimeOffset PrintedAt { get; set; }
        public long TotalAmount { get; set; }
        public string DocumentFile { get; set; } = "";
        public string Sha256 { get; set; } = "";
    }

    public enum FiscalReceiptLookupStatus
    {
        NotReady,
        Ready,
        Rejected,
    }

    public sealed class FiscalReceiptLookupResult
    {
        private FiscalReceiptLookupResult(
            FiscalReceiptLookupStatus status,
            string? reason,
            FiscalReceiptManifest? manifest,
            string? manifestPath,
            string? documentPath,
            string? format,
            string? sha256)
        {
            Status = status;
            Reason = reason;
            Manifest = manifest;
            ManifestPath = manifestPath;
            DocumentPath = documentPath;
            Format = format;
            Sha256 = sha256;
        }

        public FiscalReceiptLookupStatus Status { get; }
        public string? Reason { get; }
        public FiscalReceiptManifest? Manifest { get; }
        public string? ManifestPath { get; }
        public string? DocumentPath { get; }
        public string? Format { get; }
        public string? Sha256 { get; }

        public static FiscalReceiptLookupResult NotReady(string? reason = null) =>
            new(FiscalReceiptLookupStatus.NotReady, reason, null, null, null, null, null);

        public static FiscalReceiptLookupResult Rejected(string reason, string? manifestPath = null) =>
            new(FiscalReceiptLookupStatus.Rejected, reason, null, manifestPath, null, null, null);

        public static FiscalReceiptLookupResult Ready(
            FiscalReceiptManifest manifest,
            string manifestPath,
            string documentPath,
            string format,
            string sha256) =>
            new(FiscalReceiptLookupStatus.Ready, null, manifest, manifestPath, documentPath, format, sha256);
    }

    public interface IFiscalReceiptSource
    {
        FiscalReceiptLookupResult Find(SaleReport sale);
        void CleanupAccepted(FiscalReceiptManifest manifest, string sourceManifestFileName);
    }

    /// <summary>
    /// Exact-only filesystem boundary for a vendor KKM SDK or OFD adapter. A producer writes the
    /// PDF/PNG first and publishes the *.fiscal.json sidecar last using an atomic rename.
    /// </summary>
    public sealed class FiscalReceiptInboxSource : IFiscalReceiptSource
    {
        private static readonly byte[] PngSignature = { 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a };
        private static readonly byte[] PngIend = { 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82 };

        private readonly string _inboxRoot;
        private readonly HashSet<string> _trustedSources;
        private readonly TimeSpan _maxClockSkew;
        private readonly long _maxArtifactBytes;

        public FiscalReceiptInboxSource(
            string inboxRoot,
            IEnumerable<string> trustedSources,
            int maxClockSkewSec = 900,
            int maxArtifactMb = 10)
        {
            if (string.IsNullOrWhiteSpace(inboxRoot))
                throw new ArgumentException("Fiscal receipt inbox path is empty.", nameof(inboxRoot));

            _inboxRoot = Path.GetFullPath(inboxRoot);
            _trustedSources = new HashSet<string>(
                (trustedSources ?? Array.Empty<string>())
                    .Where(value => !string.IsNullOrWhiteSpace(value))
                    .Select(value => value.Trim()),
                StringComparer.OrdinalIgnoreCase);
            if (_trustedSources.Count == 0)
                throw new ArgumentException("At least one trusted fiscal source is required.", nameof(trustedSources));

            _maxClockSkew = TimeSpan.FromSeconds(Math.Clamp(maxClockSkewSec, 30, 3600));
            _maxArtifactBytes = Math.Clamp(maxArtifactMb, 1, 50) * 1024L * 1024L;
            Directory.CreateDirectory(_inboxRoot);
        }

        public FiscalReceiptLookupResult Find(SaleReport sale)
        {
            if (sale == null) throw new ArgumentNullException(nameof(sale));

            FiscalReceiptLookupResult? rejection = null;
            foreach (var manifestPath in CandidateManifestPaths(sale))
            {
                if (!File.Exists(manifestPath)) continue;
                var result = ValidateCandidate(sale, manifestPath);
                if (result.Status == FiscalReceiptLookupStatus.Ready) return result;
                if (result.Status == FiscalReceiptLookupStatus.Rejected) rejection = result;
            }

            return rejection ?? FiscalReceiptLookupResult.NotReady("authoritative fiscal sidecar has not appeared");
        }

        /// <summary>
        /// Removes only the dedicated inbox handoff after ReceiptArtifactStore has durably copied
        /// and re-hashed it. The manifest is removed first so a crash cannot expose an orphaned
        /// ready signal to another lookup. Failures are retryable from stored metadata.
        /// </summary>
        public void CleanupAccepted(FiscalReceiptManifest manifest, string sourceManifestFileName)
        {
            if (manifest == null) throw new ArgumentNullException(nameof(manifest));
            var manifestPath = HandoffPath(sourceManifestFileName, "source manifest");
            var documentPath = HandoffPath(manifest.DocumentFile, "source document");

            if (File.Exists(manifestPath)) File.Delete(manifestPath);
            if (File.Exists(documentPath)) File.Delete(documentPath);
        }

        private IEnumerable<string> CandidateManifestPaths(SaleReport sale)
        {
            yield return Path.Combine(_inboxRoot, SafeToken(sale.SaleId) + ".fiscal.json");
            if (sale.SourceDocumentId.HasValue)
            {
                yield return Path.Combine(_inboxRoot, $"doc-{sale.SourceDocumentId.Value}.fiscal.json");
                yield return Path.Combine(_inboxRoot, $"{sale.SourceDocumentId.Value}.fiscal.json");
            }
        }

        private FiscalReceiptLookupResult ValidateCandidate(SaleReport sale, string manifestPath)
        {
            FiscalReceiptManifest manifest;
            try
            {
                if ((File.GetAttributes(manifestPath) & FileAttributes.ReparsePoint) != 0)
                    return FiscalReceiptLookupResult.Rejected("manifest reparse points are not accepted", manifestPath);
                manifest = JsonSerializer.Deserialize<FiscalReceiptManifest>(
                               File.ReadAllText(manifestPath, Encoding.UTF8), EpharmJson.Options)
                           ?? throw new InvalidDataException("manifest is empty");
            }
            catch (IOException ex)
            {
                return FiscalReceiptLookupResult.NotReady("manifest is still being written: " + ex.Message);
            }
            catch (Exception ex) when (ex is JsonException or InvalidDataException or UnauthorizedAccessException)
            {
                return FiscalReceiptLookupResult.Rejected("invalid fiscal manifest: " + ex.Message, manifestPath);
            }

            var validationError = ValidateIdentity(sale, manifest);
            if (validationError != null)
                return FiscalReceiptLookupResult.Rejected(validationError, manifestPath);

            if (Path.IsPathRooted(manifest.DocumentFile) ||
                !string.Equals(Path.GetFileName(manifest.DocumentFile), manifest.DocumentFile, StringComparison.Ordinal))
            {
                return FiscalReceiptLookupResult.Rejected("documentFile must be a file name inside the fiscal inbox", manifestPath);
            }

            var extension = Path.GetExtension(manifest.DocumentFile).ToLowerInvariant();
            var format = extension switch
            {
                ".pdf" => "pdf",
                ".png" => "png",
                _ => "",
            };
            if (format.Length == 0)
                return FiscalReceiptLookupResult.Rejected("only original PDF or PNG artifacts are accepted", manifestPath);

            var documentPath = Path.GetFullPath(Path.Combine(_inboxRoot, manifest.DocumentFile));
            if (!IsInsideInbox(documentPath))
                return FiscalReceiptLookupResult.Rejected("documentFile escapes the fiscal inbox", manifestPath);
            if (!File.Exists(documentPath))
                return FiscalReceiptLookupResult.NotReady("fiscal document has not appeared yet");

            try
            {
                if ((File.GetAttributes(documentPath) & FileAttributes.ReparsePoint) != 0)
                    return FiscalReceiptLookupResult.Rejected("fiscal document reparse points are not accepted", manifestPath);
                using var stream = new FileStream(
                    documentPath,
                    FileMode.Open,
                    FileAccess.Read,
                    FileShare.Read,
                    64 * 1024,
                    FileOptions.SequentialScan);
                if (stream.Length < 16 || stream.Length > _maxArtifactBytes)
                    return FiscalReceiptLookupResult.Rejected(
                        $"fiscal artifact size {stream.Length} is outside the accepted range", manifestPath);

                if (!HasValidContainer(stream, format))
                    return FiscalReceiptLookupResult.Rejected(
                        $"{format.ToUpperInvariant()} signature or final marker is invalid", manifestPath);

                stream.Position = 0;
                var actualSha = Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
                var expectedSha = (manifest.Sha256 ?? "").Trim().ToLowerInvariant();
                if (expectedSha.Length != 64 || !expectedSha.All(Uri.IsHexDigit))
                    return FiscalReceiptLookupResult.Rejected("manifest sha256 must contain 64 hex characters", manifestPath);
                if (!CryptographicOperations.FixedTimeEquals(
                        Encoding.ASCII.GetBytes(actualSha),
                        Encoding.ASCII.GetBytes(expectedSha)))
                {
                    return FiscalReceiptLookupResult.Rejected("fiscal artifact SHA-256 does not match the manifest", manifestPath);
                }

                return FiscalReceiptLookupResult.Ready(
                    manifest,
                    manifestPath,
                    documentPath,
                    format,
                    actualSha);
            }
            catch (IOException ex)
            {
                return FiscalReceiptLookupResult.NotReady("fiscal document is still being written: " + ex.Message);
            }
            catch (UnauthorizedAccessException ex)
            {
                return FiscalReceiptLookupResult.Rejected("fiscal document is not readable: " + ex.Message, manifestPath);
            }
        }

        private string? ValidateIdentity(SaleReport sale, FiscalReceiptManifest manifest)
        {
            if (manifest.SchemaVersion != 1) return "unsupported fiscal manifest schemaVersion";
            if (!_trustedSources.Contains((manifest.SourceSystem ?? "").Trim()))
                return "sourceSystem is not in the configured trusted-source allowlist";
            if (!string.Equals(manifest.PharmacyId?.Trim(), sale.PharmacyId?.Trim(), StringComparison.Ordinal))
                return "manifest pharmacyId does not match the POSM sale";
            if (!string.IsNullOrWhiteSpace(manifest.SaleId) &&
                !string.Equals(manifest.SaleId.Trim(), sale.SaleId, StringComparison.Ordinal))
                return "manifest saleId does not match the POSM sale";

            if (sale.SourceDocumentId.HasValue)
            {
                if (manifest.SourceDocumentId != sale.SourceDocumentId)
                    return "manifest sourceDocumentId does not match the Standard-N DOCS.ID";
            }
            else if (!string.Equals(manifest.SaleId?.Trim(), sale.SaleId, StringComparison.Ordinal))
            {
                return "a saleId match is required when Standard-N DOCS.ID is unavailable";
            }

            if (manifest.TotalAmount != sale.TotalAmount) return "manifest totalAmount does not match the POSM sale";
            if (manifest.PrintedAt == default ||
                Math.Abs((manifest.PrintedAt - sale.PrintedAt).TotalSeconds) > _maxClockSkew.TotalSeconds)
            {
                return "manifest printedAt is outside the allowed sale correlation window";
            }

            if (string.IsNullOrWhiteSpace(manifest.FiscalDocumentNumber)) return "fiscalDocumentNumber is required";
            if (string.IsNullOrWhiteSpace(manifest.FiscalSign)) return "fiscalSign is required";
            if (string.IsNullOrWhiteSpace(manifest.CashRegisterRegistrationNumber))
                return "cashRegisterRegistrationNumber is required";
            if (string.IsNullOrWhiteSpace(manifest.OfdName)) return "ofdName is required";
            if (string.IsNullOrWhiteSpace(manifest.DocumentFile)) return "documentFile is required";
            return null;
        }

        private bool IsInsideInbox(string fullPath)
        {
            var root = _inboxRoot.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar) +
                       Path.DirectorySeparatorChar;
            return fullPath.StartsWith(root, StringComparison.OrdinalIgnoreCase);
        }

        internal static bool HasValidContainer(Stream stream, string format)
        {
            if (format == "png")
            {
                var header = new byte[PngSignature.Length];
                stream.Position = 0;
                if (stream.Read(header, 0, header.Length) != header.Length || !header.SequenceEqual(PngSignature))
                    return false;
                var tail = new byte[PngIend.Length];
                stream.Position = stream.Length - tail.Length;
                return stream.Read(tail, 0, tail.Length) == tail.Length && tail.SequenceEqual(PngIend);
            }

            var pdfHeader = new byte[5];
            stream.Position = 0;
            if (stream.Read(pdfHeader, 0, pdfHeader.Length) != pdfHeader.Length ||
                Encoding.ASCII.GetString(pdfHeader) != "%PDF-")
            {
                return false;
            }

            var tailLength = (int)Math.Min(stream.Length, 2048);
            var pdfTail = new byte[tailLength];
            stream.Position = stream.Length - tailLength;
            if (stream.Read(pdfTail, 0, pdfTail.Length) != pdfTail.Length) return false;
            return Encoding.ASCII.GetString(pdfTail).Contains("%%EOF", StringComparison.Ordinal);
        }

        private static string SafeToken(string value)
        {
            if (string.IsNullOrWhiteSpace(value)) throw new ArgumentException("saleId is empty", nameof(value));
            var safe = new string(value.Select(ch => char.IsLetterOrDigit(ch) || ch is '-' or '_' ? ch : '_').ToArray());
            return safe.Length <= 64 ? safe : safe[..64];
        }

        private string HandoffPath(string fileName, string label)
        {
            if (string.IsNullOrWhiteSpace(fileName) ||
                Path.IsPathRooted(fileName) ||
                !string.Equals(Path.GetFileName(fileName), fileName, StringComparison.Ordinal))
            {
                throw new InvalidDataException($"{label} must be a file name inside the fiscal inbox");
            }

            var path = Path.GetFullPath(Path.Combine(_inboxRoot, fileName));
            if (!IsInsideInbox(path)) throw new InvalidDataException($"{label} escapes the fiscal inbox");
            if (File.Exists(path) && (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
                throw new InvalidDataException($"{label} reparse points are not accepted");
            return path;
        }
    }
}
