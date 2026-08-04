package kz.epharm.training.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import kz.epharm.training.domain.AttendanceMethod
import kz.epharm.training.domain.CertificateStatus
import kz.epharm.training.domain.EventParticipantStatus
import kz.epharm.training.domain.OfflineEventStatus
import kz.epharm.training.domain.TrainingAssignmentStatus
import kz.epharm.training.domain.TrainingFormat
import kz.epharm.training.domain.TrainingPriority
import kz.epharm.training.domain.TrainingProgramStatus
import kz.epharm.training.domain.TrainingRewardStatus
import kz.epharm.training.domain.TrainingStageStatus
import kz.epharm.training.domain.TrainingStageType
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "training_programs")
class TrainingProgramEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    var name: String = "",
    @Column(name = "short_description", nullable = false)
    var shortDescription: String = "",
    @Column(nullable = false, columnDefinition = "TEXT")
    var description: String = "",
    @Column(name = "cover_url")
    var coverUrl: String? = null,
    @Column(nullable = false)
    var category: String = "",
    @Column(nullable = false)
    var manufacturer: String = "",
    @Column(nullable = false)
    var brand: String = "",
    @Column(nullable = false)
    var product: String = "",
    @Column(nullable = false, length = 8)
    var language: String = "ru",
    @Column(name = "manager_id")
    var managerId: UUID? = null,
    @Column(name = "allowed_formats", nullable = false)
    var allowedFormatsRaw: String = TrainingFormat.online.name,
    @Column(name = "starts_at")
    var startsAt: Instant? = null,
    @Column(name = "ends_at")
    var endsAt: Instant? = null,
    @Column(name = "normative_days", nullable = false)
    var normativeDays: Int = 14,
    @Column(name = "tags", nullable = false)
    var tagsRaw: String = "",
    @Column(name = "status", nullable = false, length = 32)
    var statusRaw: String = TrainingProgramStatus.draft.name,
    @Column(name = "current_version", nullable = false)
    var currentVersion: Int = 1,
    @Column(name = "published_at")
    var publishedAt: Instant? = null,
    @Column(name = "archived_at")
    var archivedAt: Instant? = null,
    @Column(name = "created_by", nullable = false)
    var createdBy: UUID = UUID.randomUUID(),
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var status: TrainingProgramStatus
        get() = TrainingProgramStatus.valueOf(statusRaw)
        set(value) { statusRaw = value.name }

    var allowedFormats: Set<TrainingFormat>
        get() = allowedFormatsRaw.split(',').filter(String::isNotBlank).map(TrainingFormat::valueOf).toSet()
        set(value) { allowedFormatsRaw = value.sortedBy { it.ordinal }.joinToString(",") { it.name } }

    var tags: List<String>
        get() = tagsRaw.split(',').map(String::trim).filter(String::isNotBlank)
        set(value) { tagsRaw = value.map(String::trim).filter(String::isNotBlank).distinct().joinToString(",") }

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        if (createdAt == Instant.EPOCH) createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() { updatedAt = Instant.now() }
}

@Entity
@Table(name = "training_program_versions")
class TrainingProgramVersionEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "program_id", nullable = false)
    var programId: UUID = UUID.randomUUID(),
    @Column(name = "version_no", nullable = false)
    var versionNo: Int = 1,
    @Column(name = "allowed_formats", nullable = false)
    var allowedFormatsRaw: String = TrainingFormat.online.name,
    @Column(name = "online_course_id")
    var onlineCourseId: String? = null,
    @Column(name = "passing_score", nullable = false)
    var passingScore: Int = 80,
    @Column(name = "max_attempts", nullable = false)
    var maxAttempts: Int = 3,
    @Column(name = "completion_bonus", nullable = false)
    var completionBonus: Long = 0,
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT")
    var snapshotJson: String = "{}",
    @Column(name = "created_by", nullable = false)
    var createdBy: UUID = UUID.randomUUID(),
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    var allowedFormats: Set<TrainingFormat>
        get() = allowedFormatsRaw.split(',').filter(String::isNotBlank).map(TrainingFormat::valueOf).toSet()
        set(value) { allowedFormatsRaw = value.sortedBy { it.ordinal }.joinToString(",") { it.name } }
}

