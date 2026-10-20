package kz.epharm.auth

import java.util.UUID
import kz.epharm.finance.service.PharmacistWalletService
import kz.epharm.finance.service.WalletCreditSource
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
