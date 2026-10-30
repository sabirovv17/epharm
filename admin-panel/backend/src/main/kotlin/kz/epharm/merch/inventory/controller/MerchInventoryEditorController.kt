package kz.epharm.merch.inventory.controller

import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.merch.inventory.dto.*
import kz.epharm.merch.inventory.service.MerchInventoryEditorService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/admin/merch/branches/{id}/editor")
class MerchInventoryEditorController(private val editor: MerchInventoryEditorService) {
    @GetMapping fun editor(@PathVariable id: UUID,@RequestParam month: LocalDate)=privateResponse(editor.editor(id,month))
    @PutMapping fun branch(@PathVariable id: UUID,@RequestBody body: SaveMerchBranch,@AuthenticationPrincipal actor: AdminPrincipal)=privateResponse(mapOf("revision" to editor.saveBranch(id,body,actor.userId)))
    @PostMapping("/containers") fun createContainer(@PathVariable id: UUID,@RequestBody body: SaveMerchContainer,@AuthenticationPrincipal actor: AdminPrincipal)=privateResponse(mapOf("revision" to editor.saveContainer(id,null,body,actor.userId)))
    @PutMapping("/containers/{containerId}") fun container(@PathVariable id: UUID,@PathVariable containerId: UUID,@RequestBody body: SaveMerchContainer,@AuthenticationPrincipal actor: AdminPrincipal)=privateResponse(mapOf("revision" to editor.saveContainer(id,containerId,body,actor.userId)))
    @PostMapping("/containers/{containerId}/configure") fun configure(@PathVariable id: UUID,@PathVariable containerId: UUID,@RequestBody body: ConfigureMerchShelves,@AuthenticationPrincipal actor: AdminPrincipal)=privateResponse(mapOf("revision" to editor.configure(id,containerId,body,actor.userId)))
    @PostMapping("/shelves") fun createShelf(@PathVariable id: UUID,@RequestBody body: SaveMerchShelf,@AuthenticationPrincipal actor: AdminPrincipal)=privateResponse(mapOf("revision" to editor.saveShelf(id,null,body,actor.userId)))
    @PutMapping("/shelves/{shelfId}") fun shelf(@PathVariable id: UUID,@PathVariable shelfId: UUID,@RequestBody body: SaveMerchShelf,@AuthenticationPrincipal actor: AdminPrincipal)=privateResponse(mapOf("revision" to editor.saveShelf(id,shelfId,body,actor.userId)))
    @PostMapping("/prices") fun prices(@PathVariable id: UUID,@RequestBody body: UpdateMerchPrices,@AuthenticationPrincipal actor: AdminPrincipal)=privateResponse(editor.prices(id,body,actor.userId))
    @PutMapping("/floor-plan") fun floorPlan(@PathVariable id: UUID,@RequestBody body: SaveMerchFloorPlan,@AuthenticationPrincipal actor: AdminPrincipal)=privateResponse(mapOf("revision" to editor.floorPlan(id,body,actor.userId)))
    @PostMapping("/floor-plan/publish") fun publish(@PathVariable id: UUID,@RequestBody body: MerchEditorRevision,@AuthenticationPrincipal actor: AdminPrincipal)=privateResponse(mapOf("revision" to editor.publishFloorPlan(id,body,actor.userId)))
    @PostMapping("/photos",consumes=[MediaType.MULTIPART_FORM_DATA_VALUE]) fun photo(@PathVariable id: UUID,@RequestParam expectedRevision: Long,
        @RequestParam(required=false) shelfId: UUID?,@RequestParam angle: String,@RequestParam(defaultValue="") caption: String,@RequestPart file: MultipartFile,@AuthenticationPrincipal actor: AdminPrincipal)=
        privateResponse(mapOf("revision" to editor.upload(id,expectedRevision,shelfId,angle,caption,file,actor.userId)))
    @PostMapping("/photos/{photoId}/archive") fun archivePhoto(@PathVariable id: UUID,@PathVariable photoId: UUID,@RequestBody body: MerchEditorRevision,@AuthenticationPrincipal actor: AdminPrincipal)=
        privateResponse(mapOf("revision" to editor.archivePhoto(id,photoId,body,actor.userId)))
    @GetMapping("/media/{photoId}") fun media(@PathVariable id: UUID,@PathVariable photoId: UUID): ResponseEntity<ByteArray> {
        val (mime,bytes)=editor.media(id,photoId)
        val extension=when(mime){"image/png"->"png";"image/webp"->"webp";else->"jpg"}
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime)).header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CONTENT_DISPOSITION,"inline; filename=\"$photoId.$extension\"").body(bytes)
    }
}
private fun <T> privateResponse(value: T): ResponseEntity<T> = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(value)
