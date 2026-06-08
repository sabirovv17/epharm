package kz.epharm.auth.service

import io.jsonwebtoken.Claims
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.MalformedJwtException
import io.jsonwebtoken.security.SignatureException
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

    fun parseAccessToken(token: String): Claims? = try {
        Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
    } catch (_: ExpiredJwtException) {
        null
    } catch (_: MalformedJwtException) {
        null
    } catch (_: SignatureException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun userIdOf(claims: Claims): UUID = UUID.fromString(claims.subject)

    fun roleOf(claims: Claims): AdminRole = AdminRole.valueOf(claims["role"] as String)
}
