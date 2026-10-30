package kz.epharm.merch.commercial

import kz.epharm.auth.security.AdminPrincipal
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/merch/tariffs")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD')")
class AdminTariffController(private val tariffs:TariffService) {
    @GetMapping fun list(@AuthenticationPrincipal principal:AdminPrincipal)=privateResponse(tariffs.list(principal.userId))
    @PutMapping("/{category}") fun save(@PathVariable category:String,@RequestBody body:SaveTariff,@AuthenticationPrincipal principal:AdminPrincipal)=
        privateResponse(tariffs.save(category,body,principal.userId))
    private fun <T> privateResponse(value:T)=ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(value)
}
