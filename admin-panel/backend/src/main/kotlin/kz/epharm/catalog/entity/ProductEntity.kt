package kz.epharm.catalog.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
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
     * По нему [kz.epharm.promo.service.PromoPriceScheduler] обновляет [price] раз в день,
     * чтобы блок рекомендаций фармацевта всегда показывал актуальную цену. NULL у ручных товаров.
     */
    @Column(name = "medusa_product_id", length = 64)
    var medusaProductId: String? = null,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
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
