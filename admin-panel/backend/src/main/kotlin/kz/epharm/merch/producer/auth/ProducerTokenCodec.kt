package kz.epharm.merch.producer.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.JwtException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** This purpose is deliberately absent from the admin/mobile JwtService allowlist. */
@Component
class ProducerTokenCodec(@Value("\${app.jwt.secret}") private val secret:String) {
    private val key by lazy { secret.toByteArray(Charsets.UTF_8).also { require(it.size>=32) }.let {SecretKeySpec(it,"HmacSHA256")} }
    fun issue(principal:ProducerPrincipal,now:Instant,expires:Instant):String=Jwts.builder().subject(principal.customerId.toString())
        .claim("typ","merch_producer").claim("sid",principal.sessionId.toString()).issuer(ISSUER).audience().add(AUDIENCE).and()
        .id(UUID.randomUUID().toString()).issuedAt(Date.from(now)).expiration(Date.from(minOf(now.plusSeconds(900),expires)))
        .signWith(key,Jwts.SIG.HS256).compact()
    fun parse(token:String):ProducerPrincipal? = try {
        if(token.length !in 32..8192) null else {
            val c=Jwts.parser().verifyWith(key).requireIssuer(ISSUER).requireAudience(AUDIENCE).build().parseSignedClaims(token).payload
            if(c["typ"]!="merch_producer" || c["sub"] !is String || c.expiration==null || c.issuedAt==null || c.issuedAt.toInstant().isAfter(Instant.now())) null
            else ProducerPrincipal(uuid(c.subject),uuid(c["sid"] as? String?:throw IllegalArgumentException()))
        }
    } catch(_:JwtException){null} catch(_:IllegalArgumentException){null}
    fun fingerprint(customer:UUID,phone:String)=hmac("producer-phone:$customer:$phone")
    fun codeHash(challenge:UUID,code:String)=hmac("producer-login:$challenge:$code")
    fun scopeHash(scope:String)=hmac("producer-rate:$scope")
    private fun hmac(value:String)=Mac.getInstance("HmacSHA256").run {init(key);doFinal(value.toByteArray(Charsets.UTF_8))}.joinToString(""){"%02x".format(it)}
    private fun uuid(value:String)=UUID.fromString(value).also {require(it.toString()==value)}
    companion object {
        private const val ISSUER="https://epharm.inkar.kz/producer"
        private const val AUDIENCE="epharm-merch-producer"
        fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        fun randomToken()=ByteArray(32).also(java.security.SecureRandom()::nextBytes).let {Base64.getUrlEncoder().withoutPadding().encodeToString(it)}
    }
}
