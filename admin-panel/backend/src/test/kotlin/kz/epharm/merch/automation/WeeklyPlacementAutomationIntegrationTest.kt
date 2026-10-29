package kz.epharm.merch.automation

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.merch.delivery.NativeMerchTaskDeliveryService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(properties=["app.merch.automation.enabled=true","app.merch.automation.initial-delay-ms=86400000","app.merch.native-delivery-enabled=true"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class WeeklyPlacementAutomationIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine").withDatabaseName("merch_automation_disposable")
            .withUsername("epharm").withPassword("synthetic-only").apply {start()}
        @JvmStatic @DynamicPropertySource fun props(reg:DynamicPropertyRegistry) {
            reg.add("spring.datasource.url"){postgres.jdbcUrl};reg.add("spring.datasource.username"){postgres.username};reg.add("spring.datasource.password"){postgres.password}
        }
    }
    @Autowired lateinit var jdbc:JdbcTemplate
    @Autowired lateinit var named:NamedParameterJdbcTemplate
    @Autowired lateinit var users:AdminUserRepository
    @Autowired lateinit var json:ObjectMapper
    @Autowired lateinit var engine:WeeklyPlacementAutomation
    @Autowired lateinit var delivery:NativeMerchTaskDeliveryService
    @Autowired lateinit var manager:PlatformTransactionManager
    @Autowired lateinit var mvc:MockMvc
    @Autowired lateinit var jwt:JwtService
    @MockkBean lateinit var clock:Clock
    private lateinit var now:Instant
    private lateinit var admin:AdminUserEntity
    private lateinit var person:UUID
    private lateinit var branch:UUID
    private lateinit var shelf:UUID
    private val month=LocalDate.of(2026,11,1)
    @BeforeEach fun seed() {
        jdbc.execute("TRUNCATE merch_task_dispatches,merch_automation_branch_owners,merch_automation_owner_audit,merch_media_plans,merch_shelves,merch_branches,merch_organizations,unified_external_accounts,unified_admin_profiles,unified_persons,admin_users,pharmacies,chains CASCADE")
        now=Instant.parse("2026-10-20T09:00:00Z");every {clock.instant()} answers {now}
        ReflectionTestUtils.setField(engine,"enabled",true)
        admin=users.saveAndFlush(AdminUserEntity(email="automation-owner@example.invalid",name="Synthetic owner",passwordHash="unused",roleRaw=AdminRole.HQ_HEAD.name))
        person=UUID.randomUUID();jdbc.update("INSERT INTO unified_persons(id,verification_reference) VALUES(?,'synthetic-explicit-proof')",person)
        jdbc.update("INSERT INTO unified_admin_profiles(account_id,person_id) VALUES(?,?)",admin.id,person)
        jdbc.update("""INSERT INTO chains(id,name,color,"group",points) VALUES('automation-chain','Synthetic','#000000','pilot',0)""")
        jdbc.update("""INSERT INTO pharmacies(id,name,chain_id,chain_name,city,district,addr,"group") VALUES('automation-pharmacy','Synthetic','automation-chain','Synthetic','Synthetic','','','pilot')""")
        val org=source("merch_organizations",mapOf("name" to "Synthetic","legal_name" to "Synthetic","active" to true))
        branch=source("merch_branches",mapOf("organization_id" to org,"code" to "A","name" to "Synthetic branch","city" to "Synthetic","address" to "Synthetic",
            "active" to true,"epharm_pharmacy_id" to "automation-pharmacy","pharmacy_binding_verified" to true))
        shelf=shelf("A-001")
        owner()
    }
    private fun source(table:String,fields:Map<String,Any?>,payload:Map<String,Any?> = emptyMap()):UUID {
        val id=UUID.randomUUID();val row=linkedMapOf<String,Any?>("id" to id,"source_system" to "MERCH_CRM","source_origin" to "https://crm.inkar.kz","source_id" to id,
            "source_payload" to json.writeValueAsString(payload),"created_at" to Timestamp.from(now))
        if(table!="merch_media_plan_items") row["updated_at"]=Timestamp.from(now)
        row.putAll(fields)
        named.update("INSERT INTO $table(${row.keys.joinToString(",")}) VALUES(${row.keys.joinToString(","){if(it in setOf("source_payload","specifications")) "CAST(:$it AS jsonb)" else ":$it"}})",row)
        return id
    }
    private fun shelf(code:String)=source("merch_shelves",mapOf("branch_id" to branch,"code" to code,"category" to "AA","width_m" to BigDecimal.ONE,"base_monthly_price" to 15000,
        "active" to true,"place_type" to "shelf","position_index" to 0,"specifications" to "{}","sale_mode" to "whole_or_halves","first_photo_ref" to "/api/placement-files/first.png"),
        mapOf("template_image_url" to "https://crm.inkar.kz/api/placement-files/template.png"))
    private fun plan(status:String="approved",count:Int=1,payload:Map<String,Any?> = emptyMap()):UUID {
        val id=source("merch_media_plans",mapOf("advertiser_name" to "Synthetic source campaign","placement_month" to month,"status" to status,"origin" to "request",
            "total_amount" to 15000,"reviewed_at" to Timestamp.from(now.minusSeconds(60)),"submitted_at" to Timestamp.from(now)),payload)
        repeat(count) {index-> val place=if(index==0) shelf else shelf("A-${UUID.randomUUID()}")
            source("merch_media_plan_items",mapOf("media_plan_id" to id,"shelf_id" to place,"placement_month" to month,"monthly_price" to 15000,
                "status" to "approved","sale_portion" to "left","client_photo_ref" to "/api/request-item-files/client.png"))}
        return id
    }
    private fun owner() {jdbc.update("""INSERT INTO merch_automation_branch_owners(branch_id,source_system,admin_account_id,person_id,active,revision,verification_reference,created_at,updated_at)
        VALUES(?,'EPHARM',?,?,true,1,'synthetic-explicit-HQ-assignment',now(),now())""",branch,admin.id,person)}
    private fun count(table:String)=jdbc.queryForObject("SELECT count(*) FROM $table",Long::class.java)!!
    private fun nativeAccess():UUID {
        jdbc.update("UPDATE admin_users SET access_mode='MANAGED' WHERE id=?",admin.id)
        val grants=listOf("view","edit","assign","submit","approve","cancel","financial_data").map {
            mapOf("module" to "merchandising","subsection" to "placements","action" to it,"allPharmacies" to false,
                "allMerchBranches" to true,"allProducers" to true)}
        jdbc.update("UPDATE unified_admin_profiles SET grants_json=? WHERE account_id=?",json.writeValueAsString(grants),admin.id)
        every {clock.instant()} answers {Instant.now()}
        return source("merch_producers",mapOf("name" to "Synthetic confirmed company","active" to true))
    }
    private fun command(path:String,body:Any)=mvc.perform(post(path).header("Authorization","Bearer ${jwt.issueAccessToken(admin)}")
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)))
    private fun nativeRequest(producer:UUID)=mapOf("producerId" to producer,"placementMonth" to LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).withDayOfMonth(1).plusMonths(1),
        "items" to listOf(mapOf("shelfId" to shelf,"portion" to "left")))
    private fun preservedCycle(plan:UUID,status:String="submitted",projected:Boolean=true):UUID=source("merch_task_dispatches",mapOf(
        "branch_id" to branch,"title" to "Preserved original cycle","status" to status,"automation_kind" to if(projected) "weekly_placement_photo_report" else null,
        "media_plan_id" to if(projected) plan else null,"source_media_plan_id" to if(projected) plan else null,"recurring_cycle_number" to if(projected) 1 else null,
        "cycle_starts_at" to if(projected) Timestamp.from(now.minusSeconds(60)) else null,"due_at" to Timestamp.from(Instant.parse("2026-10-31T19:00:00Z")),
        "next_cycle_at" to if(projected) Timestamp.from(Instant.parse("2026-10-31T19:00:00Z")) else null),
        mapOf("media_plan_id" to plan,"automation_kind" to "weekly_placement_photo_report","preserved" to true))

    @Test fun `future approval creates one preparation packet with actual shelf instructions checklist and zero bonus`() {
        val id=plan(count=2);val result=engine.generateForPlan(id)
        assertThat(result.dispatchesCreated).isEqualTo(1);assertThat(result.itemsCreated).isEqualTo(2);assertThat(result.diagnostics).isEmpty()
        val dispatch=jdbc.queryForMap("SELECT * FROM merch_task_dispatches")
        assertThat(dispatch["title"] as String).startsWith("Подготовка размещения")
        assertThat((dispatch["due_at"] as Timestamp).toInstant()).isEqualTo(Instant.parse("2026-10-31T19:00:00Z"))
        assertThat(dispatch["reward_amount_kzt"]).isEqualTo(0L);assertThat(dispatch["native_automation_generated"]).isEqualTo(true)
        assertThat(dispatch["automation_owner_admin_id"]).isEqualTo(admin.id)
        assertThat(count("merch_task_items")).isEqualTo(2);assertThat(count("merch_task_events")).isEqualTo(2)
        assertThat(jdbc.queryForList("SELECT reference_image_ref FROM merch_task_items",String::class.java)).containsOnly("/api/request-item-files/client.png")
        assertThat(jdbc.queryForObject("SELECT bool_and(jsonb_array_length(checklist)=3 AND required_photo_count=1 AND instructions LIKE '%левая половина%') FROM merch_task_items",Boolean::class.java)).isTrue()
        assertThat(count("merch_task_rewards")).isZero();assertThat(count("pharmacist_wallet_entries")).isZero()
        assertThat(engine.generateForPlan(id).dispatchesCreated).isZero();assertThat(count("merch_task_dispatches")).isEqualTo(1)
    }
    @Test fun `calendar cycle progresses without completing or reviewing the previous packet and never rewrites it`() {
        val id=plan();engine.generateForPlan(id)
        val original=jdbc.queryForObject("SELECT row_to_json(d)::text FROM merch_task_dispatches d",String::class.java)
        val first=jdbc.queryForObject("SELECT id FROM merch_task_dispatches",UUID::class.java)!!
        now=Instant.parse("2026-10-31T19:00:00Z")
        assertThat(engine.generateForPlan(id).dispatchesCreated).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT row_to_json(d)::text FROM merch_task_dispatches d WHERE id=?",String::class.java,first)).isEqualTo(original)
        assertThat(jdbc.queryForObject("SELECT due_at FROM merch_task_dispatches WHERE recurring_cycle_number=2",Timestamp::class.java)!!.toInstant()).isEqualTo(Instant.parse("2026-11-07T19:00:00Z"))
        assertThat(engine.generateForPlan(id).dispatchesCreated).isZero()
    }
    @Test fun `thirteen imported open submitted and completed cycle identities and future times survive repeated generation`() {
        val statuses=listOf("ready","sent","in_progress","submitted","revision_required","completed")
        val ids=(0 until 13).map {index->plan().also {preservedCycle(it,statuses[index%statuses.size])}}
        val before=jdbc.queryForList("SELECT row_to_json(d)::text FROM merch_task_dispatches d ORDER BY id",String::class.java)
        ids.forEach {assertThat(engine.generateForPlan(it).dispatchesCreated).isZero()}
        assertThat(engine.tick()).isEmpty()
        assertThat(jdbc.queryForList("SELECT row_to_json(d)::text FROM merch_task_dispatches d ORDER BY id",String::class.java)).isEqualTo(before)
        assertThat(count("merch_task_dispatches")).isEqualTo(13)
    }
    @Test fun `unprojected source cycle is a diagnostic and cannot become a duplicate first cycle`() {
        val id=plan();preservedCycle(id,projected=false)
        val result=engine.generateForPlan(id)
        assertThat(result.dispatchesCreated).isZero();assertThat(result.diagnostics.map {it.reason}).contains("source_cycle_projection_required")
        assertThat(count("merch_task_dispatches")).isEqualTo(1);assertThat(count("merch_task_items")).isZero()
    }
    @Test fun `missing owner remains blocked and a creator field cannot bypass the explicit weekly ownership gate`() {
        jdbc.execute("TRUNCATE merch_automation_branch_owners")
        val id=plan();val result=engine.generateForPlan(id)
        assertThat(result.dispatchesCreated).isEqualTo(1);assertThat(result.diagnostics.map {it.reason}).contains("verified_automation_owner_required")
        jdbc.update("UPDATE merch_task_dispatches SET created_by=?",admin.id)
        assertThat(delivery.activeTask("automation-pharmacy").task).isNull()
        owner();assertThat(delivery.activeTask("automation-pharmacy").task).isNotNull()
        jdbc.update("UPDATE merch_automation_branch_owners SET active=false WHERE branch_id=?",branch)
        assertThat(delivery.activeTask("automation-pharmacy").task).isNull()
    }
    @Test fun `exact active source MAS may remain canonically unresolved without inventing a person or administrator`() {
        jdbc.execute("TRUNCATE merch_automation_branch_owners")
        val raw=UUID.randomUUID()
        jdbc.update("""INSERT INTO unified_external_accounts(id,source_system,source_origin,source_payload,full_name,source_role,active,created_at,updated_at)
            VALUES(?,'MERCH_CRM','https://crm.inkar.kz','{}','Synthetic raw manager','mass_manager',true,now(),now())""",raw)
        jdbc.update("""INSERT INTO merch_automation_branch_owners(branch_id,source_system,source_owner_id,active,revision,verification_reference,created_at,updated_at)
            VALUES(?,'MERCH_CRM',?,true,1,'synthetic-exact-source-member-branch-access',now(),now())""",branch,raw)
        val id=plan();assertThat(engine.generateForPlan(id).dispatchesCreated).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT source_mass_owner_id FROM merch_task_dispatches",UUID::class.java)).isEqualTo(raw)
        assertThat(jdbc.queryForObject("SELECT automation_owner_admin_id IS NULL FROM merch_task_dispatches",Boolean::class.java)).isTrue()
        assertThat(delivery.activeTask("automation-pharmacy").task).isNotNull()
        assertThat(count("unified_persons")).isEqualTo(1);assertThat(count("admin_users")).isEqualTo(1)
        jdbc.update("UPDATE unified_external_accounts SET active=false WHERE id=?",raw)
        assertThat(delivery.activeTask("automation-pharmacy").task).isNull()
    }
    @Test fun `cancelled expired inactive or unmatched branches create no executable work or fake pharmacy binding`() {
        val cancelled=plan("cancelled");assertThat(engine.generateForPlan(cancelled).dispatchesCreated).isZero()
        val id=plan();jdbc.update("UPDATE merch_branches SET epharm_pharmacy_id=NULL,pharmacy_binding_verified=false WHERE id=?",branch)
        assertThat(engine.generateForPlan(id).diagnostics.map {it.reason}).contains("verified_pharmacy_required")
        jdbc.update("UPDATE merch_branches SET active=false WHERE id=?",branch)
        assertThat(engine.generateForPlan(id).diagnostics.map {it.reason}).contains("inactive_branch_or_shelf")
        jdbc.update("UPDATE merch_branches SET active=true,epharm_pharmacy_id='automation-pharmacy',pharmacy_binding_verified=true WHERE id=?",branch)
        now=Instant.parse("2026-11-30T19:00:00Z")
        assertThat(engine.generateForPlan(id).diagnostics.map {it.reason}).contains("placement_period_ended")
        assertThat(count("merch_task_dispatches")).isZero()
    }
    @Test fun `a cancelled last cycle cannot be revived and a 500 item packet is never silently split or truncated at 501`() {
        val cancelled=plan();preservedCycle(cancelled,status="cancelled")
        assertThat(engine.generateForPlan(cancelled).diagnostics.map {it.reason}).contains("last_cycle_cancelled")
        val id=plan(count=500);assertThat(engine.generateForPlan(id).itemsCreated).isEqualTo(500)
        val extra=shelf("extra-501")
        source("merch_media_plan_items",mapOf("media_plan_id" to id,"shelf_id" to extra,"placement_month" to month,"monthly_price" to 15000,"status" to "approved","sale_portion" to "whole"))
        now=Instant.parse("2026-10-31T19:00:00Z")
        val result=engine.generateForPlan(id)
        assertThat(result.dispatchesCreated).isZero();assertThat(result.diagnostics.map {it.reason}).contains("packet_limit_500")
        assertThat(count("merch_task_items")).isEqualTo(500)
    }
    @Test fun `competing workers and an explicitly disabled engine do not produce duplicate business cycles`() {
        val id=plan();ReflectionTestUtils.setField(engine,"enabled",false)
        assertThat(engine.generateForPlan(id).diagnostics.map {it.reason}).contains("disabled");assertThat(count("merch_task_dispatches")).isZero()
        ReflectionTestUtils.setField(engine,"enabled",true)
        val go=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        try {
            val runs=(1..2).map {pool.submit<AutomationRun>{check(go.await(5,TimeUnit.SECONDS));engine.generateForPlan(id)}}
            go.countDown();assertThat(runs.sumOf {it.get(10,TimeUnit.SECONDS).dispatchesCreated}).isEqualTo(1)
            assertThat(count("merch_task_dispatches")).isEqualTo(1);assertThat(count("merch_task_items")).isEqualTo(1)
        } finally {go.countDown();pool.shutdownNow()}
    }
    @Test fun `reference images keep the source priority and never fetch or accept arbitrary hosts traversal or bearer queries`() {
        val id=plan();jdbc.update("UPDATE merch_media_plan_items SET client_photo_ref='https://attacker.invalid/capture' WHERE media_plan_id=?",id)
        engine.generateForPlan(id)
        assertThat(jdbc.queryForObject("SELECT reference_image_ref FROM merch_task_items",String::class.java)).isEqualTo("/api/placement-files/first.png")
        for(ref in listOf("http://crm.inkar.kz/api/placement-files/a.png","https://attacker.invalid/api/placement-files/a.png","//attacker.invalid/a",
            "/api/placement-files/../secret","/api/placement-files/%2e%2e/secret","/api/placement-files/a.png?token=secret")) assertThat(engine.trustedReference(ref)).isFalse()
        assertThat(engine.trustedReference("https://epharm.inkar.kz/api/request-item-files/a.png")).isTrue()
    }
    @Test fun `audit failure rolls back the complete new packet and leaves its approved plan unchanged`() {
        val id=plan();jdbc.execute("ALTER TABLE merch_task_events ADD CONSTRAINT synthetic_automation_failure CHECK(false)")
        try {
            org.junit.jupiter.api.Assertions.assertThrows(Exception::class.java){engine.generateForPlan(id)}
            assertThat(count("merch_task_dispatches")).isZero();assertThat(count("merch_task_items")).isZero()
            assertThat(jdbc.queryForObject("SELECT status FROM merch_media_plans WHERE id=?",String::class.java,id)).isEqualTo("approved")
        } finally {jdbc.execute("ALTER TABLE merch_task_events DROP CONSTRAINT synthetic_automation_failure")}
    }
    @Test fun `admin assignment publishes automation only after its audit and transaction commit`() {
        val producer=nativeAccess()
        jdbc.execute("ALTER TABLE merch_placement_audit ADD CONSTRAINT synthetic_approval_failure CHECK(false)")
        try {
            command("/api/admin/merch/admin-assignments",nativeRequest(producer)).andExpect(status().isConflict)
            assertThat(count("merch_media_plans")).isZero();assertThat(count("merch_task_dispatches")).isZero()
        } finally {jdbc.execute("ALTER TABLE merch_placement_audit DROP CONSTRAINT synthetic_approval_failure")}
        val response=command("/api/admin/merch/admin-assignments",nativeRequest(producer)).andExpect(status().isOk).andReturn().response.contentAsByteArray
        val id=UUID.fromString(json.readTree(response).path("id").asText())
        assertThat(jdbc.queryForObject("SELECT status FROM merch_media_plans WHERE id=?",String::class.java,id)).isEqualTo("approved")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_task_dispatches WHERE media_plan_id=?",Long::class.java,id)).isEqualTo(1)
        assertThat(count("merch_task_items")).isEqualTo(1);assertThat(count("pharmacist_wallet_entries")).isZero()
    }
    @Test fun `ordinary review approval fires once and cancellation cannot recreate or complete its old packet`() {
        val producer=nativeAccess()
        val draft=json.readTree(command("/api/admin/merch/placements",nativeRequest(producer)).andExpect(status().isOk).andReturn().response.contentAsByteArray)
        val id=UUID.fromString(draft.path("id").asText())
        assertThat(count("merch_task_dispatches")).isZero()
        val submitted=json.readTree(command("/api/admin/merch/placements/$id/submit",mapOf("expectedRevision" to draft.path("revision").asLong()))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray)
        assertThat(count("merch_task_dispatches")).isZero()
        val approved=json.readTree(command("/api/admin/merch/placements/$id/approve",mapOf("expectedRevision" to submitted.path("revision").asLong()))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray)
        assertThat(count("merch_task_dispatches")).isEqualTo(1)
        command("/api/admin/merch/placements/$id/cancel",mapOf("expectedRevision" to approved.path("revision").asLong(),"reason" to "Synthetic cancelled placement"))
            .andExpect(status().isOk)
        assertThat(engine.generateForPlan(id).diagnostics.map {it.reason}).contains("plan_not_approved")
        assertThat(engine.tick()).isEmpty();assertThat(count("merch_task_dispatches")).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT status FROM merch_task_dispatches",String::class.java)).isEqualTo("ready")
        assertThat(count("merch_task_rewards")).isZero()
    }
    @Test fun `disabled automation allows the committed assignment without launching any task or money writer`() {
        val producer=nativeAccess();ReflectionTestUtils.setField(engine,"enabled",false)
        command("/api/admin/merch/admin-assignments",nativeRequest(producer)).andExpect(status().isOk)
        assertThat(count("merch_media_plans")).isEqualTo(1);assertThat(count("merch_task_dispatches")).isZero()
        assertThat(count("merch_task_events")).isZero();assertThat(count("pharmacist_wallet_entries")).isZero()
    }
    @Test fun `failed after commit derivation preserves approval rolls back the packet and can be safely retried`() {
        val producer=nativeAccess()
        jdbc.execute("ALTER TABLE merch_task_events ADD CONSTRAINT synthetic_callback_failure CHECK(false)")
        try {
            command("/api/admin/merch/admin-assignments",nativeRequest(producer)).andExpect(status().isOk)
            assertThat(jdbc.queryForObject("SELECT status FROM merch_media_plans",String::class.java)).isEqualTo("approved")
            assertThat(count("merch_task_dispatches")).isZero();assertThat(count("merch_task_items")).isZero()
        } finally {jdbc.execute("ALTER TABLE merch_task_events DROP CONSTRAINT synthetic_callback_failure")}
        assertThat(engine.tick().sumOf {it.dispatchesCreated}).isEqualTo(1)
        assertThat(count("merch_task_dispatches")).isEqualTo(1);assertThat(count("merch_task_items")).isEqualTo(1)
        assertThat(count("pharmacist_wallet_entries")).isZero()
    }
}
