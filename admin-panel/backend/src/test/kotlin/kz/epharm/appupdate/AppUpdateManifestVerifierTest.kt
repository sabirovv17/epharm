package kz.epharm.appupdate

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kz.epharm.appupdate.dto.RegisterReleaseRequest
import kz.epharm.appupdate.service.AppUpdateManifestVerifier
import kz.epharm.shared.error.AppException
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AppUpdateManifestVerifierTest {
    @Test
    fun `valid signature is accepted and manifest tampering is rejected`() {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val unsigned = RegisterReleaseRequest(
            platform = "win-x64",
            version = "1.2.3",
            url = "https://epharm.example/downloads/epharm-1.2.3.zip",
            sha256 = "a".repeat(64),
            manifestSignature = "pending",
            mandatory = true,
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(AppUpdateManifestVerifier.canonical(unsigned).toByteArray(StandardCharsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        val release = unsigned.copy(manifestSignature = signature)
        val verifier = AppUpdateManifestVerifier(Base64.getEncoder().encodeToString(keyPair.public.encoded))

        assertThatCode { verifier.validate(release) }.doesNotThrowAnyException()
        assertThatThrownBy { verifier.validate(release.copy(url = "https://evil.example/release.zip")) }
            .isInstanceOf(AppException::class.java)
            .hasMessageContaining("signature")
        assertThatThrownBy { verifier.validate(release.copy(sha256 = "b".repeat(64))) }
            .isInstanceOf(AppException::class.java)
            .hasMessageContaining("signature")
    }
}
