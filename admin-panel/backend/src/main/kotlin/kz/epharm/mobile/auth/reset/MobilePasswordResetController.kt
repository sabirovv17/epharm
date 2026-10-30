package kz.epharm.mobile.auth.reset

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/mobile/auth/password-reset")
class MobilePasswordResetController(private val reset: MobilePasswordResetService) {
    @PostMapping("/request")
    fun request(@Valid @RequestBody body: RequestMobilePasswordReset, request: HttpServletRequest) =
        privateResponse(reset.request(body.iin, request.remoteAddr.orEmpty()))
    @PostMapping("/verify")
    fun verify(@Valid @RequestBody body: VerifyMobilePasswordReset) = privateResponse(reset.verify(body))
    @PostMapping("/complete")
    fun complete(@Valid @RequestBody body: CompleteMobilePasswordReset) = privateResponse(reset.complete(body))
    private fun <T> privateResponse(body: T) = ResponseEntity.ok().header("Cache-Control", "no-store")
        .header("Pragma", "no-cache").body(body)
}
