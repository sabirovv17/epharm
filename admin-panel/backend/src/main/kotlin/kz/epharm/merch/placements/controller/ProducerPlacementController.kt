package kz.epharm.merch.placements.controller

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import kz.epharm.merch.placements.dto.*
import kz.epharm.merch.placements.service.PlacementService
import kz.epharm.merch.producer.auth.ProducerPrincipal
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.UUID

data class ProducerDraftRequest(val placementMonth:LocalDate,@field:NotEmpty @field:Size(max=500) val items:List<PlacementSelection>,
    @field:Size(max=500) val note:String?=null)

@RestController
@RequestMapping("/api/producer/placements")
@PreAuthorize("hasRole('PRODUCER')")
class ProducerPlacementController(private val placements:PlacementService) {
    @GetMapping fun list(@AuthenticationPrincipal principal:ProducerPrincipal,@RequestParam(required=false) status:PlacementStatus?,
        @RequestParam(defaultValue="50") limit:Int,@RequestParam(defaultValue="0") offset:Int)=
        placements.list(actor(principal),null,null,status,limit,offset)
    @GetMapping("/{id}") fun view(@PathVariable id:UUID,@AuthenticationPrincipal principal:ProducerPrincipal)=placements.view(id,actor(principal))
    @PostMapping fun create(@Valid @RequestBody body:ProducerDraftRequest,@AuthenticationPrincipal principal:ProducerPrincipal)=
        placements.create(CreatePlacementDraft(principal.customerId,body.placementMonth,body.items,body.note),actor(principal))
    @PutMapping("/{id}/draft") fun save(@PathVariable id:UUID,@Valid @RequestBody body:SavePlacementDraft,@AuthenticationPrincipal principal:ProducerPrincipal)=
        placements.save(id,body,actor(principal))
    @PostMapping("/{id}/submit") fun submit(@PathVariable id:UUID,@Valid @RequestBody body:PlacementTransition,@AuthenticationPrincipal principal:ProducerPrincipal)=
        placements.transition(id,"submit",body,actor(principal))
    @PostMapping("/{id}/cancel") fun cancel(@PathVariable id:UUID,@Valid @RequestBody body:PlacementTransition,@AuthenticationPrincipal principal:ProducerPrincipal)=
        placements.transition(id,"cancel",body,actor(principal))
    private fun actor(principal:ProducerPrincipal)=PlacementActor.Producer(principal.customerId,principal.sessionId)
}
