package kz.epharm.screens.service

import kz.epharm.screens.dto.ActivePlaylistDto
import kz.epharm.screens.dto.ActiveSlideDto
import kz.epharm.screens.dto.AssignSlideRequest
import kz.epharm.screens.dto.CreatePlaylistRequest
import kz.epharm.screens.dto.PlaylistDto
import kz.epharm.screens.dto.SlideDto
import kz.epharm.screens.dto.UpdatePlaylistRequest
import kz.epharm.screens.entity.PlaylistEntity
import kz.epharm.screens.entity.PlaylistStatus
import kz.epharm.screens.entity.SlideEntity
import kz.epharm.screens.entity.SlideKind
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.screens.repository.PlaylistRepository
import kz.epharm.screens.repository.SlideRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.shared.storage.MediaStorage
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@Service
class ScreenService(
    private val playlistRepository: PlaylistRepository,
    private val slideRepository: SlideRepository,
    private val pharmacyRepository: PharmacyRepository,
    private val mediaStorage: MediaStorage,
) {

    // ── Чтение ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun listPlaylists(status: PlaylistStatus? = null): List<PlaylistDto> {
        val rows = if (status != null) {
            playlistRepository.findAllByStatusRawOrderByUpdatedAtDesc(status.name)
        } else {
            playlistRepository.findAllByOrderByUpdatedAtDesc()
        }
        return rows.map(PlaylistDto::of)
    }

    @Transactional(readOnly = true)
    fun listSlides(): List<SlideDto> =
        slideRepository.findAllByOrderByCreatedAtDesc().map(SlideDto::of)

    /**
     * Активный плейлист для 2-го монитора кассы (Stage 3, назначение V016). Приоритет:
     *   1) активный плейлист, назначенный ИМЕННО на эту аптеку (pharmacyId) — override;
     *   2) иначе активный ГЛОБАЛЬНЫЙ (pharmacy_id IS NULL) — «загружено на все экраны»;
     *   3) иначе пусто → касса остаётся на локальном promo.mp4.
     * Так «загрузить на конкретный экран» перекрывает «на все экраны» для этой кассы.
     */
    @Transactional(readOnly = true)
    fun activePlaylistForScreen(pharmacyId: String? = null): ActivePlaylistDto {
        val active = PlaylistStatus.active.name
        val playlist = (
            pharmacyId
                ?.takeIf { it.isNotBlank() }
                ?.let { playlistRepository.findFirstByStatusRawAndPharmacyIdOrderByUpdatedAtDesc(active, it) }
            )
            ?: playlistRepository.findFirstByStatusRawAndPharmacyIdIsNullOrderByUpdatedAtDesc(active)
            ?: return ActivePlaylistDto(playlistId = null, name = "", slides = emptyList())

        val slides = slideRepository.findAllByPlaylistIdOrderByPositionAsc(playlist.id).map {
            ActiveSlideDto(url = it.mediaUrl, kind = it.kind, durationSec = it.durationSec, title = it.title)
        }
        return ActivePlaylistDto(playlistId = playlist.id, name = playlist.name, slides = slides)
    }

    // ── Плейлисты ─────────────────────────────────────────────────────────

    @Transactional
    fun createPlaylist(req: CreatePlaylistRequest): PlaylistDto {
        val entity = PlaylistEntity(
            id = "pl_${UUID.randomUUID().toString().substring(0, 8)}",
            name = req.name.trim(),
        ).also { it.status = req.status ?: PlaylistStatus.draft }
        return PlaylistDto.of(playlistRepository.save(entity))
    }

    @Transactional
    fun updatePlaylist(id: String, req: UpdatePlaylistRequest): PlaylistDto {
        val entity = loadPlaylistOrThrow(id)
        req.name?.let { entity.name = it.trim() }
        req.status?.let { entity.status = it }
        // Назначение (V016): применяем ТОЛЬКО при setTarget=true — иначе нельзя отличить
        // «сделать глобальным» (null) от «не трогать назначение».
        if (req.setTarget == true) {
            val target = req.targetPharmacyId?.trim()?.takeIf { it.isNotBlank() }
            if (target != null && !pharmacyRepository.existsById(target)) {
                throw AppException(
                    ErrorCode.VALIDATION_FAILED,
                    "Аптека $target не существует",
                    HttpStatus.BAD_REQUEST,
                )
            }
            entity.pharmacyId = target
        }
        return PlaylistDto.of(playlistRepository.save(entity))
    }

    /** Удаление плейлиста: его слайды откреплятся (остаются в библиотеке), затем delete. */
    @Transactional
    fun deletePlaylist(id: String) {
        val entity = loadPlaylistOrThrow(id)
        slideRepository.findAllByPlaylistIdOrderByPositionAsc(id).forEach {
            it.playlistId = null
            it.position = 0
            slideRepository.save(it)
        }
        playlistRepository.delete(entity)
    }

    // ── Слайды ──────────────────────────────────────────────────────────────

    /**
     * Загрузка слайда: файл → MediaStorage (MinIO) → запись в библиотеку (playlistId=null).
     * kind определяется по content-type. Допускаются только видео и изображения.
     */
    @Transactional
    fun uploadSlide(file: MultipartFile, title: String, durationSec: Int): SlideDto {
        if (title.isBlank()) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Название слайда обязательно", HttpStatus.BAD_REQUEST)
        }
        if (file.isEmpty) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Файл пуст", HttpStatus.BAD_REQUEST)
        }
        val contentType = file.contentType ?: ""
        val kind = when {
            contentType.startsWith("video/") -> SlideKind.video
            contentType.startsWith("image/") -> SlideKind.image
            else -> throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Только видео или изображение (получен content-type=$contentType)",
                HttpStatus.BAD_REQUEST,
            )
        }
        val url = mediaStorage.upload(file.bytes, contentType, file.originalFilename ?: "slide")
        val entity = SlideEntity(
            id = "sl_${UUID.randomUUID().toString().substring(0, 8)}",
            title = title.trim(),
            durationSec = if (durationSec > 0) durationSec else 15,
            mediaUrl = url,
            playlistId = null,
            position = 0,
        ).also { it.kind = kind }
        return SlideDto.of(slideRepository.save(entity))
    }

    /** Удаление слайда: из MinIO (best-effort) + из БД + пересчёт плейлиста, если был привязан. */
    @Transactional
    fun deleteSlide(id: String) {
        val entity = loadSlideOrThrow(id)
        val playlistId = entity.playlistId
        mediaStorage.delete(entity.mediaUrl)
        slideRepository.delete(entity)
        playlistId?.let { recountPlaylist(it) }
    }

    /** Привязать/открепить слайд к плейлисту + пересчёт затронутых плейлистов. */
    @Transactional
    fun assignSlide(id: String, req: AssignSlideRequest): SlideDto {
        val entity = loadSlideOrThrow(id)
        val oldPlaylist = entity.playlistId
        if (req.playlistId != null && !playlistRepository.existsById(req.playlistId)) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Плейлист ${req.playlistId} не существует",
                HttpStatus.BAD_REQUEST,
            )
        }
        entity.playlistId = req.playlistId
        entity.position = req.position
        val saved = slideRepository.save(entity)
        // Пересчёт старого и нового плейлиста (могут отличаться).
        if (oldPlaylist != null && oldPlaylist != req.playlistId) recountPlaylist(oldPlaylist)
        req.playlistId?.let { recountPlaylist(it) }
        return SlideDto.of(saved)
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /** slidesCount = число слайдов в плейлисте, durationSec = сумма их длительностей. */
    private fun recountPlaylist(playlistId: String) {
        val pl = playlistRepository.findById(playlistId).orElse(null) ?: return
        val slides = slideRepository.findAllByPlaylistIdOrderByPositionAsc(playlistId)
        pl.slidesCount = slides.size
        pl.durationSec = slides.sumOf { it.durationSec }
        playlistRepository.save(pl)
    }

    private fun loadPlaylistOrThrow(id: String): PlaylistEntity =
        playlistRepository.findById(id).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Playlist $id not found", HttpStatus.NOT_FOUND)
        }

    private fun loadSlideOrThrow(id: String): SlideEntity =
        slideRepository.findById(id).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Slide $id not found", HttpStatus.NOT_FOUND)
        }
}
