package kz.epharm.banners.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

enum class BannerStatus { active, draft, archived }

/**
 * Баннер главного экрана мобильного приложения фармацевта (п.5).
 *
 * Баннер ведёт ТОЛЬКО на детальный экран внутри приложения (решение продукта):
 * никаких target-полей на товар/кампанию/URL. Контент — картинка (в MinIO, общий
 * бакет со слайдами экранов) + заголовок + подзаголовок + детальный markdown.
 *
 * Лента мобилки берёт status=active ORDER BY position (как playlists/slides).
 */
@Entity
@Table(name = "banners")
class BannerEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "title", nullable = false)
    var title: String = "",

    @Column(name = "subtitle", length = 512)
    var subtitle: String? = null,

    @Column(name = "image_url", nullable = false, length = 512)
    var imageUrl: String = "",

    @Column(name = "detail_md")
    var detailMd: String? = null,

    @Column(name = "status", nullable = false, length = 32)
    var statusRaw: String = BannerStatus.draft.name,

    @Column(name = "position", nullable = false)
    var position: Int = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var status: BannerStatus
        get() = BannerStatus.valueOf(statusRaw)
        set(value) { statusRaw = value.name }

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
