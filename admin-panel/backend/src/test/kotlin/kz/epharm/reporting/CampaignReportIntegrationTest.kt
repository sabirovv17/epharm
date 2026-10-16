package kz.epharm.reporting

import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.repository.RuleRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Transactional
class CampaignReportIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_test").withUsername("epharm").withPassword("test-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }
    @Autowired lateinit var products: ProductRepository
    @Autowired lateinit var promos: PromoRepository
    @Autowired lateinit var rules: RuleRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var service: CampaignReportService
    @Autowired lateinit var mvc: MockMvc
    private lateinit var campaign: String
    private lateinit var rule: String
    private lateinit var sku: String
    private val from=LocalDate.parse("2025-01-01")

    @BeforeEach fun setup() {
        val suffix=UUID.randomUUID().toString()
        sku="p_$suffix";campaign="pr_$suffix";rule="r_$suffix"
        products.saveAndFlush(ProductEntity(id=sku,name="DEMO продукт"))
        promos.saveAndFlush(PromoEntity(id=campaign,title="DEMO кампания",brand="DEMO"))
        rules.saveAndFlush(RuleEntity(id=rule,recommend=sku,promoId=campaign))
    }
    private fun event(at: String="2025-01-01T00:00:00Z",display: String?=null,outcome: String="shown",decision: String?=null,kind: String="substitution",sale: String?=null,sold: String?=null,ruleId: String=rule): String {
        val id=UUID.randomUUID().toString()
        jdbc.update("INSERT INTO recommendation_events(id,session_id,pharmacist_id,pharmacy_id,rule_id,kind,recommend_sku,shown_at,displayed_at,outcome,decided_at,sale_id,sold_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,"session","", "pharmacy",ruleId,kind,sku,Timestamp.from(Instant.parse(at)),display?.let { Timestamp.from(Instant.parse(it)) },outcome,decision?.let { Timestamp.from(Instant.parse(it)) },sale,sold?.let { Timestamp.from(Instant.parse(it)) })
        return id
    }
    private fun sale(pharmacy: String="pharmacy",session: String="session",at: String="2025-01-01T01:00:00Z"): String {
        val id=UUID.randomUUID().toString()
        jdbc.update("INSERT INTO pos_sales(id,session_id,pharmacist_id,pharmacy_id,printed_at) VALUES (?,?,?,?,?)",id,session,"",pharmacy,Timestamp.from(Instant.parse(at)))
        return id
    }

    @Test fun `counts actual display cohort distinct from issued and validates sale identity before deduplication`() {
        event() // issued only
        event(display="2025-01-01T00:00:00Z",outcome="accepted",decision="2025-01-01T00:01:00Z")
        event(display="2025-01-01T02:00:00Z",kind="crosssell",outcome="rejected",decision="2025-01-01T02:01:00Z")
        val sale=sale()
        event(display="2025-01-01T00:30:00Z",sale=sale,sold="2025-01-01T01:00:00Z")
        event(display="2025-01-01T00:31:00Z",sale=sale,sold="2025-01-01T01:00:00Z")
        val wrong=sale(pharmacy="another")
        event(display="2025-01-01T03:00:00Z",sale=wrong,sold="2025-01-01T01:00:00Z")
        val wrongSession=sale(session="another")
        event(display="2025-01-01T04:00:00Z",sale=wrongSession,sold="2025-01-01T01:00:00Z")
        // Display before Almaty midnight is outside; shown_at alone must not fabricate display.
        event(at="2024-12-31T18:59:59Z",display="2024-12-31T18:59:59Z")
        // A current rule without this campaign never contributes.
        event(display="2025-01-01T00:00:00Z",ruleId="unmapped-rule")
        val report=service.report(campaign,from,from)
        assertEquals(7,report.totals.issued)
        assertEquals(1,report.totals.unconfirmed)
        assertEquals(6,report.totals.confirmed)
        assertEquals(1,report.totals.pharmacistAccepted)
        assertEquals(1,report.totals.attributedSales)
        assertEquals(2,report.totals.unverifiedSaleClaims)
        assertEquals(1,report.kinds.last().counts.confirmed)
    }

    @Test fun `end boundary and late decisions or sales never change historical cohort results`() {
        val sold=sale(at="2025-01-01T19:00:00Z")
        event(display="2025-01-01T18:59:59Z",outcome="accepted",decision="2025-01-01T19:00:00Z",sale=sold,sold="2025-01-01T19:00:00Z")
        event(display="2025-01-01T19:00:00Z")
        val report=service.report(campaign,from,from)
        assertEquals(1,report.totals.confirmed)
        assertEquals(0,report.totals.pharmacistAccepted)
        assertEquals(0,report.totals.attributedSales)
        assertEquals(1,report.totals.unconfirmed)
    }

    @Test fun `all confirmed substitutions stay distinct from zero to 120 second sales with the same dedup winner`() {
        val exact=sale(at="2025-01-01T00:02:00Z")
        event(display="2025-01-01T00:00:00Z",outcome="accepted",decision="2025-01-01T00:00:10Z",sale=exact,sold="2025-01-01T00:02:00Z")
        event(display="2025-01-01T00:00:01Z",sale=exact,sold="2025-01-01T00:02:00Z")
        val over=sale(at="2025-01-01T00:02:00.001Z")
        event(display="2025-01-01T00:00:00Z",outcome="accepted",decision="2025-01-01T00:00:10Z",sale=over,sold="2025-01-01T00:02:00.001Z")
        // The most recent event is fast, but the accepted attribution winner must stay unchanged.
        event(display="2025-01-01T00:02:00Z",sale=over,sold="2025-01-01T00:02:00.001Z")
        val skew=sale(at="2025-01-01T00:04:00Z")
        val skewEvent=event(display="2025-01-01T00:04:01Z",sale=skew,sold="2025-01-01T00:04:00Z")
        jdbc.update("UPDATE recommendation_events SET seconds_to_sale=0 WHERE id=?",skewEvent)
        val zero=sale(at="2025-01-01T00:05:00Z")
        event(display="2025-01-01T00:05:00Z",kind="crosssell",sale=zero,sold="2025-01-01T00:05:00Z")
        event(display="2025-01-01T00:06:00Z",outcome="accepted",decision="2025-01-01T00:06:10Z")
        val noAck=sale(at="2025-01-01T00:07:00Z")
        event(outcome="accepted",decision="2025-01-01T00:06:10Z",sale=noAck,sold="2025-01-01T00:07:00Z")
        val report=service.report(campaign,from,from)
        assertEquals(4,report.totals.attributedSales)
        assertEquals(2,report.totals.recommendationSalesUnder2m)
        assertEquals(1,report.confirmedSubstitutionSalesUnder2m)
        assertEquals(3,report.confirmedSubstitutionSales)
        assertEquals(28.6,report.totals.under2mConversionPercent)
        assertEquals(57.1,report.totals.attributedConversionPercent)
        assertEquals(3,report.totals.pharmacistAccepted)
        assertEquals(1,report.totals.unconfirmed)
        assertEquals(2,report.days.single().counts.recommendationSalesUnder2m)
        assertEquals(1,report.kinds.last().counts.recommendationSalesUnder2m)
    }

    @Test fun `report access denies non HQ roles for both routes and requires explicit dates`() {
        val route="/api/admin/reports/campaigns/$campaign"
        mvc.perform(get(route).param("from",from.toString()).param("to",from.toString())).andExpect(status().isUnauthorized)
        mvc.perform(get("$route/pdf").param("from",from.toString()).param("to",from.toString())).andExpect(status().isUnauthorized)
        for (role in listOf("BRAND_MANAGER","CATEGORY_LEAD","FINANCE_REVIEWER","PHARMACIST","TRAINING_MANAGER")) {
            for (suffix in listOf("","/pdf")) mvc.perform(get(route+suffix).with(user("test").roles(role)).param("from",from.toString()).param("to",from.toString())).andExpect(status().isForbidden)
        }
        for (role in listOf("HQ_HEAD","SYSTEM_ADMIN")) mvc.perform(get(route).with(user("test").roles(role)).param("from",from.toString()).param("to",from.toString()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.confirmedSubstitutionSales").value(0))
            .andExpect(jsonPath("$.confirmedSubstitutionSalesUnder2m").value(0))
            .andExpect(jsonPath("$.totals.attributedConversionPercent").isEmpty())
            .andExpect(jsonPath("$.totals.under2mConversionPercent").isEmpty())
        mvc.perform(get(route).with(user("test").roles("HQ_HEAD"))).andExpect(status().isBadRequest)
    }

    @Test fun `HQ PDF endpoint returns real private downloadable PDF`() {
        val result=mvc.perform(get("/api/admin/reports/campaigns/$campaign/pdf")
            .with(user("test").roles("HQ_HEAD")).param("from",from.toString()).param("to",from.toString()))
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/pdf"))
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("Content-Disposition","attachment; filename=campaign-report-$from-$from.pdf"))
            .andReturn()
        assertTrue(result.response.contentAsByteArray.take(5).toByteArray().contentEquals("%PDF-".toByteArray()))
    }
}
