package kz.epharm.mobile.profile.controller

import kz.epharm.mobile.auth.security.PharmacistPrincipal
import kz.epharm.mobile.profile.service.PharmacistSelfService
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/mobile/me")
class PharmacistSelfController(private val service: PharmacistSelfService) {
    @GetMapping("/balance")
    fun balance(@AuthenticationPrincipal principal: PharmacistPrincipal) =
        privateResponse(service.balance(principal.pharmacistId))

    @GetMapping("/recommendations")
    fun recommendations(
        @AuthenticationPrincipal principal: PharmacistPrincipal,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ) = privateResponse(service.recommendations(principal.pharmacistId, page, size))

    private fun <T> privateResponse(body: T): ResponseEntity<T> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}
