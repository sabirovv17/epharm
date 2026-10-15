package kz.epharm.reporting

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("report-pdf")
class CampaignReportPdfTest {
    @Test fun `Cyrillic demo PDF wraps long titles contains methodology and paginates the complete period`() {
        val from=LocalDate.parse("2025-01-01")
        val days=(0L..365L).map { ReportDay(from.plusDays(it),ReportCounts(10,1,9,2,1)) }
        val report=CampaignReport("DEMO-synthetic","DEMO · Ә Ғ Қ Ң Ұ Ү Һ І · "+"ДлинноеНазваниеПрепарата".repeat(16),"DEMO синтетические данные",from,from.plusDays(365),"Asia/Almaty",Instant.parse("2026-01-02T00:00:00Z"),Instant.parse("2026-01-01T19:00:00Z"),ReportCounts(3660,366,3294,732,366),listOf(ReportKind("substitution","Замены",ReportCounts(3660,366,3294,732,366))),days,CampaignReportService.METHODS)
        val bytes=CampaignReportPdf().render(report)
        Loader.loadPDF(bytes).use { document ->
            assertTrue(document.numberOfPages>3)
            val text=PDFTextStripper().getText(document)
            assertTrue(text.contains("DEMO"));assertTrue(text.contains("Ә Ғ Қ Ң Ұ Ү Һ І"))
            assertTrue(text.contains("Методика"));assertTrue(text.contains("2026-01-01"))
            assertTrue(text.contains("3294"));assertTrue(text.contains("732"))
            assertTrue(text.contains("Одобрено замен"))
            assertTrue(text.contains("Конверсия в продажу"))
            assertEquals(366, report.confirmedSubstitutionSales)
            assertEquals(0, report.confirmedSubstitutionSalesUnder2m)
            assertFalse(text.contains("pharmacistId"))
        }
        (System.getProperty("reporting.demo.output") ?: System.getenv("REPORTING_DEMO_OUTPUT"))?.let { output ->
            val demoDays=(0L..29L).map { day ->
                val confirmed=10+day%7
                ReportDay(from.plusDays(day),ReportCounts(confirmed+3,3,confirmed,2+day%4,day%3,0,minOf(day%3,1)))
            }
            val totals=demoDays.fold(ReportCounts()) { sum,day -> sum+day.counts }
            val demo=report.copy(campaignTitle="DEMO · Рекомендации по кампании «Тестовый препарат»",to=from.plusDays(29),
                totals=totals,days=demoDays,kinds=listOf(ReportKind("substitution","Замены",totals)),
                generatedAt=Instant.parse("2025-02-01T00:00:00Z"),asOfExclusive=Instant.parse("2025-01-30T19:00:00Z"))
            val path=Path.of(output);Files.createDirectories(path.parent);Files.write(path,CampaignReportPdf().render(demo))
        }
    }

    @Test fun `empty one day PDF remains readable and has zero confirmed displays`() {
        val day=LocalDate.parse("2025-01-01")
        val report=CampaignReport("DEMO-empty","DEMO · Пустой отчёт","",day,day,"Asia/Almaty",Instant.parse("2025-01-02T00:00:00Z"),Instant.parse("2025-01-01T19:00:00Z"),ReportCounts(),emptyList(),listOf(ReportDay(day,ReportCounts())),CampaignReportService.METHODS)
        Loader.loadPDF(CampaignReportPdf().render(report)).use { document ->
            val text=PDFTextStripper().getText(document)
            assertTrue(text.contains("Подтверждённые показы: 0"))
            assertTrue(text.contains("Принято фармацевтом: 0"))
            assertTrue(text.contains("Нет подтверждённых показов"))
            assertNull(report.totals.attributedConversionPercent)
            assertNull(report.totals.under2mConversionPercent)
        }
    }
}
