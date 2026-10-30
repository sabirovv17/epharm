package kz.epharm.auth

import io.mockk.*
import jakarta.persistence.EntityManager
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.controller.AdminAuthController
import kz.epharm.auth.dto.ChangeAdminPasswordRequest
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.auth.service.AdminAuthService
import kz.epharm.auth.service.AdminPasswordAttemptLimiter
import kz.epharm.auth.service.JwtService
import kz.epharm.auth.service.RefreshTokenService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Optional

class AdminPasswordServiceTest {
    private val users = mockk<AdminUserRepository>()
    private val encoder = mockk<PasswordEncoder>()
    private val refresh = mockk<RefreshTokenService>()
    private val entities = mockk<EntityManager>()
    private val jdbc = mockk<JdbcTemplate>()
    private val limiter = mockk<AdminPasswordAttemptLimiter>()
    private val service = AdminAuthService(users, encoder, mockk<JwtService>(), refresh, entities, jdbc)
    private val user = AdminUserEntity(passwordHash = "synthetic-old-hash")
    private val principal = AdminPrincipal(user.id, user.email, user.name, user.role, user.company)
    private val newPassword = "Synthetic-new-password"

    private fun locked() {
        every { users.findByIdForUpdate(user.id) } returns Optional.of(user)
        every { entities.refresh(user) } just Runs
    }

    private fun unchanged() {
        assertThat(user.passwordHash).isEqualTo("synthetic-old-hash")
        assertThat(user.sessionVersion).isZero()
        verify(exactly = 0) { users.saveAndFlush(any()) }
        verify { refresh wasNot Called; jdbc wasNot Called }
    }

    @Test
    fun `exhausted throttle rejects before account lock or password hashing`() {
        every { limiter.consume(user.id) } returns false
        val controller = AdminAuthController(service, limiter)
        assertThat(assertThrows<AppException> { controller.changePassword(ChangeAdminPasswordRequest("old", newPassword), principal) }.status)
            .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        verify { users wasNot Called; entities wasNot Called; encoder wasNot Called; refresh wasNot Called; jdbc wasNot Called }
    }

    @Test
    fun `wrong current password has validation status and no writes`() {
        locked()
        every { encoder.matches("wrong", user.passwordHash) } returns false
        val failure = assertThrows<AppException> { service.changePassword(principal, "wrong", newPassword) }
        assertThat(failure.code).isEqualTo(ErrorCode.VALIDATION_FAILED)
        assertThat(failure.status).isEqualTo(HttpStatus.BAD_REQUEST)
        unchanged()
    }

    @Test
    fun `same password is rejected after current password proof`() {
        locked()
        every { encoder.matches(newPassword, user.passwordHash) } returns true
        assertThat(assertThrows<AppException> { service.changePassword(principal, newPassword, newPassword) }.status)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        unchanged()
    }

    @Test
    fun `new password validation applies utf8 byte limit without touching account`() {
        for (invalid in listOf("short", " ".repeat(12), "x".repeat(73), "я".repeat(37))) {
            assertThat(assertThrows<AppException> { service.changePassword(principal, "old", invalid) }.code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED)
        }
        verify { users wasNot Called; entities wasNot Called; encoder wasNot Called }
        unchanged()
    }

    @Test
    fun `request authenticated before concurrent password commit is rejected under lock`() {
        locked()
        every { entities.refresh(user) } answers { user.sessionVersion = 1 }
        assertThat(assertThrows<AppException> { service.changePassword(principal, "known-new-password", newPassword) }.status)
            .isEqualTo(HttpStatus.UNAUTHORIZED)
        verify { encoder wasNot Called; refresh wasNot Called; jdbc wasNot Called }
        verify(exactly = 0) { users.saveAndFlush(any()) }
    }

    @Test
    fun `account blocked while request waits is rejected before password proof`() {
        locked()
        every { entities.refresh(user) } answers { user.status = AdminUserStatus.BLOCKED }
        assertThat(assertThrows<AppException> { service.changePassword(principal, "old", newPassword) }.status)
            .isEqualTo(HttpStatus.FORBIDDEN)
        verify { encoder wasNot Called }
        unchanged()
    }
}
