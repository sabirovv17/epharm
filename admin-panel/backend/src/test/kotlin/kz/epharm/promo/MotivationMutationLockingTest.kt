package kz.epharm.promo

import kz.epharm.catalog.entity.ProductEntity
import kz.epharm.catalog.repository.ProductRepository
import kz.epharm.promo.entity.PromoEntity
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.repository.PromoRepository
import kz.epharm.rules.entity.RuleEntity
import kz.epharm.rules.entity.RuleStatus
import kz.epharm.rules.entity.RuleTrigger
import kz.epharm.rules.repository.RuleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeoutException

/** Separate committed transactions prove mutation reads cannot authorize against stale draft state. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MotivationMutationLockingTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("motivation_locks").withUsername("epharm").withPassword("synthetic-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun props(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var manager: PlatformTransactionManager
    @Autowired lateinit var promos: PromoRepository
    @Autowired lateinit var rules: RuleRepository
    @Autowired lateinit var products: ProductRepository

    @BeforeEach fun seed() {
        TransactionTemplate(manager).executeWithoutResult {
            rules.deleteAll(); promos.deleteAll(); products.deleteAll()
            products.saveAndFlush(ProductEntity(id = "lock-offer", name = "Synthetic offer"))
            promos.saveAndFlush(PromoEntity(id = "lock-promo", title = "Synthetic campaign"))
            rules.saveAndFlush(RuleEntity(id = "lock-rule", recommend = "lock-offer", promoId = "lock-promo",
                trigger = RuleTrigger("product", "synthetic-trigger")))
        }
    }

    @Test fun `campaign mutation read waits for concurrent publication and observes active state`() {
        verifyBlocking(
            publish = { promos.findForUpdate("lock-promo").orElseThrow().status = PromoStatus.active },
            read = { promos.findForUpdate("lock-promo").orElseThrow().status.name },
        )
    }

    @Test fun `rule mutation read waits for concurrent publication and observes active state`() {
        verifyBlocking(
            publish = { rules.findForUpdate("lock-rule").orElseThrow().status = RuleStatus.active },
            read = { rules.findForUpdate("lock-rule").orElseThrow().status.name },
        )
    }

    private fun verifyBlocking(publish: () -> Unit, read: () -> String) {
        val executor = Executors.newFixedThreadPool(2)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reading = CountDownLatch(1)
        try {
            val writer = executor.submit {
                TransactionTemplate(manager).executeWithoutResult {
                    publish()
                    locked.countDown()
                    check(release.await(10, SECONDS)) { "Reader did not release publication" }
                }
            }
            assertThat(locked.await(10, SECONDS)).isTrue()
            val reader = executor.submit<String> {
                TransactionTemplate(manager).execute {
                    reading.countDown()
                    read()
                }!!
            }
            assertThat(reading.await(10, SECONDS)).isTrue()
            assertThrows<TimeoutException> { reader.get(200, MILLISECONDS) }
            release.countDown()
            writer.get(10, SECONDS)
            assertThat(reader.get(10, SECONDS)).isEqualTo("active")
        } finally {
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(10, SECONDS)) { "Mutation transactions did not finish" }
        }
    }
}
