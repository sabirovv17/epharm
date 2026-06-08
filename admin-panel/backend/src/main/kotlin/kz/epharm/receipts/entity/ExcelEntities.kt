package kz.epharm.receipts.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/** Шапка импорта Excel-выгрузки (источник №2 сверки). */
@Entity
@Table(name = "excel_imports")
class ExcelImportEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "file_name", nullable = false)
    var fileName: String = "",

    @Column(name = "uploaded_by", nullable = false, length = 64)
    var uploadedBy: String = "",

    @Column(name = "rows_total", nullable = false)
    var rowsTotal: Int = 0,

    @Column(name = "rows_matched", nullable = false)
    var rowsMatched: Int = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

/** Одна строка Excel-выгрузки (позиция чека). */
@Entity
@Table(name = "excel_sale_rows")
class ExcelSaleRowEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "import_id", nullable = false, length = 64)
    var importId: String = "",

    @Column(name = "fiscal_id", length = 128)
    var fiscalId: String? = null,

    @Column(name = "pharmacy_code", length = 64)
    var pharmacyCode: String? = null,

    @Column(name = "cashier", length = 128)
    var cashier: String? = null,

    @Column(name = "sku", length = 64)
    var sku: String? = null,

    @Column(name = "product_name")
    var productName: String? = null,

    @Column(name = "qty", precision = 12, scale = 3)
    var qty: BigDecimal? = null,

    @Column(name = "amount")
    var amount: Long? = null,

    @Column(name = "sold_at")
    var soldAt: Instant? = null,

    @Column(name = "matched", nullable = false)
    var matched: Boolean = false,
)
