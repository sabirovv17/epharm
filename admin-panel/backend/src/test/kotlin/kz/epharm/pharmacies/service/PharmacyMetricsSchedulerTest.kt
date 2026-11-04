package kz.epharm.pharmacies.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.time.temporal.ChronoUnit
import kz.epharm.pharmacies.entity.PharmacyEntity
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.posm.repository.PharmacyAggRow
import kz.epharm.posm.repository.PharmacyAmountRow
import kz.epharm.posm.repository.PharmacyCountRow
import kz.epharm.posm.repository.PosSaleRepository
import kz.epharm.posm.repository.RecommendationEventRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PharmacyMetricsSchedulerTest {
    private val pharmacies = mockk<PharmacyRepository>()
    private val sales = mockk<PosSaleRepository>()
    private val recommendations = mockk<RecommendationEventRepository>()
    private val scheduler = PharmacyMetricsScheduler(pharmacies, sales, recommendations)

    @Test
    fun `refresh stores exact thirty day receipt amount and accepted recommendation totals`() {
        val now = Instant.parse("2026-11-04T09:00:00Z")
        val pharmacy = PharmacyEntity(
            id = "ph-1",
            name = "Аптека",
            chainId = "chain",
            chainName = "Сеть",
            city = "Алматы",
        ).also {
            it.receipts30d = 1
            it.gmv30d = 1
            it.rulesAccepted = 1
        }
        val count = mockk<PharmacyCountRow> {
            every { pharmacyId } returns pharmacy.id
            every { cnt } returns 2
        }
        val amount = mockk<PharmacyAmountRow> {
            every { pharmacyId } returns pharmacy.id
            every { amount } returns 12_345
        }
        val accepts = mockk<PharmacyAggRow> {
            every { pharmacyId } returns pharmacy.id
            every { impressions } returns 5
            every { accepts } returns 3
        }
        every { sales.countByPharmacySince(now.minus(30, ChronoUnit.DAYS)) } returns listOf(count)
        every { sales.amountByPharmacySince(now.minus(30, ChronoUnit.DAYS)) } returns listOf(amount)
        every { recommendations.aggregateByPharmacySince(Instant.EPOCH) } returns listOf(accepts)
        every { pharmacies.findAll() } returns listOf(pharmacy)
        every { pharmacies.save(pharmacy) } returns pharmacy

        assertThat(scheduler.refreshNow(now)).isEqualTo(1)
        assertThat(pharmacy.receipts30d).isEqualTo(2)
        assertThat(pharmacy.gmv30d).isEqualTo(12_345)
        assertThat(pharmacy.rulesAccepted).isEqualTo(3)
        verify(exactly = 1) { pharmacies.save(pharmacy) }
    }

    @Test
    fun `refresh resets absent rolling sale metrics to zero`() {
        val now = Instant.parse("2026-11-04T09:00:00Z")
        val pharmacy = PharmacyEntity(
            id = "ph-empty",
            name = "Аптека без продаж",
            chainId = "chain",
            chainName = "Сеть",
            city = "Алматы",
        ).also {
            it.receipts30d = 4
            it.gmv30d = 9_999
        }
        every { sales.countByPharmacySince(any()) } returns emptyList()
        every { sales.amountByPharmacySince(any()) } returns emptyList()
        every { recommendations.aggregateByPharmacySince(Instant.EPOCH) } returns emptyList()
        every { pharmacies.findAll() } returns listOf(pharmacy)
        every { pharmacies.save(pharmacy) } returns pharmacy

        assertThat(scheduler.refreshNow(now)).isEqualTo(1)
        assertThat(pharmacy.receipts30d).isZero()
        assertThat(pharmacy.gmv30d).isZero()
    }
}
