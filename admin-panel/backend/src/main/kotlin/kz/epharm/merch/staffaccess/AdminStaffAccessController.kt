package kz.epharm.merch.staffaccess

import kz.epharm.auth.security.AdminPrincipal
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/admin/merch/staff-access")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD','CATEGORY_LEAD','REGIONAL_MANAGER')")
class AdminStaffAccessController(private val access: StaffAccessService) {
    @GetMapping("/options") fun options() = staffPrivate(access.options())
    @GetMapping fun settings(@RequestParam organizationId: UUID, @RequestParam month: LocalDate) =
        staffPrivate(access.settings(organizationId, month))
    @PostMapping fun create(@RequestBody body: CreateStaffAccess, @AuthenticationPrincipal actor: AdminPrincipal) =
        staffPrivate(access.create(body, actor.userId))
    @PostMapping("/{id}/revoke") fun revoke(@PathVariable id: UUID, @AuthenticationPrincipal actor: AdminPrincipal) =
        staffPrivate(access.revoke(id, actor.userId))
}
