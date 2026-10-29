package kz.epharm.mobile.auth.reset

import kz.epharm.auth.service.RefreshTokenService
import kz.epharm.mobile.auth.dto.MobileAuthResponse
import kz.epharm.mobile.auth.service.*
import kz.epharm.posm.staff.StaffPhoneLock
import kz.epharm.shared.PhoneUtil
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Reuses the configured sender/verifier; never treats a generic verified phone as a reset grant. */
@Service
class MobilePasswordResetService(private val jdbc: JdbcTemplate, private val provider: OtpProvider,
    private val phoneLock: StaffPhoneLock, private val encoder: PasswordEncoder,
    private val mobileAuth: MobileAuthService, private val refresh: MobileRefreshTokenService,
    @Value("\${app.otp.dev-mode:true}") private val devMode: Boolean,
    @Value("\${app.otp.dev-fixed:5445}") private val devCode: String,
    @Value("\${app.jwt.secret}") private val codeSecret: String) {
    private val random = SecureRandom()

    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=[AppException::class])
    fun request(iin: String, remoteAddress: String): MobilePasswordResetRequested {
        val now = Instant.now()
        limit("request-iin:$iin", 5, 3600, now)
        limit("request-address:$remoteAddress", 100, 3600, now)
        val before = person(iin)
        val phone = before?.phone?.let { runCatching { PhoneUtil.normalize(it) }.getOrNull() }
            ?.takeIf { Regex("\\+7[0-9]{10}").matches(it) }
        phone?.let {
            phoneLock.lock(it)
            limit("request-phone:$it", 1, 60, now)
        }
        val current = person(iin, true)
        val eligible = phone != null && current != null && current.id == before?.id &&
            current.status in setOf("active", "pending") && !current.password.isNullOrBlank() &&
            runCatching { PhoneUtil.normalize(current.phone) }.getOrNull() == phone
        val id = UUID.randomUUID()
        val code = if (provider.verificationMode == OtpVerificationMode.LOCAL)
            (if (devMode) devCode else random.nextInt(10000).toString().padStart(4, '0')) else null
        if (eligible) {
            phoneLock.limitSharedSms(phone!!, now)
            phoneLock.invalidateStaff(phone)
            jdbc.update("DELETE FROM mobile_otps WHERE phone=?", phone)
        }
        jdbc.update("""INSERT INTO mobile_password_reset_challenges(id,iin_hash,pharmacist_id,phone,password_fingerprint,
            eligible,provider,code_hash,expires_at) VALUES (?,?,?,?,?,?,?,?,?)""", id, hash(iin),
            if (eligible) current!!.id else null, phone, if (eligible) hash(current!!.password!!) else null,
            eligible, provider.id, code?.let { codeMac(id, it) }, Timestamp.from(now.plusSeconds(300)))
        if (eligible) try { provider.requestOtp(phone!!, code) } catch (error: Exception) {
            jdbc.update("UPDATE mobile_password_reset_challenges SET consumed_at=now() WHERE id=?", id)
            throw unavailable()
        }
        // Do not enumerate existing, blocked or unactivated accounts through this public endpoint.
        return MobilePasswordResetRequested(id, MASKED_PHONE)
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=[AppException::class])
    fun verify(request: VerifyMobilePasswordReset): MobilePasswordResetVerified {
        val challenge = lockChallenge(request.challengeId)
        val now = Instant.now()
        requireChallenge(challenge, request.iin, now)
        if (challenge.verified != null) invalid()
        if (challenge.attempts >= 5) throw AppException(ErrorCode.OTP_TOO_MANY_ATTEMPTS,
            "Слишком много попыток — запросите новый код", HttpStatus.TOO_MANY_REQUESTS)
        jdbc.update("UPDATE mobile_password_reset_challenges SET attempts=attempts+1 WHERE id=?", challenge.id)
        requireCurrentPerson(challenge)
        val result = if (provider.verificationMode == OtpVerificationMode.LOCAL) {
            if (MessageDigest.isEqual(codeMac(challenge.id, request.code).toByteArray(), challenge.codeHash.orEmpty().toByteArray())) OtpVerificationResult.VERIFIED else OtpVerificationResult.INVALID
        } else try { provider.verifyOtp(challenge.phone!!, request.code) } catch (error: Exception) {
            jdbc.update("UPDATE mobile_password_reset_challenges SET attempts=attempts-1 WHERE id=?", challenge.id)
            throw unavailable()
        }
        if (result == OtpVerificationResult.EXPIRED) throw AppException(ErrorCode.OTP_EXPIRED,
            "Срок действия кода истёк — запросите новый", HttpStatus.BAD_REQUEST)
        if (result != OtpVerificationResult.VERIFIED) invalid()
        val verifiedNow = Instant.now()
        requireChallenge(challenge, request.iin, verifiedNow)
        requireCurrentPerson(challenge)
        val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val expires = verifiedNow.plusSeconds(300)
        jdbc.update("""UPDATE mobile_password_reset_challenges SET verified_at=?,reset_token_hash=?,reset_expires_at=? WHERE id=?""",
            Timestamp.from(verifiedNow), hash(token), Timestamp.from(expires), challenge.id)
        return MobilePasswordResetVerified(token, expires, PhoneUtil.mask(challenge.phone!!))
    }

    @Transactional
    fun complete(request: CompleteMobilePasswordReset): MobileAuthResponse {
        if (request.password.length !in 8..128 || request.password.toByteArray(Charsets.UTF_8).size > 72)
            throw AppException(ErrorCode.VALIDATION_FAILED, "Пароль: от 8 символов и не более 72 байт UTF-8")
        val before = jdbc.queryForList("SELECT id FROM mobile_password_reset_challenges WHERE reset_token_hash=?", UUID::class.java, hash(request.resetToken))
            .singleOrNull() ?: invalid()
        val challenge = lockChallenge(before)
        val now = Instant.now()
        if (challenge.consumed || !challenge.eligible || challenge.iinHash != hash(request.iin) ||
            challenge.verified == null || challenge.resetExpires?.isAfter(now) != true ||
            challenge.resetTokenHash != hash(request.resetToken)) invalid()
        val person = requireCurrentPerson(challenge)
        jdbc.update("UPDATE pharmacists SET password_hash=?,updated_at=now() WHERE id=?", encoder.encode(request.password), person.id)
        jdbc.update("UPDATE mobile_password_reset_challenges SET consumed_at=now() WHERE id=?", challenge.id)
        phoneLock.invalidateStaff(challenge.phone!!)
        jdbc.update("DELETE FROM mobile_otps WHERE phone=?", challenge.phone)
        jdbc.update("UPDATE posm_staff_sessions SET revoked_at=now() WHERE pharmacist_id=? AND revoked_at IS NULL", person.id)
        refresh.revokeAllForPharmacist(person.id)
        return mobileAuth.issueAfterVerifiedPasswordReset(person.id)
    }

    private fun requireChallenge(c: Challenge, iin: String, now: Instant) {
        if (c.consumed || !c.eligible || c.iinHash != hash(iin) || c.provider != provider.id) invalid()
        if (!c.expires.isAfter(now)) throw AppException(ErrorCode.OTP_EXPIRED, "Срок действия кода истёк — запросите новый", HttpStatus.BAD_REQUEST)
    }
    private fun requireCurrentPerson(c: Challenge): Person {
        val p = jdbc.query("SELECT id,iin,phone,password_hash,status FROM pharmacists WHERE id=? FOR UPDATE", { rs, _ ->
            Person(rs.getString("id"), rs.getString("iin"), rs.getString("phone"), rs.getString("password_hash"), rs.getString("status"))
        }, c.person).singleOrNull() ?: invalid()
        if (p.status !in setOf("active", "pending") || p.password.isNullOrBlank() || hash(p.iin) != c.iinHash ||
            hash(p.password) != c.fingerprint || runCatching { PhoneUtil.normalize(p.phone) }.getOrNull() != c.phone) invalid()
        return p
    }
    private fun person(iin: String, lock: Boolean = false): Person? = jdbc.query(
        "SELECT id,iin,phone,password_hash,status FROM pharmacists WHERE iin=?" + if (lock) " FOR UPDATE" else "",
        { rs, _ -> Person(rs.getString("id"), rs.getString("iin"), rs.getString("phone"), rs.getString("password_hash"), rs.getString("status")) }, iin).singleOrNull()
    // All domains acquire phone before challenge/person rows to avoid opposite lock ordering.
    private fun lockChallenge(id: UUID): Challenge {
        val before = challenge(id, false) ?: invalid()
        before.phone?.let(phoneLock::lock)
        return challenge(id, true) ?: invalid()
    }
    private fun challenge(id: UUID, lock: Boolean): Challenge? = jdbc.query(
        "SELECT * FROM mobile_password_reset_challenges WHERE id=?" + if (lock) " FOR UPDATE" else "", { rs, _ ->
            Challenge(id, rs.getString("iin_hash"), rs.getString("pharmacist_id"), rs.getString("phone"), rs.getString("password_fingerprint"),
                rs.getBoolean("eligible"), rs.getString("provider"), rs.getString("code_hash"), rs.getInt("attempts"),
                rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("verified_at")?.toInstant(), rs.getString("reset_token_hash"),
                rs.getTimestamp("reset_expires_at")?.toInstant(), rs.getTimestamp("consumed_at") != null)
        }, id).singleOrNull()
    private fun limit(scope: String, max: Int, seconds: Long, now: Instant) {
        val count = jdbc.queryForObject("""INSERT INTO mobile_password_reset_rate_limits(scope_hash,window_start,attempts) VALUES (?,?,1)
            ON CONFLICT(scope_hash) DO UPDATE SET attempts=CASE WHEN mobile_password_reset_rate_limits.window_start<=?
            THEN 1 ELSE mobile_password_reset_rate_limits.attempts+1 END,window_start=CASE WHEN mobile_password_reset_rate_limits.window_start<=?
            THEN EXCLUDED.window_start ELSE mobile_password_reset_rate_limits.window_start END RETURNING attempts""", Int::class.java,
            hash(scope), Timestamp.from(now), Timestamp.from(now.minusSeconds(seconds)), Timestamp.from(now.minusSeconds(seconds)))!!
        if (count > max) throw AppException(ErrorCode.OTP_RESEND_TOO_SOON, "Повторите запрос позже", HttpStatus.TOO_MANY_REQUESTS)
    }
    private data class Person(val id: String, val iin: String, val phone: String, val password: String?, val status: String)
    private data class Challenge(val id: UUID, val iinHash: String, val person: String?, val phone: String?, val fingerprint: String?,
        val eligible: Boolean, val provider: String, val codeHash: String?, val attempts: Int, val expires: Instant,
        val verified: Instant?, val resetTokenHash: String?, val resetExpires: Instant?, val consumed: Boolean)
    private fun hash(value: String) = RefreshTokenService.hash(value)
    private fun codeMac(id: UUID, code: String) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(codeSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        doFinal("mobile-password-reset\n$id\n$code".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun invalid(): Nothing = throw AppException(ErrorCode.OTP_INVALID, "Код или подтверждение недействительны — запросите новый", HttpStatus.BAD_REQUEST)
    private fun unavailable() = AppException(ErrorCode.OTP_PROVIDER_UNAVAILABLE, "Сервис SMS временно недоступен. Повторите позже.", HttpStatus.SERVICE_UNAVAILABLE)
    companion object { private const val MASKED_PHONE = "+7 (***) ***-**-**" }
}
