package kz.epharm.posm.staff

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** Daribar codes are phone-wide: serialize issuance across all application purposes. */
@Component
class StaffPhoneLock(private val jdbc: JdbcTemplate) {
    fun lock(phone: String) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 718094))", phone)
    }
    fun invalidateStaff(phone: String) {
        jdbc.update("UPDATE posm_staff_challenges SET consumed_at=now() WHERE phone=? AND consumed_at IS NULL",phone)
    }
}
