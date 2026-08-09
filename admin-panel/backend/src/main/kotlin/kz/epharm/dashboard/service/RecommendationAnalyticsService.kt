package kz.epharm.dashboard.service

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.dashboard.dto.LogEntryDto
import kz.epharm.dashboard.dto.RecommendationAnalyticsDto
import kz.epharm.dashboard.dto.TimeBucketDto
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Аналитика «Показы и продажи» (Задача 1 + 1.2): KPI конверсии показ→продажа и времени до продажи
 * + единый журнал событий из ДВУХ таблиц (recommendation_events = показы, pos_sales = продажи),
 * всё с точным временем. РЕАЛЬНЫЕ данные за окно `app.analytics.window-days`. Имена
 * аптек/фармацевтов резолвятся точечно только для отображаемой страницы.
 */
@Service
class RecommendationAnalyticsService(
    private val eventRepository: RecommendationEventRepository,
    private val pharmacyRepository: PharmacyRepository,
    private val pharmacistRepository: PharmacistRepository,
    private val productRepository: ProductRepository,
    @Value("\${app.analytics.window-days:90}") private val windowDays: Long,
) {

    /** Промежуточная запись журнала до резолва имён. */
    private data class Raw(
        val id: String,
        val type: String,
        val at: Instant,
        val title: String,
        val triggerSku: String?,
        val pharmacyId: String,
        val pharmacistId: String,
        val pharmacistName: String?, // снимок имени продавца из Standard-N; только для продаж
        val reportedPharmacistId: String?,
        val reportedPharmacistName: String?,
        val pharmacistSource: String?,
        val amount: Long,
        val converted: Boolean,
        val secondsToSale: Int?,
        val units: Double?,        // продажа: количество единиц ЭТОЙ позиции (qty); null для показа
        val saleId: String?,       // id чека (pos_sales.id); null для показов
    )

    /** Совместимость внутренних вызовов/старых тестов: `limit` означает первую страницу. */
    fun analytics(limit: Int): RecommendationAnalyticsDto = analytics(page = 0, size = limit)

    @Transactional(readOnly = true)
    fun analytics(page: Int, size: Int): RecommendationAnalyticsDto {
        val requestedPage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val since = Instant.now().minus(windowDays, ChronoUnit.DAYS)
        val shows = eventRepository.findByShownAtGreaterThanEqualOrderByShownAtDesc(since)

        // ── KPI из показов (атрибуция показ→продажа) ─────────────────────────────
        val shown = shows.size
        val convertedEvents = shows.filter { it.soldAt != null }
        val converted = convertedEvents.size
        val secs = convertedEvents.mapNotNull { it.secondsToSale }.sorted()
        val convRate = if (shown > 0) round1(converted * 100.0 / shown) else 0.0
        val under2m = secs.count { it <= TWO_MIN }
        val avg = if (secs.isNotEmpty()) secs.average().roundToInt() else null
        val median = if (secs.isNotEmpty()) secs[secs.size / 2] else null
        val revenue = convertedEvents.sumOf { it.expectedAmount }

        // ── Единый журнал: PostgreSQL возвращает только запрошенную страницу ────
        val totalElements = eventRepository.countJournalRowsSince(since)
        val totalPages = if (totalElements == 0L) 0 else
            ((totalElements - 1L) / safeSize + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val safePage = if (totalPages == 0) 0 else requestedPage.coerceAtMost(totalPages - 1)
        val rows = eventRepository.findJournalRowsSince(
            since = since,
            pageSize = safeSize,
            rowOffset = safePage.toLong() * safeSize,
        ).map { row ->
            Raw(
                id = row.id,
                type = row.eventType,
                at = row.eventAt,
                title = row.title,
                triggerSku = row.triggerSku,
                pharmacyId = row.pharmacyId,
                pharmacistId = row.pharmacistId,
                pharmacistName = row.pharmacistName,
                reportedPharmacistId = row.reportedPharmacistId,
                reportedPharmacistName = row.reportedPharmacistName,
                pharmacistSource = row.pharmacistSource,
                amount = row.amount,
                converted = row.converted,
                secondsToSale = row.secondsToSale,
                units = row.units,
                saleId = row.saleId,
            )
        }

        val pharmacyNames = namesByIds(rows.map { it.pharmacyId }) { ids ->
            pharmacyRepository.findAllById(ids).associate { it.id to it.name }
        }
        val pharmacistNames = namesByIds(rows.map { it.pharmacistId }) { ids ->
            pharmacistRepository.findAllById(ids).associate { it.id to it.name }
        }
        // triggerSku — productId товара-триггера («что выбрал покупатель»); резолвим в имя.
        val triggerNames = namesByIds(rows.mapNotNull { it.triggerSku }) { ids ->
            productRepository.findAllById(ids).associate { it.id to it.name }
        }

        val log = rows.map { r ->
            LogEntryDto(
                id = r.id,
                type = r.type,
                at = r.at,
                title = r.title,
                triggerName = r.triggerSku?.let { triggerNames[it] },
                pharmacyId = r.pharmacyId,
                pharmacyName = pharmacyNames[r.pharmacyId] ?: r.pharmacyId.ifBlank { "—" },
                pharmacistId = r.pharmacistId.ifBlank { r.reportedPharmacistId.orEmpty() },
                // Внутренний справочник приоритетен. Если Standard-N USER_ID ещё не сопоставлен,
                // показываем сохранённое кассой имя; сырой id остаётся последним fallback.
                pharmacistName = pharmacistNames[r.pharmacistId]
                    ?: r.pharmacistName?.takeIf { it.isNotBlank() }
                    ?: r.reportedPharmacistName?.takeIf { it.isNotBlank() }
                    ?: r.reportedPharmacistId?.takeIf { it.isNotBlank() }
                    ?: r.pharmacistId.ifBlank { "—" },
                amount = r.amount,
                converted = r.converted,
                secondsToSale = r.secondsToSale,
                units = r.units,
                saleId = r.saleId,
                pharmacistSource = r.pharmacistSource,
            )
        }

        return RecommendationAnalyticsDto(
            windowDays = windowDays,
            shown = shown,
            converted = converted,
            convRate = convRate,
            convertedUnder2m = under2m,
            avgSecondsToSale = avg,
            medianSecondsToSale = median,
            attributedRevenue = revenue,
            buckets = buildBuckets(secs),
            log = log,
            page = safePage,
            pageSize = safeSize,
            totalElements = totalElements,
            totalPages = totalPages,
        )
    }

    /** Точечный резолв id→имя; пустой вход → пустая мапа (не дёргаем БД зря). */
    private fun namesByIds(ids: List<String>, load: (Set<String>) -> Map<String, String>): Map<String, String> {
        val unique = ids.filter { it.isNotBlank() }.toSet()
        return if (unique.isEmpty()) emptyMap() else load(unique)
    }

    /** Распределение времени до продажи по фиксированным корзинам (по секундам). */
    private fun buildBuckets(secsSorted: List<Int>): List<TimeBucketDto> = listOf(
        TimeBucketDto("< 30 сек", secsSorted.count { it < 30 }),
        TimeBucketDto("30 сек – 2 мин", secsSorted.count { it in 30..TWO_MIN }),
        TimeBucketDto("2 – 10 мин", secsSorted.count { it in (TWO_MIN + 1)..TEN_MIN }),
        TimeBucketDto("> 10 мин", secsSorted.count { it > TEN_MIN }),
    )

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    private companion object {
        private const val TWO_MIN = 120
        private const val TEN_MIN = 600
        private const val MAX_PAGE_SIZE = 100
    }
}
