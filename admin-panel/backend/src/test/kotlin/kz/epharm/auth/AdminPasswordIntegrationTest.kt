package kz.epharm.auth

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.auth.service.AdminAuthService
import kz.epharm.auth.service.AdminPasswordAttemptLimiter
import kz.epharm.auth.service.JwtService
import kz.epharm.shared.error.AppException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** No outer test transaction: revocation and rollback assertions observe committed database state. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AdminPasswordIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("password_test").withUsername("epharm").withPassword("local-test-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var service: AdminAuthService
    @Autowired lateinit var limiter: AdminPasswordAttemptLimiter
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var encoder: PasswordEncoder
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var transactions: PlatformTransactionManager
    private lateinit var user: AdminUserEntity
    private val oldPassword = "legacy-short"
    private val newPassword = "Synthetic-new-password"
    private val principal get() = AdminPrincipal(user.id, user.email, user.name, user.role, user.company)

    @BeforeEach fun seed() {
        user = users.saveAndFlush(AdminUserEntity(email = "password-${UUID.randomUUID()}@example.invalid",
            name = "Synthetic", roleRaw = AdminRole.TRAINER.name, passwordHash = encoder.encode(oldPassword)))
        // Self-service must not require a management grant or even a completed managed profile.
        jdbc.update("update admin_users set access_mode='MANAGED' where id=?", user.id)
    }

    @AfterEach fun cleanup() {
        jdbc.update("delete from admin_password_change_audit where account_id=?", user.id)
        jdbc.update("delete from refresh_tokens where user_id=?", user.id)
        users.deleteById(user.id)
    }

    private fun body(current: String = oldPassword, replacement: String = newPassword) =
        mapper.writeValueAsString(mapOf("currentPassword" to current, "newPassword" to replacement))

    private fun auditCount() = jdbc.queryForObject("select count(*) from admin_password_change_audit where account_id=?",
        Long::class.java, user.id)

    @Test fun `managed trainer changes own password and old access and refresh stop immediately`() {
        val login = service.login(user.email, oldPassword)
        mvc.perform(post("/api/admin/auth/password").header("Authorization", "Bearer ${login.tokens.accessToken}")
            .contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isNoContent).andExpect(header().string("Cache-Control", "no-store"))
        val changed = users.findById(user.id).orElseThrow()
        assertThat(changed.sessionVersion).isEqualTo(1L)
        assertThat(encoder.matches(newPassword, changed.passwordHash)).isTrue()
        assertThat(auditCount()).isEqualTo(1L)
        mvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer ${login.tokens.accessToken}"))
            .andExpect(status().isUnauthorized)
        assertThat(assertThrows<AppException> { service.refresh(login.tokens.refreshToken) }.status).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(assertThrows<AppException> { service.login(user.email, oldPassword) }.status).isEqualTo(HttpStatus.UNAUTHORIZED)
        val fresh = service.login(user.email, newPassword)
        mvc.perform(get("/api/admin/auth/me").header("Authorization", "Bearer ${fresh.tokens.accessToken}"))
            .andExpect(status().isOk)
    }

    @Test fun `wrong current password and invalid new passwords preserve account refresh and audit`() {
        val login = service.login(user.email, oldPassword)
        for ((current, replacement) in listOf("wrong" to newPassword, oldPassword to "short",
            oldPassword to "я".repeat(37), oldPassword to oldPassword, "" to newPassword)) {
            mvc.perform(post("/api/admin/auth/password").header("Authorization", "Bearer ${login.tokens.accessToken}")
                .contentType(MediaType.APPLICATION_JSON).content(body(current, replacement)))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        }
        val unchanged = users.findById(user.id).orElseThrow()
        assertThat(unchanged.passwordHash).isEqualTo(user.passwordHash)
        assertThat(unchanged.sessionVersion).isZero()
        assertThat(auditCount()).isZero()
        assertThat(service.refresh(login.tokens.refreshToken).tokens.accessToken).isNotBlank()
    }

    @Test fun `anonymous and same UUID pharmacist cannot change admin password`() {
        mvc.perform(post("/api/admin/auth/password").contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isUnauthorized)
        val pharmacist = jwt.issuePharmacistToken(user.id.toString(), "Synthetic", "+77000000000")
        mvc.perform(post("/api/admin/auth/password").header("Authorization", "Bearer $pharmacist")
            .contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isForbidden)
        assertThat(users.findById(user.id).orElseThrow().sessionVersion).isZero()
        assertThat(auditCount()).isZero()
    }

    @Test fun `audit insert failure rolls back password session version and refresh revocation`() {
        val login = service.login(user.email, oldPassword)
        // Fail only this synthetic account's audit INSERT, exercising the actual transaction boundary.
        jdbc.execute("alter table admin_password_change_audit add constraint test_password_audit_failure " +
            "check (account_id <> '${user.id}'::uuid) not valid")
        try {
            assertThat(limiter.consume(user.id)).isTrue()
            assertThrows<DataIntegrityViolationException> { service.changePassword(principal, oldPassword, newPassword) }
        } finally {
            jdbc.execute("alter table admin_password_change_audit drop constraint test_password_audit_failure")
        }
        val unchanged = users.findById(user.id).orElseThrow()
        assertThat(unchanged.passwordHash).isEqualTo(user.passwordHash)
        assertThat(unchanged.sessionVersion).isZero()
        assertThat(auditCount()).isZero()
        assertThat(jdbc.queryForObject("select attempts from admin_password_attempt_limits where account_id=?", Int::class.java, user.id))
            .isEqualTo(1)
        assertThat(service.refresh(login.tokens.refreshToken).tokens.accessToken).isNotBlank()
    }

    @Test fun `failed proof consumes persistent attempts while expiry and other accounts remain independent`() {
        val login = service.login(user.email, oldPassword)
        repeat(5) {
            mvc.perform(post("/api/admin/auth/password").header("Authorization", "Bearer ${login.tokens.accessToken}")
                .contentType(MediaType.APPLICATION_JSON).content(body("wrong")))
                .andExpect(status().isBadRequest)
        }
        mvc.perform(post("/api/admin/auth/password").header("Authorization", "Bearer ${login.tokens.accessToken}")
            .contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        assertThat(users.findById(user.id).orElseThrow().sessionVersion).isZero()
        assertThat(auditCount()).isZero()
        val other = users.saveAndFlush(AdminUserEntity(email = "other-${UUID.randomUUID()}@example.invalid"))
        try { assertThat(limiter.consume(other.id)).isTrue() } finally { users.deleteById(other.id) }
        jdbc.update("update admin_password_attempt_limits set window_started_at=current_timestamp-interval '16 minutes' where account_id=?", user.id)
        assertThat(limiter.consume(user.id)).isTrue()
        service.changePassword(principal, oldPassword, newPassword)
        assertThat(jdbc.queryForObject("select attempts from admin_password_attempt_limits where account_id=?", Int::class.java, user.id))
            .isEqualTo(1)
    }

    @Test fun `concurrent proof reservations enforce a shared finite threshold`() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val attempts = (1..8).map {
                pool.submit<Boolean> {
                    check(start.await(10, TimeUnit.SECONDS))
                    limiter.consume(user.id)
                }
            }
            start.countDown()
            assertThat(attempts.count { it.get(10, TimeUnit.SECONDS) }).isEqualTo(5)
            assertThat(jdbc.queryForObject("select attempts from admin_password_attempt_limits where account_id=?", Int::class.java, user.id))
                .isEqualTo(6)
        } finally {
            start.countDown()
            pool.shutdownNow()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["login", "refresh", "password"])
    fun `queued old credentials cannot cross a committing password change`(operation: String) {
        val login = service.login(user.email, oldPassword)
        val stalePrincipal = principal
        val pool = Executors.newFixedThreadPool(2)
        val changed = CountDownLatch(1)
        val commit = CountDownLatch(1)
        try {
            val mutation = pool.submit {
                TransactionTemplate(transactions).executeWithoutResult {
                    service.changePassword(stalePrincipal, oldPassword, newPassword)
                    changed.countDown()
                    check(commit.await(10, TimeUnit.SECONDS))
                }
            }
            check(changed.await(10, TimeUnit.SECONDS))
            val queued = pool.submit<HttpStatus> {
                try {
                    when (operation) {
                        "login" -> service.login(user.email, oldPassword)
                        "refresh" -> service.refresh(login.tokens.refreshToken)
                        // Even knowing the new password cannot reuse a pre-change authenticated request.
                        else -> service.changePassword(stalePrincipal, newPassword, "Synthetic-third-password")
                    }
                    HttpStatus.OK
                } catch (failure: AppException) { failure.status }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var waiting = false
            while (System.nanoTime() < deadline && !waiting && !queued.isDone) {
                waiting = jdbc.queryForObject("select count(*) from pg_locks where not granted", Long::class.java)!! > 0
                if (!waiting) Thread.sleep(10)
            }
            assertThat(waiting).describedAs("$operation must wait for the account lock").isTrue()
            commit.countDown()
            mutation.get(10, TimeUnit.SECONDS)
            assertThat(queued.get(10, TimeUnit.SECONDS)).isEqualTo(HttpStatus.UNAUTHORIZED)
            assertThat(users.findById(user.id).orElseThrow().sessionVersion).isEqualTo(1L)
            assertThat(auditCount()).isEqualTo(1L)
        } finally {
            commit.countDown()
            pool.shutdownNow()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }
}
