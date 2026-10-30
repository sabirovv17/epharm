package kz.epharm.merch.instructions

import kz.epharm.merch.placements.dto.PlacementActor
import kz.epharm.merch.producer.auth.ProducerPrincipal
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/producer/instructions")
@PreAuthorize("hasRole('PRODUCER')")
class ProducerInstructionController(private val instructions: InstructionService) {
    @GetMapping fun list(@AuthenticationPrincipal principal: ProducerPrincipal,@RequestParam(required=false) planId: UUID?,@RequestParam(required=false) status: String?,
        @RequestParam(defaultValue="20") limit: Int,@RequestParam(defaultValue="0") offset: Int)=privateInstruction(instructions.list(actor(principal),null,planId,status,limit,offset))
    @GetMapping("/targets") fun targets(@AuthenticationPrincipal principal: ProducerPrincipal,@RequestParam(defaultValue="50") limit: Int,@RequestParam(defaultValue="0") offset: Int)=privateInstruction(instructions.targets(actor(principal),null,limit,offset))
    @PostMapping(consumes=[MediaType.MULTIPART_FORM_DATA_VALUE]) fun upload(@AuthenticationPrincipal principal: ProducerPrincipal,@RequestPart metadata: CreateInstruction,@RequestPart file: MultipartFile)=privateInstruction(instructions.create(actor(principal),metadata,file))
    @GetMapping("/{id}/pdf") fun pdf(@PathVariable id: UUID,@AuthenticationPrincipal principal: ProducerPrincipal)=instructionFile(instructions.pdf(actor(principal),id),"$id.pdf")
    @GetMapping("/{id}/results") fun results(@PathVariable id: UUID,@AuthenticationPrincipal principal: ProducerPrincipal,@RequestParam(defaultValue="20") limit: Int,@RequestParam(defaultValue="0") offset: Int)=privateInstruction(instructions.results(actor(principal),id,limit,offset))
    @GetMapping("/{id}/results/{evidenceId}/file") fun result(@PathVariable id: UUID,@PathVariable evidenceId: UUID,@AuthenticationPrincipal principal: ProducerPrincipal)=instructionFile(instructions.resultFile(actor(principal),id,evidenceId),evidenceId.toString())
    private fun actor(principal: ProducerPrincipal)=PlacementActor.Producer(principal.customerId,principal.sessionId)
}