@Entity
@Table(name = "training_program_stages")
class TrainingProgramStageEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "version_id", nullable = false)
    var versionId: UUID = UUID.randomUUID(),
    @Column(name = "stage_key", nullable = false, length = 64)
    var stageKey: String = "",
    @Column(name = "type", nullable = false, length = 32)
    var typeRaw: String = TrainingStageType.material.name,
    @Column(nullable = false)
    var title: String = "",
    @Column(name = "order_no", nullable = false)
    var orderNo: Int = 0,
    @Column(nullable = false)
    var required: Boolean = true,
    @Column(name = "unlock_after_key")
    var unlockAfterKey: String? = null,
    @Column(name = "deadline_days")
    var deadlineDays: Int? = null,
    @Column(name = "passing_score")
    var passingScore: Int? = null,
    @Column(name = "max_attempts")
    var maxAttempts: Int? = null,
    @Column(nullable = false)
    var bonus: Long = 0,
    @Column(name = "manual_review", nullable = false)
    var manualReview: Boolean = false,
    @Column(name = "applicable_formats", nullable = false, length = 64)
    var applicableFormatsRaw: String = TrainingFormat.entries.joinToString(",") { it.name },
    @Column(name = "content_url", length = 1000)
    var contentUrl: String? = null,
) {
    var type: TrainingStageType
        get() = TrainingStageType.valueOf(typeRaw)
        set(value) { typeRaw = value.name }

    var applicableFormats: Set<TrainingFormat>
        get() = applicableFormatsRaw.split(',').filter(String::isNotBlank).map(TrainingFormat::valueOf).toSet()
        set(value) { applicableFormatsRaw = value.sortedBy { it.ordinal }.joinToString(",") { it.name } }
}

@Entity
@Table(name = "offline_events")
class OfflineEventEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "program_version_id", nullable = false)
    var programVersionId: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    var title: String = "",
    @Column(name = "event_type", nullable = false, length = 64)
    var eventType: String = "training",
    @Column(name = "starts_at", nullable = false)
    var startsAt: Instant = Instant.now(),
    @Column(name = "ends_at", nullable = false)
    var endsAt: Instant = Instant.now(),
    @Column(nullable = false, length = 64)
    var timezone: String = "Asia/Almaty",
    @Column(nullable = false)
    var region: String = "",
    @Column(nullable = false)
    var city: String = "",
    @Column(nullable = false)
    var address: String = "",
    @Column(name = "map_url")
    var mapUrl: String? = null,
    @Column(name = "trainer_id")
    var trainerId: UUID? = null,
    @Column(nullable = false)
    var organizer: String = "",
    @Column(nullable = false)
    var capacity: Int = 1,
    @Column(name = "registration_deadline")
    var registrationDeadline: Instant? = null,
    @Column(name = "status", nullable = false, length = 32)
    var statusRaw: String = OfflineEventStatus.draft.name,
    @Column(name = "materials_url")
    var materialsUrl: String? = null,
    @Column(nullable = false, columnDefinition = "TEXT")
    var comment: String = "",
    @Column(name = "qr_token", nullable = false)
    var qrToken: UUID = UUID.randomUUID(),
    @Column(name = "created_by", nullable = false)
    var createdBy: UUID = UUID.randomUUID(),
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var status: OfflineEventStatus
        get() = OfflineEventStatus.valueOf(statusRaw)
        set(value) { statusRaw = value.name }

    @PreUpdate
    fun onUpdate() { updatedAt = Instant.now() }
}

