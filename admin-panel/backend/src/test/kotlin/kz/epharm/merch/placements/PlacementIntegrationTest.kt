package kz.epharm.merch.placements

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.merch.placements.dto.*
import kz.epharm.merch.placements.service.PlacementService
import kz.epharm.merch.producer.auth.ProducerPrincipal
import kz.epharm.merch.producer.auth.ProducerTokenCodec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(properties=["app.merch.producer-auth-enabled=true"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class PlacementIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine").withDatabaseName("placements_disposable")
            .withUsername("epharm").withPassword("synthetic-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url"){postgres.jdbcUrl};reg.add("spring.datasource.username"){postgres.username};reg.add("spring.datasource.password"){postgres.password}
        }
    }
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var named: NamedParameterJdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var service: PlacementService
    @Autowired lateinit var transactions:PlatformTransactionManager
    @Autowired lateinit var passwords:PasswordEncoder
    @Autowired lateinit var producerTokens:ProducerTokenCodec
    private lateinit var admin: AdminUserEntity
    private lateinit var bearer: String
    private lateinit var branch: UUID
    private lateinit var foreign: UUID
    private lateinit var shelf: UUID
    private lateinit var foreignShelf: UUID
    private lateinit var producer: UUID
    private lateinit var otherProducer: UUID
    private val month get()=LocalDate.now(ZoneId.of("Asia/Almaty")).withDayOfMonth(1).plusMonths(1)
    private val actions=listOf("view","edit","submit","approve","reject","cancel","assign","financial_data")
    private val bindingPassword="Synthetic-only-482!"

    @BeforeEach fun seed() {
        jdbc.execute("""TRUNCATE merch_placement_audit,merch_inventory_audit,merch_media_plan_items,merch_media_plans,merch_monthly_inventory,
            merch_shelves,merch_inventory_containers,merch_branches,merch_organizations,merch_producers,
            unified_admin_access_audit,unified_admin_profiles,unified_persons,admin_users CASCADE""")
        admin=users.saveAndFlush(AdminUserEntity(email="placement@example.test",name="Synthetic operator",passwordHash="unused",roleRaw=AdminRole.HQ_HEAD.name))
        bearer="Bearer ${jwt.issueAccessToken(admin)}"
        jdbc.update("UPDATE admin_users SET access_mode='MANAGED' WHERE id=?",admin.id)
        val person=UUID.randomUUID();jdbc.update("INSERT INTO unified_persons VALUES(?,?)",person,"synthetic-only")
        jdbc.update("INSERT INTO unified_admin_profiles(account_id,person_id) VALUES(?,?)",admin.id,person)
        val org=source("merch_organizations",mapOf("name" to "Synthetic","legal_name" to "Synthetic","active" to true))
        fun branch(code:String)=source("merch_branches",mapOf("organization_id" to org,"code" to code,"name" to code,"address" to "Synthetic","city" to "Test","active" to true))
        branch=branch("A");foreign=branch("B")
        fun shelf(branch:UUID)=source("merch_shelves",mapOf("branch_id" to branch,"code" to UUID.randomUUID().toString(),"category" to "AA","width_m" to java.math.BigDecimal.ONE,
            "base_monthly_price" to 15000,"half_monthly_price" to 8000,"active" to true,"place_type" to "shelf","position_index" to 0,"specifications" to "{}","sale_mode" to "whole_or_halves"))
        shelf=shelf(branch);foreignShelf=shelf(foreign)
        producer=source("merch_producers",mapOf("name" to "Synthetic producer","source_account_role" to "advertiser","active" to true))
        otherProducer=source("merch_producers",mapOf("name" to "Other producer","source_account_role" to "advertiser","active" to true))
        grants()
    }
    private fun grants(allowed:List<String> = actions,all:Boolean=false) {
        fun grant(section:String,action:String,customers:Boolean)=mapOf("module" to "merchandising","subsection" to section,"action" to action,
            "allPharmacies" to false,"pharmacyIds" to emptyList<String>(),"allMerchBranches" to all,"merchBranchIds" to if(all) emptyList() else listOf(branch.toString()),
            "allProducers" to (all && customers),"producerIds" to if(customers && !all) listOf(producer.toString()) else emptyList<String>())
        val list=allowed.map {grant("placements",it,true)}+listOf(grant("producers","view",true),grant("inventory","view",false),grant("inventory","manage_availability",false),grant("inventory","financial_data",false))
        jdbc.update("UPDATE unified_admin_profiles SET grants_json=?,version=version+1 WHERE account_id=?",json.writeValueAsString(list),admin.id)
    }
    private fun source(table:String,fields:Map<String,Any?>):UUID {
        val id=fields["id"] as? UUID?:UUID.randomUUID()
        val row=linkedMapOf<String,Any?>("id" to id,"source_system" to "MERCH_CRM","source_origin" to "https://crm.inkar.kz","source_id" to id,
            "source_payload" to "{\"synthetic\":true}","created_at" to Timestamp.from(Instant.now()))
        if(table!="merch_media_plan_items") row["updated_at"]=Timestamp.from(Instant.now())
        row.putAll(fields)
        named.update("INSERT INTO $table(${row.keys.joinToString(",")}) VALUES(${row.keys.joinToString(","){if(it in setOf("source_payload","specifications")) "CAST(:$it AS jsonb)" else ":$it"}})",row)
        return id
    }
    private fun request(path:String,body:Any,put:Boolean=false)=mvc.perform((if(put) put(path) else post(path)).header("Authorization",bearer)
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
    private fun create(portion:PlacementPortion=PlacementPortion.whole,owner:UUID=producer):PlacementDto=json.readValue(request("/api/admin/merch/placements",
        CreatePlacementDraft(owner,month,listOf(PlacementSelection(shelf,portion)))).andExpect(status().isOk).andReturn().response.contentAsByteArray)
    private fun action(plan:PlacementDto,action:String,reason:String?=null):PlacementDto=json.readValue(request("/api/admin/merch/placements/${plan.id}/$action",
        PlacementTransition(plan.revision,reason)).andExpect(status().isOk).andReturn().response.contentAsByteArray)
    private fun bindingAccess(all:Boolean=true,allowed:List<String> = actions+"bind_producer") {
        grants(allowed,all)
        jdbc.update("UPDATE admin_users SET password_hash=? WHERE id=?",passwords.encode(bindingPassword),admin.id)
    }
    private fun unownedPlan():UUID {
        val id=source("merch_media_plans",mapOf("advertiser_name" to "Preserved original company label","placement_month" to month,
            "status" to "approved","origin" to "admin_assignment","total_amount" to 15000,"submitted_at" to Timestamp.from(Instant.now()),
            "review_note" to "Original public review note","assignment_note" to "Original public note"))
        source("merch_media_plan_items",mapOf("media_plan_id" to id,"shelf_id" to shelf,"placement_month" to month,"monthly_price" to 15000,"status" to "approved","sale_portion" to "whole"))
        return id
    }
    private fun bind(id:UUID,target:UUID=producer,revision:Long=0,password:String=bindingPassword,reason:String="Private verified customer proof")=
        request("/api/admin/merch/placements/$id/producer-binding",BindPlacementProducer(revision,target,reason,password))
    private fun producerBearer(id:UUID,phone:String):String {
        // Synthetic session fixture for the real dedicated producer filter; no SMS or production identity is used.
        jdbc.update("UPDATE merch_producers SET phone=?,phone_normalized=?,phone_verified_at=now() WHERE id=?",phone,phone,id)
        val now=Instant.now();val expiry=now.plusSeconds(86400);val principal=ProducerPrincipal(id,UUID.randomUUID())
        val token=producerTokens.issue(principal,now,expiry)
        jdbc.update("""INSERT INTO merch_producer_sessions(id,customer_id,access_token_hash,phone_fingerprint,created_at,expires_at)
            VALUES(?,?,?,?,?,?)""",principal.sessionId,id,ProducerTokenCodec.hash(token),producerTokens.fingerprint(id,phone),Timestamp.from(now),Timestamp.from(expiry))
        return "Bearer $token"
    }

    @Test fun `HQ binding changes only the confirmed owner and records private proof without exposing it to the producer`() {
        bindingAccess();val id=unownedPlan()
        val columns="source_id,source_payload::text,advertiser_name,status,origin,total_amount,held_until,submitted_at,review_note,assignment_note"
        val original=jdbc.queryForMap("SELECT $columns FROM merch_media_plans WHERE id=?",id)
        val originalItems=jdbc.queryForList("SELECT row_to_json(i)::text FROM merch_media_plan_items i WHERE media_plan_id=? ORDER BY id",String::class.java,id)
        mvc.perform(get("/api/admin/merch/placements/$id").header("Authorization",bearer)).andExpect(status().isOk).andExpect(jsonPath("$.canBindProducer").value(true))
        val bound:PlacementDto=json.readValue(bind(id).andExpect(status().isOk).andExpect(jsonPath("$.canBindProducer").value(false)).andReturn().response.contentAsByteArray)
        assertThat(bound.producerId).isEqualTo(producer);assertThat(bound.revision).isEqualTo(1)
        assertThat(jdbc.queryForMap("SELECT $columns FROM merch_media_plans WHERE id=?",id)).isEqualTo(original)
        assertThat(jdbc.queryForList("SELECT row_to_json(i)::text FROM merch_media_plan_items i WHERE media_plan_id=? ORDER BY id",String::class.java,id)).isEqualTo(originalItems)
        val audit=jdbc.queryForMap("SELECT action,details::text FROM merch_placement_audit WHERE plan_id=?",id)
        assertThat(audit["action"]).isEqualTo("producer_bound");assertThat(audit["details"] as String).contains("Private verified customer proof").doesNotContain(bindingPassword)
        val own=mvc.perform(get("/api/producer/placements/$id").header("Authorization",producerBearer(producer,"+77000000001")))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(own).doesNotContain("Private verified customer proof",bindingPassword,"verificationReason","verifiedPersonId")
        mvc.perform(get("/api/producer/placements/$id").header("Authorization",producerBearer(otherProducer,"+77000000002"))).andExpect(status().isForbidden)
        assertThat(BindPlacementProducer(0,producer,"Private verified customer proof",bindingPassword).toString()).doesNotContain(bindingPassword,"Private verified customer proof")
    }
    @Test fun `binding denies scoped HQ missing explicit grant and non HQ roles even with a grant`() {
        val id=unownedPlan();bindingAccess(all=false)
        bind(id).andExpect(status().isForbidden)
        bindingAccess(allowed=actions);bind(id).andExpect(status().isForbidden)
        bindingAccess()
        for(role in listOf(AdminRole.REGIONAL_MANAGER,AdminRole.CATEGORY_LEAD)) {
            jdbc.update("UPDATE admin_users SET role=? WHERE id=?",role.name,admin.id)
            bind(id).andExpect(status().isForbidden)
        }
        assertThat(jdbc.queryForObject("SELECT producer_id IS NULL FROM merch_media_plans WHERE id=?",Boolean::class.java,id)).isTrue()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_placement_audit",Long::class.java)).isZero()
    }
    @Test fun `binding verifies the current password and verified person and refuses incomplete or inactive producers`() {
        bindingAccess();val id=unownedPlan()
        bind(id,password="wrong-current-password").andExpect(status().isUnauthorized)
        jdbc.update("UPDATE merch_producers SET name=NULL WHERE id=?",producer)
        bind(id).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COMPLETE_PROFILE"))
        jdbc.update("UPDATE merch_producers SET name='Synthetic',active=false WHERE id=?",producer)
        bind(id).andExpect(status().isForbidden)
        jdbc.update("UPDATE merch_producers SET active=true WHERE id=?",producer)
        jdbc.update("UPDATE unified_persons SET verification_reference='   ' WHERE id=(SELECT person_id FROM unified_admin_profiles WHERE account_id=?)",admin.id)
        bind(id).andExpect(status().isForbidden)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_placement_audit",Long::class.java)).isZero()
    }
    @Test fun `stale binding and reassignment cannot overwrite the original confirmed customer`() {
        bindingAccess();val id=unownedPlan()
        bind(id,revision=1).andExpect(status().isConflict)
        bind(id).andExpect(status().isOk)
        bind(id,target=otherProducer,revision=1).andExpect(status().isConflict)
        assertThat(jdbc.queryForObject("SELECT producer_id FROM merch_media_plans WHERE id=?",UUID::class.java,id)).isEqualTo(producer)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_placement_audit",Long::class.java)).isEqualTo(1)
    }
    @Test fun `parallel HQ binding of one source plan accepts exactly one owner and one append only proof`() {
        bindingAccess();val id=unownedPlan();val go=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        try {
            val results=listOf(producer,otherProducer).map {target->pool.submit<Int>{check(go.await(5,TimeUnit.SECONDS));bind(id,target).andReturn().response.status}}
            go.countDown();assertThat(results.map {it.get(10,TimeUnit.SECONDS)}).containsExactlyInAnyOrder(200,409)
            assertThat(jdbc.queryForObject("SELECT revision FROM merch_media_plans WHERE id=?",Long::class.java,id)).isEqualTo(1)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_placement_audit",Long::class.java)).isEqualTo(1)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries",Long::class.java)).isZero()
        } finally {go.countDown();pool.shutdownNow()}
    }
    @Test fun `binding audit failure rolls back owner and revision without deleting preserved records`() {
        bindingAccess();val id=unownedPlan()
        jdbc.execute("ALTER TABLE merch_placement_audit ADD CONSTRAINT synthetic_binding_failure CHECK(false)")
        try {
            bind(id).andExpect(status().isConflict)
            assertThat(jdbc.queryForObject("SELECT producer_id IS NULL AND revision=0 FROM merch_media_plans WHERE id=?",Boolean::class.java,id)).isTrue()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_media_plan_items WHERE media_plan_id=?",Long::class.java,id)).isEqualTo(1)
        } finally {jdbc.execute("ALTER TABLE merch_placement_audit DROP CONSTRAINT synthetic_binding_failure")}
    }

    @Test fun `draft submit approve cancel uses server prices stable IDs and a real protected calendar`() {
        val draft=create();assertThat(draft.totalAmount).isEqualTo(15000);assertThat(draft.status).isEqualTo(PlacementStatus.draft)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_media_plan_items WHERE status='approved'",Long::class.java)).isZero()
        val pending=action(draft,"submit")
        assertThat(pending.status).isEqualTo(PlacementStatus.pending);assertThat(pending.heldUntil).isNotNull()
        val item=pending.items.single().id
        request("/api/admin/merch/branches/$branch/availability",MerchBlockRequest(shelf,month)).andExpect(status().isOk)
            .andExpect(jsonPath("$.protectedCount").value(1)).andExpect(jsonPath("$.changedCount").value(0))
        val approved=action(pending,"approve")
        assertThat(approved.id).isEqualTo(draft.id);assertThat(approved.items.single().id).isEqualTo(item)
        assertThat(approved.status).isEqualTo(PlacementStatus.approved);assertThat(approved.heldUntil).isEqualTo(pending.heldUntil)
        val cancelled=action(approved,"cancel","Administrative correction")
        assertThat(cancelled.status).isEqualTo(PlacementStatus.cancelled)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_media_plans",Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_placement_audit",Long::class.java)).isEqualTo(4)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries",Long::class.java)).isZero()
        assertThatThrownBy {jdbc.update("DELETE FROM merch_placement_audit")}.isInstanceOf(DataIntegrityViolationException::class.java)
    }
    private data class MerchBlockRequest(val id:UUID,val month:LocalDate) {
        val shelfIds=listOf(id);val fromMonth=month;val toMonth=month;val action="block";val reason="repair"
    }
    @Test fun `opposite halves coexist and whole or matching half cannot overlap`() {
        val left=action(create(PlacementPortion.left),"submit")
        val right=action(create(PlacementPortion.right),"submit")
        assertThat(left.totalAmount).isEqualTo(8000);assertThat(right.totalAmount).isEqualTo(8000)
        val whole=create()
        request("/api/admin/merch/placements/${whole.id}/submit",PlacementTransition(whole.revision)).andExpect(status().isConflict)
        val same=create(PlacementPortion.left)
        request("/api/admin/merch/placements/${same.id}/submit",PlacementTransition(same.revision)).andExpect(status().isConflict)
        mvc.perform(get("/api/admin/merch/branches/$branch/availability").param("startMonth",month.toString()).param("monthCount","1").header("Authorization",bearer))
            .andExpect(status().isOk).andExpect(jsonPath("$.shelves[0].months[0].placement.portion").value("both"))
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_monthly_inventory WHERE status IN ('held','booked')",Long::class.java)).isZero()
    }
    @Test fun `price drift requires renewed draft review and cannot be overridden by client amounts`() {
        val draft=create()
        jdbc.update("UPDATE merch_shelves SET base_monthly_price=20000 WHERE id=?",shelf)
        request("/api/admin/merch/placements/${draft.id}/submit",PlacementTransition(draft.revision)).andExpect(status().isConflict)
        val saved:PlacementDto=json.readValue(request("/api/admin/merch/placements/${draft.id}/draft",mapOf("expectedRevision" to draft.revision,
            "items" to listOf(mapOf("shelfId" to shelf,"portion" to "whole","monthlyPrice" to 1)),"totalAmount" to 1),true)
            .andExpect(status().isOk).andReturn().response.contentAsByteArray)
        assertThat(saved.totalAmount).isEqualTo(20000)
        assertThat(action(saved,"submit").totalAmount).isEqualTo(20000)
        request("/api/admin/merch/placements/${draft.id}/draft",SavePlacementDraft(draft.revision,listOf(PlacementSelection(shelf))),true)
            .andExpect(status().isConflict)
    }
    @Test fun `branch customer grants are independent and draft edit never grants publication`() {
        request("/api/admin/merch/placements",CreatePlacementDraft(otherProducer,month,listOf(PlacementSelection(shelf)))).andExpect(status().isForbidden)
        request("/api/admin/merch/placements",CreatePlacementDraft(producer,month,listOf(PlacementSelection(foreignShelf)))).andExpect(status().isForbidden)
        val draft=create();grants(listOf("view","edit"))
        request("/api/admin/merch/placements/${draft.id}/submit",PlacementTransition(draft.revision)).andExpect(status().isForbidden)
        mvc.perform(get("/api/admin/merch/placements/${draft.id}").header("Authorization",bearer)).andExpect(status().isOk)
            .andExpect(jsonPath("$.totalAmount").doesNotExist()).andExpect(jsonPath("$.canSubmit").value(false))
        jdbc.update("DELETE FROM unified_admin_profiles WHERE account_id=?",admin.id)
        jdbc.update("UPDATE admin_users SET access_mode='LEGACY' WHERE id=?",admin.id)
        mvc.perform(get("/api/admin/merch/placements").header("Authorization",bearer)).andExpect(status().isForbidden)
    }
    @Test fun `placement picker works without inventory permission and masks prices by the exact customer branch pair`() {
        grants(listOf("view","edit"))
        val current: List<Map<String,Any?>> = json.readValue(jdbc.queryForObject("SELECT grants_json FROM unified_admin_profiles WHERE account_id=?",String::class.java,admin.id)!!)
        jdbc.update("UPDATE unified_admin_profiles SET grants_json=?,version=version+1 WHERE account_id=?",json.writeValueAsString(current.filter {it["subsection"]=="placements"}),admin.id)
        mvc.perform(get("/api/admin/merch/placements/producers").header("Authorization",bearer)).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(producer.toString()))
        mvc.perform(get("/api/admin/merch/placements/catalog").param("producerId",producer.toString()).param("month",month.toString()).header("Authorization",bearer))
            .andExpect(status().isOk).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.branches[0].id").value(branch.toString()))
            .andExpect(jsonPath("$.branches[0].items[0].whole.price").doesNotExist()).andExpect(jsonPath("$.canCreate").value(true))
            .andExpect(jsonPath("$.canAssign").value(false))
        mvc.perform(get("/api/admin/merch/placements/catalog").param("producerId",otherProducer.toString()).param("month",month.toString()).header("Authorization",bearer))
            .andExpect(status().isForbidden)
    }
    @Test fun `nullable legacy company stays truthful and cannot publish or assign before completing profile`() {
        jdbc.update("UPDATE merch_producers SET name=NULL,source_payload='{\"company_name\":null}' WHERE id=?",producer)
        val draft=create()
        assertThat(draft.producerName).isNull();assertThat(draft.advertiserName).isNull();assertThat(draft.canEdit).isTrue();assertThat(draft.canSubmit).isFalse()
        request("/api/admin/merch/placements/${draft.id}/submit",PlacementTransition(draft.revision))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COMPLETE_PROFILE"))
        request("/api/admin/merch/admin-assignments",CreatePlacementDraft(producer,month,listOf(PlacementSelection(shelf))))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("COMPLETE_PROFILE"))
        mvc.perform(get("/api/admin/merch/placements/catalog").param("producerId",producer.toString()).param("month",month.toString()).header("Authorization",bearer))
            .andExpect(status().isOk).andExpect(jsonPath("$.canCreate").value(true)).andExpect(jsonPath("$.canAssign").value(false))
        assertThat(jdbc.queryForObject("SELECT source_payload->>'company_name' FROM merch_producers WHERE id=?",String::class.java,producer)).isNull()
    }
    @Test fun `reject needs reason and expired holds transition explicitly without losing deadlines or item IDs`() {
        val held=action(create(),"submit")
        request("/api/admin/merch/placements/${held.id}/reject",PlacementTransition(held.revision)).andExpect(status().isBadRequest)
        val rejected=action(held,"reject","Not approved")
        assertThat(rejected.status).isEqualTo(PlacementStatus.rejected)
        val next=action(create(),"submit")
        val originalDeadline=next.heldUntil!!
        assertThat(service.expireDue(originalDeadline.plusSeconds(1))).isEqualTo(1)
        val expired:PlacementDto=json.readValue(mvc.perform(get("/api/admin/merch/placements/${next.id}").header("Authorization",bearer))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray)
        assertThat(expired.status).isEqualTo(PlacementStatus.cancelled);assertThat(expired.heldUntil).isEqualTo(originalDeadline)
        assertThat(expired.items.single().id).isEqualTo(next.items.single().id)
        assertThat(service.expireDue(originalDeadline.plusSeconds(2))).isZero()
        assertThat(jdbc.queryForObject("SELECT actor_type FROM merch_placement_audit WHERE action='expired'",String::class.java)).isEqualTo("SYSTEM")
    }
    @Test fun `admin assignment books immediately and a different customer cannot see its history`() {
        val assigned:PlacementDto=json.readValue(request("/api/admin/merch/admin-assignments",CreatePlacementDraft(producer,month,listOf(PlacementSelection(shelf))))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray)
        assertThat(assigned.origin).isEqualTo("admin_assignment");assertThat(assigned.status).isEqualTo(PlacementStatus.approved)
        assertThatThrownBy {service.view(assigned.id,PlacementActor.Producer(otherProducer,UUID.randomUUID()))}
            .isInstanceOfSatisfying(kz.epharm.shared.error.AppException::class.java){assertThat(it.code).isEqualTo(kz.epharm.shared.error.ErrorCode.FORBIDDEN)}
    }
    @Test fun `concurrent submit of the same whole shelf yields exactly one hold`() {
        val a=create();val b=create();val go=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        try {
            val results=listOf(a,b).map {plan->pool.submit<Int>{check(go.await(10,TimeUnit.SECONDS));request("/api/admin/merch/placements/${plan.id}/submit",PlacementTransition(plan.revision)).andReturn().response.status}}
            go.countDown();assertThat(results.map {it.get(10,TimeUnit.SECONDS)}).containsExactlyInAnyOrder(200,409)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_media_plans WHERE status='pending'",Long::class.java)).isEqualTo(1)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_media_plan_items WHERE status='pending'",Long::class.java)).isEqualTo(1)
        } finally {go.countDown();pool.shutdownNow()}
    }
    @Test fun `audit failure rolls back plan item and all state changes`() {
        jdbc.execute("ALTER TABLE merch_placement_audit ADD CONSTRAINT synthetic_failure CHECK(false)")
        try {
            request("/api/admin/merch/placements",CreatePlacementDraft(producer,month,listOf(PlacementSelection(shelf)))).andExpect(status().isConflict)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_media_plans",Long::class.java)).isZero()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_media_plan_items",Long::class.java)).isZero()
        } finally {jdbc.execute("ALTER TABLE merch_placement_audit DROP CONSTRAINT synthetic_failure")}
    }
    @Test fun `revoked admin financial permission is rechecked under account lock before approval`() {
        val pending=action(create(),"submit");val ready=CountDownLatch(1);val release=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        try {
            val holder=pool.submit {TransactionTemplate(transactions).executeWithoutResult {
                jdbc.queryForList("SELECT id FROM admin_users WHERE id=? FOR UPDATE",admin.id)
                grants(listOf("view","edit"));ready.countDown();check(release.await(10,TimeUnit.SECONDS))
            }}
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue()
            val approval=pool.submit<Int> {request("/api/admin/merch/placements/${pending.id}/approve",PlacementTransition(pending.revision)).andReturn().response.status}
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);var waiting=false
            while(System.nanoTime()<until && !waiting) {
                waiting=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%admin_users%')",Boolean::class.java)!!
                if(!waiting) Thread.sleep(10)
            }
            assertThat(waiting).isTrue();release.countDown();holder.get(10,TimeUnit.SECONDS)
            assertThat(approval.get(10,TimeUnit.SECONDS)).isEqualTo(403)
            assertThat(jdbc.queryForObject("SELECT status FROM merch_media_plans WHERE id=?",String::class.java,pending.id)).isEqualTo("pending")
            assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_placement_audit",Long::class.java)).isEqualTo(2)
        } finally {release.countDown();pool.shutdownNow()}
    }
}
