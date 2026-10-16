package kz.epharm.lms.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

enum class CourseLessonKind {
    text,
    video,
    pdf,
    presentation,
    image,
    audio,
    link,
    interactive,
    quiz,
    test,
    practice,
    assignment,
    live,
}

enum class CourseLiveProvider { youtube, vimeo, teams, zoom, custom }

enum class CourseLiveStatus { scheduled, live, ended }

data class CourseLivePollOption(
    val id: String = "",
    val label: String = "",
)

data class CourseLivePoll(
    val id: String = "",
    val prompt: String = "",
    val options: List<CourseLivePollOption> = emptyList(),
    val active: Boolean = false,
)

data class CourseQuizQuestion(
    val id: String = "",
    val prompt: String = "",
    val options: List<String> = emptyList(),
    val correctOption: Int = 0,
    val explanation: String = "",
)

@Entity
@Table(name = "course_lessons")
class CourseLessonEntity(
    @Id
    @Column(nullable = false, length = 64)
    var id: String = "",

    @Column(name = "course_id", nullable = false, length = 64)
    var courseId: String = "",

    @Column(nullable = false)
    var title: String = "",

    @Column(nullable = false, length = 1000)
    var description: String = "",

    @Column(nullable = false, columnDefinition = "TEXT")
    var content: String = "",

    @Column(name = "kind", nullable = false, length = 16)
    var kindRaw: String = CourseLessonKind.text.name,

    @Column(name = "video_url", length = 1000)
    var videoUrl: String? = null,

    @Column(name = "external_url", length = 2000)
    var externalUrl: String? = null,

    @Column(name = "live_provider", length = 24)
    var liveProviderRaw: String? = null,

    @Column(name = "live_status", length = 24)
    var liveStatusRaw: String? = null,

    @Column(name = "live_start_at")
    var liveStartAt: Instant? = null,

    @Column(name = "live_end_at")
    var liveEndAt: Instant? = null,

    @Column(name = "live_chat_enabled", nullable = false)
    var liveChatEnabled: Boolean = true,

    @Column(name = "live_qa_enabled", nullable = false)
    var liveQaEnabled: Boolean = true,

    @Column(name = "live_reactions_enabled", nullable = false)
    var liveReactionsEnabled: Boolean = true,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "live_polls", nullable = false, columnDefinition = "jsonb")
    var livePolls: List<CourseLivePoll> = emptyList(),

    @Column(name = "required_lesson", nullable = false)
    var required: Boolean = true,

    @Column(name = "minimum_watch_pct")
    var minimumWatchPct: Int? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quiz_questions", nullable = false, columnDefinition = "jsonb")
    var quizQuestions: List<CourseQuizQuestion> = emptyList(),

    @Column(name = "quiz_passing_score", nullable = false)
    var quizPassingScore: Int = 80,

    @Column(name = "duration_min", nullable = false)
    var durationMin: Int = 0,

    @Column(name = "order_no", nullable = false)
    var order: Int = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    var kind: CourseLessonKind
        get() = CourseLessonKind.valueOf(kindRaw)
        set(value) { kindRaw = value.name }

    var liveProvider: CourseLiveProvider?
        get() = liveProviderRaw?.let(CourseLiveProvider::valueOf)
        set(value) { liveProviderRaw = value?.name }

    var liveStatus: CourseLiveStatus?
        get() = liveStatusRaw?.let(CourseLiveStatus::valueOf)
        set(value) { liveStatusRaw = value?.name }

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        createdAt = now
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }
}
