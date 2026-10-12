package kz.epharm.lms.repository

import jakarta.persistence.LockModeType
import kz.epharm.lms.entity.CourseEntity
import kz.epharm.lms.entity.CourseLessonEntity
import kz.epharm.lms.entity.CourseLessonAttachmentEntity
import kz.epharm.lms.entity.CourseLiveMessageEntity
import kz.epharm.lms.entity.CourseLivePollVoteEntity
import kz.epharm.lms.entity.CourseLiveReactionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface CourseRepository : JpaRepository<CourseEntity, String> {
    fun findAllByOrderByUpdatedAtDesc(): List<CourseEntity>
    fun findAllByStatusRawOrderByUpdatedAtDesc(statusRaw: String): List<CourseEntity>
}

@Repository
interface CourseLessonRepository : JpaRepository<CourseLessonEntity, String> {
    fun findAllByCourseIdOrderByOrderAscCreatedAtAsc(courseId: String): List<CourseLessonEntity>
    fun findAllByCourseIdInOrderByCourseIdAscOrderAscCreatedAtAsc(courseIds: Collection<String>): List<CourseLessonEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select lesson from CourseLessonEntity lesson where lesson.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): CourseLessonEntity?
}

@Repository
interface CourseLessonAttachmentRepository : JpaRepository<CourseLessonAttachmentEntity, String> {
    fun findAllByLessonIdOrderByCreatedAtAsc(lessonId: String): List<CourseLessonAttachmentEntity>
    fun findAllByLessonIdInOrderByLessonIdAscCreatedAtAsc(lessonIds: Collection<String>): List<CourseLessonAttachmentEntity>
}

@Repository
interface CourseLiveMessageRepository : JpaRepository<CourseLiveMessageEntity, UUID> {
    fun findTop200ByLessonIdAndHiddenFalseOrderByCreatedAtDescIdDesc(lessonId: String): List<CourseLiveMessageEntity>
    fun existsByLessonId(lessonId: String): Boolean
}

@Repository
interface CourseLivePollVoteRepository : JpaRepository<CourseLivePollVoteEntity, UUID> {
    fun findAllByLessonId(lessonId: String): List<CourseLivePollVoteEntity>
    fun existsByLessonId(lessonId: String): Boolean

    @Modifying
    @Query(
        value = """
            INSERT INTO course_live_poll_votes (id, lesson_id, poll_id, voter_id, option_id, created_at)
            VALUES (:id, :lessonId, :pollId, :voterId, :optionId, NOW())
            ON CONFLICT (lesson_id, poll_id, voter_id)
            DO UPDATE SET option_id = EXCLUDED.option_id, created_at = EXCLUDED.created_at
        """,
        nativeQuery = true,
    )
    fun upsertVote(
        @Param("id") id: UUID,
        @Param("lessonId") lessonId: String,
        @Param("pollId") pollId: String,
        @Param("voterId") voterId: String,
        @Param("optionId") optionId: String,
    ): Int
}

@Repository
interface CourseLiveReactionRepository : JpaRepository<CourseLiveReactionEntity, UUID> {
    fun findAllByLessonIdAndCreatedAtAfter(lessonId: String, createdAt: Instant): List<CourseLiveReactionEntity>
    fun existsByLessonId(lessonId: String): Boolean
}
