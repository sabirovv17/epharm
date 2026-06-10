package kz.epharm.medusa.controller

import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.mobile.catalog.dto.MobileCatalogDetailDto
import kz.epharm.mobile.catalog.dto.MobileCatalogPageDto
import kz.epharm.mobile.catalog.service.MobileCatalogService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Витрина реального каталога (Medusa) в HQ-админке — read-only. Чтобы HQ видел тот же
 * каталог, что и фармацевт в приложении. Делегирует в общий [MobileCatalogService]
 * (каталог един для мобилки и админки).
 *
 * Под admin-JWT: `@AuthenticationPrincipal AdminPrincipal?` — pharmacist-токен сюда не
 * пройдёт (в его SecurityContext лежит PharmacistPrincipal, не AdminPrincipal → null → 401).
 */
@RestController
@RequestMapping("/api/admin/storefront")
class AdminStorefrontController(
    private val catalog: MobileCatalogService,
) {
    @GetMapping("/products")
    fun products(
        @AuthenticationPrincipal admin: AdminPrincipal?,
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): MobileCatalogPageDto {
        requireAdmin(admin)
        return catalog.search(q = q, category = null, limit = limit, offset = offset)
    }

    @GetMapping("/products/{id}")
    fun product(
        @AuthenticationPrincipal admin: AdminPrincipal?,
        @PathVariable id: String,
    ): MobileCatalogDetailDto {
        requireAdmin(admin)
        return catalog.detail(id)
    }

    private fun requireAdmin(admin: AdminPrincipal?) {
        admin ?: throw AppException(ErrorCode.UNAUTHORIZED, "Не авторизован", HttpStatus.UNAUTHORIZED)
    }
}
