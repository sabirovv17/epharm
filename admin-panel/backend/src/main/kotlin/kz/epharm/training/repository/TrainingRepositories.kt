package kz.epharm.training.repository

import jakarta.persistence.LockModeType
import kz.epharm.training.entity.EventParticipantEntity
import kz.epharm.training.entity.OfflineEventEntity
import kz.epharm.training.entity.PharmacistTrainingPreferenceEntity
import kz.epharm.training.entity.TrainingAssignmentEntity
import kz.epharm.training.entity.TrainingAssignmentStageEntity
import kz.epharm.training.entity.TrainingAssignmentFormatHistoryEntity
import kz.epharm.training.entity.TrainingAssessmentResultEntity
import kz.epharm.training.entity.TrainingAuditLogEntity
import kz.epharm.training.entity.TrainingCertificateEntity
import kz.epharm.training.entity.TrainingNotificationEntity
import kz.epharm.training.entity.TrainingProgramEntity
import kz.epharm.training.entity.TrainingProgramStageEntity
import kz.epharm.training.entity.TrainingProgramVersionEntity
import kz.epharm.training.entity.TrainingRewardEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface TrainingProgramRepository : JpaRepository<TrainingProgramEntity, UUID> {
    fun findAllByOrderByUpdatedAtDesc(): List<TrainingProgramEntity>
    fun findAllByStatusRawOrderByUpdatedAtDesc(statusRaw: String): List<TrainingProgramEntity>
}

@Repository
interface TrainingProgramVersionRepository : JpaRepository<TrainingProgramVersionEntity, UUID> {
    fun findByProgramIdAndVersionNo(programId: UUID, versionNo: Int): TrainingProgramVersionEntity?
    fun findAllByProgramIdOrderByVersionNoDesc(programId: UUID): List<TrainingProgramVersionEntity>
}

@Repository
interface TrainingProgramStageRepository : JpaRepository<TrainingProgramStageEntity, UUID> {
    fun findAllByVersionIdOrderByOrderNoAsc(versionId: UUID): List<TrainingProgramStageEntity>
}

@Repository
interface OfflineEventRepository : JpaRepository<OfflineEventEntity, UUID> {
    fun findAllByOrderByStartsAtDesc(): List<OfflineEventEntity>
    fun findAllByProgramVersionIdOrderByStartsAtAsc(programVersionId: UUID): List<OfflineEventEntity>
    fun findAllByRegionInOrderByStartsAtDesc(regions: Collection<String>): List<OfflineEventEntity>
    fun findAllByTrainerIdOrderByStartsAtDesc(trainerId: UUID): List<OfflineEventEntity>
    fun findByQrToken(qrToken: UUID): OfflineEventEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OfflineEventEntity e where e.id = :id")
    fun findByIdForUpdate(id: UUID): OfflineEventEntity?
}

@Repository
interface TrainingAssignmentRepository : JpaRepository<TrainingAssignmentEntity, UUID> {
    fun findAllByOrderByCreatedAtDesc(): List<TrainingAssignmentEntity>
    fun findAllByPharmacistIdOrderByCreatedAtDesc(pharmacistId: String): List<TrainingAssignmentEntity>
    fun findAllByPharmacistIdAndStatusRawNotInOrderByDueAtAsc(
        pharmacistId: String,
        excludedStatuses: Collection<String>,
    ): List<TrainingAssignmentEntity>
    fun findAllByEventId(eventId: UUID): List<TrainingAssignmentEntity>
    fun findFirstByProgramVersionIdAndPharmacistIdAndStatusRawNotInOrderByCreatedAtDesc(
        programVersionId: UUID,
        pharmacistId: String,
        statuses: Collection<String>,
    ): TrainingAssignmentEntity?
    fun findAllByProgramVersionIdAndPharmacistIdOrderByRepeatNoDesc(
        programVersionId: UUID,
        pharmacistId: String,
    ): List<TrainingAssignmentEntity>
    fun countByStatusRaw(statusRaw: String): Long
    fun countByProgramVersionId(programVersionId: UUID): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from TrainingAssignmentEntity a where a.id = :id")
    fun findByIdForUpdate(id: UUID): TrainingAssignmentEntity?
}

