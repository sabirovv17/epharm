package kz.epharm.lms.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "course_lesson_attachments")
class CourseLessonAttachmentEntity(
    @Id
    @Column(nullable = false, length = 64)
    var id: String = "",

    @Column(name = "lesson_id", nullable = false, length = 64)
    var lessonId: String = "",

    @Column(nullable = false, length = 255)
    var title: String = "",

    @Column(name = "file_name", nullable = false, length = 255)
    var fileName: String = "",

    @Column(name = "content_type", nullable = false, length = 128)
    var contentType: String = "application/octet-stream",

    @Column(name = "media_url", nullable = false, length = 1000)
    var mediaUrl: String = "",

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),
) {
    @PrePersist
    fun onCreate() {
        createdAt = Instant.now()
    }
}
