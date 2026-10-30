package kz.epharm.auth.security

import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.repository.AdminUserRepository
import org.springframework.stereotype.Component
import java.util.UUID

/** Reloads the current admin account; signed presentation/role claims are not authorization. */
@Component
class AdminSessionLoader(private val users: AdminUserRepository) {
    fun load(subject: String?, sessionVersion: Long = 0): AdminPrincipal? {
        val id = try {
            subject?.let(UUID::fromString)?.takeIf { it.toString().equals(subject, ignoreCase = true) }
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null

        // A database failure must propagate, never fall back to stale token privileges.
        val user = users.findById(id).orElse(null) ?: return null
        if (sessionVersion < 0 || user.sessionVersion != sessionVersion) return null
        if (!user.statusRaw.equals(AdminUserStatus.ACTIVE.toDbValue(), ignoreCase = true)) return null
        val role = AdminRole.entries.firstOrNull { it.name == user.roleRaw } ?: return null
        return AdminPrincipal(user.id, user.email, user.name, role, user.company, user.sessionVersion)
    }
}
