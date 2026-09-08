using System;
using System.Security.Cryptography;
using System.Text;
using CustomerDisplay.Models.Posm;

namespace CustomerDisplay.Services
{
    /// <summary>
    /// Verifies release metadata before trusting either URL or SHA-256. The ECDSA public key is
    /// provisioned independently with the POSM installation and never comes from the update API.
    /// </summary>
    internal static class UpdateManifestVerifier
    {
        public static bool Verify(AppVersionInfo release, string publicKeySpkiBase64)
        {
            try
            {
                if (string.IsNullOrWhiteSpace(publicKeySpkiBase64) ||
                    string.IsNullOrWhiteSpace(release.ManifestSignature))
                    return false;
                var keyBytes = Convert.FromBase64String(publicKeySpkiBase64.Trim());
                var signature = Convert.FromBase64String(release.ManifestSignature.Trim());
                using var verifier = ECDsa.Create();
                verifier.ImportSubjectPublicKeyInfo(keyBytes, out var bytesRead);
                if (bytesRead != keyBytes.Length) return false;
                return verifier.VerifyData(
                    Encoding.UTF8.GetBytes(Canonical(release)),
                    signature,
                    HashAlgorithmName.SHA256,
                    DSASignatureFormat.Rfc3279DerSequence);
            }
            catch (CryptographicException) { return false; }
            catch (FormatException) { return false; }
        }

        internal static string Canonical(AppVersionInfo release) =>
            "epharm-posm-update-v1\n" +
            release.Platform.Trim() + "\n" +
            release.Version.Trim() + "\n" +
            release.Url.Trim() + "\n" +
            release.Sha256.Trim().ToLowerInvariant() + "\n" +
            release.Mandatory.ToString().ToLowerInvariant();
    }
}
