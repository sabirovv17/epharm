package kz.epharm.auth

import java.util.UUID
import kz.epharm.finance.service.PharmacistWalletService
import kz.epharm.finance.service.WalletCreditSource
import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.promo.dto.PromoRuleProductRefDto
import kz.epharm.promo.dto.SavePromoChangesRequest
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.promo.service.PromoPublicationService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/** Only a disposable dev database: a normal ledger mutation must remain impossible. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test", "dev")
@Testcontainers
class DevWalletResetIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("dev_wallet_reset_disposable").withUsername("epharm").withPassword("test-only")
            .apply { start() }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var wallet: PharmacistWalletService
    @Autowired private lateinit var products: ProductRepository
    @Autowired private lateinit var promos: PromoRepository
    @Autowired private lateinit var publication: PromoPublicationService

    @Test fun `dev reset clears financial fixtures and reseeds without weakening append-only protection`() {
        val person = jdbc.queryForObject("SELECT id FROM pharmacists ORDER BY id LIMIT 1", String::class.java)!!
        val people = jdbc.queryForObject("SELECT count(*) FROM pharmacists", Long::class.java)
        val admins = jdbc.queryForList("SELECT id FROM admin_users ORDER BY id")
        repeat(2) {
            wallet.credit(WalletCreditSource.TRAINING_BONUS, UUID.randomUUID().toString(), person, 100, "dev-reset-only")
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries", Long::class.java)).isEqualTo(1)
            assertThatThrownBy { jdbc.update("DELETE FROM pharmacist_wallet_entries") }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            mvc.perform(post("/api/admin/dev/reset")).andExpect(status().isOk)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries", Long::class.java)).isZero()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_checkpoints", Long::class.java)).isZero()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pharmacists", Long::class.java)).isEqualTo(people)
            assertThat(jdbc.queryForList("SELECT id FROM admin_users ORDER BY id")).isEqualTo(admins)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM receipts", Long::class.java)).isGreaterThan(0)
        }
    }

    @Test fun `dev reset clears applied publication history and pending draft before reseeding campaigns`() {
        mvc.perform(post("/api/admin/dev/reset")).andExpect(status().isOk)
        val originalPromos = jdbc.queryForList("SELECT id FROM promos ORDER BY id")
        val originalProducts = products.count()
        val originalRules = jdbc.queryForObject("SELECT count(*) FROM rules", Long::class.java)
        val originalAdmins = jdbc.queryForList("SELECT id FROM admin_users ORDER BY id")
        repeat(2) {
            products.saveAllAndFlush(listOf(
                ProductEntity(id = "reset-trigger", name = "Synthetic reset trigger", price = 1000),
                ProductEntity(id = "reset-offer", name = "Synthetic reset offer", price = 2000),
            ))
            promos.saveAndFlush(PromoEntity(id = "pr-reset-fixture", title = "Synthetic reset campaign",
                medusaProductId = "reset-offer", productName = "Synthetic reset offer"))
            val initial = publication.view("pr-reset-fixture")
            val bundle = initial.bundle.copy(
                campaign = initial.bundle.campaign.copy(pharmacistBonus = 100),
                rules = initial.bundle.rules.copy(replacements = listOf(PromoRuleProductRefDto(
                    medusaProductId = "reset-trigger", name = "Synthetic reset trigger", price = 1000))),
            )
            val saved = publication.save("pr-reset-fixture", SavePromoChangesRequest(initial.revision, bundle), "dev-reset-test")
            val applied = publication.apply("pr-reset-fixture", saved.revision, "dev-reset-test")
            val pending = publication.save("pr-reset-fixture", SavePromoChangesRequest(applied.revision,
                applied.bundle.copy(campaign = applied.bundle.campaign.copy(title = "Unapplied changes"))), "dev-reset-test")
            assertThat(pending.hasPendingChanges).isTrue()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM promo_publication_history", Long::class.java)).isEqualTo(1)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM promo_publication_changes WHERE bundle_json IS NOT NULL", Long::class.java)).isEqualTo(1)
            assertThatThrownBy { jdbc.update("DELETE FROM promo_publication_history") }
                .isInstanceOf(DataIntegrityViolationException::class.java)
            assertThatThrownBy { jdbc.update("UPDATE promo_publication_history SET applied_by='forbidden'") }
                .isInstanceOf(DataIntegrityViolationException::class.java)

            mvc.perform(post("/api/admin/dev/reset")).andExpect(status().isOk)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM promo_publication_changes", Long::class.java)).isZero()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM promo_publication_history", Long::class.java)).isZero()
            assertThat(jdbc.queryForList("SELECT id FROM promos ORDER BY id")).isEqualTo(originalPromos)
            assertThat(products.count()).isEqualTo(originalProducts)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM rules", Long::class.java)).isEqualTo(originalRules)
            assertThat(jdbc.queryForList("SELECT id FROM admin_users ORDER BY id")).isEqualTo(originalAdmins)
        }
    }
}

class DevWalletResetProfileGuardTest {
    @Test fun `production excludes destructive dev utilities even if dev was also selected`() {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.setActiveProfiles("prod", "dev")
            context.register(DevDataSeeder::class.java, DevController::class.java)
            context.refresh()
            assertThat(context.getBeanNamesForType(DevDataSeeder::class.java)).isEmpty()
            assertThat(context.getBeanNamesForType(DevController::class.java)).isEmpty()
        }
    }
}
