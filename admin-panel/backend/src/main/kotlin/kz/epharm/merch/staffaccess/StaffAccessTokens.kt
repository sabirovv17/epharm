package kz.epharm.merch.staffaccess

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class StaffAccessTokens(@Value("\${app.jwt.secret}") secret: String) {
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
    private val random = SecureRandom()
    fun code() = random.nextInt(1_000_000).toString().padStart(6, '0')
    fun token(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(48).also(random::nextBytes))
    fun codeLookup(value: String) = keyed("code", value)
    fun ipHash(value: String) = keyed("ip", value.take(128))
    fun invalidLookup(kind: String, value: String) = keyed("invalid-$kind", value.take(64))

    // A JWT-key rotation requires reissuing active codes; already-issued opaque sessions retain their hashes.
    private fun keyed(domain: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal("staff-access:$domain:$value".toByteArray(Charsets.UTF_8)).hex()
    }

    companion object {
        const val HEADER = "X-Staff-Session"
        fun sessionHash(token: String) = digest(token)
        fun qrHash(token: String) = digest("staff-access:qr:$token")
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hex()
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        fun normalizeCode(value: String?): String? = value?.takeIf { it.length <= 64 }
            ?.replace(Regex("[^0-9]"), "")?.takeIf { Regex("[0-9]{6}").matches(it) }
        fun normalizeToken(value: String?): String? = value?.trim()?.takeIf { Regex("[A-Za-z0-9_-]{32,256}").matches(it) }
    }
}
