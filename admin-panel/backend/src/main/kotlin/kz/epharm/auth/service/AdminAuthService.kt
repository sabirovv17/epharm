package kz.epharm.auth.service

import jakarta.persistence.EntityManager
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.AuthTokens
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.dto.RefreshResponse
import kz.epharm.auth.dto.UserDto
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Бизнес-логика аутентификации HQ-консоли.
 */
@Service
class AdminAuthService(
    private val adminUserRepository: AdminUserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val refreshTokenService: RefreshTokenService,
    private val entityManager: EntityManager,
    private val jdbc: JdbcTemplate,
) {

    @Transactional
    fun login(email: String, password: String): LoginResponse {
        val user = adminUserRepository.findByEmailForUpdate(email).orElseThrow {
            AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password", HttpStatus.UNAUTHORIZED)
        }
        entityManager.refresh(user)
        if (user.status != AdminUserStatus.ACTIVE) {
            throw AppException(
                ErrorCode.USER_NOT_ACTIVE,
                "User is not active",
                HttpStatus.FORBIDDEN,
            )
        }
        if (!passwordEncoder.matches(password, user.passwordHash)) {
            throw AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password", HttpStatus.UNAUTHORIZED)
        }
        val now = Instant.now()
        val access = jwtService.issueAccessToken(user, now)
        val refresh = refreshTokenService.issue(user.id, now)

        return LoginResponse(
            tokens = AuthTokens(
                accessToken = access,
                accessTokenExpiresAt = now.plus(jwtService.accessTtl),
                refreshToken = refresh.raw,
                refreshTokenExpiresAt = refresh.expiresAt,
            ),
            user = UserDto.of(user),
        )
    }

    @Transactional
    fun refresh(rawRefreshToken: String): RefreshResponse {
        val userId = refreshTokenService.ownerOf(rawRefreshToken)
            ?: throw AppException(
                ErrorCode.INVALID_REFRESH_TOKEN,
                "Refresh token is invalid or expired",
                HttpStatus.UNAUTHORIZED,
            )
        // Lock order matches login/password changes: account, then refresh token.
        val user = adminUserRepository.findByIdForUpdate(userId).orElseThrow {
            AppException(ErrorCode.USER_NOT_FOUND, "User not found", HttpStatus.UNAUTHORIZED)
        }
        entityManager.refresh(user)
        val now = Instant.now()
        // Prove that the refresh credential is still active before exposing account state.
        // Keep the account -> token lock order; a concurrent password change/block cannot
        // resurrect a revoked token. All rotation changes roll back on a later rejection.
        if (refreshTokenService.rotate(rawRefreshToken, now) != user.id) {
            throw AppException(ErrorCode.INVALID_REFRESH_TOKEN, "Refresh token is invalid or expired", HttpStatus.UNAUTHORIZED)
        }
        if (user.status != AdminUserStatus.ACTIVE) {
            // Revoke all tokens for safety
            refreshTokenService.revokeAllForUser(user.id)
            throw AppException(
                ErrorCode.USER_NOT_ACTIVE,
                "User is not active",
                HttpStatus.FORBIDDEN,
            )
        }
        val newAccess = jwtService.issueAccessToken(user, now)
        val newRefresh = refreshTokenService.issue(user.id, now)
        return RefreshResponse(
            tokens = AuthTokens(
                accessToken = newAccess,
                accessTokenExpiresAt = now.plus(jwtService.accessTtl),
                refreshToken = newRefresh.raw,
                refreshTokenExpiresAt = newRefresh.expiresAt,
            ),
        )
    }

    @Transactional
    fun logout(userId: UUID) {
        refreshTokenService.revokeAllForUser(userId)
    }

    @Transactional
    fun changePassword(principal: AdminPrincipal, currentPassword: String, newPassword: String) {
        if (newPassword.isBlank() || newPassword.length !in 12..72 || newPassword.toByteArray(Charsets.UTF_8).size > 72) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Новый пароль: от 12 символов, не более 72 байт UTF-8", HttpStatus.BAD_REQUEST)
        }
        val user = adminUserRepository.findByIdForUpdate(principal.userId).orElseThrow {
            AppException(ErrorCode.USER_NOT_FOUND, "Аккаунт не найден", HttpStatus.UNAUTHORIZED)
        }
        // Recheck after the row lock, including an entity loaded earlier in this request.
        entityManager.refresh(user)
        if (user.status != AdminUserStatus.ACTIVE) {
            throw AppException(ErrorCode.USER_NOT_ACTIVE, "Аккаунт неактивен", HttpStatus.FORBIDDEN)
        }
        if (user.sessionVersion != principal.sessionVersion) {
            throw AppException(ErrorCode.UNAUTHORIZED, "Сессия завершена. Войдите снова", HttpStatus.UNAUTHORIZED)
        }
        val matches = try { passwordEncoder.matches(currentPassword, user.passwordHash) }
        catch (_: IllegalArgumentException) { false }
        if (!matches) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Текущий пароль указан неверно", HttpStatus.BAD_REQUEST)
        }
        if (newPassword == currentPassword) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Новый пароль должен отличаться от текущего", HttpStatus.BAD_REQUEST)
        }
        user.passwordHash = passwordEncoder.encode(newPassword)
        user.sessionVersion = Math.incrementExact(user.sessionVersion)
        adminUserRepository.saveAndFlush(user)
        refreshTokenService.revokeAllForUser(user.id)
        jdbc.update("INSERT INTO admin_password_change_audit(id,account_id,session_version) VALUES (?,?,?)",
            UUID.randomUUID(), user.id, user.sessionVersion)
    }

    fun currentUser(userId: UUID): UserDto {
        val user = adminUserRepository.findById(userId).orElseThrow {
            AppException(ErrorCode.USER_NOT_FOUND, "User not found", HttpStatus.UNAUTHORIZED)
        }
        return UserDto.of(user)
    }
}
