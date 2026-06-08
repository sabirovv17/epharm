package kz.epharm.receipts.service

import kz.epharm.pharmacists.repository.PharmacistRepository
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
 * принял рекомендацию POSM (замену / cross-sell) — бонус НЕ начислен, ждёт подтверждения чеком.
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
    ): PendingBonusEntity {
        val pharmacist = pharmacistRepository.findById(pharmacistId).orElseThrow {
            AppException(
                ErrorCode.VALIDATION_FAILED,
                "Фармацевт $pharmacistId не найден",
                HttpStatus.BAD_REQUEST,
            )
        }
        val entity = PendingBonusEntity(
            id = "pb_${UUID.randomUUID().toString().substring(0, 8)}",
            pharmacistId = pharmacist.id,
            pharmacistName = pharmacist.name,
            pharmacyId = pharmacist.pharmacyId,
            pharmacyName = pharmacist.pharmacyName,
            sku = sku,
            productName = productName,
            expectedAmount = expectedAmount,
            bonus = bonus,
            ruleId = ruleId,
        )
        return pendingBonusRepository.save(entity)
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
