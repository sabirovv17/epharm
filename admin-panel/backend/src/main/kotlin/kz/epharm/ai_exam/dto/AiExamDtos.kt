package kz.epharm.ai_exam.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import kz.epharm.ai_exam.entity.ExamQuestionEntity
import kz.epharm.ai_exam.entity.ExamQuestionKind
import java.time.Instant

data class ExamQuestionDto(
    val id: String,
    val prompt: String,
    val kind: ExamQuestionKind,
    val category: String,
    val keywords: List<String>,
    val difficulty: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(e: ExamQuestionEntity): ExamQuestionDto = ExamQuestionDto(
            id = e.id,
            prompt = e.prompt,
            kind = e.kind,
            category = e.category,
            keywords = e.keywords,
            difficulty = e.difficulty,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
        )
    }
}

data class CreateExamQuestionRequest(
    @field:NotBlank
    val prompt: String,
    val kind: ExamQuestionKind? = ExamQuestionKind.factual,
    val category: String = "",
    val keywords: List<String> = emptyList(),
    @field:Min(1)
    @field:Max(5)
    val difficulty: Int = 1,
)

/**
 * Partial-update (PATCH). keywords при наличии перезаписывают список целиком
 * (sanitize в сервисе — trim + drop blank, урок Bug C).
 */
data class UpdateExamQuestionRequest(
    val prompt: String? = null,
    val kind: ExamQuestionKind? = null,
    val category: String? = null,
    val keywords: List<String>? = null,
    @field:Min(1)
    @field:Max(5)
    val difficulty: Int? = null,
)
