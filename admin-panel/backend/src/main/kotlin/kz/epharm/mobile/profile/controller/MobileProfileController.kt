package kz.epharm.mobile.profile.controller

import kz.epharm.mobile.auth.dto.MeDto
import kz.epharm.mobile.auth.security.PharmacistPrincipal
import kz.epharm.mobile.auth.service.MobileAuthService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Профиль фармацевта для мобильного Home (баланс/тир/статус). Семантический адрес `/api/mobile/me`
 * (дублирует `/api/mobile/auth/me`, но логически это «профиль», а не «аутентификация»).
 * Источник истины — таблица pharmacists (golden rule: что в админке, то и в приложении).
 * Под JWT (ROLE_PHARMACIST) — путь вне /auth → `.authenticated()` в SecurityConfig.
 */
@RestController
@RequestMapping("/api/mobile/me")
class MobileProfileController(
    private val mobileAuthService: MobileAuthService,
) {
    @GetMapping
    fun me(@AuthenticationPrincipal principal: PharmacistPrincipal?): MeDto {
        val p = principal ?: throw AppException(ErrorCode.UNAUTHORIZED, "Не авторизован", HttpStatus.UNAUTHORIZED)
        return mobileAuthService.me(p.pharmacistId)
    }
}
