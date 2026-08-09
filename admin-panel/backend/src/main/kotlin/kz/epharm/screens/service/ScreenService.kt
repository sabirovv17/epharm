package kz.epharm.screens.service

import kz.epharm.screens.dto.ActivePlaylistDto
import kz.epharm.screens.dto.ActiveSlideDto
import kz.epharm.screens.dto.AssignSlideRequest
import kz.epharm.screens.dto.BroadcastProfileDto
import kz.epharm.screens.dto.BroadcastProfileSummaryDto
import kz.epharm.screens.dto.CreatePlaylistRequest
import kz.epharm.screens.dto.PlaylistDto
import kz.epharm.screens.dto.SlideDto
import kz.epharm.screens.dto.UpdatePlaylistRequest
import kz.epharm.screens.entity.PlaylistEntity
import kz.epharm.screens.entity.PlaylistPharmacyAssignmentEntity
import kz.epharm.screens.entity.PlaylistStatus
import kz.epharm.screens.entity.SlideEntity
import kz.epharm.screens.entity.SlideKind
import kz.epharm.pharmacies.repository.PharmacyRepository
import kz.epharm.screens.repository.PlaylistRepository
import kz.epharm.screens.repository.PlaylistPharmacyAssignmentRepository
import kz.epharm.screens.repository.SlideRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.shared.storage.MediaStorage
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@Service
class ScreenService(
    private val playlistRepository: PlaylistRepository,
    private val slideRepository: SlideRepository,
    private val playlistAssignmentRepository: PlaylistPharmacyAssignmentRepository,
    private val pharmacyRepository: PharmacyRepository,
    private val mediaStorage: MediaStorage,
) {
    companion object {
        /** Единственный глобальный плейлист «эфир» на все кассы. */
        const val BROADCAST_PLAYLIST_ID = "pl_broadcast"
        /** Профиль для выбранной группы аптек; пустые слоты наследуются из эфира. */
        const val TARGETED_BROADCAST_PLAYLIST_ID = "pl_broadcast_targeted"
        const val BROADCAST_SLOT_COUNT = 12
    }

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
     * Активный плейлист для POSM. Приоритет совместим со старой схемой V016:
     *   1) legacy-плейлист ровно одной аптеки;
     *   2) профиль группы из playlist_pharmacy_assignments;
     *   3) основной pl_broadcast;
     *   4) legacy-глобальный плейлист во время rolling deploy.
     *
     * Для дочернего профиля возвращается уже собранный список: его собственные
     * позиции перекрывают основной эфир, остальные наследуются. POSM менять не нужно.
     */
    @Transactional(readOnly = true)
    fun activePlaylistForScreen(pharmacyId: String? = null): ActivePlaylistDto {
        val active = PlaylistStatus.active.name
        val normalizedPharmacyId = pharmacyId?.trim()?.takeIf { it.isNotBlank() }
        val legacyDirect = normalizedPharmacyId?.let {
            playlistRepository.findFirstByStatusRawAndPharmacyIdOrderByUpdatedAtDesc(active, it)
        }
        val assigned = normalizedPharmacyId
            ?.let(playlistAssignmentRepository::findByPharmacyId)
            ?.let { playlistRepository.findById(it.playlistId).orElse(null) }
            ?.takeIf { it.status == PlaylistStatus.active }
        val default = playlistRepository.findById(BROADCAST_PLAYLIST_ID).orElse(null)
            ?.takeIf { it.status == PlaylistStatus.active }
        val legacyGlobal = playlistRepository
            .findFirstByStatusRawAndPharmacyIdIsNullAndParentPlaylistIdIsNullOrderByUpdatedAtDesc(active)

        val playlist = legacyDirect
            ?: assigned
            ?: default
            ?: legacyGlobal
            ?: return ActivePlaylistDto(playlistId = null, name = "", slides = emptyList())

        return ActivePlaylistDto(
            playlistId = playlist.id,
            name = playlist.name,
            slides = effectiveSlides(playlist),
        )
    }

    @Transactional(readOnly = true)
    fun listBroadcastProfiles(): List<BroadcastProfileSummaryDto> =
        listOf(BROADCAST_PLAYLIST_ID, TARGETED_BROADCAST_PLAYLIST_ID).mapNotNull { id ->
            playlistRepository.findById(id).orElse(null)?.let { playlist ->
                BroadcastProfileSummaryDto(
                    id = playlist.id,
                    name = playlist.name,
                    defaultProfile = playlist.id == BROADCAST_PLAYLIST_ID,
                    assignedPharmacies = if (playlist.id == BROADCAST_PLAYLIST_ID) {
                        0
                    } else {
                        playlistAssignmentRepository.findAllByPlaylistIdOrderByPharmacyIdAsc(playlist.id).size
                    },
                )
            }
        }

    @Transactional(readOnly = true)
    fun getBroadcastProfile(id: String): BroadcastProfileDto {
        val playlist = loadBroadcastProfileOrThrow(id)
        return toBroadcastProfileDto(playlist)
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

    // ── Эфир: основной профиль + overrides для выбранных аптек ─────────────

    /** Полностью заменяет список аптек профиля одной транзакцией. */
    @Transactional
    fun setBroadcastProfilePharmacies(id: String, pharmacyIds: List<String>): BroadcastProfileDto {
        val playlist = loadBroadcastProfileOrThrow(id)
        if (playlist.id == BROADCAST_PLAYLIST_ID) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Основной плейлист применяется автоматически и не требует списка аптек",
                HttpStatus.BAD_REQUEST,
            )
        }

        val normalized = pharmacyIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (normalized.size > 1_000) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "За один запрос можно назначить не более 1000 аптек",
                HttpStatus.BAD_REQUEST,
            )
        }
        val found = pharmacyRepository.findAllById(normalized).map { it.id }.toSet()
        val missing = normalized.filterNot(found::contains)
        if (missing.isNotEmpty()) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Не найдены аптеки: ${missing.take(10).joinToString()}",
                HttpStatus.BAD_REQUEST,
            )
        }

        playlistAssignmentRepository.deleteAllByPlaylistId(playlist.id)
        playlistAssignmentRepository.flush()
        playlistAssignmentRepository.saveAll(
            normalized.map { pharmacyId ->
                PlaylistPharmacyAssignmentEntity(
                    pharmacyId = pharmacyId,
                    playlistId = playlist.id,
                )
            },
        )
        playlist.pharmacies = normalized.size
        playlistRepository.save(playlist)
        return toBroadcastProfileDto(playlist)
    }

    /**
     * Legacy-операция «заменить весь эфир одним роликом». Сохраняется для обратной
     * совместимости старого UI и интеграций:
     *  1) видео → MinIO;
     *  2) находим/создаём основной BROADCAST_PLAYLIST_ID;
     *  3) удаляем прежние слайды только основного плейлиста;
     *  4) привязываем новый ролик и активируем основной профиль.
     *
     * Старые объекты в MediaStorage удаляются только после успешного commit. Если
     * транзакция откатится, удаляется новый объект, а прежний эфир остаётся целым.
     * Кассы подхватят его поллингом `GET /api/posm/playlists/active`.
     */
    @Transactional
    fun broadcast(file: MultipartFile, title: String?): ActivePlaylistDto {
        val uploaded = uploadBroadcastVideo(file, title, 15)
        registerRollbackCleanup(uploaded.url)
        val playlist = activateBroadcastPlaylist()
        val previous = slideRepository.findAllByPlaylistIdOrderByPositionAsc(playlist.id)
        slideRepository.deleteAll(previous)
        slideRepository.save(uploaded.toEntity(position = 0, playlistId = playlist.id))
        recountPlaylist(playlist.id)
        registerAfterCommitCleanup(previous.map { it.mediaUrl })

        return activePlaylistForScreen(null)
    }

    /**
     * Заменяет ровно один пользовательский слот (1..12), не затрагивая остальные.
     * На POSM заполненные слоты приходят одним упорядоченным плейлистом и крутятся
     * циклически в порядке их номеров; пустые позиции просто пропускаются.
     */
    @Transactional
    fun replaceBroadcastSlot(
        slot: Int,
        file: MultipartFile,
        title: String?,
        durationSec: Int,
    ): ActivePlaylistDto {
        val uploaded = uploadBroadcastVideo(file, title, durationSec)
        registerRollbackCleanup(uploaded.url)
        val playlist = prepareBroadcastPlaylistForSlotUpdate()
        replaceSlot(playlist, slot, uploaded)
        return activePlaylistForScreen(null)
    }

    /** Загрузить override в основной или индивидуальный профиль. */
    @Transactional
    fun replaceBroadcastProfileSlot(
        id: String,
        slot: Int,
        file: MultipartFile,
        title: String?,
        durationSec: Int,
    ): BroadcastProfileDto {
        val uploaded = uploadBroadcastVideo(file, title, durationSec)
        registerRollbackCleanup(uploaded.url)
        val playlist = if (id == BROADCAST_PLAYLIST_ID) {
            prepareBroadcastPlaylistForSlotUpdate()
        } else {
            loadBroadcastProfileOrThrow(id).also {
                it.status = PlaylistStatus.active
                playlistRepository.save(it)
            }
        }
        replaceSlot(playlist, slot, uploaded)
        return toBroadcastProfileDto(playlist)
    }

    /** Удалить собственный слот. Для дочернего профиля сразу проявится основной ролик. */
    @Transactional
    fun removeBroadcastProfileSlot(id: String, slot: Int): BroadcastProfileDto {
        validateSlot(slot)
        val playlist = loadBroadcastProfileOrThrow(id)
        val matches = slideRepository.findAllByPlaylistIdOrderByPositionAsc(playlist.id)
            .filter { it.position == slot - 1 }
        slideRepository.deleteAll(matches)
        recountPlaylist(playlist.id)
        registerAfterCommitCleanup(matches.map { it.mediaUrl })
        return toBroadcastProfileDto(playlist)
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private data class UploadedBroadcastVideo(
        val url: String,
        val title: String,
        val durationSec: Int,
        val kind: SlideKind = SlideKind.video,
    ) {
        fun toEntity(position: Int, playlistId: String): SlideEntity =
            SlideEntity(
                id = "sl_${UUID.randomUUID().toString().substring(0, 8)}",
                title = title,
                durationSec = durationSec,
                mediaUrl = url,
                playlistId = playlistId,
                position = position,
            ).also { it.kind = kind }
    }

    private fun replaceSlot(
        playlist: PlaylistEntity,
        slot: Int,
        uploaded: UploadedBroadcastVideo,
    ) {
        validateSlot(slot)
        val position = slot - 1
        val matches = slideRepository.findAllByPlaylistIdOrderByPositionAsc(playlist.id)
            .filter { it.position == position }
        val target = matches.firstOrNull()
        val previousUrls = matches.map { it.mediaUrl }

        if (target == null) {
            slideRepository.save(uploaded.toEntity(position = position, playlistId = playlist.id))
        } else {
            target.title = uploaded.title
            target.kind = uploaded.kind
            target.durationSec = uploaded.durationSec
            target.mediaUrl = uploaded.url
            target.position = position
            target.playlistId = playlist.id
            slideRepository.save(target)
            // Старые неконсистентные данные не должны давать два ролика в одном слоте.
            if (matches.size > 1) slideRepository.deleteAll(matches.drop(1))
        }

        recountPlaylist(playlist.id)
        registerAfterCommitCleanup(previousUrls)
    }

    private fun validateSlot(slot: Int) {
        if (slot !in 1..BROADCAST_SLOT_COUNT) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Номер слота должен быть от 1 до $BROADCAST_SLOT_COUNT",
                HttpStatus.BAD_REQUEST,
            )
        }
    }

    /** Собирает профиль рекурсивно; собственный слайд всегда перекрывает родителя. */
    private fun effectiveSlides(
        playlist: PlaylistEntity,
        visited: Set<String> = emptySet(),
    ): List<ActiveSlideDto> {
        if (playlist.id in visited) return emptyList()
        val nextVisited = visited + playlist.id
        val merged = linkedMapOf<Int, ActiveSlideDto>()
        playlist.parentPlaylistId
            ?.let { playlistRepository.findById(it).orElse(null) }
            ?.let { parent ->
                effectiveSlides(parent, nextVisited).forEach { slide ->
                    if (slide.position in 0 until BROADCAST_SLOT_COUNT) {
                        merged[slide.position] = slide.copy(inherited = true)
                    }
                }
            }

        val ownPositions = mutableSetOf<Int>()
        slideRepository.findAllByPlaylistIdOrderByPositionAsc(playlist.id).forEach { slide ->
            if (slide.position in 0 until BROADCAST_SLOT_COUNT && ownPositions.add(slide.position)) {
                // Собственный слайд перекрывает унаследованный. Повторные legacy-записи
                // той же позиции игнорируются, чтобы POSM получил ровно один ролик.
                merged[slide.position] = slide.toActiveSlide(inherited = false)
            }
        }
        return merged.toSortedMap().values.toList()
    }

    private fun SlideEntity.toActiveSlide(inherited: Boolean): ActiveSlideDto =
        ActiveSlideDto(
            id = id,
            url = mediaUrl,
            kind = kind,
            durationSec = durationSec,
            title = title,
            position = position,
            inherited = inherited,
        )

    private fun toBroadcastProfileDto(playlist: PlaylistEntity): BroadcastProfileDto =
        BroadcastProfileDto(
            id = playlist.id,
            name = playlist.name,
            defaultProfile = playlist.id == BROADCAST_PLAYLIST_ID,
            assignedPharmacyIds = if (playlist.id == BROADCAST_PLAYLIST_ID) {
                emptyList()
            } else {
                playlistAssignmentRepository.findAllByPlaylistIdOrderByPharmacyIdAsc(playlist.id)
                    .map { it.pharmacyId }
            },
            slides = effectiveSlides(playlist),
        )

    private fun uploadBroadcastVideo(
        file: MultipartFile,
        title: String?,
        durationSec: Int,
    ): UploadedBroadcastVideo {
        if (file.isEmpty) {
            throw AppException(ErrorCode.VALIDATION_FAILED, "Файл пуст", HttpStatus.BAD_REQUEST)
        }
        val contentType = file.contentType.orEmpty()
        if (!contentType.startsWith("video/")) {
            throw AppException(
                ErrorCode.VALIDATION_FAILED,
                "Для эфира разрешены только видеофайлы (получен content-type=$contentType)",
                HttpStatus.BAD_REQUEST,
            )
        }
        val name = title?.trim()?.takeIf { it.isNotBlank() }
            ?: file.originalFilename?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotBlank() }
            ?: "Ролик на кассах"
        return UploadedBroadcastVideo(
            url = mediaStorage.upload(file.bytes, contentType, file.originalFilename ?: "broadcast-video"),
            title = name,
            durationSec = durationSec.coerceIn(1, 86_400),
        )
    }

    /** Активирует основной эфир, не выключая дочерние и legacy-адресные профили. */
    private fun activateBroadcastPlaylist(): PlaylistEntity {
        playlistRepository.findAllByStatusRawOrderByUpdatedAtDesc(PlaylistStatus.active.name)
            .filter {
                it.id != BROADCAST_PLAYLIST_ID &&
                    it.parentPlaylistId == null &&
                    it.pharmacyId == null
            }
            .forEach {
                it.status = PlaylistStatus.draft
                playlistRepository.save(it)
            }

        val playlist = playlistRepository.findById(BROADCAST_PLAYLIST_ID).orElseGet {
            PlaylistEntity(id = BROADCAST_PLAYLIST_ID, name = "Эфир касс")
        }
        playlist.name = "Эфир касс"
        playlist.pharmacyId = null
        playlist.parentPlaylistId = null
        playlist.status = PlaylistStatus.active
        return playlistRepository.save(playlist)
    }

    /**
     * Rolling-deploy compatibility: before the first slot update, the active global
     * playlist may still have a legacy id. Move its visible 1..12 positions into
     * `pl_broadcast` so replacing one slot cannot silently discard the other videos.
     */
    private fun prepareBroadcastPlaylistForSlotUpdate(): PlaylistEntity {
        val activeGlobal = playlistRepository
            .findFirstByStatusRawAndPharmacyIdIsNullAndParentPlaylistIdIsNullOrderByUpdatedAtDesc(
                PlaylistStatus.active.name,
            )
        if (activeGlobal == null || activeGlobal.id == BROADCAST_PLAYLIST_ID) {
            return activateBroadcastPlaylist()
        }

        val broadcast = playlistRepository.findById(BROADCAST_PLAYLIST_ID).orElseGet {
            playlistRepository.save(PlaylistEntity(id = BROADCAST_PLAYLIST_ID, name = "Эфир касс"))
        }
        val currentSlides = slideRepository.findAllByPlaylistIdOrderByPositionAsc(activeGlobal.id)
        val currentUrls = currentSlides.map { it.mediaUrl }.toSet()
        val staleBroadcastSlides = slideRepository.findAllByPlaylistIdOrderByPositionAsc(broadcast.id)
        slideRepository.deleteAll(staleBroadcastSlides)

        val occupied = mutableSetOf<Int>()
        currentSlides.forEach { slide ->
            if (slide.position in 0 until BROADCAST_SLOT_COUNT && occupied.add(slide.position)) {
                slide.playlistId = broadcast.id
            } else {
                // Extra/duplicate legacy entries remain available in the media library.
                slide.playlistId = null
                slide.position = 0
            }
            slideRepository.save(slide)
        }
        recountPlaylist(activeGlobal.id)
        recountPlaylist(broadcast.id)
        registerAfterCommitCleanup(
            staleBroadcastSlides.map { it.mediaUrl }.filterNot(currentUrls::contains),
        )

        return activateBroadcastPlaylist()
    }

    private fun registerRollbackCleanup(newUrl: String) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    mediaStorage.delete(newUrl)
                }
            }
        })
    }

    private fun registerAfterCommitCleanup(previousUrls: List<String>) {
        if (previousUrls.isEmpty()) return
        val uniqueUrls = previousUrls.distinct()
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            uniqueUrls.forEach(mediaStorage::delete)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                uniqueUrls.forEach(mediaStorage::delete)
            }
        })
    }

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

    private fun loadBroadcastProfileOrThrow(id: String): PlaylistEntity {
        if (id !in setOf(BROADCAST_PLAYLIST_ID, TARGETED_BROADCAST_PLAYLIST_ID)) {
            throw AppException(ErrorCode.NOT_FOUND, "Broadcast profile $id not found", HttpStatus.NOT_FOUND)
        }
        return loadPlaylistOrThrow(id)
    }

    private fun loadSlideOrThrow(id: String): SlideEntity =
        slideRepository.findById(id).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Slide $id not found", HttpStatus.NOT_FOUND)
        }
}
