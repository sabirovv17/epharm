package kz.epharm.promo

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.access.StoredGrant
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.entity.PromoTier
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleCard
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.repository.RuleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/** Actual JWT, interceptor, services, PostgreSQL and POSM matcher; no mocked authorization. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class UnifiedMotivationAuthorizationIntegrationTest {
    companion object {
        private const val BARCODE = "4871234567890"
        private const val GROUP = "grp_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val SUBGROUP = "sub_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val MNN = "mnn_cccccccccccccccccccccccccccccccc"
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("motivation_access").withUsername("epharm").withPassword("synthetic-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var users: AdminUserRepository
    @Autowired lateinit var jwt: JwtService
    @Autowired lateinit var promos: PromoRepository
    @Autowired lateinit var rules: RuleRepository
    @Autowired lateinit var products: ProductRepository
    private lateinit var admin: AdminUserEntity
    private lateinit var bearer: String

    @BeforeEach fun seed() {
        admin = users.saveAndFlush(AdminUserEntity(email = "motivation-${UUID.randomUUID()}@example.test",
            name = "Synthetic motivation administrator", passwordHash = "not-used-by-these-tests", roleRaw = AdminRole.HQ_HEAD.name))
        bearer = "Bearer ${jwt.issueAccessToken(admin)}"
        val person = UUID.randomUUID()
        jdbc.update("insert into unified_persons values(?,?)", person, "synthetic-test-proof")
        jdbc.update("insert into unified_admin_profiles(account_id,person_id) values(?,?)", admin.id, person)
        jdbc.update("update admin_users set access_mode='MANAGED' where id=?", admin.id)
        jdbc.update("""insert into chains(id,name,color,"group",points) values('mot-chain','Test','#000000','pilot',0)""")
        jdbc.update("""insert into pharmacies(id,name,chain_id,chain_name,city,district,addr,"group")
            values('mot-pharmacy','Test','mot-chain','Test','Test','','','pilot')""")
        products.saveAndFlush(ProductEntity(id = "mot-trigger", name = "Synthetic scanned product", barcode = BARCODE, price = 900))
        (1..4).forEach { index ->
            products.saveAndFlush(ProductEntity(id = "mot-offer-$index", name = "Synthetic offer $index", price = 1000 + index))
        }
    }

    private fun grant(section: String, action: String, all: Boolean = true) =
        StoredGrant("motivation", section, action, all, if (all) emptySet() else setOf("mot-pharmacy"))
    private fun grants(vararg grants: StoredGrant) {
        jdbc.update("update unified_admin_profiles set grants_json=?,version=version+1 where account_id=?",
            json.writeValueAsString(grants.toList()), admin.id)
    }
    private fun change(method: String, path: String, body: Any = emptyMap<String, Any>()): ResultActions {
        val request = when (method) { "PATCH" -> patch(path); "PUT" -> put(path); else -> post(path) }
        return mvc.perform(request.header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)))
    }
    private fun campaign(status: PromoStatus = PromoStatus.draft, bonus: Long = 0): PromoEntity =
        promos.saveAndFlush(PromoEntity(id = "mot-promo", title = "Synthetic campaign", medusaProductId = "mot-offer-1",
            productName = "Synthetic offer 1", tiers = listOf(PromoTier(1, 1001, bonus))).also { it.status = status })
    private fun linkedRule(bonus: Int = 0, active: Boolean = false): RuleEntity =
        rules.saveAndFlush(RuleEntity(id = "mot-rule", recommend = "mot-offer-1", bonus = bonus,
            trigger = RuleTrigger("product", "mot-trigger"), promoId = "mot-promo",
            card = RuleCard(pairActive = if (active) null else false)))
    private fun ref(active: Boolean = false, bonus: Int? = null) = mutableMapOf<String, Any?>(
        "medusaProductId" to "mot-trigger", "name" to "Synthetic scanned product", "barcode" to BARCODE,
        "active" to active, "bonus" to bonus,
    )
    private fun config(ref: Map<String, Any?> = ref()) = mapOf("replacements" to listOf(ref))

    @Test fun `assigned pharmacy grants cannot read or mutate global campaigns and rules`() {
        campaign()
        grants(grant("campaigns", "view", false), grant("campaigns", "edit", false),
            grant("campaigns", "create", false), grant("recommendations", "create", false))
        mvc.perform(get("/api/admin/promo").header("Authorization", bearer)).andExpect(status().isForbidden)
        change("PATCH", "/api/admin/promo/mot-promo", mapOf("title" to "Must not change")).andExpect(status().isForbidden)
        change("POST", "/api/admin/promo", mapOf("title" to "Must not create")).andExpect(status().isForbidden)
        change("POST", "/api/admin/rules", mapOf("type" to "substitution", "trigger" to mapOf("kind" to "product", "value" to "mot-trigger"),
            "recommend" to "mot-offer-1")).andExpect(status().isForbidden)
        assertThat(promos.findById("mot-promo").orElseThrow().title).isEqualTo("Synthetic campaign")
        assertThat(rules.count()).isZero()
    }

    @Test fun `draft author can save zero bonus content but cannot publish or configure money`() {
        grants(grant("campaigns", "create"), grant("campaigns", "edit"))
        val body = mapOf("title" to "Synthetic draft", "medusaProductId" to "mot-offer-1", "productName" to "Synthetic offer")
        val id = json.readTree(change("POST", "/api/admin/promo", body).andExpect(status().isOk)
            .andReturn().response.contentAsString)["id"].asText()
        change("PATCH", "/api/admin/promo/$id", mapOf("title" to "Edited draft", "pharmacistBonus" to 0)).andExpect(status().isOk)
        for (patch in listOf(mapOf("status" to "active"), mapOf("pharmacistBonus" to 200), mapOf("budget" to 1000))) {
            change("PATCH", "/api/admin/promo/$id", patch).andExpect(status().isForbidden)
        }
        assertThat(promos.findById(id).orElseThrow().status).isEqualTo(PromoStatus.draft)
        assertThat(promos.findById(id).orElseThrow().pharmacistBonus).isZero()
    }

    @Test fun `publishing and editing live campaign require publication independently from financial permission`() {
        campaign()
        grants(grant("campaigns", "edit"), grant("campaigns", "publish"))
        change("PATCH", "/api/admin/promo/mot-promo", mapOf("status" to "active", "pharmacistBonus" to 50)).andExpect(status().isForbidden)
        change("PATCH", "/api/admin/promo/mot-promo", mapOf("status" to "active")).andExpect(status().isConflict)
        promos.findById("mot-promo").orElseThrow().also { it.status = PromoStatus.active; promos.saveAndFlush(it) }
        grants(grant("campaigns", "edit"), grant("bonuses", "configure_bonus"), grant("campaigns", "archive"))
        change("PATCH", "/api/admin/promo/mot-promo", mapOf("title" to "Live change")).andExpect(status().isForbidden)
        change("POST", "/api/admin/promo/mot-promo/archive").andExpect(status().isForbidden)
        assertThat(promos.findById("mot-promo").orElseThrow().status).isEqualTo(PromoStatus.active)
    }

    @Test fun `legacy pair writes cannot bypass the managed prepared workflow even for a draft`() {
        campaign()
        grants(grant("recommendations", "edit"))
        change("PUT", "/api/admin/promo/mot-promo/rules", config(ref(active = true))).andExpect(status().isForbidden)
        change("PUT", "/api/admin/promo/mot-promo/rules", config()).andExpect(status().isConflict)
        assertThat(rules.count()).isZero()
        grants(grant("recommendations", "edit"), grant("recommendations", "publish"))
        change("PUT", "/api/admin/promo/mot-promo/rules", config(ref(active = true))).andExpect(status().isConflict)
        assertThat(rules.count()).isZero()
    }

    @Test fun `legacy text roundtrip cannot bypass the managed revision even when money is unchanged`() {
        campaign(bonus = 100)
        linkedRule(bonus = 100)
        grants(grant("recommendations", "edit"))
        change("PUT", "/api/admin/promo/mot-promo/rules", config(ref(bonus = 100).apply { put("script", "Updated text") }))
            .andExpect(status().isConflict)
        change("PUT", "/api/admin/promo/mot-promo/rules", config(ref(bonus = null).apply { put("script", "Fallback unchanged") }))
            .andExpect(status().isForbidden)
        grants(grant("recommendations", "edit"), grant("bonuses", "configure_bonus"))
        change("PUT", "/api/admin/promo/mot-promo/rules", config(ref(bonus = null).apply { put("script", "Fallback unchanged") }))
            .andExpect(status().isConflict)
        assertThat(rules.findAll().single().bonus).isEqualTo(100)
        assertThat(rules.findAll().single().script).isEmpty()
    }

    @Test fun `fallback zero removal paid trigger or extra offer cannot bypass financial configuration`() {
        campaign(bonus = 100)
        linkedRule(bonus = 20)
        grants(grant("recommendations", "edit"), grant("recommendations", "publish"))
        for (body in listOf(
            config(ref(bonus = null)), config(ref(bonus = 0)), emptyMap<String, Any>(),
            config(ref(bonus = 20).apply { put("additionalRecommendations", listOf(mapOf("medusaProductId" to "mot-offer-2", "bonus" to 500))) }),
        )) change("PUT", "/api/admin/promo/mot-promo/rules", body).andExpect(status().isForbidden)
        assertThat(rules.findById("mot-rule").orElseThrow().bonus).isEqualTo(20)
        assertThat(rules.count()).isEqualTo(1)
    }

    @Test fun `goal reward removal and target changes require financial grant and local financial scope is insufficient`() {
        val promo = campaign()
        promo.goalTarget = 5; promo.goalBonus = 100
        promos.saveAndFlush(promo)
        linkedRule().also {
            it.card = RuleCard(pairActive = false, goalTarget = 5, goalBonus = 100)
            rules.saveAndFlush(it)
        }
        grants(grant("recommendations", "edit"), grant("bonuses", "configure_bonus", false))
        change("PUT", "/api/admin/promo/mot-promo/rules", config()).andExpect(status().isForbidden)
        change("PUT", "/api/admin/promo/mot-promo/rules", config() + mapOf("goalTarget" to 6, "goalBonus" to 100))
            .andExpect(status().isForbidden)
        change("PUT", "/api/admin/promo/mot-promo/rules", config(ref(bonus = 0)) + mapOf("goalTarget" to 5, "goalBonus" to 100))
            .andExpect(status().isConflict)
        assertThat(promos.findById(promo.id).orElseThrow().goalTarget).isEqualTo(5)
    }

    @Test fun `legacy per-rule paid goal cannot be silently removed by campaign replacement`() {
        campaign()
        linkedRule().also {
            it.card = RuleCard(pairActive = false, goalTarget = 5, goalBonus = 100)
            rules.saveAndFlush(it)
        }
        grants(grant("recommendations", "edit"))
        change("PUT", "/api/admin/promo/mot-promo/rules", config(ref(bonus = 0))).andExpect(status().isForbidden)
        assertThat(rules.findById("mot-rule").orElseThrow().card?.goalBonus).isEqualTo(100)
    }

    @Test fun `standalone rule status bonus goal clearing and paid duplication cannot use generic edit`() {
        campaign()
        val rule = linkedRule(bonus = 20)
        rule.card = RuleCard(pairActive = false, goalTarget = 5, goalBonus = 100)
        rules.saveAndFlush(rule)
        grants(grant("recommendations", "edit"), grant("recommendations", "duplicate"))
        for (body in listOf(mapOf("status" to "active"), mapOf("bonus" to 0), mapOf("clearCard" to true))) {
            change("PATCH", "/api/admin/rules/mot-rule", body).andExpect(status().isForbidden)
        }
        change("POST", "/api/admin/rules/mot-rule/duplicate").andExpect(status().isForbidden)
        assertThat(rules.count()).isEqualTo(1)
        assertThat(rules.findById(rule.id).orElseThrow().bonus).isEqualTo(20)
    }

    @Test fun `paid goal eligibility and legacy paid tiers cannot change through ordinary text editing`() {
        val promo = campaign()
        linkedRule().also {
            it.card = RuleCard(pairActive = false, goalTarget = 5, goalBonus = 100)
            rules.saveAndFlush(it)
        }
        grants(grant("recommendations", "edit"), grant("campaigns", "edit"))
        change("PATCH", "/api/admin/rules/mot-rule", mapOf("trigger" to mapOf("kind" to "product", "value" to "mot-offer-2")))
            .andExpect(status().isForbidden)
        promo.tiers = listOf(PromoTier(1, 1001, 0), PromoTier(10, 900, 100))
        promos.saveAndFlush(promo)
        change("PATCH", "/api/admin/promo/mot-promo", mapOf("title" to "Would discard old paid tier"))
            .andExpect(status().isForbidden)
        assertThat(promos.findById(promo.id).orElseThrow().tiers).hasSize(2)
        assertThat(rules.findById("mot-rule").orElseThrow().trigger.value).isEqualTo("mot-trigger")
    }

    @Test fun `authorized publication preserves product ACC group subgroup and MNN matching through real POSM endpoint`() {
        publishTaxonomy()
        grants(grant("recommendations", "create"), grant("recommendations", "publish"), grant("bonuses", "configure_bonus"))
        val triggers = listOf("product" to "mot-trigger", "acc_group" to GROUP, "acc_subgroup" to SUBGROUP, "acc_mnn" to MNN)
        val ids = triggers.mapIndexed { index, (kind, value) ->
            val body = mapOf("type" to "substitution", "status" to "active", "trigger" to mapOf("kind" to kind, "value" to value),
                "recommend" to "mot-offer-${index + 1}", "bonus" to (index + 1) * 10)
            json.readTree(change("POST", "/api/admin/rules", body).andExpect(status().isOk)
                .andReturn().response.contentAsString)["id"].asText()
        }
        val response = mvc.perform(post("/api/posm/recommend").header("X-Posm-Key", "dev-posm-key")
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf(
                "pharmacyId" to "mot-pharmacy", "sessionId" to "synthetic-four-triggers", "cart" to listOf(mapOf("barcode" to BARCODE)),
            )))).andExpect(status().isOk).andReturn().response.contentAsString
        val recommendations = json.readTree(response)["recommendations"]
        assertThat(recommendations.map { it["ruleId"].asText() }).containsExactlyInAnyOrderElementsOf(ids)
        assertThat(recommendations.map { it["bonus"].asInt() }).containsExactlyInAnyOrder(10, 20, 30, 40)
    }

    @Test fun `legacy account keeps its existing rule workflow without managed grants`() {
        jdbc.update("update admin_users set access_mode='LEGACY' where id=?", admin.id)
        change("POST", "/api/admin/rules", mapOf("type" to "substitution", "status" to "active", "bonus" to 100,
            "trigger" to mapOf("kind" to "product", "value" to "mot-trigger"), "recommend" to "mot-offer-1"))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("active")).andExpect(jsonPath("$.bonus").value(100))
    }

    @Test fun `request and malformed stored campaign bonus cannot overflow POSM integer money`() {
        campaign()
        linkedRule()
        grants(grant("campaigns", "edit"), grant("campaigns", "create"), grant("recommendations", "edit"), grant("bonuses", "configure_bonus"))
        val oversized = Int.MAX_VALUE.toLong() + 1
        change("PATCH", "/api/admin/promo/mot-promo", mapOf("pharmacistBonus" to oversized)).andExpect(status().isBadRequest)
        change("POST", "/api/admin/promo", mapOf("title" to "Overflow", "pharmacistBonus" to oversized)).andExpect(status().isBadRequest)
        val promo = promos.findById("mot-promo").orElseThrow()
        promo.tiers = listOf(PromoTier(1, 1001, oversized)); promos.saveAndFlush(promo)
        change("PUT", "/api/admin/promo/mot-promo/rules", config()).andExpect(status().isBadRequest)
        assertThat(rules.findById("mot-rule").orElseThrow().bonus).isZero()
    }

    @Test fun `legacy account compatibility keeps old PATCH behavior until controlled account conversion`() {
        jdbc.update("update admin_users set access_mode='LEGACY' where id=?", admin.id)
        campaign(PromoStatus.active, 100)
        linkedRule(100, active = true).also { it.status = RuleStatus.active; rules.saveAndFlush(it) }
        grants(grant("campaigns", "edit"), grant("campaigns", "publish"), grant("bonuses", "configure_bonus"))
        change("PATCH", "/api/admin/promo/mot-promo", mapOf("pharmacistBonus" to 250)).andExpect(status().isOk)
            .andExpect(jsonPath("$.pharmacistBonus").value(250))
        assertThat(rules.findById("mot-rule").orElseThrow().bonus).isEqualTo(100)
    }

    private fun publishTaxonomy() {
        val id = UUID.randomUUID()
        jdbc.update("insert into acc_catalog_snapshots(id,sha256,source_name,item_count,barcode_count) values(?,?,?,?,?)",
            id, "a".repeat(64), "synthetic.xlsx", 1, 1)
        jdbc.update("""insert into acc_catalog_barcodes(snapshot_id,ware_id,barcode,group_key,group_label,
            subgroup_key,subgroup_label,mnn_key,mnn_label) values(?,?,?,?,?,?,?,?,?)""",
            id, "synthetic-ware", BARCODE, GROUP, "Synthetic group", SUBGROUP, "Synthetic subgroup", MNN, "Synthetic MNN")
        jdbc.update("update acc_catalog_state set active_snapshot_id=? where singleton=1", id)
    }
}
