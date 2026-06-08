package kz.epharm.lift.dto

/**
 * Lift-аналитика (ТЗ §3.2 — Pilot vs Control).
 * Read-only агрегация поверх таблицы pharmacies. Своих таблиц нет.
 *
 * pValue == null — намеренно: настоящая статзначимость требует временных рядов
 * чеков (понедельная выборка pilot/control), которых в БД пока нет. Не выдаём
 * фейковое число — фронт показывает «недостаточно данных».
 */
data class LiftSummaryDto(
    val networkLiftPct: Double,
    val pValue: Double?,
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
