package kz.epharm.dashboard.dto

import java.time.Instant

/**
 * Аналитика «Показы и продажи» (Задача 1 + 1.2) для дашборда HQ.
 * KPI конверсии показ→продажа + время до продажи + единый журнал событий из двух таблиц
 * (recommendation_events = показы, pos_sales = продажи), всё с точным временем.
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
    val log: List<LogEntryDto>,        // единый журнал: показы + продажи, свежие первыми
)

/** Корзина распределения времени до продажи (например «< 30 сек», «30 сек – 2 мин»). */
data class TimeBucketDto(val label: String, val count: Int)

/**
 * Одна строка журнала — показ рекомендации ИЛИ продажа (чек). Простой лог из двух наших таблиц
 * с точным временем; на фронте рисуется одной таблицей.
 */
data class LogEntryDto(
    val id: String,
    val type: String,             // "show" (показ) | "sale" (продажа)
    val at: Instant,              // точное время события
    val title: String,            // что: рекомендованный товар (показ) / состав чека (продажа)
    val pharmacyId: String,
    val pharmacyName: String,
    val pharmacistId: String,
    val pharmacistName: String,
    val amount: Long,             // показ: expected_amount; продажа: total_amount
    val converted: Boolean,       // показ: продан ли рекомендованный товар
    val secondsToSale: Int?,      // показ: через сколько секунд продан
)
