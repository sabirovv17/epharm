package kz.epharm.dashboard.service

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.dashboard.dto.RecommendationAnalyticsDto
import kz.epharm.dashboard.dto.RecommendationEventRowDto
import kz.epharm.dashboard.dto.TimeBucketDto
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.entity.RecommendationEventEntity
import kz.epharm.posm.repository.RecommendationEventRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Аналитика «Показано рекомендаций» (Задача 1 + 1.2): конверсия показ→продажа и время до продажи
 * за окно `app.analytics.window-days`. РЕАЛЬНЫЕ данные из recommendation_events (поля атрибуции
 * V032 наполняются при приёме чека). Имена аптек/фармацевтов/товаров-триггеров резолвятся точечно
 * только для показываемого среза (limit), не грузим всё.
 */
@Service
class RecommendationAnalyticsService(
    private val eventRepository: RecommendationEventRepository,
    private val pharmacyRepository: PharmacyRepository,
    private val pharmacistRepository: PharmacistRepository,
    private val productRepository: ProductRepository,
    @Value("\${app.analytics.window-days:90}") private val windowDays: Long,
) {

    @Transactional(readOnly = true)
    fun analytics(limit: Int): RecommendationAnalyticsDto {
        val since = Instant.now().minus(windowDays, ChronoUnit.DAYS)
        val all = eventRepository.findByShownAtGreaterThanEqualOrderByShownAtDesc(since)

        val shown = all.size
        val convertedEvents = all.filter { it.soldAt != null }
        val converted = convertedEvents.size
        val secs = convertedEvents.mapNotNull { it.secondsToSale }.sorted()

        val convRate = if (shown > 0) round1(converted * 100.0 / shown) else 0.0
        val under2m = secs.count { it <= TWO_MIN }
        val avg = if (secs.isNotEmpty()) secs.average().roundToInt() else null
        val median = if (secs.isNotEmpty()) secs[secs.size / 2] else null
        val revenue = convertedEvents.sumOf { it.expectedAmount }

        val rows = all.take(limit.coerceIn(1, MAX_LIMIT))
        val pharmacyNames = namesByIds(rows.map { it.pharmacyId }) { ids ->
            pharmacyRepository.findAllById(ids).associate { it.id to it.name }
        }
        val pharmacistNames = namesByIds(rows.map { it.pharmacistId }) { ids ->
            pharmacistRepository.findAllById(ids).associate { it.id to it.name }
        }
        // triggerSku — это наш productId товара-триггера; резолвим в имя для красивой строки.
        val triggerNames = namesByIds(rows.mapNotNull { it.triggerSku }) { ids ->
            productRepository.findAllById(ids).associate { it.id to it.name }
        }

        val events = rows.map { e ->
            RecommendationEventRowDto(
                id = e.id,
                shownAt = e.shownAt,
                kind = e.kindRaw,
                ruleId = e.ruleId,
                triggerName = e.triggerSku?.let { triggerNames[it] },
                recommendName = e.recommendName,
                recommendSku = e.recommendSku,
                pharmacyId = e.pharmacyId,
                pharmacyName = pharmacyNames[e.pharmacyId] ?: e.pharmacyId,
                pharmacistId = e.pharmacistId,
                pharmacistName = pharmacistNames[e.pharmacistId] ?: e.pharmacistId,
                amount = e.expectedAmount,
                converted = e.soldAt != null,
                soldAt = e.soldAt,
                secondsToSale = e.secondsToSale,
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
            events = events,
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
        private const val MAX_LIMIT = 500
    }
}
