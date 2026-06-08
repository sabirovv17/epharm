package kz.epharm.auth.service

import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.AuthTokens
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.dto.RefreshResponse
import kz.epharm.auth.dto.UserDto
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
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
) {

    @Transactional
    fun login(email: String, password: String): LoginResponse {
        val user = adminUserRepository.findByEmailIgnoreCase(email).orElseThrow {
            AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password", HttpStatus.UNAUTHORIZED)
        }
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
        val now = Instant.now()
        val userId = refreshTokenService.rotate(rawRefreshToken, now)
            ?: throw AppException(
                ErrorCode.INVALID_REFRESH_TOKEN,
                "Refresh token is invalid or expired",
                HttpStatus.UNAUTHORIZED,
            )
        val user = adminUserRepository.findById(userId).orElseThrow {
            AppException(ErrorCode.USER_NOT_FOUND, "User not found", HttpStatus.UNAUTHORIZED)
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

    fun currentUser(userId: UUID): UserDto {
        val user = adminUserRepository.findById(userId).orElseThrow {
            AppException(ErrorCode.USER_NOT_FOUND, "User not found", HttpStatus.UNAUTHORIZED)
        }
        return UserDto.of(user)
    }
}
