package kz.epharm.merch.tasks

import java.util.UUID
import kz.epharm.auth.security.AdminPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/merch/tasks/items/{itemId}/source-continuation")
class SourceTaskContinuationController(private val continuation:SourceTaskContinuationService) {
    @PostMapping
    fun continueItem(@PathVariable itemId:UUID,@RequestBody request:ContinueSourceTask,@AuthenticationPrincipal actor:AdminPrincipal)=
        privateResponse(continuation.continueItem(itemId,request,actor.userId))
}
