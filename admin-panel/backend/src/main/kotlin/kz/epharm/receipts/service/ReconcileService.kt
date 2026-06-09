package kz.epharm.receipts.service

import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.receipts.dto.ExcelRowInput
import kz.epharm.receipts.dto.LogSaleInput
import kz.epharm.receipts.dto.ReceiptDto
import kz.epharm.receipts.dto.ReconcileSummaryDto
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.entity.PendingBonusStatus
import kz.epharm.receipts.entity.ReceiptEntity
import kz.epharm.receipts.entity.ReceiptSource
import kz.epharm.receipts.entity.ReceiptStatus
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.repository.ReceiptRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.shared.storage.MediaStorage
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/**
 * Сверка чеков (ТЗ §3.5). Источники истины — ТОЛЬКО лог Стандарт-Н (программа на C#) +
 * Excel-выгрузка. Никаких OCR/ОФД: то, что написано в чеке, сверяется с логом кассы и Excel.
 *  - авто-одобрение: чек подтверждён ОБОИМИ источниками (✓ лог + ✓ Excel), суммы сошлись
 *    (decideFromSources) → бонус сразу credited.
 *  - ручная модерация: подтверждён только одним источником (одна галочка) или ни одним
 *    (ноль галочек, самый редкий случай) → решает менеджер в админке (approve/reject).
 *  - anti-fraud: дубль фискального чека / чек из другой аптеки / расхождение сумм → flagged.
 * Загруженное фото — доказательство для модератора (автоматически не валидируется).
 * Начисление: бонус из связанного pending_bonus идёт на pharmacist.balance + earned30d.
 */
