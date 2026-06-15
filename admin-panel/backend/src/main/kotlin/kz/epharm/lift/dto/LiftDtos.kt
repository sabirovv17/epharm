package kz.epharm.lift.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Lift-аналитика (ТЗ §3.2 — Pilot vs Control). РЕАЛЬНЫЕ данные:
 * конверсия = принятые рекомендации / показы (из recommendation_events),
 * networkLiftPct = (convPilot − convControl) / convControl × 100,
 * pValue — двусторонний z-тест пропорций (см. [kz.epharm.lift.service.LiftStatistics]).
 *
 * insufficientData == true и pValue == null — когда нет показов в pilot и/или control
 * (кассы ещё не размечены на группы / нет событий). Фронт показывает «недостаточно
 * данных» вместо фейкового числа.
 */
data class LiftSummaryDto(
    val networkLiftPct: Double,
    // Иначе Jackson сериализует pValue → "pvalue"; фронт ждёт camelCase "pValue".
    @param:JsonProperty("pValue") @get:JsonProperty("pValue") val pValue: Double?,
    val insufficientData: Boolean,
    val pilotCount: Int,
    val controlCount: Int,
    val rolledCount: Int,
    val pilotReceipts30d: Int,
    val controlReceipts30d: Int,
    val segments: List<LiftSegmentDto>,
)

data class LiftSegmentDto(
    val name: String,
    val liftPct: Double,
    val pharmacies: Int,
)
