package kz.epharm.dashboard.dto

import java.time.Instant

/**
 * Аналитика «Показано рекомендаций» (Задача 1 + 1.2) для дашборда HQ.
 * Конверсия показ→продажа и время до продажи за окно `app.analytics.window-days`.
 */
data class RecommendationAnalyticsDto(
    val windowDays: Long,
    val shown: Int,                    // всего показано рекомендаций за период
    val converted: Int,                // из них закрыто продажей рекомендованного товара
    val convRate: Double,              // converted / shown, %
    val convertedUnder2m: Int,         // продано в пределах 2 минут после показа (Задача 1)
    val avgSecondsToSale: Int?,        // среднее время до продажи, сек (Задача 1.2)
    val medianSecondsToSale: Int?,     // медиана времени до продажи, сек
    val attributedRevenue: Long,       // сумма expected_amount по проданным рекомендациям
    val buckets: List<TimeBucketDto>,  // распределение времени до продажи
    val events: List<RecommendationEventRowDto>, // последние N показов «красивой строкой лога»
)

/** Корзина распределения времени до продажи (например «< 30 сек», «30 сек – 2 мин»). */
data class TimeBucketDto(val label: String, val count: Int)

/** Одна показанная рекомендация — строка «лога» в красивой таблице админки. */
data class RecommendationEventRowDto(
    val id: String,
    val shownAt: Instant,         // когда показана
    val kind: String,             // substitution | crosssell
    val ruleId: String,
    val triggerName: String?,     // что в чеке (товар-триггер), резолв из productId
    val recommendName: String,    // что рекомендовали
    val recommendSku: String,
    val pharmacyId: String,
    val pharmacyName: String,
    val pharmacistId: String,
    val pharmacistName: String,
    val amount: Long,             // ожидаемая сумма (expected_amount рекомендованного товара)
    val converted: Boolean,       // продан ли рекомендованный товар
    val soldAt: Instant?,         // когда продан
    val secondsToSale: Int?,      // через сколько секунд после показа продан
)
