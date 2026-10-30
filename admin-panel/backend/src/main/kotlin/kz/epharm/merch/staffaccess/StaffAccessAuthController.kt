package kz.epharm.merch.staffaccess

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/staff/auth")
@ConditionalOnProperty(name = ["app.merch.staff-access-enabled"], havingValue = "true")
class StaffAccessAuthController(private val access: StaffAccessService) {
    @PostMapping("/code")
    fun code(@RequestBody body: StaffCodeRequest, request: HttpServletRequest) = staffPrivate(access.exchangeCode(body, request.remoteAddr))
    @PostMapping("/qr")
    fun qr(@RequestBody body: StaffQrRequest, request: HttpServletRequest) = staffPrivate(access.exchangeQr(body, request.remoteAddr))
    @GetMapping("/me")
    fun me(@RequestHeader(StaffAccessTokens.HEADER, required = false) raw: String?) = staffPrivate(access.requireSession(raw.orEmpty()))
    @PostMapping("/logout")
    fun logout(@RequestHeader(StaffAccessTokens.HEADER, required = false) raw: String?): ResponseEntity<Void> {
        access.logout(raw.orEmpty())
        return ResponseEntity.noContent().header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer").build()
    }
}

internal fun <T> staffPrivate(body: T): ResponseEntity<T> = ResponseEntity.ok()
    .header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer").body(body)
