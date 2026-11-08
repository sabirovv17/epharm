package kz.epharm.posm.staff

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.*
import kz.epharm.mobile.auth.service.OtpProvider
import kz.epharm.mobile.auth.service.OtpVerificationMode
import kz.epharm.mobile.auth.service.OtpVerificationResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(properties=["app.posm.staff-auth-enabled=true","app.posm.staff-registration-sms-required=false","app.otp.dev-mode=false"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class StaffRegistrationWithoutSmsIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("staff_without_sms_test").withUsername("epharm").withPassword("test-only").apply{start()}
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
    @Autowired lateinit var transactions:PlatformTransactionManager
    @MockkBean lateinit var provider:OtpProvider
    private val key="no-sms-test-device-secret-00000000000001"
    private val otherKey="no-sms-test-device-secret-00000000000002"
    private val existingIin="951212500015"
    private val newIin="000101500011"
    private val otherIin="880404400014"
    private val phone="+77000000112"
    private val context=StaffDeviceContext("123:456")
    private val request get()=StaffDeviceRegistrationWithoutSmsRequest(newIin,phone,"A1!b","Synthetic New Employee",context)
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
        jdbc.update("INSERT INTO chains(id,name,\"group\") VALUES ('no-sms-chain','DEMO','pilot') ON CONFLICT DO NOTHING")
        for(p in listOf("no-sms-pharmacy","no-sms-other")) {
            jdbc.update("""INSERT INTO pharmacies(id,name,chain_id,chain_name,city,"group")
                VALUES (?,?,'no-sms-chain','DEMO','DEMO','pilot') ON CONFLICT(id) DO UPDATE SET active=true""",p,p)
        }
        jdbc.update("DELETE FROM fulfillment_devices WHERE device_id IN ('no-sms-device','no-sms-other-device')")
        for((device,pharmacy,secret) in listOf(Triple("no-sms-device","no-sms-pharmacy",key),Triple("no-sms-other-device","no-sms-other",otherKey))) {
            jdbc.update("INSERT INTO fulfillment_devices(id,device_id,pharmacy_id,secret_sha256,secret_hint) VALUES (?,?,?,?,?)",UUID.randomUUID(),device,pharmacy,hash(secret),"test")
        }
        jdbc.update("""INSERT INTO pharmacists(id,name,iin,phone,password_hash,pharmacy_id,pharmacy_name,tier,status)
            VALUES ('no-sms-existing','Synthetic Existing',?,'+77000000111',?,'no-sms-pharmacy','DEMO','Silver','active')""",existingIin,encoder.encode("Existing123!"))
    }
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun post(path:String,body:Any,credential:String?=key,expected:Int=200):JsonNode {
        val req=post(path).contentType("application/json").content(json.writeValueAsBytes(body))
        credential?.let{req.header("X-Posm-Key",it)}
        val result=mvc.perform(req).andExpect(status().`is`(expected)).andReturn()
        return json.readTree(result.response.contentAsByteArray)
    }
    private fun register(body:Any=request,credential:String?=key,expected:Int=200)=post("/api/posm/staff/device/register-without-sms",body,credential,expected)
    private fun noSmsCalls() {
        verify(exactly=0){provider.requestOtp(any(),any())}
        verify(exactly=0){provider.verifyOtp(any(),any())}
    }
    private fun financialCounts()=listOf("posm_pharmacist_mappings","pharmacist_wallet_entries").map { table -> jdbc.queryForObject("SELECT count(*) FROM $table",Long::class.java) }
    private fun oldChallenge(personIin:String=newIin,number:String=phone):UUID=UUID.randomUUID().also {
        jdbc.update("""INSERT INTO posm_staff_challenges(id,purpose,device_id,pharmacy_id,iin,phone,eligible,provider,expires_at)
            VALUES (?,'register','no-sms-device','no-sms-pharmacy',?,?,true,'test-external',now()+interval '5 minutes')""",it,personIin,number)
    }

    @Test fun `explicit no SMS registration creates active assigned profile and process grant but no phone or financial proof`() {
        assertNull(jdbc.queryForMap("SELECT phone_verification_status FROM pharmacists WHERE id='no-sms-existing'")["phone_verification_status"])
        val cap=mvc.perform(get("/api/posm/staff/capabilities").header("X-Posm-Key",key)).andExpect(status().isOk).andReturn()
        val capabilities=json.readTree(cap.response.contentAsByteArray)
        assertFalse(capabilities["registrationSmsRequired"].asBoolean())
        assertEquals(1,capabilities["deviceEmployeeAuthVersion"].asInt())
        val before=financialCounts();val result=register()
        assertEquals("authenticated",result["status"].asText());assertFalse(result.has("context"))
        assertEquals(json.valueToTree<JsonNode>(context),result["deviceContext"])
        val row=jdbc.queryForMap("SELECT * FROM pharmacists WHERE id=?",result["pharmacist"]["id"].asText())
        assertEquals(newIin,row["iin"]);assertEquals("active",row["status"])
        assertEquals("no-sms-pharmacy",row["pharmacy_id"]);assertEquals("no-sms-pharmacy",row["pharmacy_name"])
        assertEquals("DEMO",row["city"]);assertEquals("self_declared",row["phone_verification_status"])
        assertEquals("UNKNOWN",row["standard_n_iin_match_status"]);assertEquals("NONE",row["standard_n_identity_match_method"])
        assertTrue(encoder.matches("A1!b",row["password_hash"].toString()))
        mvc.perform(post("/api/posm/staff/device/session/check").header("X-Posm-Key",key).header("X-Staff-Session",result["sessionToken"].asText())
            .contentType("application/json").content(json.writeValueAsBytes(context))).andExpect(status().isOk)
        post("/api/mobile/auth/login",mapOf("iin" to newIin,"password" to "A1!b"))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM posm_staff_challenges",Int::class.java))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM posm_staff_sessions",Int::class.java))
        assertEquals(before,financialCounts());noSmsCalls()
    }

    @Test fun `no SMS route preserves full field validation and cannot take a pharmacy from payload`() {
        for(bad in listOf(request.copy(password="123"),request.copy(password="    "),request.copy(password="я".repeat(37)),
            request.copy(iin="000000000000"),request.copy(phone="+12345678901"),request.copy(name="  "),
            request.copy(deviceContext=StaffDeviceContext("")),request.copy(deviceContext=StaffDeviceContext("x",StaffContext("u","s","w","other"))))) register(bad,expected=400)
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM pharmacists",Int::class.java))
        val payload=json.convertValue(request,Map::class.java).toMutableMap()
        payload["pharmacyId"]="no-sms-other";payload["phoneVerificationStatus"]="sms_verified"
        val result=register(payload)
        assertEquals("no-sms-pharmacy",jdbc.queryForObject("SELECT pharmacy_id FROM pharmacists WHERE id=?",String::class.java,result["pharmacist"]["id"].asText()))
        assertEquals("self_declared",jdbc.queryForObject("SELECT phone_verification_status FROM pharmacists WHERE id=?",String::class.java,result["pharmacist"]["id"].asText()))
        noSmsCalls()
    }

    @Test fun `individual active device and active pharmacy remain mandatory`() {
        register(credential=null,expected=401);register(credential="dev-posm-key",expected=401)
        jdbc.update("UPDATE fulfillment_devices SET active=false WHERE device_id='no-sms-device'")
        register(expected=401)
        jdbc.update("UPDATE fulfillment_devices SET active=true WHERE device_id='no-sms-device'")
        jdbc.update("UPDATE pharmacies SET active=false WHERE id='no-sms-pharmacy'")
        register(expected=401)
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM pharmacists",Int::class.java));noSmsCalls()
    }

    @Test fun `duplicates never overwrite activate move or reset any existing account`() {
        for(state in listOf("active","pending","blocked")) {
            jdbc.update("UPDATE pharmacists SET status=? WHERE id='no-sms-existing'",state)
            val before=jdbc.queryForMap("SELECT * FROM pharmacists WHERE id='no-sms-existing'")
            register(request.copy(iin=existingIin),expected=400)
            assertEquals(before,jdbc.queryForMap("SELECT * FROM pharmacists WHERE id='no-sms-existing'"))
        }
        register(request.copy(phone="+77000000111"),credential=otherKey,expected=400)
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM pharmacists",Int::class.java))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM posm_device_staff_sessions",Int::class.java));noSmsCalls()
    }

    @Test fun `concurrent duplicate IIN and phone claims each produce exactly one account and grant`() {
        for(sameIin in listOf(true,false)) {
            jdbc.update("DELETE FROM pharmacists WHERE id<>'no-sms-existing'");jdbc.update("DELETE FROM posm_staff_rate_limits")
            val executor=Executors.newFixedThreadPool(2);val ready=CountDownLatch(2);val go=CountDownLatch(1)
            try {
                val futures=(0..1).map { index -> executor.submit<Int> {
                    val data=if(index==0) request else if(sameIin) request.copy(phone="+77000000113",deviceContext=StaffDeviceContext("456:789")) else request.copy(iin=otherIin,deviceContext=StaffDeviceContext("456:789"))
                    ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS))
                    mvc.perform(post("/api/posm/staff/device/register-without-sms").header("X-Posm-Key",if(index==0) key else otherKey)
                        .contentType("application/json").content(json.writeValueAsBytes(data))).andReturn().response.status
                } }
                assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown()
                assertEquals(listOf(200,400),futures.map{it.get(20,TimeUnit.SECONDS)}.sorted())
                assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM pharmacists",Int::class.java))
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM posm_device_staff_sessions WHERE revoked_at IS NULL",Int::class.java))
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM pharmacists WHERE phone_verification_status='self_declared'",Int::class.java))
            } finally {go.countDown();executor.shutdownNow()}
        }
        noSmsCalls()
    }

    @Test fun `registration limits are durable across rejection caller rollback and another device`() {
        val conflict=request.copy(iin=existingIin)
        TransactionTemplate(transactions).executeWithoutResult { tx -> register(conflict,expected=400);tx.setRollbackOnly() }
        repeat(4){register(conflict,expected=400)}
        register(conflict,credential=otherKey,expected=429)
        assertEquals(6,jdbc.queryForObject("SELECT attempts FROM posm_staff_rate_limits WHERE scope_hash=?",Int::class.java,hash("register-account:$existingIin")))
        jdbc.update("UPDATE posm_staff_rate_limits SET attempts=10 WHERE scope_hash=?",hash("register-device:15:no-sms-pharmacy13:no-sms-device"))
        register(expected=429)
        assertEquals(11,jdbc.queryForObject("SELECT attempts FROM posm_staff_rate_limits WHERE scope_hash=?",Int::class.java,hash("register-device:15:no-sms-pharmacy13:no-sms-device")))
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM pharmacists",Int::class.java));noSmsCalls()
    }

    @Test fun `registration quota belongs to authenticated pharmacy and device pair not a common terminal name`() {
        jdbc.update("UPDATE fulfillment_devices SET device_id='no-sms-device' WHERE device_id='no-sms-other-device'")
        val identities=listOf(existingIin,newIin,otherIin)
        repeat(10) { index -> register(request.copy(iin=identities[index%3],phone="+77000000111",deviceContext=StaffDeviceContext("process-$index")),expected=400) }
        register(expected=429)
        val registered=register(credential=otherKey)
        assertEquals("authenticated",registered["status"].asText())
        assertEquals("no-sms-other",jdbc.queryForObject("SELECT pharmacy_id FROM pharmacists WHERE iin=?",String::class.java,newIin))
        register(request.copy(iin=otherIin),expected=429)
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM pharmacists",Int::class.java));noSmsCalls()
    }

    @Test fun `successful no SMS registration invalidates old phone challenges without creating verified OTP proof`() {
        val id=oldChallenge();register()
        assertNotNull(jdbc.queryForObject("SELECT consumed_at FROM posm_staff_challenges WHERE id=?",java.sql.Timestamp::class.java,id))
        post("/api/mobile/auth/register",mapOf("iin" to otherIin,"phone" to phone,"fio" to "Synthetic Other"),expected=400)
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM pharmacists WHERE phone=?",Int::class.java,phone))
        noSmsCalls()
    }

    @Test fun `flag stops new registration SMS only while previous real codes and recovery still require provider proof`() {
        post("/api/posm/staff/otp/request",StaffOtpRequest("register",newIin,phone),expected=503)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM posm_staff_challenges",Int::class.java))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM posm_staff_rate_limits",Int::class.java));noSmsCalls()
        val id=oldChallenge()
        post("/api/posm/staff/register",StaffPasswordRequest(id.toString(),"0000",newIin,phone,"A1!b","Synthetic New Employee"),expected=400)
        val result=post("/api/posm/staff/register",StaffPasswordRequest(id.toString(),"1234",newIin,phone,"A1!b","Synthetic New Employee"))
        assertEquals("registered",result["status"].asText())
        assertEquals("sms_verified",jdbc.queryForObject("SELECT phone_verification_status FROM pharmacists WHERE iin=?",String::class.java,newIin))
        val reset=post("/api/posm/staff/otp/request",StaffOtpRequest("reset",newIin,phone))
        verify(exactly=1){provider.requestOtp(phone,any())}
        post("/api/posm/staff/password/reset",StaffPasswordRequest(reset["challengeId"].asText(),"0000",newIin,phone,"Changed123!"),expected=400)
        assertTrue(encoder.matches("A1!b",jdbc.queryForObject("SELECT password_hash FROM pharmacists WHERE iin=?",String::class.java,newIin)))
        jdbc.update("UPDATE pharmacists SET password_hash=NULL WHERE id='no-sms-existing'")
        val activation=post("/api/posm/staff/otp/request",StaffOtpRequest("activate",existingIin,"+77000000111"))
        post("/api/posm/staff/password/activate",StaffPasswordRequest(activation["challengeId"].asText(),"0000",existingIin,"+77000000111","Changed123!"),expected=400)
        assertNull(jdbc.queryForMap("SELECT password_hash FROM pharmacists WHERE id='no-sms-existing'")["password_hash"])
    }
}
