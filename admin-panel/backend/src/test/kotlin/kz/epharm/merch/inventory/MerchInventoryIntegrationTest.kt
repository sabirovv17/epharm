package kz.epharm.merch.inventory

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.merch.inventory.dto.*
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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class MerchInventoryIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("merch_inventory_disposable").withUsername("epharm").withPassword("synthetic-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }; reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
        private val B1 = UUID.fromString("10000000-0000-4000-8000-000000000001")
        private val B2 = UUID.fromString("20000000-0000-4000-8000-000000000002")
        private val MONTH = LocalDate.of(2027, 1, 1)
    }
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var named: NamedParameterJdbcTemplate
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var tx: PlatformTransactionManager
    private lateinit var account: AdminUserEntity
    private lateinit var token: String
    private lateinit var shelf: UUID

    @BeforeEach fun seed() {
        jdbc.execute("""TRUNCATE merch_inventory_audit,merch_media_plan_items,merch_media_plans,merch_monthly_inventory,
            merch_shelves,merch_inventory_containers,merch_branches,merch_organizations,
            unified_admin_access_audit,unified_admin_profiles,unified_persons,admin_users CASCADE""")
        account = users.saveAndFlush(AdminUserEntity(email="inventory@example.test",name="Synthetic inventory operator",
            passwordHash="unused",roleRaw=AdminRole.HQ_HEAD.name))
        token = "Bearer ${jwt.issueAccessToken(account)}"
        jdbc.update("UPDATE admin_users SET access_mode='MANAGED' WHERE id=?", account.id)
        val person = UUID.randomUUID()
        jdbc.update("INSERT INTO unified_persons(id,verification_reference) VALUES(?,?)", person,"synthetic-only")
        jdbc.update("INSERT INTO unified_admin_profiles(account_id,person_id) VALUES(?,?)", account.id,person)
        val org = source("merch_organizations", mapOf("name" to "Synthetic organization", "legal_name" to "Synthetic organization", "active" to true))
        for (branch in listOf(B1,B2)) source("merch_branches", mapOf("id" to branch,"organization_id" to org,
            "code" to branch.toString(),"name" to "Synthetic branch","address" to "Synthetic address","city" to "Test","active" to true))
        shelf = shelf(B1)
        grants("view", "manage_availability", "financial_data")
    }
    private fun grants(vararg actions: String, branches: Set<UUID> = setOf(B1), all: Boolean = false) {
        val grants = actions.map { mapOf("module" to "merchandising","subsection" to "inventory","action" to it,
            "allPharmacies" to false,"pharmacyIds" to emptyList<String>(),"allMerchBranches" to all,
            "merchBranchIds" to if (all) emptyList() else branches.map(UUID::toString)) }
        jdbc.update("UPDATE unified_admin_profiles SET grants_json=?,version=version+1 WHERE account_id=?", mapper.writeValueAsString(grants), account.id)
    }
    /** Fixed test table/field literals; complete synthetic source rows never touch a staging database. */
    private fun source(table: String, fields: Map<String,Any?>): UUID {
        val id = fields["id"] as? UUID ?: UUID.randomUUID()
        val row = linkedMapOf<String,Any?>("id" to id,"source_system" to "MERCH_CRM","source_origin" to "https://crm.inkar.kz",
            "source_id" to id,"source_payload" to "{\"synthetic\":true}","created_at" to java.sql.Timestamp.from(Instant.now()))
        if (table != "merch_media_plan_items") row["updated_at"] = java.sql.Timestamp.from(Instant.now())
        row.putAll(fields)
        val values = row.keys.joinToString(",") { if (it in setOf("source_payload","specifications","metadata")) "CAST(:$it AS jsonb)" else ":$it" }
        named.update("INSERT INTO $table(${row.keys.joinToString(",")}) VALUES($values)", row)
        return id
    }
    private fun shelf(branch: UUID, active: Boolean = true): UUID = source("merch_shelves", mapOf("branch_id" to branch,
        "code" to UUID.randomUUID().toString(),"category" to "AA","width_m" to java.math.BigDecimal("1.00"),
        "base_monthly_price" to 15000,"active" to active,"place_type" to "shelf","position_index" to 0,
        "specifications" to "{}","sale_mode" to "whole_or_halves"))
    private fun cell(shelf: UUID, status: String, price: Int = 12345): UUID = source("merch_monthly_inventory", mapOf(
        "shelf_id" to shelf,"placement_month" to MONTH,"status" to status,"monthly_price" to price))
    private fun placement(shelf: UUID, status: String, portion: String = "whole", origin: String = "request", expired: Boolean = false): UUID {
        val plan = source("merch_media_plans", mapOf("advertiser_name" to "Synthetic manufacturer","placement_month" to MONTH,
            "status" to if (status == "approved") "approved" else "pending", "held_until" to java.sql.Timestamp.from(Instant.now().plusSeconds(if (expired) -60 else 3600)),
            "origin" to origin,"total_amount" to 15000,"submitted_at" to java.sql.Timestamp.from(Instant.now())))
        return source("merch_media_plan_items",mapOf("media_plan_id" to plan,"shelf_id" to shelf,"placement_month" to MONTH,
            "monthly_price" to 15000,"status" to status,"sale_portion" to portion))
    }
    private fun calendar(branch: UUID = B1): MerchAvailabilityCalendar = mapper.readValue(mvc.perform(get("/api/admin/merch/branches/$branch/availability")
        .param("startMonth", MONTH.toString()).param("monthCount","2").header("Authorization",token))
        .andExpect(status().isOk).andReturn().response.contentAsByteArray)
    private fun command(ids: List<UUID> = listOf(shelf), action: String = "block", reason: String? = "repair",
        from: LocalDate = MONTH, to: LocalDate = MONTH.plusMonths(1), note: String? = "Synthetic note") = mvc.perform(
        post("/api/admin/merch/branches/$B1/availability").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("shelfIds" to ids,"fromMonth" to from,"toMonth" to to,"action" to action,"reason" to reason,"note" to note))))

    @Test fun `legacy HQ role without a verified access profile cannot use owned merch routes`() {
        jdbc.update("DELETE FROM unified_admin_profiles WHERE account_id=?",account.id)
        jdbc.update("UPDATE admin_users SET access_mode='LEGACY' WHERE id=?",account.id)
        mvc.perform(get("/api/admin/merch/branches").header("Authorization",token)).andExpect(status().isForbidden)
        command().andExpect(status().isForbidden)
    }

    @Test fun `branch scope filters collection and denies foreign UUID and pharmacy only grants`() {
        mvc.perform(get("/api/admin/merch/branches").header("Authorization",token)).andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(B1.toString()))
        mvc.perform(get("/api/admin/merch/branches/$B2/inventory").header("Authorization",token)).andExpect(status().isForbidden)
        jdbc.update("UPDATE unified_admin_profiles SET grants_json=?,version=version+1 WHERE account_id=?", mapper.writeValueAsString(listOf(
            mapOf("module" to "merchandising","subsection" to "inventory","action" to "view","allPharmacies" to true))),account.id)
        mvc.perform(get("/api/admin/merch/branches").header("Authorization",token)).andExpect(status().isForbidden)
    }

    @Test fun `missing cells are available and GET never expires or writes inventory`() {
        val held = shelf(B1); cell(held,"held"); placement(held,"pending",expired=true)
        val before = jdbc.queryForObject("SELECT count(*) FROM merch_monthly_inventory",Long::class.java)
        val result = calendar()
        assertThat(result.shelves.first { it.id==shelf }.months).allSatisfy { assertThat(it.status).isEqualTo(MerchInventoryStatus.available) }
        assertThat(result.shelves.first { it.id==held }.months.first().status).isEqualTo(MerchInventoryStatus.held)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_monthly_inventory",Long::class.java)).isEqualTo(before)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_media_plan_items WHERE status='pending'",Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_inventory_audit",Long::class.java)).isZero()
    }

    @Test fun `calendar preserves half whole approved and admin assignment occupancy and masks prices`() {
        placement(shelf,"approved","left","admin_assignment"); placement(shelf,"pending","right")
        val whole = shelf(B1); placement(whole,"approved")
        val inactive = shelf(B1, false)
        val calendar = calendar()
        val occupied = calendar.shelves.first { it.id==shelf }.months.first()
        assertThat(occupied.status).isEqualTo(MerchInventoryStatus.booked)
        assertThat(occupied.placement!!.portion).isEqualTo("both")
        assertThat(occupied.placement.hasAdminAssignment).isTrue()
        assertThat(occupied.canEdit).isFalse()
        assertThat(calendar.shelves.first { it.id==whole }.months.first().placement!!.portion).isEqualTo("whole")
        assertThat(calendar.shelves.first { it.id==inactive }.months).allSatisfy { assertThat(it.status).isEqualTo(MerchInventoryStatus.blocked); assertThat(it.canEdit).isFalse() }
        command(to=MONTH).andExpect(status().isOk).andExpect(jsonPath("$.protectedCount").value(1)).andExpect(jsonPath("$.changedCount").value(0))
        grants("view")
        val hidden = calendar()
        assertThat(hidden.canViewPrices).isFalse(); assertThat(hidden.canManage).isFalse()
        assertThat(hidden.shelves).allSatisfy { assertThat(it.price).isNull() }
        mvc.perform(get("/api/admin/merch/branches/$B1/inventory").header("Authorization",token)).andExpect(status().isOk)
            .andExpect(jsonPath("$.shelves[0].baseMonthlyPrice").doesNotExist())
        command().andExpect(status().isForbidden)
    }

    @Test fun `block and unblock preserve imported ID price payload and audit trusted actor`() {
        val oldId = cell(shelf,"available")
        jdbc.update("UPDATE merch_branches SET price_adjustment_percent=10 WHERE id=?",B1)
        assertThat(calendar().shelves.single().price).isEqualTo(17000)
        command().andExpect(status().isOk).andExpect(jsonPath("$.targetCount").value(2)).andExpect(jsonPath("$.changedCount").value(2))
        assertThat(jdbc.queryForObject("SELECT id FROM merch_monthly_inventory WHERE shelf_id=? AND placement_month=?",UUID::class.java,shelf,MONTH)).isEqualTo(oldId)
        assertThat(jdbc.queryForObject("SELECT monthly_price FROM merch_monthly_inventory WHERE id=?",Int::class.java,oldId)).isEqualTo(12345)
        assertThat(jdbc.queryForObject("SELECT source_payload->>'synthetic' FROM merch_monthly_inventory WHERE id=?",String::class.java,oldId)).isEqualTo("true")
        assertThat(jdbc.queryForObject("SELECT blocked_by_admin FROM merch_monthly_inventory WHERE id=?",UUID::class.java,oldId)).isEqualTo(account.id)
        assertThat(jdbc.queryForObject("SELECT source_system FROM merch_monthly_inventory WHERE placement_month=?",String::class.java,MONTH.plusMonths(1))).isEqualTo("EPHARM")
        command(action="unblock",reason=null).andExpect(status().isOk).andExpect(jsonPath("$.changedCount").value(2))
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_inventory_audit WHERE actor_account_id=?",Long::class.java,account.id)).isEqualTo(2)
        assertThatThrownBy { jdbc.update("DELETE FROM merch_inventory_audit") }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { jdbc.update("UPDATE merch_inventory_audit SET action='block'") }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test fun `invalid or foreign selection is atomic and raw booked inventory stays protected`() {
        val foreign=shelf(B2); val inactive=shelf(B1,false)
        command(ids=listOf(shelf,foreign)).andExpect(status().isBadRequest)
        command(ids=listOf(shelf,shelf)).andExpect(status().isBadRequest)
        command(ids=listOf(shelf,inactive)).andExpect(status().isBadRequest)
        command(reason=null).andExpect(status().isBadRequest)
        command(note="x".repeat(301)).andExpect(status().isBadRequest)
        command(from=MONTH.plusDays(1)).andExpect(status().isBadRequest)
        command(to=MONTH.plusMonths(12)).andExpect(status().isBadRequest)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_monthly_inventory",Long::class.java)).isZero()
        cell(shelf,"booked")
        command(to=MONTH,action="unblock",reason=null).andExpect(status().isOk).andExpect(jsonPath("$.protectedCount").value(1))
        assertThat(jdbc.queryForObject("SELECT status FROM merch_monthly_inventory",String::class.java)).isEqualTo("booked")
    }

    @Test fun `audit failure rolls back every month in the availability command`() {
        jdbc.execute("ALTER TABLE merch_inventory_audit ADD CONSTRAINT synthetic_audit_failure CHECK(false)")
        try {
            command().andExpect(status().isConflict)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_monthly_inventory",Long::class.java)).isZero()
        } finally { jdbc.execute("ALTER TABLE merch_inventory_audit DROP CONSTRAINT synthetic_audit_failure") }
    }

    @Test fun `manual availability observes a committed placement under the shared branch then shelf lock order`() {
        val ready=CountDownLatch(1); val release=CountDownLatch(1); val pool=Executors.newFixedThreadPool(2)
        try {
            val booking=pool.submit { TransactionTemplate(tx).executeWithoutResult {
                jdbc.queryForList("SELECT id FROM merch_branches WHERE id=? FOR UPDATE",B1)
                jdbc.queryForList("SELECT id FROM merch_shelves WHERE id=? FOR UPDATE",shelf)
                placement(shelf,"approved"); ready.countDown(); check(release.await(10,TimeUnit.SECONDS))
            } }
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue()
            val manual=pool.submit { command(to=MONTH).andExpect(status().isOk)
                .andExpect(jsonPath("$.protectedCount").value(1)).andExpect(jsonPath("$.changedCount").value(0)) }
            release.countDown(); booking.get(10,TimeUnit.SECONDS); manual.get(10,TimeUnit.SECONDS)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM merch_monthly_inventory",Long::class.java)).isZero()
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
