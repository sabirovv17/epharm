package kz.epharm.mobile.pharmacies.controller

import kz.epharm.mobile.auth.security.PharmacistPrincipal
import kz.epharm.mobile.pharmacies.dto.MobilePharmacyDto
import kz.epharm.mobile.pharmacies.service.MobilePharmacyService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Аптеки для мобильного приложения (экран выбора аптеки при загрузке чека).
 * Реальные адреса из нашего реестра вместо хардкода во Flutter.
 * Под JWT ROLE_PHARMACIST (путь вне /auth → `.authenticated()`).
 */
@RestController
@RequestMapping("/api/mobile/pharmacies")
class MobilePharmacyController(
    private val service: MobilePharmacyService,
) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: PharmacistPrincipal?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) city: String?,
    ): List<MobilePharmacyDto> {
        principal ?: throw AppException(ErrorCode.UNAUTHORIZED, "Не авторизован", HttpStatus.UNAUTHORIZED)
        return service.list(q = q, city = city)
    }
}
