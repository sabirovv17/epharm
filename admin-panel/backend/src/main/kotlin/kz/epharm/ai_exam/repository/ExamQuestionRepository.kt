package kz.epharm.ai_exam.repository

import kz.epharm.ai_exam.entity.ExamQuestionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ExamQuestionRepository : JpaRepository<ExamQuestionEntity, String> {
    fun findAllByOrderByUpdatedAtDesc(): List<ExamQuestionEntity>
    fun findAllByKindRawOrderByUpdatedAtDesc(kindRaw: String): List<ExamQuestionEntity>
}
