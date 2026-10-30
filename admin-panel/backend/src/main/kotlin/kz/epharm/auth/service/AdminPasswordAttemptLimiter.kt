package kz.epharm.auth.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Shared fixed window: at most five password proofs per account per fifteen minutes. */
@Service
class AdminPasswordAttemptLimiter(private val jdbc: JdbcTemplate) {
    // A separate bean/transaction commits the attempt even when the password operation rolls back.
    // The caller must consume before acquiring its account row lock.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun consume(accountId: UUID): Boolean {
        val attempts = jdbc.queryForObject("""
            INSERT INTO admin_password_attempt_limits(account_id, window_started_at, attempts)
            VALUES (?, CURRENT_TIMESTAMP, 1)
            ON CONFLICT (account_id) DO UPDATE SET
                window_started_at = CASE
                    WHEN admin_password_attempt_limits.window_started_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'
                    THEN CURRENT_TIMESTAMP ELSE admin_password_attempt_limits.window_started_at END,
                attempts = CASE
                    WHEN admin_password_attempt_limits.window_started_at <= CURRENT_TIMESTAMP - INTERVAL '15 minutes'
                    THEN 1 ELSE LEAST(admin_password_attempt_limits.attempts + 1, 6) END
            RETURNING attempts
        """.trimIndent(), Int::class.java, accountId)!!
        return attempts <= 5
    }
}
