package kz.epharm.ai_exam.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

enum class ExamQuestionKind { factual, comparative, scenario }

@Entity
@Table(name = "exam_questions")
class ExamQuestionEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "prompt", nullable = false)
    var prompt: String = "",

    @Column(name = "kind", nullable = false, length = 32)
    var kindRaw: String = ExamQuestionKind.factual.name,

    @Column(name = "category", nullable = false)
    var category: String = "",

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "keywords", nullable = false, columnDefinition = "jsonb")
    var keywords: List<String> = emptyList(),

    @Column(name = "difficulty", nullable = false)
    var difficulty: Int = 1,

    @Column(name = "created_by", nullable = false, length = 64)
    var createdBy: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var kind: ExamQuestionKind
        get() = ExamQuestionKind.valueOf(kindRaw)
        set(value) { kindRaw = value.name }

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        if (createdAt == Instant.EPOCH) createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
