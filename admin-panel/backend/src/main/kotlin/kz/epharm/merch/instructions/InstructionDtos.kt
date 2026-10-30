package kz.epharm.merch.instructions

import com.fasterxml.jackson.annotation.JsonAnySetter
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

abstract class StrictInstructionRequest {
    @JsonAnySetter fun rejectUnknown(name: String,value: Any?) { throw IllegalArgumentException("Unsupported instruction field") }
}
class CreateInstruction(val id: UUID,val producerId: UUID?=null,val planIds: List<UUID>,val title: String,val instructions: String,val dueAt: Instant):StrictInstructionRequest()
class ReviewInstruction(val expectedRevision: Long,val decision: String,val reason: String):StrictInstructionRequest()
data class InstructionDto(val id: UUID,val producerId: UUID?,val title: String,val instructions: String,val dueAt: Instant,
    val status: String,val revision: Long,val planIds: List<UUID>,val reviewNote: String?,val createdAt: Instant,
    val canReview: Boolean,val completed: Long,val totalTasks: Long)
data class InstructionPage(val items: List<InstructionDto>,val total: Long,val limit: Int,val offset: Int)
data class InstructionTarget(val id: UUID,val producerId: UUID,val producerName: String?,val month: LocalDate,val label: String,val canUpload: Boolean)
data class InstructionTargetPage(val items: List<InstructionTarget>,val total: Long,val limit: Int,val offset: Int)
data class InstructionResult(val id: UUID,val branchId: UUID,val branchName: String,val itemTitle: String,val mimeType: String,val byteSize: Long,val approvedAt: Instant)
data class InstructionResultPage(val items: List<InstructionResult>,val total: Long,val limit: Int,val offset: Int)
data class InstructionFile(val mimeType: String,val bytes: ByteArray,val sha256: String)
