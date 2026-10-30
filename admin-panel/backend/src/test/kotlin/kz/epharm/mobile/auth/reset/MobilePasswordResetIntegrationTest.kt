package kz.epharm.mobile.auth.reset

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.*
import kz.epharm.auth.service.RefreshTokenService
import kz.epharm.mobile.auth.service.*
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Disposable synthetic database and mocked sender: no real SMS or production identity. */
@SpringBootTest(properties=["app.otp.dev-mode=false", "app.merch-rewards.enabled=false", "app.merch.producer-auth-enabled=false", "app.merch.native-executor-enabled=false"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class MobilePasswordResetIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine").withDatabaseName("reset_test").withUsername("test").withPassword("test").apply { start() }
        @JvmStatic @DynamicPropertySource fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
        const val IIN = "951212500015"
        const val PHONE = "+77002223344"
    }
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var people: PharmacistRepository
    @Autowired lateinit var encoder: PasswordEncoder
    @MockkBean lateinit var provider: OtpProvider

    @BeforeEach fun seed() {
        clearMocks(provider)
        every { provider.id } returns "test-reset"
        every { provider.verificationMode } returns OtpVerificationMode.EXTERNAL
        every { provider.requestOtp(any(), any()) } just Runs
        every { provider.verifyOtp(any(), any()) } returns OtpVerificationResult.INVALID
        every { provider.verifyOtp(PHONE, "1234") } returns OtpVerificationResult.VERIFIED
        jdbc.execute("TRUNCATE mobile_password_reset_challenges,mobile_password_reset_rate_limits,posm_staff_rate_limits,mobile_otps,mobile_refresh_tokens,pharmacists CASCADE")
        people.save(PharmacistEntity("u_reset", "Тестовый сотрудник", IIN, PHONE).also {
            it.status=PharmacistStatus.active; it.passwordHash=encoder.encode("Farm123!"); it.balance=42000
        })
    }
    private fun post(path: String, body: Any): MvcResult = mvc.perform(post("/api/mobile/auth/$path")
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andReturn()
    private fun body(result: MvcResult): JsonNode = json.readTree(result.response.contentAsByteArray)
    private fun request(iin: String=IIN): String {
        val result=post("password-reset/request",mapOf("iin" to iin))
        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.getHeader("Cache-Control")).contains("no-store")
        return body(result)["challengeId"].asText()
    }
    private fun verify(id: String, code: String="1234", iin: String=IIN) = post("password-reset/verify",mapOf("iin" to iin,"challengeId" to id,"code" to code))
    private fun grant(id: String=request()): String {
        val result=verify(id); assertThat(result.response.status).isEqualTo(200)
        return body(result)["resetToken"].asText()
    }
    private fun complete(token: String, iin: String=IIN, password: String="ResetFarm123!") = post("password-reset/complete",mapOf("iin" to iin,"resetToken" to token,"password" to password))
    private fun code(result: MvcResult) = body(result)["code"].asText()
    private fun count(sql: String) = jdbc.queryForObject(sql,Int::class.java)!!

    @Test fun `recovery changes only password issues own tokens revokes old refresh and single spends proof`() {
        val login=post("login",mapOf("iin" to IIN,"password" to "Farm123!"))
        val oldRefresh=body(login)["tokens"]["refreshToken"].asText()
        val token=grant()
        val saved=jdbc.queryForMap("SELECT * FROM mobile_password_reset_challenges")
        assertThat(saved["reset_token_hash"]).isEqualTo(RefreshTokenService.hash(token)).isNotEqualTo(token)
        assertThat(saved["code_hash"]).isNull() // External provider owns its code.
        val result=complete(token)
        assertThat(result.response.status).isEqualTo(200)
        assertThat(result.response.getHeader("Cache-Control")).contains("no-store")
        assertThat(body(result)["pharmacist"]["id"].asText()).isEqualTo("u_reset")
        val person=people.findById("u_reset").orElseThrow()
        assertThat(encoder.matches("ResetFarm123!",person.passwordHash)).isTrue()
        assertThat(person.status).isEqualTo(PharmacistStatus.active)
        assertThat(person.pharmacyId).isNull(); assertThat(person.balance).isEqualTo(42000)
        assertThat(post("refresh",mapOf("refreshToken" to oldRefresh)).response.status).isEqualTo(401)
        assertThat(post("login",mapOf("iin" to IIN,"password" to "Farm123!")).response.status).isEqualTo(401)
        assertThat(post("login",mapOf("iin" to IIN,"password" to "ResetFarm123!")).response.status).isEqualTo(200)
        assertThat(complete(token).response.status).isEqualTo(400)
    }

    @Test fun `unknown blocked and unactivated accounts have generic request shape and do not send or register`() {
        jdbc.update("UPDATE pharmacists SET status='blocked' WHERE id='u_reset'")
        val blocked=body(post("password-reset/request",mapOf("iin" to IIN)))
        val unknown=body(post("password-reset/request",mapOf("iin" to "880404400014")))
        jdbc.update("UPDATE pharmacists SET status='active',password_hash=NULL WHERE id='u_reset'")
        jdbc.execute("TRUNCATE mobile_password_reset_rate_limits")
        val initial=body(post("password-reset/request",mapOf("iin" to IIN)))
        for (node in listOf(blocked,unknown,initial)) {
            assertThat(node.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("challengeId","phoneMasked","ttlSeconds","retryAfterSeconds")
            assertThat(node["phoneMasked"].asText()).isEqualTo("+7 (***) ***-**-**")
            assertThat(verify(node["challengeId"].asText()).response.status).isEqualTo(400)
        }
        io.mockk.verify(exactly=0) { provider.requestOtp(any(),any()) }
        assertThat(count("SELECT count(*) FROM pharmacists")).isEqualTo(1)
    }

    @Test fun `failed attempts persist and sixth verify is rate limited`() {
        val id=request()
        repeat(5) { assertThat(code(verify(id,"0000"))).isEqualTo("OTP_INVALID") }
        assertThat(verify(id).response.status).isEqualTo(429)
        assertThat(count("SELECT attempts FROM mobile_password_reset_challenges")).isEqualTo(5)
        assertThat(count("SELECT count(*) FROM mobile_password_reset_challenges WHERE reset_token_hash IS NOT NULL")).isZero()
    }

    @Test fun `challenge and reset token cannot be borrowed by a different IIN or replayed`() {
        val id=request()
        assertThat(verify(id,iin="880404400014").response.status).isEqualTo(400)
        val token=grant(id)
        assertThat(verify(id).response.status).isEqualTo(400)
        assertThat(complete(token,iin="880404400014").response.status).isEqualTo(400)
        assertThat(complete("x".repeat(43)).response.status).isEqualTo(400)
        assertThat(complete(token).response.status).isEqualTo(200)
    }

    @Test fun `provider verification finishing after expiry cannot grant recovery`() {
        val id=request()
        jdbc.update("UPDATE mobile_password_reset_challenges SET expires_at=now()+interval '200 milliseconds'")
        every { provider.verifyOtp(PHONE,"1234") } answers { Thread.sleep(300); OtpVerificationResult.VERIFIED }
        assertThat(code(verify(id))).isEqualTo("OTP_EXPIRED")
        assertThat(count("SELECT count(*) FROM mobile_password_reset_challenges WHERE reset_token_hash IS NOT NULL")).isZero()
    }

    @Test fun `current phone password and status are rechecked after verified code`() {
        for (change in listOf(
            "UPDATE pharmacists SET phone='+77000000001' WHERE id='u_reset'",
            "UPDATE pharmacists SET password_hash='changed-password-fingerprint' WHERE id='u_reset'",
            "UPDATE pharmacists SET status='blocked' WHERE id='u_reset'")) {
            seed()
            val token=grant()
            jdbc.update(change)
            assertThat(complete(token).response.status).isEqualTo(400)
        }
    }

    @Test fun `phone change during provider call cannot issue a reset token`() {
        val id=request()
        every { provider.verifyOtp(PHONE,"1234") } answers {
            jdbc.update("UPDATE pharmacists SET phone='+77000000001' WHERE id='u_reset'")
            OtpVerificationResult.VERIFIED
        }
        assertThat(verify(id).response.status).isEqualTo(400)
        assertThat(count("SELECT count(*) FROM mobile_password_reset_challenges WHERE reset_token_hash IS NOT NULL")).isZero()
    }

    @Test fun `expired reset proof missing proof and overlong BCrypt input fail without changing password`() {
        assertThat(complete("x".repeat(43)).response.status).isEqualTo(400)
        val token=grant()
        assertThat(complete(token,password="я".repeat(40)).response.status).isEqualTo(400)
        jdbc.update("UPDATE mobile_password_reset_challenges SET reset_expires_at=now()-interval '1 second'")
        assertThat(complete(token).response.status).isEqualTo(400)
        assertThat(encoder.matches("Farm123!",people.findById("u_reset").orElseThrow().passwordHash)).isTrue()
    }

    @Test fun `untrusted fields and generic OTP proof cannot complete recovery`() {
        assertThat(post("password-reset/request",mapOf("iin" to IIN,"phone" to "+77000000001")).response.status).isEqualTo(400)
        assertThat(post("password-reset/complete",mapOf("iin" to IIN,"password" to "ResetFarm123!")).response.status).isEqualTo(400)
        assertThat(post("sms/request",mapOf("phone" to PHONE)).response.status).isEqualTo(200)
        assertThat(post("sms/verify",mapOf("phone" to PHONE,"code" to "1234")).response.status).isEqualTo(200)
        assertThat(complete("x".repeat(43)).response.status).isEqualTo(400)
    }

    @Test fun `provider failure never grants proof or burns verification attempts`() {
        val id=request()
        every { provider.verifyOtp(PHONE,"1234") } throws IllegalStateException("provider unavailable")
        assertThat(verify(id).response.status).isEqualTo(503)
        assertThat(count("SELECT attempts FROM mobile_password_reset_challenges")).isZero()
        assertThat(count("SELECT count(*) FROM mobile_password_reset_challenges WHERE reset_token_hash IS NOT NULL")).isZero()
        every { provider.verifyOtp(PHONE,"1234") } returns OtpVerificationResult.VERIFIED
        assertThat(verify(id).response.status).isEqualTo(200)
    }

    @Test fun `failed send is consumed and replaying cannot reach verifier`() {
        every { provider.requestOtp(any(),any()) } throws IllegalStateException("provider unavailable")
        assertThat(post("password-reset/request",mapOf("iin" to IIN)).response.status).isEqualTo(503)
        val id=jdbc.queryForObject("SELECT id FROM mobile_password_reset_challenges",UUID::class.java)!!.toString()
        assertThat(verify(id).response.status).isEqualTo(400)
        io.mockk.verify(exactly=0) { provider.verifyOtp(any(),any()) }
    }

    @Test fun `simultaneous complete calls single spend and produce exactly one live refresh`() {
        val token=grant()
        val executor=Executors.newFixedThreadPool(2)
        try {
            val results=executor.invokeAll(listOf(Callable { complete(token).response.status },Callable { complete(token).response.status }))
                .map { it.get(10,TimeUnit.SECONDS) }
            assertThat(results.sorted()).containsExactly(200,400)
            assertThat(count("SELECT count(*) FROM mobile_refresh_tokens WHERE revoked_at IS NULL")).isEqualTo(1)
        } finally { executor.shutdownNow() }
    }
}
