package kz.epharm.auth.controller

import jakarta.validation.Valid
import kz.epharm.auth.dto.LoginRequest
import kz.epharm.auth.dto.LoginResponse
import kz.epharm.auth.dto.RefreshRequest
import kz.epharm.auth.dto.RefreshResponse
import kz.epharm.auth.dto.UserDto
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.auth.service.AdminAuthService
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

@RestController
@RequestMapping("/api/admin/auth")
class AdminAuthController(
    private val adminAuthService: AdminAuthService,
) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): LoginResponse =
        adminAuthService.login(request.email, request.password)

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): RefreshResponse =
        adminAuthService.refresh(request.refreshToken)

    @PostMapping("/logout")
    fun logout(@AuthenticationPrincipal principal: AdminPrincipal?): ResponseEntity<Unit> {
        if (principal == null) {
            throw AppException(ErrorCode.UNAUTHORIZED, "Not authenticated", HttpStatus.UNAUTHORIZED)
        }
        adminAuthService.logout(principal.userId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: AdminPrincipal?): UserDto {
        if (principal == null) {
            throw AppException(ErrorCode.UNAUTHORIZED, "Not authenticated", HttpStatus.UNAUTHORIZED)
        }
        return adminAuthService.currentUser(principal.userId)
    }
}
