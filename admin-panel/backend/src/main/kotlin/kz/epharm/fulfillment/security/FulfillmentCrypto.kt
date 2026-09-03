package kz.epharm.fulfillment.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class FulfillmentCrypto(
    @Value("\${app.fulfillment.shared-secret:dev-fulfillment-secret-change-me-32-bytes}")
    private val sharedSecret: String,
    @Value("\${app.fulfillment.enabled:false}")
    private val enabled: Boolean,
    @Value("\${app.fulfillment.signature-skew-seconds:300}")
    private val signatureSkewSeconds: Long,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        if (enabled && (
                sharedSecret.toByteArray(StandardCharsets.UTF_8).size < 32 ||
                    sharedSecret.contains("change-me", ignoreCase = true)
            )
        ) {
            throw IllegalStateException(
                "FULFILLMENT_SHARED_SECRET must be a non-default secret of at least 32 bytes",
            )
        }
    }

    fun verifyRequest(
        timestampHeader: String?,
        signatureHeader: String?,
        method: String,
        pathAndQuery: String,
        body: ByteArray,
    ) {
        val timestamp = timestampHeader?.toLongOrNull()
            ?: unauthorized("Missing or invalid fulfillment timestamp")
        val now = Instant.now(clock).epochSecond
        if (kotlin.math.abs(now - timestamp) > signatureSkewSeconds) {
            unauthorized("Fulfillment signature timestamp is outside the allowed window")
        }
        val supplied = signatureHeader
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: unauthorized("Missing or invalid fulfillment signature")
        val bodyHash = sha256Hex(body)
        val canonical = "$timestamp\n${method.uppercase()}\n$pathAndQuery\n$bodyHash"
        val expected = hmacHex(sharedSecret, canonical)
        if (!constantTimeEquals(supplied, expected)) unauthorized("Invalid fulfillment signature")
    }

    fun pickupCodeHmac(orderId: String, code: String): String =
        hmacHex(sharedSecret, "pickup-code:$orderId:$code")

    fun sha256Hex(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).toHex()

    fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(StandardCharsets.UTF_8))

    fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
        left.toByteArray(StandardCharsets.UTF_8),
        right.toByteArray(StandardCharsets.UTF_8),
    )

    private fun hmacHex(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    private fun unauthorized(message: String): Nothing =
        throw AppException(ErrorCode.UNAUTHORIZED, message, HttpStatus.UNAUTHORIZED)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
