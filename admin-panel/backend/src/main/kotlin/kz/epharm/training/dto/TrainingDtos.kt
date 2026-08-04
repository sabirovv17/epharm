package kz.epharm.training.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import kz.epharm.training.domain.AttendanceMethod
import kz.epharm.training.domain.CertificateStatus
import kz.epharm.training.domain.DuplicateAssignmentPolicy
import kz.epharm.training.domain.EventParticipantStatus
import kz.epharm.training.domain.OfflineEventStatus
import kz.epharm.training.domain.TrainingAssignmentStatus
import kz.epharm.training.domain.TrainingFormat
import kz.epharm.training.domain.TrainingPriority
import kz.epharm.training.domain.TrainingProgramStatus
import kz.epharm.training.domain.TrainingStageStatus
import kz.epharm.training.domain.TrainingStageType
import java.time.Instant
import java.util.UUID

data class TrainingCapabilitiesDto(
    val canManagePrograms: Boolean,
    val canManageAssignments: Boolean,
    val canManageEvents: Boolean,
    val canMarkAttendance: Boolean,
    val canAdjustRewards: Boolean,
    val canRecordResults: Boolean,
    val canManagePreferences: Boolean,
    val canExport: Boolean,
    val regionalScope: List<String>,
)

data class TrainingDashboardDto(
    val activePrograms: Long,
    val totalAssignments: Long,
    val notStarted: Long,
    val inProgress: Long,
    val completed: Long,
    val overdue: Long,
    val completionRatePct: Int,
    val averageScore: Int?,
    val certificates: Long,
    val rewardsIssued: Long,
    val attendanceConfirmed: Long,
    val noShows: Long,
    val byFormat: Map<TrainingFormat, Long>,
    val capabilities: TrainingCapabilitiesDto,
)

data class TrainingProgramStageDto(
    val id: UUID,
    val key: String,
    val type: TrainingStageType,
    val title: String,
    val order: Int,
    val required: Boolean,
    val unlockAfterKey: String?,
    val deadlineDays: Int?,
    val passingScore: Int?,
    val maxAttempts: Int?,
    val bonus: Long,
    val manualReview: Boolean,
    val applicableFormats: Set<TrainingFormat>,
    val contentUrl: String?,
)

