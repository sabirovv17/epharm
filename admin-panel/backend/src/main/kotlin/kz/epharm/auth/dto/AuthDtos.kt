package kz.epharm.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import java.time.Instant

data class LoginRequest(
    @field:NotBlank
    @field:Email
    val email: String,

    @field:NotBlank
    @field:Size(min = 1, max = 128)
    val password: String,
)

data class RefreshRequest(
    @field:NotBlank
    val refreshToken: String,
)

data class AuthTokens(
    val accessToken: String,
    val accessTokenExpiresAt: Instant,
    val refreshToken: String,
    val refreshTokenExpiresAt: Instant,
)

data class LoginResponse(
    val tokens: AuthTokens,
    val user: UserDto,
)

data class RefreshResponse(
    val tokens: AuthTokens,
)

data class UserDto(
    val id: String,
    val email: String,
    val name: String,
    val role: AdminRole,
    val company: String,
) {
    companion object {
        fun of(user: AdminUserEntity): UserDto = UserDto(
            id = user.id.toString(),
            email = user.email,
            name = user.name,
            role = user.role,
            company = user.company,
        )
    }
}
