package kz.epharm.merch.tasks

import java.util.UUID
import kz.epharm.auth.security.AdminPrincipal
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/admin/merch/tasks")
class MerchTaskController(private val tasks: MerchTaskService,private val planning:TaskPlanningService) {
    @GetMapping("/templates") fun templates()=privateResponse(planning.catalog())
    @PostMapping("/templated") fun templated(@RequestBody request:CreateTemplatedTasks,@AuthenticationPrincipal actor:AdminPrincipal)=privateResponse(planning.create(request,actor.userId))
    @GetMapping("/visits/branches") fun visitBranches()=privateResponse(planning.visitBranches())
    @PostMapping("/visits") fun visit(@RequestBody request:StartMonthlyVisit,@AuthenticationPrincipal actor:AdminPrincipal)=privateResponse(planning.visit(request,actor=actor.userId))
    @GetMapping("/items/{itemId}/comments") fun comments(@PathVariable itemId:UUID,@RequestParam(defaultValue="20") limit:Int,@RequestParam(defaultValue="0") offset:Int)=privateResponse(tasks.commentsAdmin(itemId,limit,offset))
    @PostMapping("/items/{itemId}/comments") fun comment(@PathVariable itemId:UUID,@RequestBody request:AddTaskComment,@AuthenticationPrincipal actor:AdminPrincipal)=privateResponse(tasks.commentAdmin(itemId,request,actor.userId))
    @PostMapping("/items/{itemId}/block") fun block(@PathVariable itemId:UUID,@RequestBody request:BlockTaskItem,@AuthenticationPrincipal actor:AdminPrincipal)=privateResponse(tasks.blockAdmin(itemId,request,actor.userId))
    @PostMapping("/{id}/cancel") fun cancel(@PathVariable id:UUID,@RequestBody request:CancelTaskDispatch,@AuthenticationPrincipal actor:AdminPrincipal)=privateResponse(tasks.cancel(id,request,actor.userId))
    @PostMapping("/items/{itemId}/cancel") fun cancelItem(@PathVariable itemId:UUID,@RequestBody request:CancelTaskItem,@AuthenticationPrincipal actor:AdminPrincipal)=privateResponse(tasks.cancelItem(itemId,request,actor.userId))
    @GetMapping
    fun list(@RequestParam(defaultValue = "20") limit: Int, @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "all") queue:String, @RequestParam(required=false) q:String?) =
        privateResponse(tasks.list(limit, offset,queue,q))
    @GetMapping("/branches")
    fun branches() = privateResponse(tasks.branches())
    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID) = privateResponse(tasks.get(id))
    @PostMapping
    fun create(@RequestBody request: CreateMerchTaskDispatch, @AuthenticationPrincipal actor: AdminPrincipal) =
        privateResponse(tasks.create(request, actor.userId))
    @PostMapping("/bulk")
    fun bulk(@RequestBody request:CreateMerchTaskBulk,@AuthenticationPrincipal actor:AdminPrincipal)=
        privateResponse(tasks.createBulk(request,actor.userId))
    @PostMapping("/items/{itemId}/attempts")
    fun start(@PathVariable itemId: UUID, @AuthenticationPrincipal actor: AdminPrincipal) = privateResponse(tasks.startAdmin(itemId, actor.userId))
    @GetMapping("/items/{itemId}/attempts")
    fun history(@PathVariable itemId: UUID, @RequestParam(defaultValue = "20") limit: Int, @RequestParam(defaultValue = "0") offset: Int) =
        privateResponse(tasks.history(itemId, limit, offset))
    @PostMapping("/items/{itemId}/evidence", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@PathVariable itemId: UUID, @RequestParam clientUploadId: UUID, @RequestPart file: MultipartFile,
        @AuthenticationPrincipal actor: AdminPrincipal, @RequestPart(required=false) capture:TaskCapture?) = privateResponse(tasks.uploadAdmin(itemId, clientUploadId, file, actor.userId,capture))
    @PostMapping("/items/{itemId}/stock")
    fun stock(@PathVariable itemId:UUID,@RequestBody request:SaveTaskStock,@AuthenticationPrincipal actor:AdminPrincipal)=privateResponse(tasks.saveStockAdmin(itemId,request,actor.userId))
    @GetMapping("/items/{itemId}/reference") fun reference(@PathVariable itemId:UUID)=evidenceResponse(tasks.referenceAdmin(itemId))
    @GetMapping("/items/{itemId}/instruction")
    fun instruction(@PathVariable itemId:UUID)=evidenceResponse(tasks.instructionAdmin(itemId))
    @PostMapping("/items/{itemId}/submit")
    fun submit(@PathVariable itemId: UUID, @RequestBody request: SubmitMerchTaskAttempt, @AuthenticationPrincipal actor: AdminPrincipal) =
        privateResponse(tasks.submitAdmin(itemId, request, actor.userId))
    @PostMapping("/items/{itemId}/reviews")
    fun review(@PathVariable itemId: UUID, @RequestBody request: ReviewMerchTaskItem, @AuthenticationPrincipal actor: AdminPrincipal) =
        privateResponse(tasks.review(itemId, request, actor.userId))
    @GetMapping("/evidence/{id}")
    fun evidence(@PathVariable id: UUID) = evidenceResponse(tasks.downloadAdmin(id))
}

internal fun <T> privateResponse(value: T): ResponseEntity<T> = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(value)
internal fun evidenceResponse(value: Pair<MerchTaskEvidenceDto, ByteArray>): ResponseEntity<ByteArray> = ResponseEntity.ok()
    .contentType(MediaType.parseMediaType(value.first.mimeType)).header(HttpHeaders.CACHE_CONTROL, "no-store")
    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${value.first.id}\"")
    .header("X-Content-SHA256", TaskEvidenceStorage.hash(value.second))
    .header("X-Content-Type-Options", "nosniff").body(value.second)
