package kz.epharm.posm

import io.mockk.mockk
import io.mockk.verify
import io.mockk.Called
import java.time.format.DateTimeParseException
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.posm.entity.PosSaleEntity
import kz.epharm.posm.entity.RecommendationEventEntity
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import kz.epharm.posm.service.RecommendationSaleBonusService
import kz.epharm.posm.service.RulesEngineService
import kz.epharm.receipts.repository.PendingBonusRepository
import kz.epharm.receipts.service.PendingBonusService
import kz.epharm.receipts.service.ReconcileService
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class RecommendationSaleBonusPolicyTest {
    private val events = mockk<RecommendationEventRepository>()
    private val sales = mockk<PosSaleRepository>()
    private val pending = mockk<PendingBonusRepository>()
    private val pharmacists = mockk<PharmacistRepository>()
    private val rules = mockk<RulesEngineService>()
    private val registrar = mockk<PendingBonusService>()
    private val reconcile = mockk<ReconcileService>()
    private val jdbc = mockk<JdbcTemplate>()

    private fun service(enabled: Boolean, cutoff: String = "") = RecommendationSaleBonusService(
        events, sales, pending, pharmacists, rules, registrar, reconcile, jdbc, enabled, cutoff,
    )

    @Test
    fun `disabled default does not lock query or create monetary evidence`() {
        val service = service(false)
        service.beforeSale("session")
        service.afterDisplay(RecommendationEventEntity())
        service.afterSale(PosSaleEntity())
        verify { listOf(events, sales, pending, pharmacists, rules, registrar, reconcile, jdbc) wasNot Called }
    }

    @Test
    fun `enabling without a trusted explicit cutoff fails closed`() {
        assertThrows(IllegalArgumentException::class.java) { service(true) }
    }

    @Test
    fun `invalid cutoff cannot silently opt historical data into rewards`() {
        assertThrows(DateTimeParseException::class.java) { service(true, "latest") }
    }
}
