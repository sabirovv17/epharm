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
import kz.epharm.posm.service.RecommendationSaleBonusService
import kz.epharm.posm.service.RuleMatch
import kz.epharm.posm.service.RuleMatchResult
import kz.epharm.posm.service.RulesEngineService
import kz.epharm.receipts.service.PendingBonusService
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleType
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
        price = 1_500, barcode = "4600000000025", ipartId = "80310",
    )
    private val catalog = mutableListOf(trigger, recommended)

    @BeforeEach
    fun setUp() {
        service = RecommendationService(
            rulesEngine, events, pendingBonusService, jdbc, entityManager, merch, products,
            mockk<RecommendationSaleBonusService>(relaxed = true),
        )
        every { events.findAllBySessionId(any()) } returns emptyList()
        every { rulesEngine.match(any(), any()) } returns RuleMatchResult(emptyList(), emptyList())
        every { products.findAllById(any<Iterable<String>>()) } answers {
            val ids = firstArg<Iterable<String>>().toSet()
            catalog.filter { it.id in ids }
        }
        every { products.findAllByBarcodeIn(any()) } answers {
            val keys = firstArg<Collection<String>>()
            catalog.filter { product -> product.scanBarcodes().any { it in keys } }
        }
        every { products.findAllByIpartIdIn(any()) } answers {
            val keys = firstArg<Collection<String>>()
            catalog.filter { it.ipartId in keys }
        }
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

    @Test
    fun `two or three aligned product identifiers resolve the same external offer`() {
        for (withIpart in listOf(false, true)) {
            val candidate = context(BigDecimal.ONE).recommendations.single().copy(
                recommendBarcode = recommended.barcode,
                recommendIpartId = recommended.ipartId.takeIf { withIpart },
            )
            every { merch.recommendationContext(any()) } returns MerchRecommendationContextEnvelope(
                recommendations = listOf(candidate),
            )

            assertThat(service.recommend(request()).recommendations.single().recommendSku)
                .isEqualTo(recommended.id)
        }
    }

    @Test
    fun `disagreeing product identifiers are rejected without affecting local rules`() {
        val local = localMatch()
        every { rulesEngine.match(any(), any()) } returns RuleMatchResult(listOf(local), emptyList())
        every { merch.recommendationContext(any()) } returns MerchRecommendationContextEnvelope(
            recommendations = listOf(context(BigDecimal.ONE).recommendations.single().copy(
                recommendBarcode = trigger.barcode,
            )),
        )

        assertThat(service.recommend(request()).recommendations.map { it.ruleId })
            .containsExactly(local.rule.id)
    }

    @Test
    fun `unknown or ambiguous external product barcodes cannot create an offer`() {
        catalog += ProductEntity(id = "p-duplicate", name = "Дубликат", barcode = recommended.barcode)
        for (barcode in listOf(recommended.barcode, "4600000000999")) {
            every { merch.recommendationContext(any()) } returns MerchRecommendationContextEnvelope(
                recommendations = listOf(context(BigDecimal.ONE).recommendations.single().copy(
                    recommendProductId = null,
                    recommendBarcode = barcode,
                )),
            )

            assertThat(service.recommend(request()).recommendations).isEmpty()
        }
        verify(exactly = 0) { events.save(any()) }
    }

    @Test
    fun `unavailable or unmapped CRM cannot add offers or suppress a local recommendation`() {
        val local = localMatch()
        every { rulesEngine.match(any(), any()) } returns RuleMatchResult(listOf(local), emptyList())
        for (context in listOf(
            context(BigDecimal.ZERO).copy(available = false),
            context(BigDecimal.ZERO).copy(pharmacyMapped = false),
        )) {
            every { merch.recommendationContext(any()) } returns context

            val response = service.recommend(request())

            assertThat(response.recommendations.map { it.ruleId }).containsExactly(local.rule.id)
            assertThat(response.recommendations.single().balance).isNull()
            assertThat(response.conflicts).isEmpty()
        }
    }

    @Test
    fun `missing old or future confirmed zero snapshots cannot suppress local recommendations`() {
        val local = localMatch()
        every { rulesEngine.match(any(), any()) } returns RuleMatchResult(listOf(local), emptyList())
        for (observedAt in listOf(null, Instant.now().minusSeconds(1_801), Instant.now().plusSeconds(60))) {
            val base = context(BigDecimal.ZERO)
            every { merch.recommendationContext(any()) } returns base.copy(
                recommendations = emptyList(),
                balances = listOf(base.balances.single().copy(observedAt = observedAt)),
            )

            val response = service.recommend(request())

            assertThat(response.recommendations.map { it.ruleId }).containsExactly(local.rule.id)
            assertThat(response.recommendations.single().balance?.status)
                .isEqualTo(if (observedAt == null) "unknown" else "stale")
            assertThat(response.conflicts).isEmpty()
        }
    }

    @Test
    fun `fresh confirmed zero suppresses a local offer but unknown or absent stock does not`() {
        val local = localMatch()
        every { rulesEngine.match(any(), any()) } returns RuleMatchResult(listOf(local), emptyList())
        val base = context(BigDecimal.ZERO).copy(recommendations = emptyList())
        every { merch.recommendationContext(any()) } returns base

        assertThat(service.recommend(request()).recommendations).isEmpty()
        verify(exactly = 0) { events.save(any()) }

        for (balances in listOf(emptyList(), listOf(base.balances.single().copy(status = "unknown")))) {
            every { merch.recommendationContext(any()) } returns base.copy(balances = balances)
            assertThat(service.recommend(request()).recommendations.map { it.ruleId })
                .containsExactly(local.rule.id)
        }
    }

    @Test
    fun `CRM failure fallback preserves local recommendations unchanged`() {
        val local = localMatch()
        every { rulesEngine.match(any(), any()) } returns RuleMatchResult(listOf(local), emptyList())
        every { merch.recommendationContext(any()) } returns MerchRecommendationContextEnvelope(available = false)

        val response = service.recommend(request())

        assertThat(response.recommendations.map { it.ruleId }).containsExactly(local.rule.id)
        assertThat(response.recommendations.single().source).isEqualTo("epharm")
        assertThat(response.conflicts).isEmpty()
    }

    @Test
    fun `local offers keep priority and CRM fills only the remaining five per kind without duplicates`() {
        val locals = RuleType.entries.flatMap { kind ->
            (1..2).map { index -> localMatch(product("local-${kind.name}-$index"), kind) }
        }
        val external = RuleType.entries.flatMap { kind ->
            val duplicate = candidate(locals.first { it.rule.type == kind }.recommend, kind.name)
            listOf(duplicate) + (1..7).map { index ->
                candidate(product("external-${kind.name}-$index"), kind.name).copy(priority = 1_000 - index)
            }
        }
        every { rulesEngine.match(any(), any()) } returns RuleMatchResult(locals, emptyList())
        every { merch.recommendationContext(any()) } returns MerchRecommendationContextEnvelope(
            recommendations = external,
        )

        val response = service.recommend(request())

        assertThat(response.recommendations).hasSize(10)
        for (kind in RuleType.entries) {
            val rows = response.recommendations.filter { it.kind == kind.name }
            assertThat(rows.map { it.recommendSku }).containsExactlyElementsOf(
                locals.filter { it.rule.type == kind }.map { it.recommend.id } +
                    (1..3).map { "external-${kind.name}-$it" },
            )
        }
        verify(exactly = 10) { events.save(any()) }
    }

    @Test
    fun `opposite CRM offer cannot contradict or suppress the authoritative local pair`() {
        val local = localMatch(kind = RuleType.substitution)
        every { rulesEngine.match(any(), any()) } returns RuleMatchResult(listOf(local), emptyList())
        every { merch.recommendationContext(any()) } returns MerchRecommendationContextEnvelope(
            recommendations = listOf(candidate(recommended, "crosssell")),
        )

        val response = service.recommend(request())

        assertThat(response.recommendations.map { it.ruleId }).containsExactly(local.rule.id)
        assertThat(response.conflicts.single().kind).isEqualTo("contradiction")
        verify(exactly = 1) { events.save(any()) }
    }

    @Test
    fun `opposite kinds for the same external trigger and product suppress both offers`() {
        every { merch.recommendationContext(any()) } returns MerchRecommendationContextEnvelope(
            recommendations = listOf(candidate(recommended, "substitution"), candidate(recommended, "crosssell")),
        )

        val response = service.recommend(request())

        assertThat(response.recommendations).isEmpty()
        assertThat(response.conflicts.single().kind).isEqualTo("contradiction")
        verify(exactly = 0) { events.save(any()) }
    }

    @Test
    fun `different triggers can recommend one product with different kinds`() {
        val secondTrigger = product("second-trigger").apply { barcode = "4600000000032" }
        val local = localMatch(kind = RuleType.substitution)
        every { rulesEngine.match(any(), any()) } returns RuleMatchResult(listOf(local), emptyList())
        every { merch.recommendationContext(any()) } returns MerchRecommendationContextEnvelope(
            recommendations = listOf(candidate(recommended, "crosssell").copy(triggerBarcode = secondTrigger.barcode)),
        )
        val request = request().copy(cart = request().cart + CartItemDto(barcode = secondTrigger.barcode))

        val response = service.recommend(request)

        assertThat(response.recommendations.map { it.kind }).containsExactly("substitution", "crosssell")
        assertThat(response.conflicts).isEmpty()
    }

    @Test
    fun `external identifiers and stock use confirmed aliases while response keeps the actual cart code`() {
        trigger.barcodes = listOf("4600000000117", "4600000000124")
        recommended.barcodes = listOf("4600000000216")
        val base = context(BigDecimal("4"))
        every { merch.recommendationContext(any()) } returns base.copy(
            recommendations = listOf(base.recommendations.single().copy(
                recommendBarcode = recommended.barcodes.single(), recommendIpartId = recommended.ipartId,
            )),
            balances = listOf(base.balances.single().copy(barcode = recommended.barcodes.single())),
        )
        val request = request().copy(cart = listOf(CartItemDto(barcode = trigger.barcodes.last())))

        val row = service.recommend(request).recommendations.single()

        assertThat(row.triggerSku).isEqualTo(trigger.id)
        assertThat(row.triggerBarcode).isEqualTo(trigger.barcodes.last())
        assertThat(row.recommendSku).isEqualTo(recommended.id)
        assertThat(row.balance?.pharmacyAvailableQty).isEqualByComparingTo("4")
    }

    @Test
    fun `external offer is suppressed when its alternate barcode is already in the basket`() {
        recommended.barcodes = listOf("4600000000216")
        every { merch.recommendationContext(any()) } returns context(BigDecimal.ONE)
        val request = request().copy(cart = request().cart + CartItemDto(barcode = recommended.barcodes.single()))

        assertThat(service.recommend(request).recommendations).isEmpty()
        verify(exactly = 0) { events.save(any()) }
    }

    @Test
    fun `global trigger alias collision cannot be rescued by the matching local SKU`() {
        trigger.barcodes = listOf("4600000000117")
        trigger.ipartId = "SN20"
        catalog += ProductEntity(id = "p_unrelated", name = "Другой препарат", barcode = trigger.barcodes.single())
        val candidate = candidate(recommended, "crosssell").copy(
            triggerBarcode = trigger.barcodes.single(), triggerIpartId = trigger.ipartId,
        )
        every { merch.recommendationContext(any()) } returns MerchRecommendationContextEnvelope(recommendations = listOf(candidate))
        val request = request().copy(cart = listOf(CartItemDto(barcode = trigger.barcodes.single(), sku = trigger.ipartId)))

        assertThat(service.recommend(request).recommendations).isEmpty()
        verify(exactly = 0) { events.save(any()) }
    }

    @Test
    fun `ambiguous offered alias is rejected even when the supplied product ID is valid`() {
        recommended.barcodes = listOf("4600000000216")
        catalog += ProductEntity(id = "p_other_dose", name = "Другая дозировка", barcode = recommended.barcodes.single())
        every { merch.recommendationContext(any()) } returns MerchRecommendationContextEnvelope(
            recommendations = listOf(candidate(recommended, "crosssell").copy(recommendBarcode = recommended.barcodes.single())),
        )

        assertThat(service.recommend(request()).recommendations).isEmpty()
        verify(exactly = 0) { events.save(any()) }
    }

    @Test
    fun `an external local SKU trigger cannot override the known barcode of a different medicine`() {
        trigger.ipartId = "SN20"
        val other = product("p_other_dose").also { it.barcode = "4600000000315" }
        every { merch.recommendationContext(any()) } returns MerchRecommendationContextEnvelope(
            recommendations = listOf(candidate(recommended, "crosssell").copy(
                triggerBarcode = null, triggerIpartId = trigger.ipartId,
            )),
        )
        val request = request().copy(cart = listOf(CartItemDto(barcode = other.barcode, sku = trigger.ipartId)))

        assertThat(service.recommend(request).recommendations).isEmpty()
        verify(exactly = 0) { events.save(any()) }
    }

    @Test
    fun `zero stock under an alternate alias suppresses a local offer only while its ownership is unique`() {
        recommended.barcodes = listOf("4600000000216")
        val local = localMatch()
        every { rulesEngine.match(any(), any()) } returns RuleMatchResult(listOf(local), emptyList())
        val base = context(BigDecimal.ZERO)
        every { merch.recommendationContext(any()) } returns base.copy(
            recommendations = emptyList(),
            balances = listOf(base.balances.single().copy(barcode = recommended.barcodes.single())),
        )
        assertThat(service.recommend(request()).recommendations).isEmpty()
        catalog += ProductEntity(id = "p_other_dose", name = "Другая дозировка", barcode = recommended.barcodes.single())

        val response = service.recommend(request())

        assertThat(response.recommendations.single().ruleId).isEqualTo(local.rule.id)
        assertThat(response.recommendations.single().balance).isNull()
        assertThat(response.conflicts).isEmpty()
    }

    @Test
    fun `alternate trigger barcode cannot hide an external contradiction with a local pair`() {
        trigger.barcodes = listOf("4600000000117")
        val local = localMatch(kind = RuleType.substitution).copy(triggerBarcode = trigger.barcodes.single())
        every { rulesEngine.match(any(), any()) } returns RuleMatchResult(listOf(local), emptyList())
        every { merch.recommendationContext(any()) } returns MerchRecommendationContextEnvelope(
            recommendations = listOf(candidate(recommended, "crosssell")),
        )

        val response = service.recommend(request().copy(cart = listOf(CartItemDto(barcode = trigger.barcodes.single()))))

        assertThat(response.recommendations.single().ruleId).isEqualTo(local.rule.id)
        assertThat(response.conflicts.single().kind).isEqualTo("contradiction")
    }

    private fun product(id: String): ProductEntity = ProductEntity(id = id, name = id, price = 100).also(catalog::add)

    private fun localMatch(product: ProductEntity = recommended, kind: RuleType = RuleType.crosssell) = RuleMatch(
        rule = RuleEntity(id = "local-${product.id}-${kind.name}", typeRaw = kind.name, recommend = product.id),
        triggerSku = trigger.id,
        triggerName = trigger.name,
        triggerProduct = trigger,
        triggerIpartId = trigger.ipartId,
        triggerBarcode = trigger.barcode,
        recommend = product,
    )

    private fun candidate(product: ProductEntity, kind: String) = MerchRecommendationCandidate(
        id = "$kind-${product.id}",
        kind = kind,
        triggerBarcode = trigger.barcode,
        recommendProductId = product.id,
        priority = 1_000,
    )

    private fun request() = RecommendRequest(
        pharmacistId = "u_t",
        pharmacyId = "ph_t",
        sessionId = "sale-1",
        cart = listOf(CartItemDto(barcode = trigger.barcode, name = trigger.name)),
    )

    private fun context(pharmacyQty: BigDecimal) = MerchRecommendationContextEnvelope(
        generatedAt = Instant.now(),
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
                observedAt = Instant.now().minusSeconds(60),
                source = "warehouse",
                status = "confirmed",
            ),
        ),
    )
}
