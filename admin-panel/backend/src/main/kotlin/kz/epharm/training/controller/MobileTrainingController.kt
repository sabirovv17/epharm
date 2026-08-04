package kz.epharm.training.controller

import jakarta.validation.Valid
import kz.epharm.mobile.auth.security.PharmacistPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import kz.epharm.training.dto.MobileTrainingOverviewDto
import kz.epharm.training.dto.OfflineEventDto
import kz.epharm.training.dto.StageProgressRequest
import kz.epharm.training.dto.TrainingAssignmentDto
import kz.epharm.training.dto.TrainingNotificationDto
import kz.epharm.training.service.TrainingService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/mobile/training")
class MobileTrainingController(
    private val trainingService: TrainingService,
) {
    @GetMapping
    fun overview(@AuthenticationPrincipal principal: PharmacistPrincipal?): MobileTrainingOverviewDto =
        trainingService.mobileOverview(requirePrincipal(principal).pharmacistId)

    @GetMapping("/assignments/{id}")
    fun assignment(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: PharmacistPrincipal?,
    ): TrainingAssignmentDto = trainingService.mobileAssignment(requirePrincipal(principal).pharmacistId, id)

    @GetMapping("/assignments/{id}/events")
    fun availableEvents(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: PharmacistPrincipal?,
    ): List<OfflineEventDto> = trainingService.availableEvents(
        requirePrincipal(principal).pharmacistId,
        id,
    )

    @PostMapping("/assignments/{assignmentId}/events/{eventId}")
    fun selectEvent(
        @PathVariable assignmentId: UUID,
        @PathVariable eventId: UUID,
        @AuthenticationPrincipal principal: PharmacistPrincipal?,
    ): TrainingAssignmentDto = trainingService.selectEvent(
        requirePrincipal(principal).pharmacistId,
        assignmentId,
        eventId,
    )

    @PostMapping("/assignments/{id}/start")
    fun start(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: PharmacistPrincipal?,
    ): TrainingAssignmentDto = trainingService.startAssignment(requirePrincipal(principal).pharmacistId, id)

    @PatchMapping("/assignments/{assignmentId}/stages/{stageId}")
    fun updateStage(
        @PathVariable assignmentId: UUID,
        @PathVariable stageId: UUID,
        @Valid @RequestBody req: StageProgressRequest,
        @AuthenticationPrincipal principal: PharmacistPrincipal?,
    ): TrainingAssignmentDto = trainingService.updateStageProgress(
        requirePrincipal(principal).pharmacistId,
        assignmentId,
        stageId,
        req,
    )

    @PostMapping("/events/check-in/{qrToken}")
    fun checkIn(
        @PathVariable qrToken: UUID,
        @AuthenticationPrincipal principal: PharmacistPrincipal?,
    ): TrainingAssignmentDto = trainingService.checkInEvent(requirePrincipal(principal).pharmacistId, qrToken)

    @PatchMapping("/notifications/{notificationId}/read")
    fun markNotificationRead(
        @PathVariable notificationId: UUID,
        @AuthenticationPrincipal principal: PharmacistPrincipal?,
    ): TrainingNotificationDto = trainingService.markNotificationRead(
        requirePrincipal(principal).pharmacistId,
        notificationId,
    )

    private fun requirePrincipal(principal: PharmacistPrincipal?): PharmacistPrincipal = principal
        ?: throw AppException(ErrorCode.UNAUTHORIZED, "Не авторизован", HttpStatus.UNAUTHORIZED)
}
