package kz.epharm.merch.producer.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ninjasquad.springmockk.MockkBean
import io.mockk.*
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.mobile.auth.service.*
import kz.epharm.merch.placements.dto.PlacementDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.Authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.Clock
import java.util.UUID

@SpringBootTest(properties=["app.merch.producer-auth-enabled=true"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(ProducerAuthIntegrationTest.IdentityProbe::class)
class ProducerAuthIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine").withDatabaseName("producer_auth_disposable")
            .withUsername("epharm").withPassword("synthetic-only").apply {start()}
        @JvmStatic @DynamicPropertySource fun props(r:DynamicPropertyRegistry){r.add("spring.datasource.url"){postgres.jdbcUrl};r.add("spring.datasource.username"){postgres.username};r.add("spring.datasource.password"){postgres.password}}
        private const val PHONE="+77000000001"
    }
    @RestController class IdentityProbe {
        @GetMapping("/api/producer/test/principal") fun identity(authentication:Authentication)=mapOf(
            "producer" to (authentication.principal is ProducerPrincipal),"roles" to authentication.authorities.map {it.authority})
    }
    @Autowired lateinit var mvc:MockMvc
    @Autowired lateinit var jdbc:JdbcTemplate
    @Autowired lateinit var json:ObjectMapper
    @Autowired lateinit var users:AdminUserRepository
    @Autowired lateinit var jwt:JwtService
    @Autowired lateinit var mobileOtp:OtpService
    @Autowired lateinit var transactions:PlatformTransactionManager
    @MockkBean lateinit var provider:OtpProvider
    @MockkBean lateinit var clock:Clock
    private lateinit var authNow:Instant
    private lateinit var customer:UUID
    private lateinit var other:UUID
    private lateinit var admin:AdminUserEntity
    private lateinit var delivered:String
    @BeforeEach fun seed() {
        jdbc.execute("""TRUNCATE merch_producer_refresh_tokens,merch_producer_sessions,merch_producer_challenges,merch_producer_auth_limits,
            posm_staff_rate_limits,posm_staff_challenges,mobile_otps,merch_placement_audit,merch_inventory_audit,merch_media_plan_items,
            merch_media_plans,merch_monthly_inventory,merch_shelves,merch_inventory_containers,merch_branches,merch_organizations,
            merch_producers,admin_users CASCADE""")
        clearMocks(provider)
        authNow=Instant.now();every {clock.instant()} answers {authNow}
        every {provider.id} returns "synthetic-provider"
        every {provider.verificationMode} returns OtpVerificationMode.LOCAL
        every {provider.requestOtp(any(),any())} answers {delivered=secondArg<String?>().orEmpty()}
        admin=users.saveAndFlush(AdminUserEntity(email="producer-collision@example.test",name="Synthetic HQ",passwordHash="unused",roleRaw=AdminRole.HQ_HEAD.name))
        customer=admin.id;other=UUID.randomUUID()
        insertCustomer(customer,PHONE);insertCustomer(other,"+77000000002")
    }
    private fun insertCustomer(id:UUID,phone:String) {jdbc.update("""INSERT INTO merch_producers(id,source_system,source_origin,source_id,source_payload,name,active,
        phone,phone_normalized,source_account_role,created_at,updated_at) VALUES(?,'MERCH_CRM','https://crm.inkar.kz',?,'{}','Synthetic producer',true,?,?,'advertiser',now(),now())""",id,id,phone,phone)}
    private fun post(path:String,body:Any,token:String?=null)=mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
        .also {if(token!=null) it.header("Authorization","Bearer $token")}.content(json.writeValueAsString(body)))
    private fun challenge(phone:String=PHONE):ProducerSmsChallenge=json.readValue(post("/api/producer/auth/sms/request",ProducerSmsRequest(phone))
        .andExpect(status().isOk).andExpect(jsonPath("$.devCode").doesNotExist()).andReturn().response.contentAsByteArray)
    private fun login():ProducerAuthResponse {
        val c=challenge();return json.readValue(post("/api/producer/auth/sms/verify",ProducerSmsVerify(c.challengeId,delivered))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control","no-store")).andReturn().response.contentAsByteArray)
    }
    private fun me(token:String)=mvc.perform(get("/api/producer/auth/me").header("Authorization","Bearer $token"))

    @Test fun `SMS creates only a producer session even when customer UUID equals an administrator UUID`() {
        val auth=login()
        assertThat(auth.customer.id).isEqualTo(admin.id)
        mvc.perform(get("/api/producer/test/principal").header("Authorization","Bearer ${auth.accessToken}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.producer").value(true))
            .andExpect(jsonPath("$.roles.length()").value(1)).andExpect(jsonPath("$.roles[0]").value("ROLE_PRODUCER"))
        mvc.perform(get("/api/admin/pharmacists").header("Authorization","Bearer ${auth.accessToken}")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/admin/pharmacists").header("Authorization","Bearer ${jwt.issueAccessToken(admin)}")).andExpect(status().isOk)
        me(jwt.issueAccessToken(admin)).andExpect(status().isForbidden)
        me(jwt.issuePharmacistToken(customer.toString(),"Synthetic","+77000000001")).andExpect(status().isForbidden)
        me(auth.accessToken).andExpect(status().isOk).andExpect(jsonPath("$.id").value(customer.toString()))
        assertThat(jdbc.queryForObject("SELECT access_token_hash FROM merch_producer_sessions",String::class.java)).isEqualTo(ProducerTokenCodec.hash(auth.accessToken))
        assertThat(jdbc.queryForObject("SELECT token_hash FROM merch_producer_refresh_tokens",String::class.java)).isEqualTo(ProducerTokenCodec.hash(auth.refreshToken))
    }
    @Test fun `verification is challenge bound single use and stores HMAC rather than code or a brute forceable plain hash`() {
        val c=challenge();val code=delivered
        val stored=jdbc.queryForObject("SELECT code_hash FROM merch_producer_challenges",String::class.java)!!
        assertThat(stored).hasSize(64).isNotEqualTo(code).isNotEqualTo(ProducerTokenCodec.hash(code))
        post("/api/producer/auth/sms/verify",ProducerSmsVerify(UUID.randomUUID(),code)).andExpect(status().isBadRequest)
        post("/api/producer/auth/sms/verify",ProducerSmsVerify(c.challengeId,code)).andExpect(status().isOk)
        post("/api/producer/auth/sms/verify",ProducerSmsVerify(c.challengeId,code)).andExpect(status().isBadRequest)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_producer_sessions",Long::class.java)).isEqualTo(1)
    }
    @Test fun `unknown and duplicate phones are generic do not send SMS and cannot invalidate a cashier OTP`() {
        val phone="+77000000999"
        jdbc.update("INSERT INTO mobile_otps(phone,code_hash,expires_at,verified_at) VALUES(?,'synthetic',now()+interval '5 minutes',now())",phone)
        val unknown=challenge(phone)
        post("/api/producer/auth/sms/verify",ProducerSmsVerify(unknown.challengeId,"1234")).andExpect(status().isBadRequest)
        assertThat(jdbc.queryForObject("SELECT verified_at IS NOT NULL AND expires_at>now() FROM mobile_otps WHERE phone=?",Boolean::class.java,phone)).isTrue()
        insertCustomer(UUID.randomUUID(),PHONE)
        val duplicate=challenge()
        post("/api/producer/auth/sms/verify",ProducerSmsVerify(duplicate.challengeId,"1234")).andExpect(status().isBadRequest)
        verify(exactly=0) {provider.requestOtp(any(),any())}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_producer_sessions",Long::class.java)).isZero()
    }
    @Test fun `five wrong attempts and TTL expiry cannot be reset or replayed`() {
        val c=challenge();val correct=delivered;val wrong=if(correct=="000000") "111111" else "000000"
        repeat(4){post("/api/producer/auth/sms/verify",ProducerSmsVerify(c.challengeId,wrong)).andExpect(status().isBadRequest)}
        post("/api/producer/auth/sms/verify",ProducerSmsVerify(c.challengeId,wrong)).andExpect(status().isTooManyRequests)
        post("/api/producer/auth/sms/verify",ProducerSmsVerify(c.challengeId,correct)).andExpect(status().isBadRequest)
        assertThat(jdbc.queryForObject("SELECT attempts FROM merch_producer_challenges",Int::class.java)).isEqualTo(5)
        post("/api/producer/auth/sms/request",ProducerSmsRequest(PHONE)).andExpect(status().isTooManyRequests)
        val expired=challenge("+77000000002")
        jdbc.update("UPDATE merch_producer_challenges SET expires_at=now()-interval '1 second' WHERE id=?",expired.challengeId)
        post("/api/producer/auth/sms/verify",ProducerSmsVerify(expired.challengeId,delivered)).andExpect(status().isBadRequest)
    }
    @Test fun `refresh rotates access and refresh and replay revokes the whole session`() {
        val a=login()
        val b:ProducerAuthResponse=json.readValue(post("/api/producer/auth/refresh",ProducerRefreshRequest(a.refreshToken))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray)
        assertThat(b.accessToken).isNotEqualTo(a.accessToken);assertThat(b.refreshToken).isNotEqualTo(a.refreshToken)
        me(a.accessToken).andExpect(status().isUnauthorized);me(b.accessToken).andExpect(status().isOk)
        post("/api/producer/auth/refresh",ProducerRefreshRequest(a.refreshToken)).andExpect(status().isUnauthorized)
        me(b.accessToken).andExpect(status().isUnauthorized)
        post("/api/producer/auth/refresh",ProducerRefreshRequest(b.refreshToken)).andExpect(status().isUnauthorized)
    }
    @Test fun `logout and changed registered phone revoke server side access`() {
        val a=login()
        post("/api/producer/auth/logout",emptyMap<String,String>(),a.accessToken).andExpect(status().isNoContent)
        me(a.accessToken).andExpect(status().isUnauthorized)
        post("/api/producer/auth/refresh",ProducerRefreshRequest(a.refreshToken)).andExpect(status().isUnauthorized)
        jdbc.update("UPDATE merch_producer_sessions SET revoked_at=NULL WHERE customer_id=?",customer)
        jdbc.update("UPDATE merch_producers SET phone_normalized='+77000000998' WHERE id=?",customer)
        me(a.accessToken).andExpect(status().isUnauthorized)
        post("/api/producer/auth/refresh",ProducerRefreshRequest(a.refreshToken)).andExpect(status().isUnauthorized)
    }
    @Test fun `provider failures are honest consumed challenges and never issue a session or retry silently`() {
        every {provider.requestOtp(any(),any())} throws IllegalStateException("synthetic unavailable")
        post("/api/producer/auth/sms/request",ProducerSmsRequest(PHONE)).andExpect(status().isServiceUnavailable)
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM merch_producer_challenges",Boolean::class.java)).isTrue()
        post("/api/producer/auth/sms/request",ProducerSmsRequest(PHONE)).andExpect(status().isTooManyRequests)
        verify(exactly=1){provider.requestOtp(any(),any())}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_producer_sessions",Long::class.java)).isZero()
    }
    @Test fun `external provider is purpose fenced by a newer mobile SMS and temporary verify failure consumes proof`() {
        every {provider.verificationMode} returns OtpVerificationMode.EXTERNAL
        every {provider.verifyOtp(any(),any())} returns OtpVerificationResult.VERIFIED
        val c=challenge()
        jdbc.update("UPDATE posm_staff_rate_limits SET window_start=now()-interval '61 seconds'")
        mobileOtp.request(PHONE)
        post("/api/producer/auth/sms/verify",ProducerSmsVerify(c.challengeId,"1234")).andExpect(status().isBadRequest)
        verify(exactly=0){provider.verifyOtp(any(),any())}
        val next=challenge("+77000000002")
        every {provider.verifyOtp(any(),any())} throws IllegalStateException("synthetic unavailable")
        post("/api/producer/auth/sms/verify",ProducerSmsVerify(next.challengeId,"1234")).andExpect(status().isServiceUnavailable)
        post("/api/producer/auth/sms/verify",ProducerSmsVerify(next.challengeId,"1234")).andExpect(status().isBadRequest)
        verify(exactly=1){provider.verifyOtp(any(),any())}
    }

    @Test fun `native producer can choose a place and reserve only its own plan without administrator impersonation`() {
        jdbc.update("UPDATE merch_producers SET name=NULL,source_payload='{\"company_name\":null,\"bank_name\":\"Source bank\"}' WHERE id=?",customer)
        val original=jdbc.queryForObject("SELECT md5(source_payload::text) FROM merch_producers WHERE id=?",String::class.java,customer)
        val auth=login();assertThat(auth.customer.profileComplete).isFalse()
        mvc.perform(get("/api/producer/profile").header("Authorization","Bearer ${auth.accessToken}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.profile.companyName").doesNotExist()).andExpect(jsonPath("$.profile.bankName").value("Source bank"))
        mvc.perform(put("/api/producer/profile").header("Authorization","Bearer ${auth.accessToken}").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(UpdateProducerLegalProfile(0,ProducerLegalProfile(companyName="Synthetic completed company",bankName="Current bank")))))
            .andExpect(status().isOk).andExpect(jsonPath("$.profileComplete").value(true))
        assertThat(jdbc.queryForObject("SELECT md5(source_payload::text) FROM merch_producers WHERE id=?",String::class.java,customer)).isEqualTo(original)
        val org=UUID.randomUUID();val branch=UUID.randomUUID();val shelf=UUID.randomUUID()
        jdbc.update("""INSERT INTO merch_organizations(id,source_system,source_origin,source_id,source_payload,name,legal_name,active,created_at,updated_at)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}','Synthetic','Synthetic',true,now(),now())""",org,org)
        jdbc.update("""INSERT INTO merch_branches(id,source_system,source_origin,source_id,source_payload,organization_id,code,name,address,city,active,created_at,updated_at)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}',?,'A','Synthetic','Synthetic','Test',true,now(),now())""",branch,branch,org)
        jdbc.update("""INSERT INTO merch_shelves(id,source_system,source_origin,source_id,source_payload,branch_id,code,category,width_m,base_monthly_price,
            active,place_type,position_index,specifications,sale_mode,created_at,updated_at)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}',?,'A1','AA',1.00,15000,true,'shelf',0,'{}','whole_or_halves',now(),now())""",shelf,shelf,branch)
        val month=LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).withDayOfMonth(1).plusMonths(1)
        mvc.perform(get("/api/producer/branches").header("Authorization","Bearer ${auth.accessToken}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.total").value(1))
        mvc.perform(get("/api/producer/branches/$branch/availability").param("month",month.toString()).header("Authorization","Bearer ${auth.accessToken}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.items[0].whole.price").value(15000)).andExpect(jsonPath("$.items[0].whole.available").value(true))
        val draft:PlacementDto=json.readValue(post("/api/producer/placements",mapOf("producerId" to other,"placementMonth" to month,
            "items" to listOf(mapOf("shelfId" to shelf,"portion" to "whole","monthlyPrice" to 1))),auth.accessToken)
            .andExpect(status().isOk).andReturn().response.contentAsByteArray)
        assertThat(draft.producerId).isEqualTo(customer);assertThat(draft.totalAmount).isEqualTo(15000)
        post("/api/producer/placements/${draft.id}/submit",mapOf("expectedRevision" to draft.revision),auth.accessToken).andExpect(status().isOk)
        mvc.perform(get("/api/producer/branches/$branch/availability").param("month",month.toString()).header("Authorization","Bearer ${auth.accessToken}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.items[0].whole.available").value(false))
        val foreign=UUID.randomUUID()
        jdbc.update("""INSERT INTO merch_media_plans(id,source_system,source_origin,source_id,source_payload,producer_id,advertiser_name,
            placement_month,status,origin,total_amount,submitted_at,created_at,updated_at)
            VALUES(?,'MERCH_CRM','https://crm.inkar.kz',?,'{}',?,'Other',?,'draft','request',0,now(),now(),now())""",foreign,foreign,other,month)
        mvc.perform(get("/api/producer/placements").header("Authorization","Bearer ${auth.accessToken}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.total").value(1))
        mvc.perform(get("/api/producer/placements/$foreign").header("Authorization","Bearer ${auth.accessToken}")).andExpect(status().isForbidden)
        post("/api/admin/merch/placements/${draft.id}/approve",mapOf("expectedRevision" to 2),auth.accessToken).andExpect(status().isUnauthorized)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries",Long::class.java)).isZero()
    }

    @Test fun `slow external verification cannot authenticate a challenge that expired while provider answered`() {
        every {provider.verificationMode} returns OtpVerificationMode.EXTERNAL
        val c=challenge()
        every {provider.verifyOtp(any(),any())} answers {authNow=authNow.plusSeconds(301);OtpVerificationResult.VERIFIED}
        post("/api/producer/auth/sms/verify",ProducerSmsVerify(c.challengeId,"1234")).andExpect(status().isBadRequest)
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM merch_producer_challenges",Boolean::class.java)).isTrue()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_producer_sessions",Long::class.java)).isZero()
        verify(exactly=1){provider.verifyOtp(any(),any())}
    }

    @Test fun `profile protects phone source snapshot expected revision and append only audit`() {
        val auth=login()
        val before=jdbc.queryForObject("SELECT source_payload::text FROM merch_producers WHERE id=?",String::class.java,customer)
        val body=mapOf("expectedRevision" to 0,"profile" to mapOf("companyName" to "Synthetic new name","phone" to "+77000000999"))
        mvc.perform(put("/api/producer/profile").header("Authorization","Bearer ${auth.accessToken}").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isBadRequest)
        val update=UpdateProducerLegalProfile(0,ProducerLegalProfile(companyName="Synthetic new name",bin="000000000001",bic="SYNTHKZK",iban="KZ"+"0".repeat(17),kbe="17"))
        // The source format requires KZ plus eighteen characters; the deliberately short IBAN is rejected.
        mvc.perform(put("/api/producer/profile").header("Authorization","Bearer ${auth.accessToken}").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(update)))
            .andExpect(status().isBadRequest)
        val valid=UpdateProducerLegalProfile(0,ProducerLegalProfile(companyName="Synthetic new name",bin="000000000001",bic="SYNTHKZK",iban="KZ"+"0".repeat(18),kbe="17"))
        mvc.perform(put("/api/producer/profile").header("Authorization","Bearer ${auth.accessToken}").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid)))
            .andExpect(status().isOk).andExpect(jsonPath("$.revision").value(1))
        mvc.perform(put("/api/producer/profile").header("Authorization","Bearer ${auth.accessToken}").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid)))
            .andExpect(status().isConflict)
        assertThat(jdbc.queryForObject("SELECT phone_normalized FROM merch_producers WHERE id=?",String::class.java,customer)).isEqualTo(PHONE)
        assertThat(jdbc.queryForObject("SELECT source_payload::text FROM merch_producers WHERE id=?",String::class.java,customer)).isEqualTo(before)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_producer_profile_audit",Long::class.java)).isEqualTo(1)
        org.junit.jupiter.api.assertThrows<org.springframework.dao.DataIntegrityViolationException>{jdbc.update("DELETE FROM merch_producer_profile_audit")}
    }

    @Test fun `session revocation after filter authentication is rechecked under locks before a producer write`() = authorityRace(false)
    @Test fun `registered phone change after filter authentication is rechecked under locks before a producer write`() = authorityRace(true)
    private fun authorityRace(changePhone:Boolean) {
        val auth=login();val ready=CountDownLatch(1);val release=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        try {
            val holder=pool.submit {TransactionTemplate(transactions).executeWithoutResult {
                if(changePhone) {
                    jdbc.queryForList("SELECT id FROM merch_producers WHERE id=? FOR UPDATE",customer)
                    jdbc.update("UPDATE merch_producers SET phone_normalized='+77000000998' WHERE id=?",customer)
                } else {
                    jdbc.queryForList("SELECT id FROM merch_producer_sessions WHERE customer_id=? FOR UPDATE",customer)
                    jdbc.update("UPDATE merch_producer_sessions SET revoked_at=now() WHERE customer_id=?",customer)
                }
                ready.countDown();check(release.await(10,TimeUnit.SECONDS))
            }}
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue()
            val command=pool.submit<Int> {post("/api/producer/placements",mapOf("placementMonth" to LocalDate.now().withDayOfMonth(1).plusMonths(1),
                "items" to listOf(mapOf("shelfId" to UUID.randomUUID()))),auth.accessToken).andReturn().response.status}
            val table=if(changePhone) "merch_producers" else "merch_producer_sessions"
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            var waiting=false
            while(System.nanoTime()<until && !waiting) {
                waiting=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?)",Boolean::class.java,"%$table%")!!
                if(!waiting) Thread.sleep(10)
            }
            assertThat(waiting).isTrue()
            release.countDown();holder.get(10,TimeUnit.SECONDS)
            assertThat(command.get(10,TimeUnit.SECONDS)).isEqualTo(401)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_media_plans",Long::class.java)).isZero()
        } finally {release.countDown();pool.shutdownNow()}
    }
}
