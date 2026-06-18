package kz.epharm.banners.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kz.epharm.banners.entity.BannerEntity
import kz.epharm.banners.entity.BannerStatus
import java.time.Instant

/**
 * Создание баннера. Картинка загружается отдельно (POST /image → imageUrl),
 * затем её URL передаётся сюда. status по умолчанию draft.
 */
data class CreateBannerRequest(
    @field:NotBlank @field:Size(max = 255)
    val title: String,
    @field:Size(max = 512)
    val subtitle: String? = null,
    @field:Size(max = 512)
    val imageUrl: String? = null,
    val detailMd: String? = null,
    val status: BannerStatus? = BannerStatus.draft,
    @field:Min(0)
    val position: Int? = null,
)

/**
 * Частичное обновление: применяются только не-null поля (null = «не трогать»).
 * Подзаголовок/детальный текст можно очистить, прислав пустую строку.
 */
data class UpdateBannerRequest(
    @field:Size(max = 255)
    val title: String? = null,
    @field:Size(max = 512)
    val subtitle: String? = null,
    @field:Size(max = 512)
    val imageUrl: String? = null,
    val detailMd: String? = null,
    val status: BannerStatus? = null,
    @field:Min(0)
    val position: Int? = null,
)

/** Админская карточка баннера (все статусы). */
data class BannerDto(
    val id: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String,
    val detailMd: String?,
    val status: BannerStatus,
    val position: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(e: BannerEntity): BannerDto = BannerDto(
            id = e.id,
            title = e.title,
            subtitle = e.subtitle,
            imageUrl = e.imageUrl,
            detailMd = e.detailMd,
            status = e.status,
            position = e.position,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
        )
    }
}

/**
 * Баннер для мобильной ленты главного экрана. Только активные, в порядке position.
 * Без служебных полей (status/position/таймстампов) — клиент тапает → детальный экран.
 */
data class MobileBannerDto(
    val id: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String,
    val detailMd: String?,
) {
    companion object {
        fun of(e: BannerEntity): MobileBannerDto = MobileBannerDto(
            id = e.id,
            title = e.title,
            subtitle = e.subtitle,
            imageUrl = e.imageUrl,
            detailMd = e.detailMd,
        )
    }
}

/** Ответ загрузки картинки баннера. */
data class BannerImageUploadDto(
    val imageUrl: String,
)
