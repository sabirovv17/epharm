package kz.epharm.posm.staff

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Value
import java.time.Instant
import java.sql.Timestamp
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus

/** Daribar codes are phone-wide: serialize issuance across all application purposes. */
@Component
class StaffPhoneLock(private val jdbc: JdbcTemplate,
    @Value("\${app.merch-rewards.enabled:false}") private val merchRewardsEnabled: Boolean = false) {
    fun lock(phone: String) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 718094))", phone)
    }
    fun invalidateStaff(phone: String) {
        jdbc.update("UPDATE posm_staff_challenges SET consumed_at=now() WHERE phone=? AND consumed_at IS NULL",phone)
        if (merchRewardsEnabled) jdbc.update("UPDATE merch_executor_otp_challenges SET consumed_at=now() WHERE phone=? AND consumed_at IS NULL",phone)
    }
    /** Opt-in money verification shares Daribar's phone-wide issuance budget with login. */
    fun limitSharedSms(phone: String, now: Instant = Instant.now()) {
        if (!merchRewardsEnabled) return
        val key = kz.epharm.finance.merch.MerchRewardSecurity.hash("sms-shared-phone:$phone")
        val last = jdbc.query("SELECT window_start FROM posm_staff_rate_limits WHERE scope_hash=?", { rs, _ -> rs.getTimestamp(1).toInstant() }, key).singleOrNull()
        if (last != null && last.plusSeconds(60).isAfter(now)) {
            throw AppException(ErrorCode.OTP_RESEND_TOO_SOON,"Запросите новый код через минуту",HttpStatus.TOO_MANY_REQUESTS)
        }
        jdbc.update("""INSERT INTO posm_staff_rate_limits(scope_hash,window_start,attempts) VALUES (?,?,1)
            ON CONFLICT(scope_hash) DO UPDATE SET window_start=EXCLUDED.window_start,attempts=1""",key,Timestamp.from(now))
    }
}
