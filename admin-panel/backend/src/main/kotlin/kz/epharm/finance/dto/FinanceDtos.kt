package kz.epharm.finance.dto

import kz.epharm.finance.entity.PayoutBatchEntity
import kz.epharm.finance.entity.PayoutBatchStatus
import kz.epharm.finance.entity.PayoutItemEntity
import java.time.Instant

data class PayoutBatchDto(
    val id: String,
    val period: String,
    val status: PayoutBatchStatus,
    val pharmacists: Int,
    val amount: Long,
    val items: Int,
    val reviewerId: String?,
    val reviewerName: String?,
    val approvedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(e: PayoutBatchEntity): PayoutBatchDto = PayoutBatchDto(
            id = e.id,
            period = e.period,
            status = e.status,
            pharmacists = e.pharmacists,
            amount = e.amount,
            items = e.items,
            reviewerId = e.reviewerId,
            reviewerName = e.reviewerName,
            approvedAt = e.approvedAt,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
        )
    }
}

data class PayoutItemDto(
    val id: String,
    val batchId: String,
    val pharmacistId: String,
    val pharmacistName: String,
    val pharmacy: String,
    val city: String,
    val receipts: Int,
    val rules: Int,
    val amount: Long,
    val flag: String?,
) {
    companion object {
        fun of(e: PayoutItemEntity): PayoutItemDto = PayoutItemDto(
            id = e.id,
            batchId = e.batchId,
            pharmacistId = e.pharmacistId,
            pharmacistName = e.pharmacistName,
            pharmacy = e.pharmacy,
            city = e.city,
            receipts = e.receipts,
            rules = e.rules,
            amount = e.amount,
            flag = e.flag,
        )
    }
}
