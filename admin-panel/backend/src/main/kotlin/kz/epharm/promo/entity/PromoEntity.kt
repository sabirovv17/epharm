package kz.epharm.promo.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate

/**
 * Статус промо-кампании. Жизненный цикл:
 *   draft → active ⇄ paused → archived
 * Archived нельзя редактировать через PATCH (см. PromoService.update —
 * аналог RuleService Bug G fix).
 */
enum class PromoStatus { active, draft, paused, archived }

/**
 * Ценовой порог акции (хранится в jsonb-массиве promos.tiers).
 *  - minQty — «от N штук» (порог по количеству);
 *  - price  — цена за единицу при достижении порога, в тенге;
 *  - bonus  — разовый бонус за достижение порога, в тенге (0 у первого порога).
 * Пример ленты: [{1,500,0},{10,600,900},{20,700,1900}].
 */
data class PromoTier(
    val minQty: Int = 0,
    val price: Long = 0,
    val bonus: Long = 0,
)

@Entity
@Table(name = "promos")
class PromoEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "title", nullable = false)
    var title: String = "",

    @Column(name = "status", nullable = false, length = 32)
    var statusRaw: String = PromoStatus.draft.name,

    @Column(name = "brand", nullable = false)
    var brand: String = "",

    @Column(name = "period", nullable = false)
    var period: String = "",

    @Column(name = "pharmacies", nullable = false)
    var pharmacies: Int = 0,

    @Column(name = "budget", nullable = false)
    var budget: Long = 0,

    @Column(name = "spent", nullable = false)
    var spent: Long = 0,

    @Column(name = "kpi", nullable = false)
    var kpi: String = "",

    @Column(name = "cover", nullable = false, length = 16)
    var cover: String = "",

    // ── Товарная акция (V022): линк на товар Medusa + снимок + даты + пороги ──

    /** Id товара витрины Medusa ('prod_...'); NULL у старых кампаний без товара. */
    @Column(name = "medusa_product_id", length = 64)
    var medusaProductId: String? = null,

    /** Снимок названия товара на момент привязки (для списка в админке/деградации). */
    @Column(name = "product_name", nullable = false)
    var productName: String = "",

    /** Снимок фото товара. */
    @Column(name = "product_image", length = 1024)
    var productImage: String? = null,

    /** Ручная замена фото (приоритет над Medusa/снимком). NULL = берём из Medusa. */
    @Column(name = "override_image", length = 1024)
    var overrideImage: String? = null,

    /** Ручная замена описания (приоритет над Medusa). NULL = берём из Medusa. */
    @Column(name = "override_description", columnDefinition = "text")
    var overrideDescription: String? = null,

    /** Ручная замена характеристик (по строке). NULL = берём keyFacts из Medusa. */
    @Column(name = "override_characteristics", columnDefinition = "text")
    var overrideCharacteristics: String? = null,

    @Column(name = "date_start")
    var dateStart: LocalDate? = null,

    @Column(name = "date_end")
    var dateEnd: LocalDate? = null,

    /** Ценовые пороги акции (jsonb-массив). См. [PromoTier]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tiers", nullable = false, columnDefinition = "jsonb")
    var tiers: List<PromoTier> = emptyList(),

    @Column(name = "created_by", nullable = false, length = 64)
    var createdBy: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var status: PromoStatus
        get() = PromoStatus.valueOf(statusRaw)
        set(value) { statusRaw = value.name }

    /**
     * Цена товара (read-only, из Medusa). Хранится в первом ценовом пороге;
     * обновляется планировщиком [kz.epharm.promo.service.PromoPriceScheduler] раз в день.
     */
    val price: Long
        get() = tiers.firstOrNull()?.price ?: 0

    /** Бонус фармацевту за продажу (задаётся в админке). Хранится в пороге. */
    val pharmacistBonus: Long
        get() = tiers.maxOfOrNull { it.bonus } ?: 0

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
