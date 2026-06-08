package kz.epharm.auth

import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.service.JwtService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class JwtServiceTest {

    private val secret = "test-secret-key-of-at-least-32-bytes-for-hs256-please-please"
    private val jwtService = JwtService(secret = secret, accessTtlMinutes = 15)

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
    fun `parseAccessToken returns null for expired token`() {
        val user = mkUser()
        // Выдаём токен «в прошлом» — он сразу expired (TTL 15 минут, мы за 30 минут до now()).
        val past = Instant.now().minus(Duration.ofMinutes(30))
        val token = jwtService.issueAccessToken(user, past)
        assertThat(jwtService.parseAccessToken(token)).isNull()
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
    fun `constructor rejects too-short secret on first key usage`() {
        // Key создаётся лениво при первом обращении — issueAccessToken триггерит init.
        val tooShort = JwtService(secret = "too-short", accessTtlMinutes = 15)
        assertThatThrownBy { tooShort.issueAccessToken(mkUser()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("32 bytes")
    }
}
