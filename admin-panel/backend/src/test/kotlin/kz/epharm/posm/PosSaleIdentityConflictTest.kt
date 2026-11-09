package kz.epharm.posm

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import kz.epharm.posm.dto.PosSaleRequest
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.service.PharmacistIdentitySource
import kz.epharm.posm.service.PosSaleService
import kz.epharm.posm.service.PosmPharmacistIdentity
import kz.epharm.posm.service.RecommendationAttributionService
import kz.epharm.posm.service.RecommendationSaleBonusService
import kz.epharm.posm.service.RulesEngineService
import kz.epharm.receipts.service.ReconcileService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional

/** Isolated repository fakes; no database, sockets, production payload or financial repair. */
class PosSaleIdentityConflictTest {
    private val repository = mockk<PosSaleRepository>()
    private val reconcile = mockk<ReconcileService>()
    private val rules = mockk<RulesEngineService>()
    private val attribution = mockk<RecommendationAttributionService>()
    private val bonuses = mockk<RecommendationSaleBonusService>(relaxed = true)
    private val service = PosSaleService(repository, reconcile, rules, attribution, "standardn-kkm-sdk,ofd-api", bonuses)
    private val identity = PosmPharmacistIdentity("", null, PharmacistIdentitySource.UNRESOLVED, null, null)
    private val request = PosSaleRequest(
        saleId = "DEV-SALE-NOT-REAL", pharmacyId = "DEV-PHARMACY-NOT-REAL", sessionId = "DEV-SESSION-NOT-REAL",
        sourceDocumentId = 17, totalAmount = 1234, printedAt = Instant.parse("2026-11-09T00:00:00Z"),
    )
    private val existing = PosSaleEntity(
        id = request.saleId, pharmacyId = request.pharmacyId, sessionId = request.sessionId,
        sourceDocumentId = request.sourceDocumentId, totalAmount = request.totalAmount, printedAt = request.printedAt,
    )
    private val diagnosticPrefix = "pos_sale identity conflict: saleFingerprint=60c5262cc8b8ad883991013d changedFields="

    @ParameterizedTest
    @ValueSource(strings = ["pharmacyId", "sessionId", "totalAmount", "sourceDocumentId"])
    fun `each identity mismatch remains a 409 without overwriting or crediting`(field: String) {
        val changed = when (field) {
            "pharmacyId" -> request.copy(pharmacyId = "OTHER-PHARMACY")
            "sessionId" -> request.copy(sessionId = "OTHER-CHECKOUT")
            "totalAmount" -> request.copy(totalAmount = 9999)
            else -> request.copy(sourceDocumentId = 99)
        }
        val messages = captureLogs { reject(changed) }
        assertEquals(listOf(diagnosticPrefix + field), messages)
        assertEquals(request.pharmacyId, existing.pharmacyId)
        assertEquals(request.sessionId, existing.sessionId)
        assertEquals(request.totalAmount, existing.totalAmount)
        assertEquals(request.sourceDocumentId, existing.sourceDocumentId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["sessionId", "sourceDocumentId"])
    fun `missing context cannot silently turn a conflict into an accepted duplicate`(field: String) {
        val changed = if (field == "sessionId") request.copy(sessionId = null) else request.copy(sourceDocumentId = null)
        assertEquals(listOf(diagnosticPrefix + field), captureLogs { reject(changed) })
    }

    @Test
    fun `combined conflict logs only constant field names and no identifiers names or money`() {
        val changed = request.copy(pharmacyId = "SENSITIVE-PHARMACY", sessionId = "SENSITIVE-SESSION", totalAmount = 9999,
            sourceDocumentId = 99, pharmacistName = "SENSITIVE-PERSON", cashier = "SENSITIVE-CASHIER")
        assertEquals(listOf(diagnosticPrefix + "pharmacyId,sessionId,totalAmount,sourceDocumentId"),
            captureLogs { reject(changed) })
    }

    @Test
    fun `conflict fingerprint is stable across retries and distinct for different receipt ids`() {
        val changed = request.copy(totalAmount = 9999)
        val replay = captureLogs { reject(changed); reject(changed) }
        assertEquals(listOf(diagnosticPrefix + "totalAmount", diagnosticPrefix + "totalAmount"), replay)
        val another = changed.copy(saleId = "DEV-OTHER-SALE-NOT-REAL")
        val anotherExisting = PosSaleEntity(id = another.saleId, pharmacyId = request.pharmacyId,
            sessionId = request.sessionId, sourceDocumentId = request.sourceDocumentId, totalAmount = request.totalAmount)
        every { repository.findById(another.saleId) } returns Optional.of(anotherExisting)
        val other = captureLogs { reject(another) }
        assertEquals(listOf("pos_sale identity conflict: saleFingerprint=dae0b8ddab8017c6a1a564a4 changedFields=totalAmount"), other)
    }

    @Test
    fun `unchanged retry is still idempotent without save reconciliation attribution or new credits`() {
        every { repository.findById(request.saleId) } returns Optional.of(existing)
        assertFalse(service.record(request, identity))
        verify(exactly = 0) { repository.save(any()) }
        verify { reconcile wasNot Called; rules wasNot Called; attribution wasNot Called }
        verify(exactly = 0) { bonuses.afterSale(any()) }
    }

    private fun reject(changed: PosSaleRequest) {
        every { repository.findById(request.saleId) } returns Optional.of(existing)
        val error = assertThrows(ResponseStatusException::class.java) { service.record(changed, identity) }
        assertEquals(HttpStatus.CONFLICT, error.statusCode)
        assertEquals("Sale identity conflicts with the stored sale", error.reason)
        verify(exactly = 0) { repository.save(any()) }
        verify { reconcile wasNot Called; rules wasNot Called; attribution wasNot Called }
        verify(exactly = 0) { bonuses.afterSale(any()) }
    }

    private fun captureLogs(action: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(PosSaleService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            action()
            appender.list.filter { it.formattedMessage.startsWith("pos_sale identity conflict:") }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
