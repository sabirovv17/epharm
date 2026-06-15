package kz.epharm.lift

import kz.epharm.lift.service.LiftStatistics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Unit-тесты честной lift-статистики (без Spring): формула lift + z-тест. */
class LiftStatisticsTest {

    @Test
    fun `lift = относительный прирост конверсии pilot vs control`() {
        // pilot 0.6 (60/100), control 0.4 (40/100) → (0.6-0.4)/0.4 = 50%
        val r = LiftStatistics.compute(
            pilot = LiftStatistics.Group(impressions = 100, accepts = 60),
            control = LiftStatistics.Group(impressions = 100, accepts = 40),
        )
        assertEquals(50.0, r.liftPct, 0.01)
        assertTrue(r.sufficient)
        assertNotNull(r.pValue)
    }

    @Test
    fun `большая разница на больших выборках статистически значима`() {
        val r = LiftStatistics.compute(
            pilot = LiftStatistics.Group(impressions = 1000, accepts = 600),
            control = LiftStatistics.Group(impressions = 1000, accepts = 400),
        )
        assertNotNull(r.pValue)
        assertTrue(r.pValue!! < 0.05, "ожидали значимость, p=${r.pValue}")
    }

    @Test
    fun `одинаковая конверсия — lift 0, p-value близко к 1`() {
        val r = LiftStatistics.compute(
            pilot = LiftStatistics.Group(impressions = 200, accepts = 100),
            control = LiftStatistics.Group(impressions = 200, accepts = 100),
        )
        assertEquals(0.0, r.liftPct, 0.01)
        assertNotNull(r.pValue)
        assertTrue(r.pValue!! > 0.9, "ожидали p≈1, p=${r.pValue}")
    }

    @Test
    fun `нулевая конверсия контроля — lift неопределён, недостаточно данных`() {
        // control 0/100 (0%), pilot 50/100 — относительный lift = деление на 0 → не выдаём число.
        val r = LiftStatistics.compute(
            pilot = LiftStatistics.Group(impressions = 100, accepts = 50),
            control = LiftStatistics.Group(impressions = 100, accepts = 0),
        )
        assertFalse(r.sufficient)
        assertNull(r.pValue)
        assertEquals(0.0, r.liftPct, 0.01)
    }

    @Test
    fun `нет показов в группе — недостаточно данных, p-value null`() {
        val r = LiftStatistics.compute(
            pilot = LiftStatistics.Group(impressions = 0, accepts = 0),
            control = LiftStatistics.Group(impressions = 100, accepts = 40),
        )
        assertFalse(r.sufficient)
        assertNull(r.pValue)
        assertEquals(0.0, r.liftPct, 0.01)
    }
}
