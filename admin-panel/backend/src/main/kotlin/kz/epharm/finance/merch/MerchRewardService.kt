package kz.epharm.finance.merch

import kz.epharm.auth.access.UnifiedAuthorityLock
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kz.epharm.finance.merch.MerchRewardSecurity.Companion.conflict
import kz.epharm.finance.merch.MerchRewardSecurity.Companion.hash
import kz.epharm.finance.merch.MerchRewardSecurity.Companion.invalid
import kz.epharm.finance.service.PharmacistWalletService
import kz.epharm.finance.service.WalletCreditSource
import kz.epharm.mobile.auth.service.OtpProvider
import kz.epharm.mobile.auth.service.OtpVerificationMode
import kz.epharm.mobile.auth.service.OtpVerificationResult
import kz.epharm.posm.staff.StaffPhoneLock
import kz.epharm.shared.PhoneUtil
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

data class MerchContextRequest(val crmChallenge: String)
data class MerchGrantRequest(val crmChallenge: String, val otpChallengeId: UUID, val code: String)
data class MerchClaimContext(val dispatchId: UUID, val taskTitle: String, val pharmacyName: String,
    val amountKzt: Long, val currency: String = "KZT", val challengeExpiresAt: Instant, val phoneMasked: String)
data class MerchOtpResponse(val otpChallengeId: UUID, val phoneMasked: String, val expiresAt: Instant, val retryAfterSeconds: Int = 60)
data class MerchGrantResponse(val grant: String, val expiresAt: Instant, val redirectUrl: String)
data class MerchRedeemRequest(val grant: String, val dispatchId: UUID, val pharmacyId: String, val nonce: String)
data class MerchBindingResponse(val bindingId: UUID, val pharmacistId: String, val dispatchId: UUID, val pharmacyId: String)
data class MerchRewardEvent(val schemaVersion: Int, val type: String, val eventId: UUID, val dispatchId: UUID,
    val bindingId: UUID, val pharmacyId: String, val amountKzt: Long, val currency: String,
    val submittedAt: Instant, val approvedAt: Instant, val approvedByCrmUserId: UUID)
data class MerchRewardAck(val eventId: UUID, val credited: Boolean = true, val duplicate: Boolean)

