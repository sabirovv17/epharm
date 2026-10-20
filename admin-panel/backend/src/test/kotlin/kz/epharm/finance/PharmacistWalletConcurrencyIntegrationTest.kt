package kz.epharm.finance

import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kz.epharm.finance.repository.PayoutItemRepository
import kz.epharm.finance.service.PharmacistWalletService
import kz.epharm.finance.service.PayoutService
import kz.epharm.finance.service.WalletCreditSource
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.shared.error.AppException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PharmacistWalletConcurrencyIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("wallet_test").withUsername("epharm").withPassword("test-only")
            .apply { start() }
        private val personNumber = AtomicLong(700_000_000_000)
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired private lateinit var wallet: PharmacistWalletService
    @Autowired private lateinit var merch: kz.epharm.finance.merch.MerchRewardService
    @Autowired private lateinit var payouts: PayoutService
    @Autowired private lateinit var payoutItems: PayoutItemRepository
    @Autowired private lateinit var people: PharmacistRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Test
    fun `default disabled financial integration rejects all entry points without moving funds`() {
        val person=person(500)
        assertThatThrownBy { merch.context(person.id,"not-a-signed-challenge") }.isInstanceOf(AppException::class.java)
        assertThatThrownBy { merch.redeem(kz.epharm.finance.merch.MerchRedeemRequest("x".repeat(43),UUID.randomUUID(),"pharmacy","n".repeat(43))) }
            .isInstanceOf(AppException::class.java)
        assertThat(balance(person.id)).isEqualTo(500)
        assertThat(entryCount(person.id)).isZero()
    }

    @Test
    fun `concurrent independent credits preserve opening checkpoint and every new amount`() {
        val person = person(500)
        parallel((1..24).map { index -> {
            wallet.credit(WalletCreditSource.MERCH_TASK, "dispatch-${UUID.randomUUID()}", person.id, 10, "approved:$index")
        } })

        assertThat(balance(person.id)).isEqualTo(740)
        assertThat(people.findById(person.id).orElseThrow().earned30d).isEqualTo(240)
        assertThat(entryCount(person.id)).isEqualTo(24)
        assertThat(jdbc.queryForObject("SELECT balance_kzt FROM pharmacist_wallet_checkpoints WHERE pharmacist_id=?",
            Long::class.java, person.id)).isEqualTo(500)
    }

    @Test
    fun `concurrent identical approval retries credit once and return the same entry`() {
        val person = person(250)
        val dispatch = UUID.randomUUID().toString()
        val results = parallel((1..16).map { {
            wallet.credit(WalletCreditSource.MERCH_TASK, dispatch, person.id, 100, "approval:immutable")
        } })

        assertThat(results.count { it.applied }).isEqualTo(1)
        assertThat(results.map { it.entryId }.distinct()).hasSize(1)
        assertThat(balance(person.id)).isEqualTo(350)
        assertThat(entryCount(person.id)).isEqualTo(1)
    }

    @Test
    fun `one pending bonus cannot be credited through two receipts or changed recipient`() {
        val person = person(0)
        val other = person(0)
        val pendingId = "pending-${UUID.randomUUID()}"
        wallet.credit(WalletCreditSource.RECEIPT_BONUS, pendingId, person.id, 100, "receipt:first")

        for (attempt in listOf(
            { wallet.credit(WalletCreditSource.RECEIPT_BONUS, pendingId, person.id, 100, "receipt:second") },
            { wallet.credit(WalletCreditSource.RECEIPT_BONUS, pendingId, person.id, 101, "receipt:first") },
            { wallet.credit(WalletCreditSource.RECEIPT_BONUS, pendingId, other.id, 100, "receipt:first") },
        )) assertThatThrownBy { attempt() }.isInstanceOf(AppException::class.java)

        assertThat(balance(person.id)).isEqualTo(100)
        assertThat(balance(other.id)).isZero()
        assertThat(entryCount(person.id)).isEqualTo(1)
    }

    @Test
    fun `credit racing payout reservation is either reserved or remains spendable never lost`() {
        val person = person(500)
        parallel(listOf(
            { wallet.credit(WalletCreditSource.TRAINING_BONUS, UUID.randomUUID().toString(), person.id, 75, "assignment:test"); Unit },
            { payouts.generateBatch("Wallet race"); Unit },
        ))

        val reserved = payoutItems.findAll().filter { it.pharmacistId == person.id }.sumOf { it.amount }
        assertThat(balance(person.id) + reserved).isEqualTo(575)
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(amount_kzt),0) FROM pharmacist_wallet_entries WHERE pharmacist_id=?",
            Long::class.java, person.id)).isEqualTo(balance(person.id) - 500)
    }

    @Test
    fun `two payout generators reserve the same funds only once`() {
        val person = person(123)
        parallel(listOf(
            { runCatching { payouts.generateBatch("Concurrent payout") }; Unit },
            { runCatching { payouts.generateBatch("Concurrent payout") }; Unit },
        ))

        assertThat(balance(person.id)).isZero()
        assertThat(payoutItems.findAll().filter { it.pharmacistId == person.id }.sumOf { it.amount }).isEqualTo(123)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=? AND source='payout_reservation'",
            Long::class.java, person.id)).isEqualTo(1)
    }

    @Test
    fun `stale nonfinancial JPA profile update does not overwrite a concurrent credit`() {
        val person = person(10)
        val loaded = CountDownLatch(1)
        val credited = CountDownLatch(1)
        parallel(listOf(
            {
                TransactionTemplate(transactions).executeWithoutResult {
                    val stale = people.findById(person.id).orElseThrow()
                    loaded.countDown()
                    check(credited.await(10, TimeUnit.SECONDS))
                    stale.name = "Changed profile"
                    people.save(stale)
                }
            },
            {
                check(loaded.await(10, TimeUnit.SECONDS))
                wallet.credit(WalletCreditSource.MERCH_TASK, UUID.randomUUID().toString(), person.id, 20, "approved:test")
                credited.countDown()
            },
        ))

        assertThat(balance(person.id)).isEqualTo(30)
        assertThat(people.findById(person.id).orElseThrow().name).isEqualTo("Changed profile")
    }

    @Test
    fun `financial entries cannot be rewritten or deleted`() {
        val person = person(0)
        val result = wallet.credit(WalletCreditSource.MERCH_TASK, UUID.randomUUID().toString(), person.id, 20, "approved:test")

        assertThatThrownBy { jdbc.update("UPDATE pharmacist_wallet_entries SET amount_kzt=21 WHERE id=?", result.entryId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { jdbc.update("DELETE FROM pharmacist_wallet_entries WHERE id=?", result.entryId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(balance(person.id)).isEqualTo(20)
        assertThat(entryCount(person.id)).isEqualTo(1)
    }

    @Test
    fun `failed surrounding transaction rolls back both ledger and balance`() {
        val person = person(5)
        assertThatThrownBy {
            TransactionTemplate(transactions).executeWithoutResult {
                wallet.credit(WalletCreditSource.MERCH_TASK, UUID.randomUUID().toString(), person.id, 20, "approved:test")
                error("Simulated approval failure")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(balance(person.id)).isEqualTo(5)
        assertThat(entryCount(person.id)).isZero()
    }

    private fun person(opening: Long): PharmacistEntity {
        val number = personNumber.incrementAndGet().toString()
        return people.saveAndFlush(PharmacistEntity(id = "ph_wallet_${UUID.randomUUID()}", name = "Wallet test",
            iin = number, phone = "+7$number", city = "", balance = opening))
    }
    private fun balance(id: String) = people.findById(id).orElseThrow().balance
    private fun entryCount(id: String) = jdbc.queryForObject(
        "SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=?", Long::class.java, id)!!
    private fun <T> parallel(actions: List<() -> T>): List<T> {
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures = actions.map { action -> executor.submit(Callable { start.await(); action() }) }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}
