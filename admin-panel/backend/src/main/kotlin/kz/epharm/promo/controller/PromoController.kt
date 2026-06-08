package kz.epharm.promo.controller

import jakarta.validation.Valid
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.promo.dto.CreatePromoRequest
import kz.epharm.promo.dto.PromoDto
import kz.epharm.promo.dto.UpdatePromoRequest
import kz.epharm.promo.entity.PromoStatus
import kz.epharm.promo.service.PromoService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// Promo-кампании ТЗ §3.2.3. CRUD + статус-переходы.
// Аутентификация: JwtAuthenticationFilter. Per-role @PreAuthorize придёт в Этапе 3.3+.
@RestController
@RequestMapping("/api/admin/promo")
class PromoController(
    private val promoService: PromoService,
) {

    @GetMapping
    fun list(@RequestParam(required = false) status: PromoStatus?): List<PromoDto> =
        promoService.list(status = status)

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): PromoDto = promoService.get(id)

    @PostMapping
    fun create(
        @Valid @RequestBody req: CreatePromoRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): PromoDto = promoService.create(req, createdBy = requireUserId(principal))

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @Valid @RequestBody req: UpdatePromoRequest,
    ): PromoDto = promoService.update(id, req)

    @PostMapping("/{id}/archive")
    fun archive(@PathVariable id: String): PromoDto = promoService.archive(id)

    @PostMapping("/{id}/restore")
    fun restore(@PathVariable id: String): PromoDto = promoService.restore(id)

    private fun requireUserId(principal: AdminPrincipal?): String =
        principal?.userId?.toString()
            ?: throw AppException(ErrorCode.UNAUTHORIZED, "Not authenticated", HttpStatus.UNAUTHORIZED)
}
