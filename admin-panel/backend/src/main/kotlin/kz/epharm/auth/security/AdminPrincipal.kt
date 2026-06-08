package kz.epharm.auth.security

import kz.epharm.auth.domain.AdminRole
import java.util.UUID

/**
 * Спарсенные claims, лежат в Authentication.principal после JwtAuthenticationFilter.
 */
data class AdminPrincipal(
    val userId: UUID,
    val email: String,
    val name: String,
    val role: AdminRole,
    val company: String,
)
