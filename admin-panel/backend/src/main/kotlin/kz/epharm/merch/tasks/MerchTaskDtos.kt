package kz.epharm.merch.tasks

import java.time.Instant
import java.util.UUID

data class CreateMerchTaskItem(val title: String, val instructions: String = "", val required: Boolean = true,
    val requiredPhotos: Int = 1, val checklist: List<String> = emptyList(),
    val shelfId: UUID? = null, val stockProducts: List<TaskStockProduct> = emptyList(), val mediaPlanItemId: UUID? = null)
data class CreateMerchTaskDispatch(val branchId: UUID, val title: String, val dueAt: Instant,
    val rewardAmountKzt: Long = 0, val assignedPharmacistId: String? = null, val items: List<CreateMerchTaskItem>,
    val priority: String = "normal", val availableFrom: Instant? = null, val taskType: String = "photo_report")
data class CreateMerchTaskBulk(val requestId: UUID, val branchIds: Set<UUID>, val title: String, val dueAt: Instant,
    val rewardAmountKzt: Long = 0, val items: List<CreateMerchTaskItem>,
    val priority: String = "normal", val availableFrom: Instant? = null, val taskType: String = "photo_report")
data class MerchTaskBulkResult(val dispatchIds: List<UUID>)
data class TaskChecklistItem(val id: UUID, val title: String, val required: Boolean = true)
data class SubmitMerchTaskAttempt(val expectedAttempt: Int, val completedChecklistIds: Set<UUID>, val comment: String = "")
data class ReviewMerchTaskItem(val attemptId: UUID, val expectedItemVersion: Long, val decision: String, val reason: String,
    val manualUnverifiedExecutor: Boolean = false, val manualSourcePreserved: Boolean = false)
data class MerchTaskEvidenceDto(val id: UUID, val mimeType: String, val byteSize: Long, val sha256: String?,
    val originalFilename: String?, val createdAt: Instant, val verification: String = "manual_review_required",
    val capture: Map<String,Any?> = emptyMap())
data class MerchTaskReviewDto(val id: UUID, val attemptId: UUID, val reviewerAccountId: UUID?, val reviewerPersonId: UUID?,
    val decision: String, val reason: String, val createdAt: Instant)
data class MerchTaskAttemptDto(val id: UUID, val number: Int, val status: String, val executorPersonId: UUID?,
    val pharmacistId: String?, val submittedAt: Instant?, val createdAt: Instant, val evidence: List<MerchTaskEvidenceDto>,
    val review: MerchTaskReviewDto?, val comment: String = "", val completedChecklistIds: Set<UUID> = emptySet(),
    val executorIdentityStatus: String = "unresolved")
data class MerchTaskItemDto(val id: UUID, val title: String, val instructions: String, val taskType: String, val status: String,
    val required: Boolean, val requiredPhotos: Int, val checklist: List<TaskChecklistItem>, val version: Long,
    val currentAttempt: MerchTaskAttemptDto?, val legacyEvidence: List<MerchTaskEvidenceDto>,
    val availableFrom: Instant?, val dueAt: Instant?, val canStart: Boolean = false, val canUpload: Boolean = false,
    val canSubmit: Boolean = false, val canReview: Boolean = false, val canResumeSource: Boolean = false,
    val canAdoptSourceReview: Boolean = false, val execution: TaskExecutionPolicy? = null,
    val canBlock: Boolean = false, val blockedReason: String? = null, val canComment: Boolean = false,
    val canCancel: Boolean = false)
data class MerchTaskBranchDto(val id: UUID, val name: String, val city: String, val address: String,
    val active: Boolean, val createEligible: Boolean, val canConfigureReward: Boolean = false)
data class MerchTaskRewardDto(val state: String, val amountKzt: Long, val reason: String, val walletEntryId: UUID?)
data class MerchTaskDispatchDto(val id: UUID, val branchId: UUID, val title: String, val status: String, val dueAt: Instant?,
    val sourceSystem: String, val version: Long, val rewardAmountKzt: Long?, val items: List<MerchTaskItemDto>,
    val reward: MerchTaskRewardDto?, val itemCount: Int, val detailsIncluded: Boolean, val priority: String, val taskType: String,
    val canCancel: Boolean = false)
data class MerchTaskDispatchPage(val items: List<MerchTaskDispatchDto>, val limit: Int, val offset: Int, val hasMore: Boolean)
data class MerchTaskAttemptPage(val items: List<MerchTaskAttemptDto>, val limit: Int, val offset: Int, val hasMore: Boolean)

internal data class TaskDispatchRow(val id: UUID, val branchId: UUID, val title: String, val status: String, val dueAt: Instant?,
    val reward: Long, val assignedPharmacist: String?, val source: String, val historicalReward: Boolean, val version: Long,
    val availableFrom: Instant?, val pharmacyId: String?, val pharmacyBindingVerified: Boolean, val branchActive: Boolean,
    val priority: String, val taskType: String)
internal data class TaskItemRow(val id: UUID, val dispatchId: UUID, val title: String, val instructions: String, val type: String,
    val status: String, val required: Boolean, val requiredPhotos: Int, val checklist: List<TaskChecklistItem>,
    val attempt: Int, val version: Long, val availableFrom: Instant?, val dueAt: Instant?)
internal data class TaskExecutor(val personId: UUID?, val accountId: UUID?, val pharmacistId: String?, val bindingId: UUID?,
    val capabilityLinkId: UUID? = null) {
    init {
        require(personId != null || capabilityLinkId != null)
        require(capabilityLinkId == null || (personId == null && accountId == null && pharmacistId == null && bindingId == null))
    }
}
