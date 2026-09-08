using System.Security.Cryptography;
using CustomerDisplay.Models.Posm;
using CustomerDisplay.Services;
using Xunit;

namespace CustomerDisplay.Core.Tests;

public sealed class UpdateManifestVerifierTests
{
    [Fact]
    public void SignedManifestIsAcceptedAndTamperingEitherUrlOrHashIsRejected()
    {
        using var signer = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var release = new AppVersionInfo
        {
            Current = true,
            Platform = "win-x64",
            Version = "1.2.3",
            Url = "https://cdn.example/epharm-1.2.3.zip",
            Sha256 = new string('a', 64),
            Mandatory = true,
        };
        release.ManifestSignature = Convert.ToBase64String(
            signer.SignData(
                System.Text.Encoding.UTF8.GetBytes(UpdateManifestVerifier.Canonical(release)),
                HashAlgorithmName.SHA256,
                DSASignatureFormat.Rfc3279DerSequence));
        var publicKey = Convert.ToBase64String(signer.ExportSubjectPublicKeyInfo());

        Assert.True(UpdateManifestVerifier.Verify(release, publicKey));
        release.Url = "https://attacker.example/replaced.zip";
        Assert.False(UpdateManifestVerifier.Verify(release, publicKey));
        release.Url = "https://cdn.example/epharm-1.2.3.zip";
        release.Sha256 = new string('b', 64);
        Assert.False(UpdateManifestVerifier.Verify(release, publicKey));
    }

    [Fact]
    public void MissingPinnedKeyFailsClosed()
    {
        Assert.False(UpdateManifestVerifier.Verify(new AppVersionInfo(), ""));
    }
}
