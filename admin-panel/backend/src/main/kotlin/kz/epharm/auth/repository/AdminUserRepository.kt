package kz.epharm.auth.repository

import kz.epharm.auth.entity.AdminUserEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.Optional
import java.util.UUID

@Repository
interface AdminUserRepository : JpaRepository<AdminUserEntity, UUID> {

    /**
     * Поиск по email с case-insensitive сравнением и trim.
     * Использует ux_admin_users_email_lower индекс из V002.
     */
    @Query("SELECT u FROM AdminUserEntity u WHERE LOWER(u.email) = LOWER(TRIM(:email))")
    fun findByEmailIgnoreCase(email: String): Optional<AdminUserEntity>

    fun existsByEmail(email: String): Boolean
}
