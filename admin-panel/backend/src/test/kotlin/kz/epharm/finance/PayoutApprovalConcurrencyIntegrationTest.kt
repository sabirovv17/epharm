package kz.epharm.finance

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.domain.AdminUserStatus
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.entity.AdminUserEntity
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.finance.entity.PayoutBatchEntity
import kz.epharm.finance.entity.PayoutBatchStatus
import kz.epharm.finance.entity.PayoutItemEntity
import kz.epharm.finance.repository.PayoutBatchRepository
import kz.epharm.finance.repository.PayoutItemRepository
import kz.epharm.finance.service.PharmacistWalletService
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class PayoutApprovalConcurrencyIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("payout_approval_synthetic").withUsername("epharm").withPassword("synthetic-only")
            .apply { start() }

        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var batches: PayoutBatchRepository
    @Autowired private lateinit var items: PayoutItemRepository
    @Autowired private lateinit var people: PharmacistRepository
    @Autowired private lateinit var admins: AdminUserRepository
    @Autowired private lateinit var passwords: PasswordEncoder
    @Autowired private lateinit var wallet: PharmacistWalletService
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactions: PlatformTransactionManager

    // No outer test transaction: both HTTP requests must observe committed setup and commit separately.
    @Test
    fun `simultaneous reviewers approve once preserve winner and never change wallet or ledger`() {
        val (batchId, personId) = fixture(1)
        val reviewers = listOf(reviewer(), reviewer())
        val before = money(personId)
        val itemBefore = jdbc.queryForList("SELECT * FROM payout_items WHERE batch_id=? ORDER BY id", batchId)
        val pool = Executors.newFixedThreadPool(3)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val holder = pool.submit(Callable {
                TransactionTemplate(transactions).executeWithoutResult {
                    jdbc.queryForList("SELECT id FROM payout_batches WHERE id=? FOR UPDATE", batchId)
                    holding.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "Synthetic batch lock was not released" }
                }
            })
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue()
            val requests = reviewers.map { reviewer -> pool.submit(Callable {
                reviewer.first to mvc.perform(post("/api/admin/payouts/$batchId/approve")
                    .header("Authorization", reviewer.second)).andReturn().response
            }) }
            try {
                // Both real service transactions must wait at the batch read lock, not merely race by luck.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var waiting = 0L
                while (waiting < 2 && System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("""SELECT COUNT(*) FROM pg_stat_activity
                        WHERE datname=current_database() AND wait_event_type='Lock'
                          AND query ILIKE '%payout_batches%' AND query ILIKE '%for%update%'""", Long::class.java)!!
                    if (waiting < 2) Thread.sleep(10)
                }
                assertThat(waiting).isEqualTo(2)
            } finally { release.countDown() }
            holder.get(10, TimeUnit.SECONDS)
            val responses = requests.map { it.get(15, TimeUnit.SECONDS) }
            assertThat(responses.map { it.second.status }).containsExactlyInAnyOrder(200, 409)
            val winner = responses.single { it.second.status == 200 }
            val approved = json.readTree(winner.second.contentAsByteArray)
            val stored = batches.findById(batchId).orElseThrow()
            assertThat(stored.status).isEqualTo(PayoutBatchStatus.approved)
            assertThat(stored.reviewerId).isEqualTo(winner.first.toString())
            assertThat(approved.path("reviewerId").asText()).isEqualTo(stored.reviewerId)
            // PostgreSQL timestamps have microsecond precision; the HTTP DTO precedes the commit flush.
            val responseTime = Instant.parse(approved.path("approvedAt").asText())
            assertThat(stored.approvedAt).isBetween(responseTime.minusNanos(1000), responseTime.plusNanos(1000))
            val approvedBeforeRetry = jdbc.queryForMap(
                "SELECT reviewer_id,reviewer_name,approved_at,updated_at FROM payout_batches WHERE id=?", batchId)
            mvc.perform(post("/api/admin/payouts/$batchId/approve").header("Authorization", reviewers[0].second))
                .andExpect(status().isConflict)
            assertThat(jdbc.queryForMap("SELECT reviewer_id,reviewer_name,approved_at,updated_at FROM payout_batches WHERE id=?", batchId))
                .isEqualTo(approvedBeforeRetry)
            assertThat(money(personId)).isEqualTo(before)
            assertThat(jdbc.queryForList("SELECT * FROM payout_items WHERE batch_id=? ORDER BY id", batchId)).isEqualTo(itemBefore)
        } finally {
            release.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `minimum bigint corrupted item returns conflict before SQL negation and keeps money intact`() {
        val (batchId, personId) = fixture(2)
        val reviewer = reviewer()
        jdbc.update("UPDATE payout_items SET amount=? WHERE batch_id=?", Long.MIN_VALUE, batchId)
        jdbc.update("UPDATE payout_batches SET amount=? WHERE id=?", Long.MIN_VALUE, batchId)
        val before = money(personId)

        val result = mvc.perform(post("/api/admin/payouts/$batchId/approve").header("Authorization", reviewer.second))
            .andExpect(status().isConflict).andReturn().response

        assertThat(json.readTree(result.contentAsByteArray).path("code").asText()).isEqualTo("CONFLICT")
        val batch = batches.findById(batchId).orElseThrow()
        assertThat(batch.status).isEqualTo(PayoutBatchStatus.pending)
        assertThat(batch.approvedAt).isNull()
        assertThat(batch.reviewerId).isNull()
        assertThat(money(personId)).isEqualTo(before)
    }

    private fun fixture(index: Int): Pair<String, String> {
        val personId = "approval-person-${UUID.randomUUID()}"
        people.saveAndFlush(PharmacistEntity(id = personId, name = "Synthetic pharmacist",
            iin = index.toString().padStart(12, '0'), phone = "+7000000000$index", balance = 500))
        val batchId = "approval-batch-${UUID.randomUUID()}"
        val itemId = "approval-item-${UUID.randomUUID()}"
        batches.saveAndFlush(PayoutBatchEntity(id = batchId, period = "Synthetic period", pharmacists = 1, amount = 500, items = 1))
        items.saveAndFlush(PayoutItemEntity(id = itemId, batchId = batchId, pharmacistId = personId,
            pharmacistName = "Synthetic pharmacist", amount = 500))
        wallet.reserveForPayout(personId, itemId)
        return batchId to personId
    }

    private fun reviewer(): Pair<UUID, String> {
        val account = admins.saveAndFlush(AdminUserEntity(email = "approval-${UUID.randomUUID()}@example.invalid",
            name = "Synthetic reviewer", company = "Synthetic", passwordHash = passwords.encode("synthetic-password"))
            .apply { role = AdminRole.FINANCE_REVIEWER; status = AdminUserStatus.ACTIVE })
        val result = mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(LoginRequest(account.email, "synthetic-password"))))
            .andExpect(status().isOk).andReturn().response
        return account.id to "Bearer ${json.readValue(result.contentAsByteArray, LoginResponse::class.java).tokens.accessToken}"
    }

    private fun money(personId: String) = listOf(
        jdbc.queryForList("SELECT balance,earned_30d,updated_at FROM pharmacists WHERE id=?", personId),
        jdbc.queryForList("SELECT * FROM pharmacist_wallet_entries WHERE pharmacist_id=? ORDER BY id", personId),
        jdbc.queryForList("SELECT * FROM pharmacist_wallet_checkpoints WHERE pharmacist_id=?", personId),
    )
}
