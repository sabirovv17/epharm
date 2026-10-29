package kz.epharm.merch.delivery

import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.merchtasks.dto.MerchTaskDto
import kz.epharm.merchtasks.dto.MerchTaskShownRequest
import kz.epharm.shared.error.AppException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class NativeTaskDeliveryIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("native_delivery").withUsername("epharm").withPassword("synthetic-only").apply {start()}
        @JvmStatic @DynamicPropertySource fun props(reg:DynamicPropertyRegistry) {
            reg.add("spring.datasource.url"){postgres.jdbcUrl};reg.add("spring.datasource.username"){postgres.username}
            reg.add("spring.datasource.password"){postgres.password};reg.add("app.merch.native-delivery-enabled"){true}
        }
    }
    @Autowired lateinit var jdbc:JdbcTemplate
    @Autowired lateinit var users:AdminUserRepository
    @Autowired lateinit var delivery:NativeMerchTaskDeliveryService
    @Autowired lateinit var capabilities:NativeCapabilityAuthorization
    @Autowired lateinit var manager:PlatformTransactionManager
    @Autowired lateinit var mvc:MockMvc
    @Autowired lateinit var json:ObjectMapper
    @MockkBean lateinit var clock:Clock
    private lateinit var admin:AdminUserEntity
    private lateinit var branch:UUID
    private var now=Instant.parse("2026-10-29T09:00:00Z")
    private val tx get()=TransactionTemplate(manager)

    @BeforeEach fun seed() {
        jdbc.execute("TRUNCATE merch_task_dispatches,merch_branches,merch_organizations,merch_media_plans,admin_users,pharmacies,chains CASCADE")
        now=Instant.parse("2026-10-29T09:00:00Z");every {clock.instant()} answers {now}
        ReflectionTestUtils.setField(delivery,"enabled",true)
        admin=users.saveAndFlush(AdminUserEntity(email="native-delivery@example.invalid",name="Synthetic author",passwordHash="unused",roleRaw=AdminRole.HQ_HEAD.name))
        jdbc.update("""INSERT INTO chains(id,name,color,"group",points) VALUES('native-chain','Synthetic','#000000','pilot',0)""")
        for(id in listOf("native-pharmacy","foreign-pharmacy")) jdbc.update("""INSERT INTO pharmacies(id,name,chain_id,chain_name,city,district,addr,"group")
            VALUES(?,?,'native-chain','Synthetic','Synthetic','','','pilot')""",id,id)
        val org=UUID.randomUUID();branch=UUID.randomUUID()
        jdbc.update("""INSERT INTO merch_organizations(id,source_system,source_origin,source_id,source_payload,name,legal_name,active,created_at,updated_at)
            VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}','Synthetic','Synthetic',true,now(),now())""",org,org)
        jdbc.update("""INSERT INTO merch_branches(id,source_system,source_origin,source_id,source_payload,organization_id,epharm_pharmacy_id,pharmacy_binding_verified,
            code,name,address,city,active,created_at,updated_at) VALUES(?,'EPHARM','https://epharm.inkar.kz',?,'{}',?,'native-pharmacy',true,'001','Synthetic','Synthetic','Synthetic',true,now(),now())""",branch,branch,org)
    }
    private fun dispatch(status:String="ready",reward:Long=0,source:String="EPHARM",payload:Map<String,Any?> = emptyMap(),
        sourcePlan:UUID?=null,plan:UUID?=null,sourceOwner:UUID?=null,training:UUID?=null,due:Instant?=now.plusSeconds(3600),future:Instant?=null,
        priority:String="normal",automation:String?=null):UUID {
        val id=UUID.randomUUID()
        jdbc.update("""INSERT INTO merch_task_dispatches(id,source_system,source_origin,source_id,source_payload,branch_id,title,status,
            priority,task_type,due_at,reward_amount_kzt,created_by,source_mass_owner_id,source_media_plan_id,media_plan_id,automation_kind,created_at,updated_at)
            VALUES(?,?,?,?,CAST(? AS jsonb),?,'Synthetic task',?,?,'photo_report',?,?,?,?,?,?,?,now(),now())""",id,source,
            if(source=="EPHARM") "https://epharm.inkar.kz" else "https://crm.inkar.kz",id,json.writeValueAsString(payload),branch,status,priority,
            due?.let(Timestamp::from),reward,if(source=="EPHARM")admin.id else null,sourceOwner,sourcePlan,plan,automation)
        val item=UUID.randomUUID()
        jdbc.update("""INSERT INTO merch_task_items(id,dispatch_id,source_id,source_payload,title,instructions,task_type,available_from,due_at,
            status,required,required_photo_count,training_material_id,created_at,updated_at)
            VALUES(?,?,?,'{}','Synthetic shelf','','photo_report',?,?,'assigned',true,0,?,now(),now())""",item,id,item,future?.let(Timestamp::from),due?.let(Timestamp::from),training)
        return id
    }
    private fun plan(status:String="approved",month:LocalDate=LocalDate.of(2026,10,1)):UUID {
        val id=UUID.randomUUID()
        jdbc.update("""INSERT INTO merch_media_plans(id,source_system,source_origin,source_id,source_payload,advertiser_name,placement_month,status,
            origin,total_amount,submitted_at,created_at,updated_at) VALUES(?,'MERCH_CRM','https://crm.inkar.kz',?,'{}','Synthetic',?,?,'request',0,now(),now(),now())""",id,id,month,status)
        return id
    }
    private fun issued():MerchTaskDto=delivery.activeTask("native-pharmacy").task!!
    private fun receipt(task:MerchTaskDto,device:String="SYNTHETIC-POS-01",pharmacy:String="native-pharmacy")=
        MerchTaskShownRequest(task.id,pharmacy,device,task.deliveryToken)
    private fun count(table:String)=jdbc.queryForObject("SELECT count(*) FROM $table",Long::class.java)!!

    @Test fun `poll reuses one opaque capability and never records a display until actual ACK`() {
        val id=dispatch();val first=issued();val second=issued()
        assertThat(first.id).isEqualTo(id.toString());assertThat(second).isEqualTo(first)
        assertThat(first.deliveryToken).matches("[A-Za-z0-9_-]{48}")
        assertThat(first.publicUrl).isEqualTo("https://epharm.inkar.kz/tasks/staff?task=1#task=${first.deliveryToken}")
        assertThat(count("merch_task_links")).isEqualTo(1);assertThat(count("merch_task_delivery_receipts")).isZero()
        assertThat(count("merch_task_events")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT status FROM merch_task_dispatches WHERE id=?",String::class.java,id)).isEqualTo("sent")
        assertThat(jdbc.queryForObject("SELECT token_hash FROM merch_task_links",String::class.java)).isEqualTo(NativeCapabilityAuthorization.tokenHash(first.deliveryToken))
    }
    @Test fun `only an active verified pharmacy branch receives tasks`() {
        dispatch();assertThat(delivery.activeTask("foreign-pharmacy").task).isNull()
        jdbc.update("UPDATE merch_branches SET active=false WHERE id=?",branch)
        assertThat(delivery.activeTask("native-pharmacy").task).isNull()
        jdbc.update("UPDATE merch_branches SET active=true,epharm_pharmacy_id=NULL,pharmacy_binding_verified=false WHERE id=?",branch)
        assertThat(delivery.activeTask("native-pharmacy").task).isNull();assertThat(count("merch_task_links")).isZero()
    }
    @Test fun `expired future and closed tasks are filtered and revision urgent ordering is preserved`() {
        dispatch(due=now.minusSeconds(1));dispatch(status="submitted");dispatch(status="cancelled")
        dispatch(future=now.plusSeconds(1));dispatch(priority="urgent")
        val expected=dispatch(status="revision_required",priority="low")
        assertThat(issued().id).isEqualTo(expected.toString())
    }
    @Test fun `CRM delivery needs explicit manager or training projection`() {
        dispatch(source="MERCH_CRM")
        assertThat(delivery.activeTask("native-pharmacy").task).isNull()
        val training=dispatch(source="MERCH_CRM",training=UUID.randomUUID())
        assertThat(issued().id).isEqualTo(training.toString())
    }
    @Test fun `parent approval and verified exact source plan gate both issuing and existing capability use`() {
        val plan=plan("pending")
        dispatch(source="MERCH_CRM",payload=mapOf("media_plan_id" to plan),sourcePlan=plan,plan=plan,sourceOwner=UUID.randomUUID())
        assertThat(delivery.activeTask("native-pharmacy").task).isNull()
        jdbc.update("UPDATE merch_media_plans SET status='approved' WHERE id=?",plan)
        val task=issued();assertThat(capabilities.authorize(task.deliveryToken).readOnly).isFalse()
        jdbc.update("UPDATE merch_media_plans SET status='cancelled' WHERE id=?",plan)
        assertThat(delivery.markShown(receipt(task)).accepted).isFalse()
        assertThatThrownBy {capabilities.authorize(task.deliveryToken)}.isInstanceOf(AppException::class.java)
        assertThat(delivery.ownsNativeDeliveryToken(task.deliveryToken)).isTrue()
    }
    @Test fun `missing source plan projection never becomes a planless eligible task`() {
        dispatch(source="MERCH_CRM",payload=mapOf("media_plan_id" to UUID.randomUUID()),sourceOwner=UUID.randomUUID())
        dispatch(source="MERCH_CRM",sourcePlan=UUID.randomUUID(),sourceOwner=UUID.randomUUID())
        assertThat(delivery.activeTask("native-pharmacy").task).isNull();assertThat(count("merch_task_links")).isZero()
    }
    @Test fun `ACK is exact pharmacy dispatch and token and duplicate delivery is idempotent`() {
        dispatch();val task=issued()
        assertThat(delivery.markShown(receipt(task,pharmacy="foreign-pharmacy")).accepted).isFalse()
        assertThat(delivery.markShown(receipt(task).copy(dispatchId=UUID.randomUUID().toString())).accepted).isFalse()
        assertThat(delivery.markShown(receipt(task).copy(deliveryToken="x".repeat(48))).accepted).isFalse()
        assertThat(delivery.markShown(receipt(task)).accepted).isTrue()
        assertThat(delivery.markShown(receipt(task)).accepted).isTrue()
        assertThat(count("merch_task_delivery_receipts")).isEqualTo(1)
        assertThat(delivery.markShown(receipt(task,device="SYNTHETIC-POS-02")).accepted).isTrue()
        assertThat(count("merch_task_delivery_receipts")).isEqualTo(2)
    }
    @Test fun `issued capability and ACK survive issuance flag rollback but revoked or expired ownership never falls through`() {
        dispatch();val task=issued();ReflectionTestUtils.setField(delivery,"enabled",false)
        assertThat(delivery.activeTask("native-pharmacy").task).isNull()
        assertThat(capabilities.authorize(task.deliveryToken).dispatchId.toString()).isEqualTo(task.id)
        assertThat(delivery.markShown(receipt(task)).accepted).isTrue()
        jdbc.update("UPDATE merch_task_links SET revoked_at=?",Timestamp.from(now))
        assertThat(delivery.ownsNativeDeliveryToken(task.deliveryToken)).isTrue()
        assertThat(delivery.markShown(receipt(task)).accepted).isFalse()
        assertThatThrownBy {capabilities.authorize(task.deliveryToken)}.isInstanceOf(AppException::class.java)
        jdbc.update("UPDATE merch_task_links SET revoked_at=NULL,expires_at=?",Timestamp.from(now.minusSeconds(1)))
        assertThat(delivery.ownsNativeDeliveryToken(task.deliveryToken)).isTrue()
        assertThat(delivery.markShown(receipt(task)).accepted).isFalse()
    }
    @Test fun `anonymous paid capability can read metadata but cannot mutate and free capability invents no person`() {
        val id=dispatch(reward=300);val task=issued()
        mvc.perform(get("/api/public/merch/tasks/session").header("X-Merch-Task-Token",task.deliveryToken))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Referrer-Policy","no-referrer"))
            .andExpect(jsonPath("$.dispatchId").value(id.toString())).andExpect(jsonPath("$.identityRequired").value(true))
            .andExpect(jsonPath("$.deliveryToken").doesNotExist()).andExpect(jsonPath("$.personId").doesNotExist())
        assertThatThrownBy {tx.execute {capabilities.requireFreeMutation(task.deliveryToken)}}
            .isInstanceOfSatisfying(AppException::class.java){assertThat(it.status).isEqualTo(HttpStatus.FORBIDDEN)}
        jdbc.update("UPDATE merch_task_dispatches SET reward_amount_kzt=0 WHERE id=?",id)
        assertThat(tx.execute {capabilities.requireFreeMutation(task.deliveryToken).linkId}).isNotNull()
        assertThat(count("unified_persons")).isZero();assertThat(count("pharmacists")).isZero();assertThat(count("merch_task_rewards")).isZero()
    }
    @Test fun `preserved generic or kiosk links are not claimed as native POSM but verified imported POSM is reusable`() {
        val id=dispatch(source="MERCH_CRM",sourceOwner=UUID.randomUUID());val token="z".repeat(48);val expiry=now.plusSeconds(4000)
        jdbc.update("""INSERT INTO merch_task_links(id,dispatch_id,token_hash,integration_access_token,issued_channel,expires_at,source_payload)
            VALUES(?,?,?,?,'pharmapay_posm',?,'{"preserved":true}')""",UUID.randomUUID(),id,NativeCapabilityAuthorization.tokenHash(token),token,Timestamp.from(expiry))
        assertThat(delivery.ownsNativeDeliveryToken(token)).isFalse()
        assertThatThrownBy {capabilities.authorize(token)}.isInstanceOf(AppException::class.java)
        // This explicit fixture represents the importer's verified operational projection, never a payload-supplied flag.
        jdbc.update("UPDATE merch_task_links SET native_owned=true WHERE dispatch_id=?",id)
        val task=issued();assertThat(task.deliveryToken).isEqualTo(token);assertThat(task.expiresAt).isEqualTo(expiry)
        assertThat(count("merch_task_links")).isEqualTo(1)
        assertThat(capabilities.authorize(token).dispatchId).isEqualTo(id)
    }
    @Test fun `closed due or future capability is read only and cannot be used for free edits or shown ACK`() {
        val id=dispatch();val task=issued();now=now.plusSeconds(3601)
        assertThat(capabilities.authorize(task.deliveryToken).readOnly).isTrue()
        assertThat(delivery.markShown(receipt(task)).accepted).isFalse()
        assertThatThrownBy {tx.execute {capabilities.requireFreeMutation(task.deliveryToken)}}.isInstanceOf(AppException::class.java)
        now=now.minusSeconds(3601)
        jdbc.update("UPDATE merch_task_items SET available_from=? WHERE dispatch_id=?",Timestamp.from(now.plusSeconds(1)),id)
        assertThat(capabilities.authorize(task.deliveryToken).readOnly).isTrue()
    }
    @Test fun `weekly first delivery sets source KZ month boundary SLA without repeatedly extending it`() {
        val plan=plan();dispatch(source="MERCH_CRM",payload=mapOf("media_plan_id" to plan),sourcePlan=plan,plan=plan,
            sourceOwner=UUID.randomUUID(),due=null,automation="weekly_placement_photo_report")
        val task=issued()
        assertThat(task.dueAt).isEqualTo(Instant.parse("2026-10-31T19:00:00Z"))
        now=now.plusSeconds(30)
        assertThat(issued().dueAt).isEqualTo(task.dueAt)
    }
    @Test fun `revocation committed while free mutation waits on link is checked under transaction locks`() {
        dispatch();val task=issued();val acquired=CountDownLatch(1);val release=CountDownLatch(1)
        val pool=Executors.newFixedThreadPool(2)
        try {
            val revoke=pool.submit {tx.execute {jdbc.queryForList("SELECT id FROM merch_task_links WHERE token_hash=? FOR UPDATE",NativeCapabilityAuthorization.tokenHash(task.deliveryToken));acquired.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();jdbc.update("UPDATE merch_task_links SET revoked_at=now()")}}
            assertThat(acquired.await(5,TimeUnit.SECONDS)).isTrue()
            val mutation=pool.submit<Boolean> {try {tx.execute {capabilities.requireFreeMutation(task.deliveryToken)};true} catch(_:AppException){false}}
            assertThatThrownBy {mutation.get(150,TimeUnit.MILLISECONDS)}.isInstanceOf(TimeoutException::class.java)
            release.countDown();revoke.get(5,TimeUnit.SECONDS)
            assertThat(mutation.get(5,TimeUnit.SECONDS)).isFalse();assertThat(count("merch_task_attempts")).isZero()
        } finally {release.countDown();pool.shutdownNow()}
    }
}
