package kz.epharm.mobile.auth.controller

import jakarta.validation.Valid
import kz.epharm.auth.dto.RefreshRequest
import kz.epharm.auth.dto.RefreshResponse
import kz.epharm.mobile.auth.dto.MeDto
import kz.epharm.mobile.auth.dto.ActivationSmsRequest
import kz.epharm.mobile.auth.dto.ActivationStatusRequest
import kz.epharm.mobile.auth.dto.ActivationStatusResponse
import kz.epharm.mobile.auth.dto.ActivationVerifyRequest
import kz.epharm.mobile.auth.dto.ActivationVerifyResponse
import kz.epharm.mobile.auth.dto.IinPasswordLoginRequest
import kz.epharm.mobile.auth.dto.MobileAuthResponse
import kz.epharm.mobile.auth.dto.RegisterRequest
import kz.epharm.mobile.auth.dto.SmsRequestRequest
import kz.epharm.mobile.auth.dto.SmsRequestResponse
import kz.epharm.mobile.auth.dto.SmsVerifyRequest
import kz.epharm.mobile.auth.dto.SetInitialPasswordRequest
import kz.epharm.mobile.auth.dto.VerifySmsResponse
import kz.epharm.mobile.auth.security.PharmacistPrincipal
import kz.epharm.mobile.auth.service.MobileAuthService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Аутентификация мобильного приложения фармацевта (phone → OTP → JWT под роль PHARMACIST).
 *
 * Публичные (permitAll в SecurityConfig): /sms/request, /sms/verify, /register, /refresh.
 * Защищённые JWT: /logout, /me.
 */
@RestController
@RequestMapping("/api/mobile/auth")
class MobileAuthController(
    private val mobileAuthService: MobileAuthService,
) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: IinPasswordLoginRequest): MobileAuthResponse =
        mobileAuthService.login(req.iin, req.password)

    @PostMapping("/activation/status")
    fun activationStatus(@Valid @RequestBody req: ActivationStatusRequest): ActivationStatusResponse =
        mobileAuthService.activationStatus(req.iin)

    @PostMapping("/activation/sms/request")
    fun requestActivationSms(@Valid @RequestBody req: ActivationSmsRequest): SmsRequestResponse =
        mobileAuthService.requestActivationSms(req.iin, req.phone)

    @PostMapping("/activation/sms/verify")
    fun verifyActivationSms(@Valid @RequestBody req: ActivationVerifyRequest): ActivationVerifyResponse =
        mobileAuthService.verifyActivationSms(req.iin, req.phone, req.code)

    @PostMapping("/activation/password")
    fun setInitialPassword(@Valid @RequestBody req: SetInitialPasswordRequest): MobileAuthResponse =
        mobileAuthService.setInitialPassword(req.iin, req.phone, req.password)

    @PostMapping("/sms/request")
    fun requestSms(@Valid @RequestBody req: SmsRequestRequest): SmsRequestResponse =
        mobileAuthService.requestSms(req.phone)

    @PostMapping("/sms/verify")
    fun verifySms(@Valid @RequestBody req: SmsVerifyRequest): VerifySmsResponse =
        mobileAuthService.verifySms(req.phone, req.code)

    @PostMapping("/register")
    fun register(@Valid @RequestBody req: RegisterRequest): MobileAuthResponse =
        mobileAuthService.register(req.phone, req.fio, req.iin)

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody req: RefreshRequest): RefreshResponse =
        mobileAuthService.refresh(req.refreshToken)

    @PostMapping("/logout")
    fun logout(@AuthenticationPrincipal principal: PharmacistPrincipal?): ResponseEntity<Unit> {
        val p = principal ?: throw AppException(ErrorCode.UNAUTHORIZED, "Не авторизован", HttpStatus.UNAUTHORIZED)
        mobileAuthService.logout(p.pharmacistId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: PharmacistPrincipal?): MeDto {
        val p = principal ?: throw AppException(ErrorCode.UNAUTHORIZED, "Не авторизован", HttpStatus.UNAUTHORIZED)
        return mobileAuthService.me(p.pharmacistId)
    }
}
