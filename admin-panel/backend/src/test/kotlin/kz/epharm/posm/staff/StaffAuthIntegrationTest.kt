package kz.epharm.posm.staff

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.mobile.auth.service.OtpService
import kz.epharm.mobile.auth.service.OtpProvider
import kz.epharm.mobile.auth.service.OtpVerificationMode
import kz.epharm.mobile.auth.service.OtpVerificationResult
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import com.ninjasquad.springmockk.MockkBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import io.mockk.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

@SpringBootTest(properties=["app.posm.staff-auth-enabled=true"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class StaffAuthIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("staff_test").withUsername("epharm").withPassword("test-only").apply{start()}
        @JvmStatic @DynamicPropertySource fun properties(reg:DynamicPropertyRegistry) {
            reg.add("spring.datasource.url"){postgres.jdbcUrl}
            reg.add("spring.datasource.username"){postgres.username}
            reg.add("spring.datasource.password"){postgres.password}
        }
    }
    @Autowired lateinit var jdbc:JdbcTemplate
    @Autowired lateinit var mvc:MockMvc
    @Autowired lateinit var json:ObjectMapper
    @Autowired lateinit var encoder:PasswordEncoder
    @Autowired lateinit var otp:OtpService
    @Autowired lateinit var transactions:PlatformTransactionManager
    @Autowired lateinit var cleanup:StaffAuthCleanup
    @MockkBean lateinit var provider:OtpProvider
    private val key="staff-test-device-secret-0000000000000001"
    private val key2="staff-test-device-secret-0000000000000002"
    private val iin="951212500015"
    private val phone="+77002223344"
    private val context=StaffContext("cashier1","session1","ws1","pid1:start1")
    @BeforeEach fun seed() {
        clearMocks(provider)
        every { provider.id } returns "test-external"
        every { provider.verificationMode } returns OtpVerificationMode.EXTERNAL
        every { provider.requestOtp(any(),any()) } just Runs
        every { provider.verifyOtp(any(),any()) } returns OtpVerificationResult.INVALID
        every { provider.verifyOtp(any(),"1234") } returns OtpVerificationResult.VERIFIED
        jdbc.update("DELETE FROM posm_staff_sessions")
        jdbc.update("DELETE FROM posm_staff_challenges")
        jdbc.update("DELETE FROM posm_staff_rate_limits")
        jdbc.update("DELETE FROM mobile_refresh_tokens")
        jdbc.update("DELETE FROM mobile_otps")
        jdbc.update("DELETE FROM pharmacists")
        jdbc.update("INSERT INTO chains(id,name,\"group\") VALUES ('staff-chain','DEMO','pilot') ON CONFLICT DO NOTHING")
        for(p in listOf("staff-pharmacy","staff-other")) jdbc.update("INSERT INTO pharmacies(id,name,chain_id,chain_name,city,\"group\") VALUES (?,?,'staff-chain','DEMO','DEMO','pilot') ON CONFLICT DO NOTHING",p,p)
        jdbc.update("DELETE FROM fulfillment_devices WHERE device_id IN ('staff-device','staff-other-device')")
        for((device,pharmacy,secret) in listOf(Triple("staff-device","staff-pharmacy",key),Triple("staff-other-device","staff-other",key2))) {
            jdbc.update("INSERT INTO fulfillment_devices(id,device_id,pharmacy_id,secret_sha256,secret_hint) VALUES (?,?,?,?,?)",UUID.randomUUID(),device,pharmacy,hash(secret),"test")
        }
        jdbc.update("INSERT INTO pharmacists(id,name,iin,phone,password_hash,pharmacy_id,pharmacy_name,tier,status) VALUES ('staff-person','DEMO',?,?,?,'staff-pharmacy','DEMO','Silver','active')",iin,phone,encoder.encode("Password123!"))
    }
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun post(path:String,body:Any,credential:String?=key,expected:Int=200):com.fasterxml.jackson.databind.JsonNode {
        val request=post("/api/posm/staff$path").contentType("application/json").content(json.writeValueAsBytes(body))
        credential?.let{request.header("X-Posm-Key",it)}
        val result=mvc.perform(request).andExpect(status().`is`(expected)).andReturn()
        return json.readTree(result.response.contentAsByteArray)
    }
    private fun login(credential:String=key,password:String="Password123!",expected:Int=200)=post("/login",StaffLoginRequest(iin,password,context),credential,expected)
    private fun challenge(purpose:String="reset",id:String=iin,number:String=phone)=post("/otp/request",StaffOtpRequest(purpose,id,number)).get("challengeId").asText()
    private fun elapseSharedSmsCooldown() {
        // Exercise the next permitted issuance without sleeping or expiring the existing proof.
        assertEquals(1,jdbc.update("UPDATE posm_staff_rate_limits SET window_start=now()-interval '61 seconds' WHERE scope_hash=?",hash("sms-shared-phone:$phone")))
    }
    private fun complete(id:String,code:String="1234",purpose:String="reset",number:String=phone,personIin:String=iin,expected:Int=200)=post(
        if(purpose=="register") "/register" else "/password/$purpose",
        StaffPasswordRequest(id,code,personIin,number,"NewPassword123!","DEMO Новый"),expected=expected)
    private fun session(token:String,ctx:StaffContext=context,credential:String=key,expected:Int=200) {
        mvc.perform(get("/api/posm/staff/session").header("X-Posm-Key",credential).header("X-Staff-Session",token)
            .param("standardNUserId",ctx.standardNUserId).param("standardNSessionId",ctx.standardNSessionId)
            .param("workstationId",ctx.workstationId).param("processInstanceId",ctx.processInstanceId))
            .andExpect(status().`is`(expected))
    }

    @Test fun `HTTP requires individual device and grant stays scoped and revocable`() {
        post("/otp/request",StaffOtpRequest("register"," $iin ",phone),expected=400)
        login(expected=401,password="wrongPass")
        post("/login",StaffLoginRequest(iin,"Password123!",context),null,401)
        post("/login",StaffLoginRequest(iin,"Password123!",context),"dev-posm-key",401)
        login(credential=key2,expected=401)
        val token=login().get("sessionToken").asText()
        session(token)
        session(token,context.copy(standardNUserId="other"),expected=401)
        session(token,credential=key2,expected=401)
        jdbc.update("UPDATE pharmacists SET status='blocked' WHERE id='staff-person'")
        session(token,expected=401)
        login(expected=401)
        jdbc.update("UPDATE pharmacists SET status='active',pharmacy_id='staff-other' WHERE id='staff-person'")
        session(token,expected=401)
        jdbc.update("UPDATE pharmacists SET pharmacy_id='staff-pharmacy' WHERE id='staff-person'")
        mvc.perform(post("/api/posm/staff/logout").header("X-Posm-Key",key).header("X-Staff-Session",token)).andExpect(status().isNoContent)
        session(token,expected=401)
    }
    @Test fun `reset is one use purpose phone account bound and revokes existing sessions and refresh`() {
        val token=login().get("sessionToken").asText()
        jdbc.update("INSERT INTO mobile_refresh_tokens(id,pharmacist_id,token_hash,expires_at) VALUES (?,'staff-person','test-refresh',now()+interval '1 day')",UUID.randomUUID())
        val id=challenge()
        complete(id,purpose="activate",expected=400)
        complete(id,number="+77001112233",expected=400)
        complete(id,personIin="880404400014",expected=400)
        complete(id)
        complete(id,expected=400)
        session(token,expected=401)
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM mobile_refresh_tokens WHERE revoked_at IS NOT NULL",Int::class.java))
        login(expected=401)
        login(password="NewPassword123!")
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM mobile_otps",Int::class.java))
    }
    @Test fun `registration stays pending unassigned and cannot grant bonus identity`() {
        val newIin="000101500011";val number="+77000000012"
        val id=challenge("register",newIin,number)
        complete(id,purpose="reset",number=number,personIin=newIin,expected=400)
        complete(id,purpose="register",number=number,personIin=newIin)
        val row=jdbc.queryForMap("SELECT status,pharmacy_id,password_hash FROM pharmacists WHERE iin=?",newIin)
        assertEquals("pending",row["status"]);assertNull(row["pharmacy_id"])
        assertTrue(encoder.matches("NewPassword123!",row["password_hash"].toString()))
        val result=post("/login",StaffLoginRequest(newIin,"NewPassword123!",context))
        assertEquals("pending",result["status"].asText());assertTrue(result["sessionToken"].isNull)
    }
    @Test fun `unknown and mismatched reset never send SMS and activation only handles blank password`() {
        val dead=challenge(number="+77001112233")
        verify(exactly=0) { provider.requestOtp(any(),any()) }
        complete(dead,number="+77001112233",expected=400)
        jdbc.update("DELETE FROM posm_staff_rate_limits")
        jdbc.update("UPDATE pharmacists SET password_hash=NULL WHERE id='staff-person'")
        val active=challenge("activate")
        complete(active,purpose="activate")
        login(password="NewPassword123!")
    }
    @Test fun `OTP attempts commit despite surrounding rollback and stop at five`() {
        val id=challenge()
        TransactionTemplate(transactions).executeWithoutResult { tx ->
            complete(id,code="0000",expected=400)
            tx.setRollbackOnly()
        }
        assertEquals(1,jdbc.queryForObject("SELECT attempts FROM posm_staff_challenges WHERE id=?",Int::class.java,UUID.fromString(id)))
        repeat(4){complete(id,code="0000",expected=400)}
        complete(id,expected=429)
        verify(exactly=5) { provider.verifyOtp(phone,"0000") }
    }
    @Test fun `mobile and staff SMS invalidate prior phone challenges in both directions`() {
        val reset=challenge()
        elapseSharedSmsCooldown()
        mvc.perform(post("/api/mobile/auth/sms/request").contentType("application/json").content("{\"phone\":\"$phone\"}")).andExpect(status().isOk)
        complete(reset,expected=400)
        jdbc.update("DELETE FROM posm_staff_rate_limits")
        challenge()
        mvc.perform(post("/api/mobile/auth/sms/verify").contentType("application/json").content("{\"phone\":\"$phone\",\"code\":\"1234\"}")).andExpect(status().isBadRequest)
    }
    @Test fun `concurrent OTP completion changes password only once`() {
        val id=challenge()
        val executor=Executors.newFixedThreadPool(2)
        try {
            val results=(1..2).map { executor.submit<Int> {
                mvc.perform(post("/api/posm/staff/password/reset").header("X-Posm-Key",key).contentType("application/json")
                    .content(json.writeValueAsBytes(StaffPasswordRequest(id,"1234",iin,phone,"NewPassword123!")))).andReturn().response.status
            }}.map { it.get(10,TimeUnit.SECONDS) }.sorted()
            assertEquals(listOf(200,400),results)
        } finally { executor.shutdownNow() }
    }
    @Test fun `login attempts survive failures and feature capabilities never require staff grant`() {
        mvc.perform(get("/api/posm/staff/capabilities").header("X-Posm-Key",key)).andExpect(status().isOk)
        repeat(10){login(password="wrongPass",expected=401)}
        login(expected=429)
    }
    @Test fun `session expires and changes to password device and login revoke old identity`() {
        val token=login()["sessionToken"].asText()
        val next=login()["sessionToken"].asText()
        session(token,expected=401)
        session(next,context.copy(processInstanceId="new-process"),expected=401)
        jdbc.update("UPDATE posm_staff_sessions SET expires_at=now()-interval '1 second'")
        session(next,expected=401)
        val fresh=login()["sessionToken"].asText()
        jdbc.update("UPDATE pharmacists SET password_hash=? WHERE id='staff-person'",encoder.encode("ChangedElsewhere!"))
        session(fresh,expected=401)
        jdbc.update("UPDATE fulfillment_devices SET active=false WHERE device_id='staff-device'")
        session(fresh,expected=401)
    }
    @Test fun `concurrent issuance is serialized phone wide and only sends one SMS`() {
        val executor=Executors.newFixedThreadPool(2)
        try {
            val results=(1..2).map{executor.submit<Int>{
                mvc.perform(post("/api/posm/staff/otp/request").header("X-Posm-Key",key).contentType("application/json")
                    .content(json.writeValueAsBytes(StaffOtpRequest("reset",iin,phone)))).andReturn().response.status
            }}.map{it.get(10,TimeUnit.SECONDS)}.sorted()
            assertEquals(listOf(200,429),results)
            verify(exactly=1){provider.requestOtp(phone,null)}
        } finally { executor.shutdownNow() }
    }
    @Test fun `local provider verifies only scoped code hash and expired challenge cannot reset`() {
        every { provider.verificationMode } returns OtpVerificationMode.LOCAL
        val id=challenge()
        jdbc.update("UPDATE posm_staff_challenges SET expires_at=now()-interval '1 second' WHERE id=?",UUID.fromString(id))
        complete(id,code="5445",expected=400)
        jdbc.update("DELETE FROM posm_staff_rate_limits")
        val next=challenge()
        complete(next,code="1234",expected=400)
        complete(next,code="5445")
        verify(exactly=0){provider.verifyOtp(any(),any())}
    }
    @Test fun `ambiguous failed mobile SMS still durably invalidates staff reset proof`() {
        val old=challenge()
        elapseSharedSmsCooldown()
        every { provider.requestOtp(phone,null) } throws AppException(kz.epharm.shared.error.ErrorCode.SMS_SEND_FAILED,"Provider unavailable",org.springframework.http.HttpStatus.BAD_GATEWAY)
        mvc.perform(post("/api/mobile/auth/sms/request").contentType("application/json").content("{\"phone\":\"$phone\"}"))
            .andExpect(status().isBadGateway)
        // Even if Daribar sent the newest code before its response failed, it cannot fulfill old reset.
        complete(old,code="1234",expected=400)
        assertNotNull(jdbc.queryForObject("SELECT consumed_at FROM posm_staff_challenges WHERE id=?",java.sql.Timestamp::class.java,UUID.fromString(old)))
    }
    @Test fun `legacy mobile activation waits then rejects already activated staff password`() {
        jdbc.update("UPDATE pharmacists SET password_hash=NULL WHERE id='staff-person'")
        val id=challenge("activate")
        // Worst-case old verified legacy proof: never produced by the new RPS flow.
        jdbc.update("INSERT INTO mobile_otps(phone,code_hash,expires_at,verified_at,verification_provider) VALUES (?,'legacy-test',now()+interval '5 minutes',now(),'test-external')",phone)
        val verifying=CountDownLatch(1);val release=CountDownLatch(1)
        every { provider.verifyOtp(phone,"1234") } answers {
            verifying.countDown()
            assertTrue(release.await(5,TimeUnit.SECONDS))
            OtpVerificationResult.VERIFIED
        }
        val executor=Executors.newFixedThreadPool(2)
        try {
            val staff=executor.submit<Int>{
                mvc.perform(post("/api/posm/staff/password/activate").header("X-Posm-Key",key).contentType("application/json")
                    .content(json.writeValueAsBytes(StaffPasswordRequest(id,"1234",iin,phone,"NewPassword123!")))).andReturn().response.status
            }
            assertTrue(verifying.await(5,TimeUnit.SECONDS))
            val mobile=executor.submit<Int>{
                mvc.perform(post("/api/mobile/auth/activation/password").contentType("application/json")
                    .content(json.writeValueAsBytes(mapOf("iin" to iin,"phone" to phone,"password" to "MustNeverWin!")))).andReturn().response.status
            }
            release.countDown()
            assertEquals(200,staff.get(10,TimeUnit.SECONDS))
            assertEquals(409,mobile.get(10,TimeUnit.SECONDS))
            login(password="NewPassword123!")
        } finally { release.countDown();executor.shutdownNow() }
    }
    @Test fun `expired authentication data is purged without deleting current grants and proofs`() {
        val active=login()["sessionToken"].asText()
        val id=challenge()
        jdbc.update("INSERT INTO posm_staff_sessions SELECT ?,pharmacist_id,device_id,pharmacy_id,password_fingerprint,standard_n_user_id,standard_n_session_id,workstation_id,process_instance_id,now()-interval '25 hours',revoked_at,created_at FROM posm_staff_sessions",hash("expired-test-session"))
        jdbc.update("UPDATE posm_staff_challenges SET expires_at=now()-interval '25 hours' WHERE id=?",UUID.fromString(id))
        jdbc.update("INSERT INTO posm_staff_rate_limits VALUES ('expired-limit',now()-interval '25 hours',1)")
        cleanup.cleanExpired()
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM posm_staff_challenges",Int::class.java))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM posm_staff_rate_limits WHERE scope_hash='expired-limit'",Int::class.java))
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM posm_staff_sessions",Int::class.java))
        session(active)
    }
    @Test fun `database revocation survives unblock reassignment and device reactivation or credential rotation`() {
        val blocked=login()["sessionToken"].asText()
        jdbc.update("UPDATE pharmacists SET status='blocked' WHERE id='staff-person'")
        jdbc.update("UPDATE pharmacists SET status='active' WHERE id='staff-person'")
        session(blocked,expected=401)
        val reassigned=login()["sessionToken"].asText()
        jdbc.update("UPDATE pharmacists SET pharmacy_id='staff-other' WHERE id='staff-person'")
        jdbc.update("UPDATE pharmacists SET pharmacy_id='staff-pharmacy' WHERE id='staff-person'")
        session(reassigned,expected=401)
        val deviceInactive=login()["sessionToken"].asText()
        jdbc.update("UPDATE fulfillment_devices SET active=false WHERE device_id='staff-device'")
        jdbc.update("UPDATE fulfillment_devices SET active=true WHERE device_id='staff-device'")
        session(deviceInactive,expected=401)
        val rotated=login()["sessionToken"].asText()
        val newKey="rotated-staff-device-secret-000000000000001"
        jdbc.update("UPDATE fulfillment_devices SET secret_sha256=? WHERE device_id='staff-device'",hash(newKey))
        session(rotated,credential=newKey,expected=401)
    }
}
