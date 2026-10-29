package kz.epharm.merch.producer.auth

import kz.epharm.mobile.auth.service.*
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
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Instant
import java.time.Clock
import java.util.UUID

@Service
class ProducerAuthService(private val jdbc:JdbcTemplate,private val provider:OtpProvider,private val phoneLock:StaffPhoneLock,
    private val tokens:ProducerTokenCodec,@Value("\${app.merch.producer-auth-enabled:false}") private val enabled:Boolean,
    private val clock:Clock=Clock.systemUTC()) {
    private val random=SecureRandom()
    private data class Customer(val id:UUID,val name:String?,val phone:String)
    private data class Challenge(val phone:String,val customer:UUID?,val eligible:Boolean,val provider:String,val hash:String?,
        val attempts:Int,val expires:Instant,val consumed:Boolean)

    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=[AppException::class])
    fun requestOtp(request:ProducerSmsRequest,clientAddress:String):ProducerSmsChallenge {
        gate()
        val phone=normalize(request.phone);val now=clock.instant()
        phoneLock.lock(phone)
        limit("phone:$phone",5,3600,now);limit("ip:${clientAddress.take(128)}",20,3600,now)
        val recent=jdbc.queryForObject("SELECT count(*) FROM merch_producer_challenges WHERE phone=? AND created_at>?",Long::class.java,phone,Timestamp.from(now.minusSeconds(60)))!!
        if(recent>0) throw AppException(ErrorCode.OTP_RESEND_TOO_SOON,"Запросите новый код через минуту",HttpStatus.TOO_MANY_REQUESTS)
        val customer=customers(phone).singleOrNull()
        if(customer!=null) {
            phoneLock.limitSharedSms(phone,now)
            phoneLock.invalidateStaff(phone)
            jdbc.update("UPDATE mobile_otps SET expires_at=?,verified_at=NULL WHERE phone=?",Timestamp.from(now),phone)
        } else jdbc.update("UPDATE merch_producer_challenges SET consumed_at=? WHERE phone=? AND consumed_at IS NULL",Timestamp.from(now),phone)
        val id=UUID.randomUUID()
        val code=if(provider.verificationMode==OtpVerificationMode.LOCAL) random.nextInt(1_000_000).toString().padStart(6,'0') else null
        jdbc.update("""INSERT INTO merch_producer_challenges(id,purpose,customer_id,phone,eligible,provider,code_hash,created_at,expires_at)
            VALUES(?,'login',?,?,?,?,?,?,?)""",id,customer?.id,phone,customer!=null,provider.id,code?.let {tokens.codeHash(id,it)},Timestamp.from(now),Timestamp.from(now.plusSeconds(300)))
        if(customer!=null) try { provider.requestOtp(phone,code) } catch(_:Exception) {
            jdbc.update("UPDATE merch_producer_challenges SET consumed_at=? WHERE id=?",Timestamp.from(now),id)
            unavailable()
        }
        return ProducerSmsChallenge(id,phoneMasked=PhoneUtil.mask(phone))
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=[AppException::class])
    fun verify(request:ProducerSmsVerify):ProducerAuthResponse {
        gate()
        val phone=jdbc.query("SELECT phone FROM merch_producer_challenges WHERE id=?",{rs,_->rs.getString(1)},request.challengeId).singleOrNull()?:invalidOtp()
        phoneLock.lock(phone)
        val now=clock.instant()
        val challenge=jdbc.query("SELECT * FROM merch_producer_challenges WHERE id=? AND purpose='login' FOR UPDATE",{rs,_->Challenge(rs.getString("phone"),rs.getObject("customer_id",UUID::class.java),
            rs.getBoolean("eligible"),rs.getString("provider"),rs.getString("code_hash"),rs.getInt("attempts"),rs.getTimestamp("expires_at").toInstant(),rs.getTimestamp("consumed_at")!=null)},request.challengeId).singleOrNull()?:invalidOtp()
        if(challenge.consumed || !challenge.expires.isAfter(now)) invalidOtp()
        if(challenge.attempts>=5) attempts()
        jdbc.update("UPDATE merch_producer_challenges SET attempts=attempts+1 WHERE id=?",request.challengeId)
        val customer=customers(phone).singleOrNull()
        if(!challenge.eligible || customer?.id!=challenge.customer || challenge.provider!=provider.id) invalidOtp()
        val result=try {
            if(provider.verificationMode==OtpVerificationMode.LOCAL) {
                if(challenge.hash!=null && MessageDigest.isEqual(tokens.codeHash(request.challengeId,request.code).toByteArray(),challenge.hash.toByteArray()))
                    OtpVerificationResult.VERIFIED else OtpVerificationResult.INVALID
            } else provider.verifyOtp(phone,request.code)
        } catch(_:Exception) {
            jdbc.update("UPDATE merch_producer_challenges SET consumed_at=? WHERE id=?",Timestamp.from(now),request.challengeId)
            unavailable()
        }
        if(result!=OtpVerificationResult.VERIFIED) {
            if(result==OtpVerificationResult.EXPIRED || challenge.attempts+1>=5) {
                jdbc.update("UPDATE merch_producer_challenges SET consumed_at=? WHERE id=?",Timestamp.from(now),request.challengeId)
                if(challenge.attempts+1>=5) attempts()
            }
            invalidOtp()
        }
        val current=challenge.customer?.let {this.customer(it,lock=true)}
        val verifiedAt=clock.instant()
        if(!challenge.expires.isAfter(verifiedAt) || current==null || current.phone!=phone || customers(phone).singleOrNull()?.id!=current.id) {
            jdbc.update("UPDATE merch_producer_challenges SET consumed_at=? WHERE id=?",Timestamp.from(verifiedAt),request.challengeId)
            invalidOtp()
        }
        jdbc.update("UPDATE merch_producer_challenges SET consumed_at=? WHERE id=?",Timestamp.from(verifiedAt),request.challengeId)
        phoneLock.invalidateStaff(phone)
        jdbc.update("UPDATE mobile_otps SET expires_at=?,verified_at=NULL WHERE phone=?",Timestamp.from(verifiedAt),phone)
        jdbc.update("UPDATE merch_producers SET phone_verified_at=?,updated_at=? WHERE id=? AND phone_normalized=?",Timestamp.from(verifiedAt),Timestamp.from(verifiedAt),current.id,phone)
        val session=UUID.randomUUID();val expiry=verifiedAt.plusSeconds(30L*24*3600)
        val principal=ProducerPrincipal(current.id,session)
        val access=tokens.issue(principal,verifiedAt,expiry)
        jdbc.update("""INSERT INTO merch_producer_sessions(id,customer_id,access_token_hash,phone_fingerprint,created_at,expires_at)
            VALUES(?,?,?,?,?,?)""",session,current.id,ProducerTokenCodec.hash(access),tokens.fingerprint(current.id,phone),Timestamp.from(verifiedAt),Timestamp.from(expiry))
        return issueRefresh(principal,current,access,verifiedAt,expiry)
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=[AppException::class])
    fun refresh(raw:String):ProducerAuthResponse {
        gate()
        if(raw.length !in 32..128 || !Regex("[A-Za-z0-9_-]+").matches(raw)) unauthorized()
        val hash=ProducerTokenCodec.hash(raw)
        val identity=jdbc.query("""SELECT r.session_id,s.customer_id FROM merch_producer_refresh_tokens r
            JOIN merch_producer_sessions s ON s.id=r.session_id WHERE r.token_hash=?""",{rs,_->rs.getObject(1,UUID::class.java) to rs.getObject(2,UUID::class.java)},hash).singleOrNull()?:unauthorized()
        val customer=customer(identity.second,lock=true)?:unauthorized()
        val session=identity.first
        val row=jdbc.queryForList("SELECT * FROM merch_producer_sessions WHERE id=? FOR UPDATE",session).singleOrNull()?:unauthorized()
        val now=clock.instant()
        val refresh=jdbc.queryForList("SELECT * FROM merch_producer_refresh_tokens WHERE token_hash=? FOR UPDATE",hash).singleOrNull()?:unauthorized()
        if(refresh["consumed_at"]!=null) {jdbc.update("UPDATE merch_producer_sessions SET revoked_at=? WHERE id=?",Timestamp.from(now),session);unauthorized()}
        val expires=(row["expires_at"] as Timestamp).toInstant()
        if(row["revoked_at"]!=null || !expires.isAfter(now) || !(refresh["expires_at"] as Timestamp).toInstant().isAfter(now)) unauthorized()
        if(tokens.fingerprint(customer.id,customer.phone)!=row["phone_fingerprint"]) {jdbc.update("UPDATE merch_producer_sessions SET revoked_at=? WHERE id=?",Timestamp.from(now),session);unauthorized()}
        jdbc.update("UPDATE merch_producer_refresh_tokens SET consumed_at=? WHERE token_hash=?",Timestamp.from(now),hash)
        val principal=ProducerPrincipal(customer.id,session);val access=tokens.issue(principal,now,expires)
        jdbc.update("UPDATE merch_producer_sessions SET access_token_hash=? WHERE id=?",ProducerTokenCodec.hash(access),session)
        return issueRefresh(principal,customer,access,now,expires)
    }

    /** Customer → session → domain locks; the filter's verified token hash is never accepted from JSON. */
    @Transactional(propagation=Propagation.MANDATORY)
    fun lockForWrite(principal:ProducerPrincipal) {
        gate()
        val hash=(RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.getAttribute(ACCESS_HASH) as? String?:unauthorized()
        val customer=customer(principal.customerId,lock=true)?:unauthorized()
        val row=jdbc.queryForList("SELECT * FROM merch_producer_sessions WHERE id=? AND customer_id=? FOR UPDATE",principal.sessionId,principal.customerId).singleOrNull()?:unauthorized()
        if(row["revoked_at"]!=null || !(row["expires_at"] as Timestamp).toInstant().isAfter(clock.instant()) ||
            row["access_token_hash"]!=hash || row["phone_fingerprint"]!=tokens.fingerprint(customer.id,customer.phone)) unauthorized()
    }

    @Transactional(readOnly=true)
    fun authenticate(raw:String):ProducerPrincipal? {
        if(!enabled) return null
        val principal=tokens.parse(raw)?:return null
        val row=jdbc.queryForList("""SELECT s.phone_fingerprint,c.phone_normalized,c.active,c.source_account_role FROM merch_producer_sessions s
            JOIN merch_producers c ON c.id=s.customer_id WHERE s.id=? AND s.customer_id=? AND s.access_token_hash=?
            AND s.revoked_at IS NULL AND s.expires_at>now()""",principal.sessionId,principal.customerId,ProducerTokenCodec.hash(raw)).singleOrNull()?:return null
        val phone=row["phone_normalized"] as? String?:return null
        if(row["active"]!=true || row["source_account_role"]!="advertiser" || !Regex("\\+7[0-9]{10}").matches(phone) ||
            phone!=PhoneUtil.normalize(phone) || tokens.fingerprint(principal.customerId,phone)!=row["phone_fingerprint"]) return null
        return principal
    }
    @Transactional(readOnly=true)
    fun me(principal:ProducerPrincipal):ProducerProfile {
        gate();val customer=customer(principal.customerId)?:unauthorized()
        return ProducerProfile(customer.id,customer.name,PhoneUtil.mask(customer.phone))
    }
    @Transactional
    fun logout(principal:ProducerPrincipal) {gate();jdbc.update("UPDATE merch_producer_sessions SET revoked_at=now() WHERE id=? AND customer_id=?",principal.sessionId,principal.customerId)}

    private fun issueRefresh(principal:ProducerPrincipal,customer:Customer,access:String,now:Instant,expires:Instant):ProducerAuthResponse {
        val raw=ProducerTokenCodec.randomToken()
        jdbc.update("INSERT INTO merch_producer_refresh_tokens(token_hash,session_id,created_at,expires_at) VALUES(?,?,?,?)",
            ProducerTokenCodec.hash(raw),principal.sessionId,Timestamp.from(now),Timestamp.from(expires))
        return ProducerAuthResponse(access,raw,expiresIn=minOf(900,java.time.Duration.between(now,expires).seconds),sessionExpiresAt=expires,
            customer=ProducerProfile(customer.id,customer.name,PhoneUtil.mask(customer.phone)))
    }
    private fun customers(phone:String):List<Customer> = jdbc.query("SELECT id,name,phone_normalized FROM merch_producers WHERE phone_normalized=? AND active AND source_account_role='advertiser'",
        {rs,_->Customer(rs.getObject("id",UUID::class.java),rs.getString("name"),rs.getString("phone_normalized"))},phone)
    private fun customer(id:UUID,lock:Boolean=false):Customer?=jdbc.query("SELECT id,name,phone_normalized FROM merch_producers WHERE id=? AND active AND source_account_role='advertiser' AND phone_normalized IS NOT NULL"+if(lock) " FOR UPDATE" else "",
        {rs,_->Customer(rs.getObject("id",UUID::class.java),rs.getString("name"),rs.getString("phone_normalized"))},id).singleOrNull()
    private fun limit(scope:String,max:Int,seconds:Long,now:Instant) {
        val value=jdbc.queryForObject("""INSERT INTO merch_producer_auth_limits(scope_hash,window_start,attempts) VALUES(?,?,1)
            ON CONFLICT(scope_hash) DO UPDATE SET attempts=CASE WHEN merch_producer_auth_limits.window_start<=? THEN 1 ELSE merch_producer_auth_limits.attempts+1 END,
            window_start=CASE WHEN merch_producer_auth_limits.window_start<=? THEN EXCLUDED.window_start ELSE merch_producer_auth_limits.window_start END RETURNING attempts""",
            Int::class.java,tokens.scopeHash(scope),Timestamp.from(now),Timestamp.from(now.minusSeconds(seconds)),Timestamp.from(now.minusSeconds(seconds)))!!
        if(value>max) attempts()
    }
    private fun normalize(raw:String)=PhoneUtil.normalize(raw).also {if(!Regex("\\+7[0-9]{10}").matches(it)) throw AppException(ErrorCode.VALIDATION_FAILED,"Укажите корректный номер телефона")}
    private fun gate(){if(!enabled) throw AppException(ErrorCode.NOT_FOUND,"Сервис входа производителя недоступен",HttpStatus.NOT_FOUND)}
    private fun invalidOtp():Nothing=throw AppException(ErrorCode.OTP_INVALID,"Код недействителен: запросите новый",HttpStatus.BAD_REQUEST)
    private fun attempts():Nothing=throw AppException(ErrorCode.OTP_TOO_MANY_ATTEMPTS,"Слишком много попыток: повторите позже",HttpStatus.TOO_MANY_REQUESTS)
    private fun unauthorized():Nothing=throw AppException(ErrorCode.INVALID_CREDENTIALS,"Войдите в кабинет производителя заново",HttpStatus.UNAUTHORIZED)
    private fun unavailable():Nothing=throw AppException(ErrorCode.SMS_SEND_FAILED,"SMS-сервис временно недоступен: повторите позже",HttpStatus.SERVICE_UNAVAILABLE)
    companion object {const val ACCESS_HASH="epharm.verified-producer-access-hash"}
}
