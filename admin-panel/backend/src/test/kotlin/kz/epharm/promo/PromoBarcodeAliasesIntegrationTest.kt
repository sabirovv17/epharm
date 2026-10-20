package kz.epharm.promo

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import jakarta.persistence.EntityManager
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.medusa.service.MedusaPriceService
import kz.epharm.medusa.service.MedusaPriceService.MedusaSnapshot
import kz.epharm.promo.dto.CreatePromoRequest
import kz.epharm.promo.dto.InitialPromoRecommendationDto
import kz.epharm.promo.dto.PromoRuleProductRefDto
import kz.epharm.promo.dto.PromoRulesConfigDto
import kz.epharm.promo.dto.UpdatePromoRequest
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.promo.service.PromoRulesService
import kz.epharm.promo.service.PromoService
import kz.epharm.rules.entity.RuleType
import kz.epharm.shared.error.AppException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class PromoBarcodeAliasesIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("epharm_alias_authoring").withUsername("epharm").withPassword("epharm_test")
            .apply { start() }
        @JvmStatic @DynamicPropertySource
        fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
        private val codes = listOf("0000000012345", "4870223140649", "4870223140694")
        private val offerCodes = listOf("4603423004936", "4604249789012")
    }

    @Autowired private lateinit var promos: PromoService
    @Autowired private lateinit var rules: PromoRulesService
    @Autowired private lateinit var products: ProductRepository
    @Autowired private lateinit var promoRepository: PromoRepository
    @Autowired private lateinit var em: EntityManager
    @MockkBean private lateinit var prices: MedusaPriceService

    @BeforeEach
    fun source() {
        every { prices.priceOf(any<String>()) } returns 100L
        every { prices.snapshotOf(any()) } answers {
            val aliases = if (firstArg<String>() == "trigger") codes else offerCodes
            MedusaSnapshot(100L, null, aliases.first(), aliases)
        }
    }

    private fun create() = promos.create(
        CreatePromoRequest(
            title = "Isolated barcode contract", medusaProductId = "offer", productName = "Offer",
            barcodes = offerCodes,
            initialRecommendation = InitialPromoRecommendationDto(
                type = RuleType.substitution,
                trigger = PromoRuleProductRefDto(
                    medusaProductId = "trigger", name = "Trigger", barcodes = codes,
                ),
            ),
        ),
        "isolated-test",
    )

    @Test
    fun `source aliases survive create database reload GET and legacy scalar save`() {
        val created = create()
        assertThat(created.status).isEqualTo(PromoStatus.draft)
        assertThat(created.barcodes).containsExactlyElementsOf(offerCodes)
        em.flush()
        em.clear()
        val stored = products.findAllByBarcodeIn(listOf(codes.last())).single()
        assertThat(stored.id).isEqualTo("trigger")
        assertThat(stored.scanBarcodes()).containsExactlyElementsOf(codes)
        val view = rules.view(created.id)
        assertThat(view.activeCount).isZero()
        assertThat(view.config.replacements.single().barcodes).containsExactlyElementsOf(codes)

        every { prices.snapshotOf(any()) } returns null
        rules.replace(
            created.id,
            PromoRulesConfigDto(replacements = listOf(PromoRuleProductRefDto(
                medusaProductId = "trigger", name = "Trigger", barcode = codes.first(),
            ))),
            "isolated-test",
        )
        em.flush()
        em.clear()
        assertThat(rules.view(created.id).config.replacements.single().barcodes)
            .containsExactlyElementsOf(codes)
        assertThat(products.findAllByBarcodeIn(listOf(offerCodes.last())).single().id).isEqualTo("offer")
    }

    @Test
    fun `editing a preexisting campaign refreshes offered aliases without waiting for the scheduler`() {
        val created = create()
        val newCode = "4600000000999"
        every { prices.snapshotOf("offer") } returns MedusaSnapshot(
            100L, null, newCode, listOf(newCode), barcodeSourceComplete = true,
        )
        rules.replace(created.id, rules.view(created.id).config, "isolated-test")
        em.flush()
        em.clear()
        assertThat(products.findAllByBarcodeIn(listOf(newCode)).single().id).isEqualTo("offer")
        assertThat(products.findAllByBarcodeIn(offerCodes)).isEmpty()
        assertThat(promos.get(created.id).barcodes).containsExactly(newCode)
    }

    @Test
    fun `product switch cannot inherit the previous dose or pack identifiers`() {
        val created = create()
        val newCode = "4600000000999"
        every { prices.snapshotOf("new-dose") } returns MedusaSnapshot(100L, null, newCode, listOf(newCode))
        val updated = promos.update(created.id, UpdatePromoRequest(medusaProductId = "new-dose"))
        assertThat(updated.barcode).isEqualTo(newCode)
        assertThat(updated.barcodes).containsExactly(newCode)
        assertThat(updated.ipartId).isNull()
    }

    @Test
    fun `unconfirmed plural codes fail draft creation instead of merging unrelated medicines`() {
        assertThrows(AppException::class.java) {
            promos.create(
                CreatePromoRequest(
                    title = "Untrusted aliases", medusaProductId = "offer",
                    barcodes = listOf("4600000000999"),
                ),
                "isolated-test",
            )
        }
        assertThat(promoRepository.findAllByMedusaProductId("offer")).isEmpty()
    }
}
