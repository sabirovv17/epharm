package kz.epharm.reporting

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.time.LocalDate
import kz.epharm.shared.error.AppException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CampaignReportServiceTest {
    private val repository=mockk<CampaignReportRepository>()
    private val service=CampaignReportService(repository)

    @Test
    fun `inclusive Almaty dates zero-fill days and preserve distinct recommendation kinds`() {
        every { repository.campaign("campaign") } returns ReportCampaign("campaign","Кампания","Бренд")
        every { repository.aggregate("campaign",Instant.parse("2024-12-31T19:00:00Z"),Instant.parse("2025-01-03T19:00:00Z")) } returns listOf(
            ReportAggregate(LocalDate.parse("2025-01-01"),"substitution",ReportCounts(4,1,3,2,1,1,1)),
            ReportAggregate(LocalDate.parse("2025-01-03"),"crosssell",ReportCounts(1,0,1,0,0,0)),
        )
        val report=service.report("campaign",LocalDate.parse("2025-01-01"),LocalDate.parse("2025-01-03"))
        assertEquals(ReportCounts(5,1,4,2,1,1,1),report.totals)
        assertEquals(1,report.confirmedSubstitutionSalesUnder2m)
        assertEquals(3,report.days.size)
        assertEquals(ReportCounts(),report.days[1].counts)
        assertEquals(3,report.kinds.first().counts.confirmed)
        assertEquals(1,report.kinds.last().counts.confirmed)
        assertEquals("Asia/Almaty",report.timezone)
    }

    @Test
    fun `reject inverted overlong and unsupported dates before database lookup`() {
        val day=LocalDate.parse("2025-01-01")
        assertThrows(AppException::class.java) { service.report("id",day,day.minusDays(1)) }
        assertThrows(AppException::class.java) { service.report("id",day,day.plusDays(366)) }
        assertThrows(AppException::class.java) { service.report("id",LocalDate.MIN,LocalDate.MIN) }
        verify(exactly=0) { repository.campaign(any()) }
    }

    @Test
    fun `366-day empty report is complete and unknown campaign fails closed`() {
        every { repository.campaign("id") } returns ReportCampaign("id","Empty","")
        every { repository.aggregate(any(),any(),any()) } returns emptyList()
        val start=LocalDate.parse("2024-01-01")
        assertEquals(366,service.report("id",start,start.plusDays(365)).days.size)
        every { repository.campaign("missing") } returns null
        assertThrows(AppException::class.java) { service.report("missing",start,start) }
    }
}
