package kz.epharm.merch.placements.service

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** Source079/080: Mon–Fri in Almaty, preserving local submission time; no holiday calendar was supplied. */
object KazakhstanReservationCalendar {
    val timezone: ZoneId = ZoneId.of("Asia/Almaty")
    fun deadline(submitted: Instant): Instant {
        var local = submitted.atZone(timezone)
        var remaining = 3
        while (remaining > 0) {
            local = local.plusDays(1)
            if (local.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) remaining--
        }
        return local.toInstant()
    }
}
