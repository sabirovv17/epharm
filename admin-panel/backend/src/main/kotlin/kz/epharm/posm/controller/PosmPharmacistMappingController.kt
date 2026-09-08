package kz.epharm.posm.controller

import jakarta.validation.Valid
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.posm.dto.PosmPharmacistMappingDto
import kz.epharm.posm.dto.UnmappedPosmSellerDto
import kz.epharm.posm.dto.UpsertPosmPharmacistMappingRequest
import kz.epharm.posm.service.PosmPharmacistMappingService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/admin/posm/pharmacist-mappings")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD')")
class PosmPharmacistMappingController(private val service: PosmPharmacistMappingService) {
    @GetMapping
    fun list(
        @RequestParam(required = false) pharmacyId: String?,
        @RequestParam(defaultValue = "false") includeInactive: Boolean,
    ): List<PosmPharmacistMappingDto> = service.list(pharmacyId, includeInactive)

    @GetMapping("/unmapped")
    fun unmapped(): List<UnmappedPosmSellerDto> = service.unmapped()

    @PostMapping
    fun upsert(
        @Valid @RequestBody req: UpsertPosmPharmacistMappingRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): PosmPharmacistMappingDto = service.upsert(req, requirePrincipal(principal))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ) = service.revoke(id, requirePrincipal(principal))

    private fun requirePrincipal(principal: AdminPrincipal?): AdminPrincipal = principal
        ?: throw AppException(ErrorCode.UNAUTHORIZED, "Not authenticated", HttpStatus.UNAUTHORIZED)
}
