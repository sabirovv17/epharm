package kz.epharm.merch.placements

import kz.epharm.merch.placements.service.KazakhstanReservationCalendar
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class KazakhstanReservationCalendarTest {
    @Test fun `Friday submission preserves local time and skips the weekend`() {
        assertThat(KazakhstanReservationCalendar.deadline(Instant.parse("2026-10-30T18:30:00Z")))
            .isEqualTo(Instant.parse("2026-11-04T18:30:00Z"))
    }
    @Test fun `Kazakhstan Friday already began while UTC is still Thursday`() {
        assertThat(KazakhstanReservationCalendar.deadline(Instant.parse("2026-10-29T20:30:00Z")))
            .isEqualTo(Instant.parse("2026-11-03T20:30:00Z"))
    }
}
