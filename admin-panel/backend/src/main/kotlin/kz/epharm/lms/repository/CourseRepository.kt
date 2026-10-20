package kz.epharm.lms.repository

import kz.epharm.lms.entity.CourseEntity
import kz.epharm.lms.entity.CourseLessonEntity
import kz.epharm.lms.entity.CourseLessonAttachmentEntity
import kz.epharm.lms.entity.CourseLiveMessageEntity
import kz.epharm.lms.entity.CourseLivePollVoteEntity
import kz.epharm.lms.entity.CourseLiveReactionEntity
import org.springframework.data.jpa.repository.JpaRepository
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
}

@Repository
interface CourseLessonAttachmentRepository : JpaRepository<CourseLessonAttachmentEntity, String> {
    fun findAllByLessonIdOrderByCreatedAtAsc(lessonId: String): List<CourseLessonAttachmentEntity>
    fun findAllByLessonIdInOrderByLessonIdAscCreatedAtAsc(lessonIds: Collection<String>): List<CourseLessonAttachmentEntity>
}

@Repository
interface CourseLiveMessageRepository : JpaRepository<CourseLiveMessageEntity, UUID> {
    fun findTop200ByLessonIdAndHiddenFalseOrderByCreatedAtAsc(lessonId: String): List<CourseLiveMessageEntity>
}

@Repository
interface CourseLivePollVoteRepository : JpaRepository<CourseLivePollVoteEntity, UUID> {
    fun findAllByLessonId(lessonId: String): List<CourseLivePollVoteEntity>
    fun findByLessonIdAndPollIdAndVoterId(lessonId: String, pollId: String, voterId: String): CourseLivePollVoteEntity?
}

@Repository
interface CourseLiveReactionRepository : JpaRepository<CourseLiveReactionEntity, UUID> {
    fun findAllByLessonIdAndCreatedAtAfter(lessonId: String, createdAt: Instant): List<CourseLiveReactionEntity>
}
