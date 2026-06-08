package kz.epharm.receipts.service

import kz.epharm.receipts.dto.ExcelRowInput
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/**
 * Парсер Excel-выгрузки Стандарт-Н (источник №2). За интерфейсом — как OcrService/MediaStorage:
 * реальную схему колонок на проде уточнит пилот, маппинг — по названиям заголовков (RU),
 * подмена реализации не трогает сверку.
 */
interface ExcelSalesParser {
    fun parse(bytes: ByteArray): List<ExcelRowInput>
}

@Service
class PoiExcelSalesParser : ExcelSalesParser {

    private val log = LoggerFactory.getLogger(PoiExcelSalesParser::class.java)
    private val formatter = DataFormatter()

    /** Алиасы заголовков (lowercase, по вхождению) → каноническое поле. */
    private val aliases: Map<String, List<String>> = mapOf(
        "fiscal" to listOf("чек", "фискал", "№ чека"),
        "pharmacy" to listOf("аптек", "точк", "магазин"),
        "cashier" to listOf("кассир", "фармацевт", "продавец", "оператор"),
        "sku" to listOf("артикул", "код товара", "код"),
        "name" to listOf("наимен", "товар", "номенклат"),
        "qty" to listOf("кол-во", "колич", "кол."),
        "amount" to listOf("сумма", "стоим", "итого"),
        "date" to listOf("дата", "время"),
    )

    override fun parse(bytes: ByteArray): List<ExcelRowInput> {
        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { wb ->
            val sheet = wb.getSheetAt(0) ?: return emptyList()
            val headerRow = sheet.getRow(sheet.firstRowNum) ?: return emptyList()

            val col = mutableMapOf<String, Int>()
            for (cell in headerRow) {
                val text = formatter.formatCellValue(cell).trim().lowercase()
                if (text.isBlank()) continue
                for ((field, keys) in aliases) {
                    if (field !in col && keys.any { text.contains(it) }) {
                        col[field] = cell.columnIndex
                        break
                    }
                }
            }

            val rows = mutableListOf<ExcelRowInput>()
            for (r in (sheet.firstRowNum + 1)..sheet.lastRowNum) {
                val row = sheet.getRow(r) ?: continue
                val input = ExcelRowInput(
                    fiscalId = str(row, col["fiscal"]),
                    pharmacyCode = str(row, col["pharmacy"]),
                    cashier = str(row, col["cashier"]),
                    sku = str(row, col["sku"]),
                    productName = str(row, col["name"]),
                    qty = num(row, col["qty"])?.let { BigDecimal.valueOf(it) },
                    amount = num(row, col["amount"])?.let { Math.round(it) },
                    soldAt = instant(row, col["date"]),
                )
                // пропускаем полностью пустые строки
                if (input.fiscalId == null && input.sku == null && input.amount == null) continue
                rows.add(input)
            }
            log.info("Excel: распознано {} строк (колонки: {})", rows.size, col.keys)
            return rows
        }
    }

    private fun str(row: Row, idx: Int?): String? {
        if (idx == null) return null
        val cell = row.getCell(idx) ?: return null
        return formatter.formatCellValue(cell).trim().ifBlank { null }
    }

    private fun num(row: Row, idx: Int?): Double? {
        if (idx == null) return null
        val cell = row.getCell(idx) ?: return null
        return when (cell.cellType) {
            CellType.NUMERIC -> cell.numericCellValue
            CellType.STRING -> cell.stringCellValue.trim().replace(" ", "").replace(",", ".").toDoubleOrNull()
            CellType.FORMULA -> runCatching { cell.numericCellValue }.getOrNull()
            else -> null
        }
    }

    private fun instant(row: Row, idx: Int?): Instant? {
        if (idx == null) return null
        val cell: Cell = row.getCell(idx) ?: return null
        return runCatching {
            if (cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                cell.localDateTimeCellValue.toInstant(ZoneOffset.UTC)
            } else null
        }.getOrNull()
    }
}
