package kz.epharm.finance.service

import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.finance.dto.PayoutBatchDto
import kz.epharm.finance.dto.PayoutItemDto
import kz.epharm.finance.entity.PayoutBatchEntity
import kz.epharm.finance.entity.PayoutBatchStatus
import kz.epharm.finance.entity.PayoutItemEntity
import kz.epharm.finance.repository.PayoutBatchRepository
import kz.epharm.finance.repository.PayoutItemRepository
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class PayoutService(
    private val batchRepository: PayoutBatchRepository,
    private val itemRepository: PayoutItemRepository,
    private val adminUserRepository: AdminUserRepository,
    private val pharmacistRepository: PharmacistRepository,
) {

    /**
     * Сформировать батч выплат: собрать накопленный баланс фармацевтов (начисленные подтверждённые
     * бонусы) в payout_batch + payout_items, обнулив баланс («снятие в выплату»). Это конец
     * цепочки чек → бонус → ВЫПЛАТА. Вызывается cron'ом 1-го числа или вручную из админки.
     */
    @Transactional
    fun generateBatch(period: String): PayoutBatchDto {
        val owed = pharmacistRepository.findAll().filter { it.balance > 0 }
        if (owed.isEmpty()) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Нет начисленных бонусов к выплате",
                HttpStatus.BAD_REQUEST,
            )
        }
        val batchId = "pob_${UUID.randomUUID().toString().substring(0, 8)}"
        val total = owed.sumOf { it.balance }
        // Батч сохраняем ПЕРВЫМ — у payout_items FK на payout_batches(id).
        val batch = batchRepository.save(
            PayoutBatchEntity(
                id = batchId, period = period,
                pharmacists = owed.size, amount = total, items = owed.size,
            ),
        )
        owed.forEach { ph ->
            itemRepository.save(
                PayoutItemEntity(
                    id = "poi_${UUID.randomUUID().toString().substring(0, 8)}",
                    batchId = batchId,
                    pharmacistId = ph.id,
                    pharmacistName = ph.name,
                    pharmacy = ph.pharmacyName,
                    city = ph.city,
                    receipts = ph.receipts30d,
                    rules = ph.rulesAccepted30d,
                    amount = ph.balance,
                ),
            )
            ph.balance = 0 // снято в батч — баланс обнуляется
            pharmacistRepository.save(ph)
        }
        return PayoutBatchDto.of(batch)
    }

    @Transactional(readOnly = true)
    fun list(status: PayoutBatchStatus? = null): List<PayoutBatchDto> {
        val rows = if (status != null) {
            batchRepository.findAllByStatusRawOrderByCreatedAtDesc(status.name)
        } else {
            batchRepository.findAllByOrderByCreatedAtDesc()
        }
        return rows.map(PayoutBatchDto::of)
    }

    @Transactional(readOnly = true)
    fun get(id: String): PayoutBatchDto = PayoutBatchDto.of(loadOrThrow(id))

    @Transactional(readOnly = true)
    fun listItems(batchId: String): List<PayoutItemDto> {
        loadOrThrow(batchId) // ensure batch exists → 404 если нет
        return itemRepository.findAllByBatchIdOrderByAmountDesc(batchId).map(PayoutItemDto::of)
    }

    /**
     * Approve батч. Идемпотентный для pending → approved.
     * Уже approved → 409 CONFLICT (защита от двойной обработки).
     * approverId — UUID admin'а из JWT.
     */
    @Transactional
    fun approve(batchId: String, approverId: String): PayoutBatchDto {
        val entity = loadOrThrow(batchId)
        if (entity.status == PayoutBatchStatus.approved) {
            throw AppException(
                ErrorCode.CONFLICT,
                "Batch уже approved пользователем ${entity.reviewerName ?: entity.reviewerId}",
                HttpStatus.CONFLICT,
            )
        }
        val approver = adminUserRepository.findById(UUID.fromString(approverId)).orElse(null)
        entity.status = PayoutBatchStatus.approved
        entity.reviewerId = approverId
        entity.reviewerName = approver?.name
        entity.approvedAt = Instant.now()
        return PayoutBatchDto.of(batchRepository.save(entity))
    }

    private fun loadOrThrow(id: String): PayoutBatchEntity =
        batchRepository.findById(id).orElseThrow {
            AppException(
                ErrorCode.NOT_FOUND,
                "Payout batch $id not found",
                HttpStatus.NOT_FOUND,
            )
        }
}
