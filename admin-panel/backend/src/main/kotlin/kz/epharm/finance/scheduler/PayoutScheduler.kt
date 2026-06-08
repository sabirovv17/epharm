package kz.epharm.finance.scheduler

import kz.epharm.finance.service.PayoutService
import kz.epharm.receipts.service.PendingBonusService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.YearMonth

/**
 * Cron-задачи финансового потока. Исключён из профиля `test` — в интеграционных тестах
 * планировщик не нужен (логику дёргаем напрямую через сервисы).
 *
 *  - 1-го числа 09:00 — собрать накопленные бонусы фармацевтов в payout_batch (конец цепочки
 *    чек → бонус → выплата).
 *  - ежедневно 03:00 — протухание pending-бонусов, под которые чек так и не пришёл (14 дней).
 */
@Component
@Profile("!test")
class PayoutScheduler(
    private val payoutService: PayoutService,
    private val pendingBonusService: PendingBonusService,
) {
    private val log = LoggerFactory.getLogger(PayoutScheduler::class.java)

    companion object {
        private val PENDING_TTL: Duration = Duration.ofDays(14)
        private val MONTHS_RU = listOf(
            "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
            "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
        )
    }

    @Scheduled(cron = "0 0 9 1 * *") // 1-го числа каждого месяца, 09:00
    fun monthlyPayout() {
        val period = lastMonthLabel()
        runCatching { payoutService.generateBatch(period) }
            .onSuccess { log.info("Payout batch {} сформирован за {}: {} фарм., {}₸", it.id, period, it.pharmacists, it.amount) }
            .onFailure { log.info("Payout batch за {} не сформирован: {}", period, it.message) }
    }

    @Scheduled(cron = "0 0 3 * * *") // ежедневно в 03:00
    fun expireStaleBonuses() {
        val n = pendingBonusService.expireStale(PENDING_TTL)
        if (n > 0) log.info("Протухло pending-бонусов (>14 дней без чека): {}", n)
    }

    private fun lastMonthLabel(): String {
        val ym = YearMonth.now().minusMonths(1)
        return "${MONTHS_RU[ym.monthValue - 1]} ${ym.year}"
    }
}
