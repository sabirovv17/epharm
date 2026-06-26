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
import kz.epharm.posm.entity.PosSaleItem
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Аналитика «Показы и продажи» (Задача 1 + 1.2): KPI конверсии показ→продажа и времени до продажи
 * + единый журнал событий из ДВУХ таблиц (recommendation_events = показы, pos_sales = продажи),
 * всё с точным временем. РЕАЛЬНЫЕ данные за окно `app.analytics.window-days`. Имена
 * аптек/фармацевтов резолвятся точечно только для отображаемого среза (limit).
 */
@Service
class RecommendationAnalyticsService(
    private val eventRepository: RecommendationEventRepository,
    private val posSaleRepository: PosSaleRepository,
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
        val amount: Long,
        val converted: Boolean,
        val secondsToSale: Int?,
    )

    @Transactional(readOnly = true)
    fun analytics(limit: Int): RecommendationAnalyticsDto {
        val since = Instant.now().minus(windowDays, ChronoUnit.DAYS)
        val shows = eventRepository.findByShownAtGreaterThanEqualOrderByShownAtDesc(since)
        val sales = posSaleRepository.findByPrintedAtGreaterThanEqualOrderByPrintedAtDesc(since)

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

        // ── Единый журнал: показы + продажи, по времени, обрезаем до limit ───────
        val showRaw = shows.map { e ->
            Raw(
                id = e.id, type = "show", at = e.displayedAt ?: e.shownAt,
                title = e.recommendName, triggerSku = e.triggerSku,
                pharmacyId = e.pharmacyId, pharmacistId = e.pharmacistId,
                amount = e.expectedAmount, converted = e.soldAt != null, secondsToSale = e.secondsToSale,
            )
        }
        // Для строки ПРОДАЖИ — время до продажи рекомендации, которую этот чек закрыл (если закрыл).
        // Один чек может закрыть несколько показов → берём минимальное (самую быструю конверсию).
        val saleToSecs = convertedEvents
            .filter { it.secondsToSale != null && !it.saleId.isNullOrBlank() }
            .groupBy { it.saleId!! }
            .mapValues { (_, evs) -> evs.minOf { it.secondsToSale!! } }
        val saleRaw = sales.map { s ->
            Raw(
                id = s.id, type = "sale", at = s.printedAt,
                title = saleTitle(s.items), triggerSku = null,
                pharmacyId = s.pharmacyId, pharmacistId = s.pharmacistId,
                amount = s.totalAmount, converted = false, secondsToSale = saleToSecs[s.id],
            )
        }
        val rows = (showRaw + saleRaw).sortedByDescending { it.at }.take(limit.coerceIn(1, MAX_LIMIT))

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
                pharmacistId = r.pharmacistId,
                // фармацевт-токен из лога кассы (kassir=) обычно не в справочнике → показываем как есть.
                pharmacistName = pharmacistNames[r.pharmacistId] ?: r.pharmacistId.ifBlank { "—" },
                amount = r.amount,
                converted = r.converted,
                secondsToSale = r.secondsToSale,
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
        )
    }

    /** Краткий заголовок продажи: первый товар + «+N», если позиций больше. */
    private fun saleTitle(items: List<PosSaleItem>): String {
        if (items.isEmpty()) return "Чек"
        val first = items.first().name.ifBlank { items.first().sku.ifBlank { "позиция" } }
        return if (items.size == 1) first else "$first +${items.size - 1}"
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
        private const val MAX_LIMIT = 500
    }
}
