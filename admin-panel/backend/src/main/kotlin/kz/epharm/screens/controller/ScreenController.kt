package kz.epharm.screens.controller

import jakarta.validation.Valid
import kz.epharm.screens.dto.AssignSlideRequest
import kz.epharm.screens.dto.CreatePlaylistRequest
import kz.epharm.screens.dto.PlaylistDto
import kz.epharm.screens.dto.SlideDto
import kz.epharm.screens.dto.UpdatePlaylistRequest
import kz.epharm.screens.entity.PlaylistStatus
import kz.epharm.screens.service.ScreenService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

// Indoor-DOOH экраны (ТЗ §3.3): управление контентом из админки.
// Плейлисты (ротация) + библиотека слайдов (видео/картинки в MinIO).
// Назначение плейлистов на конкретные аптеки + расписание + рендер на экранах = Этап 5 (POSM).
@RestController
@RequestMapping("/api/admin/screens")
class ScreenController(private val screenService: ScreenService) {

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
}
