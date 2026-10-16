package kz.epharm.posm.staff

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class StaffAuthCleanup(private val jdbc: JdbcTemplate) {
    /** Keep only a short operational window of unused proofs and expired grants, not a PII archive. */
    @Scheduled(cron="0 17 * * * *")
    @Transactional
    fun cleanExpired() {
        jdbc.update("DELETE FROM posm_staff_challenges WHERE expires_at<now()-interval '24 hours'")
        jdbc.update("DELETE FROM posm_staff_sessions WHERE expires_at<now()-interval '24 hours'")
        jdbc.update("DELETE FROM posm_staff_rate_limits WHERE window_start<now()-interval '24 hours'")
    }
}
