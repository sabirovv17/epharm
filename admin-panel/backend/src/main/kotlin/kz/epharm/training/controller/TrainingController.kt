package kz.epharm.training.controller

import jakarta.validation.Valid
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.pharmacists.dto.PharmacistDto
import kz.epharm.pharmacists.service.PharmacistService
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.training.domain.TrainingProgramStatus
import kz.epharm.training.domain.TrainingAssignmentStatus
import kz.epharm.training.domain.TrainingFormat
import kz.epharm.training.dto.ChangeTrainingPreferenceRequest
import kz.epharm.training.dto.ChangeAssignmentFormatRequest
import kz.epharm.training.dto.CreateOfflineEventRequest
import kz.epharm.training.dto.CreateTrainingAssignmentsRequest
import kz.epharm.training.dto.CreateTrainingProgramRequest
import kz.epharm.training.dto.EventParticipantDto
import kz.epharm.training.dto.MarkAttendanceRequest
import kz.epharm.training.dto.MassChangeTrainingPreferencesRequest
import kz.epharm.training.dto.MassAssignmentResultDto
import kz.epharm.training.dto.OfflineEventDto
import kz.epharm.training.dto.PharmacistTrainingProfileDto
import kz.epharm.training.dto.RecordAssessmentResultRequest
import kz.epharm.training.dto.TrainingAssessmentResultDto
import kz.epharm.training.dto.TrainingAssignmentDto
import kz.epharm.training.dto.TrainingAssignmentFormatHistoryDto
import kz.epharm.training.dto.TrainingAssignmentPageDto
import kz.epharm.training.dto.TrainingCertificateDto
import kz.epharm.training.dto.TrainingDashboardDto
import kz.epharm.training.dto.TrainingEventQrDto
import kz.epharm.training.dto.TrainingPreferenceDto
import kz.epharm.training.dto.TrainingProgramDto
import kz.epharm.training.dto.UpdateTrainingProgramRequest
import kz.epharm.training.dto.UpdateOfflineEventRequest
import kz.epharm.training.service.TrainingService
import org.springframework.http.HttpStatus
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/admin/training")
class TrainingController(
    private val trainingService: TrainingService,
    private val pharmacistService: PharmacistService,
) {
    @GetMapping("/dashboard")
    fun dashboard(@AuthenticationPrincipal principal: AdminPrincipal?): TrainingDashboardDto =
        trainingService.dashboard(requirePrincipal(principal))

    @GetMapping("/programs")
    fun programs(@RequestParam(required = false) status: TrainingProgramStatus?): List<TrainingProgramDto> =
        trainingService.listPrograms(status)

    @GetMapping("/programs/{id}")
    fun program(@PathVariable id: UUID): TrainingProgramDto = trainingService.getProgram(id)

    /** Read-only roster for course/event assignment inside the training workspace. */
    @GetMapping("/pharmacists")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD','TRAINING_MANAGER','REGIONAL_MANAGER')")
    fun pharmacists(): List<PharmacistDto> = pharmacistService.list()

    @PostMapping("/programs")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER')")
    fun createProgram(
        @Valid @RequestBody req: CreateTrainingProgramRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): TrainingProgramDto = trainingService.createProgram(req, requirePrincipal(principal))

    @PatchMapping("/programs/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER')")
    fun updateProgram(
        @PathVariable id: UUID,
        @Valid @RequestBody req: UpdateTrainingProgramRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): TrainingProgramDto = trainingService.updateProgram(id, req, requirePrincipal(principal))

    @GetMapping("/events")
    fun events(@AuthenticationPrincipal principal: AdminPrincipal?): List<OfflineEventDto> =
        trainingService.listEvents(requirePrincipal(principal))

    @PostMapping("/events")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER','REGIONAL_MANAGER')")
    fun createEvent(
        @Valid @RequestBody req: CreateOfflineEventRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): OfflineEventDto = trainingService.createEvent(req, requirePrincipal(principal))

    @PatchMapping("/events/{eventId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER','REGIONAL_MANAGER')")
    fun updateEvent(
        @PathVariable eventId: UUID,
        @Valid @RequestBody req: UpdateOfflineEventRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): OfflineEventDto = trainingService.updateEvent(eventId, req, requirePrincipal(principal))

    @GetMapping("/events/{eventId}/participants")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD','TRAINING_MANAGER','REGIONAL_MANAGER','TRAINER')")
    fun participants(
        @PathVariable eventId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): List<EventParticipantDto> = trainingService.listParticipants(eventId, requirePrincipal(principal))

    @GetMapping("/events/{eventId}/qr")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD','TRAINING_MANAGER','REGIONAL_MANAGER','TRAINER')")
    fun eventQr(
        @PathVariable eventId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): TrainingEventQrDto = trainingService.eventQr(eventId, requirePrincipal(principal))

    @PatchMapping("/events/{eventId}/participants/{participantId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER','REGIONAL_MANAGER','TRAINER')")
    fun markAttendance(
        @PathVariable eventId: UUID,
        @PathVariable participantId: UUID,
        @Valid @RequestBody req: MarkAttendanceRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): EventParticipantDto = trainingService.markAttendance(
        eventId,
        participantId,
        req,
        requirePrincipal(principal),
    )

    @GetMapping("/assignments")
    fun assignments(
        @RequestParam(required = false) format: TrainingFormat?,
        @RequestParam(required = false) status: TrainingAssignmentStatus?,
        @RequestParam(required = false) programId: UUID?,
        @RequestParam(required = false, name = "q") query: String?,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): List<TrainingAssignmentDto> = trainingService.listAssignments(
        requirePrincipal(principal),
        format,
        status,
        programId,
        query,
    )

    @GetMapping("/assignments/page")
    fun assignmentPage(
        @RequestParam(required = false) format: TrainingFormat?,
        @RequestParam(required = false) status: TrainingAssignmentStatus?,
        @RequestParam(required = false) programId: UUID?,
        @RequestParam(required = false, name = "q") query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") size: Int,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): TrainingAssignmentPageDto = trainingService.listAssignmentsPage(
        requirePrincipal(principal),
        format,
        status,
        programId,
        query,
        page,
        size,
    )

    @GetMapping("/assignments/export.csv")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD','TRAINING_MANAGER','REGIONAL_MANAGER','TRAINER')")
    fun exportAssignments(
        @RequestParam(required = false) format: TrainingFormat?,
        @RequestParam(required = false) status: TrainingAssignmentStatus?,
        @RequestParam(required = false) programId: UUID?,
        @RequestParam(required = false, name = "q") query: String?,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): ResponseEntity<ByteArray> = ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=training-assignments.csv")
        .body(trainingService.exportAssignmentsCsv(requirePrincipal(principal), format, status, programId, query))

    @PostMapping("/assignments")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER','REGIONAL_MANAGER')")
    fun createAssignments(
        @Valid @RequestBody req: CreateTrainingAssignmentsRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): MassAssignmentResultDto = trainingService.createAssignments(req, requirePrincipal(principal))

    @PatchMapping("/assignments/{assignmentId}/format")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER','REGIONAL_MANAGER')")
    fun changeAssignmentFormat(
        @PathVariable assignmentId: UUID,
        @Valid @RequestBody req: ChangeAssignmentFormatRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): TrainingAssignmentDto = trainingService.changeAssignmentFormat(
        assignmentId,
        req,
        requirePrincipal(principal),
    )

    @GetMapping("/assignments/{assignmentId}/format-history")
    fun assignmentFormatHistory(
        @PathVariable assignmentId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): List<TrainingAssignmentFormatHistoryDto> = trainingService.assignmentFormatHistory(
        assignmentId,
        requirePrincipal(principal),
    )

    @PatchMapping("/assignments/{assignmentId}/stages/{stageId}/result")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER','REGIONAL_MANAGER','TRAINER')")
    fun recordAssessmentResult(
        @PathVariable assignmentId: UUID,
        @PathVariable stageId: UUID,
        @Valid @RequestBody req: RecordAssessmentResultRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): TrainingAssignmentDto = trainingService.recordAssessmentResult(
        assignmentId,
        stageId,
        req,
        requirePrincipal(principal),
    )

    @GetMapping("/assignments/{assignmentId}/results")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD','TRAINING_MANAGER','REGIONAL_MANAGER','TRAINER')")
    fun assessmentResults(
        @PathVariable assignmentId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): List<TrainingAssessmentResultDto> = trainingService.listAssessmentResults(
        assignmentId,
        requirePrincipal(principal),
    )

    @GetMapping("/preferences")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD','TRAINING_MANAGER','REGIONAL_MANAGER')")
    fun preferences(@AuthenticationPrincipal principal: AdminPrincipal?): List<TrainingPreferenceDto> =
        trainingService.listCurrentPreferences(requirePrincipal(principal))

    @GetMapping("/pharmacists/{pharmacistId}/preference-history")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','HQ_HEAD','TRAINING_MANAGER','REGIONAL_MANAGER')")
    fun preferenceHistory(
        @PathVariable pharmacistId: String,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): List<TrainingPreferenceDto> = trainingService.preferenceHistory(
        pharmacistId,
        requirePrincipal(principal),
    )

    @GetMapping("/pharmacists/{pharmacistId}/profile")
    fun pharmacistTrainingProfile(
        @PathVariable pharmacistId: String,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): PharmacistTrainingProfileDto = trainingService.pharmacistTrainingProfile(
        pharmacistId,
        requirePrincipal(principal),
    )

    @PatchMapping("/pharmacists/{pharmacistId}/preference")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER','REGIONAL_MANAGER')")
    fun changePreference(
        @PathVariable pharmacistId: String,
        @Valid @RequestBody req: ChangeTrainingPreferenceRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): TrainingPreferenceDto = trainingService.changePreference(
        pharmacistId,
        req,
        requirePrincipal(principal),
    )

    @PatchMapping("/pharmacists/preferences")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','TRAINING_MANAGER','REGIONAL_MANAGER')")
    fun changePreferences(
        @Valid @RequestBody req: MassChangeTrainingPreferencesRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): List<TrainingPreferenceDto> = trainingService.changePreferences(req, requirePrincipal(principal))

    @GetMapping("/certificates")
    fun certificates(@AuthenticationPrincipal principal: AdminPrincipal?): List<TrainingCertificateDto> =
        trainingService.listCertificates(requirePrincipal(principal))

    private fun requirePrincipal(principal: AdminPrincipal?): AdminPrincipal = principal
        ?: throw AppException(ErrorCode.UNAUTHORIZED, "Не авторизован", HttpStatus.UNAUTHORIZED)
}
