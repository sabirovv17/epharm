package kz.epharm.auth.service

import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

// Issue + parse JWT access-токенов (HS256).
// Refresh-токены — отдельный механизм в RefreshTokenService (UUID + DB hash, не JWT).
//
// jjwt 0.12.x API: Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
// (в 0.11.x было parserBuilder() + setSigningKey + parseClaimsJws + .body)
@Service
class JwtService(
    @Value("\${app.jwt.secret}") private val secret: String,
    @Value("\${app.jwt.access-ttl-minutes:15}") private val accessTtlMinutes: Long,
) {
    private val key: SecretKey by lazy {
        val bytes = secret.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size >= 32) { "JWT secret must be at least 32 bytes (256 bits) for HS256" }
        SecretKeySpec(bytes, "HmacSHA256")
    }

    val accessTtl: Duration = Duration.ofMinutes(accessTtlMinutes)

    fun issueAccessToken(user: AdminUserEntity, now: Instant = Instant.now()): String {
        val exp = now.plus(accessTtl)
        return Jwts.builder()
            // jti уникален — даже 2 токена в одну и ту же секунду различимы
            // (нужно для revocation lists в будущем и для тестов).
            .id(UUID.randomUUID().toString())
            .subject(user.id.toString())
            .claim("email", user.email)
            .claim("name", user.name)
            .claim("role", user.role.name)
            .claim("company", user.company)
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(key, Jwts.SIG.HS256)
            .compact()
    }

    /**
     * Access-токен фармацевта (мобильное приложение). Отличается claim'ом typ=pharmacist —
     * по нему JwtAuthenticationFilter строит PharmacistPrincipal с ролью ROLE_PHARMACIST,
     * а не AdminPrincipal. subject — строковый pharmacist.id (VARCHAR(64), не UUID).
     */
    fun issuePharmacistToken(
        pharmacistId: String,
        name: String,
        phone: String,
        now: Instant = Instant.now(),
    ): String {
        val exp = now.plus(accessTtl)
        return Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(pharmacistId)
            .claim("typ", TYP_PHARMACIST)
            .claim("name", name)
            .claim("phone", phone)
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(key, Jwts.SIG.HS256)
            .compact()
    }

    fun parseAccessToken(token: String): Claims? = try {
        Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
            .takeIf(::hasValidAccessClaims)
    } catch (_: JwtException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun hasValidAccessClaims(claims: Claims): Boolean {
        if (claims["sub"] !is String || claims.subject.isBlank() ||
            claims.expiration == null || claims["name"] !is String) return false
        return when (claims["typ"]) {
            TYP_PHARMACIST -> claims["phone"] is String
            null -> {
                UUID.fromString(claims.subject)
                claims["email"] is String && claims["company"] is String &&
                    AdminRole.entries.any { it.name == claims["role"] }
            }
            else -> false
        }
    }

    fun userIdOf(claims: Claims): UUID = UUID.fromString(claims.subject)

    fun roleOf(claims: Claims): AdminRole = AdminRole.valueOf(claims["role"] as String)

    /** true — если токен принадлежит фармацевту (claim typ=pharmacist), а не админу. */
    fun isPharmacistToken(claims: Claims): Boolean = claims["typ"] == TYP_PHARMACIST

    fun pharmacistIdOf(claims: Claims): String = claims.subject

    companion object {
        const val TYP_PHARMACIST = "pharmacist"
    }
}
