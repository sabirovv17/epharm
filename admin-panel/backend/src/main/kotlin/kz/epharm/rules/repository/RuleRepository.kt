package kz.epharm.rules.repository

import jakarta.persistence.LockModeType
import kz.epharm.rules.entity.RuleEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface RuleRepository : JpaRepository<RuleEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RuleEntity r WHERE r.id = :id")
    fun findForUpdate(id: String): Optional<RuleEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RuleEntity r WHERE r.promoId = :promoId ORDER BY r.id")
    fun findAllByPromoIdForUpdate(promoId: String): List<RuleEntity>

    fun findAllByOrderByUpdatedAtDesc(): List<RuleEntity>
    fun findAllByTypeRawOrderByUpdatedAtDesc(typeRaw: String): List<RuleEntity>
    fun findAllByStatusRawOrderByUpdatedAtDesc(statusRaw: String): List<RuleEntity>
    fun countByStatusRaw(statusRaw: String): Long

    /** Правила, сгенерированные из кампании (для редактирования/перегенерации из карточки промо). */
    fun findAllByPromoIdOrderByUpdatedAtDesc(promoId: String): List<RuleEntity>
    fun deleteByPromoId(promoId: String)
}
