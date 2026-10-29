package kz.epharm.merch.placements.reference

import kz.epharm.merch.placements.dto.PlacementActor
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID
import kz.epharm.auth.security.AdminPrincipal

@RestController
@RequestMapping("/api/admin/merch/placements")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD','CATEGORY_LEAD','REGIONAL_MANAGER')")
class PlacementReferencePhotoController(private val photos:PlacementReferencePhotoService) {
    @GetMapping("/{plan}/items/{item}/reference-photo") fun view(@PathVariable plan:UUID,@PathVariable item:UUID,@AuthenticationPrincipal user:AdminPrincipal)=privatePhoto(photos.view(plan,item,PlacementActor.Internal(user.userId)))
    @PostMapping("/{plan}/items/{item}/reference-photo",consumes=["multipart/form-data"])
    fun upload(@PathVariable plan:UUID,@PathVariable item:UUID,@RequestParam expectedRevision:Long,@RequestParam requestId:UUID,@RequestPart file:MultipartFile,@AuthenticationPrincipal user:AdminPrincipal)=privatePhoto(photos.upload(plan,item,expectedRevision,requestId,file,PlacementActor.Internal(user.userId)))
    @PostMapping("/{plan}/items/{item}/reference-photo/archive") fun archive(@PathVariable plan:UUID,@PathVariable item:UUID,@RequestBody body:ArchiveReferencePhoto,@AuthenticationPrincipal user:AdminPrincipal)=privatePhoto(photos.archive(plan,item,body,PlacementActor.Internal(user.userId)))
    @GetMapping("/{plan}/items/{item}/reference-photo/{photo}/file") fun file(@PathVariable plan:UUID,@PathVariable item:UUID,@PathVariable photo:UUID,@AuthenticationPrincipal user:AdminPrincipal)=privatePhotoFile(photos.file(plan,item,photo,PlacementActor.Internal(user.userId)))
}

internal fun <T> privatePhoto(value:T):ResponseEntity<T> = ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").body(value)
internal fun privatePhotoFile(value:Pair<String,ByteArray>):ResponseEntity<ByteArray> {
    val extension=when(value.first) {"image/png"->"png";"image/webp"->"webp";else->"jpg"}
    return ResponseEntity.ok().contentType(MediaType.parseMediaType(value.first)).header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").header("Content-Disposition","inline; filename=\"reference-photo.$extension\"").body(value.second)
}
