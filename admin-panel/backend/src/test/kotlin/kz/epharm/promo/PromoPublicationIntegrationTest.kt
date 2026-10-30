package kz.epharm.promo

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import kz.epharm.auth.access.StoredGrant
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.service.JwtService
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.posm.dto.RecommendResponse
import kz.epharm.posm.dto.OutcomeResponse
import kz.epharm.merchtasks.dto.MerchRecommendationCandidate
import kz.epharm.merchtasks.dto.MerchRecommendationContextEnvelope
import kz.epharm.merchtasks.service.MerchTaskClient
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.promo.dto.*
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.entity.PromoTier
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.*
import kz.epharm.rules.repository.RuleRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.LocalDate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Committed fixtures and independent HTTP transactions verify publication and rollback, not test-TX state. */
@SpringBootTest(properties = ["app.posm.sale-bonus.enabled=true", "app.posm.sale-bonus.eligible-after=2026-01-01T00:00:00Z"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class PromoPublicationIntegrationTest {
    companion object {
        private const val BARCODE = "4871234567890"
        private const val SECOND_BARCODE = "4871234567807"
        private const val OFFER_BARCODE = "4871234567814"
        private const val GROUP = "grp_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("promo_publication").withUsername("epharm").withPassword("synthetic-only").apply { start() }
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
    @Autowired lateinit var pharmacists: PharmacistRepository
    @MockkBean lateinit var merch: MerchTaskClient
    private lateinit var admin: AdminUserEntity
    private lateinit var bearer: String

    @BeforeEach fun seed() {
        // This database belongs solely to this Testcontainer class; no production connection is used.
        jdbc.execute("""TRUNCATE promo_publication_history,promo_publication_changes,recommendation_events,pending_bonuses,pos_sales,pharmacists,rules,promos,
            products,unified_admin_access_audit,unified_admin_profiles,unified_persons,admin_users,pharmacies,chains CASCADE""")
        every { merch.recommendationContext(any()) } returns MerchRecommendationContextEnvelope(available = false)
        admin = users.saveAndFlush(AdminUserEntity(email = "publication@example.test", name = "Synthetic publisher",
            passwordHash = "unused-test-hash", roleRaw = AdminRole.HQ_HEAD.name))
        bearer = "Bearer ${jwt.issueAccessToken(admin)}"
        val person = UUID.randomUUID()
        jdbc.update("insert into unified_persons values(?,?)", person, "synthetic-verification")
        jdbc.update("insert into unified_admin_profiles(account_id,person_id) values(?,?)", admin.id, person)
        jdbc.update("update admin_users set access_mode='MANAGED' where id=?", admin.id)
        jdbc.update("""insert into chains(id,name,color,"group",points) values('pub-chain','Test','#000000','pilot',0)""")
        jdbc.update("""insert into pharmacies(id,name,chain_id,chain_name,city,district,addr,"group")
            values('pub-pharmacy','Test','pub-chain','Test','Test','','','pilot')""")
        products.saveAllAndFlush(listOf(
            ProductEntity(id = "pub-trigger", name = "Synthetic trigger", barcode = BARCODE, price = 1000),
            ProductEntity(id = "pub-trigger-2", name = "Second trigger", barcode = "4871234567807", price = 1000),
            ProductEntity(id = "pub-offer", name = "Synthetic offer", price = 2000),
            ProductEntity(id = "pub-extra", name = "Synthetic extra", price = 3000),
        ))
        promos.saveAndFlush(PromoEntity(id = "pub-promo", title = "Published title", statusRaw = "active",
            medusaProductId = "pub-offer", productName = "Synthetic offer", tiers = listOf(PromoTier(1, 2000, 100))))
        rules.saveAndFlush(RuleEntity(id = "pub-rule", promoId = "pub-promo", statusRaw = "active",
            recommend = "pub-offer", trigger = RuleTrigger("product", "pub-trigger"), bonus = 100,
            script = "Published script", card = RuleCard(offerRank = 0, bonusInherited = true), impressions = 7))
        grants(*allGrants().toTypedArray())
    }

    private fun grant(section: String, action: String, all: Boolean = true) =
        StoredGrant("motivation", section, action, all, if (all) emptySet() else setOf("pub-pharmacy"))
    private fun allGrants() = listOf(grant("campaigns", "view"), grant("recommendations", "view"),
        grant("campaigns", "edit"), grant("recommendations", "edit"), grant("campaigns", "publish"),
        grant("recommendations", "publish"), grant("bonuses", "configure_bonus"), grant("campaigns", "archive"),
        grant("campaigns", "restore"))
    private fun grants(vararg grants: StoredGrant) {
        jdbc.update("update unified_admin_profiles set grants_json=?,version=version+1 where account_id=?",
            json.writeValueAsString(grants.toList()), admin.id)
    }
    private fun request(method: String, path: String, body: Any): ResultActions {
        val req = when (method) { "PUT" -> put(path); "PATCH" -> patch(path); else -> post(path) }
        return mvc.perform(req.header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
    }
    private fun state(): PromoChangesViewDto = json.readValue(mvc.perform(get("/api/admin/promo/pub-promo/changes")
        .header("Authorization", bearer)).andExpect(status().isOk).andReturn().response.contentAsByteArray)
    private fun save(bundle: PromoChangesBundleDto, revision: Long = state().revision): PromoChangesViewDto =
        json.readValue(request("PUT", "/api/admin/promo/pub-promo/changes", SavePromoChangesRequest(revision, bundle))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray)
    private fun apply(revision: Long): PromoChangesViewDto = json.readValue(request("POST", "/api/admin/promo/pub-promo/changes/apply",
        ApplyPromoChangesRequest(revision)).andExpect(status().isOk).andReturn().response.contentAsByteArray)
    private fun recommend(session: String, pharmacist: String = "", barcode: String = BARCODE): RecommendResponse = json.readValue(mvc.perform(post("/api/posm/recommend")
        .header("X-Posm-Key", "dev-posm-key").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf(
            "pharmacyId" to "pub-pharmacy", "pharmacistId" to pharmacist, "sessionId" to session, "cart" to listOf(mapOf("barcode" to barcode)),
        )))).andExpect(status().isOk).andReturn().response.contentAsByteArray)
    private fun posm(path: String, body: Any): ResultActions = mvc.perform(post(path).header("X-Posm-Key", "dev-posm-key")
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
    private fun shown(event: String, at: Instant) = posm("/api/posm/recommendations/$event/shown", mapOf("shownAt" to at))
        .andExpect(status().isOk)
    private fun accept(event: String): OutcomeResponse = json.readValue(posm("/api/posm/recommendations/$event/outcome",
        mapOf("outcome" to "accepted", "finalSku" to "pub-offer")).andExpect(status().isOk).andReturn().response.contentAsByteArray)
    private fun staff() = pharmacists.saveAndFlush(PharmacistEntity(id = "pub-staff", name = "Synthetic staff",
        iin = "000000000001", phone = "+77000000001", pharmacyId = "pub-pharmacy", pharmacyName = "Test", statusRaw = "active"))
    private fun offeredBarcode() {
        products.findById("pub-offer").orElseThrow().also { it.barcode = OFFER_BARCODE; products.saveAndFlush(it) }
        promos.findById("pub-promo").orElseThrow().also { it.barcode = OFFER_BARCODE; it.barcodes = listOf(OFFER_BARCODE); promos.saveAndFlush(it) }
    }
    private fun sale(session: String, printedAt: Instant) = posm("/api/posm/sales", mapOf(
        "saleId" to "sale-$session", "pharmacistId" to "pub-staff", "pharmacyId" to "pub-pharmacy", "sessionId" to session,
        "captureSource" to "standardn-print-log", "totalAmount" to 2000,
        "items" to listOf(mapOf("barcode" to OFFER_BARCODE, "qty" to 1, "price" to 2000, "total" to 2000)), "printedAt" to printedAt,
    )).andExpect(status().isOk)
    private fun scripted(bundle: PromoChangesBundleDto, text: String) = bundle.copy(rules = bundle.rules.copy(
        replacements = bundle.rules.replacements.map { it.copy(script = text) }))

    @Test fun `prepared changes do not reach POSM until Apply and preserve rule IDs events counters and report membership`() {
        val oldEvent = recommend("before-preparation").recommendations.single()
        val initial = state()
        assertThat(initial.revision).isEqualTo(0L)
        assertThat(initial.bundle.rules.replacements.single().bonus).isNull()
        val changed = scripted(initial.bundle.copy(campaign = initial.bundle.campaign.copy(pharmacistBonus = 250)), "New script")
        val prepared = save(changed)
        assertThat(prepared.hasPendingChanges).isTrue(); assertThat(prepared.canApply).isTrue()
        assertThat(promos.findById("pub-promo").orElseThrow().pharmacistBonus).isEqualTo(100)
        assertThat(recommend("still-published").recommendations.single().bonus).isEqualTo(100)
        val countBefore = jdbc.queryForObject("select count(*) from recommendation_events", Long::class.java)
        val applied = apply(prepared.revision)
        assertThat(applied.hasPendingChanges).isFalse(); assertThat(applied.revision).isEqualTo(prepared.revision + 1)
        val rule = rules.findById("pub-rule").orElseThrow()
        assertThat(rule.bonus).isEqualTo(250); assertThat(rule.script).isEqualTo("New script"); assertThat(rule.impressions).isEqualTo(7)
        assertThat(jdbc.queryForObject("select count(*) from recommendation_events", Long::class.java)).isEqualTo(countBefore)
        assertThat(jdbc.queryForObject("select bonus from recommendation_events where id=?", Long::class.java, oldEvent.eventId)).isEqualTo(100)
        assertThat(jdbc.queryForObject("select count(*) from recommendation_events e join rules r on r.id=e.rule_id where r.promo_id='pub-promo'", Long::class.java))
            .isEqualTo(countBefore)
        assertThat(jdbc.queryForObject("select count(*) from pharmacist_wallet_entries", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select applied_by from promo_publication_history where promo_id='pub-promo'", String::class.java)).isEqualTo(admin.id.toString())
        assertThat(recommend("after-apply").recommendations.single().bonus).isEqualTo(250)
    }

    @Test fun `removed offer is retired without deletion and never returns after campaign archive restore or activation`() {
        rules.saveAndFlush(RuleEntity(id = "pub-extra-rule", promoId = "pub-promo", statusRaw = "active", recommend = "pub-extra",
            trigger = RuleTrigger("product", "pub-trigger"), bonus = 0, card = RuleCard(offerRank = 1, bonusInherited = false)))
        val initial = state()
        val bundle = initial.bundle.copy(rules = initial.bundle.rules.copy(replacements = initial.bundle.rules.replacements.map {
            it.copy(additionalRecommendations = emptyList()) }))
        apply(save(bundle).revision)
        val retired = rules.findById("pub-extra-rule").orElseThrow()
        assertThat(retired.retiredAt).isNotNull(); assertThat(retired.status).isEqualTo(RuleStatus.archived)
        assertThat(retired.promoId).isEqualTo("pub-promo")
        request("POST", "/api/admin/promo/pub-promo/archive", emptyMap<String, Any>()).andExpect(status().isOk)
        request("POST", "/api/admin/promo/pub-promo/restore", emptyMap<String, Any>()).andExpect(status().isOk)
        apply(save(state().bundle).revision)
        assertThat(rules.findById("pub-extra-rule").orElseThrow().status).isEqualTo(RuleStatus.archived)
        assertThat(state().bundle.rules.replacements.single().additionalRecommendations).isEmpty()
        assertThat(recommend("retired-not-returned").recommendations.map { it.ruleId }).containsExactly("pub-rule")
    }

    @Test fun `changed trigger receives new identity while its old event remains linked to campaign history`() {
        val event = recommend("old-identity").recommendations.single()
        val initial = state()
        val bundle = initial.bundle.copy(rules = initial.bundle.rules.copy(replacements = initial.bundle.rules.replacements.map {
            it.copy(medusaProductId = "pub-trigger-2", barcode = "4871234567807") }))
        apply(save(bundle).revision)
        assertThat(rules.findById("pub-rule").orElseThrow().retiredAt).isNotNull()
        assertThat(rules.findAll().filter { it.retiredAt == null }.single().id).isNotEqualTo("pub-rule")
        assertThat(jdbc.queryForObject("select r.promo_id from recommendation_events e join rules r on r.id=e.rule_id where e.id=?",
            String::class.java, event.eventId)).isEqualTo("pub-promo")
    }

    @Test fun `legacy authoring produces conflict and explicit discard does not overwrite published changes or reset revision`() {
        val initial = state()
        val pending = save(scripted(initial.bundle, "Prepared"))
        jdbc.update("update admin_users set access_mode='LEGACY' where id=?", admin.id)
        request("PATCH", "/api/admin/promo/pub-promo", mapOf("title" to "Legacy change")).andExpect(status().isOk)
        jdbc.update("update admin_users set access_mode='MANAGED' where id=?", admin.id)
        assertThat(state().conflict).isTrue(); assertThat(state().canApply).isFalse()
        request("POST", "/api/admin/promo/pub-promo/changes/apply", ApplyPromoChangesRequest(pending.revision)).andExpect(status().isConflict)
        request("PUT", "/api/admin/promo/pub-promo/changes", SavePromoChangesRequest(pending.revision, initial.bundle)).andExpect(status().isConflict)
        val discarded: PromoChangesViewDto = json.readValue(request("PUT", "/api/admin/promo/pub-promo/changes",
            SavePromoChangesRequest(pending.revision, discard = true)).andExpect(status().isOk).andReturn().response.contentAsByteArray)
        assertThat(discarded.hasPendingChanges).isFalse(); assertThat(discarded.revision).isGreaterThan(pending.revision)
        assertThat(discarded.bundle.campaign.title).isEqualTo("Legacy change")
        request("PUT", "/api/admin/promo/pub-promo/changes", SavePromoChangesRequest(0, initial.bundle)).andExpect(status().isConflict)
        assertThat(jdbc.queryForObject("select count(*) from promo_publication_history", Long::class.java)).isZero()
    }

    @Test fun `catalog price and analytics counter updates do not conflict with a prepared authoring bundle`() {
        rules.saveAndFlush(RuleEntity(id = "pub-another-rule", promoId = "pub-promo", statusRaw = "active",
            recommend = "pub-offer", trigger = RuleTrigger("product", "pub-trigger-2"), bonus = 100,
            card = RuleCard(offerRank = 0, bonusInherited = true)))
        val pending = save(scripted(state().bundle, "Fresh text"))
        jdbc.update("update products set price=12345 where id='pub-offer'")
        jdbc.update("update rules set impressions=42,revenue=500,payout=50,updated_at=now()+interval '1 day' where id='pub-rule'")
        jdbc.update("update promos set tiers='[{\"minQty\":1,\"price\":12345,\"bonus\":100}]',spent=900 where id='pub-promo'")
        assertThat(state().conflict).isFalse()
        apply(pending.revision)
        assertThat(rules.findById("pub-rule").orElseThrow().impressions).isEqualTo(42)
    }

    @Test fun `first launch cannot publish empty inactive self-triggered or expired configurations`() {
        promos.findById("pub-promo").orElseThrow().also { it.status = PromoStatus.draft; promos.saveAndFlush(it) }
        for (configuration in listOf(PromoRulesConfigDto(), PromoRulesConfigDto(replacements = listOf(
            PromoRuleProductRefDto("pub-trigger", active = false))), PromoRulesConfigDto(replacements = listOf(
            PromoRuleProductRefDto("pub-offer", active = true))))) {
            val pending = save(state().bundle.copy(rules = configuration))
            assertThat(pending.canApply).isFalse()
            request("POST", "/api/admin/promo/pub-promo/changes/apply", ApplyPromoChangesRequest(pending.revision)).andExpect(status().isBadRequest)
        }
        val expired = save(state().bundle.copy(campaign = state().bundle.campaign.copy(dateEnd = LocalDate.of(2020, 1, 1)),
            rules = PromoRulesConfigDto(replacements = listOf(PromoRuleProductRefDto("pub-trigger", active = true)))))
        assertThat(expired.readinessIssues).contains("Период акции уже закончился")
        request("POST", "/api/admin/promo/pub-promo/changes/apply", ApplyPromoChangesRequest(expired.revision)).andExpect(status().isBadRequest)
        assertThat(promos.findById("pub-promo").orElseThrow().status).isEqualTo(PromoStatus.draft)
        assertThat(rules.findById("pub-rule").orElseThrow().retiredAt).isNull()
        assertThat(jdbc.queryForObject("select count(*) from promo_publication_history", Long::class.java)).isZero()
    }

    @Test fun `draft first Apply publishes once and paused Apply preserves pause`() {
        promos.findById("pub-promo").orElseThrow().also { it.status = PromoStatus.draft; promos.saveAndFlush(it) }
        val pending = save(state().bundle)
        apply(pending.revision)
        assertThat(promos.findById("pub-promo").orElseThrow().status).isEqualTo(PromoStatus.active)
        request("POST", "/api/admin/promo/pub-promo/changes/apply", ApplyPromoChangesRequest(pending.revision)).andExpect(status().isConflict)
        request("PATCH", "/api/admin/promo/pub-promo", mapOf("status" to "paused")).andExpect(status().isOk)
        apply(save(scripted(state().bundle, "Prepared while paused")).revision)
        assertThat(promos.findById("pub-promo").orElseThrow().status).isEqualTo(PromoStatus.paused)
        assertThat(recommend("paused-stays-paused").recommendations).isEmpty()
        assertThat(jdbc.queryForObject("select count(*) from promo_publication_history", Long::class.java)).isEqualTo(2)
    }

    @Test fun `publication and global financial permission are checked again after preparation`() {
        val pending = save(state().bundle.copy(campaign = state().bundle.campaign.copy(pharmacistBonus = 300)))
        grants(*allGrants().filter { it.action != "configure_bonus" }.toTypedArray(), grant("bonuses", "configure_bonus", false))
        assertThat(state().canApply).isFalse()
        request("POST", "/api/admin/promo/pub-promo/changes/apply", ApplyPromoChangesRequest(pending.revision)).andExpect(status().isForbidden)
        grants(*allGrants().filter { it.action != "publish" }.toTypedArray())
        request("POST", "/api/admin/promo/pub-promo/changes/apply", ApplyPromoChangesRequest(pending.revision)).andExpect(status().isForbidden)
        assertThat(promos.findById("pub-promo").orElseThrow().pharmacistBonus).isEqualTo(100)
        assertThat(jdbc.queryForObject("select count(*) from promo_publication_history", Long::class.java)).isZero()
    }

    @Test fun `full prepared projection explicitly clears nullable overrides dates and part identifier`() {
        promos.findById("pub-promo").orElseThrow().also {
            it.overrideDescription = "Old override"; it.overrideImage = "https://example.test/old.png"; it.ipartId = "123"
            it.dateStart = LocalDate.of(2020, 1, 1); it.dateEnd = LocalDate.of(2090, 1, 1); promos.saveAndFlush(it)
        }
        products.findById("pub-offer").orElseThrow().also { it.ipartId = "123"; products.saveAndFlush(it) }
        val current = state().bundle
        apply(save(current.copy(campaign = current.campaign.copy(overrideDescription = null, overrideImage = null,
            ipartId = null, dateStart = null, dateEnd = null))).revision)
        val published = promos.findById("pub-promo").orElseThrow()
        assertThat(published.overrideDescription).isNull(); assertThat(published.overrideImage).isNull()
        assertThat(published.ipartId).isNull(); assertThat(published.dateStart).isNull(); assertThat(published.dateEnd).isNull()
        assertThat(products.findById("pub-offer").orElseThrow().ipartId).isNull()
    }

    @Test fun `unknown legacy explicit bonus is preserved and direct generated bonus override stops inheritance`() {
        rules.findById("pub-rule").orElseThrow().also { it.card = it.card?.copy(bonusInherited = null); rules.saveAndFlush(it) }
        var current = state().bundle
        assertThat(current.rules.replacements.single().bonus).isEqualTo(100)
        apply(save(current.copy(campaign = current.campaign.copy(pharmacistBonus = 250))).revision)
        assertThat(rules.findById("pub-rule").orElseThrow().bonus).isEqualTo(100)
        assertThat(rules.findById("pub-rule").orElseThrow().card?.bonusInherited).isNull()
        rules.findById("pub-rule").orElseThrow().also { it.card = it.card?.copy(bonusInherited = true); rules.saveAndFlush(it) }
        jdbc.update("update admin_users set access_mode='LEGACY' where id=?", admin.id)
        request("PATCH", "/api/admin/rules/pub-rule", mapOf("bonus" to 500, "card" to mapOf("bonusInherited" to true)))
            .andExpect(status().isOk)
        jdbc.update("update admin_users set access_mode='MANAGED' where id=?", admin.id)
        assertThat(rules.findById("pub-rule").orElseThrow().card?.bonusInherited).isFalse()
        current = state().bundle
        assertThat(current.rules.replacements.single().bonus).isEqualTo(500)
        apply(save(scripted(current, "Keep override")).revision)
        assertThat(rules.findById("pub-rule").orElseThrow().bonus).isEqualTo(500)
    }

    @Test fun `expired taxonomy after save rejects Apply and rolls back published metadata rule and history`() {
        val snapshot = UUID.randomUUID()
        jdbc.update("insert into acc_catalog_snapshots(id,sha256,source_name,item_count,barcode_count) values(?,?,?,?,?)",
            snapshot, "b".repeat(64), "synthetic.xlsx", 1, 1)
        jdbc.update("insert into acc_catalog_barcodes(snapshot_id,ware_id,barcode,group_key,group_label) values(?,?,?,?,?)",
            snapshot, "synthetic", BARCODE, GROUP, "Synthetic group")
        jdbc.update("update acc_catalog_state set active_snapshot_id=? where singleton=1", snapshot)
        val initial = state().bundle
        val pending = save(initial.copy(campaign = initial.campaign.copy(title = "Must not commit"),
            rules = PromoRulesConfigDto(replacements = listOf(PromoRuleProductRefDto("", triggerKind = "acc_group", triggerValue = GROUP)))))
        jdbc.update("update acc_catalog_state set active_snapshot_id=null where singleton=1")
        request("POST", "/api/admin/promo/pub-promo/changes/apply", ApplyPromoChangesRequest(pending.revision)).andExpect(status().isBadRequest)
        assertThat(promos.findById("pub-promo").orElseThrow().title).isEqualTo("Published title")
        assertThat(rules.findById("pub-rule").orElseThrow().retiredAt).isNull()
        assertThat(state().hasPendingChanges).isTrue(); assertThat(state().canApply).isFalse()
        assertThat(jdbc.queryForObject("select count(*) from promo_publication_history", Long::class.java)).isZero()
    }

    @Test fun `history write failure rolls back already materialized campaign rules and prepared revision`() {
        val current = state().bundle
        val pending = save(scripted(current.copy(campaign = current.campaign.copy(pharmacistBonus = 300)), "Must roll back"))
        // Failure injection is confined to this class-owned disposable PostgreSQL container.
        jdbc.execute("alter table promo_publication_history add constraint test_history_failure check(false)")
        try {
            request("POST", "/api/admin/promo/pub-promo/changes/apply", ApplyPromoChangesRequest(pending.revision))
                .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONFLICT"))
            assertThat(promos.findById("pub-promo").orElseThrow().pharmacistBonus).isEqualTo(100)
            assertThat(rules.findById("pub-rule").orElseThrow().bonus).isEqualTo(100)
            assertThat(rules.findById("pub-rule").orElseThrow().script).isEqualTo("Published script")
            assertThat(state().revision).isEqualTo(pending.revision)
            assertThat(state().hasPendingChanges).isTrue()
        } finally {
            jdbc.execute("alter table promo_publication_history drop constraint test_history_failure")
        }
    }

    @Test fun `database rejects mutation or deletion of applied publication history`() {
        apply(save(scripted(state().bundle, "Audited publication")).revision)
        val original = jdbc.queryForObject("select bundle_json from promo_publication_history where promo_id='pub-promo'", String::class.java)
        assertThatThrownBy {
            jdbc.update("update promo_publication_history set applied_by='forged-actor' where promo_id='pub-promo'")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy {
            jdbc.update("delete from promo_publication_history where promo_id='pub-promo'")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(jdbc.queryForObject("select bundle_json from promo_publication_history where promo_id='pub-promo'", String::class.java))
            .isEqualTo(original)
        assertThat(jdbc.queryForObject("select count(*) from promo_publication_history", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select applied_by from promo_publication_history where promo_id='pub-promo'", String::class.java))
            .isEqualTo(admin.id.toString())
    }

    @Test fun `unchanged recommendation preserves legacy AB assignment on text publication`() {
        val variant = RuleAbTest("A", 50)
        rules.findById("pub-rule").orElseThrow().also { it.abTest = variant; rules.saveAndFlush(it) }
        apply(save(scripted(state().bundle, "Updated without touching AB assignment")).revision)
        assertThat(rules.findById("pub-rule").orElseThrow().abTest).isEqualTo(variant)
        assertThat(rules.findById("pub-rule").orElseThrow().script).isEqualTo("Updated without touching AB assignment")
    }

    @Test fun `ambiguous legacy AB variants remain separate and cannot be collapsed by campaign Apply or legacy PUT`() {
        rules.findById("pub-rule").orElseThrow().also { it.abTest = RuleAbTest("A", 50); rules.saveAndFlush(it) }
        rules.saveAndFlush(RuleEntity(id = "pub-variant-b", promoId = "pub-promo", statusRaw = "active", recommend = "pub-offer",
            trigger = RuleTrigger("product", "pub-trigger"), bonus = 100, abTest = RuleAbTest("B", 50),
            card = RuleCard(offerRank = 0, bonusInherited = true), impressions = 9))
        val initial = state()
        val pending = save(scripted(initial.bundle, "Must not overwrite variants"))
        assertThat(pending.canApply).isFalse()
        request("POST", "/api/admin/promo/pub-promo/changes/apply", ApplyPromoChangesRequest(pending.revision))
            .andExpect(status().isConflict)
        request("PUT", "/api/admin/promo/pub-promo/rules", initial.bundle.rules).andExpect(status().isConflict)
        val variants = rules.findAll().sortedBy { it.id }
        assertThat(variants).hasSize(2)
        assertThat(variants.map { it.abTest?.variant }).containsExactlyInAnyOrder("A", "B")
        assertThat(variants).allSatisfy { assertThat(it.retiredAt).isNull(); assertThat(it.status).isEqualTo(RuleStatus.active) }
        assertThat(variants.first { it.id == "pub-variant-b" }.impressions).isEqualTo(9)
        assertThat(jdbc.queryForObject("select count(*) from promo_publication_history", Long::class.java)).isZero()
    }

    @Test fun `unrepresented trigger exclusions cannot be dropped by a campaign publication`() {
        rules.findById("pub-rule").orElseThrow().also {
            it.trigger = it.trigger.copy(exclude = listOf("pub-extra")); rules.saveAndFlush(it)
        }
        val pending = save(scripted(state().bundle, "Must preserve exclusions"))
        assertThat(pending.canApply).isFalse()
        request("POST", "/api/admin/promo/pub-promo/changes/apply", ApplyPromoChangesRequest(pending.revision))
            .andExpect(status().isConflict)
        assertThat(rules.findById("pub-rule").orElseThrow().trigger.exclude).containsExactly("pub-extra")
        assertThat(rules.findById("pub-rule").orElseThrow().retiredAt).isNull()
    }

    @Test fun `managed publisher cannot bypass saved revision through legacy campaign or linked rule writes`() {
        grants(*(allGrants() + grant("campaigns", "create")).toTypedArray())
        request("PATCH", "/api/admin/promo/pub-promo", mapOf("pharmacistBonus" to 300)).andExpect(status().isConflict)
        val config = scripted(state().bundle, "Must not publish directly").rules
        request("PUT", "/api/admin/promo/pub-promo/rules", config).andExpect(status().isConflict)
        val forgedFlags = json.convertValue(config, Map::class.java) + mapOf("allowManaged" to true, "replaceNullableIdentity" to true)
        request("PUT", "/api/admin/promo/pub-promo/rules", forgedFlags).andExpect(status().isConflict)
        request("PATCH", "/api/admin/rules/pub-rule", mapOf("script" to "Must not change")).andExpect(status().isConflict)
        for (status in listOf("active", "paused")) {
            request("POST", "/api/admin/promo", mapOf("title" to "Must start as draft", "status" to status,
                "medusaProductId" to "pub-extra", "productName" to "Synthetic extra")).andExpect(status().isConflict)
        }
        rules.findById("pub-rule").orElseThrow().also { it.status = RuleStatus.draft; rules.saveAndFlush(it) }
        request("PATCH", "/api/admin/rules/pub-rule", mapOf("status" to "active")).andExpect(status().isConflict)
        assertThat(promos.findById("pub-promo").orElseThrow().pharmacistBonus).isEqualTo(100)
        assertThat(rules.findById("pub-rule").orElseThrow().script).isEqualTo("Published script")
        assertThat(rules.findById("pub-rule").orElseThrow().status).isEqualTo(RuleStatus.draft)
        assertThat(state().hasPendingChanges).isFalse()
        assertThat(jdbc.queryForObject("select count(*) from promo_publication_history", Long::class.java)).isZero()
    }

    @Test fun `managed pause and resume are status only and never normalize financial tiers`() {
        val tiers = listOf(PromoTier(1, 2000, 100), PromoTier(10, 1800, 200))
        promos.findById("pub-promo").orElseThrow().also { it.tiers = tiers; promos.saveAndFlush(it) }
        grants(*allGrants().filter { it.action != "configure_bonus" }.toTypedArray())
        request("PATCH", "/api/admin/promo/pub-promo", mapOf("status" to "paused")).andExpect(status().isOk)
        assertThat(promos.findById("pub-promo").orElseThrow().tiers).isEqualTo(tiers)
        request("PATCH", "/api/admin/promo/pub-promo", mapOf("status" to "active")).andExpect(status().isOk)
        assertThat(promos.findById("pub-promo").orElseThrow().tiers).isEqualTo(tiers)
        request("PATCH", "/api/admin/rules/pub-rule", mapOf("status" to "paused")).andExpect(status().isOk)
        assertThat(rules.findById("pub-rule").orElseThrow().status).isEqualTo(RuleStatus.paused)
        assertThat(rules.findById("pub-rule").orElseThrow().bonus).isEqualTo(100)
    }

    @Test fun `unchanged paid terms can publish text through saved revision without financial permission`() {
        grants(*allGrants().filter { it.action != "configure_bonus" }.toTypedArray())
        val pending = save(scripted(state().bundle, "Reviewed ordinary text"))
        assertThat(pending.canApply).isTrue()
        apply(pending.revision)
        assertThat(rules.findById("pub-rule").orElseThrow().bonus).isEqualTo(100)
        assertThat(rules.findById("pub-rule").orElseThrow().script).isEqualTo("Reviewed ordinary text")
    }

    @Test fun `legacy account retains direct campaign and rule CRUD during controlled conversion`() {
        jdbc.update("update admin_users set access_mode='LEGACY' where id=?", admin.id)
        request("PATCH", "/api/admin/promo/pub-promo", mapOf("title" to "Legacy title", "pharmacistBonus" to 150))
            .andExpect(status().isOk)
        request("PUT", "/api/admin/promo/pub-promo/rules", scripted(state().bundle, "Legacy script").rules)
            .andExpect(status().isOk)
        assertThat(promos.findById("pub-promo").orElseThrow().title).isEqualTo("Legacy title")
        assertThat(rules.findById("pub-rule").orElseThrow().bonus).isEqualTo(150)
        assertThat(rules.findById("pub-rule").orElseThrow().script).isEqualTo("Legacy script")
        assertThat(jdbc.queryForObject("select count(*) from promo_publication_history", Long::class.java)).isZero()
    }

    @Test fun `new campaign and both standalone and generated rule kinds use full opaque UUIDs without rewriting old IDs`() {
        grants(*(allGrants() + grant("campaigns", "create") + grant("recommendations", "create")).toTypedArray())
        val campaignIds = (1..2).map { index ->
            val response = request("POST", "/api/admin/promo", mapOf("title" to "Synthetic draft $index"))
                .andExpect(status().isOk).andReturn().response
            json.readTree(response.contentAsByteArray)["id"].asText()
        }
        assertThat(campaignIds).doesNotHaveDuplicates().allSatisfy { assertThat(it).matches("pr_[0-9a-f]{32}") }
        val standaloneIds = listOf("substitution" to "s", "crosssell" to "x").map { (type, mark) ->
            val response = request("POST", "/api/admin/rules", mapOf("type" to type, "status" to "draft",
                "trigger" to mapOf("kind" to "product", "value" to "pub-trigger"), "recommend" to "pub-extra"))
                .andExpect(status().isOk).andReturn().response
            json.readTree(response.contentAsByteArray)["id"].asText().also { assertThat(it).matches("r_${mark}_[0-9a-f]{32}") }
        }
        val bundle = state().bundle.let { current -> current.copy(rules = current.rules.copy(
            replacements = current.rules.replacements.map { it.copy(additionalRecommendations = listOf(
                PromoOfferProductRefDto("pub-extra", name = "Synthetic extra", price = 3000, bonus = 0))) },
            crossSells = listOf(PromoRuleProductRefDto("pub-trigger-2", name = "Second trigger", barcode = SECOND_BARCODE, bonus = 0)),
        )) }
        apply(save(bundle).revision)
        val generated = rules.findAll().filter { it.promoId == "pub-promo" && it.id != "pub-rule" }
        assertThat(generated).hasSize(2)
        assertThat(generated.map { it.id }).doesNotHaveDuplicates().doesNotContainAnyElementsOf(standaloneIds)
        generated.forEach { assertThat(it.id).matches("r_${if (it.type == RuleType.substitution) "s" else "x"}_[0-9a-f]{32}") }
        assertThat(promos.existsById("pub-promo")).isTrue()
        assertThat(rules.existsById("pub-rule")).isTrue()
    }

    @Test fun `same open receipt keeps its issued financial quote after Apply and catalog price changes`() {
        staff()
        val old = recommend("quote-open", "pub-staff").recommendations.single()
        assertThat(old.eventId).matches("rec_[0-9a-f]{32}")
        val bundle = state().bundle.let { it.copy(campaign = it.campaign.copy(pharmacistBonus = 250)) }
        apply(save(bundle).revision)
        products.findById("pub-offer").orElseThrow().also { it.price = 2200; products.saveAndFlush(it) }
        val repeated = recommend("quote-open", "pub-staff").recommendations.single()
        assertThat(repeated.eventId).isEqualTo(old.eventId)
        assertThat(repeated.bonus).isEqualTo(100)
        assertThat(repeated.recommendPrice).isEqualTo(2000)
        val fresh = recommend("quote-next", "pub-staff").recommendations.single()
        assertThat(fresh.eventId).isNotEqualTo(old.eventId)
        assertThat(fresh.bonus).isEqualTo(250)
        assertThat(fresh.recommendPrice).isEqualTo(2200)
        val displayed = Instant.now().truncatedTo(ChronoUnit.MICROS)
        shown(old.eventId, displayed)
        shown(old.eventId, displayed.plusSeconds(1))
        assertThat(jdbc.queryForObject("select displayed_at from recommendation_events where id=?", java.sql.Timestamp::class.java, old.eventId)?.toInstant())
            .isEqualTo(displayed)
        val pending = accept(old.eventId).pendingBonusId!!
        assertThat(accept(old.eventId).pendingBonusId).isEqualTo(pending)
        assertThat(pending).matches("pb_[0-9a-f]{32}")
        assertThat(jdbc.queryForObject("select bonus from pending_bonuses where id=?", Long::class.java, pending)).isEqualTo(100)
        assertThat(jdbc.queryForObject("select expected_amount from pending_bonuses where id=?", Long::class.java, pending)).isEqualTo(2000)
        val nextPending = accept(fresh.eventId).pendingBonusId!!
        assertThat(nextPending).isNotEqualTo(pending)
        assertThat(jdbc.queryForObject("select bonus from pending_bonuses where id=?", Long::class.java, nextPending)).isEqualTo(250)
        assertThat(jdbc.queryForObject("select count(*) from pending_bonuses", Long::class.java)).isEqualTo(2)
        assertThat(pharmacists.findById("pub-staff").orElseThrow().balance).isZero()
        assertThat(jdbc.queryForObject("select count(*) from pharmacist_wallet_entries", Long::class.java)).isZero()
    }

    @Test fun `old queued shown and outcome remain idempotent after its rule retires`() {
        staff()
        val old = recommend("retired-open", "pub-staff").recommendations.single()
        val changed = state().bundle.let { it.copy(campaign = it.campaign.copy(pharmacistBonus = 250), rules = it.rules.copy(
            replacements = it.rules.replacements.map { ref -> ref.copy(medusaProductId = "pub-trigger-2", barcode = SECOND_BARCODE) })) }
        apply(save(changed).revision)
        assertThat(rules.findById("pub-rule").orElseThrow().retiredAt).isNotNull()
        shown(old.eventId, Instant.now())
        val pending = accept(old.eventId).pendingBonusId!!
        assertThat(accept(old.eventId).pendingBonusId).isEqualTo(pending)
        assertThat(jdbc.queryForObject("select bonus from pending_bonuses where id=?", Long::class.java, pending)).isEqualTo(100)
        assertThat(jdbc.queryForObject("select rule_id from pending_bonuses where id=?", String::class.java, pending)).isEqualTo("pub-rule")
        assertThat(jdbc.queryForObject("select r.promo_id from pending_bonuses p join rules r on r.id=p.rule_id where p.id=?", String::class.java, pending))
            .isEqualTo("pub-promo")
        assertThat(jdbc.queryForObject("select count(*) from pending_bonuses", Long::class.java)).isEqualTo(1)
        assertThat(recommend("retired-new", "pub-staff").recommendations).isEmpty()
        assertThat(recommend("retired-next", "pub-staff", SECOND_BARCODE).recommendations.single().bonus).isEqualTo(250)
    }

    @Test fun `external recommendation also keeps its issued quote but next receipt receives current terms`() {
        val candidate = MerchRecommendationCandidate(id = "11111111-1111-4111-8111-111111111111", kind = "crosssell",
            triggerBarcode = BARCODE, recommendProductId = "pub-extra", bonus = 100)
        every { merch.recommendationContext(any()) } returns MerchRecommendationContextEnvelope(recommendations = listOf(candidate))
        val old = recommend("external-open").recommendations.single { it.source == "merchandising" }
        assertThat(old.eventId).matches("rec_[0-9a-f]{32}")
        every { merch.recommendationContext(any()) } returns MerchRecommendationContextEnvelope(recommendations = listOf(candidate.copy(bonus = 250)))
        products.findById("pub-extra").orElseThrow().also { it.price = 3500; products.saveAndFlush(it) }
        val repeated = recommend("external-open").recommendations.single { it.source == "merchandising" }
        assertThat(repeated.eventId).isEqualTo(old.eventId)
        assertThat(repeated.bonus).isEqualTo(100)
        assertThat(repeated.recommendPrice).isEqualTo(3000)
        val fresh = recommend("external-next").recommendations.single { it.source == "merchandising" }
        assertThat(fresh.eventId).isNotEqualTo(old.eventId)
        assertThat(fresh.bonus).isEqualTo(250)
        assertThat(fresh.recommendPrice).isEqualTo(3500)
    }

    @Test fun `out of range historical money fails closed instead of overflowing or clamping the issued quote`() {
        val old = recommend("historical-overflow").recommendations.single()
        for (column in listOf("bonus", "expected_amount")) {
            // Column names are fixed test literals, never request input.
            jdbc.update("update recommendation_events set $column=? where id=?", Int.MAX_VALUE.toLong() + 1, old.eventId)
            posm("/api/posm/recommend", mapOf("pharmacyId" to "pub-pharmacy", "sessionId" to "historical-overflow",
                "cart" to listOf(mapOf("barcode" to BARCODE))))
                .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONFLICT"))
            assertThat(jdbc.queryForObject("select $column from recommendation_events where id=?", Long::class.java, old.eventId))
                .isEqualTo(Int.MAX_VALUE.toLong() + 1)
            jdbc.update("update recommendation_events set $column=? where id=?", if (column == "bonus") 100 else 2000, old.eventId)
        }
        assertThat(jdbc.queryForObject("select count(*) from recommendation_events", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from pending_bonuses", Long::class.java)).isZero()
    }

    @Test fun `late old outcome cannot adopt a new financial reservation when old shown ACK arrives first`() =
        saleFirstReservation(oldAckFirst = true, changedBonus = true)

    @Test fun `late old outcome cannot adopt a new financial reservation when old shown ACK arrives last`() =
        saleFirstReservation(oldAckFirst = false, changedBonus = true)

    @Test fun `equivalent same SKU reservations still deduplicate across different rules`() =
        saleFirstReservation(oldAckFirst = false, changedBonus = false)

    private fun saleFirstReservation(oldAckFirst: Boolean, changedBonus: Boolean) {
        staff(); offeredBarcode()
        val session = "sale-reservation"
        val old = recommend(session, "pub-staff").recommendations.single()
        val changed = state().bundle.let { current -> current.copy(
            campaign = current.campaign.copy(pharmacistBonus = if (changedBonus) 250 else 100),
            rules = current.rules.copy(replacements = current.rules.replacements +
                PromoRuleProductRefDto("pub-trigger-2", name = "Second trigger", barcode = SECOND_BARCODE)),
        ) }
        apply(save(changed).revision)
        val newer = recommend(session, "pub-staff", SECOND_BARCODE).recommendations.single()
        assertThat(newer.ruleId).isNotEqualTo(old.ruleId)
        assertThat(newer.eventId).isNotEqualTo(old.eventId)
        val t1 = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS)
        if (oldAckFirst) { shown(old.eventId, t1); shown(newer.eventId, t1.plusSeconds(1)) }
        else { shown(newer.eventId, t1.plusSeconds(1)); shown(old.eventId, t1) }
        sale(session, t1.plusSeconds(2))
        val pending = jdbc.queryForObject("select id from pending_bonuses", String::class.java)!!
        assertThat(jdbc.queryForObject("select bonus from pending_bonuses where id=?", Long::class.java, pending))
            .isEqualTo(if (changedBonus) 250L else 100L)
        assertThat(jdbc.queryForObject("select rule_id from pending_bonuses where id=?", String::class.java, pending)).isEqualTo(newer.ruleId)
        if (changedBonus) {
            posm("/api/posm/recommendations/${old.eventId}/outcome", mapOf("outcome" to "accepted", "finalSku" to "pub-offer"))
                .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CONFLICT"))
            assertThat(jdbc.queryForObject("select outcome from recommendation_events where id=?", String::class.java, old.eventId)).isEqualTo("shown")
            assertThat(jdbc.queryForObject("select pending_bonus_id from recommendation_events where id=?", String::class.java, old.eventId)).isNull()
        } else {
            assertThat(accept(old.eventId).pendingBonusId).isEqualTo(pending)
        }
        sale(session, t1.plusSeconds(2))
        shown(old.eventId, t1)
        assertThat(jdbc.queryForObject("select count(*) from pos_sales", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from pending_bonuses", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select bonus from pending_bonuses where id=?", Long::class.java, pending))
            .isEqualTo(if (changedBonus) 250L else 100L)
        assertThat(pharmacists.findById("pub-staff").orElseThrow().balance).isZero()
        assertThat(jdbc.queryForObject("select count(*) from pharmacist_wallet_entries", Long::class.java)).isZero()
    }
}