@Entity
@Table(name = "training_assignments")
class TrainingAssignmentEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "program_version_id", nullable = false)
    var programVersionId: UUID = UUID.randomUUID(),
    @Column(name = "pharmacist_id", nullable = false, length = 64)
    var pharmacistId: String = "",
    @Column(name = "format", nullable = false, length = 16)
    var formatRaw: String = TrainingFormat.online.name,
    @Column(name = "status", nullable = false, length = 40)
    var statusRaw: String = TrainingAssignmentStatus.not_started.name,
    @Column(name = "priority", nullable = false, length = 16)
    var priorityRaw: String = TrainingPriority.normal.name,
    @Column(nullable = false)
    var required: Boolean = true,
    @Column(name = "assigned_by", nullable = false)
    var assignedBy: UUID = UUID.randomUUID(),
    @Column(name = "responsible_id")
    var responsibleId: UUID? = null,
    @Column(name = "event_id")
    var eventId: UUID? = null,
    @Column(name = "starts_at")
    var startsAt: Instant? = null,
    @Column(name = "due_at")
    var dueAt: Instant? = null,
    @Column(name = "progress_pct", nullable = false)
    var progressPct: Int = 0,
    var score: Int? = null,
    @Column(name = "started_at")
    var startedAt: Instant? = null,
    @Column(name = "completed_at")
    var completedAt: Instant? = null,
    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null,
    @Column(name = "completion_reason")
    var completionReason: String? = null,
    @Column(name = "repeat_no", nullable = false)
    var repeatNo: Int = 1,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var format: TrainingFormat
        get() = TrainingFormat.valueOf(formatRaw)
        set(value) { formatRaw = value.name }
    var status: TrainingAssignmentStatus
        get() = TrainingAssignmentStatus.valueOf(statusRaw)
        set(value) { statusRaw = value.name }
    var priority: TrainingPriority
        get() = TrainingPriority.valueOf(priorityRaw)
        set(value) { priorityRaw = value.name }

    @PreUpdate
    fun onUpdate() { updatedAt = Instant.now() }
}

@Entity
@Table(name = "training_assignment_stages")
class TrainingAssignmentStageEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "assignment_id", nullable = false)
    var assignmentId: UUID = UUID.randomUUID(),
    @Column(name = "program_stage_id", nullable = false)
    var programStageId: UUID = UUID.randomUUID(),
    @Column(name = "status", nullable = false, length = 24)
    var statusRaw: String = TrainingStageStatus.locked.name,
    @Column(name = "progress_pct", nullable = false)
    var progressPct: Int = 0,
    var score: Int? = null,
    @Column(name = "attempts_used", nullable = false)
    var attemptsUsed: Int = 0,
    @Column(name = "started_at")
    var startedAt: Instant? = null,
    @Column(name = "completed_at")
    var completedAt: Instant? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var status: TrainingStageStatus
        get() = TrainingStageStatus.valueOf(statusRaw)
        set(value) { statusRaw = value.name }

    @PreUpdate
    fun onUpdate() { updatedAt = Instant.now() }
}

@Entity
@Table(name = "training_assessment_results")
class TrainingAssessmentResultEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "assignment_id", nullable = false)
    var assignmentId: UUID = UUID.randomUUID(),
    @Column(name = "assignment_stage_id", nullable = false)
    var assignmentStageId: UUID = UUID.randomUUID(),
    @Column(name = "source_type", nullable = false, length = 32)
    var sourceTypeRaw: String = TrainingStageType.test.name,
    @Column(name = "attempt_no", nullable = false)
    var attemptNo: Int = 1,
    @Column(nullable = false)
    var score: Int = 0,
    @Column(nullable = false)
    var passed: Boolean = false,
    @Column(nullable = false, length = 2000)
    var feedback: String = "",
    @Column(name = "competency_json", nullable = false, columnDefinition = "TEXT")
    var competencyJson: String = "{}",
    @Column(name = "recorded_by", nullable = false)
    var recordedBy: UUID = UUID.randomUUID(),
    @Column(name = "recorded_at", nullable = false)
    var recordedAt: Instant = Instant.now(),
) {
    var sourceType: TrainingStageType
        get() = TrainingStageType.valueOf(sourceTypeRaw)
        set(value) { sourceTypeRaw = value.name }
}

