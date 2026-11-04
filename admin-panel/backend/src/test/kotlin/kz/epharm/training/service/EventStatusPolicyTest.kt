package kz.epharm.training.service

import kz.epharm.training.domain.OfflineEventStatus
import kz.epharm.training.entity.OfflineEventEntity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class EventStatusPolicyTest {

    private val now = Instant.parse("2026-11-04T10:00:00Z")

    @Test
    fun `past scheduled event is presented as completed`() {
        val event = event(
            status = OfflineEventStatus.scheduled,
            startsAt = Instant.parse("2026-11-03T08:00:00Z"),
            endsAt = Instant.parse("2026-11-03T10:00:00Z"),
        )

        assertThat(effectiveEventStatus(event, now)).isEqualTo(OfflineEventStatus.completed)
    }

    @Test
    fun `current scheduled event is presented as in progress`() {
        val event = event(
            status = OfflineEventStatus.scheduled,
            startsAt = Instant.parse("2026-11-04T09:00:00Z"),
            endsAt = Instant.parse("2026-11-04T11:00:00Z"),
        )

        assertThat(effectiveEventStatus(event, now)).isEqualTo(OfflineEventStatus.in_progress)
    }

    @Test
    fun `registration closes into scheduled before event starts`() {
        val event = event(
            status = OfflineEventStatus.registration,
            startsAt = Instant.parse("2026-11-05T09:00:00Z"),
            endsAt = Instant.parse("2026-11-05T11:00:00Z"),
            registrationDeadline = Instant.parse("2026-11-04T09:00:00Z"),
        )

        assertThat(effectiveEventStatus(event, now)).isEqualTo(OfflineEventStatus.scheduled)
    }

    @Test
    fun `explicit terminal state is never replaced by time`() {
        val event = event(
            status = OfflineEventStatus.cancelled,
            startsAt = Instant.parse("2026-11-04T09:00:00Z"),
            endsAt = Instant.parse("2026-11-04T11:00:00Z"),
        )

        assertThat(effectiveEventStatus(event, now)).isEqualTo(OfflineEventStatus.cancelled)
    }

    private fun event(
        status: OfflineEventStatus,
        startsAt: Instant,
        endsAt: Instant,
        registrationDeadline: Instant? = null,
    ) = OfflineEventEntity(
        startsAt = startsAt,
        endsAt = endsAt,
        registrationDeadline = registrationDeadline,
    ).also { it.status = status }
}
