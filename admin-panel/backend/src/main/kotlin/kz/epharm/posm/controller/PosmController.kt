package kz.epharm.posm.controller

import jakarta.validation.Valid
import java.security.MessageDigest
import kz.epharm.cdp.dto.CdpLookupRequest
import kz.epharm.cdp.dto.CdpLookupResponse
import kz.epharm.cdp.dto.CdpProfileDto
import kz.epharm.cdp.dto.CdpRegisterRequest
import kz.epharm.appupdate.dto.AppVersionDto
import kz.epharm.appupdate.service.AppReleaseService
import kz.epharm.cdp.service.CdpService
import kz.epharm.posm.dto.OutcomeRequest
import kz.epharm.posm.dto.OutcomeResponse
import kz.epharm.posm.dto.PosSaleRequest
import kz.epharm.posm.dto.PosSaleResponse
import kz.epharm.posm.dto.RecommendRequest
import kz.epharm.posm.dto.RecommendResponse
import kz.epharm.posm.service.PosSaleService
import kz.epharm.posm.service.RecommendationService
import kz.epharm.screens.dto.ActivePlaylistDto
import kz.epharm.screens.service.ScreenService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Module 2 (POSM в Стандарт-Н) — API кассового клиента (ТЗ §4).
 *
 * Аутентификация устройства — заголовок `X-Posm-Key`. Пути под `api/posm` в SecurityConfig
 * permitAll (JWT-фильтр пропускает), реальная защита — проверка ключа здесь. На MVP ключ
 * общий из конфига; в prod — ключ-на-устройство в БД (Stage 4).
 */
@RestController
@RequestMapping("/api/posm")
class PosmController(
    private val recommendationService: RecommendationService,
    private val posSaleService: PosSaleService,
    private val screenService: ScreenService,
    private val cdpService: CdpService,
    private val appReleaseService: AppReleaseService,
    @Value("\${app.posm.device-key:dev-posm-key}") private val deviceKey: String,
) {

    @PostMapping("/recommend")
    fun recommend(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: RecommendRequest,
    ): RecommendResponse {
        requireDeviceKey(key)
        return recommendationService.recommend(req)
    }

    @PostMapping("/recommendations/{eventId}/outcome")
    fun outcome(
        @PathVariable eventId: String,
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: OutcomeRequest,
    ): OutcomeResponse {
        requireDeviceKey(key)
        return recommendationService.recordOutcome(eventId, req)
    }

    /** Источник №1 сверки: завершённый чек из лога кассы. Идемпотентно по saleId. */
    @PostMapping("/sales")
    fun sale(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: PosSaleRequest,
    ): PosSaleResponse {
        requireDeviceKey(key)
        val accepted = posSaleService.record(req)
        return PosSaleResponse(saleId = req.saleId, accepted = accepted)
    }

    /** Stage 3: активный плейлист для 2-го монитора (видео из админки вместо хардкода). */
    @GetMapping("/playlists/active")
    fun activePlaylist(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @RequestParam(required = false) pharmacyId: String?,
    ): ActivePlaylistDto {
        requireDeviceKey(key)
        return screenService.activePlaylistForScreen(pharmacyId)
    }

    /**
     * Авто-обновление клиента: текущий релиз для платформы. Касса сравнивает version со своей
     * и сама обновляется, если новее. current=false → обновляться не нужно.
     */
    @GetMapping("/app/version")
    fun appVersion(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @RequestParam(required = false, defaultValue = "win-x64") platform: String,
    ): AppVersionDto {
        requireDeviceKey(key)
        return appReleaseService.currentFor(platform)
    }

    /** CDP (§5.6): поиск клиента лояльности по телефону. */
    @PostMapping("/cdp/lookup")
    fun cdpLookup(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: CdpLookupRequest,
    ): CdpLookupResponse {
        requireDeviceKey(key)
        return cdpService.lookup(req.phone)
    }

    /** CDP (§5.6): регистрация нового клиента на кассе. Идемпотентно по телефону. */
    @PostMapping("/cdp/register")
    fun cdpRegister(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: CdpRegisterRequest,
    ): CdpProfileDto {
        requireDeviceKey(key)
        return cdpService.register(req)
    }

    private fun requireDeviceKey(key: String?) {
        // Сравнение в постоянное время (MessageDigest.isEqual) — не даёт подобрать ключ
        // по таймингу. Длины могут различаться → сравниваем как байты, isEqual это терпит.
        if (key.isNullOrBlank() ||
            !MessageDigest.isEqual(key.toByteArray(Charsets.UTF_8), deviceKey.toByteArray(Charsets.UTF_8))
        ) {
            throw AppException(ErrorCode.UNAUTHORIZED, "Invalid or missing POSM device key", HttpStatus.UNAUTHORIZED)
        }
    }
}
