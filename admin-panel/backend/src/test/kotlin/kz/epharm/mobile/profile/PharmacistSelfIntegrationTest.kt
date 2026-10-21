package kz.epharm.mobile.profile

import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.service.JwtService
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleCard
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.entity.RuleType
import kz.epharm.rules.repository.RuleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import javax.crypto.spec.SecretKeySpec

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class PharmacistSelfIntegrationTest {
    companion object {
        private const val TEST_SECRET = "test-secret-key-of-at-least-32-bytes-for-hs256-tests-only-please"
        private val PATHS = listOf("/api/mobile/me/balance", "/api/mobile/me/recommendations")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("self_api_test").withUsername("epharm").withPassword("epharm_test")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var jwt: JwtService
    @Autowired private lateinit var pharmacists: PharmacistRepository
    @Autowired private lateinit var rules: RuleRepository
    @Autowired private lateinit var products: ProductRepository
    @Autowired private lateinit var promos: PromoRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var json: ObjectMapper

    private lateinit var account: PharmacistEntity
    private lateinit var bearer: String

    @BeforeEach
    fun seed() {
        account = pharmacists.saveAndFlush(PharmacistEntity(
            id = "self_a", name = "Self A", phone = "+77001110001", iin = "000000000001",
        ).also { it.status = PharmacistStatus.active; it.balance = 9_000_000_001 })
        pharmacists.saveAndFlush(PharmacistEntity(
            id = "self_b", name = "Self B", phone = "+77001110002", iin = "000000000002",
        ).also { it.status = PharmacistStatus.active; it.balance = 42_000 })
        bearer = "Bearer " + jwt.issuePharmacistToken(account.id, account.name, account.phone)
    }

    @Test
    fun `signed subject alone selects wallet and spoofed user selectors cannot switch owner`() {
        mvc.perform(get(PATHS[0]).header(HttpHeaders.AUTHORIZATION, bearer)
            .param("pharmacistId", "self_b").param("phone", "+77001110002").param("pharmacyId", "other")
            .header("X-Pharmacist-Id", "self_b"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.balanceKzt").value(9_000_000_001L))
            .andExpect(jsonPath("$.currency").value("KZT"))
            .andExpect(jsonPath("$.phone").doesNotExist())
            .andExpect(jsonPath("$.iin").doesNotExist())
            .andExpect(jsonPath("$.id").doesNotExist())
        val other = jwt.issuePharmacistToken("self_b", "Self B", "+77001110002")
        mvc.perform(get(PATHS[0]).header(HttpHeaders.AUTHORIZATION, "Bearer $other"))
            .andExpect(status().isOk).andExpect(jsonPath("$.balanceKzt").value(42_000))
    }

    @Test
    fun `no token device key admin and foreign provider tokens never grant self access`() {
        val expired = jwt.issuePharmacistToken(account.id, account.name, account.phone, Instant.now().minusSeconds(3600))
        val foreign = JwtService("foreign-provider-key-longer-than-thirty-two-bytes", 15)
            .issuePharmacistToken(account.id, account.name, account.phone)
        val adminTokens = listOf(AdminRole.BRAND_MANAGER, AdminRole.TRAINING_MANAGER).map { role ->
            jwt.issueAccessToken(AdminUserEntity().also { it.role = role })
        }
        val key = SecretKeySpec(TEST_SECRET.toByteArray(), "HmacSHA256")
        val unsupported = Jwts.builder().subject(account.id).compact()
        val premature = Jwts.builder().subject(account.id).claim("typ", "pharmacist")
            .claim("name", "Self A").claim("phone", account.phone)
            .notBefore(Date.from(Instant.now().plusSeconds(3600)))
            .expiration(Date.from(Instant.now().plusSeconds(7200))).signWith(key).compact()
        val malformedClaims = Jwts.builder().subject(account.id).claim("typ", "pharmacist")
            .claim("name", 123).claim("phone", account.phone)
            .expiration(Date.from(Instant.now().plusSeconds(300))).signWith(key).compact()
        for (path in PATHS) {
            mvc.perform(get(path).header("X-Posm-Key", "test-device-not-a-user"))
                .andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            for (token in listOf(expired, foreign, "invalid.jwt.token", "opaque-refresh-token", unsupported, premature, malformedClaims)) {
                mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
                    .andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            }
            for (admin in adminTokens) {
                mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer $admin"))
                    .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("FORBIDDEN"))
            }
        }
    }

    @Test
    fun `previously issued JWT cannot read after account block pending or deletion`() {
        for (state in listOf(PharmacistStatus.blocked, PharmacistStatus.pending)) {
            account.status = state
            pharmacists.saveAndFlush(account)
            for (path in PATHS) mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("USER_NOT_ACTIVE"))
        }
        pharmacists.delete(account)
        pharmacists.flush()
        for (path in PATHS) mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
    }

    @Test
    fun `feed reads current rule and campaign status without creating impressions or wallet entries`() {
        val today = LocalDate.now(ZoneId.of("Asia/Almaty"))
        val campaign = promos.saveAndFlush(PromoEntity(id = "self_promo", title = "Test campaign").also {
            it.status = PromoStatus.active; it.dateStart = today; it.dateEnd = today
        })
        products.saveAllAndFlush(listOf(
            ProductEntity(id = "self_trigger", name = "Trigger", barcode = "4600000000008"),
            ProductEntity(id = "self_offer", name = "Offer", price = 2500, barcode = "4600000000015"),
        ))
        val rule = rules.saveAndFlush(RuleEntity(
            id = "self_rule", promoId = campaign.id, recommend = "self_offer", bonus = 500,
            trigger = RuleTrigger("product", "self_trigger"), card = RuleCard(offerRank = 0),
        ).also { it.status = RuleStatus.active })
        val ledgerBefore = jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries", Long::class.java)
        val response = mvc.perform(get(PATHS[1]).header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].trigger.kind").value("product"))
            .andExpect(jsonPath("$.items[0].triggerProducts[0].name").value("Trigger"))
            .andExpect(jsonPath("$.items[0].offers[0].product.catalogPriceKzt").value(2500))
            .andExpect(jsonPath("$.items[0].offers[0].bonusKzt").value(500))
            .andReturn().response.contentAsString
        assertThat(json.readTree(response)["items"][0].has("createdBy")).isFalse()
        assertThat(rules.findById(rule.id).orElseThrow().impressions).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries", Long::class.java)).isEqualTo(ledgerBefore)
        assertThat(pharmacists.findById(account.id).orElseThrow().balance).isEqualTo(9_000_000_001)

        campaign.status = PromoStatus.paused
        promos.saveAndFlush(campaign)
        mvc.perform(get(PATHS[1]).header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk).andExpect(jsonPath("$.total").value(0))
        campaign.status = PromoStatus.active
        promos.saveAndFlush(campaign)
        rule.status = RuleStatus.draft
        rules.saveAndFlush(rule)
        mvc.perform(get(PATHS[1]).header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk).andExpect(jsonPath("$.total").value(0))
    }

    @Test
    fun `persisted ACC triggers and product aliases remain separate from offers in both types`() {
        products.saveAllAndFlush(listOf(
            ProductEntity(id = "self_trigger", name = "Trigger", barcode = "4600000000008",
                barcodes = listOf("4600000000015")),
            ProductEntity(id = "self_offer", name = "Offer"),
        ))
        val triggers = listOf(
            RuleTrigger("product", "self_trigger"),
            RuleTrigger("product_any", listOf("self_trigger"), exclude = listOf("self_offer")),
            RuleTrigger("acc_group", "group_key", label = "Group"),
            RuleTrigger("acc_subgroup", "subgroup_key", label = "Subgroup"),
            RuleTrigger("acc_mnn", "mnn_key", label = "INN"),
        )
        rules.saveAllAndFlush(triggers.flatMapIndexed { index, trigger ->
            RuleType.entries.map { type ->
                RuleEntity(id = "self_${index}_${type.name}", trigger = trigger, recommend = "self_offer")
                    .also { it.status = RuleStatus.active; it.type = type }
            }
        })
        val response = mvc.perform(get(PATHS[1]).header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk).andExpect(jsonPath("$.total").value(10))
            .andReturn().response.contentAsString
        val groups = json.readTree(response)["items"].toList()
        assertThat(groups.map { it["trigger"]["kind"].asText() }.toSet())
            .containsExactlyInAnyOrder("product", "product_any", "acc_group", "acc_subgroup", "acc_mnn")
        assertThat(groups.filter { it["trigger"]["kind"].asText() == "product" }
            .flatMap { it["triggerProducts"][0]["barcodes"].map { code -> code.asText() } })
            .contains("4600000000008", "4600000000015")
        assertThat(groups.first { it["trigger"]["kind"].asText() == "product_any" }
            ["trigger"]["exclude"][0].asText()).isEqualTo("self_offer")
        assertThat(groups.map { it["offers"][0]["product"]["id"].asText() }.toSet()).containsExactly("self_offer")
    }

    @Test
    fun `pagination validation and empty feed use stable JSON contracts`() {
        for (query in listOf("?page=-1", "?size=0", "?size=101", "?page=wrong")) {
            mvc.perform(get(PATHS[1] + query).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        }
        mvc.perform(get(PATHS[1]).header(HttpHeaders.AUTHORIZATION, bearer).param("page", Int.MAX_VALUE.toString()))
            .andExpect(status().isOk).andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.currency").value("KZT"))
    }
}
