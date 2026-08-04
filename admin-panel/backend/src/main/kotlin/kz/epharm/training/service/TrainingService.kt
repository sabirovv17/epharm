package kz.epharm.training.service

import com.fasterxml.jackson.databind.ObjectMapper
import kz.epharm.auth.domain.AdminRole
import kz.epharm.auth.repository.AdminUserRepository
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.lms.repository.CourseRepository
import kz.epharm.pharmacists.entity.PharmacistEntity
import kz.epharm.pharmacists.entity.PharmacistStatus
import kz.epharm.pharmacists.repository.PharmacistRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.training.domain.DuplicateAssignmentPolicy
import kz.epharm.training.domain.EventParticipantStatus
import kz.epharm.training.domain.OfflineEventStatus
import kz.epharm.training.domain.TrainingAssignmentStatus
import kz.epharm.training.domain.TrainingFormat
import kz.epharm.training.domain.TrainingProgramStatus
import kz.epharm.training.domain.TrainingStageStatus
import kz.epharm.training.domain.TrainingStageType
import kz.epharm.training.dto.ChangeTrainingPreferenceRequest
import kz.epharm.training.dto.ChangeAssignmentFormatRequest
import kz.epharm.training.dto.CertificateVerificationDto
import kz.epharm.training.dto.CreateOfflineEventRequest
import kz.epharm.training.dto.CreateTrainingAssignmentsRequest
import kz.epharm.training.dto.CreateTrainingProgramRequest
import kz.epharm.training.dto.CreateTrainingStageRequest
import kz.epharm.training.dto.EventParticipantDto
import kz.epharm.training.dto.MarkAttendanceRequest
import kz.epharm.training.dto.MassChangeTrainingPreferencesRequest
import kz.epharm.training.dto.MassAssignmentResultDto
import kz.epharm.training.dto.MobileTrainingOverviewDto
import kz.epharm.training.dto.OfflineEventDto
import kz.epharm.training.dto.OfflineEventSummaryDto
import kz.epharm.training.dto.RecordAssessmentResultRequest
import kz.epharm.training.dto.StageProgressRequest
import kz.epharm.training.dto.TrainingAssessmentResultDto
import kz.epharm.training.dto.TrainingAssignmentDto
import kz.epharm.training.dto.TrainingAssignmentFormatHistoryDto
import kz.epharm.training.dto.TrainingAssignmentPageDto
import kz.epharm.training.dto.TrainingAssignmentStageDto
import kz.epharm.training.dto.TrainingCapabilitiesDto
import kz.epharm.training.dto.TrainingCertificateDto
import kz.epharm.training.dto.TrainingDashboardDto
import kz.epharm.training.dto.TrainingEventQrDto
import kz.epharm.training.dto.TrainingNotificationDto
import kz.epharm.training.dto.PharmacistTrainingProfileDto
import kz.epharm.training.dto.TrainingPreferenceDto
import kz.epharm.training.dto.TrainingProgramDto
import kz.epharm.training.dto.TrainingProgramStageDto
import kz.epharm.training.dto.TrainingRewardDto
import kz.epharm.training.dto.UpdateTrainingProgramRequest
import kz.epharm.training.dto.UpdateOfflineEventRequest
import kz.epharm.training.entity.EventParticipantEntity
import kz.epharm.training.entity.OfflineEventEntity
import kz.epharm.training.entity.PharmacistTrainingPreferenceEntity
import kz.epharm.training.entity.TrainingAssignmentEntity
import kz.epharm.training.entity.TrainingAssignmentFormatHistoryEntity
import kz.epharm.training.entity.TrainingAssignmentStageEntity
import kz.epharm.training.entity.TrainingAssessmentResultEntity
import kz.epharm.training.entity.TrainingAuditLogEntity
import kz.epharm.training.entity.TrainingCertificateEntity
import kz.epharm.training.entity.TrainingNotificationEntity
import kz.epharm.training.entity.TrainingProgramEntity
import kz.epharm.training.entity.TrainingProgramStageEntity
import kz.epharm.training.entity.TrainingProgramVersionEntity
import kz.epharm.training.entity.TrainingRewardEntity
import kz.epharm.training.repository.EventParticipantRepository
import kz.epharm.training.repository.OfflineEventRepository
import kz.epharm.training.repository.PharmacistTrainingPreferenceRepository
import kz.epharm.training.repository.TrainingAssignmentRepository
import kz.epharm.training.repository.TrainingAssignmentFormatHistoryRepository
import kz.epharm.training.repository.TrainingAssignmentStageRepository
import kz.epharm.training.repository.TrainingAssessmentResultRepository
import kz.epharm.training.repository.TrainingAuditLogRepository
import kz.epharm.training.repository.TrainingCertificateRepository
import kz.epharm.training.repository.TrainingNotificationRepository
import kz.epharm.training.repository.TrainingProgramRepository
import kz.epharm.training.repository.TrainingProgramStageRepository
import kz.epharm.training.repository.TrainingProgramVersionRepository
import kz.epharm.training.repository.TrainingRewardRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

