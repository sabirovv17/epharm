package kz.epharm.merch.tasks

import java.util.UUID
import kz.epharm.mobile.auth.security.PharmacistPrincipal
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/mobile/merch/tasks")
class PharmacistMerchTaskController(private val tasks: MerchTaskService,private val planning:TaskPlanningService) {
    @GetMapping("/visits/branches") fun visitBranches(@AuthenticationPrincipal actor:PharmacistPrincipal)=privateResponse(planning.visitBranches(actor.pharmacistId))
    @PostMapping("/visits") fun visit(@RequestBody request:StartMonthlyVisit,@AuthenticationPrincipal actor:PharmacistPrincipal)=privateResponse(planning.visit(request,pharmacist=actor.pharmacistId))
    @GetMapping("/items/{itemId}/comments") fun comments(@PathVariable itemId:UUID,@RequestParam(defaultValue="20") limit:Int,@RequestParam(defaultValue="0") offset:Int,@AuthenticationPrincipal actor:PharmacistPrincipal)=privateResponse(tasks.commentsPharmacist(itemId,actor.pharmacistId,limit,offset))
    @PostMapping("/items/{itemId}/comments") fun comment(@PathVariable itemId:UUID,@RequestBody request:AddTaskComment,@AuthenticationPrincipal actor:PharmacistPrincipal)=privateResponse(tasks.commentPharmacist(itemId,request,actor.pharmacistId))
    @PostMapping("/items/{itemId}/block") fun block(@PathVariable itemId:UUID,@RequestBody request:BlockTaskItem,@AuthenticationPrincipal actor:PharmacistPrincipal)=privateResponse(tasks.blockPharmacist(itemId,request,actor.pharmacistId))
    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID, @AuthenticationPrincipal actor: PharmacistPrincipal) = privateResponse(tasks.getPharmacist(id, actor.pharmacistId))
    @PostMapping("/items/{itemId}/attempts")
    fun start(@PathVariable itemId: UUID, @AuthenticationPrincipal actor: PharmacistPrincipal) =
        privateResponse(tasks.startPharmacist(itemId, actor.pharmacistId))
    @PostMapping("/items/{itemId}/evidence", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@PathVariable itemId: UUID, @RequestParam clientUploadId: UUID, @RequestPart file: MultipartFile,
        @AuthenticationPrincipal actor: PharmacistPrincipal,@RequestPart(required=false) capture:TaskCapture?) = privateResponse(tasks.uploadPharmacist(itemId, clientUploadId, file, actor.pharmacistId,capture))
    @PostMapping("/items/{itemId}/stock")
    fun stock(@PathVariable itemId:UUID,@RequestBody request:SaveTaskStock,@AuthenticationPrincipal actor:PharmacistPrincipal)=privateResponse(tasks.saveStockPharmacist(itemId,request,actor.pharmacistId))
    @GetMapping("/items/{itemId}/reference") fun reference(@PathVariable itemId:UUID,@AuthenticationPrincipal actor:PharmacistPrincipal)=evidenceResponse(tasks.referencePharmacist(itemId,actor.pharmacistId))
    @GetMapping("/items/{itemId}/instruction")
    fun instruction(@PathVariable itemId:UUID,@AuthenticationPrincipal actor:PharmacistPrincipal)=evidenceResponse(tasks.instructionPharmacist(itemId,actor.pharmacistId))
    @PostMapping("/items/{itemId}/submit")
    fun submit(@PathVariable itemId: UUID, @RequestBody request: SubmitMerchTaskAttempt, @AuthenticationPrincipal actor: PharmacistPrincipal) =
        privateResponse(tasks.submitPharmacist(itemId, request, actor.pharmacistId))
    @GetMapping("/evidence/{id}")
    fun evidence(@PathVariable id: UUID, @AuthenticationPrincipal actor: PharmacistPrincipal) = evidenceResponse(tasks.downloadPharmacist(id, actor.pharmacistId))
}
