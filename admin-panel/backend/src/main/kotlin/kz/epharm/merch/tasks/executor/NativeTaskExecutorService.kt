package kz.epharm.merch.tasks.executor

import com.fasterxml.jackson.annotation.JsonAnySetter
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kz.epharm.auth.access.UnifiedAuthorityLock
import kz.epharm.finance.merch.MerchRewardSecurity
import kz.epharm.finance.merch.VerifiedMerchRecipientPolicy
import kz.epharm.merch.delivery.NativeCapabilityAuthorization
import kz.epharm.mobile.auth.service.OtpProvider
import kz.epharm.mobile.auth.service.OtpVerificationMode
import kz.epharm.mobile.auth.service.OtpVerificationResult
import kz.epharm.posm.staff.StaffPhoneLock
import kz.epharm.shared.PhoneUtil
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

data class NativeExecutorContext(val dispatchId:UUID,val taskTitle:String,val pharmacyName:String,val amountKzt:Long,
    val dueAt:Instant,val challengeExpiresAt:Instant,val phoneMasked:String,val bindingId:UUID?,val currency:String="KZT")
data class NativeExecutorSmsChallenge(val otpChallengeId:UUID,val phoneMasked:String,val expiresAt:Instant,val retryAfterSeconds:Int=60)
data class VerifyNativeExecutorSms(val otpChallengeId:UUID,val code:String) {
    @JsonAnySetter fun rejectUnknown(key:String,value:Any?) {throw IllegalArgumentException("Unsupported verification field")}
}
data class NativeExecutorBinding(val bindingId:UUID,val dispatchId:UUID,val pharmacistId:String,val pharmacyId:String,val duplicate:Boolean)

