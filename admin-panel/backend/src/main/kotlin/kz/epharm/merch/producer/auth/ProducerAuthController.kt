package kz.epharm.merch.producer.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/producer/auth")
@ConditionalOnProperty(name=["app.merch.producer-auth-enabled"],havingValue="true")
class ProducerAuthController(private val auth:ProducerAuthService) {
    @PostMapping("/sms/request") fun request(@Valid @RequestBody body:ProducerSmsRequest,request:HttpServletRequest)=
        noStore(auth.requestOtp(body,request.remoteAddr))
    @PostMapping("/sms/verify") fun verify(@Valid @RequestBody body:ProducerSmsVerify)=noStore(auth.verify(body))
    @PostMapping("/refresh") fun refresh(@Valid @RequestBody body:ProducerRefreshRequest)=noStore(auth.refresh(body.refreshToken))
    @GetMapping("/me") @PreAuthorize("hasRole('PRODUCER')") fun me(@AuthenticationPrincipal principal:ProducerPrincipal)=noStore(auth.me(principal))
    @PostMapping("/logout") @PreAuthorize("hasRole('PRODUCER')") fun logout(@AuthenticationPrincipal principal:ProducerPrincipal):ResponseEntity<Void> {
        auth.logout(principal);return ResponseEntity.noContent().header("Cache-Control","no-store").build()
    }
    private fun <T> noStore(body:T)=ResponseEntity.ok().header("Cache-Control","no-store").body(body)
}
