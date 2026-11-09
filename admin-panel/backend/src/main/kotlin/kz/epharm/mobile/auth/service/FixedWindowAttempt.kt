package kz.epharm.mobile.auth.service

import org.springframework.jdbc.core.JdbcTemplate

data class FixedWindowAttempt(val attempts: Int, val retryAfterSeconds: Long)

/** Atomic counter and deadline from the same PostgreSQL row/clock; caller owns the transaction. */
fun takeFixedWindowAttempt(jdbc: JdbcTemplate, scopeHash: String, windowSeconds: Long): FixedWindowAttempt =
    jdbc.query("""INSERT INTO posm_staff_rate_limits(scope_hash,window_start,attempts) VALUES (?,now(),1)
        ON CONFLICT(scope_hash) DO UPDATE SET
        attempts=CASE WHEN posm_staff_rate_limits.window_start<=now()-(? * interval '1 second') THEN 1 ELSE posm_staff_rate_limits.attempts+1 END,
        window_start=CASE WHEN posm_staff_rate_limits.window_start<=now()-(? * interval '1 second') THEN now() ELSE posm_staff_rate_limits.window_start END
        RETURNING attempts, GREATEST(1, CEIL(EXTRACT(EPOCH FROM (window_start + (? * interval '1 second') - clock_timestamp()))))::bigint AS retry_after_seconds""",
        { rs, _ -> FixedWindowAttempt(rs.getInt("attempts"), rs.getLong("retry_after_seconds")) },
        scopeHash, windowSeconds, windowSeconds, windowSeconds).single()
