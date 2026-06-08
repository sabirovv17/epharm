package kz.epharm.receipts.service

import kz.epharm.receipts.dto.ExcelImportResultDto
import kz.epharm.receipts.entity.ExcelImportEntity
import kz.epharm.receipts.entity.ExcelSaleRowEntity
import kz.epharm.receipts.repository.ExcelImportRepository
import kz.epharm.receipts.repository.ExcelSaleRowRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Импорт Excel-выгрузки Стандарт-Н (источник №2). Парсит файл, сохраняет строки для аудита,
 * прогоняет каждую через сверку (ReconcileService.ingestExcelRow) и считает сматченные.
 */
@Service
class ExcelImportService(
    private val parser: ExcelSalesParser,
    private val reconcileService: ReconcileService,
    private val importRepository: ExcelImportRepository,
    private val rowRepository: ExcelSaleRowRepository,
) {

    private val log = LoggerFactory.getLogger(ExcelImportService::class.java)

    @Transactional
    fun import(fileName: String, uploadedBy: String, bytes: ByteArray): ExcelImportResultDto {
        val rows = parser.parse(bytes)
        val importId = "imp_${UUID.randomUUID().toString().substring(0, 8)}"
        importRepository.save(
            ExcelImportEntity(id = importId, fileName = fileName, uploadedBy = uploadedBy, rowsTotal = rows.size),
        )

        var matched = 0
        rows.forEach { row ->
            val ok = reconcileService.ingestExcelRow(row)
            if (ok) matched++
            rowRepository.save(
                ExcelSaleRowEntity(
                    id = "exr_${UUID.randomUUID().toString().substring(0, 8)}",
                    importId = importId,
                    fiscalId = row.fiscalId,
                    pharmacyCode = row.pharmacyCode,
                    cashier = row.cashier,
                    sku = row.sku,
                    productName = row.productName,
                    qty = row.qty,
                    amount = row.amount,
                    soldAt = row.soldAt,
                    matched = ok,
                ),
            )
        }

        val header = importRepository.findById(importId).get().also { it.rowsMatched = matched }
        importRepository.save(header)
        log.info("Excel-импорт {}: {} строк, сматчено {}", fileName, rows.size, matched)
        return ExcelImportResultDto(importId = importId, fileName = fileName, rowsTotal = rows.size, rowsMatched = matched)
    }
}
