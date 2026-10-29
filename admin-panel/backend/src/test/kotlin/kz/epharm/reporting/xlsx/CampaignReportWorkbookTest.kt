package kz.epharm.reporting.xlsx

import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import kz.epharm.reporting.*
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CampaignReportWorkbookTest {
    @Test fun `preserves original counters methods daily data chart and nullable conversion`() {
        val day = LocalDate.parse("2026-10-01")
        val stamp = Instant.parse("2026-10-02T00:00:00Z")
        val total = ReportCounts(7, 2, 5, 4, 3, 1, 2)
        val report = CampaignReport("pr_legacy", "=PRIVATE()", "Бренд", day, day.plusDays(1), "Asia/Almaty", stamp, stamp,
            total, listOf(ReportKind("substitution", "Замены", total)),
            listOf(ReportDay(day, total), ReportDay(day.plusDays(1), ReportCounts())), listOf("Исходная методика"))
        XSSFWorkbook(ByteArrayInputStream(CampaignReportWorkbook().render(report))).use { book ->
            assertEquals(listOf("Параметры", "Сводка", "Данные"), (0 until book.numberOfSheets).map { book.getSheetName(it) })
            val summary = book.getSheet("Сводка")
            assertEquals(5.0, summary.getRow(1).getCell(3).numericCellValue)
            assertEquals(60.0, summary.getRow(1).getCell(8).numericCellValue)
            val rows = book.getSheet("Данные")
            assertEquals(2, rows.lastRowNum)
            assertEquals(2.0, rows.getRow(1).getCell(7).numericCellValue)
            assertEquals(CellType.BLANK, rows.getRow(2).getCell(8).cellType)
            assertEquals(1, rows.drawingPatriarch.charts.size)
            val params = book.getSheet("Параметры").map { it.getCell(1)?.toString() }
            assertTrue(params.contains("=PRIVATE()")); assertTrue(params.contains("Исходная методика"))
            assertTrue(params.contains("Подтверждённые показы той же когорты"))
        }
    }
}
