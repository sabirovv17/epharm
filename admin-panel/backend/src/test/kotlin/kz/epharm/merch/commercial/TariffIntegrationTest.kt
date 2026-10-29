package kz.epharm.merch.commercial

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
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
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class TariffIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine").withDatabaseName("tariffs_disposable")
            .withUsername("epharm").withPassword("synthetic-only").apply {start()}
        @JvmStatic @DynamicPropertySource fun props(reg:DynamicPropertyRegistry) {
            reg.add("spring.datasource.url"){postgres.jdbcUrl};reg.add("spring.datasource.username"){postgres.username};reg.add("spring.datasource.password"){postgres.password}
        }
    }
    @Autowired lateinit var mvc:MockMvc
    @Autowired lateinit var jdbc:JdbcTemplate
    @Autowired lateinit var named:NamedParameterJdbcTemplate
    @Autowired lateinit var json:ObjectMapper
    @Autowired lateinit var users:AdminUserRepository
    @Autowired lateinit var jwt:JwtService
    private lateinit var actor:AdminUserEntity
    private lateinit var token:String
    private val initial=Instant.parse("2026-10-01T00:00:00.123456Z")
    private val base=TariffValues("Synthetic source tariff","1.000","3000","10000","20000","1000",true)
    private val url="/api/admin/merch/tariffs"

    @BeforeEach fun seed() {
        jdbc.execute("""TRUNCATE merch_tariff_audit,merch_commercial_audit,merch_supplier_budget_plan_items,merch_supplier_budget_plans,
            merch_supplier_settings,merch_supplier_pricing_tiers,merch_placement_campaigns,merch_placement_audit,
            merch_placement_item_product_details,merch_media_plan_items,merch_media_plans,merch_monthly_inventory,
            merch_shelves,merch_inventory_containers,merch_branches,merch_organizations,merch_producers,
            unified_admin_profiles,unified_persons,admin_users CASCADE""")
        actor=users.saveAndFlush(AdminUserEntity(email="tariff@example.test",name="Synthetic HQ",passwordHash="not-used",roleRaw=AdminRole.HQ_HEAD.name))
        token="Bearer ${jwt.issueAccessToken(actor)}"
        jdbc.update("UPDATE admin_users SET access_mode='MANAGED' WHERE id=?",actor.id)
        val person=UUID.randomUUID();jdbc.update("INSERT INTO unified_persons(id,verification_reference) VALUES(?,'synthetic-person-proof')",person)
        jdbc.update("INSERT INTO unified_admin_profiles(account_id,person_id) VALUES(?,?)",actor.id,person)
        grants()
        jdbc.update("""INSERT INTO merch_supplier_pricing_tiers(category,label,price_multiplier,facing_month_price,promotion_month_price,
            training_event_price,motivation_person_price,active,updated_at,source_payload) VALUES('standard',?,1.000,3000,10000,20000,1000,true,?,'{"retained":"original-source"}')""",
            base.label,Timestamp.from(initial))
    }
    private fun grants(actions:List<String> = listOf("view","edit","financial_data","submit"),allBranches:Boolean=true,allProducers:Boolean=true) {
        val records=actions.map {mapOf("module" to "merchandising","subsection" to "placements","action" to it,"allPharmacies" to false,
            "allMerchBranches" to allBranches,"merchBranchIds" to if(allBranches) emptyList() else listOf("00000000-0000-0000-0000-000000000101"),
            "allProducers" to allProducers,"producerIds" to if(allProducers) emptyList() else listOf("00000000-0000-0000-0000-000000000102"))}
        jdbc.update("UPDATE unified_admin_profiles SET grants_json=?,version=version+1 WHERE account_id=?",json.writeValueAsString(records),actor.id)
    }
    private fun save(v:TariffValues=base.copy(facingMonthPrice="4000"),expected:Instant=initial)=mvc.perform(put("$url/standard")
        .header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(SaveTariff(expected,v))))
    private fun index():JsonNode=json.readTree(mvc.perform(get(url).header("Authorization",token)).andExpect(status().isOk)
        .andExpect(header().string("Cache-Control","no-store")).andReturn().response.contentAsByteArray)
    private fun capability()=json.readTree(mvc.perform(get("/api/admin/access/me").header("Authorization",token)).andExpect(status().isOk)
        .andReturn().response.contentAsByteArray)["canViewMerchTariffs"].asBoolean()
    private fun row()=jdbc.queryForMap("SELECT * FROM merch_supplier_pricing_tiers WHERE category='standard'")
    private fun count()=jdbc.queryForObject("SELECT count(*) FROM merch_tariff_audit",Long::class.java)!!

    @Test fun `HQ changes exact source tariffs with immutable before after audit and no source payload rewrite`() {
        assertThat(capability()).isTrue();assertThat(index()["canEdit"].asBoolean()).isTrue()
        val before=row()
        val values=base.copy(label="Reviewed tariff",multiplier="1.125",facingMonthPrice="4000",active=false)
        val saved=json.readTree(save(values).andExpect(status().isOk).andReturn().response.contentAsByteArray)
        assertThat(saved["values"]["facingMonthPrice"].asText()).isEqualTo("4000")
        assertThat(Instant.parse(saved["updatedAt"].asText())).isAfter(initial)
        assertThat(index()["items"][0]["values"]["active"].asBoolean()).isFalse()
        assertThat(row()["source_payload"]).isEqualTo(before["source_payload"])
        val audit=jdbc.queryForMap("SELECT * FROM merch_tariff_audit")
        assertThat(json.readTree(audit["before_json"].toString())["facingMonthPrice"].asText()).isEqualTo("3000")
        assertThat(json.readTree(audit["after_json"].toString())["multiplier"].asText()).isEqualTo("1.125")
        assertThat(audit["actor_admin_id"]).isEqualTo(actor.id)
        assertThrows(DataIntegrityViolationException::class.java) {jdbc.update("UPDATE merch_tariff_audit SET category='standard'")}
        assertThrows(DataIntegrityViolationException::class.java) {jdbc.update("DELETE FROM merch_tariff_audit")}
        assertThat(count()).isEqualTo(1)
    }

    @Test fun `global branches and global customers are both mandatory and edit never substitutes financial view`() {
        grants(allBranches=false);assertThat(capability()).isFalse();mvc.perform(get(url).header("Authorization",token)).andExpect(status().isForbidden)
        grants(allProducers=false);assertThat(capability()).isFalse();save().andExpect(status().isForbidden)
        grants(actions=listOf("edit","financial_data"));assertThat(capability()).isFalse();save().andExpect(status().isForbidden)
        grants(actions=listOf("view","edit"));assertThat(capability()).isFalse();save().andExpect(status().isForbidden)
        grants(actions=listOf("view","financial_data"));assertThat(capability()).isTrue();assertThat(index()["canEdit"].asBoolean()).isFalse()
        save().andExpect(status().isForbidden)
        grants(actions=emptyList());assertThat(capability()).isFalse();mvc.perform(get(url).header("Authorization",token)).andExpect(status().isForbidden)
        assertThat(count()).isZero();assertThat(row()["facing_month_price"]).isEqualTo(3000L)
    }

    @Test fun `current HQ role status and persisted authority win over a previously minted token`() {
        jdbc.update("UPDATE admin_users SET role='REGIONAL_MANAGER' WHERE id=?",actor.id)
        assertThat(capability()).isFalse();mvc.perform(get(url).header("Authorization",token)).andExpect(status().isForbidden)
        save().andExpect(status().isForbidden)
        jdbc.update("UPDATE admin_users SET role='HQ_HEAD' WHERE id=?",actor.id)
        assertThat(capability()).isTrue()
        jdbc.update("DELETE FROM unified_admin_profiles WHERE account_id=?",actor.id)
        mvc.perform(get(url).header("Authorization",token)).andExpect(status().isForbidden)
        jdbc.update("UPDATE admin_users SET status='blocked' WHERE id=?",actor.id)
        save().andExpect(status().isUnauthorized)
        assertThat(count()).isZero()
    }

    @Test fun `money is exact signed bigint range and fractional negative overflow or overprecise multipliers never round`() {
        val before=row()
        for(v in listOf(base.copy(facingMonthPrice="1.5"),base.copy(facingMonthPrice="-1"),base.copy(facingMonthPrice="9223372036854775808"),
            base.copy(multiplier="0.099"),base.copy(multiplier="10.001"),base.copy(multiplier="1.0001"),base.copy(label=" ")))
            save(v).andExpect(status().isBadRequest)
        assertThat(row()).isEqualTo(before);assertThat(count()).isZero()
        val max=Long.MAX_VALUE.toString()
        val saved=json.readTree(save(base.copy(facingMonthPrice=max)).andExpect(status().isOk).andReturn().response.contentAsByteArray)
        assertThat(saved["values"]["facingMonthPrice"].isTextual).isTrue();assertThat(saved["values"]["facingMonthPrice"].asText()).isEqualTo(max)
        assertThat(row()["facing_month_price"]).isEqualTo(Long.MAX_VALUE)
    }

    @Test fun `same timestamp concurrent edits have one winner one explicit conflict and one audit`() {
        val start=CountDownLatch(1);val executor=Executors.newFixedThreadPool(2)
        try {
            val futures=listOf("4000","5000").map {amount->executor.submit(Callable {start.await(5,TimeUnit.SECONDS)
                save(base.copy(facingMonthPrice=amount)).andReturn().response.status})}
            start.countDown();assertThat(futures.map {it.get(10,TimeUnit.SECONDS)}).containsExactlyInAnyOrder(200,409)
        } finally {executor.shutdownNow()}
        assertThat(count()).isEqualTo(1);assertThat(row()["facing_month_price"]).isIn(4000L,5000L)
        save().andExpect(status().isConflict);assertThat(count()).isEqualTo(1)
    }

    @Test fun `audit failure rolls back the tariff with its timestamp and source snapshot`() {
        val before=row();jdbc.execute("ALTER TABLE merch_tariff_audit ADD CONSTRAINT synthetic_audit_failure CHECK(false)")
        try {save().andExpect(status().isConflict)} finally {jdbc.execute("ALTER TABLE merch_tariff_audit DROP CONSTRAINT synthetic_audit_failure")}
        assertThat(row()).isEqualTo(before);assertThat(count()).isZero()
    }

    @Test fun `new commercial quotes use current tariff while earlier submitted prices and snapshots stay fixed`() {
        fun source(table:String,fields:Map<String,Any?>):UUID {
            val id=UUID.randomUUID();val values=linkedMapOf<String,Any?>("id" to id,"source_system" to "MERCH_CRM","source_origin" to "https://crm.inkar.kz",
                "source_id" to id,"source_payload" to "{\"retained\":true}","created_at" to Timestamp.from(initial),"updated_at" to Timestamp.from(initial));values.putAll(fields)
            named.update("INSERT INTO $table(${values.keys.joinToString(",")}) VALUES(${values.keys.joinToString(","){if(it in setOf("source_payload","specifications")) "CAST(:$it AS jsonb)" else ":$it"}})",values);return id
        }
        val org=source("merch_organizations",mapOf("name" to "Synthetic network","legal_name" to "Synthetic network","active" to true))
        val branch=source("merch_branches",mapOf("organization_id" to org,"code" to "SYNTH-A","name" to "Synthetic branch","address" to "Synthetic address","city" to "Synthetic city","active" to true))
        val shelf=source("merch_shelves",mapOf("branch_id" to branch,"code" to "S1","category" to "AA","width_m" to 1,"base_monthly_price" to 15000,
            "active" to true,"place_type" to "shelf","position_index" to 0,"specifications" to "{}","sale_mode" to "whole_only"))
        val producer=source("merch_producers",mapOf("name" to "Synthetic producer","active" to true,"source_account_role" to "advertiser"))
        jdbc.update("INSERT INTO merch_supplier_settings(producer_id,supplier_category,source_payload,updated_at) VALUES(?,'standard','{}',now())",producer)
        fun quote(month:LocalDate):JsonNode {
            val id=UUID.randomUUID();val product=CommercialProduct("product-a","Synthetic product","SKU","medicines","otc",1,listOf(month))
            val data=CommercialPlanData("Synthetic quote",month,month,100000,planningMode="products",productBriefs=listOf(product),
                shelfSelection=listOf(CommercialShelf(shelfId=shelf,placementMonths=listOf(month),products=listOf(product))))
            mvc.perform(put("/api/admin/merch/commercial/plans/$id").param("producerId",producer.toString()).header("Authorization",token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(SaveCommercialPlan(0,data)))).andExpect(status().isOk)
            return json.readTree(mvc.perform(post("/api/admin/merch/commercial/plans/$id/submit").header("Authorization",token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(SubmitCommercialPlan(1)))).andExpect(status().isOk).andReturn().response.contentAsByteArray)
        }
        val month=LocalDate.now(ZoneId.of("Asia/Almaty")).withDayOfMonth(1).plusMonths(1)
        val old=quote(month);val oldId=UUID.fromString(old["monthlyRequests"][0]["id"].asText())
        val oldItem=jdbc.queryForMap("SELECT monthly_price,source_payload FROM merch_media_plan_items WHERE media_plan_id=?",oldId)
        save().andExpect(status().isOk)
        val current=quote(month.plusMonths(1))
        assertThat(current["monthlyRequests"][0]["totalAmount"].asLong()-old["monthlyRequests"][0]["totalAmount"].asLong()).isEqualTo(1000)
        assertThat(jdbc.queryForMap("SELECT monthly_price,source_payload FROM merch_media_plan_items WHERE media_plan_id=?",oldId)).isEqualTo(oldItem)
    }
}
