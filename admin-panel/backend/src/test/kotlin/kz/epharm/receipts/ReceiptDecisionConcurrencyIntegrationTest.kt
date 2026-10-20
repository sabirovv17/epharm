package kz.epharm.receipts

import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.entity.ReceiptEntity
import kz.epharm.receipts.entity.ReceiptStatus
import kz.epharm.receipts.dto.ExcelRowInput
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.repository.ReceiptRepository
import kz.epharm.receipts.service.ReconcileService
import kz.epharm.shared.error.AppException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
class ReceiptDecisionConcurrencyIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres=PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("receipt_decision_test").withUsername("epharm").withPassword("test-only").apply { start() }
        private val sequence=AtomicLong(850_000_000_000)
        @JvmStatic @DynamicPropertySource fun properties(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }
    @Autowired lateinit var reconcile: ReconcileService
    @Autowired lateinit var receipts: ReceiptRepository
    @Autowired lateinit var pending: PendingBonusRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var transactions: PlatformTransactionManager

    @Test fun `reject racing committed approval cannot overwrite receipt or hide credited bonus`() {
        val fixture=fixture()
        val locked=CountDownLatch(1)
        val rejectStarted=CountDownLatch(1)
        val pool=Executors.newFixedThreadPool(2)
        try {
            val approved=pool.submit(Callable {
                TransactionTemplate(transactions).execute {
                    jdbc.queryForList("SELECT id FROM pending_bonuses WHERE id=(SELECT pending_bonus_id FROM receipts WHERE id=?) FOR UPDATE",fixture.receipt)
                    jdbc.queryForList("SELECT id FROM receipts WHERE id=? FOR UPDATE",fixture.receipt)
                    locked.countDown()
                    check(rejectStarted.await(10,TimeUnit.SECONDS))
                    reconcile.approve(fixture.receipt,"HQ-test")
                }
            })
            val rejected=pool.submit(Callable {
                check(locked.await(10,TimeUnit.SECONDS))
                rejectStarted.countDown()
                runCatching { reconcile.reject(fixture.receipt,"Other-test","Synthetic concurrent rejection") }
            })
            approved.get(20,TimeUnit.SECONDS)
            assertThat(rejected.get(20,TimeUnit.SECONDS).exceptionOrNull()).isInstanceOf(AppException::class.java)
        } finally { pool.shutdownNow() }
        val receipt=receipts.findById(fixture.receipt).orElseThrow()
        assertThat(receipt.status).isEqualTo(ReceiptStatus.approved)
        assertThat(receipt.bonusCredited).isEqualTo(250)
        assertThat(balance(fixture)).isEqualTo(250)
        reconcile.approve(fixture.receipt,"Retry-test")
        assertThat(balance(fixture)).isEqualTo(250)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pharmacist_wallet_entries WHERE pharmacist_id=?",Long::class.java,fixture.person)).isEqualTo(1)
    }

    @Test fun `late Excel in stale persistence context cannot turn concurrent terminal rejection into a credit`() {
        val fixture=fixture()
        val loaded=CountDownLatch(1)
        val rejected=CountDownLatch(1)
        val pool=Executors.newFixedThreadPool(2)
        try {
            val excel=pool.submit(Callable {
                TransactionTemplate(transactions).execute {
                    val stale=receipts.findById(fixture.receipt).orElseThrow()
                    assertThat(stale.status).isEqualTo(ReceiptStatus.moderation_required)
                    loaded.countDown()
                    check(rejected.await(10,TimeUnit.SECONDS))
                    reconcile.ingestExcelRow(ExcelRowInput(stale.fiscalId,null,null,"test-product","TEST",java.math.BigDecimal.ONE,1000,java.time.Instant.now()))
                }
            })
            val reject=pool.submit(Callable {
                check(loaded.await(10,TimeUnit.SECONDS))
                reconcile.reject(fixture.receipt,"HQ-test","Synthetic final rejection")
                rejected.countDown()
            })
            reject.get(20,TimeUnit.SECONDS)
            assertThat(excel.get(20,TimeUnit.SECONDS)).isFalse()
        } finally { pool.shutdownNow() }
        val receipt=receipts.findById(fixture.receipt).orElseThrow()
        assertThat(receipt.status).isEqualTo(ReceiptStatus.rejected)
        assertThat(receipt.bonusCredited).isZero()
        assertThat(balance(fixture)).isZero()
    }

    @Test fun `rejected receipt is terminal and cannot be turned into a new bonus by approval retry`() {
        val fixture=fixture()
        reconcile.reject(fixture.receipt,"HQ-test","Synthetic rejection")
        assertThatThrownBy { reconcile.approve(fixture.receipt,"Other-test") }.isInstanceOf(AppException::class.java)
        reconcile.reject(fixture.receipt,"Retry-test","Cannot change original terminal evidence")
        val receipt=receipts.findById(fixture.receipt).orElseThrow()
        assertThat(receipt.status).isEqualTo(ReceiptStatus.rejected)
        assertThat(receipt.reviewer).isEqualTo("HQ-test")
        assertThat(balance(fixture)).isZero()
    }

    @Test fun `credited receipt cannot be rejected even when legacy status is inconsistent`() {
        val fixture=fixture()
        jdbc.update("UPDATE receipts SET bonus_credited=250,status='flagged' WHERE id=?",fixture.receipt)
        assertThatThrownBy { reconcile.reject(fixture.receipt,"HQ-test","No implicit reversal") }.isInstanceOf(AppException::class.java)
        assertThat(receipts.findById(fixture.receipt).orElseThrow().bonusCredited).isEqualTo(250)
    }

    private data class Fixture(val person: String,val receipt: String)
    private fun fixture(): Fixture {
        val person="decision_${UUID.randomUUID()}"
        val n=sequence.incrementAndGet().toString()
        jdbc.update("INSERT INTO pharmacists(id,name,iin,phone,tier,status) VALUES (?,'TEST',?,?,'Silver','active')",person,n,"+7${n.takeLast(10)}")
        val bonus=pending.save(PendingBonusEntity(id="pb_${UUID.randomUUID()}",pharmacistId=person,pharmacistName="TEST",
            pharmacyId="test-pharmacy",pharmacyName="TEST",sku="test-product",productName="TEST",bonus=250,expectedAmount=1000))
        val receipt=receipts.save(ReceiptEntity(id="receipt_${UUID.randomUUID()}",pharmacistId=person,pharmacistName="TEST",pharmacyId="test-pharmacy",
            pharmacyName="TEST",pendingBonusId=bonus.id,fiscalId="F-${UUID.randomUUID()}",parsedSku="test-product",parsedAmount=1000,
            confirmedByLog=true).also { it.status=ReceiptStatus.moderation_required })
        return Fixture(person,receipt.id)
    }
    private fun balance(fixture: Fixture)=jdbc.queryForObject("SELECT balance FROM pharmacists WHERE id=?",Long::class.java,fixture.person)!!
}
