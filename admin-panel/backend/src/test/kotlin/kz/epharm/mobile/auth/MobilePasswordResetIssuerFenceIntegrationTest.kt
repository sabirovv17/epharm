package kz.epharm.mobile.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ninjasquad.springmockk.MockkBean
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.verify
import kz.epharm.auth.service.RefreshTokenService
import kz.epharm.mobile.auth.dto.MobileAuthResponse
import kz.epharm.mobile.auth.reset.MobilePasswordResetRequested
import kz.epharm.mobile.auth.reset.MobilePasswordResetVerified
import kz.epharm.mobile.auth.service.OtpProvider
import kz.epharm.mobile.auth.service.OtpVerificationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual HTTP issuers must serialize with password replacement, not merely revoke a stale token list. */
@SpringBootTest(properties = [
    "app.otp.dev-mode=true", "app.otp.dev-fixed=5445",
    "app.merch-rewards.enabled=false", "app.merch.producer-auth-enabled=false",
    "app.merch.native-executor-enabled=false", "app.merch.automation.enabled=false",
])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class MobilePasswordResetIssuerFenceIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("mobile_reset_fence_disposable")
            .withUsername("epharm").withPassword("synthetic-only").apply { start() }

        @JvmStatic @DynamicPropertySource
        fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }

        private const val PERSON = "u_reset_fence"
        private const val IIN = "951212500015"
        private const val PHONE = "+77002223344"
        private const val ORIGINAL_PASSWORD = "SyntheticBefore123!"
        private const val NEW_PASSWORD = "SyntheticAfter456!"
        private const val CODE = "5445"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var encoder: PasswordEncoder
    @MockkBean lateinit var provider: OtpProvider

    @BeforeEach
    fun seed() {
        // This class owns a disposable PostgreSQL database; never clears an external snapshot.
        jdbc.execute("""TRUNCATE mobile_password_reset_challenges,mobile_password_reset_rate_limits,
            mobile_refresh_tokens,mobile_otps,posm_staff_rate_limits,posm_staff_challenges,
            posm_staff_sessions,pharmacists CASCADE""")
        clearMocks(provider)
        every { provider.id } returns "synthetic-local"
        every { provider.verificationMode } returns OtpVerificationMode.LOCAL
        every { provider.requestOtp(any(), any()) } returns Unit
        jdbc.update("""INSERT INTO pharmacists(id,name,iin,phone,password_hash,tier,status)
            VALUES(?,?,?,?,?,'Silver','active')""", PERSON, "Synthetic reset employee", IIN, PHONE,
            encoder.encode(ORIGINAL_PASSWORD))
    }

    @Test
    fun `refresh waiting behind completed reset cannot resurrect a revoked session`() {
        val before = login()
        val grant = verifiedReset()
        val responses = resetBeforeIssuer(grant.resetToken, "/api/mobile/auth/refresh",
            mapOf("refreshToken" to before.tokens.refreshToken))

        assertThat(responses.second.status).isEqualTo(401)
        val after: MobileAuthResponse = json.readValue(responses.first.contentAsByteArray)
        assertOnlyResetSession(after)
        send("/api/mobile/auth/refresh", mapOf("refreshToken" to before.tokens.refreshToken))
            .andExpect(status().isUnauthorized)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mobile_refresh_tokens WHERE pharmacist_id=?", Long::class.java, PERSON))
            .isEqualTo(2L)
    }

    @Test
    fun `old password login waiting behind completed reset cannot issue a new session`() {
        val grant = verifiedReset()
        val responses = resetBeforeIssuer(grant.resetToken, "/api/mobile/auth/login",
            mapOf("iin" to IIN, "password" to ORIGINAL_PASSWORD))

        assertThat(responses.second.status).isEqualTo(401)
        val after: MobileAuthResponse = json.readValue(responses.first.contentAsByteArray)
        assertOnlyResetSession(after)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mobile_refresh_tokens WHERE pharmacist_id=?", Long::class.java, PERSON))
            .isEqualTo(1L)
        send("/api/mobile/auth/login", mapOf("iin" to IIN, "password" to ORIGINAL_PASSWORD))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `mobile SMS then reset shares cooldown with every optional feature disabled and preserves the first proof`() {
        send("/api/mobile/auth/sms/request", mapOf("phone" to PHONE)).andExpect(status().isOk)
        send("/api/mobile/auth/password-reset/request", mapOf("iin" to IIN))
            .andExpect(status().isTooManyRequests)
        send("/api/mobile/auth/password-reset/request", mapOf("iin" to IIN))
            .andExpect(status().isTooManyRequests)

        // A rejected cross-purpose issuance must not consume the SMS that was actually delivered.
        send("/api/mobile/auth/sms/verify", mapOf("phone" to PHONE, "code" to CODE))
            .andExpect(status().isOk)
        verify(exactly = 1) { provider.requestOtp(PHONE, CODE) }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mobile_password_reset_challenges", Long::class.java)).isZero()
    }

    @Test
    fun `reset SMS then mobile shares cooldown with every optional feature disabled and preserves the reset grant`() {
        val challenge = requestedReset()
        send("/api/mobile/auth/sms/request", mapOf("phone" to PHONE)).andExpect(status().isTooManyRequests)
        val grant = verifyReset(challenge)
        val after: MobileAuthResponse = json.readValue(send("/api/mobile/auth/password-reset/complete",
            mapOf("iin" to IIN, "resetToken" to grant.resetToken, "password" to NEW_PASSWORD))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray)

        assertOnlyResetSession(after)
        verify(exactly = 1) { provider.requestOtp(PHONE, CODE) }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mobile_otps", Long::class.java)).isZero()
    }

    private fun login(): MobileAuthResponse = json.readValue(send("/api/mobile/auth/login",
        mapOf("iin" to IIN, "password" to ORIGINAL_PASSWORD)).andExpect(status().isOk)
        .andReturn().response.contentAsByteArray)

    private fun requestedReset(): MobilePasswordResetRequested = json.readValue(send("/api/mobile/auth/password-reset/request",
        mapOf("iin" to IIN)).andExpect(status().isOk).andReturn().response.contentAsByteArray)

    private fun verifyReset(challenge: MobilePasswordResetRequested): MobilePasswordResetVerified = json.readValue(
        send("/api/mobile/auth/password-reset/verify", mapOf("iin" to IIN, "challengeId" to challenge.challengeId, "code" to CODE))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray)

    private fun verifiedReset() = verifyReset(requestedReset())

    private fun send(path: String, body: Any) = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(body)))

    private fun assertOnlyResetSession(after: MobileAuthResponse) {
        assertThat(after.pharmacist.id).isEqualTo(PERSON)
        assertThat(after.pharmacist.status.name).isEqualTo("active")
        assertThat(encoder.matches(NEW_PASSWORD,
            jdbc.queryForObject("SELECT password_hash FROM pharmacists WHERE id=?", String::class.java, PERSON))).isTrue()
        val hashes = jdbc.queryForList("""SELECT token_hash FROM mobile_refresh_tokens
            WHERE pharmacist_id=? AND revoked_at IS NULL""", String::class.java, PERSON)
        assertThat(hashes).containsExactly(RefreshTokenService.hash(after.tokens.refreshToken))
    }

    private fun resetBeforeIssuer(resetToken: String, issuerPath: String, issuerBody: Any): Pair<MockHttpServletResponse, MockHttpServletResponse> {
        val executor = Executors.newFixedThreadPool(2)
        try {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { blocker ->
                blocker.autoCommit = false
                blocker.prepareStatement("SELECT id FROM pharmacists WHERE id=? FOR UPDATE").use {
                    it.setString(1, PERSON)
                    it.executeQuery().use { row -> assertThat(row.next()).isTrue() }
                }
                try {
                    val completion = executor.submit<MockHttpServletResponse> {
                        send("/api/mobile/auth/password-reset/complete",
                            mapOf("iin" to IIN, "resetToken" to resetToken, "password" to NEW_PASSWORD))
                            .andReturn().response
                    }
                    awaitPharmacistWaiters(1)
                    val oldIssuer = executor.submit<MockHttpServletResponse> { send(issuerPath, issuerBody).andReturn().response }
                    awaitPharmacistWaiters(2)
                    assertThat(completion.isDone).isFalse()
                    assertThat(oldIssuer.isDone).isFalse()
                    blocker.commit()

                    val resetResponse = completion.get(15, TimeUnit.SECONDS)
                    val issuerResponse = oldIssuer.get(15, TimeUnit.SECONDS)
                    assertThat(resetResponse.status).isEqualTo(200)
                    return resetResponse to issuerResponse
                } finally {
                    blocker.rollback()
                }
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun awaitPharmacistWaiters(minimum: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val waiting = jdbc.queryForObject("""SELECT count(*) FROM pg_stat_activity
                WHERE datname=current_database() AND pid<>pg_backend_pid() AND state='active'
                AND wait_event_type='Lock' AND query ILIKE '%pharmacists%' AND query ILIKE '%for%update%'""",
                Int::class.java)!!
            if (waiting >= minimum) return
            Thread.sleep(20)
        }
        error("Expected $minimum real PostgreSQL pharmacist row-lock waiters")
    }
}
