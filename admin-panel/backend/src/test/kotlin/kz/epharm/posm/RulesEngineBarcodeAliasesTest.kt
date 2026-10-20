package kz.epharm.posm

import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.catalog.service.AccCatalogClassification
import kz.epharm.catalog.service.AccCatalogTaxonomy
import kz.epharm.posm.dto.CartItemDto
import kz.epharm.posm.service.RulesEngineService
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.repository.RuleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RulesEngineBarcodeAliasesTest {
    private val products = mockk<ProductRepository>()
    private val taxonomy = mockk<AccCatalogTaxonomy>(relaxed = true)
    private val trigger = ProductEntity(
        id = "p_20mg", name = "Препарат 20 мг капсулы №30", barcode = PRIMARY,
        barcodes = listOf(SECONDARY, THIRD), ipartId = "SN20",
    )
    private val offer = ProductEntity(id = "p_offer", name = "Аналог 20 мг капсулы №30", barcode = OFFER)
    private val catalog = mutableListOf(trigger, offer)
    private val active = mutableListOf(RuleEntity(
        id = "r_exact", recommend = offer.id, trigger = RuleTrigger(kind = "product", value = trigger.id),
    ).also { it.status = RuleStatus.active })
    private val repository = mockk<RuleRepository>()
    private val engine = RulesEngineService(repository, products, mockk<PromoRepository>(relaxed = true), taxonomy)

    @BeforeEach
    fun setUp() {
        every { repository.findAllByStatusRawOrderByUpdatedAtDesc("active") } answers { active }
        every { products.findById(any()) } answers {
            Optional.ofNullable(catalog.firstOrNull { it.id == firstArg<String>() })
        }
        every { products.findAllByBarcodeIn(any()) } answers {
            val keys = firstArg<Collection<String>>().toSet()
            catalog.filter { product -> product.scanBarcodes().any { it in keys } }
        }
        every { products.findAllByIpartIdIn(any()) } answers {
            val keys = firstArg<Collection<String>>()
            catalog.filter { it.ipartId in keys }
        }
        every { products.findAllByOrderByNameAsc() } answers { catalog }
    }

    @Test
    fun `secondary and third codes activate the same exact rule with the actual request barcode`() {
        for (barcode in listOf(SECONDARY, THIRD)) {
            val match = engine.match(listOf(CartItemDto(barcode = barcode)), barcode).matches.single()
            assertThat(match.rule.id).isEqualTo("r_exact")
            assertThat(match.triggerSku).isEqualTo(trigger.id)
            assertThat(match.triggerBarcode).isEqualTo(barcode)
        }
    }

    @Test
    fun `multiple aliases in a basket do not duplicate the rule and scanned alias wins binding`() {
        val result = engine.match(listOf(CartItemDto(barcode = SECONDARY), CartItemDto(barcode = THIRD)), THIRD)
        assertThat(result.matches).hasSize(1)
        assertThat(result.matches.single().triggerBarcode).isEqualTo(THIRD)
    }

    @Test
    fun `legacy primary barcode remains usable without an alias list`() {
        trigger.barcodes = emptyList()
        assertThat(engine.match(listOf(CartItemDto(barcode = PRIMARY))).matches.single().triggerSku)
            .isEqualTo(trigger.id)
    }

    @Test
    fun `alias collision with an unrelated product cannot fall through to local SKU or matching name`() {
        catalog += ProductEntity(id = "p_40mg", name = "Препарат 40 мг таблетки №30", barcode = SECONDARY)
        val item = CartItemDto(barcode = SECONDARY, sku = trigger.ipartId, name = trigger.name)
        assertThat(engine.match(listOf(item)).matches).isEmpty()
        assertThat(engine.resolveToProductIds(listOf(item))).isEqualTo(listOf<String?>(null))
        assertThat(engine.resolveStrongProductIds(listOf(item))).isEqualTo(listOf<String?>(null))
    }

    @Test
    fun `a globally known different medicine cannot borrow the active trigger name`() {
        val otherCode = "4600000000999"
        catalog += ProductEntity(id = "p_40mg", name = "Препарат 40 мг таблетки №30", barcode = otherCode)
        assertThat(engine.match(listOf(CartItemDto(barcode = otherCode, name = trigger.name))).matches).isEmpty()
    }

    @Test
    fun `offer already present under its alternate barcode is not recommended again`() {
        offer.barcodes = listOf("4600000000201")
        val cart = listOf(CartItemDto(barcode = SECONDARY), CartItemDto(barcode = offer.barcodes.single()))
        assertThat(engine.match(cart).matches).isEmpty()
    }

    @Test
    fun `strong sale resolution accepts aliases but keeps unknown and contradictory identifiers closed`() {
        catalog += ProductEntity(id = "p_40mg", name = "Препарат 40 мг", ipartId = "SN40")
        val items = listOf(
            CartItemDto(barcode = SECONDARY), CartItemDto(barcode = THIRD),
            CartItemDto(barcode = THIRD, sku = "SN40"),
            CartItemDto(barcode = "4600000000999", sku = "SN20", name = trigger.name),
            CartItemDto(sku = "SN20"), CartItemDto(name = trigger.name),
        )
        assertThat(engine.resolveStrongProductIds(items))
            .containsExactly(trigger.id, trigger.id, null, null, trigger.id, null)
    }

    @Test
    fun `ACC group subgroup and MNN keep matching a concrete alternate barcode`() {
        val classification = AccCatalogClassification("ware_20", SECONDARY, "group", "subgroup", "mnn")
        every { taxonomy.unambiguousBarcodes(any()) } returns mapOf(SECONDARY to classification)
        for ((kind, key) in listOf("acc_group" to "group", "acc_subgroup" to "subgroup", "acc_mnn" to "mnn")) {
            active.single().trigger = RuleTrigger(kind = kind, value = key)
            val match = engine.match(listOf(CartItemDto(barcode = SECONDARY)), SECONDARY).matches.single()
            assertThat(match.triggerBarcode).isEqualTo(SECONDARY)
            assertThat(match.triggerProduct?.id).isEqualTo(trigger.id)
        }
    }

    @Test
    fun `ACC membership cannot override a known product barcode collision`() {
        catalog += ProductEntity(id = "p_40mg", name = "Другая дозировка", barcode = SECONDARY)
        every { taxonomy.unambiguousBarcodes(any()) } returns mapOf(
            SECONDARY to AccCatalogClassification("ware_20", SECONDARY, "group", null, null),
        )
        active.single().trigger = RuleTrigger(kind = "acc_group", value = "group")
        assertThat(engine.match(listOf(CartItemDto(barcode = SECONDARY, name = trigger.name))).matches).isEmpty()
    }

    companion object {
        private const val PRIMARY = "4600000000100"
        private const val SECONDARY = "4600000000101"
        private const val THIRD = "4600000000102"
        private const val OFFER = "4600000000200"
    }
}
