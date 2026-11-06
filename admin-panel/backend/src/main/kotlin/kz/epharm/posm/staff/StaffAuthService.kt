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
    private val credentialVault: kz.epharm.learnercredentials.LearnerPasswordVault,
    @Value("\${app.otp.dev-mode:true}") private val devMode: Boolean,
    @Value("\${app.otp.dev-fixed:5445}") private val devCode: String,
) {
    private val random=SecureRandom()
    private val dummyHash=encoder.encode("unused-${UUID.randomUUID()}")

    // Independent commits preserve failed-attempt budgets even if a caller rolls back.
    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=[AppException::class])
    fun login(device: PosmDeviceIdentity, request: StaffLoginRequest): StaffSessionResponse {
        validateContext(request.context)
        val person=verifyPassword(device,request.iin,request.password)
        if (person.status=="pending") {
            credentialVault.capture(person.id,person.password!!,request.password)
            return StaffSessionResponse("pending",null,null,StaffPerson(person.id,person.name),request.context)
        }
        if (person.status!="active" || person.pharmacy!=device.pharmacyId) unauthorized()
        credentialVault.capture(person.id,person.password!!,request.password)
        return grant(device,person,request.context,request.iin)
    }

    private fun grant(device: PosmDeviceIdentity, person: Person, c: StaffContext, iin: String): StaffSessionResponse {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 718095))",device.deviceId)
        // This observation is not proof of the Standard-N account owner's identity.
        recordObservation(person,iin,c)
        val token=randomToken()
        val expires=Instant.now().plusSeconds(8*3600)
        revokeDeviceGrants(device,c.processInstanceId)
        jdbc.update("""INSERT INTO posm_staff_sessions(token_hash,pharmacist_id,device_id,pharmacy_id,password_fingerprint,
            standard_n_user_id,standard_n_session_id,workstation_id,process_instance_id,expires_at,standard_n_iin,standard_n_iin_status,
            standard_n_full_name,standard_n_full_name_status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            hash(token),person.id,device.deviceId,device.pharmacyId,hash(person.password!!),
            c.standardNUserId,c.standardNSessionId,c.workstationId,c.processInstanceId,Timestamp.from(expires),c.standardNIin,c.standardNIinStatus,
            c.standardNFullName,c.standardNFullNameStatus)
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
    fun complete(device: PosmDeviceIdentity, request: StaffPasswordRequest, purpose: String): StaffSessionResponse? {
        request.context?.let(::validateContext)
        val registration=completeAccount(device,request,purpose)
        return registration?.let { person -> request.context?.let { grant(device,person,it,request.iin) } }
    }

    private fun completeAccount(device:PosmDeviceIdentity,request:StaffPasswordRequest,purpose:String):Person? {
        password(request.password,4)
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
        val updatedHash=encoder.encode(request.password)
        var registration: Person? = null
        if(purpose=="register") {
            val name=request.name?.trim()?.takeIf { it.length in 3..255 } ?: invalid()
            // Shared phone lock prevents competing application registrations by this number.
            if(person(request.iin)!=null || jdbc.queryForObject("SELECT count(*) FROM pharmacists WHERE phone=?",Long::class.java,phone)!=0L) invalidOtp()
            val pharmacy=jdbc.query("SELECT name,city FROM pharmacies WHERE id=? AND active=true",{rs,_->rs.getString(1) to rs.getString(2)},device.pharmacyId).singleOrNull() ?: unauthorized()
            val pharmacistId="ph_${UUID.randomUUID()}"
            val inserted=jdbc.update("""INSERT INTO pharmacists(id,name,iin,phone,password_hash,tier,status,pharmacy_id,pharmacy_name,city)
                VALUES (?,?,?,?,?,'Silver',?,?,?,?) ON CONFLICT DO NOTHING""",
                pharmacistId,name,request.iin,phone,updatedHash,"active",device.pharmacyId,pharmacy.first,pharmacy.second)
            // Do not propagate PostgreSQL duplicate-key detail (IIN/phone) into the shared logger.
            if(inserted!=1) invalidOtp()
            credentialVault.capture(pharmacistId,updatedHash,request.password)
            registration=Person(pharmacistId,name,phone,"active",device.pharmacyId,updatedHash,request.iin)
        } else {
            val person=person(request.iin,true) ?: invalidOtp()
            if(person.id!=challenge.person || person.status=="blocked" || PhoneUtil.normalize(person.phone)!=phone ||
                (purpose=="activate" && !person.password.isNullOrBlank()) || (purpose=="reset" && person.password.isNullOrBlank())) invalidOtp()
            jdbc.update("UPDATE pharmacists SET password_hash=?,updated_at=now() WHERE id=?",updatedHash,person.id)
            credentialVault.capture(person.id,updatedHash,request.password)
            jdbc.update("UPDATE posm_staff_sessions SET revoked_at=now() WHERE pharmacist_id=? AND revoked_at IS NULL",person.id)
            jdbc.update("UPDATE mobile_refresh_tokens SET revoked_at=now() WHERE pharmacist_id=? AND revoked_at IS NULL",person.id)
        }
        phoneLock.invalidateStaff(phone)
        jdbc.update("DELETE FROM mobile_otps WHERE phone=?",phone)
        return registration
    }

    @Transactional(readOnly=true)
    fun session(device: PosmDeviceIdentity, token: String?, context: StaffContext, legacy: Boolean = false): StaffSessionResponse {
        validateContext(context)
        if(token.isNullOrBlank() || token.length !in 32..128) unauthorized()
        return jdbc.query("""SELECT s.*,p.name,p.status,p.pharmacy_id AS current_pharmacy,p.password_hash
            FROM posm_staff_sessions s JOIN pharmacists p ON p.id=s.pharmacist_id
            WHERE s.token_hash=? AND s.device_id=? AND s.pharmacy_id=? AND s.revoked_at IS NULL AND s.expires_at>now()""",
            {rs,_->
                val storedContext=StaffContext(rs.getString("standard_n_user_id"),rs.getString("standard_n_session_id"),rs.getString("workstation_id"),rs.getString("process_instance_id"),rs.getString("standard_n_iin"),rs.getString("standard_n_iin_status"),
                    rs.getString("standard_n_full_name"),rs.getString("standard_n_full_name_status"))
                val expectedContext=if(legacy) storedContext.copy(standardNIin=null,standardNIinStatus="unavailable",standardNFullName=null,standardNFullNameStatus="unavailable") else storedContext
                if(rs.getString("status")!="active" || rs.getString("current_pharmacy")!=device.pharmacyId || expectedContext!=context ||
                    rs.getString("password_hash")?.let(::hash)!=rs.getString("password_fingerprint")) unauthorized()
                StaffSessionResponse("authenticated",null,rs.getTimestamp("expires_at").toInstant(),StaffPerson(rs.getString("pharmacist_id"),rs.getString("name")),expectedContext)
            },hash(token),device.deviceId,device.pharmacyId).singleOrNull() ?: unauthorized()
    }

    @Transactional
    fun logout(device: PosmDeviceIdentity, token: String?) {
        if(!token.isNullOrBlank() && token.length in 32..128) jdbc.update("UPDATE posm_staff_sessions SET revoked_at=now() WHERE token_hash=? AND device_id=? AND pharmacy_id=?",hash(token),device.deviceId,device.pharmacyId)
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=[AppException::class])
    fun deviceLogin(device:PosmDeviceIdentity,request:StaffDeviceLoginRequest):StaffDeviceSessionResponse {
        validateDeviceContext(request.deviceContext)
        val person=verifyPassword(device,request.iin,request.password)
        if(person.status=="pending") {
            credentialVault.capture(person.id,person.password!!,request.password)
            return StaffDeviceSessionResponse("pending",null,null,StaffPerson(person.id,person.name),request.deviceContext)
        }
        if(person.status!="active" || person.pharmacy!=device.pharmacyId) unauthorized()
        credentialVault.capture(person.id,person.password!!,request.password)
        return deviceGrant(device,person,request.deviceContext)
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=[AppException::class])
    fun deviceRegister(device:PosmDeviceIdentity,request:StaffDeviceRegistrationRequest):StaffDeviceSessionResponse {
        validateDeviceContext(request.deviceContext)
        val credentials=StaffPasswordRequest(request.challengeId,request.code,request.iin,request.phone,request.password,request.name)
        val person=completeAccount(device,credentials,"register") ?: unauthorized()
        return deviceGrant(device,person,request.deviceContext)
    }

    private fun deviceGrant(device:PosmDeviceIdentity,person:Person,c:StaffDeviceContext):StaffDeviceSessionResponse {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 718095))",device.deviceId)
        recordObservation(person,person.iin ?: unauthorized(),c.standardNContext)
        revokeDeviceGrants(device,c.processInstanceId)
        val token=randomToken();val expires=Instant.now().plusSeconds(8*3600)
        val source=c.standardNContext
        jdbc.update("""INSERT INTO posm_device_staff_sessions(token_hash,pharmacist_id,device_id,pharmacy_id,password_fingerprint,
            process_instance_id,standard_n_user_id,standard_n_session_id,workstation_id,standard_n_iin,standard_n_iin_status,
            standard_n_full_name,standard_n_full_name_status,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            hash(token),person.id,device.deviceId,device.pharmacyId,hash(person.password!!),c.processInstanceId,
            source?.standardNUserId,source?.standardNSessionId,source?.workstationId,source?.standardNIin,source?.standardNIinStatus ?: "unavailable",
            source?.standardNFullName,source?.standardNFullNameStatus ?: "unavailable",Timestamp.from(expires))
        return StaffDeviceSessionResponse("authenticated",token,expires,StaffPerson(person.id,person.name),c)
    }

    // A rejected context must durably revoke the grant even though the HTTP response is 401.
    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=[AppException::class])
    fun deviceSession(device:PosmDeviceIdentity,token:String?,context:StaffDeviceContext):StaffDeviceSessionResponse {
        validateDeviceContext(context)
        if(token.isNullOrBlank() || token.length !in 32..128) unauthorized()
        val tokenHash=hash(token)
        val personId=jdbc.query("""SELECT pharmacist_id FROM posm_device_staff_sessions WHERE token_hash=? AND device_id=?
            AND pharmacy_id=? AND revoked_at IS NULL AND expires_at>now()""",{rs,_->rs.getString(1)},tokenHash,device.deviceId,device.pharmacyId).singleOrNull() ?: unauthorized()
        // Match password/reset writers' person-before-session lock order.
        val person=personById(personId,true) ?: unauthorized()
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 718095))",device.deviceId)
        val stored=jdbc.query("""SELECT * FROM posm_device_staff_sessions WHERE token_hash=? AND device_id=? AND pharmacy_id=?
            AND revoked_at IS NULL AND expires_at>now() FOR UPDATE""",{rs,_-> DeviceGrant(
                rs.getString("process_instance_id"),rs.getString("password_fingerprint"),rs.getString("standard_n_user_id"),
                rs.getString("standard_n_session_id"),rs.getString("workstation_id"),rs.getTimestamp("expires_at").toInstant())},
            tokenHash,device.deviceId,device.pharmacyId).singleOrNull() ?: unauthorized()
        val source=context.standardNContext
        if(person.status!="active" || person.pharmacy!=device.pharmacyId ||
            person.password?.let(::hash)!=stored.passwordFingerprint || stored.process!=context.processInstanceId ||
            (source!=null && stored.user!=null && (stored.user!=source.standardNUserId ||
                stored.session!=source.standardNSessionId || stored.workstation!=source.workstationId))) {
            jdbc.update("UPDATE posm_device_staff_sessions SET revoked_at=now() WHERE token_hash=?",tokenHash)
            unauthorized()
        }
        if(source!=null) {
            // Null intervals preserve the first reliable binding; only observations may be refreshed.
            jdbc.update("""UPDATE posm_device_staff_sessions SET standard_n_user_id=?,standard_n_session_id=?,workstation_id=?,
                standard_n_iin=?,standard_n_iin_status=?,standard_n_full_name=?,standard_n_full_name_status=? WHERE token_hash=?""",
                source.standardNUserId,source.standardNSessionId,source.workstationId,source.standardNIin,source.standardNIinStatus,
                source.standardNFullName,source.standardNFullNameStatus,tokenHash)
        }
        recordObservation(person,person.iin ?: unauthorized(),source)
        return StaffDeviceSessionResponse("authenticated",null,stored.expires,StaffPerson(person.id,person.name),context)
    }

    @Transactional
    fun deviceLogout(device:PosmDeviceIdentity,token:String?) {
        if(!token.isNullOrBlank() && token.length in 32..128) jdbc.update("""UPDATE posm_device_staff_sessions SET revoked_at=now()
            WHERE token_hash=? AND device_id=? AND pharmacy_id=?""",hash(token),device.deviceId,device.pharmacyId)
    }

    private fun verifyPassword(device:PosmDeviceIdentity,iin:String,value:String):Person {
        limit("login-device:${device.deviceId}",30,300)
        password(value,4)
        val person=person(iin,true)
        val matches=encoder.matches(value,person?.password ?: dummyHash)
        if(!matches || person==null || person.password.isNullOrBlank() || person.status=="blocked") unauthorized()
        return person
    }
    private fun revokeDeviceGrants(device:PosmDeviceIdentity,process:String) {
        // Pharmacy ZIP credentials can be shared by several terminals; replacing one process must not log out another.
        jdbc.update("UPDATE posm_staff_sessions SET revoked_at=now() WHERE device_id=? AND pharmacy_id=? AND process_instance_id=? AND revoked_at IS NULL",device.deviceId,device.pharmacyId,process)
        jdbc.update("UPDATE posm_device_staff_sessions SET revoked_at=now() WHERE device_id=? AND pharmacy_id=? AND process_instance_id=? AND revoked_at IS NULL",device.deviceId,device.pharmacyId,process)
    }
    private fun recordObservation(person:Person,iin:String,context:StaffContext?) {
        val observation=context?.let { StaffIdentityObservation.match(iin,person.name,it) } ?: StaffIdentityMatch("NONE","UNKNOWN")
        jdbc.update("""UPDATE pharmacists SET standard_n_iin_match_status=?,standard_n_iin_checked_at=now(),
            standard_n_identity_match_method=?,standard_n_identity_match_status=?,standard_n_identity_checked_at=now(),updated_at=now() WHERE id=?""",
            context?.let { StaffIinObservation.match(iin,it) } ?: "UNKNOWN",observation.method,observation.status,person.id)
    }
    private fun validateDeviceContext(context:StaffDeviceContext) {
        if(context.processInstanceId.isBlank() || context.processInstanceId.length>256 || context.processInstanceId.any(Char::isISOControl)) invalid()
        context.standardNContext?.let { source ->
            validateContext(source)
            if(source.processInstanceId!=context.processInstanceId ||
                listOf(source.standardNUserId,source.standardNSessionId,source.workstationId).any { it.isBlank() || it.length>128 }) invalid()
        }
    }
    private fun person(iin:String,lock:Boolean=false)=findPerson("iin",iin,lock)
    private fun personById(id:String,lock:Boolean=false)=findPerson("id",id,lock)
    private fun findPerson(column:String,value:String,lock:Boolean):Person?=jdbc.query(
        "SELECT id,name,phone,status,pharmacy_id,password_hash,iin FROM pharmacists WHERE $column=?"+if(lock) " FOR UPDATE" else "",
        {rs,_->Person(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7))},value).singleOrNull()
    private data class Person(val id:String,val name:String,val phone:String,val status:String,val pharmacy:String?,val password:String?,val iin:String?)
    private data class DeviceGrant(val process:String,val passwordFingerprint:String,val user:String?,val session:String?,val workstation:String?,val expires:Instant)
    private data class Challenge(val purpose:String,val device:String,val pharmacy:String,val iin:String,val phone:String,val person:String?,val eligible:Boolean,val provider:String,val codeHash:String?,val attempts:Int,val expires:Instant,val consumed:Boolean)
    private fun limit(scope:String,max:Int,seconds:Long) {
        val count=jdbc.queryForObject("""INSERT INTO posm_staff_rate_limits(scope_hash,window_start,attempts) VALUES (?,now(),1)
            ON CONFLICT(scope_hash) DO UPDATE SET attempts=CASE WHEN posm_staff_rate_limits.window_start<now()-(? * interval '1 second') THEN 1 ELSE posm_staff_rate_limits.attempts+1 END,
            window_start=CASE WHEN posm_staff_rate_limits.window_start<now()-(? * interval '1 second') THEN now() ELSE posm_staff_rate_limits.window_start END RETURNING attempts""",
            Int::class.java,hash(scope),seconds,seconds)!!
        if(count>max) throw AppException(ErrorCode.OTP_TOO_MANY_ATTEMPTS,"Слишком много попыток — попробуйте позже",HttpStatus.TOO_MANY_REQUESTS)
    }
    private fun phone(raw:String):String=PhoneUtil.normalize(raw).also { if(!Regex("\\+7[0-9]{10}").matches(it)) invalid() }
    private fun password(value:String,min:Int) { if(value.isBlank() || value.length !in min..128 || value.toByteArray(Charsets.UTF_8).size>72) invalid() }
    private fun validateContext(context: StaffContext) { if(!StaffIinObservation.valid(context)) invalid() }
    private fun randomToken()=ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun unauthorized():Nothing=throw AppException(ErrorCode.INVALID_CREDENTIALS,"Не удалось подтвердить вход сотрудника",HttpStatus.UNAUTHORIZED)
    private fun invalid():Nothing=throw AppException(ErrorCode.VALIDATION_FAILED,"Проверьте данные",HttpStatus.BAD_REQUEST)
    private fun invalidOtp():Nothing=throw AppException(ErrorCode.OTP_INVALID,"Код недействителен — запросите новый",HttpStatus.BAD_REQUEST)
}
