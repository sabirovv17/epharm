package kz.epharm.ai_exam.controller

import jakarta.validation.Valid
import kz.epharm.ai_exam.dto.CreateExamQuestionRequest
import kz.epharm.ai_exam.dto.ExamQuestionDto
import kz.epharm.ai_exam.dto.UpdateExamQuestionRequest
import kz.epharm.ai_exam.entity.ExamQuestionKind
import kz.epharm.ai_exam.service.ExamQuestionService
import kz.epharm.auth.security.AdminPrincipal
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// AI-экзаменация (ТЗ §3.4). Этап 3.6: банк вопросов (list + create).
// Сессии/результаты/сертификаты — Этап 4.
@RestController
@RequestMapping("/api/admin/ai-exam/questions")
class AiExamController(private val questionService: ExamQuestionService) {

    @GetMapping
    fun list(@RequestParam(required = false) kind: ExamQuestionKind?): List<ExamQuestionDto> =
        questionService.list(kind = kind)

    @PostMapping
    fun create(
        @Valid @RequestBody req: CreateExamQuestionRequest,
        @AuthenticationPrincipal principal: AdminPrincipal?,
    ): ExamQuestionDto = questionService.create(req, createdBy = requireUserId(principal))

    @PatchMapping("/{id}")
    fun update(@PathVariable id: String, @Valid @RequestBody req: UpdateExamQuestionRequest): ExamQuestionDto =
        questionService.update(id, req)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) = questionService.delete(id)

    private fun requireUserId(principal: AdminPrincipal?): String =
        principal?.userId?.toString()
            ?: throw AppException(ErrorCode.UNAUTHORIZED, "Not authenticated", HttpStatus.UNAUTHORIZED)
}
