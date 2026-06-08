package kz.epharm.mobile.auth.repository

import kz.epharm.mobile.auth.entity.MobileRefreshTokenEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional

@Repository
interface MobileRefreshTokenRepository : JpaRepository<MobileRefreshTokenEntity, java.util.UUID> {

    fun findByTokenHash(tokenHash: String): Optional<MobileRefreshTokenEntity>

    @Modifying
    @Query(
        "UPDATE MobileRefreshTokenEntity t SET t.revokedAt = CURRENT_TIMESTAMP " +
            "WHERE t.pharmacistId = :pharmacistId AND t.revokedAt IS NULL",
    )
    fun revokeAllForPharmacist(@Param("pharmacistId") pharmacistId: String)

    @Modifying
    @Query("DELETE FROM MobileRefreshTokenEntity t WHERE t.expiresAt < :now")
    fun deleteExpired(@Param("now") now: Instant = Instant.now())
}
