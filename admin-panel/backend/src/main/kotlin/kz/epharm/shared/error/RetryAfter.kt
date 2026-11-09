package kz.epharm.shared.error

import java.time.Duration
import java.time.Instant

/** Round up: a client must never be told to retry before the actual deadline. */
fun retryAfterSeconds(now: Instant, deadline: Instant): Long {
    val remaining = Duration.between(now, deadline)
    return (remaining.seconds + if (remaining.nano > 0) 1 else 0).coerceAtLeast(1)
}
