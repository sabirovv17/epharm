package kz.epharm.merch.tasks

import java.util.UUID
import kz.epharm.mobile.auth.security.PharmacistPrincipal
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/mobile/merch/tasks")
class PharmacistMerchTaskController(private val tasks: MerchTaskService) {
    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID, @AuthenticationPrincipal actor: PharmacistPrincipal) = privateResponse(tasks.getPharmacist(id, actor.pharmacistId))
    @PostMapping("/items/{itemId}/attempts")
    fun start(@PathVariable itemId: UUID, @AuthenticationPrincipal actor: PharmacistPrincipal) =
        privateResponse(tasks.startPharmacist(itemId, actor.pharmacistId))
    @PostMapping("/items/{itemId}/evidence", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@PathVariable itemId: UUID, @RequestParam clientUploadId: UUID, @RequestPart file: MultipartFile,
        @AuthenticationPrincipal actor: PharmacistPrincipal) = privateResponse(tasks.uploadPharmacist(itemId, clientUploadId, file, actor.pharmacistId))
    @PostMapping("/items/{itemId}/submit")
    fun submit(@PathVariable itemId: UUID, @RequestBody request: SubmitMerchTaskAttempt, @AuthenticationPrincipal actor: PharmacistPrincipal) =
        privateResponse(tasks.submitPharmacist(itemId, request, actor.pharmacistId))
    @GetMapping("/evidence/{id}")
    fun evidence(@PathVariable id: UUID, @AuthenticationPrincipal actor: PharmacistPrincipal) = evidenceResponse(tasks.downloadPharmacist(id, actor.pharmacistId))
}