@Repository
interface TrainingAssignmentStageRepository : JpaRepository<TrainingAssignmentStageEntity, UUID> {
    fun findAllByAssignmentIdOrderByProgramStageIdAsc(assignmentId: UUID): List<TrainingAssignmentStageEntity>
    fun findAllByAssignmentIdIn(assignmentIds: Collection<UUID>): List<TrainingAssignmentStageEntity>
    fun findByAssignmentIdAndProgramStageId(assignmentId: UUID, programStageId: UUID): TrainingAssignmentStageEntity?
}

@Repository
interface TrainingAssessmentResultRepository : JpaRepository<TrainingAssessmentResultEntity, UUID> {
    fun findAllByAssignmentIdOrderByRecordedAtDesc(assignmentId: UUID): List<TrainingAssessmentResultEntity>
    fun findAllByAssignmentStageIdOrderByAttemptNoDesc(assignmentStageId: UUID): List<TrainingAssessmentResultEntity>
}

@Repository
interface TrainingAssignmentFormatHistoryRepository : JpaRepository<TrainingAssignmentFormatHistoryEntity, UUID> {
    fun findAllByAssignmentIdOrderByChangedAtDesc(assignmentId: UUID): List<TrainingAssignmentFormatHistoryEntity>
}

@Repository
interface EventParticipantRepository : JpaRepository<EventParticipantEntity, UUID> {
    fun findAllByEventIdOrderByRegisteredAtAsc(eventId: UUID): List<EventParticipantEntity>
    fun findByAssignmentId(assignmentId: UUID): EventParticipantEntity?
    fun findByEventIdAndPharmacistId(eventId: UUID, pharmacistId: String): EventParticipantEntity?
    fun countByEventIdAndStatusRawIn(eventId: UUID, statuses: Collection<String>): Long
}

@Repository
interface PharmacistTrainingPreferenceRepository : JpaRepository<PharmacistTrainingPreferenceEntity, UUID> {
    fun findByPharmacistIdAndValidToIsNull(pharmacistId: String): PharmacistTrainingPreferenceEntity?
    fun findAllByPharmacistIdOrderByValidFromDesc(pharmacistId: String): List<PharmacistTrainingPreferenceEntity>
    fun findAllByValidToIsNullOrderByPharmacistIdAsc(): List<PharmacistTrainingPreferenceEntity>
}

@Repository
interface TrainingCertificateRepository : JpaRepository<TrainingCertificateEntity, UUID> {
    fun findByAssignmentId(assignmentId: UUID): TrainingCertificateEntity?
    fun findByQrToken(qrToken: UUID): TrainingCertificateEntity?
    fun findAllByPharmacistIdOrderByIssuedAtDesc(pharmacistId: String): List<TrainingCertificateEntity>
    fun findAllByOrderByIssuedAtDesc(): List<TrainingCertificateEntity>
    fun findAllByAssignmentIdIn(assignmentIds: Collection<UUID>): List<TrainingCertificateEntity>
}

@Repository
interface TrainingRewardRepository : JpaRepository<TrainingRewardEntity, UUID> {
    fun findByAssignmentId(assignmentId: UUID): TrainingRewardEntity?
    fun findAllByPharmacistIdOrderByIssuedAtDesc(pharmacistId: String): List<TrainingRewardEntity>
    fun findAllByAssignmentIdIn(assignmentIds: Collection<UUID>): List<TrainingRewardEntity>
}

@Repository
interface TrainingNotificationRepository : JpaRepository<TrainingNotificationEntity, UUID> {
    fun findAllByPharmacistIdAndStatusOrderByScheduledAtDesc(pharmacistId: String, status: String): List<TrainingNotificationEntity>
    fun findAllByPharmacistIdAndChannelAndScheduledAtLessThanEqualOrderByScheduledAtDesc(
        pharmacistId: String,
        channel: String,
        scheduledAt: java.time.Instant,
    ): List<TrainingNotificationEntity>
    fun deleteAllByEventIdAndStatusAndEventTypeIn(
        eventId: UUID,
        status: String,
        eventTypes: Collection<String>,
    ): Long
}

@Repository
interface TrainingAuditLogRepository : JpaRepository<TrainingAuditLogEntity, UUID>
