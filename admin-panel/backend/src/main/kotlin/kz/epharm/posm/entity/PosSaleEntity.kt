package kz.epharm.posm.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** Позиция чека внутри pos_sale (хранится в jsonb). sku — iPartID кассы, barcode — EAN-13 (ключ матчинга). */
data class PosSaleItem(
    val sku: String = "",
    val barcode: String? = null,
    val name: String = "",
    val qty: Double = 1.0,
    val price: Long = 0,
    val total: Long = 0,
)

/**
 * Источник №1 сверки — завершённый чек, подтверждённый логом кассы Стандарт-Н.
 * POSM-клиент шлёт после печати в /api/posm/sales. id = client-GUID (идемпотентность ретраев).
 */
@Entity
@Table(name = "pos_sales")
class PosSaleEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "session_id", length = 64)
    var sessionId: String? = null,

    @Column(name = "pharmacist_id", nullable = false, length = 64)
    var pharmacistId: String = "",

    @Column(name = "pharmacist_name")
    var pharmacistName: String? = null,

    @Column(name = "reported_pharmacist_id")
    var reportedPharmacistId: String? = null,

    @Column(name = "reported_pharmacist_name")
    var reportedPharmacistName: String? = null,

    @Column(name = "pharmacist_source", nullable = false, length = 32)
    var pharmacistSource: String = "legacy",

    @Column(name = "pharmacy_id", nullable = false, length = 64)
    var pharmacyId: String = "",

    @Column(name = "fiscal_id", length = 128)
    var fiscalId: String? = null,

    @Column(name = "cashier", length = 128)
    var cashier: String? = null,

    @Column(name = "shift", length = 64)
    var shift: String? = null,

    @Column(name = "total_amount", nullable = false)
    var totalAmount: Long = 0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items", nullable = false, columnDefinition = "jsonb")
    var items: List<PosSaleItem> = emptyList(),

    @Column(name = "printed_at", nullable = false)
    var printedAt: Instant = Instant.now(),

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