@Entity
@Table(name = "training_assignment_format_history")
class TrainingAssignmentFormatHistoryEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "assignment_id", nullable = false)
    var assignmentId: UUID = UUID.randomUUID(),
    @Column(name = "old_format", nullable = false, length = 16)
    var oldFormatRaw: String = TrainingFormat.online.name,
    @Column(name = "new_format", nullable = false, length = 16)
    var newFormatRaw: String = TrainingFormat.online.name,
    @Column(name = "old_event_id")
    var oldEventId: UUID? = null,
    @Column(name = "new_event_id")
    var newEventId: UUID? = null,
    @Column(nullable = false, length = 500)
    var reason: String = "",
    @Column(name = "changed_by", nullable = false)
    var changedBy: UUID = UUID.randomUUID(),
    @Column(name = "changed_at", nullable = false)
    var changedAt: Instant = Instant.now(),
) {
    var oldFormat: TrainingFormat
        get() = TrainingFormat.valueOf(oldFormatRaw)
        set(value) { oldFormatRaw = value.name }
    var newFormat: TrainingFormat
        get() = TrainingFormat.valueOf(newFormatRaw)
        set(value) { newFormatRaw = value.name }
}

@Entity
@Table(name = "event_participants")
class EventParticipantEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "event_id", nullable = false)
    var eventId: UUID = UUID.randomUUID(),
    @Column(name = "assignment_id", nullable = false)
    var assignmentId: UUID = UUID.randomUUID(),
    @Column(name = "pharmacist_id", nullable = false, length = 64)
    var pharmacistId: String = "",
    @Column(name = "status", nullable = false, length = 24)
    var statusRaw: String = EventParticipantStatus.registered.name,
    @Column(name = "registered_at", nullable = false)
    var registeredAt: Instant = Instant.now(),
    @Column(name = "checked_in_at")
    var checkedInAt: Instant? = null,
    @Column(name = "check_method")
    var checkMethodRaw: String? = null,
    @Column(name = "trainer_comment")
    var trainerComment: String? = null,
    var score: Int? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var status: EventParticipantStatus
        get() = EventParticipantStatus.valueOf(statusRaw)
        set(value) { statusRaw = value.name }
    var checkMethod: AttendanceMethod?
        get() = checkMethodRaw?.let(AttendanceMethod::valueOf)
        set(value) { checkMethodRaw = value?.name }

    @PreUpdate
    fun onUpdate() { updatedAt = Instant.now() }
}

@Entity
@Table(name = "pharmacist_training_preferences")
class PharmacistTrainingPreferenceEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "pharmacist_id", nullable = false, length = 64)
    var pharmacistId: String = "",
    @Column(name = "default_format", nullable = false, length = 16)
    var defaultFormatRaw: String = TrainingFormat.online.name,
    @Column(name = "changed_by", nullable = false)
    var changedBy: UUID = UUID.randomUUID(),
    @Column(nullable = false)
    var reason: String = "",
    @Column(name = "valid_from", nullable = false)
    var validFrom: Instant = Instant.now(),
    @Column(name = "valid_to")
    var validTo: Instant? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    var defaultFormat: TrainingFormat
        get() = TrainingFormat.valueOf(defaultFormatRaw)
        set(value) { defaultFormatRaw = value.name }
}

