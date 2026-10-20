package kz.epharm.finance.merch

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.ninjasquad.springmockk.SpykBean
import io.mockk.*
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kz.epharm.auth.service.JwtService
import kz.epharm.mobile.auth.service.OtpProvider
import kz.epharm.mobile.auth.service.OtpService
import kz.epharm.mobile.auth.service.OtpVerificationMode
import kz.epharm.mobile.auth.service.OtpVerificationResult
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(properties = ["app.merch-rewards.enabled=true", "app.otp.dev-mode=false",
    "app.merch-rewards.shared-secret=integration-contract-test-secret-0001"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class MerchRewardsIntegrationTest {
    companion object {
        private const val SECRET = "integration-contract-test-secret-0001"
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("merch_money_test").withUsername("epharm").withPassword("test-only").apply { start() }
        private val sequence = AtomicLong(800_000_000_000)
        @JvmStatic @DynamicPropertySource fun properties(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }
    @Autowired lateinit var service: MerchRewardService
    @Autowired lateinit var mobileOtp: OtpService
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jwt: JwtService
    @MockkBean lateinit var provider: OtpProvider
    @SpykBean lateinit var security: MerchRewardSecurity
    @SpykBean lateinit var phoneLock: kz.epharm.posm.staff.StaffPhoneLock

    @BeforeEach fun provider() {
        clearMocks(provider)
        every { provider.id } returns "test-external"
        every { provider.verificationMode } returns OtpVerificationMode.EXTERNAL
        every { provider.requestOtp(any(), any()) } just Runs
        every { provider.verifyOtp(any(), any()) } returns OtpVerificationResult.INVALID
        every { provider.verifyOtp(any(), "1234") } returns OtpVerificationResult.VERIFIED
    }

    @Test fun `claim and QR do not credit while approved bound reward credits the one existing wallet`() {
        val person = person()
        val claim = claim(person)
        assertThat(service.context(person.id, claim.token).amountKzt).isEqualTo(500)
        val granted = grant(person, claim)
        assertThat(balance(person)).isEqualTo(100)
        assertThat(granted.redirectUrl).startsWith("https://crm.inkar.kz/staff#claim=")
        assertThat(jdbc.queryForObject("SELECT token_hash FROM merch_executor_grants WHERE dispatch_id=?", String::class.java, claim.dispatch))
            .isEqualTo(MerchRewardSecurity.hash(granted.grant)).isNotEqualTo(granted.grant)
        val binding = service.redeem(MerchRedeemRequest(granted.grant, claim.dispatch, person.pharmacy, claim.nonce))
        assertThat(balance(person)).isEqualTo(100)
        val event = event(person, claim, binding)
        val ack = service.reward(event, json.writeValueAsBytes(event))
        assertThat(ack.credited).isTrue()
        assertThat(ack.duplicate).isFalse()
        assertThat(balance(person)).isEqualTo(600)
        assertThat(jdbc.queryForObject("SELECT earned_30d FROM pharmacists WHERE id=?", Long::class.java, person.id)).isEqualTo(500)
    }

    @Test fun `sixteen approval retries commit exactly one credit and immutable event`() {
        val person = person()
        val claim = claim(person)
        val binding = bind(person, claim)
        val event = event(person, claim, binding)
        val bytes = json.writeValueAsBytes(event)
        val results = parallel((1..16).map { { service.reward(event, bytes) } })
        assertThat(results.count { !it.duplicate }).isEqualTo(1)
        assertThat(balance(person)).isEqualTo(600)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_reward_events WHERE dispatch_id=?", Long::class.java, claim.dispatch)).isEqualTo(1)
    }

    @Test fun `event and dispatch identities cannot be reused with changed body amount or recipient`() {
        val person = person()
        val claim = claim(person)
        val binding = bind(person, claim)
        val event = event(person, claim, binding)
        service.reward(event, json.writeValueAsBytes(event))
        for (changed in listOf(event.copy(amountKzt = 501), event.copy(eventId = UUID.randomUUID()),
            event.copy(pharmacyId = "different"), event.copy(bindingId = UUID.randomUUID()), event.copy(dispatchId = UUID.randomUUID()))) {
            assertThatThrownBy { service.reward(changed, json.writeValueAsBytes(changed)) }.isInstanceOf(AppException::class.java)
        }
        assertThat(balance(person)).isEqualTo(600)
    }

    @Test fun `OTP belongs to challenge person and purpose not login or password JWT`() {
        val person = person()
        val first = claim(person)
        val otp = service.requestOtp(person.id, first.token)
        val second = claim(person)
        assertThatThrownBy { service.issueGrant(person.id, MerchGrantRequest(second.token, otp.otpChallengeId, "1234")) }.isInstanceOf(AppException::class.java)
        assertThatThrownBy { service.issueGrant(person.id, MerchGrantRequest(first.token, UUID.randomUUID(), "1234")) }.isInstanceOf(AppException::class.java)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_executor_grants WHERE dispatch_id=?", Long::class.java, first.dispatch)).isZero()
        val valid = service.issueGrant(person.id, MerchGrantRequest(first.token, otp.otpChallengeId, "1234"))
        assertThat(valid.grant).hasSize(43)
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM merch_executor_otp_challenges WHERE id=?",Boolean::class.java,otp.otpChallengeId)).isTrue()
        assertThatThrownBy { service.issueGrant(person.id, MerchGrantRequest(first.token, otp.otpChallengeId, "1234")) }.isInstanceOf(AppException::class.java)
        assertThatThrownBy { service.issueGrant(person.id, MerchGrantRequest(second.token, otp.otpChallengeId, "1234")) }.isInstanceOf(AppException::class.java)
    }

    @Test fun `two concurrent redemptions of one OTP issue only one grant`() {
        val person=person()
        val claim=claim(person)
        val otp=service.requestOtp(person.id,claim.token)
        val request=MerchGrantRequest(claim.token,otp.otpChallengeId,"1234")
        val results=parallel((1..2).map { { runCatching { service.issueGrant(person.id,request) } } })
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_executor_grants WHERE otp_challenge_id=?",Long::class.java,otp.otpChallengeId)).isEqualTo(1)
    }

    @Test fun `local provider money proof is six digits peppered and consumed once`() {
        val person=person()
        val claim=claim(person)
        every { provider.id } returns "test-local"
        every { provider.verificationMode } returns OtpVerificationMode.LOCAL
        val code=slot<String>()
        every { provider.requestOtp(person.phone,capture(code)) } just Runs
        val otp=service.requestOtp(person.id,claim.token)
        assertThat(code.captured).matches("[0-9]{6}")
        val stored=jdbc.queryForObject("SELECT code_hash FROM merch_executor_otp_challenges WHERE id=?",String::class.java,otp.otpChallengeId)
        assertThat(stored).isNotEqualTo(MerchRewardSecurity.hash(code.captured))
        service.issueGrant(person.id,MerchGrantRequest(claim.token,otp.otpChallengeId,code.captured))
        assertThatThrownBy { service.issueGrant(person.id,MerchGrantRequest(claim.token,otp.otpChallengeId,code.captured)) }.isInstanceOf(AppException::class.java)
    }

    @Test fun `slow provider cannot grant after isolated OTP expiry even while CRM challenge is current`() {
        val time=AtomicReference(Instant.now())
        every { security.currentTime() } answers { time.get() }
        val person=person()
        val claim=claim(person)
        val otp=service.requestOtp(person.id,claim.token)
        every { provider.verifyOtp(person.phone,"1234") } answers { time.set(time.get().plusSeconds(301)); OtpVerificationResult.VERIFIED }
        assertThatThrownBy { service.issueGrant(person.id,MerchGrantRequest(claim.token,otp.otpChallengeId,"1234")) }
            .isInstanceOf(AppException::class.java).hasMessage("Срок действия кода истёк")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_executor_grants WHERE otp_challenge_id=?",Long::class.java,otp.otpChallengeId)).isZero()
    }

    @Test fun `challenge expiring while waiting for phone lock cannot send a fresh money SMS`() {
        val time=AtomicReference(Instant.now())
        every { security.currentTime() } answers { time.get() }
        val person=person()
        val claim=claim(person)
        every { phoneLock.lock(person.phone) } answers { callOriginal(); time.set(time.get().plusSeconds(601)) }
        assertThatThrownBy { service.requestOtp(person.id,claim.token) }.isInstanceOf(AppException::class.java)
        verify(exactly=0) { provider.requestOtp(any(),any()) }
    }

    @Test fun `active self claimed or seeded branch without HQ mapping cannot start money verification`() {
        val person=person(trusted=false)
        val claim=claim(person)
        assertThatThrownBy { service.context(person.id,claim.token) }.isInstanceOf(AppException::class.java)
        assertThatThrownBy { service.requestOtp(person.id,claim.token) }.isInstanceOf(AppException::class.java)
        jdbc.update("""INSERT INTO posm_pharmacist_mappings(id,pharmacy_id,external_user_id,pharmacist_id)
            VALUES (?,?,?,?)""",person.mapping,person.pharmacy,"cashier-test",person.id)
        assertThatThrownBy { service.context(person.id,claim.token) }.isInstanceOf(AppException::class.java)
        verify(exactly=0) { provider.requestOtp(any(),any()) }
    }

    @Test fun `mapping with non HQ or inactive actor and blank external identity is not affiliation proof`() {
        val person=person()
        val claim=claim(person)
        jdbc.update("UPDATE admin_users SET role='BRAND_MANAGER' WHERE id=?",person.hq)
        assertThatThrownBy { service.context(person.id,claim.token) }.isInstanceOf(AppException::class.java)
        jdbc.update("UPDATE admin_users SET role='HQ_HEAD',status='blocked' WHERE id=?",person.hq)
        assertThatThrownBy { service.context(person.id,claim.token) }.isInstanceOf(AppException::class.java)
        jdbc.update("UPDATE admin_users SET status='active' WHERE id=?",person.hq)
        jdbc.update("UPDATE posm_pharmacist_mappings SET external_user_id=' ' WHERE id=?",person.mapping)
        assertThatThrownBy { service.context(person.id,claim.token) }.isInstanceOf(AppException::class.java)
    }

    @Test fun `HQ mapping revocation or remap invalidates already issued and bound money operations`() {
        val person=person()
        val claim=claim(person)
        val grant=grant(person,claim)
        jdbc.update("UPDATE posm_pharmacist_mappings SET active=false,revoked_at=now() WHERE id=?",person.mapping)
        assertThatThrownBy { service.redeem(MerchRedeemRequest(grant.grant,claim.dispatch,person.pharmacy,claim.nonce)) }.isInstanceOf(AppException::class.java)
        jdbc.update("UPDATE posm_pharmacist_mappings SET active=true,revoked_at=NULL WHERE id=?",person.mapping)
        val binding=service.redeem(MerchRedeemRequest(grant.grant,claim.dispatch,person.pharmacy,claim.nonce))
        val event=event(person,claim,binding)
        val other=person(pharmacy=person.pharmacy,trusted=false)
        jdbc.update("UPDATE posm_pharmacist_mappings SET pharmacist_id=? WHERE id=?",other.id,person.mapping)
        assertThatThrownBy { service.reward(event,json.writeValueAsBytes(event)) }.isInstanceOf(AppException::class.java)
        assertThatThrownBy { service.context(other.id,claim.token) }.isInstanceOf(AppException::class.java)
        assertThat(balance(person)).isEqualTo(100)
        assertThat(balance(other)).isEqualTo(100)
    }

    @Test fun `wrong OTP budget persists across transactions and provider outage is not an attempt`() {
        val person = person()
        val claim = claim(person)
        val otp = service.requestOtp(person.id, claim.token)
        every { provider.verifyOtp(person.phone, "9999") } throws AppException(ErrorCode.OTP_PROVIDER_UNAVAILABLE, "test outage")
        assertThatThrownBy { service.issueGrant(person.id, MerchGrantRequest(claim.token, otp.otpChallengeId, "9999")) }.isInstanceOf(AppException::class.java)
        assertThat(attempts(otp)).isZero()
        repeat(5) { assertThatThrownBy { service.issueGrant(person.id, MerchGrantRequest(claim.token, otp.otpChallengeId, "0000")) }.isInstanceOf(AppException::class.java) }
        assertThat(attempts(otp)).isEqualTo(5)
        assertThatThrownBy { service.issueGrant(person.id, MerchGrantRequest(claim.token, otp.otpChallengeId, "1234")) }
            .isInstanceOf(AppException::class.java).hasMessage("Запросите новый код")
    }

    @Test fun `mobile issuance shares cooldown and invalidates the separate money OTP proof`() {
        val person = person()
        val claim = claim(person)
        val otp = service.requestOtp(person.id, claim.token)
        assertThatThrownBy { mobileOtp.request(person.phone) }.isInstanceOf(AppException::class.java)
        jdbc.update("UPDATE posm_staff_rate_limits SET window_start=now()-interval '61 seconds' WHERE scope_hash=?", MerchRewardSecurity.hash("sms-shared-phone:${person.phone}"))
        mobileOtp.request(person.phone)
        mobileOtp.verify(person.phone, "1234")
        assertThatThrownBy { service.issueGrant(person.id, MerchGrantRequest(claim.token, otp.otpChallengeId, "1234")) }.isInstanceOf(AppException::class.java)
        assertThatThrownBy { service.issueGrant(person.id, MerchGrantRequest(claim.token, UUID.randomUUID(), "1234")) }.isInstanceOf(AppException::class.java)
        assertThatThrownBy { service.requestOtp(person.id, claim.token) }.isInstanceOf(AppException::class.java)
    }

    @Test fun `changed phone inactive person and wrong pharmacy fail closed before granting or credit`() {
        val person = person()
        val claim = claim(person)
        val otp = service.requestOtp(person.id, claim.token)
        jdbc.update("UPDATE pharmacists SET phone='+77009990000' WHERE id=?", person.id)
        assertThatThrownBy { service.issueGrant(person.id, MerchGrantRequest(claim.token, otp.otpChallengeId, "1234")) }.isInstanceOf(AppException::class.java)
        assertThatThrownBy { service.context(person.id, claim(person, pharmacy = "not-the-branch").token) }.isInstanceOf(AppException::class.java)
        jdbc.update("UPDATE pharmacists SET status='blocked' WHERE id=?", person.id)
        assertThatThrownBy { service.context(person.id, claim.token) }.isInstanceOf(AppException::class.java)
    }

    @Test fun `one dispatch never rebinds to a second verified actual person`() {
        val first = person()
        val second = person(pharmacy = first.pharmacy)
        val claim = claim(first)
        val binding = bind(first, claim)
        val grant = grant(second, claim)
        assertThatThrownBy { service.redeem(MerchRedeemRequest(grant.grant, claim.dispatch, first.pharmacy, claim.nonce)) }.isInstanceOf(AppException::class.java)
        assertThat(binding.pharmacistId).isEqualTo(first.id)
        assertThat(balance(first)).isEqualTo(100)
        assertThat(balance(second)).isEqualTo(100)
    }

    @Test fun `redeem is exact one use with idempotent same-binding retry even after expiry`() {
        val person = person()
        val claim = claim(person)
        val grant = grant(person, claim)
        val request = MerchRedeemRequest(grant.grant, claim.dispatch, person.pharmacy, claim.nonce)
        assertThatThrownBy { service.redeem(request.copy(nonce = "x".repeat(43))) }.isInstanceOf(AppException::class.java)
        val binding = service.redeem(request)
        jdbc.update("UPDATE merch_executor_grants SET expires_at=now()-interval '1 minute' WHERE token_hash=?", MerchRewardSecurity.hash(grant.grant))
        assertThat(service.redeem(request)).isEqualTo(binding)
        assertThatThrownBy { service.redeem(request.copy(pharmacyId = "wrong")) }.isInstanceOf(AppException::class.java)
    }

    @Test fun `expired unredeemed grant is never refreshed or bound`() {
        val person = person()
        val claim = claim(person)
        val grant = grant(person, claim)
        jdbc.update("UPDATE merch_executor_grants SET expires_at=now()-interval '1 second' WHERE token_hash=?", MerchRewardSecurity.hash(grant.grant))
        assertThatThrownBy { service.redeem(MerchRedeemRequest(grant.grant, claim.dispatch, person.pharmacy, claim.nonce)) }.isInstanceOf(AppException::class.java)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_executor_bindings WHERE dispatch_id=?", Long::class.java, claim.dispatch)).isZero()
    }

    @Test fun `changed quoted reward and blocked transferred person cannot receive approval money`() {
        val person = person()
        val claim = claim(person)
        val event = event(person,claim,bind(person,claim))
        assertThatThrownBy { val bad=event.copy(amountKzt=501); service.reward(bad,json.writeValueAsBytes(bad)) }.isInstanceOf(AppException::class.java)
        jdbc.update("UPDATE pharmacists SET status='blocked' WHERE id=?",person.id)
        assertThatThrownBy { service.reward(event,json.writeValueAsBytes(event)) }.isInstanceOf(AppException::class.java)
        val other=person()
        jdbc.update("UPDATE pharmacists SET status='active',pharmacy_id=? WHERE id=?",other.pharmacy,person.id)
        assertThatThrownBy { service.reward(event,json.writeValueAsBytes(event)) }.isInstanceOf(AppException::class.java)
        assertThat(balance(person)).isEqualTo(100)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_reward_events WHERE dispatch_id=?",Long::class.java,claim.dispatch)).isZero()
    }

    @Test fun `different concurrent approval ids for one dispatch credit once and leave conflicting event unrecorded`() {
        val person=person()
        val claim=claim(person)
        val first=event(person,claim,bind(person,claim))
        val second=first.copy(eventId=UUID.randomUUID())
        val results=parallel(listOf(first,second).map { event -> { runCatching { service.reward(event,json.writeValueAsBytes(event)) } } })
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.count { it.isFailure }).isEqualTo(1)
        assertThat(balance(person)).isEqualTo(600)
    }

    @Test fun `SMS transport failure consumes attempted proof without leaking code and shared cooldown survives`() {
        val person=person()
        val claim=claim(person)
        every { provider.requestOtp(person.phone,any()) } throws IllegalStateException("transport test failure")
        assertThatThrownBy { service.requestOtp(person.id,claim.token) }.isInstanceOf(AppException::class.java)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_executor_otp_challenges WHERE pharmacist_id=? AND consumed_at IS NULL",Long::class.java,person.id)).isZero()
        assertThatThrownBy { service.requestOtp(person.id,claim.token) }.isInstanceOf(AppException::class.java)
        assertThat(balance(person)).isEqualTo(100)
    }

    @Test fun `late manager approval is valid but submission before binding or after execution deadline is not`() {
        val person = person()
        val dispatch = UUID.randomUUID()
        val binding = UUID.randomUUID()
        val now = Instant.now()
        val created = now.minusSeconds(3600)
        val due = now.minusSeconds(1800)
        jdbc.update("""INSERT INTO merch_executor_bindings(id,dispatch_id,pharmacist_id,pharmacy_id,reward_kzt,task_due_at,verified_at,created_at)
            VALUES (?,?,?,?,500,?,?,?)""", binding, dispatch, person.id, person.pharmacy, Timestamp.from(due), Timestamp.from(created), Timestamp.from(created))
        val event = MerchRewardEvent(1,"merch_task_approved",UUID.randomUUID(),dispatch,binding,person.pharmacy,500,"KZT",created.plusSeconds(100),now,UUID.randomUUID())
        for (bad in listOf(event.copy(submittedAt = created.minusSeconds(1)),event.copy(submittedAt = due.plusSeconds(1)),
            event.copy(approvedAt = created),event.copy(approvedAt = now.plusSeconds(301)))) {
            assertThatThrownBy { service.reward(bad, json.writeValueAsBytes(bad)) }.isInstanceOf(AppException::class.java)
        }
        assertThat(service.reward(event, json.writeValueAsBytes(event)).credited).isTrue()
        assertThat(balance(person)).isEqualTo(600)
    }

    @Test fun `signed HTTP trusts persisted recipient rather than supplied identity and returns stable ACK`() {
        val person = person()
        val claim = claim(person)
        val event = event(person, claim, bind(person, claim))
        val path = "/api/integrations/merch/reward-events"
        val bytes = json.writeValueAsBytes(event)
        mvc.perform(post(path).contentType("application/json").content(bytes)).andExpect(status().isUnauthorized)
        signed(path, bytes, 201)
        val duplicate = signed(path, bytes, 200)
        assertThat(duplicate["eventId"].asText()).isEqualTo(event.eventId.toString())
        assertThat(duplicate["credited"].asBoolean()).isTrue()
        assertThat(duplicate["duplicate"].asBoolean()).isTrue()
        signed(path, json.writeValueAsBytes(event.copy(amountKzt = 501)), 409)
        assertThat(balance(person)).isEqualTo(600)
    }

    @Test fun `signed redeem HTTP binds opaque grant and rejects malformed or oversized body`() {
        val person=person()
        val claim=claim(person)
        val grant=grant(person,claim)
        val path="/api/integrations/merch/executor-grants/redeem"
        val body=json.writeValueAsBytes(MerchRedeemRequest(grant.grant,claim.dispatch,person.pharmacy,claim.nonce))
        val response=signed(path,body,200)
        assertThat(response["pharmacistId"].asText()).isEqualTo(person.id)
        assertThat(response["dispatchId"].asText()).isEqualTo(claim.dispatch.toString())
        signed(path,"{broken".toByteArray(),400)
        signed(path,ByteArray(16385),401)
    }

    @Test fun `mobile boundary requires pharmacist JWT reads DB phone and marks context no-store`() {
        val person = person()
        val claim = claim(person)
        val path = "/api/mobile/merch/executor-grants/context"
        val body = json.writeValueAsBytes(MerchContextRequest(claim.token))
        mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().isUnauthorized)
        val token = jwt.issuePharmacistToken(person.id, "TEST", "+77000000000")
        val result = mvc.perform(post(path).header("Authorization", "Bearer $token").contentType("application/json").content(body))
            .andExpect(status().isOk).andReturn().response
        assertThat(result.getHeader("Cache-Control")).contains("no-store")
        assertThat(json.readTree(result.contentAsByteArray)["phoneMasked"].asText()).isNotEqualTo("+7 (700) ***-**-00")
        service.requestOtp(person.id, claim.token)
        verify(exactly = 1) { provider.requestOtp(person.phone, null) }
    }

    @Test fun `bindings and credited approvals are append only`() {
        val person = person()
        val claim = claim(person)
        val binding = bind(person, claim)
        val event = event(person, claim, binding)
        service.reward(event, json.writeValueAsBytes(event))
        assertThatThrownBy { jdbc.update("DELETE FROM merch_executor_bindings WHERE id=?", binding.bindingId) }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { jdbc.update("UPDATE merch_reward_events SET amount_kzt=1 WHERE event_id=?", event.eventId) }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private data class Person(val id: String, val pharmacy: String, val phone: String, val hq: UUID, val mapping: UUID)
    private data class Claim(val token: String, val dispatch: UUID, val nonce: String)
    private fun person(pharmacy: String = "mp_${UUID.randomUUID()}",trusted: Boolean=true): Person {
        jdbc.update("INSERT INTO chains(id,name,\"group\") VALUES ('merch-money-chain','TEST','pilot') ON CONFLICT DO NOTHING")
        jdbc.update("INSERT INTO pharmacies(id,name,chain_id,chain_name,city,\"group\") VALUES (?,?,'merch-money-chain','TEST','TEST','pilot') ON CONFLICT DO NOTHING", pharmacy, pharmacy)
        val n = sequence.incrementAndGet().toString()
        val person = Person("mph_${UUID.randomUUID()}", pharmacy, "+7${n.takeLast(10)}",UUID.randomUUID(),UUID.randomUUID())
        jdbc.update("""INSERT INTO pharmacists(id,name,iin,phone,pharmacy_id,pharmacy_name,tier,status,balance)
            VALUES (?,'TEST',?,?,?,'TEST','Silver','active',100)""", person.id,n,person.phone,pharmacy)
        if(trusted) {
            jdbc.update("""INSERT INTO admin_users(id,email,password_hash,name,role,company,status)
                VALUES (?,?,'unused','TEST HQ','HQ_HEAD','TEST','active')""",person.hq,"${person.hq}@test.invalid")
            jdbc.update("""INSERT INTO posm_pharmacist_mappings(id,pharmacy_id,external_user_id,pharmacist_id,created_by,updated_by)
                VALUES (?,?,?,?,?,?)""",person.mapping,pharmacy,"cashier-$n",person.id,person.hq,person.hq)
            jdbc.update("""INSERT INTO posm_pharmacist_mapping_audit(mapping_id,action,pharmacy_id,external_user_id,pharmacist_id,actor_id)
                VALUES (?,'created',?,?,?,?)""",person.mapping,pharmacy,"cashier-$n",person.id,person.hq)
        }
        return person
    }
    private fun claim(person: Person, pharmacy: String = person.pharmacy): Claim {
        val now = Instant.now().epochSecond
        val id = UUID.randomUUID()
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().toByteArray().copyOf(32))
        val payload = json.writeValueAsBytes(mapOf("iss" to "crm.inkar.kz","aud" to "epharm","dispatchId" to id.toString(),
            "pharmacyId" to pharmacy,"nonce" to nonce,"iat" to now,"exp" to now+600,"taskDueAt" to now+3600,"taskTitle" to "Фото полки","rewardKzt" to 500))
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac("epharm.merch.claim.v1\n$b64"))
        return Claim("v1.$b64.$signature",id,nonce)
    }
    private fun grant(person: Person, claim: Claim): MerchGrantResponse {
        val otp = service.requestOtp(person.id, claim.token)
        return service.issueGrant(person.id, MerchGrantRequest(claim.token,otp.otpChallengeId,"1234"))
    }
    private fun bind(person: Person, claim: Claim): MerchBindingResponse {
        val grant = grant(person,claim)
        return service.redeem(MerchRedeemRequest(grant.grant,claim.dispatch,person.pharmacy,claim.nonce))
    }
    private fun event(person: Person, claim: Claim, binding: MerchBindingResponse): MerchRewardEvent {
        val created=jdbc.queryForObject("SELECT created_at FROM merch_executor_bindings WHERE id=?",Timestamp::class.java,binding.bindingId)!!.toInstant()
        val now=Instant.now()
        assertThat(now).describedAs("JVM clock versus binding DB clock").isAfterOrEqualTo(created)
        return MerchRewardEvent(1,"merch_task_approved",UUID.randomUUID(),claim.dispatch,binding.bindingId,person.pharmacy,500,"KZT",now,now,UUID.randomUUID())
    }
    private fun balance(person: Person) = jdbc.queryForObject("SELECT balance FROM pharmacists WHERE id=?",Long::class.java,person.id)!!
    private fun attempts(otp: MerchOtpResponse) = jdbc.queryForObject("SELECT attempts FROM merch_executor_otp_challenges WHERE id=?",Int::class.java,otp.otpChallengeId)!!
    private fun mac(value: String) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(SECRET.toByteArray(),"HmacSHA256")); doFinal(value.toByteArray())
    }
    private fun signed(path: String, bytes: ByteArray, expected: Int): com.fasterxml.jackson.databind.JsonNode {
        val timestamp = Instant.now().epochSecond.toString()
        val signature = mac("epharm.merch.request.v1\n$timestamp\nPOST\n$path\n${MerchRewardSecurity.hash(bytes)}").joinToString("") { "%02x".format(it) }
        val response = mvc.perform(post(path).header("X-Merch-Timestamp",timestamp).header("X-Merch-Signature",signature)
            .contentType("application/json").content(bytes)).andExpect(status().`is`(expected)).andReturn().response
        return json.readTree(response.contentAsByteArray)
    }
    private fun <T> parallel(work: List<() -> T>): List<T> {
        val pool = Executors.newFixedThreadPool(work.size)
        val start = CountDownLatch(1)
        return try {
            val futures = work.map { pool.submit(Callable { check(start.await(10, TimeUnit.SECONDS)); it() }) }
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }
}
