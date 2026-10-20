package kz.epharm.catalog.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import kz.epharm.shared.validation.BarcodeNormalizer
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "products")
class ProductEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "name", nullable = false)
    var name: String = "",

    @Column(name = "brand", nullable = false)
    var brand: String = "",

    @Column(name = "vendor", nullable = false)
    var vendor: String = "",

    @Column(name = "mnn", nullable = false)
    var mnn: String = "",

    @Column(name = "price", nullable = false)
    var price: Int = 0,

    // Объём/фасовка («150 мл») — показывается в карточке рекомендации (Фаза 2).
    @Column(name = "volume", nullable = false, length = 64)
    var volume: String = "",

    /**
     * Id товара витрины Medusa, если этот товар каталога — позиция витрины.
     * По нему [kz.epharm.promo.service.PromoPriceScheduler] обновляет [price] каждый час,
     * чтобы блок рекомендаций фармацевта всегда показывал актуальную цену. NULL у ручных товаров.
     */
    @Column(name = "medusa_product_id", length = 64)
    var medusaProductId: String? = null,

    /**
     * Штрих-код EAN-13 (из Medusa variant.barcode). Один из точных ключей матчинга корзины
     * POSM-кассы → наш productId. Заполняется для кампанийных/правиловых товаров.
     */
    @Column(name = "barcode", length = 32)
    var barcode: String? = null,

    /** All confirmed aliases of this exact product; barcode remains the legacy primary key. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "barcodes", nullable = false, columnDefinition = "jsonb")
    var barcodes: List<String> = emptyList(),

    /**
     * iPartID кассы Стандарт-Н. Заполняется вручную в кампании/паре правил, когда EAN-13
     * в кассовом логе отсутствует или отличается от Medusa.
     */
    @Column(name = "ipart_id", length = 64)
    var ipartId: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    fun scanBarcodes(): List<String> = BarcodeNormalizer.all(listOf(barcode) + barcodes)

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        if (createdAt == Instant.EPOCH) createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
