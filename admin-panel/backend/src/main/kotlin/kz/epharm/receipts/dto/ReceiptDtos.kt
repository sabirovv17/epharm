package kz.epharm.receipts.dto

import jakarta.validation.constraints.NotBlank
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.entity.ReceiptEntity
import kz.epharm.receipts.entity.ReceiptStatus
import java.time.Instant

/** Чек в очереди сверки (для админки). */
data class ReceiptDto(
    val id: String,
    val pharmacistId: String,
    val pharmacistName: String,
    val pharmacyId: String,
    val pharmacyName: String,
    val photoUrl: String?,
    val parsedSku: String,
    val parsedAmount: Long,
    val parsedCashier: String,
    val parsedAt: Instant?,
    val status: ReceiptStatus,
    val autoApproved: Boolean,
    val flagReason: String?,
    val pendingBonusId: String?,
    /** CSV id акций (pr_*), заявленных фармацевтом при загрузке чека (контекст для модератора). */
    val claimedPromoIds: String?,
    val expectedAmount: Long?,
    val bonus: Long?,
    val bonusCredited: Long,
    val source: String,
    val confirmedByLog: Boolean,
    val confirmedByExcel: Boolean,
    val reviewer: String?,
    val reviewedAt: Instant?,
    val createdAt: Instant,
) {
    companion object {
        fun of(e: ReceiptEntity, pb: PendingBonusEntity? = null): ReceiptDto = ReceiptDto(
            id = e.id,
            pharmacistId = e.pharmacistId,
            pharmacistName = e.pharmacistName,
            pharmacyId = e.pharmacyId,
            pharmacyName = e.pharmacyName,
            photoUrl = e.photoUrl,
            parsedSku = e.parsedSku,
            parsedAmount = e.parsedAmount,
            parsedCashier = e.parsedCashier,
            parsedAt = e.parsedAt,
            status = e.status,
            autoApproved = e.autoApproved,
            flagReason = e.flagReason,
            pendingBonusId = e.pendingBonusId,
            claimedPromoIds = e.claimedPromoIds,
            expectedAmount = pb?.expectedAmount,
            bonus = pb?.bonus,
            bonusCredited = e.bonusCredited,
            source = e.sourceRaw,
            confirmedByLog = e.confirmedByLog,
            confirmedByExcel = e.confirmedByExcel,
            reviewer = e.reviewer,
            reviewedAt = e.reviewedAt,
            createdAt = e.createdAt,
        )
    }
}

/** Сводка по очереди сверки (метрики для шапки). */
data class ReconcileSummaryDto(
    val queue: Long, // pending — ждут подтверждения источниками
    val moderationRequired: Long, // подтверждён одним источником — ждёт ручного решения
    val flagged: Long, // анти-фрод
    val approved: Long,
    val rejected: Long,
    val autoApproved: Long,
)

/** Причина отклонения. */
data class RejectReceiptRequest(
    @field:NotBlank
    val reason: String,
)

/** Результат импорта Excel-выгрузки (источник №2). */
data class ExcelImportResultDto(
    val importId: String,
    val fileName: String,
    val rowsTotal: Int,
    val rowsMatched: Int,
)
