package kz.epharm.auth.security

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.jsonwebtoken.Jwts
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.mobile.auth.security.PharmacistPrincipal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.Optional
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/** Real signed JWT parsing and the actual filter, with only the database repository mocked. */
class JwtAuthenticationFilterTest {
    private val secret = "synthetic-filter-test-secret-at-least-32-bytes-long"
    private val jwt = JwtService(secret, 15)
    private val users = mockk<AdminUserRepository>()
    private val filter = JwtAuthenticationFilter(jwt, AdminSessionLoader(users))
    private val user = AdminUserEntity(
        id = UUID.fromString("00000000-0000-4000-8000-000000000002"),
        email = "old-admin@example.test", name = "Old name", company = "Old company",
        roleRaw = AdminRole.SYSTEM_ADMIN.name,
    )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate(token: String? = null, posmKey: Boolean = false): Authentication? {
        val request = MockHttpServletRequest("GET", "/api/admin/auth/me")
        if (token != null) request.addHeader("Authorization", "Bearer $token")
        if (posmKey) {
            request.requestURI = "/api/posm/heartbeat"
            request.addHeader("X-Posm-Key", "synthetic-device-key")
            request.addHeader("X-Posm-Signature", "synthetic-signature")
        }
        var called = false
        var authentication: Authentication? = null
        filter.doFilter(request, MockHttpServletResponse()) { _, _ ->
            called = true
            authentication = SecurityContextHolder.getContext().authentication
        }
        assertThat(called).isTrue()
        return authentication
    }

    private fun signedClaims(overrides: Map<String, Any?>): String {
        val claims = mapOf(
            "sub" to user.id.toString(), "email" to user.email, "name" to user.name,
            "role" to AdminRole.SYSTEM_ADMIN.name, "company" to user.company,
            "exp" to Instant.now().plusSeconds(300).epochSecond,
        ) + overrides
        return Jwts.builder().content(jacksonObjectMapper().writeValueAsBytes(claims))
            .signWith(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"), Jwts.SIG.HS256).compact()
    }

    @Test
    fun `valid admin token uses current database role and profile instead of signed claims`() {
        val token = jwt.issueAccessToken(user)
        user.role = AdminRole.TRAINING_MANAGER
        user.email = "current-admin@example.test"
        user.name = "Current name"
        user.company = "Current company"
        every { users.findById(user.id) } returns Optional.of(user)

        val auth = authenticate(token)!!
        assertThat(auth.principal).isEqualTo(
            AdminPrincipal(user.id, user.email, user.name, AdminRole.TRAINING_MANAGER, user.company),
        )
        assertThat(auth.authorities.map { it.authority }).containsExactly("ROLE_TRAINING_MANAGER")
        assertThat(auth.credentials).isNull()
    }

    @Test
    fun `a still valid token stops authenticating immediately after blocking pending or deletion`() {
        val token = jwt.issueAccessToken(user)
        every { users.findById(user.id) } returns Optional.of(user)
        assertThat(authenticate(token)).isNotNull()
        SecurityContextHolder.clearContext()
        user.status = AdminUserStatus.BLOCKED
        assertThat(authenticate(token)).isNull()
        user.status = AdminUserStatus.PENDING
        assertThat(authenticate(token)).isNull()
        every { users.findById(user.id) } returns Optional.empty()
        assertThat(authenticate(token)).isNull()
        verify(exactly = 4) { users.findById(user.id) }
    }

    @Test
    fun `current role is reloaded on each request without replacing it with a default admin role`() {
        val token = jwt.issueAccessToken(user)
        every { users.findById(user.id) } returns Optional.of(user)
        assertThat(authenticate(token)!!.authorities.map { it.authority }).containsExactly("ROLE_SYSTEM_ADMIN")
        SecurityContextHolder.clearContext()
        user.role = AdminRole.BRAND_MANAGER
        assertThat(authenticate(token)!!.authorities.map { it.authority }).containsExactly("ROLE_BRAND_MANAGER")
        SecurityContextHolder.clearContext()
        user.roleRaw = "INVALID_ROLE"
        assertThat(authenticate(token)).isNull()
    }

    @Test
    fun `expired foreign and malformed tokens never reach the account repository`() {
        val expired = jwt.issueAccessToken(user, Instant.now().minusSeconds(1800))
        val foreign = JwtService("another-synthetic-signing-secret-at-least-32-bytes", 15).issueAccessToken(user)
        for (token in listOf(expired, foreign, "invalid", "", signedClaims(mapOf("sub" to "not-a-uuid")))) {
            assertThat(authenticate(token)).isNull()
        }
        verify(exactly = 0) { users.findById(any()) }
    }

    @Test
    fun `signed but malformed claims or unknown token type remain unauthenticated`() {
        for ((claim, value) in listOf(
            "sub" to 42, "sub" to "", "name" to false, "email" to 42,
            "company" to listOf("invalid"), "role" to "UNKNOWN_ROLE", "typ" to "learner", "typ" to 42,
        )) {
            assertThat(authenticate(signedClaims(mapOf(claim to value)))).describedAs(claim).isNull()
        }
        verify(exactly = 0) { users.findById(any()) }
    }

    @Test
    fun `noncanonical UUID subject cannot select an admin account`() {
        assertThat(authenticate(signedClaims(mapOf("sub" to "1-1-1-1-1")))).isNull()
        verify(exactly = 0) { users.findById(any()) }
    }

    @Test
    fun `pharmacist token keeps its original principal and never loads an admin with the same UUID`() {
        val token = jwt.issuePharmacistToken(user.id.toString(), "Test pharmacist", "+77000000000")
        val auth = authenticate(token)!!
        assertThat(auth.principal).isEqualTo(PharmacistPrincipal(user.id.toString(), "Test pharmacist", "+77000000000"))
        assertThat(auth.authorities.map { it.authority }).containsExactly("ROLE_PHARMACIST")
        verify(exactly = 0) { users.findById(any()) }
    }

    @Test
    fun `pharmacist token cannot acquire admin authority through extra signed role claims`() {
        val token = signedClaims(mapOf("typ" to JwtService.TYP_PHARMACIST, "phone" to "+77000000000"))
        val auth = authenticate(token)!!
        assertThat(auth.principal).isInstanceOf(PharmacistPrincipal::class.java)
        assertThat(auth.authorities.map { it.authority }).containsExactly("ROLE_PHARMACIST")
        verify(exactly = 0) { users.findById(any()) }
    }

    @Test
    fun `absent bearer and POSM device headers do not trigger admin authentication`() {
        assertThat(authenticate()).isNull()
        assertThat(authenticate(posmKey = true)).isNull()
        verify(exactly = 0) { users.findById(any()) }
    }

    @Test
    fun `existing authentication is preserved without an admin lookup`() {
        val existing = UsernamePasswordAuthenticationToken("existing-device-principal", null, emptyList())
        SecurityContextHolder.getContext().authentication = existing
        assertThat(authenticate(jwt.issueAccessToken(user))).isSameAs(existing)
        verify(exactly = 0) { users.findById(any()) }
    }

    @Test
    fun `database outage does not authenticate from stale token claims or run protected code`() {
        every { users.findById(user.id) } throws DataAccessResourceFailureException("Database unavailable")
        var called = false
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer ${jwt.issueAccessToken(user)}") }
        assertThatThrownBy {
            filter.doFilter(request, MockHttpServletResponse()) { _, _ -> called = true }
        }.isInstanceOf(DataAccessResourceFailureException::class.java)
        assertThat(called).isFalse()
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }
}
