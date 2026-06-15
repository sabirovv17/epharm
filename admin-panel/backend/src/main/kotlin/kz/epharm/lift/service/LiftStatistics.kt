package kz.epharm.lift.service

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Статистика для AB-теста lift (pilot vs control) — честная замена прежнему
 * «среднему предзаписанного поля». Конверсия = принятые рекомендации / показы.
 *
 * Lift% = (convPilot − convControl) / convControl × 100.
 * p-value — двусторонний двухвыборочный z-тест пропорций (pooled). Считаем только
 * когда есть наблюдения в обеих группах; иначе null (не выдаём фейковую значимость).
 */
object LiftStatistics {

    data class Group(val impressions: Long, val accepts: Long) {
        val rate: Double get() = if (impressions > 0) accepts.toDouble() / impressions else 0.0
    }

    data class Result(val liftPct: Double, val pValue: Double?, val sufficient: Boolean)

    /**
     * @param pilot   суммарные показы/принятия пилотных аптек
     * @param control суммарные показы/принятия контрольных аптек
     */
    fun compute(pilot: Group, control: Group): Result {
        // Недостаточно данных: нет показов в одной из групп — lift не определён.
        if (pilot.impressions == 0L || control.impressions == 0L) {
            return Result(liftPct = 0.0, pValue = null, sufficient = false)
        }
        // Нулевая конверсия контроля → относительный lift = (cp−0)/0 неопределён,
        // а z-тест на нулевой базе ненадёжен. Не выдаём ни число, ни значимость.
        if (control.accepts == 0L) {
            return Result(liftPct = 0.0, pValue = null, sufficient = false)
        }
        val cp = pilot.rate
        val cc = control.rate
        val lift = (cp - cc) / cc * 100.0

        // Two-proportion z-test (pooled).
        val pooled = (pilot.accepts + control.accepts).toDouble() /
            (pilot.impressions + control.impressions).toDouble()
        val se = sqrt(
            pooled * (1.0 - pooled) *
                (1.0 / pilot.impressions + 1.0 / control.impressions),
        )
        val pValue = if (se == 0.0) {
            null
        } else {
            val z = (cp - cc) / se
            // Двусторонний: 2 * (1 − Φ(|z|)).
            2.0 * (1.0 - normalCdf(abs(z)))
        }
        return Result(liftPct = round1(lift), pValue = pValue?.let { round4(it) }, sufficient = true)
    }

    /** Функция распределения N(0,1) через аппроксимацию erf (Abramowitz–Stegun 7.1.26). */
    private fun normalCdf(x: Double): Double = 0.5 * (1.0 + erf(x / sqrt(2.0)))

    private fun erf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) *
            t * kotlin.math.exp(-x * x)
        return if (x >= 0) y else -y
    }

    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
    private fun round4(v: Double): Double = Math.round(v * 10000.0) / 10000.0
}