data class TrainingProgramDto(
    val id: UUID,
    val name: String,
    val shortDescription: String,
    val description: String,
    val coverUrl: String?,
    val category: String,
    val manufacturer: String,
    val brand: String,
    val product: String,
    val language: String,
    val managerId: UUID?,
    val managerName: String?,
    val allowedFormats: Set<TrainingFormat>,
    val startsAt: Instant?,
    val endsAt: Instant?,
    val normativeDays: Int,
    val tags: List<String>,
    val status: TrainingProgramStatus,
    val version: Int,
    val versionId: UUID,
    val onlineCourseId: String?,
    val passingScore: Int,
    val maxAttempts: Int,
    val completionBonus: Long,
    val stages: List<TrainingProgramStageDto>,
    val assignments: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CreateTrainingStageRequest(
    @field:NotBlank @field:Size(max = 64)
    val key: String,
    @field:NotNull
    val type: TrainingStageType,
    @field:NotBlank @field:Size(max = 255)
    val title: String,
    @field:Min(0)
    val order: Int,
    val required: Boolean = true,
    @field:Size(max = 64)
    val unlockAfterKey: String? = null,
    @field:Min(0)
    val deadlineDays: Int? = null,
    @field:Min(0) @field:Max(100)
    val passingScore: Int? = null,
    @field:Min(1)
    val maxAttempts: Int? = null,
    @field:Min(0)
    val bonus: Long = 0,
    val manualReview: Boolean = false,
    val applicableFormats: Set<TrainingFormat> = emptySet(),
    @field:Size(max = 1000)
    val contentUrl: String? = null,
)

data class CreateTrainingProgramRequest(
    @field:NotBlank @field:Size(max = 255)
    val name: String,
    @field:Size(max = 500)
    val shortDescription: String = "",
    val description: String = "",
    @field:Size(max = 1000)
    val coverUrl: String? = null,
    @field:Size(max = 128)
    val category: String = "",
    @field:Size(max = 255)
    val manufacturer: String = "",
    @field:Size(max = 255)
    val brand: String = "",
    @field:Size(max = 255)
    val product: String = "",
    @field:Size(min = 2, max = 2)
    val language: String = "ru",
    val managerId: UUID? = null,
    @field:NotEmpty
    val allowedFormats: Set<TrainingFormat>,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    @field:Min(1)
    val normativeDays: Int = 14,
    val tags: List<@Size(max = 64) String> = emptyList(),
    val status: TrainingProgramStatus = TrainingProgramStatus.draft,
    @field:Size(max = 64)
    val onlineCourseId: String? = null,
    @field:Min(0) @field:Max(100)
    val passingScore: Int = 80,
    @field:Min(1)
    val maxAttempts: Int = 3,
    @field:Min(0)
    val completionBonus: Long = 0,
    @field:Valid
    val stages: List<CreateTrainingStageRequest> = emptyList(),
)

data class UpdateTrainingProgramRequest(
    @field:Size(max = 255)
    val name: String? = null,
    @field:Size(max = 500)
    val shortDescription: String? = null,
    val description: String? = null,
    @field:Size(max = 1000)
    val coverUrl: String? = null,
    val clearCoverUrl: Boolean = false,
    @field:Size(max = 128)
    val category: String? = null,
    @field:Size(max = 255)
    val manufacturer: String? = null,
    @field:Size(max = 255)
    val brand: String? = null,
    @field:Size(max = 255)
    val product: String? = null,
    @field:Size(min = 2, max = 2)
    val language: String? = null,
    val managerId: UUID? = null,
    val clearManager: Boolean = false,
    val allowedFormats: Set<TrainingFormat>? = null,
    val startsAt: Instant? = null,
    val clearStartsAt: Boolean = false,
    val endsAt: Instant? = null,
    val clearEndsAt: Boolean = false,
    @field:Min(1)
    val normativeDays: Int? = null,
    val tags: List<String>? = null,
    val status: TrainingProgramStatus? = null,
    val onlineCourseId: String? = null,
    val clearOnlineCourseId: Boolean = false,
    @field:Min(0) @field:Max(100)
    val passingScore: Int? = null,
    @field:Min(1)
    val maxAttempts: Int? = null,
    @field:Min(0)
    val completionBonus: Long? = null,
    @field:Valid
    val stages: List<CreateTrainingStageRequest>? = null,
)

data class OfflineEventDto(
    val id: UUID,
    val programId: UUID,
    val programVersionId: UUID,
    val programName: String,
    val title: String,
    val eventType: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val timezone: String,
    val region: String,
    val city: String,
    val address: String,
    val mapUrl: String?,
    val trainerId: UUID?,
    val trainerName: String?,
    val organizer: String,
    val capacity: Int,
    val occupied: Long,
    val registrationDeadline: Instant?,
    val status: OfflineEventStatus,
    val materialsUrl: String?,
    val comment: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class TrainingEventQrDto(
    val eventId: UUID,
    val token: UUID,
    val payload: String,
)

data class CreateOfflineEventRequest(
    @field:NotNull
    val programId: UUID,
    @field:NotBlank @field:Size(max = 255)
    val title: String,
    @field:NotBlank @field:Size(max = 64)
    val eventType: String = "training",
    @field:NotNull
    val startsAt: Instant,
    @field:NotNull
    val endsAt: Instant,
    @field:NotBlank @field:Size(max = 64)
    val timezone: String = "Asia/Almaty",
    @field:Size(max = 128)
    val region: String = "",
    @field:Size(max = 128)
    val city: String = "",
    @field:Size(max = 500)
    val address: String = "",
    @field:Size(max = 1000)
    val mapUrl: String? = null,
    val trainerId: UUID? = null,
    @field:Size(max = 255)
    val organizer: String = "",
    @field:Min(1)
    val capacity: Int,
    val registrationDeadline: Instant? = null,
    val status: OfflineEventStatus = OfflineEventStatus.scheduled,
    @field:Size(max = 1000)
    val materialsUrl: String? = null,
    val comment: String = "",
)

data class UpdateOfflineEventRequest(
    @field:Size(max = 255)
    val title: String? = null,
    @field:Size(max = 64)
    val eventType: String? = null,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    @field:Size(max = 64)
    val timezone: String? = null,
    @field:Size(max = 128)
    val region: String? = null,
    @field:Size(max = 128)
    val city: String? = null,
    @field:Size(max = 500)
    val address: String? = null,
    @field:Size(max = 1000)
    val mapUrl: String? = null,
    val trainerId: UUID? = null,
    @field:Size(max = 255)
    val organizer: String? = null,
    @field:Min(1)
    val capacity: Int? = null,
    val registrationDeadline: Instant? = null,
    val clearRegistrationDeadline: Boolean = false,
    val status: OfflineEventStatus? = null,
    @field:Size(max = 1000)
    val materialsUrl: String? = null,
    val comment: String? = null,
)

data class EventParticipantDto(
    val id: UUID,
    val eventId: UUID,
    val assignmentId: UUID,
    val pharmacistId: String,
    val pharmacistName: String,
    val pharmacyName: String,
    val status: EventParticipantStatus,
    val registeredAt: Instant,
    val checkedInAt: Instant?,
    val checkMethod: AttendanceMethod?,
    val trainerComment: String?,
    val score: Int?,
)

data class MarkAttendanceRequest(
    @field:NotNull
    val status: EventParticipantStatus,
    val method: AttendanceMethod = AttendanceMethod.manual,
    @field:Size(max = 1000)
    val comment: String? = null,
    @field:Min(0) @field:Max(100)
    val score: Int? = null,
)

data class TrainingAssignmentStageDto(
    val id: UUID,
    val programStageId: UUID,
    val key: String,
    val type: TrainingStageType,
    val title: String,
    val order: Int,
    val required: Boolean,
    val status: TrainingStageStatus,
    val progressPct: Int,
    val score: Int?,
    val attemptsUsed: Int,
    val maxAttempts: Int?,
    val passingScore: Int?,
    val contentUrl: String?,
    val startedAt: Instant?,
    val completedAt: Instant?,
)

data class TrainingAssignmentDto(
    val id: UUID,
    val programId: UUID,
    val programVersionId: UUID,
    val programVersion: Int,
    val programName: String,
    val programShortDescription: String,
    val coverUrl: String?,
    val pharmacistId: String,
    val pharmacistName: String,
    val pharmacyName: String,
    val city: String,
    val format: TrainingFormat,
    val status: TrainingAssignmentStatus,
    val priority: TrainingPriority,
    val required: Boolean,
    val event: OfflineEventSummaryDto?,
    val startsAt: Instant?,
    val dueAt: Instant?,
    val progressPct: Int,
    val score: Int?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val stages: List<TrainingAssignmentStageDto>,
    val certificate: TrainingCertificateDto?,
    val reward: TrainingRewardDto?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class TrainingAssignmentPageDto(
    val items: List<TrainingAssignmentDto>,
    val total: Int,
    val page: Int,
    val size: Int,
    val totalPages: Int,
)

data class OfflineEventSummaryDto(
    val id: UUID,
    val title: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val timezone: String,
    val city: String,
    val address: String,
    val status: OfflineEventStatus,
)

data class CreateTrainingAssignmentsRequest(
    @field:NotNull
    val programId: UUID,
    @field:NotEmpty
    val pharmacistIds: Set<@NotBlank String>,
    @field:NotNull
    val format: TrainingFormat,
    val startsAt: Instant? = null,
    val dueAt: Instant? = null,
    val eventId: UUID? = null,
    val priority: TrainingPriority = TrainingPriority.normal,
    val required: Boolean = true,
    val responsibleId: UUID? = null,
    val duplicatePolicy: DuplicateAssignmentPolicy = DuplicateAssignmentPolicy.skip,
)

data class MassAssignmentResultDto(
    val created: Int,
    val updated: Int,
    val skipped: Int,
    val assignments: List<TrainingAssignmentDto>,
)

data class ChangeAssignmentFormatRequest(
    @field:NotNull
    val format: TrainingFormat,
    val eventId: UUID? = null,
    @field:NotBlank @field:Size(max = 500)
    val reason: String,
)

data class TrainingAssignmentFormatHistoryDto(
    val id: UUID,
    val assignmentId: UUID,
    val oldFormat: TrainingFormat,
    val newFormat: TrainingFormat,
    val oldEventId: UUID?,
    val newEventId: UUID?,
    val reason: String,
    val changedBy: UUID,
    val changedByName: String,
    val changedAt: Instant,
)

data class StageProgressRequest(
    @field:Min(0) @field:Max(100)
    val progressPct: Int,
)

data class RecordAssessmentResultRequest(
    @field:Min(0) @field:Max(100)
    val score: Int,
    val passed: Boolean? = null,
    @field:Size(max = 2000)
    val feedback: String = "",
    val competencyScores: Map<String, @Min(0) @Max(100) Int> = emptyMap(),
)

data class TrainingAssessmentResultDto(
    val id: UUID,
    val assignmentId: UUID,
    val assignmentStageId: UUID,
    val sourceType: TrainingStageType,
    val attempt: Int,
    val score: Int,
    val passed: Boolean,
    val feedback: String,
    val competencyScores: Map<String, Int>,
    val recordedBy: UUID,
    val recordedByName: String,
    val recordedAt: Instant,
)

data class ChangeTrainingPreferenceRequest(
    @field:NotNull
    val defaultFormat: TrainingFormat,
    @field:NotBlank @field:Size(max = 500)
    val reason: String,
)

data class TrainingPreferenceDto(
    val id: UUID,
    val pharmacistId: String,
    val pharmacistName: String,
    val defaultFormat: TrainingFormat,
    val changedBy: UUID,
    val changedByName: String,
    val reason: String,
    val validFrom: Instant,
    val validTo: Instant?,
    val current: Boolean,
)

data class MassChangeTrainingPreferencesRequest(
    @field:NotEmpty
    val pharmacistIds: Set<@NotBlank String>,
    @field:NotNull
    val defaultFormat: TrainingFormat,
    @field:NotBlank @field:Size(max = 500)
    val reason: String,
)

data class TrainingCertificateDto(
    val id: UUID,
    val number: String,
    val assignmentId: UUID,
    val pharmacistId: String,
    val pharmacistName: String,
    val programName: String,
    val format: TrainingFormat,
    val issuedAt: Instant,
    val expiresAt: Instant?,
    val score: Int?,
    val signerName: String,
    val status: CertificateStatus,
    val verificationToken: UUID,
    val pdfUrl: String?,
)

data class TrainingRewardDto(
    val id: UUID,
    val assignmentId: UUID,
    val amount: Long,
    val reason: String,
    val status: String,
    val issuedAt: Instant,
)

data class TrainingNotificationDto(
    val id: UUID,
    val eventType: String,
    val title: String,
    val message: String,
    val assignmentId: UUID?,
    val eventId: UUID?,
    val read: Boolean,
    val scheduledAt: Instant,
    val readAt: Instant?,
)

data class CertificateVerificationDto(
    val number: String,
    val pharmacistName: String,
    val programName: String,
    val format: TrainingFormat,
    val issuedAt: Instant,
    val expiresAt: Instant?,
    val score: Int?,
    val signerName: String,
    val status: CertificateStatus,
    val valid: Boolean,
)

data class PharmacistTrainingProfileDto(
    val pharmacistId: String,
    val pharmacistName: String,
    val pharmacyName: String,
    val city: String,
    val defaultFormat: TrainingFormat?,
    val totalAssignments: Int,
    val completedAssignments: Int,
    val inProgressAssignments: Int,
    val totalRewards: Long,
    val assignments: List<TrainingAssignmentDto>,
    val certificates: List<TrainingCertificateDto>,
    val rewards: List<TrainingRewardDto>,
    val preferenceHistory: List<TrainingPreferenceDto>,
)

data class MobileTrainingOverviewDto(
    val total: Int,
    val inProgress: Int,
    val completed: Int,
    val overdue: Int,
    val upcomingEvents: List<OfflineEventSummaryDto>,
    val assignments: List<TrainingAssignmentDto>,
    val certificates: List<TrainingCertificateDto>,
    val notifications: List<TrainingNotificationDto>,
    val defaultFormat: TrainingFormat?,
)
