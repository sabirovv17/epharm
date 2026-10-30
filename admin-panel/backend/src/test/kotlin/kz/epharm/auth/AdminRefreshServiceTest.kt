package kz.epharm.auth

import io.mockk.*
import jakarta.persistence.EntityManager
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.AdminAuthService
import kz.epharm.auth.service.JwtService
import kz.epharm.auth.service.RefreshTokenService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Optional

class AdminRefreshServiceTest {
    private val users = mockk<AdminUserRepository>()
    private val tokens = mockk<RefreshTokenService>(relaxUnitFun = true)
    private val entities = mockk<EntityManager>(relaxUnitFun = true)
    private val jwt = mockk<JwtService>()
    private val service = AdminAuthService(users, mockk<PasswordEncoder>(), jwt, tokens, entities, mockk<JdbcTemplate>())
    private val user = AdminUserEntity()
    private val raw = "synthetic-refresh-proof"

    private fun account() {
        every { tokens.ownerOf(raw) } returns user.id
        every { users.findByIdForUpdate(user.id) } returns Optional.of(user)
    }

    @ParameterizedTest
    @EnumSource(AdminUserStatus::class)
    fun `invalid refresh is rejected before exposing account status`(status: AdminUserStatus) {
        account()
        every { entities.refresh(user) } answers { user.status = status }
        every { tokens.rotate(raw, any()) } returns null
        val failure = assertThrows<AppException> { service.refresh(raw) }
        assertThat(failure.status).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(failure.code).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN)
        verifyOrder { users.findByIdForUpdate(user.id); entities.refresh(user); tokens.rotate(raw, any()) }
        verify(exactly = 0) { tokens.issue(any(), any()); tokens.revokeAllForUser(any()) }
        verify { jwt wasNot Called }
    }

    @Test
    fun `valid refresh for blocked account stays forbidden and issues no credentials`() {
        account()
        every { entities.refresh(user) } answers { user.status = AdminUserStatus.BLOCKED }
        every { tokens.rotate(raw, any()) } returns user.id
        val failure = assertThrows<AppException> { service.refresh(raw) }
        assertThat(failure.status).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(failure.code).isEqualTo(ErrorCode.USER_NOT_ACTIVE)
        verifyOrder { users.findByIdForUpdate(user.id); entities.refresh(user); tokens.rotate(raw, any()) }
        verify(exactly = 0) { tokens.issue(any(), any()) }
        verify { jwt wasNot Called }
    }

    @Test
    fun `unknown refresh never loads an account`() {
        every { tokens.ownerOf(raw) } returns null
        assertThat(assertThrows<AppException> { service.refresh(raw) }.status).isEqualTo(HttpStatus.UNAUTHORIZED)
        verify { users wasNot Called; entities wasNot Called; jwt wasNot Called }
        verify(exactly = 0) { tokens.rotate(any(), any()); tokens.issue(any(), any()) }
    }
}