/** A native OTP authorizes one immutable dispatch recipient; it is neither a CRM grant nor a wallet posting. */
@Service
class NativeTaskExecutorService(private val jdbc:JdbcTemplate,private val capabilities:NativeCapabilityAuthorization,
    private val recipients:VerifiedMerchRecipientPolicy,private val phoneLock:StaffPhoneLock,private val provider:OtpProvider,
    private val security:MerchRewardSecurity,
    @Value("\${app.merch.native-executor-enabled:false}") private val enabled:Boolean,
    @Value("\${app.otp.dev-mode:true}") private val devMode:Boolean,
    @Value("\${app.jwt.secret}") private val serverSecret:String,
    private val clock:Clock=Clock.systemUTC()) {
    private data class Context(val dispatch:UUID,val link:UUID,val branch:UUID,val pharmacist:String,val person:UUID,val pharmacy:String,
        val title:String,val pharmacyName:String,val phone:String,val amount:Long,val due:Instant,val expiry:Instant,val binding:UUID?) {
        // Length framing prevents opaque source IDs and timestamps from changing field boundaries.
        val hash:String get()=MerchRewardSecurity.hash(listOf("epharm.native-merch.executor.v1",link.toString(),dispatch.toString(),
            branch.toString(),pharmacist,person.toString(),pharmacy,MerchRewardSecurity.hash(phone),amount.toString(),due.toString(),expiry.toString())
            .joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" })
        fun view()=NativeExecutorContext(dispatch,title,pharmacyName,amount,due,expiry,PhoneUtil.mask(phone),binding)
    }
    private data class Otp(val hash:String,val pharmacist:String,val phone:String,val provider:String,val codeHash:String?,
        val attempts:Int,val created:Instant,val expires:Instant,val consumed:Boolean)
    private data class Binding(val id:UUID,val pharmacist:String,val pharmacy:String,val amount:Long,val due:Instant)
    private val random=SecureRandom()

    @Transactional(readOnly=true)
    fun context(dispatch:UUID,pharmacist:String,rawToken:String):NativeExecutorContext {
        gate()
        return load(dispatch,pharmacist,rawToken,false).view()
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=[AppException::class])
    fun requestSms(dispatch:UUID,pharmacist:String,rawToken:String):NativeExecutorSmsChallenge {
        gate()
        UnifiedAuthorityLock.acquire(jdbc)
        val initial=load(dispatch,pharmacist,rawToken,true,lockRecipient=false)
        phoneLock.lock(initial.phone)
        val ctx=load(dispatch,pharmacist,rawToken,true)
        if(ctx.hash!=initial.hash) invalidOtp()
        if(ctx.binding!=null) conflict("Исполнитель уже подтверждён: обновите задание")
        val now=clock.instant()
        phoneLock.limitSharedSms(ctx.phone,now)
        phoneLock.invalidateStaff(ctx.phone)
        jdbc.update("DELETE FROM mobile_otps WHERE phone=?",ctx.phone)
        val id=UUID.randomUUID()
        val code=if(provider.verificationMode==OtpVerificationMode.LOCAL) random.nextInt(1_000_000).toString().padStart(6,'0') else null
        val expires=minOf(now.plusSeconds(300),ctx.expiry,ctx.due)
        if(!expires.isAfter(now)) expired()
        jdbc.update("""INSERT INTO merch_executor_otp_challenges(id,challenge_hash,pharmacist_id,phone,provider,code_hash,created_at,expires_at)
            VALUES(?,?,?,?,?,?,?,?)""",id,ctx.hash,ctx.pharmacist,ctx.phone,provider.id,code?.let {security.otpDigest(id,ctx.phone,it)},Timestamp.from(now),Timestamp.from(expires))
        try {
            provider.requestOtp(ctx.phone,code)
            val fresh=load(dispatch,pharmacist,rawToken,true)
            if(fresh.hash!=ctx.hash || !expires.isAfter(clock.instant())) expired()
        } catch(error:Exception) {
            consume(id)
            phoneLock.invalidateStaff(ctx.phone)
            if(error is AppException) throw error
            unavailable()
        }
        return NativeExecutorSmsChallenge(id,PhoneUtil.mask(ctx.phone),expires)
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=[AppException::class])
    fun verifySms(dispatch:UUID,pharmacist:String,rawToken:String,request:VerifyNativeExecutorSms):NativeExecutorBinding {
        gate()
        UnifiedAuthorityLock.acquire(jdbc)
        val initial=load(dispatch,pharmacist,rawToken,true,lockRecipient=false)
        phoneLock.lock(initial.phone)
        val ctx=load(dispatch,pharmacist,rawToken,true)
        if(ctx.hash!=initial.hash || !request.code.matches(Regex("[0-9]{4,8}"))) invalidOtp()
        val otp=jdbc.query("SELECT * FROM merch_executor_otp_challenges WHERE id=? FOR UPDATE",{rs,_->Otp(rs.getString("challenge_hash"),
            rs.getString("pharmacist_id"),rs.getString("phone"),rs.getString("provider"),rs.getString("code_hash"),rs.getInt("attempts"),
            rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("expires_at").toInstant(),rs.getTimestamp("consumed_at")!=null)},request.otpChallengeId).singleOrNull()?:invalidOtp()
        if(otp.hash!=ctx.hash || otp.pharmacist!=ctx.pharmacist || otp.phone!=ctx.phone || otp.provider!=provider.id) invalidOtp()
        if(otp.consumed) return ctx.binding?.let { NativeExecutorBinding(it,dispatch,pharmacist,ctx.pharmacy,true) }?:invalidOtp()
        if(!otp.expires.isAfter(clock.instant())) {consume(request.otpChallengeId);expired()}
        if(otp.attempts>=5) attempts()
        jdbc.update("UPDATE merch_executor_otp_challenges SET attempts=attempts+1 WHERE id=?",request.otpChallengeId)
        val result=try {
            if(provider.verificationMode==OtpVerificationMode.LOCAL) {
                if(security.matchesOtp(request.otpChallengeId,ctx.phone,request.code,otp.codeHash)) OtpVerificationResult.VERIFIED else OtpVerificationResult.INVALID
            } else provider.verifyOtp(ctx.phone,request.code)
        } catch(_:Exception) {consume(request.otpChallengeId);unavailable()}
        if(result!=OtpVerificationResult.VERIFIED) {
            if(result==OtpVerificationResult.EXPIRED || otp.attempts+1>=5) consume(request.otpChallengeId)
            if(result==OtpVerificationResult.EXPIRED) expired()
            if(otp.attempts+1>=5) attempts()
            invalidOtp()
        }
        val verifiedAt=clock.instant()
        val fresh=try {load(dispatch,pharmacist,rawToken,true)} catch(error:AppException) {consume(request.otpChallengeId);throw error}
        if(!otp.expires.isAfter(verifiedAt) || !fresh.expiry.isAfter(verifiedAt) || !fresh.due.isAfter(verifiedAt)) {
            consume(request.otpChallengeId);expired()
        }
        if(fresh.hash!=ctx.hash) {consume(request.otpChallengeId);invalidOtp()}
        val existing=binding(dispatch)
        if(existing!=null) requireSame(existing,fresh)
        val id=existing?.id?:UUID.randomUUID().also {
            jdbc.update("""INSERT INTO merch_executor_bindings(id,dispatch_id,pharmacist_id,pharmacy_id,reward_kzt,task_due_at,verified_at,created_at)
                VALUES(?,?,?,?,?,?,?,?)""",it,dispatch,pharmacist,fresh.pharmacy,fresh.amount,Timestamp.from(fresh.due),Timestamp.from(verifiedAt),Timestamp.from(verifiedAt))
        }
        consume(request.otpChallengeId)
        phoneLock.invalidateStaff(fresh.phone)
        jdbc.update("DELETE FROM mobile_otps WHERE phone=?",fresh.phone)
        return NativeExecutorBinding(id,dispatch,pharmacist,fresh.pharmacy,existing!=null)
    }

    private fun load(dispatch:UUID,pharmacist:String,rawToken:String,locked:Boolean,lockRecipient:Boolean=locked):Context {
        val cap=if(locked) capabilities.requirePaidMutation(rawToken) else capabilities.authorize(rawToken,true)
        if(cap.dispatchId!=dispatch) notFound()
        if(cap.rewardAmountKzt<=0) forbidden()
        val task=jdbc.queryForList("""SELECT d.title,d.due_at,d.reward_amount_kzt,d.assigned_pharmacist_id,b.epharm_pharmacy_id
            FROM merch_task_dispatches d JOIN merch_branches b ON b.id=d.branch_id WHERE d.id=?""",dispatch).singleOrNull()?:notFound()
        val pharmacy=task["epharm_pharmacy_id"] as? String?:forbidden()
        if(task["assigned_pharmacist_id"]!=null && task["assigned_pharmacist_id"]!=pharmacist) forbidden()
        val due=(task["due_at"] as? Timestamp)?.toInstant()?:conflict("Срок задания не подтверждён: требуется сверка HQ")
        val amount=(task["reward_amount_kzt"] as Number).toLong()
        if(amount!=cap.rewardAmountKzt || !due.isAfter(clock.instant())) expired()
        val recipient=recipients.requireRecipient(pharmacist,pharmacy,lockRecipient)
        val person=jdbc.query("""SELECT l.person_id FROM pharmacist_person_links l JOIN unified_persons p ON p.id=l.person_id
            WHERE l.pharmacist_id=? AND length(btrim(l.verification_reference))>0 AND length(btrim(p.verification_reference))>0""",
            {rs,_->rs.getObject(1,UUID::class.java)},pharmacist).singleOrNull()?:forbidden()
        val existing=binding(dispatch)
        val ctx=Context(dispatch,cap.linkId,cap.branchId,pharmacist,person,pharmacy,task["title"] as String,recipient.pharmacyName,recipient.phone,amount,due,cap.expiresAt,existing?.id)
        if(existing!=null) requireSame(existing,ctx)
        return ctx
    }
    private fun binding(dispatch:UUID)=jdbc.query("SELECT * FROM merch_executor_bindings WHERE dispatch_id=?",{rs,_->Binding(rs.getObject("id",UUID::class.java),
        rs.getString("pharmacist_id"),rs.getString("pharmacy_id"),rs.getLong("reward_kzt"),rs.getTimestamp("task_due_at").toInstant())},dispatch).singleOrNull()
    private fun requireSame(binding:Binding,ctx:Context) {
        if(binding.pharmacist!=ctx.pharmacist || binding.pharmacy!=ctx.pharmacy || binding.amount!=ctx.amount || binding.due!=ctx.due)
            conflict("Задание уже связано с другим получателем, аптекой, суммой или сроком")
    }
    private fun consume(id:UUID) {jdbc.update("UPDATE merch_executor_otp_challenges SET consumed_at=coalesce(consumed_at,?) WHERE id=?",Timestamp.from(clock.instant()),id)}
    private fun gate() {if(!enabled || devMode || serverSecret.toByteArray(Charsets.UTF_8).size<32) throw AppException(
        ErrorCode.UPSTREAM_UNAVAILABLE,"Подтверждение получателя нативной задачи временно недоступно",HttpStatus.SERVICE_UNAVAILABLE)}
    private fun invalidOtp():Nothing=throw AppException(ErrorCode.OTP_INVALID,"Код недействителен: запросите новый")
    private fun expired():Nothing=throw AppException(ErrorCode.OTP_EXPIRED,"Срок подтверждения истёк: обновите задание и запросите новый код")
    private fun attempts():Nothing=throw AppException(ErrorCode.OTP_TOO_MANY_ATTEMPTS,"Слишком много попыток: запросите новый код",HttpStatus.TOO_MANY_REQUESTS)
    private fun unavailable():Nothing=throw AppException(ErrorCode.OTP_PROVIDER_UNAVAILABLE,"SMS-сервис временно недоступен",HttpStatus.SERVICE_UNAVAILABLE)
    private fun conflict(message:String):Nothing=throw AppException(ErrorCode.CONFLICT,message,HttpStatus.CONFLICT)
    private fun forbidden():Nothing=throw AppException(ErrorCode.FORBIDDEN,"Нужен подтверждённый получатель этой аптеки",HttpStatus.FORBIDDEN)
    private fun notFound():Nothing=throw AppException(ErrorCode.NOT_FOUND,"Задание не найдено",HttpStatus.NOT_FOUND)
}
