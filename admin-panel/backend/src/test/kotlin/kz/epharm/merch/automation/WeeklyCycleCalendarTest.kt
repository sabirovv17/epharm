package kz.epharm.merch.automation

import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WeeklyCycleCalendarTest {
    private val month=LocalDate.of(2026,11,1)
    @Test fun `future placement has a preparation deadline at Kazakhstan month midnight`() {
        val now=Instant.parse("2026-10-20T12:00:00Z")
        val first=WeeklyCycleCalendar.first(month,now.minusSeconds(3600),now)!!
        assertThat(first.preparation).isTrue();assertThat(first.startsAt).isEqualTo(now)
        assertThat(first.dueAt).isEqualTo(Instant.parse("2026-10-31T19:00:00Z"));assertThat(first.nextAt).isEqualTo(first.dueAt)
    }
    @Test fun `late current month approval anchors to actual approval and uses seven calendar days`() {
        val approval=Instant.parse("2026-11-10T11:00:00Z")
        val first=WeeklyCycleCalendar.first(month,approval,approval.plusSeconds(60))!!
        assertThat(first.startsAt).isEqualTo(approval);assertThat(first.dueAt).isEqualTo(approval.plusSeconds(604800))
        assertThat(WeeklyCycleCalendar.next(month,first,first.dueAt.minusSeconds(1))).isNull()
        assertThat(WeeklyCycleCalendar.next(month,first,first.dueAt)!!.number).isEqualTo(2)
    }
    @Test fun `final partial week is capped and an ended placement never receives a new cycle`() {
        val approval=Instant.parse("2026-11-29T12:00:00Z")
        val first=WeeklyCycleCalendar.first(month,approval,approval)!!
        assertThat(first.dueAt).isEqualTo(Instant.parse("2026-11-30T19:00:00Z"));assertThat(first.nextAt).isNull()
        assertThat(WeeklyCycleCalendar.first(month,approval,first.dueAt)).isNull()
        assertThat(WeeklyCycleCalendar.next(month,first,first.dueAt)).isNull()
    }
}
