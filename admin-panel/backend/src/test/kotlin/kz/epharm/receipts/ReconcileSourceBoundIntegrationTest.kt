package kz.epharm.receipts

import java.time.Instant
import java.util.UUID
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.PosSaleItem
import kz.epharm.posm.entity.RecommendationEventEntity
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.receipts.dto.ExcelRowInput
import kz.epharm.receipts.dto.LogSaleInput
import kz.epharm.receipts.dto.LogSaleItem
import kz.epharm.receipts.entity.PendingBonusEntity
import kz.epharm.receipts.entity.ReceiptEntity
import kz.epharm.receipts.entity.ReceiptStatus
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.repository.ReceiptRepository
import kz.epharm.receipts.service.ReconcileService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
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
class ReconcileSourceBoundIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("source_bound_reconcile").withUsername("epharm").withPassword("test-only").apply { start() }
        @JvmStatic @DynamicPropertySource fun properties(reg: DynamicPropertyRegistry) {
            reg.add("spring.datasource.url") { postgres.jdbcUrl }
            reg.add("spring.datasource.username") { postgres.username }
            reg.add("spring.datasource.password") { postgres.password }
        }
    }
    @Autowired lateinit var reconcile: ReconcileService
    @Autowired lateinit var pending: PendingBonusRepository
    @Autowired lateinit var receipts: ReceiptRepository
    @Autowired lateinit var sales: PosSaleRepository
    @Autowired lateinit var events: RecommendationEventRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    private val pharmacy = "ph_${UUID.randomUUID()}"
    private val person = "pharmacist_${UUID.randomUUID()}"
    private val now = Instant.now()

    @BeforeEach fun person() {
        jdbc.update("INSERT INTO chains(id,name,\"group\") VALUES (?,'TEST','pilot')", pharmacy)
        jdbc.update("INSERT INTO pharmacies(id,name,chain_id,chain_name,city,\"group\") VALUES (?,'TEST',?,'TEST','TEST','pilot')", pharmacy, pharmacy)
        jdbc.update("INSERT INTO pharmacists(id,name,iin,phone,pharmacy_id,pharmacy_name,tier,status) VALUES (?,'TEST','900101300011','+77001112233',?,'TEST','Silver','active')", person, pharmacy)
    }

    @Test fun `source bound evidence always stays manual even when legacy log and Excel also arrive`() {
        val (reservation,sale) = bound()
        reconcile.ingestVerifiedRecommendationSale(reservation.id,sale.id)
        assertThat(receipt(reservation).status).isEqualTo(ReceiptStatus.moderation_required)
        reconcile.ingestLogSale(log(sale))
        assertThat(reconcile.ingestExcelRow(excel(sale.fiscalId))).isFalse()
        assertThat(receipt(reservation).status).isEqualTo(ReceiptStatus.moderation_required)
        assertThat(receipt(reservation).confirmedByExcel).isFalse()
        assertThat(balance()).isZero()
    }

    @Test fun `legacy SKU heuristic cannot steal a source bound reservation before receipt creation`() {
        val (reservation,sale) = bound()
        reconcile.ingestLogSale(log(sale))
        assertThat(reconcile.ingestExcelRow(excel(null))).isFalse()
        assertThat(receipts.findFirstByPendingBonusId(reservation.id)).isNull()
        assertThat(balance()).isZero()
    }

    @Test fun `rejected source bound receipt never reopens on printed or Excel retry`() {
        val (reservation,sale) = bound()
        reconcile.ingestVerifiedRecommendationSale(reservation.id,sale.id)
        val rejected = receipt(reservation)
        rejected.status=ReceiptStatus.rejected
        receipts.saveAndFlush(rejected)
        reconcile.ingestVerifiedRecommendationSale(reservation.id,sale.id)
        reconcile.ingestLogSale(log(sale))
        assertThat(reconcile.ingestExcelRow(excel(sale.fiscalId))).isFalse()
        assertThat(receipt(reservation).status).isEqualTo(ReceiptStatus.rejected)
        assertThat(balance()).isZero()
    }

    @Test fun `same fiscal number on two receipts is not a unique Excel proof`() {
        for (index in 1..2) {
            val reservation=pending.save(PendingBonusEntity(id="pb_${UUID.randomUUID()}",pharmacistId=person,pharmacistName="TEST",
                pharmacyId=pharmacy,pharmacyName="TEST",sku="product-test",productName="TEST",expectedAmount=1000,bonus=100))
            receipts.save(ReceiptEntity(id="receipt_${UUID.randomUUID()}",pharmacistId=person,pharmacistName="TEST",pharmacyId=pharmacy,
                pharmacyName="TEST",fiscalId="shared-fiscal",parsedSku="product-test",parsedAmount=1000,pendingBonusId=reservation.id,
                confirmedByLog=true).also { it.status=ReceiptStatus.moderation_required })
        }
        assertThat(reconcile.ingestExcelRow(excel("shared-fiscal"))).isFalse()
        assertThat(receipts.findAllByFiscalId("shared-fiscal")).allMatch { it.status==ReceiptStatus.moderation_required }
        assertThat(balance()).isZero()
    }

    @Test fun `narrow receipt requires actual same session display and never accepts cancellation close capture`() {
        val (reservation,sale) = bound(displayed=false)
        reconcile.ingestVerifiedRecommendationSale(reservation.id,sale.id)
        assertThat(receipts.findFirstByPendingBonusId(reservation.id)).isNull()
        val event=events.findAllBySessionId(sale.sessionId!!).single()
        event.displayedAt=now.minusSeconds(1)
        events.saveAndFlush(event)
        sale.captureSource="standardn-firebird-close"
        sales.saveAndFlush(sale)
        reconcile.ingestVerifiedRecommendationSale(reservation.id,sale.id)
        assertThat(receipts.findFirstByPendingBonusId(reservation.id)).isNull()
        assertThat(balance()).isZero()
    }

    private fun bound(displayed: Boolean=true): Pair<PendingBonusEntity,PosSaleEntity> {
        val sale=sales.save(PosSaleEntity(id="sale_${UUID.randomUUID()}",sessionId="session_${UUID.randomUUID()}",pharmacistId=person,
            pharmacyId=pharmacy,pharmacistSource="posm_internal",captureSource="standardn-print-log",fiscalId="fiscal_${UUID.randomUUID()}",totalAmount=1000,
            items=listOf(PosSaleItem("123",barcode="4600000000017",name="TEST",qty=1.0,price=1000,total=1000,productId="product-test")),printedAt=now))
        val reservation=pending.save(PendingBonusEntity(id="pb_${UUID.randomUUID()}",pharmacistId=person,pharmacistName="TEST",
            pharmacyId=pharmacy,pharmacyName="TEST",sku="product-test",productName="TEST",expectedAmount=1000,bonus=100,sourceSaleId=sale.id))
        events.save(RecommendationEventEntity(id="event_${UUID.randomUUID()}",sessionId=sale.sessionId!!,pharmacistId=person,pharmacyId=pharmacy,
            ruleId="test-rule",recommendSku="product-test",recommendName="TEST",pendingBonusId=reservation.id,
            displayedAt=if(displayed) now.minusSeconds(1) else null))
        return reservation to sale
    }
    private fun receipt(reservation: PendingBonusEntity)=receipts.findFirstByPendingBonusId(reservation.id)!!
    private fun log(sale: PosSaleEntity)=LogSaleInput(person,pharmacy,sale.fiscalId,null,sale.printedAt,listOf(LogSaleItem("product-test",1.0,1000,1000)))
    private fun excel(fiscal: String?)=ExcelRowInput(fiscal,pharmacy,null,"product-test","TEST",java.math.BigDecimal.ONE,1000,now)
    private fun balance()=jdbc.queryForObject("SELECT balance FROM pharmacists WHERE id=?",Long::class.java,person)!!
}
