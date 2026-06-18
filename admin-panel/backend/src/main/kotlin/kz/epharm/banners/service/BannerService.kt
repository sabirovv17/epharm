package kz.epharm.banners.service

import kz.epharm.banners.dto.BannerDto
import kz.epharm.banners.dto.BannerImageUploadDto
import kz.epharm.banners.dto.CreateBannerRequest
import kz.epharm.banners.dto.MobileBannerDto
import kz.epharm.banners.dto.UpdateBannerRequest
import kz.epharm.banners.entity.BannerEntity
import kz.epharm.banners.entity.BannerStatus
import kz.epharm.banners.repository.BannerRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.shared.storage.MediaStorage
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@Service
class BannerService(
    private val bannerRepository: BannerRepository,
    private val mediaStorage: MediaStorage,
) {

    // ── Чтение ────────────────────────────────────────────────────────────

    /** Весь список для админки (все статусы), по позиции. */
    @Transactional(readOnly = true)
    fun list(): List<BannerDto> =
        bannerRepository.findAllByOrderByPositionAscCreatedAtDesc().map(BannerDto::of)

    /** Мобильная лента главного экрана: только активные, в порядке позиции. */
    @Transactional(readOnly = true)
    fun activeFeed(): List<MobileBannerDto> =
        bannerRepository.findAllByStatusRawOrderByPositionAsc(BannerStatus.active.name)
            .map(MobileBannerDto::of)

    // ── CRUD ──────────────────────────────────────────────────────────────

    @Transactional
    fun create(req: CreateBannerRequest): BannerDto {
        val entity = BannerEntity(
            id = "bn_${UUID.randomUUID().toString().substring(0, 8)}",
            title = req.title.trim(),
            subtitle = req.subtitle?.trim()?.takeIf { it.isNotEmpty() },
            imageUrl = req.imageUrl?.trim().orEmpty(),
            detailMd = req.detailMd?.takeIf { it.isNotBlank() },
            position = req.position ?: 0,
        ).also { it.status = req.status ?: BannerStatus.draft }
        return BannerDto.of(bannerRepository.save(entity))
    }

    /** Частичное обновление: применяются только не-null поля запроса. */
    @Transactional
    fun update(id: String, req: UpdateBannerRequest): BannerDto {
        val entity = loadOrThrow(id)
        req.title?.trim()?.takeIf { it.isNotEmpty() }?.let { entity.title = it }
        // subtitle/detailMd допускают очистку: пустая строка → null.
        req.subtitle?.let { entity.subtitle = it.trim().takeIf { s -> s.isNotEmpty() } }
        req.imageUrl?.let { entity.imageUrl = it.trim() }
        req.detailMd?.let { entity.detailMd = it.takeIf { d -> d.isNotBlank() } }
        req.status?.let { entity.status = it }
        req.position?.let { entity.position = it }
        return BannerDto.of(bannerRepository.save(entity))
    }

    @Transactional
    fun delete(id: String) {
        val entity = loadOrThrow(id)
        // Картинка лежит в общем медиа-бакете → best-effort удаление (как в screens).
        entity.imageUrl.takeIf { it.isNotBlank() }?.let { mediaStorage.delete(it) }
        bannerRepository.delete(entity)
    }

    /**
     * Загрузка картинки баннера: файл → MediaStorage (MinIO, общий бакет) → URL.
     * Допускаются только изображения (валидация content-type, как uploadSlide).
     */
    @Transactional
    fun uploadImage(file: MultipartFile): BannerImageUploadDto {
        if (file.isEmpty) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Файл пуст", HttpStatus.BAD_REQUEST)
        }
        val contentType = file.contentType ?: ""
        if (!contentType.startsWith("image/")) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Только изображение (получен content-type=$contentType)",
                HttpStatus.BAD_REQUEST,
            )
        }
        val url = mediaStorage.upload(file.bytes, contentType, file.originalFilename ?: "banner")
        return BannerImageUploadDto(imageUrl = url)
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun loadOrThrow(id: String): BannerEntity =
        bannerRepository.findById(id).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Banner $id not found", HttpStatus.NOT_FOUND)
        }
}
