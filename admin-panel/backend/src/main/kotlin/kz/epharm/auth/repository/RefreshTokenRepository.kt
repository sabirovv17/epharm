package kz.epharm.auth.repository

import kz.epharm.auth.entity.RefreshTokenEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Repository
interface RefreshTokenRepository : JpaRepository<RefreshTokenEntity, UUID> {

    fun findByTokenHash(tokenHash: String): Optional<RefreshTokenEntity>

    @Query("SELECT t.userId FROM RefreshTokenEntity t WHERE t.tokenHash = :tokenHash")
    fun findOwnerByTokenHash(tokenHash: String): UUID?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshTokenEntity t WHERE t.tokenHash = :tokenHash AND t.revokedAt IS NULL AND t.expiresAt > :now")
    fun findActiveByTokenHashForUpdate(tokenHash: String, now: Instant): Optional<RefreshTokenEntity>

    @Modifying
    @Query("UPDATE RefreshTokenEntity t SET t.revokedAt = :now WHERE t.userId = :userId AND t.revokedAt IS NULL")
    fun revokeAllForUser(@Param("userId") userId: UUID, @Param("now") now: Instant = Instant.now())

    @Modifying
    @Query("DELETE FROM RefreshTokenEntity t WHERE t.expiresAt < :now")
    fun deleteExpired(@Param("now") now: Instant = Instant.now())
}
