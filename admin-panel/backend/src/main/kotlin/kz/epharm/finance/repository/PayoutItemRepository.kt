package kz.epharm.finance.repository

import kz.epharm.finance.entity.PayoutItemEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PayoutItemRepository : JpaRepository<PayoutItemEntity, String> {
    fun findAllByBatchIdOrderByAmountDesc(batchId: String): List<PayoutItemEntity>
    fun findAllByPharmacistIdOrderByCreatedAtDesc(pharmacistId: String): List<PayoutItemEntity>
}
