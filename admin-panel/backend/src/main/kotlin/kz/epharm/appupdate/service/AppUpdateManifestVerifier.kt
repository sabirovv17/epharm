package kz.epharm.appupdate.service

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kz.epharm.appupdate.dto.RegisterReleaseRequest
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class AppUpdateManifestVerifier(
    @Value("\${app.posm.update-manifest-public-key-spki:}")
    private val publicKeySpkiBase64: String,
) {
    fun validate(req: RegisterReleaseRequest) {
        val url = runCatching { java.net.URI(req.url.trim()) }.getOrNull()
        val localHttp = url?.scheme.equals("http", true) && url?.host in setOf("localhost", "127.0.0.1", "::1")
        if (url == null || (!url.scheme.equals("https", true) && !localHttp)) {
            invalid("POSM release URL must use HTTPS (HTTP is allowed only for localhost development)")
        }
        if (!req.sha256.trim().matches(Regex("[0-9a-fA-F]{64}"))) {
            invalid("sha256 must contain exactly 64 hexadecimal characters")
        }
        val signatureBytes = runCatching { Base64.getDecoder().decode(req.manifestSignature.trim()) }
            .getOrElse { invalid("manifestSignature must be valid Base64") }
        if (signatureBytes.isEmpty()) invalid("manifestSignature must not be empty")

        if (publicKeySpkiBase64.isBlank()) return // dev/test: POSM remains the enforcing boundary
        val publicKey = runCatching {
            KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.getDecoder().decode(publicKeySpkiBase64.trim())),
            )
        }.getOrElse { invalid("Configured POSM update public key is invalid") }
        val verified = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(canonical(req).toByteArray(StandardCharsets.UTF_8))
                verify(signatureBytes)
            }
        }.getOrDefault(false)
        if (!verified) invalid("POSM update manifest signature is invalid")
    }

    companion object {
        fun canonical(req: RegisterReleaseRequest): String = buildString {
            append("epharm-posm-update-v1\n")
            append(req.platform.trim()).append('\n')
            append(req.version.trim()).append('\n')
            append(req.url.trim()).append('\n')
            append(req.sha256.trim().lowercase()).append('\n')
            append(req.mandatory.toString().lowercase())
        }
    }

    private fun invalid(message: String): Nothing =
        throw AppException(ErrorCode.VALIDATION_FAILED, message, HttpStatus.BAD_REQUEST)
}
