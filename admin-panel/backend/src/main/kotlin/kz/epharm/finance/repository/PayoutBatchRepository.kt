package kz.epharm.finance.repository

import kz.epharm.finance.entity.PayoutBatchEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PayoutBatchRepository : JpaRepository<PayoutBatchEntity, String> {
    fun findAllByOrderByCreatedAtDesc(): List<PayoutBatchEntity>
    fun findAllByStatusRawOrderByCreatedAtDesc(statusRaw: String): List<PayoutBatchEntity>
    fun countByStatusRaw(statusRaw: String): Long
}
