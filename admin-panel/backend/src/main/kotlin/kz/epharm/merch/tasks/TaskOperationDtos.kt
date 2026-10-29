package kz.epharm.merch.tasks

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class AddTaskComment(val requestId:UUID,val body:String)
data class TaskCommentDto(val id:UUID,val body:String,val authorLabel:String,val isOwn:Boolean,val createdAt:Instant)
data class TaskCommentPage(val items:List<TaskCommentDto>,val hasMore:Boolean)
data class BlockTaskItem(val expectedItemVersion:Long,val reason:String)
data class CancelTaskDispatch(val expectedVersion:Long,val reason:String)
data class CancelTaskItem(val expectedItemVersion:Long,val reason:String)
data class StartMonthlyVisit(val branchId:UUID,val month:LocalDate)
data class MonthlyVisitResult(val dispatchId:UUID,val resumed:Boolean)
data class TaskTemplateDto(val id:String,val title:String,val instructions:String,val shelfMode:String,val requiredPhotos:Int,val checklist:List<String>)
data class CreateTemplatedTasks(val requestId:UUID,val branchIds:Set<UUID>,val templateType:String,val shelfMode:String,val month:LocalDate,
    val dueAt:Instant,val rewardAmountKzt:Long=0,val stockProducts:List<TaskStockProduct> = emptyList())
