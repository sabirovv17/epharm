package kz.epharm.merch.inventory.controller

import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.merch.inventory.dto.SaveMerchOrganization
import kz.epharm.merch.inventory.dto.SaveMerchBranch
import kz.epharm.merch.inventory.service.MerchInventoryEditorService
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/admin/merch/inventory")
class MerchInventoryStructureController(private val editor: MerchInventoryEditorService) {
    @GetMapping("/organizations") fun organizations()=privateResponse(editor.organizations())
    @PostMapping("/organizations") fun createOrganization(@RequestBody body: SaveMerchOrganization,@AuthenticationPrincipal actor: AdminPrincipal)=privateResponse(editor.saveOrganization(null,body,actor.userId))
    @PutMapping("/organizations/{id}") fun organization(@PathVariable id: UUID,@RequestBody body: SaveMerchOrganization,@AuthenticationPrincipal actor: AdminPrincipal)=privateResponse(editor.saveOrganization(id,body,actor.userId))
    @PostMapping("/branches") fun branch(@RequestBody body: SaveMerchBranch,@AuthenticationPrincipal actor: AdminPrincipal)=privateResponse(editor.createBranch(body,actor.userId))
}
private fun <T> privateResponse(value: T): ResponseEntity<T> = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(value)
