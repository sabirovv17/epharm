package kz.epharm.training.service

import kz.epharm.training.domain.OfflineEventStatus
import kz.epharm.training.entity.OfflineEventEntity
import java.time.Instant

/**
 * Presents time-bound event states consistently without rewriting historical rows on read.
 * Explicit terminal and draft states always win; only active scheduling states are derived.
 */
internal fun effectiveEventStatus(
    event: OfflineEventEntity,
    now: Instant = Instant.now(),
): OfflineEventStatus = when (event.status) {
    OfflineEventStatus.draft,
    OfflineEventStatus.completed,
    OfflineEventStatus.cancelled,
    OfflineEventStatus.archived -> event.status

    OfflineEventStatus.registration,
    OfflineEventStatus.scheduled,
    OfflineEventStatus.in_progress -> when {
        !now.isBefore(event.endsAt) -> OfflineEventStatus.completed
        !now.isBefore(event.startsAt) -> OfflineEventStatus.in_progress
        event.status == OfflineEventStatus.registration &&
            event.registrationDeadline?.let { !now.isBefore(it) } == true -> OfflineEventStatus.scheduled
        event.status == OfflineEventStatus.in_progress -> OfflineEventStatus.scheduled
        else -> event.status
    }
}
