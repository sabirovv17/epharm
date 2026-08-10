package kz.epharm.screens.controller

import jakarta.validation.Valid
import kz.epharm.screens.dto.ActivePlaylistDto
import kz.epharm.screens.dto.AssignSlideRequest
import kz.epharm.screens.dto.BroadcastProfileDto
import kz.epharm.screens.dto.BroadcastProfileSummaryDto
import kz.epharm.screens.dto.ConnectedRegistersDto
import kz.epharm.screens.dto.CreatePlaylistRequest
import kz.epharm.screens.dto.PlaylistDto
import kz.epharm.screens.dto.SlideDto
import kz.epharm.screens.dto.SetBroadcastProfilePharmaciesRequest
import kz.epharm.screens.dto.UpdatePlaylistRequest
import kz.epharm.screens.entity.PlaylistStatus
import kz.epharm.screens.service.ScreenPresenceService
import kz.epharm.screens.service.ScreenService
import org.springframework.http.HttpStatus
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate
import java.time.ZoneId

// Indoor-DOOH экраны (ТЗ §3.3): управление контентом из админки.
// Плейлисты (ротация) + библиотека слайдов (видео/картинки в MinIO).
// Назначение плейлистов на конкретные аптеки + расписание + рендер на экранах = Этап 5 (POSM).
@RestController
@RequestMapping("/api/admin/screens")
class ScreenController(
    private val screenService: ScreenService,
    private val screenPresenceService: ScreenPresenceService,
) {

    /**
     * Сколько касс сейчас онлайн (T4) — пульсы за последний TTL. Для каждой кассы резолвим
     * название и адрес аптеки одним batch-запросом (findAllById), чтобы в админ-виджете
     * показывать «Аспект-траст, г.Алматы, Достык 248а», а не сырой pharmacyId.
     */
    @GetMapping("/connected")
    fun connected(): ConnectedRegistersDto = screenPresenceService.connected()

    /** Excel-срез тех же live-данных, которые пользователь видит в карточке подключённых касс. */
    @GetMapping(
        "/connected/export.xlsx",
        produces = ["application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"],
    )
    fun exportConnected(): ResponseEntity<ByteArray> = ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"epharm-posm-screens-${LocalDate.now(ZoneId.of("Asia/Almaty"))}.xlsx\"",
        )
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(screenPresenceService.exportConnectedXlsx())

    // ── Плейлисты ─────────────────────────────────────────────────────────

    @GetMapping("/playlists")
    fun playlists(@RequestParam(required = false) status: PlaylistStatus?): List<PlaylistDto> =
        screenService.listPlaylists(status = status)

    @PostMapping("/playlists")
    @ResponseStatus(HttpStatus.CREATED)
    fun createPlaylist(@Valid @RequestBody req: CreatePlaylistRequest): PlaylistDto =
        screenService.createPlaylist(req)

    @PatchMapping("/playlists/{id}")
    fun updatePlaylist(@PathVariable id: String, @Valid @RequestBody req: UpdatePlaylistRequest): PlaylistDto =
        screenService.updatePlaylist(id, req)

    @DeleteMapping("/playlists/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deletePlaylist(@PathVariable id: String) = screenService.deletePlaylist(id)

    // ── Слайды ──────────────────────────────────────────────────────────────

    @GetMapping("/slides")
    fun slides(): List<SlideDto> = screenService.listSlides()

    /** Загрузка слайда: multipart (file + title + durationSec). Файл → MinIO. */
    @PostMapping("/slides", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun uploadSlide(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("title") title: String,
        @RequestParam(name = "durationSec", defaultValue = "15") durationSec: Int,
    ): SlideDto = screenService.uploadSlide(file, title, durationSec)

    @DeleteMapping("/slides/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteSlide(@PathVariable id: String) = screenService.deleteSlide(id)

    @PostMapping("/slides/{id}/assign")
    fun assignSlide(@PathVariable id: String, @Valid @RequestBody req: AssignSlideRequest): SlideDto =
        screenService.assignSlide(id, req)

    // ── Эфир: основной профиль + индивидуальный профиль группы аптек ───────

    /** Текущий эфир на кассах. Пустой playlistId → плейлиста нет. */
    @GetMapping("/broadcast")
    fun broadcast(): ActivePlaylistDto = screenService.activePlaylistForScreen(null)

    @GetMapping("/broadcast/profiles")
    fun broadcastProfiles(): List<BroadcastProfileSummaryDto> =
        screenService.listBroadcastProfiles()

    @GetMapping("/broadcast/profiles/{id}")
    fun broadcastProfile(@PathVariable id: String): BroadcastProfileDto =
        screenService.getBroadcastProfile(id)

    @PutMapping("/broadcast/profiles/{id}/pharmacies")
    fun setBroadcastProfilePharmacies(
        @PathVariable id: String,
        @Valid @RequestBody req: SetBroadcastProfilePharmaciesRequest,
    ): BroadcastProfileDto = screenService.setBroadcastProfilePharmacies(id, req.pharmacyIds)

    /** Legacy: заменить весь эфир одним роликом. multipart (file + опц. title). */
    @PostMapping("/broadcast", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadBroadcast(
        @RequestParam("file") file: MultipartFile,
        @RequestParam(name = "title", required = false) title: String?,
    ): ActivePlaylistDto = screenService.broadcast(file, title)

    /** Загрузить/заменить один слот 1..12, сохранив остальные ролики плейлиста. */
    @PostMapping("/broadcast/slots/{slot}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun replaceBroadcastSlot(
        @PathVariable slot: Int,
        @RequestParam("file") file: MultipartFile,
        @RequestParam(name = "title", required = false) title: String?,
        @RequestParam(name = "durationSec", defaultValue = "15") durationSec: Int,
    ): ActivePlaylistDto = screenService.replaceBroadcastSlot(slot, file, title, durationSec)

    @PostMapping(
        "/broadcast/profiles/{id}/slots/{slot}",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
    )
    fun replaceBroadcastProfileSlot(
        @PathVariable id: String,
        @PathVariable slot: Int,
        @RequestParam("file") file: MultipartFile,
        @RequestParam(name = "title", required = false) title: String?,
        @RequestParam(name = "durationSec", defaultValue = "15") durationSec: Int,
    ): BroadcastProfileDto =
        screenService.replaceBroadcastProfileSlot(id, slot, file, title, durationSec)

    @DeleteMapping("/broadcast/profiles/{id}/slots/{slot}")
    fun removeBroadcastProfileSlot(
        @PathVariable id: String,
        @PathVariable slot: Int,
    ): BroadcastProfileDto = screenService.removeBroadcastProfileSlot(id, slot)
}