@Entity
@Table(name = "training_certificates")
class TrainingCertificateEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "certificate_number", nullable = false, unique = true)
    var certificateNumber: String = "",
    @Column(name = "assignment_id", nullable = false, unique = true)
    var assignmentId: UUID = UUID.randomUUID(),
    @Column(name = "pharmacist_id", nullable = false, length = 64)
    var pharmacistId: String = "",
    @Column(name = "program_version_id", nullable = false)
    var programVersionId: UUID = UUID.randomUUID(),
    @Column(name = "format", nullable = false, length = 16)
    var formatRaw: String = TrainingFormat.online.name,
    @Column(name = "issued_at", nullable = false)
    var issuedAt: Instant = Instant.now(),
    @Column(name = "expires_at")
    var expiresAt: Instant? = null,
    var score: Int? = null,
    @Column(name = "signer_name", nullable = false)
    var signerName: String = "ePharm",
    @Column(name = "template_name", nullable = false)
    var templateName: String = "Стандартный сертификат",
    @Column(name = "qr_token", nullable = false)
    var qrToken: UUID = UUID.randomUUID(),
    @Column(name = "status", nullable = false, length = 24)
    var statusRaw: String = CertificateStatus.valid.name,
    @Column(name = "pdf_url")
    var pdfUrl: String? = null,
    @Column(name = "replaced_by")
    var replacedBy: UUID? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    var format: TrainingFormat
        get() = TrainingFormat.valueOf(formatRaw)
        set(value) { formatRaw = value.name }
    var status: CertificateStatus
        get() = CertificateStatus.valueOf(statusRaw)
        set(value) { statusRaw = value.name }
}

@Entity
@Table(name = "training_rewards")
class TrainingRewardEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "assignment_id", nullable = false, unique = true)
    var assignmentId: UUID = UUID.randomUUID(),
    @Column(name = "pharmacist_id", nullable = false, length = 64)
    var pharmacistId: String = "",
    @Column(nullable = false)
    var amount: Long = 0,
    @Column(nullable = false)
    var reason: String = "",
    @Column(name = "status", nullable = false, length = 24)
    var statusRaw: String = TrainingRewardStatus.issued.name,
    @Column(name = "issued_at", nullable = false)
    var issuedAt: Instant = Instant.now(),
    @Column(name = "reversed_at")
    var reversedAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    var status: TrainingRewardStatus
        get() = TrainingRewardStatus.valueOf(statusRaw)
        set(value) { statusRaw = value.name }
}

@Entity
@Table(name = "training_notifications")
class TrainingNotificationEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "pharmacist_id", nullable = false, length = 64)
    var pharmacistId: String = "",
    @Column(name = "assignment_id")
    var assignmentId: UUID? = null,
    @Column(name = "event_id")
    var eventId: UUID? = null,
    @Column(nullable = false, length = 24)
    var channel: String = "internal",
    @Column(name = "event_type", nullable = false, length = 64)
    var eventType: String = "",
    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    var payloadJson: String = "{}",
    @Column(nullable = false, length = 24)
    var status: String = "pending",
    @Column(name = "scheduled_at", nullable = false)
    var scheduledAt: Instant = Instant.now(),
    @Column(name = "sent_at")
    var sentAt: Instant? = null,
    @Column(name = "idempotency_key", nullable = false, unique = true)
    var idempotencyKey: String = "",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)

@Entity
@Table(name = "training_audit_log")
class TrainingAuditLogEntity(
    @Id
    var id: UUID = UUID.randomUUID(),
    @Column(name = "actor_id", nullable = false, length = 64)
    var actorId: String = "",
    @Column(name = "actor_type", nullable = false, length = 24)
    var actorType: String = "admin",
    @Column(nullable = false, length = 64)
    var action: String = "",
    @Column(name = "entity_type", nullable = false, length = 64)
    var entityType: String = "",
    @Column(name = "entity_id", nullable = false, length = 64)
    var entityId: String = "",
    @Column(name = "details_json", nullable = false, columnDefinition = "TEXT")
    var detailsJson: String = "{}",
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
)
