package kz.epharm.posm.staff

import java.security.SecureRandom
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kz.epharm.mobile.auth.service.OtpProvider
import kz.epharm.mobile.auth.service.OtpVerificationMode
import kz.epharm.mobile.auth.service.OtpVerificationResult
import kz.epharm.posm.service.PosmDeviceIdentity
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

@Service
class StaffAuthService(
    private val jdbc: JdbcTemplate,
    private val encoder: PasswordEncoder,
    private val provider: OtpProvider,
    private val phoneLock: StaffPhoneLock,
    @Value("\${app.otp.dev-mode:true}") private val devMode: Boolean,
    @Value("\${app.otp.dev-fixed:5445}") private val devCode: String,
) {
    private val random=SecureRandom()
    private val dummyHash=encoder.encode("unused-${UUID.randomUUID()}")

    // Independent commits preserve failed-attempt budgets even if a caller rolls back.
    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=[AppException::class])
    fun login(device: PosmDeviceIdentity, request: StaffLoginRequest): StaffSessionResponse {
        limit("login-device:${device.deviceId}",30,300)
        limit("login-account:${request.iin}",10,300)
        password(request.password,6)
        val person=person(request.iin,true)
        val matches=encoder.matches(request.password,person?.password ?: dummyHash)
        if (!matches || person==null || person.status=="blocked") unauthorized()
        if (person.status=="pending") return StaffSessionResponse("pending",null,null,StaffPerson(person.id,person.name),request.context)
        if (person.status!="active" || person.pharmacy!=device.pharmacyId) unauthorized()
        val token=randomToken()
        val expires=Instant.now().plusSeconds(8*3600)
        val c=request.context
        jdbc.update("UPDATE posm_staff_sessions SET revoked_at=now() WHERE device_id=? AND pharmacy_id=? AND revoked_at IS NULL",device.deviceId,device.pharmacyId)
        jdbc.update("""INSERT INTO posm_staff_sessions(token_hash,pharmacist_id,device_id,pharmacy_id,password_fingerprint,
            standard_n_user_id,standard_n_session_id,workstation_id,process_instance_id,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?)""",
            hash(token),person.id,device.deviceId,device.pharmacyId,hash(person.password!!),
            c.standardNUserId,c.standardNSessionId,c.workstationId,c.processInstanceId,Timestamp.from(expires))
        return StaffSessionResponse("authenticated",token,expires,StaffPerson(person.id,person.name),c)
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=[AppException::class])
    fun requestOtp(device: PosmDeviceIdentity, request: StaffOtpRequest): StaffOtpResponse {
        val phone=phone(request.phone)
        phoneLock.lock(phone)
        phoneLock.limitSharedSms(phone)
        limit("sms-device:${device.deviceId}",10,3600)
        limit("sms-account:${request.iin}",5,3600)
        limit("sms-phone:$phone",1,60)
        phoneLock.invalidateStaff(phone)
        jdbc.update("DELETE FROM mobile_otps WHERE phone=?",phone)
        val person=person(request.iin)
        val eligible=when(request.purpose) {
            "register" -> person==null && jdbc.queryForObject("SELECT count(*) FROM pharmacists WHERE phone=?",Long::class.java,phone)==0L
            "reset" -> person!=null && person.status!="blocked" && !person.password.isNullOrBlank() && PhoneUtil.normalize(person.phone)==phone
            "activate" -> person!=null && person.status!="blocked" && person.password.isNullOrBlank() && PhoneUtil.normalize(person.phone)==phone
            else -> invalid()
        }
        val id=UUID.randomUUID()
        val localCode=if (provider.verificationMode==OtpVerificationMode.LOCAL) {
            if(devMode) devCode else random.nextInt(10000).toString().padStart(4,'0')
        } else null
        jdbc.update("""INSERT INTO posm_staff_challenges(id,purpose,device_id,pharmacy_id,iin,phone,pharmacist_id,
            eligible,provider,code_hash,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            id,request.purpose,device.deviceId,device.pharmacyId,request.iin,phone,person?.id,
            eligible,provider.id,localCode?.let(::hash),Timestamp.from(Instant.now().plusSeconds(300)))
        if (eligible) {
            try { provider.requestOtp(phone,localCode) } catch (e: Exception) {
                phoneLock.invalidateStaff(phone)
                throw e
            }
        }
        return StaffOtpResponse(id.toString(),phoneMasked=PhoneUtil.mask(phone),devCode=if(devMode) devCode else null)
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=[AppException::class])
    fun complete(device: PosmDeviceIdentity, request: StaffPasswordRequest, purpose: String) {
        password(request.password,8)
        val phone=phone(request.phone)
        phoneLock.lock(phone)
        val id=runCatching { UUID.fromString(request.challengeId) }.getOrNull() ?: invalidOtp()
        val challenge=jdbc.query("SELECT * FROM posm_staff_challenges WHERE id=? FOR UPDATE",{rs,_-> Challenge(
            rs.getString("purpose"),rs.getString("device_id"),rs.getString("pharmacy_id"),rs.getString("iin"),
            rs.getString("phone"),rs.getString("pharmacist_id"),rs.getBoolean("eligible"),rs.getString("provider"),
            rs.getString("code_hash"),rs.getInt("attempts"),rs.getTimestamp("expires_at").toInstant(),rs.getTimestamp("consumed_at")!=null,
        )},id).singleOrNull() ?: invalidOtp()
        if(challenge.consumed || !challenge.expires.isAfter(Instant.now())) invalidOtp()
        if(challenge.attempts>=5) throw AppException(ErrorCode.OTP_TOO_MANY_ATTEMPTS,"Запросите новый код",HttpStatus.TOO_MANY_REQUESTS)
        jdbc.update("UPDATE posm_staff_challenges SET attempts=attempts+1 WHERE id=?",id)
        if(challenge.purpose!=purpose || challenge.device!=device.deviceId || challenge.pharmacy!=device.pharmacyId ||
            challenge.iin!=request.iin || challenge.phone!=phone || !challenge.eligible || challenge.provider!=provider.id) invalidOtp()
        val verification=if(provider.verificationMode==OtpVerificationMode.LOCAL) {
            if(hash(request.code)==challenge.codeHash) OtpVerificationResult.VERIFIED else OtpVerificationResult.INVALID
        } else {
            try { provider.verifyOtp(phone,request.code) } catch (e: Exception) {
                jdbc.update("UPDATE posm_staff_challenges SET attempts=attempts-1 WHERE id=?",id)
                throw e
            }
        }
        if(verification!=OtpVerificationResult.VERIFIED) invalidOtp()
        if(purpose=="register") {
            val name=request.name?.trim()?.takeIf { it.length in 3..255 } ?: invalid()
            // Shared phone lock prevents competing application registrations by this number.
            if(person(request.iin)!=null || jdbc.queryForObject("SELECT count(*) FROM pharmacists WHERE phone=?",Long::class.java,phone)!=0L) invalidOtp()
            val inserted=jdbc.update("""INSERT INTO pharmacists(id,name,iin,phone,password_hash,tier,status,pharmacy_id,pharmacy_name)
                VALUES (?,?,?,?,?,'Silver','pending',NULL,NULL) ON CONFLICT DO NOTHING""",
                "ph_${UUID.randomUUID()}",name,request.iin,phone,encoder.encode(request.password))
            // Do not propagate PostgreSQL duplicate-key detail (IIN/phone) into the shared logger.
            if(inserted!=1) invalidOtp()
        } else {
            val person=person(request.iin,true) ?: invalidOtp()
            if(person.id!=challenge.person || person.status=="blocked" || PhoneUtil.normalize(person.phone)!=phone ||
                (purpose=="activate" && !person.password.isNullOrBlank()) || (purpose=="reset" && person.password.isNullOrBlank())) invalidOtp()
            jdbc.update("UPDATE pharmacists SET password_hash=?,updated_at=now() WHERE id=?",encoder.encode(request.password),person.id)
            jdbc.update("UPDATE posm_staff_sessions SET revoked_at=now() WHERE pharmacist_id=? AND revoked_at IS NULL",person.id)
            jdbc.update("UPDATE mobile_refresh_tokens SET revoked_at=now() WHERE pharmacist_id=? AND revoked_at IS NULL",person.id)
        }
        phoneLock.invalidateStaff(phone)
        jdbc.update("DELETE FROM mobile_otps WHERE phone=?",phone)
    }

    @Transactional(readOnly=true)
    fun session(device: PosmDeviceIdentity, token: String?, context: StaffContext): StaffSessionResponse {
        if(token.isNullOrBlank() || token.length !in 32..128) unauthorized()
        return jdbc.query("""SELECT s.*,p.name,p.status,p.pharmacy_id AS current_pharmacy,p.password_hash
            FROM posm_staff_sessions s JOIN pharmacists p ON p.id=s.pharmacist_id
            WHERE s.token_hash=? AND s.device_id=? AND s.pharmacy_id=? AND s.revoked_at IS NULL AND s.expires_at>now()""",
            {rs,_->
                val storedContext=StaffContext(rs.getString("standard_n_user_id"),rs.getString("standard_n_session_id"),rs.getString("workstation_id"),rs.getString("process_instance_id"))
                if(rs.getString("status")!="active" || rs.getString("current_pharmacy")!=device.pharmacyId || storedContext!=context ||
                    rs.getString("password_hash")?.let(::hash)!=rs.getString("password_fingerprint")) unauthorized()
                StaffSessionResponse("authenticated",null,rs.getTimestamp("expires_at").toInstant(),StaffPerson(rs.getString("pharmacist_id"),rs.getString("name")),storedContext)
            },hash(token),device.deviceId,device.pharmacyId).singleOrNull() ?: unauthorized()
    }

    @Transactional
    fun logout(device: PosmDeviceIdentity, token: String?) {
        if(!token.isNullOrBlank() && token.length in 32..128) jdbc.update("UPDATE posm_staff_sessions SET revoked_at=now() WHERE token_hash=? AND device_id=? AND pharmacy_id=?",hash(token),device.deviceId,device.pharmacyId)
    }

    private fun person(iin: String, lock: Boolean=false): Person?=jdbc.query(
        "SELECT id,name,phone,status,pharmacy_id,password_hash FROM pharmacists WHERE iin=?"+if(lock) " FOR UPDATE" else "",
        {rs,_->Person(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6))},iin).singleOrNull()
    private data class Person(val id:String,val name:String,val phone:String,val status:String,val pharmacy:String?,val password:String?)
    private data class Challenge(val purpose:String,val device:String,val pharmacy:String,val iin:String,val phone:String,val person:String?,val eligible:Boolean,val provider:String,val codeHash:String?,val attempts:Int,val expires:Instant,val consumed:Boolean)
    private fun limit(scope:String,max:Int,seconds:Long) {
        val count=jdbc.queryForObject("""INSERT INTO posm_staff_rate_limits(scope_hash,window_start,attempts) VALUES (?,now(),1)
            ON CONFLICT(scope_hash) DO UPDATE SET attempts=CASE WHEN posm_staff_rate_limits.window_start<now()-(? * interval '1 second') THEN 1 ELSE posm_staff_rate_limits.attempts+1 END,
            window_start=CASE WHEN posm_staff_rate_limits.window_start<now()-(? * interval '1 second') THEN now() ELSE posm_staff_rate_limits.window_start END RETURNING attempts""",
            Int::class.java,hash(scope),seconds,seconds)!!
        if(count>max) throw AppException(ErrorCode.OTP_TOO_MANY_ATTEMPTS,"Слишком много попыток — попробуйте позже",HttpStatus.TOO_MANY_REQUESTS)
    }
    private fun phone(raw:String):String=PhoneUtil.normalize(raw).also { if(!Regex("\\+7[0-9]{10}").matches(it)) invalid() }
    private fun password(value:String,min:Int) { if(value.length !in min..128 || value.toByteArray(Charsets.UTF_8).size>72) invalid() }
    private fun randomToken()=ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun unauthorized():Nothing=throw AppException(ErrorCode.INVALID_CREDENTIALS,"Не удалось подтвердить вход сотрудника",HttpStatus.UNAUTHORIZED)
    private fun invalid():Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,"Проверьте данные",HttpStatus.BAD_REQUEST)
    private fun invalidOtp():Nothing=throw AppException(ErrorCode.OTP_INVALID,"Код недействителен — запросите новый",HttpStatus.BAD_REQUEST)
}