@Service
class TrainingService(
    private val programRepository: TrainingProgramRepository,
    private val versionRepository: TrainingProgramVersionRepository,
    private val programStageRepository: TrainingProgramStageRepository,
    private val eventRepository: OfflineEventRepository,
    private val assignmentRepository: TrainingAssignmentRepository,
    private val assignmentFormatHistoryRepository: TrainingAssignmentFormatHistoryRepository,
    private val assignmentStageRepository: TrainingAssignmentStageRepository,
    private val assessmentResultRepository: TrainingAssessmentResultRepository,
    private val participantRepository: EventParticipantRepository,
    private val preferenceRepository: PharmacistTrainingPreferenceRepository,
    private val certificateRepository: TrainingCertificateRepository,
    private val rewardRepository: TrainingRewardRepository,
    private val notificationRepository: TrainingNotificationRepository,
    private val auditRepository: TrainingAuditLogRepository,
    private val pharmacistRepository: PharmacistRepository,
    private val adminUserRepository: AdminUserRepository,
    private val courseRepository: CourseRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {

    private val terminalAssignmentStatuses = listOf(
        TrainingAssignmentStatus.completed.name,
        TrainingAssignmentStatus.cancelled.name,
    )

    // Programs

    @Transactional(readOnly = true)
    fun listPrograms(status: TrainingProgramStatus? = null): List<TrainingProgramDto> {
        val rows = status?.let { programRepository.findAllByStatusRawOrderByUpdatedAtDesc(it.name) }
            ?: programRepository.findAllByOrderByUpdatedAtDesc()
        return rows.map(::programDto)
    }

    @Transactional(readOnly = true)
    fun getProgram(id: UUID): TrainingProgramDto = programDto(loadProgram(id))

    @Transactional
    fun createProgram(req: CreateTrainingProgramRequest, principal: AdminPrincipal): TrainingProgramDto {
        validateProgramRequest(req)
        val program = TrainingProgramEntity(
            name = req.name.trim(),
            shortDescription = req.shortDescription.trim(),
            description = req.description.trim(),
            coverUrl = req.coverUrl?.trim()?.takeIf(String::isNotEmpty),
            category = req.category.trim(),
            manufacturer = req.manufacturer.trim(),
            brand = req.brand.trim(),
            product = req.product.trim(),
            language = req.language,
            managerId = req.managerId,
            startsAt = req.startsAt,
            endsAt = req.endsAt,
            normativeDays = req.normativeDays,
            createdBy = principal.userId,
        ).also {
            it.allowedFormats = req.allowedFormats
            it.tags = req.tags
            it.status = req.status
            if (req.status == TrainingProgramStatus.published) it.publishedAt = Instant.now()
        }
        val savedProgram = programRepository.save(program)
        createVersion(
            program = savedProgram,
            versionNo = 1,
            formats = req.allowedFormats,
            onlineCourseId = req.onlineCourseId,
            passingScore = req.passingScore,
            maxAttempts = req.maxAttempts,
            completionBonus = req.completionBonus,
            requestedStages = req.stages,
            actor = principal,
            snapshot = req,
        )
        audit(principal.userId.toString(), "admin", "program_created", "training_program", savedProgram.id.toString())
        return programDto(savedProgram)
    }

    @Transactional
    fun updateProgram(
        id: UUID,
        req: UpdateTrainingProgramRequest,
        principal: AdminPrincipal,
    ): TrainingProgramDto {
        val program = loadProgram(id)
        if (program.status == TrainingProgramStatus.archived) {
            conflict("Архивную программу нельзя изменять")
        }
        val currentVersion = loadCurrentVersion(program)
        req.name?.let { require(it.isNotBlank()) { "Название программы не может быть пустым" }; program.name = it.trim() }
        req.shortDescription?.let { program.shortDescription = it.trim() }
        req.description?.let { program.description = it.trim() }
        if (req.clearCoverUrl) program.coverUrl = null
        else req.coverUrl?.let { program.coverUrl = it.trim().takeIf(String::isNotEmpty) }
        req.category?.let { program.category = it.trim() }
        req.manufacturer?.let { program.manufacturer = it.trim() }
        req.brand?.let { program.brand = it.trim() }
        req.product?.let { program.product = it.trim() }
        req.language?.let {
            if (it !in setOf("ru", "kk")) badRequest("Язык программы должен быть ru или kk")
            program.language = it
        }
        if (req.clearManager) program.managerId = null
        else req.managerId?.let { ensureAdminExists(it); program.managerId = it }
        req.allowedFormats?.let {
            require(it.isNotEmpty()) { "Нужен хотя бы один формат обучения" }
            program.allowedFormats = it
        }
        if (req.clearStartsAt) program.startsAt = null else req.startsAt?.let { program.startsAt = it }
        if (req.clearEndsAt) program.endsAt = null else req.endsAt?.let { program.endsAt = it }
        req.normativeDays?.let { program.normativeDays = it }
        req.tags?.let { program.tags = it }
        req.status?.let {
            program.status = it
            if (it == TrainingProgramStatus.published && program.publishedAt == null) program.publishedAt = Instant.now()
            if (it == TrainingProgramStatus.archived) program.archivedAt = Instant.now()
        }
        validatePeriod(program.startsAt, program.endsAt)

        val versionChanged = req.allowedFormats != null || req.onlineCourseId != null || req.clearOnlineCourseId ||
            req.passingScore != null || req.maxAttempts != null || req.completionBonus != null || req.stages != null
        if (req.allowedFormats != null && req.stages == null) {
            badRequest("При изменении доступных форматов передайте маршруты этапов для новой версии")
        }
        if (versionChanged) {
            val nextVersion = program.currentVersion + 1
            program.currentVersion = nextVersion
            val copiedStages = req.stages ?: programStageRepository
                .findAllByVersionIdOrderByOrderNoAsc(currentVersion.id)
                .map {
                    CreateTrainingStageRequest(
                        key = it.stageKey,
                        type = it.type,
                        title = it.title,
                        order = it.orderNo,
                        required = it.required,
                        unlockAfterKey = it.unlockAfterKey,
                        deadlineDays = it.deadlineDays,
                        passingScore = it.passingScore,
                        maxAttempts = it.maxAttempts,
                        bonus = it.bonus,
                        manualReview = it.manualReview,
                        applicableFormats = it.applicableFormats,
                        contentUrl = it.contentUrl,
                    )
                }
            createVersion(
                program = program,
                versionNo = nextVersion,
                formats = req.allowedFormats ?: currentVersion.allowedFormats,
                onlineCourseId = if (req.clearOnlineCourseId) null else req.onlineCourseId ?: currentVersion.onlineCourseId,
                passingScore = req.passingScore ?: currentVersion.passingScore,
                maxAttempts = req.maxAttempts ?: currentVersion.maxAttempts,
                completionBonus = req.completionBonus ?: currentVersion.completionBonus,
                requestedStages = copiedStages,
                actor = principal,
                snapshot = req,
            )
            audit(
                principal.userId.toString(),
                "admin",
                "program_version_created",
                "training_program",
                program.id.toString(),
                mapOf("version" to nextVersion),
            )
        }
        programRepository.save(program)
        return programDto(program)
    }

    // Dashboard and capabilities

    @Transactional(readOnly = true)
    fun dashboard(principal: AdminPrincipal): TrainingDashboardDto {
        val assignments = scopedAssignments(principal, assignmentRepository.findAllByOrderByCreatedAtDesc())
        val completed = assignments.count { it.status == TrainingAssignmentStatus.completed }
        val inProgressStatuses = setOf(
            TrainingAssignmentStatus.in_progress,
            TrainingAssignmentStatus.waiting_online,
            TrainingAssignmentStatus.waiting_test,
            TrainingAssignmentStatus.waiting_exam,
            TrainingAssignmentStatus.waiting_event_selection,
            TrainingAssignmentStatus.waiting_offline,
            TrainingAssignmentStatus.waiting_attendance,
            TrainingAssignmentStatus.waiting_review,
            TrainingAssignmentStatus.retake_required,
        )
        val participants = scopedEvents(principal, eventRepository.findAllByOrderByStartsAtDesc())
            .flatMap { participantRepository.findAllByEventIdOrderByRegisteredAtAsc(it.id) }
        return TrainingDashboardDto(
            activePrograms = programRepository.findAllByOrderByUpdatedAtDesc().count {
                it.status in setOf(
                    TrainingProgramStatus.published,
                    TrainingProgramStatus.scheduled,
                    TrainingProgramStatus.paused,
                )
            }.toLong(),
            totalAssignments = assignments.size.toLong(),
            notStarted = assignments.count {
                it.status == TrainingAssignmentStatus.not_started || it.status == TrainingAssignmentStatus.scheduled
            }.toLong(),
            inProgress = assignments.count { it.status in inProgressStatuses }.toLong(),
            completed = completed.toLong(),
            overdue = assignments.count { effectiveStatus(it) == TrainingAssignmentStatus.overdue }.toLong(),
            completionRatePct = if (assignments.isEmpty()) 0 else (completed * 100.0 / assignments.size).roundToInt(),
            averageScore = assignments.mapNotNull { it.score }.takeIf(List<Int>::isNotEmpty)?.average()?.roundToInt(),
            certificates = assignments.count { certificateRepository.findByAssignmentId(it.id) != null }.toLong(),
            rewardsIssued = assignments.sumOf { rewardRepository.findByAssignmentId(it.id)?.amount ?: 0 },
            attendanceConfirmed = participants.count {
                it.status in setOf(EventParticipantStatus.attended, EventParticipantStatus.late)
            }.toLong(),
            noShows = participants.count { it.status == EventParticipantStatus.no_show }.toLong(),
            byFormat = TrainingFormat.entries.associateWith { format -> assignments.count { it.format == format }.toLong() },
            capabilities = capabilities(principal),
        )
    }

    fun capabilities(principal: AdminPrincipal): TrainingCapabilitiesDto {
        val full = principal.role in setOf(AdminRole.SYSTEM_ADMIN, AdminRole.HQ_HEAD)
        val manager = principal.role == AdminRole.TRAINING_MANAGER
        val regional = principal.role == AdminRole.REGIONAL_MANAGER
        val trainer = principal.role == AdminRole.TRAINER
        return TrainingCapabilitiesDto(
            canManagePrograms = full || manager,
            canManageAssignments = full || manager || regional,
            canManageEvents = full || manager || regional,
            canMarkAttendance = full || manager || regional || trainer,
            canAdjustRewards = full,
            canRecordResults = full || manager || regional || trainer,
            canManagePreferences = full || manager || regional,
            canExport = full || manager || regional || trainer,
            regionalScope = if (regional) regionsFor(principal.userId) else emptyList(),
        )
    }

    // Offline events and attendance

    @Transactional(readOnly = true)
    fun listEvents(principal: AdminPrincipal): List<OfflineEventDto> =
        scopedEvents(principal, eventRepository.findAllByOrderByStartsAtDesc()).map(::eventDto)

    @Transactional
    fun createEvent(req: CreateOfflineEventRequest, principal: AdminPrincipal): OfflineEventDto {
        if (!req.endsAt.isAfter(req.startsAt)) badRequest("Окончание события должно быть позже начала")
        val program = loadProgram(req.programId)
        val version = loadCurrentVersion(program)
        if (TrainingFormat.offline !in version.allowedFormats && TrainingFormat.hybrid !in version.allowedFormats) {
            badRequest("Программа не поддерживает очный или гибридный формат")
        }
        req.trainerId?.let(::ensureAdminExists)
        val normalizedRegion = req.region.trim()
        if (principal.role == AdminRole.REGIONAL_MANAGER && normalizedRegion !in regionsFor(principal.userId)) {
            forbidden("Регион события не входит в вашу область доступа")
        }
        val event = OfflineEventEntity(
            programVersionId = version.id,
            title = req.title.trim(),
            eventType = req.eventType.trim(),
            startsAt = req.startsAt,
            endsAt = req.endsAt,
            timezone = req.timezone.trim(),
            region = normalizedRegion,
            city = req.city.trim(),
            address = req.address.trim(),
            mapUrl = req.mapUrl?.trim()?.takeIf(String::isNotEmpty),
            trainerId = req.trainerId ?: principal.userId.takeIf { principal.role == AdminRole.TRAINER },
            organizer = req.organizer.trim(),
            capacity = req.capacity,
            registrationDeadline = req.registrationDeadline,
            materialsUrl = req.materialsUrl?.trim()?.takeIf(String::isNotEmpty),
            comment = req.comment.trim(),
            createdBy = principal.userId,
        ).also { it.status = req.status }
        val saved = eventRepository.save(event)
        audit(principal.userId.toString(), "admin", "event_created", "offline_event", saved.id.toString())
        return eventDto(saved)
    }

    @Transactional
    fun updateEvent(eventId: UUID, req: UpdateOfflineEventRequest, principal: AdminPrincipal): OfflineEventDto {
        val event = loadEvent(eventId)
        ensureEventVisible(event, principal)
        if (event.status == OfflineEventStatus.archived) conflict("Архивное событие нельзя изменять")

        val previousStartsAt = event.startsAt
        val previousEndsAt = event.endsAt
        val previousAddress = event.address
        val previousCity = event.city
        val previousStatus = event.status

        req.title?.let {
            if (it.isBlank()) badRequest("Название события не может быть пустым")
            event.title = it.trim()
        }
        req.eventType?.let { event.eventType = it.trim() }
        req.startsAt?.let { event.startsAt = it }
        req.endsAt?.let { event.endsAt = it }
        req.timezone?.let { event.timezone = it.trim() }
        req.region?.let {
            val region = it.trim()
            if (principal.role == AdminRole.REGIONAL_MANAGER && region !in regionsFor(principal.userId)) {
                forbidden("Регион события не входит в вашу область доступа")
            }
            event.region = region
        }
        req.city?.let { event.city = it.trim() }
        req.address?.let { event.address = it.trim() }
        req.mapUrl?.let { event.mapUrl = it.trim().takeIf(String::isNotEmpty) }
        req.trainerId?.let { ensureAdminExists(it); event.trainerId = it }
        req.organizer?.let { event.organizer = it.trim() }
        if (req.clearRegistrationDeadline) {
            event.registrationDeadline = null
        } else {
            req.registrationDeadline?.let { event.registrationDeadline = it }
        }
        req.materialsUrl?.let { event.materialsUrl = it.trim().takeIf(String::isNotEmpty) }
        req.comment?.let { event.comment = it.trim() }
        req.status?.let { event.status = it }

        if (!event.endsAt.isAfter(event.startsAt)) badRequest("Окончание события должно быть позже начала")
        event.registrationDeadline?.let {
            if (it.isAfter(event.startsAt)) badRequest("Регистрация должна завершаться до начала события")
        }
        req.capacity?.let { capacity ->
            val occupied = participantRepository.countByEventIdAndStatusRawIn(
                event.id,
                activeParticipantStatusNames(),
            )
            if (capacity < occupied) conflict("Вместимость не может быть меньше числа зарегистрированных участников")
            event.capacity = capacity
        }

        val saved = eventRepository.save(event)
        val assignments = assignmentRepository.findAllByEventId(event.id)
        val scheduleChanged = previousStartsAt != saved.startsAt || previousEndsAt != saved.endsAt ||
            previousAddress != saved.address || previousCity != saved.city

        if (saved.status == OfflineEventStatus.cancelled && previousStatus != OfflineEventStatus.cancelled) {
            participantRepository.findAllByEventIdOrderByRegisteredAtAsc(saved.id).forEach { participant ->
                if (participant.status !in setOf(EventParticipantStatus.attended, EventParticipantStatus.late)) {
                    participant.status = EventParticipantStatus.cancelled
                    participant.checkedInAt = null
                    participantRepository.save(participant)
                }
            }
            assignments.forEach { assignment ->
                if (assignment.status !in setOf(TrainingAssignmentStatus.completed, TrainingAssignmentStatus.cancelled)) {
                    assignment.eventId = null
                    assignmentRepository.save(assignment)
                    recalculateAssignment(assignment)
                    createNotification(
                        pharmacistId = assignment.pharmacistId,
                        assignmentId = assignment.id,
                        eventId = saved.id,
                        eventType = "training_event_cancelled",
                        payload = mapOf("event" to saved.title),
                    )
                }
            }
            notificationRepository.deleteAllByEventIdAndStatusAndEventTypeIn(
                saved.id,
                "pending",
                listOf("training_event_24h", "training_event_2h"),
            )
        } else if (scheduleChanged) {
            notificationRepository.deleteAllByEventIdAndStatusAndEventTypeIn(
                saved.id,
                "pending",
                listOf("training_event_24h", "training_event_2h"),
            )
            assignments.forEach { assignment ->
                scheduleEventNotifications(assignment, saved)
                createNotification(
                    pharmacistId = assignment.pharmacistId,
                    assignmentId = assignment.id,
                    eventId = saved.id,
                    eventType = "training_event_changed",
                    payload = mapOf("event" to saved.title, "startsAt" to saved.startsAt),
                )
            }
        }

        audit(
            principal.userId.toString(),
            "admin",
            "event_updated",
            "offline_event",
            saved.id.toString(),
            mapOf("status" to saved.status.name, "scheduleChanged" to scheduleChanged),
        )
        return eventDto(saved)
    }

    @Transactional(readOnly = true)
    fun listParticipants(eventId: UUID, principal: AdminPrincipal): List<EventParticipantDto> {
        val event = loadEvent(eventId)
        ensureEventVisible(event, principal)
        return participantRepository.findAllByEventIdOrderByRegisteredAtAsc(eventId).map(::participantDto)
    }

    @Transactional(readOnly = true)
    fun eventQr(eventId: UUID, principal: AdminPrincipal): TrainingEventQrDto {
        val event = loadEvent(eventId)
        ensureEventVisible(event, principal)
        return TrainingEventQrDto(
            eventId = event.id,
            token = event.qrToken,
            payload = "epharm://training/check-in/${event.qrToken}",
        )
    }

    @Transactional
    fun markAttendance(
        eventId: UUID,
        participantId: UUID,
        req: MarkAttendanceRequest,
        principal: AdminPrincipal,
    ): EventParticipantDto {
        val event = loadEvent(eventId)
        ensureEventVisible(event, principal)
        if (principal.role == AdminRole.TRAINER && event.trainerId != principal.userId) {
            forbidden("Тренер может отмечать посещаемость только на своих мероприятиях")
        }
        val participant = participantRepository.findById(participantId).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Участник не найден", HttpStatus.NOT_FOUND)
        }
        if (participant.eventId != eventId) badRequest("Участник не относится к этому событию")
        participant.status = req.status
        participant.checkMethod = req.method
        participant.trainerComment = req.comment?.trim()
        participant.score = req.score
        val attendanceAccepted = req.status in setOf(EventParticipantStatus.attended, EventParticipantStatus.late)
        participant.checkedInAt = if (attendanceAccepted) Instant.now() else null
        participantRepository.save(participant)

        val assignment = loadAssignment(participant.assignmentId)
        val versionStages = programStageRepository.findAllByVersionIdOrderByOrderNoAsc(assignment.programVersionId)
        val offlineStageIds = versionStages.filter { it.type == TrainingStageType.offline_event }.map { it.id }.toSet()
        val assignmentStages = assignmentStageRepository.findAllByAssignmentIdOrderByProgramStageIdAsc(assignment.id)
        assignmentStages.filter { it.programStageId in offlineStageIds }.forEach { stage ->
            when (req.status) {
                EventParticipantStatus.attended, EventParticipantStatus.late -> {
                    stage.status = TrainingStageStatus.completed
                    stage.progressPct = 100
                    stage.score = req.score
                    stage.completedAt = Instant.now()
                }
                EventParticipantStatus.no_show -> {
                    stage.status = TrainingStageStatus.failed
                    stage.progressPct = 0
                    assignment.status = TrainingAssignmentStatus.retake_required
                }
                EventParticipantStatus.excused -> {
                    stage.status = TrainingStageStatus.available
                    stage.progressPct = 0
                    stage.score = null
                    stage.completedAt = null
                    assignment.eventId = null
                }
                else -> Unit
            }
            assignmentStageRepository.save(stage)
        }
        recalculateAssignment(assignment)
        if (attendanceAccepted) {
            createNotification(
                pharmacistId = participant.pharmacistId,
                assignmentId = participant.assignmentId,
                eventId = event.id,
                eventType = "training_attendance_confirmed",
                payload = mapOf("event" to event.title),
            )
        }
        audit(
            principal.userId.toString(),
            "admin",
            "attendance_marked",
            "event_participant",
            participant.id.toString(),
            mapOf("status" to req.status.name),
        )
        return participantDto(participant)
    }

    // Assignments

    @Transactional(readOnly = true)
    fun listAssignments(
        principal: AdminPrincipal,
        format: TrainingFormat? = null,
        status: TrainingAssignmentStatus? = null,
        programId: UUID? = null,
        query: String? = null,
    ): List<TrainingAssignmentDto> {
        val normalizedQuery = query?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
        return mapAssignments(scopedAssignments(principal, assignmentRepository.findAllByOrderByCreatedAtDesc()))
            .asSequence()
            .filter { format == null || it.format == format }
            .filter { status == null || it.status == status }
            .filter { programId == null || it.programId == programId }
            .filter {
                normalizedQuery == null ||
                    "${it.pharmacistName} ${it.pharmacyName} ${it.city} ${it.programName}"
                        .lowercase().contains(normalizedQuery)
            }
            .toList()
    }

    @Transactional(readOnly = true)
    fun listAssignmentsPage(
        principal: AdminPrincipal,
        format: TrainingFormat? = null,
        status: TrainingAssignmentStatus? = null,
        programId: UUID? = null,
        query: String? = null,
        requestedPage: Int = 0,
        requestedSize: Int = 25,
    ): TrainingAssignmentPageDto {
        val page = requestedPage.coerceAtLeast(0)
        val size = requestedSize.coerceIn(1, 100)
        val filtered = listAssignments(principal, format, status, programId, query)
        val from = (page.toLong() * size).coerceAtMost(filtered.size.toLong()).toInt()
        val to = (from + size).coerceAtMost(filtered.size)
        return TrainingAssignmentPageDto(
            items = filtered.subList(from, to),
            total = filtered.size,
            page = page,
            size = size,
            totalPages = if (filtered.isEmpty()) 0 else (filtered.size + size - 1) / size,
        )
    }

    @Transactional
    fun exportAssignmentsCsv(
        principal: AdminPrincipal,
        format: TrainingFormat? = null,
        status: TrainingAssignmentStatus? = null,
        programId: UUID? = null,
        query: String? = null,
    ): ByteArray {
        val rows = listAssignments(principal, format, status, programId, query)
        val generatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val csv = buildString {
            appendLine("Отчёт;Назначения обучения")
            appendLine("Сформирован;${csvCell(generatedAt)}")
            appendLine("Автор;${csvCell(principal.name)}")
            appendLine()
            appendLine("Фармацевт;Аптека;Город;Программа;Версия;Формат;Статус;Прогресс, %;Балл;Срок;Завершено;Бонус, ₸;Сертификат")
            rows.forEach { row ->
                appendLine(
                    listOf(
                        row.pharmacistName,
                        row.pharmacyName,
                        row.city,
                        row.programName,
                        row.programVersion.toString(),
                        row.format.name,
                        row.status.name,
                        row.progressPct.toString(),
                        row.score?.toString().orEmpty(),
                        row.dueAt?.let(DateTimeFormatter.ISO_INSTANT::format).orEmpty(),
                        row.completedAt?.let(DateTimeFormatter.ISO_INSTANT::format).orEmpty(),
                        row.reward?.amount?.toString().orEmpty(),
                        row.certificate?.number.orEmpty(),
                    ).joinToString(";") { csvCell(it) },
                )
            }
        }
        audit(
            principal.userId.toString(),
            "admin",
            "assignments_exported",
            "training_assignment",
            principal.userId.toString(),
            mapOf("rows" to rows.size, "format" to format?.name, "status" to status?.name),
        )
        return ("\uFEFF" + csv).toByteArray(Charsets.UTF_8)
    }

    @Transactional
    fun createAssignments(
        req: CreateTrainingAssignmentsRequest,
        principal: AdminPrincipal,
    ): MassAssignmentResultDto {
        val program = loadProgram(req.programId)
        if (program.status !in setOf(TrainingProgramStatus.published, TrainingProgramStatus.scheduled, TrainingProgramStatus.paused)) {
            badRequest("Назначать можно только опубликованную или запланированную программу")
        }
        val version = loadCurrentVersion(program)
        if (req.format !in version.allowedFormats) badRequest("Формат ${req.format} не разрешён для программы")
        req.responsibleId?.let(::ensureAdminExists)
        val event = req.eventId?.let(::loadEvent)
        if (event != null && event.programVersionId != version.id) badRequest("Событие относится к другой версии программы")
        event?.let { ensureEventVisible(it, principal) }
        if (req.format == TrainingFormat.online && event != null) badRequest("Онлайн-назначение не может быть привязано к очному событию")
        if (req.dueAt != null && req.startsAt != null && !req.dueAt.isAfter(req.startsAt)) {
            badRequest("Дедлайн должен быть позже даты начала")
        }

        val pharmacists = pharmacistRepository.findAllById(req.pharmacistIds).associateBy { it.id }
        val missing = req.pharmacistIds - pharmacists.keys
        if (missing.isNotEmpty()) badRequest("Фармацевты не найдены: ${missing.joinToString()}")
        pharmacists.values.forEach {
            if (it.status != PharmacistStatus.active) badRequest("Фармацевт ${it.name} не активирован")
            ensurePharmacistVisible(it, principal)
        }

        var created = 0
        var updated = 0
        var skipped = 0
        val result = mutableListOf<TrainingAssignmentEntity>()
        req.pharmacistIds.forEach { pharmacistId ->
            val active = assignmentRepository
                .findFirstByProgramVersionIdAndPharmacistIdAndStatusRawNotInOrderByCreatedAtDesc(
                    version.id,
                    pharmacistId,
                    terminalAssignmentStatuses,
                )
            if (active != null) {
                when (req.duplicatePolicy) {
                    DuplicateAssignmentPolicy.skip, DuplicateAssignmentPolicy.repeat -> {
                        skipped++
                        result += active
                    }
                    DuplicateAssignmentPolicy.update_deadline -> {
                        active.startsAt = req.startsAt ?: active.startsAt
                        active.dueAt = req.dueAt ?: active.dueAt
                        active.priority = req.priority
                        active.responsibleId = req.responsibleId ?: active.responsibleId
                        active.required = req.required
                        if (event != null && active.eventId != event.id) {
                            attachEvent(active, event, pharmacistId)
                        }
                        assignmentRepository.save(active)
                        updated++
                        result += active
                    }
                }
                return@forEach
            }

            val repeatNo = assignmentRepository
                .findAllByProgramVersionIdAndPharmacistIdOrderByRepeatNoDesc(version.id, pharmacistId)
                .firstOrNull()?.repeatNo?.plus(1) ?: 1
            val assignment = TrainingAssignmentEntity(
                programVersionId = version.id,
                pharmacistId = pharmacistId,
                required = req.required,
                assignedBy = principal.userId,
                responsibleId = req.responsibleId,
                eventId = event?.id,
                startsAt = req.startsAt,
                dueAt = req.dueAt ?: Instant.now().plusSeconds(program.normativeDays * 86_400L),
                repeatNo = repeatNo,
            ).also {
                it.format = req.format
                it.priority = req.priority
                it.status = initialAssignmentStatus(req.startsAt, req.format, event)
            }
            val saved = try {
                assignmentRepository.saveAndFlush(assignment)
            } catch (_: DataIntegrityViolationException) {
                conflict("Активное назначение этой программы уже существует")
            }
            createAssignmentStages(saved, version)
            if (event != null) attachEvent(saved, event, pharmacistId)
            createNotification(
                pharmacistId = pharmacistId,
                assignmentId = saved.id,
                eventId = event?.id,
                eventType = "training_assigned",
                payload = mapOf("program" to program.name, "format" to req.format.name),
            )
            scheduleDeadlineNotifications(saved, program.name)
            val pharmacist = pharmacists.getValue(pharmacistId)
            pharmacist.coursesTotal += 1
            pharmacistRepository.save(pharmacist)
            version.onlineCourseId?.let { courseId ->
                courseRepository.findById(courseId).ifPresent { course ->
                    course.enrolled += 1
                    courseRepository.save(course)
                }
            }
            created++
            result += saved
        }
        audit(
            principal.userId.toString(),
            "admin",
            "assignments_created",
            "training_program",
            program.id.toString(),
            mapOf("created" to created, "updated" to updated, "skipped" to skipped),
        )
        return MassAssignmentResultDto(created, updated, skipped, mapAssignments(result))
    }

    @Transactional
    fun changeAssignmentFormat(
        assignmentId: UUID,
        req: ChangeAssignmentFormatRequest,
        principal: AdminPrincipal,
    ): TrainingAssignmentDto {
        val assignment = loadAssignment(assignmentId)
        ensureAssignmentVisible(assignment, principal)
        if (assignment.status == TrainingAssignmentStatus.cancelled) conflict("Отменённое назначение нельзя изменить")
        val version = versionRepository.findById(assignment.programVersionId).orElseThrow()
        if (req.format !in version.allowedFormats) badRequest("Выбранный формат не разрешён для этой версии программы")
        if ((certificateRepository.findByAssignmentId(assignment.id) != null || rewardRepository.findByAssignmentId(assignment.id) != null) &&
            principal.role != AdminRole.SYSTEM_ADMIN
        ) {
            forbidden("После выдачи сертификата или бонуса формат может изменить только системный администратор")
        }
        val event = req.eventId?.let(::loadEvent)
        if (event != null && event.programVersionId != version.id) badRequest("Событие относится к другой версии программы")
        event?.let { ensureEventVisible(it, principal) }
        if (req.format == TrainingFormat.online && event != null) badRequest("Онлайн-формат не использует очное событие")
        if (assignment.format == req.format && assignment.eventId == req.eventId) return mapAssignments(listOf(assignment)).single()

        val oldFormat = assignment.format
        val oldEventId = assignment.eventId
        val definitions = programStageRepository.findAllByVersionIdOrderByOrderNoAsc(version.id)
        val stagesByDefinition = assignmentStageRepository
            .findAllByAssignmentIdOrderByProgramStageIdAsc(assignment.id)
            .associateBy { it.programStageId }
            .toMutableMap()

        definitions.forEach { definition ->
            val applies = req.format in definition.applicableFormats
            val existing = stagesByDefinition[definition.id]
            if (applies && existing == null) {
                stagesByDefinition[definition.id] = assignmentStageRepository.save(
                    TrainingAssignmentStageEntity(
                        assignmentId = assignment.id,
                        programStageId = definition.id,
                    ).also { it.status = TrainingStageStatus.locked },
                )
            } else if (!applies && existing != null && existing.status != TrainingStageStatus.completed) {
                existing.status = TrainingStageStatus.skipped
                assignmentStageRepository.save(existing)
            }
        }

        assignment.format = req.format
        assignment.eventId = null
        assignmentRepository.save(assignment)
        if (req.format == TrainingFormat.online) {
            participantRepository.findByAssignmentId(assignment.id)?.let {
                it.status = EventParticipantStatus.cancelled
                participantRepository.save(it)
            }
        } else if (event != null) {
            attachEvent(assignment, event, assignment.pharmacistId)
        }
        reconcileStageAvailability(assignment)
        recalculateAssignment(assignment)
        assignmentFormatHistoryRepository.save(
            TrainingAssignmentFormatHistoryEntity(
                assignmentId = assignment.id,
                oldEventId = oldEventId,
                newEventId = assignment.eventId,
                reason = req.reason.trim(),
                changedBy = principal.userId,
            ).also {
                it.oldFormat = oldFormat
                it.newFormat = req.format
            },
        )
        audit(
            principal.userId.toString(),
            "admin",
            "assignment_format_changed",
            "training_assignment",
            assignment.id.toString(),
            mapOf("oldFormat" to oldFormat.name, "newFormat" to req.format.name, "reason" to req.reason.trim()),
        )
        return mapAssignments(listOf(assignment)).single()
    }

    @Transactional(readOnly = true)
    fun assignmentFormatHistory(
        assignmentId: UUID,
        principal: AdminPrincipal,
    ): List<TrainingAssignmentFormatHistoryDto> {
        val assignment = loadAssignment(assignmentId)
        ensureAssignmentVisible(assignment, principal)
        return assignmentFormatHistoryRepository.findAllByAssignmentIdOrderByChangedAtDesc(assignmentId).map { row ->
            TrainingAssignmentFormatHistoryDto(
                id = row.id,
                assignmentId = row.assignmentId,
                oldFormat = row.oldFormat,
                newFormat = row.newFormat,
                oldEventId = row.oldEventId,
                newEventId = row.newEventId,
                reason = row.reason,
                changedBy = row.changedBy,
                changedByName = adminUserRepository.findById(row.changedBy).orElse(null)?.name ?: "Удалённый пользователь",
                changedAt = row.changedAt,
            )
        }
    }

    @Transactional
    fun changePreference(
        pharmacistId: String,
        req: ChangeTrainingPreferenceRequest,
        principal: AdminPrincipal,
    ): TrainingPreferenceDto = savePreference(
        pharmacistId = pharmacistId,
        format = req.defaultFormat,
        reason = req.reason,
        principal = principal,
    )

    @Transactional
    fun changePreferences(
        req: MassChangeTrainingPreferencesRequest,
        principal: AdminPrincipal,
    ): List<TrainingPreferenceDto> {
        val result = req.pharmacistIds.sorted().map { pharmacistId ->
            savePreference(pharmacistId, req.defaultFormat, req.reason, principal)
        }
        audit(
            principal.userId.toString(),
            "admin",
            "default_format_changed_mass",
            "pharmacist_training_preference",
            principal.userId.toString(),
            mapOf("count" to result.size, "format" to req.defaultFormat.name),
        )
        return result
    }

    @Transactional(readOnly = true)
    fun listCurrentPreferences(principal: AdminPrincipal): List<TrainingPreferenceDto> =
        preferenceRepository.findAllByValidToIsNullOrderByPharmacistIdAsc()
            .filter { preference ->
                val pharmacist = pharmacistRepository.findById(preference.pharmacistId).orElse(null)
                    ?: return@filter false
                pharmacistVisible(pharmacist, principal)
            }
            .map(::preferenceDto)

    @Transactional(readOnly = true)
    fun preferenceHistory(pharmacistId: String, principal: AdminPrincipal): List<TrainingPreferenceDto> {
        val pharmacist = loadPharmacist(pharmacistId)
        ensurePharmacistVisible(pharmacist, principal)
        return preferenceRepository.findAllByPharmacistIdOrderByValidFromDesc(pharmacistId).map(::preferenceDto)
    }

    private fun savePreference(
        pharmacistId: String,
        format: TrainingFormat,
        reason: String,
        principal: AdminPrincipal,
    ): TrainingPreferenceDto {
        val pharmacist = loadPharmacist(pharmacistId)
        ensurePharmacistVisible(pharmacist, principal)
        val now = Instant.now()
        preferenceRepository.findByPharmacistIdAndValidToIsNull(pharmacistId)?.let {
            if (it.defaultFormat == format) return preferenceDto(it)
            it.validTo = now
            // Close the partial-unique current row before inserting the next one.
            preferenceRepository.saveAndFlush(it)
        }
        val pref = PharmacistTrainingPreferenceEntity(
            pharmacistId = pharmacistId,
            changedBy = principal.userId,
            reason = reason.trim(),
            validFrom = now,
        ).also { it.defaultFormat = format }
        preferenceRepository.save(pref)
        audit(principal.userId.toString(), "admin", "default_format_changed", "pharmacist", pharmacistId)
        return preferenceDto(pref)
    }

    // Mobile pharmacist flow

    @Transactional(readOnly = true)
    fun mobileOverview(pharmacistId: String): MobileTrainingOverviewDto {
        loadPharmacist(pharmacistId)
        val assignments = assignmentRepository.findAllByPharmacistIdOrderByCreatedAtDesc(pharmacistId)
        val dtos = mapAssignments(assignments)
        val certificates = certificateRepository.findAllByPharmacistIdOrderByIssuedAtDesc(pharmacistId)
            .map(::certificateDto)
        val upcoming = dtos.mapNotNull { it.event }
            .filter { it.startsAt.isAfter(Instant.now()) && it.status != OfflineEventStatus.cancelled }
            .distinctBy { it.id }
            .sortedBy { it.startsAt }
        return MobileTrainingOverviewDto(
            total = dtos.size,
            inProgress = dtos.count { it.status !in setOf(TrainingAssignmentStatus.completed, TrainingAssignmentStatus.cancelled) && it.progressPct > 0 },
            completed = dtos.count { it.status == TrainingAssignmentStatus.completed },
            overdue = dtos.count { it.status == TrainingAssignmentStatus.overdue },
            upcomingEvents = upcoming,
            assignments = dtos,
            certificates = certificates,
            notifications = notificationRepository
                .findAllByPharmacistIdAndChannelAndScheduledAtLessThanEqualOrderByScheduledAtDesc(
                    pharmacistId,
                    "internal",
                    Instant.now(),
                )
                .map(::notificationDto),
            defaultFormat = preferenceRepository.findByPharmacistIdAndValidToIsNull(pharmacistId)?.defaultFormat,
        )
    }

    @Transactional
    fun markNotificationRead(pharmacistId: String, notificationId: UUID): TrainingNotificationDto {
        val notification = notificationRepository.findById(notificationId).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Уведомление не найдено", HttpStatus.NOT_FOUND)
        }
        if (notification.pharmacistId != pharmacistId || notification.channel != "internal") {
            forbidden("Уведомление принадлежит другому пользователю")
        }
        if (notification.scheduledAt.isAfter(Instant.now())) forbidden("Уведомление ещё не отправлено")
        if (notification.status != "sent") {
            notification.status = "sent"
            notification.sentAt = Instant.now()
            notificationRepository.save(notification)
            audit(pharmacistId, "pharmacist", "notification_read", "training_notification", notification.id.toString())
        }
        return notificationDto(notification)
    }

    @Transactional(readOnly = true)
    fun mobileAssignment(pharmacistId: String, assignmentId: UUID): TrainingAssignmentDto {
        val assignment = loadOwnedAssignment(pharmacistId, assignmentId)
        return mapAssignments(listOf(assignment)).single()
    }

    @Transactional(readOnly = true)
    fun availableEvents(pharmacistId: String, assignmentId: UUID): List<OfflineEventDto> {
        val assignment = loadOwnedAssignment(pharmacistId, assignmentId)
        if (assignment.format == TrainingFormat.online) return emptyList()
        val pharmacist = loadPharmacist(pharmacistId)
        val now = Instant.now()
        return eventRepository.findAllByProgramVersionIdOrderByStartsAtAsc(assignment.programVersionId)
            .asSequence()
            .filter { event -> eventAcceptsSelfRegistration(event, assignment, pharmacist, now) }
            .map(::eventDto)
            .toList()
    }

    @Transactional
    fun selectEvent(pharmacistId: String, assignmentId: UUID, eventId: UUID): TrainingAssignmentDto {
        val assignment = loadOwnedAssignment(pharmacistId, assignmentId)
        if (assignment.status in setOf(TrainingAssignmentStatus.completed, TrainingAssignmentStatus.cancelled)) {
            conflict("Завершённое или отменённое назначение нельзя изменить")
        }
        if (assignment.format == TrainingFormat.online) forbidden("Онлайн-формат не использует очное мероприятие")
        val event = loadEvent(eventId)
        val pharmacist = loadPharmacist(pharmacistId)
        if (!eventAcceptsSelfRegistration(event, assignment, pharmacist, Instant.now())) {
            conflict("Мероприятие недоступно для этого назначения, региона или периода")
        }
        attachEvent(assignment, event, pharmacistId)
        reconcileStageAvailability(assignment)
        recalculateAssignment(assignment)
        createNotification(
            pharmacistId = pharmacistId,
            assignmentId = assignment.id,
            eventId = event.id,
            eventType = "training_event_selected",
            payload = mapOf("event" to event.title, "startsAt" to event.startsAt),
        )
        audit(
            pharmacistId,
            "pharmacist",
            "event_selected",
            "training_assignment",
            assignment.id.toString(),
            mapOf("eventId" to event.id),
        )
        return mapAssignments(listOf(assignment)).single()
    }

    @Transactional
    fun startAssignment(pharmacistId: String, assignmentId: UUID): TrainingAssignmentDto {
        val assignment = loadOwnedAssignment(pharmacistId, assignmentId)
        if (assignment.status == TrainingAssignmentStatus.cancelled) conflict("Назначение отменено")
        if (assignment.status == TrainingAssignmentStatus.completed) return mapAssignments(listOf(assignment)).single()
        if (assignment.startsAt?.isAfter(Instant.now()) == true) conflict("Программа ещё не началась")
        if (assignment.startedAt == null) assignment.startedAt = Instant.now()
        assignment.status = statusForNextStage(assignment)
        assignmentRepository.save(assignment)
        audit(pharmacistId, "pharmacist", "assignment_started", "training_assignment", assignment.id.toString())
        return mapAssignments(listOf(assignment)).single()
    }

    @Transactional
    fun updateStageProgress(
        pharmacistId: String,
        assignmentId: UUID,
        stageId: UUID,
        req: StageProgressRequest,
    ): TrainingAssignmentDto {
        val assignment = loadOwnedAssignment(pharmacistId, assignmentId)
        if (assignment.status in setOf(TrainingAssignmentStatus.completed, TrainingAssignmentStatus.cancelled)) {
            conflict("Завершённое или отменённое назначение нельзя изменять")
        }
        val assignmentStage = assignmentStageRepository.findById(stageId).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Этап назначения не найден", HttpStatus.NOT_FOUND)
        }
        if (assignmentStage.assignmentId != assignmentId) badRequest("Этап относится к другому назначению")
        if (assignmentStage.status == TrainingStageStatus.locked) conflict("Предыдущий обязательный этап ещё не завершён")
        val programStage = programStageRepository.findById(assignmentStage.programStageId).orElseThrow()
        if (programStage.type !in setOf(TrainingStageType.material, TrainingStageType.online_course)) {
            forbidden("Результат теста, экзамена или очного этапа фиксируется доверенным административным контуром")
        }
        val now = Instant.now()
        if (assignment.startedAt == null) assignment.startedAt = now
        if (assignmentStage.startedAt == null) assignmentStage.startedAt = now
        assignmentStage.progressPct = req.progressPct
        assignmentStage.status = if (req.progressPct == 0) TrainingStageStatus.available else TrainingStageStatus.in_progress
        if (req.progressPct == 100) {
            if (programStage.manualReview) {
                assignmentStage.status = TrainingStageStatus.waiting_review
            } else {
                assignmentStage.status = TrainingStageStatus.completed
                assignmentStage.completedAt = now
            }
        }
        assignmentStageRepository.save(assignmentStage)
        unlockDependentStages(assignment, programStage.stageKey)
        recalculateAssignment(assignment)
        audit(
            pharmacistId,
            "pharmacist",
            "stage_progress_updated",
            "training_assignment_stage",
            stageId.toString(),
            mapOf("progress" to req.progressPct),
        )
        return mapAssignments(listOf(assignment)).single()
    }

    @Transactional
    fun recordAssessmentResult(
        assignmentId: UUID,
        stageId: UUID,
        req: RecordAssessmentResultRequest,
        principal: AdminPrincipal,
    ): TrainingAssignmentDto {
        val assignment = assignmentRepository.findById(assignmentId).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Назначение не найдено", HttpStatus.NOT_FOUND)
        }
        ensureAssignmentVisible(assignment, principal)
        if (assignment.status in setOf(TrainingAssignmentStatus.completed, TrainingAssignmentStatus.cancelled)) {
            conflict("Завершённое или отменённое назначение нельзя изменять")
        }
        val stage = assignmentStageRepository.findById(stageId).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Этап назначения не найден", HttpStatus.NOT_FOUND)
        }
        if (stage.assignmentId != assignmentId) badRequest("Этап относится к другому назначению")
        if (stage.status == TrainingStageStatus.locked) conflict("Предыдущий обязательный этап ещё не завершён")
        val definition = programStageRepository.findById(stage.programStageId).orElseThrow()
        if (definition.type !in setOf(TrainingStageType.test, TrainingStageType.ai_exam, TrainingStageType.manual_review)) {
            badRequest("Для этого этапа результат проверки не применяется")
        }
        if (principal.role == AdminRole.TRAINER && definition.type != TrainingStageType.manual_review) {
            forbidden("Тренер может фиксировать только результат очной аттестации")
        }
        val nextAttempt = stage.attemptsUsed + 1
        if (definition.maxAttempts != null && nextAttempt > definition.maxAttempts!!) {
            conflict("Лимит попыток исчерпан")
        }
        val threshold = definition.passingScore
        val passed = req.passed ?: (threshold == null || req.score >= threshold)
        val now = Instant.now()
        stage.attemptsUsed = nextAttempt
        stage.score = req.score
        stage.progressPct = 100
        stage.startedAt = stage.startedAt ?: now
        stage.status = if (passed) TrainingStageStatus.completed else TrainingStageStatus.failed
        stage.completedAt = now.takeIf { passed }
        assignmentStageRepository.save(stage)
        assessmentResultRepository.save(
            TrainingAssessmentResultEntity(
                assignmentId = assignment.id,
                assignmentStageId = stage.id,
                attemptNo = nextAttempt,
                score = req.score,
                passed = passed,
                feedback = req.feedback.trim(),
                competencyJson = objectMapper.writeValueAsString(req.competencyScores),
                recordedBy = principal.userId,
                recordedAt = now,
            ).also { it.sourceType = definition.type },
        )
        if (passed) {
            unlockDependentStages(assignment, definition.stageKey)
        } else {
            assignment.status = TrainingAssignmentStatus.retake_required
            assignmentRepository.save(assignment)
        }
        recalculateAssignment(assignment)
        audit(
            principal.userId.toString(),
            "admin",
            "assessment_result_recorded",
            "training_assignment_stage",
            stage.id.toString(),
            mapOf("attempt" to nextAttempt, "score" to req.score, "passed" to passed),
        )
        return mapAssignments(listOf(assignment)).single()
    }

    @Transactional(readOnly = true)
    fun listAssessmentResults(
        assignmentId: UUID,
        principal: AdminPrincipal,
    ): List<TrainingAssessmentResultDto> {
        val assignment = assignmentRepository.findById(assignmentId).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Назначение не найдено", HttpStatus.NOT_FOUND)
        }
        ensureAssignmentVisible(assignment, principal)
        return assessmentResultRepository.findAllByAssignmentIdOrderByRecordedAtDesc(assignmentId)
            .map(::assessmentResultDto)
    }

    @Transactional
    fun checkInEvent(pharmacistId: String, qrToken: UUID): TrainingAssignmentDto {
        val event = eventRepository.findByQrToken(qrToken)
            ?: throw AppException(ErrorCode.NOT_FOUND, "QR-код события не найден", HttpStatus.NOT_FOUND)
        val now = Instant.now()
        if (now.isBefore(event.startsAt.minusSeconds(7_200)) || now.isAfter(event.endsAt.plusSeconds(3_600))) {
            conflict("Отметка доступна за 2 часа до начала и до часа после завершения события")
        }
        val participant = participantRepository.findByEventIdAndPharmacistId(event.id, pharmacistId)
            ?: forbidden("Фармацевт не зарегистрирован на это событие")
        participant.status = EventParticipantStatus.attended
        participant.checkMethod = kz.epharm.training.domain.AttendanceMethod.qr
        participant.checkedInAt = now
        participantRepository.save(participant)
        val assignment = loadOwnedAssignment(pharmacistId, participant.assignmentId)
        completeOfflineStages(assignment, participant.score)
        recalculateAssignment(assignment)
        createNotification(
            pharmacistId = pharmacistId,
            assignmentId = assignment.id,
            eventId = event.id,
            eventType = "training_attendance_confirmed",
            payload = mapOf("event" to event.title),
        )
        audit(pharmacistId, "pharmacist", "event_qr_check_in", "offline_event", event.id.toString())
        return mapAssignments(listOf(assignment)).single()
    }

    @Transactional(readOnly = true)
    fun listCertificates(principal: AdminPrincipal): List<TrainingCertificateDto> {
        val visibleAssignmentIds = scopedAssignments(principal, assignmentRepository.findAllByOrderByCreatedAtDesc())
            .map { it.id }.toSet()
        return certificateRepository.findAllByOrderByIssuedAtDesc()
            .filter { it.assignmentId in visibleAssignmentIds }
            .map(::certificateDto)
    }

    @Transactional(readOnly = true)
    fun verifyCertificate(qrToken: UUID): CertificateVerificationDto {
        val certificate = certificateRepository.findByQrToken(qrToken)
            ?: throw AppException(ErrorCode.NOT_FOUND, "Сертификат не найден", HttpStatus.NOT_FOUND)
        val pharmacist = loadPharmacist(certificate.pharmacistId)
        val version = versionRepository.findById(certificate.programVersionId).orElseThrow()
        val program = loadProgram(version.programId)
        val status = effectiveCertificateStatus(certificate)
        return CertificateVerificationDto(
            number = certificate.certificateNumber,
            pharmacistName = pharmacist.name,
            programName = program.name,
            format = certificate.format,
            issuedAt = certificate.issuedAt,
            expiresAt = certificate.expiresAt,
            score = certificate.score,
            signerName = certificate.signerName,
            status = status,
            valid = status == kz.epharm.training.domain.CertificateStatus.valid,
        )
    }

    @Transactional(readOnly = true)
    fun pharmacistTrainingProfile(
        pharmacistId: String,
        principal: AdminPrincipal,
    ): PharmacistTrainingProfileDto {
        val pharmacist = loadPharmacist(pharmacistId)
        ensurePharmacistVisible(pharmacist, principal)
        val assignments = mapAssignments(
            assignmentRepository.findAllByPharmacistIdOrderByCreatedAtDesc(pharmacistId),
        )
        val rewards = rewardRepository.findAllByPharmacistIdOrderByIssuedAtDesc(pharmacistId).map(::rewardDto)
        return PharmacistTrainingProfileDto(
            pharmacistId = pharmacist.id,
            pharmacistName = pharmacist.name,
            pharmacyName = pharmacist.pharmacyName.orEmpty(),
            city = pharmacist.city,
            defaultFormat = preferenceRepository.findByPharmacistIdAndValidToIsNull(pharmacistId)?.defaultFormat,
            totalAssignments = assignments.size,
            completedAssignments = assignments.count { it.status == TrainingAssignmentStatus.completed },
            inProgressAssignments = assignments.count {
                it.status !in setOf(TrainingAssignmentStatus.completed, TrainingAssignmentStatus.cancelled)
            },
            totalRewards = rewards.filter { it.status == "issued" }.sumOf { it.amount },
            assignments = assignments,
            certificates = certificateRepository.findAllByPharmacistIdOrderByIssuedAtDesc(pharmacistId)
                .map(::certificateDto),
            rewards = rewards,
            preferenceHistory = preferenceRepository.findAllByPharmacistIdOrderByValidFromDesc(pharmacistId)
                .map(::preferenceDto),
        )
    }

    // Mapping and lifecycle helpers

    private fun createVersion(
        program: TrainingProgramEntity,
        versionNo: Int,
        formats: Set<TrainingFormat>,
        onlineCourseId: String?,
        passingScore: Int,
        maxAttempts: Int,
        completionBonus: Long,
        requestedStages: List<CreateTrainingStageRequest>,
        actor: AdminPrincipal,
        snapshot: Any,
    ): TrainingProgramVersionEntity {
        onlineCourseId?.let {
            if (!courseRepository.existsById(it)) badRequest("Онлайн-курс $it не найден")
        }
        val version = TrainingProgramVersionEntity(
            programId = program.id,
            versionNo = versionNo,
            onlineCourseId = onlineCourseId,
            passingScore = passingScore,
            maxAttempts = maxAttempts,
            completionBonus = completionBonus,
            snapshotJson = objectMapper.writeValueAsString(snapshot),
            createdBy = actor.userId,
        ).also { it.allowedFormats = formats }
        versionRepository.save(version)
        val stages = requestedStages.ifEmpty { defaultStages(formats, onlineCourseId, passingScore, maxAttempts) }
        validateStages(stages, formats, onlineCourseId)
        stages.sortedBy { it.order }.forEach { req ->
            val applicableFormats = resolveStageFormats(req, formats)
            programStageRepository.save(
                TrainingProgramStageEntity(
                    versionId = version.id,
                    stageKey = req.key.trim(),
                    title = req.title.trim(),
                    orderNo = req.order,
                    required = req.required,
                    unlockAfterKey = req.unlockAfterKey?.trim()?.takeIf(String::isNotEmpty),
                    deadlineDays = req.deadlineDays,
                    passingScore = req.passingScore,
                    maxAttempts = req.maxAttempts,
                    bonus = req.bonus,
                    manualReview = req.manualReview,
                    contentUrl = req.contentUrl?.trim()?.takeIf(String::isNotEmpty),
                ).also {
                    it.type = req.type
                    it.applicableFormats = applicableFormats
                },
            )
        }
        return version
    }

    private fun defaultStages(
        formats: Set<TrainingFormat>,
        courseId: String?,
        passingScore: Int,
        maxAttempts: Int,
    ): List<CreateTrainingStageRequest> {
        val rows = mutableListOf<CreateTrainingStageRequest>()
        var order = 0
        if (TrainingFormat.online in formats || TrainingFormat.hybrid in formats) {
            rows += CreateTrainingStageRequest(
                key = "online",
                type = if (courseId == null) TrainingStageType.material else TrainingStageType.online_course,
                title = if (courseId == null) "Материалы программы" else "Онлайн-курс",
                order = order++,
                applicableFormats = formats.intersect(setOf(TrainingFormat.online, TrainingFormat.hybrid)),
            )
        }
        if (TrainingFormat.offline in formats || TrainingFormat.hybrid in formats) {
            rows += CreateTrainingStageRequest(
                key = "offline",
                type = TrainingStageType.offline_event,
                title = "Очное обучение",
                order = order++,
                unlockAfterKey = rows.lastOrNull()?.key.takeIf { TrainingFormat.hybrid in formats },
                applicableFormats = formats.intersect(setOf(TrainingFormat.hybrid, TrainingFormat.offline)),
            )
        }
        rows += CreateTrainingStageRequest(
            key = "final_test",
            type = TrainingStageType.test,
            title = "Итоговый тест",
            order = order,
            unlockAfterKey = rows.lastOrNull()?.key,
            passingScore = passingScore,
            maxAttempts = maxAttempts,
            applicableFormats = formats,
        )
        return rows
    }

    private fun validateProgramRequest(req: CreateTrainingProgramRequest) {
        validatePeriod(req.startsAt, req.endsAt)
        if (req.language !in setOf("ru", "kk")) badRequest("Поддерживаются языки ru и kk")
        req.managerId?.let(::ensureAdminExists)
    }

    private fun validatePeriod(startsAt: Instant?, endsAt: Instant?) {
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            badRequest("Дата завершения должна быть позже даты начала")
        }
    }

    private fun validateStages(
        stages: List<CreateTrainingStageRequest>,
        formats: Set<TrainingFormat>,
        onlineCourseId: String?,
    ) {
        if (stages.isEmpty()) badRequest("Маршрут программы не может быть пустым")
        if (stages.map { it.key.trim() }.distinct().size != stages.size) badRequest("Ключи этапов должны быть уникальными")
        if (stages.map { it.order }.distinct().size != stages.size) badRequest("Порядок этапов должен быть уникальным")
        val keys = stages.map { it.key.trim() }.toSet()
        stages.forEach { stage ->
            val applicableFormats = resolveStageFormats(stage, formats)
            if (applicableFormats.isEmpty()) badRequest("Этап ${stage.key}: выберите хотя бы один формат")
            if (!formats.containsAll(applicableFormats)) {
                badRequest("Этап ${stage.key}: маршрут содержит формат, не разрешённый программой")
            }
            if (stage.unlockAfterKey != null && stage.unlockAfterKey !in keys) {
                badRequest("Этап ${stage.key}: условие разблокировки не найдено")
            }
            if (stage.unlockAfterKey == stage.key) badRequest("Этап не может разблокировать сам себя")
            if (stage.type in setOf(TrainingStageType.test, TrainingStageType.ai_exam) && stage.passingScore == null) {
                badRequest("Этап ${stage.key}: укажите проходной балл")
            }
            if (
                stage.type == TrainingStageType.online_course &&
                onlineCourseId == null &&
                stage.contentUrl.isNullOrBlank()
            ) {
                badRequest("Этап ${stage.key}: привяжите онлайн-курс или укажите ссылку на материал")
            }
        }
        formats.forEach { format ->
            val route = stages.filter { format in resolveStageFormats(it, formats) }
            val required = route.filter { it.required }
            if (required.isEmpty()) badRequest("Для формата $format нужен хотя бы один обязательный этап")
            if (format == TrainingFormat.online && required.any { it.type == TrainingStageType.offline_event }) {
                badRequest("Онлайн-маршрут не может требовать очное мероприятие")
            }
            if (format == TrainingFormat.hybrid) {
                if (required.none { it.type in setOf(TrainingStageType.material, TrainingStageType.online_course) }) {
                    badRequest("Гибридный маршрут должен содержать обязательный онлайн-этап")
                }
                if (required.none { it.type == TrainingStageType.offline_event }) {
                    badRequest("Гибридный маршрут должен содержать обязательное очное мероприятие")
                }
            }
            if (format == TrainingFormat.offline && required.none { it.type == TrainingStageType.offline_event }) {
                badRequest("Офлайн-маршрут должен содержать обязательное очное мероприятие")
            }
        }
    }

    private fun resolveStageFormats(
        stage: CreateTrainingStageRequest,
        programFormats: Set<TrainingFormat>,
    ): Set<TrainingFormat> {
        if (stage.applicableFormats.isNotEmpty()) return stage.applicableFormats
        return when (stage.type) {
            TrainingStageType.material, TrainingStageType.online_course ->
                programFormats.intersect(setOf(TrainingFormat.online, TrainingFormat.hybrid))
            TrainingStageType.offline_event ->
                programFormats.intersect(setOf(TrainingFormat.hybrid, TrainingFormat.offline))
            else -> programFormats
        }
    }

    private fun createAssignmentStages(assignment: TrainingAssignmentEntity, version: TrainingProgramVersionEntity) {
        val applicable = programStageRepository.findAllByVersionIdOrderByOrderNoAsc(version.id)
            .filter { assignment.format in it.applicableFormats }
        if (applicable.none { it.required }) badRequest("Для выбранного формата нет обязательных этапов")
        applicable.forEachIndexed { index, stage ->
            assignmentStageRepository.save(
                TrainingAssignmentStageEntity(
                    assignmentId = assignment.id,
                    programStageId = stage.id,
                ).also { it.status = if (index == 0) TrainingStageStatus.available else TrainingStageStatus.locked },
            )
        }
    }

    private fun attachEvent(assignment: TrainingAssignmentEntity, event: OfflineEventEntity, pharmacistId: String) {
        val lockedEvent = eventRepository.findByIdForUpdate(event.id)
            ?: throw AppException(ErrorCode.NOT_FOUND, "Событие не найдено", HttpStatus.NOT_FOUND)
        if (lockedEvent.status in setOf(OfflineEventStatus.cancelled, OfflineEventStatus.completed, OfflineEventStatus.archived)) {
            conflict("На это событие нельзя зарегистрироваться")
        }
        if (lockedEvent.registrationDeadline?.isBefore(Instant.now()) == true) conflict("Регистрация на событие закрыта")
        val assignmentParticipant = participantRepository.findByAssignmentId(assignment.id)
        val occupied = participantRepository.countByEventIdAndStatusRawIn(
            event.id,
            activeParticipantStatusNames(),
        )
        val participantAtTarget = participantRepository.findByEventIdAndPharmacistId(event.id, pharmacistId)
        if (participantAtTarget != null && participantAtTarget.assignmentId != assignment.id) {
            conflict("Фармацевт уже зарегистрирован на это событие по другому назначению")
        }
        val alreadyOccupiesTarget = assignmentParticipant?.eventId == event.id &&
            assignmentParticipant.status in setOf(
                EventParticipantStatus.registered,
                EventParticipantStatus.confirmed,
                EventParticipantStatus.attended,
                EventParticipantStatus.late,
            )
        if (!alreadyOccupiesTarget && participantAtTarget == null && occupied >= lockedEvent.capacity) {
            conflict("На событии нет свободных мест")
        }
        assignment.eventId = event.id
        assignmentRepository.save(assignment)
        if (assignmentParticipant == null) {
            participantRepository.save(
                EventParticipantEntity(eventId = event.id, assignmentId = assignment.id, pharmacistId = pharmacistId),
            )
        } else if (assignmentParticipant.eventId != event.id || assignmentParticipant.status == EventParticipantStatus.cancelled) {
            assignmentParticipant.eventId = event.id
            assignmentParticipant.status = EventParticipantStatus.registered
            assignmentParticipant.registeredAt = Instant.now()
            assignmentParticipant.checkedInAt = null
            assignmentParticipant.checkMethod = null
            assignmentParticipant.trainerComment = null
            assignmentParticipant.score = null
            participantRepository.save(assignmentParticipant)
        }
        scheduleEventNotifications(assignment, lockedEvent)
    }

    private fun initialAssignmentStatus(
        startsAt: Instant?,
        format: TrainingFormat,
        event: OfflineEventEntity?,
    ): TrainingAssignmentStatus {
        if (startsAt?.isAfter(Instant.now()) == true) return TrainingAssignmentStatus.scheduled
        if (format != TrainingFormat.online && event == null) return TrainingAssignmentStatus.waiting_event_selection
        return TrainingAssignmentStatus.not_started
    }

    private fun unlockDependentStages(assignment: TrainingAssignmentEntity, completedKey: String) {
        val definitions = programStageRepository.findAllByVersionIdOrderByOrderNoAsc(assignment.programVersionId)
            .associateBy { it.id }
        val stages = assignmentStageRepository.findAllByAssignmentIdOrderByProgramStageIdAsc(assignment.id)
            .filter { stage -> assignment.format in (definitions[stage.programStageId]?.applicableFormats ?: emptySet()) }
            .sortedBy { definitions[it.programStageId]?.orderNo ?: Int.MAX_VALUE }
        stages.forEachIndexed { index, stage ->
            val definition = definitions[stage.programStageId] ?: return@forEachIndexed
            if (stage.status != TrainingStageStatus.locked) return@forEachIndexed
            val dependency = definition.unlockAfterKey?.let { dependencyKey ->
                stages.firstOrNull { candidate -> definitions[candidate.programStageId]?.stageKey == dependencyKey }
            }
            // A route stage can be absent because it does not apply to the assignment format.
            val explicitDependencySatisfied = definition.unlockAfterKey == null || dependency == null ||
                definition.unlockAfterKey == completedKey ||
                dependency.status in setOf(TrainingStageStatus.completed, TrainingStageStatus.skipped)
            val previousRequiredComplete = stages.take(index).all { previous ->
                definitions[previous.programStageId]?.required != true ||
                    previous.status in setOf(TrainingStageStatus.completed, TrainingStageStatus.skipped)
            }
            if (explicitDependencySatisfied && previousRequiredComplete) {
                stage.status = TrainingStageStatus.available
                assignmentStageRepository.save(stage)
            }
        }
    }

    private fun reconcileStageAvailability(assignment: TrainingAssignmentEntity) {
        val definitions = programStageRepository.findAllByVersionIdOrderByOrderNoAsc(assignment.programVersionId)
            .associateBy { it.id }
        val stages = assignmentStageRepository.findAllByAssignmentIdOrderByProgramStageIdAsc(assignment.id)
            .filter { assignment.format in (definitions[it.programStageId]?.applicableFormats ?: emptySet()) }
            .sortedBy { definitions[it.programStageId]?.orderNo ?: Int.MAX_VALUE }
        stages.forEachIndexed { index, stage ->
            if (stage.status == TrainingStageStatus.completed) return@forEachIndexed
            val definition = definitions.getValue(stage.programStageId)
            val dependency = definition.unlockAfterKey?.let { key ->
                stages.firstOrNull { definitions[it.programStageId]?.stageKey == key }
            }
            val dependencySatisfied = dependency == null ||
                dependency.status in setOf(TrainingStageStatus.completed, TrainingStageStatus.skipped)
            val previousRequiredComplete = stages.take(index).all { previous ->
                definitions[previous.programStageId]?.required != true ||
                    previous.status in setOf(TrainingStageStatus.completed, TrainingStageStatus.skipped)
            }
            stage.status = if (dependencySatisfied && previousRequiredComplete) {
                TrainingStageStatus.available
            } else {
                TrainingStageStatus.locked
            }
            assignmentStageRepository.save(stage)
        }
    }

    private fun completeOfflineStages(assignment: TrainingAssignmentEntity, score: Int?) {
        val definitions = programStageRepository.findAllByVersionIdOrderByOrderNoAsc(assignment.programVersionId)
            .associateBy { it.id }
        assignmentStageRepository.findAllByAssignmentIdOrderByProgramStageIdAsc(assignment.id)
            .filter {
                definitions[it.programStageId]?.type == TrainingStageType.offline_event &&
                    assignment.format in (definitions[it.programStageId]?.applicableFormats ?: emptySet())
            }
            .forEach { stage ->
                stage.status = TrainingStageStatus.completed
                stage.progressPct = 100
                stage.score = score
                stage.completedAt = Instant.now()
                assignmentStageRepository.save(stage)
                unlockDependentStages(assignment, definitions.getValue(stage.programStageId).stageKey)
            }
    }

    private fun recalculateAssignment(assignment: TrainingAssignmentEntity) {
        val lockedAssignment = assignmentRepository.findByIdForUpdate(assignment.id)
            ?: throw AppException(ErrorCode.NOT_FOUND, "Назначение не найдено", HttpStatus.NOT_FOUND)
        val definitions = programStageRepository.findAllByVersionIdOrderByOrderNoAsc(lockedAssignment.programVersionId)
            .associateBy { it.id }
        val stages = assignmentStageRepository.findAllByAssignmentIdOrderByProgramStageIdAsc(lockedAssignment.id)
            .filter { lockedAssignment.format in (definitions[it.programStageId]?.applicableFormats ?: emptySet()) }
        lockedAssignment.progressPct = if (stages.isEmpty()) 0 else stages.map { it.progressPct }.average().roundToInt()
        val requiredStages = stages.filter { definitions[it.programStageId]?.required == true }
        val allComplete = requiredStages.isNotEmpty() && requiredStages.all {
            it.status in setOf(TrainingStageStatus.completed, TrainingStageStatus.skipped)
        }
        if (allComplete) {
            completeAssignment(lockedAssignment, stages)
            return
        }
        if (lockedAssignment.status != TrainingAssignmentStatus.retake_required) {
            lockedAssignment.status = statusForNextStage(lockedAssignment)
        }
        assignmentRepository.save(lockedAssignment)
    }

    private fun statusForNextStage(assignment: TrainingAssignmentEntity): TrainingAssignmentStatus {
        val definitions = programStageRepository.findAllByVersionIdOrderByOrderNoAsc(assignment.programVersionId)
            .associateBy { it.id }
        val next = assignmentStageRepository.findAllByAssignmentIdOrderByProgramStageIdAsc(assignment.id)
            .filter { assignment.format in (definitions[it.programStageId]?.applicableFormats ?: emptySet()) }
            .filter { it.status !in setOf(TrainingStageStatus.completed, TrainingStageStatus.skipped) }
            .minByOrNull { definitions[it.programStageId]?.orderNo ?: Int.MAX_VALUE }
            ?.let { definitions[it.programStageId] }
        return when (next?.type) {
            TrainingStageType.online_course, TrainingStageType.material -> TrainingAssignmentStatus.waiting_online
            TrainingStageType.test -> TrainingAssignmentStatus.waiting_test
            TrainingStageType.ai_exam -> TrainingAssignmentStatus.waiting_exam
            TrainingStageType.offline_event -> if (assignment.eventId == null) {
                TrainingAssignmentStatus.waiting_event_selection
            } else {
                TrainingAssignmentStatus.waiting_offline
            }
            TrainingStageType.manual_review -> TrainingAssignmentStatus.waiting_review
            null -> TrainingAssignmentStatus.in_progress
        }
    }

    private fun completeAssignment(
        assignment: TrainingAssignmentEntity,
        stages: List<TrainingAssignmentStageEntity>,
    ) {
        if (assignment.status == TrainingAssignmentStatus.completed) return
        val now = Instant.now()
        assignment.status = TrainingAssignmentStatus.completed
        assignment.progressPct = 100
        assignment.score = stages.mapNotNull { it.score }.takeIf(List<Int>::isNotEmpty)?.average()?.roundToInt()
        assignment.completedAt = now
        assignmentRepository.save(assignment)

        val version = versionRepository.findById(assignment.programVersionId).orElseThrow()
        val program = loadProgram(version.programId)
        val pharmacist = loadPharmacist(assignment.pharmacistId)
        if (certificateRepository.findByAssignmentId(assignment.id) == null) {
            val certificate = TrainingCertificateEntity(
                certificateNumber = certificateNumber(assignment.id, now),
                assignmentId = assignment.id,
                pharmacistId = assignment.pharmacistId,
                programVersionId = version.id,
                issuedAt = now,
                score = assignment.score,
            ).also { it.format = assignment.format }
            certificateRepository.save(certificate)
        }
        if (version.completionBonus > 0 && rewardRepository.findByAssignmentId(assignment.id) == null) {
            rewardRepository.save(
                TrainingRewardEntity(
                    assignmentId = assignment.id,
                    pharmacistId = assignment.pharmacistId,
                    amount = version.completionBonus,
                    reason = "Завершение программы «${program.name}»",
                    issuedAt = now,
                ),
            )
            pharmacist.balance += version.completionBonus
            pharmacist.earned30d += version.completionBonus
        }
        pharmacist.coursesDone += 1
        pharmacistRepository.save(pharmacist)
        version.onlineCourseId?.let { courseId ->
            courseRepository.findById(courseId).ifPresent { course ->
                course.completed += 1
                courseRepository.save(course)
            }
        }
        createNotification(
            pharmacistId = pharmacist.id,
            assignmentId = assignment.id,
            eventType = "training_completed",
            payload = mapOf("program" to program.name, "bonus" to version.completionBonus),
        )
        audit("system", "system", "assignment_completed", "training_assignment", assignment.id.toString())
    }

    private fun programDto(program: TrainingProgramEntity): TrainingProgramDto {
        val version = loadCurrentVersion(program)
        val stages = programStageRepository.findAllByVersionIdOrderByOrderNoAsc(version.id)
        val managerName = program.managerId?.let { adminUserRepository.findById(it).orElse(null)?.name }
        return TrainingProgramDto(
            id = program.id,
            name = program.name,
            shortDescription = program.shortDescription,
            description = program.description,
            coverUrl = program.coverUrl,
            category = program.category,
            manufacturer = program.manufacturer,
            brand = program.brand,
            product = program.product,
            language = program.language,
            managerId = program.managerId,
            managerName = managerName,
            allowedFormats = version.allowedFormats,
            startsAt = program.startsAt,
            endsAt = program.endsAt,
            normativeDays = program.normativeDays,
            tags = program.tags,
            status = program.status,
            version = version.versionNo,
            versionId = version.id,
            onlineCourseId = version.onlineCourseId,
            passingScore = version.passingScore,
            maxAttempts = version.maxAttempts,
            completionBonus = version.completionBonus,
            stages = stages.map {
                TrainingProgramStageDto(
                    id = it.id,
                    key = it.stageKey,
                    type = it.type,
                    title = it.title,
                    order = it.orderNo,
                    required = it.required,
                    unlockAfterKey = it.unlockAfterKey,
                    deadlineDays = it.deadlineDays,
                    passingScore = it.passingScore,
                    maxAttempts = it.maxAttempts,
                    bonus = it.bonus,
                    manualReview = it.manualReview,
                    applicableFormats = it.applicableFormats,
                    contentUrl = it.contentUrl,
                )
            },
            assignments = assignmentRepository.countByProgramVersionId(version.id),
            createdAt = program.createdAt,
            updatedAt = program.updatedAt,
        )
    }

    private fun eventDto(event: OfflineEventEntity): OfflineEventDto {
        val version = versionRepository.findById(event.programVersionId).orElseThrow()
        val program = loadProgram(version.programId)
        val trainerName = event.trainerId?.let { adminUserRepository.findById(it).orElse(null)?.name }
        val occupied = participantRepository.countByEventIdAndStatusRawIn(
            event.id,
            activeParticipantStatusNames(),
        )
        return OfflineEventDto(
            id = event.id,
            programId = program.id,
            programVersionId = version.id,
            programName = program.name,
            title = event.title,
            eventType = event.eventType,
            startsAt = event.startsAt,
            endsAt = event.endsAt,
            timezone = event.timezone,
            region = event.region,
            city = event.city,
            address = event.address,
            mapUrl = event.mapUrl,
            trainerId = event.trainerId,
            trainerName = trainerName,
            organizer = event.organizer,
            capacity = event.capacity,
            occupied = occupied,
            registrationDeadline = event.registrationDeadline,
            status = event.status,
            materialsUrl = event.materialsUrl,
            comment = event.comment,
            createdAt = event.createdAt,
            updatedAt = event.updatedAt,
        )
    }

    private fun participantDto(row: EventParticipantEntity): EventParticipantDto {
        val pharmacist = loadPharmacist(row.pharmacistId)
        return EventParticipantDto(
            id = row.id,
            eventId = row.eventId,
            assignmentId = row.assignmentId,
            pharmacistId = row.pharmacistId,
            pharmacistName = pharmacist.name,
            pharmacyName = pharmacist.pharmacyName.orEmpty(),
            status = row.status,
            registeredAt = row.registeredAt,
            checkedInAt = row.checkedInAt,
            checkMethod = row.checkMethod,
            trainerComment = row.trainerComment,
            score = row.score,
        )
    }

    private fun mapAssignments(rows: List<TrainingAssignmentEntity>): List<TrainingAssignmentDto> {
        if (rows.isEmpty()) return emptyList()
        val versions = versionRepository.findAllById(rows.map { it.programVersionId }.distinct()).associateBy { it.id }
        val programs = programRepository.findAllById(versions.values.map { it.programId }.distinct()).associateBy { it.id }
        val pharmacists = pharmacistRepository.findAllById(rows.map { it.pharmacistId }.distinct()).associateBy { it.id }
        val events = eventRepository.findAllById(rows.mapNotNull { it.eventId }.distinct()).associateBy { it.id }
        val definitions = programStageRepository.findAllById(
            versions.keys.flatMap { versionId -> programStageRepository.findAllByVersionIdOrderByOrderNoAsc(versionId).map { it.id } },
        ).associateBy { it.id }
        val rowIds = rows.map { it.id }
        val assignmentStages = assignmentStageRepository.findAllByAssignmentIdIn(rowIds).groupBy { it.assignmentId }
        val certificates = certificateRepository.findAllByAssignmentIdIn(rowIds).associateBy { it.assignmentId }
        val rewards = rewardRepository.findAllByAssignmentIdIn(rowIds).associateBy { it.assignmentId }
        return rows.map { assignment ->
            val version = versions.getValue(assignment.programVersionId)
            val program = programs.getValue(version.programId)
            val pharmacist = pharmacists.getValue(assignment.pharmacistId)
            val event = assignment.eventId?.let(events::get)
            val stageDtos = assignmentStages[assignment.id].orEmpty()
                .filter { assignment.format in definitions.getValue(it.programStageId).applicableFormats }
                .sortedBy { definitions[it.programStageId]?.orderNo ?: Int.MAX_VALUE }
                .map { row ->
                    val definition = definitions.getValue(row.programStageId)
                    TrainingAssignmentStageDto(
                        id = row.id,
                        programStageId = row.programStageId,
                        key = definition.stageKey,
                        type = definition.type,
                        title = definition.title,
                        order = definition.orderNo,
                        required = definition.required,
                        status = row.status,
                        progressPct = row.progressPct,
                        score = row.score,
                        attemptsUsed = row.attemptsUsed,
                        maxAttempts = definition.maxAttempts,
                        passingScore = definition.passingScore,
                        contentUrl = definition.contentUrl,
                        startedAt = row.startedAt,
                        completedAt = row.completedAt,
                    )
                }
            TrainingAssignmentDto(
                id = assignment.id,
                programId = program.id,
                programVersionId = version.id,
                programVersion = version.versionNo,
                programName = program.name,
                programShortDescription = program.shortDescription,
                coverUrl = program.coverUrl,
                pharmacistId = pharmacist.id,
                pharmacistName = pharmacist.name,
                pharmacyName = pharmacist.pharmacyName.orEmpty(),
                city = pharmacist.city,
                format = assignment.format,
                status = effectiveStatus(assignment),
                priority = assignment.priority,
                required = assignment.required,
                event = event?.let(::eventSummary),
                startsAt = assignment.startsAt,
                dueAt = assignment.dueAt,
                progressPct = assignment.progressPct,
                score = assignment.score,
                startedAt = assignment.startedAt,
                completedAt = assignment.completedAt,
                stages = stageDtos,
                certificate = certificates[assignment.id]?.let(::certificateDto),
                reward = rewards[assignment.id]?.let(::rewardDto),
                createdAt = assignment.createdAt,
                updatedAt = assignment.updatedAt,
            )
        }
    }

    private fun eventSummary(event: OfflineEventEntity) = OfflineEventSummaryDto(
        id = event.id,
        title = event.title,
        startsAt = event.startsAt,
        endsAt = event.endsAt,
        timezone = event.timezone,
        city = event.city,
        address = event.address,
        status = event.status,
    )

    private fun certificateDto(row: TrainingCertificateEntity): TrainingCertificateDto {
        val pharmacist = loadPharmacist(row.pharmacistId)
        val version = versionRepository.findById(row.programVersionId).orElseThrow()
        val program = loadProgram(version.programId)
        return TrainingCertificateDto(
            id = row.id,
            number = row.certificateNumber,
            assignmentId = row.assignmentId,
            pharmacistId = row.pharmacistId,
            pharmacistName = pharmacist.name,
            programName = program.name,
            format = row.format,
            issuedAt = row.issuedAt,
            expiresAt = row.expiresAt,
            score = row.score,
            signerName = row.signerName,
            status = effectiveCertificateStatus(row),
            verificationToken = row.qrToken,
            pdfUrl = row.pdfUrl ?: "/api/public/training/certificates/${row.qrToken}/pdf",
        )
    }

    private fun effectiveCertificateStatus(row: TrainingCertificateEntity): kz.epharm.training.domain.CertificateStatus =
        if (row.status == kz.epharm.training.domain.CertificateStatus.valid && row.expiresAt?.isBefore(Instant.now()) == true) {
            kz.epharm.training.domain.CertificateStatus.expired
        } else {
            row.status
        }

    private fun rewardDto(row: TrainingRewardEntity) = TrainingRewardDto(
        id = row.id,
        assignmentId = row.assignmentId,
        amount = row.amount,
        reason = row.reason,
        status = row.status.name,
        issuedAt = row.issuedAt,
    )

    private fun preferenceDto(row: PharmacistTrainingPreferenceEntity): TrainingPreferenceDto {
        val pharmacist = loadPharmacist(row.pharmacistId)
        val changedByName = adminUserRepository.findById(row.changedBy).orElse(null)?.name ?: "Удалённый пользователь"
        return TrainingPreferenceDto(
            id = row.id,
            pharmacistId = row.pharmacistId,
            pharmacistName = pharmacist.name,
            defaultFormat = row.defaultFormat,
            changedBy = row.changedBy,
            changedByName = changedByName,
            reason = row.reason,
            validFrom = row.validFrom,
            validTo = row.validTo,
            current = row.validTo == null,
        )
    }

    private fun notificationDto(row: TrainingNotificationEntity): TrainingNotificationDto {
        val payload = objectMapper.readTree(row.payloadJson)
        val program = payload.path("program").asText("")
        val event = payload.path("event").asText("")
        val bonus = payload.path("bonus").asLong(0)
        val (title, message) = when (row.eventType) {
            "training_assigned" -> "Назначено обучение" to program
            "training_deadline_72h" -> "До срока обучения 3 дня" to program
            "training_deadline_24h" -> "До срока обучения 1 день" to program
            "training_event_selected" -> "Вы выбрали мероприятие" to event
            "training_event_24h" -> "Мероприятие начнётся завтра" to event
            "training_event_2h" -> "Мероприятие начнётся через 2 часа" to event
            "training_event_changed" -> "Мероприятие перенесено" to event
            "training_event_cancelled" -> "Мероприятие отменено" to event
            "training_attendance_confirmed" -> "Посещение подтверждено" to event
            "training_completed" -> "Обучение завершено" to buildString {
                append(program)
                if (bonus > 0) append(" · начислено $bonus ₸")
            }
            else -> "Обучение" to (program.ifBlank { event.ifBlank { "Есть обновление по обучению" } })
        }
        return TrainingNotificationDto(
            id = row.id,
            eventType = row.eventType,
            title = title,
            message = message,
            assignmentId = row.assignmentId,
            eventId = row.eventId,
            read = row.status == "sent",
            scheduledAt = row.scheduledAt,
            readAt = row.sentAt,
        )
    }

    private fun assessmentResultDto(row: TrainingAssessmentResultEntity): TrainingAssessmentResultDto =
        TrainingAssessmentResultDto(
            id = row.id,
            assignmentId = row.assignmentId,
            assignmentStageId = row.assignmentStageId,
            sourceType = row.sourceType,
            attempt = row.attemptNo,
            score = row.score,
            passed = row.passed,
            feedback = row.feedback,
            competencyScores = objectMapper.readValue(row.competencyJson, Map::class.java)
                .entries.associate { (key, value) -> key.toString() to (value as Number).toInt() },
            recordedBy = row.recordedBy,
            recordedByName = adminUserRepository.findById(row.recordedBy).orElse(null)?.name ?: "Удалённый пользователь",
            recordedAt = row.recordedAt,
        )

    // Access scope and persistence helpers

    private fun scopedAssignments(principal: AdminPrincipal, rows: List<TrainingAssignmentEntity>): List<TrainingAssignmentEntity> {
        return when (principal.role) {
            AdminRole.REGIONAL_MANAGER -> {
                val regions = regionsFor(principal.userId).toSet()
                val visiblePharmacists = pharmacistRepository.findAllById(rows.map { it.pharmacistId }.distinct())
                    .filter { it.city in regions }.map { it.id }.toSet()
                rows.filter { it.pharmacistId in visiblePharmacists }
            }
            AdminRole.TRAINER -> {
                val ownEvents = eventRepository.findAllByTrainerIdOrderByStartsAtDesc(principal.userId).map { it.id }.toSet()
                rows.filter { it.eventId in ownEvents }
            }
            else -> rows
        }
    }

    private fun scopedEvents(principal: AdminPrincipal, rows: List<OfflineEventEntity>): List<OfflineEventEntity> = when (principal.role) {
        AdminRole.REGIONAL_MANAGER -> rows.filter { it.region in regionsFor(principal.userId) }
        AdminRole.TRAINER -> rows.filter { it.trainerId == principal.userId }
        else -> rows
    }

    private fun ensurePharmacistVisible(pharmacist: PharmacistEntity, principal: AdminPrincipal) {
        if (principal.role == AdminRole.REGIONAL_MANAGER && pharmacist.city !in regionsFor(principal.userId)) {
            forbidden("Фармацевт находится вне закреплённых регионов")
        }
        if (principal.role == AdminRole.TRAINER) forbidden("Тренер не может назначать программы")
    }

    private fun pharmacistVisible(pharmacist: PharmacistEntity, principal: AdminPrincipal): Boolean = when (principal.role) {
        AdminRole.REGIONAL_MANAGER -> pharmacist.city in regionsFor(principal.userId)
        AdminRole.TRAINER -> false
        else -> true
    }

    private fun ensureAssignmentVisible(assignment: TrainingAssignmentEntity, principal: AdminPrincipal) {
        if (assignment !in scopedAssignments(principal, listOf(assignment))) {
            forbidden("Назначение находится вне вашей области доступа")
        }
    }

    private fun ensureEventVisible(event: OfflineEventEntity, principal: AdminPrincipal) {
        if (event !in scopedEvents(principal, listOf(event))) forbidden("Событие находится вне вашей области доступа")
    }

    private fun eventAcceptsSelfRegistration(
        event: OfflineEventEntity,
        assignment: TrainingAssignmentEntity,
        pharmacist: PharmacistEntity,
        now: Instant,
    ): Boolean {
        if (event.programVersionId != assignment.programVersionId) return false
        if (event.status !in setOf(OfflineEventStatus.registration, OfflineEventStatus.scheduled)) return false
        if (event.registrationDeadline?.isBefore(now) == true || event.startsAt.isBefore(now)) return false
        if (assignment.startsAt?.let { event.startsAt.isBefore(it) } == true) return false
        if (assignment.dueAt?.let { event.endsAt.isAfter(it) } == true) return false
        val locationMatches = (event.city.isBlank() && event.region.isBlank()) ||
            event.city.equals(pharmacist.city, ignoreCase = true) ||
            event.region.equals(pharmacist.city, ignoreCase = true)
        if (!locationMatches) return false
        val occupied = participantRepository.countByEventIdAndStatusRawIn(
            event.id,
            activeParticipantStatusNames(),
        )
        val existing = participantRepository.findByEventIdAndPharmacistId(event.id, pharmacist.id)
        return existing != null || occupied < event.capacity
    }

    private fun regionsFor(adminUserId: UUID): List<String> = jdbcTemplate.queryForList(
        "SELECT region FROM admin_training_regions WHERE admin_user_id = ? ORDER BY region",
        String::class.java,
        adminUserId,
    )

    private fun activeParticipantStatusNames(): List<String> = listOf(
        EventParticipantStatus.registered.name,
        EventParticipantStatus.confirmed.name,
        EventParticipantStatus.attended.name,
        EventParticipantStatus.late.name,
    )

    private fun createNotification(
        pharmacistId: String,
        assignmentId: UUID? = null,
        eventId: UUID? = null,
        eventType: String,
        payload: Any,
        scheduledAt: Instant? = null,
    ) {
        val now = Instant.now()
        val key = listOf(eventType, pharmacistId, assignmentId, eventId, scheduledAt?.epochSecond ?: "immediate")
            .joinToString(":")
        if (notificationRepository.existsById(UUID.nameUUIDFromBytes(key.toByteArray()))) return
        notificationRepository.save(
            TrainingNotificationEntity(
                id = UUID.nameUUIDFromBytes(key.toByteArray()),
                pharmacistId = pharmacistId,
                assignmentId = assignmentId,
                eventId = eventId,
                eventType = eventType,
                payloadJson = objectMapper.writeValueAsString(payload),
                scheduledAt = scheduledAt ?: now,
                idempotencyKey = key,
            ),
        )
    }

    private fun scheduleDeadlineNotifications(
        assignment: TrainingAssignmentEntity,
        programName: String,
    ) {
        val now = Instant.now()
        listOf(72L to "training_deadline_72h", 24L to "training_deadline_24h").forEach { (hours, type) ->
            val scheduledAt = assignment.dueAt?.minusSeconds(hours * 3_600L) ?: return@forEach
            if (scheduledAt.isAfter(now)) {
                createNotification(
                    pharmacistId = assignment.pharmacistId,
                    assignmentId = assignment.id,
                    eventType = type,
                    payload = mapOf("program" to programName, "dueAt" to assignment.dueAt),
                    scheduledAt = scheduledAt,
                )
            }
        }
    }

    private fun scheduleEventNotifications(
        assignment: TrainingAssignmentEntity,
        event: OfflineEventEntity,
    ) {
        val now = Instant.now()
        listOf(24L to "training_event_24h", 2L to "training_event_2h").forEach { (hours, type) ->
            val scheduledAt = event.startsAt.minusSeconds(hours * 3_600L)
            if (scheduledAt.isAfter(now)) {
                createNotification(
                    pharmacistId = assignment.pharmacistId,
                    assignmentId = assignment.id,
                    eventId = event.id,
                    eventType = type,
                    payload = mapOf("event" to event.title, "startsAt" to event.startsAt),
                    scheduledAt = scheduledAt,
                )
            }
        }
    }

    private fun audit(
        actorId: String,
        actorType: String,
        action: String,
        entityType: String,
        entityId: String,
        details: Any = emptyMap<String, Any?>(),
    ) {
        auditRepository.save(
            TrainingAuditLogEntity(
                actorId = actorId,
                actorType = actorType,
                action = action,
                entityType = entityType,
                entityId = entityId,
                detailsJson = objectMapper.writeValueAsString(details),
            ),
        )
    }

    private fun certificateNumber(id: UUID, now: Instant): String {
        val year = now.atZone(ZoneOffset.UTC).year
        val suffix = id.toString().replace("-", "").take(10).uppercase()
        return "EPH-$year-$suffix"
    }

    private fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun effectiveStatus(row: TrainingAssignmentEntity): TrainingAssignmentStatus {
        if (row.status !in setOf(TrainingAssignmentStatus.completed, TrainingAssignmentStatus.cancelled, TrainingAssignmentStatus.paused) &&
            row.dueAt?.isBefore(Instant.now()) == true
        ) return TrainingAssignmentStatus.overdue
        return row.status
    }

    private fun loadProgram(id: UUID): TrainingProgramEntity = programRepository.findById(id).orElseThrow {
        AppException(ErrorCode.NOT_FOUND, "Программа обучения не найдена", HttpStatus.NOT_FOUND)
    }

    private fun loadCurrentVersion(program: TrainingProgramEntity): TrainingProgramVersionEntity =
        versionRepository.findByProgramIdAndVersionNo(program.id, program.currentVersion)
            ?: throw AppException(ErrorCode.INTERNAL, "Версия программы не найдена", HttpStatus.INTERNAL_SERVER_ERROR)

    private fun loadEvent(id: UUID): OfflineEventEntity = eventRepository.findById(id).orElseThrow {
        AppException(ErrorCode.NOT_FOUND, "Событие не найдено", HttpStatus.NOT_FOUND)
    }

    private fun loadAssignment(id: UUID): TrainingAssignmentEntity = assignmentRepository.findById(id).orElseThrow {
        AppException(ErrorCode.NOT_FOUND, "Назначение не найдено", HttpStatus.NOT_FOUND)
    }

    private fun loadOwnedAssignment(pharmacistId: String, id: UUID): TrainingAssignmentEntity {
        val assignment = loadAssignment(id)
        if (assignment.pharmacistId != pharmacistId) forbidden("Назначение принадлежит другому фармацевту")
        return assignment
    }

    private fun loadPharmacist(id: String): PharmacistEntity = pharmacistRepository.findById(id).orElseThrow {
        AppException(ErrorCode.NOT_FOUND, "Фармацевт не найден", HttpStatus.NOT_FOUND)
    }

    private fun ensureAdminExists(id: UUID) {
        if (!adminUserRepository.existsById(id)) badRequest("Пользователь $id не найден")
    }

    private fun badRequest(message: String): Nothing =
        throw AppException(ErrorCode.VALIDATION_FAILED, message, HttpStatus.BAD_REQUEST)

    private fun conflict(message: String): Nothing =
        throw AppException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT)

    private fun forbidden(message: String): Nothing =
        throw AppException(ErrorCode.FORBIDDEN, message, HttpStatus.FORBIDDEN)
}
