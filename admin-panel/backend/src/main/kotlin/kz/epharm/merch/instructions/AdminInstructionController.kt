package kz.epharm.merch.instructions

import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.merch.placements.dto.PlacementActor
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/admin/merch/instructions")
class AdminInstructionController(private val instructions: InstructionService) {
    @GetMapping fun list(@AuthenticationPrincipal actor: AdminPrincipal,@RequestParam(required=false) producerId: UUID?,@RequestParam(required=false) planId: UUID?,
        @RequestParam(required=false) status: String?,@RequestParam(defaultValue="20") limit: Int,@RequestParam(defaultValue="0") offset: Int)=
        privateInstruction(instructions.list(PlacementActor.Internal(actor.userId),producerId,planId,status,limit,offset))
    @GetMapping("/targets") fun targets(@AuthenticationPrincipal actor: AdminPrincipal,@RequestParam(required=false) producerId: UUID?,
        @RequestParam(defaultValue="50") limit: Int,@RequestParam(defaultValue="0") offset: Int)=privateInstruction(instructions.targets(PlacementActor.Internal(actor.userId),producerId,limit,offset))
    @PostMapping(consumes=[MediaType.MULTIPART_FORM_DATA_VALUE]) fun upload(@AuthenticationPrincipal actor: AdminPrincipal,@RequestPart metadata: CreateInstruction,@RequestPart file: MultipartFile)=
        privateInstruction(instructions.create(PlacementActor.Internal(actor.userId),metadata,file))
    @PostMapping("/{id}/review") fun review(@PathVariable id: UUID,@AuthenticationPrincipal actor: AdminPrincipal,@RequestBody request: ReviewInstruction)=
        privateInstruction(instructions.review(PlacementActor.Internal(actor.userId),id,request))
    @GetMapping("/{id}/pdf") fun pdf(@PathVariable id: UUID,@AuthenticationPrincipal actor: AdminPrincipal)=instructionFile(instructions.pdf(PlacementActor.Internal(actor.userId),id),"$id.pdf")
    @GetMapping("/{id}/results") fun results(@PathVariable id: UUID,@AuthenticationPrincipal actor: AdminPrincipal,@RequestParam(defaultValue="20") limit: Int,@RequestParam(defaultValue="0") offset: Int)=
        privateInstruction(instructions.results(PlacementActor.Internal(actor.userId),id,limit,offset))
    @GetMapping("/{id}/results/{evidenceId}/file") fun result(@PathVariable id: UUID,@PathVariable evidenceId: UUID,@AuthenticationPrincipal actor: AdminPrincipal)=
        instructionFile(instructions.resultFile(PlacementActor.Internal(actor.userId),id,evidenceId),evidenceId.toString())
}
internal fun <T> privateInstruction(body: T)=ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(body)
internal fun instructionFile(file: InstructionFile,name: String): ResponseEntity<ByteArray> = ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mimeType))
    .header(HttpHeaders.CACHE_CONTROL,"no-store").header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"$name\"")
    .header("X-Content-SHA256",file.sha256).header("X-Content-Type-Options","nosniff").body(file.bytes)
