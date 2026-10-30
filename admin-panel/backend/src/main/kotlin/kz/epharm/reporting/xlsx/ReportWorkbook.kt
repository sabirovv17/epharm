package kz.epharm.reporting.xlsx

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.concurrent.Semaphore
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.http.HttpStatus
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode

data class ReportSheet(val name: String, val columns: List<String>, val rows: List<List<Any?>>)

/** Only server-selected columns enter a report. Text is always an OOXML string, never a formula. */
object ReportWorkbook {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val MAX_ROWS = 50_000
    const val MAX_CELLS = 200_000
    private const val MAX_TEXT = 32_767
    private val formulaPrefix = Regex("^[\\s\\p{Cc}]*[=+@-]")
    private val renderSlot = Semaphore(1)

    fun render(sheets: List<ReportSheet>, decorate: (XSSFWorkbook) -> Unit = {}): ByteArray {
        if (!renderSlot.tryAcquire()) throw AppException(ErrorCode.CONFLICT,
            "Другой XLSX ещё формируется: повторите скачивание позже", HttpStatus.TOO_MANY_REQUESTS)
        try {
            return renderAcquired(sheets, decorate)
        } finally {
            renderSlot.release()
        }
    }

    private fun renderAcquired(sheets: List<ReportSheet>, decorate: (XSSFWorkbook) -> Unit): ByteArray {
        require(sheets.isNotEmpty() && sheets.map { it.name }.distinct().size == sheets.size)
        val cells = sheets.sumOf { (it.rows.size.toLong() + 1) * it.columns.size }
        if (sheets.any { it.rows.size > MAX_ROWS } || cells > MAX_CELLS) tooLarge()
        XSSFWorkbook().use { book ->
            val header = book.createCellStyle().apply {
                setFont(book.createFont().apply { bold = true })
                fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
            }
            val quoted = book.createCellStyle().apply { quotePrefixed = true }
            for (source in sheets) {
                require(source.columns.isNotEmpty())
                val sheet = book.createSheet(source.name)
                (listOf(source.columns) + source.rows).forEachIndexed { rowIndex, values ->
                    require(values.size == source.columns.size) { "Report row does not match its columns" }
                    val row = sheet.createRow(rowIndex)
                    values.forEachIndexed { index, value ->
                        val cell = row.createCell(index)
                        when (value) {
                            null -> cell.setBlank()
                            is Boolean -> cell.setCellValue(value)
                            is Byte, is Short, is Int -> cell.setCellValue((value as Number).toDouble())
                            is Long -> if (value in -999_999_999_999_999L..999_999_999_999_999L) {
                                cell.setCellValue(value.toDouble())
                            } else cell.setCellValue(value.toString()) // Excel's 15-digit precision cannot preserve this integer.
                            is Double -> { require(value.isFinite()); cell.setCellValue(value) }
                            is BigDecimal -> cell.setCellValue(value.toPlainString())
                            else -> {
                                val text = value.toString()
                                if (text.length > MAX_TEXT) tooLarge()
                                cell.setCellType(CellType.STRING)
                                cell.setCellValue(text)
                                if (formulaPrefix.containsMatchIn(text)) cell.cellStyle = quoted
                            }
                        }
                        if (rowIndex == 0) cell.cellStyle = header
                    }
                }
                sheet.createFreezePane(0, 1)
                if (source.rows.isNotEmpty()) sheet.setAutoFilter(CellRangeAddress(0, source.rows.size, 0, source.columns.lastIndex))
                source.columns.indices.forEach { sheet.setColumnWidth(it, 24 * 256) }
            }
            decorate(book)
            return ByteArrayOutputStream().use { output -> book.write(output); output.toByteArray() }
        }
    }

    private fun tooLarge(): Nothing = throw AppException(ErrorCode.VALIDATION_FAILED,
        "Отчёт превышает лимит XLSX ($MAX_ROWS строк / $MAX_CELLS ячеек): сузьте период или разделите выборку; строки не усечены", HttpStatus.PAYLOAD_TOO_LARGE)
}
