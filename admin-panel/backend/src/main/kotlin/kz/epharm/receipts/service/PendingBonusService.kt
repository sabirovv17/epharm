package kz.epharm.receipts.service

import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.entity.PendingBonusStatus
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Регистрация pending-бонуса (ТЗ §3.5, вход для сверки чеков). Создаётся, когда фармацевт
 * принял рекомендацию POSM либо показанный информационный оффер подтверждён точной печатной
 * продажей — бонус НЕ начислен, ждёт сверки/модерации. sourceSaleId исключает подбор по догадке.
 *
 * Вынесено отдельным сервисом, чтобы POSM-домен (recommendation outcome) не лез напрямую в
 * receipts-репозитории. Зависимость односторонняя: posm → receipts.
 */
@Service
class PendingBonusService(
    private val pendingBonusRepository: PendingBonusRepository,
    private val pharmacistRepository: PharmacistRepository,
) {

    @Transactional
    fun register(
        pharmacistId: String,
        sku: String,
        productName: String,
        expectedAmount: Long,
        bonus: Long,
        ruleId: String?,
        sourceSaleId: String? = null,
        occurredAt: Instant? = null,
        expectedPharmacyId: String? = null,
    ): PendingBonusEntity {
        val pharmacist = pharmacistRepository.findById(pharmacistId).orElseThrow {
            AppException(
                ErrorCode.VALIDATION_FAILED,
                "Фармацевт $pharmacistId не найден",
                HttpStatus.BAD_REQUEST,
            )
        }
        if (sourceSaleId != null && (occurredAt == null || expectedPharmacyId.isNullOrBlank() ||
                pharmacist.status != PharmacistStatus.active || pharmacist.pharmacyId != expectedPharmacyId)) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Verified sale requires an active pharmacist in the exact pharmacy")
        }
        val entity = PendingBonusEntity(
            id = "pb_${UUID.randomUUID().toString().substring(0, 8)}",
            pharmacistId = pharmacist.id,
            pharmacistName = pharmacist.name,
            pharmacyId = pharmacist.pharmacyId ?: "",
            pharmacyName = pharmacist.pharmacyName ?: "",
            sku = sku,
            productName = productName,
            expectedAmount = expectedAmount,
            bonus = bonus,
            ruleId = ruleId,
            sourceSaleId = sourceSaleId,
            // Offline evidence belongs to its actual display time, not the later upload time.
            createdAt = occurredAt ?: Instant.now(),
        )
        return pendingBonusRepository.save(entity)
    }

    @Transactional(readOnly = true)
    fun reuseForOutcome(id: String, pharmacistId: String, pharmacyId: String, sku: String): String {
        val pending = pendingBonusRepository.findById(id).orElse(null)
        if (pending == null || pending.pharmacistId != pharmacistId || pending.pharmacyId != pharmacyId ||
            pending.sku != sku || pending.status != PendingBonusStatus.awaiting_receipt) {
            throw AppException(ErrorCode.CONFLICT, "Reservation identity or state conflicts with this recommendation", HttpStatus.CONFLICT)
        }
        return pending.id
    }

    /**
     * Протухание зависших pending-бонусов: если чек так и не пришёл за `olderThan`, бонус
     * сгорает (awaiting_receipt → expired) и больше не матчится. Вызывается ежедневным cron'ом.
     * Возвращает число протухших.
     */
    @Transactional
    fun expireStale(olderThan: Duration): Int {
        val cutoff = Instant.now().minus(olderThan)
        val stale = pendingBonusRepository.findAll().filter {
            it.status == PendingBonusStatus.awaiting_receipt && it.createdAt.isBefore(cutoff)
        }
        stale.forEach { it.status = PendingBonusStatus.expired }
        pendingBonusRepository.saveAll(stale)
        return stale.size
    }
}
