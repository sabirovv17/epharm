package kz.epharm.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.jsonwebtoken.Jwts
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.service.JwtService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

class JwtServiceTest {

    private val secret = "test-secret-key-of-at-least-32-bytes-for-hs256-please-please"
    private val jwtService = JwtService(secret = secret, accessTtlMinutes = 15)
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
    private val json = jacksonObjectMapper()

    private fun mkUser(): AdminUserEntity = AdminUserEntity(
        id = UUID.randomUUID(),
        email = "damir@jadran.com",
        passwordHash = "hash",
        name = "Дамир Нурланов",
        company = "Jadran",
    ).also {
        it.role = AdminRole.BRAND_MANAGER
    }

    @Test
    fun `issueAccessToken — round-trip parsing returns same claims`() {
        val user = mkUser()
        val token = jwtService.issueAccessToken(user)
        val claims = jwtService.parseAccessToken(token)

        assertThat(claims).isNotNull
        assertThat(claims!!.subject).isEqualTo(user.id.toString())
        assertThat(claims["email"]).isEqualTo("damir@jadran.com")
        assertThat(claims["name"]).isEqualTo("Дамир Нурланов")
        assertThat(claims["role"]).isEqualTo("BRAND_MANAGER")
        assertThat(claims["company"]).isEqualTo("Jadran")

        assertThat(jwtService.userIdOf(claims)).isEqualTo(user.id)
        assertThat(jwtService.roleOf(claims)).isEqualTo(AdminRole.BRAND_MANAGER)
    }

    @Test
    fun `issuePharmacistToken preserves the pharmacist identity`() {
        val token = jwtService.issuePharmacistToken("u_pharmacist", "Фармацевт", "+77001112233")
        val claims = jwtService.parseAccessToken(token)

        assertThat(claims).isNotNull
        assertThat(jwtService.isPharmacistToken(claims!!)).isTrue()
        assertThat(jwtService.pharmacistIdOf(claims)).isEqualTo("u_pharmacist")
        assertThat(claims["name"]).isEqualTo("Фармацевт")
        assertThat(claims["phone"]).isEqualTo("+77001112233")
    }

    @Test
    fun `existing tokens may contain empty presentation fields`() {
        assertThat(jwtService.parseAccessToken(jwtService.issueAccessToken(AdminUserEntity()))).isNotNull
        assertThat(jwtService.parseAccessToken(jwtService.issuePharmacistToken("u_empty", "", ""))).isNotNull
    }

    @Test
    fun `parseAccessToken returns null for expired token`() {
        val user = mkUser()
        // Выдаём токен «в прошлом» — он сразу expired (TTL 15 минут, мы за 30 минут до now()).
        val past = Instant.now().minus(Duration.ofMinutes(30))
        val token = jwtService.issueAccessToken(user, past)
        assertThat(jwtService.parseAccessToken(token)).isNull()
        assertThat(jwtService.parseAccessToken(jwtService.issuePharmacistToken("u_expired", "", "", past))).isNull()
    }

    @Test
    fun `parseAccessToken returns null for bogus token`() {
        assertThat(jwtService.parseAccessToken("not.a.jwt")).isNull()
        assertThat(jwtService.parseAccessToken("")).isNull()
        assertThat(jwtService.parseAccessToken("xxx.yyy.zzz")).isNull()
    }

    @Test
    fun `parseAccessToken returns null for token signed by different key`() {
        val user = mkUser()
        val other = JwtService(
            secret = "another-secret-but-also-at-least-32-bytes-long-1234",
            accessTtlMinutes = 15,
        )
        val token = other.issueAccessToken(user)
        // Наш jwtService отвергает токен другого секрета.
        assertThat(jwtService.parseAccessToken(token)).isNull()
    }

    @Test
    fun `parseAccessToken returns null for unsigned and unsupported tokens`() {
        val unsigned = Jwts.builder().claims(adminClaims()).compact()
        val content = Jwts.builder().content("not a claims payload").signWith(key, Jwts.SIG.HS256).compact()
        val malformed = Jwts.builder().content("{").signWith(key, Jwts.SIG.HS256).compact()
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val unsupported = listOf(
            encoder.encodeToString("""{"alg":"unsupported"}""".toByteArray(Charsets.UTF_8)),
            encoder.encodeToString(json.writeValueAsBytes(adminClaims())),
            "AA",
        ).joinToString(".")

        for (token in listOf(unsigned, content, malformed, unsupported)) {
            assertThat(jwtService.parseAccessToken(token)).isNull()
        }
    }

    @Test
    fun `parseAccessToken returns null for a token before its not-before time`() {
        val token = signedClaims(adminClaims() + ("nbf" to Instant.now().plusSeconds(300).epochSecond))
        assertThat(jwtService.parseAccessToken(token)).isNull()
    }

    @Test
    fun `parseAccessToken returns null when required claims are absent`() {
        for (claim in listOf("sub", "name", "exp", "email", "company", "role")) {
            assertThat(jwtService.parseAccessToken(signedClaims(adminClaims() - claim)))
                .describedAs("Missing admin claim %s", claim).isNull()
        }
        for (claim in listOf("sub", "name", "exp", "phone")) {
            assertThat(jwtService.parseAccessToken(signedClaims(pharmacistClaims() - claim)))
                .describedAs("Missing pharmacist claim %s", claim).isNull()
        }
    }

    @Test
    fun `parseAccessToken returns null for invalid claim types and unknown identities`() {
        val invalidAdmin = listOf(
            "sub" to 123,
            "sub" to "",
            "sub" to "not-a-uuid",
            "name" to 123,
            "email" to listOf("test@example.com"),
            "company" to false,
            "role" to 123,
            "role" to "PHARMACIST",
            "typ" to 123,
            "typ" to "other",
            "exp" to mapOf("invalid" to true),
        )
        for ((claim, value) in invalidAdmin) {
            assertThat(jwtService.parseAccessToken(signedClaims(adminClaims() + (claim to value))))
                .describedAs("Invalid admin claim %s=%s", claim, value).isNull()
        }
        for ((claim, value) in listOf("sub" to 123, "sub" to " ", "name" to false, "phone" to 123)) {
            assertThat(jwtService.parseAccessToken(signedClaims(pharmacistClaims() + (claim to value))))
                .describedAs("Invalid pharmacist claim %s=%s", claim, value).isNull()
        }
    }

    private fun adminClaims(): Map<String, Any> = mapOf(
        "sub" to UUID.randomUUID().toString(),
        "name" to "Администратор",
        "email" to "test@example.com",
        "company" to "ePharm",
        "role" to AdminRole.HQ_HEAD.name,
        "exp" to Instant.now().plusSeconds(300).epochSecond,
    )

    private fun pharmacistClaims(): Map<String, Any> = mapOf(
        "sub" to "u_pharmacist",
        "typ" to JwtService.TYP_PHARMACIST,
        "name" to "Фармацевт",
        "phone" to "+77001112233",
        "exp" to Instant.now().plusSeconds(300).epochSecond,
    )

    private fun signedClaims(claims: Map<String, Any>): String = Jwts.builder()
        .content(json.writeValueAsBytes(claims))
        .signWith(key, Jwts.SIG.HS256)
        .compact()

    @Test
    fun `constructor rejects too-short secret on first key usage`() {
        // Key создаётся лениво при первом обращении — issueAccessToken триггерит init.
        val tooShort = JwtService(secret = "too-short", accessTtlMinutes = 15)
        assertThatThrownBy { tooShort.issueAccessToken(mkUser()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("32 bytes")
    }
}
