package kz.epharm.merch.tasks

import java.util.UUID
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

/** Every method reauthorizes the header capability; a task ID or QR possession never proves a person. */
@RestController
@RequestMapping("/api/public/merch/tasks")
class CapabilityMerchTaskController(private val tasks:MerchTaskService) {
    @GetMapping("/items/{itemId}/comments") fun comments(@PathVariable itemId:UUID,@RequestHeader("X-Merch-Task-Token") token:String,
        @RequestParam(defaultValue="20") limit:Int,@RequestParam(defaultValue="0") offset:Int)=response(tasks.commentsCapability(itemId,token,limit,offset))
    @GetMapping
    fun get(@RequestHeader("X-Merch-Task-Token") token:String)=response(tasks.getCapability(token))
    @PostMapping("/items/{itemId}/attempts")
    fun start(@PathVariable itemId:UUID,@RequestHeader("X-Merch-Task-Token") token:String)=response(tasks.startCapability(itemId,token))
    @PostMapping("/items/{itemId}/evidence",consumes=[MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@PathVariable itemId:UUID,@RequestParam clientUploadId:UUID,@RequestPart file:MultipartFile,
        @RequestHeader("X-Merch-Task-Token") token:String,@RequestPart(required=false) capture:TaskCapture?)=response(tasks.uploadCapability(itemId,clientUploadId,file,token,capture))
    @PostMapping("/items/{itemId}/stock")
    fun stock(@PathVariable itemId:UUID,@RequestBody request:SaveTaskStock,@RequestHeader("X-Merch-Task-Token") token:String)=response(tasks.saveStockCapability(itemId,request,token))
    @GetMapping("/items/{itemId}/reference") fun reference(@PathVariable itemId:UUID,@RequestHeader("X-Merch-Task-Token") token:String)=evidenceResponse(tasks.referenceCapability(itemId,token))
    @GetMapping("/items/{itemId}/instruction")
    fun instruction(@PathVariable itemId:UUID,@RequestHeader("X-Merch-Task-Token") token:String)=evidenceResponse(tasks.instructionCapability(itemId,token))
    @PostMapping("/items/{itemId}/submit")
    fun submit(@PathVariable itemId:UUID,@RequestBody request:SubmitMerchTaskAttempt,
        @RequestHeader("X-Merch-Task-Token") token:String)=response(tasks.submitCapability(itemId,request,token))
    @GetMapping("/evidence/{id}")
    fun evidence(@PathVariable id:UUID,@RequestHeader("X-Merch-Task-Token") token:String):ResponseEntity<ByteArray> {
        val (metadata,bytes)=tasks.downloadCapability(id,token)
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(metadata.mimeType)).header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"${metadata.id}\"")
            .header("X-Content-SHA256",TaskEvidenceStorage.hash(bytes)).header("X-Content-Type-Options","nosniff").body(bytes)
    }
    private fun <T> response(value:T)=ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(value)
}
