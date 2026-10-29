package kz.epharm.merch.tasks.executor

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.*
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO
import kz.epharm.auth.access.StoredGrant
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.finance.merch.MerchRewardSecurity
import kz.epharm.merch.delivery.NativeCapabilityAuthorization
import kz.epharm.merch.tasks.*
import kz.epharm.mobile.auth.service.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(properties=["app.merch.native-executor-enabled=true","app.otp.dev-mode=false"])
@AutoConfigureMockMvc @ActiveProfiles("test") @Testcontainers
class NativeTaskExecutorIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine").withDatabaseName("native_paid_executor")
            .withUsername("epharm").withPassword("synthetic-only").apply {start()}
        private val files=Files.createTempDirectory("synthetic-paid-task-evidence-").toRealPath().also {
            Files.setPosixFilePermissions(it,PosixFilePermissions.fromString("rwx------")) }
        @JvmStatic @DynamicPropertySource fun properties(reg:DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") {postgres.jdbcUrl};reg.add("spring.datasource.username") {postgres.username};reg.add("spring.datasource.password") {postgres.password}
            reg.add("app.merch-tasks.evidence-root") {files.toString()}
        }
        private const val PERSON="native-paid-pharmacist"
        private const val PHONE="+77000000001"
    }
    @Autowired lateinit var jdbc:JdbcTemplate
    @Autowired lateinit var mvc:MockMvc
    @Autowired lateinit var json:ObjectMapper
    @Autowired lateinit var users:AdminUserRepository
    @Autowired lateinit var jwt:JwtService
    @Autowired lateinit var security:MerchRewardSecurity
    @MockkBean lateinit var provider:OtpProvider
    @MockkBean lateinit var clock:Clock
    private lateinit var now:Instant
    private lateinit var admin:AdminUserEntity
    private lateinit var branch:UUID
    private lateinit var mapping:UUID
    private val mobileAuth get()="Bearer ${jwt.issuePharmacistToken(PERSON,"Synthetic",PHONE)}"
    private val adminAuth get()="Bearer ${jwt.issueAccessToken(admin)}"
    private val photo get()=ByteArrayOutputStream().use {out->ImageIO.write(BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",out);out.toByteArray()}
    private data class Task(val dto:MerchTaskDispatchDto,val token:String)

    @BeforeEach fun seed() {
        // This class owns only a disposable PG16 and synthetic private evidence directory.
        jdbc.execute("TRUNCATE merch_task_dispatches,merch_branches,merch_organizations,merch_executor_bindings,merch_executor_otp_challenges,merch_reward_events,pharmacist_wallet_entries,pharmacist_wallet_checkpoints,posm_staff_challenges,posm_staff_rate_limits,mobile_otps,pharmacist_person_links,pharmacists,unified_admin_profiles,unified_persons,admin_users,pharmacies,chains CASCADE")
        clearMocks(provider,clock)
        now=Instant.now();every {clock.instant()} answers {now}
        every {provider.id} returns "synthetic-external"
        every {provider.verificationMode} returns OtpVerificationMode.EXTERNAL
        every {provider.requestOtp(any(),any())} just Runs
        every {provider.verifyOtp(any(),any())} returns OtpVerificationResult.INVALID
        every {provider.verifyOtp(any(),"1234")} returns OtpVerificationResult.VERIFIED
        admin=users.saveAndFlush(AdminUserEntity(email="native-paid@example.invalid",name="Synthetic HQ",passwordHash="unused-synthetic",roleRaw=AdminRole.HQ_HEAD.name))
        val adminPerson=UUID.randomUUID();val recipientPerson=UUID.randomUUID()
        for(id in listOf(adminPerson,recipientPerson)) jdbc.update("insert into unified_persons values(?,?)",id,"synthetic-reviewed-proof")
        jdbc.update("insert into unified_admin_profiles(account_id,person_id) values(?,?)",admin.id,adminPerson)
        jdbc.update("update admin_users set access_mode='MANAGED' where id=?",admin.id)
        jdbc.update("""insert into chains(id,name,color,"group",points) values('native-paid-chain','Synthetic','#000000','pilot',0)""")
        for(id in listOf("native-paid-pharmacy","native-other-pharmacy")) jdbc.update("""insert into pharmacies(id,name,chain_id,chain_name,city,district,addr,"group")
            values(?,?,'native-paid-chain','Synthetic','Synthetic','','','pilot')""",id,id)
        jdbc.update("""insert into pharmacists(id,name,iin,phone,pharmacy_id,pharmacy_name,city,tier,status,joined_at,balance)
            values(?,'Synthetic','000000000001',?,'native-paid-pharmacy','Synthetic','Synthetic','Silver','active',current_date,500)""",PERSON,PHONE)
        jdbc.update("insert into pharmacist_person_links(pharmacist_id,person_id,verification_reference,verified_by) values(?,?,'synthetic-person-proof',?)",PERSON,recipientPerson,admin.id)
        mapping=UUID.randomUUID()
        jdbc.update("""insert into posm_pharmacist_mappings(id,pharmacy_id,external_user_id,pharmacist_id,created_by,updated_by)
            values(?,'native-paid-pharmacy','synthetic-source-user',?,?,?)""",mapping,PERSON,admin.id,admin.id)
        jdbc.update("""insert into posm_pharmacist_mapping_audit(mapping_id,action,pharmacy_id,external_user_id,pharmacist_id,actor_id)
            values(?,'created','native-paid-pharmacy','synthetic-source-user',?,?)""",mapping,PERSON,admin.id)
        val org=UUID.randomUUID();branch=UUID.randomUUID()
        jdbc.update("""insert into merch_organizations(id,source_system,source_origin,source_id,source_payload,name,legal_name,active,created_at,updated_at)
            values(?,'EPHARM','https://epharm.inkar.kz',?,'{}','Synthetic','Synthetic',true,now(),now())""",org,org)
        jdbc.update("""insert into merch_branches(id,source_system,source_origin,source_id,source_payload,organization_id,epharm_pharmacy_id,pharmacy_binding_verified,code,name,address,city,active,created_at,updated_at)
            values(?,'EPHARM','https://epharm.inkar.kz',?,'{}',?,'native-paid-pharmacy',true,'01','Synthetic','','Synthetic',true,now(),now())""",branch,branch,org)
        jdbc.update("update unified_admin_profiles set grants_json=? where account_id=?",json.writeValueAsString(listOf("view","create","review","financial_data").map {
            StoredGrant("merchandising","tasks",it,merchBranchIds=setOf(branch.toString())) }),admin.id)
    }
    private fun makeTask():Task {
        val request=CreateMerchTaskDispatch(branch,"Synthetic paid display",now.plusSeconds(3600),700,PERSON,listOf(CreateMerchTaskItem("One photo evidence")))
        val dto=json.readValue(mvc.perform(post("/api/admin/merch/tasks").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(request))).andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskDispatchDto::class.java)
        val token="synthetic_native_paid_"+UUID.randomUUID().toString().replace("-","")
        jdbc.update("""insert into merch_task_links(id,dispatch_id,token_hash,native_owned,issued_channel,expires_at,source_payload)
            values(?,?,?,true,'pharmapay_posm',now()+interval '1 day','{}')""",UUID.randomUUID(),dto.id,NativeCapabilityAuthorization.tokenHash(token))
        return Task(dto,token)
    }
    private fun context(task:Task)=mvc.perform(get("/api/mobile/merch/tasks/${task.dto.id}/executor").header("Authorization",mobileAuth).header("X-Merch-Task-Token",task.token))
    private fun request(task:Task)=mvc.perform(post("/api/mobile/merch/tasks/${task.dto.id}/executor/sms/request").header("Authorization",mobileAuth).header("X-Merch-Task-Token",task.token))
    private fun challenge(task:Task)=json.readValue(request(task).andExpect(status().isOk).andReturn().response.contentAsByteArray,NativeExecutorSmsChallenge::class.java)
    private fun verify(task:Task,id:UUID,code:String="1234")=mvc.perform(post("/api/mobile/merch/tasks/${task.dto.id}/executor/sms/verify")
        .header("Authorization",mobileAuth).header("X-Merch-Task-Token",task.token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(VerifyNativeExecutorSms(id,code))))

    @Test fun `native paid OTP binds one exact recipient without credit then real execution and review credits existing wallet once`() {
        val task=makeTask()
        mvc.perform(get("/api/mobile/merch/tasks/${task.dto.id}").header("Authorization",mobileAuth)).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("OTP_NOT_VERIFIED"))
        val body=context(task).andExpect(status().isOk).andExpect(jsonPath("$.bindingId").isEmpty).andReturn().response.contentAsString
        assertFalse(body.contains(PHONE));assertFalse(body.contains(task.token))
        val otp=challenge(task)
        val binding=json.readValue(verify(task,otp.otpChallengeId).andExpect(status().isOk).andReturn().response.contentAsByteArray,NativeExecutorBinding::class.java)
        verify(task,otp.otpChallengeId).andExpect(status().isOk).andExpect(jsonPath("$.bindingId").value(binding.bindingId.toString())).andExpect(jsonPath("$.duplicate").value(true))
        context(task).andExpect(status().isOk).andExpect(jsonPath("$.bindingId").value(binding.bindingId.toString()))
        assertEquals(0,jdbc.queryForObject("select count(*) from pharmacist_wallet_entries",Int::class.java))
        assertEquals(500L,jdbc.queryForObject("select balance from pharmacists where id=?",Long::class.java,PERSON))
        val item=task.dto.items.single()
        val attempt=json.readValue(mvc.perform(post("/api/mobile/merch/tasks/items/${item.id}/attempts").header("Authorization",mobileAuth))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskAttemptDto::class.java)
        mvc.perform(multipart("/api/mobile/merch/tasks/items/${item.id}/evidence").file(MockMultipartFile("file","photo.png","image/png",photo))
            .param("clientUploadId",UUID.randomUUID().toString()).header("Authorization",mobileAuth)).andExpect(status().isOk)
        mvc.perform(post("/api/mobile/merch/tasks/items/${item.id}/submit").header("Authorization",mobileAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(SubmitMerchTaskAttempt(attempt.number,emptySet())))).andExpect(status().isOk)
        val current=json.readValue(mvc.perform(get("/api/admin/merch/tasks/${task.dto.id}").header("Authorization",adminAuth))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray,MerchTaskDispatchDto::class.java).items.single()
        repeat(2) { mvc.perform(post("/api/admin/merch/tasks/items/${item.id}/reviews").header("Authorization",adminAuth).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(ReviewMerchTaskItem(attempt.id,current.version,"approve","HQ inspected exact task evidence")))).andExpect(status().isOk) }
        assertEquals(1,jdbc.queryForObject("select count(*) from merch_executor_bindings",Int::class.java))
        assertEquals(1,jdbc.queryForObject("select count(*) from pharmacist_wallet_entries where source='merch_task' AND source_id=?",Int::class.java,task.dto.id.toString()))
        assertEquals(1200L,jdbc.queryForObject("select balance from pharmacists where id=?",Long::class.java,PERSON))
    }

    @Test fun `legacy or another native link OTP cannot be consumed for native dispatch purpose`() {
        val task=makeTask();val otp=challenge(task);val foreign=UUID.randomUUID()
        jdbc.update("""insert into merch_executor_otp_challenges(id,challenge_hash,pharmacist_id,phone,provider,created_at,expires_at)
            values(?,?,?,?,?,now(),now()+interval '5 minutes')""",foreign,MerchRewardSecurity.hash("synthetic-legacy-claim"),PERSON,PHONE,provider.id)
        verify(task,foreign).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("OTP_INVALID"))
        val otherToken="different_native_link_"+UUID.randomUUID().toString().replace("-","")
        jdbc.update("""insert into merch_task_links(id,dispatch_id,token_hash,native_owned,issued_channel,expires_at,source_payload)
            values(?,?,?,true,'pharmapay_posm',now()+interval '1 day','{}')""",UUID.randomUUID(),task.dto.id,NativeCapabilityAuthorization.tokenHash(otherToken))
        verify(task.copy(token=otherToken),otp.otpChallengeId).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("OTP_INVALID"))
        io.mockk.verify(exactly=0) {provider.verifyOtp(any(),any())}
        verify(task,otp.otpChallengeId).andExpect(status().isOk)
    }

    @Test fun `slow verified external response after OTP TTL never produces a binding`() {
        val task=makeTask();val otp=challenge(task)
        every {provider.verifyOtp(PHONE,"1234")} answers {now=now.plusSeconds(301);OtpVerificationResult.VERIFIED}
        verify(task,otp.otpChallengeId).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("OTP_EXPIRED"))
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_executor_bindings",Int::class.java))
        assertNotNull(jdbc.queryForObject("select consumed_at from merch_executor_otp_challenges where id=?",Timestamp::class.java,otp.otpChallengeId))
    }

    @Test fun `post provider phone change fails fresh recipient proof without binding or money`() {
        val task=makeTask();val otp=challenge(task)
        every {provider.verifyOtp(PHONE,"1234")} answers {
            jdbc.update("update pharmacists set phone='+77000000002' where id=?",PERSON);OtpVerificationResult.VERIFIED }
        verify(task,otp.otpChallengeId).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("OTP_INVALID"))
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_executor_bindings",Int::class.java))
        assertEquals(0,jdbc.queryForObject("select count(*) from pharmacist_wallet_entries",Int::class.java))
    }

    @Test fun `mapping revocation during provider verification is rechecked using shared exact legacy policy`() {
        val task=makeTask();val otp=challenge(task)
        every {provider.verifyOtp(PHONE,"1234")} answers {
            jdbc.update("update posm_pharmacist_mappings set active=false,revoked_at=now() where id=?",mapping);OtpVerificationResult.VERIFIED }
        verify(task,otp.otpChallengeId).andExpect(status().isForbidden)
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_executor_bindings",Int::class.java))
        assertNotNull(jdbc.queryForObject("select consumed_at from merch_executor_otp_challenges where id=?",Timestamp::class.java,otp.otpChallengeId))
    }

    @Test fun `existing binding mismatch and missing verified person deny before sending or consuming an OTP`() {
        val task=makeTask()
        jdbc.update("""insert into merch_executor_bindings(id,dispatch_id,pharmacist_id,pharmacy_id,reward_kzt,task_due_at,verified_at)
            values(?,?,?,'native-paid-pharmacy',900,?,now())""",UUID.randomUUID(),task.dto.id,PERSON,Timestamp.from(task.dto.dueAt))
        context(task).andExpect(status().isConflict);request(task).andExpect(status().isConflict)
        io.mockk.verify(exactly=0) {provider.requestOtp(any(),any())}
        val other=makeTask();jdbc.execute("TRUNCATE pharmacist_person_links")
        request(other).andExpect(status().isForbidden)
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_executor_otp_challenges",Int::class.java))
    }

    @Test fun `native request shares phone cooldown invalidates other purposes and preserves failure cooldown`() {
        val task=makeTask();val old=UUID.randomUUID()
        jdbc.update("""insert into merch_executor_otp_challenges(id,challenge_hash,pharmacist_id,phone,provider,created_at,expires_at)
            values(?,?,?,?,?,now(),now()+interval '5 minutes')""",old,MerchRewardSecurity.hash("old-purpose"),PERSON,PHONE,provider.id)
        jdbc.update("insert into mobile_otps(phone,code_hash,expires_at) values(?,'synthetic',now()+interval '5 minutes')",PHONE)
        jdbc.update("""insert into posm_staff_challenges(id,purpose,device_id,pharmacy_id,iin,phone,eligible,provider,expires_at)
            values(?,'reset','synthetic-device','native-paid-pharmacy','000000000001',?,true,?,now()+interval '5 minutes')""",UUID.randomUUID(),PHONE,provider.id)
        every {provider.requestOtp(any(),any())} throws IllegalStateException("synthetic provider outage")
        request(task).andExpect(status().isServiceUnavailable)
        request(task).andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("OTP_RESEND_TOO_SOON"))
        assertEquals(0,jdbc.queryForObject("select count(*) from mobile_otps",Int::class.java))
        assertEquals(0,jdbc.queryForObject("select count(*) from posm_staff_challenges where consumed_at is null",Int::class.java))
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_executor_otp_challenges where consumed_at is null",Int::class.java))
        io.mockk.verify(exactly=1) {provider.requestOtp(any(),any())}
    }

    @Test fun `local provider stores purpose bound digest no raw code and fifth wrong attempt consumes proof`() {
        var delivered=""
        every {provider.verificationMode} returns OtpVerificationMode.LOCAL
        every {provider.requestOtp(any(),any())} answers {delivered=secondArg<String?>().orEmpty()}
        val task=makeTask();val otp=challenge(task)
        assertTrue(delivered.matches(Regex("[0-9]{6}")))
        assertEquals(security.otpDigest(otp.otpChallengeId,PHONE,delivered),jdbc.queryForObject("select code_hash from merch_executor_otp_challenges where id=?",String::class.java,otp.otpChallengeId))
        val wrong=if(delivered=="000000") "111111" else "000000"
        repeat(4) {verify(task,otp.otpChallengeId,wrong).andExpect(status().isBadRequest)}
        verify(task,otp.otpChallengeId,wrong).andExpect(status().isTooManyRequests)
        verify(task,otp.otpChallengeId,delivered).andExpect(status().isBadRequest)
        assertEquals(0,jdbc.queryForObject("select count(*) from merch_executor_bindings",Int::class.java))
        io.mockk.verify(exactly=0) {provider.verifyOtp(any(),any())}
    }
}
