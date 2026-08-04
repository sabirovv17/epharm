package kz.epharm.screens.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kz.epharm.screens.entity.PlaylistEntity
import kz.epharm.screens.entity.PlaylistStatus
import kz.epharm.screens.entity.SlideEntity
import kz.epharm.screens.entity.SlideKind
import java.time.Instant

/** Создание плейлиста. Слайды добавляются отдельно (assign). */
data class CreatePlaylistRequest(
    @field:NotBlank @field:Size(max = 255)
    val name: String,
    val status: PlaylistStatus? = PlaylistStatus.draft,
)

data class UpdatePlaylistRequest(
    @field:Size(max = 255)
    val name: String? = null,
    val status: PlaylistStatus? = null,
    // Назначение (V016). Трёхзначная семантика через флаг setTarget, т.к. null=«не менять»
    // конфликтует с null=«все экраны». setTarget=true → применить targetPharmacyId
    // (в т.ч. null = сделать глобальным). setTarget=false/отсутствует → назначение не трогаем.
    val setTarget: Boolean? = null,
    @field:Size(max = 64)
    val targetPharmacyId: String? = null,
)

/**
 * Привязка слайда к плейлисту (или открепление при playlistId=null).
 * position — порядок в ротации.
 */
data class AssignSlideRequest(
    val playlistId: String? = null,
    @field:Min(0)
    val position: Int = 0,
)

data class PlaylistDto(
    val id: String,
    val name: String,
    val status: PlaylistStatus,
    val slidesCount: Int,
    val durationSec: Int,
    val pharmacies: Int,
    // V016 — назначение: null = все экраны (глобальный), иначе id конкретной аптеки.
    val pharmacyId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(e: PlaylistEntity): PlaylistDto = PlaylistDto(
            id = e.id,
            name = e.name,
            status = e.status,
            slidesCount = e.slidesCount,
            durationSec = e.durationSec,
            pharmacies = e.pharmacies,
            pharmacyId = e.pharmacyId,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
        )
    }
}

/**
 * Активный плейлист для 2-го монитора кассы (Stage 3). POSM-клиент получает упорядоченный
 * список media-URL (MinIO) и крутит их в цикле вместо захардкоженного promo.mp4.
 */
data class ActivePlaylistDto(
    val playlistId: String?,
    val name: String,
    val slides: List<ActiveSlideDto>,
)

data class ActiveSlideDto(
    val id: String,
    val url: String,
    val kind: SlideKind,
    val durationSec: Int,
    val title: String,
    /** Нулевая позиция в плейлисте. В админке позиции 0..11 отображаются как слоты 1..12. */
    val position: Int,
)

/** Подключённые кассы (T4): сколько устройств онлайн + детали (для админ-виджета на экранах). */
data class ConnectedRegistersDto(
    val total: Int,
    val devices: List<RegisterPresenceDto>,
)

data class RegisterPresenceDto(
    val deviceId: String,
    val pharmacyId: String?,
    // Название и адрес аптеки, резолвятся из справочника по pharmacyId (null, если аптека не
    // найдена/не задана) — чтобы в админ-виджете читалась «Аспект-траст», а не «sloc_…».
    val pharmacyName: String?,
    val pharmacyAddress: String?,
    val lastSeen: Instant,
)

data class SlideDto(
    val id: String,
    val title: String,
    val kind: SlideKind,
    val durationSec: Int,
    val mediaUrl: String,
    val playlistId: String?,
    val position: Int,
) {
    companion object {
        fun of(e: SlideEntity): SlideDto = SlideDto(
            id = e.id,
            title = e.title,
            kind = e.kind,
            durationSec = e.durationSec,
            mediaUrl = e.mediaUrl,
            playlistId = e.playlistId,
            position = e.position,
        )
    }
}
