package kz.epharm.reporting.xlsx

import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kz.epharm.shared.error.AppException
import org.springframework.http.HttpStatus

class ReportWorkbookTest {
    @Test fun `real workbook preserves identifiers nulls and exact large monetary values without formulas`() {
        val text = listOf("001234567890", "+77000000001", "=HYPERLINK(\"https://example.invalid\")", "\t@SUM(A1)", "-1+1")
        val bytes = ReportWorkbook.render(listOf(ReportSheet("Данные", listOf("ID", "Текст", "Сумма", "Нет данных"),
            text.map { listOf("0001", it, 123L, null) } + listOf(listOf("0002", "Большая сумма", Long.MAX_VALUE, null),
                listOf("0003", "Точная сумма", BigDecimal("1000000000000000.25"), null)))))
        assertEquals('P'.code.toByte(), bytes[0]); assertEquals('K'.code.toByte(), bytes[1])
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { book ->
            val sheet = book.getSheetAt(0)
            text.forEachIndexed { index, value ->
                assertEquals(value, sheet.getRow(index + 1).getCell(1).stringCellValue)
                assertEquals(CellType.STRING, sheet.getRow(index + 1).getCell(1).cellType)
                assertEquals(CellType.BLANK, sheet.getRow(index + 1).getCell(3).cellType)
            }
            assertEquals("0001", sheet.getRow(1).getCell(0).stringCellValue)
            assertEquals(123.0, sheet.getRow(1).getCell(2).numericCellValue)
            assertEquals(Long.MAX_VALUE.toString(), sheet.getRow(6).getCell(2).stringCellValue)
            assertEquals("1000000000000000.25", sheet.getRow(7).getCell(2).stringCellValue)
            assertTrue(sheet.getRow(3).getCell(1).cellStyle.quotePrefixed)
        }
    }

    @Test fun `over limit export fails explicitly instead of truncating rows or text`() {
        assertThrows<AppException> { ReportWorkbook.render(listOf(ReportSheet("Данные", listOf("ID"),
            List(ReportWorkbook.MAX_ROWS + 1) { listOf(it) }))) }
        assertThrows<AppException> { ReportWorkbook.render(listOf(ReportSheet("Данные", listOf("Текст"), listOf(listOf("x".repeat(32768)))))) }
    }

    @Test fun `malformed row cannot silently lose columns`() {
        assertThrows<IllegalArgumentException> { ReportWorkbook.render(listOf(ReportSheet("Данные", listOf("A", "B"), listOf(listOf(1))))) }
    }

    @Test fun `cell headroom bound rejects a wide report explicitly and releases its slot`() {
        val columns = List(200) { "Column $it" }
        val row = List<Any?>(columns.size) { 1 }
        val error = assertThrows<AppException> { ReportWorkbook.render(listOf(ReportSheet("Данные", columns, List(1000) { row }))) }
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.status)
        assertTrue(error.message.orEmpty().contains(ReportWorkbook.MAX_CELLS.toString()))
        assertTrue(ReportWorkbook.render(smallReport()).isNotEmpty())
    }

    @Test fun `only one workbook allocates at a time and a failed renderer releases its slot`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val first = executor.submit<ByteArray> {
            ReportWorkbook.render(smallReport()) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            var decoratorCalled = false
            val error = assertThrows<AppException> { ReportWorkbook.render(smallReport()) { decoratorCalled = true } }
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.status)
            assertFalse(decoratorCalled)
        } finally {
            release.countDown()
            try { assertTrue(first.get(10, TimeUnit.SECONDS).isNotEmpty()) } finally { executor.shutdownNow() }
        }
        assertThrows<IllegalStateException> { ReportWorkbook.render(smallReport()) { throw IllegalStateException("Synthetic failure") } }
        assertTrue(ReportWorkbook.render(smallReport()).isNotEmpty())
    }

    private fun smallReport() = listOf(ReportSheet("Данные", listOf("ID"), listOf(listOf("synthetic"))))
}
