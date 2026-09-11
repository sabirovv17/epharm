package kz.epharm.posm.controller

import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.Valid
import kz.epharm.cdp.dto.CdpLookupRequest
import kz.epharm.cdp.dto.CdpLookupResponse
import kz.epharm.cdp.dto.CdpProfileDto
import kz.epharm.cdp.dto.CdpRegisterRequest
import kz.epharm.appupdate.dto.AppVersionDto
import kz.epharm.appupdate.service.AppReleaseService
import kz.epharm.cdp.service.CdpService
import kz.epharm.merchtasks.dto.MerchTaskShownRequest
import kz.epharm.merchtasks.service.MerchTaskClient
import kz.epharm.posm.dto.HeartbeatResponse
import kz.epharm.posm.dto.MarkShownRequest
import kz.epharm.posm.dto.MarkShownResponse
import kz.epharm.posm.dto.OutcomeRequest
import kz.epharm.posm.dto.OutcomeResponse
import kz.epharm.posm.dto.PosSaleRequest
import kz.epharm.posm.dto.PosSaleResponse
import kz.epharm.posm.dto.RecommendRequest
import kz.epharm.posm.dto.RecommendResponse
import kz.epharm.posm.service.DevicePresenceService
import kz.epharm.posm.service.PosmDeviceAuthenticationService
import kz.epharm.posm.service.PosmPharmacistIdentityService
import kz.epharm.posm.service.PosSaleService
import kz.epharm.posm.service.RecommendationService
import kz.epharm.screens.dto.ActivePlaylistDto
import kz.epharm.screens.service.ScreenService
import org.slf4j.LoggerFactory
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
 * permitAll (JWT-фильтр пропускает), реальная защита — проверка ключа здесь. В production
 * принимаются только индивидуальные отзываемые ключи устройств; fleet key оставлен как
 * отключаемое переходное окно регистрации.
 */
