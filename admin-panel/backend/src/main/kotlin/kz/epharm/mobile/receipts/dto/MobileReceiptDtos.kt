package kz.epharm.mobile.receipts.dto

import kz.epharm.receipts.dto.ReceiptDto
import kz.epharm.receipts.entity.ReceiptStatus
import java.time.Instant

/**
 * Чек в мобильном приложении фармацевта — лёгкая проекция админского ReceiptDto под UI мобилки.
 * Статусы переведены в язык приложения (inReview / confirmed / rejected).
 */
data class MobileReceiptDto(
    val id: String,
    val status: String,
    val productName: String,
    val sku: String,
    val amount: Long,
    val bonus: Long?,
    val bonusCredited: Long,
    val photoUrl: String?,
    val rejectedReason: String?,
    val pharmacyName: String,
    val createdAt: Instant,
) {
    companion object {
        fun from(dto: ReceiptDto, productName: String): MobileReceiptDto = MobileReceiptDto(
            id = dto.id,
            status = mapStatus(dto.status),
            productName = productName,
            sku = dto.parsedSku,
            // Сумма из лога/Excel; пока не подтверждена источником — ожидаемая из pending-бонуса.
            amount = if (dto.parsedAmount > 0) dto.parsedAmount else (dto.expectedAmount ?: 0),
            bonus = dto.bonus,
            bonusCredited = dto.bonusCredited,
            photoUrl = dto.photoUrl,
            rejectedReason = dto.flagReason,
            pharmacyName = dto.pharmacyName,
            createdAt = dto.createdAt,
        )

        /**
         * Маппинг backend-статусов сверки → 3 UI-статуса мобилки:
         *  approved → confirmed; rejected → rejected; pending/moderation_required/flagged → inReview.
         */
        private fun mapStatus(s: ReceiptStatus): String = when (s) {
            ReceiptStatus.approved -> "confirmed"
            ReceiptStatus.rejected -> "rejected"
            ReceiptStatus.pending,
            ReceiptStatus.moderation_required,
            ReceiptStatus.flagged,
            -> "inReview"
        }
    }
}
