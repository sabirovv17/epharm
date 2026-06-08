package kz.epharm.ai_exam.service

import kz.epharm.ai_exam.dto.CreateExamQuestionRequest
import kz.epharm.ai_exam.dto.ExamQuestionDto
import kz.epharm.ai_exam.dto.UpdateExamQuestionRequest
import kz.epharm.ai_exam.entity.ExamQuestionEntity
import kz.epharm.ai_exam.entity.ExamQuestionKind
import kz.epharm.ai_exam.repository.ExamQuestionRepository
import kz.epharm.shared.error.AppException
import kz.epharm.shared.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ExamQuestionService(
    private val questionRepository: ExamQuestionRepository,
) {

    @Transactional(readOnly = true)
    fun list(kind: ExamQuestionKind? = null): List<ExamQuestionDto> {
        val rows = if (kind != null) {
            questionRepository.findAllByKindRawOrderByUpdatedAtDesc(kind.name)
        } else {
            questionRepository.findAllByOrderByUpdatedAtDesc()
        }
        return rows.map(ExamQuestionDto::of)
    }

    @Transactional
    fun create(req: CreateExamQuestionRequest, createdBy: String): ExamQuestionDto {
        val entity = ExamQuestionEntity(
            id = "q_${UUID.randomUUID().toString().substring(0, 8)}",
            prompt = req.prompt.trim(),
            category = req.category.trim(),
            keywords = req.keywords.map { it.trim() }.filter { it.isNotBlank() },
            difficulty = req.difficulty,
            createdBy = createdBy,
        ).also { it.kind = req.kind ?: ExamQuestionKind.factual }
        return ExamQuestionDto.of(questionRepository.save(entity))
    }

    @Transactional
    fun update(id: String, req: UpdateExamQuestionRequest): ExamQuestionDto {
        val entity = loadOrThrow(id)
        req.prompt?.let { entity.prompt = it.trim() }
        req.kind?.let { entity.kind = it }
        req.category?.let { entity.category = it.trim() }
        req.keywords?.let { kw -> entity.keywords = kw.map { it.trim() }.filter { it.isNotBlank() } }
        req.difficulty?.let { entity.difficulty = it }
        return ExamQuestionDto.of(questionRepository.save(entity))
    }

    @Transactional
    fun delete(id: String) {
        val entity = loadOrThrow(id)
        questionRepository.delete(entity)
    }

    private fun loadOrThrow(id: String): ExamQuestionEntity =
        questionRepository.findById(id).orElseThrow {
            AppException(ErrorCode.NOT_FOUND, "Question $id not found", HttpStatus.NOT_FOUND)
        }
}