@RestController
@RequestMapping("/api/posm")
class PosmController(
    private val recommendationService: RecommendationService,
    private val posSaleService: PosSaleService,
    private val screenService: ScreenService,
    private val cdpService: CdpService,
    private val appReleaseService: AppReleaseService,
    private val devicePresenceService: DevicePresenceService,
    private val pharmacistIdentityService: PosmPharmacistIdentityService,
    private val deviceAuthentication: PosmDeviceAuthenticationService,
    private val merchTaskClient: MerchTaskClient,
) {
    private val log = LoggerFactory.getLogger(PosmController::class.java)

    @PostMapping("/recommend")
    fun recommend(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: RecommendRequest,
    ): RecommendResponse {
        deviceAuthentication.authenticate(key, claimedPharmacyId = req.pharmacyId)
        val identity = pharmacistIdentityService.resolve(
            pharmacyId = req.pharmacyId,
            reportedPharmacistId = req.pharmacistId,
            reportedPharmacistName = req.pharmacistName,
        )
        return recommendationService.recommend(req.copy(pharmacistId = identity.pharmacistId))
    }

    @PostMapping("/recommendations/{eventId}/outcome")
    fun outcome(
        @PathVariable eventId: String,
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: OutcomeRequest,
    ): OutcomeResponse {
        val device = deviceAuthentication.authenticate(key)
        return recommendationService.recordOutcome(eventId, req, device.pharmacyId)
    }

    /**
     * Фактический показ попапа фармацевту (V032): касса шлёт client-время показа через outbox.
     * По нему считаем время до продажи точнее, чем по моменту генерации рекомендации.
     */
    @PostMapping("/recommendations/{eventId}/shown")
    fun markShown(
        @PathVariable eventId: String,
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: MarkShownRequest,
    ): MarkShownResponse {
        val device = deviceAuthentication.authenticate(key)
        recommendationService.markDisplayed(eventId, req.shownAt, device.pharmacyId)
        return MarkShownResponse(eventId = eventId, ok = true)
    }

    /** Источник №1 сверки: завершённый чек из лога кассы. Идемпотентно по saleId. */
    @PostMapping("/sales")
    fun sale(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: PosSaleRequest,
    ): PosSaleResponse {
        deviceAuthentication.authenticate(key, claimedPharmacyId = req.pharmacyId)
        val identity = pharmacistIdentityService.resolve(
            pharmacyId = req.pharmacyId,
            reportedPharmacistId = req.pharmacistId,
            reportedPharmacistName = req.pharmacistName,
        )
        val effective = req.copy(
            pharmacistId = identity.pharmacistId,
            pharmacistName = identity.pharmacistName,
        )
        val accepted = posSaleService.record(effective, identity)
        return PosSaleResponse(saleId = req.saleId, accepted = accepted)
    }

    /** Stage 3: активный плейлист для 2-го монитора (видео из админки вместо хардкода). */
    @GetMapping("/playlists/active")
    fun activePlaylist(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @RequestParam(required = false) pharmacyId: String?,
    ): ActivePlaylistDto {
        val device = deviceAuthentication.authenticate(key, claimedPharmacyId = pharmacyId)
        return screenService.activePlaylistForScreen(pharmacyId ?: device.pharmacyId)
    }

    /**
     * Авто-обновление клиента: текущий релиз для платформы. Касса сравнивает version со своей
     * и сама обновляется, если новее. current=false → обновляться не нужно.
     */
    @GetMapping("/app/version")
    fun appVersion(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @RequestParam(required = false, defaultValue = "win-x64") platform: String,
        @RequestParam(required = false) deviceId: String?,
        @RequestParam(required = false) currentVersion: String?,
    ): AppVersionDto {
        deviceAuthentication.authenticate(key, claimedDeviceId = deviceId, touchLastSeen = true)
        val release = appReleaseService.currentFor(platform)
        log.info(
            "POSM update check: deviceId={}, currentVersion={}, targetVersion={}, platform={}",
            deviceId?.take(128) ?: "legacy",
            currentVersion?.take(32) ?: "legacy",
            release.version,
            platform.take(32),
        )
        return release
    }

    /**
     * Пульс кассы (T4): касса шлёт каждые ~60с. deviceId — стабильный id устройства
     * (имя машины/GUID), pharmacyId — аптека. По пульсам считаем «подключено N касс».
     */
    @PostMapping("/heartbeat")
    fun heartbeat(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @RequestParam(required = false) deviceId: String?,
        @RequestParam(required = false) pharmacyId: String?,
        @RequestParam(required = false) monitorCount: Int?,
        @RequestParam(required = false) appVersion: String?,
    ): HeartbeatResponse {
        val device = deviceAuthentication.authenticate(
            key,
            claimedPharmacyId = pharmacyId,
            claimedDeviceId = deviceId,
            touchLastSeen = true,
        )
        val id = deviceId?.takeIf { it.isNotBlank() } ?: device.deviceId ?: "posm"
        val effectivePharmacyId = pharmacyId?.takeIf { it.isNotBlank() } ?: device.pharmacyId
        // Старые клиенты не передают monitorCount. Некорректное значение игнорируем, чтобы
        // heartbeat оставался fail-safe и касса не выпадала из online-списка.
        devicePresenceService.heartbeat(
            id,
            effectivePharmacyId,
            monitorCount = monitorCount?.takeIf { it in 1..16 },
            appVersion = appVersion
                ?.trim()
                ?.takeIf { it.matches(Regex("[0-9A-Za-z.+-]{1,32}")) },
        )
        return HeartbeatResponse(ok = true, deviceId = id)
    }

    /** Active merchandising task for the authenticated device's pharmacy. */
    @GetMapping("/tasks")
    fun activeMerchTask(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @RequestParam pharmacyId: String,
    ): JsonNode {
        val device = deviceAuthentication.authenticate(key, claimedPharmacyId = pharmacyId)
        return merchTaskClient.activeTask(device.pharmacyId ?: pharmacyId.trim())
    }

    /** Confirms that a task QR code was actually visible on a specific authenticated device. */
    @PostMapping("/tasks/shown")
    fun markMerchTaskShown(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody payload: MerchTaskShownRequest,
    ): JsonNode {
        val device = deviceAuthentication.authenticate(
            key,
            claimedPharmacyId = payload.pharmacyId,
            claimedDeviceId = payload.deviceId,
            touchLastSeen = true,
        )
        return merchTaskClient.markShown(
            payload.copy(
                pharmacyId = device.pharmacyId ?: payload.pharmacyId.trim(),
                deviceId = device.deviceId ?: payload.deviceId.trim(),
            ),
        )
    }

    /** CDP (§5.6): поиск клиента лояльности по телефону. */
    @PostMapping("/cdp/lookup")
    fun cdpLookup(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: CdpLookupRequest,
    ): CdpLookupResponse {
        deviceAuthentication.authenticate(key)
        return cdpService.lookup(req.phone)
    }

    /** CDP (§5.6): регистрация нового клиента на кассе. Идемпотентно по телефону. */
    @PostMapping("/cdp/register")
    fun cdpRegister(
        @RequestHeader(name = "X-Posm-Key", required = false) key: String?,
        @Valid @RequestBody req: CdpRegisterRequest,
    ): CdpProfileDto {
        deviceAuthentication.authenticate(key)
        return cdpService.register(req)
    }

}
