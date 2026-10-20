package kz.epharm.posm

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityManager
import java.math.BigDecimal
import java.time.Instant
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.merchtasks.dto.MerchProductBalance
import kz.epharm.merchtasks.dto.MerchRecommendationCandidate
import kz.epharm.merchtasks.dto.MerchRecommendationContextEnvelope
import kz.epharm.merchtasks.service.MerchTaskClient
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.posm.dto.RecommendRequest
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.posm.service.RecommendationService
import kz.epharm.posm.service.RuleMatchResult
import kz.epharm.posm.service.RulesEngineService
import kz.epharm.receipts.service.PendingBonusService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class MerchRecommendationMergeTest {
    private val rulesEngine = mockk<RulesEngineService>()
    private val events = mockk<RecommendationEventRepository>()
    private val pendingBonusService = mockk<PendingBonusService>()
    private val jdbc = mockk<JdbcTemplate>()
    private val entityManager = mockk<EntityManager>()
    private val merch = mockk<MerchTaskClient>()
    private val products = mockk<ProductRepository>()
    private lateinit var service: RecommendationService

    private val trigger = ProductEntity(
        id = "p-trigger", name = "Исходный препарат", brand = "B", vendor = "V", mnn = "M",
        price = 1_000, barcode = "4600000000018",
    )
    private val recommended = ProductEntity(
        id = "p-recommended", name = "Рекомендуемый препарат", brand = "B", vendor = "V", mnn = "M",
        price = 1_500, barcode = "4600000000025",
    )

    @BeforeEach
    fun setUp() {
        service = RecommendationService(
            rulesEngine, events, pendingBonusService, jdbc, entityManager, merch, products,
        )
        every { events.findAllBySessionId(any()) } returns emptyList()
        every { rulesEngine.match(any(), any()) } returns RuleMatchResult(emptyList(), emptyList())
        every { products.findAllById(any<Iterable<String>>()) } returns listOf(recommended)
        every { products.findAllByBarcodeIn(any()) } returns listOf(trigger)
        every { events.findFirstBySessionIdAndRuleIdAndTriggerSkuOrderByShownAtDesc(any(), any(), any()) } returns null
        every { events.save(any()) } answers { firstArg() }
    }

    @Test
    fun `external offer becomes a normal ePharm event and carries shared stock`() {
        every { merch.recommendationContext(any()) } returns context(pharmacyQty = BigDecimal("4"))

        val response = service.recommend(request())

        val recommendation = response.recommendations.single()
        assertThat(recommendation.source).isEqualTo("merchandising")
        assertThat(recommendation.ruleId).isEqualTo("merch:8ffafcf4-f0a0-4662-bcd2-1dd3c11bd888")
        assertThat(recommendation.recommendSku).isEqualTo(recommended.id)
        assertThat(recommendation.balance?.pharmacyAvailableQty).isEqualByComparingTo("4")
        assertThat(recommendation.balance?.networkAvailableQty).isNull()
        assertThat(recommendation.balance?.warehouseAvailableQty).isEqualByComparingTo("120")
        verify(exactly = 1) {
            events.save(match {
                it.ruleId.startsWith("merch:") && it.recommendSku == recommended.id && it.bonus == 320L
            })
        }
    }

    @Test
    fun `confirmed zero pharmacy stock suppresses the offer without inventing a sale event`() {
        every { merch.recommendationContext(any()) } returns context(pharmacyQty = BigDecimal.ZERO)

        val response = service.recommend(request())

        assertThat(response.recommendations).isEmpty()
        assertThat(response.conflicts).hasSize(1)
        val conflict = response.conflicts.single()
        assertThat(conflict.kind).isEqualTo("unavailable")
        assertThat(conflict.ruleIds).containsExactly("merch:8ffafcf4-f0a0-4662-bcd2-1dd3c11bd888")
        verify(exactly = 0) { events.save(any()) }
    }

    private fun request() = RecommendRequest(
        pharmacistId = "u_t",
        pharmacyId = "ph_t",
        sessionId = "sale-1",
        cart = listOf(CartItemDto(barcode = trigger.barcode, name = trigger.name)),
    )

    private fun context(pharmacyQty: BigDecimal) = MerchRecommendationContextEnvelope(
        generatedAt = Instant.parse("2026-10-19T10:30:00Z"),
        recommendations = listOf(
            MerchRecommendationCandidate(
                id = "8ffafcf4-f0a0-4662-bcd2-1dd3c11bd888",
                kind = "crosssell",
                triggerBarcode = trigger.barcode,
                recommendProductId = recommended.id,
                script = "Предложите вместе",
                bonus = 320,
                priority = 10,
            ),
        ),
        balances = listOf(
            MerchProductBalance(
                productId = recommended.id,
                pharmacyAvailableQty = pharmacyQty,
                networkAvailableQty = null,
                warehouseAvailableQty = BigDecimal("120"),
                observedAt = Instant.parse("2026-10-19T10:29:00Z"),
                source = "warehouse",
                status = "confirmed",
            ),
        ),
    )
}