@Service
class MerchRewardService(
    private val jdbc: JdbcTemplate,
    private val security: MerchRewardSecurity,
    private val phoneLock: StaffPhoneLock,
    private val provider: OtpProvider,
    private val wallet: PharmacistWalletService,
    private val recipients: VerifiedMerchRecipientPolicy,
) {
    private val random = SecureRandom()

    @Transactional(readOnly = true)
    fun context(personId: String, token: String): MerchClaimContext {
        val claim = security.challenge(token)
        val person = person(personId, claim.pharmacyId)
        return MerchClaimContext(claim.dispatchId, claim.taskTitle, person.pharmacyName,
            claim.rewardKzt, challengeExpiresAt = claim.expiresAt, phoneMasked = PhoneUtil.mask(person.phone))
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = [AppException::class])
    fun requestOtp(personId: String, token: String): MerchOtpResponse {
        UnifiedAuthorityLock.acquire(jdbc)
        val claim = security.challenge(token)
        val initial = person(personId, claim.pharmacyId)
        phoneLock.lock(initial.phone)
        val person = person(personId, claim.pharmacyId, locked = true)
        if (person.phone != initial.phone) invalid()
        val now = security.currentTime()
        security.challenge(token,now)
        phoneLock.limitSharedSms(person.phone, now)
        phoneLock.invalidateStaff(person.phone)
        jdbc.update("DELETE FROM mobile_otps WHERE phone=?", person.phone)
        val id = UUID.randomUUID()
        val code = if (provider.verificationMode == OtpVerificationMode.LOCAL) random.nextInt(1_000_000).toString().padStart(6, '0') else null
        val expires = minOf(now.plusSeconds(300), claim.expiresAt)
        jdbc.update("""INSERT INTO merch_executor_otp_challenges
            (id,challenge_hash,pharmacist_id,phone,provider,code_hash,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?)""",
            id, hash(token), personId, person.phone, provider.id, code?.let { security.otpDigest(id,person.phone,it) }, Timestamp.from(now), Timestamp.from(expires))
        try {
            security.challenge(token)
            provider.requestOtp(person.phone, code)
        } catch (e: Exception) {
            phoneLock.invalidateStaff(person.phone)
            // Preserve invalidation/cooldown even for an unexpected transport exception.
            throw AppException(ErrorCode.SMS_SEND_FAILED, "Не удалось отправить код — попробуйте позже", HttpStatus.SERVICE_UNAVAILABLE)
        }
        return MerchOtpResponse(id, PhoneUtil.mask(person.phone), expires)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = [AppException::class])
    fun issueGrant(personId: String, request: MerchGrantRequest): MerchGrantResponse {
        UnifiedAuthorityLock.acquire(jdbc)
        val claim = security.challenge(request.crmChallenge)
        if (!request.code.matches(Regex("[0-9]{4,8}"))) invalidOtp()
        val initial = person(personId, claim.pharmacyId)
        phoneLock.lock(initial.phone)
        val person = person(personId, claim.pharmacyId, locked = true)
        if (person.phone != initial.phone) invalidOtp()
        val otp = jdbc.query("SELECT * FROM merch_executor_otp_challenges WHERE id=? FOR UPDATE", { rs, _ -> Otp(
            rs.getString("challenge_hash"), rs.getString("pharmacist_id"), rs.getString("phone"), rs.getString("provider"),
            rs.getString("code_hash"), rs.getInt("attempts"), rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("consumed_at") != null,
        ) }, request.otpChallengeId).singleOrNull() ?: invalidOtp()
        if (otp.consumed || otp.challengeHash != hash(request.crmChallenge) || otp.person != personId ||
            otp.phone != person.phone || otp.provider != provider.id) invalidOtp()
        if (!otp.expires.isAfter(security.currentTime())) throw AppException(ErrorCode.OTP_EXPIRED, "Срок действия кода истёк")
        if (otp.attempts >= 5) throw AppException(ErrorCode.OTP_TOO_MANY_ATTEMPTS, "Запросите новый код", HttpStatus.TOO_MANY_REQUESTS)
        val verification = when (provider.verificationMode) {
            OtpVerificationMode.LOCAL -> if (security.matchesOtp(request.otpChallengeId,person.phone,request.code,otp.codeHash)) OtpVerificationResult.VERIFIED else OtpVerificationResult.INVALID
            OtpVerificationMode.EXTERNAL -> provider.verifyOtp(person.phone, request.code)
        }
        if (verification != OtpVerificationResult.VERIFIED) {
            jdbc.update("UPDATE merch_executor_otp_challenges SET attempts=attempts+1 WHERE id=?", request.otpChallengeId)
            if (verification == OtpVerificationResult.EXPIRED) throw AppException(ErrorCode.OTP_EXPIRED, "Срок действия кода истёк")
            invalidOtp()
        }
        // A slow SMS provider cannot turn an expired task/challenge into a fresh grant.
        val verifiedAt = security.currentTime()
        if (!otp.expires.isAfter(verifiedAt)) throw AppException(ErrorCode.OTP_EXPIRED, "Срок действия кода истёк")
        security.challenge(request.crmChallenge,verifiedAt)
        // Explicit consumption is local to this proof; phone-wide invalidation also revokes other purposes.
        jdbc.update("UPDATE merch_executor_otp_challenges SET consumed_at=? WHERE id=?",Timestamp.from(verifiedAt),request.otpChallengeId)
        phoneLock.invalidateStaff(person.phone)
        jdbc.update("DELETE FROM mobile_otps WHERE phone=?", person.phone)
        val grant = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val expires = minOf(verifiedAt.plusSeconds(120), claim.taskDueAt)
        jdbc.update("""INSERT INTO merch_executor_grants(token_hash,otp_challenge_id,dispatch_id,pharmacist_id,
            pharmacy_id,phone_hash,nonce_hash,reward_kzt,task_due_at,verified_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            hash(grant), request.otpChallengeId, claim.dispatchId, personId, claim.pharmacyId, hash(person.phone), hash(claim.nonce),
            claim.rewardKzt, Timestamp.from(claim.taskDueAt), Timestamp.from(verifiedAt), Timestamp.from(expires))
        return MerchGrantResponse(grant, expires, "https://crm.inkar.kz/staff#claim=${claim.nonce}&grant=$grant")
    }

    @Transactional
    fun redeem(request: MerchRedeemRequest): MerchBindingResponse {
        UnifiedAuthorityLock.acquire(jdbc)
        security.requireEnabled()
        if (!request.grant.matches(Regex("[A-Za-z0-9_-]{43}")) || request.nonce.length != 43) invalid()
        lockDispatch(request.dispatchId)
        val grant = jdbc.query("SELECT * FROM merch_executor_grants WHERE token_hash=? FOR UPDATE", { rs, _ -> Grant(
            rs.getObject("dispatch_id", UUID::class.java), rs.getString("pharmacist_id"), rs.getString("pharmacy_id"),
            rs.getString("phone_hash"), rs.getString("nonce_hash"), rs.getLong("reward_kzt"), rs.getTimestamp("task_due_at").toInstant(),
            rs.getTimestamp("verified_at").toInstant(), rs.getTimestamp("expires_at").toInstant(), rs.getObject("binding_id", UUID::class.java),
        ) }, hash(request.grant)).singleOrNull() ?: invalid()
        if (grant.dispatch != request.dispatchId || grant.pharmacy != request.pharmacyId || grant.nonceHash != hash(request.nonce)) invalid()
        val person = person(grant.person, grant.pharmacy, locked = true)
        if (hash(person.phone) != grant.phoneHash) invalid()
        grant.binding?.let { return MerchBindingResponse(it, grant.person, grant.dispatch, grant.pharmacy) }
        val now = Instant.now()
        if (!grant.expires.isAfter(now) || !grant.due.isAfter(now)) invalid()
        val existing = binding(grant.dispatch)
        val id = if (existing != null) {
            if (existing.person != grant.person || existing.pharmacy != grant.pharmacy || existing.amount != grant.amount || existing.due != grant.due) conflict()
            existing.id
        } else UUID.randomUUID().also {
            jdbc.update("""INSERT INTO merch_executor_bindings(id,dispatch_id,pharmacist_id,pharmacy_id,reward_kzt,task_due_at,verified_at,created_at)
                VALUES (?,?,?,?,?,?,?,?)""", it, grant.dispatch, grant.person, grant.pharmacy, grant.amount, Timestamp.from(grant.due), Timestamp.from(grant.verified), Timestamp.from(now))
        }
        jdbc.update("UPDATE merch_executor_grants SET binding_id=?,redeemed_at=? WHERE token_hash=?", id, Timestamp.from(now), hash(request.grant))
        return MerchBindingResponse(id, grant.person, grant.dispatch, grant.pharmacy)
    }

    @Transactional
    fun reward(event: MerchRewardEvent, payload: ByteArray): MerchRewardAck {
        UnifiedAuthorityLock.acquire(jdbc)
        security.requireEnabled()
        if (event.schemaVersion != 1 || event.type != "merch_task_approved" || event.currency != "KZT" || event.amountKzt <= 0) invalid()
        // Event id serializes cross-dispatch reuse; dispatch id serializes different approval ids.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 817104))", event.eventId.toString())
        lockDispatch(event.dispatchId)
        val payloadHash = hash(payload)
        val existing = jdbc.query("SELECT payload_hash FROM merch_reward_events WHERE event_id=? OR dispatch_id=?",
            { rs, _ -> rs.getString(1) }, event.eventId, event.dispatchId)
        if (existing.isNotEmpty()) {
            if (existing.size != 1 || existing.single() != payloadHash) conflict()
            return MerchRewardAck(event.eventId, duplicate = true)
        }
        val binding = binding(event.dispatchId) ?: invalid()
        if (binding.id != event.bindingId || binding.pharmacy != event.pharmacyId || binding.amount != event.amountKzt) conflict()
        person(binding.person, binding.pharmacy, locked = true)
        if (event.submittedAt.isBefore(binding.created) || event.submittedAt.isAfter(binding.due) ||
            event.approvedAt.isBefore(event.submittedAt) || event.approvedAt.isAfter(Instant.now().plusSeconds(300))) invalid()
        val credit = wallet.credit(WalletCreditSource.MERCH_TASK, event.dispatchId.toString(), binding.person,
            event.amountKzt, "approval:${event.eventId}:$payloadHash")
        jdbc.update("""INSERT INTO merch_reward_events(event_id,dispatch_id,binding_id,pharmacist_id,pharmacy_id,
            amount_kzt,submitted_at,approved_at,approved_by_crm_user_id,payload_hash,wallet_entry_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            event.eventId,event.dispatchId,event.bindingId,binding.person,binding.pharmacy,event.amountKzt,
            Timestamp.from(event.submittedAt),Timestamp.from(event.approvedAt),event.approvedByCrmUserId,payloadHash,credit.entryId)
        return MerchRewardAck(event.eventId, duplicate = false)
    }

    private fun lockDispatch(dispatch: UUID) {
        UnifiedAuthorityLock.acquire(jdbc)
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 817105))", dispatch.toString())
    }
    private fun person(id: String, pharmacy: String, locked: Boolean = false) =
        recipients.requireRecipient(id, pharmacy, locked)
    private fun binding(dispatch: UUID) = jdbc.query("SELECT * FROM merch_executor_bindings WHERE dispatch_id=?", { rs, _ -> Binding(
        rs.getObject("id", UUID::class.java), rs.getString("pharmacist_id"), rs.getString("pharmacy_id"), rs.getLong("reward_kzt"),
        rs.getTimestamp("task_due_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
    ) }, dispatch).singleOrNull()
    private fun invalidOtp(): Nothing = throw AppException(ErrorCode.OTP_INVALID, "Код недействителен — запросите новый")
    private data class Otp(val challengeHash: String, val person: String, val phone: String, val provider: String,
        val codeHash: String?, val attempts: Int, val expires: Instant, val consumed: Boolean)
    private data class Grant(val dispatch: UUID, val person: String, val pharmacy: String, val phoneHash: String, val nonceHash: String,
        val amount: Long, val due: Instant, val verified: Instant, val expires: Instant, val binding: UUID?)
    private data class Binding(val id: UUID, val person: String, val pharmacy: String, val amount: Long, val due: Instant, val created: Instant)
}