@Service
class ReconcileService(
    private val receiptRepository: ReceiptRepository,
    private val pendingBonusRepository: PendingBonusRepository,
    private val pharmacistRepository: PharmacistRepository,
    private val mediaStorage: MediaStorage,
) {

    private val log = LoggerFactory.getLogger(ReconcileService::class.java)

    companion object {
        private const val AMOUNT_TOLERANCE_PCT = 2 // ±2%
        private val MATCH_WINDOW: Duration = Duration.ofMinutes(30)
    }

    // ── Чтение ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun list(status: ReceiptStatus? = null): List<ReceiptDto> {
        val rows = if (status != null) {
            receiptRepository.findAllByStatusRawOrderByCreatedAtDesc(status.name)
        } else {
            receiptRepository.findAllByOrderByCreatedAtDesc()
        }
        return rows.map { toDto(it) }
    }

    @Transactional(readOnly = true)
    fun get(id: String): ReceiptDto = toDto(loadOrThrow(id))

    /** История чеков конкретного фармацевта (мобильное приложение). */
    @Transactional(readOnly = true)
    fun listForPharmacist(pharmacistId: String): List<ReceiptDto> =
        receiptRepository.findAllByPharmacistIdOrderByCreatedAtDesc(pharmacistId).map { toDto(it) }

    @Transactional(readOnly = true)
    fun summary(): ReconcileSummaryDto = ReconcileSummaryDto(
        queue = receiptRepository.countByStatusRaw(ReceiptStatus.pending.name),
        moderationRequired = receiptRepository.countByStatusRaw(ReceiptStatus.moderation_required.name),
        flagged = receiptRepository.countByStatusRaw(ReceiptStatus.flagged.name),
        approved = receiptRepository.countByStatusRaw(ReceiptStatus.approved.name),
        rejected = receiptRepository.countByStatusRaw(ReceiptStatus.rejected.name),
        autoApproved = receiptRepository.findAllByStatusRawOrderByCreatedAtDesc(ReceiptStatus.approved.name)
            .count { it.autoApproved }
            .toLong(),
    )

    // ── Загрузка чека (Pharmacist App; на MVP — dev/seed/тест) ──────────────

    /**
     * Фармацевт загрузил чек: фото → MinIO, матчим с pending_bonus, определяем ветку.
     * Возвращает созданный чек.
     *
     * Аптека: фармацевт ЯВНО выбирает её в приложении при загрузке (AddressSheet) —
     * [pharmacyId]/[pharmacyName] приходят с клиента и имеют приоритет над аптекой из
     * профиля (важно: при саморегистрации профиль аптеки может быть пуст). Так выбранная
     * аптека корректно сохраняется и видна в детали чека и в админ-очереди.
     */
    @Transactional
    fun submitReceipt(
        pharmacistId: String,
        photoBytes: ByteArray?,
        photoContentType: String?,
        photoName: String?,
        pharmacyId: String? = null,
        pharmacyName: String? = null,
    ): ReceiptDto {
        val pharmacist = pharmacistRepository.findById(pharmacistId).orElseThrow {
            AppException(ErrorCode.VALIDATION_FAILED, "Фармацевт $pharmacistId не найден", HttpStatus.BAD_REQUEST)
        }
        if (photoBytes == null) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Нужно фото чека", HttpStatus.BAD_REQUEST)
        }

        // Кандидат на матч — свежий открытый pending-бонус фармацевта.
        val candidate = latestAwaitingFor(pharmacistId)

        val photoUrl =
            mediaStorage.upload(photoBytes, photoContentType ?: "image/jpeg", photoName ?: "receipt.jpg")

        // Фото — только доказательство для модератора. SKU берём из связанной POSM-брони;
        // фактическую сумму/фискальный id/кассира заполнит сверка по источникам
        // (лог Стандарт-Н → ingestLogSale, Excel → ingestExcelRows).
        // Аптека: приоритет выбранной в приложении, иначе — из профиля фармацевта.
        val receipt = ReceiptEntity(
            id = "rcp_${UUID.randomUUID().toString().substring(0, 8)}",
            pharmacistId = pharmacist.id,
            pharmacistName = pharmacist.name,
            pharmacyId = pharmacyId?.takeIf { it.isNotBlank() } ?: pharmacist.pharmacyId ?: "",
            pharmacyName = pharmacyName?.takeIf { it.isNotBlank() } ?: pharmacist.pharmacyName ?: "",
            photoUrl = photoUrl,
            parsedSku = candidate?.sku ?: "",
            pendingBonusId = candidate?.id,
        )

        decideBranch(receipt, candidate)
        val saved = receiptRepository.save(receipt)
        return toDto(saved, candidate)
    }

    // ── Ручные действия модератора ──────────────────────────────────────────

    @Transactional
    fun approve(id: String, reviewer: String): ReceiptDto {
        val receipt = loadOrThrow(id)
        if (receipt.status == ReceiptStatus.approved) return toDto(receipt)
        creditFor(receipt)
        receipt.status = ReceiptStatus.approved
        receipt.autoApproved = false
        receipt.reviewer = reviewer
        receipt.reviewedAt = Instant.now()
        return toDto(receiptRepository.save(receipt))
    }

    @Transactional
    fun reject(id: String, reviewer: String, reason: String): ReceiptDto {
        val receipt = loadOrThrow(id)
        receipt.status = ReceiptStatus.rejected
        receipt.flagReason = reason
        receipt.reviewer = reviewer
        receipt.reviewedAt = Instant.now()
        return toDto(receiptRepository.save(receipt))
    }

    // ── Сверка по 3 источникам (Stage 2: лог кассы + Excel + ручная модерация) ──

    /**
     * Источник №1 — завершённый чек из лога кассы (POSM-клиент → /api/posm/sales).
     * Матчим позиции с открытыми pending-бонусами фармацевта; подтверждаем чек логом.
     */
    @Transactional
    fun ingestLogSale(input: LogSaleInput) {
        for (item in input.items) {
            val pending = pendingBonusRepository
                .findAllByPharmacistIdAndSkuAndStatusRawOrderByCreatedAtDesc(
                    input.pharmacistId, item.sku, PendingBonusStatus.awaiting_receipt.name,
                )
                .firstOrNull { withinTimeWindow(input.soldAt, it.createdAt) }
                ?: continue

            val receipt = receiptRepository.findFirstByPendingBonusId(pending.id)
                ?: newReceiptForPending(pending, input.fiscalId, input.cashier, input.soldAt, item.total)

            receipt.source = ReceiptSource.posm
            receipt.confirmedByLog = true
            if (receipt.fiscalId.isNullOrBlank()) receipt.fiscalId = input.fiscalId
            if (receipt.parsedCashier.isBlank()) receipt.parsedCashier = input.cashier ?: ""
            if (receipt.parsedAt == null) receipt.parsedAt = input.soldAt
            if (receipt.parsedAmount == 0L) receipt.parsedAmount = item.total

            decideFromSources(receipt)
            receiptRepository.save(receipt)
        }
    }

    /**
     * Источник №2 — Excel-выгрузка. Возвращает число сматченных строк.
     * Приоритет матчинга: (1) по fiscal_id к уже существующему чеку (лог его создал);
     * (2) Excel пришёл раньше лога → однозначный матч по SKU+сумме к открытому pending без чека.
     */
    @Transactional
    fun ingestExcelRows(rows: List<ExcelRowInput>): Int = rows.count { ingestExcelRow(it) }

    /** Сверка одной строки Excel. true если сматчили с pending-бонусом/чеком. */
    @Transactional
    fun ingestExcelRow(row: ExcelRowInput): Boolean {
        // (1) сильный матч по фискальному номеру
        if (!row.fiscalId.isNullOrBlank()) {
            val existing = receiptRepository.findAllByFiscalId(row.fiscalId)
                .firstOrNull { it.pendingBonusId != null }
            if (existing != null) {
                applyExcelToReceipt(existing, row)
                return true
            }
        }
        // (2) Excel раньше лога — матч по SKU + сумме к единственному подходящему pending
        val sku = row.sku ?: return false
        val amount = row.amount ?: return false
        val pending = pendingBonusRepository
            .findAllBySkuAndStatusRaw(sku, PendingBonusStatus.awaiting_receipt.name)
            .filter {
                amountWithinTolerance(amount, it.expectedAmount) &&
                    receiptRepository.findFirstByPendingBonusId(it.id) == null
            }
            .singleOrNull() ?: return false // только однозначный матч — иначе ждём лог

        val receipt = newReceiptForPending(pending, row.fiscalId, row.cashier, row.soldAt ?: Instant.now(), amount)
        applyExcelToReceipt(receipt, row)
        return true
    }

    private fun applyExcelToReceipt(receipt: ReceiptEntity, row: ExcelRowInput) {
        receipt.source = ReceiptSource.posm
        receipt.confirmedByExcel = true
        if (receipt.fiscalId.isNullOrBlank()) receipt.fiscalId = row.fiscalId
        if (receipt.parsedAmount == 0L) row.amount?.let { receipt.parsedAmount = it }

        // Cross-check суммы лог vs Excel — расхождение помечаем на ручную проверку.
        val excelAmount = row.amount
        if (receipt.confirmedByLog && excelAmount != null && receipt.parsedAmount > 0 &&
            !amountWithinTolerance(excelAmount, receipt.parsedAmount)
        ) {
            receipt.flagReason = "amount_mismatch"
        }
        decideFromSources(receipt)
        receiptRepository.save(receipt)
    }

    /** Решение по чеку на основе подтверждённых источников. */
    private fun decideFromSources(receipt: ReceiptEntity) {
        val both = receipt.confirmedByLog && receipt.confirmedByExcel
        when {
            both && receipt.flagReason == "amount_mismatch" -> receipt.status = ReceiptStatus.flagged
            both -> {
                creditFor(receipt)
                receipt.status = ReceiptStatus.approved
                receipt.autoApproved = true
                receipt.reviewer = "auto"
                receipt.reviewedAt = Instant.now()
            }
            // подтверждён только одним источником → ручная модерация (источник №3)
            receipt.confirmedByLog || receipt.confirmedByExcel -> receipt.status = ReceiptStatus.moderation_required
            else -> receipt.status = ReceiptStatus.pending
        }
    }

    private fun newReceiptForPending(
        pending: PendingBonusEntity,
        fiscalId: String?,
        cashier: String?,
        soldAt: Instant,
        amount: Long,
    ): ReceiptEntity = ReceiptEntity(
        id = "rcp_${UUID.randomUUID().toString().substring(0, 8)}",
        pharmacistId = pending.pharmacistId,
        pharmacistName = pending.pharmacistName,
        pharmacyId = pending.pharmacyId,
        pharmacyName = pending.pharmacyName,
        fiscalId = fiscalId,
        parsedSku = pending.sku,
        parsedAmount = amount,
        parsedCashier = cashier ?: "",
        parsedAt = soldAt,
        pendingBonusId = pending.id,
    ).also { it.source = ReceiptSource.posm }

    // ── Логика ветвления ────────────────────────────────────────────────────

    /**
     * Ветка чека ПРИ ЗАГРУЗКЕ из приложения. Авто-одобрения здесь НЕ бывает — подтверждение
     * даёт только сверка по источникам (лог Стандарт-Н + Excel → decideFromSources).
     * На загрузке проверяем лишь анти-фрод; иначе чек ждёт подтверждения (pending).
     */
    private fun decideBranch(receipt: ReceiptEntity, candidate: PendingBonusEntity?) {
        // Анти-фрод: дубль фискального чека (если фискальный id уже известен).
        if (!receipt.fiscalId.isNullOrBlank() && receiptRepository.existsByFiscalId(receipt.fiscalId!!)) {
            receipt.status = ReceiptStatus.flagged
            receipt.flagReason = "duplicate_receipt"
            return
        }
        // Анти-фрод: чек из аптеки, отличной от POSM-записи.
        if (candidate != null && receipt.pharmacyId != candidate.pharmacyId) {
            receipt.status = ReceiptStatus.flagged
            receipt.flagReason = "wrong_pharmacy"
            return
        }
        // Иначе — ждёт подтверждения источниками (лог/Excel); до того — pending.
        receipt.status = ReceiptStatus.pending
    }

    /** Начисление бонуса фармацевту: balance += bonus, earned30d += bonus, pending → matched. */
    private fun creditFor(receipt: ReceiptEntity, knownCandidate: PendingBonusEntity? = null) {
        if (receipt.bonusCredited > 0) return // идемпотентность — уже начислено
        val pending = knownCandidate
            ?: receipt.pendingBonusId?.let { pendingBonusRepository.findById(it).orElse(null) }
            ?: return
        val pharmacist = pharmacistRepository.findById(receipt.pharmacistId).orElse(null) ?: return
        pharmacist.balance += pending.bonus
        pharmacist.earned30d += pending.bonus
        pharmacistRepository.save(pharmacist)
        pending.status = PendingBonusStatus.matched
        pendingBonusRepository.save(pending)
        receipt.bonusCredited = pending.bonus
        log.info("Бонус {}₸ начислен фармацевту {} по чеку {}", pending.bonus, pharmacist.id, receipt.id)
    }

    private fun amountWithinTolerance(actual: Long, expected: Long): Boolean {
        if (expected <= 0) return false
        return abs(actual - expected) * 100 <= expected * AMOUNT_TOLERANCE_PCT
    }

    private fun withinTimeWindow(parsedAt: Instant?, posmAt: Instant): Boolean {
        val at = parsedAt ?: return false
        // Чек после события POSM и в пределах окна (учитываем небольшой дрейф назад).
        val delta = Duration.between(posmAt, at)
        return delta >= Duration.ofMinutes(-5) && delta <= MATCH_WINDOW
    }

    private fun latestAwaitingFor(pharmacistId: String): PendingBonusEntity? =
        pendingBonusRepository.findAll()
            .filter { it.pharmacistId == pharmacistId && it.status == PendingBonusStatus.awaiting_receipt }
            .maxByOrNull { it.createdAt }

    private fun loadOrThrow(id: String): ReceiptEntity =
        receiptRepository.findById(id).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Receipt $id not found", HttpStatus.NOT_FOUND)
        }

    private fun toDto(e: ReceiptEntity, known: PendingBonusEntity? = null): ReceiptDto {
        val pb = known ?: e.pendingBonusId?.let { pendingBonusRepository.findById(it).orElse(null) }
        return ReceiptDto.of(e, pb)
    }
}
