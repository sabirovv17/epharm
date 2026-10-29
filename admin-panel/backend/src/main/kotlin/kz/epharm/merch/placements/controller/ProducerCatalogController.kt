package kz.epharm.merch.placements.controller

import kz.epharm.merch.placements.dto.PlacementActor
import kz.epharm.merch.placements.service.PlacementService
import kz.epharm.merch.producer.auth.ProducerPrincipal
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/producer/branches")
@PreAuthorize("hasRole('PRODUCER')")
class ProducerCatalogController(private val placements:PlacementService) {
    @GetMapping fun branches(@AuthenticationPrincipal principal:ProducerPrincipal,@RequestParam(defaultValue="50") limit:Int,
        @RequestParam(defaultValue="0") offset:Int)=placements.producerBranches(PlacementActor.Producer(principal.customerId,principal.sessionId),limit,offset)
    @GetMapping("/{id}/availability") fun availability(@PathVariable id:UUID,@RequestParam month:LocalDate,
        @AuthenticationPrincipal principal:ProducerPrincipal)=placements.producerAvailability(PlacementActor.Producer(principal.customerId,principal.sessionId),id,month)
}
