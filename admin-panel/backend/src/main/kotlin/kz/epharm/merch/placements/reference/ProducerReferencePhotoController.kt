package kz.epharm.merch.placements.reference

import kz.epharm.merch.placements.dto.PlacementActor
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID
import kz.epharm.merch.producer.auth.ProducerPrincipal

@RestController
@RequestMapping("/api/producer/placements")
@PreAuthorize("hasRole('PRODUCER')")
class ProducerReferencePhotoController(private val photos:PlacementReferencePhotoService) {
    @GetMapping("/{plan}/items/{item}/reference-photo") fun view(@PathVariable plan:UUID,@PathVariable item:UUID,@AuthenticationPrincipal user:ProducerPrincipal)=privatePhoto(photos.view(plan,item,actor(user)))
    @PostMapping("/{plan}/items/{item}/reference-photo",consumes=["multipart/form-data"])
    fun upload(@PathVariable plan:UUID,@PathVariable item:UUID,@RequestParam expectedRevision:Long,@RequestParam requestId:UUID,@RequestPart file:MultipartFile,@AuthenticationPrincipal user:ProducerPrincipal)=privatePhoto(photos.upload(plan,item,expectedRevision,requestId,file,actor(user)))
    @PostMapping("/{plan}/items/{item}/reference-photo/archive") fun archive(@PathVariable plan:UUID,@PathVariable item:UUID,@RequestBody body:ArchiveReferencePhoto,@AuthenticationPrincipal user:ProducerPrincipal)=privatePhoto(photos.archive(plan,item,body,actor(user)))
    @GetMapping("/{plan}/items/{item}/reference-photo/{photo}/file") fun file(@PathVariable plan:UUID,@PathVariable item:UUID,@PathVariable photo:UUID,@AuthenticationPrincipal user:ProducerPrincipal)=privatePhotoFile(photos.file(plan,item,photo,actor(user)))
    private fun actor(user:ProducerPrincipal)=PlacementActor.Producer(user.customerId,user.sessionId)
}
