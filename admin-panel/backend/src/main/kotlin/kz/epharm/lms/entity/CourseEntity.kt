package kz.epharm.lms.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

/**
 * Статус курса: draft → published ⇄ (archived).
 * Archived нельзя редактировать через PATCH (урок Bug G), но в Этапе 3.6
 * LMS — list + create, апдейт-флоу придёт позже.
 */
enum class CourseStatus { published, draft, archived }

@Entity
@Table(name = "courses")
class CourseEntity(
    @Id
    @Column(name = "id", nullable = false, length = 64)
    var id: String = "",

    @Column(name = "title", nullable = false)
    var title: String = "",

    @Column(name = "status", nullable = false, length = 32)
    var statusRaw: String = CourseStatus.draft.name,

    @Column(name = "category", nullable = false)
    var category: String = "",

    @Column(name = "lessons", nullable = false)
    var lessons: Int = 0,

    @Column(name = "duration_min", nullable = false)
    var durationMin: Int = 0,

    @Column(name = "enrolled", nullable = false)
    var enrolled: Int = 0,

    @Column(name = "completed", nullable = false)
    var completed: Int = 0,

    @Column(name = "bonus", nullable = false)
    var bonus: Int = 0,

    @Column(name = "created_by", nullable = false, length = 64)
    var createdBy: String = "",

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var status: CourseStatus
        get() = CourseStatus.valueOf(statusRaw)
        set(value) { statusRaw = value.name }

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
