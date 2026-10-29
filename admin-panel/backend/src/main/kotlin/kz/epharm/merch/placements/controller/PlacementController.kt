package kz.epharm.merch.placements.controller

import jakarta.validation.Valid
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.merch.placements.dto.*
import kz.epharm.merch.placements.service.PlacementService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/admin/merch")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD','CATEGORY_LEAD','REGIONAL_MANAGER')")
class PlacementController(private val placements: PlacementService) {
    @GetMapping("/producers") fun producers()=placements.producers()
    @GetMapping("/placements/producers") fun producerOptions()=placements.producerOptions()
    @GetMapping("/placements") fun list(@AuthenticationPrincipal actor: AdminPrincipal,
        @RequestParam(required=false) producerId: UUID?,@RequestParam(required=false) branchId: UUID?,
        @RequestParam(required=false) status: PlacementStatus?,@RequestParam(defaultValue="50") limit: Int,
        @RequestParam(defaultValue="0") offset: Int)=placements.list(PlacementActor.Internal(actor.userId),producerId,branchId,status,limit,offset)
    @GetMapping("/placements/catalog") fun catalog(@AuthenticationPrincipal actor:AdminPrincipal,@RequestParam producerId:UUID,
        @RequestParam month:java.time.LocalDate,@RequestParam(required=false) branchId:UUID?,@RequestParam(defaultValue="50") limit:Int,
        @RequestParam(defaultValue="0") offset:Int)=placements.adminCatalog(PlacementActor.Internal(actor.userId),producerId,month,branchId,limit,offset)
    @GetMapping("/placements/{id}") fun view(@PathVariable id: UUID,@AuthenticationPrincipal actor: AdminPrincipal)=placements.view(id,PlacementActor.Internal(actor.userId))
    @PostMapping("/placements/{id}/producer-binding")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD')")
    fun bindProducer(@PathVariable id:UUID,@Valid @RequestBody request:BindPlacementProducer,@AuthenticationPrincipal actor:AdminPrincipal)=
        placements.bindProducer(id,request,PlacementActor.Internal(actor.userId))
    @PostMapping("/placements") fun create(@Valid @RequestBody request: CreatePlacementDraft,@AuthenticationPrincipal actor: AdminPrincipal)=placements.create(request,PlacementActor.Internal(actor.userId))
    @PutMapping("/placements/{id}/draft") fun save(@PathVariable id: UUID,@Valid @RequestBody request: SavePlacementDraft,@AuthenticationPrincipal actor: AdminPrincipal)=placements.save(id,request,PlacementActor.Internal(actor.userId))
    @PostMapping("/placements/{id}/submit") fun submit(@PathVariable id: UUID,@Valid @RequestBody request: PlacementTransition,@AuthenticationPrincipal actor: AdminPrincipal)=placements.transition(id,"submit",request,PlacementActor.Internal(actor.userId))
    @PostMapping("/placements/{id}/approve") fun approve(@PathVariable id: UUID,@Valid @RequestBody request: PlacementTransition,@AuthenticationPrincipal actor: AdminPrincipal)=placements.transition(id,"approve",request,PlacementActor.Internal(actor.userId))
    @PostMapping("/placements/{id}/reject") fun reject(@PathVariable id: UUID,@Valid @RequestBody request: PlacementTransition,@AuthenticationPrincipal actor: AdminPrincipal)=placements.transition(id,"reject",request,PlacementActor.Internal(actor.userId))
    @PostMapping("/placements/{id}/cancel") fun cancel(@PathVariable id: UUID,@Valid @RequestBody request: PlacementTransition,@AuthenticationPrincipal actor: AdminPrincipal)=placements.transition(id,"cancel",request,PlacementActor.Internal(actor.userId))
    @PostMapping("/placements/{id}/items/approve") fun approveItems(@PathVariable id:UUID,@Valid @RequestBody request:PlacementItemReview,@AuthenticationPrincipal actor:AdminPrincipal)=placements.reviewItems(id,"approve",request,PlacementActor.Internal(actor.userId))
    @PostMapping("/placements/{id}/items/reject") fun rejectItems(@PathVariable id:UUID,@Valid @RequestBody request:PlacementItemReview,@AuthenticationPrincipal actor:AdminPrincipal)=placements.reviewItems(id,"reject",request,PlacementActor.Internal(actor.userId))
    @PostMapping("/admin-assignments") fun assign(@Valid @RequestBody request: CreatePlacementDraft,@AuthenticationPrincipal actor: AdminPrincipal)=placements.assign(request,PlacementActor.Internal(actor.userId))
}
