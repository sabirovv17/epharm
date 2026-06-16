package kz.epharm.receipts.repository

import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.entity.ReceiptEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PendingBonusRepository : JpaRepository<PendingBonusEntity, String> {
    /** Открытые pending-бонусы фармацевта по SKU — для матчинга чека (свежие первыми). */
    fun findAllByPharmacistIdAndSkuAndStatusRawOrderByCreatedAtDesc(
        pharmacistId: String,
        sku: String,
        statusRaw: String,
    ): List<PendingBonusEntity>

    /** Открытые pending-бонусы по SKU (любой фармацевт) — для матчинга Excel-строки без фармацевта. */
    fun findAllBySkuAndStatusRaw(sku: String, statusRaw: String): List<PendingBonusEntity>

    /** Открытые брони фармацевта (любой SKU) — для авто-матчинга при загрузке чека (свежие первыми).
     *  SQL-фильтр по индексу вместо findAll()+in-memory: на проде брони исчисляются тысячами. */
    fun findAllByPharmacistIdAndStatusRawOrderByCreatedAtDesc(
        pharmacistId: String,
        statusRaw: String,
    ): List<PendingBonusEntity>

    fun countByStatusRaw(statusRaw: String): Long
}

@Repository
interface ReceiptRepository : JpaRepository<ReceiptEntity, String> {
    fun findAllByOrderByCreatedAtDesc(): List<ReceiptEntity>
    fun findAllByStatusRawOrderByCreatedAtDesc(statusRaw: String): List<ReceiptEntity>
    /** История чеков конкретного фармацевта (для мобильного приложения). */
    fun findAllByPharmacistIdOrderByCreatedAtDesc(pharmacistId: String): List<ReceiptEntity>
    fun countByStatusRaw(statusRaw: String): Long
    /** Анти-фрод: дубль фискального чека. */
    fun existsByFiscalIdAndIdNot(fiscalId: String, id: String): Boolean
    fun existsByFiscalId(fiscalId: String): Boolean
    /** Чек, связанный с pending-бонусом — для апдейта источниками сверки. */
    fun findFirstByPendingBonusId(pendingBonusId: String): ReceiptEntity?
    /** Чеки с данным фискальным номером — для cross-match Excel ↔ лог. */
    fun findAllByFiscalId(fiscalId: String): List<ReceiptEntity>
}
