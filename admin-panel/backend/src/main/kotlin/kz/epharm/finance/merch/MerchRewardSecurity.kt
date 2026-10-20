package kz.epharm.finance.merch

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

data class MerchClaim(
    val dispatchId: UUID, val pharmacyId: String, val nonce: String,
    val expiresAt: Instant, val taskDueAt: Instant, val taskTitle: String, val rewardKzt: Long,
)

@Component
class MerchRewardSecurity(
    private val json: ObjectMapper,
    @Value("\${app.merch-rewards.enabled:false}") private val enabled: Boolean,
    @Value("\${app.merch-rewards.shared-secret:}") private val secret: String,
    @Value("\${app.otp.dev-mode:true}") private val otpDevMode: Boolean,
) {
    fun requireEnabled() {
        if (!enabled || secret.toByteArray(UTF_8).size < 32 || otpDevMode) {
            throw AppException(ErrorCode.UPSTREAM_UNAVAILABLE, "Подтверждение вознаграждения временно недоступно", HttpStatus.SERVICE_UNAVAILABLE)
        }
    }

    fun challenge(token: String, now: Instant = Instant.now()): MerchClaim {
        requireEnabled()
        if (token.length !in 20..4096) invalid()
        val parts = token.split('.')
        if (parts.size != 3 || parts[0] != "v1" || !parts[1].matches(B64) || !parts[2].matches(B64)) invalid()
        val signature = decode(parts[2])
        if (!MessageDigest.isEqual(signature, mac("epharm.merch.claim.v1\n${parts[1]}"))) invalid()
        val tree = runCatching { json.readTree(decode(parts[1])) }.getOrNull() ?: invalid()
        fun text(name: String): String = tree[name]?.takeIf { it.isTextual }?.textValue() ?: invalid()
        fun number(name: String): Long = tree[name]?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue() ?: invalid()
        if (text("iss") != "crm.inkar.kz" || text("aud") != "epharm") invalid()
        val issued = number("iat")
        val expiry = number("exp")
        val due = number("taskDueAt")
        if (issued < 0 || issued > now.epochSecond + 30 || expiry <= now.epochSecond ||
            expiry <= issued || expiry - issued > 600 || due < expiry) invalid()
        val pharmacy = text("pharmacyId")
        val nonce = text("nonce")
        val title = text("taskTitle")
        val amount = number("rewardKzt")
        if (!pharmacy.matches(Regex("[A-Za-z0-9_-]{1,64}")) || nonce.length != 43 || decode(nonce).size != 32 ||
            title.isBlank() || title.length > 160 || title.any { it.isISOControl() } || amount <= 0) invalid()
        return MerchClaim(uuid(text("dispatchId")), pharmacy, nonce, Instant.ofEpochSecond(expiry),
            runCatching { Instant.ofEpochSecond(due) }.getOrElse { invalid() }, title, amount)
    }

    fun verifyRequest(timestamp: String?, signature: String?, method: String, path: String, body: ByteArray, now: Instant = Instant.now()) {
        requireEnabled()
        val seconds = timestamp?.takeIf { it.matches(Regex("[0-9]{1,12}")) }?.toLongOrNull() ?: unauthorized()
        if (seconds < now.epochSecond - 300 || seconds > now.epochSecond + 300 || body.size > MAX_BODY) unauthorized()
        val received = signature?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
            ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray() ?: unauthorized()
        val canonical = "epharm.merch.request.v1\n$timestamp\n$method\n$path\n${hash(body)}"
        if (!MessageDigest.isEqual(received, mac(canonical))) unauthorized()
    }

    private fun mac(value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret.toByteArray(UTF_8), "HmacSHA256")); doFinal(value.toByteArray(UTF_8))
    }
    private fun decode(value: String) = runCatching { Base64.getUrlDecoder().decode(value) }.getOrElse { invalid() }

    companion object {
        const val MAX_BODY = 16 * 1024
        private val B64 = Regex("[A-Za-z0-9_-]+")
        fun hash(value: String) = hash(value.toByteArray(UTF_8))
        fun hash(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
        fun uuid(value: String): UUID = runCatching { UUID.fromString(value) }.getOrNull()
            ?.takeIf { it.toString() == value } ?: invalid()
        fun invalid(): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED, "Недействительное подтверждение задания")
        fun conflict(): Nothing = throw AppException(ErrorCode.CONFLICT, "Подтверждение задания уже связано с другим получателем или суммой", HttpStatus.CONFLICT)
        private fun unauthorized(): Nothing = throw AppException(ErrorCode.UNAUTHORIZED, "Недействительная подпись запроса", HttpStatus.UNAUTHORIZED)
    }
}
