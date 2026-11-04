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
import org.springframework.jdbc.core.JdbcTemplate
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
    private val wallet: PharmacistWalletService,
    private val jdbc: JdbcTemplate,
) {

    /**
     * Сформировать батч выплат: собрать накопленный баланс фармацевтов (начисленные подтверждённые
     * бонусы) в payout_batch + payout_items, обнулив баланс («снятие в выплату»). Это конец
     * цепочки чек → бонус → ВЫПЛАТА. Вызывается cron'ом 1-го числа или вручную из админки.
     */
    @Transactional
    fun generateBatch(period: String): PayoutBatchDto {
        wallet.lockPayoutGeneration()
        val batchId = "pob_${UUID.randomUUID().toString().substring(0, 8)}"
        // Lock in a stable order and reserve the current balance, never an earlier JPA snapshot.
        val owed = pharmacistRepository.findAll().filter { it.balance > 0 }.sortedBy { it.id }.mapNotNull { ph ->
            val itemId = "poi_${UUID.randomUUID().toString().substring(0, 8)}"
            val reservation = wallet.reserveForPayout(ph.id, itemId)
            if (reservation.amountKzt == 0L) null else PayoutItemEntity(
                id = itemId, batchId = batchId, pharmacistId = ph.id, pharmacistName = ph.name,
                pharmacy = ph.pharmacyName ?: "", city = ph.city, receipts = ph.receipts30d,
                rules = ph.rulesAccepted30d, amount = -reservation.amountKzt,
            )
        }
        if (owed.isEmpty()) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Нет начисленных бонусов к выплате",
                HttpStatus.BAD_REQUEST,
            )
        }
        val total = owed.fold(0L) { sum, item -> Math.addExact(sum, item.amount) }
        // Батч сохраняем ПЕРВЫМ — у payout_items FK на payout_batches(id).
        val batch = batchRepository.save(
            PayoutBatchEntity(
                id = batchId, period = period,
                pharmacists = owed.size, amount = total, items = owed.size,
            ),
        )
        itemRepository.saveAll(owed)
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
        val entity = loadForUpdateOrThrow(batchId)
        if (entity.status == PayoutBatchStatus.approved) {
            throw AppException(
                ErrorCode.CONFLICT,
                "Batch уже approved пользователем ${entity.reviewerName ?: entity.reviewerId}",
                HttpStatus.CONFLICT,
            )
        }
        validateApproval(entity)
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

    private fun loadForUpdateOrThrow(id: String): PayoutBatchEntity =
        batchRepository.findByIdForUpdate(id).orElseThrow {
            AppException(
                ErrorCode.NOT_FOUND,
                "Payout batch $id not found",
                HttpStatus.NOT_FOUND,
            )
        }

    private fun validateApproval(batch: PayoutBatchEntity) {
        val items = itemRepository.findAllByBatchIdOrderByAmountDesc(batch.id)
        // Reject malformed amounts before PostgreSQL negates them (BIGINT MIN cannot be negated).
        if (!payoutBatchItemsConsistent(batch, items)) rejectInconsistentBatch()
        val reservations = jdbc.queryForObject(
            """SELECT COUNT(*) FROM payout_items item
                JOIN pharmacist_wallet_entries wallet
                  ON wallet.source = 'payout_reservation'
                 AND wallet.source_id = item.id
                 AND wallet.pharmacist_id = item.pharmacist_id
                 AND wallet.amount_kzt = -item.amount
                 AND wallet.earned_delta_kzt = 0
               WHERE item.batch_id = ?""",
            Long::class.java,
            batch.id,
        ) ?: 0L
        if (!payoutBatchApprovalConsistent(batch, items, reservations)) {
            rejectInconsistentBatch()
        }
    }

    private fun rejectInconsistentBatch(): Nothing = throw AppException(
        ErrorCode.CONFLICT,
        "Батч не согласован с позициями и резервами кошелька. Выполните сверку перед утверждением.",
        HttpStatus.CONFLICT,
    )
}

internal fun payoutBatchApprovalConsistent(
    batch: PayoutBatchEntity,
    items: List<PayoutItemEntity>,
    matchingReservations: Long,
): Boolean = payoutBatchItemsConsistent(batch, items) && matchingReservations == items.size.toLong()

internal fun payoutBatchItemsConsistent(
    batch: PayoutBatchEntity,
    items: List<PayoutItemEntity>,
): Boolean {
    val uniquePharmacists = items.map { it.pharmacistId }.toSet().size
    val total = runCatching {
        items.fold(0L) { sum, item -> Math.addExact(sum, item.amount) }
    }.getOrNull() ?: return false
    return items.isNotEmpty() &&
        items.all { it.amount > 0 } &&
        batch.items == items.size &&
        batch.pharmacists == uniquePharmacists &&
        uniquePharmacists == items.size &&
        batch.amount == total
}
