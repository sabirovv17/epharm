package kz.epharm.pharmacies.service

import java.time.Instant
import java.time.temporal.ChronoUnit
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Ежедневный пересчёт реальных метрик реестра аптек (ТЗ §3.2) из живых данных:
 *  - receipts30d  = чеки кассы (pos_sales) за 30 дней;
 *  - rulesAccepted = принятые рекомендации (recommendation_events) за всё время.
 *
 * Раньше эти поля были статичным dev-seed и в проде висели нулями. Lift/Dashboard
 * теперь считают из таблиц напрямую, а этот шедулер держит «витринные» поля реестра
 * консистентными с реальностью. Запуск — после рефреша цен (перед рабочим днём).
 *
 * Cron: `app.analytics.metrics-refresh-cron` (по умолчанию 06:30 Asia/Almaty).
 */
@Service
class PharmacyMetricsScheduler(
    private val pharmacyRepository: PharmacyRepository,
    private val posSaleRepository: PosSaleRepository,
    private val recommendationEventRepository: RecommendationEventRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${app.analytics.metrics-refresh-cron:0 30 6 * * *}", zone = "Asia/Almaty")
    fun scheduledRefresh() {
        val updated = refreshNow()
        log.info("Пересчёт метрик аптек: обновлено {} записей", updated)
    }

    /** Точка для ручного запуска/теста. Возвращает число изменённых аптек. */
    @Transactional
    fun refreshNow(now: Instant = Instant.now()): Int {
        val since30d = now.minus(30, ChronoUnit.DAYS)
        val receiptsByPharmacy = posSaleRepository.countByPharmacySince(since30d)
            .associate { it.pharmacyId to it.cnt.toInt() }
        // rulesAccepted — накопительно (всё время): передаём раннюю границу.
        val acceptsByPharmacy = recommendationEventRepository.aggregateByPharmacySince(Instant.EPOCH)
            .associate { it.pharmacyId to it.accepts.toInt() }

        val pharmacies = pharmacyRepository.findAll()
        var changed = 0
        for (ph in pharmacies) {
            val receipts = receiptsByPharmacy[ph.id] ?: 0
            val accepts = acceptsByPharmacy[ph.id] ?: 0
            if (ph.receipts30d != receipts || ph.rulesAccepted != accepts) {
                ph.receipts30d = receipts
                ph.rulesAccepted = accepts
                pharmacyRepository.save(ph)
                changed++
            }
        }
        return changed
    }
}
